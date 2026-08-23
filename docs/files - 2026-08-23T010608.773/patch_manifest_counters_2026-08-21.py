#!/usr/bin/env python3
"""
patch_manifest_counters_2026-08-21.py

Adds to HomeStateImportController.kt:
  1. manifest_id   -- "<slug>-<iso8601>" on the top-level manifest block
  2. Five counters on BOTH source-record builders (catalog + Geofabrik):
       processed / selected / dupes / adds / errors    (imported is KEPT)
  3. Populates those five at completion, from the counters that already exist:
       - catalog  : TrailImporter.ImportResult fields
       - geofabrik: the OSM ledger's last import record

Absent counters are written as -1, NOT 0. A false zero on the errors line is
the one number that must never lie.

DRY RUN BY DEFAULT.  Re-run with --apply to write.
Marker guard: MANIFESTCOUNTS-2026-08-21  (re-running after apply is a no-op)
"""

import sys, os, shutil, datetime

MARKER = "MANIFESTCOUNTS-2026-08-21"

REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ─────────────────────────────────────────────────────────────────────
# EDIT 1 — manifest_id on the top-level block
# ─────────────────────────────────────────────────────────────────────
A1_OLD = '''        return JSONObject().apply {
            put("process_state", "in_progress")
            put("started_at", iso8601Now())'''

A1_NEW = '''        return JSONObject().apply {
            // ''' + MARKER + ''': stable per-run identifier. Field, not filename --
            // manifests written before this change have no id and must still read
            // as valid. Slug (not name) because California ships as two entries.
            put("manifest_id", "${state.slug}-${iso8601Now()}")
            put("process_state", "in_progress")
            put("started_at", iso8601Now())'''

# ─────────────────────────────────────────────────────────────────────
# EDIT 2a — catalog source-record builder
# ─────────────────────────────────────────────────────────────────────
A2_OLD = '''                put("process", "trails_list_area")
                put("status", "pending")
                put("imported", 0)'''

A2_NEW = '''                put("process", "trails_list_area")
                put("status", "pending")
                put("imported", 0)
                // ''' + MARKER + ''': recap counters. selected = dupes + adds + errors.
                put("processed", 0)
                put("selected", 0)
                put("dupes", 0)
                put("adds", 0)
                put("errors", 0)'''

# ─────────────────────────────────────────────────────────────────────
# EDIT 2b — Geofabrik source-record builder
# ─────────────────────────────────────────────────────────────────────
A3_OLD = '''            put("gpkg_url", state.gpkgUrl)
            put("status", "pending")
            put("imported", 0)'''

A3_NEW = '''            put("gpkg_url", state.gpkgUrl)
            put("status", "pending")
            put("imported", 0)
            // ''' + MARKER + ''': recap counters. selected = dupes + adds + errors.
            put("processed", 0)
            put("selected", 0)
            put("dupes", 0)
            put("adds", 0)
            put("errors", 0)'''

# ─────────────────────────────────────────────────────────────────────
# EDIT 3a — catalog completion: every field is already on ImportResult
# ─────────────────────────────────────────────────────────────────────
A4_OLD = '''            val total = results.sumOf { it.inserted }
            src.put("imported", total)'''

A4_NEW = '''            val total = results.sumOf { it.inserted }
            src.put("imported", total)
            // ''' + MARKER + ''': the recap five, straight off ImportResult --
            // TrailImporter already computes every one of these and discards
            // all but `inserted`. processed = the whole tally; `rejected` is
            // out-of-area, so selected is processed minus rejected.
            val pDropped  = results.sumOf { it.dropped }
            val pAliased  = results.sumOf { it.aliased }
            val pSkipped  = results.sumOf { it.skipped }
            val pRejected = results.sumOf { it.rejected }
            val pErrors   = results.sumOf { it.errors }
            val pProcessed = total + pDropped + pAliased + pSkipped + pRejected + pErrors
            src.put("processed", pProcessed)
            src.put("selected", pProcessed - pRejected)
            src.put("dupes", pDropped + pAliased + pSkipped)
            src.put("adds", total)
            src.put("errors", pErrors)'''

