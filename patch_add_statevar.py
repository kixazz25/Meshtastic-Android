#!/usr/bin/env python3
"""Add missing observeGatePassed state var to ConvoyReconnectWaitScreen.kt"""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find channelWriteDone var declaration
target_line = None
for i, line in enumerate(lines):
    if "var channelWriteDone" in line and "remember" in line:
        target_line = i
        break

if target_line is None:
    print("FAIL: channelWriteDone var not found")
    sys.exit(1)

# Check if observeGatePassed already declared
already = any("var observeGatePassed" in l for l in lines)
if already:
    print("SKIP: observeGatePassed already declared")
    sys.exit(0)

# Insert after channelWriteDone line
new_line = "    var observeGatePassed    by remember { mutableStateOf(false) }\n"
lines.insert(target_line + 1, new_line)
print(f"OK   inserted observeGatePassed after line {target_line + 1}")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
