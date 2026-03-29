kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
html_path = 'app/src/main/assets/convoy_map.html'

# ── Patch 1: Remove clearDownloadedAreas() from DOWNLOAD REGION button ──
kt = open(kt_path, encoding='utf-8').read()

old_clear = '''                                webViewRef.value?.evaluateJavascript("clearDownloadedAreas()", null)
                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)'''

new_clear = '''                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)'''

if old_clear in kt:
    kt = kt.replace(old_clear, new_clear)
    print('DOWNLOAD REGION clear removed OK')
else:
    print('ERROR: DOWNLOAD REGION clear pattern not found')

# ── Patch 2: Add showDownloaded state variable after isOfflineMode ──
old_state = 'var isOfflineMode by remember { mutableStateOf(false) }'
new_state = '''var isOfflineMode by remember { mutableStateOf(false) }
    var showDownloaded by remember { mutableStateOf(false) }'''

if old_state in kt:
    kt = kt.replace(old_state, new_state)
    print('showDownloaded state added OK')
else:
    print('ERROR: isOfflineMode state not found')

# ── Patch 3: Replace SHOW DOWNLOADED button with toggle behavior ──
old_btn = '''                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                webViewRef.value?.evaluateJavascript("getMapBounds()", null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E2E40)
                        ) {
                            Text("⬜  SHOW DOWNLOADED", color = Color(0xFF4DA6FF), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }'''

new_btn = '''                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (showDownloaded) {
                                    showDownloaded = false
                                    webViewRef.value?.evaluateJavascript("clearDownloadedAreas()", null)
                                } else {
                                    showDownloaded = true
                                    webViewRef.value?.evaluateJavascript("getMapBounds()", null)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (showDownloaded) Color(0xFF1A3A2A) else Color(0xFF1E2E40)
                        ) {
                            Text(
                                if (showDownloaded) "✅  HIDE DOWNLOADED" else "⬜  SHOW DOWNLOADED",
                                color = if (showDownloaded) Color(0xFF4AE09A) else Color(0xFF4DA6FF),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }'''

if old_btn in kt:
    kt = kt.replace(old_btn, new_btn)
    open(kt_path, 'w', encoding='utf-8').write(kt)
    print('Toggle button patched OK')
else:
    print('ERROR: SHOW DOWNLOADED button pattern not found')

# ── Patch 4: Restore showSearchCenter and crosshair in convoy_map.html ──
html = open(html_path, encoding='utf-8').read()

old_setview = '    function setView(lat, lon, zoom) { map.setView([lat, lon], zoom); }'

new_setview = '''    var searchCenterMarker = null;
    var crosshairLines = [];
    function showSearchCenter(lat, lng) {
      if (searchCenterMarker) map.removeLayer(searchCenterMarker);
      searchCenterMarker = L.circleMarker([lat, lng], {
        radius: 6, color: '#FF6B35', fillColor: '#FF6B35', fillOpacity: 1, weight: 2
      }).addTo(map);
      crosshairLines.forEach(function(l) { map.removeLayer(l); });
      crosshairLines = [];
      var latLine = L.polyline([[lat, -180], [lat, 180]], {color: '#4DA6FF', weight: 1.5, opacity: 0.7, dashArray: '6,4'}).addTo(map);
      var lngLine = L.polyline([[-90, lng], [90, lng]], {color: '#4DA6FF', weight: 1.5, opacity: 0.7, dashArray: '6,4'}).addTo(map);
      crosshairLines.push(latLine, lngLine);
    }
    function clearSearchCenter() {
      if (searchCenterMarker) { map.removeLayer(searchCenterMarker); searchCenterMarker = null; }
      crosshairLines.forEach(function(l) { map.removeLayer(l); });
      crosshairLines = [];
    }
    function setView(lat, lon, zoom) { map.setView([lat, lon], zoom); }'''

if 'function showSearchCenter' not in html:
    if old_setview in html:
        html = html.replace(old_setview, new_setview)
        open(html_path, 'w', encoding='utf-8').write(html)
        print('convoy_map.html center marker restored OK')
    else:
        print('ERROR: setView pattern not found in html')
else:
    print('showSearchCenter already present — skipping')
