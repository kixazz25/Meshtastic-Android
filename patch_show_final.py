kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Find the SHOW DOWNLOADED button clickable block
start = None
for i, line in enumerate(lines):
    if 'SHOW DOWNLOADED' in line or 'HIDE DOWNLOADED' in line:
        for j in range(i, max(i-15, 0), -1):
            if 'fillMaxWidth().clickable' in lines[j]:
                start = j
                break
        break

if start is None:
    print('ERROR: button not found')
else:
    # Find end of clickable lambda
    end = None
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count('{') - lines[i].count('}')
        if i > start and depth <= 0 and '},' in lines[i]:
            end = i
            break

    print(f'Replacing lines {start+1} to {end+1}')
    indent = '                            '
    ind2  = '                                '
    ind3  = '                                    '
    ind4  = '                                        '
    ind5  = '                                            '
    ind6  = '                                                '
    new_lines = [
        f'{indent}modifier = Modifier.fillMaxWidth().clickable {{\n',
        f'{ind2}if (showDownloaded) {{\n',
        f'{ind3}showDownloaded = false\n',
        f'{ind3}webViewRef.value?.evaluateJavascript("clearDownloadedAreas()", null)\n',
        f'{ind2}}} else {{\n',
        f'{ind3}val wv = webViewRef.value ?: return@clickable\n',
        f'{ind3}val tilesDir = java.io.File(context.filesDir, "tiles/SAT/18")\n',
        f'{ind3}Thread {{\n',
        f'{ind4}val bounds = mutableListOf<String>()\n',
        f'{ind4}if (tilesDir.exists()) {{\n',
        f'{ind5}val z = 18\n',
        f'{ind5}val n = 1 shl z\n',
        f'{ind5}tilesDir.listFiles()?.forEach {{ xDir: java.io.File ->\n',
        f'{ind6}val x = xDir.name.toLongOrNull() ?: return@forEach\n',
        f'{ind6}xDir.listFiles()?.forEach {{ yFile: java.io.File ->\n',
        f'{ind6}    val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach\n',
        f'{ind6}    val tileN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n))))\n',
        f'{ind6}    val tileS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / n))))\n',
        f'{ind6}    val tileW = x.toDouble() / n * 360.0 - 180.0\n',
        f'{ind6}    val tileE = (x + 1).toDouble() / n * 360.0 - 180.0\n',
        f'{ind6}    bounds.add("{{\\"n\\":$tileN,\\"s\\":$tileS,\\"e\\":$tileE,\\"w\\":$tileW}}")\n',
        f'{ind6}}}\n',
        f'{ind5}}}\n',
        f'{ind4}}}\n',
        f'{ind4}val json = "[${"{bounds.joinToString(\\",\\")}"}]"\n',
        f'{ind4}android.os.Handler(android.os.Looper.getMainLooper()).post {{\n',
        f'{ind5}wv.evaluateJavascript("showDownloadedAreas($json)", null)\n',
        f'{ind5}showDownloaded = true\n',
        f'{ind4}}}\n',
        f'{ind3}}}.start()\n',
        f'{ind2}}}\n',
        f'{indent}}},\n',
    ]
    lines[start:end+1] = new_lines
    open(kt_path, 'w', encoding='utf-8').writelines(lines)
    print('Fixed OK')
