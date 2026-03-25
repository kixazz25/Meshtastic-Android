#!/usr/bin/env python3
"""Remove SIM/LIVE button from map screen."""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find: // Sim mode toggle (dev only)
start = None
for i, line in enumerate(lines):
    if "Sim mode toggle (dev only)" in line:
        start = i
        break

if start is None:
    print("FAIL: Sim mode toggle comment not found")
    sys.exit(1)

# Find closing } of TextButton block
depth = 0
end = None
for i in range(start, start + 20):
    depth += lines[i].count("{") - lines[i].count("}")
    if depth == 0 and i > start:
        end = i
        break

if end is None:
    print("FAIL: closing brace not found")
    sys.exit(1)

print(f"Removing lines {start + 1} to {end + 1}")
del lines[start:end + 1]

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("OK   SIM/LIVE button removed")
print("DONE")
