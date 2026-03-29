import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)"""

new = """                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("PATCHED OK")
else:
    print("ERROR: anchor not found")
    sys.exit(1)
