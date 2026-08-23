#!/usr/bin/env python3
"""
patch_area_C_wire_2026-08-21.py

PATCH C of 3 — the UI handoff. REQUIRES A + B APPLIED.

The bbox handoff ALREADY EXISTS and works: ConvoyMapViewerScreen's
`onNavigateToTrailSources = { bbox -> ... }` receives a valid drawn bbox today.
The only thing that changes is what happens next -- instead of navigating to a
source-SELECTION screen, we resolve the states and run every source.

  before:  draw bbox -> writePendingArea -> navigate to source picker
  after :  draw bbox -> resolve states    -> executeArea, all sources, no checklist

Reuses HomeStatePickerScreen rather than building a second progress UI: it
already owns the running/done phases, the step indicators, the blinking
do-not-close banner and the completion recap with the new counters.

⚠ CODE RULE 1 (nullable-as-shortcut) -- justification for `areaBbox: DoubleArray?`:
the picker has TWO genuinely different entry modes. ABSENT = state mode: the
screen geocodes, offers the detected state, the rider picks. PRESENT = area mode:
the bbox is already known, detection is meaningless, and the screen enters at its
running phase. Both existing callers (the OSM test harness, and the authority gate
when it lands) legitimately omit it. This is a mode discriminator, not a shortcut
around wiring a parameter through.

NOT IN THIS PATCH: the BY STATE / BY AREA selector (checklist item 4). The entry
point today stays the existing "Import Trails by Area" checkbox, which is enough
to exercise the multi-state loop -- the thing that is actually untested.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: AREAWIRE-2026-08-21C
"""

import sys, os, shutil, datetime

MARKER = "AREAWIRE-2026-08-21C"
PRIOR_B = "AREABUILD-2026-08-21B"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
PICKER = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStatePickerScreen.kt"
MAPVIEW = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyMapViewerScreen.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ═══ PICKER ═════════════════════════════════════════════════════════
P1_OLD = '''fun HomeStatePickerScreen(
    onNavigateBack: () -> Unit = {}
) {'''

P1_NEW = '''fun HomeStatePickerScreen(
    onNavigateBack: () -> Unit = {},
    // ''' + MARKER + ''': CODE RULE 1 justification -- this is a MODE DISCRIMINATOR,
    // not a shortcut. ABSENT = state mode (geocode, offer, rider picks).
    // PRESENT = area mode (bbox already drawn; detection is meaningless and the
    // screen enters at its running phase). Order is S, W, N, E.
    areaBbox: DoubleArray? = null
) {'''

P2_OLD = '''    LaunchedEffect(Unit) {
        val states = withContext(Dispatchers.IO) { GeofabrikCatalog.load(context) }'''

P2_NEW = '''    LaunchedEffect(Unit) {
        // ''' + MARKER + ''': AREA MODE -- skip detection entirely. The states are
        // resolved from the DRAWN bbox; their own Geofabrik bboxes are only the
        // reference used to select them, never the area imported.
        if (areaBbox != null) {
            val all = withContext(Dispatchers.IO) { GeofabrikCatalog.load(context) }
            val hits = GeofabrikCatalog.findByBbox(
                all, areaBbox[0], areaBbox[1], areaBbox[2], areaBbox[3]
            )
            Log.i(TAG, "AREA import: bbox S=${areaBbox[0]} W=${areaBbox[1]} " +
                "N=${areaBbox[2]} E=${areaBbox[3]} -> ${hits.size} state(s): " +
                hits.joinToString(", ") { it.slug })
            phase = "running"
            // An empty state list is NOT an error -- catalog sources may still
            // intersect the box. The manifest records exactly what was resolved.
            HomeStateImportController.executeArea(
                context, areaBbox[0], areaBbox[1], areaBbox[2], areaBbox[3], hits
            )
            phase = "done"
            return@LaunchedEffect
        }
        val states = withContext(Dispatchers.IO) { GeofabrikCatalog.load(context) }'''

