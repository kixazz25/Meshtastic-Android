package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.bluetooth.BluetoothManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState   // COMMRADIOS-2026-10-11: our own rows (the Nordic scanner screen is gone)
import androidx.compose.foundation.verticalScroll
import org.json.JSONObject
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * GRPAWARE-2026-09-28 / GRPAWARE2-2026-09-28 (Fred): GRP AWARENESS -- replaces the "Mesh" button on both maps.
 *  - The label: always shown; green when the radio is connected, red when not; it PULSES each time the group's radio
 *    traffic arrives (every update of the ride map's node list).
 *  - Tap -> this panel, callable from anywhere (open()/close()), hosted in Main beside the other shared overlays:
 *    SYNC (Fred): Android's paired list is the source of truth -- only radios Android has paired are shown, and a saved
 *    radio Android no longer has paired is dropped (no ghosts), all the time, not only while the panel is open.
 *    OUR OWN CONNECTION SECTION on Meshtastic's scanner functions: the radios paired with this tablet (Bluetooth + USB)
 *    with CONNECT / DISCONNECT / "Connecting..." and FORGET (the app's saved radio + Android's pairing); ADD A RADIO
 *    (Android's Bluetooth settings do the live search and pairing -- as for Meshtastic itself) · CLOSE ·
 *    SETTINGS -- experienced mesh operators only (internet -> the Meshtastic web client, after the rider disconnects the
 *    radio; no internet -> the settings built into the app) · APPLY RIDE TO RADIO · MESHTASTIC (temporary: today's
 *    unfold of the Meshtastic rail, kept until we are sure nothing else is needed from it).
 */
object GrpAwarenessLauncher {
    val showing = mutableStateOf(false)
    val connected = mutableStateOf(false)
    /** The time of the last radio traffic -- the label pulses when it changes. */
    val pulseAt = mutableStateOf(0L)
    fun open() {   // GRPMODE-2026-10-13: group awareness off -> the popup instead of the panel
        if (!GroupMode.on.value) { GroupMode.askToActivate(); return }
        showing.value = true
    }
    /** CHECKINCONNECT-2026-09-28 (Fred): opened by CHK IN with no radio -- once a radio connects, the check-in opens. */
    var thenCheckIn = false
    fun close() { showing.value = false; thenCheckIn = false }
    fun pulse() { pulseAt.value = System.currentTimeMillis() }
    /** CHECKINAPPLY-2026-09-28: the connected radio's node number (set from Main). CODE RULE 1: null = no radio. */
    val myNodeNum = mutableStateOf<Int?>(null)
    /** LINKFIX-2026-10-03 (Fred): the radio is TALKING. COMMHEALTH2-2026-10-11: from the comm side's link health, set by the tick. */
    val talking = mutableStateOf(false)
    /** LINKFIX-2026-10-03: the automatic reconnects failed -- ask the rider to turn the radio off and on. */
    val powerCycle = mutableStateOf(false)
    var powerCycleDismissed = false
    /** LINKHB-2026-10-05: other carts in the tick array -- the power-cycle banner only shows while riding with a group. */
    val ridingGroup = mutableStateOf(false)
}

/** The label's colour: green / red by connection, brightening briefly on each pulse. */
@Composable
fun grpAwarenessColor(): Color {
    // LINKFIX-2026-10-03 (Fred): green only while the radio is TALKING; a connected-but-silent radio is red.
    // GRPSTATUS-2026-10-24 (Fred 12:08): a STATUS light keeps its own colours -- green / red / grey, never the scheme's text colour.
    val base = if (!GroupMode.on.value) Color(0xFF5A6470)   // GRPMODE-2026-10-13: greyed while group awareness is off
        else if (GrpAwarenessLauncher.connected.value && GrpAwarenessLauncher.talking.value) Color(0xFF35C46A) else Color(0xFFE0453A)
    var lit by remember { mutableStateOf(false) }
    val at = GrpAwarenessLauncher.pulseAt.value
    LaunchedEffect(at) { if (at > 0L) { lit = true; delay(400); lit = false } }
    val alpha by animateFloatAsState(if (lit) 1f else 0.62f, label = "grpPulse")
    return base.copy(alpha = alpha)
}

private fun hasInternet(ctx: Context): Boolean = try {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
} catch (e: Exception) { false }

// COMMLINKREQ-2026-10-11: forgetPairing() moved to the comm side (MeshCommLink.forget).

// COMMBOND-2026-10-11: the pairedMacs helper is removed -- the paired list handed to the panel is kept current by the comm side.

// COMMHEALTH2-2026-10-11: the ghost check (isGhostSelection) moved to the comm side (MeshCommLink).

private fun openBluetoothSettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// COMMRADIOS-2026-10-11: radioLongName removed -- each radio's name arrives in the comm side's list (RadioEntry.name, the name table).

private fun openAppSettings(ctx: Context) {   // COMMRADIOS-2026-10-11: where Android grants the Nearby devices permission
    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * GRPAWARE2-2026-09-28 (Fred): "Last applied" for the connected radio -- the most recent APPLY the configurator recorded
 * for it (convoy_backups/<radio>/<...>.json: title = the ride, appliedAt). "" = no radio; "none" = never applied by
 * GroupTrack.
 */
/** CHECKINAPPLY-2026-09-28: the ride LAST APPLIED to this radio -- (rideId, title) from the configurator's records.
 *  CODE RULE 1: null = nothing applied by GroupTrack (or no radio); rideId null = GroupTrack default / not a ride. */
fun lastAppliedRide(ctx: Context, nodeNum: Int?): Pair<String?, String>? {   // COMMSAVES-2026-10-14: through the Comm API
    if (nodeNum == null || nodeNum == 0) return null
    val best = com.geeksville.mesh.comm.CommAccess.config(ctx).appliedHistory(nodeNum.toString()).firstOrNull { it.title != "As found" } ?: return null
    return Pair(best.rideId, best.title.ifBlank { "a saved configuration" })
}

fun lastAppliedLine(ctx: Context, nodeNum: Int?): String {   // COMMSAVES-2026-10-14: through the Comm API
    if (nodeNum == null || nodeNum == 0) return ""
    val best = com.geeksville.mesh.comm.CommAccess.config(ctx).appliedHistory(nodeNum.toString()).firstOrNull() ?: return "none"
    val whenText = runCatching {
        java.time.LocalDateTime.parse(best.appliedAt)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
    }.getOrDefault(best.appliedAt)
    return best.title.ifBlank { "a saved configuration" } + " \u00b7 " + whenText
}

/**
 * GRPAWARE2-2026-09-28 (Fred): the GRP Awareness panel -- design v8. COMMRADIOS-2026-10-11 (2.7b P2b): GroupTrack draws its
 * OWN rows from the comm side's radio list (paired first, then new radios nearby) -- Meshtastic's scanner screen is no longer
 * embedded. Same look and words as before.
 *  - The title red until a radio is connected, then green.
 *  - CONNECTED TO: the radio's name with DISCONNECT, and "Last applied: <ride> . <date>"; empty when not connected.
 *  - AVAILABLE RADIOS: paired ones with CONNECT and FORGET; new ones marked NEW TO THIS ANDROID with PAIR. One radio at a
 *    time: CONNECT and PAIR are greyed while a radio is in use (DISCONNECT first). The radio in use is only in its box.
 *  - FORGET, confirmed: removed from GroupTrack and from Android's Bluetooth pairings.
 *  - Three actions side by side: SELECT A RIDE AND APPLY SETTINGS TO THE RADIO . RADIO SETTINGS . MESHTASTIC SIDE MENU.
 */
@Composable
fun GrpAwarenessPanel(
    radios: List<com.grouptrack.comm.RadioEntry>,   // COMMRADIOS-2026-10-11: paired first, then new radios nearby
    scanProblem: String?,   // COMMRADIOS-2026-10-11: CODE RULE 1: null = scanning; PERMISSION_MISSING / BLUETOOTH_OFF = say so
    selectedAddress: String,
    connected: Boolean,
    unpairedNote: Boolean,   // COMMHEALTH2-2026-10-11: the comm side cleared a radio Android no longer has paired (RADIO_UNPAIRED)
    lastApplied: String,   // lastAppliedLine(): "" no radio, "none" never applied, else "<ride> . <date>"
    network: String?,   // RADIONET-2026-10-12: the radio's network (CommConfig.currentNetwork). CODE RULE 1: null = not read yet -> record line only
    onDisconnect: () -> Unit,
    onConnect: (String, (String) -> Unit) -> Unit,   // COMMRADIOS-2026-10-11: CONNECT or PAIR by id; the callback gets a failure reason
    onForget: (String, (Boolean) -> Unit) -> Unit,   // COMMLINKREQ-2026-10-11: true = forgotten; false = Android refused
    menus: Set<String>,   // COMMSETTINGS-2026-10-12: the comm-side screens offered (CommMenus.items) -- a button shows only when offered
    onLocalSettings: () -> Unit,
    onApplyRide: () -> Unit,
    onMeshtastic: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<com.grouptrack.comm.RadioEntry?>(null) }
    val busy = connected || (selectedAddress.isNotBlank() && selectedAddress != "n")   // one radio at a time
    val btOff = scanProblem == "BLUETOOTH_OFF"   // COMMBTOFF-2026-10-11 (Fred 10-11): Bluetooth off -> NO radio status, only the message
    val current = radios.firstOrNull { it.id == selectedAddress }
    val green = Gt.accent; val red = Gt.bad; val dim = Gt.dim
    fun failWords(reason: String): String = when (reason) {
        "PERMISSION_MISSING" -> "GroupTrack needs the Nearby devices permission to pair a radio."
        "BLUETOOTH_OFF" -> "Bluetooth is off — turn it on to connect your radio."   // COMMBTOFF-2026-10-11
        "RADIO_NOT_FOUND" -> "That radio is no longer nearby — turn it on and try again."
        "PAIR_FAILED" -> "Pairing did not finish — try PAIR again and enter the radio's PIN (123456, or the code on its screen)."
        else -> "The radio could not be connected ($reason)."
    }
    Box(modifier = Modifier.fillMaxSize().background(Gt.panel), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp), color = Gt.panel) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {   // TOUCHSTD-2026-10-22: scrolls
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("GRP Awareness", color = if (connected) green else red, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    com.geeksville.mesh.grouptrack.GtHelpButton("grp")   // TOUCHSTD-2026-10-22
                    TextButton(onClick = onClose) { Text("✕", color = Gt.accent, fontSize = 18.sp) }
                }
                // CHECKINCONNECT-2026-09-28: opened by CHK IN with no radio
                if (GrpAwarenessLauncher.thenCheckIn) Text("Connect your radio to check in.", color = Gt.warn, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (unpairedNote) Text("Your radio was forgotten by Android — PAIR it again.", color = Gt.warn, fontSize = 13.sp, fontWeight = FontWeight.Bold)   // COMMHEALTH2-2026-10-11
                // CONNECTED TO -- empty when nothing is connected; not shown at all while Bluetooth is off (COMMBTOFF-2026-10-11)
                if (!btOff) Surface(shape = RoundedCornerShape(10.dp), color = if (busy) Gt.text.copy(alpha = 0.18f) else Gt.text.copy(alpha = 0.18f)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (connected) "● CONNECTED TO" else if (busy) "◌ CONNECTING TO" else "CONNECTED TO",
                                color = if (busy) Gt.good else Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (busy) {
                                Text(current?.name ?: "your radio", color = Gt.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                // RADIONET-2026-10-12 (Fred 10-12): set up for GroupTrack = the RADIO is on a "Group..." network; this
                                // tablet's record (by radio id) is the second line. Amber only for a radio on another network.
                                if (connected && network != null) {
                                    val shown = network.ifBlank { "the radio's default" }
                                    if (network.startsWith(com.grouptrack.core.NetworkGenerator.CHANNEL_PREFIX))
                                        Text("GroupTrack network " + shown, color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    else Text("Not set up for GroupTrack (network " + shown + ")", color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                if (connected && lastApplied.isNotBlank()) Text(
                                    if (lastApplied == "none") "Not applied from this tablet before" else "Last applied here: " + lastApplied,
                                    color = Gt.accent, fontSize = 12.sp)
                            }
                        }
                        if (busy) TextButton(onClick = { onDisconnect(); message = "" }) { Text("DISCONNECT", color = Gt.bad, fontWeight = FontWeight.Bold) }
                    }
                }
                if (!btOff) Text("AVAILABLE RADIOS", color = Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (btOff) Unit else if (!busy) Text("Select a radio from the list to connect.", color = Gt.warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else Text("One radio at a time — DISCONNECT the one above to connect or pair another.", color = dim, fontSize = 12.sp)
                // COMMRADIOS-2026-10-11: why no radios can be found, in GroupTrack's words, with the way to fix it
                when (scanProblem) {
                    "BLUETOOTH_OFF" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bluetooth is off — turn it on to find your radio.", color = Gt.warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { openBluetoothSettings(ctx) }) { Text("BLUETOOTH") }
                    }
                    "PERMISSION_MISSING" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GroupTrack needs the Nearby devices permission to find radios.", color = Gt.warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { openAppSettings(ctx) }) { Text("ALLOW") }
                    }
                    else -> {}
                }
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    val rows = radios.filter { !(it.id == selectedAddress && busy) }   // the radio in use lives in its box
                    if (rows.isEmpty() && scanProblem == null) Text("Looking for radios nearby…", color = dim, fontSize = 12.sp)
                    rows.forEach { radio ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(radio.name, color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(if (radio.paired) radio.id.drop(1) else "NEW TO THIS ANDROID · " + radio.id.drop(1),
                                    color = if (radio.paired) dim else Gt.warn, fontSize = 12.sp)
                            }
                            TextButton(enabled = !busy, onClick = {
                                message = if (radio.paired) "" else "Pairing " + radio.name + " — enter the radio's PIN when Android asks."
                                onConnect(radio.id) { reason -> message = failWords(reason) }
                            }) { Text(if (radio.paired) "CONNECT" else "PAIR") }
                            if (radio.paired) TextButton(onClick = { forgetting = radio }) { Text("FORGET", color = Gt.bad) }
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, color = Gt.warn, fontSize = 13.sp)
                // Three actions, side by side
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(modifier = Modifier.weight(1f).clickable { onApplyRide() }, shape = RoundedCornerShape(8.dp), color = Gt.panel) {
                        Text("SELECT A RIDE AND APPLY SETTINGS TO THE RADIO", color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                    }
                    // NOWEBCLIENT-2026-09-29 (Fred): RADIO SETTINGS always opens the Meshtastic app's own radio settings -- the web
                    // client left the radio disconnected, and the internet check was unreliable (said online with it off).
                    if ("radio-settings" in menus) Surface(modifier = Modifier.weight(1f).clickable { message = ""; onLocalSettings() },   // COMMSETTINGS-2026-10-12
                        shape = RoundedCornerShape(8.dp), color = Gt.panel) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("RADIO SETTINGS", color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("⚠ experienced mesh operators only", color = Gt.warn, fontSize = 12.sp)
                        }
                    }
                    if ("nodes" in menus) Surface(modifier = Modifier.weight(1f).clickable { message = ""; onMeshtastic() }, shape = RoundedCornerShape(8.dp), color = Gt.panel) {   // COMMSETTINGS-2026-10-12
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("NODES", color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)   // NODESMENU-2026-10-15 (Fred): replaces the side menu
                            Text("radios heard", color = Gt.dim, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    // FORGET, confirmed: from GroupTrack and from Android's Bluetooth pairings.
    forgetting?.let { r ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget " + r.name + "?") },
            text = { Text("It will be removed from GroupTrack and from this tablet's Android Bluetooth pairings. To use it again it " +
                "reappears as new to this Android while the panel scans — PAIR it with the radio's PIN.") },
            confirmButton = { TextButton(onClick = {
                if (r.id == selectedAddress) onDisconnect()
                val rName = r.name
                onForget(r.id) { ok ->   // COMMLINKREQ-2026-10-11: the comm side forgets; GroupTrack says what happened
                    message = if (ok) rName + " is forgotten." else "Android needs you to do it: tap " + rName + " → Forget, then come back."
                    if (!ok) openBluetoothSettings(ctx)
                }
                forgetting = null
            }) { Text("FORGET") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("CANCEL") } },
        )
    }
}
