#!/usr/bin/env python3
"""
patch_gate_F_sweep_and_check_2026-08-21.py

PATCH F — the two pieces the authority startup job needs. NO GATE EDIT.

Design: living master, 00-GATE-FINAL (08-16, UPDATED 08-21).
Supersedes unified-spec §8 and the recovery half of §7A.

Adds to HomeStateImportController:

  sweepManifests(ctx)      completed -> history; incomplete -> stamped killed,
                           then history. Runs EVERY launch. Fred: "completed is
                           swept, incomplete is killed on startup. Period."

  needsTrailData(ctx)      spatial DB file exists? -> trails > 0? Both misses
                           mean Home State.

Both are standalone and callable from anywhere, so they can be proven before
ConvoyAuthorityGateScreenV2.kt -- certified across three paths on 08-16 -- is
touched at all. Patch G wires them in.

Also makes SpatialDbManager.dbDir() internal (was private), the same move made
for loadSourceCatalog on 08-20, rather than duplicating a path that already
appears nine times in that file.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: GATEJOB-2026-08-21F
"""

import sys, os, shutil, datetime

MARKER = "GATEJOB-2026-08-21F"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
CTRL = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"
SPAT = r"app\src\main\java\com\geeksville\mesh\convoy\SpatialDbManager.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── F1: dbDir private -> internal ───────────────────────────────────
F1_OLD = '''    private fun dbDir(): File {'''
F1_NEW = '''    // ''' + MARKER + ''': internal so the startup job can test for the DB FILE
    // without opening it. init() calls openOrCreateDatabase, which creates an
    // empty schema where real data should be -- the 08-01 mechanism.
    internal fun dbDir(): File {'''

# ── F2: the two functions, appended before hasCompletedImport ───────
F2_OLD = '''    fun hasCompletedImport(ctx: Context): Boolean {'''

