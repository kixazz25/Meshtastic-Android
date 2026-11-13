package com.geeksville.mesh.convoy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.geeksville.mesh.grouptrack.GtHkCertClock
import com.geeksville.mesh.grouptrack.GtHkCertLog
import com.geeksville.mesh.grouptrack.GtHkCertStubs
import java.io.File

/**
 * STEP17G2F1-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * 2.7e's StartupHousekeeping, running as REAL ANDROID CODE on real Android objects, so the Droid
 * comparison is 2.7e against the shared port -- not one transcription against another.
 *
 * ══════════════════════════════════════════════════════════════════
 *  SUBSTITUTION MANIFEST -- piece 1 (everyLaunch). Counted; nothing else is touched.
 * ══════════════════════════════════════════════════════════════════
 *   1x   object StartupHousekeeping     ->  object StartupHousekeepingCertOld
 *   1x   Log.d(TAG, ...)                ->  GtHkCertLog.d(TAG, ...)
 *   0x   GtCertGuard.skip(...)          ->  (absent: it is a 2.8 TEST-ONLY line, and it is on
 *                                            run(), not on everyLaunch -- `git diff 2.7e` 11-13)
 * The body below is byte-for-byte 2.7e, comment included.
 *
 * ⚠ GROWS BY PATCH. Pieces 2-4 add run(), jobPalette, jobSchemaConverge and jobManifestSweep with
 * their own manifests. The file keeps its name; the manifest above is appended to, never rewritten.
 */
object StartupHousekeepingCertOld {

    private const val TAG = "Housekeeping"

    fun everyLaunch(ctx: Context) {
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
        GtHkCertLog.d(TAG, "everyLaunch: no-op -- jobs live in run()")
    }

    // ══════════════════════════════════════════════════════════════════
    //  PIECE 4 -- run() and its three jobs.  ⛔ everyLaunch above is CERTIFIED and untouched.
    //
    //  SUBSTITUTION MANIFEST -- piece 4. Counted. Control flow untouched.
    //    1x  SpatialDbManager.init(ctx)             ->  (removed: the harness opens the scratch databases)
    //    1x  SpatialDbManager.dbDir()               ->  the `markerDir` parameter
    //    1x  SpatialDbManager.getSpatialDb()        ->  the `sp` parameter  (⭐ the ?: return arm is KEPT)
    //    1x  SpatialDbManager.getExtensionDb()      ->  the `ext` parameter
    //    1x  TrailFilterState.ensureDefaults(ctx)   ->  GtHkCertStubs.ensureDefaults()
    //    1x  TrailFilterState.mergeShippedCategories(ctx) -> GtHkCertStubs.mergeCategories()
    //    1x  OwnershipReclass.ownershipFile()       ->  the `ownFile` parameter
    //    1x  HomeStateImportController.sweepManifests(ctx) -> GtHkCertStubs.sweepManifests()
    //    3x  System.currentTimeMillis()             ->  GtHkCertClock.now()   (⛔ RULE 1)
    //   12x  Log.i/w/e(TAG, ...)                    ->  GtHkCertLog.i/w/e(TAG, ...)
    //    7x  PROBE (ADDED)                          ->  GtHkCertLog.probe(...) / .effect(...)
    //    0x  GtCertGuard.skip("StartupHousekeeping.run") -> (absent: 2.8 TEST-ONLY, `git diff 2.7e` 11-13)
    //
    //  ⛔ THIS REALLY DELETES EVERY TRAIL when the marker is missing. That is 2.7e's documented
    //  behaviour and the reason 2.8 guards it in cert mode; here it runs for real against a
    //  scratch database the harness built, never the device's.
    // ══════════════════════════════════════════════════════════════════

    private const val SCHEMA_MARKER = ".schema_2026-09-03A"

    private val TRAIL_COLUMNS = listOf(
        "status", "land_status", "use_type", "carto_code_source",
    )

    data class Result(
        val ran: Boolean,
        val cleared: Int,
        val needsReload: Boolean,
        val error: String? = null,
    )

