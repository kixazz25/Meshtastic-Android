lines = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyGpsService.kt', 'r', encoding='utf-8').readlines()

# Insert after line 61 (0-indexed 61) - after onLocationUpdate declaration
lines.insert(62, '    var totalDistanceMiles: Double = 0.0\n')
lines.insert(63, '        private set\n')
lines.insert(64, '\n')
lines.insert(65, '    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {\n')
lines.insert(66, '        val R = 3958.8\n')
lines.insert(67, '        val dLat = Math.toRadians(lat2 - lat1)\n')
lines.insert(68, '        val dLon = Math.toRadians(lon2 - lon1)\n')
lines.insert(69, '        val a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2)\n')
lines.insert(70, '        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))\n')
lines.insert(71, '    }\n')
lines.insert(72, '\n')

# Lines shifted by 12 now
# lastLat = null was at line 171 (0-indexed 170), now at 182
# Insert totalDistanceMiles reset after lastLon = null (line 172, now 183)
for i, l in enumerate(lines):
    if 'lastLat = null' in l:
        print(f'lastLat=null now at line {i+1}')
        # Insert totalDistanceMiles = 0.0 after lastLon = null (next line)
        lines.insert(i+2, '        totalDistanceMiles = 0.0\n')
        break

# lastLat = lat was at 278, now shifted - find it
for i, l in enumerate(lines):
    if 'lastLat = lat' in l:
        print(f'lastLat=lat now at line {i+1}')
        # Insert distance accumulation after lastLon = lon (i+1)
        lines.insert(i+2, '        if (prevLat != null && prevLon != null) {\n')
        lines.insert(i+3, '            totalDistanceMiles += haversineMiles(prevLat, prevLon, lat, lon)\n')
        lines.insert(i+4, '        }\n')
        break

open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyGpsService.kt', 'w', encoding='utf-8').writelines(lines)
print('Done')
