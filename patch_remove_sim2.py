#!/usr/bin/env python3
import sys
TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"
with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()
start = None
for i, line in enumerate(lines):
    if "setSimulationMode" in line and "onClick" in line:
        start = i
        break
if start is None:
    print("FAIL")
    sys.exit(1)
print(f"Found at line {start + 1}")
depth = 0
end = None
for i in range(start, start + 15):
    depth += lines[i].count("{") - lines[i].count("}")
    if depth <= 0 and i > start:
        end = i
        break
if end is None:
    print("FAIL end")
    sys.exit(1)
print(f"Removing lines {start + 1} to {end + 1}")
del lines[start:end + 1]
with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)
print("OK done")