F2_NEW = '''    // ═══ ''' + MARKER + ''' — THE AUTHORITY STARTUP JOB ═══════════════
    //
    // Runs inside the authority task, AFTER the gate's checks pass and BEFORE
    // Convoy. That slot is what makes both of these safe: the all-files grant is
    // already proven by use, and nothing can have launched an import yet.

    /**
     * Resolve every manifest left in imports/. Runs EVERY launch.
     *
     *   every source has a count  -> completed -> move to history
     *   any source has no count   -> stamp killed -> move to history
     *
     * Afterwards imports/ is empty. Fred, 08-21: "completed is swept,
     * incomplete is killed on startup. Period."
     *
     * ⛔ ORDER IS LOAD-BEARING: STAMP FIRST, THEN MOVE. Same reason
     * OsmImportLedger.archiveLedger() renames before discardState() sweeps. If
     * the stamp fails nothing has moved and it retries next launch. If the move
     * fails after a good stamp, a marked file sits in imports/ -- visible and
     * recoverable. The state to avoid is a file in history that nobody stamped,
     * because then "killed" has to be INFERRED rather than read.
     *
     * ⚠ Manifests written before 2026-08-21 have no manifest_id and none of the
     * five recap counters -- only `imported`. ABSENT FIELDS MEAN OLD, NOT BROKEN.
     * A pre-today completed manifest must sweep as completed; if this test
     * demanded the new fields, a device holding 43,348 records would read as
     * never-imported.
     *
     * ⚠ NEVER call this anywhere but the startup job. Run while an import is
     * live, it would archive the controller's own manifest out from under it.
     */
    fun sweepManifests(ctx: Context): Int {
        val dir = importsDir(ctx)
        val history = File(dir, "history").apply { if (!exists()) mkdirs() }
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: return 0
        if (files.isEmpty()) {
            Log.i(TAG, "sweep: imports/ is empty, nothing to do")
            return 0
        }

        var swept = 0
        for (f in files) {
            try {
                val json = JSONObject(f.readText())
                val sources = json.optJSONArray("sources")

                // "counted" = the source reported an outcome. Old manifests carry
                // `imported` only; new ones carry the five. Either satisfies it.
                var allCounted = sources != null && sources.length() > 0
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val s = sources.getJSONObject(i)
                        val counted = s.has("imported") &&
                            s.optString("status") == "completed"
                        if (!counted) { allCounted = false; break }
                    }
                }

                if (!allCounted) {
                    // STAMP FIRST.
                    json.put("process_state", "killed")
                    json.put("killed_at", iso8601Now())
                    f.writeText(json.toString(2))
                }

                // THEN MOVE. Name by manifest_id where present -- it carries a
                // timestamp, so two runs on the same day stop colliding in
                // history. No id means an old manifest: keep its filename.
                val id = json.optString("manifest_id", "")
                val target = if (id.isBlank()) File(history, f.name)
                             else File(history, "$id.json")

                if (f.renameTo(target)) {
                    swept++
                    Log.i(TAG, "sweep: ${f.name} -> history/${target.name} " +
                        "(${if (allCounted) "completed" else "KILLED"})")
                } else {
                    Log.w(TAG, "sweep: could not move ${f.name} -- left in place, " +
                        "will retry next launch")
                }
            } catch (e: Exception) {
                // A manifest we cannot parse is still evidence. Never delete it.
                Log.e(TAG, "sweep: ${f.name} unreadable, left in place: ${e.message}")
            }
        }
        Log.i(TAG, "sweep: $swept of ${files.size} manifest(s) moved to history")
        return swept
    }

    /**
     * Does this rider need trail data? Two tests, both misses land on Home State.
     *
     *   1. spatial DB FILE does not exist  -> true  (fresh install)
     *   2. exists but holds zero trails    -> true
     *   3. otherwise                       -> false (straight to the Ride Map)
     *
     * ⛔ THE FILE CHECK MUST NOT OPEN THE DATABASE. SpatialDbManager.init() calls
     * openOrCreateDatabase, which writes an empty schema where a file is missing.
     * On a genuinely new install that is harmless; where a file is missing for any
     * OTHER reason it is the 08-01 data-loss mechanism. One File.exists() buys the
     * guarantee outright instead of depending on when it is safe.
     *
     * ⚠ A FAILED QUERY FALLS THROUGH TO THE MAP, not to Home State. Fred: "query
     * has to be successful to request home state add." Locked, mid-write or
     * unreadable is not evidence of emptiness, and the panel is always available.
     */
    fun needsTrailData(ctx: Context): Boolean {
        val dbFile = File(SpatialDbManager.dbDir(), "grouptrack_spatial.db")
        if (!dbFile.exists()) {
            Log.i(TAG, "needsTrailData: no spatial DB file -> HOME STATE")
            return true
        }
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val count = db.use { d ->
                d.rawQuery("SELECT COUNT(*) FROM trails", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
            }
            when {
                count < 0 -> {
                    Log.w(TAG, "needsTrailData: count unreadable -> RIDE MAP (panel available)")
                    false
                }
                count == 0 -> {
                    Log.i(TAG, "needsTrailData: 0 trails -> HOME STATE")
                    true
                }
                else -> {
                    Log.i(TAG, "needsTrailData: $count trails -> RIDE MAP")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "needsTrailData: query failed (${e.message}) -> RIDE MAP (panel available)")
            false
        }
    }

    fun hasCompletedImport(ctx: Context): Boolean {'''

FILES = [
    (SPAT, [("dbDir internal", F1_OLD, F1_NEW)]),
    (CTRL, [("sweep + needsTrailData", F2_OLD, F2_NEW)]),
]


def main():
    apply = "--apply" in sys.argv
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
                problems.append("  %-26s found %d times (need 1)" % (name, n))
            else:
                print("  OK  %-26s anchor matched" % name)
        if problems:
            print("\nABORT -- NO WRITE TO ANY FILE:"); print("\n".join(problems)); return 2

        out = src
        for _name, old, new in edits:
            out = out.replace(old, new, 1)
        results.append((path, True, out))

    if all(ok is None for _p, ok, _o in results):
        print("\nAlready patched."); return 0

    # imports the new code needs, checked not assumed
    ctrl_out = [o for p, ok, o in results if ok and p.endswith("HomeStateImportController.kt")]
    if ctrl_out:
        o = ctrl_out[0]
        for imp in ("import java.io.File", "import org.json.JSONObject"):
            if imp not in o:
                print("\nABORT -- %s missing from HomeStateImportController.kt." % imp)
                print("  The new code needs it. No write."); return 5
        print("  OK  File + JSONObject imports present")

    print("\nMarkers after patch: %d (expect 2)" % sum(
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

    print("\nNothing calls either function yet -- the gate is untouched.")
    print("Compile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
