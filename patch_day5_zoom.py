# Day 5 - Fix 1: Update zoom levels in ConvoyConfig
path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyConfig.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """    const val MAP_DEFAULT_ZOOM = 16.0
    const val MAP_GROUP_ZOOM_PADDING = 1.4f
    const val MAP_CART_ZOOM = 17.0"""

new = """    const val MAP_DEFAULT_ZOOM = 18.0
    const val MAP_GROUP_ZOOM_PADDING = 1.4f
    const val MAP_CART_ZOOM = 18.0
    const val MAP_MIN_ZOOM = 16.0
    // TODO: Settings slider MAP_DEFAULT_ZOOM range 16-20
    // TODO: Settings slider MAP_CART_ZOOM range 16-20"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("Updated zoom levels")
else:
    print("ERROR: not found")

# Day 5 - Fix 2: Open map to MY CART GPS location on launch
screen_path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(screen_path, "r", encoding="utf-8") as f:
    sc = f.read()

old2 = """            setTileSource(TileSourceFactory.USGS_SAT)
            setMultiTouchControls(true)
            minZoomLevel = 2.0
            maxZoomLevel = 20.0
            controller.setZoom(ConvoyConfig.MAP_DEFAULT_ZOOM)
            controller.setCenter(GeoPoint(37.4691, -113.6215)) // New Harmony UT"""

new2 = """            setTileSource(TileSourceFactory.USGS_SAT)
            setMultiTouchControls(true)
            minZoomLevel = ConvoyConfig.MAP_MIN_ZOOM
            maxZoomLevel = 20.0
            controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)
            // Center on device GPS location if available, else New Harmony UT fallback
            val locProvider = org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(context)
            val lastKnown = locProvider.lastKnownLocation
            val startCenter = if (lastKnown != null)
                GeoPoint(lastKnown.latitude, lastKnown.longitude)
            else
                GeoPoint(37.4691, -113.6215)
            controller.setCenter(startCenter)"""

if old2 in sc:
    sc = sc.replace(old2, new2)
    with open(screen_path, "w", encoding="utf-8") as f:
        f.write(sc)
    print("Map opens to GPS location")
else:
    print("ERROR: map init marker not found")

# Day 5 - Fix 3: Increase OSMDroid tile cache
old3 = """            setTileSource(TileSourceFactory.USGS_SAT)"""
# Add cache config before tile source
new3 = """            // Increase tile cache for offline use
            org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheMaxBytes = 1024L * 1024 * 1024 // 1GB
            org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheTrimBytes = 900L * 1024 * 1024  // trim to 900MB
            setTileSource(TileSourceFactory.USGS_SAT)"""

if old3 in sc:
    sc = sc.replace(old3, new3, 1)
    with open(screen_path, "w", encoding="utf-8") as f:
        f.write(sc)
    print("Increased tile cache to 1GB")
else:
    print("ERROR: cache marker not found")
