package com.geeksville.mesh.convoy

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

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
            android.util.Log.e(TAG, "Missing slot for delete entry $entryId")
            DownloadQueueManager.markFailed(entryId, "Delete job missing source")
            return Result.failure()
        }

        android.util.Log.i(TAG, "Starting delete: $label id=$entryId box N=$north S=$south E=$east W=$west")
        DownloadQueueManager.init(appContext)
        MapSourceManager.init(appContext)
        showProgress(entryId, label, 0, 1)

        // -- Derive the same tile set the download would have written -------
        val tiles = ConvoyTileCalculator.calculateTiles(north, south, east, west)
        if (tiles.isEmpty()) {
            android.util.Log.w(TAG, "No tiles in box for $slotName")
            DownloadQueueManager.markComplete(entryId, 0, 0)
            return Result.success()
        }

        // Contiguous x/y rectangle per zoom -- see the class comment.
        data class ZRange(val z: Int, val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int)
        val ranges = tiles.groupBy { it.z }.map { (z, list) ->
            ZRange(z, list.minOf { it.x }, list.maxOf { it.x },
                      list.minOf { it.y }, list.maxOf { it.y })
        }.sortedBy { it.z }

        // -- Every store this slot writes to, by the download's own rule ----
        val source = MapSourceManager.getSourceByKey(slotName)
        val layers = source?.layers ?: emptyList()
        val storeNames: List<String> = if (layers.isEmpty()) {
            listOf(slotName)
        } else {
            layers.mapIndexed { i, layer -> if (i == 0) slotName else layer.cacheDir }
        }
        android.util.Log.i(TAG,
            "Delete $slotName: ${ranges.size} zoom levels across ${storeNames.size} store(s): $storeNames")

        val totalUnits = Math.max(1, ranges.size * storeNames.size)
        var unitsDone = 0
        var totalRemoved = 0
        // DELETE-BANDING-2026-07-25: running total across stores, so a band's
        // progress update reflects the whole job rather than one zoom.
        var totalRemovedSoFar = 0
        // The entry's totalTiles is now a REAL count (see enqueueDelete), so
        // this denominator is reachable and the bar completes.
        val totalExpected = inputData.getInt("total_expected", 0).coerceAtLeast(1)

        for (store in storeNames) {
            if (isStopped) {
                android.util.Log.i(TAG, "Delete stopped by system/user")
                return Result.failure()
            }
            if (!MBTilesStore.storeExists(store)) {
                android.util.Log.d(TAG, "No store for $store - nothing to remove")
                unitsDone += ranges.size
                continue
            }
            for (r in ranges) {
                if (isStopped) return Result.failure()
                // DELETE-BANDING-2026-07-25: delete a BAND OF ROWS at a time, not
                // the whole rectangle in one statement.
                //
                // Patch M issued one DELETE per zoom. That bounded the STATEMENT
                // COUNT but not the STATEMENT SIZE: a high-zoom rectangle is
                // hundreds of thousands of rows, each carrying a 50-100KB blob,
                // so one statement churned gigabytes uninterruptibly. On 07-26
                // SAT sat inside a single z17 delete for 7.5+ minutes with no
                // progress and no way to cancel - isStopped is only checked
                // BETWEEN statements.
                //
                // A band is still a contiguous sub-rectangle, so the semantics
                // are identical; only the transaction size changes. (SQLite on
                // Android lacks SQLITE_ENABLE_UPDATE_DELETE_LIMIT, so
                // DELETE ... LIMIT is not available - banding rows is the
                // portable equivalent.)
                val width = (r.xMax - r.xMin + 1).coerceAtLeast(1)
                val bandRows = (TARGET_ROWS_PER_STATEMENT / width).coerceAtLeast(1)
                var y = r.yMin
                var zRemoved = 0
                while (y <= r.yMax) {
                    if (isStopped) {
                        android.util.Log.i(TAG, "Delete stopped mid-band at $store z${r.z} y=$y")
                        return Result.failure()
                    }
                    val yEnd = Math.min(r.yMax, y + bandRows - 1)
                    zRemoved += MBTilesStore.deleteTileRange(store, r.z, r.xMin, r.xMax, y, yEnd)
                    totalRemoved += 0  // counted via zRemoved below
                    DownloadQueueManager.updateProgress(entryId, totalRemovedSoFar + zRemoved, 0)
                    showProgress(entryId, label, totalRemovedSoFar + zRemoved, totalExpected)
                    y = yEnd + 1
                }
                totalRemovedSoFar += zRemoved
                totalRemoved = totalRemovedSoFar
                unitsDone++
                if (zRemoved > 0) {
                    android.util.Log.d(TAG, "$store z${r.z}: removed $zRemoved (${(r.yMax - r.yMin + 1)} rows, band=$bandRows)")
                }
            }
        }

        // -- Complete -------------------------------------------------------
        // Zero removed IS a legitimate outcome (nothing was there). It is
        // logged plainly so it cannot be mistaken for a full-area deletion.
        DownloadQueueManager.markComplete(entryId, totalRemoved, 0)

        val reclaimable = storeNames.sumOf { MBTilesStore.reclaimableBytes(it) }
        android.util.Log.i(TAG,
            "Delete complete: $label -- $totalRemoved tiles removed, " +
            "${reclaimable / (1024 * 1024)} MB reclaimable (needs VACUUM to return to disk)")

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            entryId.hashCode() + 10000,
            ConvoyDownloadNotification.completeNotification(appContext, label, totalRemoved).build()
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
            android.util.Log.d(TAG, "setForeground skipped: ${e.message}")
        }
    }
}
