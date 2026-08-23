#!/usr/bin/env python3
"""
patch_panel_E_import_buttons_2026-08-21.py

PATCH E — the Map Features panel's import row.

Patch D killed the "IMPORT OSM DATA" handler but NOT the button, so it still
rendered and did nothing. A dead control is worse than either extreme: a tester
taps it, nothing happens, and files a bug. This removes the button itself.

Three buttons become two, and both get honest labels:

    IMPORT TRAILS          ->  IMPORT TRAILS BY
                               STATE OR AREA          (two lines)
    IMPORT FEATURES        ->  IMPORT TRACKS,
                               WAYPOINTS & ROUTES     (two lines)
    IMPORT OSM DATA        ->  REMOVED

⛔ THE DISPATCH KEYS DO NOT CHANGE. `onImport("Trails")` and `onImport("Artifacts")`
are matched by string in ConvoyMapViewerScreen. The 08-17 label pass left a comment
saying exactly this above the Artifacts button. LABEL ONLY.

The two survivors are Modifier.weight(1f) in a Row, so removing the third simply
lets the remaining two share the width -- no layout arithmetic needed.

⚠ 8sp across half a panel: two-line labels are DELIBERATE, written as explicit
"\\n" rather than left to wrap, so the break lands where we chose it. The 08-18
pass had Help clip to "HE" by trusting automatic sizing.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: PANELBTN-2026-08-21E
"""

import sys, os, shutil, datetime

MARKER = "PANELBTN-2026-08-21E"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyArtifactsPanel.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── E1: relabel button 1 (IMPORT TRAILS -> two lines) ───────────────
E1_OLD = '''                                Text("IMPORT TRAILS", color = aGreen, fontSize = 8.sp,'''
E1_NEW = '''                                // ''' + MARKER + ''': label only -- onImport("Trails") is a
                                // dispatch key matched in ConvoyMapViewerScreen. Two lines
                                // written explicitly so the break lands where we chose it.
                                Text("IMPORT TRAILS BY\\nSTATE OR AREA", color = aGreen, fontSize = 8.sp,'''

# ── E2: relabel button 2 (IMPORT FEATURES -> what it imports) ───────
E2_OLD = '''                                Text("IMPORT FEATURES", color = aBlue, fontSize = 8.sp,'''
E2_NEW = '''                                // ''' + MARKER + ''': names what it imports instead of the
                                // category word. "Features" and "Trails" read as the same
                                // thing to a rider; tracks/waypoints/routes do not.
                                Text("IMPORT TRACKS,\\nWAYPOINTS & ROUTES", color = aBlue, fontSize = 8.sp,'''

# ── E3: remove the OSM button entirely ─────────────────────────────
E3_OLD = '''                            Surface(
                                modifier = Modifier.weight(1f).clickable { onImport("OSM") },
                                shape = RoundedCornerShape(4.dp), color = Color(0xFF0D1520)
                            ) {
                                Text("IMPORT OSM DATA", color = aOrange, fontSize = 8.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
                            }
'''

E3_NEW = '''                            // ''' + MARKER + ''': "IMPORT OSM DATA" REMOVED (design spec §2).
                            // OSM is no longer a separate concept -- it is one source inside
                            // Import Trails, run automatically with every other source that
                            // covers the chosen state or area. Patch D killed its handler;
                            // the button itself survived and rendered as a dead control.
                            // Screenshots of the old flow were captured 2026-08-21 for the
                            // manual before removal.
'''

EDITS = [
    ("relabel IMPORT TRAILS",   E1_OLD, E1_NEW),
    ("relabel IMPORT FEATURES", E2_OLD, E2_NEW),
    ("remove OSM button",       E3_OLD, E3_NEW),
]


def selftest():
    bad = []
    for name, old, new in EDITS:
        for oname, oold, _ in EDITS:
            if oname != name and oold in new:
                bad.append("%s replacement contains %s anchor" % (name, oname))
    return bad


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    bad = selftest()
    if bad:
        print("ABORT - self-test failed:")
        print("\n".join("  " + b for b in bad)); return 3

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

    # post-checks
    if 'onImport("OSM")' in out:
        print("\nABORT -- onImport(\"OSM\") still present. No write."); return 5
    for key in ('onImport("Trails")', 'onImport("Artifacts")'):
        if out.count(key) != 1:
            print("\nABORT -- dispatch key %s appears %d times (need 1). No write."
                  % (key, out.count(key)))
            return 6
    print("  OK  dispatch keys intact: onImport(\"Trails\"), onImport(\"Artifacts\")")
    print("  OK  onImport(\"OSM\") gone")

    # aOrange may now be unused in this file -- report, do not act
    if out.count("aOrange") == 0:
        print("  --  aOrange no longer referenced (was the OSM button's colour)")
    else:
        print("  --  aOrange still used %d time(s) elsewhere" % out.count("aOrange"))

    print("\nMarker occurrences: %d (expect 3)" % out.count(MARKER))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(BACKUP_DIR, "ConvoyArtifactsPanel.kt.bak_%s" % stamp)
    shutil.copy2(target, backup)
    with open(target, "w", encoding="utf-8") as f:
        f.write(out)
    print("Backup: %s" % backup)
    print("APPLIED: %s" % target)
    print("\nCompile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    print("\n⚠ CHECK ON DEVICE: two-line labels at 8sp across half the panel.")
    print("  If either clips, the fix is the label text, not the font size.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
