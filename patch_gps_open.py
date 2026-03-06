path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """            controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)
            // Center on device GPS location if available, else New Harmony UT fallback
            val locProvider = org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(context)
            val lastKnown = locProvider.lastKnownLocation
            val startCenter = if (lastKnown != null)
                GeoPoint(lastKnown.latitude, lastKnown.longitude)
            else
                GeoPoint(37.4691, -113.6215)
            controller.setCenter(startCenter)"""

new = """            controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)
            // Center on device GPS location using LocationManager
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val lastKnown = try {
                lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) { null }
            val startCenter = if (lastKnown != null)
                GeoPoint(lastKnown.latitude, lastKnown.longitude)
            else
                GeoPoint(37.4691, -113.6215)
            controller.setCenter(startCenter)"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("Fixed GPS open using LocationManager")
else:
    print("ERROR: not found")
