kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Step 1: Remove the floating onMapBoundsReady block (outside any interface)
# Find it by looking for the @JavascriptInterface annotation that's outside the block
start_remove = None
end_remove = None
for i, line in enumerate(lines):
    if '@android.webkit.JavascriptInterface' in line and i > 0:
        # Check if previous non-empty line is a closing brace (floating)
        prev = lines[i-1].strip()
        if prev == '}':
            start_remove = i - 1  # include the stray closing brace? No, keep it
            start_remove = i      # just remove from the annotation
            # Find end of this function
            depth = 0
            for j in range(i+1, len(lines)):
                depth += lines[j].count('{') - lines[j].count('}')
                if depth < 0 or (depth == 0 and lines[j].strip() == '}'):
                    end_remove = j
                    break
            break

if start_remove and end_remove:
    print(f'Removing floating block lines {start_remove+1} to {end_remove+1}')
    del lines[start_remove:end_remove+1]
    print('Floating block removed')
else:
    print('WARNING: floating block not found cleanly, searching differently')
    # Find onMapBoundsReady and remove its entire function
    start_remove = None
    for i, line in enumerate(lines):
        if 'fun onMapBoundsReady' in line:
            # Walk back to find @JavascriptInterface
            for j in range(i, max(i-3,0), -1):
                if '@android.webkit.JavascriptInterface' in lines[j]:
                    start_remove = j
                    break
            if start_remove:
                # Find end - matching braces
                depth = 0
                end_remove = None
                for j in range(start_remove, len(lines)):
                    depth += lines[j].count('{') - lines[j].count('}')
                    if j > start_remove and depth <= 0:
                        end_remove = j
                        break
                if end_remove:
                    print(f'Removing lines {start_remove+1} to {end_remove+1}')
                    del lines[start_remove:end_remove+1]
                    print('Removed OK')
            break

# Step 2: Insert onMapBoundsReady before }, "Android")
target = None
for i, line in enumerate(lines):
    if '}, "Android")' in line:
        target = i
        break

if target is None:
    print('ERROR: could not find }, "Android")')
else:
    print(f'Inserting onMapBoundsReady before line {target+1}')
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
