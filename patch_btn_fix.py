kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
kt = open(kt_path, encoding='utf-8').read()

# Find and replace everything between the Surface clickable and the shape line
old = '''                                val json = "[${bounds.joinToString(",")}]"
                                webViewRef.value?.evaluateJavascript("showDownloadedAreas($json)", null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E2E40)'''

new = '''                                webViewRef.value?.evaluateJavascript("getMapBounds()", null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E2E40)'''

if old in kt:
    # Also need to remove the tile scanning code above this
    # Find the start of the clickable lambda for SHOW DOWNLOADED
    marker = '                                webViewRef.value?.evaluateJavascript("getMapBounds()", null)'
    if marker not in kt:
        kt = kt.replace(old, new)
        # Now remove the dead tile scanning code before the json line
        import re
        kt = re.sub(
            r'                                val tilesDir = java\.io\.File\(context\.filesDir, "tiles/SAT/18"\).*?                                val json = "\[.*?\]"\n',
            '',
            kt,
            flags=re.DOTALL
        )
        open(kt_path, 'w', encoding='utf-8').write(kt)
        print('Fixed OK')
    else:
        print('Already patched')
else:
    print('ERROR: pattern not found')
    idx = kt.find('showDownloadedAreas($json)')
    print('showDownloadedAreas found at:', idx)