    fun run(sp: SQLiteDatabase?, ext: SQLiteDatabase?, markerDir: File, ownFile: File): Result {
        GtHkCertLog.i(TAG, "=== HOUSEKEEPING START ===")
        val started = GtHkCertClock.now()

        jobPalette()

        // the boundary -- init() is the harness's job here

        val result = jobSchemaConverge(sp, ext, markerDir)

        jobManifestSweep()

        try {
            val own = ownFile
            for (x in listOf(own, File(own.path + ".part"), File(own.path + ".tmp")))
                if (x.exists()) { GtHkCertLog.effect("delete " + x.name); GtHkCertLog.i(TAG, "PRUNEUGRC: removed ${x.name} (${x.length() / 1_048_576} MB): ${x.delete()}") }
        } catch (e: Exception) { GtHkCertLog.w(TAG, "PRUNEUGRC: ${e.message}") }

        val ms = GtHkCertClock.now() - started
        GtHkCertLog.i(TAG, "=== HOUSEKEEPING DONE in ${ms}ms ===")
        GtHkCertLog.probe("result", "${result.ran},${result.cleared},${result.needsReload},${if (result.error != null) "err" else "none"}")   // PROBE
        return result
    }

    private fun jobPalette() {
        try {
            GtHkCertStubs.ensureDefaults()
        } catch (e: Exception) {
            GtHkCertLog.w(TAG, "jobPalette: ensureDefaults: ${e.message}")
        }
        try {
            // ⚠ NO PROBE for `added`: both sides get that list from the same fixture, so comparing
            // it would compare the fixture, not the port. The DECISION -- whether the merge ran at
            // all -- is already in `effects` via ensureDefaults, and whether it THREW shows up as
            // the effect being absent.
            val added = GtHkCertStubs.mergeCategories()
            if (added.isNotEmpty()) GtHkCertLog.i(TAG, "jobPalette: added categor(y/ies) $added")
        } catch (e: Exception) {
            GtHkCertLog.w(TAG, "jobPalette: category merge: ${e.message}")
        }
    }

    private fun jobSchemaConverge(sp: SQLiteDatabase?, ext: SQLiteDatabase?, markerDir: File): Result {
        val marker = File(markerDir, SCHEMA_MARKER)
        if (marker.exists()) {
            GtHkCertLog.i(TAG, "jobSchemaConverge: marker present -- nothing to do")
            // ⚠ NO PROBE: the marker-present path is already distinguishable in `result`
            // (false,0,false,null) and by the ABSENCE of any DELETE / ALTER / markerWrite effect.
            return Result(ran = false, cleared = 0, needsReload = false)
        }
        return try {
            val db = sp
                ?: return Result(false, 0, false, "database unavailable")
            var cleared = 0
            db.rawQuery("SELECT COUNT(*) FROM trails", null).use {
                if (it.moveToFirst()) cleared = it.getInt(0)
            }
            if (cleared > 0) {
                GtHkCertLog.i(TAG, "jobSchemaConverge: clearing $cleared trails")
                db.execSQL("DELETE FROM trails"); GtHkCertLog.effect("DELETE FROM trails")          // PROBE
                try { ext?.execSQL("DELETE FROM trail_properties"); if (ext != null) GtHkCertLog.effect("DELETE FROM trail_properties") }
                catch (e: Exception) { GtHkCertLog.w(TAG, "trail_properties: ${e.message}") }
            }
            for (col in TRAIL_COLUMNS) {
                try {
                    db.rawQuery("SELECT $col FROM trails LIMIT 1", null)
                        .use { it.moveToFirst() }
                } catch (_: Exception) {
                    GtHkCertLog.i(TAG, "jobSchemaConverge: adding column $col")
                    try { db.execSQL("ALTER TABLE trails ADD COLUMN $col TEXT"); GtHkCertLog.effect("ALTER TABLE trails ADD COLUMN $col TEXT") }   // PROBE
                    catch (e: Exception) { GtHkCertLog.w(TAG, "$col: ${e.message}") }
                }
            }
            marker.parentFile?.mkdirs()
            marker.writeText("cleared=$cleared at ${GtHkCertClock.now()}\n")
            GtHkCertLog.effect("markerWrite $SCHEMA_MARKER")                            // PROBE (⛔ RULE 4: never the text)
            GtHkCertLog.i(TAG, "jobSchemaConverge: done, cleared $cleared")
            Result(ran = true, cleared = cleared, needsReload = true)
        } catch (e: Exception) {
            GtHkCertLog.e(TAG, "jobSchemaConverge FAILED: ${e.javaClass.simpleName} ${e.message}")
            Result(ran = true, cleared = 0, needsReload = false, error = e.message)
        }
    }

    private fun jobManifestSweep() {
        try {
            GtHkCertStubs.sweepManifests()
        } catch (e: Exception) {
            GtHkCertLog.w(TAG, "jobManifestSweep: ${e.message}")
        }
    }
}
