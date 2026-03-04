package com.geeksville.mesh.convoy

import android.graphics.Color
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.expressions.Expression.*
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.*
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * ConvoyMarkerRenderer
 *
 * IMP-001 · Task 4.4 + 4.5 · feature/convoy-tab
 *
 * Manages all MapLibre layers for the convoy view:
 *   - Node circle layer  (color + size data-driven from ConvoyNode)
 *   - Node symbol layer  (icon driven by ConvoyNode.markerSymbol)
 *   - Lead track polyline with per-segment coloring (REQ-109)
 *
 * Call [attachToStyle] once after map style loads.
 * Call [update] on every ViewModel tick (5 s) with the latest node list
 * and track segment colors from ConvoyEngine.computeLeadTrackColors().
 */
class ConvoyMarkerRenderer {

    // ── Source / Layer IDs ────────────────────────────────────────────────
    companion object {
        const val SOURCE_NODES        = "convoy-nodes-source"
        const val SOURCE_TRACK        = "convoy-track-source"
        const val LAYER_CIRCLES       = "convoy-circles-layer"
        const val LAYER_SYMBOLS       = "convoy-symbols-layer"
        const val LAYER_TRACK         = "convoy-track-layer"

        // Symbol names — must match drawables registered in MapLibre style
        const val SYMBOL_TRIANGLE_UP  = "convoy-triangle-up"
        const val SYMBOL_TRIANGLE_DOWN = "convoy-triangle-down"
        const val SYMBOL_STAR         = "convoy-star"
        const val SYMBOL_CIRCLE       = "convoy-circle"

        // Feature property keys written into GeoJSON
        private const val PROP_COLOR   = "markerColor"
        private const val PROP_SIZE    = "markerSize"
        private const val PROP_SYMBOL  = "markerSymbol"
        private const val PROP_NODE_ID = "nodeId"
        private const val PROP_LOST    = "isLost"
    }

    private var mapStyle: Style? = null

    // ── Attach ────────────────────────────────────────────────────────────

    /**
     * Call once inside map.getStyle { style -> renderer.attachToStyle(style) }
     */
    fun attachToStyle(style: Style) {
        mapStyle = style

        // ── Node source (empty until first update) ──────────────────────
        style.addSource(GeoJsonSource(SOURCE_NODES, emptyFeatureCollection()))

        // ── Track source ─────────────────────────────────────────────────
        style.addSource(GeoJsonSource(SOURCE_TRACK, emptyFeatureCollection()))

        // ── Track layer (below node circles) ─────────────────────────────
        style.addLayer(
            LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                lineWidth(3f),
                lineColor(get(PROP_COLOR)),   // per-segment color from feature property
                lineCap("round"),
                lineJoin("round")
            )
        )

        // ── Circle layer ──────────────────────────────────────────────────
        style.addLayer(
            CircleLayer(LAYER_CIRCLES, SOURCE_NODES).withProperties(
                circleColor(get(PROP_COLOR)),
                circleRadius(
                    // data-driven radius: markerSize property mapped to dp values
                    interpolate(
                        linear(),
                        get(PROP_SIZE),
                        literal(1), literal(8f),   // small
                        literal(2), literal(11f),  // medium
                        literal(3), literal(14f)   // large (lead/tail/operator)
                    )
                ),
                circleStrokeWidth(2f),
                circleStrokeColor(
                    // LOST nodes get a white stroke to stand out
                    switchCase(
                        toBoolean(get(PROP_LOST)), literal("#FFFFFF"),
                        literal("#00000066")
                    )
                )
            )
        )

        // ── Symbol layer (icons on top of circles) ────────────────────────
        style.addLayer(
            SymbolLayer(LAYER_SYMBOLS, SOURCE_NODES).withProperties(
                iconImage(get(PROP_SYMBOL)),
                iconSize(
                    interpolate(
                        linear(),
                        get(PROP_SIZE),
                        literal(1), literal(0.5f),
                        literal(2), literal(0.7f),
                        literal(3), literal(0.9f)
                    )
                ),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
            )
        )
    }

    // ── Update ────────────────────────────────────────────────────────────

    /**
     * Refresh all layers with latest data.
     *
     * @param nodes       Full list of ConvoyNode from ConvoyViewModel
     * @param trackSegments  List of (latLng pairs + color) from
     *                       ConvoyEngine.computeLeadTrackColors()
     *                       Each segment: Pair<List<LatLngPoint>, String (hex color)>
     */
    fun update(
        nodes: List<ConvoyNode>,
        trackSegments: List<TrackSegment>
    ) {
        val style = mapStyle ?: return

        // ── Rebuild node GeoJSON ──────────────────────────────────────────
        val nodeFeatures = JSONArray()
        for (node in nodes) {
            val lat = node.latitude  ?: continue
            val lon = node.longitude ?: continue

            val props = JSONObject().apply {
                put(PROP_NODE_ID, node.nodeId)
                put(PROP_COLOR,   node.markerColor)
                put(PROP_SIZE,    node.markerSize)
                put(PROP_SYMBOL,  node.markerSymbol)
                put(PROP_LOST,    node.isLost)
            }

            val geometry = JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().apply {
                    put(lon)
                    put(lat)
                })
            }

            nodeFeatures.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", props)
                put("geometry", geometry)
            })
        }

        val nodeGeoJson = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", nodeFeatures)
        }.toString()

        (style.getSource(SOURCE_NODES) as? GeoJsonSource)
            ?.setGeoJson(nodeGeoJson)

        // ── Rebuild track GeoJSON (per-segment LineStrings) ───────────────
        val trackFeatures = JSONArray()
        for (segment in trackSegments) {
            if (segment.points.size < 2) continue

            val coords = JSONArray()
            for (pt in segment.points) {
                coords.put(JSONArray().apply {
                    put(pt.longitude)
                    put(pt.latitude)
                })
            }

            val props = JSONObject().apply {
                put(PROP_COLOR, segment.color)
            }

            val geometry = JSONObject().apply {
                put("type", "LineString")
                put("coordinates", coords)
            }

            trackFeatures.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", props)
                put("geometry", geometry)
            })
        }

        val trackGeoJson = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", trackFeatures)
        }.toString()

        (style.getSource(SOURCE_TRACK) as? GeoJsonSource)
            ?.setGeoJson(trackGeoJson)
    }

    /**
     * Show or hide the lead track polyline layer (REQ-110 toggle).
     */
    fun setLeadTrackVisible(visible: Boolean) {
        mapStyle?.getLayer(LAYER_TRACK)?.setProperties(
            visibility(if (visible) "visible" else "none")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun emptyFeatureCollection(): String =
        """{"type":"FeatureCollection","features":[]}"""
}

/**
 * One colored segment of the lead vehicle track (REQ-109).
 *
 * @param points  Ordered list of lat/lon points forming this segment
 * @param color   Hex color string (e.g. "#FF4444") — the color of the
 *                last convoy cart to pass through this segment
 */
data class TrackSegment(
    val points: List<LatLngPoint>,
    val color: String
)

/**
 * Lightweight lat/lon value type used by ConvoyMarkerRenderer.
 * Avoids a hard dependency on MapLibre's LatLng in the engine layer.
 */
data class LatLngPoint(
    val latitude: Double,
    val longitude: Double
)
