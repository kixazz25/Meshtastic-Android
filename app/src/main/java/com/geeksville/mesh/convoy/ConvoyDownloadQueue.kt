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
    val refreshSlot: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
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
            refreshSlot = j.optString("refreshSlot", "")
        )
    }
}

// ===========================================================
// DownloadQueueManager -- singleton queue, max 2 concurrent
// ===========================================================
object DownloadQueueManager {

    private const val MAX_CONCURRENT = 2
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
        val layerCount = MapSourceManager.getDownloadSources().sumOf { it.second.size }
        val totalTiles = tiles.size * layerCount

        val entry = QueueEntry(
            north = north, south = south, east = east, west = west,
            totalTiles = totalTiles, label = label
        )

        val current = _queue.value.toMutableList()
        current.add(entry)
        _queue.value = current
        saveQueue()
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
        val cells = ConvoyTileDownloader.gridCells(north, south, east, west)
        if (cells.isEmpty()) {
            // Small area — single job
            val entry = QueueEntry(
                north = north, south = south, east = east, west = west,
                totalTiles = 0,
                label = "DL $slotName",
                refreshMode = replaceExisting,
                refreshSlot = slotName
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
                totalTiles = 0,
                label = "DL $slotName ${i + 1}/${cells.size}",
                refreshMode = replaceExisting,
                refreshSlot = slotName
            )
            current.add(entry)
        }
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG, "Enqueued area: $slotName ${cells.size} cells replace=$replaceExisting")
        startNextIfAvailable()
        return cells.size
    }

        fun enqueueRefresh(
        context: Context,
        slotName: String,
        sourceName: String
    ): Int {
        init(context)
        val bounds = ConvoyTileDownloader.tileBoundsLatLon(slotName) ?: return 0
        val cells = ConvoyTileDownloader.gridCells(bounds[0], bounds[1], bounds[2], bounds[3])
        if (cells.isEmpty()) return 0

        val current = _queue.value.toMutableList()
        cells.forEachIndexed { i, cell ->
            val entry = QueueEntry(
                north = cell[0], south = cell[1], east = cell[2], west = cell[3],
                totalTiles = 0,
                label = "REFRESH $slotName ${i + 1}/${cells.size}",
                refreshMode = true,
                refreshSlot = slotName
            )
            current.add(entry)
        }
        _queue.value = current
        saveQueue()
        android.util.Log.i(TAG, "Enqueued refresh: $slotName ${cells.size} grid cells")
        startNextIfAvailable()
        return cells.size
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
        val entry = _queue.value.find { it.id == entryId }
        if (entry?.refreshMode == true) {
            // Refresh cells: silently remove from queue, no log clutter
            val current = _queue.value.toMutableList()
            current.removeAll { it.id == entryId }
            _queue.value = current
            saveQueue()
            android.util.Log.d(TAG, "Refresh cell complete, removed: id=$entryId downloaded=$downloaded")
        } else {
            updateEntry(entryId) {
                it.copy(
                    status = QueueStatus.COMPLETE,
                    downloadedTiles = downloaded,
                    failedTiles = failed
                )
            }
            android.util.Log.i(TAG, "Complete: id=$entryId downloaded=$downloaded failed=$failed")
        }
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
        val queued = current.filter { it.status == QueueStatus.QUEUED }
        for (next in queued.take(slotsAvailable)) {
            launchWorker(next)
        }
    }

    private fun launchWorker(entry: QueueEntry) {
        val inputData = Data.Builder()
            .putString("entry_id", entry.id)
            .putDouble("north", entry.north)
            .putDouble("south", entry.south)
            .putDouble("east", entry.east)
            .putDouble("west", entry.west)
            .putString("label", entry.label)
            .putBoolean("refresh_mode", entry.refreshMode)
            .putString("refresh_slot", entry.refreshSlot)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ConvoyDownloadWorker>()
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
            file.writeText(arr.toString(2))
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
            android.util.Log.e(TAG, "Load queue failed: ${e.message}")
        }
    }
}
