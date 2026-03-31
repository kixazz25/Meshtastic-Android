#!/usr/bin/env python3
"""
patch_lead_lock_fix.py
Lead lock fix — one truth, no duplication, ConvoyLead logcat logging.
Run from ~/Meshtastic-Android directory.
"""

# ─────────────────────────────────────────────────────────────
# ConvoyEngine.kt
# ─────────────────────────────────────────────────────────────
ENG_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyEngine.kt"

with open(ENG_PATH, "r") as f:
    eng = f.read()

# 1. Add lockedLeadId parameter to compute()
OLD_COMPUTE_SIG = """    fun compute(
        nodes: List<ConvoyNode>,
        myCartId: String = "",
        nowMs: Long = System.currentTimeMillis(),
        leadLocked: Boolean = false
    ): ConvoyState {"""
NEW_COMPUTE_SIG = """    fun compute(
        nodes: List<ConvoyNode>,
        myCartId: String = "",
        nowMs: Long = System.currentTimeMillis(),
        leadLocked: Boolean = false,
        lockedLeadId: String = ""
    ): ConvoyState {"""
assert OLD_COMPUTE_SIG in eng, "FAIL: compute() signature not found"
eng = eng.replace(OLD_COMPUTE_SIG, NEW_COMPUTE_SIG, 1)

# 2. Pass lockedLeadId to assignLeadTail
OLD_ASSIGN_CALL = "        val withRoles = assignLeadTail(sorted, leadLocked)"
NEW_ASSIGN_CALL = "        val withRoles = assignLeadTail(sorted, leadLocked, lockedLeadId)"
assert OLD_ASSIGN_CALL in eng, "FAIL: assignLeadTail() call not found"
eng = eng.replace(OLD_ASSIGN_CALL, NEW_ASSIGN_CALL, 1)

# 3. Fix assignLeadTail — match on nodeId when locked, no stationary fallback before lock
OLD_ASSIGN_FUN = """    fun assignLeadTail(nodes: List<ConvoyNode>, leadLocked: Boolean = false): List<ConvoyNode> {
        val active = nodes.filter { it.status == ConvoyStatus.ACTIVE }
        if (active.isEmpty()) return nodes
        val leadNode = if (leadLocked)
            nodes.firstOrNull { it.isLead }                    // locked — keep existing lead, no re-election
                ?: active.minByOrNull { it.convoyPosition }     // fallback only if no lead yet
        else
            active.filter { it.speed_mph > 0.5f }               // must be moving to qualify
                .minByOrNull { it.convoyPosition }
                ?: active.minByOrNull { it.convoyPosition }     // fallback if nobody moving"""
NEW_ASSIGN_FUN = """    fun assignLeadTail(nodes: List<ConvoyNode>, leadLocked: Boolean = false, lockedLeadId: String = ""): List<ConvoyNode> {
        val active = nodes.filter { it.status == ConvoyStatus.ACTIVE }
        if (active.isEmpty()) return nodes
        val leadNode = if (leadLocked && lockedLeadId.isNotEmpty())
            nodes.firstOrNull { it.nodeId == lockedLeadId }    // locked — match by nodeId, one truth
                ?: active.minByOrNull { it.convoyPosition }     // fallback only if locked node lost signal
        else
            active.filter { it.speed_mph > 0.5f }               // must be moving to qualify
                .minByOrNull { it.convoyPosition }               // no fallback to stationary — null if nobody moving"""
assert OLD_ASSIGN_FUN in eng, "FAIL: assignLeadTail() body not found"
eng = eng.replace(OLD_ASSIGN_FUN, NEW_ASSIGN_FUN, 1)

with open(ENG_PATH, "w") as f:
    f.write(eng)
print(f"OK: {ENG_PATH} patched")

# ─────────────────────────────────────────────────────────────
# ConvoyViewModel.kt
# ─────────────────────────────────────────────────────────────
VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"