# ─────────────────────────────────────────────────────────────────────
# EDIT 3b — Geofabrik completion, read from the ledger
#   `updated` does not exist on ImportResult (08-20 bug list) so optInt
#   returns 0 and it is dead weight -- left alone, not this patch's job.
#   Counters the ledger may not carry are written -1 = "not reported".
# ─────────────────────────────────────────────────────────────────────
A5_OLD = '''            val count = last.optInt("inserted", 0) + last.optInt("updated", 0)
            src.put("imported", count)'''

A5_NEW = '''            val count = last.optInt("inserted", 0) + last.optInt("updated", 0)
            src.put("imported", count)
            // ''' + MARKER + ''': the recap five from the OSM ledger.
            // -1 means NOT REPORTED by this ledger, which is not the same as
            // zero. The recap must be able to say "not reported" rather than
            // claim a clean run it cannot vouch for.
            val gAdds    = last.optInt("inserted", -1)
            val gDropped = last.optInt("dropped", -1)
            val gAliased = last.optInt("aliased", -1)
            val gErrors  = last.optInt("errors", -1)
            val gFound   = last.optInt("found", -1)
            val gDupes   = if (gDropped < 0 && gAliased < 0) -1
                           else maxOf(gDropped, 0) + maxOf(gAliased, 0)
            src.put("processed", gFound)
            // Whole-state import applies no out-of-area cut, so nothing is
            // rejected: selected == processed. The BY AREA path must override
            // this when it lands -- there, selected is the bbox result.
            src.put("selected", gFound)
            src.put("dupes", gDupes)
            src.put("adds", gAdds)
            src.put("errors", gErrors)'''

EDITS = [
    ("manifest_id on top-level block",        A1_OLD, A1_NEW),
    ("counters on catalog source record",     A2_OLD, A2_NEW),
    ("counters on Geofabrik source record",   A3_OLD, A3_NEW),
    ("populate five -- catalog completion",   A4_OLD, A4_NEW),
    ("populate five -- Geofabrik completion", A5_OLD, A5_NEW),
]


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: target not found:\n  %s" % target)
        return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if MARKER in src:
        print("Already applied (%s present in file). Nothing to do." % MARKER)
        return 0

    # ---- verify EVERY anchor before touching anything -----------------
    problems = []
    for name, old, _new in EDITS:
        n = src.count(old)
        if n != 1:
            problems.append("  %-40s found %d times (need exactly 1)" % (name, n))
        else:
            print("  OK  %-40s anchor matched" % name)

    if problems:
        print("\nABORT -- no write. Anchors did not match verbatim:")
        print("\n".join(problems))
        print("\nThe file has moved on since this script was written.")
        print("Re-read the anchors and regenerate rather than loosening them.")
        return 2

    out = src
    for _name, old, new in EDITS:
        out = out.replace(old, new, 1)

    added = out.count(MARKER)
    print("\nAll 5 anchors matched. Marker occurrences after patch: %d (expect 5)" % added)

    if not apply:
        print("\nDRY RUN -- NOTHING WAS WRITTEN.")
        print("Re-run with --apply to write the change:")
        print("  python %s --apply" % os.path.basename(__file__))
        return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(
        BACKUP_DIR, "HomeStateImportController.kt.bak_%s" % stamp)
    shutil.copy2(target, backup)
    print("Backup written: %s" % backup)

    with open(target, "w", encoding="utf-8") as f:
        f.write(out)

    print("APPLIED to %s" % target)
    print("\nNext: compile gate + marker count in one command --")
    print('  ./gradlew compileGoogleReleaseKotlin && grep -c "%s" \\' % MARKER)
    print("    app/src/main/java/com/geeksville/mesh/convoy/HomeStateImportController.kt")
    print("  (expect BUILD SUCCESSFUL followed by 5)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
