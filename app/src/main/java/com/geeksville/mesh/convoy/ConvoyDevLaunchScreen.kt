package com.geeksville.mesh.convoy

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

// ============================================================
// ConvoyDevLaunchScreen.kt
// DEV ONLY — Remove before Play Store submission.
// Shown only when V3_FEATURES_ENABLED = true.
//
// Five-button simulator for all launch route scenarios:
//   1. NEW USER           — no account → Sign In → Terms → Privacy → Dashboard
//   2. GOOGLE NEW TO GT   — user_id set, no terms → Terms → Privacy → Dashboard
//   3. FREE (paywall off) — signed in, no sub, paywall disabled → Dashboard full
//   4. FREE (paywall on)  — signed in, no sub, paywall active → Subscription screen
//   5. PREMIUM            — signed in, subscribed → Dashboard full access
// ============================================================

@Composable
fun ConvoyDevLaunchScreen(onLaunch: () -> Unit) {
    if (!ConvoyConfig.V3_FEATURES_ENABLED) {
        onLaunch()
        return
    }

    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF050D1A)),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(16.dp)
        ) {
            Text(
                text = "DEV LAUNCH SIMULATOR",
                color = Color(0xFFFF8C00), fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Select scenario to simulate — sets SharedPreferences and launches",
                color = Color(0xFF445566), fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Scenario buttons
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DevScenarioButton("1", "NEW USER",
                "No account — Sign In → Terms → Privacy → Dashboard",
                Color(0xFF4AB8E8)) {
                ConvoySessionManager.clearSession(context)
                ConvoyDevSeeder.clear(context)
                onLaunch()
            }

            DevScenarioButton("2", "GOOGLE — NEW TO GROUPTRACK",
                "user_id set, no terms — Terms → Privacy → Dashboard",
                Color(0xFF4AB8E8)) {
                ConvoySessionManager.clearSession(context)
                ConvoyDevSeeder.clear(context)
                ConvoySessionManager.saveUser(context,
                    "dev-user-001", "google-dev-001",
                    "dev@grouptrack.org", "Dev", "User")
                onLaunch()
            }

            DevScenarioButton("3", "FREE USER — PAYWALL OFF",
                "Signed in, no subscription, PAYWALL_ENABLED=false → Dashboard full access",
                Color(0xFF22C55E)) {
                ConvoySessionManager.clearSession(context)
                seedSignedInUser(context, subscribed = false)
                onLaunch()
            }

            DevScenarioButton("4", "FREE USER — PAYWALL ON",
                "Signed in, no subscription, paywall active → Subscription screen",
                Color(0xFFF59E0B)) {
                ConvoySessionManager.clearSession(context)
                seedSignedInUser(context, subscribed = false)
                android.widget.Toast.makeText(context,
                    "Set PAYWALL_ENABLED=true in ConvoyConfig to fully test this path",
                    android.widget.Toast.LENGTH_LONG).show()
                onLaunch()
            }

            DevScenarioButton("5", "PREMIUM USER",
                "Signed in, subscribed, full access → Dashboard",
                Color(0xFFFFCC44)) {
                ConvoySessionManager.clearSession(context)
                seedSignedInUser(context, subscribed = true)
                ConvoyDevSeeder.seed(context)
                onLaunch()
            }
        }

        // Footer
        Box(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "DEV ONLY — Remove before Play Store submission",
                color = Color(0xFF332211), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
            )
        }
    }
}

private fun seedSignedInUser(context: Context, subscribed: Boolean) {
    ConvoySessionManager.saveUser(context,
        "dev-user-001", "google-dev-001",
        "dev@grouptrack.org", "Dev", "User")
    ConvoySessionManager.acceptTerms(context)
    ConvoySessionManager.acceptPrivacy(context)
    setDevSubscribed(context, subscribed)
}

@Composable
private fun DevScenarioButton(
    number: String, title: String, subtitle: String,
    color: Color, onClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(16.dp))
                .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center) {
                Text(text = number, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = color, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(text = subtitle, color = Color(0xFF667788), fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp), lineHeight = 14.sp)
            }
            Text(text = "▶", color = color.copy(alpha = 0.5f), fontSize = 14.sp)
        }
    }
}
