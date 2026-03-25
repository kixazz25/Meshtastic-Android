#!/usr/bin/env python3
"""Add observation gate to Stage 1 connected block using line numbers."""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find: addLog("Stage 1: Connected \u2713") — the exact line
target_line = None
for i, line in enumerate(lines):
    if 'addLog("Stage 1: Connected' in line and "BT toggle" not in line:
        target_line = i
        break

if target_line is None:
    print("FAIL: Stage 1 Connected log line not found")
    sys.exit(1)

print(f"Found 'Stage 1: Connected' at line {target_line + 1}")
print(f"Context: {lines[target_line].rstrip()}")

# Insert observation gate lines AFTER the addLog line, BEFORE return@LaunchedEffect
# target_line     = addLog("Stage 1: Connected \u2713")
# target_line + 1 = return@LaunchedEffect
# Insert between them

gate_lines = [
    "            // Observation gate: verify PSK from binary before channel write\n",
    "            statusMsg = \"\\u25cf Radio reconnected \\u2014 verify radio sees convoy nodes, then tap RADIO OK\"\n",
    "            addLog(\"Observation gate active \\u2014 waiting for user confirmation\")\n",
    "            while (!observeGatePassed) { kotlinx.coroutines.delay(200) }\n",
    "            addLog(\"Observation gate passed\")\n",
    "            statusMsg = \"\\u25cf Observation complete \\u2014 tap WRITE CHANNEL to continue\"\n",
]

insert_at = target_line + 1
for j, gl in enumerate(gate_lines):
    lines.insert(insert_at + j, gl)

print(f"OK   inserted {len(gate_lines)} observation gate lines after line {target_line + 1}")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
print("Run: ./gradlew assembleGoogleDebug")
