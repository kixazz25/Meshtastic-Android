package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.workDataOf
import com.geeksville.mesh.grouptrack.GtImportAndroid
import com.geeksville.mesh.grouptrack.GtOsmAndroid
import com.grouptrack.shared.osm.GtOsmExtract

/**
 * C2 (REDUCE): the Geofabrik zip becomes the skinny DB, as ONE operation (OSM-C2-WORKER-2026-07-28; R2 / R3; feature_count = the
 * osm_trails count only; the zip never deleted to make room).
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR of the certified shared GtOsmExtract (2.8 step 15g, 57cd2a8a7: Utah extract, the minis, the
 * extent abort -- OLD = NEW = iPad) on the app's environment (GtImportAndroid) and GtOsmAndroid (free space, the Downloads sweep, the
 * notification -- 2.7e's code). The public API is 2.7e's, unchanged: the same constructor, progress JSON, Result. The 2.7e file is in
 * git history (57cd2a8a7) and in the TEST-ONLY OsmExtractWorkerCertOld.
 */
class OsmExtractWorker(
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
        private const val TAG = "OsmExtract"
        const val KEY_SLUG = "slug"
        const val KEY_ERROR = "error"
        fun uniqueName(slug: String) = "osm_extract_$slug"
        fun enqueue(ctx: Context, slug: String) {
            // INPROCESS-2026-10-02: retired -- the state import runs the extract directly. Nothing enqueues a job.
            Log.w(TAG, "INPROCESS-2026-10-02: background extract for $slug retired; not enqueued")
        }
    }

    suspend fun doWork(): Result =   // INPROCESS-2026-10-02 (no suspension inside, as 2.7e)
        when (val r = GtOsmExtract(GtImportAndroid(applicationContext), GtOsmAndroid(applicationContext), runSlug, onProgress).doWork()) {
            is GtOsmExtract.Result.Success -> Result.success()
            is GtOsmExtract.Result.Failure -> Result.failure(workDataOf(KEY_ERROR to r.error))
        }
}
