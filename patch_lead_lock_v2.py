#!/usr/bin/env python3
"""
patch_lead_lock_fix.py
Lead lock fix — one truth, no duplication, ConvoyLead logcat logging.
Run from ~/Meshtastic-Android directory.
"""
import re

# ─────────────────────────────────────────────────────────────
# ConvoyEngine.kt
# ─────────────────────────────────────────────────────────────
ENG_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyEngine.kt"

with open(ENG_PATH, "r", encoding="utf-8") as f:
    eng = f.read()

# 1. Add lockedLeadId parameter to compute()
OLD_COMPUTE_SIG = "    fun compute(\n        nodes: List<ConvoyNode>,\n        myCartId: String = \"\",\n        nowMs: Long = System.currentTimeMillis(),\n        leadLocked: Boolean = false\n    ): ConvoyState {"
NEW_COMPUTE_SIG = "    fun compute(\n        nodes: List<ConvoyNode>,\n        myCartId: String = \"\",\n        nowMs: Long = System.currentTimeMillis(),\n        leadLocked: Boolean = false,\n        lockedLeadId: String = \"\"\n    ): ConvoyState {"
assert OLD_COMPUTE_SIG in eng, "FAIL: compute() signature not found"
eng = eng.replace(OLD_COMPUTE_SIG, NEW_COMPUTE_SIG, 1)

# 2. Pass lockedLeadId to assignLeadTail
OLD_ASSIGN_CALL = "        val withRoles = assignLeadTail(sorted, leadLocked)"
NEW_ASSIGN_CALL = "        val withRoles = assignLeadTail(sorted, leadLocked, lockedLeadId)"
assert OLD_ASSIGN_CALL in eng, "FAIL: assignLeadTail() call not found"
eng = eng.replace(OLD_ASSIGN_CALL, NEW_ASSIGN_CALL, 1)

# 3. Fix assignLeadTail signature and locked branch using regex to handle unicode comments
eng = re.sub(
    r'fun assignLeadTail\(nodes: List<ConvoyNode>, leadLocked: Boolean = false\): List<ConvoyNode> \{(\s+val active = nodes\.filter \{ it\.status == ConvoyStatus\.ACTIVE \})(\s+if \(active\.isEmpty\(\)\) return nodes)(\s+val leadNode = if \(leadLocked\))\s+nodes\.firstOrNull \{ it\.isLead \}[^\n]*\n\s+\?: active\.minByOrNull \{ it\.convoyPosition \}[^\n]*\n\s+else\s+active\.filter \{ it\.speed_mph > 0\.5f \}[^\n]*\n\s+\.minByOrNull \{ it\.convoyPosition \}\s+\?: active\.minByOrNull \{ it\.convoyPosition \}[^\n]*',
    lambda m: (
        "fun assignLeadTail(nodes: List<ConvoyNode>, leadLocked: Boolean = false, lockedLeadId: String = \"\"): List<ConvoyNode> {"
        + m.group(1)
        + m.group(2)
        + "\n        val leadNode = if (leadLocked && lockedLeadId.isNotEmpty())\n"
        + "            nodes.firstOrNull { it.nodeId == lockedLeadId }    // locked \u2014 match by nodeId, one truth\n"
        + "                ?: active.minByOrNull { it.convoyPosition }     // fallback only if locked node lost signal\n"
        + "        else\n"
        + "            active.filter { it.speed_mph > 0.5f }               // must be moving to qualify\n"
        + "                .minByOrNull { it.convoyPosition }               // no fallback to stationary \u2014 null if nobody moving"
    ),
    eng
)

assert "lockedLeadId: String" in eng, "FAIL: assignLeadTail regex did not apply"

with open(ENG_PATH, "w", encoding="utf-8") as f:
    f.write(eng)
print(f"OK: {ENG_PATH} patched")

# ─────────────────────────────────────────────────────────────
# ConvoyViewModel.kt
# ─────────────────────────────────────────────────────────────
VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"

with open(VM_PATH, "r", encoding="utf-8") as f:
    vm = f.read()

