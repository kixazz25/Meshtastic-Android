package com.geeksville.mesh.convoy

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

        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1A3050)).clickable { onBack() }
                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("\u2190 BACK", color = Color(0xFF4AB8E8), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("DOWNLOAD RIDE DETAILS", color = Color.White, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Color(0xFF4AB8E8))

        Column(modifier = Modifier.fillMaxWidth().weight(1f)
            .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Info
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A1628)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("DOWNLOAD RIDE CONFIG TO DEVICE", color = Color(0xFF4AB8E8),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Text("Internet required  \u2022  No radio needed at this step.",
                    color = Color(0xFF445566), fontSize = 10.sp)
                Text("Connect radio separately to apply config.",
                    color = Color(0xFF445566), fontSize = 10.sp)
            }

            if (masterConfig == null) {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A1A1A)).padding(12.dp)) {
                    Text("\u26a0 Master config not found. Apply master config first.",
                        color = Color(0xFFF44336), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Ride list
            Text("SELECT A RIDE", color = Color(0xFF4AB8E8), fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)

            if (eligibleRides.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F2035)).padding(20.dp),
                    contentAlignment = Alignment.Center) {
                    Text("NO ELIGIBLE RIDES\nAccept or maybe a ride invite first.",
                        color = Color(0xFF2A4060), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            } else {
                eligibleRides.forEach { ride ->
                    val rid        = ride["id"] ?: ""
                    val isSelected = selectedId == rid
                    val statusColor = if ((ride["status"] ?: "").lowercase() == "accepted")
                        Color(0xFF22C55E) else Color(0xFFF59E0B)
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF0F2840) else Color(0xFF0F2035))
                        .clickable { selectedId = rid; downloadStatus = "idle" }
                        .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(ride["name"] ?: "", color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Box(modifier = Modifier.clip(RoundedCornerShape(3.dp))
                                .background(statusColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text((ride["status"] ?: "").uppercase(), color = statusColor,
                                    fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text(ride["date"] ?: "", color = Color(0xFF4AB8E8), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Text("${ride["location"] ?: ""}  \u2022  ${ride["organizer"] ?: ""}",
                            color = Color(0xFF445566), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        if (isSelected) {
                            Text("\u2713 SELECTED", color = Color(0xFF4AB8E8), fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Map area stub
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A1628)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("MAP AREA DOWNLOAD", color = Color(0xFF4AB8E8), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Download offline tiles for this ride area",
                        color = Color(0xFF2A4060), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("Phase C — ConvoyMapAreaDownload.kt",
                        color = Color(0xFF1A3050), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                Text("STUB", color = Color(0xFF2A4060), fontSize = 8.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }

            // Result
            when (downloadStatus) {
                "success" -> Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D2010)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("\u2713 RIDE CONFIG DOWNLOADED", color = Color(0xFF22C55E),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Config saved to device.", color = Color(0xFF445566), fontSize = 10.sp)
                    Text("Go to Radio \u2192 Apply Master / Ride Config when radio is connected.",
                        color = Color(0xFF445566), fontSize = 10.sp)
                }
                "error" -> Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A1A1A)).padding(12.dp)) {
                    Text("\u2717 DOWNLOAD FAILED", color = Color(0xFFF44336),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(errorMsg, color = Color(0xFF445566), fontSize = 10.sp)
                }
                else -> {}
            }

            // Confirmation panel — shows selected ride name and cancel option
            if (selectedId != null && downloadStatus != "success") {
                val selectedRide = eligibleRides.firstOrNull { it["id"] == selectedId }
                if (selectedRide != null) {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F2840)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("DOWNLOADING CONFIG FOR:", color = Color(0xFF4AB8E8),
                            fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                        Text(selectedRide["name"] ?: "", color = Color.White,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${selectedRide["date"] ?: ""}  •  ${selectedRide["organizer"] ?: ""}",
                            color = Color(0xFF445566), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF2A1A1A)).clickable {
                                selectedId = null; downloadStatus = "idle"
                            }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                            Text("✕  CANCEL — SELECT A DIFFERENT RIDE",
                                color = Color(0xFFF44336), fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Download button
            val canDownload = selectedId != null && masterConfig != null &&
                              downloadStatus != "success" && downloadStatus != "downloading"

            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (canDownload) Color(0xFF1A3050) else Color(0xFF0A1628))
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
                            hardwareId       = viewModel?.myNodeInfo?.value?.let { "!%08x".format(it.myNodeNum) } ?: "unknown",
                            deviceId         = viewModel?.myNodeInfo?.value?.deviceId ?: "",
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
                    color = if (canDownload || downloadStatus == "success") Color(0xFF4AB8E8)
                            else Color(0xFF2A4060),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
