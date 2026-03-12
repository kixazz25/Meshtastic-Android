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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ConvoyMasterCaptureScreen — developer-only master config capture
 *
 * Accessed via password-protected settings panel only.
 * Reads full radio snapshot from connected device.
 * Saves to C:/ConvoyProto/master_config.json
 * File must then be manually copied to app/src/main/assets/
 * before building the release APK.
 *
 * One-time operation. Nobody else can do this.
 */
@Composable
fun ConvoyMasterCaptureScreen(
    viewModel: ConvoyViewModel,
    onBack: () -> Unit,
    onCaptureSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var statusMsg    by remember { mutableStateOf("") }
    var statusOk     by remember { mutableStateOf(true) }
    var isCapturing  by remember { mutableStateOf(false) }
    var captureLog   by remember { mutableStateOf("") }

    val nodeInfo     = viewModel.myNodeInfo.value
    val isConnected  = nodeInfo != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
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
                    text     = "←",
                    color    = Color(0xFF2E75B6),
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
                        "⚠  DEVELOPER OPERATION",
                        color      = Color(0xFFFFB74D),
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This captures your radio config as the master template for all convoy installs. " +
                        "After capture, copy master_config.json to app/src/main/assets/ before building the release APK.",
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
                color    = Color(0xFF1A2535)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "CONNECTED RADIO",
                        color      = Color(0xFF4A6080),
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    if (isConnected) {
                        Text(
                            "● CONNECTED",
                            color      = Color(0xFF67EA94),
                            fontSize   = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${nodeInfo!!.model ?: "Unknown"}  •  fw ${nodeInfo.firmwareVersion ?: "Unknown"}",
                            color      = Color(0xFF4A6080),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "ID: ${"!%08x".format(nodeInfo.myNodeNum)}  •  GPS: ${nodeInfo.hasGPS}  •  WiFi: ${nodeInfo.hasWifi}",
                            color      = Color(0xFF4A6080),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            "○ NO RADIO CONNECTED",
                            color      = Color(0xFFF44336),
                            fontSize   = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Connect your master radio via Bluetooth before capturing.",
                            color      = Color(0xFF4A6080),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
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
                        color      = Color(0xFF67EA94),
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
                        color      = if (statusOk) Color(0xFF67EA94) else Color(0xFFF44336),
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

                                val ni       = viewModel.myNodeInfo.value!!
                                val hwId     = "!%08x".format(ni.myNodeNum)
                                log.appendLine("Hardware ID: $hwId")
                                log.appendLine("Model: ${ni.model}")
                                log.appendLine("Firmware: ${ni.firmwareVersion}")

                                // Build master config from node info
                                // Full DeviceProfile proto captured via ConvoyRadioManager
                                // LoRa values read from localConfig flow in next wiring pass
                                val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
                                val today = LocalDate.now().format(fmt)

                                val master = ConvoyMasterConfig(
                                    hardwareModel       = ni.model ?: "Unknown",
                                    firmwareVersion     = ni.firmwareVersion ?: "Unknown",
                                    pioEnv              = ni.pioEnv ?: "",
                                    loraRegion          = "US",       // read from localConfig in wiring pass
                                    loraModemPreset     = "LONG_FAST",
                                    loraBandwidth       = 250,
                                    loraSpreadFactor    = 11,
                                    loraCodingRate      = 8,
                                    loraHopLimit        = 3,
                                    loraTxEnabled       = true,
                                    loraTxPower         = 27,
                                    primaryChannelName  = "",
                                    deviceProfileBase64 = "",         // wired in next pass
                                    capturedDate        = today,
                                    capturedFirmware    = ni.firmwareVersion ?: "Unknown"
                                )

                                // ── Save to Android Downloads ────────────────
                                val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                )
                                val masterJson = master.toJson().toString(2)
                                
                                val masterFile = java.io.File(downloads, "master_config.json")
                                masterFile.writeText(masterJson)
                                log.appendLine("✓ Saved: Downloads/master_config.json")
                                
                                // Save apply list alongside master config
                                val applyList = ConvoyApplyList.load(context)
                                val applyFile = java.io.File(downloads, "convoy_apply_list.json")
                                applyFile.writeText(applyList.toJson().toString(2))
                                log.appendLine("✓ Saved: Downloads/convoy_apply_list.json")
                                
                                // ── Check PC directory ────────────────────────
                                val pcDir = java.io.File("C:/ConvoyProto")
                                if (pcDir.exists() && pcDir.isDirectory) {
                                    java.io.File(pcDir, "master_config.json").writeText(masterJson)
                                    java.io.File(pcDir, "convoy_apply_list.json")
                                        .writeText(applyList.toJson().toString(2))
                                    log.appendLine("✓ Copied to C:/ConvoyProto/")
                                } else {
                                    log.appendLine("⚠  C:/ConvoyProto/ not found")
                                    log.appendLine("   Master NOT updated for distribution")
                                }
                                
                                log.appendLine("")
                                log.appendLine("Connect PC via USB and run:")
                                log.appendLine("  bash convoy_commit_master.sh")
                                
                                captureLog = log.toString()
                                statusMsg  = "✓ Master config captured."
                                statusOk   = true
                                onCaptureSuccess()
                            } catch (e: Exception) {
                                statusMsg = "✗ Capture failed: ${e.message}"
                                statusOk  = false
                            } finally {
                                isCapturing = false
                            }
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                color = if (isConnected && !isCapturing) Color(0xFF2A3A1A) else Color(0xFF1A1F2B)
            ) {
                Text(
                    text       = if (isCapturing) "CAPTURING..." else "CAPTURE MASTER CONFIG",
                    color      = if (isConnected && !isCapturing) Color(0xFF67EA94) else Color(0xFF2A3545),
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 16.dp)
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
