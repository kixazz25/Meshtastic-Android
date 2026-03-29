kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Step 1: Remove existing onMapBoundsReady function wherever it is
start_remove = None
end_remove = None
for i, line in enumerate(lines):
    if 'fun onMapBoundsReady' in line:
        # Walk back to @JavascriptInterface
        for j in range(i, max(i-3, 0), -1):
            if '@android.webkit.JavascriptInterface' in lines[j]:
                start_remove = j
                break
        if start_remove is not None:
            depth = 0
            for j in range(start_remove, len(lines)):
                depth += lines[j].count('{') - lines[j].count('}')
                if j > start_remove and depth <= 0:
                    end_remove = j
                    break
        break

if start_remove is not None and end_remove is not None:
    print(f'Removing existing onMapBoundsReady lines {start_remove+1} to {end_remove+1}')
    del lines[start_remove:end_remove+1]
    print('Removed OK')
else:
    print('WARNING: onMapBoundsReady not found to remove')

# Step 2: Find the SECOND }, "Android") and insert before it
count = 0
target = None
for i, line in enumerate(lines):
    if '}, "Android")' in line:
        count += 1
        if count == 2:
            target = i
            break

if target is None:
    print('ERROR: second }, "Android") not found')
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
    print('Inserted OK')
    # Verify
    for i, line in enumerate(lines):
        if 'fun onMapBoundsReady' in line:
            print(f'onMapBoundsReady now at line {i+1}')
