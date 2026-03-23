import re

# Fix ConvoyGpsService - add distance accumulation
content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyGpsService.kt', 'r', encoding='utf-8').read()

old = '''    var onLocationUpdate: ((lat: Double, lon: Double, alt: Double) -> Unit)? = null'''
new = '''    var onLocationUpdate: ((lat: Double, lon: Double, alt: Double) -> Unit)? = null
    var totalDistanceMiles: Double = 0.0
        private set

    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 3958.8 // Earth radius in miles
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }'''

print('Found onLocationUpdate:', old in content)
result = content.replace(old, new)

# Reset distance on stopTrack
old2 = '''        val file = currentTempFile
        currentTempFile = null
        lastLat = null
        lastLon = null
        state = State.IDLE'''
new2 = '''        val file = currentTempFile
        currentTempFile = null
        lastLat = null
        lastLon = null
        totalDistanceMiles = 0.0
        state = State.IDLE'''

print('Found stopTrack reset:', old2 in result)
result = result.replace(old2, new2)

# Accumulate distance in writeKmlPoint
old3 = '''        val prevLat = lastLat
        val prevLon = lastLon
        lastLat = lat
        lastLon = lon
        // Trail update is handled via onLocationUpdate callback to ViewModel'''
new3 = '''        val prevLat = lastLat
        val prevLon = lastLon
        lastLat = lat
        lastLon = lon
        if (prevLat != null && prevLon != null) {
            totalDistanceMiles += haversineMiles(prevLat, prevLon, lat, lon)
        }
        // Trail update is handled via onLocationUpdate callback to ViewModel'''

print('Found writeKmlPoint:', old3 in result)
result = result.replace(old3, new3)

open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyGpsService.kt', 'w', encoding='utf-8').write(result)
print('Done ConvoyGpsService')

# Fix ConvoyViewModel - expose distanceMiles StateFlow
content2 = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt', 'r', encoding='utf-8').read()

old4 = '''    private var gpsServiceConn: android.content.ServiceConnection? = null
    private var gpsService: ConvoyGpsService? = null
    private var pendingTempFile: java.io.File? = null
    private var lastGpsLat: Double? = null
    private var lastGpsLon: Double? = null'''
new4 = '''    private var gpsServiceConn: android.content.ServiceConnection? = null
    private var gpsService: ConvoyGpsService? = null
    private var pendingTempFile: java.io.File? = null
    private var lastGpsLat: Double? = null
    private var lastGpsLon: Double? = null
    private val _distanceMiles = MutableStateFlow(0.0)
    val distanceMiles: StateFlow<Double> = _distanceMiles.asStateFlow()'''

print('Found gpsServiceConn:', old4 in content2)
result2 = content2.replace(old4, new4)

# Update distanceMiles on each location update
old5 = '''                svc.onLocationUpdate = { lat, lon, _ ->
                    val prevLat = lastGpsLat
                    val prevLon = lastGpsLon
                    if (prevLat != null && prevLon != null) {
                        val seg = ConvoyEngine.LeadTrackSegment(
                            startLat = prevLat, startLon = prevLon,
                            endLat = lat, endLon = lon, color = "#2E75B6"
                        )
                        _gpsTrailSegments.value = _gpsTrailSegments.value + seg
                    }
                    lastGpsLat = lat
                    lastGpsLon = lon
                }'''
new5 = '''                svc.onLocationUpdate = { lat, lon, _ ->
                    val prevLat = lastGpsLat
                    val prevLon = lastGpsLon
                    if (prevLat != null && prevLon != null) {
                        val seg = ConvoyEngine.LeadTrackSegment(
                            startLat = prevLat, startLon = prevLon,
                            endLat = lat, endLon = lon, color = "#2E75B6"
                        )
                        _gpsTrailSegments.value = _gpsTrailSegments.value + seg
                    }
                    lastGpsLat = lat
                    lastGpsLon = lon
                    _distanceMiles.value = svc.totalDistanceMiles
                }'''

print('Found onLocationUpdate vm:', old5 in result2)
result2 = result2.replace(old5, new5)

# Reset distance on stopRecording
old6 = '''    fun stopRecording() {
        pendingTempFile = gpsService?.stopTrack()
        lastGpsLat = null
        lastGpsLon = null
        _routeRecording.value = false
    }'''
new6 = '''    fun stopRecording() {
        pendingTempFile = gpsService?.stopTrack()
        lastGpsLat = null
        lastGpsLon = null
        _distanceMiles.value = 0.0
        _routeRecording.value = false
    }'''

print('Found stopRecording:', old6 in result2)
result2 = result2.replace(old6, new6)

open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt', 'w', encoding='utf-8').write(result2)
print('Done ConvoyViewModel')
