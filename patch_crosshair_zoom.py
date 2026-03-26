import sys

html_path = "app/src/main/assets/convoy_map.html"
config_path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt"
screen_path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

errors = []

# ── Part 1: Add crosshair lines to convoy_map.html ───────────
with open(html_path, "r", encoding="utf-8") as f:
    html = f.read()

old1 = "    function showSearchCenter(lat,lng) {"
new1 = """    var crosshairLines = [];
    function showCrosshair(lat,lng) {
      crosshairLines.forEach(function(l){map.removeLayer(l);});
      crosshairLines = [];
      var latLine = L.polyline([[-90,lng],[90,lng]], {color:'#2E75B6',weight:1,opacity:0.5,dashArray:'4,6'}).addTo(map);
      var lngLine = L.polyline([[lat,-180],[lat,180]], {color:'#2E75B6',weight:1,opacity:0.5,dashArray:'4,6'}).addTo(map);
      crosshairLines.push(latLine, lngLine);
    }
    function clearCrosshair() {
      crosshairLines.forEach(function(l){map.removeLayer(l);});
      crosshairLines = [];
    }
    function showSearchCenter(lat,lng) {"""

if old1 in html:
    html = html.replace(old1, new1)
    print("Part 1 OK — crosshair lines added to HTML")
else:
    errors.append("Part 1: HTML showSearchCenter anchor not found")

# Update showSearchCenter to also draw crosshair
old1b = "      searchCenterMarker = L.marker([lat,lng]).addTo(map).bindPopup(lat.toFixed(5)+\", \"+lng.toFixed(5)).openPopup();\n    }"
new1b = "      searchCenterMarker = L.marker([lat,lng]).addTo(map).bindPopup(lat.toFixed(5)+\", \"+lng.toFixed(5)).openPopup();\n      showCrosshair(lat,lng);\n    }"

if old1b in html:
    html = html.replace(old1b, new1b)
    print("Part 1b OK — crosshair called from showSearchCenter")
else:
    errors.append("Part 1b: showSearchCenter body anchor not found")

# Update clearSearchCenter to also clear crosshair
old1c = "    function clearSearchCenter() {\n      if(searchCenterMarker) { map.removeLayer(searchCenterMarker); searchCenterMarker=null; }\n    }"
new1c = "    function clearSearchCenter() {\n      if(searchCenterMarker) { map.removeLayer(searchCenterMarker); searchCenterMarker=null; }\n      clearCrosshair();\n    }"

if old1c in html:
    html = html.replace(old1c, new1c)
    print("Part 1c OK — crosshair cleared with center marker")
else:
    errors.append("Part 1c: clearSearchCenter anchor not found")

with open(html_path, "w", encoding="utf-8") as f:
    f.write(html)

# ── Part 2: Add SEARCH_FLY_ZOOM to ConvoyConfig ──────────────
with open(config_path, "r", encoding="utf-8") as f:
    config = f.read()

old2 = "    var DOWNLOAD_ZOOM = 18"
new2 = "    var DOWNLOAD_ZOOM = 18\n    var SEARCH_FLY_ZOOM = 10"

if old2 in config:
    config = config.replace(old2, new2)
    with open(config_path, "w", encoding="utf-8") as f:
        f.write(config)
    print("Part 2 OK — SEARCH_FLY_ZOOM added to ConvoyConfig")
else:
    errors.append("Part 2: ConvoyConfig anchor not found")

# ── Part 3: Add fly zoom slider to gear panel + update setView calls ──────
with open(screen_path, "r", encoding="utf-8") as f:
    screen = f.read()

# Add slider after download zoom slider block
old3 = '                        Spacer(Modifier.height(10.dp))\n                        // ── Zoom slider ──────────────────────────────────\n                        Text("ZOOM  ${mapZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp,\n                            fontFamily = FontFamily.Monospace)'
new3 = '                        Spacer(Modifier.height(10.dp))\n                        // ── Download zoom slider ─────────────────────────\n                        Text("DOWNLOAD ZOOM  ${mapZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp,\n                            fontFamily = FontFamily.Monospace)'

if old3 in screen:
    screen = screen.replace(old3, new3)
    print("Part 3a OK — renamed zoom slider label")
else:
    errors.append("Part 3a: zoom slider label anchor not found")

# Add fly zoom slider after the download zoom slider closing brace
old3b = "                            ConvoyConfig.DOWNLOAD_ZOOM = mapZoomLevel.toInt()\n                                        // TODO: invalidate via JS bridge\n                            },"
new3b = "                            ConvoyConfig.DOWNLOAD_ZOOM = mapZoomLevel.toInt()\n                            },"

if old3b in screen:
    screen = screen.replace(old3b, new3b)
    print("Part 3b OK — cleaned TODO comment")
else:
    errors.append("Part 3b: TODO comment anchor not found")

# Add fly zoom slider after download zoom slider
old3c = "                        Spacer(Modifier.height(10.dp))\n                        // ── Online/Offline toggle ────────────────────────"
new3c = """                        Spacer(Modifier.height(6.dp))
                        // ── Search fly zoom slider ───────────────────────
                        var flyZoomLevel by remember { mutableStateOf(ConvoyConfig.SEARCH_FLY_ZOOM.toFloat()) }
                        Text("FLY ZOOM  ${flyZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(
                            value = flyZoomLevel,
                            onValueChange = { flyZoomLevel = it },
                            onValueChangeFinished = { ConvoyConfig.SEARCH_FLY_ZOOM = flyZoomLevel.toInt() },
                            valueRange = 8f..16f,
                            steps = 7,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        // ── Online/Offline toggle ────────────────────────"""

if old3c in screen:
    screen = screen.replace(old3c, new3c)
    print("Part 3c OK — fly zoom slider added")
else:
    errors.append("Part 3c: online/offline anchor not found")

# Update setView calls to use SEARCH_FLY_ZOOM
screen = screen.replace(
    'webViewRef.value?.evaluateJavascript("setView($clat,$clng,10)", null)',
    'webViewRef.value?.evaluateJavascript("setView($clat,$clng,${ConvoyConfig.SEARCH_FLY_ZOOM})", null)'
)
screen = screen.replace(
    'webViewRef.value?.evaluateJavascript("setView(${addr.latitude},${addr.longitude},10)", null)',
    'webViewRef.value?.evaluateJavascript("setView(${addr.latitude},${addr.longitude},${ConvoyConfig.SEARCH_FLY_ZOOM})", null)'
)
print("Part 3d OK — setView calls use SEARCH_FLY_ZOOM")

with open(screen_path, "w", encoding="utf-8") as f:
    f.write(screen)

if errors:
    for e in errors: print("ERROR:", e)
    sys.exit(1)

print("ALL PATCHED OK")
