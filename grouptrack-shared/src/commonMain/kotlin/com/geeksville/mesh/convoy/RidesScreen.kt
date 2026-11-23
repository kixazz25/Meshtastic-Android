package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.ride.GtRideJson
import com.grouptrack.shared.ride.GtRideStore
import com.grouptrack.shared.ride.RideFiles

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

/**
 * RIDESCONV-2026-11-23: what RidesScreen needs from its HOST, and nothing else.
 *
 * Every entry is here because the PLATFORM owns it -- not because the screen was redesigned.
 * The screen's body is unchanged from the Android original; these four are the seams that could
 * not cross. CODE RULE 1: no defaults -- a host that cannot do one of these has to say so.
 */
class RidesBridge(
    /** The rides folder and its file operations. GtRideStore.healFromFiles / deleteRide and
     *  GtRideJson.build all take it. Android: GtRidesAndroid.Files(context); iOS: IosRideFiles(). */
    val files: RideFiles,
    /** Android's SYSTEM BACK. iOS has no hardware/gesture equivalent to intercept here, so its
     *  host passes an empty composable -- the screen's own behaviour (clear the selection, else
     *  close) stays in the screen either way. */
    val backHandler: @Composable (Boolean, () -> Unit) -> Unit,
    /** HELP. GtPanelChrome's own note: two of the three standard controls need the host, because
     *  Android's issue capture needs LocalView. GtCloseButton IS shared and is called directly. */
    val help: @Composable (String) -> Unit,
    /** RIDESCONVFIX-2026-11-23: the Edit path opens the ride form, so the list carries the form's bridge.
     *  It disappears when both screens take one bridge, which is a consolidation question. */
    val createBridge: RideCreateBridge,
    /** The SHARE SHEET. Android: ConvoyRideSend.send(context, id) -- ACTION_SEND_MULTIPLE;
     *  iOS: UIActivityViewController. Returns the message to show the rider, or null for none. */
    val send: (String) -> String?,
)

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

/**
 * RIDESCONV-2026-11-23: the same list, through the shared store. The SQL did not change -- it moved
 * into GtRideStore.ridesForList() verbatim (same columns, same WHERE, same ORDER BY, same LIMIT).
 * What changed here is only the SEAMS it was made of:
 *   Context                              -> b.files (RideFiles)
 *   SpatialDbManager.getExtensionDb      -> GtRideStore's own env.dataDb(), inside ridesForList
 *   ConvoyProfileStore.load()?.userId    -> GtRideStore.env.profile()?.userId
 *   ConvoyRideJsonWriter.build(ctx,id)   -> GtRideJson.build(fs,id) -- the same non-writing check
 * The per-ride completeness call stays per-ride, exactly as the Android original had it.
 */
private fun loadRideLines(b: RidesBridge): List<RideLine> {
    GtRideStore.healFromFiles(b.files)
    // RIDESCONVFIX-2026-11-23: exactly what Android read -- ConvoyProfileStore.load()?.userId -- and the
    // ride env already carries it, so no platform call is needed for the identity.
    val meId = GtRideStore.env.profile()?.userId.orEmpty()
    return try {
        GtRideStore.ridesForList().map { r ->
            val mine = meId.isNotBlank() && r.organizerId == meId
            // Complete = the writer's own check for MY rides; a received ride carries its network in its file.
            val complete = if (mine) GtRideJson.build(b.files, r.rideId)?.missing?.isEmpty() == true else true
            RideLine(r.rideId, r.rideName, r.rideDate, r.organizerName,
                r.distributedAt.isNotBlank(), mine, complete)
        }
    } catch (e: Exception) {
        gtLogger("Rides").w { "RIDESLIST: load failed: ${e.message}" }; emptyList()
    }
}

private val RLBG: Color get() = Gt.panel  // STD1-2026-10-24 was 0xF20F1216 
private val RLCARD: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF161B22 
private val RLSEL: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF1F4E79 
private val RLINK: Color get() = Gt.text  // STD1-2026-10-24 was 0xFFE8EEF5 
private val RLDIM: Color get() = Gt.dim  // STD1-2026-10-24 was 0xFF8B949E 
private val RLACC: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF58A6FF 

@Composable
fun RidesScreen(b: RidesBridge, onClose: () -> Unit) {
    var rides by remember { mutableStateOf(loadRideLines(b)) }
    var filter by remember { mutableStateOf("All") }
    var selected by remember { mutableStateOf<String?>(null) }
    // EDITRIDE-2026-09-27 (Fred): Edit opens the ride form OVER this list, filled in; SAVE or CANCEL comes back here.
    var editing by remember { mutableStateOf<String?>(null) }   // CODE RULE 1: null = not editing
    editing?.let { rid ->
        ConvoyRideCreateScreen(
            b = b.createBridge,
            initialRouteId = GtRideStore.rideForEdit(rid)?.routeId,
            editRideId = rid,
            onRideCreated = { editing = null; rides = loadRideLines(b) },
            onBack = { editing = null })
        return
    }
    var confirmDelete by remember { mutableStateOf<RideLine?>(null) }
    var message by remember { mutableStateOf("") }

    b.backHandler(true) { if (selected != null) selected = null else onClose() }

    // Filter = the originators actually named in the rides (the ride file's originator; the heal copies it into
    // the record) -- nothing invented. "Mine" is still decided by userId, for Edit only.
    val filters = listOf("All") + rides.map { it.owner }.distinct().sortedBy { it.lowercase() }
    val shown = if (filter == "All") rides else rides.filter { it.owner == filter }

    Box(Modifier.fillMaxSize().background(RLBG).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {   // GTRIDES-2026-10-22: HELP ✕ top right
                Text("Rides", color = RLINK, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                b.help("rides.mine")
                com.geeksville.mesh.grouptrack.GtCloseButton(onClose)
            }
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
                    Text(r.name, color = if (on) Gt.text else RLINK, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${r.date.ifBlank { "no date" }} \u2022 ${r.state}", color = RLDIM, fontSize = 12.sp)
                }
                if (on) {
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                message = if (r.mine) {
                                    (b.send(r.id) ?: "").also { rides = loadRideLines(b) }
                                } else "Forwarding a received ride comes next."
                            },
                            enabled = !r.mine || r.complete,
                        ) { Text("Share") }   // WWRMENU-2026-09-30 (Fred): Share, not Send
                        OutlinedButton(onClick = { editing = selected }, enabled = r.mine) { Text("Edit") }   /* EDITRIDE-2026-09-27 */
                        OutlinedButton(onClick = { confirmDelete = r }) { Text("Delete") }
                    }
                    if (r.mine && !r.complete) Text("Incomplete rides cannot be shared yet.", color = RLDIM, fontSize = 12.sp)
                }
            }
            if (message.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(message, color = RLINK, fontSize = 13.sp) }
            Spacer(Modifier.height(14.dp))
            // GTRIDES-2026-10-22: the bottom Close moved to the top (✕)
        }
    }

    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \u201c${r.name}\u201d?") },
            text = { Text("The ride file, its record and its radio config are removed. Radio backups made for this ride are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = GtRideStore.deleteRide(b.files, r.id, "deleted by the rider")
                    message = if (ok) "Deleted \u201c${r.name}\u201d." else "Could not delete \u201c${r.name}\u201d."
                    confirmDelete = null; selected = null; rides = loadRideLines(b)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
