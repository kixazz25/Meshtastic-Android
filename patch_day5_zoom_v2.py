# Fix ConvoyConfig zoom values
config_path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyConfig.kt"
with open(config_path, "r", encoding="utf-8") as f:
    content = f.read()

content = content.replace("    const val MAP_DEFAULT_ZOOM = 16.0", "    const val MAP_DEFAULT_ZOOM = 18.0")
content = content.replace("    const val MAP_CART_ZOOM = 17.0", "    const val MAP_CART_ZOOM = 18.0\n    const val MAP_MIN_ZOOM = 16.0")

with open(config_path, "w", encoding="utf-8") as f:
    f.write(content)
print("Updated ConvoyConfig zoom levels")

# Fix ConvoyScreen - open to GPS location
screen_path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(screen_path, "r", encoding="utf-8") as f:
    sc = f.read()

old = """            controller.setZoom(ConvoyConfig.MAP_DEFAULT_ZOOM)
            // Center on New Harmony UT (simulation default)
            controller.setCenter(GeoPoint(37.4691, -113.6215))"""

new = """            controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)
            // Center on device GPS location if available, else New Harmony UT fallback
            val locProvider = org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(context)
            val lastKnown = locProvider.lastKnownLocation
            val startCenter = if (lastKnown != null)
                GeoPoint(lastKnown.latitude, lastKnown.longitude)
            else
                GeoPoint(37.4691, -113.6215)
            controller.setCenter(startCenter)"""

if old in sc:
    sc = sc.replace(old, new)
    with open(screen_path, "w", encoding="utf-8") as f:
        f.write(sc)
    print("Map opens to GPS location")
else:
    print("ERROR: screen marker not found")
    idx = sc.find("setZoom(ConvoyConfig.MAP_DEFAULT_ZOOM)")
    print(repr(sc[idx-20:idx+120]))
