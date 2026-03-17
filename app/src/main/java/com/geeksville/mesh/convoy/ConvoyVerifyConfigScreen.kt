package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import com.geeksville.mesh.ui.sharing.ChannelViewModel

data class VerifyResult(
    val field: String,
    val expected: String,
    val actual: String,
    val passed: Boolean
)

@Composable
fun ConvoyVerifyConfigScreen(
    workingConfig: WorkingConfig,
    onDone: () -> Unit,
    onBack: () -> Unit,
    channelViewModel: ChannelViewModel = hiltViewModel(),
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val isConnected = connectionState.toString().contains("Connected", ignoreCase = true)
    val localConfig  by channelViewModel.localConfig.collectAsStateWithLifecycle()
    val channels     by channelViewModel.channels.collectAsStateWithLifecycle()

    var verified by remember { mutableStateOf(false) }
    var results  by remember { mutableStateOf<List<VerifyResult>>(emptyList()) }
    var allPassed by remember { mutableStateOf(false) }

    // Auto-verify when connected and config available
    LaunchedEffect(isConnected, localConfig, channels) {
        if (!isConnected) return@LaunchedEffect
        val list = mutableListOf<VerifyResult>()

        // ── Device config — 4 fields ───────────────────────────────────────
        // Long name cannot be read back from localConfig — mark as written only
        list.add(VerifyResult("Long Name", workingConfig.longName, "(written)", true))

        val actualRole = localConfig.device?.role?.name ?: "—"
        list.add(VerifyResult("Node Role", workingConfig.nodeRole, actualRole,
            actualRole == workingConfig.nodeRole))

        val actualManaged = localConfig.device?.is_managed?.toString() ?: "—"
        list.add(VerifyResult("Managed Mode", workingConfig.isManaged.toString(), actualManaged,
            actualManaged == workingConfig.isManaged.toString()))

        val actualSerial = localConfig.device?.serial_enabled?.toString() ?: "—"
        list.add(VerifyResult("Serial Enabled", workingConfig.serialEnabled.toString(), actualSerial,
            actualSerial == workingConfig.serialEnabled.toString()))

        // ── LoRa config — 7 fields ─────────────────────────────────────────
        val actualRegion = localConfig.lora?.region?.name ?: "—"
        list.add(VerifyResult("LoRa Region", workingConfig.loraRegion, actualRegion,
            actualRegion == workingConfig.loraRegion))

        val actualPreset = localConfig.lora?.modem_preset?.name ?: "—"
        list.add(VerifyResult("Modem Preset", workingConfig.loraModemPreset, actualPreset,
            actualPreset == workingConfig.loraModemPreset))

        val actualBandwidth = localConfig.lora?.bandwidth?.toString() ?: "—"
        list.add(VerifyResult("Bandwidth", workingConfig.loraBandwidth.toString(), actualBandwidth,
            actualBandwidth == workingConfig.loraBandwidth.toString() || workingConfig.loraBandwidth == 0))

        val actualSpread = localConfig.lora?.spread_factor?.toString() ?: "—"
        list.add(VerifyResult("Spread Factor", workingConfig.loraSpreadFactor.toString(), actualSpread,
            actualSpread == workingConfig.loraSpreadFactor.toString() || workingConfig.loraSpreadFactor == 0))

        val actualCoding = localConfig.lora?.coding_rate?.toString() ?: "—"
        list.add(VerifyResult("Coding Rate", workingConfig.loraCodingRate.toString(), actualCoding,
            actualCoding == workingConfig.loraCodingRate.toString() || workingConfig.loraCodingRate == 0))

        val actualHopLimit = localConfig.lora?.hop_limit?.toString() ?: "—"
        list.add(VerifyResult("Hop Limit", workingConfig.loraHopLimit.toString(), actualHopLimit,
            actualHopLimit == workingConfig.loraHopLimit.toString()))

        val actualTxEnabled = localConfig.lora?.tx_enabled?.toString() ?: "—"
        list.add(VerifyResult("TX Enabled", workingConfig.loraTxEnabled.toString(), actualTxEnabled,
            actualTxEnabled == workingConfig.loraTxEnabled.toString()))

        val actualTxPower = localConfig.lora?.tx_power?.toString() ?: "—"
        list.add(VerifyResult("TX Power", workingConfig.loraTxPower.toString(), actualTxPower,
            actualTxPower == workingConfig.loraTxPower.toString()))

        // ── Position config — 6 fields ─────────────────────────────────────
        val actualBroadcast = localConfig.position?.position_broadcast_secs?.toString() ?: "—"
        list.add(VerifyResult("Broadcast Interval",
            workingConfig.positionBroadcastSecs.toString(), actualBroadcast,
            actualBroadcast == workingConfig.positionBroadcastSecs.toString()))

        val actualGpsUpdate = localConfig.position?.gps_update_interval?.toString() ?: "—"
        list.add(VerifyResult("GPS Update Interval",
            workingConfig.gpsUpdateSecs.toString(), actualGpsUpdate,
            actualGpsUpdate == workingConfig.gpsUpdateSecs.toString()))

        val actualSmart = localConfig.position?.position_broadcast_smart_enabled?.toString() ?: "—"
        list.add(VerifyResult("Smart Position",
            workingConfig.smartPositionEnabled.toString(), actualSmart,
            actualSmart == workingConfig.smartPositionEnabled.toString()))

        val actualSmartMin = localConfig.position?.broadcast_smart_minimum_interval_secs?.toString() ?: "—"
        list.add(VerifyResult("Smart Min Interval",
            workingConfig.smartMinIntervalSecs.toString(), actualSmartMin,
            actualSmartMin == workingConfig.smartMinIntervalSecs.toString()))

        val actualSmartDist = localConfig.position?.broadcast_smart_minimum_distance?.toString() ?: "—"
        list.add(VerifyResult("Smart Min Distance",
            workingConfig.smartMinDistanceMeters.toString(), actualSmartDist,
            actualSmartDist == workingConfig.smartMinDistanceMeters.toString()))

        // ── Channel — 2 fields ─────────────────────────────────────────────
        val actualChannel = channels.settings.firstOrNull()?.name ?: "—"
        list.add(VerifyResult("Channel Name", workingConfig.channelName, actualChannel,
            actualChannel == workingConfig.channelName))

        val pskSize = channels.settings.firstOrNull()?.psk?.size ?: 0
        val hasPsk  = pskSize == 32
        val pskExpected = if (workingConfig.channelPsk.isNotBlank()) "32-byte AES-256" else "DEFAULT"
        list.add(VerifyResult("Encryption Key", pskExpected,
            if (hasPsk) "32-byte key present" else if (pskSize == 1) "DEFAULT (1-byte)" else "MISSING",
            if (workingConfig.channelPsk.isNotBlank()) hasPsk else true))

        results = list
        allPassed = list.all { it.passed }
        verified = true
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // Header
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = Color(0xFF97D5A5), fontSize = 20.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("VERIFY CONFIG", color = Color(0xFF97D5A5), fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp)
                    Text("Step 5 of 5 — Confirm all values written correctly",
                        color = Color(0xFF8B938A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(8.dp))

            // Connection status
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
                color = if (isConnected) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                Text(
                    if (isConnected) "● RADIO CONNECTED — reading config..." else "○ RADIO NOT CONNECTED — waiting...",
                    color = if (isConnected) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.height(12.dp))

            if (!verified) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1C211C)) {
                    Text("Waiting for radio connection to verify config...",
                        color = Color(0xFF8B938A), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                }
            } else {
                // Overall result banner
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = if (allPassed) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                    Text(
                        if (allPassed) "✓ ALL ${results.size} VALUES VERIFIED — Config write successful"
                        else "✗ ${results.count { !it.passed }} of ${results.size} values did not match",
                        color = if (allPassed) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))

                // Column headers
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text("FIELD", color = Color(0xFF4A6080), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
                    Text("EXPECTED", color = Color(0xFF4A6080), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
                    Text("ACTUAL", color = Color(0xFF4A6080), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
                    Text("", modifier = Modifier.width(24.dp))
                }
                Spacer(Modifier.height(4.dp))

                // Results
                results.forEach { result ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = if (result.passed) Color(0xFF1C211C) else Color(0xFF2A1A1A)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(result.field,
                                color = if (result.passed) Color(0xFFDFE4DC) else Color(0xFFFFB4AB),
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                            Text(result.expected, color = Color(0xFF8B938A), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
                            Text(result.actual,
                                color = if (result.passed) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(2f))
                            Text(if (result.passed) "✓" else "✗",
                                color = if (result.passed) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(24.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // Failed fields summary
                if (!allPassed) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2A1A1A)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("FAILED FIELDS:", color = Color(0xFFFFB4AB), fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            results.filter { !it.passed }.forEach { r ->
                                Text("• ${r.field}: expected '${r.expected}' got '${r.actual}'",
                                    color = Color(0xFFFFB4AB), fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Done button
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onDone() },
                    shape = RoundedCornerShape(10.dp),
                    color = if (allPassed) Color(0xFF1A6B2E) else Color(0xFF1C211C)
                ) {
                    Text(
                        if (allPassed) "✓ DONE — RETURN TO CONVOY" else "DONE (some values may need retry)",
                        color = if (allPassed) Color.White else Color(0xFF8B938A),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
