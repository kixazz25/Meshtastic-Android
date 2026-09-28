package com.geeksville.mesh.convoy

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.bluetooth.BluetoothManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
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
 * GRPAWARE-2026-09-28 (Fred): GRP AWARENESS -- replaces the "Mesh" button on both maps.
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
    fun close() { showing.value = false }
    fun pulse() { pulseAt.value = System.currentTimeMillis() }
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

@Composable
fun GrpAwarenessPanel(
    radios: List<com.geeksville.mesh.model.DeviceListEntry>,   // the paired radios (Bluetooth + USB), from Meshtastic's scanner
    selectedAddress: String,                                   // the radio GroupTrack is set to ("n" = none)
    connected: Boolean,
    onConnectRadio: (com.geeksville.mesh.model.DeviceListEntry) -> Unit,
    onDisconnect: () -> Unit,
    onLocalSettings: () -> Unit,
    onApplyRide: () -> Unit,
    onMeshtastic: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<com.geeksville.mesh.model.DeviceListEntry?>(null) }
    // GRPAWARE-SYNC: re-read Android's paired list every 2 s while open (catches a return from Bluetooth settings).
    var syncTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); syncTick++ } }
    val paired = remember(syncTick) { pairedMacs(ctx) }
    val shown = radios.filter { r -> r !is com.geeksville.mesh.model.DeviceListEntry.Ble || r.address.uppercase() in paired }
    val current = shown.firstOrNull { it.fullAddress == selectedAddress }
    val connecting = !connected && selectedAddress.isNotBlank() && selectedAddress != "n"
    Box(modifier = Modifier.fillMaxSize().background(Color(0x99000000)).clickable { onClose() },
        contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth().padding(20.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(14.dp), color = Color(0xFF12213A)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GRP Awareness", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(when {
                        connected -> "\u25CF Connected" + (current?.let { " \u00b7 " + it.name } ?: "")
                        connecting -> "\u25CC Connecting\u2026"
                        else -> "\u25CF No radio connected"
                    },
                    color = when { connected -> Color(0xFF35C46A); connecting -> Color(0xFFE8C27A); else -> Color(0xFFE0453A) },
                    fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Your radios", color = Color(0xFF8FA3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (shown.isEmpty()) Text("No radios paired with this tablet yet \u2014 tap ADD A RADIO.", color = Color(0xFF8899AA), fontSize = 13.sp)
                shown.forEach { r ->
                    val isThis = r.fullAddress == selectedAddress
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(r.address, color = Color(0xFF8899AA), fontSize = 11.sp)
                        }
                        when {
                            isThis && connected -> TextButton(onClick = { onDisconnect(); message = "" }) { Text("DISCONNECT", color = Color(0xFFF08C84)) }
                            isThis && connecting -> Text("Connecting\u2026", color = Color(0xFFE8C27A), fontSize = 12.sp)
                            else -> TextButton(onClick = { onConnectRadio(r); message = "" }) { Text("CONNECT") }
                        }
                        if (r is com.geeksville.mesh.model.DeviceListEntry.Ble)
                            TextButton(onClick = { forgetting = r }) { Text("FORGET", color = Color(0xFF8899AA)) }
                    }
                }
                if (message.isNotBlank()) Text(message, color = Color(0xFFE8A33D), fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        message = "Pair the radio in Android's Bluetooth settings (its PIN), then come back \u2014 it appears here."
                        openBluetoothSettings(ctx)
                    }) { Text("ADD A RADIO") }
                    TextButton(onClick = {
                        if (hasInternet(ctx)) {
                            if (connected || connecting) message = "The radio must be disconnected to use the web client. " +
                                "Tap DISCONNECT above, then SETTINGS again."
                            else {
                                message = ""
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://client.meshtastic.org"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        } else onLocalSettings()
                    }) { Text("SETTINGS") }
                }
                Text("Settings: experienced mesh operators only.", color = Color(0xFF8899AA), fontSize = 11.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onApplyRide) { Text("APPLY RIDE TO RADIO") }
                    TextButton(onClick = onMeshtastic) { Text("MESHTASTIC") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("CLOSE") }
                }
            }
        }
    }
    // FORGET: from BOTH lists -- the app's saved radio (disconnect if current) and Android's pairing.
    forgetting?.let { r ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget " + r.name + "?") },
            text = { Text("It is disconnected if in use and removed from this tablet's paired radios. You will pair it again " +
                "with ADD A RADIO (the radio's PIN).") },
            confirmButton = { TextButton(onClick = {
                if (r.fullAddress == selectedAddress) onDisconnect()
                val ok = forgetPairing(ctx, r.address)
                message = if (ok) r.name + " is forgotten. ADD A RADIO to pair it again."
                          else "Android needs you to do it: tap " + r.name + " \u2192 Forget, then come back."
                if (!ok) openBluetoothSettings(ctx)
                forgetting = null
            }) { Text("FORGET") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("CANCEL") } },
        )
    }
}
