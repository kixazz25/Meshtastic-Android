kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
html_path = 'app/src/main/assets/convoy_map.html'

# ── Patch 1: Add getMapBounds() to convoy_map.html ──
html = open(html_path, encoding='utf-8').read()

old_html = '''    function clearDownloadedAreas() {
      if (downloadedAreaLayer) { map.removeLayer(downloadedAreaLayer); downloadedAreaLayer = null; }
    }'''

new_html = '''    function clearDownloadedAreas() {
      if (downloadedAreaLayer) { map.removeLayer(downloadedAreaLayer); downloadedAreaLayer = null; }
    }
    function getMapBounds() {
      var b = map.getBounds();
      Android.onMapBoundsReady(b.getNorth(), b.getSouth(), b.getEast(), b.getWest());
    }'''

if old_html in html:
    html = html.replace(old_html, new_html)
    open(html_path, 'w', encoding='utf-8').write(html)
    print('convoy_map.html patched OK')
else:
    print('ERROR: convoy_map.html pattern not found')

# ── Patch 2: Update showDownloadedAreas() — no borders so tiles merge visually ──
html = open(html_path, encoding='utf-8').read()

old_rect = '''        L.rectangle([[b.s, b.w], [b.n, b.e]], {
          color: '#4DA6FF', weight: 1, fillColor: '#4DA6FF',
          fillOpacity: 0.18, dashArray: null
        }).addTo(downloadedAreaLayer);'''

new_rect = '''        L.rectangle([[b.s, b.w], [b.n, b.e]], {
          color: 'transparent', weight: 0, fillColor: '#4DA6FF',
          fillOpacity: 0.22
        }).addTo(downloadedAreaLayer);'''

if old_rect in html:
    html = html.replace(old_rect, new_rect)
    open(html_path, 'w', encoding='utf-8').write(html)
    print('convoy_map.html rect style patched OK')
else:
    print('ERROR: rect style pattern not found')

# ── Patch 3: Add onMapBoundsReady to JavascriptInterface ──
kt = open(kt_path, encoding='utf-8').read()

old_js_interface = '''                            @android.webkit.JavascriptInterface
                            fun onAreaSelected(north: Double, south: Double, east: Double, west: Double) {'''

new_js_interface = '''                            @android.webkit.JavascriptInterface
                            fun onMapBoundsReady(north: Double, south: Double, east: Double, west: Double) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    val wv = webViewRef.value ?: return@post
                                    val tilesDir = java.io.File(context.filesDir, "tiles/SAT/18")
                                    Thread {
                                        val bounds = mutableListOf<String>()
                                        if (tilesDir.exists()) {
                                            val z = 18
                                            val n = 1 shl z
                                            // Convert viewport bounds to tile x/y range
                                            val xMin = ((west + 180.0) / 360.0 * n).toLong()
                                            val xMax = ((east + 180.0) / 360.0 * n).toLong()
                                            val yMin = ((1.0 - Math.log(Math.tan(Math.toRadians(north)) + 1.0 / Math.cos(Math.toRadians(north))) / Math.PI) / 2.0 * n).toLong()
                                            val yMax = ((1.0 - Math.log(Math.tan(Math.toRadians(south)) + 1.0 / Math.cos(Math.toRadians(south))) / Math.PI) / 2.0 * n).toLong()
                                            tilesDir.listFiles()?.forEach { xDir: java.io.File ->
                                                val x = xDir.name.toLongOrNull() ?: return@forEach
                                                if (x < xMin || x > xMax) return@forEach
                                                xDir.listFiles()?.forEach { yFile: java.io.File ->
                                                    val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                                    if (y < yMin || y > yMax) return@forEach
                                                    val tileN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n))))
                                                    val tileS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / n))))
                                                    val tileW = x.toDouble() / n * 360.0 - 180.0
                                                    val tileE = (x + 1).toDouble() / n * 360.0 - 180.0
                                                    bounds.add("{\\"n\\":$tileN,\\"s\\":$tileS,\\"e\\":$tileE,\\"w\\":$tileW}")
                                                }
                                            }
                                        }
                                        val json = "[${bounds.joinToString(",")}]"
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            wv.evaluateJavascript("showDownloadedAreas($json)", null)
                                        }
                                    }.start()
                                }
                            }
                            @android.webkit.JavascriptInterface
                            fun onAreaSelected(north: Double, south: Double, east: Double, west: Double) {'''

if old_js_interface in kt:
    kt = kt.replace(old_js_interface, new_js_interface)
    print('JavascriptInterface patched OK')
else:
    print('ERROR: JavascriptInterface pattern not found')

# ── Patch 4: Replace SHOW DOWNLOADED button click to call getMapBounds() ──
old_btn = '''                            modifier = Modifier.fillMaxWidth().clickable {
                                val tilesDir = java.io.File(context.filesDir, "tiles/SAT/18")
                                val bounds = mutableListOf<String>()
                                if (tilesDir.exists()) {
                                    val z = 18
                                    tilesDir.listFiles()?.forEach { xDir: java.io.File ->
                                        val x = xDir.name.toLongOrNull() ?: return@forEach
                                        xDir.listFiles()?.forEach { yFile: java.io.File ->
                                            val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                            val tileN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / (1 shl z)))))
                                            val tileS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / (1 shl z)))))
                                            val tileW = x.toDouble() / (1 shl z) * 360.0 - 180.0
                                            val tileE = (x + 1).toDouble() / (1 shl z) * 360.0 - 180.0
                                            bounds.add("{\\"n\\":$tileN,\\"s\\":$tileS,\\"e\\":$tileE,\\"w\\":$tileW}")
                                        }
                                    }
                                }
                                val json = "[${bounds.joinToString(",")}]"
                                webViewRef.value?.evaluateJavascript("showDownloadedAreas($json)", null)
                            },'''

new_btn = '''                            modifier = Modifier.fillMaxWidth().clickable {
                                webViewRef.value?.evaluateJavascript("getMapBounds()", null)
                            },'''

if old_btn in kt:
    kt = kt.replace(old_btn, new_btn)
    open(kt_path, 'w', encoding='utf-8').write(kt)
    print('Button patched OK')
else:
    print('ERROR: button pattern not found')
