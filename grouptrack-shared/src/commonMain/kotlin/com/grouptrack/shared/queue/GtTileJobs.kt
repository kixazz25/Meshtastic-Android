package com.grouptrack.shared.queue

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.mapsource.GtMapSpec
import com.grouptrack.shared.settings.GtConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/*
 * TILEJOBS-2026-11-25 -- the queue's two JOBS, SHARED (release 2.8b, map downloads items 8 + 9).
 *
 * download = 2.7e ConvoyDownloadWorker.doWork (AREA and MAP_SOURCE_REFRESH jobs -- and corridors, which on the vector
 *            map are queued as area squares through GtVectorQueue);
 * delete   = 2.7e ConvoyDeleteWorker.doWork (DELETE_AREA_TILES).
 * Bodies as they were. What a shared file cannot do itself is the HOST's:
 *   isStopped                          -> coroutine cancellation (CoroutineWorker cancels on stop; the iPad cancels its job)
 *   DownloadQueueManager.updateProgress / markComplete / markFailed, notifications -> onProgress / onComplete / onFailed
 *   VectorTiles / ElevationTiles / MapSourceManager.getDownloadSources -> sourcesFor(slot)
 *   MapSourceManager.getSourceByKey(slot).layers (delete's store names)  -> storesFor(slot)
 *   MBTilesStore.storeExists / deleteTileRange / reclaimableBytes        -> the store lambdas
 *   ConvoyConfig.DOWNLOAD_ZOOM          -> downloadZoom
 * The worker's queue-entry lookup (ORPHAN-2026-08-08O) and its input parsing stay with the host: they are the queue's.
 * 2.7e's `refreshOnly` branch is not carried: it is `val refreshOnly = false` (REPLACEFIX-2026-11-03) and cannot run
 * (Fred 12:25 approved leaving it out).
 * CODE RULE 1: no defaults.
 */
