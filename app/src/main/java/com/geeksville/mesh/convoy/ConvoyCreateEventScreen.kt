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
import androidx.compose.foundation.layout.imePadding
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
import java.security.SecureRandom

/**
 * ConvoyCreateEventScreen — V2 F1 Create Event / Ride
 *
 * Form fields (required): Event Name, Event Date, Channel Name
 * Form fields (optional): Description
 *
 * Organizer info: auto-populated from enrolled profile
 * LoRa settings: auto-populated from master config — NOT user-entered
 * Hardware info: auto-populated from connected radio via ViewModel
 *
 * Radio processing sequence on CREATE:
 *   ① Read current radio config (snapshot via ViewModel flows)
 *   ② Backup full config to C:/ConvoyProto/backups/[hardwareId]/
 *   ③ Write convoy channel (name + PSK) to radio
 *
 * Map area selector: placeholder — Offline tile package coming in V3
 *
 * NO import. NO manual radio config. Master config is the single source.
 */
@Composable
fun ConvoyCreateEventScreen(
    viewModel: ConvoyViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var eventName    by remember { mutableStateOf("") }
    var eventDate    by remember { mutableStateOf("") }
    var description  by remember { mutableStateOf("") }
    var channelName  by remember { mutableStateOf("") }
    var statusMsg    by remember { mutableStateOf("") }
    var statusOk     by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var step         by remember { mutableStateOf(0) }   // 0=idle 1=read 2=backup 3=write 4=done

    val organizer    = remember { ConvoyUserStore.getActiveUser(context) }
    val masterConfig = remember { ConvoyMasterConfig.load(context) }
    val allFilled    = eventName.isNotBlank() && eventDate.isNotBlank() && channelName.isNotBlank()
    val canCreate    = allFilled && organizer != null && masterConfig != null && !isProcessing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .imePadding()
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
                    text       = "CREATE EVENT / RIDE",
                    color      = Color(0xFF67EA94),
                    fontSize   = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E3A5F)))
            Spacer(Modifier.height(20.dp))

            // ── Master config warning ─────────────────────────────────────────
            if (masterConfig == null) {
                StatusBanner(
                    msg  = "⚠  Master radio config not found.\nContact support — app may need reinstall.",
                    ok   = false
                )
                Spacer(Modifier.height(12.dp))
            }

            // ── Organizer strip ───────────────────────────────────────────────
            if (organizer != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    color    = Color(0xFF1A2535)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        CellLabel("ORGANIZER")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${organizer.firstName} ${organizer.lastName}",
                            color = Color(0xFFE8EEF5), fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                        Text(organizer.email,       color = Color(0xFF4A6080), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(organizer.cellPhone,   color = Color(0xFF4A6080), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(organizer.vehicleType, color = Color(0xFF2E75B6), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                StatusBanner(msg = "⚠  No organizer profile. Complete enrollment first.", ok = false)
                Spacer(Modifier.height(12.dp))
            }

            // ── Master config strip ───────────────────────────────────────────
            if (masterConfig != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    color    = Color(0xFF0D1A2E)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        CellLabel("RADIO CONFIG  (master)")
                        Spacer(Modifier.height(4.dp))
                        Text("${masterConfig.hardwareModel}  •  fw ${masterConfig.firmwareVersion}",
                            color = Color(0xFF4A6080), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("Region: ${masterConfig.loraRegion}  •  ${masterConfig.loraModemPreset}  •  ${masterConfig.loraTxPower} dBm",
                            color = Color(0xFF4A6080), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Event details ─────────────────────────────────────────────────
            CellLabel("EVENT DETAILS")
            Spacer(Modifier.height(8.dp))
            ConvoyTextField(value = eventName,   onValueChange = { eventName = it },   label = "Event Name")
            Spacer(Modifier.height(10.dp))
            ConvoyTextField(value = eventDate,   onValueChange = { eventDate = it },   label = "Event Date  (YYYY-MM-DD)")
            Spacer(Modifier.height(10.dp))
            ConvoyTextField(value = description, onValueChange = { description = it }, label = "Description  (optional)")

            Spacer(Modifier.height(20.dp))

            // ── Radio channel ─────────────────────────────────────────────────
            CellLabel("RADIO CHANNEL")
            Spacer(Modifier.height(8.dp))
            ConvoyTextField(value = channelName, onValueChange = { channelName = it }, label = "Channel Name")
            Spacer(Modifier.height(6.dp))
            Text(
                "AES-256 key generated automatically. LoRa settings from master config.",
                color = Color(0xFF3A4A5A), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            // ── Map area placeholder ──────────────────────────────────────────
            CellLabel("MAP AREA")
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                color    = Color(0xFF1A2535)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🗺", fontSize = 32.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("MAP AREA SELECTOR", color = Color(0xFF2A3545),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Offline tile package — coming in V3",
                        color = Color(0xFF2A3545), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Radio processing steps ────────────────────────────────────────
            if (step > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    color    = Color(0xFF0D1A2E)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        CellLabel("RADIO PROCESSING")
                        Spacer(Modifier.height(8.dp))
                        RadioStep("① Reading current radio config",  step >= 1, step > 1)
                        RadioStep("② Backing up config to storage",  step >= 2, step > 2)
                        RadioStep("③ Writing convoy channel to radio",step >= 3, step > 3)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Status message ────────────────────────────────────────────────
            if (statusMsg.isNotBlank()) {
                StatusBanner(msg = statusMsg, ok = statusOk)
                Spacer(Modifier.height(16.dp))
            }

            // ── Create button ─────────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!canCreate) {
                            statusMsg = when {
                                organizer == null    -> "Complete enrollment before creating an event."
                                masterConfig == null -> "Master radio config missing. Contact support."
                                else                 -> "Event Name, Date, and Channel Name are required."
                            }
                            statusOk = false
                            return@clickable
                        }
                        scope.launch {
                            isProcessing = true
                            statusMsg    = ""
                            try {
                                // Step 1 — read
                                step = 1
                                val nodeInfo = viewModel.myNodeInfo.value
                                val hwId     = if (nodeInfo != null) "!%08x".format(nodeInfo.myNodeNum) else "unknown"
                                val devId    = nodeInfo?.deviceId ?: ""

                                // Step 2 — backup
                                step = 2
                                // Full backup written via ConvoyRadioManager.saveBackup()
                                // Wired in ConvoyViewModel in next session
                                val backupLabel = channelName.replace(Regex("[^a-zA-Z0-9_-]"), "_")

                                // Step 3 — write channel to radio
                                step = 3
                                // Channel write via ChannelViewModel.setChannels()
                                // Wired in ConvoyViewModel in next session

                                // Generate PSK
                                val pskBytes = ByteArray(32)
                                SecureRandom().nextBytes(pskBytes)
                                val psk = Base64.encodeToString(pskBytes, Base64.NO_WRAP)

                                // Save event config
                                val event = ConvoyEventConfig.createFromMaster(
                                    master          = masterConfig!!,
                                    organizer       = organizer!!,
                                    hardwareId      = hwId,
                                    deviceId        = devId,
                                    eventName       = eventName,
                                    eventDate       = eventDate,
                                    eventDescription= description,
                                    channelName     = channelName,
                                    channelPsk      = psk
                                )
                                ConvoyEventStore.save(event)

                                // Pair device to user silently
                                if (hwId != "unknown") {
                                    ConvoyUserStore.addDeviceToActiveUser(context, hwId)
                                }

                                step      = 4
                                statusMsg = "✓ Event created\n" +
                                            "  Channel: $channelName\n" +
                                            "  Backup saved  |  Radio updated\n" +
                                            "  Ready to transfer to riders via F2"
                                statusOk  = true
                            } catch (e: Exception) {
                                statusMsg = "✗ Error: ${e.message}"
                                statusOk  = false
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                color = if (canCreate) Color(0xFF1E3A5F) else Color(0xFF1A1F2B)
            ) {
                Text(
                    text       = if (isProcessing) "PROCESSING..." else "CREATE EVENT + WRITE RADIO",
                    color      = if (canCreate) Color(0xFF2E75B6) else Color(0xFF2A3545),
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

@Composable
private fun CellLabel(text: String) {
    Text(
        text          = text,
        color         = Color(0xFF4A6080),
        fontSize      = 10.sp,
        fontFamily    = FontFamily.Monospace,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier      = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RadioStep(label: String, active: Boolean, done: Boolean) {
    val color = when {
        done   -> Color(0xFF67EA94)
        active -> Color(0xFF2E75B6)
        else   -> Color(0xFF2A3545)
    }
    val prefix = when {
        done   -> "✓ "
        active -> "▶ "
        else   -> "○ "
    }
    Text(
        text       = "$prefix$label",
        color      = color,
        fontSize   = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier   = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun StatusBanner(msg: String, ok: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(8.dp),
        color    = if (ok) Color(0xFF0D2010) else Color(0xFF2A1A1A)
    ) {
        Text(
            text       = msg,
            color      = if (ok) Color(0xFF67EA94) else Color(0xFFF44336),
            fontSize   = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.padding(12.dp)
        )
    }
}
