package com.geeksville.mesh.convoy

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
import no.nordicsemi.android.common.scanner.rememberFilterState
import no.nordicsemi.android.common.scanner.view.ScannerView
import org.meshtastic.core.ble.MeshtasticBleConstants.BLE_NAME_PATTERN
import org.meshtastic.core.ble.MeshtasticBleConstants.SERVICE_UUID
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
    fun open() { showing.value = true }
    /** CHECKINCONNECT-2026-09-28 (Fred): opened by CHK IN with no radio -- once a radio connects, the check-in opens. */
    var thenCheckIn = false
    fun close() { showing.value = false; thenCheckIn = false }
    fun pulse() { pulseAt.value = System.currentTimeMillis() }
    /** CHECKINAPPLY-2026-09-28: the connected radio's node number (set from Main). CODE RULE 1: null = no radio. */
    val myNodeNum = mutableStateOf<Int?>(null)
}

/** The label's colour: green / red by connection, brightening briefly on each pulse. */
@Composable
fun grpAwarenessColor(): Color {
    val base = if (GrpAwarenessLauncher.connected.value) Color(0xFF35C46A) else Color(0xFFE0453A)
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

/** GRPAWARE: remove Android's pairing with a radio. Android has no public "unpair", so this uses the hidden
 *  BluetoothDevice.removeBond(); false where Android refuses -- the caller then opens Bluetooth settings instead. */
private fun forgetPairing(ctx: Context, mac: String): Boolean = try {
    val dev = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(mac)
    (dev?.javaClass?.getMethod("removeBond")?.invoke(dev) as? Boolean) == true
} catch (e: Exception) { false }

/** GRPAWARE-SYNC: the MACs Android currently has paired. Empty if Bluetooth is off or not permitted.
 *  ANDROID'S PAIRED LIST IS THE SOURCE OF TRUTH -- GroupTrack never shows or keeps a Bluetooth radio Android has not paired. */
fun pairedMacs(ctx: Context): Set<String> = try {
    ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.map { it.address.uppercase() }?.toSet() ?: emptySet()
} catch (e: Exception) { emptySet() }

/** GRPAWARE-SYNC: true when the app's saved radio is a Bluetooth radio Android no longer has paired -- a ghost. */
fun isGhostSelection(ctx: Context, selectedAddress: String?): Boolean {
    val a = selectedAddress ?: return false
    if (!a.startsWith("x") || a.length < 2) return false          // "x" = Bluetooth; USB/TCP are not Android pairings
    val paired = pairedMacs(ctx)
    return paired.isNotEmpty() && a.substring(1).uppercase() !in paired
}

private fun openBluetoothSettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** GRPAWARE2: the radio's own name for itself (its long name), else the Bluetooth name until the radio is known. */
fun radioLongName(d: com.geeksville.mesh.model.DeviceListEntry): String =
    d.node?.user?.long_name?.takeIf { it.isNotBlank() } ?: d.name

/**
 * GRPAWARE2-2026-09-28 (Fred): "Last applied" for the connected radio -- the most recent APPLY the configurator recorded
 * for it (convoy_backups/<radio>/<...>.json: title = the ride, appliedAt). "" = no radio; "none" = never applied by
 * GroupTrack.
 */
/** CHECKINAPPLY-2026-09-28: the ride LAST APPLIED to this radio -- (rideId, title) from the configurator's records.
 *  CODE RULE 1: null = nothing applied by GroupTrack (or no radio); rideId null = GroupTrack default / not a ride. */
fun lastAppliedRide(ctx: Context, nodeNum: Int?): Pair<String?, String>? {
    if (nodeNum == null || nodeNum == 0) return null
    val hex = "%08x".format(nodeNum)
    val dir = java.io.File(ctx.filesDir, "convoy_backups").listFiles()?.firstOrNull { d ->
        d.isDirectory && (d.name.lowercase().contains(hex) || d.name == nodeNum.toString() || d.name == (nodeNum.toLong() and 0xffffffffL).toString())
    } ?: return null
    val best = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }
        ?.filter { it.optString("appliedAt").isNotBlank() && it.optString("title") != "As found" }?.maxByOrNull { it.optString("appliedAt") } ?: return null
    val rid = best.optString("rideId").takeIf { it.isNotBlank() && it != "null" }
    return Pair(rid, best.optString("title").ifBlank { "a saved configuration" })
}

