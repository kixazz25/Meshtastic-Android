package com.geeksville.mesh.convoy

import android.content.Context

/**
 * ConvoyMarkerRenderer
 *
 * Marker rendering is handled via WebView + Leaflet.js (convoy_map.html).
 * This class is retained only as the bridge for node tap callbacks from
 * JavaScript back into the ViewModel.
 */
class ConvoyMarkerRenderer(
    private val context: Context,
    val onNodeTapped: (ConvoyNode) -> Unit = {}
)

/**
 * Lightweight lat/lon point — keeps ConvoyEngine free of map library dependency.
 */
data class LatLngPoint(val latitude: Double, val longitude: Double)

/**
 * A colored polyline segment for track rendering via Leaflet.
 */
data class TrackSegment(
    val points: List<LatLngPoint>,
    val color: String = "#000000"
)
