package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.workDataOf
import com.grouptrack.shared.osm.GtOsmImport

/**
 * C3 IMPORT: skinny DB -> the production spatial DB (OSM-C3A-IMPORT-2026-07-28): every trail OVERLAPPING the ledger's pending box
 * through the same add core (insertTrail), a moved osm_id added as a second trail, the classification carried (CARTOCARRY), then the
 * points (whole state) into reference_points.
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR of the certified shared GtOsmImport (2.8 step 15g, 57cd2a8a7: the area / state / again /
 * changed-geometry / LIVE imports -- OLD = NEW = iPad) on the app's own handles and the one shared writer (SpatialDbManager.sharedOsmImport).
 * The public API is 2.7e's, unchanged. With NO spatial database open the call answers 2.7e's checks in 2.7e's order (no skinny, no
 * pending box, "spatial DB not initialised"). The 2.7e file is in git history (57cd2a8a7) and in the TEST-ONLY OsmImportWorkerCertOld.
 */
class OsmImportWorker(
    appContext: Context,
    private val runSlug: String,
    private val onProgress: (String) -> Unit
) {
    private val applicationContext: Context = appContext.applicationContext

    sealed class Result {
        object Success : Result()
        class Failure(val data: Data) : Result()
        companion object {
            fun success(): Result = Success
            fun failure(data: Data): Result = Failure(data)
        }
    }

    companion object {
        private const val TAG = "OsmImport"
        const val KEY_SLUG = "slug"
        const val KEY_ERROR = "error"
        /** ONE source id for all states, not one per state. */
        const val OSM_SOURCE_ID = GtOsmImport.OSM_SOURCE_ID
        fun uniqueName(slug: String) = "osm_import_$slug"
        fun enqueue(ctx: Context, slug: String) {
            // INPROCESS-2026-10-02: retired -- the state import runs the import directly. Nothing enqueues a job.
            Log.w(TAG, "INPROCESS-2026-10-02: background import for $slug retired; not enqueued")
        }
    }

    private fun fail(msg: String): Result { Log.e(TAG, "FAIL: $msg"); return Result.failure(workDataOf(KEY_ERROR to msg)) }

    suspend fun doWork(): Result {   // INPROCESS-2026-10-02 (no suspension inside, as 2.7e)
        val imp = SpatialDbManager.sharedOsmImport(applicationContext, runSlug, onProgress)
        if (imp == null) {
            // no spatial database: 2.7e's checks, in 2.7e's order
            if (!OsmImportStage.skinnyFor(applicationContext, runSlug).exists()) return fail("no skinny DB for $runSlug")
            OsmImportLedger.pendingBbox(applicationContext, runSlug) ?: return fail("no pending bbox for $runSlug")
            return fail("spatial DB not initialised")
        }
        return when (val r = imp.doWork()) {
            is GtOsmImport.Result.Success -> Result.success()
            is GtOsmImport.Result.Failure -> Result.failure(workDataOf(KEY_ERROR to r.error))
        }
    }
}
