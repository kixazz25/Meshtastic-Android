lines = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt', 'r', encoding='utf-8').readlines()

# Insert _distanceMiles StateFlow after line 373 (0-indexed 372)
print(f'Line 373: {lines[372].rstrip()}')
lines.insert(373, '    private val _distanceMiles = MutableStateFlow(0.0)\n')
lines.insert(374, '    val distanceMiles: StateFlow<Double> = _distanceMiles.asStateFlow()\n')
lines.insert(375, '\n')

# lastGpsLon = lon was at line 395, now at 398 - insert _distanceMiles update after it
for i, l in enumerate(lines):
    if 'lastGpsLon = lon' in l:
        print(f'lastGpsLon=lon now at line {i+1}')
        lines.insert(i+1, '                    _distanceMiles.value = svc.totalDistanceMiles\n')
        break

# Find stopRecording and add reset - find _routeRecording.value = false after lastGpsLon = null
for i, l in enumerate(lines):
    if 'fun stopRecording' in l:
        print(f'stopRecording at line {i+1}')
        # Find _routeRecording.value = false within next 10 lines
        for j in range(i, i+10):
            if '_routeRecording.value = false' in lines[j]:
                lines.insert(j, '        _distanceMiles.value = 0.0\n')
                print(f'Inserted reset at line {j+1}')
                break
        break

open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt', 'w', encoding='utf-8').writelines(lines)
print('Done')
