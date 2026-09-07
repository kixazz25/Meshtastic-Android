package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * RIDERTRAILS-2026-09-07 -- the visible run for ADD TRAILS FROM TRACKS.
 *
 * State is hoisted, matching SyncTracksDialog: the caller owns status, result
 * and running, and supplies onStart and onClose. Nothing here reaches the
 * database or decides anything.
 *
 * WHAT THE RUN DOES: walks every recorded track, finds the stretches where the
 * ride left the published trail network, and writes each one as an unnamed
 * trail. The scan is safe to repeat -- ground already covered by a rider trail
 * reads as on-network the second time and produces nothing.
 *
 * A null [result] means NOT RUN. A result with zero trails means it ran and
 * found nothing new, which after a first successful pass is the expected
 * answer, not a failure. The dialog must be able to say which of the two
 * happened, or a rider cannot tell a working button from a broken one.
 */
@Composable
fun RiderTrailsDialog(
    status: String,
    result: RiderTrailWriter.Result?,
    running: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        containerColor = Color(0xFF0A1628),
        title = {
            Text("ADD TRAILS FROM TRACKS", color = Color(0xFF8FD0FF),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                fontSize = 14.sp)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val r = result
                when {
                    running -> {
                        CircularProgressIndicator(color = Color(0xFF8FD0FF))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(status, color = Color(0xFF4DA6FF),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    r != null -> {
                        // The number that matters, first and largest.
                        Text("${r.trailsAdded} new trail(s)",
                            color = if (r.trailsAdded > 0) Color(0xFF39FF14)
                                    else Color(0xFF97D5A5),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("%.2f miles".format(r.miles), color = Color(0xFF8FD0FF),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("from ${r.tracksScanned} track(s)",
                            color = Color(0xFF4A6080),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (r.trailsAdded == 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            // Zero is the RIGHT answer on a second run, and a
                            // rider should not have to wonder.
                            Text("Every track is already covered by a known " +
                                "trail. Nothing new to add.",
                                color = Color(0xFF4A6080),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                    else -> {
                        Text("Reads every recorded track and adds the ground " +
                            "you rode where no trail is published.",
                            color = Color(0xFF8FD0FF),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Safe to run more than once.",
                            color = Color(0xFF4A6080),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onStart,
                enabled = !running && result == null
            ) {
                Text("START", color = if (running || result != null)
                    Color(0xFF4A6080) else Color(0xFF39FF14),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!running) onClose() }) {
                Text("CLOSE",
                    color = if (running) Color(0xFF4A6080) else Color(0xFF8FD0FF),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    )
}
