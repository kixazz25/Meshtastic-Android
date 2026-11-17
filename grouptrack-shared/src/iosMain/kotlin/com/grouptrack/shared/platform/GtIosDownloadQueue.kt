package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.queue.GtQueueEntry
import com.grouptrack.shared.queue.GtQueueJson
import com.grouptrack.shared.queue.GtQueueStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path

/**
 * IOSDLQ-2026-11-17: the iPad's download-queue RUNTIME -- 2.7e DownloadQueueManager's state, its file
 * and its scheduler, on the PLATFORM side.
 *
 * ⭐ WHY THE PLATFORM SIDE AND NOT commonMain. That is the design queue16c certified, and its own
 * header says so: "NOTHING LIVE IS WRITTEN: the OLD copy's enqueueArea adds to
 * DownloadQueueCertOld.collected (no init, no saveQueue, no worker); NEW's platform collects." So
 * shared code owns the ARITHMETIC (GtVectorQueue, GtTileMath) and the RECORD (GtQueueEntry,
 * GtQueueJson, both certified); the platform owns the runtime, reached through GtQueuePlatform.append.
 * ⛔ A shared runtime was written on 11-17 and REMOVED unbuilt (SEAMREF-2026-11-17): the call graph
 * around DownloadQueueManager is ~20 enqueuers, ~21 worker callbacks and ~55 panel reads, all naming
 * one Android object, so moving it would re-point ~100 call sites against a certified design. Do not
 * reintroduce it.
 *
 * ⭐ THE WORK ARRIVES AS A LAMBDA, which is GtStateDataQueue's certified shape (17e2):
 * `GtStateDataQueue(io, abort)` takes its work as `fetch` and reports through `onChange`. Same here:
 * [fetch] does the downloading and answers true on success. That is what lets the HARNESS certify this
 * runtime -- state, file, quarantine, interrupted-entry resume, selection -- with a deterministic fake
 * fetch and no network at all, before any URLSession code exists.
 *
 * ⚠ WHAT THIS CERTIFIES AND WHAT IT DOES NOT. It runs the picked entries one after another in the
 * order chosen. 2.7e runs them CONCURRENTLY in up to maxConcurrent workers. The SELECTION is identical
 * and that is what the harness pins; true concurrency belongs to the real fetch and is deferred with
 * it. Saying this out loud because "the queue is certified" must not come to mean "downloads are
 * concurrent on the iPad".
 *
 * ⛔ THE PICKER MUST BE SERIALISED. 2.7e marks startNextIfAvailable @Synchronized for a measured
 * reason -- VECONE-2026-11-01: "10-31 18:14 14 jobs got 22 worker launches". commonMain has no
 * @Synchronized and iosMain has no cheap monitor, so [picking] is a re-entrancy latch: a second caller
 * returns instead of launching. It is NOT a lock -- it does not wait -- which is correct here, because
 * the second caller's only job was to fill free slots and the first caller is already doing that.
 */
