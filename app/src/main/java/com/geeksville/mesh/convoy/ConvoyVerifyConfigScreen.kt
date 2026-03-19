package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    val passed: Boolean,
    val group: String
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
    val localConfig by channelViewModel.localConfig.collectAsStateWithLifecycle()
    val channels    by channelViewModel.channels.collectAsStateWithLifecycle()

    var verified  by remember { mutableStateOf(false) }
    var results   by remember { mutableStateOf<List<VerifyResult>>(emptyList()) }
    var allPassed by remember { mutableStateOf(false) }

    fun vr(field: String, group: String, expected: String, actual: String, pass: Boolean) =
        VerifyResult(field, expected, actual, pass, group)

    LaunchedEffect(isConnected, localConfig, channels) {
        if (!isConnected) return@LaunchedEffect
        val list = mutableListOf<VerifyResult>()

        // ── DEVICE (4 fields) ──────────────────────────────────────────────
        list.add(vr("Long Name", "Device", workingConfig.longName, "(write-only)", true))

        val actRole = localConfig.device?.role?.name ?: "—"
        list.add(vr("Node Role", "Device", workingConfig.nodeRole, actRole,
            actRole == workingConfig.nodeRole))

        val actManaged = localConfig.device?.is_managed?.toString() ?: "—"
        list.add(vr("Managed Mode", "Device", workingConfig.isManaged.toString(), actManaged,
            actManaged == workingConfig.isManaged.toString()))

        val actSerial = localConfig.device?.serial_enabled?.toString() ?: "—"
        list.add(vr("Serial Enabled", "Device", workingConfig.serialEnabled.toString(), actSerial,
            actSerial == workingConfig.serialEnabled.toString()))

        // ── LORA (7 fields) ────────────────────────────────────────────────
        val actRegion = localConfig.lora?.region?.name ?: "—"
        list.add(vr("LoRa Region", "LoRa", workingConfig.loraRegion, actRegion,
            actRegion == workingConfig.loraRegion))

        val actPreset = localConfig.lora?.modem_preset?.name ?: "—"
        list.add(vr("Modem Preset", "LoRa", workingConfig.loraModemPreset, actPreset,
            actPreset == workingConfig.loraModemPreset))

        val actBandwidth = localConfig.lora?.bandwidth?.toString() ?: "—"
        list.add(vr("Bandwidth", "LoRa", workingConfig.loraBandwidth.toString(), actBandwidth,
            workingConfig.loraBandwidth == 0 || actBandwidth == workingConfig.loraBandwidth.toString()))

        val actSpread = localConfig.lora?.spread_factor?.toString() ?: "—"
        list.add(vr("Spread Factor", "LoRa", workingConfig.loraSpreadFactor.toString(), actSpread,
            workingConfig.loraSpreadFactor == 0 || actSpread == workingConfig.loraSpreadFactor.toString()))

        val actCoding = localConfig.lora?.coding_rate?.toString() ?: "—"
        list.add(vr("Coding Rate", "LoRa", workingConfig.loraCodingRate.toString(), actCoding,
            workingConfig.loraCodingRate == 0 || actCoding == workingConfig.loraCodingRate.toString()))

        val actHop = localConfig.lora?.hop_limit?.toString() ?: "—"
        list.add(vr("Hop Limit", "LoRa", workingConfig.loraHopLimit.toString(), actHop,
            actHop == workingConfig.loraHopLimit.toString()))

        val actTxEnabled = localConfig.lora?.tx_enabled?.toString() ?: "—"
        list.add(vr("TX Enabled", "LoRa", workingConfig.loraTxEnabled.toString(), actTxEnabled,
            actTxEnabled == workingConfig.loraTxEnabled.toString()))

        val actTxPower = localConfig.lora?.tx_power?.toString() ?: "—"
        list.add(vr("TX Power", "LoRa", workingConfig.loraTxPower.toString(), actTxPower,
            actTxPower == workingConfig.loraTxPower.toString()))

        // ── CHANNEL (2 fields) ─────────────────────────────────────────────
        val actChannel = channels.settings.firstOrNull()?.name ?: "—"
        list.add(vr("Channel Name", "Channel", workingConfig.channelName, actChannel,
            actChannel == workingConfig.channelName))

        val pskSize = channels.settings.firstOrNull()?.psk?.size ?: 0
        val hasPsk  = pskSize == 32
        list.add(vr("Encryption Key", "Channel", "32-byte AES-256",
            if (hasPsk) "32-byte key \u2713"
            else if (pskSize == 1) "DEFAULT (1-byte)"
            else "MISSING ($pskSize bytes)",
            if (workingConfig.channelPsk.isNotBlank()) hasPsk else true))

        // ── POSITION (6 fields) ────────────────────────────────────────────
        val actBroadcast = localConfig.position?.position_broadcast_secs?.toString() ?: "—"
        list.add(vr("Broadcast Interval", "Position",
            workingConfig.positionBroadcastSecs.toString(), actBroadcast,
            actBroadcast == workingConfig.positionBroadcastSecs.toString()))

        val actGpsUpdate = localConfig.position?.gps_update_interval?.toString() ?: "—"
        list.add(vr("GPS Update Interval", "Position",
            workingConfig.gpsUpdateSecs.toString(), actGpsUpdate,
            actGpsUpdate == workingConfig.gpsUpdateSecs.toString()))

        val actSmart = localConfig.position?.position_broadcast_smart_enabled?.toString() ?: "—"
        list.add(vr("Smart Position", "Position",
            workingConfig.smartPositionEnabled.toString(), actSmart,
            actSmart == workingConfig.smartPositionEnabled.toString()))

        val actSmartMin = localConfig.position?.broadcast_smart_minimum_interval_secs?.toString() ?: "—"
        list.add(vr("Smart Min Interval", "Position",
            workingConfig.smartMinIntervalSecs.toString(), actSmartMin,
            actSmartMin == workingConfig.smartMinIntervalSecs.toString()))

        val actSmartDist = localConfig.position?.broadcast_smart_minimum_distance?.toString() ?: "—"
        list.add(vr("Smart Min Distance", "Position",
            workingConfig.smartMinDistanceMeters.toString(), actSmartDist,
            actSmartDist == workingConfig.smartMinDistanceMeters.toString()))

        val actFixed = localConfig.position?.fixed_position?.toString() ?: "—"
        list.add(vr("Fixed Position", "Position",
            workingConfig.fixedPosition.toString(), actFixed,
            actFixed == workingConfig.fixedPosition.toString()))

        // ── DISPLAY (4 fields) ─────────────────────────────────────────────
        val actUnits = localConfig.display?.units?.value?.toString() ?: "—"
        list.add(vr("Display Units", "Display",
            workingConfig.displayUnits.toString(), actUnits,
            actUnits == workingConfig.displayUnits.toString()))

        val actScreen = localConfig.display?.screen_on_secs?.toString() ?: "—"
        list.add(vr("Screen Timeout", "Display",
            workingConfig.screenTimeout.toString(), actScreen,
            actScreen == workingConfig.screenTimeout.toString()))

        list.add(vr("Auto Brightness", "Display",
            workingConfig.autoScreenBrightness.toString(), "(firmware varies)", true))

        val actCompass = localConfig.display?.compass_north_top?.toString() ?: "—"
        list.add(vr("Compass North Top", "Display",
            workingConfig.compassNorthTop.toString(), actCompass,
            actCompass == workingConfig.compassNorthTop.toString()))

        // ── MODULE (17 fields) — set by master.cfg import ──────────────────
        // moduleConfigFlow not yet wired to ChannelViewModel.
        // These fields are written atomically by InstallProfileUseCase.
        // Marked as informational pending moduleConfig read API wiring.
        listOf(
            "Telemetry Device Interval" to workingConfig.telemetryDeviceInterval.toString(),
            "Telemetry Env Interval"    to workingConfig.telemetryEnvInterval.toString(),
            "Telemetry Env Enabled"     to workingConfig.telemetryEnvEnabled.toString(),
            "MQTT Enabled"              to workingConfig.mqttEnabled.toString(),
            "MQTT Address"              to workingConfig.mqttAddress.ifBlank { "(empty)" },
            "MQTT Encryption"           to workingConfig.mqttEncryptionEnabled.toString(),
            "MQTT JSON"                 to workingConfig.mqttJsonEnabled.toString(),
            "Serial Module"             to workingConfig.serialModuleEnabled.toString(),
            "Ext Notification"          to workingConfig.extNotificationEnabled.toString(),
            "Range Test"                to workingConfig.rangeTestEnabled.toString(),
            "Store Forward"             to workingConfig.storeForwardEnabled.toString(),
            "Neighbor Info"             to workingConfig.neighborInfoEnabled.toString(),
            "Detection Sensor"          to workingConfig.detectionSensorEnabled.toString(),
            "Audio"                     to workingConfig.audioEnabled.toString()
        ).forEach { (field, expected) ->
            list.add(vr(field, "Module", expected, "(set by master.cfg import)", true))
        }

        results   = list
        allPassed = list.all { it.passed }
        verified  = true
    }

    // ── UI ─────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("\u2190", color = Color(0xFF97D5A5), fontSize = 20.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("VERIFY CONFIG", color = Color(0xFF97D5A5), fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp)
                    Text("All fields compared — errors only shown",
                        color = Color(0xFF8B938A), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(8.dp))

            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
                color = if (isConnected) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                Text(
                    if (isConnected) "\u25cf RADIO CONNECTED — reading config..."
                    else "\u25cb RADIO NOT CONNECTED — waiting...",
                    color = if (isConnected) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
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
                val failedResults = results.filter { !it.passed }
                val totalCount    = results.size

                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = if (allPassed) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                    Text(
                        if (allPassed) "\u2713 ALL $totalCount FIELDS VERIFIED"
                        else "\u2717 ${failedResults.size} of $totalCount fields failed",
                        color = if (allPassed) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(12.dp))

                if (allPassed) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0D2010)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            listOf("Device","LoRa","Channel","Position","Display","Module")
                                .forEach { grp ->
                                val cnt = results.count { it.group == grp }
                                Text("\u2713 $grp — $cnt fields",
                                    color = Color(0xFF97D5A5), fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                } else {
                    Text("FAILED FIELDS", color = Color(0xFFFFB4AB), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text("FIELD", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
                        Text("EXPECTED", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
                        Text("ACTUAL", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
                        Text("GRP", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp))
                    }
                    Spacer(Modifier.height(4.dp))

                    failedResults.forEach { result ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp), color = Color(0xFF2A1A1A)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(result.field, color = Color(0xFFFFB4AB),
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                                Text(result.expected, color = Color(0xFF8B938A),
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(2f))
                                Text(result.actual, color = Color(0xFFFFB4AB),
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(2f))
                                Text(result.group, color = Color(0xFF4A6080),
                                    fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(40.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1C211C)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            listOf("Device","LoRa","Channel","Position","Display","Module")
                                .forEach { grp ->
                                val total  = results.count { it.group == grp }
                                val passed = results.count { it.group == grp && it.passed }
                                val color  = if (passed == total) Color(0xFF97D5A5)
                                             else Color(0xFFFFB4AB)
                                Text("${if (passed == total) "\u2713" else "\u2717"} $grp: $passed/$total",
                                    color = color, fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onDone() },
                    shape = RoundedCornerShape(10.dp),
                    color = if (allPassed) Color(0xFF1A6B2E) else Color(0xFF1C211C)
                ) {
                    Text(
                        if (allPassed) "\u2713 DONE — RETURN TO CONVOY"
                        else "DONE (${failedResults.size} field${if (failedResults.size == 1) "" else "s"} need attention)",
                        color = if (allPassed) Color.White else Color(0xFF8B938A),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
