#!/usr/bin/env python3
"""Remove observation gate — Stage 1 reconnect goes straight to onProceed()."""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find: addLog("Stage 1: Connected \u2713")
target = None
for i, line in enumerate(lines):
    if 'addLog("Stage 1: Connected' in line and "BT toggle" not in line:
        target = i
        break

if target is None:
    print("FAIL: Stage 1 Connected log not found")
    sys.exit(1)

print(f"Found at line {target + 1}")

# Find closing brace of if (rawConnected) block — look for onProceed()
proceed_line = None
for i in range(target, target + 15):
    if "onProceed()" in lines[i]:
        proceed_line = i
        break

if proceed_line is None:
    print("FAIL: onProceed() not found")
    sys.exit(1)

print(f"Found onProceed() at line {proceed_line + 1}")

# Replace everything from target+1 to proceed_line-1 with clean direct proceed
new_block = [
    "            addLog(\"Stage 1 complete \\u2014 proceeding to Verify\")\n",
    "            kotlinx.coroutines.delay(1500)\n",
    "            scope.launch { onProceed() }\n",
]

# Keep target line (addLog Connected), replace target+1 through proceed_line
del lines[target + 1 : proceed_line + 1]
for j, nl in enumerate(new_block):
    lines.insert(target + 1 + j, nl)

print("OK   observation gate removed — Stage 1 proceeds directly to Verify")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
print("Run: ./gradlew assembleGoogleDebug")
