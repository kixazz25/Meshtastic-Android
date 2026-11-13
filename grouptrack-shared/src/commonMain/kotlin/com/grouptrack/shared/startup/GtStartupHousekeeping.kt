package com.grouptrack.shared.startup
// STEP17G2F-2026-11-13: the shared port of 2.7e StartupHousekeeping (2.8 step 17, group G2f)

/**
 * GtStartupHousekeeping — the shared port of 2.7e's StartupHousekeeping.
 *
 * ⛔ PORTED AS IS, WARTS AND ALL. Every rule, every order, every swallowed
 * exception is 2.7e's. Concerns are recorded against 2.7f and fixed after 2.8
 * first gen -- a fix inside a parity port is the 11-12 patch-g mistake.
 *
 * ⚠ THE 09-03 RULE THIS ENCODES, kept verbatim from 2.7e: one job, one thread,
 * four steps in order, and the gate does not answer until it returns.
 *     1. RELEASE CHECK   is this device on the current schema marker?
 *     2. CLEAR           empty the trails
 *     3. ALTER           bring the EMPTY table to the current schema
 *     4. RELOAD          say so, so the gate routes to the import picker
 * And the ALTER only ever runs on an EMPTY table.
 *
 * ⛔⛔ TWO PORT NOTES -- read before "correcting" anything here.
 *
 * 1. @Synchronized IS NOT HERE, AND THAT IS FAITHFUL. In 2.7e the KDoc that says
 *    "BLOCKING, AND DELIBERATELY SO" is followed by @Synchronized, then a SECOND
 *    KDoc, then `fun everyLaunch`. Kotlin treats comments as whitespace, so the
 *    annotation binds to everyLaunch -- the NO-OP -- and run(), which deletes
 *    every trail, is NOT synchronized. @Synchronized is JVM-only and has no
 *    commonMain form, but a lock on a one-line no-op has no observable behaviour
 *    either way. ⭐ The behaviour that matters -- run() unsynchronized -- is the
 *    same on both sides. Recorded as a 2.7f defect (living master), not fixed here.
 *
 * 2. e.javaClass.simpleName in jobSchemaConverge's catch is JVM-only; this uses
 *    e::class.simpleName. It appears only in a LOG line, which is never a
 *    compared key.
 */
object GtStartupHousekeeping {

    private const val TAG = "Housekeeping"

    /**
     * ⭐ THE SCHEMA MARKER. Bump it and every device clears, re-alters and reloads
     * on next launch -- one path, tested, no per-column migration reasoning.
     * ⚠ It lives beside the DATABASES, not in settings: settings are wiped by
     * "clear data" while the databases survive, which would re-clear a device
     * that did not need it.
     */
    const val SCHEMA_MARKER = ".schema_2026-09-03A"

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
     * EVERYLAUNCH-2026-09-08, emptied HOUSEKEEPING-2026-09-12: a deliberate no-op.
     *
     * ⚠ KEPT rather than deleted because the gate still calls it
     * (ConvoyAuthorityGateScreenV2:449); removing the call is a separate edit and
     * this must not break startup in between.
     *
     * ⛔ DO NOT ADD JOBS HERE. run() is the container. A job added here runs AFTER
     * initDb() has already opened the database files, which is the exact mistake
     * the 09-12 restructure fixed.
     */
    fun everyLaunch(env: GtHkEnv) {
        env.log("everyLaunch: no-op -- jobs live in run()")
    }

    /**
     * HOUSEKEEPING-2026-09-12: THE startup container. One call, every launch.
     *
     * ⭐ 1. ONE CONTAINER -- every startup cleanup job lives here.
     * ⭐ 2. SEQUENTIAL, NEVER CONCURRENT -- no job begins before the previous returns.
     * ⛔ 3. TWO PHASES, AND initDb() IS THE BOUNDARY.
     *        PHASE 1  anything that MOVES, COPIES or REPLACES the database files.
     *        initDb() OPENS them.
     *        PHASE 2  anything that reads or writes their CONTENTS.
     * ⛔ 4. EVERY JOB GATES ITSELF, and NO JOB'S CONDITION MAY STOP THE STREAM.
     * ⛔ 6. A JOB RETURNS FROM ITSELF, NEVER FROM THE CONTAINER -- one exit, at the end.
     * ⚠ FAILURE POLICY: most jobs log and continue. A phase 1 file job is the
     *   exception: if it fails, initDb() must not open a half-moved store.
     */
    fun run(env: GtHkEnv): Result {
        env.log("=== HOUSEKEEPING START ===")
        val started = env.now()

        // ══════════════════════════════════════════════════════════
        //  PHASE 1 -- the database FILES may move. They must be CLOSED.
        // ══════════════════════════════════════════════════════════
        // jobStorageConversion(env)   // 2.7e: commented out, owns a SCREEN (the SAF grant needs a rider)
        jobPalette(env)

        // ══════════════════════════════════════════════════════════
        //  THE BOUNDARY -- initDb() OPENS the database files
        // ══════════════════════════════════════════════════════════
        env.initDb()

        // ══════════════════════════════════════════════════════════
        //  PHASE 2 -- the databases are OPEN.
        // ══════════════════════════════════════════════════════════
        // ⚠ The only job whose outcome leaves this function: the gate reads
        // needsReload to decide whether to show the trail import.
        val result = jobSchemaConverge(env)

        // Every launch. Failure here is clutter, not a fault.
        jobManifestSweep(env)

        // PRUNEUGRC-2026-11-02 (Fred 12:55): every launch, no marker (CODE RULE 3) -- OUT when 2.7e is cut.
        // ⚠ 2.7e calls delete() INSIDE the log's interpolation; the order is preserved.
        try {
            val own = env.ownershipFilePath()
            for (x in listOf(own, "$own.part", "$own.tmp"))
                if (env.fileExists(x))
                    env.log("PRUNEUGRC: removed ${baseName(x)} (${env.fileLength(x) / 1_048_576} MB): ${env.fileDelete(x)}")
        } catch (e: Exception) {
            env.log("PRUNEUGRC: ${e.message}")
        }

        val ms = env.now() - started
        env.log("=== HOUSEKEEPING DONE in ${ms}ms ===")
        return result
    }

