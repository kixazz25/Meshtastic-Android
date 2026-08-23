#!/usr/bin/env python3
"""
patch_area_D_selector_2026-08-21.py

PATCH D of 4 — the entry point. REQUIRES A + B + C APPLIED.

Design spec §1B / §2 (grouptrack_design_unified_import_2026-08-20.html):

    "Import Trails"        -> selector: BY STATE / BY AREA
       BY STATE            -> state list, drills down to a state
       BY AREA             -> download panel pre-checked -> draw bbox
    "Import OSM Data"      -> REMOVED

Both branches feed the SAME import process. The import process is the manifest,
the loop, the progress display and the completion recap. Everything upstream
only decides a bbox and writes the JSON -- selection UI does no work.

WHAT THIS FIXES that patch C did not: there are TWO exits to the old
source-selection screen, not one. C replaced the panel's own callback
(onNavigateToTrailSources at ~:2829). The one that actually fires is the panel's
EXECUTE label (~:2678). Both now route to the area import.

BY STATE deliberately has NO GPS detection and NO pre-selection and NO
"home state" language. Home State is the INSTALL-sequence concept and keeps its
own framing for the gate. This is any state -- a trip to Colorado.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: TRAILSELECT-2026-08-21D
"""

import sys, os, shutil, datetime

MARKER = "TRAILSELECT-2026-08-21D"
PRIOR_C = "AREAWIRE-2026-08-21C"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
MAPVIEW = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyMapViewerScreen.kt"
PICKER  = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStatePickerScreen.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ═══ PICKER: a "skip detection, straight to the list" mode ══════════
# Same screen, same import, same progress + recap. Only the ENTRY differs:
#   areaBbox != null  -> running   (area)
#   anyState == true  -> list      (any state, nothing pre-selected)
#   otherwise         -> detecting (home state, GPS pre-select -- the gate)
P1_OLD = '''    areaBbox: DoubleArray? = null
) {'''

P1_NEW = '''    areaBbox: DoubleArray? = null,
    // ''' + MARKER + ''': ANY-STATE mode. Home State is the INSTALL concept --
    // GPS detect, "your Home state is X", pre-selected. This is a trip to
    // Colorado: no detection, no pre-selection, no home-state language.
    // Same screen, same import process, different entry phase.
    anyState: Boolean = false
) {'''

P2_OLD = '''        val states = withContext(Dispatchers.IO) { GeofabrikCatalog.load(context) }
        allStates = GeofabrikCatalog.displayList(states)
        val detected = withContext(Dispatchers.IO) { GeofabrikCatalog.detectHomeState(context) }'''

P2_NEW = '''        val states = withContext(Dispatchers.IO) { GeofabrikCatalog.load(context) }
        allStates = GeofabrikCatalog.displayList(states)
        // ''' + MARKER + ''': any-state entry -- straight to the list, nothing chosen.
        if (anyState) {
            phase = "list"
            Log.i(TAG, "ANY-STATE import: ${allStates.size} states, no pre-selection")
            return@LaunchedEffect
        }
        val detected = withContext(Dispatchers.IO) { GeofabrikCatalog.detectHomeState(context) }'''

# ═══ MAP VIEWER ════════════════════════════════════════════════════
M1_OLD = '''    var areaImportBbox by remember { mutableStateOf<DoubleArray?>(null) }'''
M1_NEW = '''    var areaImportBbox by remember { mutableStateOf<DoubleArray?>(null) }
    // ''' + MARKER + ''': the Import Trails selector, and the any-state picker
    // it drills down to. Two booleans because they are two different screens,
    // not two names for one state.
    var showTrailImportSelector by remember { mutableStateOf(false) }
    var showAnyStatePicker by remember { mutableStateOf(false) }'''

# ── the menu dispatch: Trails -> selector, OSM entry removed ────────
M2_OLD = '''                        "Trails" -> onNavigateToTrailSources()'''
M2_NEW = '''                        // ''' + MARKER + ''': was onNavigateToTrailSources() -- the old
                        // source-SELECTION screen. Now the BY STATE / BY AREA selector.
                        "Trails" -> showTrailImportSelector = true'''

M3_OLD = '''                        "OSM" -> showHomeStatePicker = true'''
M3_NEW = '''                        // ''' + MARKER + ''': "Import OSM Data" REMOVED (design §2). OSM is
                        // no longer a separate concept -- it is one source inside
                        // Import Trails. Screenshots captured 08-21 before removal.
                        "OSM" -> { /* removed - see Import Trails */ }'''

# ── the panel EXECUTE label: the exit that actually fires ───────────
M4_OLD = '''                                if (panelTrailsChecked && downloadBbox.isValid) {
                                    // LAUNCHMODE-FIX-2026-07-27: the panel callback at ~:1711
                                    // sets this; THIS path never did, so the trail screen opened
                                    // on SELECT_SOURCE and ignored the area just written.
                                    TrailImporter.launchMode = TrailImporter.LaunchMode.BY_AREA
                                    TrailImporter.writePendingArea(
                                        downloadBbox.north, downloadBbox.south,
                                        downloadBbox.east, downloadBbox.west)
                                    onNavigateToTrailSources()
                                }'''

