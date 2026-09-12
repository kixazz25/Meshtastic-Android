package com.geeksville.mesh.convoy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * StartupHousekeeping — the ONE job that runs before the app. HOUSEKEEP-2026-09-03.
 *
 * ⛔⛔ WHY THIS FILE EXISTS. Fred, 09-03: *"we had a very specific design that
 * required these activities firing before the convoy map loaded — the release
 * test, the clear, the db alter and the reload, all in a single thread. What we
 * got looked nothing like it."*
 *
 * He is right. The pieces were put wherever each one seemed to fit: the clear in
 * the gate's job, the ALTERs on the DATABASE-OPEN path, and the reload INFERRED
 * from a row count that some other thread happened to read. Three places, three
 * threads, and the ordering held only by luck.
 *
 * ⛔ THE 09-03 LOG, WHICH IS WHY THIS IS NOT A STYLE ARGUMENT:
 *     05:37:50  gate evaluates -- 145,942 trails -> Granted
 *     05:38:03  Database init failed: database is locked
 *     05:38:34  Database init failed: database is locked
 *     05:38:45  opens -- 0 trails
 *     05:38:58  clearTrailsOnce removes 145,942 rows
 * ⭐ The gate answered SIXTY-EIGHT SECONDS before the clear ran. The rider was
 * let into a map with no trails and no prompt, while a delete was still running.
 * Fred: *"we are lucky this did not ANR."*
 *
 * ⭐⭐ THE RULE THIS ENCODES: one job, one thread, four steps in order, and the
 * gate does not answer until it returns.
 *
 *     1. RELEASE CHECK   is this device on the current schema marker?
 *     2. CLEAR           empty the trails
 *     3. ALTER           bring the EMPTY table to the current schema
 *     4. RELOAD          say so, so the gate routes to the import picker
 *
 * ⛔ AND THE ALTER ONLY EVER RUNS ON AN EMPTY TABLE. Fred, 09-03: *"altering a
 * table with no data being populated — no, I do not want that. It needs to be
 * cleared and reloaded if the table is incorrect."* A column added beside
 * populated rows is exactly the half-migrated state that cost 09-02: data that
 * looks fine until something reads it.
 */
object StartupHousekeeping {

    private const val TAG = "Housekeeping"

    /**
     * ⭐ THE SCHEMA MARKER. Bump it and every device clears, re-alters and
     * reloads on next launch -- one path, tested, no per-column migration
     * reasoning anywhere.
     * ⚠ The file lives beside the DATABASES in shared storage, not in
     * SharedPreferences: prefs are wiped by "clear data" while the databases
     * survive, which would have re-cleared a device that did not need it.
     */
    private const val SCHEMA_MARKER = ".schema_2026-09-03A"

    /** Every column `trails` must have beyond its original shape. */
    private val TRAIL_COLUMNS = listOf(
        "status", "land_status", "use_type", "carto_code_source",
    )

    /** What the job decided, for the gate to render and act on. */
    data class Result(
        val ran: Boolean,          // did this launch do the work?
        val cleared: Int,          // rows removed
        val needsReload: Boolean,  // route to the import picker
        val error: String? = null,
    )

