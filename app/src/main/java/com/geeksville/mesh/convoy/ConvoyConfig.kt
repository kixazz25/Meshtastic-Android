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
    // HTMLVER-2026-08-13B: what the bundled map HTML reported when it last loaded.
    // Written by whichever map screen loaded most recently, displayed in
    // settings. "not reported" means no map has been opened yet this session,
    // or the HTML predates the version marker - which is itself the answer.
    @JvmField var MAP_HTML_VERSION: String = "not reported"
    const val LOCAL_TILE_BASE = "convoy://tiles/"

    // Shared tile storage — package-independent, survives app reinstall/rename
    // <tileRoot>/maps/tiles/{source}/{z}/{x}/{y}.png
    //
    // TILEROOT-2026-09-11: \u26d4 tileRoot(), NOT root(). The MBTiles store stays in
    // PUBLIC storage through release 1 while everything else migrates to
    // app-private -- ~17 GB that cannot move quickly, against ~415 MB that can.
    // \u2b50 Release 2 changes this ONE accessor rather than reopening the migration.
    //
    // \u26a0 The line above is the property being traded away if the tiles ever move
    // internal: a rider can back a public map store up to an SD card and restore
    // it to a replacement device. Under Android 11+ an app-private directory is
    // not reachable by any file manager, so none of that is possible there.
    val TILE_DIR: java.io.File
        get() {
            val dir = java.io.File(GroupTrackStorage.tileRoot(), "maps/tiles")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    // LASTPATHS-2026-09-12: ⛔ migrateTiles() DELETED. It moved tiles
    // from getExternalFilesDir()/tiles to TILE_DIR -- app-private OUT to
    // shared storage -- which was correct before MBTiles, when tiles were
    // moved out for persistence. ⚠ Storage is now going the other way, so
    // once tileRoot() resolves internal its source and destination would be
    // the SAME directory: it would rename each source onto itself and then
    // delete the parent if the listing came back empty.
    // ⚠ Its guard was a SharedPreferences flag while the data it guarded
    // lived in shared storage -- a guard and its subject with different
    // lifetimes, which is the shape of the 08-16 reinstall wipe.
    // ⭐ Deleted rather than disabled: dead code that looks live survives
    // because nobody is sure about it.
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
    // ZOOMFLOOR-2026-08-11E: floor lowered from the old value so a rider who
    // zooms out offline still has a basemap. Below the floor NOTHING is
    // downloaded, so the map goes blank -- tracks and trails still draw
    // as vectors, but the imagery is absent and that reads as broken.
    //
    // It costs almost nothing. For a 42-mile corridor the low levels are
    // on the order of 5-8 tiles in total, against 11,163 for the range
    // above them -- a z10 tile already covers 31 km, and each level down
    // roughly halves the count until it bottoms out at one.
    //
    // A corridor is not a corridor at these zooms and that is fine: one
    // z4 tile is ~2,500 km across, so it covers most of the western US.
    // A continental view for a single tile.
    const val DOWNLOAD_ZOOM_MIN = 4
    var TRACK_MULTICOLOR = true
}
