package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConvoyMyRidesScreen(
    onCreateRide: () -> Unit,
    onRideDetail: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val rideCount = ConvoyDevSeeder.getRideCount(context)

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "My Rides")

        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A3050))
                .clickable { onCreateRide() }.padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "+ CREATE NEW RIDE", color = GroupTrackColors.SkyBlue,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(text = "Organize a ride, send invites, auto-download maps",
                    color = Color(0xFF445566), fontSize = 11.sp)
            }
        }

        if (rideCount == 0) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "🏁", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(text = "No rides yet", color = Color(0xFFAABBCC), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(text = "Create a ride or accept an invite", color = Color(0xFF445566), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(rideCount) { i ->
                    val name = ConvoyDevSeeder.getRideName(context, i)
                    val status = ConvoyDevSeeder.getRideStatus(context, i)
                    val date = ConvoyDevSeeder.getRideDate(context, i)
                    val organizer = ConvoyDevSeeder.getRideOrganizer(context, i)
                    val statusColor = when(status) {
                        "ORGANIZED" -> Color(0xFF4AB8E8)
                        "ENROLLED"  -> Color(0xFF22C55E)
                        "COMPLETED" -> Color(0xFF445566)
                        else        -> Color(0xFF445566)
                    }
                    Row(modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F2035))
                        .clickable { onRideDetail("ride_00${i+1}") }
                        .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(text = date, color = Color(0xFF445566), fontSize = 11.sp)
                            Text(text = organizer, color = Color(0xFF445566), fontSize = 11.sp)
                        }
                        Text(text = status, color = statusColor, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }
        }

        GroupTrackBottomNav(
            activeTab = GroupTrackTab.RIDES,
            onHome = onBack,
            onRides = {},
            onMap = onBack,
            onProfile = onBack
        )
    }
}
