package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// ConvoyDashboardScreen.kt
// V3.0 Phase B — Dashboard landing screen
//
// Five buttons: RIDES, EXPLORE, TRACKS, PROFILE, RADIO CONFIG
// Subscribed users: direct navigation
// Free users: onShowSubscription() called instead
//
// Fully built in Phase B PB-22
// ============================================================

private val Navy     = Color(0xFF0F2035)
private val NavyDark = Color(0xFF0A1628)
private val SkyBlue  = Color(0xFF4AB8E8)
private val White    = Color(0xFFFFFFFF)
private val WhiteDim = Color(0xFFAABBCC)
private val Grey     = Color(0xFF445566)
private val Gold     = Color(0xFFFFCC44)

data class DashboardButton(
    val icon: String,
    val label: String,
    val subLabel: String,
    val requiresSubscription: Boolean,
    val onClick: () -> Unit
)

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
    val buttons = listOf(
        DashboardButton("🏁", "RIDES", "Create & manage organized rides", true) {
            if (isSubscribed) onNavigateToRides() else onShowSubscription()
        },
        DashboardButton("🔍", "EXPLORE", "Discover rides and organizers", true) {
            if (isSubscribed) onNavigateToExplore() else onShowSubscription()
        },
        DashboardButton("📍", "TRACKS", "Community track library", true) {
            if (isSubscribed) onNavigateToTracks() else onShowSubscription()
        },
        DashboardButton("👤", "PROFILE", "Account, ride history, following", true) {
            if (isSubscribed) onNavigateToProfile() else onShowSubscription()
        },
        DashboardButton("📡", "FIELD RADIO", "Radio config — always available", false) {
            onNavigateToFieldRadio()
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, Navy))),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        GroupTrackHeader(subtitle = "Dashboard")

        // Subscription status banner
        if (!isSubscribed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A0A00))
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clickable { onShowSubscription() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⭐  Unlock all features — \$3.00/month  →",
                    color = Gold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Dashboard buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            buttons.forEach { btn ->
                DashboardButtonItem(
                    button = btn,
                    isSubscribed = isSubscribed
                )
            }
        }

        GroupTrackBottomNav(
            activeTab = GroupTrackTab.HOME,
            onHome = {},
            onRides = onNavigateToRides,
            onMap = onBack,
            onProfile = onNavigateToProfile
        )
    }
}

@Composable
fun DashboardButtonItem(button: DashboardButton, isSubscribed: Boolean) {
    val locked = button.requiresSubscription && !isSubscribed
    val bgColor = if (locked) Color(0xFF0F1E2E) else Color(0xFF1A3050)
    val textColor = if (locked) Color(0xFF334455) else White
    val subColor = if (locked) Color(0xFF223344) else WhiteDim

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { button.onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = button.icon, fontSize = 28.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = button.label,
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = button.subLabel,
                    color = subColor,
                    fontSize = 11.sp
                )
            }
        }
        if (locked) {
            Text(text = "🔒", fontSize = 18.sp)
        } else {
            Text(text = "→", color = SkyBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
