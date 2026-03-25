#!/usr/bin/env python3
"""
Patch ConvoyReconnectWaitScreen.kt — add observation gate between Stage 1 and Stage 2.
Run from repo root:
  python3 patch_reconnect_observe_gate.py
"""

import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    src = f.read()

original = src

# ── Change 1: add observeGatePassed state var ─────────────────────────────────
OLD1 = "    var channelWriteDone  by remember { mutableStateOf(false) }"
NEW1 = (
    "    var channelWriteDone     by remember { mutableStateOf(false) }\n"
    "    var observeGatePassed    by remember { mutableStateOf(false) }"
)
if OLD1 not in src:
    print("FAIL Change 1: anchor not found — check file manually")
    sys.exit(1)
src = src.replace(OLD1, NEW1, 1)
print("OK   Change 1: observeGatePassed state var added")

# ── Change 2: add observation gate pause in coroutine after channelWriteDone = true ──
OLD2 = (
    "            channelWriteDone = true\n"
    "            statusMsg = \"\u2713 Channel + PSK committed \u2014 waiting for reboot...\"\n"
    "            // \u2500\u2500 Stage 2: Wait 60s for reimport reboot \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"
)
NEW2 = (
    "            channelWriteDone = true\n"
    "            // \u2500\u2500 Observation gate \u2014 user verifies radio state before Stage 2 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
    "            statusMsg = \"\u25cf Channel written \u2014 check radio now. Tap RADIO OK to proceed to Stage 2\"\n"
    "            addLog(\"Observation gate: verify radio sees convoy nodes before Stage 2\")\n"
    "            while (!observeGatePassed) { kotlinx.coroutines.delay(200) }\n"
    "            addLog(\"Observation gate passed \u2014 proceeding to Stage 2\")\n"
    "            statusMsg = \"\u2713 Channel + PSK committed \u2014 waiting for reboot...\"\n"
    "            // \u2500\u2500 Stage 2: Wait 60s for reimport reboot \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"
)
if OLD2 not in src:
    print("FAIL Change 2: anchor not found — check file manually")
    sys.exit(1)
src = src.replace(OLD2, NEW2, 1)
print("OK   Change 2: observation gate pause added in coroutine")

# ── Change 3: add RADIO OK button in UI before Stage 2 PROCEED button ─────────
OLD3 = (
    "            // Stage 2 button \u2014 PROCEED TO VERIFY\n"
    "            if (channelWriteDone) {"
)
NEW3 = (
    "            // Observation gate button \u2014 shown between Stage 1 complete and Stage 2 start\n"
    "            if (channelWriteDone && !observeGatePassed) {\n"
    "                Surface(\n"
    "                    modifier = Modifier.fillMaxWidth()\n"
    "                        .clickable { observeGatePassed = true },\n"
    "                    shape = RoundedCornerShape(12.dp),\n"
    "                    color = Color(0xFF4A3A00)\n"
    "                ) {\n"
    "                    Text(\n"
    "                        text = \"\u26a0 RADIO OK \u2014 PROCEED TO STAGE 2\",\n"
    "                        color = Color(0xFFFFB74D),\n"
    "                        fontSize = 13.sp, fontFamily = FontFamily.Monospace,\n"
    "                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,\n"
    "                        modifier = Modifier.padding(vertical = 16.dp)\n"
    "                    )\n"
    "                }\n"
    "                Spacer(Modifier.height(12.dp))\n"
    "            }\n"
    "            // Stage 2 button \u2014 PROCEED TO VERIFY\n"
    "            if (channelWriteDone && observeGatePassed) {"
)
if OLD3 not in src:
    print("FAIL Change 3: anchor not found — check file manually")
    sys.exit(1)
src = src.replace(OLD3, NEW3, 1)
print("OK   Change 3: RADIO OK observation button added in UI")

if src == original:
    print("WARN: No changes were made — check anchors")
    sys.exit(1)

with open(TARGET, "w", encoding="utf-8") as f:
    f.write(src)

print("")
print("DONE — ConvoyReconnectWaitScreen.kt patched successfully")
print("Run: ./gradlew assembleGoogleDebug")
