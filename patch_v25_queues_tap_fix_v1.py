#!/usr/bin/env python3
"""
patch_v25_queues_tap_fix_v1.py

PROBLEM
  Convoy QUEUES button (ConvoyScreen.kt) does nothing on tap. The button has
  both a pointerInput { detectDragGestures {...} } (drag-to-move) AND a
  .clickable { queuesOpen = !queuesOpen }. detectDragGestures consumes the
  pointer events, so .clickable never sees the tap -> button drags but won't tap.

FIX
  Handle the tap inside a pointerInput (detectTapGestures) so tap + drag
  coexist (two separate pointerInput blocks each get their own coroutine;
  Compose disambiguates tap vs drag by movement). Remove the dead .clickable.

  Edit 1: replace the .clickable line with a detectTapGestures pointerInput.
  Edit 2: ensure the detectTapGestures import is present.

  ConvoyScreen.kt is CRLF -> normalize on read, write back LF (git restores CRLF).
"""

import io
import sys

SCREEN = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

# --- Edit 1: swap .clickable for a detectTapGestures pointerInput ---
# Anchor: the drag pointerInput block immediately followed by the .clickable line.
CLICK_ANCHOR = (
    '                .pointerInput(Unit) {\n'
    '                    detectDragGestures { change, dragAmount ->\n'
    '                        change.consume()\n'
    '                        queuesOffsetX += dragAmount.x\n'
    '                        queuesOffsetY += dragAmount.y\n'
    '                    }\n'
    '                }\n'
    '                .clickable { queuesOpen = !queuesOpen },\n'
)
CLICK_REPLACE = (
    '                .pointerInput(Unit) {\n'
    '                    detectDragGestures { change, dragAmount ->\n'
    '                        change.consume()\n'
    '                        queuesOffsetX += dragAmount.x\n'
    '                        queuesOffsetY += dragAmount.y\n'
    '                    }\n'
    '                }\n'
    '                // FIX 2026-06-02: tap was consumed by detectDragGestures; handle tap\n'
    '                // in its own pointerInput so tap + drag coexist (was a dead .clickable).\n'
    '                .pointerInput(Unit) {\n'
    '                    detectTapGestures { queuesOpen = !queuesOpen }\n'
    '                },\n'
)

# --- Edit 2: ensure detectTapGestures import ---
IMPORT_DRAG = "import androidx.compose.foundation.gestures.detectDragGestures\n"
IMPORT_TAP = "import androidx.compose.foundation.gestures.detectTapGestures\n"


def patch_screen(text):
    if "detectTapGestures { queuesOpen" in text:
        raise SystemExit("ERROR: already patched (detectTapGestures tap present).")

    # Edit 1
    n = text.count(CLICK_ANCHOR)
    if n == 0:
        raise SystemExit("ERROR [click]: anchor not found. No edit applied.")
    if n > 1:
        raise SystemExit(f"ERROR [click]: anchor found {n}x (expected 1). No edit.")
    text = text.replace(CLICK_ANCHOR, CLICK_REPLACE, 1)

    # Edit 2: add import if missing. Prefer placing right after the drag import.
    if IMPORT_TAP not in text:
        if IMPORT_DRAG in text:
            text = text.replace(IMPORT_DRAG, IMPORT_DRAG + IMPORT_TAP, 1)
        else:
            # Fallback: add after the first import line in the file.
            idx = text.find("\nimport ")
            if idx == -1:
                raise SystemExit("ERROR [import]: no import block found to anchor.")
            insat = text.find("\n", idx + 1) + 1
            text = text[:insat] + IMPORT_TAP + text[insat:]
    return text


def run(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        orig = f.read()
    had_crlf = "\r\n" in orig
    norm = orig.replace("\r\n", "\n")
    new = patch_screen(norm)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new)
    print(f"PATCHED: {path}" + ("  (normalized CRLF->LF)" if had_crlf else ""))


def selftest():
    sample = (
        "import androidx.compose.foundation.gestures.detectDragGestures\n"
        "import androidx.compose.runtime.remember\n"
        "// ... button ...\n"
        + CLICK_ANCHOR
        + "            shape = RoundedCornerShape(6.dp),\n"
    )
    out = patch_screen(sample)
    assert "detectTapGestures { queuesOpen = !queuesOpen }" in out, "tap not added"
    assert ".clickable { queuesOpen" not in out, "dead .clickable still present"
    assert IMPORT_TAP in out, "import not added"
    # idempotency
    try:
        patch_screen(out)
    except SystemExit:
        pass
    else:
        raise AssertionError("double-apply not refused")
    # import-already-present case
    sample2 = IMPORT_TAP + sample
    out2 = patch_screen(sample2)
    assert out2.count(IMPORT_TAP) == 1, "import duplicated"
    print("SELFTEST PASS")


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    run(SCREEN)
    print("QUEUES tap fix applied. Review with git --no-pager diff before building.")


if __name__ == "__main__":
    main()
