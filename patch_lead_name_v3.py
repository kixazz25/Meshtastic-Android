#!/usr/bin/env python3
"""
patch_lead_name_v3.py
Minimal surgical patch:
1. ConvoyEngine: replace assignLeadTail with callsign-based election
2. ConvoyViewModel: remove lock machinery, keep all other code intact
Run from ~/Meshtastic-Android directory.
"""
import re

# ─────────────────────────────────────────────────────────────
# ConvoyEngine.kt
# ─────────────────────────────────────────────────────────────
ENG_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyEngine.kt"
with open(ENG_PATH, "r", encoding="utf-8") as f:
    eng = f.read()

# Remove leadLocked param from compute() signature
eng = eng.replace(
    "        leadLocked: Boolean = false\n    ): ConvoyState {",
    "    ): ConvoyState {"
)

# Remove leadLocked from assignLeadTail call
eng = eng.replace(
    "        val withRoles = assignLeadTail(sorted, leadLocked)",
    "        val withRoles = assignLeadTail(sorted)"
)

# Replace assignLeadTail body using regex — avoids unicode comment matching
eng = re.sub(
    r'(    fun assignLeadTail\(nodes: List<ConvoyNode>)[^)]*(\): List<ConvoyNode> \{).*?(    fun computeSpan)',
    r'''\1\2
        val active = nodes.filter { it.status == ConvoyStatus.ACTIVE }
        if (active.isEmpty()) return nodes
        // Lead = node with callsign "Lead" (case-insensitive) — set on radio hardware
        val leadNode = nodes.firstOrNull { it.callsign.equals("Lead", ignoreCase = true) }
        // Tail = rearmost active node, excluding lead
        val tailNode = active
            .filter { it.nodeId != leadNode?.nodeId }
            .maxByOrNull { it.convoyPosition }
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
    \3''',
    eng,
    flags=re.DOTALL
)

with open(ENG_PATH, "w", encoding="utf-8") as f:
    f.write(eng)
print(f"OK: {ENG_PATH} patched")

# ─────────────────────────────────────────────────────────────
# ConvoyViewModel.kt  
# ─────────────────────────────────────────────────────────────
VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"
with open(VM_PATH, "r", encoding="utf-8") as f:
    vm = f.read()

# 1. Remove lock state variables block
vm = vm.replace(
    "    // \u2500\u2500 Lead lock state \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n    private var _leadLockedFlag: Boolean = false\n    private var leadLockDistanceAccum: Float = 0f\n    private var lastLeadLockLat: Double? = null\n    private var lastLeadLockLon: Double? = null\n    private val _leadLocked = MutableStateFlow(false)\n    val leadLocked: StateFlow<Boolean> = _leadLocked.asStateFlow()\n",
    ""
)

# 2. Clean startGroupTrack — remove lock resets
vm = vm.replace("        _leadLockedFlag = false\n", "")
vm = vm.replace("        leadLockDistanceAccum = 0f\n", "")
vm = vm.replace("        lastLeadLockLat = null\n", "")
vm = vm.replace("        lastLeadLockLon = null\n", "")
vm = vm.replace("        _leadLocked.value = false\n", "")

# 3. Remove setExplicitLead
vm = re.sub(
    r'    // \u2500\u2500 Lead lock overrides.*?fun setExplicitLead\(nodeId: String\) \{.*?\}\n',
    '',
    vm,
    flags=re.DOTALL
)

# 4. Simplify recalcLead
vm = re.sub(
    r'    fun recalcLead\(\) \{.*?\}',
    '    fun recalcLead() { /* lead determined by callsign on radio */ }',
    vm,
    flags=re.DOTALL
)

# 5. Remove leadLocked from compute() call
vm = vm.replace(
    "        val state = ConvoyEngine.compute(\n            nodes = nodes,\n            myCartId = resolveMyCartId(),\n            nowMs = nowMs,\n            leadLocked = _leadLockedFlag\n        )",
    "        val state = ConvoyEngine.compute(\n            nodes = nodes,\n            myCartId = resolveMyCartId(),\n            nowMs = nowMs\n        )"
)

# 6. Remove lock accumulator block
vm = re.sub(
    r'        // \u2500\u2500 Lead lock \u2014 accumulate my cart distance.*?(?=        // Accumulate route trail)',
    '',
    vm,
    flags=re.DOTALL
)

with open(VM_PATH, "w", encoding="utf-8") as f:
    f.write(vm)
print(f"OK: {VM_PATH} patched")

print("\nRun: ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
