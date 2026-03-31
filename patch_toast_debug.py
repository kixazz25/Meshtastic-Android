#!/usr/bin/env python3
"""
patch_toast_debug.py
Add debug toast every 60 ticks showing lock state and lead cart name.
Run from ~/Meshtastic-Android directory.
"""

VM_PATH = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"

with open(VM_PATH, "r", encoding="utf-8") as f:
    txt = f.read()

# Add toast counter after _leadLockedFlag declaration
OLD_FLAG = "    private var _leadLockedFlag: Boolean = false"
NEW_FLAG = "    private var _leadLockedFlag: Boolean = false\n    private var _debugToastTick: Int = 0"
assert OLD_FLAG in txt, "FAIL: _leadLockedFlag not found"
txt = txt.replace(OLD_FLAG, NEW_FLAG, 1)

# Add toast block before lead lock accumulator
OLD_ANCHOR = "        // \u2500\u2500 Lead lock \u2014 accumulate my cart distance, lock after 1/4 mile \u2500\u2500\u2500\u2500"
NEW_TOAST = (
    "        // \u2500\u2500 Debug toast every 60 ticks \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
    "        _debugToastTick++\n"
    "        if (_debugToastTick >= 60) {\n"
    "            _debugToastTick = 0\n"
    "            val leadName = state.lead?.longName ?: \"NONE\"\n"
    "            val msg = if (_leadLockedFlag) \"LOCKED: $leadName\" else \"FREE: $leadName\"\n"
    "            android.os.Handler(android.os.Looper.getMainLooper()).post {\n"
    "                android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()\n"
    "            }\n"
    "        }\n"
    "        // \u2500\u2500 Lead lock \u2014 accumulate my cart distance, lock after 1/4 mile \u2500\u2500\u2500\u2500"
)
assert OLD_ANCHOR in txt, "FAIL: lead lock anchor not found"
txt = txt.replace(OLD_ANCHOR, NEW_TOAST, 1)

with open(VM_PATH, "w", encoding="utf-8") as f:
    f.write(txt)
print("OK: toast debug added")
print("\nRun: ./gradlew assembleGoogleDebug 2>&1 | grep -E '^e:|BUILD'")
