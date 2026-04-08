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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// ConvoyInviteSendScreen.kt — s12
// V3.0 Phase B — Send Invite stub
// Phase C: POST /invites, QR code, share sheet
// ============================================================

@Composable
fun ConvoyInviteSendScreen(
    onNavigateToCreateRide: () -> Unit = {},
    onNavigateToFieldRadio: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "Send Invite")
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Text(text = "SUNDAY DESERT RUN", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(text = "April 13, 2026  ·  9:00 AM", color = Color(0xFF445566), fontSize = 12.sp)

            // QR Code placeholder
            Box(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0F2035)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "▦", color = Color(0xFF4AB8E8), fontSize = 80.sp)
                    Text(text = "QR CODE — Phase C", color = Color(0xFF445566),
                        fontSize = 10.sp, letterSpacing = 2.sp)
                }
            }

            // Invite link
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "INVITE LINK", color = Color(0xFF445566), fontSize = 9.sp, letterSpacing = 2.sp)
                    Text(text = "grouptrack.org/invite/dev-token-001",
                        color = Color(0xFF4AB8E8), fontSize = 11.sp)
                }
            }

            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(GroupTrackColors.SkyBlue)
                .clickable {
                    android.widget.Toast.makeText(context, "Share sheet — Phase C", android.widget.Toast.LENGTH_SHORT).show()
                }.padding(14.dp), contentAlignment = Alignment.Center) {
                Text("COPY & SHARE LINK", color = GroupTrackColors.Navy,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Text(text = "Riders tap the link to download ride data, maps, and radio config automatically.",
                color = Color(0xFF445566), fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }

        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            NavBtn3("🗺", "MAP") { onBack() }
            NavBtn3("＋", "+RIDE") { onNavigateToCreateRide() }
            NavBtn3("📻", "RADIO") { onNavigateToFieldRadio() }
        }
    }
}

@Composable
private fun NavBtn3(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = icon, fontSize = 18.sp)
        Text(text = label, color = Color(0xFF4AB8E8), fontSize = 8.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