fun lastAppliedLine(ctx: Context, nodeNum: Int?): String {
    if (nodeNum == null || nodeNum == 0) return ""
    val hex = "%08x".format(nodeNum)
    val dir = java.io.File(ctx.filesDir, "convoy_backups").listFiles()?.firstOrNull { d ->
        d.isDirectory && (d.name.lowercase().contains(hex) || d.name == nodeNum.toString() || d.name == (nodeNum.toLong() and 0xffffffffL).toString())
    } ?: return "none"
    val best = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }
        ?.filter { it.optString("appliedAt").isNotBlank() }?.maxByOrNull { it.optString("appliedAt") } ?: return "none"
    val whenText = runCatching {
        java.time.LocalDateTime.parse(best.optString("appliedAt"))
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
    }.getOrDefault(best.optString("appliedAt"))
    return best.optString("title").ifBlank { "a saved configuration" } + " \u00b7 " + whenText
}

/**
 * GRPAWARE2-2026-09-28 (Fred): the GRP Awareness panel -- design v8.
 *  - The title red until a radio is connected, then green.
 *  - CONNECTED TO: the radio's long name with DISCONNECT, and "Last applied: <ride> . <date>"; empty when not connected.
 *  - AVAILABLE RADIOS: the radios found nearby by Meshtastic's own scanner (Nordic ScannerView, Meshtastic's filter):
 *    paired ones with CONNECT and FORGET; new ones marked NEW TO THIS ANDROID with PAIR. One radio at a time: CONNECT
 *    and PAIR are greyed while a radio is connected (DISCONNECT first). The connected radio is only in its box.
 *  - FORGET, confirmed: removed from GroupTrack and from Android's Bluetooth pairings.
 *  - Three actions side by side: SELECT A RIDE AND APPLY SETTINGS TO THE RADIO . RADIO SETTINGS (experienced mesh
 *    operators only; the web client with internet -- after DISCONNECT -- the app's settings without) . MESHTASTIC
 *    SIDE MENU (temporary). The X closes.
 */
