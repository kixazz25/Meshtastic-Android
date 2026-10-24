package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// ConvoyProfileScreen.kt
// V3.0 Phase B — Profile screen (s05)
// Edit profile fields. Subscription + organizer status display.
// Phase C: GET/PUT /users/profile wired to API.
// ============================================================

@Composable
fun ConvoyProfileScreen(
    onBack: () -> Unit,
    onMyOrganizers: () -> Unit = {},
    onApplyMasterConfig: () -> Unit = {},
    onArchiveRestore: () -> Unit = {},
    onNavigateToCompletedRides: (String) -> Unit = {},
    onNavigateToSearchByArea: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Pre-populate from session
    var firstName by remember { mutableStateOf(ConvoySessionManager.getFirstName(context)) }
    var lastName by remember { mutableStateOf(context.getSharedPreferences("grouptrack_user", android.content.Context.MODE_PRIVATE).getString("last_name", "") ?: "") }
    var email by remember { mutableStateOf(ConvoySessionManager.getEmail(context)) }
    var cell by remember { mutableStateOf("") }
    var addressLine1 by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var zipCode by remember { mutableStateOf(ConvoySessionManager.getZipCode(context)) }
    var radiusMiles by remember { mutableStateOf(ConvoySessionManager.getSearchRadius(context).toString()) }
    var emailOptIn by remember { mutableStateOf(true) }

    val isOrganizer = ConvoySessionManager.isOrganizer(context)
    val isSubscribed = ConvoySessionManager.isSubscribed(context)

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "Profile")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Status badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBadge(
                    label = if (isSubscribed) "SUBSCRIBED" else "FREE",
                    color = if (isSubscribed) Gt.good else Gt.text
                )
                if (isOrganizer) {
                    StatusBadge(label = "ORGANIZER", color = Gt.accent)
                }
            }

            ProfileSectionLabel("PERSONAL INFO")
            ProfileField("First Name", firstName) { firstName = it }
            ProfileField("Last Name", lastName) { lastName = it }
            ProfileField("Email", email, enabled = false) { email = it }
            ProfileField("Cell Phone", cell, placeholder = "Optional") { cell = it }

            ProfileSectionLabel("RIDE DISCOVERY")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    ProfileField("Zip Code", zipCode) {
                        zipCode = it
                        ConvoySessionManager.setZipCode(context, it)
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    ProfileField("Radius (mi)", radiusMiles, placeholder = "100") {
                        radiusMiles = it
                        it.toIntOrNull()?.let { r -> ConvoySessionManager.setSearchRadius(context, r) }
                    }
                }
            }
            Text(
                text = "Rides within this radius of your zip code appear on your Dashboard",
                color = Gt.text, fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            ProfileSectionLabel("ADDRESS")
            ProfileField("Street Address", addressLine1, placeholder = "123 Main St") { addressLine1 = it }
            var addressLine2 by remember { mutableStateOf("") }
            ProfileField("Apt / Suite", addressLine2, placeholder = "Optional") { addressLine2 = it }
            ProfileField("City", city) {
                city = it
                ConvoySessionManager.setCity(context, it)
            }
            ProfileField("State", state, placeholder = "e.g. UT") {
                state = it
                ConvoySessionManager.setState(context, it)
            }
            ProfileField("Zip Code", zipCode, enabled = false) {}

            ProfileSectionLabel("PREFERENCES")
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "EMAIL OPT-IN",
                        color = Gt.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                    Text(
                        text = "Receive ride announcements from organizers you follow",
                        color = Gt.text, fontSize = 12.sp
                    )
                }
                Switch(
                    checked = emailOptIn,
                    onCheckedChange = { emailOptIn = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Gt.accent,
                        checkedTrackColor = Gt.panel,
                        uncheckedThumbColor = Gt.text,
                        uncheckedTrackColor = Gt.panel
                    )
                )
            }

            // Save button
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(GroupTrackColors.SkyBlue.copy(alpha = 0.18f))
                    .clickable { /* Phase C: PUT /users/profile */ }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SAVE PROFILE",
                    color = GroupTrackColors.Navy, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }

            // Subscription management
            if (!isSubscribed) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Gt.panel)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SUBSCRIBE — $3.00/month",
                        color = Gt.warn, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Ride history link
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel)
                    .clickable { /* Phase C: navigate to s07 */ }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Ride History", color = Gt.accent, fontSize = 13.sp)
                Text(text = "→", color = Gt.accent, fontSize = 16.sp)
            }

            // Following link
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel)
                    .clickable { /* Phase C: navigate to s08 */ }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Following", color = Gt.accent, fontSize = 13.sp)
                Text(text = "→", color = Gt.accent, fontSize = 16.sp)
            }

            Spacer(Modifier.height(8.dp))
        }

        GroupTrackBottomNav(
            activeTab = GroupTrackTab.PROFILE,
            onHome = onBack,
            onRides = onBack,
            onMap = onBack,
            onProfile = {},
            onRadio = onBack,
            onMyOrganizers = onMyOrganizers,
            onApplyMasterConfig = onApplyMasterConfig,
            onArchiveRestore = onArchiveRestore,
            onNavigateToCompletedRides = onNavigateToCompletedRides,
            onNavigateToSearchByArea = onNavigateToSearchByArea
        )
    }
}

@Composable
private fun ProfileSectionLabel(label: String) {
    Text(
        text = label,
        color = Gt.accent, fontSize = 12.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label, color = color,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    placeholder: String = "",
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = if (placeholder.isNotEmpty()) {{ Text(placeholder, color = Gt.text, fontSize = 13.sp) }} else null,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gt.accent,
            unfocusedBorderColor = Gt.line,
            disabledBorderColor = Gt.line,
            focusedTextColor = Gt.text,
            unfocusedTextColor = Gt.text,
            disabledTextColor = Gt.dim,
            focusedLabelColor = Gt.accent,
            unfocusedLabelColor = Gt.text,
            disabledLabelColor = Gt.panel,
            cursorColor = Gt.accent
        )
    )
}
