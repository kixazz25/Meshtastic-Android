package com.geeksville.mesh.convoy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConvoyRideDetailScreen(rideId: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "Ride Detail")
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(text = "Ride Detail — Phase C", color = Color(0xFF445566), fontSize = 13.sp)
        }
        Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(16.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Text(text = "← BACK", color = Color(0xFF445566), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
