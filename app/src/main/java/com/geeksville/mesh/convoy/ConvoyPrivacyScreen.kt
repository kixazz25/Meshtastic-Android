package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConvoyPrivacyScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "Privacy Policy")
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(text = "PRIVACY POLICY V$PRIVACY_VERSION", color = GroupTrackColors.SkyBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 16.dp))
            PrivacySection("1. What We Collect", "We collect your Google account name and email address when you sign in. We collect GPS track data, ride participation records, and app usage during active rides.")
            PrivacySection("2. How We Use Your Data", "Your name and email are used to identify you to ride organizers you enroll with. GPS tracks are stored to build ride history and the community track library. We do not sell your personal data to third parties.")
            PrivacySection("3. Data Shared With Organizers", "When you enroll in a ride, the organizer can see your name, email address, and GPS track for that ride. You consent to this sharing by accepting an invite.")
            PrivacySection("4. Data Retention", "Your account data is retained while your account is active. You may request deletion of your account and associated data at any time by contacting grouptrack.org support.")
            PrivacySection("5. Security", "Data is transmitted over HTTPS and stored on secured AWS infrastructure. We apply industry standard security practices to protect your information.")
            PrivacySection("6. Changes", "This policy may be updated. You will be notified and asked to re-accept if material changes are made.")
            Spacer(Modifier.height(24.dp))
        }
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GroupTrackButton(text = "I ACCEPT THE PRIVACY POLICY", onClick = { ConvoySessionManager.acceptPrivacy(context); onAccept() })
            GroupTrackButton(text = "DECLINE", onClick = onDecline, color = Color(0xFF2A3545))
            Text(text = "You must accept the Privacy Policy to use GroupTrack platform features.", color = Color(0xFF445566), fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
    Text(text = body, color = Color(0xFFAABBCC), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 20.dp))
}
