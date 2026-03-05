path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Move RETURN button to AFTER the contact lost banner
old = """        // ── NODE mode: RETURN button at top center ─────────────────────────
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

        // ── CONTACT LOST banner ───────────────────────────────────────────
        if (convoyState.hasLost && hudMode != HudMode.COLLAPSED) {
            val lostNames = convoyState.nodes
                .filter { it.status == ConvoyStatus.LOST }
                .map { it.callsign }
            ContactLostBanner(
                lostCount = convoyState.lostCount,
                lostNames = lostNames,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }"""

new = """        // ── CONTACT LOST banner ───────────────────────────────────────────
        if (convoyState.hasLost && hudMode != HudMode.COLLAPSED) {
            val lostNames = convoyState.nodes
                .filter { it.status == ConvoyStatus.LOST }
                .map { it.callsign }
            ContactLostBanner(
                lostCount = convoyState.lostCount,
                lostNames = lostNames,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // ── NODE mode: RETURN button at top center (above banner) ─────────
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

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("Fixed RETURN button z-order")
else:
    print("ERROR: marker not found")
