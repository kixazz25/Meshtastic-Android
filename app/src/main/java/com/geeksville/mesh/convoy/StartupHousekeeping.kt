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
    fun run(ctx: Context): Result {
        val marker = File(SpatialDbManager.dbDir(), SCHEMA_MARKER)
        if (marker.exists()) {
            Log.i(TAG, "marker present -- nothing to do")
            return Result(ran = false, cleared = 0, needsReload = false)
        }

        Log.i(TAG, "=== HOUSEKEEPING START ===")
        val started = System.currentTimeMillis()
        return try {
            // ── 1. THE PALETTE, FIRST ────────────────────────────────────
            // ⭐ Fred, 09-03: *"I would move the asset move ahead of the
            // imports as the asset is required to assign colors and cats to
            // trails."* Right -- the categories and colours must EXIST before
            // anything draws or classifies, so this cannot sit after the
            // import. It was in the gate's block below the clear, which is the
            // wrong side of the line.
            TrailFilterState.ensureDefaults(ctx)

            SpatialDbManager.init(ctx)
            val db = SpatialDbManager.getSpatialDb()
                ?: return Result(false, 0, false, "database unavailable")
            val ext = SpatialDbManager.getExtensionDb()

            // ── 3. CLEAR ────────────────────────────────────────────────
            // ⚠ BEFORE the ALTER, always. The ALTER is only correct on an
            // empty table, so the order is not a preference.
            var cleared = 0
            db.rawQuery("SELECT COUNT(*) FROM trails", null).use {
                if (it.moveToFirst()) cleared = it.getInt(0)
            }
            if (cleared > 0) {
                Log.i(TAG, "clearing $cleared trails")
                db.execSQL("DELETE FROM trails")
                try { ext?.execSQL("DELETE FROM trail_properties") }
                catch (e: Exception) { Log.w(TAG, "trail_properties: ${e.message}") }
            }

            // ── 4. ALTER, on the now-empty table ────────────────────────
            // ⚠ Each column probed separately: a device that got some and not
            // others must converge, and one probe on the first would skip the
            // rest.
            for (col in TRAIL_COLUMNS) {
                try {
                    db.rawQuery("SELECT $col FROM trails LIMIT 1", null)
                        .use { it.moveToFirst() }
                } catch (_: Exception) {
                    Log.i(TAG, "adding column $col")
                    try { db.execSQL("ALTER TABLE trails ADD COLUMN $col TEXT") }
                    catch (e: Exception) { Log.w(TAG, "$col: ${e.message}") }
                }
            }

            // ── 5. RELOAD ───────────────────────────────────────────────
            // ⭐ DERIVED, NOT COUNTED. We just emptied the table, so it is
            // empty. The 09-03 failure was a count read by another thread that
            // answered from before the delete.
            marker.parentFile?.mkdirs()
            marker.writeText(
                "cleared=$cleared at ${System.currentTimeMillis()}\n")
            // ⚠ The manifest sweep comes along too -- it was in the same
            // gate block and is startup housekeeping by any reading. Failure
            // here is not worth aborting for: an un-swept manifest is clutter,
            // not a fault.
            try { HomeStateImportController.sweepManifests(ctx) }
            catch (e: Exception) { Log.w(TAG, "manifest sweep: ${e.message}") }

            val ms = System.currentTimeMillis() - started
            Log.i(TAG, "=== HOUSEKEEPING DONE in ${ms}ms, cleared $cleared ===")
            Result(ran = true, cleared = cleared, needsReload = true)
        } catch (e: Exception) {
            // ⚠ NO MARKER ON FAILURE, so the next launch tries again. A device
            // half-way through is worse than one that has not started.
            Log.e(TAG, "housekeeping FAILED: ${e.javaClass.simpleName} ${e.message}")
            Result(ran = true, cleared = 0, needsReload = false, error = e.message)
        }
    }
}
