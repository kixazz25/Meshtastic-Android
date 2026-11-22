package com.grouptrack.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.grouptrack.Gt
import com.geeksville.mesh.grouptrack.GtScheme
import com.grouptrack.comm.CommLink
import com.grouptrack.comm.CommMenuItem
import com.grouptrack.comm.CommMenus
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.Link
import com.grouptrack.comm.RadioEntry
import com.grouptrack.shared.net.GtNetworkGenerator
import kotlinx.coroutines.launch

/**
 * IOSGRP2-2026-11-22: THE iPAD'S GRP AWARENESS -- the anticipated iOS version of
 * GrpAwarenessPanel, built ALONGSIDE Android's rather than replacing it (Fred 13:03), so
 * the two can be put side by side and adjusted ONCE instead of eight times.
 *
 * ⛔ ANDROID IS NOT TOUCHED by this file.
 *
 * ⛔ EVERY FACT COMES FROM THE COMM API (Fred 13:06). Nothing reaches into mesh and
 * nothing reads the OS. The Bluetooth entry is a CommMenus item opened by id -- this panel
 * does not know what the id does.
 *
 * ⛔ NOT TESTABLE ON THE SIMULATOR (Fred 13:09): no Bluetooth there, so the scan answers
 * BLUETOOTH_UNAVAILABLE and the list is empty. That is this panel working correctly.
 *
 * Same layout, words and order as Android's wherever iOS can honestly say the same thing.
 * Each place it cannot is marked ⚠ iOS at the line.
 */

@Composable
fun GtIosGrpAwareness(
    comm: CommLink,
    menus: CommMenus,
    /** CODE RULE 1: null = not read yet -> the record line only, as Android defines it. */
    network: String?,
    onClose: () -> Unit,
) {
    val radios by comm.radios.collectAsState()
    val link by comm.link.collectAsState()
    val health by comm.health.collectAsState()
    val selected by comm.selectedRadioId.collectAsState()
    val scanProblem by comm.scanProblem.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    val offered = remember { menus.items() }

    GtIosGrpAwarenessPanel(
        radios = radios,
        scanProblem = scanProblem,
        selectedAddress = selected ?: "",
        connected = link == Link.CONNECTED,
        connecting = link == Link.CONNECTING,
        healthReason = health.reason,
        network = network,
        message = message,
        menus = offered,
        onMenu = { id ->
            // GroupTrack asks by id. It never names an OS or radio screen.
            message = when (val r = menus.open(id)) {
                is CommResult.Ok -> ""
                is CommResult.Failed -> "That screen could not be opened (" + r.reason + ")."
                is CommResult.NotSupported -> "This iPad does not offer that screen."
                else -> ""
            }
        },
        onConnect = { id ->
            message = "Connecting \u2014 iOS asks for the radio's PIN if it needs it."
            scope.launch {
                message = when (val r = comm.connect(id)) {
                    is CommResult.Ok -> ""
                    is CommResult.Failed -> failWords(r.reason)
                    is CommResult.NotSupported -> "This iPad cannot connect a radio yet."
                    else -> ""
                }
            }
        },
        onDisconnect = { message = ""; scope.launch { comm.disconnect() } },
        onApplyRide = null,   // CODE RULE 1: null = CommConfig.applyRide is NotSupported on iOS (N38)
        onClose = onClose,
        help = {},            // SEAMRULE-2026-11-19: HELP needs GtHelp's link table -- the host's to supply
    )
}

/** GroupTrack owns the words; the comm side owns the reason. Android's failWords, iOS's cases. */
private fun failWords(reason: String): String = when (reason) {
    "BLUETOOTH_UNAVAILABLE" -> "This iPad cannot reach Bluetooth."
    "GROUP_AWARENESS_OFF" -> "Group awareness is off \u2014 turn it on to use a radio."
    "RADIO_NOT_FOUND" -> "That radio is no longer nearby \u2014 turn it on and try again."
    "CONNECT_TIMEOUT" -> "The radio did not answer. Turn it off and on, then try again."
    else -> "The radio could not be connected (" + reason + ")."
}

