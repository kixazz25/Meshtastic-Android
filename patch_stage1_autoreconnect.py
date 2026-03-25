#!/usr/bin/env python3
"""
Patch ConvoyReconnectWaitScreen.kt:
1. Add auto-reconnect (disconnect i==15, reconnect i==12) to Stage 1 countdown
2. Move observation gate to after Stage 1 reconnect, before WRITE CHANNEL button
"""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# ── Find Stage 1 countdown loop ───────────────────────────────────────────────
# Looking for: "for (i in 60 downTo 1) {" inside LaunchedEffect(Unit)
stage1_for_line = None
for i, line in enumerate(lines):
    if "for (i in 60 downTo 1)" in line and stage1_for_line is None:
        stage1_for_line = i
        break

if stage1_for_line is None:
    print("FAIL: Stage 1 countdown loop not found")
    sys.exit(1)

print(f"Found Stage 1 countdown at line {stage1_for_line + 1}")

# Find the delay(1000) inside the Stage 1 loop (first occurrence after stage1_for_line)
delay_line = None
for i in range(stage1_for_line, stage1_for_line + 10):
    if "delay(1000)" in lines[i]:
        delay_line = i
        break

if delay_line is None:
    print("FAIL: delay(1000) not found in Stage 1 loop")
    sys.exit(1)

print(f"Found delay(1000) at line {delay_line + 1}")

# Replace the countdown body to add auto-reconnect
OLD_COUNTDOWN = (
    "            countdown = i\n"
    "            statusMsg = \"\\u25cc Binary install reboot \u2014 please wait... ${i}s\"\n"
    "            delay(1000)\n"
)
NEW_COUNTDOWN = (
    "            countdown = i\n"
    "            statusMsg = \"\\u25cc Binary install reboot \u2014 please wait... ${i}s\"\n"
    "            // Auto-reconnect: disconnect at i==15, reconnect at i==12\n"
    "            if (i == 15) {\n"
    "                addLog(\"Stage 1: Auto BT disconnect at ${i}s...\")\n"
    "                try {\n"
    "                    convoyViewModel.setDeviceAddress(savedDeviceAddress ?: \"\")\n"
    "                } catch (e: Exception) {\n"
    "                    addLog(\"Stage 1: BT disconnect failed: ${e.message}\")\n"
    "                }\n"
    "            }\n"
    "            if (i == 12) {\n"
    "                addLog(\"Stage 1: Auto BT reconnect at ${i}s...\")\n"
    "                try {\n"
    "                    convoyViewModel.setDeviceAddress(savedDeviceAddress ?: \"\")\n"
    "                } catch (e: Exception) {\n"
    "                    addLog(\"Stage 1: BT reconnect failed: ${e.message}\")\n"
    "                }\n"
    "            }\n"
    "            delay(1000)\n"
)

src = "".join(lines)
if OLD_COUNTDOWN not in src:
    print("FAIL Change 1: Stage 1 countdown body anchor not found")
    sys.exit(1)
src = src.replace(OLD_COUNTDOWN, NEW_COUNTDOWN, 1)
print("OK   Change 1: auto-reconnect added to Stage 1 countdown")

# ── Move observation gate: remove from inside writeChannel(), add to UI ───────
# Remove from inside writeChannel() — the while(!observeGatePassed) block
OLD_GATE_IN_WRITE = (
    "            // \u2500\u2500 Observation gate \u2014 user verifies radio before Stage 2 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
    "            statusMsg = \"\\u25cf Channel written \u2014 check radio. Tap RADIO OK to proceed to Stage 2\"\n"
    "            addLog(\"Observation gate: verify radio sees convoy nodes before Stage 2\")\n"
    "            while (!observeGatePassed) { kotlinx.coroutines.delay(200) }\n"
    "            addLog(\"Observation gate passed \u2014 proceeding to Stage 2\")\n"
)
if OLD_GATE_IN_WRITE not in src:
    print("WARN Change 2: observation gate in writeChannel not found — may already be removed")
else:
    src = src.replace(OLD_GATE_IN_WRITE, "", 1)
    print("OK   Change 2: observation gate removed from writeChannel()")

# ── Add observation gate pause in Stage 1 LaunchedEffect after connected ──────
# After "stage1GatePassed = true" and connected check — insert gate before WRITE CHANNEL
# Find: stage1GatePassed = true followed by connected check
OLD_STAGE1_GATE = (
    "        stage1GatePassed = true\n"
    "        addLog(\"Stage 1: 60s wait complete\")\n"
    "        if (rawConnected) {\n"
    "            phase = \"CONNECTED\"\n"
    "            statusMsg = \"\\u25cf Radio reconnected \u2014 tap WRITE CHANNEL to continue\"\n"
    "            addLog(\"Stage 1: Connected \\u2713\")\n"
    "            return@LaunchedEffect\n"
    "        }\n"
)
NEW_STAGE1_GATE = (
    "        stage1GatePassed = true\n"
    "        addLog(\"Stage 1: 60s wait complete\")\n"
    "        if (rawConnected) {\n"
    "            phase = \"CONNECTED\"\n"
    "            // Observation gate: pause here so user can verify PSK from binary before channel write\n"
    "            statusMsg = \"\\u25cf Radio reconnected \u2014 verify radio sees convoy nodes, then tap RADIO OK\"\n"
    "            addLog(\"Stage 1: Connected \\u2713 \u2014 observation gate active\")\n"
    "            while (!observeGatePassed) { kotlinx.coroutines.delay(200) }\n"
    "            addLog(\"Observation gate passed\")\n"
    "            statusMsg = \"\\u25cf Observation complete \u2014 tap WRITE CHANNEL to continue\"\n"
    "            return@LaunchedEffect\n"
    "        }\n"
)
if OLD_STAGE1_GATE not in src:
    print("FAIL Change 3: Stage 1 connected block anchor not found")
    sys.exit(1)
src = src.replace(OLD_STAGE1_GATE, NEW_STAGE1_GATE, 1)
print("OK   Change 3: observation gate moved to Stage 1 post-reconnect")

with open(TARGET, "w", encoding="utf-8") as f:
    f.write(src)

print("")
print("DONE — patch applied")
print("Run: ./gradlew assembleGoogleDebug")
