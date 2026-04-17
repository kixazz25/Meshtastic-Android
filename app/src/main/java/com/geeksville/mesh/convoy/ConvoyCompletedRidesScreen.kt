package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
// ConvoyCompletedRidesScreen.kt
// V3.0 Phase B — Rides Menu items 2-5
// HISTORY: shows immediately, all completed rides
// BY NAME: mileage slider + ALL toggle, search executes on button tap
// BY ORGANIZER: mileage slider + ALL toggle, shows accordions immediately
// BY DATE: mileage slider + ALL toggle, search executes on button tap
// Phase C: replace seeder data with GET /rides?status=completed
// ============================================================

data class CompletedRide(
    val id: String,
    val name: String,
    val date: String,
    val organizer: String,
    val organizerId: String,
    val city: String,
    val trailhead: String,
    val distance: String
)

@Composable
fun ConvoyCompletedRidesScreen(
    initialTab: String = "HISTORY",
    onNavigateToDetail: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context     = LocalContext.current
    val scrollState = rememberScrollState()
    var activeTab   by remember { mutableStateOf(initialTab) }
    val tabs = listOf("HISTORY", "BY NAME", "BY ORGANIZER", "BY DATE")

    val userRadius = remember { ConvoySessionManager.getSearchRadius(context).toFloat() }

    // Seeder completed rides — Phase C: replace with API
    val allRides = remember {
        listOf(
            CompletedRide("ride-001", "Sunday Desert Run — Gooseberry Mesa",   "March 2, 2026",    "Fred Dev",        "dev-user-001", "Springdale, UT",   "Gooseberry Trailhead",   "24 mi"),
            CompletedRide("ride-002", "Sand Hollow OHV Adventure",                  "March 15, 2026",   "Dave H",          "org-dave-001", "Hurricane, UT",    "Sand Hollow State Park", "18 mi"),
            CompletedRide("ride-003", "Gooseberry Mesa Evening Run",                "March 22, 2026",   "Red Rock Riders", "org-rrr-001",  "Springdale, UT",   "Gooseberry Trailhead",   "22 mi"),
            CompletedRide("ride-004", "Zion Overlook Trail Ride",                   "April 5, 2026",    "Fred Dev",        "dev-user-001", "Rockville, UT",    "Smithsonian Butte Rd",   "31 mi"),
            CompletedRide("ride-005", "JEM Trail Technical Run",                    "April 12, 2026",   "Dave H",          "org-dave-001", "St. George, UT",   "JEM Trailhead",          "16 mi"),
        )
    }

    val organizers = remember { allRides.map { it.organizer to it.organizerId }.distinct() }

    // Per-tab search state
    var nameQuery       by remember { mutableStateOf("") }
    var nameSearched    by remember { mutableStateOf(false) }
    var nameRadius      by remember { mutableStateOf(userRadius) }
    var nameAllRides    by remember { mutableStateOf(false) }

    var orgRadius       by remember { mutableStateOf(userRadius) }
    var orgAllRides     by remember { mutableStateOf(false) }
    val orgExpanded     = remember { mutableStateMapOf<String, Boolean>() }

    var dateFrom        by remember { mutableStateOf("") }
    var dateTo          by remember { mutableStateOf("") }
    var dateSearched    by remember { mutableStateOf(false) }
    var dateRadius      by remember { mutableStateOf(userRadius) }
    var dateAllRides    by remember { mutableStateOf(false) }

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
            Text("COMPLETED RIDES", color = Color.White, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = Color(0xFF4AB8E8))

        // ── Tab bar ───────────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
            .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { tab ->
                val isActive = tab == activeTab
                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (isActive) Color(0xFF1A3050) else Color(0xFF050E1A))
                    .clickable { activeTab = tab }
                    .padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(tab, color = if (isActive) Color(0xFF4AB8E8) else Color(0xFF2A4060),
                        fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = Color(0xFF1A2840))

        Column(modifier = Modifier.fillMaxWidth().weight(1f)
            .verticalScroll(scrollState).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            when (activeTab) {

                // ── HISTORY — show all immediately ────────────────────────────
                "HISTORY" -> {
                    if (allRides.isEmpty()) {
                        EmptyState("NO COMPLETED RIDES FOUND")
                    } else {
                        allRides.forEach { ride ->
                            CompletedRideCard(ride = ride, onClick = { onNavigateToDetail(ride.id) })
                        }
                    }
                }

                // ── BY NAME ───────────────────────────────────────────────────
                "BY NAME" -> {
                    FilterBar(
                        radius = nameRadius,
                        zipCode = ConvoySessionManager.getZipCode(context),
                        city = ConvoySessionManager.getCity(context),
                        state = ConvoySessionManager.getState(context),
                        allRides = nameAllRides,
                        onRadiusChange = { nameRadius = it; nameSearched = false },
                        onAllRidesChange = { nameAllRides = it; nameSearched = false }
                    )
                    OutlinedTextField(
                        value = nameQuery,
                        onValueChange = { nameQuery = it; nameSearched = false },
                        label = { Text("Search ride name", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4AB8E8),
                            unfocusedBorderColor = Color(0xFF1A3050),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFF4AB8E8),
                            unfocusedLabelColor = Color(0xFF445566),
                            cursorColor = Color(0xFF4AB8E8)
                        )
                    )
                    SearchButton(enabled = nameQuery.length >= 2) { nameSearched = true }
                    if (nameSearched) {
                        val results = allRides.filter { it.name.contains(nameQuery, ignoreCase = true) }
                        if (results.isEmpty()) EmptyState("NO RIDES FOUND MATCHING \"$nameQuery\"")
                        else results.forEach { ride ->
                            CompletedRideCard(ride = ride, onClick = { onNavigateToDetail(ride.id) })
                        }
                    } else if (nameQuery.isEmpty()) {
                        SearchPrompt("Enter a ride name to search")
                    }
                }

                // ── BY ORGANIZER — show accordions immediately ────────────────
                "BY ORGANIZER" -> {
                    FilterBar(
                        radius = orgRadius,
                        zipCode = ConvoySessionManager.getZipCode(context),
                        allRides = orgAllRides,
                        onRadiusChange = { orgRadius = it },
                        onAllRidesChange = { orgAllRides = it }
                    )
                    organizers.forEach { (name, id) ->
                        val orgRides = allRides.filter { it.organizerId == id }
                        val expanded = orgExpanded.getOrDefault(id, false)
                        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F2035))) {
                            Row(modifier = Modifier.fillMaxWidth()
                                .clickable { orgExpanded[id] = !expanded }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, color = Color.White, fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold)
                                    Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF1A3050))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)) {
                                        Text("${orgRides.size} RIDE${if (orgRides.size != 1) "S" else ""}",
                                            color = Color(0xFF4AB8E8), fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace)
                                    }
                                }
                                Text(if (expanded) "▲" else "▼",
                                    color = Color(0xFF4AB8E8), fontSize = 11.sp)
                            }
                            if (expanded) {
                                androidx.compose.material3.HorizontalDivider(
                                    thickness = 1.dp, color = Color(0xFF1A2840))
                                Column(modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    orgRides.forEach { ride ->
                                        CompletedRideCard(ride = ride,
                                            onClick = { onNavigateToDetail(ride.id) })
                                    }
                                }
                            }
                        }
                    }
                }

                // ── BY DATE ───────────────────────────────────────────────────
                "BY DATE" -> {
                    FilterBar(
                        radius = dateRadius,
                        zipCode = ConvoySessionManager.getZipCode(context),
                        allRides = dateAllRides,
                        onRadiusChange = { dateRadius = it; dateSearched = false },
                        onAllRidesChange = { dateAllRides = it; dateSearched = false }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dateFrom,
                            onValueChange = { dateFrom = it; dateSearched = false },
                            label = { Text("From MM/DD/YYYY", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4AB8E8),
                                unfocusedBorderColor = Color(0xFF1A3050),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedLabelColor = Color(0xFF4AB8E8),
                                unfocusedLabelColor = Color(0xFF445566),
                                cursorColor = Color(0xFF4AB8E8)
                            )
                        )
                        OutlinedTextField(
                            value = dateTo,
                            onValueChange = { dateTo = it; dateSearched = false },
                            label = { Text("To MM/DD/YYYY", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4AB8E8),
                                unfocusedBorderColor = Color(0xFF1A3050),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedLabelColor = Color(0xFF4AB8E8),
                                unfocusedLabelColor = Color(0xFF445566),
                                cursorColor = Color(0xFF4AB8E8)
                            )
                        )
                    }
                    SearchButton(enabled = dateFrom.isNotEmpty() || dateTo.isNotEmpty()) {
                        dateSearched = true
                    }
                    if (dateSearched) {
                        // Phase C: real date filter against ride_date field
                        val results = allRides
                        if (results.isEmpty()) EmptyState("NO RIDES FOUND IN DATE RANGE")
                        else results.forEach { ride ->
                            CompletedRideCard(ride = ride, onClick = { onNavigateToDetail(ride.id) })
                        }
                    } else {
                        SearchPrompt("Enter a date range to search")
                    }
                }
            }
            Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
