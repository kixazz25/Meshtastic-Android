package com.geeksville.mesh.convoy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SOURCECLEAR-2026-08-06
 *
 * Executes the destructive half of a map-source change: stop the queue, clear
 * its history, delete the outgoing source's tile stores.
 *
 * WHAT THIS DOES NOT DO: reload. Clearing and reloading are separate actions
 * by design - the user chooses reload deliberately afterwards (all tracks, a
 * selection, or nothing at all and let the download panel handle it later).
 *
 * WHY THE DIRS COME FROM THE RECORD: ConvoyMapSourceScreen.applySource calls
 * MapSourceManager.updateSlotSource BEFORE the dialog appears, so by the time
 * this runs the slot already resolves to the NEW source. The outgoing source's
 * cache dirs exist nowhere in live state - only in the migration record, which
 * captured them beforehand. Reading them back from the record is therefore not
 * indirection, it is the only correct source, and it makes resuming an
 * interrupted clear identical to running a fresh one.
 *
 * On the SAT column that is three stores, not one: SAT.mbtiles plus
 * SAT_LABELS_PLACES and SAT_LABELS_TRANSPORT. Deleting only the base leaves
 * ~461 MB of orphaned label tiles belonging to a source no longer assigned
 * anywhere, with nothing left that knows to remove them.
 */
object ConvoySourceClear {

    private const val TAG = "SourceClear"

    /** How long to wait for cancelled workers to actually stop. */
    private const val IDLE_TIMEOUT_MS = 15_000L
    private const val IDLE_POLL_MS = 250L