    /**
     * ⛔ BLOCKING, AND DELIBERATELY SO. The caller must not proceed until this
     * returns. Everything that made 09-03 fail came from something continuing
     * while this work was still going on.
     */
    @Synchronized
    /**
     * EVERYLAUNCH-2026-09-08: the SECOND housekeeping pass -- the one with no
     * marker.
     *
     * ⭐ WHY THERE ARE TWO (Fred, 09-08). [run] checks its marker first and
     * returns immediately when present, so everything inside it fires only on
     * the launches that clear and reload trails. That is right for a
     * destructive one-shot and wrong for convergence: a category added to the
     * shipped palette today would reach a rider on their next full reload,
     * which could be months.
     *
     * ⭐ AND THE POINT IS THE DECLARED PLACE, not the one task in it. The
     * 08-16 startup survey exists because one-shots were scattered through the
     * startup stack by hunt-seek-find. Anything that must converge a device to
     * the current build belongs here, where it can be found.
     *
     * ⛔ THE RULES FOR ANYTHING ADDED HERE:
     *   • CHEAP. It runs on every launch. A startup tax accumulates unnoticed.
     *   • IDEMPOTENT. The second run must find nothing to do.
     *   • NON-DESTRUCTIVE. Nothing here may delete or overwrite rider data --
     *     that is [run]'s job, behind a marker, on purpose.
     *   • It must never throw. A convergence failure is not a reason to stop
     *     the app starting.
     */
    fun everyLaunch(ctx: Context) {
        // DEFAULTSEVERY-2026-09-11: \u26d4 THE FILE FIRST, THEN THE CATEGORIES.
        // ensureDefaults used to run ONLY inside run(), behind the schema
        // marker -- so a rider with no map_keys.json on an ordinary launch got
        // no palette written at all. That was invisible while each map's HTML
        // still carried a hardcoded TRAIL_STYLE to fall back on. \u26a0 Once that
        // literal is emptied, the same gap draws EVERY TRAIL CYAN.
        //
        // \u2b50 It belongs here by this function's own rules: a File.exists()
        // check is cheap, it does nothing when the file is present, and it
        // writes only when absent -- it cannot overwrite anything the rider
        // chose. \u26a0 It stays in run() as well, where the palette must exist
        // before the import classifies anything.
        // HOUSEKEEPING-2026-09-12: ⛔ EMPTIED, DELIBERATELY. Everything that
        // was here is now a job inside run(), in phase 1, where it belongs.
        //
        // ⚠ THIS FUNCTION IS KEPT AS A NO-OP rather than deleted because the
        // gate still calls it; removing the call is a separate edit and this
        // must not break startup in between.
        //
        // ⛔ DO NOT ADD JOBS HERE. run() is the container -- see the rules on
        // it. A job added here runs AFTER init() has already opened the
        // database files, which is the exact mistake the restructure fixes.
        Log.d(TAG, "everyLaunch: no-op -- jobs live in run()")
    }

