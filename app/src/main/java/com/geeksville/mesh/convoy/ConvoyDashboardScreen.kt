package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    onBack: () -> Unit,
    onNavigateToCreateRide: (String?) -> Unit = {},
    onDownloadRideConfig: () -> Unit = {},
    onApplyMasterConfig: () -> Unit = {},
    onArchiveRestore: () -> Unit = {},
    onNavigateToCompletedRides: (String) -> Unit = {},
    onNavigateToSearchByArea: () -> Unit = {}
) {
    val context = LocalContext.current
    var seedKey by remember { mutableStateOf(0) }

    val firstName     = remember(seedKey) { ConvoySessionManager.getFirstName(context) }
    val isOrganizer   = remember(seedKey) { ConvoySessionManager.isOrganizer(context) }
    var activeRole    by remember { mutableStateOf(if (isOrganizer) "ORGANIZER" else "RIDER") }
    val currentUserId = remember { ConvoySessionManager.getUserId(context) ?: "" }
    var zipCode       by remember { mutableStateOf(ConvoySessionManager.getZipCode(context)) }
    var radius        by remember { mutableStateOf(ConvoySessionManager.getSearchRadius(context).toString()) }

    val notifCount  = remember(seedKey) { ConvoyDevSeeder.getNotifCount(context) }
    val rideCount   = remember(seedKey) { ConvoyDevSeeder.getRideCount(context) }
    val inviteCount = remember(seedKey) { ConvoyDevSeeder.getInviteCount(context) }
    val pubCount    = remember(seedKey) { ConvoyDevSeeder.getPublicRideCount(context) }

    var notifExpanded  by remember { mutableStateOf(true) }
    var inviteExpanded by remember { mutableStateOf(true) }
    var ridesExpanded  by remember { mutableStateOf(false) }
    var nearMeExpanded by remember { mutableStateOf(false) }

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

    var showDevMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        // ── Header ────────────────────────────────────────────────────────────
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
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 3.sp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "PROFILE \u2192",
                                color = Gt.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Gt.panel)
                                    .clickable { onNavigateToProfile() }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(thickness = 3.dp, color = GroupTrackColors.SkyBlue)
        }

        // ── Role / sub bar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().background(Gt.panel)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // RIDER tab — always visible
                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (activeRole == "RIDER") Gt.good.copy(alpha = 0.22f) else Gt.good.copy(alpha = 0.2f))
                    .clickable { activeRole = "RIDER" }
                    .padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("RIDER", color = if (activeRole == "RIDER") Gt.text else Gt.good,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                // ORGANIZER tab — only visible to organizers
                if (isOrganizer) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (activeRole == "ORGANIZER") Gt.accent.copy(alpha = 0.22f) else Gt.accent.copy(alpha = 0.2f))
                        .clickable { activeRole = "ORGANIZER" }
                        .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("ORGANIZER", color = if (activeRole == "ORGANIZER") Gt.text else Gt.accent,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
            // ADD A RIDE button — always visible
            Text(
                text = "+ ADD RIDE",
                color = Gt.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Gt.accent.copy(alpha = 0.22f))
                    .clickable { onNavigateToCreateRide(null) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ConvoyConfig.V3_FEATURES_ENABLED) {
                    Text(
                        text = "DEV",
                        color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Gt.panel)
                            .clickable { showDevMenu = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isSubscribed) {
                    Text(
                        text = "SUBSCRIBE $3/mo",
                        color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onShowSubscription() }
                    )
                }
                Text(
                    text = "PROFILE \u2192",
                    color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onNavigateToProfile() }
                )
            }
        }

        // ── Dev menu ──────────────────────────────────────────────────────────
        if (showDevMenu) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showDevMenu = false }) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Gt.panel).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("DEV MENU", color = Gt.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    listOf(
                        "SEED DATA"     to { ConvoyDevSeeder.seed(context); seedKey++; android.widget.Toast.makeText(context, "Seeded", android.widget.Toast.LENGTH_SHORT).show() },
                        "CLEAR SESSION" to { ConvoySessionManager.clearSession(context); ConvoyDevSeeder.clear(context); seedKey++; android.widget.Toast.makeText(context, "Cleared", android.widget.Toast.LENGTH_SHORT).show() },
                        "CLEAR + SEED"  to { ConvoyDevSeeder.clear(context); ConvoyDevSeeder.seed(context); seedKey++; android.widget.Toast.makeText(context, "Reset", android.widget.Toast.LENGTH_SHORT).show() },
                        "SHOW SESSION"  to { val uid = ConvoySessionManager.getUserId(context) ?: "none"; android.widget.Toast.makeText(context, "ID:$uid sub:${ConvoySessionManager.isSubscribed(context)}", android.widget.Toast.LENGTH_LONG).show() },
                        "CLOSE"         to {}
                    ).forEach { (label, action) ->
                        Box(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(Gt.panel).clickable { action(); showDevMenu = false }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

            // ── ORGANIZER PENDING RIDES ───────────────────────────────────────
            if (isOrganizer && activeRole == "ORGANIZER") {
                item {
                    ConvoyOrganizerPendingRides(
                        onNavigateToCreateRide = onNavigateToCreateRide
                    )
                }
            }

            // ── SECTION 1: ORGANIZER NOTIFICATIONS ───────────────────────────
            if (isOrganizer && activeRole == "ORGANIZER" && notifCount > 0) {
                item {
                    AccordionHeader(
                        title = "ORGANIZER OPEN RIDES", count = notifCount,
                        expanded = notifExpanded,
                        onClick = { notifExpanded = !notifExpanded }
                    )
                }
            }
            if (isOrganizer && activeRole == "ORGANIZER" && notifExpanded && notifCount > 0) {
                items(notifCount) { i ->
                    val rideName    = ConvoyDevSeeder.getNotif(context, i, "ride")
                    val visibility  = ConvoyDevSeeder.getNotif(context, i, "visibility")
                    val rideStatus  = ConvoyDevSeeder.getNotif(context, i, "ride_status")
                    val date        = ConvoyDevSeeder.getNotif(context, i, "date")
                    val location    = ConvoyDevSeeder.getNotif(context, i, "location")
                    val trailhead   = ConvoyDevSeeder.getNotif(context, i, "trailhead")
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
                            .background(Gt.panel)
                            .clickable { onNavigateToRides() }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(text = rideName, color = Gt.text, fontSize = 13.sp,
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
                                    if (trailhead.isNotEmpty()) append(" \u00b7 $trailhead")
                                },
                                color = Gt.text, fontSize = 12.sp
                            )
                        }
                        if (date.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    append(date)
                                    if (arriveTime.isNotEmpty()) append(" \u00b7 Arrive $arriveTime")
                                    if (departTime.isNotEmpty()) append(" \u00b7 Depart $departTime")
                                },
                                color = Gt.text, fontSize = 12.sp
                            )
                        }
                        if (description.isNotEmpty()) {
                            Text(text = description, color = Gt.accent, fontSize = 12.sp,
                                lineHeight = 16.sp, maxLines = 3)
                        }
                        val totalReplied = accepted + maybe + declined
                        var repliesOpen by remember { mutableStateOf(false) }
                        if (invited > 0 || totalReplied > 0) {
                            NotifAccordion(
                                "REPLIES \u2014 $totalReplied of $invited responded",
                                Gt.accent, repliesOpen
                            ) { repliesOpen = !repliesOpen }
                            if (repliesOpen) {
                                Column(
                                    modifier = Modifier.padding(start = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (accepted > 0) NotifAccordion("ACCEPTED ($accepted)", Gt.good, acceptedOpen) { acceptedOpen = !acceptedOpen }
                                    if (maybe > 0)    NotifAccordion("MAYBE ($maybe)", Gt.warn, maybeOpen) { maybeOpen = !maybeOpen }
                                    if (declined > 0) NotifAccordion("DECLINED ($declined)", Gt.bad, declinedOpen) { declinedOpen = !declinedOpen }
                                    if (invited > 0)  NotifAccordion("INVITED ($invited)", Gt.text, invitedOpen) { invitedOpen = !invitedOpen }
                                }
                            }
                        }
                    }
                }
            }

            // ── SECTION 2: INVITES PENDING REPLIES ───────────────────────────
            if (activeRole == "RIDER") {
            if (inviteCount > 0) {
                item {
                    AccordionHeader(
                        title = "INVITES PENDING REPLIES", count = inviteCount, showCountInline = true,
                        expanded = inviteExpanded,
                        onClick = { inviteExpanded = !inviteExpanded }
                    )
                }
            }
            if (inviteExpanded && inviteCount > 0) {
                items(inviteCount) { i ->
                    val rideName   = ConvoyDevSeeder.getInviteField(context, i, "ride")
                    val orgName    = ConvoyDevSeeder.getInviteField(context, i, "organizer")
                    val orgId      = ConvoyDevSeeder.getInviteField(context, i, "organizer_id")
                    val email      = ConvoyDevSeeder.getInviteField(context, i, "email")
                    val date       = ConvoyDevSeeder.getInviteField(context, i, "date")
                    val time       = ConvoyDevSeeder.getInviteField(context, i, "time")
                    val visibility = ConvoyDevSeeder.getInviteField(context, i, "visibility")
                    val rideStatus = ConvoyDevSeeder.getInviteField(context, i, "ride_status")
                    val location   = ConvoyDevSeeder.getInviteField(context, i, "location")
                    val trailhead  = ConvoyDevSeeder.getInviteField(context, i, "trailhead")
                    var myStatus   by remember { mutableStateOf(ConvoyDevSeeder.getInviteField(context, i, "my_status").ifEmpty { "invited" }) }
                    val isFollowing = orgId in followedOrganizers

                    RideDisplayPanel(
                        rideName      = rideName,
                        visibility    = visibility,
                        rideStatus    = rideStatus,
                        city          = location,
                        trailhead     = trailhead,
                        dateTime      = "$date \u00b7 $time",
                        orgName       = orgName,
                        orgId         = orgId,
                        orgEmail      = email,
                        currentUserId = currentUserId,
                        isFollowing   = isFollowing,
                        myStatus      = myStatus,
                        onAccept      = { myStatus = "accepted" },
                        onMaybe       = { myStatus = "maybe" },
                        onDecline     = { myStatus = "declined" },
                        onDetail      = { onNavigateToRides() },
                        onToggleFollow = { toggleFollow(orgId, orgName) }
                    )
                }
            }

            // ── SECTION 3: MY INVITE RESPONSES ───────────────────────────────
            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    AccordionHeader(
                        title = "MY INVITE RESPONSES", count = rideCount, showCountInline = true,
                        expanded = ridesExpanded,
                        onClick = { ridesExpanded = !ridesExpanded },
                        modifier = Modifier.weight(1f)
                    )
                    if (ridesExpanded) {
                        Text("SEE ALL", color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            modifier = Modifier.clickable { onNavigateToRides() }.padding(8.dp))
                    }
                }
            }
            if (ridesExpanded && rideCount > 0) {
                items(minOf(rideCount, 3)) { i ->
                    val rideName   = ConvoyDevSeeder.getRideName(context, i)
                    val myStatus   = ConvoyDevSeeder.getRideStatus(context, i)
                    val date       = ConvoyDevSeeder.getRideDate(context, i)
                    val time       = ConvoyDevSeeder.getRideField(context, i, "time")
                    val orgName    = ConvoyDevSeeder.getRideOrganizer(context, i)
                    val orgId      = ConvoyDevSeeder.getRideField(context, i, "organizer_id")
                    val email      = ConvoyDevSeeder.getRideEmail(context, i)
                    val rideStatus = ConvoyDevSeeder.getRideField(context, i, "ride_status")
                    val visibility = ConvoyDevSeeder.getRideField(context, i, "visibility")
                    val location   = ConvoyDevSeeder.getRideField(context, i, "location")
                    val trailhead  = ConvoyDevSeeder.getRideField(context, i, "trailhead")
                    val isFollowing = orgId in followedOrganizers
                    var currentStatus by remember { mutableStateOf(myStatus) }

                    RideDisplayPanel(
                        rideName      = rideName,
                        visibility    = visibility,
                        rideStatus    = rideStatus,
                        city          = location,
                        trailhead     = trailhead,
                        dateTime      = "$date \u00b7 $time",
                        orgName       = orgName,
                        orgId         = orgId,
                        orgEmail      = email,
                        currentUserId = currentUserId,
                        isFollowing   = isFollowing,
                        myStatus      = currentStatus,
                        onAccept      = { currentStatus = "accepted" },
                        onMaybe       = { currentStatus = "maybe" },
                        onDecline     = { currentStatus = "declined" },
                        onDetail      = { onNavigateToRides() },
                        onToggleFollow = { toggleFollow(orgId, orgName) }
                    )
                }
            }

            // ── SECTION 4: PUBLIC RIDES NEAR ME ──────────────────────────────
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(Gt.panel)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable { nearMeExpanded = !nearMeExpanded }) {
                            Text(if (nearMeExpanded) "\u25bc" else "\u25b6", color = Gt.accent, fontSize = 12.sp)
                            Text("PUBLIC RIDES NEAR ME", color = Gt.text, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        OutlinedTextField(
                            value = zipCode,
                            onValueChange = { zipCode = it; ConvoySessionManager.setZipCode(context, it) },
                            modifier = Modifier.width(90.dp).height(48.dp),
                            singleLine = true,
                            label = { Text("ZIP", fontSize = 12.sp, color = Gt.text) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Gt.accent, unfocusedBorderColor = Gt.line,
                                focusedTextColor = Gt.text, unfocusedTextColor = Gt.text, cursorColor = Gt.accent
                            )
                        )
                        Text("\u00b7", color = Gt.text, fontSize = 12.sp)
                        OutlinedTextField(
                            value = radius,
                            onValueChange = { v -> radius = v; v.toIntOrNull()?.let { r -> ConvoySessionManager.setSearchRadius(context, r) } },
                            modifier = Modifier.width(64.dp).height(48.dp),
                            singleLine = true,
                            label = { Text("MI", fontSize = 12.sp, color = Gt.text) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Gt.accent, unfocusedBorderColor = Gt.line,
                                focusedTextColor = Gt.text, unfocusedTextColor = Gt.text, cursorColor = Gt.accent
                            )
                        )
                        Text("mi", color = Gt.text, fontSize = 12.sp)
                        if (pubCount > 0) {
                            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(Gt.accent.copy(alpha = 0.22f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(pubCount.toString(), color = Gt.text,
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (nearMeExpanded) {
                        Spacer(Modifier.height(8.dp))
                        if (pubCount == 0) {
                            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(Gt.panel).padding(16.dp),
                                contentAlignment = Alignment.Center) {
                                Text("No public rides found near $zipCode within $radius miles",
                                    color = Gt.text, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (i in 0 until pubCount) {
                                    val rideName   = ConvoyDevSeeder.getPublicRideField(context, i, "name")
                                    val orgName    = ConvoyDevSeeder.getPublicRideField(context, i, "organizer")
                                    val orgId      = ConvoyDevSeeder.getPublicRideField(context, i, "organizer_id")
                                    val email      = ConvoyDevSeeder.getPublicRideField(context, i, "email")
                                    val date       = ConvoyDevSeeder.getPublicRideField(context, i, "date")
                                    val time       = ConvoyDevSeeder.getPublicRideField(context, i, "time")
                                    val rideStatus = ConvoyDevSeeder.getPublicRideField(context, i, "ride_status")
                                    val location   = ConvoyDevSeeder.getPublicRideField(context, i, "location")
                                    val trailhead  = ConvoyDevSeeder.getPublicRideField(context, i, "trailhead")
                                    val isFollowing = orgId in followedOrganizers

                                    RideDisplayPanel(
                                        rideName      = rideName,
                                        visibility    = "PUBLIC",
                                        rideStatus    = rideStatus,
                                        city          = location,
                                        trailhead     = trailhead,
                                        dateTime      = "$date \u00b7 $time",
                                        orgName       = orgName,
                                        orgId         = orgId,
                                        orgEmail      = email,
                                        currentUserId = currentUserId,
                                        isFollowing   = isFollowing,
                                        myStatus      = "",
                                        onAccept      = {},
                                        onMaybe       = {},
                                        onDecline     = {},
                                        onDetail      = { onNavigateToExplore() },
                                        onToggleFollow = { toggleFollow(orgId, orgName) },
                                        showDecline = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

            } // end activeRole == RIDER
        // ── Bottom nav ────────────────────────────────────────────────────────
        GroupTrackBottomNav(
            activeTab = GroupTrackTab.HOME,
            onHome = {},
            onRides = onNavigateToRides,
            onMap = onBack,
            onProfile = onNavigateToProfile,
            onMyProfile = onNavigateToProfile,
            onRadio = onNavigateToFieldRadio,
            onApplyMasterConfig = onApplyMasterConfig,
            onArchiveRestore = onArchiveRestore,
            onNavigateToCompletedRides = onNavigateToCompletedRides,
            onNavigateToSearchByArea = onNavigateToSearchByArea,
            onDownloadRideConfig = onDownloadRideConfig
        )
    }
}

// ── Common Ride Display Panel ─────────────────────────────────────────────────
// Line 1: Ride Name                              [VISIBILITY]  [STATUS]
// Line 2: City, State -- Trailhead    FOLLOW [x]  Organizer Name
// Line 3: Date · Start time
// Line 4: organizer@email.com
// Line 5: [ ACCEPT ]  [ MAYBE ]  [ DECLINE ]  [ SHOW RIDE DETAILS ]
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RideDisplayPanel(
    rideName: String,
    visibility: String,
    rideStatus: String,
    city: String,
    trailhead: String,
    dateTime: String,
    orgName: String,
    orgId: String,
    orgEmail: String,
    currentUserId: String,
    isFollowing: Boolean,
    myStatus: String,
    onAccept: () -> Unit,
    onMaybe: () -> Unit,
    onDecline: () -> Unit,
    onDetail: () -> Unit,
    onToggleFollow: () -> Unit,
    showDecline: Boolean = true
) {
    val canRespond = rideStatus.lowercase() == "open"
    val showFollow = orgId.isNotEmpty() && orgId != currentUserId

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Gt.panel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Line 1: Ride name + badges ────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = rideName, color = Gt.text, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                VisibilityBadge(visibility)
                RideStatusBadge(rideStatus)
            }
        }

        // ── Line 2: City/Trailhead + FOLLOW label + checkbox + Organizer name ─
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    if (city.isNotEmpty()) append(city)
                    if (trailhead.isNotEmpty()) append(" \u2014 $trailhead")
                },
                color = Gt.text, fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (showFollow) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // FOLLOW label
                    Text(
                        text = "FOLLOW",
                        color = Gt.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    // Checkbox
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (isFollowing) Gt.accent.copy(alpha = 0.18f)
                                else Color.Transparent
                            )
                            .then(
                                Modifier.clickable { onToggleFollow() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Border drawn via nested box when unchecked
                        if (!isFollowing) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.Transparent)
                                    .padding(1.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Gt.panel)
                                )
                            }
                            // Border
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.Transparent)
                                    .padding(0.dp)
                            )
                        }
                        if (isFollowing) {
                            Text(
                                text = "\u2713",
                                color = Gt.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Organizer name
                    Text(
                        text = orgName,
                        color = Gt.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Line 3: Date / time ───────────────────────────────────────────────
        if (dateTime.isNotBlank()) {
            Text(
                text = dateTime,
                color = Gt.accent, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // ── Line 4: Email ─────────────────────────────────────────────────────
        if (orgEmail.isNotEmpty()) {
            Text(
                text = orgEmail,
                color = Gt.text, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // ── Line 5: Action buttons ────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            RideActionButton(label = "ACCEPT",  active = myStatus.lowercase() == "accepted", enabled = canRespond, activeColor = Gt.good, onClick = onAccept)
            RideActionButton(label = "MAYBE",   active = myStatus.lowercase() == "maybe",    enabled = canRespond, activeColor = Gt.warn, onClick = onMaybe)
            if (showDecline) {
                RideActionButton(label = "DECLINE", active = myStatus.lowercase() == "declined", enabled = canRespond, activeColor = Gt.bad, onClick = onDecline)
            }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(Gt.panel)
                    .clickable { onDetail() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text("SHOW RIDE DETAILS", color = Gt.accent,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RideActionButton(
    label: String, active: Boolean, enabled: Boolean,
    activeColor: Color, onClick: () -> Unit
) {
    val borderColor = when {
        !enabled -> Gt.dim
        active   -> Gt.good
        else     -> Gt.bad
    }
    val textColor = when {
        !enabled -> Gt.dim
        active   -> Gt.good
        else     -> Gt.bad
    }
    Box(
        modifier = Modifier
            .border(2.dp, borderColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(Gt.panel)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(label, color = textColor, fontSize = 12.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
            letterSpacing = 1.sp)
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun VisibilityBadge(visibility: String) {
    val color = if (visibility.uppercase() == "PUBLIC") Gt.accent else Gt.warn
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f))
        .padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(visibility.ifEmpty { "PRIVATE" }.uppercase(), color = color,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun RideStatusBadge(status: String) {
    val (color, label) = when (status.lowercase()) {
        "open"      -> Gt.good to "OPEN"
        "closed"    -> Gt.text to "CLOSED"
        "cancelled" -> Gt.bad to "CANCELLED"
        "pending"   -> Gt.text to "PENDING"
        "completed" -> Gt.text to "COMPLETED"
        else        -> Gt.good to "OPEN"
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.15f))
        .padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, color = color, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun NotifAccordion(label: String, color: Color, expanded: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
        .background(color.copy(alpha = 0.08f)).clickable { onClick() }
        .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (expanded) "\u25bc $label" else "\u25b6 $label",
            color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AccordionHeader(
    title: String, count: Int, expanded: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier,
    showCountInline: Boolean = false
) {
    Row(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(Gt.panel).clickable { onClick() }
        .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)) {
            Text(if (expanded) "\u25bc" else "\u25b6", color = Gt.accent, fontSize = 12.sp)
            Text(title, color = Gt.text, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (showCountInline && count > 0) {
                Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(Gt.accent.copy(alpha = 0.22f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(count.toString(), color = Gt.text,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!showCountInline && count > 0) {
            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .background(Gt.accent.copy(alpha = 0.22f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(count.toString(), color = Gt.text,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// DEADCODE2-2026-10-23: DashBadge (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyDashboardScreen.pieces.txt

// DEADCODE2-2026-10-23: dashStatusColor (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyDashboardScreen.pieces.txt