private fun FilterBar(
    radius: Float,
    allRides: Boolean,
    zipCode: String = "",
    city: String = "",
    state: String = "",
    onRadiusChange: (Float) -> Unit,
    onAllRidesChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF0A1628)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("SEARCH AREA", color = Color(0xFF4AB8E8), fontSize = 9.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            // ALL RIDES toggle
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { onAllRidesChange(!allRides) }) {
                Text("ALL RIDES", color = if (allRides) Color.White else Color(0xFF2A4060),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (allRides) Color(0xFF4AB8E8) else Color.Transparent)
                    .then(Modifier.clickable { onAllRidesChange(!allRides) }),
                    contentAlignment = Alignment.Center) {
                    if (!allRides) Box(modifier = Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Transparent)
                        .then(Modifier.padding(1.dp)
                            .background(Color(0xFF2A4060), RoundedCornerShape(2.dp))))
                    else Text("✓", color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!allRides) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("RADIUS", color = Color(0xFF445566), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
                Text(
                    buildString {
                        append("${radius.toInt()} mi")
                        val location = buildString {
                            if (city.isNotEmpty() && state.isNotEmpty()) append("$city, $state")
                            else if (city.isNotEmpty()) append(city)
                            else if (state.isNotEmpty()) append(state)
                        }
                        if (location.isNotEmpty()) append("  •  $location")
                        if (zipCode.isNotEmpty()) append("  $zipCode")
                        if (location.isEmpty() && zipCode.isEmpty()) append("  •  complete profile to set location")
                    },
                    color = Color(0xFF4AB8E8), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = radius,
                onValueChange = onRadiusChange,
                valueRange = 10f..500f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF4AB8E8),
                    activeTrackColor = Color(0xFF4AB8E8),
                    inactiveTrackColor = Color(0xFF1A3050)
                )
            )
        }
    }
}

@Composable
private fun SearchButton(enabled: Boolean, onSearch: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (enabled) Color(0xFF1A3050) else Color(0xFF080E18))
        .then(if (enabled) Modifier.clickable { onSearch() } else Modifier)
        .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text("SEARCH", color = if (enabled) Color(0xFF4AB8E8) else Color(0xFF1A3050),
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SearchPrompt(message: String) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF080E18)).padding(24.dp),
        contentAlignment = Alignment.Center) {
        Text(message, color = Color(0xFF2A4060), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF0F2035)).padding(24.dp),
        contentAlignment = Alignment.Center) {
        Text(message, color = Color(0xFF2A4060), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun CompletedRideCard(ride: CompletedRide, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF0F2035)).clickable { onClick() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(ride.name, color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF0D2010)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("COMPLETED", color = Color(0xFF22C55E), fontSize = 8.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        Text("${ride.city}  —  ${ride.trailhead}", color = Color(0xFF445566), fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ride.date, color = Color(0xFF4AB8E8), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
            Text(ride.organizer, color = Color(0xFF445566), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
            Text(ride.distance, color = Color(0xFF445566), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
        }
        Text("→ TAP FOR DETAILS", color = Color(0xFF2A4060), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace)
    }
}
