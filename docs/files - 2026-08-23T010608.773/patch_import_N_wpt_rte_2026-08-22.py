#!/usr/bin/env python3
"""
patch_import_N_wpt_rte_2026-08-22.py

RE-ENABLE GPX WAYPOINT AND ROUTE IMPORT.

Testers could not import routes. Cause found by reading the code rather than
guessing: ConvoyTrackOps.kt:637 and :651 carry a TEMP BYPASS from 2026-06-02 --

    val waypoints = emptyList<GpxWaypoint>()  // was: parseGpxWaypoints(text)
    val routes    = emptyList<GpxRoute>()     // was: parseGpxRoutes(text)

with the comment: "GPX import of waypoints/routes is a separate untested task.
In-app waypoint/route creation is UNAFFECTED. Re-enable the parseGpx* call when
import logic is built + tested."

⭐ EVERYTHING DOWNSTREAM WAS ALREADY WRITTEN. The insert loops are complete --
insertWaypoint(name, lat, lon, type), the WKT build, the bbox computation,
insertRoute(...). Only the two parser calls were stubbed. This is a deliberate
hold that outlived its reason, not a missing feature.

AllDocs confirms it was known and recorded: "import currently processes tracks
only -- waypoint/route GPX import is a TEMP BYPASS (2026-06-02, untested)."

VERIFIED BEFORE PATCHING, not assumed:
  - coordinate order is CORRECT end to end. parseGpxRoutes stores Pair(lon, lat),
    the data class documents "(lon, lat) pairs", the consumer destructures
    (lon, lat), and the WKT emits "first second" = lon lat. No transposition.
  - the waypoint type mapping already handles what matters: spring/water/creek
    -> "water", scenic/viewpoint/overlook -> "scenic", hazard/caution -> "hazard".

ALSO FIXED HERE: the <rte> regex matched only a BARE tag. A GPX writing
<rte xmlns=...> or <rte > matched nothing and imported silently as zero routes.
Loosened to tolerate attributes. The <wpt> pattern already allowed them.

⚠ TWO CONSEQUENCES OF ENABLING THIS, both real:

1. THE SOURCE FILE IS NOW DELETED. :666 deletes the source when
   totalImported > 0. Today waypoint and route counts are always zero, so a
   waypoints-only GPX imports nothing and survives. After this it is consumed.
   Correct behaviour -- but new.

2. insertRoute WRITES A PERMANENT SPATIAL-DB ROW, bypassing the
   draft-then-graduate path. An imported route is permanent immediately, and
   permanent routes are the ONLY unrecoverable class on this project (WIP drafts
   are files and survive everything; a graduated route's DB row is the sole copy).
   ⛔ TEST ON DROID 2 FIRST. Droid 1 holds 89K trails and is the protected device.

⚠ STILL UNTESTED, as the original bypass said. The parsers read correctly and the
inserts are proven from in-app use, but the JOIN between them has never executed.
The first import is the test, not the confirmation.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: GPXIMPORT-2026-08-22N
"""

import sys, os, shutil, datetime

MARKER = "GPXIMPORT-2026-08-22N"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyTrackOps.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── N1: waypoints ───────────────────────────────────────────────────
N1_OLD = '''            // TEMP BYPASS 2026-06-02: GPX import of waypoints/routes is a separate untested task.
            // In-app waypoint/route creation is UNAFFECTED. Re-enable the parseGpx* call when import
            // logic is built + tested. See STATE_OF_PLAY_2026-06-02.
            val waypoints = emptyList<GpxWaypoint>()  // was: parseGpxWaypoints(text)'''

N1_NEW = '''            // ''' + MARKER + ''': BYPASS LIFTED. The 2026-06-02 hold said "re-enable when
            // import logic is built + tested" -- the logic below was already complete
            // the whole time; only this call was stubbed. Testers could not import
            // routes because of it.
            // ⚠ The source file is deleted once anything imports (see step 4). Before
            // this, a waypoints-only GPX imported nothing and survived.
            val waypoints = parseGpxWaypoints(text)'''

# ── N2: routes ──────────────────────────────────────────────────────
N2_OLD = '''            // TEMP BYPASS 2026-06-02: GPX import of waypoints/routes is a separate untested task.
            // In-app waypoint/route creation is UNAFFECTED. Re-enable the parseGpx* call when import
            // logic is built + tested. See STATE_OF_PLAY_2026-06-02.
            val routes = emptyList<GpxRoute>()  // was: parseGpxRoutes(text)'''

N2_NEW = '''            // ''' + MARKER + ''': BYPASS LIFTED. Coordinate order verified end to end
            // before enabling: the parser stores Pair(lon, lat), the data class
            // documents it, the loop below destructures (lon, lat), and the WKT emits
            // lon lat. No transposition.
            // ⛔ insertRoute writes a PERMANENT spatial-DB row -- an imported route
            // skips draft-then-graduate and is permanent immediately. Permanent routes
            // are the only unrecoverable class on this project.
            val routes = parseGpxRoutes(text)'''

# ── N3: <rte> regex would not match a tag with attributes ───────────
N3_OLD = '''        val rtePattern = Regex("""<rte>([\\s\\S]*?)</rte>""")'''

N3_NEW = '''        // ''' + MARKER + ''': was <rte> -- a BARE tag only. A GPX writing
        // <rte xmlns=...> or even "<rte >" matched nothing and imported silently as
        // zero routes, which is indistinguishable from a file with no routes in it.
        // The <wpt> pattern already tolerated attributes; this one did not.
        val rtePattern = Regex("""<rte(?:\\s[^>]*)?>([\\s\\S]*?)</rte>""")'''

EDITS = [
    ("waypoint bypass lifted", N1_OLD, N1_NEW),
    ("route bypass lifted",    N2_OLD, N2_NEW),
    ("<rte> allows attributes", N3_OLD, N3_NEW),
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

    # the stubs must be gone, and the real calls present exactly once each
    for bad in ('emptyList<GpxWaypoint>()', 'emptyList<GpxRoute>()'):
        if bad in out:
            print("\nABORT -- stub %s survives. No write." % bad); return 5
    print("  OK  both empty-list stubs gone")
    for good in ('parseGpxWaypoints(text)', 'parseGpxRoutes(text)'):
        if out.count(good) != 1:
            print("\nABORT -- %s appears %d times (need 1). No write."
                  % (good, out.count(good))); return 6
    print("  OK  both parsers called exactly once")

    print("\nMarker occurrences: %d (expect 3)" % out.count(MARKER))

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
