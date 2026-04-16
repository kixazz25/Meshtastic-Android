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
import kotlinx.coroutines.launch

// ============================================================
// ConvoyMyOrganizersScreen.kt
// V3.0 Phase B — Profile Menu Item 2
// My Organizers — list organizers in zip/radius area
// Three tabs: FOLLOWING | NOT FOLLOWING | ALL
// Follow/unfollow toggle per row
// Phase C: replace seeder data with GET /users?is_organizer=1&zip=&radius=
// ============================================================

@Composable
fun ConvoyMyOrganizersScreen(
    onBack: () -> Unit = {}
) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Seeder organizers — Phase C: replace with API call
    data class OrganizerRow(val id: String, val name: String, val location: String)
    val allOrganizers = remember {
        listOf(
            OrganizerRow("dev-user-001", "Fred Dev",         "St. George, UT"),
            OrganizerRow("org-dave-001", "Dave H",           "St. George, UT"),
            OrganizerRow("org-rrr-001",  "Red Rock Riders",  "St. George, UT"),
            OrganizerRow("org-moab-001", "Moab Trail Crew",  "Moab, UT"),
            OrganizerRow("org-slc-001",  "SLC Offroad Club", "Salt Lake City, UT")
        )
    }

    // Follow state — load from ConvoySessionManager, toggle locally
    // Phase C: sync with server on toggle
    val followedIds = remember {
        mutableStateOf(
            setOf("org-dave-001", "org-rrr-001") // seeder default — dave and red rock followed
        )
    }

    var activeTab by remember { mutableStateOf("ALL") }
    val tabs = listOf("FOLLOWING", "NOT FOLLOWING", "ALL")

    val displayList = remember(activeTab, followedIds.value) {
        when (activeTab) {
            "FOLLOWING"     -> allOrganizers.filter { it.id in followedIds.value }
            "NOT FOLLOWING" -> allOrganizers.filter { it.id !in followedIds.value }
            else            -> allOrganizers
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1A3050)).clickable { onBack() }
                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("← BACK", color = Color(0xFF4AB8E8), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("MY ORGANIZERS", color = Color.White, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Color(0xFF4AB8E8))

        // ── Tab bar ───────────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
            .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { tab ->
                val isActive = tab == activeTab
                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (isActive) Color(0xFF1A3050) else Color(0xFF050E1A))
                    .clickable { activeTab = tab }
                    .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(tab, color = if (isActive) Color(0xFF4AB8E8) else Color(0xFF2A4060),
                        fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = Color(0xFF1A2840))

        // ── Organizer list ────────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxWidth().weight(1f)
            .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            if (displayList.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F2035)).padding(24.dp),
                    contentAlignment = Alignment.Center) {
                    Text(
                        when (activeTab) {
                            "FOLLOWING"     -> "YOU ARE NOT FOLLOWING ANY ORGANIZERS"
                            "NOT FOLLOWING" -> "YOU FOLLOW ALL ORGANIZERS IN YOUR AREA"
                            else            -> "NO ORGANIZERS FOUND IN YOUR AREA"
                        },
                        color = Color(0xFF2A4060), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                displayList.forEach { org ->
                    val isFollowing = org.id in followedIds.value
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F2035)).padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {

                        Column(modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(org.name, color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold)
                            Text(org.location, color = Color(0xFF445566), fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)
                        }

                        // Follow/unfollow toggle
                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isFollowing) Color(0xFF0D2010) else Color(0xFF1A3050)
                            )
                            .clickable {
                                scope.launch {
                                    val userId = ConvoySessionManager.getUserId(context) ?: return@launch
                                    if (isFollowing) {
                                        ConvoyApiClient.unfollowOrganizer(userId, org.id)
                                        followedIds.value = followedIds.value - org.id
                                    } else {
                                        ConvoyApiClient.followOrganizer(userId, org.id)
                                        followedIds.value = followedIds.value + org.id
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                if (isFollowing) "✓ FOLLOWING" else "+ FOLLOW",
                                color = if (isFollowing) Color(0xFF22C55E) else Color(0xFF4AB8E8),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Phase C note
            Spacer(Modifier.height(4.dp))
            Text("Phase C: list populated from GET /users?is_organizer=1 filtered by your zip + radius",
                color = Color(0xFF1A2840), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(60.dp))
        }
    }
}
