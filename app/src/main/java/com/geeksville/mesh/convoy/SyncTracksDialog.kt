package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
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
        containerColor = Gt.panel,
        title = {   // GTSYNC-2026-10-23: the standard HELP and X (X off while running)
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("SYNC TRACKS", color = Gt.accent,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                com.geeksville.mesh.grouptrack.GtHelpButton("map.synctracks")
                com.geeksville.mesh.grouptrack.GtCloseButton(onClose, enabled = !syncRunning)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val res = syncResult
                if (res != null && !syncRunning) {
                    // ---- RECAP: failures first ----
                    if (res.failures.isEmpty()) {
                        Text("\u2713 no failures", color = Gt.accent,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Text("\u26A0 ${res.failures.size} FAILED \u2014 research these:",
                            color = Gt.warn,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                            items(res.failures) { f ->
                                Text(f, color = Gt.warn,
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("processed ${res.processed} \u00B7 renamed ${res.renamed}",
                        color = Gt.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("properties ${res.propsWritten}/${res.propsTotal} written",
                        color = Gt.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("\u2014 full detail below \u2014", color = Gt.text,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
                            line.startsWith("FAIL:") || line.startsWith("\u26A0") -> Gt.warn
                            line.startsWith("props:") -> Gt.accent
                            line.startsWith("ADDED") || line.startsWith("===") -> Gt.good
                            else -> Gt.dim
                        }
                        Text(line, color = col, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
                if (syncRunning) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), color = Gt.accent)
                        Spacer(Modifier.fillMaxWidth(0.05f).height(1.dp))
                        Text("  running\u2026", color = Gt.accent,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
                    color = if (syncRunning) Gt.text else Gt.good,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !syncRunning,
                onClick = onClose
            ) {
                Text("CLOSE",
                    color = if (syncRunning) Gt.text else Gt.dim,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    )
}
