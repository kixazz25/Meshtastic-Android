import sys

content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt', 'r', encoding='utf-8').read()
lines = content.split('\n')

# Replace lines 358-456 (0-indexed: 357-455) with new GPS service delegation
# Line 358: // ── Route recorder ────
# Line 456: } (closing brace of writeKmlPoint)
# Line 457: blank
# Line 458: // ── Tick loop

start = 357  # 0-indexed line 358
end = 457    # 0-indexed line 458 (exclusive - keep tick loop)

print(f'Replacing lines {start+1} to {end} with GPS service delegation')
print(f'First line being replaced: {lines[start]}')
print(f'First line being kept after replacement: {lines[end]}')

new_section = [
    '    // ── Route recorder -- delegated to ConvoyGpsService ────────────────────',
    '    private var gpsServiceConn: android.content.ServiceConnection? = null',
    '    private var gpsService: ConvoyGpsService? = null',
    '    private var pendingTempFile: java.io.File? = null',
    '    private var lastGpsLat: Double? = null',
    '    private var lastGpsLon: Double? = null',
    '',
    '    private fun bindGpsService(context: android.content.Context, onBound: (ConvoyGpsService) -> Unit) {',
    '        val conn = object : android.content.ServiceConnection {',
    '            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {',
    '                val svc = (service as ConvoyGpsService.LocalBinder).getService()',
    '                gpsService = svc',
    '                svc.onLocationUpdate = { lat, lon, _ ->',
    '                    val prevLat = lastGpsLat',
    '                    val prevLon = lastGpsLon',
    '                    if (prevLat != null && prevLon != null) {',
    '                        val seg = ConvoyEngine.LeadTrackSegment(',
    '                            startLat = prevLat, startLon = prevLon,',
    '                            endLat = lat, endLon = lon, color = "#2E75B6"',
    '                        )',
    '                        _gpsTrailSegments.value = _gpsTrailSegments.value + seg',
    '                    }',
    '                    lastGpsLat = lat',
    '                    lastGpsLon = lon',
    '                }',
    '                onBound(svc)',
    '            }',
    '            override fun onServiceDisconnected(name: android.content.ComponentName) {',
    '                gpsService = null',
    '            }',
    '        }',
    '        gpsServiceConn = conn',
    '        val intent = android.content.Intent(context, ConvoyGpsService::class.java)',
    '        context.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE)',
    '    }',
    '',
    '    fun startRecording(context: android.content.Context) {',
    '        ConvoyGpsService.start(context)',
    '        bindGpsService(context) { svc ->',
    '            svc.startTrack()',
    '            _routeRecording.value = true',
    '        }',
    '    }',
    '',
    '    fun pauseRecording() {',
    '        gpsService?.pauseTrack()',
    '        _routeRecording.value = false',
    '    }',
    '',
    '    fun resumeRecording(context: android.content.Context) {',
    '        if (gpsService == null) {',
    '            bindGpsService(context) { svc ->',
    '                svc.resumeTrack()',
    '                _routeRecording.value = true',
    '            }',
    '        } else {',
    '            gpsService?.resumeTrack()',
    '            _routeRecording.value = true',
    '        }',
    '    }',
    '',
    '    fun stopRecording() {',
    '        pendingTempFile = gpsService?.stopTrack()',
    '        lastGpsLat = null',
    '        lastGpsLon = null',
    '        _routeRecording.value = false',
    '    }',
    '',
    '    fun finalizeTrack(name: String, context: android.content.Context) {',
    '        val temp = pendingTempFile ?: return',
    '        gpsService?.finalizeTrack(temp, name)',
    '        pendingTempFile = null',
    '        gpsServiceConn?.let { context.unbindService(it) }',
    '        gpsServiceConn = null',
    '        gpsService = null',
    '    }',
    '',
]

new_lines = lines[:start] + new_section + lines[end:]
result = '\n'.join(new_lines)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt', 'w', encoding='utf-8').write(result)
print(f'Done. New file has {len(new_lines)} lines.')
