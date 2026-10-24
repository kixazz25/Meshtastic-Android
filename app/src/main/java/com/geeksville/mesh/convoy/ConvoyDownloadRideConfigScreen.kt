package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import android.util.Base64
import java.security.SecureRandom

// ============================================================
// ConvoyDownloadRideConfigScreen.kt
// V3.0 Phase B — Radio Menu Item 1
// Download Ride Details to Device
//
// PURPOSE:
//   Shows rides where rider is accepted or maybe.
//   Rider selects a ride.
//   App builds ConvoyEventConfig JSON from ride data + master config.
//   Saves JSON to device via ConvoyEventStore.save()
//   JSON is consumed by ConvoyApplyRadioScreen when radio is connected.
//   File existence = config downloaded status.
//   File deleted after successful radio apply.
//
// REQUIRES: Internet connection
// DOES NOT REQUIRE: Radio connection
//
// Phase C: Replace seeder data with GET /enrollments?user_id=&status=accepted,maybe
//          Pull channelName and channelPsk from RDS ride record directly.
// ============================================================

@Composable
fun ConvoyDownloadRideConfigScreen(
    viewModel: ConvoyViewModel? = null,
    onBack: () -> Unit = {}
) {
    val context     = LocalContext.current
    val scrollState = rememberScrollState()

    val masterConfig = remember { ConvoyMasterConfig.load(context) }
    val organizer    = remember { ConvoyUserStore.getActiveUser(context) }

    // Load eligible rides — accepted or maybe
    // Phase C: replace with API call
    val eligibleRides = remember {
        val count = ConvoyDevSeeder.getRideCount(context)
        (0 until count).filter { i ->
            val s = ConvoyDevSeeder.getRideStatus(context, i).lowercase()
            s == "accepted" || s == "maybe"
        }.map { i ->
            mapOf(
                "id"        to ConvoyDevSeeder.getRideField(context, i, "id").ifEmpty { "ride-00${i+1}" },
                "name"      to ConvoyDevSeeder.getRideName(context, i),
                "date"      to ConvoyDevSeeder.getRideDate(context, i),
                "organizer" to ConvoyDevSeeder.getRideOrganizer(context, i),
                "location"  to ConvoyDevSeeder.getRideField(context, i, "location"),
                "status"    to ConvoyDevSeeder.getRideStatus(context, i)
            )
        }
    }

    var selectedId     by remember { mutableStateOf<String?>(null) }
    var downloadStatus by remember { mutableStateOf("idle") }
    var errorMsg       by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        Row(modifier = Modifier.fillMaxWidth().background(Gt.panel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(Gt.panel).clickable { onBack() }
                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("\u2190 BACK", color = Gt.accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("DOWNLOAD RIDE DETAILS", color = Gt.text, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Gt.accent)

        Column(modifier = Modifier.fillMaxWidth().weight(1f)
            .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Info
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("DOWNLOAD RIDE CONFIG TO DEVICE", color = Gt.accent,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Text("Internet required  \u2022  No radio needed at this step.",
                    color = Gt.text, fontSize = 12.sp)
                Text("Connect radio separately to apply config.",
                    color = Gt.text, fontSize = 12.sp)
            }

            if (masterConfig == null) {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(12.dp)) {
                    Text("\u26a0 Master config not found. Apply master config first.",
                        color = Gt.bad, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Ride list
            Text("SELECT A RIDE", color = Gt.accent, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)

            if (eligibleRides.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(20.dp),
                    contentAlignment = Alignment.Center) {
                    Text("NO ELIGIBLE RIDES\nAccept or maybe a ride invite first.",
                        color = Gt.text, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                }
            } else {
                eligibleRides.forEach { ride ->
                    val rid        = ride["id"] ?: ""
                    val isSelected = selectedId == rid
                    val statusColor = if ((ride["status"] ?: "").lowercase() == "accepted")
                        Gt.accent else Gt.warn
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Gt.panel else Gt.panel)
                        .clickable { selectedId = rid; downloadStatus = "idle" }
                        .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(ride["name"] ?: "", color = Gt.text, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Box(modifier = Modifier.clip(RoundedCornerShape(3.dp))
                                .background(statusColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text((ride["status"] ?: "").uppercase(), color = statusColor,
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text(ride["date"] ?: "", color = Gt.accent, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace)
                        Text("${ride["location"] ?: ""}  \u2022  ${ride["organizer"] ?: ""}",
                            color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        if (isSelected) {
                            Text("\u2713 SELECTED", color = Gt.accent, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Map area stub
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("MAP AREA DOWNLOAD", color = Gt.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Download offline tiles for this ride area",
                        color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("Phase C — ConvoyMapAreaDownload.kt",
                        color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Text("STUB", color = Gt.text, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }

            // Result
            when (downloadStatus) {
                "success" -> Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("\u2713 RIDE CONFIG DOWNLOADED", color = Gt.accent,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Config saved to device.", color = Gt.text, fontSize = 12.sp)
                    Text("Go to Radio \u2192 Apply Master / Ride Config when radio is connected.",
                        color = Gt.text, fontSize = 12.sp)
                }
                "error" -> Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(12.dp)) {
                    Text("\u2717 DOWNLOAD FAILED", color = Gt.bad,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(errorMsg, color = Gt.text, fontSize = 12.sp)
                }
                else -> {}
            }

            // Confirmation panel — shows selected ride name and cancel option
            if (selectedId != null && downloadStatus != "success") {
                val selectedRide = eligibleRides.firstOrNull { it["id"] == selectedId }
                if (selectedRide != null) {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Gt.panel).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("DOWNLOADING CONFIG FOR:", color = Gt.accent,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                        Text(selectedRide["name"] ?: "", color = Gt.text,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${selectedRide["date"] ?: ""}  •  ${selectedRide["organizer"] ?: ""}",
                            color = Gt.text, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace)
                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Gt.panel).clickable {
                                selectedId = null; downloadStatus = "idle"
                            }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                            Text("✕  CANCEL — SELECT A DIFFERENT RIDE",
                                color = Gt.bad, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Download button
            val canDownload = selectedId != null && masterConfig != null &&
                              downloadStatus != "success" && downloadStatus != "downloading"

            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (canDownload) Gt.panel else Gt.panel)
                .then(if (canDownload) Modifier.clickable {
                    val ride = eligibleRides.firstOrNull { it["id"] == selectedId }
                    if (ride == null || organizer == null || masterConfig == null) {
                        downloadStatus = "error"; errorMsg = "Missing data"; return@clickable
                    }
                    downloadStatus = "downloading"
                    try {
                        // Phase C: use channelName + channelPsk from RDS ride record
                        val pskBytes = ByteArray(32)
                        SecureRandom().nextBytes(pskBytes)
                        val psk = Base64.encodeToString(pskBytes, Base64.NO_WRAP)
                        val suffix = (1..4).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }.joinToString("")
                        val channelName = "CONVOY-$suffix"
                        val event = ConvoyEventConfig.createFromMaster(
                            master           = masterConfig,
                            organizer        = organizer,
                            hardwareId       = viewModel?.rideHardwareId() ?: "unknown",   // COMMIDENT-2026-10-09: through the Comm API
                            deviceId         = viewModel?.rideDeviceId() ?: "",
                            eventName        = ride["name"] ?: "",
                            eventDate        = ride["date"] ?: "",
                            eventDescription = ride["location"] ?: "",
                            channelName      = channelName,
                            channelPsk       = psk
                        )
                        ConvoyEventStore.save(context, event)
                        downloadStatus = "success"
                    } catch (e: Exception) {
                        downloadStatus = "error"; errorMsg = e.message ?: "Error"
                    }
                } else Modifier)
                .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center) {
                Text(
                    when (downloadStatus) {
                        "downloading" -> "DOWNLOADING..."
                        "success"     -> "\u2713 DOWNLOADED"
                        else -> if (canDownload) "DOWNLOAD RIDE CONFIG" else "SELECT A RIDE FIRST"
                    },
                    color = if (canDownload || downloadStatus == "success") Gt.accent
                            else Gt.panel,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
