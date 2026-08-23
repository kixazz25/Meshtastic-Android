#!/usr/bin/env python3
"""
patch_gate_G_wire_2026-08-21.py

PATCH G — wires the startup job into the authority loop. REQUIRES F.

Design: living master, 00-GATE-FINAL (08-16, UPDATED 08-21).

    authority checks pass
        -> SWEEP imports/ (every launch, silent)
        -> trails > 0 ?  no  -> NeedTrailData  -> Home State picker
                         yes -> Granted        -> Ride Map

⛔ THE THREE CERTIFIED PATHS ARE NOT MODIFIED (device-verified 08-16):
   - clean pass renders NO gate at all (passedOnEntry)
   - the Continue settle-barrier survives only for the granted-during-session path
   - StorageDeclined has NO proceed door
NeedTrailData is a branch BESIDE them, evaluated only once storage and background
have already passed. Nothing that reaches the existing states behaves differently.

⚠ WHY THE JOB IS IN evaluateState AND NOT AT onProceed:
Fred, 08-21 -- "if authority is not given then this is part of that loop." The gate
is the nav START destination, so it runs every launch even when it renders nothing.
Putting the job in the loop is what makes the sweep run on an ESTABLISHED device,
where the gate passes silently and no screen is ever shown.

⚠ evaluateState is called repeatedly (every resume, and per retry attempt). The
sweep moves files and the trail check opens a database -- neither belongs in
something that fires on a loop. Both are therefore guarded by a one-shot latch:
the job runs ONCE per process, on the first evaluation that gets past authority.

WHAT THE DB CHECK IS ACTUALLY FOR (settled with Fred, 08-21): the databases are
created by the activity, so on a genuinely fresh install the file EXISTS and is
empty -- the trail count is what answers. The file-exists branch covers the
uninstall-leaving-data-then-reinstall case, the same scenario the 08-16 marker fix
was built for: real data on disk, count is high, rider goes straight to the map.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: GATEJOB-2026-08-21G
"""

import sys, os, shutil, datetime

MARKER = "GATEJOB-2026-08-21G"
PRIOR_F = "GATEJOB-2026-08-21F"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
GATE = r"app\src\main\java\com\geeksville\mesh\convoy\ConvoyAuthorityGateScreenV2.kt"
CTRL = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── G1: the new state ───────────────────────────────────────────────
G1_OLD = '''    object Granted           : AuthorityState()   // everything satisfied
}'''

G1_NEW = '''    object Granted           : AuthorityState()   // everything satisfied
    // ''' + MARKER + ''': authority is satisfied but the rider has no trails.
    // Evaluated only AFTER storage and background pass, so it can never
    // pre-empt or alter any of the three certified authority paths.
    object NeedTrailData     : AuthorityState()   // authority OK, zero trails -> Home State
}

// ''' + MARKER + ''': one-shot latch. evaluateState runs on every resume and on
// every retry attempt; the sweep MOVES FILES and the trail check OPENS A DATABASE.
// Neither belongs in something that fires on a loop, so the job runs once per
// process, on the first evaluation that gets past authority.
private var startupJobDone = false'''

# ── G2: run the job inside the evaluation ───────────────────────────
G2_OLD = '''    val result = when {
        !storage    -> AuthorityState.NeedStorage
        !background -> AuthorityState.NeedBackground
        else        -> AuthorityState.Granted
    }'''

G2_NEW = '''    // ''' + MARKER + ''': THE STARTUP JOB. Runs inside the authority loop, which is
    // what makes it run on an ESTABLISHED device too -- there the gate evaluates,
    // passes silently and renders nothing, but this still executes.
    // Slot confirmed 08-16: AFTER the authority gate, BEFORE Convoy. The all-files
    // grant is already proven by real use at this point, and nothing can have
    // launched an import yet, so the sweep cannot collide with a live manifest.
    if (storage && background && !startupJobDone) {
        startupJobDone = true
        try {
            HomeStateImportController.sweepManifests(context)
        } catch (e: Exception) {
            // Housekeeping must never block the gate. A sweep that fails leaves
            // the manifests where they are and retries next launch.
            android.util.Log.e("ConvoyGate", "startup sweep failed: " + e.message)
        }
    }

    val needsTrails = if (storage && background) {
        try {
            HomeStateImportController.needsTrailData(context)
        } catch (e: Exception) {
            // Never strand the rider at the gate over a failed check.
            android.util.Log.e("ConvoyGate", "trail check failed: " + e.message)
            false
        }
    } else false

    val result = when {
        !storage     -> AuthorityState.NeedStorage
        !background  -> AuthorityState.NeedBackground
        needsTrails  -> AuthorityState.NeedTrailData
        else         -> AuthorityState.Granted
    }'''

