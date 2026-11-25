package com.geeksville.mesh.convoy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * DROIDQPANEL-2026-11-25 -- Android's HOST for the shared download queue panel (release 2.8b, map.queues).
 *
 * The panel's body moved to commonMain as GtDownloadQueuePanel (QUEUEPANEL-2026-11-25), unchanged. This is
 * what Android hands it: DownloadQueueManager's queue and operations, Android's clock, and the date format
 * the panel always used. Same name and signature as before, so GroupTrackScreen and GtQueueSummary are
 * untouched. The iPad host hands the same panel its own queue (dlQueue) -- one screen, two hosts.
 */
private val dateFmt = SimpleDateFormat("MMM d h:mm a", Locale.US)

@Composable
fun DownloadQueuePanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val q by DownloadQueueManager.queue.collectAsState()
    val entries = remember(q) { with(DownloadQueueManager) { q.toGt() } }
    val ctx = LocalContext.current
    GtDownloadQueuePanel(
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
        entries = entries,
        paused = DownloadQueueManager.isQueuePaused(),
        maxConcurrent = DownloadQueueManager.MAX_CONCURRENT,
        onLoadMaxConcurrent = { DownloadQueueManager.loadMaxConcurrent(ctx) },
        onSetMaxConcurrent = { v -> DownloadQueueManager.setMaxConcurrent(ctx, v) },
        onHold = { DownloadQueueManager.holdQueue() },
        onResume = { DownloadQueueManager.resumeQueue() },
        onCancel = { id -> DownloadQueueManager.cancel(id) },
        onCancelAll = { DownloadQueueManager.cancelAll() },
        onPromote = { id -> DownloadQueueManager.promote(id) },
        onRemove = { id -> DownloadQueueManager.removeEntry(id) },
        nowMs = { System.currentTimeMillis() },
        fmtDate = { ms -> dateFmt.format(Date(ms)) },
    )
}