# 1. Remove setExplicitLead entirely
OLD_EXPLICIT = "    fun setExplicitLead(nodeId: String) {\n        _leadLockedFlag = true\n        _leadLocked.value = true\n    }"
assert OLD_EXPLICIT in vm, "FAIL: setExplicitLead not found"
vm = vm.replace(OLD_EXPLICIT, "", 1)

# 2. recalcLead — clear flag only, no accumulator reset
OLD_RECALC = "    fun recalcLead() {\n        _leadLockedFlag = false\n        leadLockDistanceAccum = 0f\n        lastLeadLockLat = null\n        lastLeadLockLon = null\n        _leadLocked.value = false\n    }"
NEW_RECALC = "    fun recalcLead() {\n        _leadLockedFlag = false\n        _leadLocked.value = false\n        android.util.Log.d(\"ConvoyLead\", \"RECALC: lock cleared \u2014 FREE, re-elects next tick\")\n    }"
assert OLD_RECALC in vm, "FAIL: recalcLead not found"
vm = vm.replace(OLD_RECALC, NEW_RECALC, 1)

# 3. Tick — pass lockedLeadId from existing state, add logcat
OLD_COMPUTE = "        val state = ConvoyEngine.compute(\n            nodes = nodes,\n            myCartId = resolveMyCartId(),\n            nowMs = nowMs,\n            leadLocked = _leadLockedFlag\n        )\n        _convoyState.value = state"
NEW_COMPUTE = (
    "        val lockedLeadId = if (_leadLockedFlag) _convoyState.value.lead?.nodeId ?: \"\" else \"\"\n"
    "        val state = ConvoyEngine.compute(\n"
    "            nodes = nodes,\n"
    "            myCartId = resolveMyCartId(),\n"
    "            nowMs = nowMs,\n"
    "            leadLocked = _leadLockedFlag,\n"
    "            lockedLeadId = lockedLeadId\n"
    "        )\n"
    "        _convoyState.value = state\n"
    "        // \u2500\u2500 ConvoyLead tick log \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
    "        val lockStatus = if (_leadLockedFlag) \"LOCKED(${lockedLeadId})\" else \"FREE\"\n"
    "        android.util.Log.d(\"ConvoyLead\", \"TICK lockState=\$lockStatus lead=\${state.lead?.longName}(\${state.lead?.nodeId}) accum=\${leadLockDistanceAccum}\")\n"
    "        state.nodes.forEach { n ->\n"
    "            android.util.Log.d(\"ConvoyLead\", \"  NODE \${n.longName} id=\${n.nodeId} spd=\${n.speed_mph} isLead=\${n.isLead} isMyCart=\${n.isMyCart} pos=\${n.convoyPosition} status=\${n.status}\")\n"
    "        }"
)
assert OLD_COMPUTE in vm, "FAIL: ConvoyEngine.compute() call not found"
vm = vm.replace(OLD_COMPUTE, NEW_COMPUTE, 1)

# 4. Auto-lock — log when lock fires
OLD_AUTOLOCK = "                    if (leadLockDistanceAccum >= ConvoyConfig.LEAD_LOCK_DISTANCE_MILES) {\n                        _leadLockedFlag = true\n                        _leadLocked.value = true\n                    }"
NEW_AUTOLOCK = (
    "                    if (leadLockDistanceAccum >= ConvoyConfig.LEAD_LOCK_DISTANCE_MILES) {\n"
    "                        _leadLockedFlag = true\n"
    "                        _leadLocked.value = true\n"
    "                        android.util.Log.d(\"ConvoyLead\", \"AUTO-LOCK fired: locked on \${state.lead?.longName}(\${state.lead?.nodeId}) after \${ConvoyConfig.LEAD_LOCK_DISTANCE_MILES} miles\")\n"
    "                    }"
)
assert OLD_AUTOLOCK in vm, "FAIL: auto-lock block not found"
vm = vm.replace(OLD_AUTOLOCK, NEW_AUTOLOCK, 1)

with open(VM_PATH, "w", encoding="utf-8") as f:
    f.write(vm)
print(f"OK: {VM_PATH} patched")

print("\nAll patches applied. Run:")
print("  ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