# ═══ MAP VIEWER ═════════════════════════════════════════════════════
M1_OLD = '''    var showHomeStatePicker by remember { mutableStateOf(false) }'''
M1_NEW = '''    var showHomeStatePicker by remember { mutableStateOf(false) }
    // ''' + MARKER + ''': non-null holds the drawn bbox (S,W,N,E) AND is the
    // "area import overlay is open" flag. One piece of state, not two -- two
    // flags for one concept is the 00f defect this codebase already carries.
    var areaImportBbox by remember { mutableStateOf<DoubleArray?>(null) }'''

M2_OLD = '''                        android.util.Log.i("DownloadPanel", "writePendingArea called, navigating...")
                        onNavigateToTrailSources()'''

M2_NEW = '''                        // ''' + MARKER + ''': THE DEVIATION POINT. The bbox handoff above
                        // is unchanged and already proven. What changes is the
                        // destination: no source SELECTION screen. Every source that
                        // intersects the box runs, because running them all is what
                        // makes the upsert enrichment work (design spec §4).
                        // ⚠ writePendingArea/launchMode above are now vestigial for
                        // this path -- remove in the cleanup pass, AFTER device verify.
                        android.util.Log.i("DownloadPanel",
                            "AREA IMPORT: launching for bbox S=${bbox.south} W=${bbox.west} " +
                            "N=${bbox.north} E=${bbox.east}")
                        areaImportBbox = doubleArrayOf(
                            bbox.south, bbox.west, bbox.north, bbox.east)
                        showDownloadPanel = false'''

M3_OLD = '''            if (showHomeStatePicker) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showHomeStatePicker = false
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216))
                ) {
                    HomeStatePickerScreen(
                        onNavigateBack = { showHomeStatePicker = false }
                    )
                }
            }'''

M3_NEW = M3_OLD + '''

            // ''' + MARKER + ''': AREA import overlay. Same screen as the state
            // picker -- it already owns the running/done phases, the step
            // indicators, the do-not-close banner and the completion recap.
            // Building a second progress UI would be two implementations of one
            // thing, which is the rule this release is meant to enforce.
            val areaBb = areaImportBbox
            if (areaBb != null) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    areaImportBbox = null
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216))
                ) {
                    HomeStatePickerScreen(
                        onNavigateBack = { areaImportBbox = null },
                        areaBbox = areaBb
                    )
                }
            }'''

FILES = [
    (PICKER, [
        ("picker signature + areaBbox", P1_OLD, P1_NEW),
        ("area mode in LaunchedEffect", P2_OLD, P2_NEW),
    ]),
    (MAPVIEW, [
        ("areaImportBbox state",        M1_OLD, M1_NEW),
        ("deviation point",             M2_OLD, M2_NEW),
        ("area import overlay",         M3_OLD, M3_NEW),
    ]),
]


def selftest():
    bad = []
    for _path, edits in FILES:
        for name, old, new in edits:
            for oname, oold, _ in edits:
                if oname != name and oold in new:
                    bad.append("%s replacement contains %s anchor" % (name, oname))
    return bad


def main():
    apply = "--apply" in sys.argv

    bad = selftest()
    if bad:
        print("ABORT - self-test failed:")
        print("\n".join("  " + b for b in bad))
        return 3

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
        if base == "HomeStatePickerScreen.kt" and PRIOR_B not in open(
                os.path.join(REPO, r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"),
                encoding="utf-8").read():
            print("ABORT: patch B (%s) not applied." % PRIOR_B); return 4

        problems = []
        for name, old, _new in edits:
            n = src.count(old)
            if n != 1:
                problems.append("  %-30s found %d times (need 1)" % (name, n))
            else:
                print("  OK  %-30s anchor matched" % name)
        if problems:
            print("\nABORT -- NO WRITE TO ANY FILE:")
            print("\n".join(problems))
            return 2

        out = src
        for _name, old, new in edits:
            out = out.replace(old, new, 1)
        results.append((path, True, out))

    if all(ok is None for _p, ok, _o in results):
        print("\nAll files already patched."); return 0

    print("\nMarkers after patch: %d" % sum(
        o.count(MARKER) for _p, ok, o in results if ok))

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
    print("\nThen on DROID 2: Map Features -> draw an area crossing a state line.")
    print("Read the manifest BEFORE the first download finishes:")
    print("  adb -s 24039703201775 shell ls -la /sdcard/Documents/GroupTrack/imports/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
