kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Find the SHOW DOWNLOADED button that still uses getMapBounds
# It's at line 898 (0-indexed: 897)
start = None
for i, line in enumerate(lines):
    if 'getMapBounds()' in line and 'evaluateJavascript' in line:
        # Walk back to find the clickable start
        for j in range(i, max(i-10, 0), -1):
            if 'fillMaxWidth().clickable' in lines[j]:
                start = j
                break
        if start:
            break

if start is None:
    print('ERROR: button not found')
else:
    # Find end of clickable lambda - the },
    end = None
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count('{') - lines[i].count('}')
        if i > start and depth <= 0 and '},' in lines[i]:
            end = i
            break

    print(f'Replacing lines {start+1} to {end+1}')
    indent = '                            '
    new_lines = [
        f'{indent}modifier = Modifier.fillMaxWidth().clickable {{\n',
        f'{indent}    if (showDownloaded) {{\n',
        f'{indent}        showDownloaded = false\n',
        f'{indent}        webViewRef.value?.evaluateJavascript("clearDownloadedAreas()", null)\n',
        f'{indent}    }} else {{\n',
        f'{indent}        val wv = webViewRef.value ?: return@clickable\n',
        f'{indent}        val tilesDir = java.io.File(context.filesDir, "tiles/SAT/18")\n',
        f'{indent}        Thread {{\n',
        f'{indent}            val bounds = mutableListOf<String>()\n',
        f'{indent}            if (tilesDir.exists()) {{\n',
        f'{indent}                val z = 18; val n = 1 shl z\n',
        f'{indent}                tilesDir.listFiles()?.forEach {{ xDir: java.io.File ->\n',
        f'{indent}                    val x = xDir.name.toLongOrNull() ?: return@forEach\n',
        f'{indent}                    xDir.listFiles()?.forEach {{ yFile: java.io.File ->\n',
        f'{indent}                        val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach\n',
        f'{indent}                        val tN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n))))\n',
        f'{indent}                        val tS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / n))))\n',
        f'{indent}                        val tW = x.toDouble() / n * 360.0 - 180.0\n',
        f'{indent}                        val tE = (x + 1).toDouble() / n * 360.0 - 180.0\n',
        f'{indent}                        bounds.add("{{\\"n\\":$tN,\\"s\\":$tS,\\"e\\":$tE,\\"w\\":$tW}}")\n',
        f'{indent}                    }}\n',
        f'{indent}                }}\n',
        f'{indent}            }}\n',
        f'{indent}            val json = "[" + bounds.joinToString(",") + "]"\n',
        f'{indent}            android.os.Handler(android.os.Looper.getMainLooper()).post {{\n',
        f'{indent}                wv.evaluateJavascript("showDownloadedAreas($json)", null)\n',
        f'{indent}                showDownloaded = true\n',
        f'{indent}            }}\n',
        f'{indent}        }}.start()\n',
        f'{indent}    }}\n',
        f'{indent}}},\n',
    ]
    lines[start:end+1] = new_lines
    open(kt_path, 'w', encoding='utf-8').writelines(lines)
    print('Fixed OK')
