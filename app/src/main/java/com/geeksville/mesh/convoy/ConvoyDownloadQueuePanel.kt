package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----------------------------------------------------------------
// Download Queue Panel -- summary pill + expandable detail view
// Renders at bottom of Map Viewer over the map.
// Summary: "2 active, 3 queued -- ~15 min"  (tap to expand)
// Detail: two slot progress bars + item list + actions
// ----------------------------------------------------------------

private val panelBg = Color(0xE6131820)
private val accentGreen = Color(0xFF1CF0A0)
private val accentBlue = Color(0xFF4DA6FF)
private val dimText = Color(0xFF4A6080)
private val brightText = Color(0xFFCCDDEE)
private val dangerRed = Color(0xFFFF5555)
private val mono = FontFamily.Monospace
private val dateFmt = SimpleDateFormat("MMM d h:mm a", Locale.US)

@Composable
fun DownloadQueuePanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queue by DownloadQueueManager.queue.collectAsState()
    // QUEUE-PANEL-B-2026-07-24: the filter scopes the DISPLAY *and* the destructive
    // actions. null = ALL. Scoping both is deliberate: a CANCEL that acts on
    // rows you cannot see is how the wrong thing gets wiped.
    var typeFilter by remember { mutableStateOf<DownloadType?>(null) }
    var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val shown = queue.filter { typeFilter == null || it.downloadType == typeFilter }
    val active = shown.filter { it.status == QueueStatus.DOWNLOADING }
    val queued = shown.filter { it.status == QueueStatus.QUEUED }
    val completed = shown.filter { it.status in setOf(QueueStatus.COMPLETE, QueueStatus.FAILED, QueueStatus.CANCELLED) }

    // Nothing to show
    if (queue.isEmpty()) return
    // Only completed items and not expanded -- nothing to show
    if (active.isEmpty() && queued.isEmpty() && !expanded) return

    Surface(
        modifier = modifier.padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = panelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // QUEUE-PANEL-B-2026-07-24: confirm dialog for scoped destructive actions
            confirmAction?.let { (msg, act) ->
                ConfirmDialog(msg, onConfirm = act, onDismiss = { confirmAction = null })
            }
            // -- Summary bar (always visible, tappable) --
            QueueSummaryBar(
                active = active,
                queued = queued,
                onClick = onToggle
            )

            // -- Expanded detail --
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // ── Queue management header (always visible) ──
                    // QUEUE-PANEL-B-2026-07-24: filter row above the actions.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip("ALL", typeFilter == null) { typeFilter = null }
                        FilterChip("AREA", typeFilter == DownloadType.AREA) {
                            typeFilter = DownloadType.AREA
                        }
                        FilterChip("CORR", typeFilter == DownloadType.CORRIDOR) {
                            typeFilter = DownloadType.CORRIDOR
                        }
                        FilterChip("RFSH", typeFilter == DownloadType.MAP_SOURCE_REFRESH) {
                            typeFilter = DownloadType.MAP_SOURCE_REFRESH
                        }
                        FilterChip("DEL", typeFilter == DownloadType.DELETE_AREA_TILES) {
                            typeFilter = DownloadType.DELETE_AREA_TILES
                        }
                    }
                    val isPaused = DownloadQueueManager.isQueuePaused()
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
                                if (isPaused) DownloadQueueManager.resumeQueue()
                                else DownloadQueueManager.holdQueue()
                            }
                            // QUEUE-PANEL-B-2026-07-24: label states SCOPE + COUNT, and
                            // the action is GATED. Never single-tap destructive.
                            val cancelTargets = active + queued
                            ActionButton(
                                "CANCEL $scopeName (${cancelTargets.size})",
                                dangerRed
                            ) {
                                confirmAction = Pair(
                                    "Cancel ${cancelTargets.size} $scopeName download(s)?",
                                    { cancelTargets.forEach { e -> DownloadQueueManager.cancel(e.id) } }
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
                                    { completed.forEach { e -> DownloadQueueManager.removeEntry(e.id) } }
                                )
                            }
                        }
                    }
                    // ── Scrollable item list ──
                    // PHASE0-QUEUE-PANEL-2026-07-24: the map WebView's PAN handler was
                    // claiming vertical drags before verticalScroll could see
                    // them - taps landed, drags did nothing. Consume the drag
                    // here so it never propagates down to the WebView.
                    // LAZYCOLUMN-2026-07-24: the diagnostic readout is retired - it
                    // did its job. It first showed max=0 (unbounded viewport),
                    // then 100%-at-8-of-42 (bounded box, but rows past the box
                    // never laid out). A LazyColumn needs neither the readout
                    // nor a ScrollState here.
                    // SLIDER-REMOVED-2026-07-24: Slider REMOVED. Compose's Slider is a
                    // HORIZONTAL control - wrong shape for a vertical scroll, and
                    // it was solving the wrong problem anyway (scroll max=0 showed
                    // the viewport was never bounded; Patch C's height(420.dp) is
                    // what actually gave the list range). Drag stands on its own.
                    // LAZYCOLUMN-2026-07-24: Column+verticalScroll could not do this.
                    // With a fixed height it laid out only the rows that FIT
                    // (8 of 42) and reported 100% scrolled at row 8 - there was
                    // literally nothing below to scroll to. LazyColumn composes
                    // rows ON DEMAND inside a bounded viewport and owns its own
                    // gesture handling, so the pointerInput/dispatchRawDelta
                    // workaround is retired with it.
                    LazyColumn(modifier = Modifier
                        // SCROLL-HEIGHT-2026-07-24: was heightIn(max = 300.dp). That sets a
                        // MAXIMUM, not a height - and this Column sits inside an
                        // AnimatedVisibility inside a Column inside a Surface,
                        // which passes UNBOUNDED height down. Given unbounded
                        // height the content laid out at full natural size, the
                        // viewport equalled the content, and maxValue came out
                        // 0 - so nothing could scroll it and the slider (gated
                        // on maxValue > 0) never rendered. A FIXED height forces
                        // a bounded viewport. 420dp is ~20 rows; 300 was ~7,
                        // unreadable for a 215-entry queue. The popup is
                        // collapsed-only now and never shows this list, so the
                        // only consumers are the two 90%-width managers.
                        .height(420.dp)
                    ) {
                    // Active download slots (max 2)
                    // LAZYCOLUMN-2026-07-24: keys keep row identity stable across
                        // recomposition, so promoting or cancelling a row does not
                        // reshuffle what is on screen.
                        itemsIndexed(active, key = { _, e -> "a" + e.id }) { idx, entry ->
                            if (idx > 0) Spacer(Modifier.height(6.dp))
                            ActiveSlotBar(entry)
                        }

                    // Queued items
                    if (queued.isNotEmpty()) {
                        item(key = "hdr_queued") {
                            Spacer(Modifier.height(10.dp))
                            Text("QUEUED", color = dimText, fontSize = 9.sp,
                                fontFamily = mono, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                        }
                        itemsIndexed(queued, key = { _, e -> "q" + e.id }) { idx, entry ->
                            QueuedItemRow(entry, position = idx + 1)
                        }
                    }

                    // Completed/failed items
                    if (completed.isNotEmpty()) {
                        item(key = "hdr_done") {
                            Spacer(Modifier.height(10.dp))
                            Text("COMPLETED", color = dimText, fontSize = 9.sp,
                                fontFamily = mono, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                        }
                        items(completed, key = { e -> "c" + e.id }) { entry ->
                            CompletedItemRow(entry)
                        }
                    }

                    } // end scrollable list
                }
            }
        }
    }
}

