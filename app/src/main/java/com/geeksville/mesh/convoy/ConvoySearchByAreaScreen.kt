package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// ConvoySearchByAreaScreen.kt
// V2.5 stub — Search Completed Rides by Map Area
//
// FUNCTIONAL DEFINITION:
//   Map centered on user zip code at default 10-mile radius.
//   User pans and zooms to define search area.
//   Ride tracks from server displayed as overlays on map.
//   Rides visible within map bounds appear in list below.
//   Standard CompletedRideCard + ConvoyCompletedRideDetailScreen.
//   Tap track on map to open ride detail directly.
//
// DEPENDENCIES:
//   MAP-03 KML/GPX overlay layer (V2.5)
//   GET /ride_tracks?bounds=N,S,E,W API endpoint
//   Leaflet bounds change listener -> Android bridge
//
// STATUS: V2.5 stub
// ============================================================

@Composable
fun ConvoySearchByAreaScreen(
    onBack: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        Row(modifier = Modifier.fillMaxWidth().background(Gt.panel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(Gt.panel).clickable { onBack() }
                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("← BACK", color = Gt.accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("SEARCH BY AREA", color = Gt.text, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.clip(RoundedCornerShape(3.dp))
                .background(Gt.panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("V2.5", color = Gt.warn, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Gt.accent)

        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {

            Spacer(Modifier.height(20.dp))

            Text("SEARCH BY MAP AREA", color = Gt.text, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Coming in V2.5", color = Gt.warn, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WHAT THIS WILL DO:", color = Gt.accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp)
                listOf(
                    "Map opens centered on your zip code",
                    "Pan and zoom to define search area",
                    "Completed ride tracks overlay on map",
                    "Rides visible in map bounds appear in list below",
                    "Standard ride card and detail view",
                    "Tap any track on map to open ride detail"
                ).forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = Gt.accent, fontSize = 12.sp)
                        Text(item, color = Gt.text, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("REQUIRES V2.5 MAP FUNCTIONS", color = Gt.text, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("MAP-03  KML/GPX overlay layer", color = Gt.text, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
                Text("API     GET /ride_tracks?bounds=N,S,E,W", color = Gt.text,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("BRIDGE  Leaflet bounds change listener", color = Gt.text,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
