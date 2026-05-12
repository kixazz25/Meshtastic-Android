package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val active = queue.filter { it.status == QueueStatus.DOWNLOADING }
    val queued = queue.filter { it.status == QueueStatus.QUEUED }
    val completed = queue.filter { it.status in setOf(QueueStatus.COMPLETE, QueueStatus.FAILED, QueueStatus.CANCELLED) }

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
                    // Active download slots (max 2)
                    active.forEachIndexed { idx, entry ->
                        if (idx > 0) Spacer(Modifier.height(6.dp))
                        ActiveSlotBar(entry)
                    }

                    // Queued items
                    if (queued.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("QUEUED", color = dimText, fontSize = 9.sp,
                            fontFamily = mono, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        queued.forEachIndexed { idx, entry ->
                            QueuedItemRow(entry, position = idx + 1)
                        }
                    }

                    // Completed/failed items
                    if (completed.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("COMPLETED", color = dimText, fontSize = 9.sp,
                            fontFamily = mono, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        completed.forEach { entry ->
                            CompletedItemRow(entry)
                        }
                    }

                    // Action buttons
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (active.isNotEmpty() || queued.isNotEmpty()) {
                            ActionButton("CANCEL ALL", dangerRed) {
                                (active + queued).forEach {
                                    DownloadQueueManager.cancel(it.id)
                                }
                            }
                        }
                        if (completed.isNotEmpty()) {
                            ActionButton("CLEAR DONE", dimText) {
                                DownloadQueueManager.clearCompleted()
                            }
                        }
                    }
                }
            }
        }
    }
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
            val parts = mutableListOf<String>()
            if (active.isNotEmpty()) parts.add("${active.size} active")
            if (queued.isNotEmpty()) parts.add("${queued.size} queued")
            Text(
                parts.joinToString(", "),
                color = accentGreen, fontSize = 11.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold
            )
            // Aggregate ETA
            val totalRemaining = active.sumOf { it.totalTiles - it.downloadedTiles }
            if (totalRemaining > 0 && active.any { it.downloadedTiles > 0 }) {
                Text(
                    "$totalRemaining tiles remaining",
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
        Text(
            "#$position  ${entry.label.ifEmpty { "Download" }}",
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
