package com.geeksville.mesh.convoy

object ConvoyConfig {
    // Three-state display: 0=OFF, 1=ON, 2=SELECTED
    @Volatile var trailDisplayState: Int = 1  // ON by default
    @Volatile var trackDisplayState: Int = 0
    @Volatile var waypointDisplayState: Int = 0
    @Volatile var routeDisplayState: Int = 0
    @Volatile var trailChecked: Set<String>? = null
    @Volatile var trackChecked: Set<String>? = null
    @Volatile var waypointChecked: Set<String>? = null
    @Volatile var routeChecked: Set<String>? = null

    const val MAP_DEFAULT_ZOOM = 18.0
    // TILE_SOURCES — reads from MapSourceManager (map_sources.json)
    // No hardcoded URLs. Single source of truth.
    val TILE_SOURCES: Map<String, String>
        get() = MapSourceManager.getSlotSources()
            .associate { (key, _, url) -> key to url }
            .plus("SAT_LOCAL" to LOCAL_TILE_BASE + "SAT/{z}/{x}/{y}.png")
    var ACTIVE_TILE_SOURCE: String
        get() = MapSourceManager.activeSourceKey
        set(value) { MapSourceManager.setActive(value) }
    // ESRI_LABELS_URL / ESRI_TRANSPORT_URL removed.
    // Overlay URLs now come from MapSourceManager.getOverlayLayers().
    const val LOCAL_TILE_BASE = "convoy://tiles/"

    // Shared tile storage — package-independent, survives app reinstall/rename
    // /sdcard/Documents/GroupTrack/maps/tiles/{source}/{z}/{x}/{y}.png
    val TILE_DIR: java.io.File
        get() {
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                ), "GroupTrack/maps/tiles"
            )
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    // One-time migration: MOVE old package-specific tiles to shared location.
    // File.renameTo() is instant — filesystem pointer change, not a copy.
    fun migrateTiles(context: android.content.Context) {
        val prefs = context.getSharedPreferences("grouptrack", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("tiles_migrated", false)) return

        val oldDir = java.io.File(context.getExternalFilesDir(null), "tiles")
        val newDir = TILE_DIR

        if (oldDir.exists() && oldDir.isDirectory) {
            // Move each source directory (SAT, HYB, TOPO, TOPO+)
            val sources = oldDir.listFiles() ?: emptyArray()
            var moved = 0
            for (sourceDir in sources) {
                if (!sourceDir.isDirectory) continue
                val dest = java.io.File(newDir, sourceDir.name)
                if (dest.exists()) {
                    // Destination already has this source — skip, don't overwrite
                    android.util.Log.d("TileMigrate", "SKIP ${sourceDir.name} — already exists at destination")
                    continue
                }
                val ok = sourceDir.renameTo(dest)
                if (ok) {
                    moved++
                    android.util.Log.d("TileMigrate", "MOVED ${sourceDir.name} to shared storage")
                } else {
                    android.util.Log.e("TileMigrate", "FAILED to move ${sourceDir.name}")
                }
            }
            // Clean up empty old directory
            if ((oldDir.listFiles() ?: emptyArray()).isEmpty()) {
                oldDir.delete()
                android.util.Log.d("TileMigrate", "Deleted empty old tiles directory")
            }
            android.util.Log.d("TileMigrate", "Migration complete: $moved source(s) moved")
        } else {
            android.util.Log.d("TileMigrate", "No old tiles directory found — fresh install")
        }

        prefs.edit().putBoolean("tiles_migrated", true).apply()
    } 
    const val MAP_GROUP_ZOOM_PADDING = 1.4f
    const val MAP_CART_ZOOM = 18.0
    const val MAP_MIN_ZOOM = 16.0
    /** Show downloaded tile overlay on map open — OFF bypasses z18 scan */
    var SHOW_DOWNLOADED_ON_OPEN = false
    const val BLINK_LOST_MS = 3000L
    const val BLINK_DROP_MS = 150L
    const val MARKER_SIZE_LARGE_DP = 24
    const val MARKER_SIZE_MEDIUM_DP = 16
    const val TICK_MS = 5000L
    var SIGNAL_DROP_MINUTES = 2f
    var LOST_MINUTES = 10f
    var OFF_TRACK_MILES = 0.028f  // 150 feet
    const val API_BASE_URL = "https://grouptrack.org/convoy_api.php/"  // V3.0 backend
    const val V3_FEATURES_ENABLED = false
    const val PAYWALL_ENABLED = false              // Flip to true when growth warrants paywall                  // Flip to true to expose V3 to testers
    const val IS_STANDALONE_BUILD = true  // true = beta APK, false = Google Play build
    var TRACK_EXPORT_FORMAT = "GPX"  // GPX = default (Garmin/Strava/AllTrails), KML = Google Earth/route donation
    var DOWNLOAD_ZOOM = 18
    var SEARCH_FLY_ZOOM = 10
    const val DOWNLOAD_ZOOM_MIN = 10
    var TRACK_MULTICOLOR = true
}
