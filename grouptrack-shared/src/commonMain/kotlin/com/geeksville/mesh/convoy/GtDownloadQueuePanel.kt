package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.grouptrack.Gt
import com.geeksville.mesh.grouptrack.GtPanel               // QUEUEMONITOR-2026-11-25
import com.geeksville.mesh.grouptrack.LocalGtPanelChrome    // QUEUEMONITOR-2026-11-25
import com.grouptrack.shared.queue.GtDownloadType
import com.grouptrack.shared.queue.GtQueueEntry
import com.grouptrack.shared.queue.GtQueueStatus
import com.grouptrack.shared.text.GtFmt

/*
 * QUEUEPANEL-2026-11-25 -- the map download queue panel, SHARED (release 2.8b, map row 1 map.queues).
 *
 * Relocated from app/.../convoy/ConvoyDownloadQueuePanel.kt (2.7e) with the body as it was. What changed is
 * only what a shared screen cannot do itself, and each is now a parameter the HOST supplies -- the
 * GtAlertSettingsPanel pattern: "this panel raises callbacks and does not own dialogs".
 *   DownloadQueueManager.queue / isQueuePaused / MAX_CONCURRENT  ->  entries / paused / maxConcurrent
 *   DownloadQueueManager.hold / resume / cancel / cancelAll / promote / removeEntry / set+loadMaxConcurrent
 *                                                                ->  the on* callbacks
 *   System.currentTimeMillis()                                   ->  nowMs   (RideStats: shared code has no clock)
 *   SimpleDateFormat("MMM d h:mm a", Locale.US)                  ->  fmtDate (and no time zone)
 *   "%,d".format / String.format("%.1f")                         ->  GtFmt.d / GtFmt.f (GTFMT-2026-11-07)
 *   QueueEntry / QueueStatus / DownloadType                      ->  GtQueueEntry / GtQueueStatus / GtDownloadType
 * The decisions behind the callbacks are GtQueueRules (QUEUERULES-2026-11-25), on both platforms.
 * CODE RULE 1: no defaults -- each host wires every parameter.
 */

private val panelBg: Color get() = Gt.panel  // STD1-2026-10-24
private val accentGreen: Color get() = Gt.accent  // STD1-2026-10-24
private val accentBlue: Color get() = Gt.accent  // STD1-2026-10-24
private val dimText: Color get() = Gt.dim  // STD1-2026-10-24
private val brightText: Color get() = Gt.accent  // STD1-2026-10-24
private val dangerRed: Color get() = Gt.bad  // STD1-2026-10-24
private val mono = FontFamily.Monospace