    /**
     * SOURCEDETACH-2026-08-06
     *
     * A scope that OUTLIVES THE SCREEN.
     *
     * The caller used rememberCoroutineScope(), which is cancelled the moment
     * the composable leaves composition - so navigating away from Map Sources
     * mid-delete could kill a GB-scale operation partway through. The record
     * stays accurate (noteDeleted runs per store) but the column is left half
     * cleared and the user is not told.
     *
     * SupervisorJob so one failed migration cannot cancel a sibling.
     */
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Run [clearColumn] outside the caller's lifecycle. [onResult] is invoked
     * on Main so Compose state is never written from an IO thread; if the
     * screen is gone by then it simply writes state nobody observes.
     */
    fun clearColumnDetached(record: File, onResult: (Result) -> Unit) {
        detachedScope.launch {
            val r = clearColumn(record)
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    /** Same detachment for the non-destructive path. */
    fun removeOrphanedStoresDetached(record: File, onResult: (Result) -> Unit) {
        detachedScope.launch {
            val r = removeOrphanedStores(record)
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    sealed class Result {
        data class Success(
            val deletedDirs: List<String>,
            val bytesFreed: Long
        ) : Result()

        data class Failed(val reason: String) : Result()
    }

    /**
     * Run the clear described by [record].
     *
     * Safe to call on a fresh record or on one left behind by an interrupted
     * run - already-deleted dirs are skipped, so a resume finishes the job
     * rather than repeating it.
     *
     * The queue is resumed on every exit path, including failure. A migration
     * that fails should not also leave the user with a permanently paused
     * queue.
     */
    suspend fun clearColumn(record: File): Result = withContext(Dispatchers.IO) {
        val root = ConvoySourceMigration.read(record)
            ?: return@withContext Result.Failed("migration record unreadable")

        val slot = root.optString("slot", "?")
        val fromArr = root.optJSONArray("from_cache_dirs")
        if (fromArr == null || fromArr.length() == 0) {
            val why = "record has no from_cache_dirs - nothing safe to delete"
            ConvoySourceMigration.fail(record, why)
            return@withContext Result.Failed(why)
        }

        val allDirs = ArrayList<String>()
        for (i in 0 until fromArr.length()) allDirs.add(fromArr.getString(i))

        val alreadyArr = root.optJSONArray("deleted_cache_dirs")
        val already = HashSet<String>()
        if (alreadyArr != null) {
            for (i in 0 until alreadyArr.length()) already.add(alreadyArr.getString(i))
        }
        val todo = allDirs.filter { it !in already }
        if (already.isNotEmpty()) {
            Log.i(TAG, "RESUME $slot: ${already.size} already deleted, ${todo.size} remaining")
        }

        // Measure before deleting - afterwards the files are gone and the
        // number the user is shown would be zero.
        val bytesBefore = ConvoySourceMigration.totalBytes(todo)

        try {
            // 1 - no new jobs start
            ConvoySourceMigration.setPhase(record, "holding_queue")
            DownloadQueueManager.holdQueue()

            // 2 - cancel in-flight workers (keeps COMPLETE entries)
            ConvoySourceMigration.setPhase(record, "cancelling")
            DownloadQueueManager.cancelAll()

            // 3 - cancelWorkById is async: WAIT for workers to actually stop.
            //     Deleting a store out from under a live worker is how you get
            //     a half-written file or a wedged handle.
            ConvoySourceMigration.setPhase(record, "awaiting_idle")
            if (!awaitIdle()) {
                val why = "workers still running after ${IDLE_TIMEOUT_MS}ms - " +
                    "refusing to delete stores under a live worker"
                ConvoySourceMigration.fail(record, why)
                DownloadQueueManager.resumeQueue()
                return@withContext Result.Failed(why)
            }

            // 4 - drain the history cancelAll deliberately kept.
            //     The queue is the record, so this is a deliberate destruction
            //     of evidence - which is precisely why the migration record
            //     exists and lives outside the maps tree.
            ConvoySourceMigration.setPhase(record, "clearing_queue")
            DownloadQueueManager.clearCompleted()
            ConvoySourceMigration.noteQueueCleared(record)

            // 5 - delete the OLD source's stores, recording each as it goes so
            //     an interruption resumes exactly rather than approximately.
            ConvoySourceMigration.setPhase(record, "deleting_tiles")
            val deleted = ArrayList<String>()
            val failedDirs = ArrayList<String>()
            for (dir in todo) {
                val ok = try {
                    MBTilesStore.deleteSource(dir)
                } catch (e: Exception) {
                    Log.e(TAG, "deleteSource $dir threw: ${e.message}")
                    false
                }
                if (ok) {
                    ConvoySourceMigration.noteDeleted(record, dir)
                    deleted.add(dir)
                } else {
                    failedDirs.add(dir)
                }
            }

            if (failedDirs.isNotEmpty()) {
                val why = "could not delete: ${failedDirs.joinToString(", ")}"
                ConvoySourceMigration.fail(record, why)
                DownloadQueueManager.resumeQueue()
                return@withContext Result.Failed(why)
            }

            // 6 - done
            ConvoySourceMigration.complete(record)
            DownloadQueueManager.resumeQueue()
            Log.i(TAG, "CLEARED $slot: ${deleted.size} store(s), $bytesBefore bytes")
            Result.Success(deleted + already.toList(), bytesBefore)

        } catch (e: Exception) {
            val why = "unexpected failure: ${e.message}"
            ConvoySourceMigration.fail(record, why)
            try { DownloadQueueManager.resumeQueue() } catch (_: Exception) {}
            Result.Failed(why)
        }
    }

    /**
     * Poll until no entry is DOWNLOADING, or the timeout expires.
     * DownloadQueueManager.queue is a public StateFlow, so this needs no
     * changes inside the queue manager.
     */
    private suspend fun awaitIdle(): Boolean {
        var waited = 0L
        while (waited < IDLE_TIMEOUT_MS) {
            val busy = DownloadQueueManager.queue.value
                .count { it.status == QueueStatus.DOWNLOADING }
            if (busy == 0) {
                if (waited > 0) Log.i(TAG, "workers idle after ${waited}ms")
                return true
            }
            delay(IDLE_POLL_MS)
            waited += IDLE_POLL_MS
        }
        val stillBusy = DownloadQueueManager.queue.value
            .count { it.status == QueueStatus.DOWNLOADING }
        Log.e(TAG, "awaitIdle TIMEOUT with $stillBusy still downloading")
        return false
    }

    /**
     * SOURCEORPHAN-2026-08-06
     *
     * Remove stores the NEW source will never use.
     *
     * The label stores are orphaned by the SOURCE CHANGE, not by clearing.
     * Google Hybrid declares no overlay layers, so the moment the slot flips
     * nothing will ever fetch or draw SAT_LABELS_PLACES or
     * SAT_LABELS_TRANSPORT again - under clear, replace, or keep alike. They
     * become files belonging to a source no longer assigned anywhere, with
     * nothing else in the app aware they exist.
     *
     * THE RULE: from_cache_dirs MINUS to_cache_dirs is dead on every path.
     * The tile-handling choice only ever governs the BASE store. On an
     * Esri -> Esri change both sets contain the overlays, so this correctly
     * does nothing.
     *
     * clearColumn already deletes all of from_cache_dirs and therefore covers
     * the orphans implicitly. This is for the NON-destructive paths - keep,
     * and later replace-in-place - which reclaim the labels while leaving the
     * base store alone.
     *
     * Deliberately does NOT cancel the queue: keep is meant to be
     * non-disruptive, and cancelling a user's downloads to reclaim space they
     * did not ask about would be a surprise. If anything is downloading this
     * FAILS and asks for a retry when idle.
     */
    suspend fun removeOrphanedStores(record: File): Result = withContext(Dispatchers.IO) {
        val root = ConvoySourceMigration.read(record)
            ?: return@withContext Result.Failed("migration record unreadable")

        val fromArr = root.optJSONArray("from_cache_dirs")
        val toArr = root.optJSONArray("to_cache_dirs")

        val from = ArrayList<String>()
        if (fromArr != null) for (i in 0 until fromArr.length()) from.add(fromArr.getString(i))
        val to = HashSet<String>()
        if (toArr != null) for (i in 0 until toArr.length()) to.add(toArr.getString(i))

        val alreadyArr = root.optJSONArray("deleted_cache_dirs")
        val already = HashSet<String>()
        if (alreadyArr != null) {
            for (i in 0 until alreadyArr.length()) already.add(alreadyArr.getString(i))
        }

        val orphans = from.filter { it !in to && it !in already }
        if (orphans.isEmpty()) {
            Log.i(TAG, "no orphaned stores for this change")
            ConvoySourceMigration.setPhase(record, "no_orphans")
            return@withContext Result.Success(emptyList(), 0L)
        }

        val busy = DownloadQueueManager.queue.value
            .count { it.status == QueueStatus.DOWNLOADING }
        if (busy > 0) {
            val why = "$busy download(s) in progress - try again when the queue is idle"
            Log.e(TAG, why)
            return@withContext Result.Failed(why)
        }

        val bytes = ConvoySourceMigration.totalBytes(orphans)
        ConvoySourceMigration.setPhase(record, "deleting_orphans")
        Log.i(TAG, "orphans: ${orphans.joinToString(", ")} ($bytes bytes)")

        val deleted = ArrayList<String>()
        val failedDirs = ArrayList<String>()
        for (dir in orphans) {
            val ok = try {
                MBTilesStore.deleteSource(dir)
            } catch (e: Exception) {
                Log.e(TAG, "deleteSource $dir threw: ${e.message}")
                false
            }
            if (ok) {
                ConvoySourceMigration.noteDeleted(record, dir)
                deleted.add(dir)
            } else {
                failedDirs.add(dir)
            }
        }

        if (failedDirs.isNotEmpty()) {
            val why = "could not delete: ${failedDirs.joinToString(", ")}"
            ConvoySourceMigration.fail(record, why)
            return@withContext Result.Failed(why)
        }

        Log.i(TAG, "removed ${deleted.size} orphaned store(s), $bytes bytes")
        Result.Success(deleted, bytes)
    }

    /**
     * True when a previous clear did not finish. The caller should PROMPT
     * rather than resume automatically - finishing means deleting GB of tiles,
     * and the launch that discovers it may be at a trailhead.
     */
    fun hasUnfinished(): Boolean = ConvoySourceMigration.inProgress().isNotEmpty()
}
