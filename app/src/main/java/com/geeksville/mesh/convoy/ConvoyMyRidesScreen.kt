package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
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
            containerColor = Gt.panel,
            contentColor = Gt.accent) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                    text = {
                        Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = if (selectedTab == i) Gt.accent else Gt.text)
                    })
            }
        }

        if (filteredRides.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(text = "No rides in this category", color = Gt.text, fontSize = 12.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredRides) { ride ->
                    var isFollowing by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Gt.panel).clickable { onNavigateToRideDetail() }
                        .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = ride["name"] ?: "", color = Gt.text, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            val status = ride["status"] ?: ""
                            val color = when (status) {
                                "ORGANIZED" -> Gt.accent
                                "ENROLLED" -> Gt.good
                                "COMPLETED" -> Gt.text
                                else -> Gt.text
                            }
                            Text(text = status, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(text = "${ride["date"]}  ·  ${ride["time"]}",
                            color = Gt.text, fontSize = 12.sp)
                        Text(text = "Organizer: ${ride["organizer"]}  ·  ${ride["email"]}",
                            color = Gt.text, fontSize = 12.sp)

                        // Device readiness icons
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 4.dp)) {
                            Text(text = "🗺 ⏳", fontSize = 12.sp)
                            Text(text = "📡 ⚠️", fontSize = 12.sp)
                            Text(text = "📋 ✅", fontSize = 12.sp)
                        }

                        // Follow checkbox
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp).clickable(onClick = {})
                        ) {
                            Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (isFollowing) Gt.accent.copy(alpha = 0.22f) else Gt.panel)
                                .clickable { isFollowing = !isFollowing })
                            Text(text = if (isFollowing) "Following organizer" else "Follow organizer",
                                color = if (isFollowing) Gt.accent else Gt.text,
                                fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background(Gt.panel).padding(vertical = 8.dp),
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
        Text(text = label, color = Gt.accent, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
