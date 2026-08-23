#!/usr/bin/env python3
"""
patch_area_A_unthread_state_2026-08-21.py

PATCH A of 3 — pure refactor, NO behaviour change.

runGeofabrikSource() and runCatalogSource() each take `state: GeofabrikState`.
In a multi-state AREA run there is no single state: each Geofabrik row is its
own state, and the catalog rows want the RUN's bbox. So `state` has to come
out of both signatures before B can emit N rows.

What `state` is actually used for, measured:
  runGeofabrikSource : (1) ledger description string "Geofabrik <name> (auto
                       download)"  (2) the bbox handed to setPendingImport
  runCatalogSource   : the bbox handed to TrailImporter.importByArea

Both are replaced:
  - the name comes from the ROW (src.name), which already carries it
  - the bbox comes from the RUN, passed in as four doubles

Scope string on setPendingImport stays "state" in this patch. Patch C flips it
per process type -- keeping it here would be a behaviour change inside a
refactor, which is what makes a refactor unverifiable.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: AREAREFACTOR-2026-08-21A
"""

import sys, os, shutil, datetime

MARKER = "AREAREFACTOR-2026-08-21A"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── 1. Geofabrik runner signature ────────────────────────────────────
A1_OLD = '''    private suspend fun runGeofabrikSource(
        context: Context, src: JSONObject, state: GeofabrikState
    ): Boolean {'''

A1_NEW = '''    // ''' + MARKER + ''': `state` removed. A multi-state area run has no single
    // state -- each Geofabrik row IS its own state, and its slug/url/name already
    // live on the row. The bbox is a property of the RUN, so it is passed in.
    private suspend fun runGeofabrikSource(
        context: Context, src: JSONObject,
        bboxSouth: Double, bboxWest: Double, bboxNorth: Double, bboxEast: Double
    ): Boolean {'''

# ── 2. ledger description string ─────────────────────────────────────
A2_OLD = '''            "Geofabrik ${state.name} (auto download)",'''
A2_NEW = '''            "Geofabrik ${src.optString("name", slug)} (auto download)",'''

# ── 3. the pending bbox ──────────────────────────────────────────────
A3_OLD = '''        val bbox = doubleArrayOf(state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast)
        OsmImportLedger.setPendingImport(context, slug, "state", bbox)
        Log.i(TAG, "setPendingImport for $slug: state bbox")'''

A3_NEW = '''        // ''' + MARKER + ''': the RUN's bbox, not the state's. For a state import these
        // are the same four numbers. For an area import they are the drawn box --
        // which is the whole point: a state's Geofabrik bbox clips neighbouring
        // states' corners and would pull ground the rider never drew.
        val bbox = doubleArrayOf(bboxSouth, bboxWest, bboxNorth, bboxEast)
        OsmImportLedger.setPendingImport(context, slug, "state", bbox)
        Log.i(TAG, "setPendingImport for $slug: bbox " +
            "S=$bboxSouth W=$bboxWest N=$bboxNorth E=$bboxEast")'''

# ── 4. Catalog runner signature ──────────────────────────────────────
A4_OLD = '''    private suspend fun runCatalogSource(
        context: Context, src: JSONObject, state: GeofabrikState
    ): Boolean {'''

A4_NEW = '''    // ''' + MARKER + ''': `state` removed -- this path only ever wanted the bbox.
    private suspend fun runCatalogSource(
        context: Context, src: JSONObject,
        bboxSouth: Double, bboxWest: Double, bboxNorth: Double, bboxEast: Double
    ): Boolean {'''

# ── 5. Catalog call to importByArea ──────────────────────────────────
A5_OLD = '''            val results = TrailImporter.importByArea(
                context, listOf(sourceId),
                state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast
            )'''

A5_NEW = '''            val results = TrailImporter.importByArea(
                context, listOf(sourceId),
                bboxSouth, bboxWest, bboxNorth, bboxEast
            )'''

# ── 6. the dispatch site in execute() ────────────────────────────────
A6_OLD = '''                val ok = when (src.getString("process")) {
                    "geofabrik_full_state" -> runGeofabrikSource(context, src, state)
                    "trails_list_area" -> runCatalogSource(context, src, state)'''

A6_NEW = '''                // ''' + MARKER + ''': runners now take the run's bbox, not a state object.
                val ok = when (src.getString("process")) {
                    "geofabrik_full_state" -> runGeofabrikSource(
                        context, src,
                        state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast)
                    "trails_list_area" -> runCatalogSource(
                        context, src,
                        state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast)'''

EDITS = [
    ("runGeofabrikSource signature",   A1_OLD, A1_NEW),
    ("ledger description from row",    A2_OLD, A2_NEW),
    ("pending bbox from run",          A3_OLD, A3_NEW),
    ("runCatalogSource signature",     A4_OLD, A4_NEW),
    ("importByArea bbox args",         A5_OLD, A5_NEW),
    ("dispatch passes bbox",           A6_OLD, A6_NEW),
]

# self-test: no guard string may appear inside any replacement block
def selftest():
    bad = []
    for name, old, new in EDITS:
        for other_name, other_old, _ in EDITS:
            if other_old in new and other_old != old:
                bad.append("%s replacement contains %s anchor" % (name, other_name))
    return bad


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    bad = selftest()
    if bad:
        print("ABORT - script self-test failed:")
        print("\n".join("  " + b for b in bad))
        return 3

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target)
        return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if MARKER in src:
        print("Already applied (%s present). Nothing to do." % MARKER)
        return 0

    problems = []
    for name, old, _new in EDITS:
        n = src.count(old)
        if n != 1:
            problems.append("  %-32s found %d times (need 1)" % (name, n))
        else:
            print("  OK  %-32s anchor matched" % name)

    if problems:
        print("\nABORT -- no write:")
        print("\n".join(problems))
        return 2

    out = src
    for _name, old, new in EDITS:
        out = out.replace(old, new, 1)

    # post-check: no lingering `state.bbox` reference inside the two runners
    if "state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast" in out:
        # the dispatch site legitimately keeps one -- expect exactly 2 there
        cnt = out.count("state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast")
        print("\n  note: %d dispatch-site state.bbox references remain (expect 2)" % cnt)

    print("\nMarker occurrences after patch: %d (expect 4)" % out.count(MARKER))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__))
        return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(BACKUP_DIR, "HomeStateImportController.kt.bak_%s" % stamp)
    shutil.copy2(target, backup)
    with open(target, "w", encoding="utf-8") as f:
        f.write(out)
    print("Backup: %s" % backup)
    print("APPLIED: %s" % target)
    print("\nNo behaviour change expected. Compile gate only:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