// PHASE0-QUEUE-PANEL-2026-07-24: thousands separator for tile counts.
private fun fmtTiles(n: Int): String = "%,d".format(n)

/**
 * PHASE0-QUEUE-PANEL-2026-07-24 - ETA PLACEHOLDER. Returns null today (line 3 hidden).
 *
 * PHASE 1 implements this from THE LAST 2 COMPLETED DOWNLOADS (Fred's spec):
 * once QueueEntry carries `completedAt` alongside its existing `createdAt`,
 * each completed entry yields a real observed rate of
 * downloadedTiles / (completedAt - createdAt). Averaging the last 2 smooths
 * the estimate, survives app restarts (persisted in the entry, not held in
 * memory), and does NOT jitter with connectivity the way instantaneous
 * throughput sampling does - which matters on trails where the link drops.
 *
 * Until then this returns null so line 3 does not render. Do NOT substitute a
 * live-rate estimate here: an unstable number is worse than none.
 *
 * RETURNS Pair(duration, completionStamp) - BOTH go on line 3, because they
 * answer different questions. Duration ("how long do I wait?") is what you
 * want for a short job; a completion stamp ("when will it be done?") is what
 * you want for a long one, because "31h 15m" forces arithmetic while you are
 * standing in a parking lot. A corridor job at ~894,507 tiles can genuinely
 * run into DAYS, so bare minutes are useless.
 *
 * DURATION - resolve to days/hours/minutes, NEVER bare minutes:
 *     < 1 hr  "42 min"   |   < 24 hr  "3h 20m"   |   >= 24 hr  "2d 7h"
 * COMPLETION STAMP - reuse the existing `dateFmt` ("MMM d h:mm a"):
 *     "Jul 26 6:40 PM"
 *
 * ROUND LONG ESTIMATES. A two-sample rate cannot justify minute precision: if
 * it is off by 30%, a 30-hour job's stated finish is wrong by NINE HOURS. Keep
 * the "~" prefix and round anything over ~8 hours to the nearest hour so it
 * does not read as a promise.
 *
 * @param tilesRemaining active-remaining + queued total
 * @return Pair("3h 20m", "Jul 26 6:40 PM"), or null when no rate is known yet
 */
