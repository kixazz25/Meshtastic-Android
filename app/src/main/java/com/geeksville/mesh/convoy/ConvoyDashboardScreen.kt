package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

@Composable
fun ConvoyDashboardScreen(
    isSubscribed: Boolean,
    onNavigateToRides: () -> Unit,
    onNavigateToExplore: () -> Unit,
    onNavigateToTracks: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToFieldRadio: () -> Unit,
    onShowSubscription: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var seedKey by remember { mutableStateOf(0) }

    val firstName   = remember(seedKey) { ConvoySessionManager.getFirstName(context) }
    val isOrganizer = remember(seedKey) { ConvoySessionManager.isOrganizer(context) }
    var zipCode     by remember { mutableStateOf(ConvoySessionManager.getZipCode(context)) }
    var radius      by remember { mutableStateOf(ConvoySessionManager.getSearchRadius(context).toString()) }
    var showInviteOnly by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }

    val notifCount  = remember(seedKey) { ConvoyDevSeeder.getNotifCount(context) }
    val rideCount   = remember(seedKey) { ConvoyDevSeeder.getRideCount(context) }
    val inviteCount = remember(seedKey) { ConvoyDevSeeder.getInviteCount(context) }
    val pubCount    = remember(seedKey) { ConvoyDevSeeder.getPublicRideCount(context) }

    // Accordion state — only MY RIDES collapses
    var ridesExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        GroupTrackHeader(subtitle = if (firstName.isNotEmpty()) "Welcome, $firstName" else "Dashboard")

        // Role badges + dev menu
        var showDevMenu by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoleBadge("RIDER", Color(0xFF22C55E))
                if (isOrganizer) RoleBadge("ORGANIZER", Color(0xFF4AB8E8))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isSubscribed) {
                    Text(text = "SUBSCRIBE $3/mo", color = Color(0xFFFFCC44),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onShowSubscription() })
                }
                if (ConvoyConfig.V3_FEATURES_ENABLED) {
                    Text(text = "DEV", color = Color(0xFF445566), fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1A3050))
                            .clickable { showDevMenu = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
        }

        if (showDevMenu) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showDevMenu = false }) {
                Column(modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFF0A1628)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "DEV MENU", color = Color(0xFF4AB8E8), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    listOf(
                        "SEED DATA" to { ConvoyDevSeeder.seed(context); android.widget.Toast.makeText(context, "Seeded", android.widget.Toast.LENGTH_SHORT).show() },
                        "CLEAR SESSION" to { ConvoySessionManager.clearSession(context); ConvoyDevSeeder.clear(context); android.widget.Toast.makeText(context, "Cleared", android.widget.Toast.LENGTH_SHORT).show() },
                        "CLEAR + SEED" to { ConvoyDevSeeder.clear(context); ConvoyDevSeeder.seed(context); android.widget.Toast.makeText(context, "Cleared and seeded", android.widget.Toast.LENGTH_SHORT).show() },
                        "SHOW SESSION" to { val uid = ConvoySessionManager.getUserId(context) ?: "none"; android.widget.Toast.makeText(context, "ID:$uid sub:${ConvoySessionManager.isSubscribed(context)}", android.widget.Toast.LENGTH_LONG).show() },
                        "CLOSE" to {}
                    ).forEach { (label, action) ->
                        Box(modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1A3050))
                            .clickable { action(); showDevMenu = false }
                            .padding(12.dp), contentAlignment = Alignment.Center) {
                            Text(text = label, color = Color(0xFF4AB8E8), fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // NOTIFICATIONS — always expanded, full detail, tap to view ride
            if (notifCount > 0) {
                item {
                    DashSectionLabel("NOTIFICATIONS")
                }
                items(notifCount) { i ->
                    val ride = ConvoyDevSeeder.getNotif(context, i, "ride")
                    val msg  = ConvoyDevSeeder.getNotif(context, i, "msg")
                    val time = ConvoyDevSeeder.getNotif(context, i, "time")
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F2035)).clickable { onNavigateToRides() }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "\uD83D\uDD14", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = ride, color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                            Text(text = msg, color = Color(0xFFAABBCC), fontSize = 11.sp)
                        }
                        Text(text = time, color = Color(0xFF445566), fontSize = 10.sp)
                    }
                }
            }

            // PENDING INVITES — always expanded, full detail, action buttons
            if (inviteCount > 0) {
                item {
                    DashSectionLabel("PENDING INVITES")
                }
                items(inviteCount) { i ->
                    val rideName  = ConvoyDevSeeder.getInviteField(context, i, "ride")
                    val organizer = ConvoyDevSeeder.getInviteField(context, i, "organizer")
                    val email     = ConvoyDevSeeder.getInviteField(context, i, "email")
                    val date      = ConvoyDevSeeder.getInviteField(context, i, "date")
                    var status by remember { mutableStateOf("INVITED") }
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F2035)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = rideName, color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(text = status, color = dashStatusColor(status),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(text = date, color = Color(0xFF445566), fontSize = 11.sp)
                        Text(text = "Organizer: $organizer", color = Color(0xFF445566), fontSize = 11.sp)
                        Text(text = email, color = Color(0xFF4AB8E8), fontSize = 11.sp)
                        var followInvite by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp)) {
                            Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (followInvite) Color(0xFF4AB8E8) else Color(0xFF1A3050))
                                .clickable { followInvite = !followInvite })
                            Text(text = if (followInvite) "Following $organizer" else "Follow $organizer",
                                color = if (followInvite) Color(0xFF4AB8E8) else Color(0xFF445566),
                                fontSize = 10.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusButton("ACCEPT", Color(0xFF22C55E)) { status = "ACCEPTED" }
                            StatusButton("MAYBE", Color(0xFFF59E0B)) { status = "MAYBE" }
                            StatusButton("DECLINE", Color(0xFFEF4444)) { status = "DECLINED" }
                            StatusButton("DETAIL", Color(0xFF4AB8E8)) { onNavigateToRides() }
                        }
                    }
                }
            }

            // MY RIDES — accordion, collapsed by default
            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    AccordionHeader(
                        title = "MY RIDES",
                        count = rideCount,
                        expanded = ridesExpanded,
                        onClick = { ridesExpanded = !ridesExpanded },
                        modifier = Modifier.weight(1f)
                    )
                    if (ridesExpanded) {
                        Text(text = "SEE ALL", color = Color(0xFF4AB8E8), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            modifier = Modifier.clickable { onNavigateToRides() }.padding(8.dp))
                    }
                }
            }
            if (ridesExpanded) {
                if (rideCount > 0) {
                    items(minOf(rideCount, 3)) { i ->
                        val name      = ConvoyDevSeeder.getRideName(context, i)
                        val status    = ConvoyDevSeeder.getRideStatus(context, i)
                        val date      = ConvoyDevSeeder.getRideDate(context, i)
                        val organizer = ConvoyDevSeeder.getRideOrganizer(context, i)
                        val email     = ConvoyDevSeeder.getRideEmail(context, i)
                        val enrolled  = ConvoyDevSeeder.getRideEnrolled(context, i)
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F2035)).clickable { onNavigateToRides() }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = name, color = Color.White, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(text = status, color = dashStatusColor(status),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(text = date, color = Color(0xFF445566), fontSize = 11.sp)
                            Text(text = "Organizer: $organizer  \u2022  $email",
                                color = Color(0xFF445566), fontSize = 11.sp)
                            if (status == "ORGANIZED") {
                                Text(text = "$enrolled riders enrolled",
                                    color = Color(0xFF4AB8E8), fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    item { EmptyState("No rides yet") }
                }
            }

            // AVAILABLE RIDES NEAR ME — inline editable header, rides collapse with arrow
            item {
                Spacer(Modifier.height(4.dp))
                Column {
                    // Header row — zip/miles editable inline, count badge, arrow toggles rides
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1A3050))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Arrow + label
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable { filterExpanded = !filterExpanded }) {
                            Text(text = if (filterExpanded) "▼" else "▶",
                                color = Color(0xFF4AB8E8), fontSize = 10.sp)
                            Text(text = "RIDES NEAR", color = Color.White,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        // Inline zip field
                        OutlinedTextField(
                            value = zipCode,
                            onValueChange = { zipCode = it; ConvoySessionManager.setZipCode(context, it) },
                            modifier = Modifier.width(90.dp).height(48.dp),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White, fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4AB8E8),
                                unfocusedBorderColor = Color(0xFF2A4060),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF4AB8E8)
                            )
                        )
                        Text(text = "·", color = Color(0xFF445566), fontSize = 12.sp)
                        // Inline miles field
                        OutlinedTextField(
                            value = radius,
                            onValueChange = { v -> radius = v
                                v.toIntOrNull()?.let { r -> ConvoySessionManager.setSearchRadius(context, r) } },
                            modifier = Modifier.width(64.dp).height(48.dp),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White, fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4AB8E8),
                                unfocusedBorderColor = Color(0xFF2A4060),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF4AB8E8)
                            )
                        )
                        Text(text = "mi", color = Color(0xFF445566), fontSize = 10.sp)
                        // Count badge
                        if (pubCount > 0) {
                            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF4AB8E8))
                                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(text = pubCount.toString(), color = Color(0xFF0A1628),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Expanded content — invite toggle + ride rows
                    if (filterExpanded) {
                        Spacer(Modifier.height(6.dp))
                        // Invite toggle
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF0A1628))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "INVITE REQUIRED ONLY", color = Color(0xFF445566),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Switch(
                                checked = showInviteOnly,
                                onCheckedChange = { showInviteOnly = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF4AB8E8),
                                    checkedTrackColor = Color(0xFF1A3050),
                                    uncheckedThumbColor = Color(0xFF445566),
                                    uncheckedTrackColor = Color(0xFF0A1628)
                                )
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // Ride rows
                        if (pubCount == 0) {
                            EmptyState("No public rides found near $zipCode within $radius miles")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (i in 0 until pubCount) {
                                    val inviteRequired = ConvoyDevSeeder.getPublicRideInviteRequired(context, i)
                                    if (showInviteOnly && !inviteRequired) continue
                                    val name      = ConvoyDevSeeder.getPublicRideField(context, i, "name")
                                    val organizer = ConvoyDevSeeder.getPublicRideField(context, i, "organizer")
                                    val email     = ConvoyDevSeeder.getPublicRideField(context, i, "email")
                                    val date      = ConvoyDevSeeder.getPublicRideField(context, i, "date")
                                    val time      = ConvoyDevSeeder.getPublicRideField(context, i, "time")
                                    val distance  = ConvoyDevSeeder.getPublicRideField(context, i, "distance")
                                    var followPub by androidx.compose.runtime.mutableStateOf(false)
                                    Column(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0F2035))
                                            .clickable { onNavigateToExplore() }
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(text = name, color = Color.White, fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                            Text(text = distance, color = Color(0xFF4AB8E8), fontSize = 11.sp)
                                        }
                                        Text(text = "$date  ·  $time",
                                            color = Color(0xFF445566), fontSize = 11.sp)
                                        Text(text = "Organizer: $organizer",
                                            color = Color(0xFF445566), fontSize = 11.sp)
                                        Text(text = email, color = Color(0xFF4AB8E8), fontSize = 11.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Box(modifier = Modifier.size(14.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(if (followPub) Color(0xFF4AB8E8) else Color(0xFF1A3050))
                                                .clickable { followPub = !followPub })
                                            Text(
                                                text = if (followPub) "Following $organizer" else "Follow $organizer",
                                                color = if (followPub) Color(0xFF4AB8E8) else Color(0xFF445566),
                                                fontSize = 10.sp
                                            )
                                        }
                                        if (inviteRequired) {
                                            Text(text = "INVITE REQUIRED", color = Color(0xFFF59E0B),
                                                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }

        GroupTrackBottomNav(
            activeTab = GroupTrackTab.HOME,
            onHome = {},
            onRides = onNavigateToRides,
            onMap = onBack,
            onProfile = onNavigateToProfile,
            onRadio = onNavigateToFieldRadio
        )
    }
}

@Composable
private fun AccordionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A3050)).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = if (expanded) "\u25BC" else "\u25B6",
                color = Color(0xFF4AB8E8), fontSize = 10.sp)
            Text(text = title, color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        if (count > 0) {
            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF4AB8E8))
                .padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(text = count.toString(), color = Color(0xFF0A1628),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DashSectionLabel(label: String) {
    Text(text = label, color = Color(0xFF4AB8E8), fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun EmptyState(msg: String) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF0A1628)).padding(16.dp),
        contentAlignment = Alignment.Center) {
        Text(text = msg, color = Color(0xFF445566), fontSize = 12.sp,
            textAlign = TextAlign.Center)
    }
}

private fun dashStatusColor(status: String) = when (status) {
    "ORGANIZED" -> Color(0xFF4AB8E8)
    "ENROLLED"  -> Color(0xFF22C55E)
    "ACCEPTED"  -> Color(0xFF22C55E)
    "MAYBE"     -> Color(0xFFF59E0B)
    "DECLINED"  -> Color(0xFFEF4444)
    "INVITED"   -> Color(0xFFF59E0B)
    "COMPLETED" -> Color(0xFF445566)
    else        -> Color(0xFF445566)
}

@Composable
private fun dashFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF4AB8E8), unfocusedBorderColor = Color(0xFF1A3050),
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedLabelColor = Color(0xFF4AB8E8), unfocusedLabelColor = Color(0xFF445566),
    cursorColor = Color(0xFF4AB8E8)
)

@Composable
private fun RoleBadge(label: String, color: Color) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f))
        .padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text = label, color = color, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun StatusButton(label: String, color: Color, onClick: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f)).clickable { onClick() }
        .padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(text = label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
