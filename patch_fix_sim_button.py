#!/usr/bin/env python3
"""Remove orphaned SIM/LIVE button remnants from ConvoyScreen.kt"""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find onClick = { viewModel.setSimulationMode line
start = None
for i, line in enumerate(lines):
    if "setSimulationMode" in line and "onClick" in line:
        start = i
        break

if start is None:
    print("FAIL: setSimulationMode onClick not found")
    sys.exit(1)

# Find closing brace of this block — the } that closes the Surface content
end = None
depth = 0
for i in range(start, start + 20):
    depth += lines[i].count("{") - lines[i].count("}")
    if depth <= 0 and i > start:
        end = i
        break

print(f"Removing lines {start + 1} to {end + 1}")
del lines[start:end + 1]
print("OK   orphaned SIM/LIVE button block removed")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
