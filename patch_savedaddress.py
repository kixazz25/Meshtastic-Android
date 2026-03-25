#!/usr/bin/env python3
"""Add savedDeviceAddress declaration to ConvoyReconnectWaitScreen.kt"""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Check if already declared
if any("savedDeviceAddress" in l and "remember" in l for l in lines):
    print("SKIP: savedDeviceAddress already declared")
    sys.exit(0)

# Find: fun addLog — insert savedDeviceAddress just before it
target = None
for i, line in enumerate(lines):
    if "fun addLog(" in line:
        target = i
        break

if target is None:
    print("FAIL: fun addLog not found")
    sys.exit(1)

new_line = "    val savedDeviceAddress = remember { uiViewModel.getDeviceAddress() ?: \"\" }\n"
lines.insert(target, new_line)
print(f"OK   inserted savedDeviceAddress before line {target + 1}")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
print("Run: ./gradlew assembleGoogleDebug")