@Composable
fun GtDownloadQueuePanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
    entries: List<GtQueueEntry>,
    paused: Boolean,
    maxConcurrent: Int,
    onLoadMaxConcurrent: () -> Unit,
    onSetMaxConcurrent: (Int) -> Unit,
    onHold: () -> Unit,
    onResume: () -> Unit,
    onCancel: (String) -> Unit,
    onCancelAll: () -> Unit,
    onPromote: (String) -> Unit,
    onRemove: (String) -> Unit,
    nowMs: () -> Long,
    fmtDate: (Long) -> String,
) {
    val queue = entries
    // QUEUE-PANEL-B-2026-07-24: the filter scopes the DISPLAY *and* the destructive
    // actions. null = ALL. Scoping both is deliberate: a CANCEL that acts on
    // rows you cannot see is how the wrong thing gets wiped.
    var typeFilter by remember { mutableStateOf<GtDownloadType?>(null) }
    var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    // QEVAL-2026-08-08I: restore the persisted concurrency once per panel entry.
    LaunchedEffect(Unit) { onLoadMaxConcurrent() }
    var qeSlots by remember { mutableIntStateOf(maxConcurrent) }
    // QTRUTH-2026-08-08L: section fold state. Completed starts CLOSED.
    var qtQueuedOpen by remember { mutableStateOf(true) }
    var qtDoneOpen by remember { mutableStateOf(false) }
    val shown = queue.filter { typeFilter == null || it.downloadType == typeFilter }
    val active = shown.filter { it.status == GtQueueStatus.DOWNLOADING }
    val queued = shown.filter { it.status == GtQueueStatus.QUEUED }
    val completed = shown.filter { it.status in setOf(GtQueueStatus.COMPLETE, GtQueueStatus.FAILED, GtQueueStatus.CANCELLED) }

    // ORPHAN-2026-08-08O: the escape hatch must outlive the queue.
    val qoHeld = paused
    if (queue.isEmpty() && !qoHeld) return
    if (active.isEmpty() && queued.isEmpty() && !expanded && !qoHeld) return

    Surface(
        modifier = modifier.padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = panelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            confirmAction?.let { (msg, act) ->
                ConfirmDialog(msg, onConfirm = act, onDismiss = { confirmAction = null })
            }
            // ORPHAN-2026-08-08O: held-queue banner + escape controls
            if (qoHeld) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "QUEUE HELD - nothing will start",
                        color = dangerRed, fontSize = 12.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    ActionButton("RELEASE", accentGreen) { onResume() }
                }
            }
            // ORPHAN-2026-08-08O: cancel is reachable even with no entries.
            if (qoHeld || queue.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    ActionButton("CANCEL WORKERS", dangerRed) {
                        confirmAction = Pair(
                            "Stop every download job, including any still running "
                            + "with no queue entry, and release the queue?"
                        ) {
                            onCancelAll()
                            onResume()
                            confirmAction = null
                        }
                    }
                }
            }
            QueueSummaryBar(active = active, queued = queued, onClick = onToggle, nowMs = nowMs, fmtDate = fmtDate)
            // QEVAL-2026-08-08I: AGGREGATE throughput + concurrency control
            if (expanded) {
                // QTRUTH-2026-08-08L / ETA-ONERATE-2026-08-09H: every running job in the UNFILTERED queue, one rate.
                val qeRunning = queue.filter { it.status == GtQueueStatus.DOWNLOADING }
                val qeLive = liveRate(queue, nowMs())
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        GtFmt.f(qeLive, 1) + " tiles/sec across " + qeRunning.size + " running",
                        color = accentBlue, fontSize = 12.sp, fontFamily = mono
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("slots", color = dimText, fontSize = 12.sp, fontFamily = mono)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "-",
                            color = if (qeSlots > 1) accentGreen else dimText,
                            fontSize = 13.sp, fontFamily = mono,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(enabled = qeSlots > 1) {
                                    qeSlots -= 1
                                    onSetMaxConcurrent(qeSlots)
                                }
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            "$qeSlots",
                            color = brightText, fontSize = 12.sp,
                            fontFamily = mono, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "+",
                            color = if (qeSlots < 4) accentGreen else dimText,
                            fontSize = 13.sp, fontFamily = mono,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(enabled = qeSlots < 4) {
                                    qeSlots += 1
                                    onSetMaxConcurrent(qeSlots)
                                }
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // QUEUE-PANEL-B-2026-07-24: filter row above the actions.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip("ALL", typeFilter == null) { typeFilter = null }
                        FilterChip("AREA", typeFilter == GtDownloadType.AREA) { typeFilter = GtDownloadType.AREA }
                        FilterChip("CORR", typeFilter == GtDownloadType.CORRIDOR) { typeFilter = GtDownloadType.CORRIDOR }
                        FilterChip("RFSH", typeFilter == GtDownloadType.MAP_SOURCE_REFRESH) { typeFilter = GtDownloadType.MAP_SOURCE_REFRESH }
                        FilterChip("DEL", typeFilter == GtDownloadType.DELETE_AREA_TILES) { typeFilter = GtDownloadType.DELETE_AREA_TILES }
                    }
                    val isPaused = paused
                    val scopeName = typeFilter?.let { scopeLabel(it) } ?: "ALL"
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (active.isNotEmpty() || queued.isNotEmpty()) {
                            ActionButton(
                                if (isPaused) "RESUME" else "HOLD",
                                if (isPaused) accentGreen else accentBlue
                            ) {
                                if (isPaused) onResume() else onHold()
                            }
                            // QUEUE-PANEL-B-2026-07-24: label states SCOPE + COUNT, and the action is GATED.
                            val cancelTargets = active + queued
                            ActionButton(
                                "CANCEL $scopeName (${cancelTargets.size})",
                                dangerRed
                            ) {
                                confirmAction = Pair(
                                    "Cancel ${cancelTargets.size} $scopeName download(s)?",
                                    { cancelTargets.forEach { e -> onCancel(e.id) } }
                                )
                            }
                        }
                        if (completed.isNotEmpty()) {
                            ActionButton(
                                "CLEAR $scopeName (${completed.size})",
                                dimText
                            ) {
                                confirmAction = Pair(
                                    "Clear ${completed.size} finished $scopeName entr(ies)?",
                                    { completed.forEach { e -> onRemove(e.id) } }
                                )
                            }
                        }
                    }
                    // LAZYCOLUMN-2026-07-24 / SCROLL-HEIGHT-2026-07-24: a bounded viewport (fixed height) so the
                    // list scrolls; LazyColumn composes rows on demand and owns its own gestures.
                    LazyColumn(modifier = Modifier.height(420.dp)) {
                        itemsIndexed(active, key = { _, e -> "a" + e.id }) { idx, entry ->
                            if (idx > 0) Spacer(Modifier.height(6.dp))
                            ActiveSlotBar(entry, onCancel)
                        }
                        if (queued.isNotEmpty()) {
                            item(key = "hdr_queued") {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    (if (qtQueuedOpen) "\u25BC " else "\u25B6 ") +
                                        "QUEUED (${queued.size})",
                                    color = dimText, fontSize = 12.sp,
                                    fontFamily = mono, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { qtQueuedOpen = !qtQueuedOpen }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            if (qtQueuedOpen) {
                                itemsIndexed(queued, key = { _, e -> "q" + e.id }) { idx, entry ->
                                    QueuedItemRow(entry, position = idx + 1, onPromote = onPromote, onCancel = onCancel, fmtDate = fmtDate)
                                }
                            }
                        }
                        if (completed.isNotEmpty()) {
                            item(key = "hdr_done") {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    (if (qtDoneOpen) "\u25BC " else "\u25B6 ") +
                                        "COMPLETED (${completed.size})",
                                    color = dimText, fontSize = 12.sp,
                                    fontFamily = mono, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { qtDoneOpen = !qtDoneOpen }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            if (qtDoneOpen) {
                                items(completed, key = { e -> "c" + e.id }) { entry ->
                                    CompletedItemRow(entry, fmtDate)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * QUEUEPANEL-2026-11-25: GTQSUM-2026-10-23's downloads summary, SHARED -- relocated from
 * app/.../grouptrack/MapScreens.kt GtQueueSummary. The existing summary while maps download, else
 * "No maps downloading"; tapping either opens the downloads panel. [panel] is the collapsed queue
 * panel, drawn by the host so its callbacks are wired once, there.
 */
@Composable
fun GtDownloadQueueSummary(
    entries: List<GtQueueEntry>,
    onOpen: () -> Unit,
    modifier: Modifier,
    panel: @Composable () -> Unit,
) {
    val busy = entries.any { it.status == GtQueueStatus.DOWNLOADING || it.status == GtQueueStatus.QUEUED }
    if (busy) panel()
    else Row(
        modifier = modifier.background(Gt.panel, RoundedCornerShape(16.dp))
            .clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("No maps downloading", color = Gt.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Text("DETAILS \u203a", color = Gt.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * QUEUEMONITOR-2026-11-25: MAP DOWNLOADS IN PROGRESS -- the map.queues frame, SHARED, on the chrome standard
 * GtPanel (Fred 12:05, option 1): title, HELP (map.queues), ISSUE and close, and MOVE / HIDE because the
 * queue STAYS on screen (GtPanel's own doc: "the panel stays on screen after its job (Queues, ...)").
 * Was Android's inline frame (GroupTrackScreen.kt, MAPTARGET4-2026-10-23 "a monitor, not a queue").
 * [panel] is the expanded queue panel, drawn by the host so its callbacks are wired once there;
 * [empty] shows the "No maps downloading" line under it, as Android's frame did.
 * QMONSAME-2026-11-25 (Fred 14:04, "they should be the same" / "iPad works better"): the monitor OWNS its placement, the
 * iPad's layout -- the whole screen less a 16 dp margin, centred, the frame 90% of that width -- so both hosts draw
 * it identically. [modifier] is for stacking only (the Droid's zIndex); it must not place or size the frame.
 */
@Composable
fun GtDownloadQueueMonitor(
    empty: Boolean,
    onClose: () -> Unit,
    modifier: Modifier,
    panel: @Composable () -> Unit,
) {
    val chrome = LocalGtPanelChrome.current
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp).wrapContentSize(Alignment.Center)) {   // QMONSAME-2026-11-25
        GtPanel(
            title = "MAP DOWNLOADS IN PROGRESS",
            helpId = "map.queues",
            onHelp = { id -> chrome.openHelp(id) },
            onDone = onClose,
            stays = true,
            width = maxWidth * 0.90f,
            maxHeight = maxHeight,
        ) {
            panel()
            if (empty) {
                Text("No maps downloading",   // MAPTARGET4-2026-10-23
                    color = Gt.text, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/** IOSQLAUNCH-2026-11-25: map.queues' LAUNCH, the shared standard -- RidesLauncher's shape exactly
 *  (RidesScreen.kt). A host's actions entry calls open(); the host draws GtDownloadQueueMonitor while showing. */
object QueuesLauncher {
    var showing by mutableStateOf(false)
    fun open() { showing = true }
    fun close() { showing = false }
}

// PHASE0-QUEUE-PANEL-2026-07-24: thousands separator for tile counts ("%,d").
private fun fmtTiles(n: Int): String = GtFmt.d(n)

/** DURATION - days/hours/minutes, never bare minutes: "42 min" | "3h 20m" | "2d 7h". */
private fun fmtDuration(totalSec: Long): String {
    val m = totalSec / 60
    return when {
        m < 60 -> "$m min"
        m < 1440 -> "${m / 60}h ${m % 60}m"
        else -> "${m / 1440}d ${(m % 1440) / 60}h"
    }
}

/** ETA-ONERATE-2026-08-09H: THE rate -- the running jobs summed. One definition, so the number on screen
 *  and the completion estimate cannot disagree. */
private fun liveRate(entries: List<GtQueueEntry>, now: Long): Double =
    entries.filter { it.status == GtQueueStatus.DOWNLOADING }.sumOf { e ->
        val began = if (e.startedAt > 0L) e.startedAt else e.createdAt
        val secs = if (began > 0L) (now - began) / 1000.0 else 0.0
        if (secs > 0.0) e.downloadedTiles / secs else 0.0
    }

/** ETA-ONERATE-2026-08-09H: tiles remaining divided by the rate ALREADY ON SCREEN. Over ~8 h rounds to the hour. */
private fun estimateRemaining(tilesRemaining: Int, rate: Double, now: Long, fmtDate: (Long) -> String): Pair<String, String>? {
    if (tilesRemaining <= 0 || rate <= 0.0) return null
    var sec = (tilesRemaining / rate).toLong()
    if (sec > 8 * 3600) sec = ((sec + 1800) / 3600) * 3600
    return Pair(fmtDuration(sec), fmtDate(now + sec * 1000))
}

@Composable
private fun QueueSummaryBar(
    active: List<GtQueueEntry>,
    queued: List<GtQueueEntry>,
    onClick: () -> Unit,
    nowMs: () -> Long,
    fmtDate: (Long) -> String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (active.isNotEmpty()) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = accentGreen,
                strokeWidth = 2.dp
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            // PHASE0-QUEUE-PANEL-2026-07-24: three-line indicator.
            val activeTiles = active.sumOf { it.totalTiles - it.downloadedTiles }
            val queuedTiles = queued.sumOf { it.totalTiles }
            Text(
                if (active.isEmpty()) "idle"
                else "${active.size} active  ${fmtTiles(activeTiles)} tiles",
                color = accentGreen, fontSize = 12.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold
            )
            if (queued.isNotEmpty()) {
                Text(
                    "${queued.size} in queue  ${fmtTiles(queuedTiles)} tiles",
                    color = dimText, fontSize = 12.sp, fontFamily = mono
                )
            }
            // ETA-ONERATE-2026-08-09H: same rate the panel shows.
            val now = nowMs()
            val eta = estimateRemaining(activeTiles + queuedTiles, liveRate(active, now), now, fmtDate)
            if (eta != null) {
                Text(
                    "~${eta.first}  \u00b7  done ${eta.second}",
                    color = dimText, fontSize = 12.sp, fontFamily = mono
                )
            }
        }
        Text("TAP", color = dimText, fontSize = 12.sp, fontFamily = mono)
    }
}

/** QUEUE-PANEL-B-2026-07-24: gated confirm -- destructive actions are never a single tap. */
@Composable
private fun ConfirmDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = panelBg,
        title = { Text("Confirm", color = brightText, fontSize = 12.sp, fontFamily = mono) },
        text = { Text(message, color = brightText, fontSize = 12.sp, fontFamily = mono) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text("PROCEED", color = dangerRed, fontSize = 12.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = dimText, fontSize = 12.sp, fontFamily = mono)
            }
        }
    )
}

/** QUEUE-PANEL-B-2026-07-24: filter chip. */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Gt.text.copy(alpha = 0.18f),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            (if (selected) "\u2713 " else "") + label,
            color = if (selected) Gt.text else dimText,
            fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

/** QUEUE-PANEL-B-2026-07-24: short scope name for button labels. */
private fun scopeLabel(t: GtDownloadType): String = when (t) {
    GtDownloadType.AREA -> "AREA"
    GtDownloadType.CORRIDOR -> "CORR"
    GtDownloadType.MAP_SOURCE_REFRESH -> "RFSH"
    GtDownloadType.DELETE_AREA_TILES -> "DEL"
}

@Composable
private fun ActiveSlotBar(entry: GtQueueEntry, onCancel: (String) -> Unit) {
    val pct = if (entry.totalTiles > 0) entry.downloadedTiles.toFloat() / entry.totalTiles else 0f
    val pctInt = (pct * 100).toInt()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                entry.label.ifEmpty { "Download" },
                color = brightText, fontSize = 12.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,   // VECQUEUELINE-2026-10-31
                modifier = Modifier.weight(1f)
            )
            Text(
                "${entry.downloadedTiles}/${entry.totalTiles} ($pctInt%)",
                color = accentBlue, fontSize = 12.sp, fontFamily = mono
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "X",
                color = dangerRed, fontSize = 12.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onCancel(entry.id) }
            )
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = accentBlue,
            trackColor = Gt.panel
        )
    }
}

@Composable
private fun QueuedItemRow(
    entry: GtQueueEntry,
    position: Int,
    onPromote: (String) -> Unit,
    onCancel: (String) -> Unit,
    fmtDate: (Long) -> String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // QUEUE-PANEL-B-2026-07-24 / QTRUTH-2026-08-08L: kind and turn order; the type token matches the chip.
        Text(
            "#$position  ${scopeLabel(entry.downloadType)} ${entry.priority}  ${entry.label.ifEmpty { "Download" }}",
            color = brightText, fontSize = 12.sp, fontFamily = mono,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,   // VECQUEUELINE-2026-10-31
            modifier = Modifier.weight(1f)   // VECQUEUELINE2-2026-10-31
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
            Text(
                "${entry.totalTiles} tiles",
                color = dimText, fontSize = 12.sp, fontFamily = mono, maxLines = 1, softWrap = false   // VECQUEUELINE2
            )
            Text(
                fmtDate(entry.createdAt),
                color = dimText, fontSize = 12.sp, fontFamily = mono, maxLines = 1, softWrap = false   // VECQUEUELINE2
            )
            // QUEUE-PANEL-B-2026-07-24: PROMOTE to priority 1 -- deliberately NOT "X", which means CANCEL.
            if (entry.priority != 1) {
                Text(
                    "\u25B2",
                    color = accentGreen, fontSize = 12.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onPromote(entry.id) }
                )
            }
            Text(
                "X",
                color = dangerRed, fontSize = 12.sp, fontFamily = mono,
                modifier = Modifier.clickable { onCancel(entry.id) }
            )
        }
    }
}

/** QEVAL-2026-08-08I: elapsed span. Seconds under a minute, so a fast job does not read as "0m". */
private fun qdElapsed(ms: Long): String {
    if (ms <= 0L) return "-"
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

@Composable
private fun CompletedItemRow(entry: GtQueueEntry, fmtDate: (Long) -> String) {
    // QEVAL-2026-08-08I: per-row state keyed on id.
    var qdOpen by remember(entry.id) { mutableStateOf(false) }
    val statusText = when (entry.status) {
        GtQueueStatus.COMPLETE -> "Done"
        GtQueueStatus.FAILED -> "Failed"
        GtQueueStatus.CANCELLED -> "Cancelled"
        else -> ""
    }
    val statusColor = when (entry.status) {
        GtQueueStatus.COMPLETE -> accentGreen
        GtQueueStatus.FAILED -> dangerRed
        GtQueueStatus.CANCELLED -> dimText
        else -> dimText
    }
    Column(modifier = Modifier
        .fillMaxWidth()
        .clickable { qdOpen = !qdOpen }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "$statusText  ${entry.label.ifEmpty { "Download" }}",   // VECQUEUELINE-2026-10-31
                color = statusColor, fontSize = 12.sp, fontFamily = mono,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                "${entry.downloadedTiles} tiles",
                color = dimText, fontSize = 12.sp, fontFamily = mono
            )
        }
        // QEVAL-2026-08-08I: the detail -- all already recorded on the entry.
        if (qdOpen) {
            val began = if (entry.startedAt > 0L) entry.startedAt else entry.createdAt
            val dur = if (entry.completedAt > 0L) entry.completedAt - began else 0L
            val stored = entry.tilesPerSec
            val rate = if (stored > 0.0) stored
                       else if (dur > 0L) entry.downloadedTiles * 1000.0 / dur else 0.0
            val approx = stored <= 0.0 && entry.startedAt <= 0L
            Column(modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) {
                Text(
                    "started  ${if (began > 0L) fmtDate(began) else "-"}" +
                        "    took  ${qdElapsed(dur)}",
                    color = dimText, fontSize = 12.sp, fontFamily = mono
                )
                Text(
                    (if (approx) "~" else "") + GtFmt.f(rate, 1) + " tiles/sec",
                    color = accentBlue, fontSize = 12.sp, fontFamily = mono
                )
                Text(
                    "downloaded ${entry.downloadedTiles}  of ${entry.totalTiles} submitted" +
                        (if (entry.failedTiles > 0) "   ${entry.failedTiles} failed" else ""),
                    color = dimText, fontSize = 12.sp, fontFamily = mono
                )
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = Gt.panel
    ) {
        Text(
            text, color = color, fontSize = 12.sp,
            fontFamily = mono, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}
