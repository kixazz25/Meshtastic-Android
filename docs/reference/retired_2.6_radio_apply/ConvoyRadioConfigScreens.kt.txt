package com.geeksville.mesh.convoy

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
import kotlinx.coroutines.launch
import org.meshtastic.core.model.ConnectionState

// ── Radio connection state ────────────────────────────────────────────────────

enum class RadioReadiness { CONNECTED, CONNECTING, DISCONNECTED, SLEEPING }

fun ConnectionState.toRadioReadiness(): RadioReadiness = when (this) {
    is ConnectionState.Connected   -> RadioReadiness.CONNECTED
    is ConnectionState.Connecting  -> RadioReadiness.CONNECTING
    is ConnectionState.DeviceSleep -> RadioReadiness.SLEEPING
    else                           -> RadioReadiness.DISCONNECTED
}

@Composable
fun ConvoyRadioStatusBar(readiness: RadioReadiness) {
    val label = when (readiness) {
        RadioReadiness.CONNECTED    -> "\u25cf CONNECTED"
        RadioReadiness.CONNECTING   -> "\u25cc CONNECTING..."
        RadioReadiness.SLEEPING     -> "\ud83d\udca4 DEVICE SLEEP"
        RadioReadiness.DISCONNECTED -> "\u25cb DISCONNECTED"
    }
    val color = when (readiness) {
        RadioReadiness.CONNECTED    -> Color(0xFF97D5A5)
        RadioReadiness.CONNECTING   -> Color(0xFFFFB74D)
        RadioReadiness.SLEEPING     -> Color(0xFFFFB74D)
        RadioReadiness.DISCONNECTED -> Color(0xFFFFB4AB)
    }
    val bg = when (readiness) {
        RadioReadiness.CONNECTED    -> Color(0xFF0D2010)
        RadioReadiness.CONNECTING   -> Color(0xFF2A1F0D)
        RadioReadiness.SLEEPING     -> Color(0xFF2A1F0D)
        RadioReadiness.DISCONNECTED -> Color(0xFF2A1A1A)
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), color = bg) {
        Text(label, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
    }
}

// ── Shared PROCEED button ─────────────────────────────────────────────────────

@Composable
fun ConvoyProceedButton(
    isConnected: Boolean,
    label: String = "PROCEED \u2192",
    isProcessing: Boolean = false,
    onClick: () -> Unit
) {
    val enabled = isConnected && !isProcessing
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = when {
            isProcessing -> Color(0xFF101510)
            isConnected  -> Color(0xFF1A6B2E)
            else         -> Color(0xFF8B1A1A)
        }
    ) {
        Text(
            text = when {
                isProcessing -> "WRITING..."
                isConnected  -> label
                else         -> "\u26a0 CONNECT RADIO TO PROCEED"
            },
            color = when {
                isProcessing -> Color(0xFF262B26)
                isConnected  -> Color.White
                else         -> Color(0xFFFFB4AB)
            },
            fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp)
        )
    }
}

// ── Shared CANCEL button ──────────────────────────────────────────────────────

@Composable
fun ConvoyCancelButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(10.dp), color = Color(0xFF2A1A1A)
    ) {
        Text("CANCEL", color = Color(0xFFFFB4AB), fontSize = 13.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
    }
}

// ── Shared field row ──────────────────────────────────────────────────────────

@Composable
fun ConvoyConfigRow(label: String, value: String, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF8B938A), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(value,
            color = if (highlight) Color(0xFF97D5A5) else Color(0xFFDFE4DC),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun ConvoyConfigSection(title: String, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1C211C)) {
        Column {
            Text(title, color = Color(0xFF8B938A), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            HorizontalDivider(color = Color(0xFF262B26))
            content()
        }
    }
}

// ── Screen header with live radio status ──────────────────────────────────────

@Composable
fun ConvoyConfigHeader(
    title: String,
    subtitle: String,
    step: Int,
    totalSteps: Int,
    readiness: RadioReadiness,
    onBack: () -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("\u2190", color = Color(0xFF97D5A5), fontSize = 20.sp,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color(0xFF97D5A5), fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp)
                Text("Step $step of $totalSteps \u2014 $subtitle",
                    color = Color(0xFF8B938A), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
        ConvoyRadioStatusBar(readiness)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262B26)))
        Spacer(Modifier.height(12.dp))
    }
}

// ── Archive Screen ────────────────────────────────────────────────────────────

