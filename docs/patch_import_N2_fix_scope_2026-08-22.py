#!/usr/bin/env python3
"""
patch_import_N2_fix_scope_2026-08-22.py

FIXES PATCH N, which did not compile:

    ConvoyTrackOps.kt:644:47 Unresolved reference 'text'
    ConvoyTrackOps.kt:662:41 Unresolved reference 'text'

MY ERROR. The 2026-06-02 bypass comment read "was: parseGpxWaypoints(text)" and
I restored it verbatim. But `text` is the variable in importTrackFile (:202) --
a DIFFERENT function. The bypass sits in importGpxAllArtifacts (:487), where the
whole-file content is `fullText` (:533).

The comment recorded what USED TO compile, in a function that has since been
split. ⭐ A three-month-old comment is a record, not a specification -- verify
the identifiers are still in scope before restoring from one.

`fullText` is also the RIGHT variable on the merits: the waypoint and route
parsers need the WHOLE FILE, not the per-track `singleGpx` slice built at :571.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: GPXIMPORT-2026-08-22N2
"""

import sys, os, shutil, datetime

MARKER = "GPXIMPORT-2026-08-22N2"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyTrackOps.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── N1: waypoints ───────────────────────────────────────────────────
N1_OLD = '''            val waypoints = parseGpxWaypoints(text)'''

N1_NEW = '''            val waypoints = parseGpxWaypoints(fullText)'''

# ── N2: routes ──────────────────────────────────────────────────────
N2_OLD = '''            val routes = parseGpxRoutes(text)'''

N2_NEW = '''            val routes = parseGpxRoutes(fullText)'''

EDITS = [
    ("waypoints: text -> fullText", N1_OLD, N1_NEW),
    ("routes: text -> fullText",    N2_OLD, N2_NEW),
]


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target); return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if MARKER in src:
        print("Already applied (%s present)." % MARKER); return 0

    problems = []
    for name, old, _new in EDITS:
        n = src.count(old)
        if n != 1:
            problems.append("  %-26s found %d times (need 1)" % (name, n))
        else:
            print("  OK  %-26s anchor matched" % name)
    if problems:
        print("\nABORT -- no write:"); print("\n".join(problems)); return 2

    out = src
    for _name, old, new in EDITS:
        out = out.replace(old, new, 1)

    if "parseGpxWaypoints(text)" in out or "parseGpxRoutes(text)" in out:
        print("\nABORT -- a stale `text` reference survives. No write."); return 5
    print("  OK  no stale `text` references")
    for good in ("parseGpxWaypoints(fullText)", "parseGpxRoutes(fullText)"):
        if out.count(good) != 1:
            print("\nABORT -- %s appears %d times (need 1). No write."
                  % (good, out.count(good))); return 6
    print("  OK  both parsers read fullText exactly once")

    print("\nMarker occurrences: %d (expect 2)" % out.count(MARKER))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(BACKUP_DIR, "ConvoyTrackOps.kt.bak_%s" % stamp)
    shutil.copy2(target, backup)
    with open(target, "w", encoding="utf-8") as f:
        f.write(out)
    print("Backup: %s" % backup)
    print("APPLIED: %s" % target)
    print("\n⛔ TEST ON DROID 2 FIRST. insertRoute writes permanent rows and")
    print("   Droid 1 holds 89K trails.")
    print("\nCompile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
