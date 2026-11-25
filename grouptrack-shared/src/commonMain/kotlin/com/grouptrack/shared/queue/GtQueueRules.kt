package com.grouptrack.shared.queue

/**
 * QUEUERULES-2026-11-25 -- RULEMOD-2026-11-17's shared rule module, for the download queue.
 *
 * The DECISIONS 2.7e DownloadQueueManager makes on its list, as pure functions over GtQueueEntry:
 * no I/O, no platform. Each platform's RUNTIME (Android DownloadQueueManager, iPad IosDownloadQueue)
 * applies the rule, then does its own save, worker cancel and reschedule -- the runtime writes files
 * and is not a rule (RULEMOD's own admission test).
 *
 * Source: app/src/main/java/com/geeksville/mesh/convoy/ConvoyDownloadQueue.kt -- setMaxConcurrent /
 * loadMaxConcurrent :180-199, promote :1045, cancel :1059, cancelAll :1090, removeEntry :1108,
 * clearCompleted :1127. One rule, both platforms: change it here and both change.
 *
 * A null return means "no change" -- 2.7e returns early there without saving, logging or
 * rescheduling, so the runtime must do the same.
 */
object GtQueueRules {

    /** QEVAL-2026-08-08I: the value when nothing is saved. */
    const val MAX_CONCURRENT_DEFAULT = 4

    /** QEVAL-2026-08-08I: concurrency is clamped 1..4. */
    fun clampConcurrent(value: Int): Int = value.coerceIn(1, 4)

    /** What clearCompleted removes. */
    val TERMINAL: Set<GtQueueStatus> =
        setOf(GtQueueStatus.COMPLETE, GtQueueStatus.CANCELLED, GtQueueStatus.FAILED)

    /** 2.7e promote: QUEUED only; priority -> PROMOTED. Additive -- a second promoted job becomes
     *  another 1, nothing is demoted. Null when the id is absent or the entry is not QUEUED. */
    fun promote(entries: List<GtQueueEntry>, entryId: String): List<GtQueueEntry>? {
        val idx = entries.indexOfFirst { it.id == entryId }
        if (idx < 0) return null
        if (entries[idx].status != GtQueueStatus.QUEUED) return null
        return entries.toMutableList().also { it[idx] = it[idx].copy(priority = GtDownloadPriority.PROMOTED) }
    }

    /** 2.7e cancel and removeEntry share this list change: remove the entry entirely.
     *  Null when the id is absent. */
    fun remove(entries: List<GtQueueEntry>, entryId: String): List<GtQueueEntry>? {
        if (entries.none { it.id == entryId }) return null
        return entries.filter { it.id != entryId }
    }

    /** 2.7e cancelAll: keep COMPLETE for history, remove the rest. */
    fun cancelAll(entries: List<GtQueueEntry>): List<GtQueueEntry> =
        entries.filter { it.status == GtQueueStatus.COMPLETE }

    /** 2.7e clearCompleted: remove COMPLETE, CANCELLED and FAILED. */
    fun clearCompleted(entries: List<GtQueueEntry>): List<GtQueueEntry> =
        entries.filter { it.status !in TERMINAL }
}
