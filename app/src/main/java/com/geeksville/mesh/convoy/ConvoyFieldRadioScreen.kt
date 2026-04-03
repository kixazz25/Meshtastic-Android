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
// ConvoyFieldRadioScreen.kt
// V3.0 Phase B — Field Radio screen
//
// Always active. No internet needed.
// Radio config only — apply master config, apply ride config,
// verify config, archive/restore.
//
// This is the offline radio management hub.
// Fully built in Phase B PB-23
// ============================================================

private val Navy     = Color(0xFF0F2035)
private val NavyDark = Color(0xFF0A1628)
private val SkyBlue  = Color(0xFF4AB8E8)
private val White    = Color(0xFFFFFFFF)
private val WhiteDim = Color(0xFFAABBCC)
private val Grey     = Color(0xFF445566)
private val Green    = Color(0xFF1CF0A0)

@Composable
fun ConvoyFieldRadioScreen(
    onNavigateToApplyMaster: () -> Unit,
    onNavigateToVerify: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, Navy))),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        GroupTrackHeader(subtitle = "Field Radio")

        // Always-available banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A2010))
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "📡  Always active — no internet required",
                color = Green,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        // Radio config buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FieldRadioButton(
                icon = "⚙️",
                label = "APPLY MASTER CONFIG",
                subLabel = "Write GroupTrack default channel and settings to radio",
                onClick = onNavigateToApplyMaster
            )
            FieldRadioButton(
                icon = "🏁",
                label = "APPLY RIDE CONFIG",
                subLabel = "Write ride-specific channel and encryption to radio",
                onClick = { /* PB-07 — requires active ride download */ },
                enabled = false,
                lockedReason = "Download a ride invite to enable"
            )
            FieldRadioButton(
                icon = "✓",
                label = "VERIFY CONFIG",
                subLabel = "Read back radio and confirm all fields match",
                onClick = onNavigateToVerify
            )
        }

        // Back
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A1628))
                .padding(16.dp)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "← BACK",
                color = Grey,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun FieldRadioButton(
    icon: String,
    label: String,
    subLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    lockedReason: String = ""
) {
    val bgColor = if (enabled) Color(0xFF1A3050) else Color(0xFF0F1E2E)
    val textColor = if (enabled) Color.White else Color(0xFF334455)
    val subColor = if (enabled) Color(0xFFAABBCC) else Color(0xFF223344)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 28.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (!enabled && lockedReason.isNotEmpty()) lockedReason else subLabel,
                    color = subColor,
                    fontSize = 11.sp
                )
            }
        }
        if (enabled) {
            Text(text = "→", color = SkyBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(text = "🔒", fontSize = 18.sp)
        }
    }
}
