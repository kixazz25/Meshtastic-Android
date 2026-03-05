path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# ── 1. Replace HudModeRow with large pill buttons + HIDE ─────────────────────
old1 = """@Composable
fun HudModeRow(current: HudMode, onModeChange: (HudMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        listOf(HudMode.GROUP, HudMode.MY_CART, HudMode.COLLAPSED).forEach { mode ->
            val label = when (mode) {
                HudMode.GROUP     -> "GROUP"
                HudMode.MY_CART   -> "MY CART"
                HudMode.COLLAPSED -> "▾"
                HudMode.NODE      -> "NODE"
            }
            Text(
                text = label,
                color = if (mode == current) Color(0xFF2E75B6) else Color(0xFF3D5066),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (mode == current) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clickable { onModeChange(mode) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}"""

new1 = """@Composable
fun HudModeRow(current: HudMode, onModeChange: (HudMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // GROUP button
        Surface(
            modifier = Modifier.weight(1f).clickable { onModeChange(HudMode.GROUP) },
            shape = RoundedCornerShape(10.dp),
            color = if (current == HudMode.GROUP) Color(0xFF2E75B6) else Color(0xFF2A3545)
        ) {
            Text(
                text = "GROUP",
                color = if (current == HudMode.GROUP) Color.White else Color(0xFF7A8DA0),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
        // MY CART button
        Surface(
            modifier = Modifier.weight(1f).clickable { onModeChange(HudMode.MY_CART) },
            shape = RoundedCornerShape(10.dp),
            color = if (current == HudMode.MY_CART) Color(0xFF2E75B6) else Color(0xFF2A3545)
        ) {
            Text(
                text = "MY CART",
                color = if (current == HudMode.MY_CART) Color.White else Color(0xFF7A8DA0),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
        // HIDE button
        Surface(
            modifier = Modifier.clickable { onModeChange(HudMode.COLLAPSED) },
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF2A3545)
        ) {
            Text(
                text = "HIDE",
                color = Color(0xFF7A8DA0),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}"""

if old1 in content:
    content = content.replace(old1, new1)
    print("Fixed HudModeRow")
else:
    print("ERROR: HudModeRow not found")

# ── 2. Redesign GroupHud to show SPAN large on right ─────────────────────────
old2 = """    HudCard {
        HudModeRow(current = HudMode.GROUP, onModeChange = onModeChange)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            HudStat("UNITS", "${state.nodes.size}")
            HudStat("ACTIVE", "${state.activeCount}", Color(0xFF00AA00))
            HudStat("LOST", "${state.lostCount}", if (state.lostCount > 0) Color(0xFFF44336) else Color(0xFF7A8DA0))
            HudStat("SPAN", "%.1f mi".format(state.span_miles))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            HudStat("LEAD", state.lead?.callsign ?: "--", Color(0xFF1CF0A0))
            HudStat("TAIL", state.tail?.callsign ?: "--", Color(0xFFFF8C42))
        }
    }"""

new2 = """    HudCard {
        HudModeRow(current = HudMode.GROUP, onModeChange = onModeChange)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left 3/4 — stats grid
            Column(modifier = Modifier.weight(3f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    HudStat("UNITS", "${state.nodes.size}")
                    HudStat("ACTIVE", "${state.activeCount}", Color(0xFF00AA00))
                    HudStat("LOST", "${state.lostCount}", if (state.lostCount > 0) Color(0xFFF44336) else Color(0xFF7A8DA0))
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    HudStat("LEAD", state.lead?.callsign ?: "--", Color(0xFF1CF0A0))
                    HudStat("TAIL", state.tail?.callsign ?: "--", Color(0xFFFF8C42))
                }
            }
            // Right 1/4 — SPAN large
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("SPAN", color = Color(0xFF4A6080), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
                Text("%.1f".format(state.span_miles), color = Color(0xFFE8EEF5),
                    fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("mi", color = Color(0xFF4A6080), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }"""

if old2 in content:
    content = content.replace(old2, new2)
    print("Fixed GroupHud with large SPAN")
else:
    print("ERROR: GroupHud not found")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
