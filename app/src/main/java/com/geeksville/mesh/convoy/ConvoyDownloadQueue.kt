package com.geeksville.mesh.convoy

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// ===========================================================
// QueueEntry -- one download request (region x all layers)
// ===========================================================
enum class QueueStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED, CANCELLED
}

// QUEUE-SCHEMA-2026-07-24: job kind. IDENTITY - this is what the panel filter
// matches and what a scoped cancel catches. Kept SEPARATE from `priority`
// (which is only about turn order) so a promoted CORRIDOR job is still
// CORRIDOR for filtering. DELETE_AREA_TILES is declared now even though it is
// unwired - the control already exists in the panel, and declaring it once
// avoids a second schema migration later. NOTE it REMOVES tiles rather than
// fetching them, so launchWorker must DISPATCH ON TYPE when it is wired.
enum class DownloadType { AREA, CORRIDOR, MAP_SOURCE_REFRESH, DELETE_AREA_TILES }

// QUEUE-SCHEMA-2026-07-24: default turn order per kind. Explicit numbers, not the
// enum ordinal - an ordinal silently changes meaning if anyone reorders the
// enum. 1 is reserved for manual promotion and is never submitted at.
object DownloadPriority {
    const val DELETE = 0
    const val PROMOTED = 1
    const val CORRIDOR = 2
    const val AREA = 3
    const val REFRESH = 4
    fun forType(t: DownloadType): Int = when (t) {
        DownloadType.DELETE_AREA_TILES -> DELETE
        DownloadType.CORRIDOR -> CORRIDOR
        DownloadType.AREA -> AREA
        DownloadType.MAP_SOURCE_REFRESH -> REFRESH
    }
}

data class QueueEntry(
    val id: String = UUID.randomUUID().toString(),
    val north: Double = 0.0,
    val south: Double = 0.0,
    val east: Double = 0.0,
    val west: Double = 0.0,
    val status: QueueStatus = QueueStatus.QUEUED,
    val totalTiles: Int = 0,
    val downloadedTiles: Int = 0,
    val failedTiles: Int = 0,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val label: String = "",
    val workRequestId: String? = null,
    val refreshMode: Boolean = false,
    val refreshSlot: String = "",
    // QUEUE-SCHEMA-2026-07-24: see DownloadType / DownloadPriority above.
    val downloadType: DownloadType = DownloadType.AREA,
    val priority: Int = DownloadPriority.AREA,
    // 0L until the job finishes. With createdAt this gives an OBSERVED rate
    // (downloadedTiles / elapsed) that the panel averages over the last 2
    // completed entries for its ETA - persisted, so it survives a restart.
    val completedAt: Long = 0L,
    // THROUGHPUT-2026-08-08C: when the job ACTUALLY STARTED, not when it was
    // enqueued. createdAt above is queue time -- a job that sat behind 87
    // others carries hours of waiting in (completedAt - createdAt), which made
    // every observed rate a fraction of the real one. 0L until dispatched.
    val startedAt: Long = 0L,
    // THROUGHPUT-2026-08-08C: tiles per second, COMPUTED AT COMPLETION and
    // stored. The job is the only thing that knows both its duration and its
    // tile count at the moment both are true, so the calculation belongs there
    // rather than being re-derived from timestamps at render time. 0.0 until
    // complete. Persisted, so history is readable later.
    val tilesPerSec: Double = 0.0,
    // CORRIDOR-QUEUE-2026-07-24: the track this entry belongs to. Written ONLY by the
    // corridor path, read ONLY by the corridor worker - inert to everything
    // else. The corridor stores a GEOMETRY REFERENCE rather than a tile list:
    // a 60K-tile list would be megabytes per entry in download_queue.json.
    // The worker re-derives from this hash at run time.
    val geomHash: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        // THROUGHPUT-2026-08-08C
        put("startedAt", startedAt)
        put("tilesPerSec", tilesPerSec)
        put("id", id)
        put("north", north)
        put("south", south)
        put("east", east)
        put("west", west)
        put("status", status.name)
        put("totalTiles", totalTiles)
        put("downloadedTiles", downloadedTiles)
        put("failedTiles", failedTiles)
        put("retryCount", retryCount)
        put("createdAt", createdAt)
        put("label", label)
        put("workRequestId", workRequestId ?: "")
        put("refreshMode", refreshMode)
        put("refreshSlot", refreshSlot)
        // QUEUE-SCHEMA-2026-07-24
        put("downloadType", downloadType.name)
        put("priority", priority)
        put("completedAt", completedAt)
        put("geomHash", geomHash)   // CORRIDOR-QUEUE-2026-07-24
    }

    companion object {
        fun fromJson(j: JSONObject): QueueEntry = QueueEntry(
            id = j.optString("id", UUID.randomUUID().toString()),
            north = j.optDouble("north", 0.0),
            south = j.optDouble("south", 0.0),
            east = j.optDouble("east", 0.0),
            west = j.optDouble("west", 0.0),
            status = try { QueueStatus.valueOf(j.optString("status", "QUEUED")) } catch (_: Exception) { QueueStatus.QUEUED },
            totalTiles = j.optInt("totalTiles", 0),
            downloadedTiles = j.optInt("downloadedTiles", 0),
            failedTiles = j.optInt("failedTiles", 0),
            retryCount = j.optInt("retryCount", 0),
            createdAt = j.optLong("createdAt", System.currentTimeMillis()),
            label = j.optString("label", ""),
            workRequestId = j.optString("workRequestId", "").ifEmpty { null },
            refreshMode = j.optBoolean("refreshMode", false),
            refreshSlot = j.optString("refreshSlot", ""),
            // QUEUE-SCHEMA-2026-07-24: EVERY entry already on a tester's device
            // lacks these keys. optString/optInt/optLong + the try-catch keep
            // this total - a throw here is the boot-loop mechanism.
            downloadType = try {
                DownloadType.valueOf(j.optString("downloadType", "AREA"))
            } catch (_: Exception) { DownloadType.AREA },
            priority = j.optInt("priority", DownloadPriority.AREA),
            completedAt = j.optLong("completedAt", 0L),
            // THROUGHPUT-2026-08-08C: optLong/optDouble defaults mean an OLD
            // download_queue.json loads clean -- absent fields read as 0.
            startedAt = j.optLong("startedAt", 0L),
            tilesPerSec = j.optDouble("tilesPerSec", 0.0),
            // CORRIDOR-QUEUE-2026-07-24: defaulted - legacy entries lack this key.
            geomHash = j.optString("geomHash", "")
        )
    }
}

