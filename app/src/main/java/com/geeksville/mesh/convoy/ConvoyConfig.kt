package com.geeksville.mesh.convoy

object ConvoyConfig {
    const val MAP_DEFAULT_ZOOM = 18.0
    var TILE_SOURCES = mutableMapOf(
        "SAT" to "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "HYB" to "https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
        "TOPO" to "https://services.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}",
        "TOPO+" to "https://server.arcgisonline.com/ArcGIS/rest/services/USA_Topo_Maps/MapServer/tile/{z}/{y}/{x}",
        "SAT_LOCAL" to "convoy://tiles/SAT/{z}/{x}/{y}.png"
    )
    var ACTIVE_TILE_SOURCE = "HYB"
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
    const val PAYWALL_ENABLED = true              // Flip to true when growth warrants paywall                  // Flip to true to expose V3 to testers
    const val IS_STANDALONE_BUILD = true  // true = beta APK, false = Google Play build
    var TRACK_EXPORT_FORMAT = "GPX"  // GPX = default (Garmin/Strava/AllTrails), KML = Google Earth/route donation
    var DOWNLOAD_ZOOM = 18
    var SEARCH_FLY_ZOOM = 10
    const val DOWNLOAD_ZOOM_MIN = 10
    var TRACK_MULTICOLOR = true
}
