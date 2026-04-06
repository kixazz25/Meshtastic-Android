package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── GroupTrack Brand Colors ────────────────────────────────────────────────────
object GroupTrackColors {
    val Navy      = Color(0xFF1A2E4A)   // dark background, headers
    val SkyBlue   = Color(0xFF4AB8E8)   // primary brand, buttons
    val Orange    = Color(0xFFF5A623)   // "Group" in logo
    val White     = Color(0xFFFFFFFF)
    val LightGrey = Color(0xFFEEF4FB)   // alternating row background
    val DarkGrey  = Color(0xFF555555)   // secondary text
    val Green     = Color(0xFF00AA44)   // success / locked status
    val Amber     = Color(0xFFF0A500)   // warning
    val Red       = Color(0xFF8B0000)   // danger / remove actions
}

// ── GroupTrack Logo Text ───────────────────────────────────────────────────────
// Renders "Group" in orange and "Track" in sky blue
@Composable
fun GroupTrackLogoText(
    fontSize: Int = 28,
    modifier: Modifier = Modifier
) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = GroupTrackColors.Orange, fontWeight = FontWeight.ExtraBold)) {
                append("Group")
            }
            withStyle(SpanStyle(color = GroupTrackColors.SkyBlue, fontWeight = FontWeight.ExtraBold)) {
                append("Track")
            }
        },
        fontSize = fontSize.sp,
        fontFamily = FontFamily.Default,
        modifier = modifier
    )
}

// ── GroupTrack Screen Header ───────────────────────────────────────────────────
// Navy header bar with GroupTrack logo + optional subtitle
@Composable
fun GroupTrackHeader(
    subtitle: String = "",
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(GroupTrackColors.Navy)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GroupTrackLogoText(fontSize = 32)
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle.uppercase(),
                        color = GroupTrackColors.SkyBlue,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                }
            }
        }
        // Sky blue accent divider
        HorizontalDivider(
            thickness = 3.dp,
            color = GroupTrackColors.SkyBlue
        )
    }
}

// ── GroupTrack Primary Button ──────────────────────────────────────────────────
@Composable
fun GroupTrackButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = GroupTrackColors.SkyBlue
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = GroupTrackColors.DarkGrey
        )
    ) {
        Text(
            text = text,
            color = GroupTrackColors.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp
        )
    }
}

// ── GroupTrack Tag line ────────────────────────────────────────────────────────
@Composable
fun GroupTrackTagline(modifier: Modifier = Modifier) {
    Text(
        text = "OFF-GRID  |  MESH RADIO  |  NO CELL REQUIRED",
        color = GroupTrackColors.DarkGrey,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
        modifier = modifier
    )
}
// ── Bottom Navigation Bar ─────────────────────────────────────────────────────
enum class GroupTrackTab { HOME, RIDES, MAP, PROFILE }

@Composable
fun GroupTrackBottomNav(
    activeTab: GroupTrackTab,
    onHome: () -> Unit,
    onRides: () -> Unit,
    onMap: () -> Unit,
    onProfile: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color(0xFF0A1628))
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
    ) {
        data class NavItem(val tab: GroupTrackTab, val icon: String, val label: String, val action: () -> Unit)
        listOf(
            NavItem(GroupTrackTab.HOME,    "🏠", "HOME",    onHome),
            NavItem(GroupTrackTab.RIDES,   "🏁", "RIDES",   onRides),
            NavItem(GroupTrackTab.MAP,     "🗺", "MAP",     onMap),
            NavItem(GroupTrackTab.PROFILE, "👤", "PROFILE", onProfile)
        ).forEach { item ->
            val tab = item.tab; val icon = item.icon; val label = item.label; val action = item.action
            val isActive = tab == activeTab
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier
                    .clickable { action() }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.Text(text = icon, fontSize = 22.sp)
                androidx.compose.material3.Text(
                    text = label,
                    color = if (isActive) GroupTrackColors.SkyBlue else androidx.compose.ui.graphics.Color(0xFF445566),
                    fontSize = 9.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