// ===========================================================
// DownloadQueueManager -- singleton queue, max 2 concurrent
// ===========================================================
object DownloadQueueManager {

    // QEVAL-2026-08-08I: was a compile-time constant of 2.
    // [V2.6b] 2 queues (2 beat 3 on balanced segments 07-11) -- but that was
    // measured against ESRI at ~11 tiles/sec. Google Hybrid runs ~25, a
    // different server characteristic, so the conclusion does not carry over.
    // Runtime-settable 1..4 so it can be re-measured without a rebuild.
    // ⚠ RAISING takes effect as jobs finish (startNextIfAvailable fills the new
    // slots on the next completion). LOWERING does not pull running jobs back;
    // it drifts down as they finish. Correct, not a bug.
    // QTRUTH-2026-08-08L: default 2 -> 3. Measured 08-08, per slot: 2 slots
    // 13.1 t/s, 3 slots 10.0, 4 slots 8.3 -- aggregate 26.2 / 30.0 / 33.2,
    // zero failures at any setting. Rising with shrinking increments, so 3 is
    // most of the available gain with one fewer connection per source.
    // ⚠ CONFOUNDED: those three jobs hit three DIFFERENT sources, so part of
    // the gain is parallelism across hosts rather than concurrency against
    // one. A clean test is several SAT-only jobs at 2, 3 and 4.
    private var maxConcurrent: Int = 4
    val MAX_CONCURRENT: Int get() = maxConcurrent

