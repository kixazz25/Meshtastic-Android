import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """                            val pending = PendingDownload(
                                tileCount     = estimate.tileCount,
                                sizeMB        = estimate.sizeMB,
                                withinCeiling = estimate.withinCeiling,"""

new = """                            val pending = ConvoyViewModel.PendingDownload(
                                tileCount     = estimate.tileCount,
                                sizeMB        = estimate.estimatedMB,
                                withinCeiling = estimate.withinCeiling,"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("PATCHED OK")
else:
    print("ERROR: anchor not found")
    sys.exit(1)
