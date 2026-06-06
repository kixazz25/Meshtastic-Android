#!/usr/bin/env python3
"""
patch_v25_streaming_perf_v4.py

PURPOSE
  One-line performance fix for the streaming track importer.

  The streaming loop checked `block.contains("</trk>")` on EVERY line read.
  `block` is the accumulating track buffer, so this re-scans the whole
  growing buffer once per line => O(n^2) per track. High-point-count tracks
  took MINUTES (observed: 2-3.5 min/track on the 87-track onX file) purely
  from re-scanning, even though memory was fine.

  FIX: check the CURRENT line `ln` for the closing tag instead of the whole
  block. The closing line is appended to `block` BEFORE this check (so the
  block still holds the complete track when processed), and `<trk>`+`</trk>`
  on the same line still fires correctly (inTrk is set in the same iteration
  before the check). O(1) per line => each track is linear. Big tracks go
  from minutes to seconds.

  FILE: ConvoyTrackOps.kt (LF line endings — no CRLF handling needed).
  Single anchored replacement; aborts cleanly if the anchor isn't found.
"""

import io
import sys

OPS = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyTrackOps.kt"

ANCHOR = '                    if (inTrk && block.contains("</trk>")) {'
REPLACE = (
    '                    if (inTrk && ln.contains("</trk>")) {'
    '  // PERF 2026-06-02: check current line, not whole block (was O(n^2)/track)'
)


def patch_ops(text):
    if 'ln.contains("</trk>")' in text:
        raise SystemExit("ERROR: already patched (ln.contains present).")
    n = text.count(ANCHOR)
    if n == 0:
        raise SystemExit("ERROR: anchor not found. No edit applied.")
    if n > 1:
        raise SystemExit(f"ERROR: anchor found {n}x (expected 1). No edit applied.")
    return text.replace(ANCHOR, REPLACE, 1)


def run(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        orig = f.read()
    new = patch_ops(orig)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new)
    print(f"PATCHED: {path}")


def selftest():
    sample = (
        '                    } else {\n'
        '                        block.append(ln).append("\\n")\n'
        '                    }\n'
        '                    if (inTrk && block.contains("</trk>")) {\n'
        '                        inTrk = false\n'
    )
    out = patch_ops(sample)
    assert 'ln.contains("</trk>")' in out, "fix not applied"
    assert 'block.contains("</trk>")' not in out, "old check still present"
    # idempotency
    try:
        patch_ops(out)
    except SystemExit:
        pass
    else:
        raise AssertionError("double-apply not refused")
    print("SELFTEST PASS")


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    run(OPS)
    print("Perf fix applied. Review with git --no-pager diff before building.")


if __name__ == "__main__":
    main()
