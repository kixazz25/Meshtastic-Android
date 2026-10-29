package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
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
    onNavigateToCompletedRides: (String) -> Unit = {},
    onNavigateToSearchByArea: () -> Unit = {},
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
            modifier = Modifier.fillMaxWidth().background(Gt.panel)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(Gt.panel)
                .clickable { onBack() }
                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("\u2190 BACK", color = Gt.accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("CREATE A RIDE", color = Gt.text, fontSize = 12.sp,   // RIDENAME-2026-10-29 (D16)
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Gt.accent)

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── ORGANIZER STRIP ───────────────────────────────────────────────
            if (organizer != null) {
                CreateSectionLabel("ORGANIZER")
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${organizer.firstName} ${organizer.lastName}",
                        color = Gt.text, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(organizer.email, color = Gt.accent, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(12.dp)) {
                    Text("\u26a0 Complete enrollment before creating a ride.",
                        color = Gt.bad, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // ── MASTER CONFIG STRIP ───────────────────────────────────────────
            if (masterConfig != null) {
                CreateSectionLabel("RADIO CONFIG — MASTER")
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${masterConfig.hardwareModel}  •  fw ${masterConfig.firmwareVersion}",
                        color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("${masterConfig.loraRegion}  •  ${masterConfig.loraModemPreset}  •  ${masterConfig.loraTxPower} dBm",
                        color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("Channel: $channelName",
                        color = Gt.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Encryption: AES-256 auto-generated",
                        color = Gt.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(12.dp)) {
                    Text("\u26a0 Master radio config not found.",
                        color = Gt.bad, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
                .background(Gt.panel).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PUBLIC RIDE", color = Gt.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Visible in Public Rides Near Me",
                        color = Gt.text, fontSize = 12.sp)
                }
                Switch(checked = isPublic, onCheckedChange = { isPublic = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Gt.accent,
                        checkedTrackColor   = Gt.panel,
                        uncheckedThumbColor = Gt.text,
                        uncheckedTrackColor = Gt.panel))
            }

            // ── MAP AREA — Phase C ────────────────────────────────────────────
            CreateSectionLabel("MAP AREA — PHASE C")
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("SET MAP AREA", color = Gt.warn, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold)
                Text("\u26a0 PENDING — Phase C", color = Gt.warn, fontSize = 12.sp)
            }

            // ── RADIO PROCESSING STEPS ────────────────────────────────────────
            if (step > 0) {
                CreateSectionLabel("RADIO PROCESSING")
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RadioStep("Reading device info",         step >= 1, step > 1)
                    RadioStep("Generating channel and PSK",  step >= 2, step > 2)
                    RadioStep("Saving ride config",          step >= 3, step > 3)
                }
            }

            // ── SUCCESS ───────────────────────────────────────────────────────
            if (step == 4) {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\u2713 RIDE SAVED", color = Gt.accent, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Channel: $channelName", color = Gt.accent, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                    Text("PSK: AES-256 secured", color = Gt.accent, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Gt.accent.copy(alpha = 0.22f))
                        .clickable { onRideCreated() }
                        .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text("PROCEED \u2192", color = Gt.text, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // ── STATUS MESSAGE ────────────────────────────────────────────────
            if (statusMsg.isNotBlank() && step != 4) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (statusOk) Gt.panel else Gt.panel)
                    .padding(12.dp)) {
                    Text(statusMsg,
                        color = if (statusOk) Gt.good else Gt.bad,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // ── SAVE BUTTONS ──────────────────────────────────────────────────
            if (step != 4) {
                // SAVE AS PENDING
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (canSave) Gt.panel else Gt.panel)
                    .clickable(enabled = canSave && !isProcessing) {
                        scope.launch {
                            isProcessing = true
                            statusMsg = ""
                            try {
                                step = 1
                                val hwId = viewModel?.rideHardwareId() ?: "unknown"   // COMMIDENT-2026-10-09: through the Comm API
                                val devId = viewModel?.rideDeviceId() ?: ""

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
                        color = if (canSave) Gt.accent else Gt.text,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }

                // OPEN RIDE — enabled only after save (step == 4 handled above, this is pre-save hint)
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel)
                    .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text("OPEN RIDE — Save first",
                        color = Gt.text, fontSize = 12.sp,
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
                    .background(Gt.panel).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PUBLIC RIDE ENROLLMENT",
                            color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Auto-invites all followers on OPEN RIDE",
                            color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Email sent to all followers simultaneously",
                            color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text("PHASE B", color = Gt.text, fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                }

                // ── PRIVATE RIDE ENROLLMENT PROCESS ──────────────────────────
                // ConvoyPrivateInviteScreen.kt — Phase B
                // Three sub-processes below — all stubbed.
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("PRIVATE RIDE ENROLLMENT",
                            color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("STUB", color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                    }

                    // Sub-process 1: Rider Selection
                    // Name search across organizer follower list.
                    // Tap name to select — adds to invite list.
                    // Manual email entry for riders not in follower list.
                    // Selected riders shown in scrollable chip list.
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Gt.panel).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("1. RIDER SELECTION",
                                color = Gt.accent, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Name search from follower list",
                                color = Gt.text, fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Manual email entry for non-followers",
                                color = Gt.text, fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text("STUB", color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }

                    // Sub-process 2: Invite Generation
                    // Creates enrollment record per selected rider with status=invited.
                    // Sets invite_date to current date.
                    // POST /invites per rider — Phase B API endpoint.
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Gt.panel).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("2. INVITE GENERATION",
                                color = Gt.accent, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Creates enrollment record per rider",
                                color = Gt.text, fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Status = invited  |  POST /invites",
                                color = Gt.text, fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text("STUB", color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }

                    // Sub-process 3: Invite Email
                    // Sends individual email invite per selected rider.
                    // Email contains: ride name, date, arrive time, rollout,
                    // trailhead, organizer name + email (tappable).
                    // Server-side send — triggered by POST /invites response.
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Gt.panel).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("3. INVITE EMAIL",
                                color = Gt.accent, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Individual email per selected rider",
                                color = Gt.text, fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text("Server-side send on POST /invites",
                                color = Gt.text, fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text("STUB", color = Gt.text, fontSize = 12.sp,
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
                    .background(Gt.panel).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PUBLIC RIDE ENROLLMENT",
                            color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Auto-invites all followers on OPEN RIDE",
                            color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Email sent to all followers simultaneously",
                            color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text("PHASE B", color = Gt.text, fontSize = 12.sp,
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
                    .background(Gt.panel).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PRIVATE RIDE ENROLLMENT",
                            color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Manual invite — follower search + email entry",
                            color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("ConvoyPrivateInviteScreen.kt — Phase B",
                            color = Gt.text, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text("STUB", color = Gt.text, fontSize = 12.sp,
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
            onArchiveRestore = onArchiveRestore,
            onNavigateToCompletedRides = onNavigateToCompletedRides,
            onNavigateToSearchByArea = onNavigateToSearchByArea
        )
    }
}

// ── Private composables ───────────────────────────────────────────────────────

@Composable
private fun RadioStep(label: String, active: Boolean, done: Boolean) {
    val color = when {
        done   -> Gt.good
        active -> Gt.accent
        else   -> Gt.panel
    }
    val prefix = when {
        done   -> "\u2713 "
        active -> "\u25b6 "
        else   -> "\u25cb "
    }
    Text("$prefix$label", color = color, fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun CreateSectionLabel(label: String) {
    Text(label, color = Gt.accent, fontSize = 12.sp,
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
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = Gt.text, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Gt.accent,
            unfocusedBorderColor = Gt.line,
            focusedTextColor     = Gt.text,
            unfocusedTextColor   = Gt.text,
            focusedLabelColor    = Gt.accent,
            unfocusedLabelColor  = Gt.text,
            cursorColor          = Gt.accent))
}
