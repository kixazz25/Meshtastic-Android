kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
kt = open(kt_path, encoding='utf-8').read()

count = 0

# Fix SAT/18 to SAT/14 in button scan
old1 = 'val tilesDir = java.io.File(context.filesDir, "tiles/SAT/18")\n                        Thread {'
new1 = 'val tilesDir = java.io.File(context.filesDir, "tiles/SAT/14")\n                        Thread {'
if old1 in kt:
    kt = kt.replace(old1, new1)
    count += 1
    print('Button tilesDir patched OK')
else:
    print('WARNING: button tilesDir pattern not found')

# Fix z=18 to z=14 in button scan
old_z1 = 'val z = 18; val n = 1 shl z'
new_z1 = 'val z = 14; val n = 1 shl z'
if old_z1 in kt:
    kt = kt.replace(old_z1, new_z1)
    count += 1
    print('Button z value patched OK')
else:
    print('WARNING: button z value pattern not found')

if count > 0:
    open(kt_path, 'w', encoding='utf-8').write(kt)
    print(f'Done — {count} replacements made')
else:
    print('ERROR: no patterns found — checking what exists')
    idx = kt.find('SAT/18')
    if idx >= 0:
        print(f'SAT/18 found at char {idx}:')
        print(repr(kt[max(0,idx-100):idx+200]))
    else:
        print('SAT/18 not found at all')
