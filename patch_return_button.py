path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add RETURN overlay button at top center of map when in NODE mode
old1 = "        // ── CONTACT LOST banner ───────────────────────────────────────────"
new1 = """        // ── NODE mode: RETURN button at top center ─────────────────────────
        if (hudMode == HudMode.NODE) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .clickable { viewModel.dismissNodeHud() },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xCC1E252F),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = "← CONVOY",
                    color = Color(0xFF2E75B6),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }

        // ── CONTACT LOST banner ───────────────────────────────────────────"""

if old1 not in content:
    print("ERROR: marker not found"); exit(1)
content = content.replace(old1, new1)

# 2. Remove RETURN from NodeDetailHud header row - replace with just role text
old2 = """            Text(node.callsign, color = Color(0xFFE8EEF5), fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("[ ${node.role} ]", color = Color(0xFF7A8DA0), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Text("RETURN", color = Color(0xFF2E75B6), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onDismiss() }.padding(horizontal = 8.dp, vertical = 4.dp))"""

new2 = """            Text(node.callsign, color = Color(0xFFE8EEF5), fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("[ ${node.role} ]", color = Color(0xFF7A8DA0), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)"""

if old2 not in content:
    print("ERROR: HUD header marker not found"); exit(1)
content = content.replace(old2, new2)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
print("Patched: RETURN button at top center, removed from HUD header")
