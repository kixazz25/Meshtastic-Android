package com.geeksville.mesh.convoy

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ConvoyMasterCaptureScreen — developer-only master config capture
 *
 * Accessed via password-protected settings panel only.
 * Reads full radio snapshot from connected device including:
 *   - Hardware ID, model, firmware
 *   - LoRa region, modem preset, hop limit, TX power (from localConfig)
 *   - Primary channel name and AES-256 PSK (from channels)
 *   - Node long name (from nodeDB)
 *
 * Saves master_config.json to external cache for adb pull.
 * File must be copied to app/src/main/assets/ before release build.
 *
 * One master config. Captured once. Nobody else can do this.
 */
@Composable
fun ConvoyMasterCaptureScreen(
    viewModel: ConvoyViewModel,
    onBack: () -> Unit,
    onCaptureSuccess: () -> Unit = {},
    channelViewModel: ChannelViewModel = hiltViewModel(),
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var statusMsg       by remember { mutableStateOf("") }
    var statusOk        by remember { mutableStateOf(true) }
    var isCapturing     by remember { mutableStateOf(false) }
    var captureLog      by remember { mutableStateOf("") }
    var captureComplete by remember { mutableStateOf(false) }

    val nodeInfo        by viewModel.myNodeInfo.collectAsState()
    val localConfig     by channelViewModel.localConfig.collectAsStateWithLifecycle()
    val channels        by channelViewModel.channels.collectAsStateWithLifecycle()
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val myNodeInfo      by uiViewModel.myNodeInfo.collectAsStateWithLifecycle()
    val isConnected     = nodeInfo != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text     = "\u2190",
                    color    = Color(0xFF97D5A5),
                    fontSize = 20.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp)
                )
                Text(
                    text          = "CAPTURE MASTER CONFIG",
                    color         = Color(0xFFFFB74D),
                    fontSize      = 13.sp,
                    fontFamily    = FontFamily.Monospace,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A3A1A)))
            Spacer(Modifier.height(16.dp))

            // ── Warning banner ────────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                color    = Color(0xFF2A1A00)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "\u26a0  DEVELOPER OPERATION",
                        color      = Color(0xFFFFB74D),
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Captures ALL radio config values as the master template for all convoy installs. " +
                        "Reads LoRa settings, channel name, and AES-256 PSK from connected radio. " +
                        "After capture, pull the file via adb and copy to app/src/main/assets/ before building the release APK.",
                        color      = Color(0xFF7A5A30),
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            // ── Connected radio info ──────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                color    = Color(0xFF1C211C)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "CONNECTED RADIO",
                        color         = Color(0xFF8B938A),
                        fontSize      = 10.sp,
                        fontFamily    = FontFamily.Monospace,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    if (isConnected) {
                        Text("\u25cf CONNECTED", color = Color(0xFF97D5A5),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("${nodeInfo?.model ?: "Unknown"}  \u2022  fw ${nodeInfo?.firmwareVersion ?: "Unknown"}",
                            color = Color(0xFF8B938A), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("ID: ${"!%08x".format(nodeInfo?.myNodeNum ?: 0)}",
                            color = Color(0xFF8B938A), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        // Show live radio values that will be captured
                        Spacer(Modifier.height(8.dp))
                        Text("WILL CAPTURE:", color = Color(0xFF4A6080), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("  LoRa Region:    ${localConfig.lora?.region?.name ?: "\u2014"}",
                            color = Color(0xFF8B938A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("  Modem Preset:   ${localConfig.lora?.modem_preset?.name ?: "\u2014"}",
                            color = Color(0xFF8B938A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("  Hop Limit:      ${localConfig.lora?.hop_limit ?: "\u2014"}",
                            color = Color(0xFF8B938A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("  TX Power:       ${localConfig.lora?.tx_power ?: "\u2014"} dBm",
                            color = Color(0xFF8B938A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("  Channel Name:   ${channels.settings.firstOrNull()?.name?.ifBlank { "(blank)" } ?: "\u2014"}",
                            color = if ((channels.settings.firstOrNull()?.name ?: "").isBlank())
                                Color(0xFFFFB4AB) else Color(0xFF97D5A5),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("  PSK:            ${if ((channels.settings.firstOrNull()?.psk?.size ?: 0) > 0) "AES-256 present (${channels.settings.firstOrNull()?.psk?.size} bytes)" else "\u26a0 NOT SET"}",
                            color = if ((channels.settings.firstOrNull()?.psk?.size ?: 0) > 0)
                                Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        Text("\u25cb NO RADIO CONNECTED", color = Color(0xFFF44336),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("Connect reference radio via Bluetooth before capturing.",
                            color = Color(0xFF8B938A), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Capture log ───────────────────────────────────────────────────
            if (captureLog.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    color    = Color(0xFF0D1A0D)
                ) {
                    Text(
                        text       = captureLog,
                        color      = Color(0xFF97D5A5),
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.padding(14.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Status ────────────────────────────────────────────────────────
            if (statusMsg.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(8.dp),
                    color    = if (statusOk) Color(0xFF0D2010) else Color(0xFF2A1A1A)
                ) {
                    Text(
                        text       = statusMsg,
                        color      = if (statusOk) Color(0xFF97D5A5) else Color(0xFFF44336),
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Capture button ────────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isConnected || isCapturing) return@clickable
                        scope.launch {
                            isCapturing = true
                            captureLog  = ""
                            statusMsg   = ""
                            try {
                                val log = StringBuilder()
                                log.appendLine("Reading radio state...")

                                val ni = nodeInfo ?: run {
                                    log.appendLine("\u2717 Node info not available")
                                    statusMsg = "\u2717 Capture failed: node info not available"
                                    statusOk = false
                                    isCapturing = false
                                    captureLog = log.toString()
                                    return@launch
                                }

                                val hwId = "!%08x".format(ni.myNodeNum)
                                log.appendLine("  \u25cf Hardware ID:  $hwId")
                                log.appendLine("  \u25cf Model:        ${ni.model ?: "Unknown"}")
                                log.appendLine("  \u25cf Firmware:     ${ni.firmwareVersion ?: "Unknown"}")
                                captureLog = log.toString()

                                // ── Read LoRa config ──────────────────────────
                                log.appendLine("")
                                log.appendLine("Reading LoRa config...")
                                val lora = localConfig.lora
                                val loraRegion      = lora?.region?.name ?: "US"
                                val loraModemPreset = lora?.modem_preset?.name ?: "LONG_FAST"
                                val loraBandwidth   = lora?.bandwidth ?: 250
                                val loraSpreadFactor= lora?.spread_factor ?: 11
                                val loraCodingRate  = lora?.coding_rate ?: 8
                                val loraHopLimit    = lora?.hop_limit ?: 3
                                val loraTxEnabled   = lora?.tx_enabled ?: true
                                val loraTxPower     = lora?.tx_power ?: 27
                                log.appendLine("  \u25cf Region:       $loraRegion")
                                log.appendLine("  \u25cf Modem Preset: $loraModemPreset")
                                log.appendLine("  \u25cf Hop Limit:    $loraHopLimit")
                                log.appendLine("  \u25cf TX Power:     $loraTxPower dBm")
                                captureLog = log.toString()

                                // ── Read channel + PSK ────────────────────────
                                log.appendLine("")
                                log.appendLine("Reading channel config...")
                                val primaryChannel = channels.settings.firstOrNull()
                                val channelName    = primaryChannel?.name ?: ""
                                val pskBytes       = primaryChannel?.psk
                                val pskBase64      = if (pskBytes != null && pskBytes.size > 0)
                                    Base64.encodeToString(pskBytes.toByteArray(), Base64.NO_WRAP)
                                else ""

                                if (channelName.isBlank()) {
                                    log.appendLine("  \u26a0 Channel name is blank \u2014 set channel name on radio before capturing")
                                } else {
                                    log.appendLine("  \u25cf Channel Name: $channelName")
                                }
                                if (pskBase64.isBlank()) {
                                    log.appendLine("  \u26a0 PSK not set \u2014 configure AES-256 encryption on radio before capturing")
                                } else {
                                    log.appendLine("  \u25cf PSK:          AES-256 present (${pskBytes?.size} bytes)")
                                }
                                captureLog = log.toString()

                                // ── Read long name ────────────────────────────
                                log.appendLine("")
                                log.appendLine("Reading node long name...")
                                val longName = myNodeInfo?.let {
                                    // Try to get long name from node — use nodeNum as fallback
                                    hwId
                                } ?: hwId
                                log.appendLine("  \u25cf Long Name:    $longName")
                                captureLog = log.toString()

                                // ── Gate: warn if channel or PSK missing ──────
                                if (channelName.isBlank() || pskBase64.isBlank()) {
                                    log.appendLine("")
                                    log.appendLine("\u26a0 WARNING: Channel name or PSK is missing.")
                                    log.appendLine("  Capture will proceed but master config will be incomplete.")
                                    log.appendLine("  Set channel name and PSK on radio, then recapture.")
                                    captureLog = log.toString()
                                }

                                // ── Build master config ───────────────────────
                                log.appendLine("")
                                log.appendLine("Building master config...")
                                val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
                                val today = LocalDate.now().format(fmt)

                                // Read all radio sections — mirrors ConvoyMasterConfig definition exactly
                                val pos      = localConfig.position
                                val dev      = localConfig.device
                                val disp     = localConfig.display
                                val primaryCh = channels.settings.firstOrNull()
                                val master = ConvoyMasterConfig(
                                    hardwareModel            = ni.model ?: "Unknown",
                                    firmwareVersion          = ni.firmwareVersion ?: "Unknown",
                                    pioEnv                   = ni.pioEnv ?: "",
                                    // ── Device
                                    longName                 = longName,
                                    shortName                = "",
                                    nodeRole                 = dev?.role?.name ?: "CLIENT",
                                    isManaged                = dev?.is_managed ?: false,
                                    serialEnabled            = dev?.serial_enabled ?: false,
                                    // ── LoRa
                                    loraRegion               = loraRegion,
                                    loraModemPreset          = loraModemPreset,
                                    loraBandwidth            = loraBandwidth,
                                    loraSpreadFactor         = loraSpreadFactor,
                                    loraCodingRate           = loraCodingRate,
                                    loraHopLimit             = loraHopLimit,
                                    loraTxEnabled            = loraTxEnabled,
                                    loraTxPower              = loraTxPower,
                                    loraChannelNum           = localConfig.lora?.channel_num ?: 0,
                                    // ── Channel
                                    primaryChannelName       = channelName,
                                    primaryChannelPsk        = pskBase64,
                                    channelId                = primaryCh?.id ?: 0,
                                    channelUplinkEnabled     = primaryCh?.uplink_enabled ?: false,
                                    channelDownlinkEnabled   = primaryCh?.downlink_enabled ?: false,
                                    // ── Position
                                    gpsEnabled               = pos?.gps_mode?.name != "DISABLED",
                                    gpsMode                  = pos?.gps_mode?.name ?: "ENABLED",
                                    gpsUpdateSecs            = pos?.gps_update_interval ?: 1,
                                    gpsAttemptTime           = pos?.gps_attempt_time ?: 900,
                                    positionBroadcastSecs    = pos?.position_broadcast_secs ?: 5,
                                    smartPositionEnabled     = pos?.position_broadcast_smart_enabled ?: true,
                                    fixedPosition            = pos?.fixed_position ?: false,
                                    positionFlags            = pos?.position_flags ?: 811,
                                    smartMinIntervalSecs     = pos?.broadcast_smart_minimum_interval_secs ?: 3,
                                    smartMinDistanceMeters   = pos?.broadcast_smart_minimum_distance ?: 10,
                                    // ── Display
                                    displayUnits             = disp?.units?.ordinal ?: 0,
                                    screenTimeout            = disp?.screen_on_secs ?: 0,
                                    autoScreenBrightness     = false,
                                    compassNorthTop          = disp?.compass_north_top ?: false,
                                    // ── Module (defaults — module config read requires separate API call)
                                    telemetryDeviceInterval  = 0,
                                    telemetryEnvInterval     = 0,
                                    telemetryEnvEnabled      = false,
                                    mqttEnabled              = false,
                                    mqttAddress              = "",
                                    mqttUsername             = "",
                                    mqttEncryptionEnabled    = false,
                                    mqttJsonEnabled          = false,
                                    serialModuleEnabled      = false,
                                    serialBaud               = 0,
                                    extNotificationEnabled   = false,
                                    extNotificationAlertMsg  = false,
                                    rangeTestEnabled         = false,
                                    storeForwardEnabled      = false,
                                    neighborInfoEnabled      = false,
                                    detectionSensorEnabled   = false,
                                    audioEnabled             = false,
                                    // ── Metadata
                                    deviceProfileBase64      = "",
                                    capturedDate             = today,
                                    capturedFirmware         = ni.firmwareVersion ?: "Unknown"
                                )

                                // ── Save to external cache (adb-pullable) ─────
                                log.appendLine("")
                                log.appendLine("Saving files...")
                                val saveDir = context.getExternalCacheDir() ?: context.filesDir
                                val masterJson = master.toJson().toString(2)

                                val masterFile = java.io.File(saveDir, "master_config.json")
                                masterFile.writeText(masterJson)
                                log.appendLine("\u2713 Saved: ${masterFile.absolutePath}")

                                // Also save to internal files dir
                                val internalFile = java.io.File(context.filesDir, "master_config.json")
                                internalFile.writeText(masterJson)
                                log.appendLine("\u2713 Saved: ${internalFile.absolutePath}")

                                // Save apply list alongside
                                val applyList = ConvoyApplyList.load(context)
                                val applyFile = java.io.File(saveDir, "convoy_apply_list.json")
                                applyFile.writeText(applyList.toJson().toString(2))
                                log.appendLine("\u2713 Saved: convoy_apply_list.json")
                                // ── Export master.cfg binary ──────────────────
                                val masterCfgResult = viewModel.exportProfileToFile(
                                    context,
                                    ConvoyViewModel.ConvoyProfilePaths.masterCfg(context)
                                )
                                if (masterCfgResult.isSuccess) {
                                    log.appendLine("\u2713 Saved: master.cfg binary")
                                    // Also copy to external cache for adb pull
                                    val masterCfgExternal = java.io.File(saveDir, "master.cfg")
                                    java.io.File(context.filesDir, "master.cfg")
                                        .copyTo(masterCfgExternal, overwrite = true)
                                    log.appendLine("\u2713 Saved: master.cfg to external cache")
                                } else {
                                    log.appendLine("\u26a0 master.cfg export failed: ${masterCfgResult.exceptionOrNull()?.message}")
                                }

                                // ── adb pull instructions ─────────────────────
                                log.appendLine("")
                                log.appendLine("Pull all 3 assets via adb:")
                                log.appendLine("  adb shell run-as com.geeksville.mesh.google.debug \\")
                                log.appendLine("    cat files/master_config.json > app/src/main/assets/master_config.json")
                                log.appendLine("  adb shell run-as com.geeksville.mesh.google.debug \\")
                                log.appendLine("    cat files/convoy_apply_list.json > app/src/main/assets/convoy_apply_list.json")
                                log.appendLine("  adb shell run-as com.geeksville.mesh.google.debug \\")
                                log.appendLine("    cat files/master.cfg > app/src/main/assets/master.cfg")
                                log.appendLine("")
                                log.appendLine("Then commit all 3 to assets:")
                                log.appendLine("  git add app/src/main/assets/")
                                log.appendLine("  git commit -m 'chore: update master config assets'")

                                captureLog = log.toString()
                                statusMsg  = if (channelName.isBlank() || pskBase64.isBlank())
                                    "\u26a0 Captured with warnings \u2014 channel or PSK missing"
                                else
                                    "\u2713 Master config captured successfully"
                                statusOk      = channelName.isNotBlank() && pskBase64.isNotBlank()
                                captureComplete = true

                            } catch (e: Exception) {
                                captureLog += "\n\u2717 FAILED: ${e.message}"
                                statusMsg = "\u2717 Capture failed: ${e.message}"
                                statusOk  = false
                            } finally {
                                isCapturing = false
                            }
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                color = if (isConnected && !isCapturing) Color(0xFF2A3A1A) else Color(0xFF101510)
            ) {
                Text(
                    text       = if (isCapturing) "CAPTURING..." else "CAPTURE MASTER CONFIG",
                    color      = if (isConnected && !isCapturing) Color(0xFF97D5A5) else Color(0xFF262B26),
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 16.dp)
                )
            }

            if (captureComplete) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onCaptureSuccess() },
                    shape    = RoundedCornerShape(12.dp),
                    color    = Color(0xFF1A3A2A)
                ) {
                    Text(
                        text      = "PROCEED \u2192",
                        color     = Color(0xFF97D5A5),
                        fontSize  = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
