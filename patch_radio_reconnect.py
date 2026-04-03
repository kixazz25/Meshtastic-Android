#!/usr/bin/env python3
"""
patch_radio_reconnect.py
Add radio inactivity detection and reconnect button.
- Detects 5 min of no GPS updates from my cart
- Shows banner in ConvoyScreen with RECONNECT button
- Calls Bluetooth toggle reconnect on tap
Run from ~/Meshtastic-Android directory.
"""

VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"
SC_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

# ─────────────────────────────────────────────────────────────
# ConvoyViewModel.kt
# ─────────────────────────────────────────────────────────────
with open(VM_PATH, "r", encoding="utf-8") as f:
    vm = f.read()

# 1. Add inactivity StateFlow and last GPS time tracker after autoPan
OLD_ANCHOR = "    fun setAutoPan(pan: Boolean) { _autoPan.value = pan }"
NEW_ANCHOR = (
    "    fun setAutoPan(pan: Boolean) { _autoPan.value = pan }\n"
    "    // ── Radio inactivity detection ────────────────────────────────────\n"
    "    private val _radioInactive = MutableStateFlow(false)\n"
    "    val radioInactive: StateFlow<Boolean> = _radioInactive.asStateFlow()\n"
    "    private var lastMyCartSeenMs: Long = 0L\n"
    "    private val RADIO_INACTIVE_MS = 5 * 60 * 1000L // 5 minutes\n"
    "    fun reconnectRadio() {\n"
    "        val bluetoothManager = appContext.getSystemService(android.content.Context.BLUETOOTH_SERVICE)\n"
    "            as? android.bluetooth.BluetoothManager\n"
    "        val adapter = bluetoothManager?.adapter ?: return\n"
    "        viewModelScope.launch {\n"
    "            try {\n"
    "                @Suppress(\"DEPRECATION\") adapter.disable()\n"
    "                kotlinx.coroutines.delay(3000)\n"
    "                @Suppress(\"DEPRECATION\") adapter.enable()\n"
    "                _radioInactive.value = false\n"
    "            } catch (e: Exception) {\n"
    "                android.util.Log.e(\"ConvoyReconnect\", \"Reconnect failed: ${e.message}\")\n"
    "            }\n"
    "        }\n"
    "    }"
)
assert OLD_ANCHOR in vm, "FAIL: autoPan anchor not found"
vm = vm.replace(OLD_ANCHOR, NEW_ANCHOR, 1)

# 2. Add inactivity check in tick loop after _convoyState.value = state
OLD_TICK = "        _convoyState.value = state\n
        // Accumulate route trail"
NEW_TICK = (
    "        _convoyState.value = state\n"
    "        // ── Radio inactivity check ───────────────────────────────────────\n"
    "        val myCart = state.nodes.firstOrNull { it.isMyCart }\n"
    "        if (myCart != null && myCart.lastSeenMs > 0L) {\n"
    "            if (myCart.lastSeenMs != lastMyCartSeenMs) {\n"
    "                lastMyCartSeenMs = myCart.lastSeenMs\n"
    "                _radioInactive.value = false\n"
    "            } else if (lastMyCartSeenMs > 0L && (System.currentTimeMillis() - lastMyCartSeenMs) > RADIO_INACTIVE_MS) {\n"
    "                _radioInactive.value = true\n"
    "            }\n"
    "        }\n"
    "        // Accumulate route trail"
)
assert OLD_TICK in vm, "FAIL: tick anchor not found"
vm = vm.replace(OLD_TICK, NEW_TICK, 1)

with open(VM_PATH, "w", encoding="utf-8") as f:
    f.write(vm)
print(f"OK: {VM_PATH} patched")

# ─────────────────────────────────────────────────────────────
# ConvoyScreen.kt — add banner below map
# ─────────────────────────────────────────────────────────────
with open(SC_PATH, "r", encoding="utf-8") as f:
    sc = f.read()

# Add radioInactive state collection near other state collections
OLD_COLLECT = "    val autoPan by viewModel.autoPan.collectAsStateWithLifecycle()"
NEW_COLLECT = (
    "    val autoPan by viewModel.autoPan.collectAsStateWithLifecycle()\n"
    "    val radioInactive by viewModel.radioInactive.collectAsStateWithLifecycle()"
)
assert OLD_COLLECT in sc, "FAIL: autoPan collect not found"
sc = sc.replace(OLD_COLLECT, NEW_COLLECT, 1)

# Add banner above the HUD row — find the HUD composable call
OLD_HUD = "        // ── HUD strip"
NEW_HUD = (
    "        // ── Radio inactivity banner ───────────────────────────────────\n"
    "        if (radioInactive) {\n"
    "            androidx.compose.foundation.layout.Box(\n"
    "                modifier = androidx.compose.ui.Modifier\n"
    "                    .fillMaxWidth()\n"
    "                    .padding(horizontal = 12.dp, vertical = 4.dp)\n"
    "                    .clip(RoundedCornerShape(10.dp))\n"
    "                    .background(Color(0xFF1A7A1A))\n"
    "                    .clickable { viewModel.reconnectRadio() },\n"
    "                contentAlignment = androidx.compose.ui.Alignment.Center\n"
    "            ) {\n"
    "                Text(\n"
    "                    text = \"RADIO INACTIVE — TAP TO RECONNECT\",\n"
    "                    color = Color.White,\n"
    "                    fontSize = 14.sp,\n"
    "                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,\n"
    "                    modifier = androidx.compose.ui.Modifier.padding(vertical = 12.dp)\n"
    "                )\n"
    "            }\n"
    "        }\n"
    "        // ── HUD strip"
)
assert OLD_HUD in sc, "FAIL: HUD strip anchor not found"
sc = sc.replace(OLD_HUD, NEW_HUD, 1)

with open(SC_PATH, "w", encoding="utf-8") as f:
    f.write(sc)
print(f"OK: {SC_PATH} patched")

print("\nRun: ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
