package com.geeksville.mesh.convoy

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

// ============================================================
// ConvoyCreateRideScreen.kt — s10 / s10b
// V3.0 Phase B — Create Ride
// Auto-generates channel name (Convoy-XXXX) and PSK.
// Trailhead + map area = stubs, orange pending gates.
// Phase C: POST /rides, trailhead/map area from Map Manager.
// ============================================================

@Composable
fun ConvoyCreateRideScreen(
    onRideCreated: () -> Unit = {},
    onNavigateToFieldRadio: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var rideName by remember { mutableStateOf("") }
    var rideDate by remember { mutableStateOf("") }
    var rideTime by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var zipCode by remember { mutableStateOf(ConvoySessionManager.getZipCode(context)) }
    var isPublic by remember { mutableStateOf(false) }
    var trailheadSet by remember { mutableStateOf(false) }
    var mapAreaSet by remember { mutableStateOf(false) }
    var showOrgTerms by remember { mutableStateOf(!ConvoySessionManager.isOrganizer(context)) }

    // Auto-generate channel name on screen load
    val channelName = remember {
        val chars = ('A'..'Z') + ('0'..'9')
        "Convoy-" + (1..4).map { chars.random() }.joinToString("")
    }
    val pskGenerated = remember { UUID.randomUUID().toString().replace("-", "").take(32) }

    val canSave = rideName.isNotBlank() && rideDate.isNotBlank() && rideTime.isNotBlank()

    // Organizer terms inline — fires if not yet organizer
    if (showOrgTerms) {
        Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GroupTrackHeader(subtitle = "Organizer Terms")
            Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(16.dp)) {
                Text(text = "ORGANIZER TERMS V1.0", color = Color(0xFF4AB8E8),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(12.dp))
                Text(text = "By creating a ride you agree to:\n\n• Provide accurate ride information\n• Respect all riders' privacy\n• Not use GroupTrack for commercial purposes without an org account\n• Accept responsibility for rides you organize\n\nOrganizer status is awarded automatically. There is no additional fee.",
                    color = Color(0xFFAABBCC), fontSize = 12.sp, lineHeight = 18.sp)
            }
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(GroupTrackColors.SkyBlue)
                .clickable {
                    ConvoySessionManager.setOrganizer(context, true)
                    showOrgTerms = false
                }.padding(16.dp), contentAlignment = Alignment.Center) {
                Text("ACCEPT & CONTINUE", color = GroupTrackColors.Navy,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).clickable { onBack() }.padding(14.dp),
                contentAlignment = Alignment.Center) {
                Text("CANCEL", color = Color(0xFF445566), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "Create Ride")

        Column(modifier = Modifier.fillMaxWidth().weight(1f)
            .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            CreateSectionLabel("RIDE INFO")
            CreateField("Ride Name", rideName, "e.g. Sunday Desert Run") { rideName = it }
            CreateField("Date", rideDate, "e.g. April 13, 2026") { rideDate = it }
            CreateField("Start Time", rideTime, "e.g. 9:00 AM") { rideTime = it }
            CreateField("Description (optional)", description, "Meeting point, notes...") { description = it }
            CreateField("Zip Code", zipCode, "Ride area zip code") { zipCode = it }

            // Public/Private toggle
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(text = "PUBLIC RIDE", color = Color(0xFFAABBCC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Visible in Available Rides Near Me", color = Color(0xFF445566), fontSize = 10.sp)
                }
                Switch(checked = isPublic, onCheckedChange = { isPublic = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF4AB8E8), checkedTrackColor = Color(0xFF1A3050),
                        uncheckedThumbColor = Color(0xFF445566), uncheckedTrackColor = Color(0xFF0A1628)))
            }

            // Channel info (auto-generated, read-only)
            CreateSectionLabel("RADIO CONFIG — AUTO-GENERATED")
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Channel Name", color = Color(0xFF445566), fontSize = 11.sp)
                    Text(text = channelName, color = Color(0xFF4AB8E8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Encryption Key", color = Color(0xFF445566), fontSize = 11.sp)
                    Text(text = "AES-256 ••••••••", color = Color(0xFF22C55E), fontSize = 11.sp)
                }
                Text(text = "Auto-generated. Applied to riders' radios on invite accept.",
                    color = Color(0xFF334455), fontSize = 10.sp)
            }

            // Map requirements (Phase C stubs)
            CreateSectionLabel("MAP REQUIREMENTS — PHASE C")
            MapGateButton("SET TRAILHEAD", trailheadSet) { trailheadSet = !trailheadSet }
            MapGateButton("SET MAP AREA", mapAreaSet) { mapAreaSet = !mapAreaSet }

            Spacer(Modifier.height(8.dp))

            // Save button
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (canSave) GroupTrackColors.SkyBlue else Color(0xFF1A3050))
                .clickable(enabled = canSave) {
                    if (canSave) {
                        // Phase B: set organizer, save locally
                        ConvoySessionManager.setOrganizer(context, true)
                        // Phase C: POST /rides here
                        android.widget.Toast.makeText(context,
                            "Ride saved — $channelName", android.widget.Toast.LENGTH_SHORT).show()
                        onRideCreated()
                    }
                }.padding(16.dp), contentAlignment = Alignment.Center) {
                Text(text = "SAVE RIDE", color = if (canSave) GroupTrackColors.Navy else Color(0xFF445566),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            CreateNavBtn("🗺", "MAP") { onBack() }
            CreateNavBtn("＋", "+RIDE") { }
            CreateNavBtn("📻", "RADIO") { onNavigateToFieldRadio() }
        }
    }
}

@Composable
private fun MapGateButton(label: String, isSet: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (isSet) Color(0xFF0F2035) else Color(0xFF1A0A00))
        .clickable { onClick() }.padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = if (isSet) Color(0xFF22C55E) else Color(0xFFF97316),
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text = if (isSet) "✅ SET" else "⚠️ PENDING — Phase C",
            color = if (isSet) Color(0xFF22C55E) else Color(0xFFF97316), fontSize = 10.sp)
    }
}

@Composable
private fun CreateSectionLabel(label: String) {
    Text(text = label, color = Color(0xFF4AB8E8), fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CreateField(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = { Text(placeholder, color = Color(0xFF334455), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF4AB8E8), unfocusedBorderColor = Color(0xFF1A3050),
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Color(0xFF4AB8E8), unfocusedLabelColor = Color(0xFF445566),
            cursorColor = Color(0xFF4AB8E8)))
}

@Composable
private fun CreateNavBtn(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = icon, fontSize = 18.sp)
        Text(text = label, color = Color(0xFF4AB8E8), fontSize = 8.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
