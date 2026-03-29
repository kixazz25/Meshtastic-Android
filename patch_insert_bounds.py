kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Find }, "Android") line which closes the JavascriptInterface
target = None
for i, line in enumerate(lines):
    if '}, "Android")' in line:
        target = i
        break

if target is None:
    print('ERROR: could not find }, "Android")')
else:
    print(f'Inserting before line {target+1}')
    new_code = '''                            @android.webkit.JavascriptInterface
                            fun onMapBoundsReady(north: Double, south: Double, east: Double, west: Double) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    val wv = webViewRef.value ?: return@post
                                    val tilesDir = java.io.File(context.filesDir, "tiles/SAT/18")
                                    Thread {
                                        val bounds = mutableListOf<String>()
                                        if (tilesDir.exists()) {
                                            val z = 18
                                            val n = 1 shl z
                                            val xMin = ((west + 180.0) / 360.0 * n).toLong() - 1
                                            val xMax = ((east + 180.0) / 360.0 * n).toLong() + 1
                                            val yMin = ((1.0 - Math.log(Math.tan(Math.toRadians(north)) + 1.0 / Math.cos(Math.toRadians(north))) / Math.PI) / 2.0 * n).toLong() - 1
                                            val yMax = ((1.0 - Math.log(Math.tan(Math.toRadians(south)) + 1.0 / Math.cos(Math.toRadians(south))) / Math.PI) / 2.0 * n).toLong() + 1
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
'''
    lines.insert(target, new_code)
    open(kt_path, 'w', encoding='utf-8').writelines(lines)
    print('Fixed OK')
