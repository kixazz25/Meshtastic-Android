package com.geeksville.mesh.convoy

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * CORRIDOR-WORKER-2026-07-24
 *
 * Executes a CORRIDOR download: one track, ONE source, tiles derived from the
 * track's GEOMETRY rather than its bounding box.
 *
 * WHY THIS EXISTS AS ITS OWN WORKER
 *   The queue type selects the worker (see launchWorker). Corridor is not a
 *   branch inside the area worker - it derives a different tile set from a
 *   different input. A tester track ("bar 10") is a ~100-mile path whose BBOX
 *   needs 894,507 tiles and is ~95% empty desert; the ridden corridor is about
 *   half a mile wide.
 *
 * WHAT IT DOES NOT USE
 *   The entry's north/south/east/west are populated for DISPLAY AND PROGRESS
 *   ONLY. This worker never downloads from them - it re-derives the corridor
 *   from `geom_hash`. Storing a geometry REFERENCE rather than a 60K-tile list
 *   keeps download_queue.json small, which matters because that file has no
 *   recovery path on a release build.
 *
 * ⚠ PRE-FETCH FILTER, NOT WRITE-ONLY
 *   Tiles already on disk are filtered out BEFORE fetching, mirroring the
 *   refresh branch of ConvoyDownloadWorker. Without this a resumed corridor
 *   would RE-REQUEST every tile from Esri and only then discover it already
 *   had them - full network cost paid twice, which is real money on per-tile
 *   pricing. It also makes the progress count honest: remaining work, not the
 *   whole corridor.
 */
class ConvoyCorridorWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "CorridorWorker"
        private const val PROGRESS_INTERVAL = 25
    }

    override suspend fun doWork(): Result {
        val entryId = inputData.getString("entry_id") ?: return Result.failure()
        val geomHash = inputData.getString("geom_hash") ?: ""
        val slotName = inputData.getString("refresh_slot") ?: ""
        val label = inputData.getString("label") ?: "Corridor tiles"
        val replaceExisting = inputData.getBoolean("refresh_mode", false)

        if (geomHash.isBlank() || slotName.isBlank()) {
            android.util.Log.e(TAG, "Missing geom_hash or slot - hash='$geomHash' slot='$slotName'")
            DownloadQueueManager.markFailed(entryId, "Corridor job missing track or source")
            return Result.failure()
        }

        android.util.Log.i(TAG, "Starting corridor: $label hash=$geomHash id=$entryId")
        DownloadQueueManager.init(appContext)
        MapSourceManager.init(appContext)
        showProgress(entryId, label, 0, 1)

        // -- Derive the corridor from the track geometry -----------------
        val segments = SpatialDbManager.getTrackPoints(appContext, geomHash)
        if (segments.isNullOrEmpty()) {
            android.util.Log.e(TAG, "No geometry for hash=$geomHash")
            DownloadQueueManager.markFailed(entryId, "Track geometry not found")
            return Result.failure()
        }
        val corridor = ConvoyTileCalculator.corridorTiles(segments)
        if (corridor.isEmpty()) {
            android.util.Log.w(TAG, "Empty corridor for hash=$geomHash")
            DownloadQueueManager.markComplete(entryId, 0, 0)
            return Result.success()
        }

        // -- Skip what is already on disk BEFORE fetching -----------------
        // replaceExisting means the user asked to overwrite, so keep everything.
        val tiles = if (replaceExisting) corridor else corridor.filter { t ->
            !MBTilesStore.hasTile(slotName, t.z, t.x, t.y)
        }
        android.util.Log.i(TAG,
            "Corridor $slotName: ${corridor.size} derived, ${tiles.size} missing, replace=$replaceExisting")

        if (tiles.isEmpty()) {
            android.util.Log.i(TAG, "Corridor already complete on disk: $label")
            DownloadQueueManager.markComplete(entryId, 0, 0)
            return Result.success()
        }

        val source = MapSourceManager.getSourceByKey(slotName)
        val layers = source?.layers ?: emptyList()
        if (layers.isEmpty()) {
            android.util.Log.e(TAG, "No layers for slot=$slotName")
            DownloadQueueManager.markFailed(entryId, "No layers for source $slotName")
            return Result.failure()
        }

        val totalTiles = tiles.size * layers.size
        var totalDownloaded = 0
        var totalFailed = 0
        DownloadQueueManager.updateProgress(entryId, 0, 0)

        for ((layerIndex, layer) in layers.withIndex()) {
            if (isStopped) {
                android.util.Log.i(TAG, "Worker stopped by system/user")
                return Result.failure()
            }
            val result = ConvoyTileDownloader.downloadTiles(
                context = appContext,
                tiles = tiles,
                sourceUrl = layer.urlTemplate,
                sourceName = if (layerIndex == 0) slotName else layer.cacheDir,
                forceOverwrite = replaceExisting,
                isOverlay = (layer.role == "overlay")
            ) { _, _, failCount ->
                totalDownloaded++
                totalFailed = failCount
                if (totalDownloaded % PROGRESS_INTERVAL == 0 || totalDownloaded == totalTiles) {
                    showProgress(entryId, label, totalDownloaded, totalTiles)
                    DownloadQueueManager.updateProgress(entryId, totalDownloaded, totalFailed)
                    setProgressAsync(
                        Data.Builder()
                            .putInt("downloaded", totalDownloaded)
                            .putInt("total", totalTiles)
                            .putInt("failed", totalFailed)
                            .build()
                    )
                }
            }
            result.onFailure { e ->
                android.util.Log.e(TAG, "Corridor layer ${layer.cacheDir} failed: ${e.message}")
            }
        }

        DownloadQueueManager.markComplete(entryId, totalDownloaded, totalFailed)
        android.util.Log.i(TAG,
            "Corridor complete: $label downloaded=$totalDownloaded failed=$totalFailed")
        return Result.success()
    }

    /** Mirrors ConvoyDownloadWorker.showProgress EXACTLY. setForegroundAsync
     *  is not decoration: without a foreground service a long corridor
     *  download gets killed by the system part-way through. */
    private fun showProgress(entryId: String, label: String, downloaded: Int, total: Int) {
        try {
            val notification = ConvoyDownloadNotification
                .progressNotification(appContext, label, downloaded, total)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    ConvoyDownloadNotification.cancelIntent(appContext, entryId)
                )
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    entryId.hashCode(),
                    notification.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(entryId.hashCode(), notification.build())
            }
            setForegroundAsync(info)
        } catch (e: Exception) {
            // Worker may be finishing -- safe to ignore
            android.util.Log.d(TAG, "setForeground skipped: ${e.message}")
        }
    }
}
