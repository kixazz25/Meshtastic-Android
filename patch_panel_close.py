kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
kt = open(kt_path, encoding='utf-8').read()

old = '''                            if (showMapSettings) {
                            }
                            showMapSettings = !showMapSettings'''

new = '''                            if (showMapSettings) {
                                webViewRef.value?.evaluateJavascript("clearSearchCenter()", null)
                                webViewRef.value?.evaluateJavascript("clearAreaBoundary()", null)
                            }
                            showMapSettings = !showMapSettings'''

if old in kt:
    kt = kt.replace(old, new)
    open(kt_path, 'w', encoding='utf-8').write(kt)
    print('Fixed OK')
else:
    print('ERROR: pattern not found')
