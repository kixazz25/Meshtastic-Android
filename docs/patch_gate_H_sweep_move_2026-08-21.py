#!/usr/bin/env python3
"""
patch_gate_H_sweep_move_2026-08-21.py

PATCH H — fixes the sweep's move. REQUIRES F + G.

MEASURED ON DROID 2, 2026-08-21 13:11:
    sweep: could not move import_2026-08-21_area-4-states_state.json
    sweep: 0 of 1 manifest(s) moved to history

The history directory existed and was writable; the manifest was valid and
correctly classified. `File.renameTo()` simply returns false on Android's
FUSE-mounted external storage when the move crosses into a subdirectory.
It reports failure by returning false -- no exception, nothing to catch.

FIX: copy-then-delete, ordered so a failure leaves the ORIGINAL in place.
    1. write content to the target
    2. verify the target exists and the length matches
    3. only then delete the original
A failure part-way leaves a duplicate, never a loss. That is the safe
direction: the sweep retries next launch and the record still exists.

ALSO FIXED: the log line said "could not move" without saying what the
manifest had been classified AS. Reading that line, Claude concluded the
classification was broken when it was correct -- the code had done the right
thing and the log could not show it. Classification is now logged BEFORE the
move is attempted, so the two are never confused again.

⚠ NOT A BUG, recorded so it is not "fixed" later: the four-state manifest was
correctly read as COMPLETED (all ten sources carry `imported` and
status=completed), so the killed stamp correctly did not run and
process_state stayed "completed".

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: SWEEPMOVE-2026-08-21H
"""

import sys, os, shutil, datetime

MARKER = "SWEEPMOVE-2026-08-21H"
PRIOR = "GATEJOB-2026-08-21F"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

H1_OLD = '''                // THEN MOVE. Name by manifest_id where present -- it carries a
                // timestamp, so two runs on the same day stop colliding in
                // history. No id means an old manifest: keep its filename.
                val id = json.optString("manifest_id", "")
                val target = if (id.isBlank()) File(history, f.name)
                             else File(history, "$id.json")

                if (f.renameTo(target)) {
                    swept++
                    Log.i(TAG, "sweep: ${f.name} -> history/${target.name} " +
                        "(${if (allCounted) "completed" else "KILLED"})")
                } else {
                    Log.w(TAG, "sweep: could not move ${f.name} -- left in place, " +
                        "will retry next launch")
                }'''

H1_NEW = '''                // THEN MOVE. Name by manifest_id where present -- it carries a
                // timestamp, so two runs on the same day stop colliding in
                // history. No id means an old manifest: keep its filename.
                val id = json.optString("manifest_id", "")
                val target = if (id.isBlank()) File(history, f.name)
                             else File(history, "$id.json")

                // ''' + MARKER + ''': log the CLASSIFICATION before attempting the
                // move. The previous log said only "could not move", which made a
                // correct classification look like a broken one.
                val verdict = if (allCounted) "completed" else "KILLED"
                Log.i(TAG, "sweep: ${f.name} classified $verdict -> history/${target.name}")

                // ''' + MARKER + ''': COPY-THEN-DELETE, not renameTo.
                // renameTo() returns false on FUSE-mounted external storage when
                // the move crosses into a subdirectory -- measured on Droid 2
                // 2026-08-21 with a writable target directory and a valid file.
                // It fails by RETURNING FALSE, so there is no exception to catch.
                // Order matters: verify the copy landed before deleting the
                // original, so a part-way failure leaves a DUPLICATE, never a loss.
                var moved = false
                try {
                    target.writeText(f.readText())
                    if (target.exists() && target.length() == f.length()) {
                        if (f.delete()) {
                            moved = true
                        } else {
                            // Copy is safe; the original would not delete. Say so
                            // plainly -- next launch will see both.
                            Log.w(TAG, "sweep: copied ${f.name} but could not delete " +
                                "the original -- duplicate left in imports/")
                        }
                    } else {
                        Log.w(TAG, "sweep: copy of ${f.name} did not verify " +
                            "(target ${target.length()}B vs source ${f.length()}B) -- " +
                            "original left in place")
                        target.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sweep: move of ${f.name} failed: ${e.message} -- " +
                        "original left in place, will retry next launch")
                }

                if (moved) swept++'''

EDITS = [("sweep move: copy-then-delete", H1_OLD, H1_NEW)]


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target); return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if PRIOR not in src:
        print("ABORT: patch F (%s) not applied." % PRIOR); return 4
    if MARKER in src:
        print("Already applied (%s present)." % MARKER); return 0

    problems = []
    for name, old, _new in EDITS:
        n = src.count(old)
        if n != 1:
            problems.append("  %-30s found %d times (need 1)" % (name, n))
        else:
            print("  OK  %-30s anchor matched" % name)
    if problems:
        print("\nABORT -- no write:"); print("\n".join(problems)); return 2

    out = src
    for _name, old, new in EDITS:
        out = out.replace(old, new, 1)

    if "renameTo" in out:
        print("\nABORT -- renameTo still present in the file. No write."); return 5
    print("  OK  renameTo gone")

    if out.count("if (moved) swept++") != 1:
        print("\nABORT -- swept counter not wired exactly once. No write."); return 6
    print("  OK  swept counter wired once")

    print("\nMarker occurrences: %d (expect 2)" % out.count(MARKER))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(BACKUP_DIR, "HomeStateImportController.kt.bak_%s" % stamp)
    shutil.copy2(target, backup)
    with open(target, "w", encoding="utf-8") as f:
        f.write(out)
    print("Backup: %s" % backup)
    print("APPLIED: %s" % target)
    print("\nCompile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    print("\nThe four-state manifest is still in imports/ -- it is the test case.")
    print("Expect on next launch:")
    print("  sweep: <file> classified completed -> history/area-4-states-...json")
    print("  sweep: 1 of 1 manifest(s) moved to history")
    return 0


if __name__ == "__main__":
    sys.exit(main())
