package com.geeksville.mesh.grouptrack

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geeksville.mesh.convoy.MBTilesStore
import com.geeksville.mesh.convoy.VectorTiles

/**
 * VECMAP-2026-10-30 (2.7e step 4.1): the app answers the vector page's requests itself -- nothing goes to the internet.
 *   https://gtvec.local/a/<file>       -> assets/vecmap/<file>  (MapLibre, style, fonts, the page)
 *   https://gtvec.local/t/z/x/y.pbf    -> the VEC store; a tile not held is an EMPTY tile (the map shows background, no error)
 * One host for page, style, fonts and tiles: same origin, so no file:// fetch limits; CORS header added anyway.
 */
object VecMapServer {
    const val HOST = "gtvec.local"
    private const val TAG = "VecMap"
    private val CORS = mapOf("Access-Control-Allow-Origin" to "*")
    private fun ok(mime: String, bytes: ByteArray) =
        WebResourceResponse(mime, null, 200, "OK", CORS, java.io.ByteArrayInputStream(bytes))
    private fun notFound() =
        WebResourceResponse("text/plain", null, 404, "Not Found", CORS, java.io.ByteArrayInputStream(ByteArray(0)))

    /** Null = not ours (the web view handles it). */
    fun serve(context: android.content.Context, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != HOST) return null
        val path = u.path ?: return notFound()
        return try {
            when {
                path.startsWith("/t/") -> {
                    val seg = path.removePrefix("/t/").removeSuffix(".pbf").split("/")
                    val z = seg.getOrNull(0)?.toIntOrNull(); val x = seg.getOrNull(1)?.toIntOrNull(); val y = seg.getOrNull(2)?.toIntOrNull()
                    if (z == null || x == null || y == null) notFound()
                    else {
                        val raw = MBTilesStore.readTile(VectorTiles.SLOT, z, x, y)
                        // VECMAPDIAG-2026-10-30: what the map is actually given
                        android.util.Log.i(TAG, "VECMAPDIAG tile z$z/$x/$y " + (if (raw == null) "NOT HELD" else
                            "held ${raw.size} B head=" + raw.take(8).joinToString("") { "%02x".format(it) }))
                        ok("application/x-protobuf", raw?.let { gunzipIfNeeded(it) } ?: ByteArray(0))
                    }
                }
                path.startsWith("/a/") -> {
                    val rel = path.removePrefix("/a/")
                    if (rel.contains("..")) notFound()
                    else ok(mimeOf(rel), context.assets.open("vecmap/$rel").use { it.readBytes() })
                }
                else -> notFound()
            }
        } catch (e: java.io.FileNotFoundException) {
            // VECMAPGLYPH-2026-10-30: a font range we do not bundle is served EMPTY, not 404 -- MapLibre drops the whole tile
            // when a glyph range fails (13:11: peaks with a triangle blanked whole areas). It then just lacks those glyphs.
            android.util.Log.w(TAG, "VECMAPGLYPH not bundled $path")
            if (path.startsWith("/a/fonts/")) ok("application/x-protobuf", ByteArray(0)) else notFound()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "VECMAP-2026-10-30 $path: ${e.message}")
            notFound()
        }
    }

    /** OpenFreeMap may hand over gzip bytes; the page needs them plain. */
    private fun gunzipIfNeeded(b: ByteArray): ByteArray =
        if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte())
            java.util.zip.GZIPInputStream(b.inputStream()).use { it.readBytes() } else b

    private fun mimeOf(p: String): String = when {
        p.endsWith(".html") -> "text/html"
        p.endsWith(".js") -> "application/javascript"
        p.endsWith(".css") -> "text/css"
        p.endsWith(".json") -> "application/json"
        p.endsWith(".pbf") -> "application/x-protobuf"
        p.endsWith(".png") -> "image/png"   // VECLIB-2026-10-30: the sprite sheet
        else -> "application/octet-stream"
    }
}

/** VECMAP-2026-10-30: the proof -- MapLibre drawing the VEC store offline, in its own web view. Opens on the newest recipe's first box. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GtVectorMapTestScreen(online: Boolean, onDone: () -> Unit) {   // VECMAPONLINE-2026-10-30: online = OpenFreeMap's own style (A/B)
    val start = remember { try { VectorTiles.recipeBoxes()?.second?.firstOrNull() } catch (e: Exception) { null } }
    // VECMAPDIAG-2026-10-30: what the VEC store holds, per zoom, and where z12 is
    remember {
        Thread {
            try {
                val lv = MBTilesStore.zoomLevelsPresent(VectorTiles.SLOT)
                val per = lv.joinToString(" ") { z -> "z$z=" + MBTilesStore.countInTileRange(VectorTiles.SLOT, z, 0L, (1L shl z) - 1, 0L, (1L shl z) - 1) }
                android.util.Log.i("VecMap", "VECMAPDIAG store VEC levels=$lv  $per")
                val e = MBTilesStore.tileExtentAtZoom(VectorTiles.SLOT, 12)
                android.util.Log.i("VecMap", "VECMAPDIAG z12 extent x ${e?.getOrNull(0)}..${e?.getOrNull(1)} y ${e?.getOrNull(2)}..${e?.getOrNull(3)}  start=${start?.joinToString()}")
            } catch (ex: Exception) { android.util.Log.e("VecMap", "VECMAPDIAG store: ${ex.message}") }
        }.start(); true
    }
    // VECMAPGPS-2026-10-30 (Fred 13:04): open where the rider IS -- last known GPS / network / passive fix, zoom 13.
    val gpsCtx = androidx.compose.ui.platform.LocalContext.current
    val here = remember {
        try {
            val lm = gpsCtx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } }
                .maxByOrNull { it.time }
        } catch (e: Exception) { null }
    }
    val hash = when {
        here != null -> "#13/${here.latitude}/${here.longitude}"
        start != null -> "#12/${(start[0] + start[1]) / 2}/${(start[2] + start[3]) / 2}"
        else -> "#5/40/-100"
    }
    remember { android.util.Log.i("VecMap", "VECMAPGPS open at $hash (" + (if (here != null) "last fix ${here.provider}" else "no fix -- recipe box") + ")"); true }
    // VECMAPSIZE-2026-10-30: the Dialog window wraps its content, so the web view started at zero height and MapLibre drew
    // nothing (12:30: three tiles requested, one row). The test map is given the screen's size explicitly.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      androidx.compose.foundation.layout.Box(modifier = Modifier.size(cfg.screenWidthDp.dp, cfg.screenHeightDp.dp)) {
        GtScreenFrame(if (online) "Vector map (test, ONLINE)" else "Vector map (test)", "map.recipes", onDone) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                WebView(ctx).apply {
                    // VECMAPSIZE2-2026-10-30: fill the parent both ways (a view made in code defaults to WRAP_CONTENT)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        android.util.Log.i("VecMap", "VECMAPSIZE2 view ${v.width}x${v.height}")
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            android.util.Log.i("VecMapJS", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                            request?.let { VecMapServer.serve(ctx.applicationContext, it) } ?: super.shouldInterceptRequest(view, request)
                    }
                    loadUrl("https://${VecMapServer.HOST}/a/vecmap_test.html" + (if (online) "?online=1" else "") + hash)
                }
            })
        }
      }   // VECMAPSIZE-2026-10-30
    }
}