@Composable
fun GrpAwarenessPanel(
    scanModel: com.geeksville.mesh.ui.connections.ScannerViewModel,
    paired: List<com.geeksville.mesh.model.DeviceListEntry>,   // the paired radios (their long names)
    selectedAddress: String,
    connected: Boolean,
    lastApplied: String,   // lastAppliedLine(): "" no radio, "none" never applied, else "<ride> . <date>"
    onDisconnect: () -> Unit,
    onLocalSettings: () -> Unit,
    onApplyRide: () -> Unit,
    onMeshtastic: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<com.geeksville.mesh.model.DeviceListEntry?>(null) }
    val busy = connected || (selectedAddress.isNotBlank() && selectedAddress != "n")   // one radio at a time
    val current = paired.firstOrNull { it.fullAddress == selectedAddress }
    val green = Color(0xFF35C46A); val red = Color(0xFFE0453A); val dim = Color(0xFF8899AA)
    val filterState = rememberFilterState(filter = { Any { ServiceUuid(SERVICE_UUID); Name(Regex(BLE_NAME_PATTERN)) } })
    Box(modifier = Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp), color = Color(0xFF12213A)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("GRP Awareness", color = if (connected) green else red, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("\u2715", color = Color(0xFF7FB2E5), fontSize = 18.sp) }
                }
                // CHECKINCONNECT-2026-09-28: opened by CHK IN with no radio
                if (GrpAwarenessLauncher.thenCheckIn) Text("Connect your radio to check in.", color = Color(0xFFE8C27A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                // CONNECTED TO -- empty when nothing is connected
                Surface(shape = RoundedCornerShape(10.dp), color = if (busy) Color(0xFF0E2A1A) else Color(0xFF0D1A2C)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (connected) "\u25CF CONNECTED TO" else if (busy) "\u25CC CONNECTING TO" else "CONNECTED TO",
                                color = if (busy) Color(0xFF6EE0A5) else Color(0xFF5E7288), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (busy) {
                                Text(current?.let { radioLongName(it) } ?: "your radio", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                if (connected && lastApplied.isNotBlank()) Text(
                                    if (lastApplied == "none") "No GroupTrack configuration applied to this radio yet" else "Last applied: " + lastApplied,
                                    color = if (lastApplied == "none") Color(0xFFE8A33D) else Color(0xFFB8C8D8), fontSize = 12.sp)
                            }
                        }
                        if (busy) TextButton(onClick = { onDisconnect(); message = "" }) { Text("DISCONNECT", color = Color(0xFFF08C84), fontWeight = FontWeight.Bold) }
                    }
                }
                Text("AVAILABLE RADIOS", color = Color(0xFF8FA3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (!busy) Text("Select a radio from the list to connect.", color = Color(0xFFE8C27A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else Text("One radio at a time \u2014 DISCONNECT the one above to connect or pair another.", color = dim, fontSize = 11.sp)
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    ScannerView(
                        state = filterState,
                        onScanResultSelected = { result -> if (!busy) scanModel.onSelected(com.geeksville.mesh.model.DeviceListEntry.Ble(result.peripheral)) },
                        deviceItem = { result ->
                            val device = remember(result.peripheral.address, paired) {
                                paired.find { it.fullAddress == "x${result.peripheral.address}" }
                                    ?: com.geeksville.mesh.model.DeviceListEntry.Ble(result.peripheral)
                            }
                            if (!(device.fullAddress == selectedAddress && busy)) {   // the radio in use lives in its box
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(radioLongName(device), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text(if (device.bonded) device.address else "NEW TO THIS ANDROID \u00b7 " + device.address,
                                            color = if (device.bonded) dim else Color(0xFFF2C14E), fontSize = 11.sp)
                                    }
                                    TextButton(enabled = !busy, onClick = { scanModel.onSelected(device); message = "" }) {
                                        Text(if (device.bonded) "CONNECT" else "PAIR")
                                    }
                                    if (device.bonded) TextButton(onClick = { forgetting = device }) { Text("FORGET", color = Color(0xFFF08C84)) }
                                }
                            }
                        },
                    )
                }
                if (message.isNotBlank()) Text(message, color = Color(0xFFE8A33D), fontSize = 13.sp)
                // Three actions, side by side
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(modifier = Modifier.weight(1f).clickable { onApplyRide() }, shape = RoundedCornerShape(8.dp), color = Color(0xFF12304F)) {
                        Text("SELECT A RIDE AND APPLY SETTINGS TO THE RADIO", color = Color(0xFF7FC4FF), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                    }
                    // NOWEBCLIENT-2026-09-29 (Fred): RADIO SETTINGS always opens the Meshtastic app's own radio settings -- the web
                    // client left the radio disconnected, and the internet check was unreliable (said online with it off).
                    Surface(modifier = Modifier.weight(1f).clickable { message = ""; onLocalSettings() },
                        shape = RoundedCornerShape(8.dp), color = Color(0xFF2A2210)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("RADIO SETTINGS", color = Color(0xFFF2C14E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("\u26A0 experienced mesh operators only", color = Color(0xFFC9A659), fontSize = 9.sp)
                        }
                    }
                    Surface(modifier = Modifier.weight(1f).clickable { onMeshtastic() }, shape = RoundedCornerShape(8.dp), color = Color(0xFF16263E)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("MESHTASTIC SIDE MENU", color = Color(0xFF9CC7F5), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("temporary", color = Color(0xFF6F8196), fontSize = 9.sp)
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
            title = { Text("Forget " + radioLongName(r) + "?") },
            text = { Text("It will be removed from GroupTrack and from this tablet's Android Bluetooth pairings. To use it again it " +
                "reappears as new to this Android while the panel scans \u2014 PAIR it with the radio's PIN.") },
            confirmButton = { TextButton(onClick = {
                if (r.fullAddress == selectedAddress) onDisconnect()
                val ok = forgetPairing(ctx, r.address)
                message = if (ok) radioLongName(r) + " is forgotten." else "Android needs you to do it: tap " + r.name + " \u2192 Forget, then come back."
                if (!ok) openBluetoothSettings(ctx)
                forgetting = null
            }) { Text("FORGET") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("CANCEL") } },
        )
    }
}