with open(VM_PATH, "r") as f:
    vm = f.read()

# 1. Remove setExplicitLead entirely
OLD_EXPLICIT = """    fun setExplicitLead(nodeId: String) {
        _leadLockedFlag = true
        _leadLocked.value = true
    }"""
assert OLD_EXPLICIT in vm, "FAIL: setExplicitLead not found"
vm = vm.replace(OLD_EXPLICIT, "", 1)

# 2. recalcLead — clear flag only, no accumulator reset, lock re-fires on next tick
OLD_RECALC = """    fun recalcLead() {
        _leadLockedFlag = false
        leadLockDistanceAccum = 0f
        lastLeadLockLat = null
        lastLeadLockLon = null
        _leadLocked.value = false
    }"""
NEW_RECALC = """    fun recalcLead() {
        _leadLockedFlag = false
        _leadLocked.value = false
        android.util.Log.d("ConvoyLead", "RECALC: lock cleared — FREE, re-elects next tick")
    }"""
assert OLD_RECALC in vm, "FAIL: recalcLead not found"
vm = vm.replace(OLD_RECALC, NEW_RECALC, 1)

# 3. Tick — pass lockedLeadId from existing state, add logcat
OLD_COMPUTE = """        val state = ConvoyEngine.compute(
            nodes = nodes,
            myCartId = resolveMyCartId(),
            nowMs = nowMs,
            leadLocked = _leadLockedFlag
        )
        _convoyState.value = state"""
NEW_COMPUTE = """        val lockedLeadId = if (_leadLockedFlag) _convoyState.value.lead?.nodeId ?: "" else ""
        val state = ConvoyEngine.compute(
            nodes = nodes,
            myCartId = resolveMyCartId(),
            nowMs = nowMs,
            leadLocked = _leadLockedFlag,
            lockedLeadId = lockedLeadId
        )
        _convoyState.value = state
        // ── ConvoyLead tick log ──────────────────────────────────────────
        val lockStatus = if (_leadLockedFlag) "LOCKED(${lockedLeadId})" else "FREE"
        android.util.Log.d("ConvoyLead", "TICK lockState=$lockStatus lead=${state.lead?.longName}(${state.lead?.nodeId}) accum=${leadLockDistanceAccum}")
        state.nodes.forEach { n ->
            android.util.Log.d("ConvoyLead", "  NODE ${n.longName} id=${n.nodeId} spd=${n.speed_mph} isLead=${n.isLead} isMyCart=${n.isMyCart} pos=${n.convoyPosition} status=${n.status}")
        }"""
assert OLD_COMPUTE in vm, "FAIL: ConvoyEngine.compute() call not found"
vm = vm.replace(OLD_COMPUTE, NEW_COMPUTE, 1)

# 4. Auto-lock — log when lock fires
OLD_AUTOLOCK = """                    if (leadLockDistanceAccum >= ConvoyConfig.LEAD_LOCK_DISTANCE_MILES) {
                        _leadLockedFlag = true
                        _leadLocked.value = true
                    }"""
NEW_AUTOLOCK = """                    if (leadLockDistanceAccum >= ConvoyConfig.LEAD_LOCK_DISTANCE_MILES) {
                        _leadLockedFlag = true
                        _leadLocked.value = true
                        android.util.Log.d("ConvoyLead", "AUTO-LOCK fired: locked on ${state.lead?.longName}(${state.lead?.nodeId}) after ${ConvoyConfig.LEAD_LOCK_DISTANCE_MILES} miles")
                    }"""
assert OLD_AUTOLOCK in vm, "FAIL: auto-lock block not found"
vm = vm.replace(OLD_AUTOLOCK, NEW_AUTOLOCK, 1)

with open(VM_PATH, "w") as f:
    f.write(vm)
print(f"OK: {VM_PATH} patched")

print("\nAll patches applied. Run:")
print("  ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
