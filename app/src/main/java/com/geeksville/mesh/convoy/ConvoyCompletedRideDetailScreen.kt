package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import android.content.Intent
import android.net.Uri
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
// ConvoyCompletedRideDetailScreen.kt
// V3.0 Phase B — Completed Ride Detail
// Same layout as ConvoyRideDetailScreen but with
// post-ride action buttons: MAP / DOWNLOAD TRACKS / SURVEY+COMMENTS
// Phase C: replace seeder data with GET /rides/{id}
// ============================================================

@Composable
fun ConvoyCompletedRideDetailScreen(
    rideId: String = "",
    onNavigateToMap: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context     = LocalContext.current
    val scrollState = rememberScrollState()

    // Seeder data — Phase C: load from GET /rides/{rideId}
    val rideData = remember(rideId) {
        mapOf(
            "ride-001" to mapOf("name" to "Sunday Desert Run — Gooseberry Mesa",   "date" to "March 2, 2026",   "arrive" to "7:30 AM", "rollout" to "8:00 AM", "city" to "Springdale, UT",  "trailhead" to "Gooseberry Trailhead",   "org" to "Fred Dev",        "email" to "fred@grouptrack.org",    "distance" to "24 mi", "desc" to "Moderate terrain, UTV recommended."),
            "ride-002" to mapOf("name" to "Sand Hollow OHV Adventure",             "date" to "March 15, 2026",  "arrive" to "8:00 AM", "rollout" to "8:30 AM", "city" to "Hurricane, UT",   "trailhead" to "Sand Hollow State Park", "org" to "Dave H",          "email" to "dave@grouptrack.org",    "distance" to "18 mi", "desc" to "Beginner friendly. Wide open terrain."),
            "ride-003" to mapOf("name" to "Gooseberry Mesa Evening Run",           "date" to "March 22, 2026",  "arrive" to "3:00 PM", "rollout" to "3:30 PM", "city" to "Springdale, UT",  "trailhead" to "Gooseberry Trailhead",   "org" to "Red Rock Riders", "email" to "info@redrockriders.org", "distance" to "22 mi", "desc" to "Evening golden hour ride. Bring lights."),
            "ride-004" to mapOf("name" to "Zion Overlook Trail Ride",              "date" to "April 5, 2026",   "arrive" to "6:30 AM", "rollout" to "7:00 AM", "city" to "Rockville, UT",   "trailhead" to "Smithsonian Butte Rd",   "org" to "Fred Dev",        "email" to "fred@grouptrack.org",    "distance" to "31 mi", "desc" to "Technical sections. Experienced riders."),
            "ride-005" to mapOf("name" to "JEM Trail Technical Run",               "date" to "April 12, 2026",  "arrive" to "7:00 AM", "rollout" to "7:30 AM", "city" to "St. George, UT",  "trailhead" to "JEM Trailhead",          "org" to "Dave H",          "email" to "dave@grouptrack.org",    "distance" to "16 mi", "desc" to "Rocky technical terrain. High clearance required."),
        )
    }
    val ride = rideData[rideId] ?: rideData["ride-001"]!!

    var surveyExpanded by remember { mutableStateOf(false) }
    var surveyRating   by remember { mutableStateOf(0) }
    var surveyComment  by remember { mutableStateOf("") }
    var surveySaved    by remember { mutableStateOf(false) }

    fun launchEmail(address: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address"))
        context.startActivity(Intent.createChooser(intent, "Send email"))
    }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        // ── Top bar ───────────────────────────────────────────────────────────
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
            Text("RIDE DETAIL", color = Gt.text, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.clip(RoundedCornerShape(3.dp))
                .background(Gt.panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("COMPLETED", color = Gt.accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Gt.accent)

        Column(modifier = Modifier.fillMaxWidth().weight(1f)
            .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // ── Ride header ───────────────────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ride["name"] ?: "", color = Gt.text, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold)
                Text("${ride["city"]}  —  ${ride["trailhead"]}", color = Gt.text,
                    fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DetailField("DATE",     ride["date"] ?: "")
                    DetailField("ARRIVE",   ride["arrive"] ?: "")
                    DetailField("ROLLOUT",  ride["rollout"] ?: "")
                    DetailField("DISTANCE", ride["distance"] ?: "")
                }
                Text(ride["desc"] ?: "", color = Gt.text, fontSize = 12.sp,
                    lineHeight = 16.sp)
            }

            // ── Organizer ─────────────────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("ORGANIZER", color = Gt.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp)
                    Text(ride["org"] ?: "", color = Gt.text, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold)
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(Gt.panel)
                    .clickable { launchEmail(ride["email"] ?: "") }
                    .padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(ride["email"] ?: "", color = Gt.accent, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }

            // ── Post-ride action buttons ──────────────────────────────────────
            Text("POST-RIDE ACTIONS", color = Gt.accent, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp)

            // MAP button
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel)
                .clickable { onNavigateToMap() }
                .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("VIEW ON MAP", color = Gt.text, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Show ride track on convoy map", color = Gt.text, fontSize = 12.sp)
                }
                Text("→", color = Gt.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            // DOWNLOAD TRACKS button
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel)
                .clickable { /* Phase C: download GPX/KML to my_tracks */ }
                .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("DOWNLOAD TRACKS", color = Gt.text, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Save GPX/KML to My Tracks  •  Phase C", color = Gt.text, fontSize = 12.sp)
                }
                Text("↓", color = Gt.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            // SURVEY + COMMENTS expandable
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel)) {
                Row(modifier = Modifier.fillMaxWidth().clickable { surveyExpanded = !surveyExpanded }
                    .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("SURVEY + COMMENTS", color = Gt.text, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                        Text("Rate this ride and leave trail comments", color = Gt.text,
                            fontSize = 12.sp)
                    }
                    Text(if (surveyExpanded) "▲" else "▼", color = Gt.accent,
                        fontSize = 12.sp)
                }
                if (surveyExpanded) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Star rating
                        Text("RIDE RATING", color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..5).forEach { star ->
                                Text(if (star <= surveyRating) "★" else "☆",
                                    color = if (star <= surveyRating) Gt.warn else Gt.text,
                                    fontSize = 28.sp, modifier = Modifier.clickable { surveyRating = star })
                            }
                        }
                        // Comments
                        Text("TRAIL COMMENTS", color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        androidx.compose.material3.OutlinedTextField(
                            value = surveyComment,
                            onValueChange = { surveyComment = it },
                            placeholder = { Text("Trail conditions, notes for other riders...",
                                color = Gt.text, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Gt.accent,
                                unfocusedBorderColor = Gt.line,
                                focusedTextColor = Gt.text,
                                unfocusedTextColor = Gt.text,
                                cursorColor = Gt.accent
                            )
                        )
                        if (surveySaved) {
                            Text("✓ SURVEY SAVED  —  Phase C: POST /rides/{id}/survey",
                                color = Gt.accent, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace)
                        } else {
                            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (surveyRating > 0) Gt.panel else Gt.panel)
                                .then(if (surveyRating > 0) Modifier.clickable {
                                    surveySaved = true
                                    // Phase C: POST /rides/{rideId}/survey
                                } else Modifier)
                                .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center) {
                                Text(if (surveyRating > 0) "SAVE SURVEY" else "SELECT A RATING FIRST",
                                    color = if (surveyRating > 0) Gt.accent else Gt.text,
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Gt.accent, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        Text(value, color = Gt.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
