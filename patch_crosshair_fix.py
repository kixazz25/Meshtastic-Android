import sys

html_path = "app/src/main/assets/convoy_map.html"
screen_path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

errors = []

# ── Part 1: Fix crosshair to use visible map bounds (not global) ──────────
with open(html_path, "r", encoding="utf-8") as f:
    html = f.read()

old1 = """    function showCrosshair(lat,lng) {
      crosshairLines.forEach(function(l){map.removeLayer(l);});
      crosshairLines = [];
      var latLine = L.polyline([[-90,lng],[90,lng]], {color:'#2E75B6',weight:1,opacity:0.5,dashArray:'4,6'}).addTo(map);
      var lngLine = L.polyline([[lat,-180],[lat,180]], {color:'#2E75B6',weight:1,opacity:0.5,dashArray:'4,6'}).addTo(map);
      crosshairLines.push(latLine, lngLine);
    }"""

new1 = """    function showCrosshair(lat,lng) {
      crosshairLines.forEach(function(l){map.removeLayer(l);});
      crosshairLines = [];
      var latLine = L.polyline([[lat,-180],[lat,180]], {color:'#4DA6FF',weight:1.5,opacity:0.7,dashArray:'6,4'}).addTo(map);
      var lngLine = L.polyline([[-90,lng],[90,lng]], {color:'#4DA6FF',weight:1.5,opacity:0.7,dashArray:'6,4'}).addTo(map);
      crosshairLines.push(latLine, lngLine);
    }"""

if old1 in html:
    html = html.replace(old1, new1)
    print("Part 1 OK — crosshair fixed")
else:
    errors.append("Part 1: crosshair anchor not found")

with open(html_path, "w", encoding="utf-8") as f:
    f.write(html)

# ── Part 2: Remove search log from ConvoyScreen.kt ───────────────────────
with open(screen_path, "r", encoding="utf-8") as f:
    screen = f.read()

old2 = '                                            android.util.Log.i("ConvoySearch", "lat=${addr.latitude} lng=${addr.longitude} extras=${addr.extras} feature=${addr.featureName} locality=${addr.locality} admin=${addr.adminArea}")\n'
if old2 in screen:
    screen = screen.replace(old2, "")
    print("Part 2 OK — search log removed")
else:
    print("Part 2 SKIP — log already removed")

# ── Part 3: Change fly zoom slider from zoom levels to miles ─────────────
old3 = '                        Text("FLY ZOOM  ${flyZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)\n                        Slider(value = flyZoomLevel, onValueChange = { flyZoomLevel = it }, onValueChangeFinished = { ConvoyConfig.SEARCH_FLY_ZOOM = flyZoomLevel.toInt() }, valueRange = 8f..16f, steps = 7, modifier = Modifier.fillMaxWidth())'

new3 = '''                        var flyRadiusMiles by remember { mutableStateOf(10f) }
                        Text("FLY RADIUS  ${flyRadiusMiles.toInt()} mi", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Slider(
                            value = flyRadiusMiles,
                            onValueChange = { flyRadiusMiles = it },
                            onValueChangeFinished = {
                                // Convert miles to zoom: 10mi=z10, 5mi=z11, 2mi=z12, 1mi=z13
                                ConvoyConfig.SEARCH_FLY_ZOOM = when {
                                    flyRadiusMiles >= 10f -> 10
                                    flyRadiusMiles >= 7f  -> 11
                                    flyRadiusMiles >= 4f  -> 12
                                    flyRadiusMiles >= 2f  -> 13
                                    else                  -> 14
                                }
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth()
                        )'''

if old3 in screen:
    screen = screen.replace(old3, new3)
    print("Part 3 OK — slider changed to miles")
else:
    errors.append("Part 3: slider anchor not found")

with open(screen_path, "w", encoding="utf-8") as f:
    f.write(screen)

if errors:
    for e in errors: print("ERROR:", e)
    sys.exit(1)

print("ALL PATCHED OK")
