package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
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
import com.geeksville.mesh.model.UIViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geeksville.mesh.ui.sharing.ChannelViewModel
import kotlinx.coroutines.launch

@Composable
fun ConvoyApplyRadioScreen(
    onDone: () -> Unit,
    convoyViewModel: ConvoyViewModel = hiltViewModel(),
    navController: androidx.navigation.NavController? = null,
    channelViewModel: ChannelViewModel = hiltViewModel(),
    uiViewModel: UIViewModel = hiltViewModel()
) {
    val context      = LocalContext.current
    val scope        = rememberCoroutineScope()
    val applyList    = remember { ConvoyApplyList.load(context) }
    val masterConfig = remember { ConvoyMasterConfig.load(context) }
    val rides        = remember { ConvoyEventStore.loadAll(context).sortedBy { it.eventDate } }

    val myNodeInfo  by uiViewModel.myNodeInfo.collectAsStateWithLifecycle()
    val localConfig by channelViewModel.localConfig.collectAsStateWithLifecycle()
    val channels    by channelViewModel.channels.collectAsStateWithLifecycle()
    val isConnected = myNodeInfo != null
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val writerState     by ConvoyRadioWriter.state.collectAsStateWithLifecycle()

    var applyMode    by remember { mutableStateOf("MASTER") }
    var selectedRide by remember { mutableStateOf<ConvoyEventConfig?>(null) }
    var longName     by remember { mutableStateOf("") }
    var showConfirm  by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var archiveLog   by remember { mutableStateOf("") }
    var archiveOk    by remember { mutableStateOf(true) }
    var proceedDone  by remember { mutableStateOf(false) }

    var confirmLoraOpen     by remember { mutableStateOf(false) }
    var confirmChannelOpen  by remember { mutableStateOf(false) }
    var confirmDeviceOpen   by remember { mutableStateOf(false) }
    var confirmPositionOpen by remember { mutableStateOf(false) }
    var confirmDisplayOpen  by remember { mutableStateOf(false) }
    var confirmModuleOpen   by remember { mutableStateOf(false) }

    val canReview = isConnected && (applyMode == "MASTER" || selectedRide != null)

    val ourNodeInfo    by convoyViewModel.ourNodeInfo.collectAsStateWithLifecycle()
    val workingLongName = when {
        longName.isNotBlank()              -> longName
        ourNodeInfo?.user?.long_name?.isNotBlank() == true -> ourNodeInfo!!.user.long_name
        else -> myNodeInfo?.let { "!%08x".format(it.myNodeNum) } ?: "—"
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // Header
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = Color(0xFF97D5A5), fontSize = 20.sp,
                    modifier = Modifier.clickable { onDone() }.padding(end = 12.dp))
                Text("APPLY RADIO CONFIG", color = Color(0xFF97D5A5), fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(6.dp))

            // Radio status
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                color = if (isConnected) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isConnected) "● CONNECTED" else "○ NO RADIO",
                        color = if (isConnected) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    if (isConnected) {
                        Text("  ${myNodeInfo?.model ?: ""}  fw ${myNodeInfo?.firmwareVersion ?: ""}",
                            color = Color(0xFF8B938A), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Text("  Connect radio via Bluetooth to proceed",
                            color = Color(0xFF8B938A), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262B26)))
            Spacer(Modifier.height(12.dp))

            // Operation selector
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MASTER" to "Apply Master Config", "RIDE" to "Apply Ride").forEach { (mode, label) ->
                    Surface(
                        modifier = Modifier.weight(1f).clickable {
                            applyMode = mode; selectedRide = null
                            showConfirm = false; proceedDone = false; archiveLog = ""
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (applyMode == mode) Color(0xFF2E75B6) else Color(0xFF1C211C)
                    ) {
                        Text(label, color = if (applyMode == mode) Color.White else Color(0xFF8B938A),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Ride picker
            if (applyMode == "RIDE") {
                if (rides.isEmpty()) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2A1A1A)) {
                        Text("No rides found. Create a ride first.", color = Color(0xFFFFB4AB),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp))
                    }
                } else {
                    Text("SELECT RIDE", color = Color(0xFF8B938A), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                        modifier = Modifier.padding(bottom = 6.dp))
                    rides.forEach { ride ->
                        val isSelected = selectedRide?.eventId == ride.eventId
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clickable { selectedRide = ride; showConfirm = false; proceedDone = false },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF1F4E79) else Color(0xFF1C211C)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(ride.eventName, color = Color(0xFFDFE4DC), fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Text(ride.eventDate, color = Color(0xFF8B938A), fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                                Text(ride.channelName, color = Color(0xFF97D5A5), fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // REVIEW CHANGES button
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = canReview && !isProcessing) {
                    showConfirm = true
                },
                shape = RoundedCornerShape(10.dp),
                color = when {
                    !isConnected -> Color(0xFF2A1A1A)
                    !canReview   -> Color(0xFF101510)
                    else         -> Color(0xFF1F4E79)
                }
            ) {
                Text(
                    text = when {
                        !isConnected -> "⚠ CONNECT RADIO TO PROCEED"
                        !canReview   -> "SELECT A RIDE TO PROCEED"
                        else         -> "REVIEW CHANGES →"
                    },
                    color = when {
                        !isConnected -> Color(0xFFFFB4AB)
                        !canReview   -> Color(0xFF262B26)
                        else         -> Color.White
                    },
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            }
            Spacer(Modifier.height(16.dp))

            // Confirmation accordion
            if (showConfirm) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E75B6)))
                Spacer(Modifier.height(12.dp))
                Text("CHANGES TO BE APPLIED", color = Color(0xFF2E75B6), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))

                val currentRegion    = localConfig.lora?.region?.name ?: "—"
                val currentPreset    = localConfig.lora?.modem_preset?.name ?: "—"
                val currentHopLimit  = localConfig.lora?.hop_limit?.toString() ?: "—"
                val currentTxEnabled = localConfig.lora?.tx_enabled?.toString() ?: "—"
                val currentTxPower   = localConfig.lora?.tx_power?.toString() ?: "—"
                val currentChannel   = channels.settings.firstOrNull()?.name ?: "—"

                // Long Name — shown here with working config value
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1C211C)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("LONG NAME", color = Color(0xFF8B938A), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                        Text("Working config default: $workingLongName", color = Color(0xFF4A6080),
                            fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(value = longName, onValueChange = { longName = it },
                            placeholder = { Text(workingLongName, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, color = Color(0xFF8B938A)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Channel name — large and prominent, always visible
                val displayChannelName = if (applyMode == "RIDE") selectedRide?.channelName ?: "—"
                                         else masterConfig?.primaryChannelName ?: "—"
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0D1A2A)) {
                    Column(modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NEW CHANNEL", color = Color(0xFF4A6080), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(displayChannelName, color = Color(0xFF4DA6FF), fontSize = 26.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text("AES-256 ENCRYPTED", color = Color(0xFF4A6080), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                ConfirmHeader()
                Spacer(Modifier.height(6.dp))

                // LoRa
                ApplyAccordionHeader("LORA", confirmLoraOpen) { confirmLoraOpen = !confirmLoraOpen }
                AnimatedVisibility(visible = confirmLoraOpen) {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        listOf(
                            Triple(LoraField.REGION,        currentRegion,    masterConfig?.loraRegion ?: "—"),
                            Triple(LoraField.MODEM_PRESET,  currentPreset,    masterConfig?.loraModemPreset ?: "—"),
                            Triple(LoraField.BANDWIDTH,     "—",              masterConfig?.loraBandwidth?.toString() ?: "—"),
                            Triple(LoraField.SPREAD_FACTOR, "—",              masterConfig?.loraSpreadFactor?.toString() ?: "—"),
                            Triple(LoraField.CODING_RATE,   "—",              masterConfig?.loraCodingRate?.toString() ?: "—"),
                            Triple(LoraField.HOP_LIMIT,     currentHopLimit,  masterConfig?.loraHopLimit?.toString() ?: "—"),
                            Triple(LoraField.TX_ENABLED,    currentTxEnabled, masterConfig?.loraTxEnabled?.toString() ?: "—"),
                            Triple(LoraField.TX_POWER,      currentTxPower,   masterConfig?.loraTxPower?.toString() ?: "—"),
                        ).forEach { (field, current, masterVal) ->
                            val checked = field in applyList.loraFields
                            ConfirmRow(field.label, current, if (checked) masterVal else current,
                                if (checked) "CHECKLIST" else "ORIGINAL RADIO", checked)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Channel
                ApplyAccordionHeader("CHANNEL", confirmChannelOpen) { confirmChannelOpen = !confirmChannelOpen }
                AnimatedVisibility(visible = confirmChannelOpen) {
                    val src   = if (applyMode == "RIDE") "RIDE FILE" else "MASTER CFG"
                    val newCh = if (applyMode == "RIDE") selectedRide?.channelName ?: "—"
                               else masterConfig?.primaryChannelName ?: "—"
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        ConfirmRow("Channel Name", currentChannel, newCh, src, true)
                        ConfirmRow("Encryption Key", "****", "****", src, true)
                        ChannelField.values()
                            .filter { it != ChannelField.CHANNEL_NAME && it != ChannelField.ENCRYPTION_KEY }
                            .forEach { field ->
                                val checked = field in applyList.channelFields
                                ConfirmRow(field.label, "—", if (checked) "From master" else "—",
                                    if (checked) "CHECKLIST" else "ORIGINAL RADIO", checked)
                            }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Device
                ApplyAccordionHeader("DEVICE", confirmDeviceOpen) { confirmDeviceOpen = !confirmDeviceOpen }
                AnimatedVisibility(visible = confirmDeviceOpen) {
                    val displayName = longName.ifBlank { workingLongName }
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        ConfirmRow("Long Name", workingLongName, displayName,
                            if (longName.isBlank()) "WORKING CONFIG" else "EDITED", true)
                        DeviceField.values().filter { it.name != "LONG_NAME" }.forEach { field ->
                            val checked = field in applyList.deviceFields
                            ConfirmRow(field.label, "—", if (checked) "From master" else "—",
                                if (checked) "CHECKLIST" else "ORIGINAL RADIO", checked)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Position
                ApplyAccordionHeader("POSITION", confirmPositionOpen) { confirmPositionOpen = !confirmPositionOpen }
                AnimatedVisibility(visible = confirmPositionOpen) {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        PositionField.values().forEach { field ->
                            val checked = field in applyList.positionFields
                            ConfirmRow(field.label, "—", if (checked) "From master" else "—",
                                if (checked) "CHECKLIST" else "ORIGINAL RADIO", checked)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Display
                ApplyAccordionHeader("DISPLAY", confirmDisplayOpen) { confirmDisplayOpen = !confirmDisplayOpen }
                AnimatedVisibility(visible = confirmDisplayOpen) {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        DisplayField.values().forEach { field ->
                            val checked = field in applyList.displayFields
                            ConfirmRow(field.label, "—", if (checked) "From master" else "—",
                                if (checked) "CHECKLIST" else "ORIGINAL RADIO", checked)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Modules
                ApplyAccordionHeader("MODULES", confirmModuleOpen) { confirmModuleOpen = !confirmModuleOpen }
                AnimatedVisibility(visible = confirmModuleOpen) {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        ModuleField.values().forEach { field ->
                            val checked = field in applyList.moduleFields
                            ConfirmRow(field.label, "—", if (checked) "From master" else "—",
                                if (checked) "CHECKLIST" else "ORIGINAL RADIO", checked)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ── Writer state log ─────────────────────────────────────────
                if (writerState.step != WriteStep.IDLE) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0D1A0D)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            writerState.log.forEach { entry ->
                                Text(entry, color = Color(0xFF97D5A5), fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // BLE status during pauses
                    if (writerState.step == WriteStep.PAUSE_1 || writerState.step == WriteStep.PAUSE_2) {
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1C211C)) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (writerState.bleConnected) "● BLE RECONNECTED" else "○ BLE DISCONNECTED — waiting for radio...",
                                    color = if (writerState.bleConnected) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // BLE monitor — update writer during pauses
                LaunchedEffect(connectionState) {
                    ConvoyRadioWriter.updateBleStatus(
                        connectionState.toString().contains("Connected", ignoreCase = true)
                    )
                }

                // PROCEED TO UPDATE / CANCEL buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f).clickable { showConfirm = false; onDone() },
                        shape = RoundedCornerShape(10.dp), color = Color(0xFF2A1A1A)
                    ) {
                        Text("CANCEL", color = Color(0xFFFFB4AB), fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    Surface(
                        modifier = Modifier.weight(1f).clickable(enabled = !isProcessing) {
                            val ni = myNodeInfo ?: return@clickable
                            // ── Rules Engine — build WorkingConfig from master + radio per apply list ──
                            val m   = masterConfig  // master config values
                            val r   = localConfig   // current radio values
                            val ch  = channels.settings.firstOrNull() // primary channel
                            val al  = applyList     // apply list rules
                            val lora = r.lora
                            val pos  = r.position
                            val dev  = r.device
                            val disp = r.display

                            // Channel source for RIDE vs MASTER mode
                            val rideChannel = applyMode == "RIDE"
                            val channelName = if (rideChannel) selectedRide?.channelName ?: "" else m?.primaryChannelName ?: ""
                            val channelPsk  = if (rideChannel) selectedRide?.channelPsk  ?: "" else m?.primaryChannelPsk  ?: ""

                            fun <T> rule(field: Any, masterVal: T, radioVal: T): T =
                                if (field in al.loraFields || field in al.deviceFields ||
                                    field in al.channelFields || field in al.positionFields ||
                                    field in al.displayFields || field in al.moduleFields)
                                    masterVal else radioVal

                            val wconfig = WorkingConfig(
                                nodeId                   = "!%08x".format(ni.myNodeNum),
                                // ── Device ────────────────────────────────────────────────────
                                longName                 = longName.ifBlank { workingLongName },
                                shortName                = if (DeviceField.SHORT_NAME in al.deviceFields) m?.shortName ?: "" else ourNodeInfo?.user?.short_name ?: "",
                                nodeRole                 = if (DeviceField.NODE_ROLE in al.deviceFields) m?.nodeRole ?: "CLIENT" else dev?.role?.name ?: "CLIENT",
                                isManaged                = if (DeviceField.IS_MANAGED in al.deviceFields) m?.isManaged ?: false else dev?.is_managed ?: false,
                                serialEnabled            = if (DeviceField.SERIAL_ENABLED in al.deviceFields) m?.serialEnabled ?: false else dev?.serial_enabled ?: false,
                                // ── LoRa ──────────────────────────────────────────────────────
                                loraRegion               = if (LoraField.REGION in al.loraFields) m?.loraRegion ?: "US" else lora?.region?.name ?: "US",
                                loraModemPreset          = if (LoraField.MODEM_PRESET in al.loraFields) m?.loraModemPreset ?: "LONG_FAST" else lora?.modem_preset?.name ?: "LONG_FAST",
                                loraBandwidth            = if (LoraField.BANDWIDTH in al.loraFields) m?.loraBandwidth ?: 0 else lora?.bandwidth ?: 0,
                                loraSpreadFactor         = if (LoraField.SPREAD_FACTOR in al.loraFields) m?.loraSpreadFactor ?: 0 else lora?.spread_factor ?: 0,
                                loraCodingRate           = if (LoraField.CODING_RATE in al.loraFields) m?.loraCodingRate ?: 0 else lora?.coding_rate ?: 0,
                                loraHopLimit             = if (LoraField.HOP_LIMIT in al.loraFields) m?.loraHopLimit ?: 3 else lora?.hop_limit ?: 3,
                                loraTxEnabled            = if (LoraField.TX_ENABLED in al.loraFields) m?.loraTxEnabled ?: true else lora?.tx_enabled ?: true,
                                loraTxPower              = if (LoraField.TX_POWER in al.loraFields) m?.loraTxPower ?: 27 else lora?.tx_power ?: 27,
                                loraChannelNum           = if (LoraField.CHANNEL_NUM in al.loraFields) m?.loraChannelNum ?: 0 else lora?.channel_num ?: 0,
                                // ── Channel ───────────────────────────────────────────────────
                                channelName              = channelName,
                                channelPsk               = channelPsk,
                                channelId                = if (ChannelField.CHANNEL_ID in al.channelFields) m?.channelId ?: 0 else ch?.id ?: 0,
                                channelUplinkEnabled     = if (ChannelField.UPLINK_ENABLED in al.channelFields) m?.channelUplinkEnabled ?: false else ch?.uplink_enabled ?: false,
                                channelDownlinkEnabled   = if (ChannelField.DOWNLINK_ENABLED in al.channelFields) m?.channelDownlinkEnabled ?: false else ch?.downlink_enabled ?: false,
                                positionPrecision        = if (ChannelField.MODULE_SETTINGS in al.channelFields) m?.positionPrecision ?: 32 else ch?.module_settings?.position_precision ?: 0,
                                channelIsMuted           = if (ChannelField.MODULE_SETTINGS in al.channelFields) m?.channelIsMuted ?: false else ch?.module_settings?.is_muted ?: false,
                                // ── Position ──────────────────────────────────────────────────
                                gpsEnabled               = if (PositionField.GPS_ENABLED in al.positionFields) m?.gpsEnabled ?: true else pos?.gps_mode?.name != "DISABLED",
                                gpsMode                  = if (PositionField.GPS_MODE in al.positionFields) m?.gpsMode ?: "ENABLED" else pos?.gps_mode?.name ?: "ENABLED",
                                gpsUpdateSecs            = if (PositionField.GPS_UPDATE_INTERVAL in al.positionFields) m?.gpsUpdateSecs ?: 1 else pos?.gps_update_interval ?: 1,
                                gpsAttemptTime           = if (PositionField.GPS_ATTEMPT_TIME in al.positionFields) m?.gpsAttemptTime ?: 900 else pos?.gps_attempt_time ?: 900,
                                positionBroadcastSecs    = if (PositionField.POSITION_BROADCAST_SECS in al.positionFields) m?.positionBroadcastSecs ?: 5 else pos?.position_broadcast_secs ?: 5,
                                smartPositionEnabled     = if (PositionField.POSITION_BROADCAST_SMART in al.positionFields) m?.smartPositionEnabled ?: true else pos?.position_broadcast_smart_enabled ?: true,
                                fixedPosition            = if (PositionField.FIXED_POSITION in al.positionFields) m?.fixedPosition ?: false else pos?.fixed_position ?: false,
                                positionFlags            = if (PositionField.POSITION_FLAGS in al.positionFields) m?.positionFlags ?: 811 else pos?.position_flags ?: 811,
                                smartMinIntervalSecs     = m?.smartMinIntervalSecs ?: 3,
                                smartMinDistanceMeters   = m?.smartMinDistanceMeters ?: 10,
                                // ── Display ───────────────────────────────────────────────────
                                displayUnits             = if (DisplayField.UNITS in al.displayFields) m?.displayUnits ?: 0 else disp?.units?.ordinal ?: 0,
                                screenTimeout            = if (DisplayField.SCREEN_TIMEOUT in al.displayFields) m?.screenTimeout ?: 0 else disp?.screen_on_secs ?: 0,
                                autoScreenBrightness     = if (DisplayField.AUTO_SCREEN_BRIGHTNESS in al.displayFields) m?.autoScreenBrightness ?: false else false,
                                compassNorthTop          = if (DisplayField.COMPASS_NORTH_TOP in al.displayFields) m?.compassNorthTop ?: false else disp?.compass_north_top ?: false,
                                // ── Module ────────────────────────────────────────────────────
                                telemetryDeviceInterval  = if (ModuleField.TELEMETRY_DEVICE_INTERVAL in al.moduleFields) m?.telemetryDeviceInterval ?: 0 else 0,
                                telemetryEnvInterval     = if (ModuleField.TELEMETRY_ENV_INTERVAL in al.moduleFields) m?.telemetryEnvInterval ?: 0 else 0,
                                telemetryEnvEnabled      = if (ModuleField.TELEMETRY_ENV_ENABLED in al.moduleFields) m?.telemetryEnvEnabled ?: false else false,
                                mqttEnabled              = if (ModuleField.MQTT_ENABLED in al.moduleFields) m?.mqttEnabled ?: false else false,
                                mqttAddress              = if (ModuleField.MQTT_ADDRESS in al.moduleFields) m?.mqttAddress ?: "" else "",
                                mqttUsername             = if (ModuleField.MQTT_USERNAME in al.moduleFields) m?.mqttUsername ?: "" else "",
                                mqttEncryptionEnabled    = if (ModuleField.MQTT_ENCRYPTION_ENABLED in al.moduleFields) m?.mqttEncryptionEnabled ?: false else false,
                                mqttJsonEnabled          = if (ModuleField.MQTT_JSON_ENABLED in al.moduleFields) m?.mqttJsonEnabled ?: false else false,
                                serialModuleEnabled      = if (ModuleField.SERIAL_ENABLED in al.moduleFields) m?.serialModuleEnabled ?: false else false,
                                serialBaud               = if (ModuleField.SERIAL_BAUD in al.moduleFields) m?.serialBaud ?: 0 else 0,
                                extNotificationEnabled   = if (ModuleField.EXT_NOTIFICATION_ENABLED in al.moduleFields) m?.extNotificationEnabled ?: false else false,
                                extNotificationAlertMsg  = if (ModuleField.EXT_NOTIFICATION_ALERT_MSG in al.moduleFields) m?.extNotificationAlertMsg ?: false else false,
                                rangeTestEnabled         = if (ModuleField.RANGE_TEST_ENABLED in al.moduleFields) m?.rangeTestEnabled ?: false else false,
                                storeForwardEnabled      = if (ModuleField.STORE_FORWARD_ENABLED in al.moduleFields) m?.storeForwardEnabled ?: false else false,
                                neighborInfoEnabled      = if (ModuleField.NEIGHBOR_INFO_ENABLED in al.moduleFields) m?.neighborInfoEnabled ?: false else false,
                                detectionSensorEnabled   = if (ModuleField.DETECTION_SENSOR_ENABLED in al.moduleFields) m?.detectionSensorEnabled ?: false else false,
                                audioEnabled             = if (ModuleField.AUDIO_ENABLED in al.moduleFields) m?.audioEnabled ?: false else false,
                                source                   = if (applyMode == "RIDE") "RIDE:${selectedRide?.eventId}" else "MASTER"
                            )
                            scope.launch {
                            android.util.Log.i("ConvoyApply", "=== PROCEED TO UPDATE started mode=$applyMode ===")

                            // ── Step 1: Archive current radio as binary .cfg ──────────────
                            android.util.Log.i("ConvoyApply", "Step 1: Archiving current radio state...")
                            val archiveResult = ConvoyMasterApply.archiveCurrentRadio(
                                context, "!%08x".format(ni.myNodeNum), convoyViewModel
                            )
                            if (archiveResult.isSuccess) {
                                android.util.Log.i("ConvoyApply", "Step 1: Archive complete -> ${archiveResult.getOrThrow()}")
                            } else {
                                android.util.Log.e("ConvoyApply", "Step 1: Archive FAILED -> ${archiveResult.exceptionOrNull()?.message}")
                            }

                            // Step 2: Build binary from WorkingConfig
                            android.util.Log.i("ConvoyApply", "Step 2: Building binary from WorkingConfig mode=$applyMode...")
                            val profile = ConvoyProfileBuilder.buildProfile(wconfig).copy(short_name = null)
                            android.util.Log.i("ConvoyApply", "Step 2: Binary built OK")
                            // Step 3: Install binary - radio will reboot
                            android.util.Log.i("ConvoyApply", "Step 3: Installing to radio...")
                            convoyViewModel.installProfileToRadio(ni.myNodeNum, profile)
                            android.util.Log.i("ConvoyApply", "Step 3: Install complete - radio rebooting")

                            // ── Step 6: Set WorkingConfig and navigate to Verify ──────────
                            android.util.Log.i("ConvoyApply", "Step 6: Setting WorkingConfig and navigating to Verify...")
                            convoyViewModel.setWorkingConfig(wconfig)
                            navController?.navigate(org.meshtastic.core.navigation.ConvoyRoutes.ConvoyReconnectWait)
                            android.util.Log.i("ConvoyApply", "=== PROCEED TO UPDATE complete ===")
                            } // end scope.launch
                        },
                        shape = RoundedCornerShape(10.dp), color = Color(0xFF15512C)
                    ) {
                        Text("PROCEED TO UPDATE", color = Color(0xFF97D5A5), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ConfirmHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("FIELD", color = Color(0xFF4A6080), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
        Text("CURRENT", color = Color(0xFF4A6080), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
        Text("NEW VALUE", color = Color(0xFF4A6080), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
        Text("RULE", color = Color(0xFF4A6080), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun ConfirmRow(field: String, current: String, newVal: String, rule: String, changing: Boolean) {
    val ruleColor = when (rule) {
        "CHECKLIST"      -> Color(0xFF97D5A5)
        "MASTER CFG"     -> Color(0xFF2E75B6)
        "RIDE FILE"      -> Color(0xFFF9C835)
        "ORIGINAL RADIO" -> Color(0xFF8B938A)
        "WORKING CONFIG" -> Color(0xFFCAC4D0)
        "EDITED"         -> Color(0xFFFFB74D)
        else             -> Color(0xFF8B938A)
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        color = if (changing) Color(0xFF1C211C) else Color(0xFF101510)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(field, color = if (changing) Color(0xFFDFE4DC) else Color(0xFF8B938A),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
            Text(current, color = Color(0xFF8B938A), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
            Text(newVal, color = if (changing) Color(0xFFDFE4DC) else Color(0xFF8B938A),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
            Text(rule, color = ruleColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
        }
    }
}

@Composable
private fun ApplyAccordionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = if (expanded) Color(0xFF15512C) else Color(0xFF262B26)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = if (expanded) Color(0xFF97D5A5) else Color(0xFFDFE4DC),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(if (expanded) "▲" else "▼", color = Color(0xFF8B938A), fontSize = 10.sp)
        }
    }
}
