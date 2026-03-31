#!/usr/bin/env python3
"""
patch_tick_debug.py
Add file-based debug logging at PRE-TICK, TICK-START, and TICK-END.
Run from ~/Meshtastic-Android directory.
"""

VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"

with open(VM_PATH, "r", encoding="utf-8") as f:
    txt = f.read()

# 1. Add debug file writer helper after startTick definition line
OLD_START_TICK = "    private fun startTick() {\n        tickJob?.cancel()\n        tickJob = viewModelScope.launch {\n            while (true) {\n                tick()\n                delay(ConvoySimulation.TICK_MS)\n            }\n        }"
NEW_START_TICK = (
    "    private fun writeDebug(msg: String) {\n"
    "        try {\n"
    "            val f = java.io.File(appContext.filesDir, \"convoy_tick_debug.txt\")\n"
    "            f.appendText(\"${System.currentTimeMillis()} $msg\\n\")\n"
    "        } catch (e: Exception) { /* ignore */ }\n"
    "    }\n"
    "    private fun startTick() {\n"
    "        tickJob?.cancel()\n"
    "        tickJob = viewModelScope.launch {\n"
    "            while (true) {\n"
    "                writeDebug(\"PRE-TICK locked=$_leadLockedFlag\")\n"
    "                tick()\n"
    "                delay(ConvoySimulation.TICK_MS)\n"
    "            }\n"
    "        }"
)
assert OLD_START_TICK in txt, "FAIL: startTick not found"
txt = txt.replace(OLD_START_TICK, NEW_START_TICK, 1)

# 2. Add TICK-START at top of tick()
OLD_TICK_START = "    private fun tick() { try {"
NEW_TICK_START = "    private fun tick() { try {\n        writeDebug(\"TICK-START locked=$_leadLockedFlag\")"
assert OLD_TICK_START in txt, "FAIL: tick() start not found"
txt = txt.replace(OLD_TICK_START, NEW_TICK_START, 1)

# 3. Add TICK-END after _convoyState.value = state
OLD_TICK_END = "        _convoyState.value = state\n        // \u2500\u2500 ConvoyLead tick log"
NEW_TICK_END = "        _convoyState.value = state\n        writeDebug(\"TICK-END locked=$_leadLockedFlag lead=${state.lead?.longName}(${state.lead?.nodeId}) accum=$leadLockDistanceAccum\")\n        // \u2500\u2500 ConvoyLead tick log"
assert OLD_TICK_END in txt, "FAIL: tick end anchor not found"
txt = txt.replace(OLD_TICK_END, NEW_TICK_END, 1)

with open(VM_PATH, "w", encoding="utf-8") as f:
    f.write(txt)
print("OK: tick debug file logging added")
print("Debug file: filesDir/convoy_tick_debug.txt")
print("\nRun: ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
