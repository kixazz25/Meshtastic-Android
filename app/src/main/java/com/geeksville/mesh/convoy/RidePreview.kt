package com.geeksville.mesh.convoy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream

/**
 * RIDEPREVIEW-2026-09-24 — the ride's map picture (Fred 09-24).
 *
 * ADD A RIDE on the planner FITS the planner's own map to the route, waits for the tiles, and captures
 * a centred SQUARE of it -- then opens ride creation. No second map, no separate map state. The ride
 * screen shows the picture in a square box; on Save it is kept beside the ride as rides/<rideId>.jpg.
 * The route of a saved ride never changes (only date, start time and description are editable), so
 * the picture never needs recapturing.
 *
 * Never blocks ADD A RIDE: any failure (no map, no geometry, a blank capture) just continues, and the
 * ride screen falls back to the drawn route line.
 */
object RidePreview {
    private const val TAG = "RidePreview"
    private const val SETTLE_MS = 2200L      // time for the fitted tiles to arrive (RIDEFIT-2026-09-24)
    private const val SIZE_PX = 720          // saved picture is 720 x 720
    private const val EDGE_CSS_PX = 16       // breathing room around the route inside the square

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** The picture captured at ADD A RIDE, before the ride exists (keyed by route). */
    fun pendingFile(context: Context, routeId: String) = File(context.cacheDir, "ride_preview_" + safe(routeId) + ".jpg")

    /** The picture kept with a saved ride. */
    fun rideFile(context: Context, rideId: String) = File(GroupTrackStorage.dir("rides", context), safe(rideId) + ".jpg")

    /**
     * Fit [webView]'s map to the route, wait, capture the centred square, then run [then].
     * [a] and [b] are ADD A RIDE's two arguments; whichever one has stored geometry is the route id.
     */
    fun captureThen(webView: WebView?, a: String, b: String, then: () -> Unit) {
        val wv = webView ?: run { then(); return }
        val routeId = listOf(a, b).firstOrNull { ConvoyRideStore.routeGeometry(it) != null }
            ?: run { Log.w(TAG, "no geometry for either argument"); then(); return }
        val pts = ConvoyRideStore.routeGeometry(routeId)?.let { ConvoyRideStore.parseWktLine(it) } ?: emptyList()
        if (pts.isEmpty() || wv.width <= 0 || wv.height <= 0) { then(); return }
        // parseWktLine gives (LON, LAT). Only the extremes matter for a fit.
        val minLat = pts.minOf { it.second }; val maxLat = pts.maxOf { it.second }
        val minLon = pts.minOf { it.first }; val maxLon = pts.maxOf { it.first }
        // Fit the route INSIDE the centred square that will be captured, not the whole map.
        val density = wv.resources.displayMetrics.density
        val side = minOf(wv.width, wv.height)
        val padX = ((wv.width - side) / 2 / density).toInt() + EDGE_CSS_PX
        val padY = ((wv.height - side) / 2 / density).toInt() + EDGE_CSS_PX
        // RIDEFIT-2026-09-24: call the PAGE's own function -- the page keeps its Leaflet map private, so a
        // direct map.fitBounds from injected script did nothing and the picture showed the old view.
        // The script reports back, so a failure is in the log, never silent.
        val js = "(function(){try{" +
            "if(typeof fitRouteSquare==='function'){fitRouteSquare($minLat,$minLon,$maxLat,$maxLon,$padX,$padY);return 'fitted';}" +
            "fitBounds([$minLat,$maxLat],[$minLon,$maxLon]);return 'fitted-plain';" +
            "}catch(e){return 'ERR '+e;}})()"
        wv.evaluateJavascript(js) { r -> Log.i(TAG, "RIDEFIT-2026-09-24: fit -> $r") }
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                capture(wv, pendingFile(wv.context, routeId))
            } catch (e: Exception) {
                Log.w(TAG, "capture failed: ${e.message}")
            }
            then()
        }, SETTLE_MS)
    }

    private fun capture(wv: WebView, out: File) {
        val w = wv.width; val h = wv.height
        if (w <= 0 || h <= 0) return
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        wv.draw(Canvas(full))
        val side = minOf(w, h)
        val square = Bitmap.createBitmap(full, (w - side) / 2, (h - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE_PX, SIZE_PX, true)
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        Log.i(TAG, "RIDEPREVIEW-2026-09-24: captured ${out.name} (${out.length()} bytes)")
    }

    /** The picture to show on the ride screen: the ride's own, else the pending one for the route. */
    fun load(context: Context, routeId: String, rideId: String? = null): Bitmap? {
        // rideId is nullable BY DESIGN (CODE RULE 1): a ride being created has no id yet.
        val f = listOfNotNull(rideId?.let { rideFile(context, it) }, pendingFile(context, routeId))
            .firstOrNull { it.isFile && it.length() > 0 } ?: return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }
    }

    /** On Save: keep the route's pending picture as the ride's own. */
    fun adopt(context: Context, routeId: String, rideId: String) {
        val src = pendingFile(context, routeId)
        if (!src.isFile) return
        try {
            src.copyTo(rideFile(context, rideId), overwrite = true)
            Log.i(TAG, "RIDEPREVIEW-2026-09-24: kept as ${rideFile(context, rideId).name}")
        } catch (e: Exception) {
            Log.w(TAG, "adopt failed: ${e.message}")
        }
    }
}
