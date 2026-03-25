#!/usr/bin/env python3
"""Replace splash screen content with grouptrack_splash.png image — no text overlays."""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find: if (showSplash) {
start = None
for i, line in enumerate(lines):
    if "if (showSplash) {" in line:
        start = i
        break

if start is None:
    print("FAIL: if (showSplash) not found")
    sys.exit(1)

print(f"Found splash block at line {start + 1}")

# Find the closing brace of the splash Box — count braces
depth = 0
end = None
for i in range(start, start + 100):
    depth += lines[i].count("{") - lines[i].count("}")
    if depth == 0 and i > start:
        end = i
        break

if end is None:
    print("FAIL: closing brace not found")
    sys.exit(1)

print(f"Splash block ends at line {end + 1}")

# Replace the entire splash block with image-only version
new_splash = [
    "        if (showSplash) {\n",
    "            Box(\n",
    "                modifier = Modifier.fillMaxSize(),\n",
    "                contentAlignment = Alignment.Center\n",
    "            ) {\n",
    "                androidx.compose.foundation.Image(\n",
    "                    painter = androidx.compose.ui.res.painterResource(\n",
    "                        id = com.geeksville.mesh.R.drawable.grouptrack_splash\n",
    "                    ),\n",
    "                    contentDescription = \"GroupTrack\",\n",
    "                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,\n",
    "                    modifier = Modifier.fillMaxSize()\n",
    "                )\n",
    "            }\n",
    "        }\n",
]

lines[start:end + 1] = new_splash
print(f"OK   replaced {end - start + 1} lines with image-only splash")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
print("Run: ./gradlew assembleGoogleDebug")
