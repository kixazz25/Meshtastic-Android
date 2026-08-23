#!/usr/bin/env python3
"""
patch_manifest_ui_2026-08-21.py

Second half of the recap work. Patch 1 wrote the five counters into the
manifest; this carries them to the screen.

  HomeStateImportController.kt
    1. Five fields on ImportSourceProgress (default -1 = not reported)
    2. publishProgress() reads them out of the manifest JSON

  HomeStatePickerScreen.kt
    3. CompletionPanel: source line still shows RECORDS ADDED, unchanged.
       A twisty under each source reveals processed / selected / dupes /
       adds / unprocessed errors.
    4. `import androidx.compose.foundation.clickable` -- added ONLY if the
       file does not already have it.

-1 renders as "not reported", never as 0. A source whose ledger did not
carry a counter must not appear to have run clean.

DRY RUN BY DEFAULT.  Re-run with --apply to write.
Marker guard: MANIFESTUI-2026-08-21
"""

import sys, os, shutil, datetime

MARKER = "MANIFESTUI-2026-08-21"

REPO = r"C:\Users\kixaz\Meshtastic-Android"
CTRL = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"
UI   = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStatePickerScreen.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

CLICKABLE_IMPORT = "import androidx.compose.foundation.clickable"

# ─────────────────────────────────────────────────────────────────────
# CONTROLLER EDIT 1 — five fields on ImportSourceProgress
# ─────────────────────────────────────────────────────────────────────
C1_OLD = '''    val status: String,             // "pending" | "in_progress" | "completed" | "failed"
    val imported: Int = 0,
    val currentStep: String? = null, // "Downloading" | "Extracting" | "Importing" | "Cleanup"'''

C1_NEW = '''    val status: String,             // "pending" | "in_progress" | "completed" | "failed"
    val imported: Int = 0,
    // ''' + MARKER + ''': the recap five. -1 means NOT REPORTED by this source,
    // which is not the same as zero -- the UI must be able to say so.
    val processed: Int = -1,
    val selected: Int = -1,
    val dupes: Int = -1,
    val adds: Int = -1,
    val errors: Int = -1,
    val currentStep: String? = null, // "Downloading" | "Extracting" | "Importing" | "Cleanup"'''

# ─────────────────────────────────────────────────────────────────────
# CONTROLLER EDIT 2 — publishProgress carries them through
# ─────────────────────────────────────────────────────────────────────
C2_OLD = '''                imported = s.optInt("imported", 0),
                currentStep = s.optString("current_step", null),'''

C2_NEW = '''                imported = s.optInt("imported", 0),
                // ''' + MARKER + ''': manifests written before 2026-08-21 carry no
                // counters. Absent must read as -1 (not reported), never 0.
                processed = s.optInt("processed", -1),
                selected = s.optInt("selected", -1),
                dupes = s.optInt("dupes", -1),
                adds = s.optInt("adds", -1),
                errors = s.optInt("errors", -1),
                currentStep = s.optString("current_step", null),'''

# ─────────────────────────────────────────────────────────────────────
# UI EDIT — completion panel gets a per-source twisty
# ─────────────────────────────────────────────────────────────────────
U1_OLD = '''                progress?.sources?.forEach { src ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(src.name, color = txtLight, fontSize = 13.sp)
                        Text(
                            if (src.status == "completed") "${src.imported}" else "failed",
                            color = if (src.status == "completed") green else Color(0xFFCC4444),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }'''

U1_NEW = '''                progress?.sources?.forEach { src ->
                    // ''' + MARKER + ''': the summary line is unchanged -- records
                    // added, one number. Detail is behind a twisty so the recap
                    // does not overwhelm the rider.
                    var showDetail by remember(src.id) { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { showDetail = !showDetail }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                (if (showDetail) "\\u25BE  " else "\\u25B8  ") + src.name,
                                color = txtLight, fontSize = 13.sp
                            )
                            Text(
                                if (src.status == "completed") "${src.imported}" else "failed",
                                color = if (src.status == "completed") green else Color(0xFFCC4444),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        if (showDetail) {
                            Column(modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) {
                                RecapLine("Records processed", src.processed)
                                RecapLine("Records selected", src.selected)
                                RecapLine("Duplicates", src.dupes)
                                RecapLine("Adds", src.adds)
                                RecapLine("Unprocessed errors", src.errors)
                            }
                        }
                    }
                }'''

