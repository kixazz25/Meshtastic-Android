package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    val currentUserId = remember { ConvoySessionManager.getUserId(context) ?: "" }
    var zipCode     by remember { mutableStateOf(ConvoySessionManager.getZipCode(context)) }
    var radius      by remember { mutableStateOf(ConvoySessionManager.getSearchRadius(context).toString()) }

    val notifCount  = remember(seedKey) { ConvoyDevSeeder.getNotifCount(context) }
    val rideCount   = remember(seedKey) { ConvoyDevSeeder.getRideCount(context) }
    val inviteCount = remember(seedKey) { ConvoyDevSeeder.getInviteCount(context) }
    val pubCount    = remember(seedKey) { ConvoyDevSeeder.getPublicRideCount(context) }

    // Accordion state
    var notifExpanded  by remember { mutableStateOf(true) }   // expanded by default — urgent
    var inviteExpanded by remember { mutableStateOf(true) }   // expanded by default — urgent
    var ridesExpanded  by remember { mutableStateOf(false) }
    var nearMeExpanded by remember { mutableStateOf(false) }

    // Global follow state — single source of truth for all screens
    var followedOrganizers by remember {
        mutableStateOf(
            (context.getSharedPreferences("grouptrack_follows", android.content.Context.MODE_PRIVATE)
                .getStringSet("followed_ids", emptySet()) ?: emptySet()).toMutableSet()
        )
    }
    fun toggleFollow(organizerId: String, organizerName: String) {
        if (organizerId.isEmpty() || organizerId == currentUserId) return
        val prefs = context.getSharedPreferences("grouptrack_follows", android.content.Context.MODE_PRIVATE)
        followedOrganizers = if (organizerId in followedOrganizers) {
            ConvoyEnrollmentQueue.unfollowOrganizer(context, organizerId, organizerName)
            (followedOrganizers - organizerId).toMutableSet()
        } else {
            ConvoyEnrollmentQueue.followOrganizer(context, organizerId, organizerName)
            (followedOrganizers + organizerId).toMutableSet()
        }
        prefs.edit().putStringSet("followed_ids", followedOrganizers).apply()
    }

    // Dev menu state
    var showDevMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        // ── Header — logo + welcome name + profile button on same line ─────────
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(GroupTrackColors.Navy)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            id = run {
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                ctx.resources.getIdentifier("grouptrack_logo", "drawable", ctx.packageName)
                                    .takeIf { it != 0 } ?: android.R.drawable.ic_menu_gallery
                            }
                        ),
                        contentDescription = "GroupTrack",
                        modifier = Modifier.height(40.dp)
                    )
                    if (firstName.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "WELCOME, ${firstName.uppercase()}",
                                color = GroupTrackColors.SkyBlue,
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 3.sp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "PROFILE →",
                                color = Color(0xFF4AB8E8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF1A3050))
                                    .clickable { onNavigateToProfile() }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(thickness = 3.dp, color = GroupTrackColors.SkyBlue)
        }
        // Role badges + subscribe + DEV row
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DashBadge("RIDER", Color(0xFF22C55E))
                if (isOrganizer) DashBadge("ORGANIZER", Color(0xFF4AB8E8))
                if (ConvoyConfig.V3_FEATURES_ENABLED) {
                    Text(
                        text = "DEV",
                        color = Color(0xFF4AB8E8), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1A3050))
                            .clickable { showDevMenu = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isSubscribed) {
                    Text(
                        text = "SUBSCRIBE $3/mo",
                        color = Color(0xFFFFCC44), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onShowSubscription() }
                    )
                }
                Text(
                    text = "PROFILE →",
                    color = Color(0xFF4AB8E8), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onNavigateToProfile() }
                )
            }
        }

        // Dev menu dialog
        if (showDevMenu) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showDevMenu = false }) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("DEV MENU", color = Color(0xFF4AB8E8), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    listOf(
                        "SEED DATA" to { ConvoyDevSeeder.seed(context); seedKey++; android.widget.Toast.makeText(context, "Seeded", android.widget.Toast.LENGTH_SHORT).show() },
                        "CLEAR SESSION" to { ConvoySessionManager.clearSession(context); ConvoyDevSeeder.clear(context); seedKey++; android.widget.Toast.makeText(context, "Cleared", android.widget.Toast.LENGTH_SHORT).show() },
                        "CLEAR + SEED" to { ConvoyDevSeeder.clear(context); ConvoyDevSeeder.seed(context); seedKey++; android.widget.Toast.makeText(context, "Reset", android.widget.Toast.LENGTH_SHORT).show() },
                        "SHOW SESSION" to { val uid = ConvoySessionManager.getUserId(context) ?: "none"; android.widget.Toast.makeText(context, "ID:$uid sub:${ConvoySessionManager.isSubscribed(context)}", android.widget.Toast.LENGTH_LONG).show() },
                        "CLOSE" to {}
                    ).forEach { (label, action) ->
                        Box(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1A3050)).clickable { action(); showDevMenu = false }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = Color(0xFF4AB8E8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ── Main content ──────────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── NOTIFICATIONS — organizer only, collapsible ──────────────────
            if (isOrganizer && notifCount > 0) {
                item {
                    AccordionHeader(
                        title = "NOTIFICATIONS", count = notifCount,
                        expanded = notifExpanded,
                        onClick = { notifExpanded = !notifExpanded }
                    )
                }
            }
            if (isOrganizer && notifExpanded && notifCount > 0) {
                items(notifCount) { i ->
                    val rideName   = ConvoyDevSeeder.getNotif(context, i, "ride")
                    val visibility = ConvoyDevSeeder.getNotif(context, i, "visibility")
                    val rideStatus = ConvoyDevSeeder.getNotif(context, i, "ride_status")
                    val date       = ConvoyDevSeeder.getNotif(context, i, "date")
                    val location   = ConvoyDevSeeder.getNotif(context, i, "location")
                    val trailhead  = ConvoyDevSeeder.getNotif(context, i, "trailhead")
                    val accepted    = ConvoyDevSeeder.getNotifInt(context, i, "accepted")
                    val maybe       = ConvoyDevSeeder.getNotifInt(context, i, "maybe")
                    val declined    = ConvoyDevSeeder.getNotifInt(context, i, "declined")
                    val invited     = ConvoyDevSeeder.getNotifInt(context, i, "invited")
                    val arriveTime  = ConvoyDevSeeder.getNotif(context, i, "arrive_time")
                    val departTime  = ConvoyDevSeeder.getNotif(context, i, "depart_time")
                    val description = ConvoyDevSeeder.getNotif(context, i, "description")
                    var acceptedOpen by remember { mutableStateOf(false) }
                    var maybeOpen    by remember { mutableStateOf(false) }
                    var declinedOpen by remember { mutableStateOf(false) }
                    var invitedOpen  by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F2035))
                            .clickable { onNavigateToRides() }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Title + badges
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(text = rideName, color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                VisibilityBadge(visibility)
                                RideStatusBadge(rideStatus)
                            }
                        }
                        // Location
                        if (location.isNotEmpty() || trailhead.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    if (location.isNotEmpty()) append(location)
                                    if (trailhead.isNotEmpty()) append(" · $trailhead")
                                },
                                color = Color(0xFF445566), fontSize = 11.sp
                            )
                        }
                        // Date + times
                        if (date.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    append(date)
                                    if (arriveTime.isNotEmpty()) append(" · Arrive $arriveTime")
                                    if (departTime.isNotEmpty()) append(" · Depart $departTime")
                                },
                                color = Color(0xFF445566), fontSize = 11.sp
                            )
                        }
                        // Description/organizer comments
                        if (description.isNotEmpty()) {
                            Text(text = description, color = Color(0xFFAABBCC), fontSize = 11.sp,
                                lineHeight = 16.sp, maxLines = 3)
                        }
                        // REPLIES — nested twistie, collapsed by default
                        val totalReplied = accepted + maybe + declined
                        var repliesOpen by remember { mutableStateOf(false) }
                        if (invited > 0 || totalReplied > 0) {
                            NotifAccordion(
                                "REPLIES — $totalReplied of $invited responded",
                                Color(0xFF4AB8E8), repliesOpen
                            ) { repliesOpen = !repliesOpen }
                            if (repliesOpen) {
                                Column(
                                    modifier = Modifier.padding(start = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (accepted > 0) NotifAccordion("ACCEPTED ($accepted)", Color(0xFF22C55E), acceptedOpen) { acceptedOpen = !acceptedOpen }
                                    if (maybe > 0)    NotifAccordion("MAYBE ($maybe)", Color(0xFFF59E0B), maybeOpen) { maybeOpen = !maybeOpen }
                                    if (declined > 0) NotifAccordion("DECLINED ($declined)", Color(0xFFEF4444), declinedOpen) { declinedOpen = !declinedOpen }
                                    if (invited > 0)  NotifAccordion("INVITED ($invited)", Color(0xFF445566), invitedOpen) { invitedOpen = !invitedOpen }
                                }
                            }
                        }
                    }
                }
            }

            // ── PENDING INVITES — collapsible ────────────────────────────────
            if (inviteCount > 0) {
                item {
                    AccordionHeader(
                        title = "PENDING INVITES", count = inviteCount,
                        expanded = inviteExpanded,
                        onClick = { inviteExpanded = !inviteExpanded }
                    )
                }
            }
            if (inviteExpanded && inviteCount > 0) {
                items(inviteCount) { i ->
                    val rideName   = ConvoyDevSeeder.getInviteField(context, i, "ride")
                    val organizer  = ConvoyDevSeeder.getInviteField(context, i, "organizer")
                    val orgId      = ConvoyDevSeeder.getInviteField(context, i, "organizer_id")
                    val email      = ConvoyDevSeeder.getInviteField(context, i, "email")
                    val date       = ConvoyDevSeeder.getInviteField(context, i, "date")
                    val time       = ConvoyDevSeeder.getInviteField(context, i, "time")
                    val visibility = ConvoyDevSeeder.getInviteField(context, i, "visibility")
                    val rideStatus = ConvoyDevSeeder.getInviteField(context, i, "ride_status")
                    val location   = ConvoyDevSeeder.getInviteField(context, i, "location")
                    val trailhead  = ConvoyDevSeeder.getInviteField(context, i, "trailhead")
                    val description = ConvoyDevSeeder.getInviteField(context, i, "description")
                    var status     by remember { mutableStateOf(ConvoyDevSeeder.getInviteField(context, i, "my_status").ifEmpty { "INVITED" }) }
                    val isFollowing = orgId in followedOrganizers

                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F2035)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Title row
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(text = rideName, color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                VisibilityBadge(visibility)
                                RideStatusBadge(rideStatus)
                            }
                        }
                        // Location
                        if (location.isNotEmpty() || trailhead.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    if (location.isNotEmpty()) append(location)
                                    if (trailhead.isNotEmpty()) append(" · $trailhead")
                                },
                                color = Color(0xFF445566), fontSize = 11.sp
                            )
                        }
                        Text(text = "$date · $time", color = Color(0xFF445566), fontSize = 11.sp)
                        Text(text = "Organizer: $organizer", color = Color(0xFF445566), fontSize = 11.sp)
                        Text(text = email, color = Color(0xFF4AB8E8), fontSize = 11.sp)
                        // Description
                        if (description.isNotEmpty()) {
                            Text(text = description, color = Color(0xFFAABBCC), fontSize = 11.sp,
                                lineHeight = 16.sp, maxLines = 2)
                        }
                        // Follow checkbox
                        if (orgId.isNotEmpty() && orgId != currentUserId) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                    .background(if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF1A3050))
                                    .clickable { toggleFollow(orgId, organizer) })
                                Text(
                                    text = if (isFollowing) "Following $organizer" else "Follow $organizer",
                                    color = if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF445566),
                                    fontSize = 10.sp
                                )
                            }
                        }
                        // My status
                        Text(text = "My status: $status", color = dashStatusColor(status),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        // Action buttons — only valid transitions
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (status != "ACCEPTED") StatusButton("ACCEPT", Color(0xFF22C55E)) { status = "ACCEPTED" }
                            if (status != "MAYBE")    StatusButton("MAYBE",  Color(0xFFF59E0B)) { status = "MAYBE" }
                            if (status != "DECLINED") StatusButton("DECLINE", Color(0xFFEF4444)) { status = "DECLINED" }
                            StatusButton("DETAIL", Color(0xFF4AB8E8)) { onNavigateToRides() }
                        }
                    }
                }
            }

            // ── MY RIDES accordion ────────────────────────────────────────────
            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    AccordionHeader(
                        title = "MY RIDES", count = rideCount,
                        expanded = ridesExpanded,
                        onClick = { ridesExpanded = !ridesExpanded },
                        modifier = Modifier.weight(1f)
                    )
                    if (ridesExpanded) {
                        Text("SEE ALL", color = Color(0xFF4AB8E8), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            modifier = Modifier.clickable { onNavigateToRides() }.padding(8.dp))
                    }
                }
            }

            if (ridesExpanded && rideCount > 0) {
                items(minOf(rideCount, 3)) { i ->
                    val name        = ConvoyDevSeeder.getRideName(context, i)
                    val myStatus    = ConvoyDevSeeder.getRideStatus(context, i)
                    val date        = ConvoyDevSeeder.getRideDate(context, i)
                    val time        = ConvoyDevSeeder.getRideField(context, i, "time")
                    val organizer   = ConvoyDevSeeder.getRideOrganizer(context, i)
                    val orgId       = ConvoyDevSeeder.getRideField(context, i, "organizer_id")
                    val email       = ConvoyDevSeeder.getRideEmail(context, i)
                    val enrolled    = ConvoyDevSeeder.getRideEnrolled(context, i)
                    val rideStatus  = ConvoyDevSeeder.getRideField(context, i, "ride_status")
                    val visibility  = ConvoyDevSeeder.getRideField(context, i, "visibility")
                    val location    = ConvoyDevSeeder.getRideField(context, i, "location")
                    val trailhead   = ConvoyDevSeeder.getRideField(context, i, "trailhead")
                    val description = ConvoyDevSeeder.getRideField(context, i, "description")
                    val isFollowing = orgId in followedOrganizers
                    val isMyRide    = orgId == currentUserId

                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F2035)).clickable { onNavigateToRides() }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(text = name, color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                VisibilityBadge(visibility)
                                RideStatusBadge(rideStatus)
                            }
                        }
                        if (location.isNotEmpty() || trailhead.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    if (location.isNotEmpty()) append(location)
                                    if (trailhead.isNotEmpty()) append(" · $trailhead")
                                },
                                color = Color(0xFF445566), fontSize = 11.sp
                            )
                        }
                        Text(text = "$date · $time", color = Color(0xFF445566), fontSize = 11.sp)
                        if (description.isNotEmpty()) {
                            Text(text = description, color = Color(0xFFAABBCC), fontSize = 11.sp,
                                lineHeight = 16.sp, maxLines = 2)
                        }
                        if (!isMyRide && orgId.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                    .background(if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF1A3050))
                                    .clickable { toggleFollow(orgId, organizer) })
                                Text(
                                    text = if (isFollowing) "Following $organizer" else "Follow $organizer",
                                    color = if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF445566),
                                    fontSize = 10.sp
                                )
                            }
                        }
                        if (isMyRide) {
                            Text(text = "$enrolled riders enrolled",
                                color = Color(0xFF4AB8E8), fontSize = 11.sp)
                        } else {
                            Text(text = "My status: $myStatus",
                                color = dashStatusColor(myStatus), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── RIDES NEAR ME accordion ───────────────────────────────────────
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1A3050))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable { nearMeExpanded = !nearMeExpanded }) {
                            Text(if (nearMeExpanded) "▼" else "▶", color = Color(0xFF4AB8E8), fontSize = 10.sp)
                            Text("RIDES NEAR", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedTextField(
                            value = zipCode,
                            onValueChange = { zipCode = it; ConvoySessionManager.setZipCode(context, it) },
                            modifier = Modifier.width(90.dp).height(48.dp),
                            singleLine = true,
                            label = { Text("ZIP", fontSize = 9.sp, color = Color(0xFF445566)) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4AB8E8),
                                unfocusedBorderColor = Color(0xFF2A4060),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF4AB8E8)
                            )
                        )
                        Text("·", color = Color(0xFF445566), fontSize = 12.sp)
                        OutlinedTextField(
                            value = radius,
                            onValueChange = { v -> radius = v
                                v.toIntOrNull()?.let { r -> ConvoySessionManager.setSearchRadius(context, r) } },
                            modifier = Modifier.width(64.dp).height(48.dp),
                            singleLine = true,
                            label = { Text("MI", fontSize = 9.sp, color = Color(0xFF445566)) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4AB8E8),
                                unfocusedBorderColor = Color(0xFF2A4060),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF4AB8E8)
                            )
                        )
                        Text("mi", color = Color(0xFF445566), fontSize = 10.sp)
                        if (pubCount > 0) {
                            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF4AB8E8))
                                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(pubCount.toString(), color = Color(0xFF0A1628),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (nearMeExpanded) {
                        Spacer(Modifier.height(8.dp))
                        if (pubCount == 0) {
                            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0A1628)).padding(16.dp),
                                contentAlignment = Alignment.Center) {
                                Text("No public rides found near $zipCode within $radius miles",
                                    color = Color(0xFF445566), fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (i in 0 until pubCount) {
                                    val name        = ConvoyDevSeeder.getPublicRideField(context, i, "name")
                                    val organizer   = ConvoyDevSeeder.getPublicRideField(context, i, "organizer")
                                    val orgId       = ConvoyDevSeeder.getPublicRideField(context, i, "organizer_id")
                                    val email       = ConvoyDevSeeder.getPublicRideField(context, i, "email")
                                    val date        = ConvoyDevSeeder.getPublicRideField(context, i, "date")
                                    val time        = ConvoyDevSeeder.getPublicRideField(context, i, "time")
                                    val distance    = ConvoyDevSeeder.getPublicRideField(context, i, "distance_miles")
                                    val rideStatus  = ConvoyDevSeeder.getPublicRideField(context, i, "ride_status")
                                    val location    = ConvoyDevSeeder.getPublicRideField(context, i, "location")
                                    val trailhead   = ConvoyDevSeeder.getPublicRideField(context, i, "trailhead")
                                    val description = ConvoyDevSeeder.getPublicRideField(context, i, "description")
                                    val isFollowing = orgId in followedOrganizers
                                    val isMyRide    = orgId == currentUserId

                                    Column(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0F2035))
                                            .clickable { onNavigateToExplore() }
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = name, color = Color.White, fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text("${distance}mi", color = Color(0xFF4AB8E8), fontSize = 11.sp)
                                                RideStatusBadge(rideStatus)
                                            }
                                        }
                                        if (location.isNotEmpty() || trailhead.isNotEmpty()) {
                                            Text(
                                                text = buildString {
                                                    if (location.isNotEmpty()) append(location)
                                                    if (trailhead.isNotEmpty()) append(" · $trailhead")
                                                },
                                                color = Color(0xFF445566), fontSize = 11.sp
                                            )
                                        }
                                        Text("$date · $time", color = Color(0xFF445566), fontSize = 11.sp)
                                        Text("Organizer: $organizer", color = Color(0xFF445566), fontSize = 11.sp)
                                        Text(email, color = Color(0xFF4AB8E8), fontSize = 11.sp)
                                        if (description.isNotEmpty()) {
                                            Text(description, color = Color(0xFFAABBCC), fontSize = 11.sp,
                                                lineHeight = 16.sp, maxLines = 2)
                                        }
                                        if (!isMyRide && orgId.isNotEmpty()) {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                                    .background(if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF1A3050))
                                                    .clickable { toggleFollow(orgId, organizer) })
                                                Text(
                                                    text = if (isFollowing) "Following $organizer" else "Follow $organizer",
                                                    color = if (isFollowing) Color(0xFF4AB8E8) else Color(0xFF445566),
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Bottom nav ────────────────────────────────────────────────────────
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

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun VisibilityBadge(visibility: String) {
    val color = if (visibility == "PUBLIC") Color(0xFF4AB8E8) else Color(0xFFF59E0B)
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f))
        .padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(visibility.ifEmpty { "PRIVATE" }, color = color,
            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun RideStatusBadge(status: String) {
    val (color, label) = when (status) {
        "OPEN"      -> Color(0xFF22C55E) to "OPEN"
        "CLOSED"    -> Color(0xFFF59E0B) to "CLOSED"
        "CANCELLED" -> Color(0xFFEF4444) to "CANCELLED"
        "PENDING"   -> Color(0xFF445566) to "PENDING"
        "COMPLETED" -> Color(0xFF445566) to "COMPLETED"
        else        -> Color(0xFF22C55E) to "OPEN"
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f))
        .padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun NotifAccordion(label: String, color: Color, expanded: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.08f)).clickable { onClick() }
        .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (expanded) "▼ $label" else "▶ $label",
            color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AccordionHeader(
    title: String, count: Int, expanded: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(Color(0xFF1A3050)).clickable { onClick() }
        .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (expanded) "▼" else "▶", color = Color(0xFF4AB8E8), fontSize = 10.sp)
            Text(title, color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        if (count > 0) {
            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF4AB8E8)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(count.toString(), color = Color(0xFF0A1628),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DashSectionLabel(label: String) {
    Text(label, color = Color(0xFF4AB8E8), fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun DashBadge(label: String, color: Color) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, color = color, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun StatusButton(label: String, color: Color, onClick: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f)).clickable { onClick() }
        .padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun dashStatusColor(status: String) = when (status) {
    "ORGANIZED" -> Color(0xFF4AB8E8)
    "ACCEPTED"  -> Color(0xFF22C55E)
    "ENROLLED"  -> Color(0xFF22C55E)
    "MAYBE"     -> Color(0xFFF59E0B)
    "DECLINED"  -> Color(0xFFEF4444)
    "INVITED"   -> Color(0xFFF59E0B)
    "COMPLETED" -> Color(0xFF445566)
    "PENDING"   -> Color(0xFF445566)
    else        -> Color(0xFF445566)
}
