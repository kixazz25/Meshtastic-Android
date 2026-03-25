#!/usr/bin/env python3
"""Remove remaining SIM/LIVE button block from ConvoyScreen.kt"""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find all lines related to SIM/LIVE button
start = None
end = None
for i, line in enumerate(lines):
    if "setSimulationMode" in line and start is None:
        # Walk back to find onClick line
        for j in range(i, max(i-3, 0), -1):
            if "onClick" in lines[j]:
                start = j
                break
        if start is None:
            start = i

if start is None:
    print("FAIL: setSimulationMode not found — may already be removed")
    sys.exit(0)

# Walk forward counting braces to find end
depth = 0
for i in range(start, start + 20):
    depth += lines[i].count("{") - lines[i].count("}")
    if depth <= 0 and i > start:
        end = i
        break

if end is None:
    print("FAIL: closing brace not found")
    sys.exit(1)

print(f"Removing lines {start + 1} to {end + 1}:")
for i in range(start, end + 1):
    print(f"  {i+1}: {lines[i].rstrip()}")

del lines[start:end + 1]

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("OK   SIM/LIVE button removed")
print("DONE")