# ── G3: passedOnEntry must not swallow the new state ────────────────
G3_OLD = '''    val passedOnEntry = remember { state is AuthorityState.Granted }'''

G3_NEW = '''    val passedOnEntry = remember { state is AuthorityState.Granted }
    // ''' + MARKER + ''': NeedTrailData is deliberately NOT part of passedOnEntry.
    // passedOnEntry means "nothing to say, proceed without a screen"; this state
    // exists precisely to show one. Keeping them separate is what leaves the
    // certified clean-pass behaviour exactly as it was.'''

# ── G4: render branch ───────────────────────────────────────────────
G4_OLD = '''                is AuthorityState.Granted -> {
                    GateBody(
                        title = "Access granted",'''

G4_NEW = '''                // ''' + MARKER + ''': authority satisfied, no trails yet. The picker
                // owns this surface -- it already carries the state list, the
                // progress display and the completion recap. Full-screen because
                // the import is the whole task at this point.
                is AuthorityState.NeedTrailData -> {
                    HomeStatePickerScreen(
                        onNavigateBack = {
                            // Re-evaluate rather than proceed: if the rider imported,
                            // trails now exist and this resolves to Granted. If they
                            // backed out, the gate asks again -- the setup is offered
                            // again on next launch by design.
                            state = evaluateState(context)
                            if (state is AuthorityState.NeedTrailData) onProceed()
                        }
                    )
                }
                is AuthorityState.Granted -> {
                    GateBody(
                        title = "Access granted",'''

FILES = [
    (GATE, [
        ("NeedTrailData state + latch", G1_OLD, G1_NEW),
        ("startup job in evaluation",   G2_OLD, G2_NEW),
        ("passedOnEntry note",          G3_OLD, G3_NEW),
        ("picker render branch",        G4_OLD, G4_NEW),
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

    ctrl = os.path.join(REPO, CTRL)
    if PRIOR_F not in open(ctrl, encoding="utf-8").read():
        print("ABORT: patch F (%s) is not applied. Run it first." % PRIOR_F); return 4

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

        problems = []
        for name, old, _new in edits:
            n = src.count(old)
            if n != 1:
                problems.append("  %-30s found %d times (need 1)" % (name, n))
            else:
                print("  OK  %-30s anchor matched" % name)
        if problems:
            print("\nABORT -- NO WRITE:"); print("\n".join(problems)); return 2

        out = src
        for _name, old, new in edits:
            out = out.replace(old, new, 1)
        results.append((path, True, out))

    if all(ok is None for _p, ok, _o in results):
        print("\nAlready patched."); return 0

    out = [o for _p, ok, o in results if ok][0]

    # the three certified paths must still be present, untouched
    for probe, label in (
        ("if (passedOnEntry) onProceed()", "clean-pass proceed"),
        ("is AuthorityState.StorageDeclined", "StorageDeclined branch"),
        ("Final settle barrier", "Continue settle barrier"),
    ):
        if probe not in out:
            print("\nABORT -- certified path missing after patch: %s" % label); return 5
    print("  OK  three certified paths intact")

    # the picker is a composable in the same package -- no import needed, but the
    # gate must not already reference it under another name
    print("  OK  picker referenced as HomeStatePickerScreen")
    print("\nMarkers after patch: %d (expect 5)" % out.count(MARKER))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    for path, ok, o in results:
        if not ok:
            continue
        base = os.path.basename(path)
        backup = os.path.join(BACKUP_DIR, "%s.bak_%s" % (base, stamp))
        shutil.copy2(path, backup)
        with open(path, "w", encoding="utf-8") as f:
            f.write(o)
        print("  wrote %s  (backup %s)" % (base, backup))

    print("\n⚠ THIS EDITS THE CERTIFIED GATE. After the build, re-verify all three")
    print("  authority paths on Droid 2 before trusting the new one:")
    print("    adb -s 24039703201775 logcat -s ConvoyGate")
    print("\nCompile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
