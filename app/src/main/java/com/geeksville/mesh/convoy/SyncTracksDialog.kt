package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared SYNC TRACKS dialog -- the visible run + failure-first recap panel.
 * Extracted from ConvoyTrackImportScreen so both the import screen AND the
 * launch-time auto-resync host can show the identical UI (write-once-reuse).
 *
 * State is hoisted: the caller owns syncLines / syncResult / syncRunning and
 * supplies onStart (kick the sync) and onClose (dismiss). The scroll state is
 * internal (pure UI). Behavior mirrors the original inline panel exactly.
 */
@Composable
fun SyncTracksDialog(
    syncLines: List<String>,
    syncResult: SpatialDbManager.TrackSyncResult?,
    syncRunning: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    val syncListState = rememberLazyListState()
    AlertDialog(
        onDismissRequest = { if (!syncRunning) onClose() },
        containerColor = Color(0xFF0A1628),
        title = {
            Text("SYNC TRACKS", color = Color(0xFF4DA6FF),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val res = syncResult
                if (res != null && !syncRunning) {
                    // ---- RECAP: failures first ----
                    if (res.failures.isEmpty()) {
                        Text("\u2713 no failures", color = Color(0xFF39FF14),
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Text("\u26A0 ${res.failures.size} FAILED \u2014 research these:",
                            color = Color(0xFFFFB020),
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                            items(res.failures) { f ->
                                Text(f, color = Color(0xFFFFB020),
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                    modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("processed ${res.processed} \u00B7 renamed ${res.renamed}",
                        color = Color(0xFF97D5A5), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("properties ${res.propsWritten}/${res.propsTotal} written",
                        color = Color(0xFF97D5A5), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("\u2014 full detail below \u2014", color = Color(0xFF4A6080),
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                // Auto-scroll the live feed to the newest line.
                LaunchedEffect(syncLines.size) {
                    if (syncLines.isNotEmpty()) {
                        syncListState.animateScrollToItem(syncLines.size - 1)
                    }
                }
                // ---- LIVE FEED (always shown; this is the running detail) ----
                LazyColumn(
                    state = syncListState,
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                ) {
                    items(syncLines) { line ->
                        val col = when {
                            line.startsWith("FAIL:") || line.startsWith("\u26A0") -> Color(0xFFFFB020)
                            line.startsWith("props:") -> Color(0xFF6FB6FF)
                            line.startsWith("ADDED") || line.startsWith("===") -> Color(0xFF39FF14)
                            else -> Color(0xFF7A8DA0)
                        }
                        Text(line, color = col, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
                if (syncRunning) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), color = Color(0xFF4DA6FF))
                        Spacer(Modifier.fillMaxWidth(0.05f).height(1.dp))
                        Text("  running\u2026", color = Color(0xFF4DA6FF),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !syncRunning,
                onClick = onStart
            ) {
                Text(if (syncResult == null) "START" else "RE-RUN",
                    color = if (syncRunning) Color(0xFF4A6080) else Color(0xFF39FF14),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !syncRunning,
                onClick = onClose
            ) {
                Text("CLOSE",
                    color = if (syncRunning) Color(0xFF4A6080) else Color(0xFF7A8DA0),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    )
}
