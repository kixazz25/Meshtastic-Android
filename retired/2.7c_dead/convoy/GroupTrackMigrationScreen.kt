package com.geeksville.mesh.convoy

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MIGSCREEN-2026-09-11: moves the GroupTrack data out of shared storage, and is
 * the only thing on screen while it does.
 *
 * \u26d4 WHY IT OWNS THE SURFACE. The work takes time and must not be cancelled --
 * Fred, 09-11: *"the last thing I want is this cancelled in the field."* Rendered
 * in place of the gate body, so no Exit button exists while it runs. The same
 * approach HomeStatePickerScreen already takes for the trail import.
 *
 * \u26a0\u26a0 THE BUTTONS ARE SCAFFOLDING. In the field build there are none: the
 * migration runs, certifies and proceeds on its own. They exist now so each step
 * can be verified separately, and REMOVING THEM IS A HUMAN RESPONSIBILITY.
 */
@Composable
fun GroupTrackMigrationScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val lines = remember { mutableStateListOf<GroupTrackMigration.ItemResult>() }
    var running by remember { mutableStateOf(false) }
    var migrated by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var renamed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1216))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(36.dp))
        Text(
            "STORAGE UPDATE",
            color = Color(0xFF58A6FF), fontSize = 18.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Moving your trails, tracks, routes and settings into the app's own " +
                "storage.\nYour downloaded maps are not affected.",
            color = Color(0xFF8B949E), fontSize = 13.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
        )

        if (running) {
            Spacer(Modifier.height(16.dp))
            Text(
                "WORKING \u2014 DO NOT INTERRUPT",
                color = Color(0xFFFF4444), fontSize = 14.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(18.dp))
        // \u2b50 EVERY ITEM SHOWS ITS OWN COMPARISON, not a tick. A size and a row
        // count on both sides is certification; a tick is a claim.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            lines.forEach { r ->
                Text(
                    r.line,
                    color = if (r.ok) Color(0xFFDDE3E9) else Color(0xFFFF6B6B),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (failed) {
            Text(
                "\u26d4 STOPPED. Nothing was renamed or deleted \u2014 your data in shared " +
                    "storage is untouched.",
                color = Color(0xFFFF6B6B), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
        }

        // \u26a0 SCAFFOLDING \u2014 REMOVE BEFORE THE FIELD BUILD.
        if (!running) {
            if (!migrated) {
                MigButton("Move to internal storage") {
                    running = true
                    lines.clear()
                    kotlinx.coroutines.MainScope().launch {
                        val ok = withContext(Dispatchers.IO) {
                            // \u26a0 PROTOTYPE ONLY: put any *-EXT names back so the loop
                            // can be run again after a pass that reached the rename.
                            GroupTrackMigration.prototypeRestoreExtNames()
                            if (!GroupTrackMigration.clearInternal(context)) return@withContext false
                            GroupTrackMigration.migrate(context) { r ->
                                kotlinx.coroutines.MainScope().launch { lines.add(r) }
                            }
                        }
                        running = false
                        migrated = ok
                        failed = !ok
                    }
                }
            } else if (!renamed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Certified. Run the app and check it before renaming \u2014 the " +
                        "rename is the point of no easy return.",
                    color = Color(0xFF8B949E), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                MigButton("Continue without renaming") { onDone() }
                Spacer(Modifier.height(8.dp))
                MigButton("Rename shared storage to -EXT") {
                    running = true
                    kotlinx.coroutines.MainScope().launch {
                        val ok = withContext(Dispatchers.IO) {
                            GroupTrackMigration.renameSources(context) { r ->
                                kotlinx.coroutines.MainScope().launch { lines.add(r) }
                            }
                        }
                        running = false
                        renamed = ok
                    }
                }
            } else {
                MigButton("Continue") { onDone() }
            }
        }
    }
}

@Composable
private fun MigButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1F6FEB),
            contentColor = Color(0xFFE6EDF3)
        )
    ) {
        Text(label, fontFamily = FontFamily.Monospace)
    }
}