    /**
     * HOUSEKEEPING-2026-09-12: THE startup container. One call, every launch.
     *
     * ══════════════════════════════════════════════════════════════════
     *  THE RULES. Read these before adding anything.
     * ══════════════════════════════════════════════════════════════════
     *
     * \u2b50 1. ONE CONTAINER. Every startup cleanup job lives here. Called once,
     *    after authority resolves and before any map loads. \u26a0 There used to be
     *    TWO entry points -- run() and everyLaunch() -- and the gate called them
     *    in sequence with init() inside the first. Anything added to the second
     *    that touched the database FILES was already too late.
     *
     * \u2b50 2. SEQUENTIAL, NEVER CONCURRENT. No job begins before the previous
     *    returns. \u26a0 This is what makes the ordering rule below meaningful --
     *    with threads there would be no ordering to reason about, only races.
     *
     * \u26d4 3. TWO PHASES, AND init() IS THE BOUNDARY.
     *
     *      PHASE 1 -- anything that MOVES, COPIES or REPLACES the database
     *                 files. They must be closed.
     *      init()  -- OPENS the database files.
     *      PHASE 2 -- anything that reads or writes their CONTENTS. They must
     *                 be open.
     *
     *    \u26a0 The boundary is not a preference and not a position. A schema ALTER
     *    belongs in phase 2 because it needs an open database; a file migration
     *    belongs in phase 1 because init() would be holding the very file it
     *    replaces. \u26d4 Reverse them and the ALTER runs on an empty store which
     *    the migration then copies over -- both jobs "succeed" and the result is
     *    wrong.
     *
     * \u26d4 4. EVERY JOB GATES ITSELF. A one-time job owns its own marker or
     *    record, checks it itself, and returns quietly when there is nothing to
     *    do. \u26a0 NO JOB'S CONDITION MAY STOP THE STREAM. That was the bug here:
     *    run() opened by reading the schema marker and returning, so ONE job's
     *    "nothing to do" silently skipped every other job in the function.
     *
     * \u26a0 5. INSERTION REQUIRES IMPACT ANALYSIS. Which phase. What it touches.
     *    What it needs open or closed. What it might collide with. \u26d4 Not
     *    "wherever there is a gap" -- the sequence is the design, not an
     *    accident of the order things were written.
     *
     * \u26d4 6. A JOB RETURNS FROM ITSELF, NEVER FROM THE CONTAINER. No `return`
     *    inside a job that is not returning from the job's OWN function; no
     *    exception escaping a job; the container has ONE exit, at the end.
     *    \u26a0 A job cannot gate itself until it is a function -- with the body
     *    inline, "return when there is nothing to do" and "return from the
     *    container" are the same statement.
     *
     * \u2b50 NO RELEASE OR UPDATE SECTIONS. Every job is called on every launch and
     *    decides for itself. A job that should run once ever gates on a marker it
     *    writes; once per release, on a marker whose NAME changes with the
     *    release; always, on nothing. \u26a0 The condition travels with the job, so
     *    moving it between phases moves its behaviour with it.
     *
     * \u26a0 FAILURE POLICY: most jobs log and continue -- an un-swept manifest is
     *    clutter, not a fault. \u26d4 A PHASE 1 FILE JOB IS THE EXCEPTION: if it
     *    fails, init() must NOT go on to open a half-moved store.
     */
    fun run(ctx: Context): Result {
        Log.i(TAG, "=== HOUSEKEEPING START ===")
        val started = System.currentTimeMillis()
        // ══════════════════════════════════════════════════════════
        //  PHASE 1 -- the database FILES may move. They must be CLOSED.
        // ══════════════════════════════════════════════════════════

        // Runs once, ever. Gates on its own record JSON.
        // \u26a0 NEEDS A RIDER -- the SAF tree grant cannot be taken unattended --
        // so the conversion owns a SCREEN and the gate renders it. What lands
        // here is the call that runs once the grant exists.
        // \u26d4 FIRST IN PHASE 1: it REPLACES the database files init() opens.
        // \u2b50 Screen wording (Fred, 09-12): "Select Documents to continue install.
        // Existing users will migrate their data. New users will just continue
        // with the install."
        // jobStorageConversion(ctx)

        // Every launch. Creates or updates map_keys.json.
        jobPalette(ctx)

        // ══════════════════════════════════════════════════════════
        //  THE BOUNDARY -- init() OPENS the database files
        // ══════════════════════════════════════════════════════════
        SpatialDbManager.init(ctx)

        // ══════════════════════════════════════════════════════════
        //  PHASE 2 -- the databases are OPEN.
        // ══════════════════════════════════════════════════════════

        // Once per schema version. Gates on SCHEMA_MARKER.
        // \u26a0 The only job whose outcome leaves this function: the gate reads
        // needsReload to decide whether to show the trail import.
        val result = jobSchemaConverge(ctx)

        // Every launch. Failure here is clutter, not a fault.
        jobManifestSweep(ctx)

        val ms = System.currentTimeMillis() - started
        Log.i(TAG, "=== HOUSEKEEPING DONE in ${ms}ms ===")
        return result
    }

    // ══════════════════════════════════════════════════════════════════
    //  THE JOBS. Each is self-contained: its own condition, its own
    //  try/catch, its own logging.
    //  \u26d4 RULE 6 -- A JOB RETURNS FROM ITSELF, NEVER FROM THE CONTAINER.
    // ══════════════════════════════════════════════════════════════════

