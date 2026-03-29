import math

# ── Patch 1: convoy_map.html — add showDownloadedAreas() and clearDownloadedAreas() ──
html_path = 'app/src/main/assets/convoy_map.html'
html = open(html_path, encoding='utf-8').read()

old_html = '''    function setView(lat, lon, zoom) { map.setView([lat, lon], zoom); }'''

new_html = '''    var downloadedAreaLayer = null;
    function showDownloadedAreas(bounds) {
      if (downloadedAreaLayer) { map.removeLayer(downloadedAreaLayer); }
      downloadedAreaLayer = L.layerGroup();
      for (var i = 0; i < bounds.length; i++) {
        var b = bounds[i];
        L.rectangle([[b.s, b.w], [b.n, b.e]], {
          color: '#4DA6FF', weight: 1, fillColor: '#4DA6FF',
          fillOpacity: 0.18, dashArray: null
        }).addTo(downloadedAreaLayer);
      }
      downloadedAreaLayer.addTo(map);
    }
    function clearDownloadedAreas() {
      if (downloadedAreaLayer) { map.removeLayer(downloadedAreaLayer); downloadedAreaLayer = null; }
    }
    function setView(lat, lon, zoom) { map.setView([lat, lon], zoom); }'''

if old_html in html:
    html = html.replace(old_html, new_html)
    open(html_path, 'w', encoding='utf-8').write(html)
    print('convoy_map.html patched OK')
else:
    print('ERROR: convoy_map.html pattern not found')

# ── Patch 2: ConvoyScreen.kt — add SHOW DOWNLOADED button and tile scanner ──
kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
kt = open(kt_path, encoding='utf-8').read()

# 2a — clear downloaded areas when draw mode activates
old_draw = '                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)'
new_draw = '                                webViewRef.value?.evaluateJavascript("clearDownloadedAreas()", null)\n                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)'

if old_draw in kt:
    kt = kt.replace(old_draw, new_draw)
    print('Draw mode clear patched OK')
else:
    print('ERROR: draw mode pattern not found')

# 2b — add SHOW DOWNLOADED button after DOWNLOAD REGION button
old_btn = '''                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                locationSearchResults = emptyList()
                                webViewRef.value?.evaluateJavascript("clearAreaBoundary()", null)
                                webViewRef.value?.evaluateJavascript("clearSearchCenter()", null)
                                webViewRef.value?.evaluateJavascript("clearDownloadedAreas()", null)
                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2A3545)
                        ) {
                            Text("⬇  DOWNLOAD REGION", color = Color(0xFF7A8DA0), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }'''

new_btn = '''                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                locationSearchResults = emptyList()
                                webViewRef.value?.evaluateJavascript("clearAreaBoundary()", null)
                                webViewRef.value?.evaluateJavascript("clearSearchCenter()", null)
                                webViewRef.value?.evaluateJavascript("clearDownloadedAreas()", null)
                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2A3545)
                        ) {
                            Text("⬇  DOWNLOAD REGION", color = Color(0xFF7A8DA0), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val tilesDir = java.io.File(ctx.filesDir, "tiles/SAT/10")
                                val bounds = mutableListOf<String>()
                                if (tilesDir.exists()) {
                                    tilesDir.listFiles()?.forEach { xDir ->
                                        val x = xDir.name.toLongOrNull() ?: return@forEach
                                        xDir.listFiles()?.forEach { yFile ->
                                            val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                            val z = 10
                                            val n = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / (1 shl z)))))
                                            val s = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / (1 shl z)))))
                                            val w = x.toDouble() / (1 shl z) * 360.0 - 180.0
                                            val e = (x + 1).toDouble() / (1 shl z) * 360.0 - 180.0
                                            bounds.add("{\"n\":$n,\"s\":$s,\"e\":$e,\"w\":$w}")
                                        }
                                    }
                                }
                                val json = "[${bounds.joinToString(",")}]"
                                webViewRef.value?.evaluateJavascript("showDownloadedAreas($json)", null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E2E40)
                        ) {
                            Text("⬜  SHOW DOWNLOADED", color = Color(0xFF4DA6FF), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }'''

if old_btn in kt:
    kt = kt.replace(old_btn, new_btn)
    open(kt_path, 'w', encoding='utf-8').write(kt)
    print('ConvoyScreen.kt button patched OK')
else:
    print('ERROR: button pattern not found in ConvoyScreen.kt')
