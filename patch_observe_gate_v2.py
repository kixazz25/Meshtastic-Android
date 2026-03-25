#!/usr/bin/env python3
"""
Patch ConvoyReconnectWaitScreen.kt — observation gate between Stage 1 and Stage 2.
Uses line number search instead of string anchors.
"""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# ── Find key line numbers ─────────────────────────────────────────────────────
line_channelWriteDone = None
line_stage2_comment   = None
line_stage2_button    = None

for i, line in enumerate(lines):
    if "channelWriteDone = true" in line and line_channelWriteDone is None:
        line_channelWriteDone = i
    if "Stage 2: Wait 60s for reimport reboot" in line and line_stage2_comment is None:
        line_stage2_comment = i
    if "// Stage 2 button" in line and "PROCEED TO VERIFY" in line:
        line_stage2_button = i

print(f"Found channelWriteDone = true  at line {line_channelWriteDone + 1 if line_channelWriteDone is not None else 'NOT FOUND'}")
print(f"Found Stage 2 comment          at line {line_stage2_comment + 1 if line_stage2_comment is not None else 'NOT FOUND'}")
print(f"Found Stage 2 button           at line {line_stage2_button + 1 if line_stage2_button is not None else 'NOT FOUND'}")

if None in (line_channelWriteDone, line_stage2_comment, line_stage2_button):
    print("FAIL: Could not find all anchors")
    sys.exit(1)

# ── Change 2: insert observation gate lines before the Stage 2 comment ────────
gate_lines = [
    "            // \u2500\u2500 Observation gate \u2014 user verifies radio before Stage 2 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n",
    "            statusMsg = \"\\u25cf Channel written \u2014 check radio. Tap RADIO OK to proceed to Stage 2\"\n",
    "            addLog(\"Observation gate: verify radio sees convoy nodes before Stage 2\")\n",
    "            while (!observeGatePassed) { kotlinx.coroutines.delay(200) }\n",
    "            addLog(\"Observation gate passed \u2014 proceeding to Stage 2\")\n",
]
# Insert before the Stage 2 comment line
for j, gl in enumerate(gate_lines):
    lines.insert(line_stage2_comment + j, gl)
print("OK   Change 2: observation gate pause inserted before Stage 2 comment")

# Recalculate line_stage2_button after insertion
offset = len(gate_lines)
line_stage2_button += offset

# ── Change 3: replace Stage 2 button condition ────────────────────────────────
# Find the next line after stage2_button comment which should be: if (channelWriteDone) {
btn_if_line = line_stage2_button + 1
if "if (channelWriteDone)" in lines[btn_if_line]:
    lines[btn_if_line] = lines[btn_if_line].replace(
        "if (channelWriteDone) {",
        "if (channelWriteDone && observeGatePassed) {"
    )
    print("OK   Change 3a: Stage 2 button condition updated to require observeGatePassed")
else:
    print(f"WARN Change 3a: Expected 'if (channelWriteDone)' at line {btn_if_line+1}, found: {lines[btn_if_line].rstrip()}")

# ── Change 3b: insert RADIO OK button before the Stage 2 button ──────────────
radio_ok_lines = [
    "            // Observation gate button\n",
    "            if (channelWriteDone && !observeGatePassed) {\n",
    "                Surface(\n",
    "                    modifier = Modifier.fillMaxWidth()\n",
    "                        .clickable { observeGatePassed = true },\n",
    "                    shape = RoundedCornerShape(12.dp),\n",
    "                    color = Color(0xFF4A3A00)\n",
    "                ) {\n",
    "                    Text(\n",
    "                        text = \"\\u26a0 RADIO OK \u2014 PROCEED TO STAGE 2\",\n",
    "                        color = Color(0xFFFFB74D),\n",
    "                        fontSize = 13.sp, fontFamily = FontFamily.Monospace,\n",
    "                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,\n",
    "                        modifier = Modifier.padding(vertical = 16.dp)\n",
    "                    )\n",
    "                }\n",
    "                Spacer(Modifier.height(12.dp))\n",
    "            }\n",
]
for j, rl in enumerate(radio_ok_lines):
    lines.insert(line_stage2_button + j, rl)
print("OK   Change 3b: RADIO OK button inserted before Stage 2 PROCEED button")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("")
print("DONE — ConvoyReconnectWaitScreen.kt patched successfully")
print("Run: ./gradlew assembleGoogleDebug")
