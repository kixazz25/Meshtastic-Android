#!/usr/bin/env python3
"""
patch_panel_J_lineheight_2026-08-21.py

PATCH J — the two-line Map Features buttons take too much vertical space.

Patch E made both labels two lines. The gap between the lines is not padding
we added: Compose gives Text a default lineHeight from the type scale, tuned
for body text at 14-16sp. It does NOT scale down with fontSize, so at 8sp the
line box stays tall and the two lines read as a paragraph rather than a wrapped
sentence.

    lineHeight = 10.sp   ~1.2x the font size, standard tight leading.

The 6.dp vertical padding is deliberately UNCHANGED -- the button shrinks
because each line box shrinks, not because the padding was cut. If it ends up
too tight on device, 11 or 12.sp adds air without returning to the default gap.

⚠ TextUnit import: fontSize/8.sp is already in use in this file, so `.sp` is
imported. lineHeight takes the same TextUnit type -- no new import. Checked,
not assumed.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: BTNLINE-2026-08-21J
"""

import sys, os, shutil, datetime

MARKER = "BTNLINE-2026-08-21J"
PRIOR = "PANELBTN-2026-08-21E"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyArtifactsPanel.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

J1_OLD = '''                                Text("IMPORT TRAILS BY\\nSTATE OR AREA", color = aGreen, fontSize = 8.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))'''

J1_NEW = '''                                Text("IMPORT TRAILS BY\\nSTATE OR AREA", color = aGreen, fontSize = 8.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    // ''' + MARKER + ''': the default lineHeight comes from the type
                                    // scale and does not shrink with fontSize, so two 8sp lines got
                                    // a body-text line box. 10.sp is ~1.2x -- the lines flow like a
                                    // wrapped sentence and the button loses the excess height.
                                    lineHeight = 10.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))'''

J2_OLD = '''                                Text("IMPORT TRACKS,\\nWAYPOINTS & ROUTES", color = aBlue, fontSize = 8.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))'''

J2_NEW = '''                                Text("IMPORT TRACKS,\\nWAYPOINTS & ROUTES", color = aBlue, fontSize = 8.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    // ''' + MARKER + ''': same tight leading as the button beside it --
                                    // both must shrink together or the row goes lopsided.
                                    lineHeight = 10.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))'''

EDITS = [
    ("IMPORT TRAILS BY leading",  J1_OLD, J1_NEW),
    ("IMPORT TRACKS leading",     J2_OLD, J2_NEW),
]


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target); return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if PRIOR not in src:
        print("ABORT: patch E (%s) not applied." % PRIOR); return 4
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

    # lineHeight takes a TextUnit; .sp must already be imported (fontSize uses it)
    if "import androidx.compose.ui.unit.sp" not in out:
        print("\nABORT -- the .sp extension is not imported in this file. No write."); return 5
    print("  OK  .sp import present (lineHeight needs no new import)")

    if out.count("lineHeight = 10.sp") != 2:
        print("\nABORT -- expected exactly 2 lineHeight settings, found %d. No write."
              % out.count("lineHeight = 10.sp")); return 6
    print("  OK  both buttons set, and only those two")

    print("\nMarker occurrences: %d (expect 2)" % out.count(MARKER))

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
    print("\n⚠ Check on device: if the lines now sit too close, 11 or 12.sp.")
    print("Compile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
