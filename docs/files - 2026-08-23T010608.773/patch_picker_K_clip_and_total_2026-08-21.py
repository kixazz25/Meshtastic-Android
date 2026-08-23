#!/usr/bin/env python3
"""
patch_picker_K_clip_and_total_2026-08-21.py

PATCH K — two defects visible in the 2026-08-21 device captures.

--- 1. THE BUTTONS CLIP -------------------------------------------------
Both selector buttons carry Modifier.height(52.dp) -- a FIXED height. The
primary button holds two lines (14sp + 10sp) and the outlined button holds
two 12sp lines; neither fits in 52dp, so the second line is sliced through
mid-glyph. Measured in home_state_selector, 2026-08-21.

⚠ NOT a lineHeight problem. Patch J tightened leading on the Map Features
buttons, which was a genuine type-scale issue. This is a fixed box cropping
its content -- a different cause with a similar symptom, and treating it as
leading would have shrunk the text to fit a box that should have grown.

FIX: heightIn(min = 52.dp). The buttons keep their minimum size and grow to
their content instead of cutting it.

--- 2. "TOTAL 0" ON A SUCCESSFUL IMPORT --------------------------------
The per-source figure and the Total both read `imported`, which is ADDS ONLY.
The capture shows four sources at 0 and Total 0 after a 3m 7s run -- a New
Hampshire re-import where all 43,348 records were already present. A
successful run is indistinguishable from total failure.

FIX: keep adds as the headline number, and when duplicates are non-zero say
so on the same line. No new data -- dupes is already on ImportSourceProgress.

    US Forest Service Trails        0 · 1,004 already had
    Total                           0 · 43,348 already had

⚠ Deliberately NOT changing the headline number to processed or selected.
Fred's call stands: the line shows records added. This adds the context that
makes zero readable, nothing more.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: PICKERFIX-2026-08-21K
"""

import sys, os, shutil, datetime

MARKER = "PICKERFIX-2026-08-21K"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStatePickerScreen.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── K1: primary LOAD button -- fixed height crops line 2 ────────────
K1_OLD = '''                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(52.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOAD ${state.name.uppercase()} TRAIL DATA",'''

K1_NEW = '''                shape = RoundedCornerShape(8.dp),
                // ''' + MARKER + ''': was height(52.dp) -- a FIXED box, so the second
                // line ("Downloads all available sources") was sliced mid-glyph.
                // heightIn keeps the minimum and lets the button grow to its content.
                modifier = Modifier.heightIn(min = 52.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOAD ${state.name.uppercase()} TRAIL DATA",'''

# ── K2: outlined "Select a different" button ───────────────────────
K2_OLD = '''            OutlinedButton(
                onClick = onSelectDifferent,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(52.dp)
            ) {'''

K2_NEW = '''            OutlinedButton(
                onClick = onSelectDifferent,
                shape = RoundedCornerShape(8.dp),
                // ''' + MARKER + ''': same fixed-height crop as the button beside it.
                modifier = Modifier.heightIn(min = 52.dp)
            ) {'''

# ── K3: per-source line -- make a zero readable ────────────────────
K3_OLD = '''                            Text(
                                if (src.status == "completed") "${src.imported}" else "failed",
                                color = if (src.status == "completed") green else Color(0xFFCC4444),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )'''

K3_NEW = '''                            // ''' + MARKER + ''': adds stays the headline number. When
                            // duplicates are non-zero the line says so, because a
                            // re-import that found everything already present would
                            // otherwise read exactly like a total failure.
                            Text(
                                if (src.status != "completed") "failed"
                                else if (src.dupes > 0) "${src.imported} \\u00B7 ${src.dupes} already had"
                                else "${src.imported}",
                                color = if (src.status == "completed") green else Color(0xFFCC4444),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )'''

# ── K4: the Total row ──────────────────────────────────────────────
K4_OLD = '''                    Text("Total", color = txtLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("${progress?.sources?.sumOf { it.imported } ?: 0}",
                        color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold)'''

K4_NEW = '''                    Text("Total", color = txtLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    // ''' + MARKER + ''': same treatment as the source lines. A run whose
                    // records were all already present shows "0" without this, and
                    // "Import Complete / Total 0" teaches the rider that a working
                    // import looks like a broken one.
                    Text(
                        run {
                            val added = progress?.sources?.sumOf { it.imported } ?: 0
                            val had = progress?.sources?.sumOf { if (it.dupes > 0) it.dupes else 0 } ?: 0
                            if (had > 0) "$added \\u00B7 $had already had" else "$added"
                        },
                        color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold)'''

EDITS = [
    ("LOAD button heightIn",      K1_OLD, K1_NEW),
    ("Select-different heightIn", K2_OLD, K2_NEW),
    ("per-source dupes context",  K3_OLD, K3_NEW),
    ("Total dupes context",       K4_OLD, K4_NEW),
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
            problems.append("  %-28s found %d times (need 1)" % (name, n))
        else:
            print("  OK  %-28s anchor matched" % name)
    if problems:
        print("\nABORT -- no write:"); print("\n".join(problems)); return 2

    out = src
    for _name, old, new in EDITS:
        out = out.replace(old, new, 1)

    # heightIn is a different import from height -- both live in foundation.layout
    if "import androidx.compose.foundation.layout.heightIn" not in out:
        lines = out.split("\n")
        anchor = "import androidx.compose.foundation.layout.height"
        idx = next((i for i, l in enumerate(lines) if l.strip() == anchor), -1)
        if idx < 0:
            # wildcard import of the layout package covers it
            if "import androidx.compose.foundation.layout.*" in out:
                print("  OK  layout.* wildcard import covers heightIn")
            else:
                print("\nABORT -- cannot confirm heightIn is importable in this file.")
                print("  Add the import by hand or re-anchor. No write."); return 5
        else:
            lines.insert(idx + 1, "import androidx.compose.foundation.layout.heightIn")
            out = "\n".join(lines)
            print("  OK  heightIn import ADDED after line %d" % (idx + 1))
    else:
        print("  OK  heightIn already imported")

    if "Modifier.height(52.dp)" in out:
        print("\nABORT -- a fixed height(52.dp) survives. No write."); return 6
    print("  OK  no fixed 52.dp heights remain")

    print("\nMarker occurrences: %d (expect 4)" % out.count(MARKER))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(BACKUP_DIR, "HomeStatePickerScreen.kt.bak_%s" % stamp)
    shutil.copy2(target, backup)
    with open(target, "w", encoding="utf-8") as f:
        f.write(out)
    print("Backup: %s" % backup)
    print("APPLIED: %s" % target)
    print("\nRe-capture for the manual AFTER this build -- the current captures")
    print("show clipped buttons and a Total of 0 on a successful run.")
    print("\nCompile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
