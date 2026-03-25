#!/usr/bin/env python3
"""Wire Stage 1 observation gate directly to onProceed() — skip Stage 2 entirely."""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find: statusMsg = "Observation complete — tap WRITE CHANNEL"
target = None
for i, line in enumerate(lines):
    if "Observation complete" in line and "WRITE CHANNEL" in line:
        target = i
        break

if target is None:
    print("FAIL: observation complete line not found")
    sys.exit(1)

print(f"Found at line {target + 1}: {lines[target].rstrip()}")

# Replace that line + return@LaunchedEffect with auto-proceed
# target     = statusMsg = "Observation complete..."
# target + 1 = return@LaunchedEffect
lines[target] = (
    "            statusMsg = \"\\u25cf Observation complete \\u2014 proceeding to Verify...\"\n"
    "            addLog(\"Stage 1 complete \\u2014 proceeding directly to Verify\")\n"
    "            kotlinx.coroutines.delay(1500)\n"
    "            scope.launch { onProceed() }\n"
)

# Remove the return@LaunchedEffect that follows
if "return@LaunchedEffect" in lines[target + 1]:
    lines[target + 1] = ""
    print("OK   removed return@LaunchedEffect")

print("OK   Stage 1 observation gate now calls onProceed() directly")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
print("Run: ./gradlew assembleGoogleDebug")