@Composable
fun ConvoyWriteArchiveScreen(
    onProceed: () -> Unit,
    onCancel: () -> Unit,
    convoyViewModel: ConvoyViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel(),
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val context         = LocalContext.current
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val myNodeInfo      by uiViewModel.myNodeInfo.collectAsStateWithLifecycle()
    val localConfig     by channelViewModel.localConfig.collectAsStateWithLifecycle()
    val channels        by channelViewModel.channels.collectAsStateWithLifecycle()
    val readiness       = connectionState.toRadioReadiness()
    val isConnected     = readiness == RadioReadiness.CONNECTED

    var archiveDone   by remember { mutableStateOf(false) }
    var archiveFailed by remember { mutableStateOf(false) }
    var isRunning     by remember { mutableStateOf(false) }
    var logLines      by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }

    fun addLog(msg: String, ok: Boolean = true) { logLines = logLines + Pair(msg, ok) }

    val channelsReady = channels.settings.isNotEmpty()
    val configReady   = localConfig.lora != null
    LaunchedEffect(isConnected, channelsReady, configReady) {
        if (isConnected && channelsReady && configReady && !archiveDone && !archiveFailed && !isRunning) {
            isRunning = true
            logLines = emptyList()
            try {
                addLog("READING RADIO...")
                val ni = myNodeInfo
                if (ni == null) {
                    addLog("\u2717 Radio connected but node info not available", false)
                    archiveFailed = true
                    isRunning = false
                    return@LaunchedEffect
                }
                addLog("  \u25cf Node ID:  ${"!%08x".format(ni.myNodeNum)}")
                addLog("  \u25cf Model:    ${ni.model ?: "Unknown"}  fw ${ni.firmwareVersion ?: "?"}")
                addLog("  \u25cf LoRa:     ${localConfig.lora?.region?.name ?: "\u2014"}  ${localConfig.lora?.modem_preset?.name ?: "\u2014"}")
                addLog("  \u25cf Channel:  ${channels.settings.firstOrNull()?.name ?: "\u2014"}")
                addLog("  \u25cf GPS:      ${if (ni.hasGPS) "Yes" else "No"}")
                addLog("  \u25cf WiFi:     ${if (ni.hasWifi) "Yes" else "No"}")
                addLog("")
                addLog("WRITING ARCHIVE...")
                val nodeLongName = convoyViewModel.ourNodeInfo.value?.user?.long_name ?: ""
                val snapshot = ConvoyRadioManager.buildSnapshot(
                    myNodeNum = ni.myNodeNum, deviceId = ni.deviceId ?: "",
                    model = ni.model, firmwareVersion = ni.firmwareVersion,
                    pioEnv = ni.pioEnv, hasGPS = ni.hasGPS, hasWifi = ni.hasWifi,
                    maxChannels = ni.maxChannels, longName = nodeLongName,
                    deviceProfile = null, localConfig = localConfig, channelSet = channels
                )
                val filePath = ConvoyRadioManager.saveBackup(context, snapshot, "pre_write")
                addLog("  \u25cf File: $filePath")
                addLog("")
                addLog("\u2713 ARCHIVE COMPLETE \u2014 safe to proceed")
                convoyViewModel.workingConfig.value?.let { wc ->
                    convoyViewModel.setWorkingConfig(wc.copy(nodeId = "!%08x".format(ni.myNodeNum)))
                }
                archiveDone = true
            } catch (e: Exception) {
                addLog("\u2717 FAILED: ${e.message}", false)
                archiveFailed = true
            } finally {
                isRunning = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("\u2190", color = Color(0xFF97D5A5), fontSize = 20.sp,
                    modifier = Modifier.clickable { onCancel() }.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("ARCHIVE RADIO STATE", color = Color(0xFF97D5A5), fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp)
                    Text("Pre-write safety backup \u2014 required before any config change",
                        color = Color(0xFF8B938A), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(8.dp))
            ConvoyRadioStatusBar(readiness)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262B26)))
            Spacer(Modifier.height(16.dp))

            if (!isConnected && !archiveDone) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF2A1A1A)) {
                    Text(
                        text = when (readiness) {
                            RadioReadiness.CONNECTING -> "\u25cc Waiting for radio \u2014 archive will run automatically..."
                            RadioReadiness.SLEEPING   -> "\ud83d\udca4 Radio sleeping \u2014 wake and reconnect via Bluetooth"
                            else                      -> "\u25cb No radio \u2014 connect via Bluetooth to begin archive"
                        },
                        color = Color(0xFFFFB4AB), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            if (logLines.isNotEmpty()) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0D1A0D)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        logLines.forEach { (msg, ok) ->
                            Text(msg,
                                color = if (ok) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp))
                        }
                        if (isRunning) {
                            Text("...", color = Color(0xFF97D5A5), fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (archiveFailed) {
                Surface(modifier = Modifier.fillMaxWidth().clickable {
                    archiveFailed = false; archiveDone = false
                }, shape = RoundedCornerShape(10.dp), color = Color(0xFF2A1F0D)) {
                    Text("\u21ba RETRY ARCHIVE", color = Color(0xFFFFB74D), fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (archiveDone) {
                Surface(modifier = Modifier.fillMaxWidth().clickable { onProceed() },
                    shape = RoundedCornerShape(10.dp), color = Color(0xFF1A6B2E)) {
                    Text("PROCEED TO DEVICE CONFIG \u2192", color = Color.White, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            ConvoyCancelButton { onCancel() }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Screen 1: Device Config ───────────────────────────────────────────────────

@Composable
fun ConvoyDeviceConfigScreen(
    workingConfig: WorkingConfig,
    onProceed: () -> Unit,
    onBack: () -> Unit,
    channelViewModel: ChannelViewModel = hiltViewModel(),
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val readiness   = connectionState.toRadioReadiness()
    val isConnected = readiness == RadioReadiness.CONNECTED
    var isProcessing     by remember { mutableStateOf(false) }
    var waitingReconnect by remember { mutableStateOf(false) }
    var statusMsg        by remember { mutableStateOf("") }
    var statusOk         by remember { mutableStateOf(true) }
    var wasConnected     by remember { mutableStateOf(isConnected) }

    var countdown        by remember { mutableStateOf(0) }
    var reconnectFailed  by remember { mutableStateOf(false) }

    LaunchedEffect(isConnected) {
        if (waitingReconnect && !wasConnected && isConnected) {
            statusMsg = "\u25cf Radio reconnected \u2014 ready to proceed"
            statusOk = true
            waitingReconnect = false
            reconnectFailed = false
            countdown = 0
        }
        wasConnected = isConnected
    }

    LaunchedEffect(waitingReconnect) {
        if (!waitingReconnect) return@LaunchedEffect
        for (i in 10 downTo 1) {
            countdown = i
            kotlinx.coroutines.delay(1000)
        }
        countdown = 0
        if (!isConnected) {
            statusMsg = "\u25cc Not reconnected \u2014 issuing reconnect..."
            statusOk = true
            uiViewModel.reconnectDevice(context)
            kotlinx.coroutines.delay(3000)
            if (!isConnected) {
                statusMsg = "\u2717 Radio did not reconnect. Check radio and tap CANCEL to retry."
                statusOk = false
                reconnectFailed = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Spacer(Modifier.height(12.dp))
            ConvoyConfigHeader("DEVICE CONFIG", "Long name, node role", 3, 4, readiness, onBack)
            ConvoyConfigSection("DEVICE SETTINGS") {
                ConvoyConfigRow("Long Name",      workingConfig.longName,        highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Node Role",      workingConfig.nodeRole,        highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Managed Mode",   workingConfig.isManaged.toString())
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Serial Enabled", workingConfig.serialEnabled.toString())
            }
            Spacer(Modifier.height(8.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0D1A0D)) {
                Text("No reboot required after this step.", color = Color(0xFF8B938A),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            if (statusMsg.isNotBlank()) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = if (statusOk) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                    Text(statusMsg,
                        color = if (statusOk) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            if (waitingReconnect) {
                ConvoyProceedButton(
                    isConnected  = isConnected,
                    isProcessing = false,
                    label = when {
                        isConnected   -> "\u25cf RECONNECTED \u2014 PROCEED TO POSITION \u2192"
                        countdown > 0 -> "\u25cc WAITING FOR RADIO... ${countdown}s"
                        else          -> "\u25cc RECONNECTING..."
                    }
                ) { if (isConnected) onProceed() }
            } else {
                ConvoyProceedButton(isConnected = isConnected, isProcessing = isProcessing,
                    label = "WRITE DEVICE CONFIG \u2192") {
                    scope.launch {
                        isProcessing = true
                        try {
                            val role = try {
                                org.meshtastic.proto.Config.DeviceConfig.Role.valueOf(workingConfig.nodeRole)
                            } catch (e: Exception) {
                                org.meshtastic.proto.Config.DeviceConfig.Role.CLIENT
                            }
                            channelViewModel.setConfig(org.meshtastic.proto.Config(
                                device = org.meshtastic.proto.Config.DeviceConfig(
                                    role       = role,
                                    is_managed = workingConfig.isManaged
                                )
                            ))
                            statusMsg = "\u2713 Device config written \u2014 waiting for radio..."
                            statusOk  = true
                            waitingReconnect = true
                        } catch (e: Exception) {
                            statusMsg = "\u2717 Failed: ${e.message}"
                            statusOk  = false
                        } finally { isProcessing = false }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ConvoyCancelButton { onBack() }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Screen 2: LoRa Config ─────────────────────────────────────────────────────

@Composable
fun ConvoyLoRaConfigScreen(
    workingConfig: WorkingConfig,
    onProceed: () -> Unit,
    onBack: () -> Unit,
    channelViewModel: ChannelViewModel = hiltViewModel(),
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val readiness   = connectionState.toRadioReadiness()
    val isConnected = readiness == RadioReadiness.CONNECTED
    var isProcessing     by remember { mutableStateOf(false) }
    var waitingReconnect by remember { mutableStateOf(false) }
    var statusMsg        by remember { mutableStateOf("") }
    var statusOk         by remember { mutableStateOf(true) }
    var wasConnected     by remember { mutableStateOf(isConnected) }

    var countdown        by remember { mutableStateOf(0) }
    var reconnectFailed  by remember { mutableStateOf(false) }

    LaunchedEffect(isConnected) {
        if (waitingReconnect && !wasConnected && isConnected) {
            statusMsg = "\u25cf Radio reconnected \u2014 ready to proceed"
            statusOk = true
            waitingReconnect = false
            reconnectFailed = false
            countdown = 0
        }
        wasConnected = isConnected
    }

    // Auto-reconnect sequence after reboot write
    LaunchedEffect(waitingReconnect) {
        if (!waitingReconnect) return@LaunchedEffect
        // Wait 10 seconds for radio to reboot
        for (i in 10 downTo 1) {
            countdown = i
            kotlinx.coroutines.delay(1000)
        }
        countdown = 0
        // Check if reconnected
        if (!isConnected) {
            statusMsg = "\u25cc Not reconnected \u2014 issuing reconnect..."
            statusOk = true
            uiViewModel.reconnectDevice(context)
            // Wait 3 more seconds after reconnect command
            kotlinx.coroutines.delay(3000)
            if (!isConnected) {
                statusMsg = "\u2717 Radio did not reconnect. Check radio and tap CANCEL to retry."
                statusOk = false
                reconnectFailed = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Spacer(Modifier.height(12.dp))
            ConvoyConfigHeader("LORA CONFIG", "Region, modem preset, hop limit", 1, 4, readiness, onBack)
            ConvoyConfigSection("LORA SETTINGS") {
                ConvoyConfigRow("Region",       workingConfig.loraRegion,             highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Modem Preset", workingConfig.loraModemPreset,        highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Hop Limit",    workingConfig.loraHopLimit.toString())
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("TX Enabled",   workingConfig.loraTxEnabled.toString())
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("TX Power",     "${workingConfig.loraTxPower} dBm")
            }
            Spacer(Modifier.height(8.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2A1A1A)) {
                Text("\u26a0 Radio will REBOOT after this write. Wait for reconnect before continuing.",
                    color = Color(0xFFFFB74D), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            if (statusMsg.isNotBlank()) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = if (statusOk) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                    Text(statusMsg,
                        color = if (statusOk) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            if (waitingReconnect) {
                ConvoyProceedButton(
                    isConnected  = isConnected,
                    isProcessing = false,
                    label = when {
                        isConnected  -> "\u25cf RECONNECTED \u2014 PROCEED TO CHANNEL \u2192"
                        countdown > 0 -> "\u25cc WAITING FOR RADIO... ${countdown}s"
                        else          -> "\u25cc RECONNECTING..."
                    }
                ) { if (isConnected) onProceed() }
            } else {
                ConvoyProceedButton(isConnected = isConnected, isProcessing = isProcessing,
                    label = "WRITE LORA CONFIG \u2192") {
                    scope.launch {
                        isProcessing = true
                        try {
                            val regionCode = try {
                                org.meshtastic.proto.Config.LoRaConfig.RegionCode.valueOf(workingConfig.loraRegion)
                            } catch (e: Exception) { org.meshtastic.proto.Config.LoRaConfig.RegionCode.US }
                            val modemPreset = try {
                                org.meshtastic.proto.Config.LoRaConfig.ModemPreset.valueOf(workingConfig.loraModemPreset)
                            } catch (e: Exception) { org.meshtastic.proto.Config.LoRaConfig.ModemPreset.LONG_FAST }
                            channelViewModel.setConfig(org.meshtastic.proto.Config(
                                lora = org.meshtastic.proto.Config.LoRaConfig(
                                    region       = regionCode,
                                    modem_preset = modemPreset,
                                    hop_limit    = workingConfig.loraHopLimit,
                                    tx_enabled   = workingConfig.loraTxEnabled,
                                    tx_power     = workingConfig.loraTxPower,
                                    use_preset   = true
                                )
                            ))
                            statusMsg = "\u2713 LoRa config written \u2014 radio rebooting...\n\u25cb Waiting for reconnect..."
                            statusOk = true
                            waitingReconnect = true
                        } catch (e: Exception) {
                            statusMsg = "\u2717 Failed: ${e.message}"
                            statusOk = false
                        } finally { isProcessing = false }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ConvoyCancelButton { onBack() }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Screen 3: Position Config ─────────────────────────────────────────────────

@Composable
fun ConvoyPositionConfigScreen(
    workingConfig: WorkingConfig,
    onProceed: () -> Unit,
    onBack: () -> Unit,
    channelViewModel: ChannelViewModel = hiltViewModel(),
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val readiness   = connectionState.toRadioReadiness()
    val isConnected = readiness == RadioReadiness.CONNECTED
    var isProcessing by remember { mutableStateOf(false) }
    var statusMsg    by remember { mutableStateOf("") }
    var statusOk     by remember { mutableStateOf(true) }
    val broadcastSecs = workingConfig.positionBroadcastSecs
    val gpsUpdateSecs = workingConfig.gpsUpdateSecs
    val smartEnabled  = workingConfig.smartPositionEnabled
    val smartMinSecs  = workingConfig.smartMinIntervalSecs
    val smartMinDist  = workingConfig.smartMinDistanceMeters

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Spacer(Modifier.height(12.dp))
            ConvoyConfigHeader("POSITION CONFIG", "GPS and broadcast intervals", 4, 4, readiness, onBack)
            ConvoyConfigSection("POSITION BROADCAST") {
                ConvoyConfigRow("Broadcast Interval", "$broadcastSecs seconds", highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Smart Position",     if (smartEnabled) "Enabled" else "Disabled")
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Smart Min Interval", "$smartMinSecs seconds", highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Smart Min Distance", "$smartMinDist meters")
            }
            Spacer(Modifier.height(8.dp))
            ConvoyConfigSection("GPS UPDATE") {
                ConvoyConfigRow("GPS Update Interval", "$gpsUpdateSecs second", highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("GPS Mode", "ENABLED")
            }
            Spacer(Modifier.height(8.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0D1A2A)) {
                Text("Convoy uses lower intervals than Android defaults. No reboot required after this step.",
                    color = Color(0xFF7AB4D5), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            if (statusMsg.isNotBlank()) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = if (statusOk) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                    Text(statusMsg,
                        color = if (statusOk) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            ConvoyProceedButton(isConnected = isConnected, isProcessing = isProcessing,
                label = "WRITE POSITION CONFIG \u2192") {
                scope.launch {
                    isProcessing = true
                    try {
                        channelViewModel.setConfig(org.meshtastic.proto.Config(
                            position = org.meshtastic.proto.Config.PositionConfig(
                                position_broadcast_secs               = broadcastSecs,
                                position_broadcast_smart_enabled      = smartEnabled,
                                broadcast_smart_minimum_interval_secs = smartMinSecs,
                                broadcast_smart_minimum_distance      = smartMinDist,
                                gps_update_interval                   = gpsUpdateSecs,
                                gps_mode = org.meshtastic.proto.Config.PositionConfig.GpsMode.ENABLED
                            )
                        ))
                        statusMsg = "\u2713 Position config written"
                        statusOk  = true
                        kotlinx.coroutines.delay(800)
                        onProceed()
                    } catch (e: Exception) {
                        statusMsg = "\u2717 Failed: ${e.message}"
                        statusOk  = false
                    } finally { isProcessing = false }
                }
            }
            Spacer(Modifier.height(8.dp))
            ConvoyCancelButton { onBack() }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Screen 4: Channel + PSK ───────────────────────────────────────────────────

@Composable
fun ConvoyChannelConfigScreen(
    workingConfig: WorkingConfig,
    onComplete: () -> Unit,
    onBack: () -> Unit,
    channelViewModel: ChannelViewModel = hiltViewModel(),
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val readiness   = connectionState.toRadioReadiness()
    val isConnected = readiness == RadioReadiness.CONNECTED
    var isProcessing     by remember { mutableStateOf(false) }
    var waitingReconnect by remember { mutableStateOf(false) }
    var statusMsg        by remember { mutableStateOf("") }
    var statusOk         by remember { mutableStateOf(true) }
    var wasConnected     by remember { mutableStateOf(isConnected) }
    var writeComplete    by remember { mutableStateOf(false) }

    var countdown        by remember { mutableStateOf(0) }
    var reconnectFailed  by remember { mutableStateOf(false) }

    LaunchedEffect(isConnected) {
        if (waitingReconnect && !wasConnected && isConnected) {
            statusMsg = "\u25cf Radio reconnected \u2014 write complete"
            statusOk = true
            waitingReconnect = false
            writeComplete = true
            reconnectFailed = false
            countdown = 0
        }
        wasConnected = isConnected
    }

    LaunchedEffect(waitingReconnect) {
        if (!waitingReconnect) return@LaunchedEffect
        for (i in 10 downTo 1) {
            countdown = i
            kotlinx.coroutines.delay(1000)
        }
        countdown = 0
        if (!isConnected) {
            statusMsg = "\u25cc Not reconnected \u2014 issuing reconnect..."
            statusOk = true
            uiViewModel.reconnectDevice(context)
            kotlinx.coroutines.delay(3000)
            if (!isConnected) {
                statusMsg = "\u2717 Radio did not reconnect. Check radio and tap CANCEL to retry."
                statusOk = false
                reconnectFailed = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Spacer(Modifier.height(12.dp))
            ConvoyConfigHeader("CHANNEL + PSK", "Channel name and encryption key", 2, 4, readiness, onBack)
            ConvoyConfigSection("CHANNEL SETTINGS") {
                ConvoyConfigRow("Channel Name",   workingConfig.channelName,                          highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Encryption Key", "AES-256 \u2014 ${workingConfig.channelPsk.take(8)}...", highlight = true)
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Channel Index",  "0 (Primary)")
                HorizontalDivider(color = Color(0xFF262B26))
                ConvoyConfigRow("Source",         workingConfig.source)
            }
            Spacer(Modifier.height(8.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2A1A1A)) {
                Text("\u26a0 Radio will REBOOT after this write. This is the final step.",
                    color = Color(0xFFFFB74D), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            if (statusMsg.isNotBlank()) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = if (statusOk) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                    Text(statusMsg,
                        color = if (statusOk) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            if (writeComplete) {
                ConvoyProceedButton(isConnected = isConnected,
                    label = "\u2713 COMPLETE \u2014 RETURN TO CONVOY") { onComplete() }
            } else if (waitingReconnect) {
                ConvoyProceedButton(isConnected = isConnected, isProcessing = false,
                    label = when {
                        isConnected   -> "\u25cf RECONNECTED \u2014 PROCEED TO DEVICE CONFIG \u2192"
                        countdown > 0 -> "\u25cc WAITING FOR RADIO... ${countdown}s"
                        else          -> "\u25cc RECONNECTING..."
                    }
                ) { if (isConnected) { writeComplete = true; onComplete() } }
            } else {
                ConvoyProceedButton(isConnected = isConnected, isProcessing = isProcessing,
                    label = "WRITE CHANNEL + PSK \u2192") {
                    scope.launch {
                        isProcessing = true
                        try {
                            val pskBytes = okio.ByteString.of(
                                *android.util.Base64.decode(
                                    workingConfig.channelPsk, android.util.Base64.NO_WRAP)
                            )
                            channelViewModel.setChannels(org.meshtastic.proto.ChannelSet(
                                settings = listOf(org.meshtastic.proto.ChannelSettings(
                                    name = workingConfig.channelName, psk = pskBytes
                                ))
                            ))
                            statusMsg = "\u2713 Channel written \u2014 radio rebooting...\n\u25cb Waiting for reconnect..."
                            statusOk = true
                            waitingReconnect = true
                        } catch (e: Exception) {
                            statusMsg = "\u2717 Failed: ${e.message}"
                            statusOk = false
                        } finally { isProcessing = false }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!writeComplete && !waitingReconnect) ConvoyCancelButton { onBack() }
            Spacer(Modifier.height(32.dp))
        }
    }
}
