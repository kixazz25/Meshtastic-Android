import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """                                        val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"
                                        webViewRef.value?.evaluateJavascript("setTileUrl('"+localUrl+"')", null)"""

new = """                                        val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"
                                        android.util.Log.i("ConvoyOffline", "Switching to local URL: $localUrl")
                                        webViewRef.value?.evaluateJavascript("setTileUrl('"+localUrl+"')", null)"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("PATCHED OK")
else:
    print("ERROR: anchor not found")
    sys.exit(1)
