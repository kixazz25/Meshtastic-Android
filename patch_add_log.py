import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = "                            val estimate = ConvoyTileCalculator.quickEstimate(north, south, east, west)"

new = """                            android.util.Log.i("ConvoyDownload", "onAreaSelected N=$north S=$south E=$east W=$west zoom=${ConvoyConfig.DOWNLOAD_ZOOM_MIN}-${ConvoyConfig.DOWNLOAD_ZOOM}")
                            val estimate = ConvoyTileCalculator.quickEstimate(north, south, east, west)
                            android.util.Log.i("ConvoyDownload", "estimate tiles=${estimate.tileCount} mb=${estimate.estimatedMB}")"""

# Replace both occurrences
count = content.count(old)
if count == 0:
    print("ERROR: anchor not found")
    sys.exit(1)

content = content.replace(old, new)
with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print(f"PATCHED OK — {count} occurrence(s) replaced")
