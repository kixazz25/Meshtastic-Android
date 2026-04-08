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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// ConvoyBroadcastScreen.kt — s13
// V3.0 Phase B — Broadcast stub
// Phase C: POST /broadcast/{ride_id}, email queue insert
// ============================================================

@Composable
fun ConvoyBroadcastScreen(
    onNavigateToCreateRide: () -> Unit = {},
    onNavigateToFieldRadio: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var broadcastSent by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "Broadcast")
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Text(text = "SUNDAY DESERT RUN", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(text = "April 13, 2026  ·  9:00 AM", color = Color(0xFF445566), fontSize = 12.sp)

            // Audience stats
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "BROADCAST AUDIENCE", color = Color(0xFF445566), fontSize = 9.sp, letterSpacing = 2.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Followers", color = Color(0xFFAABBCC), fontSize = 12.sp)
                    Text(text = "12", color = Color(0xFF4AB8E8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Email opt-ins", color = Color(0xFFAABBCC), fontSize = 12.sp)
                    Text(text = "9", color = Color(0xFF4AB8E8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (broadcastSent) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF22C55E).copy(alpha = 0.15f))
                    .padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(text = "✅ BROADCAST SENT", color = Color(0xFF22C55E),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(text = "This ride has already been broadcast. One-time only.",
                    color = Color(0xFF445566), fontSize = 11.sp)
            } else {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
                    .clickable {
                        broadcastSent = true
                        android.widget.Toast.makeText(context, "Broadcast queued — Phase C", android.widget.Toast.LENGTH_SHORT).show()
                    }.padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(text = "SEND BROADCAST", color = Color(0xFFF59E0B),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(text = "Sends email to all followers who have opted in. One-time only per ride.",
                    color = Color(0xFF445566), fontSize = 11.sp)
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            BcastNavBtn("🗺", "MAP") { onBack() }
            BcastNavBtn("＋", "+RIDE") { onNavigateToCreateRide() }
            BcastNavBtn("📻", "RADIO") { onNavigateToFieldRadio() }
        }
    }
}

@Composable
private fun BcastNavBtn(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = icon, fontSize = 18.sp)
        Text(text = label, color = Color(0xFF4AB8E8), fontSize = 8.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
