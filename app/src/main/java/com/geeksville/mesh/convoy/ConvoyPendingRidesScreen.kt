package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
// V3.0 Phase B — Organizer Pending Rides Composable
// REFACTORED: No longer a standalone screen.
// Renders as a section within Dashboard Section 1 for organizers.
// Shows pending rides list + CREATE NEW RIDE button.
// Tap ride → navigates to ConvoyCreateRideScreen pre-populated.
// CREATE NEW RIDE → navigates to blank ConvoyCreateRideScreen.
// All business logic remains in ConvoyCreateRideScreen and ConvoyEventConfig.
// ============================================================

@Composable
fun ConvoyOrganizerPendingRides(
    onNavigateToCreateRide: (eventId: String?) -> Unit = {}
) {
    val context = LocalContext.current

    // Load pending rides from seeder — Phase C: replace with GET /rides?status=pending
    val pendingRides = remember {
        val count = ConvoyDevSeeder.getRideCount(context)
        (0 until count).filter { i ->
            ConvoyDevSeeder.getRideStatus(context, i) == "ORGANIZED" &&
            ConvoyDevSeeder.getRideField(context, i, "ride_status") == "PENDING"
        }.map { i ->
            Triple(
                ConvoyDevSeeder.getRideField(context, i, "ride_004_id").ifEmpty { "ride-00${i+1}" },
                ConvoyDevSeeder.getRideName(context, i),
                ConvoyDevSeeder.getRideDate(context, i)
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // ── Section header ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(Color(0xFF0F2035)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "WORK WITH PENDING RIDES",
                color = Color(0xFF4AB8E8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${pendingRides.size} PENDING",
                color = Color(0xFFF59E0B),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }



        // ── Pending rides list ────────────────────────────────────────────────
        if (pendingRides.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F2035)).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "NO PENDING RIDES",
                        color = Color(0xFF2A4060),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Tap Create to start a new ride",
                        color = Color(0xFF1A3050),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            pendingRides.forEach { ride ->
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F2035))
                        .clickable { onNavigateToCreateRide(ride.first) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ride.second,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "PENDING",
                                color = Color(0xFFF59E0B),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text(
                        ride.third,
                        color = Color(0xFF4AB8E8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Tap to edit · OPEN RIDE to launch",
                        color = Color(0xFF2A4060),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
