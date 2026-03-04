package com.geeksville.mesh.convoy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * ConvoyMarkerRenderer
 *
 * IMP-001 · Task 4.4 + 4.5 · feature/convoy-tab
 *
 * Manages all OSMDroid overlays for the convoy map view:
 *   - Node markers  (shape + color data-driven from ConvoyNode)
 *   - Lead track polyline with per-segment coloring (REQ-109)
 *
 * Call [attach] once after the MapView is ready.
 * Call [update] on every ViewModel tick (5 s).
 */
class ConvoyMarkerRenderer(private val context: Context) {

    private var mapView: MapView? = null
    private val nodeMarkers = mutableListOf<Marker>()
    private val trackPolylines = mutableListOf<Polyline>()
    private var trackVisible = true

    // ── Attach ────────────────────────────────────────────────────────────

    fun attach(map: MapView) {
        mapView = map
    }

    // ── Update ────────────────────────────────────────────────────────────

    /**
     * Refresh all overlays with latest data. Called on each 5-second tick.
     */
    fun update(nodes: List<ConvoyNode>, trackSegments: List<TrackSegment>) {
        val map = mapView ?: return

        // Remove previous overlays
        map.overlays.removeAll(nodeMarkers)
        map.overlays.removeAll(trackPolylines)
        nodeMarkers.clear()
        trackPolylines.clear()

        // ── Lead track polylines (REQ-109 per-segment coloring) ───────────
        if (trackVisible) {
            for (segment in trackSegments) {
                if (segment.points.size < 2) continue
                val polyline = Polyline().apply {
                    setPoints(segment.points.map { GeoPoint(it.latitude, it.longitude) })
                    outlinePaint.apply {
                        color = parseColor(segment.color)
                        strokeWidth = 8f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        style = Paint.Style.STROKE
                    }
                    isEnabled = false // suppress tap info window
                }
                trackPolylines.add(polyline)
            }
            map.overlays.addAll(trackPolylines)
        }

        // ── Node markers ──────────────────────────────────────────────────
        for (node in nodes) {
            val marker = Marker(map).apply {
                position = GeoPoint(node.latitude, node.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = node.callsign
                snippet = buildSnippet(node)
                icon = buildMarkerDrawable(context, node.markerColor, node.markerSymbol, node.markerSize)
            }
            nodeMarkers.add(marker)
        }
        map.overlays.addAll(nodeMarkers)
        map.invalidate()
    }

    // ── REQ-110: lead track visibility toggle ─────────────────────────────

    fun setLeadTrackVisible(visible: Boolean) {
        val map = mapView ?: return
        trackVisible = visible
        if (visible) map.overlays.addAll(trackPolylines)
        else map.overlays.removeAll(trackPolylines)
        map.invalidate()
    }

    // ── Marker icon builder ───────────────────────────────────────────────

    /**
     * Draws a bitmap icon for a node.
     * markerSymbol values from ConvoyNode:
     *   "triangle"         → LEAD-1   (triangle pointing up)
     *   "triangle-stroked" → SIERRA-20 (triangle pointing down)
     *   "star"             → HOTEL-10  (5-point star)
     *   "circle"           → all others
     *
     * markerSize values: "large" | "medium"
     */
    private fun buildMarkerDrawable(
        context: Context,
        colorHex: String,
        symbol: String,
        size: String
    ): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = when (size) {
            "large" -> (48 * density).toInt()
            else    -> (36 * density).toInt()
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(colorHex)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.08f
        }

        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r  = sizePx * 0.42f

        when (symbol) {
            "triangle" -> {
                // Triangle up — LEAD
                val path = Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx + r, cy + r)
                    lineTo(cx - r, cy + r)
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }
            "triangle-stroked" -> {
                // Triangle down — TAIL
                val path = Path().apply {
                    moveTo(cx, cy + r)
                    lineTo(cx + r, cy - r)
                    lineTo(cx - r, cy - r)
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }
            "star" -> {
                // 5-point star — operator cart HOTEL-10
                val path = buildStarPath(cx, cy, r, r * 0.45f, 5)
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }
            else -> {
                // Circle — all other convoy nodes
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, strokePaint)
            }
        }

        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    }

    private fun buildStarPath(
        cx: Float, cy: Float,
        outerR: Float, innerR: Float,
        points: Int
    ): Path {
        val path = Path()
        val step = Math.PI / points
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = i * step - Math.PI / 2
            val x = cx + (r * Math.cos(angle)).toFloat()
            val y = cy + (r * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun buildSnippet(node: ConvoyNode) = buildString {
        append("Pos #${node.convoyPosition} · ${node.status.name}")
        if (node.speed_mph > 0f) append(" · %.0f mph".format(node.speed_mph))
        if (node.battery_pct in 1..99) append(" · ${node.battery_pct}%")
    }

    private fun parseColor(hex: String) = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        Color.GRAY
    }
}

/**
 * One colored segment of the lead vehicle track (REQ-109).
 */
data class TrackSegment(
    val points: List<LatLngPoint>,
    val color: String
)

/**
 * Lightweight lat/lon point — keeps ConvoyEngine free of OSMDroid dependency.
 */
data class LatLngPoint(
    val latitude: Double,
    val longitude: Double
)