M4_NEW = '''                                if (panelTrailsChecked && downloadBbox.isValid) {
                                    // ''' + MARKER + ''': THE EXIT THAT ACTUALLY FIRES.
                                    // Patch C replaced the panel's own callback; this
                                    // EXECUTE-label path is the second exit to the same
                                    // old screen and was still live. Both now run the
                                    // area import -- every intersecting source, no
                                    // selection step (design §4: no checklist, ever).
                                    // ⚠ launchMode/writePendingArea are vestigial here;
                                    // remove in the cleanup pass after device verify.
                                    android.util.Log.i("DownloadPanel",
                                        "AREA IMPORT (exec): S=${downloadBbox.south} " +
                                        "W=${downloadBbox.west} N=${downloadBbox.north} " +
                                        "E=${downloadBbox.east}")
                                    areaImportBbox = doubleArrayOf(
                                        downloadBbox.south, downloadBbox.west,
                                        downloadBbox.north, downloadBbox.east)
                                    showDownloadPanel = false
                                }'''

# ── the selector overlay + any-state overlay ───────────────────────
M5_OLD = '''            val areaBb = areaImportBbox'''
M5_NEW = '''            // ''' + MARKER + ''': IMPORT TRAILS -- the selector. Selection UI only;
            // it decides which entry point runs and does no work itself.
            if (showTrailImportSelector) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showTrailImportSelector = false
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216)),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Import Trails",
                            color = Color(0xFF7BB661),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                        Text(
                            "Load trails, scenic points and places from every\\n" +
                            "available source for a whole state or a drawn area.",
                            color = Color(0xFF8899AA),
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(28.dp))

                        // BY STATE -> any-state list. No GPS, no pre-selection.
                        androidx.compose.material3.Button(
                            onClick = {
                                showTrailImportSelector = false
                                showAnyStatePicker = true
                            },
                            modifier = Modifier.width(260.dp)
                        ) {
                            Text("BY STATE", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text(
                            "A whole state - a trip to Colorado",
                            color = Color(0xFF667788), fontSize = 11.sp
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))

                        // BY AREA -> download panel, row pre-checked, rider draws.
                        androidx.compose.material3.Button(
                            onClick = {
                                showTrailImportSelector = false
                                panelTrailsChecked = true
                                showDownloadPanel = true
                            },
                            modifier = Modifier.width(260.dp)
                        ) {
                            Text("BY AREA", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text(
                            "Draw a box - may cross state lines",
                            color = Color(0xFF667788), fontSize = 11.sp
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))

                        androidx.compose.material3.TextButton(
                            onClick = { showTrailImportSelector = false }
                        ) {
                            Text("Cancel", color = Color(0xFF8899AA), fontSize = 13.sp)
                        }
                    }
                }
            }

            // ''' + MARKER + ''': ANY-STATE picker. Same screen and same import
            // process as Home State; it simply enters at the list.
            if (showAnyStatePicker) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showAnyStatePicker = false
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216))
                ) {
                    HomeStatePickerScreen(
                        onNavigateBack = { showAnyStatePicker = false },
                        anyState = true
                    )
                }
            }

            val areaBb = areaImportBbox'''

FILES = [
    (PICKER, [
        ("picker anyState param",   P1_OLD, P1_NEW),
        ("any-state entry phase",   P2_OLD, P2_NEW),
    ]),
    (MAPVIEW, [
        ("selector state vars",     M1_OLD, M1_NEW),
        ("Trails -> selector",      M2_OLD, M2_NEW),
        ("OSM entry removed",       M3_OLD, M3_NEW),
        ("panel EXECUTE exit",      M4_OLD, M4_NEW),
        ("selector + anystate UI",  M5_OLD, M5_NEW),
    ]),
]


def selftest():
    bad = []
    for _p, edits in FILES:
        for name, old, new in edits:
            for oname, oold, _ in edits:
                if oname != name and oold in new and oold != old:
                    bad.append("%s replacement contains %s anchor" % (name, oname))
    return bad


def main():
    apply = "--apply" in sys.argv

    bad = selftest()
    if bad:
        print("ABORT - self-test failed:"); print("\n".join("  " + b for b in bad)); return 3

    results = []
    for rel, edits in FILES:
        path = os.path.join(REPO, rel)
        base = os.path.basename(rel)
        if not os.path.isfile(path):
            print("ABORT: not found:\n  %s" % path); return 1
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()

        print(base)
        if MARKER in src:
            print("  already patched"); results.append((path, None, src)); continue
        if PRIOR_C not in src:
            print("ABORT: patch C (%s) not applied to %s." % (PRIOR_C, base)); return 4

        problems = []
        for name, old, _new in edits:
            n = src.count(old)
            if n != 1:
                problems.append("  %-28s found %d times (need 1)" % (name, n))
            else:
                print("  OK  %-28s anchor matched" % name)
        if problems:
            print("\nABORT -- NO WRITE TO ANY FILE:"); print("\n".join(problems)); return 2

        out = src
        for _name, old, new in edits:
            out = out.replace(old, new, 1)
        results.append((path, True, out))

    if all(ok is None for _p, ok, _o in results):
        print("\nAll files already patched."); return 0

    print("\nMarkers after patch: %d" % sum(o.count(MARKER) for _p, ok, o in results if ok))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    for path, ok, out in results:
        if not ok:
            continue
        base = os.path.basename(path)
        backup = os.path.join(BACKUP_DIR, "%s.bak_%s" % (base, stamp))
        shutil.copy2(path, backup)
        with open(path, "w", encoding="utf-8") as f:
            f.write(out)
        print("  wrote %s  (backup %s)" % (base, backup))

    print("\nCompile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
