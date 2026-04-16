package com.geeksville.mesh.convoy

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.security.SecureRandom

// ============================================================
// ConvoyCreateRideScreen.kt
// V3.0 Phase B — Create Ride
// Merges V2.4 radio functions with V3.0 ride fields
// Radio: inline save if radio connected, pended if not
// Phase C: POST /rides, map area from Map Manager
// ============================================================

@Composable
fun ConvoyCreateRideScreen(
    viewModel: ConvoyViewModel? = null,
    onRideCreated: () -> Unit = {},
    onNavigateToFieldRadio: () -> Unit = {},
    onApplyMasterConfig: () -> Unit = {},
    onArchiveRestore: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // ── Ride fields ───────────────────────────────────────────────────────────
    var rideName      by remember { mutableStateOf("") }
    var rideDate      by remember { mutableStateOf("") }
    var arriveTime    by remember { mutableStateOf("") }
    var rolloutTime   by remember { mutableStateOf("") }
    var city          by remember { mutableStateOf("") }
    var state         by remember { mutableStateOf("") }
    var trailhead     by remember { mutableStateOf("") }
    var zipCode       by remember { mutableStateOf(ConvoySessionManager.getZipCode(context)) }
    var description   by remember { mutableStateOf("") }
    var isPublic      by remember { mutableStateOf(false) }
    var reminderDays  by remember { mutableStateOf("5") }

    // ── Radio / config state ──────────────────────────────────────────────────
    val organizer    = remember { ConvoyUserStore.getActiveUser(context) }
    val masterConfig = remember { ConvoyMasterConfig.load(context) }
    var isProcessing by remember { mutableStateOf(false) }
    var step         by remember { mutableStateOf(0) }
    var savedPsk     by remember { mutableStateOf("") }
    var statusMsg    by remember { mutableStateOf("") }
    var statusOk     by remember { mutableStateOf(true) }

    // ── Auto-generate channel name from ride name ─────────────────────────────
    val channelName = remember(rideName) {
        if (rideName.isNotBlank()) {
            val suffix = (1..4).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }.joinToString("")
            "CONVOY-$suffix"
        } else "CONVOY-XXXX"
    }

    val canSave = rideName.isNotBlank() && rideDate.isNotBlank() &&
                  arriveTime.isNotBlank() && rolloutTime.isNotBlank() &&
                  city.isNotBlank() && trailhead.isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1A3050))
                .clickable { onBack() }
                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("\u2190 BACK", color = Color(0xFF4AB8E8), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("CREATE RIDE", color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Color(0xFF4AB8E8))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── ORGANIZER STRIP ───────────────────────────────────────────────
            if (organizer != null) {
                CreateSectionLabel("ORGANIZER")
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F2035)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${organizer.firstName} ${organizer.lastName}",
                        color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(organizer.email, color = Color(0xFF4AB8E8), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A1A1A)).padding(12.dp)) {
                    Text("\u26a0 Complete enrollment before creating a ride.",
                        color = Color(0xFFF44336), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // ── MASTER CONFIG STRIP ───────────────────────────────────────────
            if (masterConfig != null) {
                CreateSectionLabel("RADIO CONFIG — MASTER")
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1628)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${masterConfig.hardwareModel}  •  fw ${masterConfig.firmwareVersion}",
                        color = Color(0xFF445566), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("${masterConfig.loraRegion}  •  ${masterConfig.loraModemPreset}  •  ${masterConfig.loraTxPower} dBm",
                        color = Color(0xFF445566), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Channel: $channelName",
                        color = Color(0xFF4AB8E8), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Encryption: AES-256 auto-generated",
                        color = Color(0xFF22C55E), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A1A1A)).padding(12.dp)) {
                    Text("\u26a0 Master radio config not found.",
                        color = Color(0xFFF44336), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // ── RIDE INFO ─────────────────────────────────────────────────────
            CreateSectionLabel("RIDE INFO")
            CreateField("Ride Name *", rideName, "e.g. Sunday Desert Run") { rideName = it }
            CreateField("Date *", rideDate, "e.g. April 13, 2026") { rideDate = it }

            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    CreateField("Arrive Time *", arriveTime, "7:30 AM") { arriveTime = it }
                }
                Box(modifier = Modifier.weight(1f)) {
                    CreateField("Rollout Time *", rolloutTime, "8:00 AM") { rolloutTime = it }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(2f)) {
                    CreateField("City *", city, "St. George") { city = it }
                }
                Box(modifier = Modifier.weight(1f)) {
                    CreateField("State *", state, "UT") { state = it }
                }
            }

            CreateField("Trailhead Name *", trailhead, "Gooseberry Mesa Trailhead") { trailhead = it }
            CreateField("Zip Code", zipCode, "Ride area zip") { zipCode = it }
            CreateField("Description (optional)", description, "Meeting point, notes...") { description = it }
            CreateField("Reminder Days", reminderDays, "5") { reminderDays = it }

            // ── PUBLIC / PRIVATE ──────────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PUBLIC RIDE", color = Color(0xFFAABBCC), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Visible in Public Rides Near Me",
                        color = Color(0xFF445566), fontSize = 10.sp)
                }
                Switch(checked = isPublic, onCheckedChange = { isPublic = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color(0xFF4AB8E8),
                        checkedTrackColor   = Color(0xFF1A3050),
                        uncheckedThumbColor = Color(0xFF445566),
                        uncheckedTrackColor = Color(0xFF0A1628)))
            }

            // ── MAP AREA — Phase C ────────────────────────────────────────────
            CreateSectionLabel("MAP AREA — PHASE C")
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A0A00)).padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("SET MAP AREA", color = Color(0xFFF97316), fontSize = 12.sp,
                    fontWeight = FontWeight.Bold)
                Text("\u26a0 PENDING — Phase C", color = Color(0xFFF97316), fontSize = 10.sp)
            }

            // ── RADIO PROCESSING STEPS ────────────────────────────────────────
            if (step > 0) {
                CreateSectionLabel("RADIO PROCESSING")
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1628)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RadioStep("Reading device info",         step >= 1, step > 1)
                    RadioStep("Generating channel and PSK",  step >= 2, step > 2)
                    RadioStep("Saving ride config",          step >= 3, step > 3)
                }
            }

            // ── SUCCESS ───────────────────────────────────────────────────────
            if (step == 4) {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D2010)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\u2713 RIDE SAVED", color = Color(0xFF22C55E), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Channel: $channelName", color = Color(0xFF4AB8E8), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    Text("PSK: AES-256 secured", color = Color(0xFF22C55E), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2E75B6))
                        .clickable { onRideCreated() }
                        .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text("PROCEED \u2192", color = Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // ── STATUS MESSAGE ────────────────────────────────────────────────
            if (statusMsg.isNotBlank() && step != 4) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (statusOk) Color(0xFF0D2010) else Color(0xFF2A1A1A))
                    .padding(12.dp)) {
                    Text(statusMsg,
                        color = if (statusOk) Color(0xFF22C55E) else Color(0xFFF44336),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // ── SAVE BUTTONS ──────────────────────────────────────────────────
            if (step != 4) {
                // SAVE AS PENDING
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (canSave) Color(0xFF1A3050) else Color(0xFF0A1628))
                    .clickable(enabled = canSave && !isProcessing) {
                        scope.launch {
                            isProcessing = true
                            statusMsg = ""
                            try {
                                step = 1
                                val nodeInfo = viewModel?.myNodeInfo?.value
                                val hwId = if (nodeInfo != null) "!%08x".format(nodeInfo.myNodeNum) else "unknown"
                                val devId = nodeInfo?.deviceId ?: ""

                                step = 2
                                val pskBytes = ByteArray(32)
                                SecureRandom().nextBytes(pskBytes)
                                val psk = Base64.encodeToString(pskBytes, Base64.NO_WRAP)
                                savedPsk = psk

                                step = 3
                                val event = ConvoyEventConfig.createFromMaster(
                                    master           = masterConfig ?: return@launch,
                                    organizer        = organizer ?: return@launch,
                                    hardwareId       = hwId,
                                    deviceId         = devId,
                                    eventName        = rideName,
                                    eventDate        = rideDate,
                                    eventDescription = description,
                                    channelName      = channelName,
                                    channelPsk       = psk
                                )
                                ConvoyEventStore.save(context, event)
                                if (hwId != "unknown") {
                                    ConvoyUserStore.addDeviceToActiveUser(context, hwId)
                                }
                                step = 4
                                statusOk = true
                            } catch (e: Exception) {
                                statusMsg = "\u2717 Error: ${e.message}"
                                statusOk = false
                                step = 0
                            } finally {
                                isProcessing = false
                            }
                        }
                    }
                    .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(
                        if (isProcessing) "SAVING..." else "SAVE AS PENDING",
                        color = if (canSave) Color(0xFF4AB8E8) else Color(0xFF445566),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }

                // OPEN RIDE — enabled only after save (step == 4 handled above, this is pre-save hint)
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1628))
                    .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text("OPEN RIDE — Save first",
                        color = Color(0xFF2A4060), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }

                // ── PUBLIC RIDE ENROLLMENT PROCESS ────────────────────────────
                // Triggered when organizer taps OPEN RIDE on a PUBLIC ride.
                // Auto-creates invited enrollments for ALL organizer followers.
                // Sends email invite to all followers simultaneously.
                // Ride appears in Section 4 for non-followers.
                // Phase B: POST /rides triggers server-side follower enrollment.
                // STUB — wired when OPEN RIDE button is implemented.
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1628)).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PUBLIC RIDE ENROLLMENT",
                            color = Color(0xFF4AB8E8), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Auto-invites all followers on OPEN RIDE",
                            color = Color(0xFF2A4060), fontSize = 9.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Email sent to all followers simultaneously",
                            color = Color(0xFF2A4060), fontSize = 9.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text("PHASE B", color = Color(0xFF2A4060), fontSize = 8.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                }

                // ── PRIVATE RIDE ENROLLMENT PROCESS ──────────────────────────
                // ConvoyPrivateInviteScreen.kt — Phase B
                // Three sub-processes below — all stubbed.
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1628)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("PRIVATE RIDE ENROLLMENT",
                            color = Color(0xFF4AB8E8), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("STUB", color = Color(0xFF2A4060), fontSize = 8.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                    }

                    // Sub-process 1: Rider Selection
                    // Name search across organizer follower list.
                    // Tap name to select — adds to invite list.
                    // Manual email entry for riders not in follower list.
                    // Selected riders shown in scrollable chip list.
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF060E1A)).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("1. RIDER SELECTION",
                                color = Color(0xFF4AB8E8), fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Name search from follower list",
                                color = Color(0xFF2A4060), fontSize = 8.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Manual email entry for non-followers",
                                color = Color(0xFF2A4060), fontSize = 8.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text("STUB", color = Color(0xFF2A4060), fontSize = 8.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }

                    // Sub-process 2: Invite Generation
                    // Creates enrollment record per selected rider with status=invited.
                    // Sets invite_date to current date.
                    // POST /invites per rider — Phase B API endpoint.
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF060E1A)).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("2. INVITE GENERATION",
                                color = Color(0xFF4AB8E8), fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Creates enrollment record per rider",
                                color = Color(0xFF2A4060), fontSize = 8.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Status = invited  |  POST /invites",
                                color = Color(0xFF2A4060), fontSize = 8.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text("STUB", color = Color(0xFF2A4060), fontSize = 8.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }

                    // Sub-process 3: Invite Email
                    // Sends individual email invite per selected rider.
                    // Email contains: ride name, date, arrive time, rollout,
                    // trailhead, organizer name + email (tappable).
                    // Server-side send — triggered by POST /invites response.
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF060E1A)).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("3. INVITE EMAIL",
                                color = Color(0xFF4AB8E8), fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Individual email per selected rider",
                                color = Color(0xFF2A4060), fontSize = 8.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Server-side send on POST /invites",
                                color = Color(0xFF2A4060), fontSize = 8.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text("STUB", color = Color(0xFF2A4060), fontSize = 8.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }

                // ── PUBLIC RIDE ENROLLMENT PROCESS ────────────────────────────
                // Triggered when organizer taps OPEN RIDE on a PUBLIC ride.
                // Auto-creates invited enrollments for ALL organizer followers.
                // Sends email invite to all followers simultaneously.
                // Ride appears in Section 4 for non-followers.
                // Phase B: POST /rides triggers server-side follower enrollment.
                // STUB — wired when OPEN RIDE button is implemented.
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1628)).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PUBLIC RIDE ENROLLMENT",
                            color = Color(0xFF4AB8E8), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Auto-invites all followers on OPEN RIDE",
                            color = Color(0xFF2A4060), fontSize = 9.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Email sent to all followers simultaneously",
                            color = Color(0xFF2A4060), fontSize = 9.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text("PHASE B", color = Color(0xFF2A4060), fontSize = 8.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                }

                // ── PRIVATE RIDE ENROLLMENT PROCESS ──────────────────────────
                // Organizer manually selects riders from follower list by name search
                // or adds new email addresses not in follower list.
                // Individual email invites sent per selected rider.
                // Private ride does NOT appear in Section 4.
                // Screen: ConvoyPrivateInviteScreen.kt — to be built Phase B.
                // STUB — navigates to invite screen when implemented.
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1628)).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PRIVATE RIDE ENROLLMENT",
                            color = Color(0xFF4AB8E8), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Manual invite — follower search + email entry",
                            color = Color(0xFF2A4060), fontSize = 9.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("ConvoyPrivateInviteScreen.kt — Phase B",
                            color = Color(0xFF2A4060), fontSize = 9.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text("STUB", color = Color(0xFF2A4060), fontSize = 8.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // ── Bottom nav ────────────────────────────────────────────────────────
        GroupTrackBottomNav(
            activeTab = GroupTrackTab.RIDES,
            onHome    = { onBack() },
            onRides   = {},
            onMap     = { onBack() },
            onProfile = {},
            onRadio   = onNavigateToFieldRadio,
            onApplyMasterConfig = onApplyMasterConfig,
            onArchiveRestore = onArchiveRestore
        )
    }
}

// ── Private composables ───────────────────────────────────────────────────────

@Composable
private fun RadioStep(label: String, active: Boolean, done: Boolean) {
    val color = when {
        done   -> Color(0xFF22C55E)
        active -> Color(0xFF4AB8E8)
        else   -> Color(0xFF2A4060)
    }
    val prefix = when {
        done   -> "\u2713 "
        active -> "\u25b6 "
        else   -> "\u25cb "
    }
    Text("$prefix$label", color = color, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun CreateSectionLabel(label: String) {
    Text(label, color = Color(0xFF4AB8E8), fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CreateField(
    label: String, value: String, placeholder: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = { Text(placeholder, color = Color(0xFF334455), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Color(0xFF4AB8E8),
            unfocusedBorderColor = Color(0xFF1A3050),
            focusedTextColor     = Color.White,
            unfocusedTextColor   = Color.White,
            focusedLabelColor    = Color(0xFF4AB8E8),
            unfocusedLabelColor  = Color(0xFF445566),
            cursorColor          = Color(0xFF4AB8E8)))
}
