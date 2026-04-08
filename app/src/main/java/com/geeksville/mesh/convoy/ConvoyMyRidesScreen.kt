package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
// ConvoyMyRidesScreen.kt — s09
// V3.0 Phase B — My Rides
// Tabs: ALL / ENROLLED / ORGANIZED / COMPLETED
// Phase C: wire GET /rides enrolled + completed + organized
// ============================================================

@Composable
fun ConvoyMyRidesScreen(
    onNavigateToRideDetail: () -> Unit = {},
    onNavigateToCreateRide: () -> Unit = {},
    onNavigateToFieldRadio: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val tabs = listOf("ALL", "ENROLLED", "ORGANIZED", "COMPLETED")
    var selectedTab by remember { mutableStateOf(0) }

    val rideCount = ConvoyDevSeeder.getRideCount(context)
    val allRides = (0 until rideCount).map { i ->
        mapOf(
            "name" to ConvoyDevSeeder.getRideName(context, i),
            "status" to ConvoyDevSeeder.getRideStatus(context, i),
            "date" to ConvoyDevSeeder.getRideDate(context, i),
            "time" to ConvoyDevSeeder.getRideTime(context, i),
            "organizer" to ConvoyDevSeeder.getRideOrganizer(context, i),
            "email" to ConvoyDevSeeder.getRideEmail(context, i)
        )
    }

    val filteredRides = when (selectedTab) {
        1 -> allRides.filter { it["status"] == "ENROLLED" }
        2 -> allRides.filter { it["status"] == "ORGANIZED" }
        3 -> allRides.filter { it["status"] == "COMPLETED" }
        else -> allRides
    }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "My Rides")

        TabRow(selectedTabIndex = selectedTab,
            containerColor = Color(0xFF0A1628),
            contentColor = Color(0xFF4AB8E8)) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                    text = {
                        Text(text = title, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = if (selectedTab == i) Color(0xFF4AB8E8) else Color(0xFF445566))
                    })
            }
        }

        if (filteredRides.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(text = "No rides in this category", color = Color(0xFF445566), fontSize = 12.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredRides) { ride ->
                    var isFollowing by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F2035)).clickable { onNavigateToRideDetail() }
                        .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = ride["name"] ?: "", color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            val status = ride["status"] ?: ""
                            val color = when (status) {
                                "ORGANIZED" -> Color(0xFF4AB8E8)
                                "ENROLLED" -> Color(0xFF22C55E)
                                "COMPLETED" -> Color(0xFF445566)
                                else -> Color(0xFF445566)
                            }
                            Text(text = status, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(text = "${ride["date"]}  ·  ${ride["time"]}",
                            color = Color(0xFF445566), fontSize = 11.sp)
                        Text(text = "Organizer: ${ride["organizer"]}  ·  ${ride["email"]}",
                            color = Color(0xFF445566), fontSize = 11.sp)

                        // Device readiness icons
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 4.dp)) {
                            Text(text = "🗺 ⏳", fontSize = 11.sp)
                            Text(text = "📡 ⚠️", fontSize = 11.sp)
                            Text(text = "📋 ✅", fontSize = 11.sp)
                        }

                        // Follow checkbox
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp).clickable(onClick = {})
                        ) {
                            Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF1A3050))
                                .clickable { isFollowing = !isFollowing })
                            Text(text = if (isFollowing) "Following organizer" else "Follow organizer",
                                color = if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF445566),
                                fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            BottomNavBtn("🗺", "MAP") { onBack() }
            BottomNavBtn("＋", "+RIDE") { onNavigateToCreateRide() }
            BottomNavBtn("📻", "RADIO") { onNavigateToFieldRadio() }
        }
    }
}

@Composable
private fun BottomNavBtn(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = icon, fontSize = 18.sp)
        Text(text = label, color = Color(0xFF4AB8E8), fontSize = 8.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