private fun fmtDuration(totalSec: Long): String {
    val m = totalSec / 60
    return when {
        m < 60 -> "$m min"
        m < 1440 -> "${m / 60}h ${m % 60}m"
        else -> "${m / 1440}d ${(m % 1440) / 60}h"
    }
}

private fun estimateRemaining(tilesRemaining: Int): Pair<String, String>? {
    // QUEUE-PANEL-B-2026-07-24: rate from THE LAST 2 COMPLETED entries. A completed
    // entry is a clean measurement over a known tile count, it is persisted so
    // it survives a restart, and averaging two smooths the connectivity swings
    // that make instantaneous sampling useless on a trail.
    if (tilesRemaining <= 0) return null
    val done = DownloadQueueManager.queue.value
        .filter { it.status == QueueStatus.COMPLETE && it.completedAt > 0L && it.downloadedTiles > 0 }
        .sortedByDescending { it.completedAt }
        .take(2)
    if (done.size < 2) return null
    // THROUGHPUT-2026-08-08C: prefer the rate the job STORED at completion.
    // Falls back to computing from startedAt, then to createdAt for entries
    // that predate the field. createdAt is QUEUE time -- a job that waited
    // behind others carries that wait in its elapsed, which is what made these
    // estimates unusable.
    val rates = done.mapNotNull {
        if (it.tilesPerSec > 0.0) it.tilesPerSec
        else {
            val began = if (it.startedAt > 0L) it.startedAt else it.createdAt
            val sec = (it.completedAt - began) / 1000.0
            if (sec > 0) it.downloadedTiles / sec else null
        }
    }
    if (rates.isEmpty()) return null
    val rate = rates.average()
    if (rate <= 0.0) return null
    var sec = (tilesRemaining / rate).toLong()
    // Round anything over ~8h to the nearest hour: a two-sample rate cannot
    // justify minute precision. Off by 30% on a 30-hour job is NINE HOURS out,
    // so do not let it read as a promise.
    if (sec > 8 * 3600) sec = ((sec + 1800) / 3600) * 3600
    return Pair(fmtDuration(sec), dateFmt.format(Date(System.currentTimeMillis() + sec * 1000)))
}

@Composable
private fun QueueSummaryBar(
    active: List<QueueEntry>,
    queued: List<QueueEntry>,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Spinning indicator
        if (active.isNotEmpty()) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = accentGreen,
                strokeWidth = 2.dp
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            // PHASE0-QUEUE-PANEL-2026-07-24: three-line indicator.
            //   line 1: "<n> active  <tiles> tiles"          (work in flight)
            //   line 2: "<n> in queue  <tiles> tiles"        (still to come)
            //   line 3: "~<duration>  -  done <date time>"   (how long / when)
            // The QUEUED tile total was previously NOT shown at all - the old
            // "tiles remaining" line summed ACTIVE ONLY.
            val activeTiles = active.sumOf { it.totalTiles - it.downloadedTiles }
            val queuedTiles = queued.sumOf { it.totalTiles }
            Text(
                if (active.isEmpty()) "idle"
                else "${active.size} active  ${fmtTiles(activeTiles)} tiles",
                color = accentGreen, fontSize = 11.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold
            )
            if (queued.isNotEmpty()) {
                Text(
                    "${queued.size} in queue  ${fmtTiles(queuedTiles)} tiles",
                    color = dimText, fontSize = 9.sp, fontFamily = mono
                )
            }
            // line 3 - total duration AND completion stamp; renders only once
            // a rate is known (Phase 1). Both, because they answer different
            // questions: how long to wait vs when it will actually be done.
            val eta = estimateRemaining(activeTiles + queuedTiles)
            if (eta != null) {
                Text(
                    "~${eta.first}  ·  done ${eta.second}",
                    color = dimText, fontSize = 9.sp, fontFamily = mono
                )
            }
        }
        // Expand/collapse indicator
        Text(
            "TAP",
            color = dimText, fontSize = 8.sp, fontFamily = mono
        )
    }
}