@Composable
fun GtIosGrpAwarenessPanel(
    radios: List<RadioEntry>,
    /** CODE RULE 1: null = nothing wrong. iOS's only value is BLUETOOTH_UNAVAILABLE. */
    scanProblem: String?,
    selectedAddress: String,
    connected: Boolean,
    connecting: Boolean,
    /** CODE RULE 1: null = talking, nothing to say. The comm side's own word. */
    healthReason: String?,
    /** CODE RULE 1: null = not read yet -> the record line only. */
    network: String?,
    message: String,
    /** The comm side's screens. Android's rule: a button shows only when one is offered. */
    menus: List<CommMenuItem>,
    onMenu: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    /** CODE RULE 1: null = this platform cannot apply a ride yet, so no button is drawn. */
    onApplyRide: (() -> Unit)?,
    onClose: () -> Unit,
    help: @Composable () -> Unit = {},
) {
    val busy = connected || connecting
    val current = radios.firstOrNull { it.id == selectedAddress }
    val dim = Gt.dim
    Box(modifier = Modifier.fillMaxSize().background(Gt.panel), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp), color = Gt.panel) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "GRP Awareness", color = if (connected) Gt.accent else Gt.bad, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    help()
                    TextButton(onClick = onClose) { Text("\u2715", color = Gt.accent, fontSize = 18.sp) }
                }

                Surface(shape = RoundedCornerShape(10.dp), color = Gt.text.copy(alpha = 0.18f)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (connected) "\u25cf CONNECTED TO" else if (busy) "\u25cc CONNECTING TO" else "CONNECTED TO",
                                color = if (busy) Gt.good else Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                            if (busy) {
                                Text(current?.name ?: "your radio", color = Gt.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                // RADIONET-2026-10-12: set up for GroupTrack = the RADIO is on a "Group..." network.
                                // GtNetworkGenerator.CHANNEL_PREFIX is the SHARED constant; Android reads an app-side copy of
                                // the same value, which is a duplicate worth a card, not a difference in behaviour.
                                if (connected && network != null) {
                                    val shown = network.ifBlank { "the radio's default" }
                                    if (network.startsWith(GtNetworkGenerator.CHANNEL_PREFIX)) {
                                        Text("GroupTrack network " + shown, color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("Not set up for GroupTrack (network " + shown + ")", color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                // ⚠ iOS: no "Last applied" line. appliedHistory is empty and applyRide is
                                // NotSupported, so nothing has ever been applied FROM this iPad. Android's own
                                // words for that state are used rather than inventing new ones.
                                if (connected) Text("Not applied from this iPad before", color = Gt.accent, fontSize = 12.sp)
                                if (healthReason != null) Text(healthReason, color = Gt.warn, fontSize = 12.sp)
                            }
                        }
                        if (busy) {
                            TextButton(onClick = onDisconnect) { Text("DISCONNECT", color = Gt.bad, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                Text("AVAILABLE RADIOS", color = Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (!busy) {
                    Text("Select a radio from the list to connect.", color = Gt.warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("One radio at a time \u00b7 DISCONNECT the one above to connect another.", color = dim, fontSize = 12.sp)
                }

                // ⚠ iOS: the scan reason is NAMED. Android branches on BLUETOOTH_OFF and PERMISSION_MISSING
                // -- its two values -- then `else -> {}`. iOS's only value is BLUETOOTH_UNAVAILABLE, which
                // would fall into that else and tell the rider nothing. Unknown values print rather than
                // vanish: no hidden errors.
                if (scanProblem != null) {
                    Text(
                        if (scanProblem == "BLUETOOTH_UNAVAILABLE") {
                            "Bluetooth is not available to GroupTrack, so no radios can be found."
                        } else {
                            "The radio scan stopped (" + scanProblem + ")."
                        },
                        color = Gt.warn, fontSize = 13.sp,
                    )
                }

                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    val rows = radios.filter { !(it.id == selectedAddress && busy) }
                    if (rows.isEmpty() && scanProblem == null) {
                        Text("Looking for radios nearby\u2026", color = dim, fontSize = 12.sp)
                    }
                    rows.forEach { radio ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(radio.name, color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                // ⚠ iOS: the id WHOLE -- Android's .drop(1) strips its "x" transport prefix and
                                // would eat a UUID's first character. And no "NEW TO THIS ANDROID": paired is
                                // false for every entry by design, so claiming either way would be a guess.
                                Text(radio.id, color = dim, fontSize = 12.sp)
                            }
                            // ⚠ iOS: always CONNECT, never PAIR -- connecting is what raises iOS's own pairing
                            // sheet, so one label is the truth for both cases.
                            TextButton(enabled = !busy, onClick = { onConnect(radio.id) }) { Text("CONNECT") }
                            // ⚠ iOS: NO FORGET (Fred 12:56). GtIosCommLink.forget() answers NotSupported --
                            // the bond is iOS's. Forgetting is on the iOS Bluetooth menu entry below.
                        }
                    }
                }

                if (message.isNotBlank()) Text(message, color = Gt.warn, fontSize = 13.sp)

                // The comm side's screens, and GroupTrack's own apply. Fred 13:08: the iOS Bluetooth panel
                // is reached FROM OUR MENU, so it arrives here as a CommMenus item like any other -- this
                // panel does not know which id means Bluetooth.
                if (menus.isNotEmpty() || onApplyRide != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (onApplyRide != null) {
                            Surface(
                                modifier = Modifier.weight(1f).clickable { onApplyRide() },
                                shape = RoundedCornerShape(8.dp), color = Gt.button,
                                border = BorderStroke(1.dp, GtScheme.current.value.edge),
                            ) {
                                Text(
                                    "SELECT A RIDE AND APPLY SETTINGS TO THE RADIO", color = Gt.accent,
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                        menus.forEach { item ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable { onMenu(item.id) },
                                shape = RoundedCornerShape(8.dp), color = Gt.button,
                                border = BorderStroke(1.dp, GtScheme.current.value.edge),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(item.label.uppercase(), color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("pairing, forgetting and Bluetooth itself", color = Gt.dim, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
