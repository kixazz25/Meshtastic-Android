import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

errors = []

# Part 1: Rename zoom slider label
old1 = '                        // ── Zoom slider ──────────────────────────────────\n                        Text("ZOOM  ${mapZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp,\n                            fontFamily = FontFamily.Monospace)'
new1 = '                        // ── Download zoom slider ─────────────────────────\n                        Text("DOWNLOAD ZOOM  ${mapZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp,\n                            fontFamily = FontFamily.Monospace)'

if old1 in content:
    content = content.replace(old1, new1)
    print("Part 1 OK — zoom label renamed")
else:
    errors.append("Part 1: zoom label anchor not found")

# Part 2: Insert fly zoom slider before online/offline toggle
old2 = '                        Spacer(Modifier.height(6.dp))\n                        // ── Online/Offline toggle ────────────────────────'
new2 = '''                        Spacer(Modifier.height(6.dp))
                        // ── Search fly zoom slider ───────────────────────
                        var flyZoomLevel by remember { mutableStateOf(ConvoyConfig.SEARCH_FLY_ZOOM.toFloat()) }
                        Text("FLY ZOOM  ${flyZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(
                            value = flyZoomLevel,
                            onValueChange = { flyZoomLevel = it },
                            onValueChangeFinished = { ConvoyConfig.SEARCH_FLY_ZOOM = flyZoomLevel.toInt() },
                            valueRange = 8f..16f,
                            steps = 7,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        // ── Online/Offline toggle ────────────────────────'''

if old2 in content:
    content = content.replace(old2, new2)
    print("Part 2 OK — fly zoom slider added")
else:
    errors.append("Part 2: online/offline anchor not found")

if errors:
    for e in errors: print("ERROR:", e)
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("ALL PATCHED OK")
