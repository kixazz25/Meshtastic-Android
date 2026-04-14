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

// ============================================================
// ConvoyPendingRidesScreen.kt
// V3.0 Phase B — Pending Rides List
// First screen on + RIDE tap.
// Lists all pending rides. Tap to edit/open.
// Big button to create new ride.
// ============================================================

@Composable
fun ConvoyPendingRidesScreen(
    viewModel: ConvoyViewModel? = null,
    onNavigateToCreateRide: (rideId: String?) -> Unit = {},
    onNavigateToFieldRadio: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context     = LocalContext.current
    val scrollState = rememberScrollState()

    // Load pending rides from local store — Phase C: replace with GET /rides?status=pending
    val pendingRides = remember {
        ConvoyEventConfig.loadAll(context)
    }

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
            Text("MY RIDES", color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Color(0xFF4AB8E8))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── CREATE NEW RIDE — big button ──────────────────────────────────
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF4AB8E8))
                    .clickable { onNavigateToCreateRide(null) }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("+ CREATE A NEW RIDE", color = Color(0xFF0A1628), fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }

            // ── PENDING RIDES LIST ────────────────────────────────────────────
            if (pendingRides.isEmpty()) {
                // Empty state
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F2035)).padding(24.dp),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("NO PENDING RIDES", color = Color(0xFF2A4060), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp)
                        Text("Create a ride to get started",
                            color = Color(0xFF1A3050), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                Text("MY PENDING RIDES", color = Color(0xFF4AB8E8), fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace)

                pendingRides.forEach { ride ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F2035))
                            .clickable { onNavigateToCreateRide(ride.eventId) }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(ride.eventName, color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Box(modifier = Modifier.clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("PENDING", color = Color(0xFFF59E0B), fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text(ride.eventDate, color = Color(0xFF4AB8E8), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Text("Tap to edit or open ride",
                            color = Color(0xFF2A4060), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        GroupTrackBottomNav(
            activeTab = GroupTrackTab.RIDES,
            onHome    = { onBack() },
            onRides   = {},
            onMap     = { onBack() },
            onProfile = {},
            onRadio   = onNavigateToFieldRadio
        )
    }
}
