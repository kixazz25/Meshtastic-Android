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

        // Show foreground notification
        showProgress(entryId, label, 0, 1)

        MapSourceManager.init(appContext)

        // ── Refresh mode: single slot, bounds-based, only existing tiles ──
        if (refreshMode && refreshSlot.isNotEmpty()) {
            android.util.Log.i(TAG, "REFRESH MODE: slot=$refreshSlot cell bounds N=$north S=$south E=$east W=$west")
            val source = MapSourceManager.getSourceByKey(refreshSlot)
            val layers = source?.layers ?: emptyList()

            // Calculate tiles from cell bounds
            val cellTiles = ConvoyTileCalculator.calculateTiles(north, south, east, west)

            // Filter to only tiles that exist on disk (refresh, not create)
            val existingTiles = cellTiles.filter { tile ->
                ConvoyTileDownloader.tilePath(appContext, refreshSlot, tile).exists()
            }

            val totalTiles = existingTiles.size * layers.size
            var totalDownloaded = 0
            var totalFailed = 0

            android.util.Log.i(TAG, "Refresh cell: ${cellTiles.size} in bounds, ${existingTiles.size} exist on disk, ${layers.size} layers")
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
                    sourceName = layer.cacheDir,
                    forceOverwrite = true
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
                }
            }
            DownloadQueueManager.markComplete(entryId, totalDownloaded, totalFailed)
            return Result.success()
        }

        // ── Normal mode: all slots from bounding box ──
        // Calculate tile keys for the bounding box
        val tiles = ConvoyTileCalculator.calculateTiles(north, south, east, west)

        // Get all layers to download from MapSourceManager
        val downloadSources = MapSourceManager.getDownloadSources()
        val totalLayers = downloadSources.sumOf { it.second.size }
        val totalTiles = tiles.size * totalLayers
        var totalDownloaded = 0
        var totalFailed = 0

        android.util.Log.i(TAG, "Tiles: ${tiles.size} x $totalLayers layers = $totalTiles total")

        DownloadQueueManager.updateProgress(entryId, 0, 0)

        for ((slotName, layers) in downloadSources) {
            for (layer in layers) {
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
                    sourceName = layer.cacheDir
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
