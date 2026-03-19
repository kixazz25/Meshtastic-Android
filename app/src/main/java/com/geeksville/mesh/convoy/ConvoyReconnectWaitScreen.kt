package com.geeksville.mesh.convoy

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
import kotlinx.coroutines.delay

/**
 * ConvoyReconnectWaitScreen — reusable reconnect gate before Verify
 *
 * Sequence:
 *   1. Monitor connection state
 *   2. If not connected after 10 seconds → BT toggle (off, wait 3s, on)
 *   3. Monitor reconnect after BT toggle
 *   4. If still not connected → show error
 *   5. PROCEED TO VERIFY button — enabled only when connected (hard button, user confirms)
 *   6. CANCEL button always available
 */
@Composable
fun ConvoyReconnectWaitScreen(
    onProceed: () -> Unit,
    onCancel: () -> Unit,
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val context         = LocalContext.current
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val isConnected     = connectionState.toString().contains("Connected", ignoreCase = true)

    var countdown        by remember { mutableStateOf(10) }
    var phase            by remember { mutableStateOf("WAITING") } // WAITING, BT_TOGGLE, RECONNECTING, CONNECTED, FAILED
    var statusMsg        by remember { mutableStateOf("Waiting for radio to reconnect after reboot...") }
    var wasConnected     by remember { mutableStateOf(false) }

    // Detect reconnect
    LaunchedEffect(isConnected) {
        if (isConnected && phase != "CONNECTED") {
            phase = "CONNECTED"
            statusMsg = "\u25cf Radio reconnected — ready to verify"
            countdown = 0
        }
        wasConnected = isConnected
    }

    // Main reconnect sequence
    LaunchedEffect(Unit) {
        if (isConnected) {
            phase = "CONNECTED"
            statusMsg = "\u25cf Already connected — ready to verify"
            return@LaunchedEffect
        }

        // Phase 1: Count down 10 seconds
        phase = "WAITING"
        for (i in 10 downTo 1) {
            if (isConnected) return@LaunchedEffect
            countdown = i
            statusMsg = "\u25cc Waiting for radio... ${i}s"
            delay(1000)
        }

        if (isConnected) return@LaunchedEffect

        // Phase 2: BT toggle
        phase = "BT_TOGGLE"
        statusMsg = "\u25cc Not connected — toggling Bluetooth..."
        uiViewModel.reconnectDevice(context)

        // Phase 3: Wait 3 seconds after BT toggle
        delay(3000)
        phase = "RECONNECTING"
        statusMsg = "\u25cc Bluetooth toggled — waiting for reconnect..."

        // Phase 4: Wait up to 15 more seconds
        for (i in 15 downTo 1) {
            if (isConnected) return@LaunchedEffect
            statusMsg = "\u25cc Reconnecting... ${i}s"
            delay(1000)
        }

        if (!isConnected) {
            phase = "FAILED"
            statusMsg = "\u2717 Radio did not reconnect. Check radio and retry."
        }
    }

    // Block back navigation — CANCEL is the only exit
    BackHandler(enabled = true) {
        // Intentionally blocked — user must use CANCEL button
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101510))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon
            Text(
                text = when (phase) {
                    "CONNECTED" -> "\u25cf"
                    "FAILED"    -> "\u2717"
                    else        -> "\u25cc"
                },
                fontSize  = 48.sp,
                color     = when (phase) {
                    "CONNECTED" -> Color(0xFF97D5A5)
                    "FAILED"    -> Color(0xFFFFB4AB)
                    else        -> Color(0xFFFFB74D)
                }
            )
            Spacer(Modifier.height(24.dp))

            // Title
            Text(
                text          = "RECONNECT CHECK",
                color         = Color(0xFF97D5A5),
                fontSize      = 14.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(8.dp))

            // Status
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                color    = when (phase) {
                    "CONNECTED" -> Color(0xFF0D2010)
                    "FAILED"    -> Color(0xFF2A1A1A)
                    else        -> Color(0xFF1A1A0D)
                }
            ) {
                Text(
                    text       = statusMsg,
                    color      = when (phase) {
                        "CONNECTED" -> Color(0xFF97D5A5)
                        "FAILED"    -> Color(0xFFFFB4AB)
                        else        -> Color(0xFFFFB74D)
                    },
                    fontSize   = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(16.dp)
                )
            }

            if (countdown > 0 && phase == "WAITING") {
                Spacer(Modifier.height(8.dp))
                Text(
                    text       = "$countdown",
                    color      = Color(0xFF8B938A),
                    fontSize   = 36.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(32.dp))

            // PROCEED button — only enabled when connected
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isConnected) { onProceed() },
                shape = RoundedCornerShape(12.dp),
                color = if (isConnected) Color(0xFF15512C) else Color(0xFF1C211C)
            ) {
                Text(
                    text       = if (isConnected) "\u25cf PROCEED TO VERIFY" else "WAITING FOR RADIO...",
                    color      = if (isConnected) Color(0xFF97D5A5) else Color(0xFF8B938A),
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 16.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // CANCEL button — always available
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
