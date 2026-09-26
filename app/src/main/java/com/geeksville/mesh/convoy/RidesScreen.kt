package com.geeksville.mesh.convoy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * RIDESLIST-2026-09-26 (Fred) -- Work with Rides -> "Rides -- send, edit, delete": ONE list of every ride on this
 * tablet (the self-heal runs first, so received rides always appear), a filter by ORIGINATOR -- All + every
 * originator actually named in the rides on this tablet, your own name included (Fred: no invented "Mine" chip),
 * and three actions on the SELECTED line:
 *   Send    -- my ready rides now (the existing send); forwarding a RECEIVED ride (its stored file unchanged,
 *              owner line, CC the owner) comes next.
 *   Edit    -- MY rides only; the ADD A RIDE form in edit mode comes next.
 *   Delete  -- ANY ride, after a confirmation: ConvoyRideStore.deleteRide -- the ride file, its record and its
 *              radio config (the same delete the 30-day expiry uses). Radio backups are kept.
 * "Mine" = the ride's organizer_id equals this profile's userId (stable; renaming never loses your rides).
 */

object RidesLauncher {
    var showing by mutableStateOf(false)
    fun open() { showing = true }
    fun close() { showing = false }
}

private class RideLine(
    val id: String, val name: String, val date: String, val creatorName: String,
    val sent: Boolean, val mine: Boolean, val complete: Boolean,
) {
    val owner: String get() = creatorName.ifBlank { "another rider" }
    val state: String get() = when {
        !mine -> "received from $owner"
        sent -> "sent"
        complete -> "ready to send"
        else -> "incomplete"
    }
}

private fun loadRideLines(context: android.content.Context): List<RideLine> {
    ConvoyRideStore.healFromFiles(context)
    val db = SpatialDbManager.getExtensionDb() ?: return emptyList()
    val meId = ConvoyProfileStore.load()?.userId.orEmpty()
    return try {
        db.rawQuery(
            "SELECT ride_id, ride_name, ride_date, COALESCE(organizer_id,''), COALESCE(organizer_name,''), " +
                "COALESCE(distributed_at,'') FROM rides ORDER BY created_at DESC LIMIT 200", null,
        ).use { c ->
            val out = mutableListOf<RideLine>()
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val mine = meId.isNotBlank() && c.getString(3) == meId
                // Complete = the writer's own check for MY rides; a received ride carries its network in its file.
                val complete = if (mine) ConvoyRideJsonWriter.build(context, id)?.missing?.isEmpty() == true else true
                out += RideLine(id, c.getString(1) ?: "Ride", c.getString(2) ?: "", c.getString(4) ?: "",
                    (c.getString(5) ?: "").isNotBlank(), mine, complete)
            }
            out
        }
    } catch (e: Exception) {
        android.util.Log.w("Rides", "RIDESLIST: load failed: ${e.message}"); emptyList()
    }
}

private val RLBG = Color(0xF20F1216)
private val RLCARD = Color(0xFF161B22)
private val RLSEL = Color(0xFF1F4E79)
private val RLINK = Color(0xFFE8EEF5)
private val RLDIM = Color(0xFF8B949E)
private val RLACC = Color(0xFF58A6FF)

@Composable
fun RidesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var rides by remember { mutableStateOf(loadRideLines(context)) }
    var filter by remember { mutableStateOf("All") }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<RideLine?>(null) }
    var message by remember { mutableStateOf("") }

    BackHandler(enabled = true) { if (selected != null) selected = null else onClose() }

    // Filter = the originators actually named in the rides (the ride file's originator; the heal copies it into
    // the record) -- nothing invented. "Mine" is still decided by userId, for Edit only.
    val filters = listOf("All") + rides.map { it.owner }.distinct().sortedBy { it.lowercase() }
    val shown = if (filter == "All") rides else rides.filter { it.owner == filter }

    Box(Modifier.fillMaxSize().background(RLBG).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("Rides", color = RLINK, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${rides.size} on this tablet \u2022 tap a ride, then Send, Edit or Delete", color = RLDIM, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                filters.forEach { f ->
                    Text(f, color = if (f == filter) RLACC else RLDIM, fontSize = 14.sp,
                        fontWeight = if (f == filter) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { filter = f; selected = null; message = "" }
                            .padding(end = 18.dp, top = 6.dp, bottom = 6.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty()) {
                Text(if (rides.isEmpty()) "No rides on this tablet." else "No rides match this filter.", color = RLDIM)
            }
            shown.forEach { r ->
                val on = r.id == selected
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(if (on) RLSEL else RLCARD, RoundedCornerShape(8.dp))
                        .clickable { selected = if (on) null else r.id; message = "" }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(r.name, color = if (on) Color.White else RLINK, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${r.date.ifBlank { "no date" }} \u2022 ${r.state}", color = RLDIM, fontSize = 12.sp)
                }
                if (on) {
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                message = if (r.mine) {
                                    (ConvoyRideSend.send(context, r.id) ?: "").also { rides = loadRideLines(context) }
                                } else "Forwarding a received ride comes next."
                            },
                            enabled = !r.mine || r.complete,
                        ) { Text("Send") }
                        OutlinedButton(onClick = { message = "Editing ride details comes next." }, enabled = r.mine) { Text("Edit") }
                        OutlinedButton(onClick = { confirmDelete = r }) { Text("Delete") }
                    }
                    if (r.mine && !r.complete) Text("Incomplete rides cannot be sent yet.", color = RLDIM, fontSize = 12.sp)
                }
            }
            if (message.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(message, color = RLINK, fontSize = 13.sp) }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }

    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \u201c${r.name}\u201d?") },
            text = { Text("The ride file, its record and its radio config are removed. Radio backups made for this ride are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = ConvoyRideStore.deleteRide(context, r.id, "deleted by the rider")
                    message = if (ok) "Deleted \u201c${r.name}\u201d." else "Could not delete \u201c${r.name}\u201d."
                    confirmDelete = null; selected = null; rides = loadRideLines(context)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
