#!/usr/bin/env python3
"""Remove WRITE CHANNEL button from UI — Stage 1 now goes directly to Verify."""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Find: // Stage 1 button — WRITE CHANNEL
start = None
for i, line in enumerate(lines):
    if "// Stage 1 button" in line and "WRITE CHANNEL" in line:
        start = i
        break

if start is None:
    print("FAIL: Stage 1 button comment not found")
    sys.exit(1)

print(f"Found Stage 1 button block at line {start + 1}")

# Find end of this if block — ends before "if (channelWriteDone && !observeGatePassed)"
end = None
for i in range(start, start + 30):
    if "channelWriteDone && observeGatePassed" in lines[i] or "channelWriteDone &&" in lines[i]:
        end = i
        break

if end is None:
    print("FAIL: end of button block not found")
    sys.exit(1)

print(f"Removing lines {start + 1} to {end}")
del lines[start:end]
print(f"OK   WRITE CHANNEL button block removed")

with open(TARGET, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("DONE")
print("Run: ./gradlew assembleGoogleDebug")
