kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Find FIND AREA button clickable - it's the one with getMapBounds() wrongly
start = None
for i, line in enumerate(lines):
    if 'FIND AREA' in line:
        # Walk back to find clickable
        for j in range(i, max(i-10, 0), -1):
            if 'fillMaxWidth().clickable' in lines[j]:
                start = j
                break
        break

if start is None:
    print('ERROR: FIND AREA button not found')
else:
    # Find end of clickable lambda
    end = None
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count('{') - lines[i].count('}')
        if i > start and depth <= 0 and '},' in lines[i]:
            end = i
            break

    print(f'Replacing FIND AREA clickable lines {start+1} to {end+1}')
    indent = '                            '
    new_lines = [
        f'{indent}modifier = Modifier.fillMaxWidth().clickable {{\n',
        f'{indent}    if (locationSearchQuery.isBlank()) return@clickable\n',
        f'{indent}    locationSearchError = ""\n',
        f'{indent}    locationSearchResults = emptyList()\n',
        f'{indent}    Thread {{\n',
        f'{indent}        try {{\n',
        f'{indent}            val gc = android.location.Geocoder(ctx, java.util.Locale.getDefault())\n',
        f'{indent}            val results = gc.getFromLocationName(locationSearchQuery, 5)\n',
        f'{indent}            android.os.Handler(android.os.Looper.getMainLooper()).post {{\n',
        f'{indent}                if (results.isNullOrEmpty()) {{\n',
        f'{indent}                    locationSearchError = "No results — try adding state (e.g. Zion UT)"\n',
        f'{indent}                }} else {{\n',
        f'{indent}                    locationSearchResults = results\n',
        f'{indent}                }}\n',
        f'{indent}            }}\n',
        f'{indent}        }} catch (e: Exception) {{\n',
        f'{indent}            android.os.Handler(android.os.Looper.getMainLooper()).post {{\n',
        f'{indent}                locationSearchError = "Search failed: ${{e.message}}"\n',
        f'{indent}            }}\n',
        f'{indent}        }}\n',
        f'{indent}    }}.start()\n',
        f'{indent}}},\n'
    ]
    lines[start:end+1] = new_lines
    open(kt_path, 'w', encoding='utf-8').writelines(lines)
    print('Fixed OK')
