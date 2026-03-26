#!/usr/bin/env python3
"""
GroupTrack HUD Typography Standard — complete patch
Applies consistently across GroupHud, MyCartHud, NodeDetailHud, HudStat, HudCard

Typography Standard:
  XS  = 9sp  SemiBold  — micro labels (INT, slider)
  S   = 11sp SemiBold  — stat labels (SPAN, CARTS, CH%)
  M   = 13sp Bold      — section headers (GROUP, MY CART)
  L   = 16sp Black     — stat values, callsigns, units
  XL  = 36sp Black     — hero numbers (span miles, speed)

All text: white Shadow(blurRadius=8f) for contrast on any map background
No FontFamily.Monospace — use default system font for consistent rendering
Transparent background — shadow provides all contrast
Colors: labels=dark, values=dark, active=green, lost=red, lead=green, tail=orange
"""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    src = f.read()

changes = 0

def r(old, new, label):
    global src, changes
    if old in src:
        src = src.replace(old, new, 1)
        changes += 1
        print(f"OK   {label}")
    else:
        print(f"SKIP {label} — anchor not found")

# ── Standard shadow import helper ─────────────────────────────────────────────
# We'll use inline style on each Text. Shadow is in androidx.compose.ui.graphics
# Already imported via existing code.

SHADOW = 'style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f))'

# ── HudCard — remove dark background, keep transparent ───────────────────────
r(
    '.background(Color(0xBB000000), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))\n            .padding(horizontal = 8.dp, vertical = 6.dp)',
    '.padding(start = 0.dp, bottom = 12.dp)',
    "HudCard — restore transparent background"
)

# ── HudStat — apply shadow standard ──────────────────────────────────────────
r(
    '''@Composable
fun HudStat(label: String, value: String, valueColor: Color = Color(0xFFFF4444)) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = Color(0xFFFFFFFF).copy(alpha = 0.85f), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp)
        Text(value, color = valueColor, fontSize = 14.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}''',
    '''@Composable
fun HudStat(label: String, value: String, valueColor: Color = Color(0xFF111111)) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = Color(0xFF111111), fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
        Text(value, color = valueColor, fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
    }
}''',
    "HudStat — white shadow, Black weight, system font"
)

# ── GroupHud — GROUP header ───────────────────────────────────────────────────
r(
    'Text("GROUP", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,\n                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,\n                letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 4.dp))',
    'Text("GROUP", color = Color(0xFF111111), fontSize = 13.sp,\n                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,\n                modifier = Modifier.padding(bottom = 4.dp),\n                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "GroupHud — GROUP header M size with shadow"
)

# ── GroupHud — SPAN label ─────────────────────────────────────────────────────
r(
    'Text("SPAN", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,\n                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,\n                        letterSpacing = 1.sp, modifier = Modifier.padding(end = 4.dp, bottom = 6.dp))',
    'Text("SPAN", color = Color(0xFF111111), fontSize = 11.sp,\n                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,\n                        modifier = Modifier.padding(end = 4.dp, bottom = 6.dp),\n                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "GroupHud — SPAN label S size"
)

# ── GroupHud — SPAN hero number ───────────────────────────────────────────────
r(
    'Text("%.1f".format(state.span_miles),\n                        color = Color(0xFFFF0000).copy(alpha = 1f),\n                        fontSize = 48.sp, fontFamily = FontFamily.Monospace,\n                        fontWeight = FontWeight.Bold, lineHeight = 48.sp)',
    'Text("%.1f".format(state.span_miles),\n                        color = Color(0xFF111111),\n                        fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp,\n                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 10f)))',
    "GroupHud — SPAN hero number XL size"
)

# ── GroupHud — mi unit ────────────────────────────────────────────────────────
r(
    'Text(" mi", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,\n                        fontFamily = FontFamily.Monospace,\n                        modifier = Modifier.padding(bottom = 6.dp))',
    'Text(" mi", color = Color(0xFF111111), fontSize = 13.sp,\n                        fontWeight = FontWeight.Bold,\n                        modifier = Modifier.padding(bottom = 6.dp),\n                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "GroupHud — mi unit M size"
)

# ── GroupHud — CH% label ──────────────────────────────────────────────────────
r(
    'Text("CH%", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,\n                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,\n                        letterSpacing = 1.sp)',
    'Text("CH%", color = Color(0xFF111111), fontSize = 11.sp,\n                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,\n                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "GroupHud — CH% label S size"
)

# ── GroupHud — CH% value ──────────────────────────────────────────────────────
r(
    'Text("%.0f%%".format(avgChannelUtil), color = chColor,\n                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,\n                            fontWeight = FontWeight.Bold)',
    'Text("%.0f%%".format(avgChannelUtil), color = chColor,\n                            fontSize = 13.sp, fontWeight = FontWeight.Black,\n                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "GroupHud — CH% value M size"
)

# ── GroupHud — slider interval label ─────────────────────────────────────────
r(
    'Text("${currentIntervalSecs}s", color = Color(0xFFFF0000).copy(alpha = 0.8f),\n                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)',
    'Text("${currentIntervalSecs}s", color = Color(0xFF111111), fontSize = 10.sp,\n                fontWeight = FontWeight.Bold,\n                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 6f)))',
    "GroupHud — slider interval label XS"
)