/** QUEUE-PANEL-B-2026-07-24: gated confirm. Destructive actions are never a
 *  single tap, and the message NAMES the scope and the count so nobody
 *  cancels a filtered subset believing they cleared the whole queue. */
@Composable
private fun ConfirmDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = panelBg,
        title = { Text("Confirm", color = brightText, fontSize = 12.sp, fontFamily = mono) },
        text = { Text(message, color = brightText, fontSize = 11.sp, fontFamily = mono) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text("PROCEED", color = dangerRed, fontSize = 11.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = dimText, fontSize = 11.sp, fontFamily = mono)
            }
        }
    )
}

/** QUEUE-PANEL-B-2026-07-24: filter chip. Selected reads bright on green. */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (selected) accentGreen else Color(0xFF1A2030),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0B0F14) else dimText,
            fontSize = 8.sp, fontFamily = mono, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

/** QUEUE-PANEL-B-2026-07-24: short scope name for button labels. */
private fun scopeLabel(t: DownloadType): String = when (t) {
    DownloadType.AREA -> "AREA"
    DownloadType.CORRIDOR -> "CORR"
    DownloadType.MAP_SOURCE_REFRESH -> "RFSH"
    DownloadType.DELETE_AREA_TILES -> "DEL"
}

@Composable
private fun ActiveSlotBar(entry: QueueEntry) {
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
                color = brightText, fontSize = 10.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${entry.downloadedTiles}/${entry.totalTiles} ($pctInt%)",
                color = accentBlue, fontSize = 9.sp, fontFamily = mono
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "X",
                color = dangerRed, fontSize = 10.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { DownloadQueueManager.cancel(entry.id) }
            )
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = accentBlue,
            trackColor = Color(0xFF1A2030)
        )
    }
}

@Composable
private fun QueuedItemRow(entry: QueueEntry, position: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // QUEUE-PANEL-B-2026-07-24: per-job detail - kind and turn order, so the
        // queue explains its own ordering rather than looking arbitrary.
        Text(
            "#$position  [${scopeLabel(entry.downloadType)}/p${entry.priority}]  ${entry.label.ifEmpty { "Download" }}",
            color = brightText, fontSize = 9.sp, fontFamily = mono
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${entry.totalTiles} tiles",
                color = dimText, fontSize = 9.sp, fontFamily = mono
            )
            Text(
                dateFmt.format(Date(entry.createdAt)),
                color = dimText, fontSize = 8.sp, fontFamily = mono
            )
            // QUEUE-PANEL-B-2026-07-24: PROMOTE to priority 1 (runs next). Deliberately
            // NOT "X" - X already means CANCEL on this row and on ActiveSlotBar,
            // and one glyph with two opposite meanings on adjacent rows is how a
            // job gets cancelled when the user meant to prioritise it.
            if (entry.priority != 1) {
                Text(
                    "\u25B2",
                    color = accentGreen, fontSize = 11.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { DownloadQueueManager.promote(entry.id) }
                )
            }
            Text(
                "X",
                color = dangerRed, fontSize = 9.sp, fontFamily = mono,
                modifier = Modifier.clickable { DownloadQueueManager.cancel(entry.id) }
            )
        }
    }
}

@Composable
private fun CompletedItemRow(entry: QueueEntry) {
    val statusText = when (entry.status) {
        QueueStatus.COMPLETE -> "Done"
        QueueStatus.FAILED -> "Failed"
        QueueStatus.CANCELLED -> "Cancelled"
        else -> ""
    }
    val statusColor = when (entry.status) {
        QueueStatus.COMPLETE -> accentGreen
        QueueStatus.FAILED -> dangerRed
        QueueStatus.CANCELLED -> dimText
        else -> dimText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "${entry.label.ifEmpty { "Download" }}  $statusText",
            color = statusColor, fontSize = 9.sp, fontFamily = mono
        )
        Text(
            "${entry.downloadedTiles} tiles",
            color = dimText, fontSize = 9.sp, fontFamily = mono
        )
    }
}

@Composable
private fun ActionButton(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF2A3545)
    ) {
        Text(
            text, color = color, fontSize = 9.sp,
            fontFamily = mono, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}
