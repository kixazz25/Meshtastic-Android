#!/usr/bin/env python3
"""Insert auto-reconnect into Stage 1 countdown by line number."""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find: delay(1000) inside Stage 1 countdown (first occurrence after "Waiting 60s")
waiting_line = None
delay_line = None
for i, line in enumerate(lines):
    if "Waiting 60s for binary install reboot" in line:
        waiting_line = i
    if waiting_line and "delay(1000)" in line and delay_line is None:
        delay_line = i
        break

if delay_line is None:
    print("FAIL: delay(1000) in Stage 1 countdown not found")
    sys.exit(1)

print(f"Found Stage 1 delay(1000) at line {delay_line + 1}")

# Check auto-reconnect not already there
already = any("i == 15" in l or "i == 12" in l for l in lines)
if already:
    print("SKIP: auto-reconnect already present")
    sys.exit(0)

# Insert auto-reconnect BEFORE delay(1000)
new_lines = [
    "            // Auto-reconnect: disconnect at i==15, reconnect at i==12\n",
    "            if (i == 15) {\n",
    "                addLog(\"Stage 1: Auto BT disconnect...\")\n",
    "                uiViewModel.setDeviceAddress(\"n\")\n",
    "            }\n",
    "            if (i == 12) {\n",
    "                addLog(\"Stage 1: Auto BT reconnect...\")\n",
    "                uiViewModel.setDeviceAddress(savedDeviceAddress ?: \"\")\n",
    "            }\n",
]

for j, nl in enumerate(new_lines):
    lines.insert(delay_line + j, nl)

print(f"OK   inserted {len(new_lines)} auto-reconnect lines before delay(1000)")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
print("Run: ./gradlew assembleGoogleDebug")
