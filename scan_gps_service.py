lines = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyGpsService.kt', 'r', encoding='utf-8').readlines()

# Find key lines
for i, l in enumerate(lines):
    if 'var onLocationUpdate' in l:
        print(f'onLocationUpdate at line {i+1}: {l.rstrip()}')
    if 'lastLat = null' in l and 'lastLon = null' in l.join(lines[i:i+2]):
        print(f'lastLat=null at line {i+1}: {l.rstrip()}')
    if 'lastLat = lat' in l:
        print(f'lastLat=lat at line {i+1}: {l.rstrip()}')
    if 'lastLon = lon' in l:
        print(f'lastLon=lon at line {i+1}: {l.rstrip()}')
