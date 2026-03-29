kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
kt = open(kt_path, encoding='utf-8').read()

old = 'val gc = android.location.Geocoder(ctx, java.util.Locale.getDefault())'
new = 'val gc = android.location.Geocoder(context, java.util.Locale.getDefault())'

if old in kt:
    kt = kt.replace(old, new)
    open(kt_path, 'w', encoding='utf-8').write(kt)
    print('Fixed OK')
else:
    print('ERROR: pattern not found')
