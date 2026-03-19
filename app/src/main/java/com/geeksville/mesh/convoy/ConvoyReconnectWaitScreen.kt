package com.geeksville.mesh.convoy

import android.util.Base64
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.ui.sharing.ChannelViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings

/**
 * ConvoyReconnectWaitScreen — Two-stage gated reconnect
 *
 * STAGE 1: Wait for binary install reboot
 *   - 60s forced countdown
 *   - BT toggle if not reconnected
 *   - PROCEED TO WRITE CHANNEL — hard button, user confirms connected
 *   - User taps → setChannels() fires → radio reboots
 *
 * STAGE 2: Wait for channel write reboot
 *   - 60s forced countdown
 *   - BT toggle if not reconnected
 *   - PROCEED TO VERIFY — hard button, user confirms connected
 *   - User taps → navigate to Verify
 *
 * Back navigation blocked — CANCEL is only exit.
 */
@Composable
fun ConvoyReconnectWaitScreen(
    onProceed: () -> Unit,
    onCancel: () -> Unit,
    uiViewModel: UIViewModel = hiltViewModel(),
    convoyViewModel: ConvoyViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel()
) {
    val context         = LocalContext.current
    val scope           = rememberCoroutineScope()
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val rawConnected    = connectionState.toString().contains("Connected", ignoreCase = true)

    // Stage 1 gate — binary install reboot
    var stage1GatePassed  by remember { mutableStateOf(false) }
    // Stage 2 gate — channel write reboot
    var stage2GatePassed  by remember { mutableStateOf(false) }
    // Channel write done flag
    var channelWriteDone  by remember { mutableStateOf(false) }

    val stage1Connected   = rawConnected && stage1GatePassed
    val stage2Connected   = rawConnected && stage2GatePassed

    var countdown    by remember { mutableStateOf(60) }
    var stage        by remember { mutableStateOf(1) }      // 1 or 2
    var phase        by remember { mutableStateOf("WAITING") }
    var statusMsg    by remember { mutableStateOf("Waiting for radio reboot after binary install...") }
    var logLines     by remember { mutableStateOf(listOf<String>()) }

    fun addLog(msg: String) {
        Log.i("ConvoyReconnect", msg)
        logLines = logLines + msg
    }

    // Stage 1 sequence — binary install reboot wait
    LaunchedEffect(Unit) {
        stage = 1
        phase = "WAITING"
        addLog("Stage 1: Waiting 60s for binary install reboot...")

        for (i in 60 downTo 1) {
            countdown = i
            statusMsg = "\u25cc Binary install reboot — please wait... ${i}s"
            delay(1000)
        }
        countdown = 0
        stage1GatePassed = true
        addLog("Stage 1: 60s wait complete")

        if (rawConnected) {
            phase = "CONNECTED"
            statusMsg = "\u25cf Radio reconnected — tap WRITE CHANNEL to continue"
            addLog("Stage 1: Connected \u2713")
            return@LaunchedEffect
        }

        // BT toggle
        phase = "BT_TOGGLE"
        statusMsg = "\u25cc Not connected — toggling Bluetooth..."
        addLog("Stage 1: BT toggle...")
        uiViewModel.reconnectDevice(context)
        delay(3000)

        phase = "RECONNECTING"
        for (i in 30 downTo 1) {
            if (rawConnected) {
                phase = "CONNECTED"
                statusMsg = "\u25cf Radio reconnected — tap WRITE CHANNEL to continue"
                addLog("Stage 1: Connected after BT toggle \u2713")
                return@LaunchedEffect
            }
            statusMsg = "\u25cc Reconnecting... ${i}s"
            delay(1000)
        }

        if (!rawConnected) {
            phase = "FAILED"
            statusMsg = "\u2717 Radio did not reconnect. Check radio and retry."
            addLog("Stage 1: FAILED")
        } else {
            phase = "CONNECTED"
            statusMsg = "\u25cf Radio reconnected — tap WRITE CHANNEL to continue"
            addLog("Stage 1: Connected \u2713")
        }
    }

    // Catch reconnect during stage 1 BT toggle
    LaunchedEffect(rawConnected, stage1GatePassed) {
        if (rawConnected && stage1GatePassed && !channelWriteDone &&
            (phase == "RECONNECTING" || phase == "BT_TOGGLE")) {
            phase = "CONNECTED"
            statusMsg = "\u25cf Radio reconnected — tap WRITE CHANNEL to continue"
            Log.i("ConvoyReconnect", "Stage 1: Reconnected during BT toggle")
        }
    }

    // Catch reconnect during stage 2 BT toggle
    LaunchedEffect(rawConnected, stage2GatePassed) {
        if (rawConnected && stage2GatePassed &&
            (phase == "RECONNECTING_2" || phase == "BT_TOGGLE_2")) {
            phase = "CONNECTED_2"
            statusMsg = "\u25cf Radio reconnected — tap PROCEED TO VERIFY"
            Log.i("ConvoyReconnect", "Stage 2: Reconnected during BT toggle")
        }
    }

    // Block back navigation
    BackHandler(enabled = true) { }

    // WRITE CHANNEL action — triggered by user tap
    fun writeChannel() {
        scope.launch {
            val wconfig = convoyViewModel.workingConfig.value
            if (wconfig == null) {
                addLog("\u2717 No WorkingConfig — cannot write channel")
                return@launch
            }

            // Write channel
            phase = "WRITING_CHANNEL"
            statusMsg = "\u25cc Writing channel + PSK..."
            addLog("Stage 1 PROCEED: Writing channel ${wconfig.channelName}...")
            try {
                val pskBytes = okio.ByteString.of(
                    *Base64.decode(wconfig.channelPsk, Base64.DEFAULT)
                )
                val chSettings = ChannelSettings(
                    name             = wconfig.channelName,
                    psk              = pskBytes,
                    uplink_enabled   = wconfig.channelUplinkEnabled,
                    downlink_enabled = wconfig.channelDownlinkEnabled
                )
                channelViewModel.setChannels(ChannelSet(settings = listOf(chSettings)))
                channelWriteDone = true
                addLog("\u2713 Channel written: ${wconfig.channelName} — radio rebooting")
                statusMsg = "\u2713 Channel written — waiting for reboot..."
            } catch (e: Exception) {
                addLog("\u2717 Channel write failed: ${e.message}")
                statusMsg = "\u2717 Channel write failed"
                return@launch
            }

            delay(1000)

            // Stage 2: Wait 60s for channel write reboot
            stage = 2
            phase = "WAITING_2"
            addLog("Stage 2: Waiting 60s for channel write reboot...")
            for (i in 60 downTo 1) {
                countdown = i
                statusMsg = "\u25cc Channel write reboot — please wait... ${i}s"
                delay(1000)
            }
            countdown = 0
            stage2GatePassed = true
            addLog("Stage 2: 60s wait complete")

            if (rawConnected) {
                phase = "CONNECTED_2"
                statusMsg = "\u25cf Radio reconnected — tap PROCEED TO VERIFY"
                addLog("Stage 2: Connected \u2713")
                return@launch
            }

            // BT toggle stage 2
            phase = "BT_TOGGLE_2"
            statusMsg = "\u25cc Not connected — toggling Bluetooth..."
            addLog("Stage 2: BT toggle...")
            uiViewModel.reconnectDevice(context)
            delay(3000)

            phase = "RECONNECTING_2"
            for (i in 30 downTo 1) {
                if (rawConnected) {
                    phase = "CONNECTED_2"
                    statusMsg = "\u25cf Radio reconnected — tap PROCEED TO VERIFY"
                    addLog("Stage 2: Connected after BT toggle \u2713")
                    return@launch
                }
                statusMsg = "\u25cc Reconnecting... ${i}s"
                delay(1000)
            }

            if (!rawConnected) {
                phase = "FAILED"
                statusMsg = "\u2717 Radio did not reconnect. Check radio and retry."
                addLog("Stage 2: FAILED")
            } else {
                phase = "CONNECTED_2"
                statusMsg = "\u25cf Radio reconnected — tap PROCEED TO VERIFY"
                addLog("Stage 2: Connected \u2713")
            }
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Stage indicator
            Text(
                text = "STAGE ${stage} OF 2",
                color = Color(0xFF4A6080),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))

            // Status icon
            Text(
                text = when (phase) {
                    "CONNECTED", "CONNECTED_2" -> "\u25cf"
                    "FAILED"                   -> "\u2717"
                    "WRITING_CHANNEL"          -> "\u270e"
                    else                       -> "\u25cc"
                },
                fontSize = 48.sp,
                color = when (phase) {
                    "CONNECTED", "CONNECTED_2" -> Color(0xFF97D5A5)
                    "FAILED"                   -> Color(0xFFFFB4AB)
                    "WRITING_CHANNEL"          -> Color(0xFFF9C835)
                    else                       -> Color(0xFFFFB74D)
                }
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text          = "RECONNECT CHECK",
                color         = Color(0xFF97D5A5),
                fontSize      = 14.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(8.dp))

            // Status message
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                color    = when (phase) {
                    "CONNECTED", "CONNECTED_2" -> Color(0xFF0D2010)
                    "FAILED"                   -> Color(0xFF2A1A1A)
                    "WRITING_CHANNEL"          -> Color(0xFF1A1A08)
                    else                       -> Color(0xFF1A1A0D)
                }
            ) {
                Text(
                    text       = statusMsg,
                    color      = when (phase) {
                        "CONNECTED", "CONNECTED_2" -> Color(0xFF97D5A5)
                        "FAILED"                   -> Color(0xFFFFB4AB)
                        "WRITING_CHANNEL"          -> Color(0xFFF9C835)
                        else                       -> Color(0xFFFFB74D)
                    },
                    fontSize   = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(16.dp)
                )
            }

            // Countdown
            if (countdown > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text       = "$countdown",
                    color      = Color(0xFF8B938A),
                    fontSize   = 36.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            // Log panel
            if (logLines.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(8.dp),
                    color    = Color(0xFF0D1A0D)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        logLines.takeLast(6).forEach { line ->
                            Text(
                                text       = line,
                                color      = Color(0xFF97D5A5),
                                fontSize   = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Stage 1 PROCEED button — write channel
            if (!channelWriteDone) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = stage1Connected) { writeChannel() },
                    shape = RoundedCornerShape(12.dp),
                    color = if (stage1Connected) Color(0xFF1F4E79) else Color(0xFF1C211C)
                ) {
                    Text(
                        text       = if (stage1Connected) "\u270e WRITE CHANNEL + PSK"
                                     else if (!stage1GatePassed) "WAITING FOR REBOOT..."
                                     else "WAITING FOR RADIO...",
                        color      = if (stage1Connected) Color.White else Color(0xFF8B938A),
                        fontSize   = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.padding(vertical = 16.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Stage 2 PROCEED button — go to verify
            if (channelWriteDone) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = stage2Connected) { onProceed() },
                    shape = RoundedCornerShape(12.dp),
                    color = if (stage2Connected) Color(0xFF15512C) else Color(0xFF1C211C)
                ) {
                    Text(
                        text       = if (stage2Connected) "\u25cf PROCEED TO VERIFY"
                                     else if (!stage2GatePassed) "WAITING FOR REBOOT..."
                                     else "WAITING FOR RADIO...",
                        color      = if (stage2Connected) Color(0xFF97D5A5) else Color(0xFF8B938A),
                        fontSize   = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.padding(vertical = 16.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // CANCEL — always available
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCancel() },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2A1A1A)
            ) {
                Text(
                    text       = "CANCEL",
                    color      = Color(0xFFFFB4AB),
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}