class IosDownloadQueue(
    private val ids: GtIds,
    private val clock: GtClock,
    private val paths: GtImportPaths = IosImportPaths(),
    private val fs: FileSystem = FileSystem.SYSTEM,
    /** The work. True = the entry downloaded. 2.7e's ConvoyDownloadWorker / CorridorWorker /
     *  DeleteWorker; a deterministic fake in the harness. It receives a progress reporter so it can
     *  call back mid-run exactly as the Android workers do. */
    private val fetch: (GtQueueEntry, (downloaded: Int, failed: Int) -> Unit) -> Boolean,
) {
    private val log = gtLogger("DownloadQueue")

    private var entries: List<GtQueueEntry> = emptyList()
    private var queuePaused = false
    private var initialized = false
    private var loaded = false
    private var picking = false
    private var pendingPick = false

    /** QEVAL-2026-08-08I: runtime-settable, clamped 1..4, as 2.7e's setMaxConcurrent clamps it. */
    var maxConcurrent: Int = 4
        set(v) { field = if (v < 1) 1 else if (v > 4) 4 else v }

    fun queue(): List<GtQueueEntry> = entries
    fun isQueuePaused(): Boolean = queuePaused
    fun holdQueue() { queuePaused = true }
    fun resumeQueue() { queuePaused = false; startNextIfAvailable() }

    // ---- the file ------------------------------------------------------------------------------

    private fun dir(): Path = paths.filesDir() / QUEUE_DIR
    private fun file(): Path = dir() / QUEUE_FILE

    /** 2.7e saveQueue: mkdirs, the JSONArray as toString(2) -- which is GtQueueJson.fileText, already
     *  certified -- to a .tmp, delete the target if present, rename the tmp over it, and on a failed
     *  rename DELETE THE TMP rather than leave it behind. */
    fun saveQueue() {
        try {
            val d = dir(); fs.createDirectories(d)
            val f = file(); val tmp = d / (QUEUE_FILE + ".tmp")
            fs.write(tmp) { writeUtf8(GtQueueJson.fileText(entries)) }
            if (fs.exists(f)) try { fs.delete(f, mustExist = true) } catch (e: Exception) { }
            try { fs.atomicMove(tmp, f) } catch (e: Exception) {
                try { fs.delete(tmp, mustExist = true) } catch (e2: Exception) { }
                log.e { "Save queue failed: ${e.message}" }
            }
        } catch (e: Exception) { log.e { "Save queue failed: ${e.message}" } }
    }

    /** 2.7e loadQueue: rebuild every entry, and on ANY failure QUARANTINE the file to <name>.corrupt
     *  and continue with an EMPTY queue. The quarantine is the point -- a corrupt queue must not stop
     *  the app and must not vanish either. A previous .corrupt is removed first; if the rename fails
     *  the bad file is deleted, so the app never starts by failing to parse. */
    fun loadQueue() {
        val f = file()
        try {
            if (!fs.exists(f)) return
            val arr = GtOrgJson.parse(fs.read(f) { readUtf8() }) as JsonArray
            entries = arr.map { GtQueueJson.fromJson(it as JsonObject, ids, clock) }
            log.i { "Loaded ${entries.size} queue entries from disk" }
        } catch (e: Exception) {
            log.e { "Load queue failed: ${e.message} - quarantining" }
            try {
                if (fs.exists(f)) {
                    val q = dir() / (QUEUE_FILE + ".corrupt")
                    if (fs.exists(q)) try { fs.delete(q, mustExist = true) } catch (e2: Exception) { }
                    try { fs.atomicMove(f, q) } catch (e2: Exception) {
                        try { fs.delete(f, mustExist = true) } catch (e3: Exception) { }
                    }
                    log.w { "Quarantined corrupt queue file" }
                }
            } catch (e2: Exception) { log.e { "Quarantine failed: ${e2.message}" } }
            entries = emptyList()
        }
    }

    /**
     * 2.7e init()'s tail: load, then flip every interrupted entry (DOWNLOADING -> QUEUED), and ONLY IF
     * SOMETHING CHANGED save and schedule.
     * ⭐ THE TILE QUEUE AUTO-RESTARTS; THE STATE IMPORT DOES NOT, and both are deliberate. A tile
     * fetch is idempotent, so re-running it costs bandwidth and nothing else. An interrupted state
     * import is stamped by startup housekeeping and the RIDER reruns it (GtManifestSweep; downloads
     * are KEPT so nothing is fetched twice), because it has stages and needs a decision.
     */
    fun initAndResume() {
        if (initialized) return
        initialized = true
        ensureLoaded()
        var changed = false
        entries = entries.map {
            if (it.status == GtQueueStatus.DOWNLOADING) { changed = true; it.copy(status = GtQueueStatus.QUEUED) } else it
        }
        if (changed) { saveQueue(); startNextIfAvailable() }
    }

    /**
     * BUSYREAD-2026-11-17: load the file ONCE, with no flip and no scheduling -- for a READER that
     * needs the queue's state and must not change it.
     *
     * ⚠ WHY THIS EXISTS, measured while writing the harness. GtRecipeEnv.vectorBusyCount called
     * [initAndResume] because 2.7e calls DownloadQueueManager.init(context) there and its comment says
     * "the init is part of the check, not setup". On ANDROID that is harmless: startNextIfAvailable
     * launches ASYNC WorkManager workers, so the count taken immediately after still sees
     * QUEUED/DOWNLOADING. Here the fetch is SYNCHRONOUS, so scheduling inside the read ran every job
     * to completion before the count and answered 0 where Android answers 3 -- a read that erased what
     * it was about to report. The DOWNLOADING -> QUEUED flip is count-neutral (both are counted), so
     * only the scheduling had to go.
     * ⚠ Calling loadQueue() directly instead would be its own bug: progress held in memory is
     * deliberately NOT saved (see updateProgress), so a reload on a live queue discards it. Hence once.
     */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        loadQueue()
    }

    // ---- GtQueuePlatform.append's contract ------------------------------------------------------

    /** Add in order, save, then start what can start. 2.7e holds its lock for the list change and the
     *  save and calls startNextIfAvailable AFTER it -- that shape is kept. */
    fun append(newEntries: List<GtQueueEntry>) {
        if (newEntries.isNotEmpty()) {
            entries = entries + newEntries
            saveQueue()
        }
        startNextIfAvailable()
    }

    // ---- the worker callbacks (2.7e's 21 external call sites) -----------------------------------

    /** ⚠ 2.7e updateProgress sets the state DIRECTLY and does NOT save -- unlike markComplete /
     *  markFailed, which go through updateEntry and do. Deliberate: progress fires per batch and
     *  saving each time would thrash the file. Do not "fix" this into a save. */
    fun updateProgress(entryId: String, downloaded: Int, failed: Int) {
        val i = entries.indexOfFirst { it.id == entryId }
        if (i < 0) return
        entries = entries.toMutableList().also {
            it[i] = it[i].copy(downloadedTiles = downloaded, failedTiles = failed)
        }
    }

    /**
     * 2.7e markComplete. ⭐ SHOWALLCOMPLETE-2026-08-05: the refreshMode branch used to SILENTLY REMOVE
     * the entry -- and refreshMode is also the replace-tiles flag, so every REPLACE download deleted
     * its own completion (two Google corridor runs, 561 and 681 tiles, both flawless and both
     * invisible). ALL completions are visible.
     * COMPLETEDAT-STAMP-2026-07-24: without the stamp completedAt stays 0L, estimateRemaining finds no
     * samples, and the summary's duration line never renders.
     * THROUGHPUT-2026-08-08C: tilesPerSec computed here, where duration and count are both final;
     * began falls back to createdAt only for an entry predating startedAt -- the panel can tell,
     * because startedAt is 0.
     */
    fun markComplete(entryId: String, downloaded: Int, failed: Int) {
        updateEntry(entryId) {
            val began = if (it.startedAt > 0L) it.startedAt else it.createdAt
            val sec = (clock.epochMs() - began) / 1000.0
            it.copy(
                completedAt = clock.epochMs(),
                status = GtQueueStatus.COMPLETE,
                downloadedTiles = downloaded,
                failedTiles = failed,
                tilesPerSec = if (sec > 0.0) downloaded / sec else 0.0,
            )
        }
        log.i { "Complete: id=$entryId downloaded=$downloaded failed=$failed" }
        startNextIfAvailable()
    }

    /** 2.7e markFailed: FAILED and retryCount + 1, then schedule. */
    fun markFailed(entryId: String, message: String) {
        updateEntry(entryId) { it.copy(status = GtQueueStatus.FAILED, retryCount = it.retryCount + 1) }
        log.e { "Failed: id=$entryId reason=$message" }
        startNextIfAvailable()
    }

    /** 2.7e updateEntry: replace one entry by id in place, keeping list order, and SAVE. */
    fun updateEntry(entryId: String, transform: (GtQueueEntry) -> GtQueueEntry) {
        val i = entries.indexOfFirst { it.id == entryId }
        if (i < 0) return
        entries = entries.toMutableList().also { it[i] = transform(it[i]) }
        saveQueue()
    }

    // ---- the scheduler -------------------------------------------------------------------------

    /**
     * 2.7e startNextIfAvailable, refusal for refusal and in order: not initialized, paused, already at
     * maxConcurrent, else fill the free slots.
     * ⭐ sortedBy IS STABLE and QUEUE-SCHEMA-2026-07-24 says why: there was NO ordering once, so
     * refreshes appeared to "run ahead" when they had merely been enqueued first. Equal priorities keep
     * list order, so a promoted job runs ahead of its peers without reshuffling them.
     * ⛔ [picking] is VECONE-2026-11-01's @Synchronized in the only form available here: a re-entrancy
     * latch. markComplete and markFailed both call back into this while it is running.
     *
     * ⭐ DRAIN-2026-11-17 -- WHY THE LOOP, and the defect it fixes. Kotlin's @Synchronized is a
     * REENTRANT monitor: in 2.7e the worker callback calls markComplete, which calls back into this on
     * a thread that may already hold it, re-enters, and starts the next job. A bare boolean latch is
     * NOT reentrant, so it refused that legitimate re-entry: simulated at 14 jobs with maxConcurrent 4
     * the queue launched 4, completed them, and left the other 10 QUEUED FOREVER. [pendingPick] carries
     * the refused re-entry back out to this loop, which is how a SYNCHRONOUS fetch reproduces async
     * re-entry. Measured in gt_dlqsim_2026-11-17_v2 cases 8, 16, 17, 18.
     * ⚠ THE LOOP MUST NOT OUTLIVE THE SYNCHRONOUS FETCH. It turns once per completion, never past the
     * free slots: an async fetch returns with its entry DOWNLOADING, so pendingPick is false and the
     * loop stops with exactly maxConcurrent running (case 17). When real URLSession code lands, that
     * case is the one that must keep passing -- otherwise this becomes a loop that ignores the limit.
     * ⚠ EVERY MEMBER CAN RETURN THE RIGHT VALUE AND THE SERVICE STILL BE WRONG. A per-member harness
     * passes the stalled queue: append appended, markComplete completed. Only running the JOB catches
     * it. That is what the functional compare is for.
     */
        // ⭐ RULEMOD-2026-11-17: SELECTION (order, the 1..4 clamp, the drain) is a RULE and belongs in
        // the deferred shared rule module -- see the full note on GtJobSurface. The RUNTIME around it
        // does not: it writes files. Today the rule lives HERE and in the other platform's copy, so a
        // change to how the next entry is chosen must be made in both. There is no list but that note.
    fun startNextIfAvailable() {
        if (!initialized) return
        if (queuePaused) return
        if (picking) { pendingPick = true; return }
        picking = true
        try {
            while (true) {
                pendingPick = false
                if (queuePaused) break
                val running = entries.count { it.status == GtQueueStatus.DOWNLOADING }
                if (running >= maxConcurrent) break
                val slots = maxConcurrent - running
                val queued = entries.filter { it.status == GtQueueStatus.QUEUED }.sortedBy { it.priority }
                if (queued.isEmpty()) break
                for (next in queued.take(slots)) run(next)
                if (!pendingPick) break
            }
        } finally { picking = false }
    }

    /** One entry: stamp startedAt and DOWNLOADING (2.7e launchWorker stamps startedAt BEFORE building
     *  the worker, "so it is set even if WorkManager defers the actual start"), then hand it to [fetch]
     *  and record the outcome through the same two calls the Android workers make. */
    private fun run(entry: GtQueueEntry) {
        updateEntry(entry.id) { it.copy(startedAt = clock.epochMs(), status = GtQueueStatus.DOWNLOADING) }
        val ok = try {
            fetch(entry) { d, f -> updateProgress(entry.id, d, f) }
        } catch (e: Exception) {
            log.e { "fetch threw for ${entry.id}: ${e.message}" }
            false
        }
        val cur = entries.firstOrNull { it.id == entry.id }
        if (ok) markComplete(entry.id, cur?.downloadedTiles ?: 0, cur?.failedTiles ?: 0)
        else markFailed(entry.id, "fetch returned false")
    }

    companion object {
        const val QUEUE_DIR = "download_queue"
        const val QUEUE_FILE = "download_queue.json"
    }
}
