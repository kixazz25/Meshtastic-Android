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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geeksville.mesh.model.UIViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ConvoyReconnectWaitScreen
 *
 * Single-stage reconnect wait after binary radio config install.
 *
 * Flow:
 *   1. 60s forced countdown — waits for radio reboot after binary install
 *   2. Auto BT disconnect at i==15, reconnect at i==12
 *   3. On reconnect (auto or manual) — proceed directly to verify
 *
 * Two-stage channel/PSK write removed April 2026 -- V2.4.1 sprint.
 * Binary install alone tested as sufficient for 2.7 firmware.
 * Full two-stage implementation preserved in git history.
 * See commit prior to V2.4.1 tag if second write needs restoration.
 *
 * Back navigation blocked -- CANCEL is only exit.
 */
@Composable
fun ConvoyReconnectWaitScreen(
    onProceed: () -> Unit,
    onCancel:  () -> Unit,
    uiViewModel:   UIViewModel   = hiltViewModel(),
    convoyViewModel: ConvoyViewModel = hiltViewModel()
) {
    val scope           = rememberCoroutineScope()
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val rawConnected    = connectionState.toString().contains("Connected", ignoreCase = true)

    var countdown  by remember { mutableStateOf(60) }
    var phase      by remember { mutableStateOf("WAITING") }
    var statusMsg  by remember { mutableStateOf("Waiting for radio reboot after binary install...") }
    var logLines   by remember { mutableStateOf(listOf<String>()) }

    val savedDeviceAddress = remember { uiViewModel.getDeviceAddress() ?: "" }

    fun addLog(msg: String) {
        android.util.Log.i("ConvoyReconnect", msg)
        logLines = logLines + msg
    }

    // 60s countdown + auto BT cycle + auto-proceed to verify
    LaunchedEffect(Unit) {
        addLog("Waiting 60s for binary install reboot...")

        for (i in 60 downTo 1) {
            countdown = i
            statusMsg = "◌ Binary install reboot — please wait... ${i}s"
            // Auto BT disconnect/reconnect to ensure clean radio connection
            if (i == 15) {
                addLog("Auto BT disconnect...")
                uiViewModel.setDeviceAddress("n")
            }
            if (i == 12) {
                addLog("Auto BT reconnect...")
                uiViewModel.setDeviceAddress(savedDeviceAddress)
            }
            delay(1000)
        }
        countdown = 0
        addLog("60s wait complete")

        if (rawConnected) {
            // Connected after auto BT cycle — proceed to verify
            phase = "CONNECTED"
            statusMsg = "● Radio reconnected — proceeding to verify..."
            addLog("Connected ✓ -- proceeding to verify")
            delay(1500)
            scope.launch { onProceed() }
            return@LaunchedEffect
        }

        // Not reconnected after auto cycle -- show manual BT toggle instruction
        phase = "BT_MANUAL"
        statusMsg = "◌ Not connected — toggle Bluetooth OFF then ON in Android settings"
        addLog("Waiting for manual BT toggle...")

        // Wait up to 60s for manual reconnect
        for (i in 60 downTo 1) {
            if (rawConnected) {
                phase = "CONNECTED"
                statusMsg = "● Radio reconnected — proceeding to verify..."
                addLog("Connected after BT toggle ✓ -- proceeding to verify")
                delay(1500)
                scope.launch { onProceed() }
                return@LaunchedEffect
            }
            delay(1000)
        }

        // Still not connected after manual wait
        phase = "FAILED"
        statusMsg = "✗ Radio did not reconnect. Check radio and Bluetooth, then CANCEL and retry."
        addLog("FAILED -- radio did not reconnect")
    }

    // Catch reconnect during BT_MANUAL or FAILED state
    LaunchedEffect(rawConnected) {
        if (rawConnected && (phase == "BT_MANUAL" || phase == "FAILED")) {
            phase = "CONNECTED"
            statusMsg = "● Radio reconnected — proceeding to verify..."
            addLog("Reconnected during manual wait ✓ -- proceeding to verify")
            delay(1500)
            scope.launch { onProceed() }
        }
    }

    // Block back navigation
    BackHandler(enabled = true) { }

    // ── UI ────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status icon
            Text(
                text = when (phase) {
                    "CONNECTED"  -> "●"
                    "FAILED"     -> "✗"
                    "BT_MANUAL"  -> "⚠"
                    else         -> "◌"
                },
                fontSize = 48.sp,
                color = when (phase) {
                    "CONNECTED" -> Color(0xFF97D5A5)
                    "FAILED"    -> Color(0xFFFFB4AB)
                    "BT_MANUAL" -> Color(0xFFFFB74D)
                    else        -> Color(0xFFFFB74D)
                }
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "RECONNECT CHECK",
                color = Color(0xFF97D5A5), fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(8.dp))

            // Status message
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = when (phase) {
                    "CONNECTED" -> Color(0xFF0D2010)
                    "FAILED"    -> Color(0xFF2A1A1A)
                    "BT_MANUAL" -> Color(0xFF2A1A08)
                    else        -> Color(0xFF1A1A0D)
                }
            ) {
                Text(
                    text = statusMsg,
                    color = when (phase) {
                        "CONNECTED" -> Color(0xFF97D5A5)
                        "FAILED"    -> Color(0xFFFFB4AB)
                        "BT_MANUAL" -> Color(0xFFFFB74D)
                        else        -> Color(0xFFFFB74D)
                    },
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Manual BT toggle instructions
            if (phase == "BT_MANUAL") {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF2A1A08)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "⚠ Manual Bluetooth Toggle Required",
                            color = Color(0xFFFFB74D), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("1. Pull down notification shade",
                            color = Color(0xFFFFB74D), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Text("2. Tap Bluetooth OFF",
                            color = Color(0xFFFFB74D), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Text("3. Wait 3 seconds",
                            color = Color(0xFFFFB74D), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Text("4. Tap Bluetooth ON",
                            color = Color(0xFFFFB74D), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Text("Will proceed to verify automatically on reconnect.",
                            color = Color(0xFF8B938A), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Countdown
            if (countdown > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "$countdown",
                    color = Color(0xFF8B938A), fontSize = 36.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            }

            // Log panel
            if (logLines.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0D1A0D)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        logLines.takeLast(6).forEach { line ->
                            Text(
                                text = line,
                                color = Color(0xFF97D5A5),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // CANCEL -- always available
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onCancel() },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2A1A1A)
            ) {
                Text(
                    text = "CANCEL",
                    color = Color(0xFFFFB4AB), fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}