class GtTileJobs(
    private val downloader: GtTileDownloader,
    private val downloadZoom: () -> Int,
    private val sourcesFor: (slot: String) -> List<Pair<String, List<GtMapSpec.GtTileLayerSpec>>>,
    private val storesFor: (slot: String) -> List<String>,
    private val storeExists: (type: String) -> Boolean,
    private val deleteTileRange: (type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int) -> Int,
    private val reclaimableBytes: (type: String) -> Long,
) {
    /** DONE = 2.7e Result.success(); STOPPED = Result.failure() with nothing marked (stopped by system/user);
     *  FAILED = Result.failure() after onFailed was called. */
    enum class Outcome { DONE, STOPPED, FAILED, NO_SOURCES }   // NO_SOURCES: DROIDDLWORKER-2026-11-25, see download()

    companion object {
        const val DOWNLOAD_PROGRESS_INTERVAL = 250   // 2.7e ConvoyDownloadWorker.PROGRESS_INTERVAL
        const val TARGET_ROWS_PER_STATEMENT = 2000   // 2.7e ConvoyDeleteWorker
    }

    private val dlog = gtLogger("DownloadWorker")
    private val xlog = gtLogger("DeleteWorker")

    /** 2.7e ConvoyDownloadWorker.doWork, after the orphan check. [onProgress] is (downloaded, total, failed). */
    suspend fun download(
        e: GtQueueEntry,
        onProgress: (downloaded: Int, total: Int, failed: Int) -> Unit,
        onComplete: (downloaded: Int, failed: Int) -> Unit,
    ): Outcome {
        val slot = e.refreshSlot
        val vectorSlot = slot == GtMapSpec.VEC_SLOT || slot == GtMapSpec.DEM_SLOT
        dlog.i { "Starting download: ${e.label} id=${e.id}" }

        val tiles = if (vectorSlot)   // DEMSOURCE-2026-10-30: DEM z0-12; VECENQUEUE-2026-10-30: never list z15-z18
            GtTileCalc.calculateTiles(e.north, e.south, e.east, e.west, zMin = 0,
                zMax = if (slot == GtMapSpec.DEM_SLOT) GtMapSpec.DEM_Z_MAX else GtMapSpec.VEC_Z_MAX, downloadZoom = downloadZoom())
            else GtTileCalc.calculateTiles(e.north, e.south, e.east, e.west, zMin = GtConfig.DOWNLOAD_ZOOM_MIN,
                zMax = downloadZoom(), downloadZoom = downloadZoom())

        val downloadSources = sourcesFor(slot)
        if (vectorSlot && downloadSources.isEmpty()) {   // DEMSOURCE-2026-10-30
            dlog.e { "VECSOURCE-2026-10-30: vector TileJSON not reachable -- ${e.label} failed, nothing downloaded" }
            onComplete(0, tiles.size)
            return Outcome.NO_SOURCES   // DROIDDLWORKER-2026-11-25: 2.7e returns here WITHOUT the completion notice
        }
        val totalLayers = downloadSources.sumOf { it.second.size }
        val totalTiles = tiles.size * totalLayers
        var totalDownloaded = 0
        var totalFailed = 0

        dlog.i { "Tiles: ${tiles.size} x $totalLayers layers = $totalTiles total" }
        onProgress(0, totalTiles, 0)

        for ((slotName, layers) in downloadSources) {
            for ((layerIndex, layer) in layers.withIndex()) {
                if (!currentCoroutineContext().isActive) {
                    dlog.i { "Worker stopped by system/user" }
                    return Outcome.STOPPED
                }
                dlog.d { "Layer: ${layer.cacheDir} url=${layer.urlTemplate.take(60)}..." }
                val result = downloader.downloadTiles(
                    tiles = tiles,
                    sourceUrl = layer.urlTemplate,
                    sourceName = if (layerIndex == 0) slotName else layer.cacheDir,
                    forceOverwrite = e.refreshMode,   // REPLACEFIX-2026-11-03: Replace = Yes overwrites what is stored
                    isOverlay = (layer.role == "overlay"),
                ) { _, _, failCount ->
                    totalDownloaded++
                    totalFailed = failCount
                    if (totalDownloaded % DOWNLOAD_PROGRESS_INTERVAL == 0 || totalDownloaded == totalTiles) {
                        onProgress(totalDownloaded, totalTiles, totalFailed)
                    }
                }
                result.onFailure { ex -> dlog.e { "Layer ${layer.cacheDir} failed: ${ex.message}" } }
            }
        }

        onComplete(totalDownloaded, totalFailed)
        dlog.i { "Complete: ${e.label} -- $totalDownloaded downloaded, $totalFailed failed" }
        return Outcome.DONE
    }

    /** 2.7e ConvoyDeleteWorker.doWork. REMOVEAREA-2026-11-03: zoom RANGES, never a tile list (a state ran out of
     *  memory); banded deletes of ~TARGET_ROWS_PER_STATEMENT rows. [onProgress] is (removed so far, expected). */
    suspend fun delete(
        e: GtQueueEntry,
        onProgress: (removed: Int, expected: Int) -> Unit,
        onComplete: (removed: Int) -> Unit,
        onFailed: (message: String) -> Unit,
    ): Outcome {
        val slotName = e.refreshSlot
        if (slotName.isBlank()) {
            xlog.e { "Missing slot for delete entry ${e.id}" }
            onFailed("Delete job missing source")
            return Outcome.FAILED
        }
        xlog.i { "Starting delete: ${e.label} id=${e.id} box N=${e.north} S=${e.south} E=${e.east} W=${e.west}" }

        val zr = GtTileCalc.zoomRanges(e.north, e.south, e.east, e.west, GtConfig.DOWNLOAD_ZOOM_MIN, downloadZoom())
        if (zr.isEmpty()) {
            xlog.w { "No tiles in box for $slotName" }
            onComplete(0)
            return Outcome.DONE
        }
        val ranges = zr.sortedBy { it[0] }   // [z, xMin, xMax, yMin, yMax]
        val storeNames = storesFor(slotName)
        xlog.i { "Delete $slotName: ${ranges.size} zoom levels across ${storeNames.size} store(s): $storeNames" }

        var totalRemovedSoFar = 0
        val totalExpected = e.totalTiles.coerceAtLeast(1)

        for (store in storeNames) {
            if (!currentCoroutineContext().isActive) {
                xlog.i { "Delete stopped by system/user" }
                return Outcome.STOPPED
            }
            if (!storeExists(store)) {
                xlog.d { "No store for $store - nothing to remove" }
                continue
            }
            for (r in ranges) {
                if (!currentCoroutineContext().isActive) return Outcome.STOPPED
                val z = r[0]; val xMin = r[1]; val xMax = r[2]; val yMin = r[3]; val yMax = r[4]
                val width = (xMax - xMin + 1).coerceAtLeast(1)
                val bandRows = (TARGET_ROWS_PER_STATEMENT / width).coerceAtLeast(1)
                var y = yMin
                var zRemoved = 0
                while (y <= yMax) {
                    if (!currentCoroutineContext().isActive) {
                        xlog.i { "Delete stopped mid-band at $store z$z y=$y" }
                        return Outcome.STOPPED
                    }
                    val yEnd = minOf(yMax, y + bandRows - 1)
                    zRemoved += deleteTileRange(store, z, xMin, xMax, y, yEnd)
                    onProgress(totalRemovedSoFar + zRemoved, totalExpected)
                    y = yEnd + 1
                }
                totalRemovedSoFar += zRemoved
                if (zRemoved > 0) xlog.d { "$store z$z: removed $zRemoved (${(yMax - yMin + 1)} rows, band=$bandRows)" }
            }
        }

        onComplete(totalRemovedSoFar)
        val reclaimable = storeNames.sumOf { reclaimableBytes(it) }
        xlog.i { "Delete complete: ${e.label} -- $totalRemovedSoFar tiles removed, " +
            "${reclaimable / (1024 * 1024)} MB reclaimable (needs VACUUM to return to disk)" }
        return Outcome.DONE
    }
}
