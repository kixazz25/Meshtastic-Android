#!/usr/bin/env python3
"""
patch_gate_I_filename_2026-08-21.py

PATCH I — the sweep's real defect. REQUIRES F + G + H.

MEASURED ON DROID 2, 2026-08-21 13:38, with copy-then-delete in place:

    sweep: ...classified completed -> history/area-4-states-2026-08-21T11:03:50.json
    sweep: move of ... failed: /storage/emulated/0/Documents/GroupTrack/imports/
           history/area-4-states-2026-08-21T11:03:50.json: open failed:
           EPERM (Operation not permitted)

⛔ THE CAUSE IS THE COLONS IN THE FILENAME, NOT THE MOVE MECHANISM.
`manifest_id` is built from iso8601Now(), which emits HH:mm:ss. Android's
external storage is FAT/exFAT-derived and rejects ':' in a filename -- the
create fails with EPERM. The earlier renameTo() failure was the SAME cause;
it just reported by returning false, with no message, so it was misdiagnosed
as FUSE refusing a cross-directory rename.

⭐ Patch H still earns its place: copy-then-delete behaved exactly as designed
-- it left the original in place and produced a diagnosable error where
renameTo's bare `false` produced none. The mechanism change did not fix the
bug; it EXPOSED it. Keep it.

FIX: sanitize the filename only. `manifest_id` INSIDE the file keeps its
readable ISO form -- it is what gets read back on a support call, and nothing
about the file's contents was ever the problem.

    area-4-states-2026-08-21T11:03:50.json   EPERM
    area-4-states-2026-08-21T11-03-50.json   fine

⚠ Same class as two entries already in the locked environment file: `adb pull`
to a drive root failing, and Git-Bash mangling /sdcard. Characters that are
fine in one place and not another.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: SWEEPNAME-2026-08-21I
"""

import sys, os, shutil, datetime

MARKER = "SWEEPNAME-2026-08-21I"
PRIOR = "SWEEPMOVE-2026-08-21H"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

I1_OLD = '''                val id = json.optString("manifest_id", "")
                val target = if (id.isBlank()) File(history, f.name)
                             else File(history, "$id.json")'''

I1_NEW = '''                val id = json.optString("manifest_id", "")
                // ''' + MARKER + ''': SANITIZE THE FILENAME. manifest_id carries an
                // ISO timestamp (HH:mm:ss) and Android's external storage is
                // FAT/exFAT-derived -- a ':' in a filename fails the create with
                // EPERM. Measured on Droid 2 2026-08-21. The id INSIDE the file is
                // untouched: it is what gets read back on a support call, and the
                // contents were never the problem.
                val safeId = id.replace(':', '-')
                val target = if (safeId.isBlank()) File(history, f.name)
                             else File(history, "$safeId.json")'''

EDITS = [("sanitize history filename", I1_OLD, I1_NEW)]


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target); return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if PRIOR not in src:
        print("ABORT: patch H (%s) not applied." % PRIOR); return 4
    if MARKER in src:
        print("Already applied (%s present)." % MARKER); return 0

    problems = []
    for name, old, _new in EDITS:
        n = src.count(old)
        if n != 1:
            problems.append("  %-28s found %d times (need 1)" % (name, n))
        else:
            print("  OK  %-28s anchor matched" % name)
    if problems:
        print("\nABORT -- no write:"); print("\n".join(problems)); return 2

    out = src
    for _name, old, new in EDITS:
        out = out.replace(old, new, 1)

    # the raw id must no longer reach the filename
    if 'File(history, "$id.json")' in out:
        print("\nABORT -- the unsanitized id is still used for the filename. No write.")
        return 5
    print("  OK  unsanitized id no longer reaches the filename")

    # the id written INTO manifests must be unchanged -- this patch is filename only
    if 'put("manifest_id", "${areaLabel.lowercase().replace(\' \', \'-\')}-${iso8601Now()}")' not in out:
        print("\nABORT -- manifest_id construction changed. This patch is filename-only.")
        return 6
    print("  OK  manifest_id inside the file is unchanged")

    print("\nMarker occurrences: %d (expect 1)" % out.count(MARKER))

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
    print("\nExpect on next launch:")
    print("  sweep: ... classified completed -> history/area-4-states-2026-08-21T11-03-50.json")
    print("  sweep: 1 of 1 manifest(s) moved to history")
    print("\nCompile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