    /** QEVAL-2026-08-08I: clamped 1..4 and persisted. */
    fun setMaxConcurrent(context: Context, value: Int) {
        val v = value.coerceIn(1, 4)
        maxConcurrent = v
        try {
            context.getSharedPreferences("convoy_queue", Context.MODE_PRIVATE)
                .edit().putInt("max_concurrent", v).apply()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "setMaxConcurrent persist failed: ${e.message}")
        }
        android.util.Log.i(TAG, "QEVAL-2026-08-08I maxConcurrent=$v")
        startNextIfAvailable()
    }

    /** QEVAL-2026-08-08I: restore the saved value. Safe to call repeatedly. */
    fun loadMaxConcurrent(context: Context) {
        maxConcurrent = try {
            context.getSharedPreferences("convoy_queue", Context.MODE_PRIVATE)
                .getInt("max_concurrent", 4).coerceIn(1, 4)
        } catch (e: Exception) { 4 }
    }
    private const val QUEUE_FILE = "download_queue.json"
    private const val TAG = "DownloadQueue"

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    private var queuePaused = false
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private lateinit var appCtx: Context
    private var initialized = false

    // -- Init: create channel, load persisted queue --------
    fun init(context: Context) {
        if (initialized) return
        appCtx = context.applicationContext
        initialized = true
        ConvoyDownloadNotification.createChannel(appCtx)
        loadQueue()
        // Resume any entries that were DOWNLOADING when app was killed
        val current = _queue.value.toMutableList()
        var changed = false
        for (i in current.indices) {
            if (current[i].status == QueueStatus.DOWNLOADING) {
                current[i] = current[i].copy(status = QueueStatus.QUEUED)
                changed = true
            }
        }
        if (changed) {
            _queue.value = current
            saveQueue()
            startNextIfAvailable()
        }
    }

    // -- Enqueue a new region download ---------------------
    fun enqueue(
        context: Context,
        north: Double, south: Double,
        east: Double, west: Double,
        label: String = ""
    ): QueueEntry {
        init(context)
        val tiles = ConvoyTileCalculator.calculateTiles(north, south, east, west)
        MapSourceManager.init(context)
        // THROUGHPUT-2026-08-08C: was an UNFILTERED sumOf over getDownloadSources
        // -- the layer count of EVERY download source added together (its own
        // log line below says layerCountAllSlots, slot=ALL). Under Esri that
        // summed to 3 and happened to match what one Esri download wrote, so it
        // looked right. Under Google Hybrid one layer is written and the
        // estimate stayed at 3x: measured 08-08, a job estimated 19,000 and
        // finished at 6,463 (2.94x).
        // ⚠ NOT a blanket removal of the multiplier. A slot back on Esri must
        // still count its 3 layers. This takes the layer count of the slot
        // being written, matching enqueueArea's segmented path (:257) and the
        // workers (ConvoyCorridorWorker:114, ConvoyDownloadWorker:83).
        val layerCount = MapSourceManager.getDownloadSources()
            .filter { it.first == "SAT" }.sumOf { it.second.size }
        val totalTiles = tiles.size * Math.max(1, layerCount)

        val entry = QueueEntry(
            north = north, south = south, east = east, west = west,
            totalTiles = totalTiles, label = label,
            // QUEUE-SCHEMA-2026-07-24
            downloadType = DownloadType.AREA,
            priority = DownloadPriority.AREA
        )

        val current = _queue.value.toMutableList()
        current.add(entry)
        _queue.value = current
        saveQueue()
        // SIZECALC-LOG-2026-07-23: unsegmented path - no sizeTiles, no split.
        android.util.Log.i(
            "SIZECALC",
            "src=enqueue label='${entry.label}' slot=ALL " +
            "N=$north S=$south E=$east W=$west " +
            "dLat=${north - south} dLon=${east - west} " +
            "oneLayer=${tiles.size} slotLayers=NA satLayers=NA " +
            "layerCountAllSlots=$layerCount " +
            "sizeTiles=NONE segments=NONE entryTiles=${entry.totalTiles} " +
            "id=${entry.id}"
        )
        android.util.Log.i(TAG, "Enqueued: ${entry.label} (${entry.totalTiles} tiles) id=${entry.id}")
        startNextIfAvailable()
        return entry
    }

    /** Enqueue grid-based refresh: multiple small jobs per ~12 mile cell.
     *  Returns number of cells enqueued. Call from background thread. */
    /** Unified area download: one slot, broken into grid cells */
    fun enqueueArea(
        context: Context,
        slotName: String,
        north: Double, south: Double, east: Double, west: Double,
        replaceExisting: Boolean = false
    ): Int {
        init(context)
        val slotLayers = MapSourceManager.getDownloadSources().filter { it.first == slotName }.sumOf { it.second.size }
        // SEGMENTATION (2026-07-11): size off REAL job volume = 1-layer estimate * slotLayers.
        //   SAT = 3 layers over same geography, so slotLayers=3 -> ~24.7K*3 = ~74K -> 2 segments.
        //   (Bug history: sizing off the bare 1-layer estimate under-segmented; caught by dry-run JSON.)
        val oneLayerTiles = ConvoyTileCalculator.quickEstimate(north, south, east, west).tileCount
        // ALL SOURCES RIDE THE SAT-SIZED GRID (design: "SAT sizes the grid, all ride it").
        // enqueueArea is called once per slot from submitDownload's loop; without this,
        // SAT (3 layers ~74K) split into 2 but TOPO/TOPO+ (~24K) stayed 1 -> mismatched grids.
        // So size off the SAT slot's layer count REGARDLESS of which slot is enqueuing:
        val satLayers = MapSourceManager.getDownloadSources()
            .find { it.first == "SAT" }?.second?.size ?: slotLayers
        val sizeTiles = oneLayerTiles * Math.max(1, satLayers)
        val cells = ConvoyTileDownloader.segmentCells(north, south, east, west, sizeTiles)
        // SIZECALC-LOG-2026-07-23: every term of the sizing decision on one line.
        run {
            val entryTilesWhole =
                ConvoyTileCalculator.calculateTiles(north, south, east, west).size * slotLayers
            android.util.Log.i(
                "SIZECALC",
                "src=enqueueArea label='DL $slotName' slot=$slotName " +
                "N=$north S=$south E=$east W=$west " +
                "dLat=${north - south} dLon=${east - west} " +
                "oneLayer=$oneLayerTiles slotLayers=$slotLayers satLayers=$satLayers " +
                "sizeTiles=$sizeTiles segments=${cells.size} " +
                "entryTilesWholeBox=$entryTilesWhole " +
                "perCellApprox=${entryTilesWhole / Math.max(1, cells.size)} " +
                "replace=$replaceExisting"
            )
        }
        if (cells.isEmpty()) {
            // Small area — single job
            val entry = QueueEntry(
                north = north, south = south, east = east, west = west,
                totalTiles = ConvoyTileCalculator.calculateTiles(north, south, east, west).size * slotLayers,
                label = "DL $slotName",
                refreshMode = replaceExisting,
                refreshSlot = slotName,
                // QUEUE-SCHEMA-2026-07-24: this branch is UNREACHABLE - segmentCells
                // floors at Math.max(1,..) so cells is never empty. Typed
                // anyway so it cannot become a silent AREA-default if the
                // floor ever changes.
                downloadType = DownloadType.AREA,
                priority = DownloadPriority.AREA
            )
            val current = _queue.value.toMutableList()
            current.add(entry)
            _queue.value = current
            saveQueue()
            startNextIfAvailable()
            return 1
        }
        val current = _queue.value.toMutableList()
        cells.forEachIndexed { i, cell ->
            val entry = QueueEntry(
                north = cell[0], south = cell[1], east = cell[2], west = cell[3],
                totalTiles = ConvoyTileCalculator.calculateTiles(cell[0], cell[1], cell[2], cell[3]).size * slotLayers,
                label = "DL $slotName ${i + 1}/${cells.size}",
                refreshMode = replaceExisting,
                refreshSlot = slotName,
                // QUEUE-SCHEMA-2026-07-24
                downloadType = DownloadType.AREA,
                priority = DownloadPriority.AREA
            )
            // SIZECALC-LOG-2026-07-23: per-cell entry as queued.
            android.util.Log.i(
                "SIZECALC",
                "  cell ${i + 1}/${cells.size} src=enqueueArea slot=$slotName " +
                "label='${entry.label}' entryTiles=${entry.totalTiles} " +
                "N=${cell[0]} S=${cell[1]} E=${cell[2]} W=${cell[3]} " +
                "id=${entry.id}"
            )
            current.add(entry)
        }
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG, "Enqueued area: $slotName ${cells.size} cells replace=$replaceExisting")
        startNextIfAvailable()
        return cells.size
    }

    /**
     * BOX 2 - shared enqueue processor. No UI. Callable from any working body,
     * attended or unattended (area / track engine / import loop / sync loop).
     * Takes a bbox + sources chosen up front (box 1); enqueues one divided
     * area-download per source. enqueueArea handles large + small boxes.
     */
    fun submitDownload(
        context: Context,
        north: Double, south: Double, east: Double, west: Double,
        selectedSlots: List<String>,
        replaceExisting: Boolean = false
    ) {
        for (slotName in selectedSlots) {
            enqueueArea(context, slotName, north, south, east, west, replaceExisting)
        }
    }

    /** DELETE-AREA-2026-07-25: queue a tile DELETE for one bbox, one slot.
     *
     *  Mirrors enqueueArea's shape (one entry per slot, submitted from a loop)
     *  but deliberately does NOT segment. Segmentation exists because a
     *  download runs for HOURS and must survive interruption; a delete of the
     *  same area is ~18 indexed statements and completes in under a second.
     *  Segmenting would multiply queue rows for no benefit.
     *
     *  ⚠ refreshMode is LEFT FALSE ON PURPOSE. It is the replace-tiles flag,
     *  but markComplete() still reads it as "is a refresh" and SILENTLY
     *  REMOVES such entries from the queue. A delete entry with refreshMode
     *  set would vanish on completion with no completion row and no stamp.
     *
     *  totalTiles is the GEOMETRY count (tiles in the box x layers) -- what
     *  COULD be there. The worker reports what actually WAS there. */
    fun enqueueDelete(
        context: Context,
        slotName: String,
        north: Double, south: Double, east: Double, west: Double
    ): Int {
        init(context)
        MapSourceManager.init(context)
        val slotLayers = MapSourceManager.getDownloadSources()
            .filter { it.first == slotName }.sumOf { it.second.size }
        // DELETE-BANDING-2026-07-25: count what is ACTUALLY on disk, not what the
        // box could hold. Patch M used the geometry count (tiles in box x
        // layers), which showed "1,500,000 tiles" for an area that held a
        // fraction of that - and left the progress bar with a denominator it
        // could never reach, so a finished job looked permanently stuck.
        //
        // These are indexed COUNT(*)s over tile_index, ~18 per store, and this
        // runs on the background thread submitDelete already uses.
        val tilesInBox = ConvoyTileCalculator.calculateTiles(north, south, east, west)
        val storeNames: List<String> = run {
            val layers = MapSourceManager.getSourceByKey(slotName)?.layers ?: emptyList()
            if (layers.isEmpty()) listOf(slotName)
            else layers.mapIndexed { i, l -> if (i == 0) slotName else l.cacheDir }
        }
        var totalTiles = 0
        for (store in storeNames) {
            if (!MBTilesStore.storeExists(store)) continue
            for ((z, list) in tilesInBox.groupBy { it.z }) {
                totalTiles += MBTilesStore.countTileRange(
                    store, z,
                    list.minOf { it.x }, list.maxOf { it.x },
                    list.minOf { it.y }, list.maxOf { it.y }
                )
            }
        }
        val boxTiles = tilesInBox.size

        val entry = QueueEntry(
            north = north, south = south, east = east, west = west,
            totalTiles = totalTiles,
            label = "DEL $slotName",
            refreshMode = false,
            refreshSlot = slotName,
            downloadType = DownloadType.DELETE_AREA_TILES,
            priority = DownloadPriority.DELETE
        )
        val current = _queue.value.toMutableList()
        current.add(entry)
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG,
            "DELETE queued: slot=$slotName box=$boxTiles geom-tiles, stores=$storeNames, " +
            "ACTUALLY ON DISK=$totalTiles id=${entry.id}")
        if (totalTiles == 0) {
            android.util.Log.i(TAG, "DELETE $slotName: nothing on disk in this area")
        }
        startNextIfAvailable()
        return totalTiles
    }

    /** DELETE-AREA-2026-07-25: submit a delete for EVERY download source.
     *  Fred: "assume deleting all three sources and skip the screen prompt.
     *  Delete is a delete. Clear the area of tiles." One entry per slot so
     *  progress is per-source and cancelling SAT leaves TOPO -- matching how
     *  area jobs already appear in the panel. */
    fun submitDelete(
        context: Context,
        north: Double, south: Double, east: Double, west: Double
    ): Int {
        init(context)
        MapSourceManager.init(context)
        var total = 0
        for ((slotName, _) in MapSourceManager.getDownloadSources()) {
            total += enqueueDelete(context, slotName, north, south, east, west)
        }
        return total
    }

        fun enqueueRefresh(
        context: Context,
        slotName: String,
        sourceName: String
    ): Int {
        init(context)
        val bounds = ConvoyTileDownloader.tileBoundsLatLon(slotName) ?: return 0
        // [2026-07-22] segmentation parity with enqueueArea: SAT sizes the grid, all ride it
        val slotLayers = MapSourceManager.getDownloadSources()
            .filter { it.first == slotName }.sumOf { it.second.size }
        val oneLayerTiles = ConvoyTileCalculator
            .quickEstimate(bounds[0], bounds[1], bounds[2], bounds[3]).tileCount
        val satLayers = MapSourceManager.getDownloadSources()
            .find { it.first == "SAT" }?.second?.size ?: slotLayers
        val sizeTiles = oneLayerTiles * Math.max(1, satLayers)
        val cells = ConvoyTileDownloader.segmentCells(
            bounds[0], bounds[1], bounds[2], bounds[3], sizeTiles
        )
        if (cells.isEmpty()) return 0

        val current = _queue.value.toMutableList()
        cells.forEachIndexed { i, cell ->
            val entry = QueueEntry(
                north = cell[0], south = cell[1], east = cell[2], west = cell[3],
                totalTiles = ConvoyTileCalculator
                    .calculateTiles(cell[0], cell[1], cell[2], cell[3]).size * Math.max(1, slotLayers),
                label = "REFRESH $slotName ${i + 1}/${cells.size}",
                refreshMode = true,
                refreshSlot = slotName,
                // QUEUE-SCHEMA-2026-07-24: refreshMode stays as the REPLACE-TILES
                // behaviour flag; downloadType now carries the KIND. Do not
                // let the two drift into meaning the same thing.
                downloadType = DownloadType.MAP_SOURCE_REFRESH,
                priority = DownloadPriority.REFRESH
            )
            current.add(entry)
        }
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG, "Enqueued refresh: $slotName ${cells.size} grid cells")
        startNextIfAvailable()
        return cells.size
    }

    /** CORRIDOR-QUEUE-2026-07-24: queue a CORRIDOR download for ONE track, ONE source.
     *
     *  Call once per selected slot - one entry per source (matching how
     *  submitDownload/enqueueArea already produce one row per slot), so the
     *  panel needs no special case, progress is per-source, and cancelling
     *  SAT leaves TOPO running.
     *
     *  The corridor is derived HERE for `totalTiles` and AGAIN in the worker
     *  when it runs. Deliberate: without the enqueue-time derivation the panel
     *  shows 0 tiles until the job starts and the ETA has nothing to work
     *  with. Cost is one extra derivation; benefit is the size is visible
     *  immediately - which is what the bbox-vs-corridor comparison needs.
     *
     *  The bbox fields ARE populated, but ONLY for display/progress. The
     *  corridor worker does not download from them.
     *
     *  MUST be called from a background thread (DB read + tile derivation).
     *  @return tiles queued, or 0 if the track has no usable geometry. */
    /** CORRIDORBATCH-2026-08-05: what a corridor batch queued.
     *  jobs    = queue entries created (tracks x slots, minus skips)
     *  tiles   = summed tile count across those entries, for the ETA
     *  skipped = tracks with no usable geometry -- reported, never silent */
    data class CorridorBatchResult(
        val jobs: Int = 0,
        val tiles: Int = 0,
        val skipped: Int = 0
    )

    /** CORRIDORBATCH-2026-08-05: queue corridor downloads for MANY tracks across MANY slots.
     *
     *  Gives corridor the same contract enqueueArea already has: the caller hands
     *  over the work, this owns the decomposition. Three callers share it --
     *  the artifact panel (one track), the download picker (selected tracks), and
     *  refresh (all tracks, ONE map column, replace = true).
     *
     *  ONE ENTRY PER TRACK PER SLOT, matching CORRIDOR-WIRING-2026-07-24: progress
     *  stays per-source and cancelling SAT leaves TOPO running.
     *
     *  A track with no usable geometry is SKIPPED AND COUNTED. enqueueCorridor
     *  returns 0 for those; across a large batch that would otherwise be silent.
     *
     *  ⛔ MUST be called from a background thread -- enqueueCorridor reads the DB
     *  and derives the corridor per call (~50-100 ms per track per slot).
     *
     *  Does NOT clear any store. A clear is destructive and belongs in its own
     *  queued job ordered ahead of this one. */
    /** CORRMIGRATE-2026-08-07H: write a COMPLETED row for a delete that ran
     *  INLINE rather than through the queue.
     *
     *  The corridor delete deliberately does not enter the queue -- it runs
     *  while the queue is held, so it cannot race anything. But the user still
     *  needs to see it happened, and the queue is where this app records work.
     *  markComplete() cannot be used: it expects an entry that was queued and
     *  dispatched. This writes a terminal row directly.
     *
     *  Written AFTER the delete with the ACTUAL removed count, so the row is
     *  truthful. A row written before would claim a completion that had not
     *  happened yet if the app died mid-delete.
     *
     *  Uses the EXISTING DownloadType.DELETE_AREA_TILES. The name says "area"
     *  and this is a corridor, but the behaviour is right (priority 0, sorts
     *  with deletes) and the LABEL carries the distinction to the user. Adding
     *  an enum value would re-trigger the exhaustive `when` at :797 -- the
     *  build-breaking window this whole design exists to avoid. */
    fun recordCompletedDelete(
        context: Context,
        label: String,
        tilesRemoved: Int,
        // CORRMIGRATE-SCOPE-2026-08-07J: NOT nullable. QueueEntry.geomHash is
        // non-null, and the only caller is the corridor delete loop, which
        // reaches this call solely for tracks whose hash came back non-null
        // from allTrackGeomHashes(). No caller legitimately has nothing to
        // pass, so a nullable here would be a shortcut rather than a state.
        geomHash: String
    ) {
        init(context)
        val now = System.currentTimeMillis()
        val entry = QueueEntry(
            status = QueueStatus.COMPLETE,
            totalTiles = tilesRemoved,
            downloadedTiles = tilesRemoved,
            label = label,
            downloadType = DownloadType.DELETE_AREA_TILES,
            priority = DownloadPriority.DELETE,
            createdAt = now,
            completedAt = now,
            geomHash = geomHash
        )
        val current = _queue.value.toMutableList()
        current.add(entry)
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG, "CORRMIGRATE-2026-08-07H recorded: $label tiles=$tilesRemoved")
    }

    fun enqueueCorridorBatch(
        context: Context,
        geomHashes: List<String>,
        slotNames: List<String>,
        replaceExisting: Boolean
    ): CorridorBatchResult {
        if (geomHashes.isEmpty() || slotNames.isEmpty()) {
            android.util.Log.w(TAG,
                "CORRIDORBATCH-2026-08-05: nothing to queue " +
                "(tracks=${geomHashes.size} slots=${slotNames.size})")
            return CorridorBatchResult()
        }
        var jobs = 0
        var tiles = 0
        val skippedHashes = mutableListOf<String>()
        for (hash in geomHashes) {
            // A track with no geometry fails identically for every slot, so record
            // the skip ONCE per track rather than once per slot.
            var queuedForThisTrack = 0
            for (slot in slotNames) {
                val t = enqueueCorridor(context, hash, slot, replaceExisting)
                if (t > 0) { jobs++; tiles += t; queuedForThisTrack++ }
            }
            if (queuedForThisTrack == 0) skippedHashes.add(hash)
        }
        if (skippedHashes.isNotEmpty()) {
            // Joined OUTSIDE the log template: Kotlin does not allow a nested string
            // literal inside a ${...} expression -- the quote terminates the outer string.
            val skippedShort = skippedHashes.joinToString(separator = ", ") { it.take(8) }
            android.util.Log.w(TAG,
                "CORRIDORBATCH-2026-08-05: skipped ${skippedHashes.size} track(s) with no usable " +
                "geometry: $skippedShort")
        }
        android.util.Log.i(TAG,
            "CORRIDORBATCH-2026-08-05: ${geomHashes.size} track(s) x ${slotNames.size} slot(s) " +
            "-> $jobs job(s), $tiles tile(s), ${skippedHashes.size} skipped, " +
            "replace=$replaceExisting")
        return CorridorBatchResult(jobs = jobs, tiles = tiles, skipped = skippedHashes.size)
    }

    fun enqueueCorridor(
        context: Context,
        geomHash: String,
        slotName: String,
        replaceExisting: Boolean = false
    ): Int {
        init(context)
        MapSourceManager.init(context)
        // ROUTECORR-2026-08-10B: tracks first, then routes. See getCorridorGeometry -
        // geom_hash is content-addressed, so the same hash is the same
        // geometry wherever it is stored and the corridor is identical.
        val segments = SpatialDbManager.getCorridorGeometry(context, geomHash)
        if (segments.isNullOrEmpty()) {
            android.util.Log.w(TAG, "enqueueCorridor: no geometry for $geomHash")
            return 0
        }
        // ZOOMSLOT-2026-08-10D: pass the slot so the zoom rule applies. The delete
        // path deliberately does not - see corridorTiles.
        val tiles = ConvoyTileCalculator.corridorTiles(segments, slotName = slotName)
        if (tiles.isEmpty()) {
            android.util.Log.w(TAG, "enqueueCorridor: empty corridor for $geomHash")
            return 0
        }
        val slotLayers = MapSourceManager.getDownloadSources()
            .filter { it.first == slotName }.sumOf { it.second.size }
        val totalTiles = tiles.size * Math.max(1, slotLayers)
        // Bbox for DISPLAY ONLY - the worker derives from geomHash, not this.
        var n = -90.0
        var s = 90.0
        var e = -180.0
        var w = 180.0
        for (seg in segments) {
            for ((lat, lon) in seg) {
                if (lat > n) n = lat
                if (lat < s) s = lat
                if (lon > e) e = lon
                if (lon < w) w = lon
            }
        }
        val entry = QueueEntry(
            north = n, south = s, east = e, west = w,
            totalTiles = totalTiles,
            // QTRUTH-2026-08-08L: was the bare type-plus-slot string for EVERY
            // corridor job --
            // 272 identical rows. The hash is already in hand, so the name is
            // too. Type and priority come from the row format now, so the
            // label carries slot + track only.
            // ROUTECORR-2026-08-10B: one indexed row per table instead of scanning every
            // track. Also picks up route names, so a route corridor reads as its
            // route rather than as a hash fragment.
            label = "$slotName " + (
                SpatialDbManager.nameForGeomHash(geomHash) ?: geomHash.take(8)
            ),
            refreshMode = replaceExisting,
            refreshSlot = slotName,
            downloadType = DownloadType.CORRIDOR,
            priority = DownloadPriority.CORRIDOR,
            geomHash = geomHash
        )
        val current = _queue.value.toMutableList()
        current.add(entry)
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG,
            "CORRIDOR queued: slot=$slotName tiles=${tiles.size} x$slotLayers layers " +
            "= $totalTiles hash=$geomHash id=${entry.id}")
        startNextIfAvailable()
        return totalTiles
    }

    /**
     * RECREATE-2026-08-11G: RECREATE A SOURCE -- re-download every tile the store already
     * holds, in place.
     *
     * WHY THIS EXISTS. enqueueRefresh above derives a bbox from the store's
     * EXTENT and chops that rectangle into grid cells. Coverage in two distant
     * places makes a rectangle spanning both, and every empty cell in between
     * still becomes a job: 3,287 of them on 08-11, with an ETA of 194 days.
     *
     * Here a quadrant is COUNTED before it is queued, and dropped when it holds
     * nothing. Disjoint coverage therefore stays disjoint by construction rather
     * than by inference, and totalTiles is the STORED count so the estimate is
     * honest before the first tile moves.
     *
     * The worker needs no change: it already filters refresh jobs to hasTile, so
     * a tile you do not hold is never fetched. What was wrong was queueing the
     * ground at all.
     *
     * Submitted as MAP_SOURCE_REFRESH -- the existing type. No enum change, so
     * nothing can break a queue file already persisted on a device.
     *
     * Corridor shape survives because nothing here infers shape: the tiles being
     * refreshed ARE the corridor. And with replace-in-place the old tile stays
     * until the new one lands, so coverage never drops while it runs.
     *
     * @return the number of jobs queued
     */
    fun enqueueRecreateSource(
        context: Context,
        slotName: String,
        maxTilesPerJob: Int = 50000
    ): Int {
        init(context)
        MapSourceManager.init(context)

        val levels = MBTilesStore.zoomLevelsPresent(slotName)
        if (levels.isEmpty()) {
            android.util.Log.i(TAG, "RECREATE-2026-08-11G $slotName: nothing stored")
            return 0
        }
        // The finest level present gives the tightest starting box.
        val refZ = levels.max()
        val ext = MBTilesStore.tileExtentAtZoom(slotName, refZ) ?: return 0

        // Stored tiles inside a tile-space box at refZ, summed across EVERY
        // level present. A coarser level is mapped down into refZ space so one
        // box means the same ground at every zoom.
        fun storedIn(x0: Long, x1: Long, y0: Long, y1: Long): Int {
            var n = 0
            for (z in levels) {
                if (z <= refZ) {
                    val s = refZ - z
                    n += MBTilesStore.countInTileRange(
                        slotName, z, x0 shr s, x1 shr s, y0 shr s, y1 shr s)
                } else {
                    val s = z - refZ
                    n += MBTilesStore.countInTileRange(
                        slotName, z, x0 shl s, ((x1 + 1) shl s) - 1,
                        y0 shl s, ((y1 + 1) shl s) - 1)
                }
            }
            return n
        }

        val boxes = ArrayList<LongArray>()
        var dropped = 0
        fun split(x0: Long, x1: Long, y0: Long, y1: Long, depth: Int) {
            val n = storedIn(x0, x1, y0, y1)
            if (n == 0) { dropped++; return }          // <-- the whole point
            val single = (x0 == x1 && y0 == y1)
            if (n <= maxTilesPerJob || single || depth > 24) {
                boxes.add(longArrayOf(x0, x1, y0, y1, n.toLong())); return
            }
            val mx = (x0 + x1) / 2
            val my = (y0 + y1) / 2
            split(x0, mx, y0, my, depth + 1)
            split(mx + 1, x1, y0, my, depth + 1)
            split(x0, mx, my + 1, y1, depth + 1)
            split(mx + 1, x1, my + 1, y1, depth + 1)
        }
        split(ext[0], ext[1], ext[2], ext[3], 0)
        if (boxes.isEmpty()) return 0

        val n = 1L shl refZ
        fun lonOf(x: Long) = x.toDouble() / n * 360.0 - 180.0
        fun latOf(y: Long) = Math.toDegrees(
            Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y.toDouble() / n))))

        val slotLayers = MapSourceManager.getDownloadSources()
            .filter { it.first == slotName }.sumOf { it.second.size }
        val current = _queue.value.toMutableList()
        var totalTiles = 0L
        boxes.forEachIndexed { i, b ->
            val tiles = b[4].toInt() * Math.max(1, slotLayers)
            totalTiles += tiles
            current.add(QueueEntry(
                north = latOf(b[2]), south = latOf(b[3] + 1),
                west = lonOf(b[0]), east = lonOf(b[1] + 1),
                totalTiles = tiles,
                label = "RECREATE $slotName ${i + 1}/${boxes.size}",
                refreshMode = true,
                refreshSlot = slotName,
                downloadType = DownloadType.MAP_SOURCE_REFRESH,
                priority = DownloadPriority.REFRESH
            ))
        }
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG,
            "RECREATE-2026-08-11G $slotName: ${boxes.size} job(s), $totalTiles tile(s), " +
            "$dropped empty quadrant(s) dropped")
        startNextIfAvailable()
        return boxes.size
    }

    /** QUEUE-SCHEMA-2026-07-24: move ONE entry to priority 1 (runs next).
     *  Per ENTRY, not per submission - a 3-source track is 3 rows and 3 taps.
     *  Promotion is ADDITIVE: a second promoted job simply becomes another 1
     *  rather than displacing the first, so nothing is demoted behind the
     *  user's back. Only affects QUEUED entries; a running job is already
     *  past the decision. */
    fun promote(entryId: String) {
        val current = _queue.value.toMutableList()
        val idx = current.indexOfFirst { it.id == entryId }
        if (idx < 0) return
        if (current[idx].status != QueueStatus.QUEUED) return
        current[idx] = current[idx].copy(priority = DownloadPriority.PROMOTED)
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG, "Promoted to priority 1: ${current[idx].label}")
        startNextIfAvailable()
    }

    // -- Cancel a queued or active download -----------------
    fun cancel(entryId: String) {
        val entry = _queue.value.find { it.id == entryId } ?: return
        if (entry.workRequestId != null) {
            try {
                WorkManager.getInstance(appCtx)
                    .cancelWorkById(UUID.fromString(entry.workRequestId))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Cancel WorkManager failed: ${e.message}")
            }
        }
        // Remove from queue entirely — cancelled jobs don't come back
        val current = _queue.value.toMutableList()
        current.removeAll { it.id == entryId }
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG, "Cancelled and removed: ${entry.label} id=$entryId")
        startNextIfAvailable()
    }

    // -- Queue control ------------------------------------
    fun holdQueue() {
        queuePaused = true
        android.util.Log.i(TAG, "Queue PAUSED")
    }
    fun resumeQueue() {
        queuePaused = false
        android.util.Log.i(TAG, "Queue RESUMED")
        startNextIfAvailable()
    }
    fun isQueuePaused(): Boolean = queuePaused
    fun cancelAll() {
        val current = _queue.value.toMutableList()
        for (entry in current) {
            if (entry.workRequestId != null && entry.status == QueueStatus.DOWNLOADING) {
                try {
                    WorkManager.getInstance(appCtx)
                        .cancelWorkById(java.util.UUID.fromString(entry.workRequestId))
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "CancelAll worker failed: ${e.message}")
                }
            }
        }
        val completed = current.filter { it.status == QueueStatus.COMPLETE }
        _queue.value = completed  // keep completed for history, remove rest
        saveQueue()
        android.util.Log.i(TAG, "Cancelled all: ${current.size - completed.size} removed, ${completed.size} complete kept")
    }
    fun removeEntry(entryId: String) {
        val entry = _queue.value.find { it.id == entryId } ?: return
        if (entry.workRequestId != null && entry.status == QueueStatus.DOWNLOADING) {
            try {
                WorkManager.getInstance(appCtx)
                    .cancelWorkById(java.util.UUID.fromString(entry.workRequestId))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Remove worker cancel failed: ${e.message}")
            }
        }
        val current = _queue.value.toMutableList()
        current.removeAll { it.id == entryId }
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG, "Removed: ${entry.label} id=$entryId")
        startNextIfAvailable()
    }
    // -- Clear completed/cancelled/failed entries ----------
    fun clearCompleted() {
        val terminal = setOf(QueueStatus.COMPLETE, QueueStatus.CANCELLED, QueueStatus.FAILED)
        val current = _queue.value.toMutableList()
        current.removeAll { it.status in terminal }
        _queue.value = current
        saveQueue()
    }

    // -- Progress update from Worker -----------------------
    fun updateProgress(entryId: String, downloaded: Int, failed: Int) {
        val current = _queue.value.toMutableList()
        val idx = current.indexOfFirst { it.id == entryId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(downloadedTiles = downloaded, failedTiles = failed)
            _queue.value = current
        }
    }







    // -- Mark complete from Worker -------------------------
    fun markComplete(entryId: String, downloaded: Int, failed: Int) {
        // SHOWALLCOMPLETE-2026-08-05: the refreshMode branch used to SILENTLY REMOVE the entry --
        // no COMPLETE status, no completedAt stamp, no row on the panel. But
        // refreshMode is ALSO the replace-tiles flag, so every REPLACE download
        // deleted its own completion (two Google corridor runs, 561 and 681 tiles,
        // both flawless and both invisible, 08-05). Only FAILED entries survived,
        // which is the opposite of useful. ALL completions are now visible.
        updateEntry(entryId) {
            it.copy(
                // COMPLETEDAT-STAMP-2026-07-24: without this stamp completedAt stays
                // 0L forever, estimateRemaining() finds no usable samples,
                // and the summary's duration / completion-time line never
                // renders. With createdAt this is the elapsed time behind
                // the observed rate (downloadedTiles / elapsed).
                completedAt = System.currentTimeMillis(),
                status = QueueStatus.COMPLETE,
                downloadedTiles = downloaded,
                failedTiles = failed,
                // THROUGHPUT-2026-08-08C: computed here, where duration and
                // tile count are both known and both final. Falls back to
                // createdAt only for an entry that predates the startedAt
                // field -- a stale-but-present number beats none, and the
                // panel can tell the difference because startedAt is 0.
                tilesPerSec = run {
                    val began = if (it.startedAt > 0L) it.startedAt else it.createdAt
                    val sec = (System.currentTimeMillis() - began) / 1000.0
                    if (sec > 0.0) downloaded / sec else 0.0
                }
            )
        }
        android.util.Log.i(TAG, "Complete: id=$entryId downloaded=$downloaded failed=$failed")
        startNextIfAvailable()
    }

    // -- Mark failed from Worker ---------------------------
    fun markFailed(entryId: String, message: String) {
        updateEntry(entryId) {
            it.copy(status = QueueStatus.FAILED, retryCount = it.retryCount + 1)
        }
        android.util.Log.e(TAG, "Failed: id=$entryId reason=$message")
        startNextIfAvailable()
    }

    // -- Launch next queued entry if under concurrency cap --
    private fun startNextIfAvailable() {
        if (!initialized) return
        if (queuePaused) return
        val current = _queue.value
        val running = current.count { it.status == QueueStatus.DOWNLOADING }
        if (running >= MAX_CONCURRENT) return

        val slotsAvailable = MAX_CONCURRENT - running
        // QUEUE-SCHEMA-2026-07-24: there was NO ordering here before - it took
        // whatever came first in list order, which is why refreshes appeared
        // to "run ahead" (they were simply enqueued first). sortedBy is
        // STABLE, so equal priorities keep their relative list order and a
        // promoted job runs ahead of its peers without reshuffling them.
        val queued = current.filter { it.status == QueueStatus.QUEUED }
            .sortedBy { it.priority }
        for (next in queued.take(slotsAvailable)) {
            launchWorker(next)
        }
    }

    private fun launchWorker(entry: QueueEntry) {
        // THROUGHPUT-2026-08-08C: the job starts HERE. Without this stamp the
        // observed rate is measured from enqueue time and is wrong by however
        // long the job waited. Written before the worker is built so it is set
        // even if WorkManager defers the actual start by a moment.
        updateEntry(entry.id) { it.copy(startedAt = System.currentTimeMillis()) }
        val inputData = Data.Builder()
            .putString("entry_id", entry.id)
            .putDouble("north", entry.north)
            .putDouble("south", entry.south)
            .putDouble("east", entry.east)
            .putDouble("west", entry.west)
            .putString("label", entry.label)
            .putBoolean("refresh_mode", entry.refreshMode)
            .putString("refresh_slot", entry.refreshSlot)
            // CORRIDOR-QUEUE-2026-07-24: the corridor worker re-derives its tiles from
            // this. Empty for every non-corridor entry, which ignores it.
            .putString("geom_hash", entry.geomHash)
            // DELETE-BANDING-2026-07-25: the delete worker reports progress against
            // the REAL on-disk count computed at enqueue time, not a geometry
            // estimate it would never reach.
            .putInt("total_expected", entry.totalTiles)
            .build()

        // DELETE-AREA-2026-07-25: a delete needs NO network. Requiring CONNECTED
        // for every job would make freeing storage impossible offline -
        // which is exactly when a user needs to free storage.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (entry.downloadType == DownloadType.DELETE_AREA_TILES)
                    NetworkType.NOT_REQUIRED
                else
                    NetworkType.CONNECTED
            )
            .build()

        // CORRIDOR-WORKER-2026-07-24: the QUEUE TYPE selects the worker. Corridor and
        // delete are their OWN code paths, not branches inside the area
        // worker - delete in particular REMOVES tiles rather than fetching.
        // This `when` is EXHAUSTIVE on purpose: a 5th DownloadType later
        // becomes a COMPILE ERROR here rather than a silent fallthrough.
        val builder = when (entry.downloadType) {
            // Unchanged. Refresh already rides this worker via refreshMode;
            // how source-refresh replaces map data end to end is a SEPARATE
            // question, out of scope (2.6 task).
            DownloadType.AREA,
            DownloadType.MAP_SOURCE_REFRESH ->
                OneTimeWorkRequestBuilder<ConvoyDownloadWorker>()

            DownloadType.CORRIDOR ->
                OneTimeWorkRequestBuilder<ConvoyCorridorWorker>()

            // DELETE-AREA-2026-07-25: wired. Its own worker, not a branch inside
            // the area worker - it REMOVES tiles rather than fetching them.
            DownloadType.DELETE_AREA_TILES ->
                OneTimeWorkRequestBuilder<ConvoyDeleteWorker>()
        }
        val workRequest = builder
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag("grouptrack_tile_download")
            .addTag(entry.id)
            .build()

        WorkManager.getInstance(appCtx).enqueue(workRequest)

        updateEntry(entry.id) {
            it.copy(
                status = QueueStatus.DOWNLOADING,
                workRequestId = workRequest.id.toString()
            )
        }
        android.util.Log.i(TAG, "Launched worker for: ${entry.label} id=${entry.id}")
    }

    // -- Internal: update a single entry -------------------
    private fun updateEntry(entryId: String, transform: (QueueEntry) -> QueueEntry) {
        val current = _queue.value.toMutableList()
        val idx = current.indexOfFirst { it.id == entryId }
        if (idx >= 0) {
            current[idx] = transform(current[idx])
            _queue.value = current
            saveQueue()
        }
    }

    // -- Persistence: save queue to JSON -------------------
    private fun saveQueue() {
        try {
            val dir = File(appCtx.filesDir, "download_queue")
            dir.mkdirs()
            val file = File(dir, QUEUE_FILE)
            val arr = JSONArray()
            for (entry in _queue.value) arr.put(entry.toJson())
            val tmp = File(dir, QUEUE_FILE + ".tmp")
            tmp.writeText(arr.toString(2))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) { tmp.delete() }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Save queue failed: ${e.message}")
        }
    }

    // -- Persistence: load queue from JSON -----------------
    private fun loadQueue() {
        try {
            val file = File(appCtx.filesDir, "download_queue/$QUEUE_FILE")
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            val entries = mutableListOf<QueueEntry>()
            for (i in 0 until arr.length()) {
                entries.add(QueueEntry.fromJson(arr.getJSONObject(i)))
            }
            _queue.value = entries
            android.util.Log.i(TAG, "Loaded ${entries.size} queue entries from disk")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Load queue failed: ${e.message} - quarantining")
            try {
                val bad = File(appCtx.filesDir, "download_queue/" + QUEUE_FILE)
                if (bad.exists()) {
                    val quarantine = File(bad.parentFile, QUEUE_FILE + ".corrupt")
                    if (quarantine.exists()) quarantine.delete()
                    if (!bad.renameTo(quarantine)) bad.delete()
                    android.util.Log.w(TAG, "Quarantined corrupt queue file")
                }
            } catch (q: Exception) {
                android.util.Log.e(TAG, "Quarantine failed: " + q.message)
            }
            _queue.value = emptyList()
        }
    }
}
