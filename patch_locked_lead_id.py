#!/usr/bin/env python3
"""
patch_locked_lead_id.py
Store _lockedLeadId persistently in ViewModel.
Set once at lock time. Clear on RECALC. Never derived from convoyState.
Run from ~/Meshtastic-Android directory.
"""

VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"

with open(VM_PATH, "r", encoding="utf-8") as f:
    txt = f.read()

# 1. Add _lockedLeadId after _leadLockedFlag
OLD_FLAG = "    private var _leadLockedFlag: Boolean = false"
NEW_FLAG = "    private var _leadLockedFlag: Boolean = false\n    private var _lockedLeadId: String = \"\""
assert OLD_FLAG in txt, "FAIL: _leadLockedFlag not found"
txt = txt.replace(OLD_FLAG, NEW_FLAG, 1)

# 2. recalcLead — clear _lockedLeadId
OLD_RECALC = "    fun recalcLead() {\n        _leadLockedFlag = false\n        _leadLocked.value = false"
NEW_RECALC = "    fun recalcLead() {\n        _leadLockedFlag = false\n        _lockedLeadId = \"\"\n        _leadLocked.value = false"
assert OLD_RECALC in txt, "FAIL: recalcLead not found"
txt = txt.replace(OLD_RECALC, NEW_RECALC, 1)

# 3. Tick — use stored _lockedLeadId instead of deriving from convoyState
OLD_LOCKED = "        val lockedLeadId = if (_leadLockedFlag) _convoyState.value.lead?.nodeId ?: \"\" else \"\""
NEW_LOCKED = "        val lockedLeadId = if (_leadLockedFlag) _lockedLeadId else \"\""
assert OLD_LOCKED in txt, "FAIL: lockedLeadId derivation not found"
txt = txt.replace(OLD_LOCKED, NEW_LOCKED, 1)

# 4. Auto-lock — store nodeId into _lockedLeadId at lock time
OLD_AUTOLOCK = "                        _leadLockedFlag = true\n                        _leadLocked.value = true"
NEW_AUTOLOCK = "                        _lockedLeadId = state.lead?.nodeId ?: \"\"\n                        _leadLockedFlag = true\n                        _leadLocked.value = true"
assert OLD_AUTOLOCK in txt, "FAIL: auto-lock block not found"
txt = txt.replace(OLD_AUTOLOCK, NEW_AUTOLOCK, 1)

with open(VM_PATH, "w", encoding="utf-8") as f:
    f.write(txt)
print("OK: _lockedLeadId stored persistently")
print("\nRun: ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
