package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// ConvoyMyRidesScreen.kt
// V3.0 Phase B — My Rides screen (s09)
// Lists enrolled, completed, and organized rides.
// Phase B: stub list with CREATE RIDE entry point.
// Phase C: GET /rides from API.
// ============================================================

@Composable
fun ConvoyMyRidesScreen(
    onCreateRide: () -> Unit,
    onRideDetail: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)
    ) {
        GroupTrackHeader(subtitle = "My Rides")

        // Create ride CTA
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A3050))
                .clickable { onCreateRide() }
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "+ CREATE NEW RIDE", color = GroupTrackColors.SkyBlue,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(text = "Organize a ride, send invites, auto-download maps",
                    color = Color(0xFF445566), fontSize = 11.sp)
            }
        }

        // Stub empty state
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "🏁", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text(text = "No rides yet", color = Color(0xFFAABBCC), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(text = "Create a ride or accept an invite", color = Color(0xFF445566), fontSize = 12.sp)
            }
        }

        // Back
        GroupTrackBottomNav(
            activeTab = GroupTrackTab.RIDES,
            onHome = onBack,
            onRides = {},
            onMap = onBack,
            onProfile = onBack
        )
    }
}
