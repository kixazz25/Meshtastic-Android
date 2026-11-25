package com.geeksville.mesh.convoy

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.grouptrack.shared.log.gtLogger

/**
 * DELETE-AREA-2026-07-25
 *
 * Removes downloaded map tiles inside a bounding box. This is the AREA
 * DOWNLOAD worker with the direction reversed: same bbox, same tile
 * derivation, same progress/notification/completion plumbing -- the only
 * substitution is that a remote fetch + insertTile becomes a local DELETE.
 *
 * WHY ITS OWN WORKER RATHER THAN A FLAG ON THE DOWNLOAD WORKER
 *   launchWorker dispatches on downloadType. Delete never touches the
 *   network, has different completion semantics, and runs in seconds rather
 *   than hours -- threading a "mode" through the download worker would put
 *   two opposite operations behind one set of branches.
 *
 * ⚠ LAYERS ARE SEPARATE STORES. ConvoyDownloadWorker writes with
 *       sourceName = if (layerIndex == 0) slotName else layer.cacheDir
 *   so SAT's base imagery lands in SAT.mbtiles and each overlay in its own
 *   file. THIS WORKER USES THE IDENTICAL RULE. Deleting only `slotName`
 *   would clear the base and leave transportation/places behind -- which
 *   presents as "the delete did not work".
 *
 * ⚠ RANGE DELETE, NOT PER-TILE. A bbox is a contiguous rectangle in x/y at
 *   every zoom, so one BETWEEN statement per zoom is EXACTLY equivalent to
 *   enumerating every tile -- ~18 statements instead of ~50,000. The ranges
 *   are derived by grouping ConvoyTileCalculator.calculateTiles(), the SAME
 *   call the download uses, so "delete what I downloaded" is true by
 *   construction rather than by two functions agreeing to stay in step.
 *
 * NOTE ON SPACE: deleting frees pages INSIDE the .mbtiles; the file keeps
 * its size until a VACUUM. That is a separate explicit action -- VACUUM
 * needs roughly the file's own size in free space, so it cannot be fired
 * blindly at the end of every delete.
 *
 * Input data:
 *   entry_id              -- QueueEntry UUID
 *   north/south/east/west -- bounding box
 *   label                 -- notification text
 *   refresh_slot          -- the slot whose tiles are being removed
 */
class ConvoyDeleteWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DeleteWorker"
        private val log = gtLogger(TAG)   // GTLOGKERMIT-2026-11-16: android.util.Log(TAG, ..) -- same tag, so the logs read as 2.7e's
        // DELETE-BANDING-2026-07-25: approximate tiles touched per DELETE
        // statement. Small enough that a statement returns in well under a
        // second even on satellite blobs, so progress moves and isStopped can
        // fire; large enough that per-statement overhead stays negligible.
        private const val TARGET_ROWS_PER_STATEMENT = 2000
    }

    override suspend fun doWork(): Result {
        val entryId = inputData.getString("entry_id") ?: return Result.failure()
        val north = inputData.getDouble("north", 0.0)
        val south = inputData.getDouble("south", 0.0)
        val east = inputData.getDouble("east", 0.0)
        val west = inputData.getDouble("west", 0.0)
        val label = inputData.getString("label") ?: "Remove tiles"
        val slotName = inputData.getString("refresh_slot") ?: ""

        if (slotName.isBlank()) {
            log.e { "Missing slot for delete entry $entryId" }
            DownloadQueueManager.markFailed(entryId, "Delete job missing source")
            return Result.failure()
        }

        log.i { "Starting delete: $label id=$entryId box N=$north S=$south E=$east W=$west" }
        DownloadQueueManager.init(appContext)
        MapSourceManager.init(appContext)
        showProgress(entryId, label, 0, 1)

        // -- Derive the same tile set the download would have written -------
        // DROIDDELWORKER-2026-11-25: the job body is the SHARED GtTileJobs.delete (commonMain) -- the one the iPad runs.
        // Built from the INPUT DATA, as 2.7e worked from it (no orphan check here). createdAt is not read by the job.
        val job = com.grouptrack.shared.queue.GtQueueEntry(
            id = entryId,
            north = north, south = south, east = east, west = west,
            createdAt = 0L,
            label = label,
            refreshSlot = slotName,
            totalTiles = inputData.getInt("total_expected", 0),
            downloadType = com.grouptrack.shared.queue.GtDownloadType.DELETE_AREA_TILES,
        )
        var removedCount = 0
        val outcome = ConvoyTileDownloader.tileJobs(appContext).delete(
            job,
            onProgress = { removed, expected ->
                DownloadQueueManager.updateProgress(entryId, removed, 0)
                showProgress(entryId, label, removed, expected)
            },
            onComplete = { removed ->
                removedCount = removed
                DownloadQueueManager.markComplete(entryId, removed, 0)
            },
            onFailed = { msg -> DownloadQueueManager.markFailed(entryId, msg) },
        )
        if (outcome != com.grouptrack.shared.queue.GtTileJobs.Outcome.DONE) return Result.failure()   // stopped, or failed

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            entryId.hashCode() + 10000,
            ConvoyDownloadNotification.completeNotification(appContext, label, removedCount).build()
        )
        return Result.success()
    }

    private fun showProgress(entryId: String, label: String, done: Int, total: Int) {
        try {
            val notification = ConvoyDownloadNotification
                .progressNotification(appContext, label, done, total)
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
            log.d { "setForeground skipped: ${e.message}" }
        }
    }
}
