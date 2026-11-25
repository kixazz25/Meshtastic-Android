package com.grouptrack.shared.platform

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.mapsource.GtMapSpec
import com.grouptrack.shared.mbtiles.GtMBTiles
import com.grouptrack.shared.queue.GtDownloadType
import com.grouptrack.shared.queue.GtQueueEntry
import com.grouptrack.shared.queue.GtTileDownloader
import com.grouptrack.shared.queue.GtTileJobs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * IOSQRUN-2026-11-25: the iPad's WORKER for the download queue -- what WorkManager + ConvoyDownloadWorker /
 * ConvoyDeleteWorker are on Android. The JOBS are the SHARED GtTileJobs (download, delete) over the SHARED
 * GtTileDownloader, the same Android's workers now run. This class is only the iPad's host for them:
 *   HTTP          IosHttpBytes (NSURLSession)          io      Dispatchers.Default (IO is internal on native)
 *   store         GtMBTiles(IosTileEnv) -- <GroupTrack>/maps/tiles/<slot>.mbtiles, the store the map reads
 *   sources       GtMapSpec VEC / DEM over IosMapSpecEnv -- the iPad has the one vector map, so those are its slots
 *   stores        the slot alone -- VEC and DEM are single-layer, which is what 2.7e's rule gives for them
 * 2.7e launchWorker's dispatch: DELETE_AREA_TILES -> delete; AREA / MAP_SOURCE_REFRESH (and vector corridors,
 * queued as area squares) -> download.
 * ASYNC, so the queue's throttle is real: fetch starts the job in the background and answers null ("started"); the
 * entry stays DOWNLOADING and holds its slot until the job reports, on the main thread, through the queue's own
 * updateProgress / markComplete / markFailed -- the calls 2.7e's workers make. stop() is the queue's cancelWork:
 * 2.7e's WorkManager.cancelWorkById. A job that throws marks nothing, as a 2.7e worker that throws marks nothing.
 */
class IosQueueRunner {
    private val log = gtLogger("DownloadQueue")

    /** Set once, right after the queue is built with this runner's fetch -- the two refer to each other. */
    lateinit var queue: IosDownloadQueue

    /** 2.7e ConvoyConfig.DOWNLOAD_ZOOM: 18 and never assigned (measured 11-17, IosQueuePlatform's note). */
    var downloadZoom: Int = 18

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = HashMap<String, Job>()   // touched on the main thread only
    private val store = GtMBTiles(IosTileEnv(IosImportPaths()))

    private val jobs = GtTileJobs(
        downloader = GtTileDownloader(
            http = IosHttpBytes,
            io = Dispatchers.Default,
            hasTile = { t, z, x, y -> store.hasTile(t, z, x, y) },
            // every live iPad slot (VEC, DEM) is "overlay": stored as received, as 2.7e TileCodec passes overlays through
            insertTile = { t, z, x, y, b, _ -> store.insertTile(t, z, x, y, b) },
            downloadZoom = { downloadZoom },
        ),
        downloadZoom = { downloadZoom },
        sourcesFor = { slot ->
            when (slot) {
                GtMapSpec.VEC_SLOT -> GtMapSpec.vectorDownloadSources(IosMapSpecEnv)
                GtMapSpec.DEM_SLOT -> GtMapSpec.elevationDownloadSources(IosMapSpecEnv)
                else -> emptyList()
            }
        },
        storesFor = { slot -> listOf(slot) },
        storeExists = { t -> store.storeExists(t) },
        deleteTileRange = { t, z, x0, x1, y0, y1 -> store.deleteTileRange(t, z, x0, x1, y0, y1) },
        reclaimableBytes = { t -> store.reclaimableBytes(t) },
    )

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    /** The queue's fetch. Starts the job in the background; null = started (it reports through the queue). */
    fun fetch(e: GtQueueEntry, progress: (downloaded: Int, failed: Int) -> Unit): Boolean? {
        running[e.id] = scope.launch {
            try {
                if (e.downloadType == GtDownloadType.DELETE_AREA_TILES) {
                    jobs.delete(
                        e,
                        onProgress = { removed, _ -> onMain { progress(removed, 0) } },
                        onComplete = { removed -> onMain { queue.markComplete(e.id, removed, 0) } },
                        onFailed = { msg -> onMain { queue.markFailed(e.id, msg) } },
                    )
                } else {
                    jobs.download(
                        e,
                        onProgress = { d, _, f -> onMain { progress(d, f) } },
                        onComplete = { d, f -> onMain { queue.markComplete(e.id, d, f) } },
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                log.e { "IOSQRUN-2026-11-25: job threw for " + e.id + ": " + x.message }
            } finally {
                onMain { running.remove(e.id) }
            }
        }
        return null
    }

    /** The queue's cancelWork -- 2.7e's WorkManager.cancelWorkById. */
    fun stop(entryId: String) {
        running.remove(entryId)?.cancel()
    }
}
