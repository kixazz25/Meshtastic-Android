path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Remove floating RETURN overlay from map
old1 = """        // ── NODE mode: RETURN button at top center (above banner) ─────────
        if (hudMode == HudMode.NODE) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .clickable { viewModel.dismissNodeHud() },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE1E252F),
                shadowElevation = 8.dp
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
        }"""

if old1 in content:
    content = content.replace(old1, "        // NODE mode RETURN is inside NodeDetailHud panel")
    print("Removed floating overlay")
else:
    print("ERROR: overlay not found")

# 2. Add RETURN button inside NodeDetailHud as a proper button in header row
old2 = """            Text(node.callsign, color = Color(0xFFE8EEF5), fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("[ ${node.role} ]", color = Color(0xFF7A8DA0), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)"""

new2 = """            Text(node.callsign, color = Color(0xFFE8EEF5), fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("[ ${node.role} ]", color = Color(0xFF7A8DA0), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Surface(
                modifier = Modifier.clickable { onDismiss() },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2E75B6)
            ) {
                Text("RETURN", color = Color.White, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }"""

if old2 in content:
    content = content.replace(old2, new2)
    print("Added RETURN button in NodeDetailHud")
else:
    print("ERROR: NodeDetailHud header not found")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
