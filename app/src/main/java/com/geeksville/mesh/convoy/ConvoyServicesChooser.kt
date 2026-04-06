package com.geeksville.mesh.convoy

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
// ConvoyServicesChooser.kt
// V3.0 Phase B — Event/Ride Services nav icon bottom sheet
//
// Two-button chooser shown when Event/Ride Services icon tapped.
// ONLINE  → sign-in → dashboard (if wifi + signed in → dashboard direct)
// OFFLINE → offline services (Field Radio, Map Manager, etc.)
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoyServicesChooser(
    context: Context,
    onDismiss: () -> Unit,
    onOnline: () -> Unit,
    onOffline: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val signedIn = ConvoySessionManager.isSignedIn(context)
    val subscribed = ConvoySessionManager.isSubscribed(context)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0A1628),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "Event / Ride Services",
                color = Color(0xFF4AB8E8),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            )

            // ONLINE button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF1A3050), Color(0xFF0F2035))
                        )
                    )
                    .clickable { onOnline() }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌐", fontSize = 32.sp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "ONLINE SERVICES",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = when {
                                    signedIn && subscribed -> "Dashboard — rides, explore, tracks, profile"
                                    signedIn -> "Sign in complete — subscription required"
                                    else -> "Sign in with Google to access"
                                },
                                color = Color(0xFF4AB8E8),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        text = if (signedIn && subscribed) "→" else "🔑",
                        color = Color(0xFF4AB8E8),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // OFFLINE button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A2010))
                    .clickable { onOffline() }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📡", fontSize = 32.sp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "OFFLINE SERVICES",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Field radio, map manager, GPX import/export",
                                color = Color(0xFF1CF0A0),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        text = "→",
                        color = Color(0xFF1CF0A0),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Always active note
            Text(
                text = "Offline services always active — no internet or subscription required",
                color = Color(0xFF445566),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Dismiss
            Text(
                text = "✕  CLOSE",
                color = Color(0xFF445566),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(8.dp)
            )
        }
    }
}