    // ══════════════════════════════════════════════════════════════════
    //  THE JOBS. Each is self-contained: its own condition, its own
    //  try/catch, its own logging.  ⛔ RULE 6 -- a job returns from ITSELF.
    // ══════════════════════════════════════════════════════════════════

    /**
     * PHASE 1. Every launch. Creates or updates the palette.
     * ⚠ Gates itself by doing nothing: ensureDefaults writes only when the file is
     * absent; mergeShippedCategories adds only categories not already there.
     */
    private fun jobPalette(env: GtHkEnv) {
        try {
            env.ensureDefaults()
        } catch (e: Exception) {
            env.log("jobPalette: ensureDefaults: ${e.message}")
        }
        try {
            val added = env.mergeShippedCategories()
            if (added.isNotEmpty()) env.log("jobPalette: added categor(y/ies) $added")
        } catch (e: Exception) {
            env.log("jobPalette: category merge: ${e.message}")
        }
    }

    /**
     * PHASE 2. Once per schema version. Gates on [SCHEMA_MARKER].
     *
     * ⛔ THE MARKER CHECK IS HERE AND RETURNS FROM THIS FUNCTION -- rule 6. It was
     * the first line of run(), so this job's "nothing to do" skipped every other
     * job in the container.
     * ⚠ CLEAR BEFORE ALTER, ALWAYS -- the ALTER is only correct on an empty table.
     * ⚠ NO MARKER ON FAILURE -- the next launch tries again.
     */
    private fun jobSchemaConverge(env: GtHkEnv): Result {
        if (env.markerExists(SCHEMA_MARKER)) {
            env.log("jobSchemaConverge: marker present -- nothing to do")
            return Result(ran = false, cleared = 0, needsReload = false)
        }
        return try {
            val db = env.spatialDb()
                ?: return Result(false, 0, false, "database unavailable")
            val ext = env.extDb()

            var cleared = 0
            cleared = db.rawQueryInt("SELECT COUNT(*) FROM trails")
            if (cleared > 0) {
                env.log("jobSchemaConverge: clearing $cleared trails")
                db.execSQL("DELETE FROM trails")
                try { ext?.execSQL("DELETE FROM trail_properties") }
                catch (e: Exception) { env.log("trail_properties: ${e.message}") }
            }

            // ⚠ Each column probed separately: a device that got some and not others
            // must converge, and one probe on the first would skip the rest.
            for (col in TRAIL_COLUMNS) {
                if (db.probeColumnThrows(col)) {
                    env.log("jobSchemaConverge: adding column $col")
                    try { db.execSQL("ALTER TABLE trails ADD COLUMN $col TEXT") }
                    catch (e: Exception) { env.log("$col: ${e.message}") }
                }
            }

            // ⭐ DERIVED, NOT COUNTED. We just emptied the table, so it is empty.
            env.markerWrite(SCHEMA_MARKER, "cleared=$cleared at ${env.now()}\n")
            env.log("jobSchemaConverge: done, cleared $cleared")
            Result(ran = true, cleared = cleared, needsReload = true)
        } catch (e: Exception) {
            env.log("jobSchemaConverge FAILED: ${e::class.simpleName} ${e.message}")
            Result(ran = true, cleared = 0, needsReload = false, error = e.message)
        }
    }

    /**
     * PHASE 2. Every launch.
     * ⚠ An un-swept manifest is clutter, not a fault -- it catches its own and
     * returns quietly (rule 6).
     */
    private fun jobManifestSweep(env: GtHkEnv) {
        try {
            env.sweepManifests()
        } catch (e: Exception) {
            env.log("jobManifestSweep: ${e.message}")
        }
    }

    /** 2.7e uses File.name; the shared port takes the basename of the path. */
    private fun baseName(path: String): String = path.substringAfterLast('/')
}