# ── GroupHud — INT label ──────────────────────────────────────────────────────
r(
    'Text("INT", color = Color(0xFFFF0000).copy(alpha = 0.6f),\n                fontSize = 9.sp, fontFamily = FontFamily.Monospace)',
    'Text("INT", color = Color(0xFF111111), fontSize = 9.sp,\n                fontWeight = FontWeight.SemiBold,\n                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 6f)))',
    "GroupHud — INT label XS"
)

# ── GroupHud — Active/Lost color fix ─────────────────────────────────────────
r(
    'HudStat("Active",      "${state.activeCount}")',
    'HudStat("Active", "${state.activeCount}", Color(0xFF00CC44))',
    "GroupHud — Active green"
)
r(
    'HudStat("Lost",        "${state.lostCount}")',
    'HudStat("Lost", "${state.lostCount}", Color(0xFFFF4444))',
    "GroupHud — Lost red"
)

# ── MyCartHud — MY CART header ────────────────────────────────────────────────
r(
    'Text("My Cart  ★ ${myCart?.callsign ?: myCartId.takeLast(8)}", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,\n            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,\n            letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 6.dp))',
    'Text("My Cart  ★ ${myCart?.callsign ?: myCartId.takeLast(8)}", color = Color(0xFF111111), fontSize = 13.sp,\n            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,\n            modifier = Modifier.padding(bottom = 6.dp),\n            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "MyCartHud — header M size"
)

# ── MyCartHud — MY CART not found ────────────────────────────────────────────
r(
    'Text("MY CART not found", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 12.sp,\n                fontFamily = FontFamily.Monospace)',
    'Text("MY CART not found", color = Color(0xFF111111), fontSize = 12.sp,\n                fontWeight = FontWeight.SemiBold,\n                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "MyCartHud — not found message"
)

# ── MyCartHud — Speed label ───────────────────────────────────────────────────
r(
    'Text("Speed", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,\n                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,\n                        letterSpacing = 1.sp)',
    'Text("Speed", color = Color(0xFF111111), fontSize = 11.sp,\n                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,\n                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "MyCartHud — Speed label S"
)

# ── MyCartHud — Speed hero number ────────────────────────────────────────────
r(
    'Text("%.0f".format(myCart.speed_mph), color = Color(0xFFFF0000).copy(alpha = 1f),\n                            fontSize = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,\n                            lineHeight = 48.sp)',
    'Text("%.0f".format(myCart.speed_mph), color = Color(0xFF111111),\n                            fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp,\n                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 10f)))',
    "MyCartHud — Speed hero XL"
)

# ── MyCartHud — mph unit ──────────────────────────────────────────────────────
r(
    'Text(" mph", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,\n                            fontFamily = FontFamily.Monospace,\n                            modifier = Modifier.padding(bottom = 6.dp))',
    'Text(" mph", color = Color(0xFF111111), fontSize = 13.sp,\n                            fontWeight = FontWeight.Bold,\n                            modifier = Modifier.padding(bottom = 6.dp),\n                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "MyCartHud — mph unit M"
)

# ── NodeDetailHud — callsign header ──────────────────────────────────────────
r(
    'Text(node.callsign, color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,\n            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,\n            letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 6.dp))',
    'Text(node.callsign, color = Color(0xFF111111), fontSize = 13.sp,\n            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,\n            modifier = Modifier.padding(bottom = 6.dp),\n            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))',
    "NodeDetailHud — callsign header M"
)

# ── NodeDetailHud — STATUS color fix ─────────────────────────────────────────
r(
    'ConvoyStatus.ACTIVE      -> Color(0xFF00AA00)',
    'ConvoyStatus.ACTIVE      -> Color(0xFF00CC44)',
    "NodeDetailHud — Active brighter green"
)

# ── Slider — explicit colors for visibility on all devices ───────────────────
r(
    '''            androidx.compose.material3.Slider(
                value = currentIntervalSecs.toFloat(),
                onValueChange = { onIntervalChange(it.toInt()) },
                valueRange = 2f..8f,
                steps = 5,
                modifier = Modifier
                    .height(80.dp)
                    .graphicsLayer { rotationZ = -90f }
                    .width(80.dp)
            )''',
    '''            androidx.compose.material3.Slider(
                value = currentIntervalSecs.toFloat(),
                onValueChange = { onIntervalChange(it.toInt()) },
                valueRange = 2f..8f,
                steps = 5,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF2E75B6),
                    inactiveTrackColor = Color(0xFFFFFFFF).copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .height(80.dp)
                    .graphicsLayer { rotationZ = -90f }
                    .width(80.dp)
            )''',
    "Slider — explicit white thumb and track colors"
)

with open(TARGET, "w", encoding="utf-8") as f:
    f.write(src)

print(f"\nDONE — {changes} changes applied")
print("Run: ./gradlew assembleGoogleDebug")
