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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// ConvoyRidesScreen.kt
// V3.0 Phase B — Rides Menu
// Entry point for all ride management functions.
// Stubs for history and search — built out as volume grows.
// ============================================================

@Composable
fun ConvoyRidesScreen(
    viewModel: ConvoyViewModel? = null,
    onNavigateToPendingRides: () -> Unit = {},
    onNavigateToFieldRadio: () -> Unit = {},
    onApplyMasterConfig: () -> Unit = {},
    onArchiveRestore: () -> Unit = {},
    onNavigateToCompletedRides: (String) -> Unit = {},
    onNavigateToSearchByArea: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("RIDES", color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Color(0xFF4AB8E8))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── CREATE / OPEN A RIDE ──────────────────────────────────────────
            RidesMenuItem(
                label    = "CREATE / OPEN A RIDE",
                subtitle = "Create new ride or open a pending ride",
                status   = "ACTIVE",
                onClick  = onNavigateToPendingRides
            )

            // ── MY COMPLETED RIDES — STUB ─────────────────────────────────────
            // Shows organizer and rider completed rides.
            // Sorted most frequent first, oldest last.
            // Entry point for track upload, survey, trail comments.
            // Phase C — ConvoyCompletedRidesScreen.kt
            RidesMenuItem(
                label    = "MY COMPLETED RIDES",
                subtitle = "Organizer and rider history — most recent first",
                status   = "ACTIVE",
                onClick  = { onNavigateToCompletedRides("HISTORY") }
            )

            // ── SEARCH BY AREA — STUB ─────────────────────────────────────────
            // Map-based search for completed rides in a geographic area.
            // Uses zip code + mileage radius filter.
            // Shows track version on map.
            // Phase C — ConvoySearchAreaScreen.kt
            RidesMenuItem(
                label    = "SEARCH RIDES BY AREA",
                subtitle = "Find rides near a location — zip + mileage filter  •  Requires new map",
                status   = "PHASE C",
                onClick  = {}
            )

            // ── SEARCH BY ORGANIZER — STUB ────────────────────────────────────
            // Search completed rides by organizer name.
            // Results in twistie per organizer.
            // Zip + mileage filter applies.
            // Phase C — ConvoySearchOrganizerScreen.kt
            RidesMenuItem(
                label    = "SEARCH BY ORGANIZER",
                subtitle = "Find rides by organizer",
                status   = "ACTIVE",
                onClick  = { onNavigateToCompletedRides("BY ORGANIZER") }
            )

            // ── SEARCH BY TRAIL — STUB ────────────────────────────────────────
            // Search by trail name or trailhead.
            // Zip + mileage filter applies.
            // Phase C — ConvoySearchTrailScreen.kt
            RidesMenuItem(
                label    = "SEARCH BY NAME",
                subtitle = "Find rides by name",
                status   = "ACTIVE",
                onClick  = { onNavigateToCompletedRides("BY NAME") }
            )
            RidesMenuItem(
                label    = "SEARCH BY DATE RANGE",
                subtitle = "Find rides within a date range",
                status   = "ACTIVE",
                onClick  = { onNavigateToCompletedRides("BY DATE") }
            )
        }

        GroupTrackBottomNav(
            activeTab = GroupTrackTab.RIDES,
            onHome    = { onBack() },
            onRides   = {},
            onMap     = { onBack() },
            onProfile = {},
            onRadio   = onNavigateToFieldRadio,
            onApplyMasterConfig = onApplyMasterConfig,
            onArchiveRestore = onArchiveRestore,
            onNavigateToCompletedRides = onNavigateToCompletedRides,
            onNavigateToSearchByArea = onNavigateToSearchByArea
        )
    }
}

@Composable
private fun RidesMenuItem(
    label: String,
    subtitle: String,
    status: String,
    onClick: () -> Unit
) {
    val isActive = status == "ACTIVE"
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (isActive) Color(0xFF0F2035) else Color(0xFF080E18))
            .then(if (isActive) Modifier.clickable { onClick() } else Modifier)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f)) {
            Text(label,
                color = if (isActive) Color.White else Color(0xFF2A4060),
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle,
                color = if (isActive) Color(0xFF445566) else Color(0xFF1A3050),
                fontSize = 10.sp, lineHeight = 14.sp)
        }
        Box(modifier = Modifier.clip(RoundedCornerShape(3.dp))
            .background(
                if (isActive) Color(0xFF4AB8E8).copy(alpha = 0.15f)
                else Color(0xFF1A3050).copy(alpha = 0.3f))
            .padding(horizontal = 7.dp, vertical = 3.dp)) {
            Text(status,
                color = if (isActive) Color(0xFF4AB8E8) else Color(0xFF2A4060),
                fontSize = 8.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
        }
    }
}
