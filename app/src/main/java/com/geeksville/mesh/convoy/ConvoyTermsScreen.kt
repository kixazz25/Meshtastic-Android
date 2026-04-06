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
fun ConvoyTermsScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {
        GroupTrackHeader(subtitle = "Rider Terms of Service")
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(text = "RIDER TERMS V$TERMS_VERSION", color = GroupTrackColors.SkyBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 16.dp))
            TermsSection("1. Beta Program", "GroupTrack is currently in beta. Features may change, be added, or removed. You agree to provide feedback and report issues to help improve the product.")
            TermsSection("2. Acceptable Use", "GroupTrack is designed for legitimate group coordination activities including motorcycle riding, hiking, overlanding, and similar outdoor pursuits. You agree not to use the platform for any unlawful purpose.")
            TermsSection("3. Subscription", "Access to GroupTrack platform features requires an active subscription at $3.00/month. The convoy map, radio configuration, and offline features are free and always available.")
            TermsSection("4. Data", "Ride tracks, GPS data, and participation history are stored on GroupTrack servers. You retain ownership of your data. See Privacy Policy for full details.")
            TermsSection("5. Liability", "GroupTrack provides coordination tools only. You are responsible for your own safety and the safety of your group at all times. GroupTrack assumes no liability for incidents occurring during use of the application.")
            TermsSection("6. Changes", "These terms may be updated. You will be notified and asked to re-accept if material changes are made.")
            Spacer(Modifier.height(24.dp))
        }
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GroupTrackButton(text = "I ACCEPT THE RIDER TERMS", onClick = { ConvoySessionManager.acceptTerms(context); onAccept() })
            GroupTrackButton(text = "DECLINE", onClick = onDecline, color = Color(0xFF2A3545))
            Text(text = "You must accept the Rider Terms to use GroupTrack platform features.", color = Color(0xFF445566), fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TermsSection(title: String, body: String) {
    Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
    Text(text = body, color = Color(0xFFAABBCC), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 20.dp))
}