# helper composable, appended after CompletionPanel's opening marker use
U2_OLD = '''private fun CompletionPanel(progress: ImportProgress?, onDone: () -> Unit) {'''

U2_NEW = '''private fun RecapLine(label: String, value: Int) {
    // ''' + MARKER + ''': -1 is NOT zero. A counter the source never reported
    // must say so rather than imply a clean run.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = txtLight, fontSize = 11.sp)
        Text(
            if (value < 0) "not reported" else "$value",
            color = if (value < 0) Color(0xFF888888) else txtLight,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun CompletionPanel(progress: ImportProgress?, onDone: () -> Unit) {'''

CTRL_EDITS = [
    ("five fields on ImportSourceProgress", C1_OLD, C1_NEW),
    ("publishProgress carries the five",    C2_OLD, C2_NEW),
]

UI_EDITS = [
    ("completion panel twisty",  U1_OLD, U1_NEW),
    ("RecapLine helper",         U2_OLD, U2_NEW),
]


def patch_file(path, edits, add_import=False):
    """Returns (ok, newtext, notes). Verifies every anchor before changing."""
    notes = []
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()

    if MARKER in src:
        return None, src, ["  already patched (%s present)" % MARKER]

    problems = []
    for name, old, _new in edits:
        n = src.count(old)
        if n != 1:
            problems.append("  %-38s found %d times (need 1)" % (name, n))
        else:
            notes.append("  OK  %-38s anchor matched" % name)

    if problems:
        return False, src, problems

    out = src
    for _name, old, new in edits:
        out = out.replace(old, new, 1)

    if add_import:
        if CLICKABLE_IMPORT in out:
            notes.append("  --  clickable import already present, not added")
        else:
            # insert after the last existing import line
            lines = out.split("\n")
            last = max(i for i, l in enumerate(lines) if l.startswith("import "))
            lines.insert(last + 1, CLICKABLE_IMPORT)
            out = "\n".join(lines)
            notes.append("  OK  clickable import ADDED after line %d" % (last + 1))

    return True, out, notes


def main():
    apply = "--apply" in sys.argv
    ctrl = os.path.join(REPO, CTRL)
    ui = os.path.join(REPO, UI)

    for p in (ctrl, ui):
        if not os.path.isfile(p):
            print("ABORT: not found:\n  %s" % p)
            return 1

    print("HomeStateImportController.kt")
    ok1, out1, n1 = patch_file(ctrl, CTRL_EDITS)
    print("\n".join(n1))

    print("\nHomeStatePickerScreen.kt")
    ok2, out2, n2 = patch_file(ui, UI_EDITS, add_import=True)
    print("\n".join(n2))

    if ok1 is False or ok2 is False:
        print("\nABORT -- NO WRITE TO EITHER FILE.")
        print("Anchors did not match verbatim. Re-read and regenerate rather")
        print("than loosening them -- a half-applied pair is worse than none.")
        return 2

    if ok1 is None and ok2 is None:
        print("\nBoth files already patched. Nothing to do.")
        return 0

    if not apply:
        print("\nDRY RUN -- NOTHING WAS WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__))
        return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    for path, ok, out in ((ctrl, ok1, out1), (ui, ok2, out2)):
        if not ok:
            continue
        base = os.path.basename(path)
        backup = os.path.join(BACKUP_DIR, "%s.bak_%s" % (base, stamp))
        shutil.copy2(path, backup)
        with open(path, "w", encoding="utf-8") as f:
            f.write(out)
        print("  wrote %s   (backup: %s)" % (base, backup))

    print("\nNow compile ONCE for both patches:")
    print('  ./gradlew compileGoogleReleaseKotlin && \\')
    print('    grep -c "MANIFESTCOUNTS-2026-08-21" app/src/main/java/com/geeksville/mesh/convoy/HomeStateImportController.kt && \\')
    print('    grep -c "%s" app/src/main/java/com/geeksville/mesh/convoy/HomeStatePickerScreen.kt' % MARKER)
    print("  (expect BUILD SUCCESSFUL, then 5, then 3)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
