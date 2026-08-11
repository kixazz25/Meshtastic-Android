package com.geeksville.mesh.convoy

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * Background tile download worker. Runs as foreground service with
 * progress notification. Survives app backgrounding and device rotation.
 *
 * Input data:
 *   entry_id  -- QueueEntry UUID
 *   north/south/east/west -- bounding box
 *   label     -- user-friendly name for notification
 */
class ConvoyDownloadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DownloadWorker"
        private const val PROGRESS_INTERVAL = 250  // update notification every N tiles
    }

    override suspend fun doWork(): Result {
        val entryId = inputData.getString("entry_id") ?: return Result.failure()
        val north = inputData.getDouble("north", 0.0)
        val south = inputData.getDouble("south", 0.0)
        val east = inputData.getDouble("east", 0.0)
        val west = inputData.getDouble("west", 0.0)
        val label = inputData.getString("label") ?: "Map tiles"
        val refreshMode = inputData.getBoolean("refresh_mode", false)
        val refreshSlot = inputData.getString("refresh_slot") ?: ""

        android.util.Log.i(TAG, "Starting download: $label id=$entryId")

        // Initialize queue manager (handles app-killed restart case)
        DownloadQueueManager.init(appContext)
        // ── ORPHAN-2026-08-08O: no entry, no work. ──
        // This worker takes every parameter from inputData, so it will happily
        // run with no queue entry behind it -- which is what happened on 08-08
        // when an abandoned migration cleared the entries while WorkManager
        // still held the jobs. They ran for 20 minutes reporting 0 tiles and
        // could not be cancelled, because the panel hides itself when the
        // queue is empty.
        // ⚠ FAILURE, not success: an orphan reporting success puts a lie in
        // the log. failure() is also terminal, where retry() would resurrect
        // it on backoff.
        // ⚠ No markFailed here -- there is no entry to mark, and writing one
        // would trade a running ghost for a permanent row.
        if (DownloadQueueManager.queue.value.none { it.id == entryId }) {
            android.util.Log.w(TAG,
                "ORPHAN-2026-08-08O: no queue entry for id=$entryId ('$label'). "
                + "Nothing owns this job, so there is no work. Throwing it out.")
            return Result.failure()
        }

        // Show foreground notification
        showProgress(entryId, label, 0, 1)

        MapSourceManager.init(appContext)

        // ── Refresh mode: single slot, bounds-based, only existing tiles ──
        if (refreshMode && refreshSlot.isNotEmpty()) {
            android.util.Log.i(TAG, "REFRESH MODE: slot=$refreshSlot cell bounds N=$north S=$south E=$east W=$west")
            val source = MapSourceManager.getSourceByKey(refreshSlot)
            val layers = source?.layers ?: emptyList()
            // SLOTTRACE-2026-08-04: TEMPORARY. Remove in the commit that closes the trace.
            // What did this slot actually resolve to, in this process, at this moment?
            if (source == null) {
                android.util.Log.e(TAG,
                    "SLOTTRACE-2026-08-04: slot=$refreshSlot RESOLVED TO NOTHING. "
                    + "No layers, so nothing will download and this job will still "
                    + "report success. Column is terminated or the id is unknown.")
            } else {
                android.util.Log.d(TAG,
                    "SLOTTRACE-2026-08-04: slot=$refreshSlot -> id=${source.id} "
                    + "label=${source.shortLabel} layers=${layers.size} "
                    + "baseUrl=${source.baseUrl}")
                layers.forEach { ly ->
                    android.util.Log.d(TAG,
                        "SLOTTRACE-2026-08-04:   layer role=${ly.role} cacheDir=${ly.cacheDir} "
                        + "url=${ly.urlTemplate}")
                }
            }

            // Calculate tiles from cell bounds
            // REFRESHOOM-2026-08-11L: ask the store what it HOLDS in these bounds.
            //
            // This used to compute every tile in the rectangle and then keep the
            // ones on disk. The filter was right; building the rectangle first
            // was not. A sparse corridor spread over degrees of ground is
            // millions of objects allocated and immediately discarded, and on
            // 08-11 that exhausted a 512 MB heap and killed the app on the first
            // Recreate job.
            //
            // Now bounded by what is stored, whatever the box size -- and
            // faster, since one indexed range scan per level replaces the
            // allocations and a per-tile existence check.
            val existingTiles = MBTilesStore.tilesInBounds(
                refreshSlot, north, south, east, west)
            val totalTiles = existingTiles.size * layers.size
            var totalDownloaded = 0
            var totalFailed = 0

            android.util.Log.i(TAG, "REFRESHOOM-2026-08-11L Refresh cell: ${existingTiles.size} stored tile(s) in bounds, ${layers.size} layer(s)")
            DownloadQueueManager.updateProgress(entryId, 0, 0)

            if (existingTiles.isEmpty()) {
                DownloadQueueManager.markComplete(entryId, 0, 0)
                return Result.success()
            }

            for (layer in layers) {
                if (isStopped) return Result.failure()
                val result = ConvoyTileDownloader.downloadTiles(
                    context = appContext,
                    tiles = existingTiles,
                    sourceUrl = layer.urlTemplate,
                    sourceName = refreshSlot,
                    forceOverwrite = true,
                    isOverlay = (layer.role == "overlay")
                ) { downloaded, _, failCount ->
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
                    android.util.Log.e(TAG, "Refresh layer ${layer.cacheDir} failed: ${e.message}")
                    // SLOTTRACE-2026-08-04: TEMPORARY. A cause with no context cost an evening
                    // of reconstruction on 08-02 ("CORR SAT failed", nothing else).
                    android.util.Log.e(TAG,
                        "SLOTTRACE-2026-08-04: FAILURE slot=$refreshSlot layer=${layer.cacheDir} "
                        + "url=${layer.urlTemplate} cause=${e::class.java.simpleName}: ${e.message}")
                }
            }
            DownloadQueueManager.markComplete(entryId, totalDownloaded, totalFailed)
            return Result.success()
        }

        // ── Normal mode: all slots from bounding box ──
        // Calculate tile keys for the bounding box
        val tiles = ConvoyTileCalculator.calculateTiles(north, south, east, west)

        // Get all layers to download from MapSourceManager
        val allSources = MapSourceManager.getDownloadSources()
        val downloadSources = if (refreshSlot.isNotEmpty()) allSources.filter { it.first == refreshSlot } else allSources
        val totalLayers = downloadSources.sumOf { it.second.size }
        val totalTiles = tiles.size * totalLayers
        var totalDownloaded = 0
        var totalFailed = 0

        android.util.Log.i(TAG, "Tiles: ${tiles.size} x $totalLayers layers = $totalTiles total")

        DownloadQueueManager.updateProgress(entryId, 0, 0)

        for ((slotName, layers) in downloadSources) {
            for ((layerIndex, layer) in layers.withIndex()) {
                // Check if WorkManager cancelled us
                if (isStopped) {
                    android.util.Log.i(TAG, "Worker stopped by system/user")
                    return Result.failure()
                }

                android.util.Log.d(TAG, "Layer: ${layer.cacheDir} url=${layer.urlTemplate.take(60)}...")

                val result = ConvoyTileDownloader.downloadTiles(
                    context = appContext,
                    tiles = tiles,
                    sourceUrl = layer.urlTemplate,
                    sourceName = if (layerIndex == 0) slotName else layer.cacheDir,
                    isOverlay = (layer.role == "overlay")
                ) { downloaded, _, failCount ->
                    totalDownloaded++
                    totalFailed = failCount

                    // Throttle notification + queue updates
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
                    android.util.Log.e(TAG, "Layer ${layer.cacheDir} failed: ${e.message}")
                }
            }
        }

        // -- Complete --------------------------------------
        DownloadQueueManager.markComplete(entryId, totalDownloaded, totalFailed)

        // Show completion notification (separate ID so it persists)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            entryId.hashCode() + 10000,
            ConvoyDownloadNotification.completeNotification(appContext, label, totalDownloaded).build()
        )

        android.util.Log.i(TAG, "Complete: $label -- $totalDownloaded downloaded, $totalFailed failed")
        return Result.success()
    }

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