    /**
     * PHASE 1. Every launch. Creates or updates `map_keys.json`.
     *
     * \u2b50 Fred, 09-03: *"the asset is required to assign colors and cats to
     * trails"* -- the categories must EXIST before anything draws or classifies.
     *
     * \u26a0 PHASE 1 because it writes a FILE, and because the conversion above it
     * may have just moved the root it writes into.
     *
     * \u26a0 Gates itself by doing nothing: ensureDefaults writes only when the file
     * is absent; mergeShippedCategories adds only categories not already there.
     */
    private fun jobPalette(ctx: Context) {
        try {
            TrailFilterState.ensureDefaults(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "jobPalette: ensureDefaults: ${e.message}")
        }
        try {
            val added = TrailFilterState.mergeShippedCategories(ctx)
            if (added.isNotEmpty()) Log.i(TAG, "jobPalette: added categor(y/ies) $added")
        } catch (e: Exception) {
            Log.w(TAG, "jobPalette: category merge: ${e.message}")
        }
    }

    /**
     * PHASE 2. Once per schema version. Gates on [SCHEMA_MARKER].
     *
     * \u26d4 THE MARKER CHECK IS HERE, AND IT RETURNS FROM THIS FUNCTION. It was
     * the first line of run(), so this job's "nothing to do" skipped every other
     * job in the container; then it moved below init() and still returned from
     * run(), so the manifest sweep below it never ran. \u26a0 Rule 6.
     *
     * \u26a0 CLEAR BEFORE ALTER, ALWAYS. The ALTER is only correct on an empty
     * table, so the order is not a preference.
     *
     * \u26a0 NO MARKER ON FAILURE -- the next launch tries again. A device half-way
     * through is worse than one that has not started.
     */
    private fun jobSchemaConverge(ctx: Context): Result {
        val marker = File(SpatialDbManager.dbDir(), SCHEMA_MARKER)
        if (marker.exists()) {
            Log.i(TAG, "jobSchemaConverge: marker present -- nothing to do")
            return Result(ran = false, cleared = 0, needsReload = false)
        }
        return try {
            val db = SpatialDbManager.getSpatialDb()
                ?: return Result(false, 0, false, "database unavailable")
            val ext = SpatialDbManager.getExtensionDb()

            var cleared = 0
            db.rawQuery("SELECT COUNT(*) FROM trails", null).use {
                if (it.moveToFirst()) cleared = it.getInt(0)
            }
            if (cleared > 0) {
                Log.i(TAG, "jobSchemaConverge: clearing $cleared trails")
                db.execSQL("DELETE FROM trails")
                try { ext?.execSQL("DELETE FROM trail_properties") }
                catch (e: Exception) { Log.w(TAG, "trail_properties: ${e.message}") }
            }

            // \u26a0 Each column probed separately: a device that got some and not
            // others must converge, and one probe on the first would skip the rest.
            for (col in TRAIL_COLUMNS) {
                try {
                    db.rawQuery("SELECT $col FROM trails LIMIT 1", null)
                        .use { it.moveToFirst() }
                } catch (_: Exception) {
                    Log.i(TAG, "jobSchemaConverge: adding column $col")
                    try { db.execSQL("ALTER TABLE trails ADD COLUMN $col TEXT") }
                    catch (e: Exception) { Log.w(TAG, "$col: ${e.message}") }
                }
            }

            // \u2b50 DERIVED, NOT COUNTED. We just emptied the table, so it is empty.
            // The 09-03 failure was a count read by another thread answering from
            // before the delete.
            marker.parentFile?.mkdirs()
            marker.writeText("cleared=$cleared at ${System.currentTimeMillis()}\n")
            Log.i(TAG, "jobSchemaConverge: done, cleared $cleared")
            Result(ran = true, cleared = cleared, needsReload = true)
        } catch (e: Exception) {
            Log.e(TAG, "jobSchemaConverge FAILED: ${e.javaClass.simpleName} ${e.message}")
            Result(ran = true, cleared = 0, needsReload = false, error = e.message)
        }
    }

    /**
     * PHASE 2. Every launch.
     *
     * \u26a0 Failure is not worth aborting for: an un-swept manifest is clutter, not
     * a fault. \u26d4 Which is why it catches its own and returns quietly -- rule 6.
     */
    private fun jobManifestSweep(ctx: Context) {
        try {
            HomeStateImportController.sweepManifests(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "jobManifestSweep: ${e.message}")
        }
    }
}
