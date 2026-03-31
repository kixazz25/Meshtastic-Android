#!/usr/bin/env python3
"""
patch_lead_by_name.py
Replace lead election with longName="Lead" rule.
Remove all lock machinery — no flags, no accumulator, no distance.
Tail calculation unchanged.
Run from ~/Meshtastic-Android directory.
"""

# ─────────────────────────────────────────────────────────────
# ConvoyEngine.kt — replace assignLeadTail
# ─────────────────────────────────────────────────────────────
ENG_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyEngine.kt"

with open(ENG_PATH, "r", encoding="utf-8") as f:
    eng = f.read()

# 1. Remove lockedLeadId from compute() signature
OLD_SIG = "        leadLocked: Boolean = false,\n        lockedLeadId: String = \"\"\n    ): ConvoyState {"
NEW_SIG = "    ): ConvoyState {"
assert OLD_SIG in eng, "FAIL: compute() signature not found"
eng = eng.replace(OLD_SIG, NEW_SIG, 1)

# 2. Remove leadLocked from compute() signature
OLD_SIG2 = "        leadLocked: Boolean = false,\n    ): ConvoyState {"
NEW_SIG2 = "    ): ConvoyState {"
if OLD_SIG2 in eng:
    eng = eng.replace(OLD_SIG2, NEW_SIG2, 1)

# Clean up any remaining leadLocked param
eng = eng.replace("        leadLocked: Boolean = false\n    ): ConvoyState {", "    ): ConvoyState {")

# 3. Remove lockedLeadId passthrough to assignLeadTail
OLD_CALL = "        val withRoles = assignLeadTail(sorted, leadLocked, lockedLeadId)"
NEW_CALL = "        val withRoles = assignLeadTail(sorted)"
if OLD_CALL in eng:
    eng = eng.replace(OLD_CALL, NEW_CALL, 1)
else:
    eng = eng.replace("        val withRoles = assignLeadTail(sorted, leadLocked)", "        val withRoles = assignLeadTail(sorted)")

# 4. Replace assignLeadTail with name-based election
import re
eng = re.sub(
    r'fun assignLeadTail\(nodes: List<ConvoyNode>[^)]*\): List<ConvoyNode> \{.*?(?=\n    fun )',
    '''fun assignLeadTail(nodes: List<ConvoyNode>): List<ConvoyNode> {
        val active = nodes.filter { it.status == ConvoyStatus.ACTIVE }
        if (active.isEmpty()) return nodes
        // Lead = node with callsign "Lead" or "lead" — set on radio, never auto-elected
        val leadNode = nodes.firstOrNull { it.callsign.equals("Lead", ignoreCase = true) }
        val tailNode = active.maxByOrNull { it.convoyPosition }
        return nodes.map { node ->
            node.copy(
                isLead = node.nodeId == leadNode?.nodeId,
                isTail = node.nodeId == tailNode?.nodeId,
                role = when {
                    node.nodeId == leadNode?.nodeId -> "Lead"
                    node.nodeId == tailNode?.nodeId -> "Tail"
                    node.isMyCart -> "My Cart"
                    else -> "Convoy"
                }
            )
        }
    }

    ''',
    eng,
    flags=re.DOTALL
)

with open(ENG_PATH, "w", encoding="utf-8") as f:
    f.write(eng)
print(f"OK: {ENG_PATH} patched")

# ─────────────────────────────────────────────────────────────
# ConvoyViewModel.kt — remove lock machinery
# ─────────────────────────────────────────────────────────────
VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"

with open(VM_PATH, "r", encoding="utf-8") as f:
    vm = f.read()

# 1. Remove lock state variables
for old in [
    "    private var _leadLockedFlag: Boolean = false\n    private var _lockedLeadId: String = \"\"\n",
    "    private var _leadLockedFlag: Boolean = false\n",
    "    private var _lockedLeadId: String = \"\"\n",
    "    private var leadLockDistanceAccum: Float = 0f\n",
    "    private var lastLeadLockLat: Double? = null\n",
    "    private var lastLeadLockLon: Double? = null\n",
    "    private var _debugToastTick: Int = 0\n",
]:
    vm = vm.replace(old, "", 1)

# 2. Remove _leadLocked StateFlow
vm = vm.replace(
    "    private val _leadLocked = MutableStateFlow(false)\n    val leadLocked: StateFlow<Boolean> = _leadLocked.asStateFlow()\n",
    ""
)

# 3. Remove recalcLead and startGroupTrack lock resets
old_recalc = """    fun recalcLead() {
        _leadLockedFlag = false
        _lockedLeadId = \"\"
        _leadLocked.value = false
        android.util.Log.d("ConvoyLead", "RECALC: lock cleared \u2014 FREE, re-elects next tick")
    }"""
vm = vm.replace(old_recalc, "    fun recalcLead() { /* lead set by callsign — no lock to clear */ }", 1)

# 4. Clean startGroupTrack lock resets
vm = vm.replace("        _leadLockedFlag = false\n", "")
vm = vm.replace("        leadLockDistanceAccum = 0f\n", "")
vm = vm.replace("        lastLeadLockLat = null\n", "")
vm = vm.replace("        lastLeadLockLon = null\n", "")
vm = vm.replace("        _leadLocked.value = false\n", "")

# 5. Fix compute() call — remove leadLocked and lockedLeadId params
old_compute = (
    "        val lockedLeadId = if (_leadLockedFlag) _lockedLeadId else \"\"\n"
    "        val state = ConvoyEngine.compute(\n"
    "            nodes = nodes,\n"
    "            myCartId = resolveMyCartId(),\n"
    "            nowMs = nowMs,\n"
    "            leadLocked = _leadLockedFlag,\n"
    "            lockedLeadId = lockedLeadId\n"
    "        )"
)
new_compute = (
    "        val state = ConvoyEngine.compute(\n"
    "            nodes = nodes,\n"
    "            myCartId = resolveMyCartId(),\n"
    "            nowMs = nowMs\n"
    "        )"
)
assert old_compute in vm, "FAIL: compute() call not found"
vm = vm.replace(old_compute, new_compute, 1)

# 6. Remove entire lock accumulator block
import re
vm = re.sub(
    r'        // \u2500\u2500 Lead lock \u2014 accumulate my cart distance.*?(?=        // Accumulate route trail)',
    "",
    vm,
    flags=re.DOTALL
)

# 7. Fix tick log — remove lockStatus references
vm = re.sub(r'        val lockStatus = .*?\n', '', vm)
vm = re.sub(r'        android\.util\.Log\.d\("ConvoyLead", "TICK lockState=.*?\n', '', vm)
vm = re.sub(r'        android\.util\.Log\.d\("ConvoyLead", "AUTO-LOCK.*?\n', '', vm)

# 8. Fix debug toast — remove lock references  
vm = re.sub(r'        // \u2500\u2500 Debug toast.*?        \}\n', '', vm, flags=re.DOTALL)

with open(VM_PATH, "w", encoding="utf-8") as f:
    f.write(vm)
print(f"OK: {VM_PATH} patched")

print("\nAll patches applied. Run:")
print("  ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
