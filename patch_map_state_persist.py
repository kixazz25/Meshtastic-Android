#!/usr/bin/env python3
"""
patch_map_state_persist.py
Move isOfflineMode, mapTypeLabel, isLocalTiles to ViewModel StateFlows
so they survive Compose recomposition.
Run from ~/Meshtastic-Android directory.
"""

VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"
SC_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

# ─────────────────────────────────────────────────────────────
# ConvoyViewModel.kt — add three StateFlows
# ─────────────────────────────────────────────────────────────
with open(VM_PATH, "r", encoding="utf-8") as f:
    vm = f.read()

OLD_ANCHOR = "    private val _trackLeadOnly = MutableStateFlow(true)\n    val trackLeadOnly: StateFlow<Boolean> = _trackLeadOnly.asStateFlow()"
NEW_ANCHOR = (
    "    private val _trackLeadOnly = MutableStateFlow(true)\n"
    "    val trackLeadOnly: StateFlow<Boolean> = _trackLeadOnly.asStateFlow()\n"
    "    // ── Map display state — persists across recomposition ────────────────\n"
    "    private val _isOfflineMode = MutableStateFlow(false)\n"
    "    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()\n"
    "    fun setOfflineMode(offline: Boolean) { _isOfflineMode.value = offline }\n"
    "    private val _mapTypeLabel = MutableStateFlow(\"SAT\")\n"
    "    val mapTypeLabel: StateFlow<String> = _mapTypeLabel.asStateFlow()\n"
    "    fun setMapTypeLabel(label: String) { _mapTypeLabel.value = label }\n"
    "    private val _isLocalTiles = MutableStateFlow(false)\n"
    "    val isLocalTiles: StateFlow<Boolean> = _isLocalTiles.asStateFlow()\n"
    "    fun setLocalTiles(local: Boolean) { _isLocalTiles.value = local }"
)
assert OLD_ANCHOR in vm, "FAIL: trackLeadOnly anchor not found"
vm = vm.replace(OLD_ANCHOR, NEW_ANCHOR, 1)

with open(VM_PATH, "w", encoding="utf-8") as f:
    f.write(vm)
print(f"OK: {VM_PATH} patched")

# ─────────────────────────────────────────────────────────────
# ConvoyScreen.kt — replace remember with collectAsState
# ─────────────────────────────────────────────────────────────
with open(SC_PATH, "r", encoding="utf-8") as f:
    sc = f.read()

# Replace isOfflineMode remember with ViewModel StateFlow
sc = sc.replace(
    "    var isOfflineMode by remember { mutableStateOf(false) }",
    "    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()"
)

# Replace mapTypeLabel remember with ViewModel StateFlow
sc = sc.replace(
    "    var mapTypeLabel by remember { mutableStateOf(\"SAT\") }",
    "    val mapTypeLabel by viewModel.mapTypeLabel.collectAsStateWithLifecycle()"
)

# Replace isLocalTiles remember with ViewModel StateFlow
sc = sc.replace(
    "    var isLocalTiles by remember { mutableStateOf(false) }",
    "    val isLocalTiles by viewModel.isLocalTiles.collectAsStateWithLifecycle()"
)

# Fix isOfflineMode assignments — replace = with viewModel.setOfflineMode()
sc = sc.replace(
    "                    isOfflineMode = goOffline\n                    if (goOffline) {",
    "                    viewModel.setOfflineMode(goOffline)\n                    if (goOffline) {"
)

# Fix mapTypeLabel assignments
import re
sc = re.sub(
    r'mapTypeLabel = "([^"]+)"',
    lambda m: f'viewModel.setMapTypeLabel("{m.group(1)}")',
    sc
)

# Fix isLocalTiles assignments
sc = re.sub(
    r'isLocalTiles = (true|false)',
    lambda m: f'viewModel.setLocalTiles({m.group(1)})',
    sc
)

with open(SC_PATH, "w", encoding="utf-8") as f:
    f.write(sc)
print(f"OK: {SC_PATH} patched")

print("\nRun: ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
