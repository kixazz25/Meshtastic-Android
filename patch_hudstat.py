#!/usr/bin/env python3
"""Fix HudStat label and value text with white shadow standard."""

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    src = f.read()

OLD = '''        Text(label, color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp)
        Text(value, color = valueColor, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)'''

NEW = '''        Text(label, color = Color(0xFF111111), fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
        Text(value, color = valueColor, fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))'''

if OLD in src:
    src = src.replace(OLD, NEW, 1)
    print("OK   HudStat label+value — white shadow, Black weight, system font")
else:
    print("FAIL — anchor not found")

with open(TARGET, "w", encoding="utf-8") as f:
    f.write(src)

print("DONE")
