import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Find and show the exact line for debugging
for i, line in enumerate(content.splitlines(), 1):
    if "setTileUrl" in line and "localUrl" in line:
        print(f"Line {i}: {repr(line)}")

# Replace single-quote version with double-quote version
old = """            val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"
            webViewRef.value?.evaluateJavascript("setTileUrl('$localUrl')", null)"""

new = """            val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"
            webViewRef.value?.evaluateJavascript("setTileUrl(\"$localUrl\")", null)"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("FIXED")
else:
    print("ERROR: anchor not found — check line output above")
    sys.exit(1)
