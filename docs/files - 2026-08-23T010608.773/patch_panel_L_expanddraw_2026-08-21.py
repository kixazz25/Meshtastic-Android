#!/usr/bin/env python3
"""
patch_panel_L_expanddraw_2026-08-21.py

PATCH L — BY AREA lands on a collapsed draw section.

Import Trails -> BY AREA sets panelTrailsChecked = true and opens the download
panel (TRAILSELECT-2026-08-21D). The panel opens with its "Draw Area" section
COLLAPSED, so the rider who chose BY AREA specifically to draw has to expand it
first -- an extra tap to reach the only control they came for.

FIX: initialise expandDrawArea from trailsChecked, which the panel already
receives. Opened by BY AREA -> the draw section starts open. Opened any other
way -> unchanged.

    var expandDrawArea by remember { mutableStateOf(trailsChecked) }

⚠ `remember` captures the INITIAL value, so if trailsChecked flips while the
panel is already open the section will not respond. That is correct here: the
flag is set before the panel opens, never during. Recorded so the behaviour is
not later mistaken for a bug.

⭐ No new parameter. The panel already knows why it was opened -- the checkbox
IS the intent, which is the same reasoning that made AreaDrawPurpose unnecessary
on 07-29.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: EXPANDDRAW-2026-08-21L
"""

import sys, os, shutil, datetime

MARKER = "EXPANDDRAW-2026-08-21L"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyDownloadPanel.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

L1_OLD = '''    var expandDrawArea by remember { mutableStateOf(false) }'''

L1_NEW = '''    // ''' + MARKER + ''': open the draw section when the panel was opened WITH
    // trails already checked -- i.e. from Import Trails -> BY AREA, where drawing
    // is the only reason the rider is here. Opened any other way it stays closed.
    // remember() captures the initial value; trailsChecked is set before the panel
    // opens and never flips while it is open, so that is the correct semantics.
    var expandDrawArea by remember { mutableStateOf(trailsChecked) }'''

EDITS = [("expandDrawArea from trailsChecked", L1_OLD, L1_NEW)]


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target); return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if MARKER in src:
        print("Already applied (%s present)." % MARKER); return 0

    # trailsChecked must be a parameter of this composable, not something we hope for
    if "trailsChecked: Boolean" not in src:
        print("ABORT -- trailsChecked is not a parameter of this file. No write."); return 5
    print("  OK  trailsChecked is a parameter of the panel")

    problems = []
    for name, old, _new in EDITS:
        n = src.count(old)
        if n != 1:
            problems.append("  %-34s found %d times (need 1)" % (name, n))
        else:
            print("  OK  %-34s anchor matched" % name)
    if problems:
        print("\nABORT -- no write:"); print("\n".join(problems)); return 2

    out = src
    for _name, old, new in EDITS:
        out = out.replace(old, new, 1)

    if "mutableStateOf(trailsChecked)" not in out:
        print("\nABORT -- the initialiser did not change. No write."); return 6
    print("  OK  expandDrawArea now initialises from trailsChecked")

    print("\nMarker occurrences: %d (expect 1)" % out.count(MARKER))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(BACKUP_DIR, "ConvoyDownloadPanel.kt.bak_%s" % stamp)
    shutil.copy2(target, backup)
    with open(target, "w", encoding="utf-8") as f:
        f.write(out)
    print("Backup: %s" % backup)
    print("APPLIED: %s" % target)
    print("\nCheck on device: Import Trails -> BY AREA should land with the draw")
    print("section already open. The MAPS FAB route should be unchanged.")
    print("\nCompile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
