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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// ConvoyRideDetailScreen.kt — s11
// V3.0 Phase B — Ride Detail
// Every ride row in the app routes here.
// Phase C: wire GET /rides/{id} + GET /enrollments/{ride_id}
// ============================================================

@Composable
fun ConvoyRideDetailScreen(
    rideId: String = "",
    onNavigateToSendInvite: () -> Unit = {},
    onNavigateToBroadcast: () -> Unit = {},
    onNavigateToCreateRide: () -> Unit = {},
    onNavigateToFieldRadio: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val isOrganizer = ConvoySessionManager.isOrganizer(context)

    // Seeded data for display — Phase C: replace with API call
    var rideStatus by remember { mutableStateOf("ENROLLED") }
    var isFollowing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "Ride Detail")

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Ride info card
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F2035)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Sunday Desert Run — Gooseberry Mesa",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    RideStatusBadge(rideStatus)
                }
                Text(text = "April 13, 2026  ·  9:00 AM", color = Color(0xFF445566), fontSize = 12.sp)
                Text(text = "Gooseberry Mesa, UT", color = Color(0xFF445566), fontSize = 12.sp)
                Text(text = "Organizer: Fred Dev", color = Color(0xFF445566), fontSize = 12.sp)
                Text(text = "fred@grouptrack.org", color = Color(0xFF4AB8E8), fontSize = 12.sp)
                Text(text = "Channel: Convoy-A3F7", color = Color(0xFF445566), fontSize = 11.sp)

                // Follow organizer
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (isFollowing) Color(0xFF4AB8E8) else Color.Transparent)
                        .then(Modifier.background(
                            if (isFollowing) Color(0xFF4AB8E8) else Color.Transparent,
                            RoundedCornerShape(3.dp)
                        ))
                        .clickable { isFollowing = !isFollowing },
                        contentAlignment = Alignment.Center) {
                        if (isFollowing) Text("✓", color = GroupTrackColors.Navy,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        else Box(modifier = Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1A3050)))
                    }
                    Text(text = if (isFollowing) "Following Fred Dev" else "Follow Fred Dev",
                        color = if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF445566),
                        fontSize = 11.sp)
                }
            }

            // Rider status actions
            if (rideStatus != "ORGANIZED") {
                DetailSectionLabel("YOUR STATUS")
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val statuses = when (rideStatus) {
                        "INVITED" -> listOf("ACCEPT" to Color(0xFF22C55E), "MAYBE" to Color(0xFFF59E0B), "DECLINE" to Color(0xFFEF4444))
                        "ENROLLED", "ACCEPTED", "MAYBE" -> listOf("CANCEL" to Color(0xFFEF4444), "MAYBE" to Color(0xFFF59E0B))
                        else -> emptyList()
                    }
                    statuses.forEach { (label, color) ->
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                            .background(color.copy(alpha = 0.15f))
                            .clickable {
                                rideStatus = when (label) {
                                    "ACCEPT" -> "ENROLLED"
                                    "MAYBE" -> "MAYBE"
                                    "DECLINE", "CANCEL" -> "DECLINED"
                                    else -> rideStatus
                                }
                            }.padding(10.dp),
                            contentAlignment = Alignment.Center) {
                            Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Device readiness (stub)
            DetailSectionLabel("DEVICE READINESS")
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                DevReadinessItem("🗺", "Maps", "⏳ Pending")
                DevReadinessItem("📡", "Radio", "⚠️ Apply config")
                DevReadinessItem("📋", "JSON", "✅ Delivered")
            }

            // Organizer tools
            if (isOrganizer) {
                DetailSectionLabel("ORGANIZER TOOLS")
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF4AB8E8).copy(alpha = 0.15f))
                        .clickable { onNavigateToSendInvite() }.padding(12.dp),
                        contentAlignment = Alignment.Center) {
                        Text("SEND INVITE", color = Color(0xFF4AB8E8),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
                        .clickable { onNavigateToBroadcast() }.padding(12.dp),
                        contentAlignment = Alignment.Center) {
                        Text("BROADCAST", color = Color(0xFFF59E0B),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Enrollment list
                DetailSectionLabel("ENROLLED RIDERS — 4")
                listOf(
                    Triple("Fred Dev", "ORGANIZED", "✅ ✅ ✅"),
                    Triple("Dave H", "ENROLLED", "✅ ⚠️ ✅"),
                    Triple("Sarah K", "ENROLLED", "⏳ ⚠️ ⏳"),
                    Triple("Mike T", "MAYBE", "⏳ ⚠️ ⏳")
                ).forEach { (name, status, icons) ->
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0F2035)).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(text = name, color = Color.White, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(text = icons, fontSize = 11.sp)
                            RideStatusBadge(status)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            // Description
            DetailSectionLabel("DESCRIPTION")
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(14.dp)) {
                Text(text = "Meet at the main trailhead at 8:45 AM. Ride starts at 9:00 AM sharp. Bring water and snacks for a full day ride.",
                    color = Color(0xFFAABBCC), fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))
        }

        // Bottom nav — 3 buttons
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
            .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            BottomNavButton("🗺", "MAP") { onBack() }
            BottomNavButton("＋", "+RIDE") { onNavigateToCreateRide() }
            BottomNavButton("📻", "RADIO") { onNavigateToFieldRadio() }
        }
    }
}

@Composable
private fun RideStatusBadge(status: String) {
    val color = when (status) {
        "ORGANIZED" -> Color(0xFF4AB8E8)
        "ENROLLED", "ACCEPTED" -> Color(0xFF22C55E)
        "MAYBE" -> Color(0xFFF59E0B)
        "DECLINED" -> Color(0xFFEF4444)
        "INVITED" -> Color(0xFFF59E0B)
        "COMPLETED" -> Color(0xFF445566)
        else -> Color(0xFF445566)
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f))
        .padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text = status, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DevReadinessItem(icon: String, label: String, status: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = icon, fontSize = 20.sp)
        Text(text = label, color = Color(0xFF445566), fontSize = 10.sp)
        Text(text = status, color = Color(0xFFAABBCC), fontSize = 10.sp)
    }
}

@Composable
private fun DetailSectionLabel(label: String) {
    Text(text = label, color = Color(0xFF4AB8E8), fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}

@Composable
private fun BottomNavButton(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = icon, fontSize = 18.sp)
        Text(text = label, color = Color(0xFF4AB8E8), fontSize = 8.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
