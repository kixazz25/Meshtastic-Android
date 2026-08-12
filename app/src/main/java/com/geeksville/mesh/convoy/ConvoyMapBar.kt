package com.geeksville.mesh.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------
// ConvoyMapBar -- fixed source bar at top of both maps
//
// Layout: [NAV BUTTON] | SAT | TOPO | TOPO+ | NET/LOCAL toggle
//
// Convoy map:  navLabel = "PLANNING MAP"
// Planning map: navLabel = "CONVOY", navIsBack = true
// ----------------------------------------------------------------

@Composable
fun ConvoyMapBar(
    navLabel: String,
    navIsBack: Boolean = false,
    onNavigate: () -> Unit,
    activeSource: String,
    isOffline: Boolean,
    onSourceChange: (label: String) -> Unit,
    onOfflineToggle: (Boolean) -> Unit,
    // PLANGATE-2026-08-12C: tint for the nav button. NULL keeps the default,
    // so the planning map's own bar is untouched by this.
    navTint: Color? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xEE131820),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Nav button
            Surface(
                modifier = Modifier.weight(1f).clickable { onNavigate() },
                shape = RoundedCornerShape(4.dp),
                // PLANGATE-2026-08-12C: green with a connection, yellow without.
                color = navTint ?: Color(0xFF2A3545)
            ) {
                Text(
                    if (navIsBack) "<  $navLabel" else navLabel,
                    // PLANGATE-2026-08-12C: lift the label when the button is tinted,
                    // or the muted grey disappears against the fill.
                    color = if (navTint != null) Color(0xFFE6EDF3)
                            else Color(0xFF7A8DA0),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            // Divider
            Box(
                modifier = Modifier.width(1.dp).height(20.dp)
                    .padding(vertical = 2.dp)
                    .let { it }
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF2A3545)) {}
            }

            // Source buttons from MapSourceManager
            MapSourceManager.getSlotSources().forEach { (label, _, _) ->
                val isActive = activeSource == label
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSourceChange(label) },
                    shape = RoundedCornerShape(4.dp),
                    color = if (isActive) Color(0xFF2E75B6) else Color(0xFF1A2233)
                ) {
                    Text(
                        label,
                        color = if (isActive) Color.White else Color(0xFF4A6080),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier.width(1.dp).height(20.dp)
                    .padding(vertical = 2.dp)
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF2A3545)) {}
            }

            // NET/LOCAL toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    if (isOffline) "LOCAL" else "NET",
                    color = if (isOffline) Color(0xFF4DA6FF) else Color(0xFF1CF0A0),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Switch(
                    checked = isOffline,
                    onCheckedChange = { onOfflineToggle(it) },
                    modifier = Modifier.height(20.dp).width(36.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF4DA6FF),
                        checkedTrackColor = Color(0xFF1A2A3A),
                        uncheckedThumbColor = Color(0xFF1CF0A0),
                        uncheckedTrackColor = Color(0xFF1A2A1A)
                    )
                )
            }
        }
    }
}
