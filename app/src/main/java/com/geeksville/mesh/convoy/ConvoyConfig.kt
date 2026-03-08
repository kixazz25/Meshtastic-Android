package com.geeksville.mesh.convoy

object ConvoyConfig {
    const val MAP_DEFAULT_ZOOM = 18.0
    var TILE_SOURCES = mutableMapOf(
        "SAT" to "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "TOPO" to "https://tile.opentopomap.org/{z}/{x}/{y}.png",
        "RD" to "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    )
    var ACTIVE_TILE_SOURCE = "SAT" 
    const val MAP_GROUP_ZOOM_PADDING = 1.4f
    const val MAP_CART_ZOOM = 18.0
    const val MAP_MIN_ZOOM = 16.0
    const val BLINK_LOST_MS = 3000L
    const val BLINK_DROP_MS = 150L
    const val MARKER_SIZE_LARGE_DP = 24
    const val MARKER_SIZE_MEDIUM_DP = 16
    const val TICK_MS = 5000L
    var SIGNAL_DROP_MINUTES = 2f
    var LOST_MINUTES = 10f
    var OFF_TRACK_MILES = 0.5f
    var DOWNLOAD_ZOOM = 18
}
