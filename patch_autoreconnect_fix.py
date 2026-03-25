#!/usr/bin/env python3
"""
Fix auto-reconnect in Stage 1 countdown:
- Replace convoyViewModel.setDeviceAddress with uiViewModel.setDeviceAddress("n") / (savedAddress)
- Also add autoReconnectTick function if not present
"""
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyReconnectWaitScreen.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    src = f.read()

original = src

# ── Fix the disconnect call (i==15) ──────────────────────────────────────────
OLD_DISCONNECT = (
    "            if (i == 15) {\n"
    "                addLog(\"Stage 1: Auto BT disconnect at ${i}s...\")\n"
    "                try {\n"
    "                    convoyViewModel.setDeviceAddress(savedDeviceAddress ?: \"\")\n"
    "                } catch (e: Exception) {\n"
    "                    addLog(\"Stage 1: BT disconnect failed: ${e.message}\")\n"
    "                }\n"
    "            }\n"
)
NEW_DISCONNECT = (
    "            if (i == 15) {\n"
    "                addLog(\"Stage 1: Auto BT disconnect at ${i}s...\")\n"
    "                try {\n"
    "                    uiViewModel.setDeviceAddress(\"n\")\n"
    "                } catch (e: Exception) {\n"
    "                    addLog(\"Stage 1: BT disconnect failed: ${e.message}\")\n"
    "                }\n"
    "            }\n"
)
if OLD_DISCONNECT not in src:
    print("FAIL: disconnect anchor not found")
    sys.exit(1)
src = src.replace(OLD_DISCONNECT, NEW_DISCONNECT, 1)
print("OK   Fix 1: disconnect uses uiViewModel.setDeviceAddress(\"n\")")

# ── Fix the reconnect call (i==12) ────────────────────────────────────────────
OLD_RECONNECT = (
    "            if (i == 12) {\n"
    "                addLog(\"Stage 1: Auto BT reconnect at ${i}s...\")\n"
    "                try {\n"
    "                    convoyViewModel.setDeviceAddress(savedDeviceAddress ?: \"\")\n"
    "                } catch (e: Exception) {\n"
    "                    addLog(\"Stage 1: BT reconnect failed: ${e.message}\")\n"
    "                }\n"
    "            }\n"
)
NEW_RECONNECT = (
    "            if (i == 12) {\n"
    "                addLog(\"Stage 1: Auto BT reconnect at ${i}s...\")\n"
    "                try {\n"
    "                    uiViewModel.setDeviceAddress(savedDeviceAddress ?: \"\")\n"
    "                } catch (e: Exception) {\n"
    "                    addLog(\"Stage 1: BT reconnect failed: ${e.message}\")\n"
    "                }\n"
    "            }\n"
)
if OLD_RECONNECT not in src:
    print("FAIL: reconnect anchor not found")
    sys.exit(1)
src = src.replace(OLD_RECONNECT, NEW_RECONNECT, 1)
print("OK   Fix 2: reconnect uses uiViewModel.setDeviceAddress(savedDeviceAddress)")

if src == original:
    print("WARN: no changes made")
    sys.exit(1)

with open(TARGET, "w", encoding="utf-8") as f:
    f.write(src)

print("")
print("DONE")
print("Run: ./gradlew assembleGoogleDebug")
