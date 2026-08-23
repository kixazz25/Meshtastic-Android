#!/usr/bin/env python3
"""
patch_import_N3_hoist_2026-08-22.py

THIRD attempt at the same two lines. The first two were guesses; this one is
read off the code.

  patch N  -> parseGpxWaypoints(text)      Unresolved reference 'text'
  patch N2 -> parseGpxWaypoints(fullText)  Unresolved reference 'fullText'

⭐ WHY BOTH FAILED, and it is the same mistake twice: I reached for a variable
name instead of checking its SCOPE.
  - `text` belongs to importTrackFile (:202) -- a different function. The
    2026-06-02 bypass comment named it, and the function has since been split.
  - `fullText` is declared at :532 INSIDE a `run { }` block that closes long
    before the waypoint section at :644.

⛔ AND THE OBVIOUS FIX IS WRONG. Calling sourceFile.readText() again at the
waypoint section would compile -- sourceFile is in scope, :666 deletes it. But
the comment at :520 says the previous whole-file approach "OOM'd on large onX
exports", which is why the streaming rewrite happened. A SECOND full read
doubles the memory on exactly the files that already crashed.

FIX: hoist the single read ABOVE the `run { }` block. One read, one string,
both the track scan and the waypoint/route parsers use it. No extra memory over
what the current code already allocates.

⚠ The OOM risk is NOT removed by this -- it is unchanged. The whole-file read
already exists; this only widens where it is visible. Large-export OOM remains
an open issue against this function.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: GPXIMPORT-2026-08-22N3
"""

import sys, os, shutil, datetime

MARKER = "GPXIMPORT-2026-08-22N3"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyTrackOps.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── hoist the read out of the run block ─────────────────────────────
H_OLD = '''            run {
                val fullText = sourceFile.readText()
                val trkPattern = Regex("""<trk>([\\s\\S]*?)</trk>""")'''

H_NEW = '''            // ''' + MARKER + ''': hoisted OUT of the run block. It was scoped inside,
            // so the waypoint and route parsers below could not see it. Reading the
            // file a SECOND time down there would compile, but the comment above
            // records that the whole-file approach OOM'd on large onX exports --
            // two reads doubles that. One read, both consumers.
            val fullText = sourceFile.readText()
            run {
                val trkPattern = Regex("""<trk>([\\s\\S]*?)</trk>""")'''

EDITS = [("hoist fullText above run{}", H_OLD, H_NEW)]


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target); return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if MARKER in src:
        print("Already applied (%s present)." % MARKER); return 0

    # the two call sites must already read fullText (patch N2 applied)
    for need in ("parseGpxWaypoints(fullText)", "parseGpxRoutes(fullText)"):
        if need not in src:
            print("ABORT: %s not present -- run patch N2 first." % need); return 4
    print("  OK  both call sites already reference fullText")

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

    # exactly one declaration, and it must now sit before both uses
    decl = out.count("val fullText = sourceFile.readText()")
    if decl != 1:
        print("\nABORT -- %d declarations of fullText (need 1). No write." % decl); return 5
    print("  OK  exactly one fullText declaration")

    dpos = out.index("val fullText = sourceFile.readText()")
    for use in ("parseGpxWaypoints(fullText)", "parseGpxRoutes(fullText)",
                "trkPattern.findAll(fullText)"):
        if out.index(use) < dpos:
            print("\nABORT -- %s appears BEFORE the declaration. No write." % use); return 6
    print("  OK  declaration precedes every use")

    # the run block must still open right after
    if "val fullText = sourceFile.readText()\n            run {" not in out:
        print("\nABORT -- the run block did not survive the hoist. No write."); return 7
    print("  OK  run block intact")

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
    print("\n  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
