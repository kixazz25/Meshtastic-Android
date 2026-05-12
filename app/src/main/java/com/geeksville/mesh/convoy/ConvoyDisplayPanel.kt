package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ----------------------------------------------------------------
// ConvoyDisplayPanel -- floating draggable twisty panel
//
// Closed: small pill showing "> DISPLAY"
// Open: toggle list for map layer visibility
//
// Same component on both convoy map and planning map.
// ----------------------------------------------------------------

private val panelBg = Color(0xEE131820)
private val itemBg = Color(0xFF1A2233)
private val textBright = Color(0xFFCCDDEE)
private val textDim = Color(0xFF4A6080)
private val accentBlue = Color(0xFF4DA6FF)
private val accentGreen = Color(0xFF1CF0A0)
private val mono = FontFamily.Monospace

@Composable
fun ConvoyDisplayPanel(
    tracksOn: Boolean,
    onTracksToggle: () -> Unit,
    trailsOn: Boolean,
    onTrailsToggle: () -> Unit,
    downloadedOn: Boolean,
    onDownloadedToggle: () -> Unit,
    scanningDownloaded: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            },
        shape = RoundedCornerShape(10.dp),
        color = panelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(8.dp).width(180.dp)) {
            // Twisty header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (expanded) "v" else ">",
                    color = accentBlue,
                    fontSize = 11.sp,
                    fontFamily = mono,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "DISPLAY",
                    color = accentBlue,
                    fontSize = 10.sp,
                    fontFamily = mono,
                    fontWeight = FontWeight.Bold
                )
            }

            // Expandable toggle list
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Tracks
                    DisplayToggleRow(
                        label = "Tracks",
                        isOn = tracksOn,
                        onToggle = onTracksToggle,
                        iconColor = accentBlue
                    )

                    // Trails
                    DisplayToggleRow(
                        label = "Trails",
                        isOn = trailsOn,
                        onToggle = onTrailsToggle,
                        iconColor = accentGreen
                    )

                    // Waypoints -- V2.5 greyed
                    DisplayToggleRow(
                        label = "Waypoints",
                        isOn = false,
                        onToggle = {},
                        enabled = false,
                        versionTag = "V2.5"
                    )

                    // Routes -- V2.5 greyed
                    DisplayToggleRow(
                        label = "Routes",
                        isOn = false,
                        onToggle = {},
                        enabled = false,
                        versionTag = "V2.5"
                    )

                    // Downloaded areas
                    DisplayToggleRow(
                        label = if (scanningDownloaded) "Scanning..." else "Downloaded areas",
                        isOn = downloadedOn,
                        onToggle = onDownloadedToggle,
                        iconColor = accentBlue
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayToggleRow(
    label: String,
    isOn: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    iconColor: Color = textDim,
    versionTag: String? = null
) {
    val alpha = if (enabled) 1f else 0.45f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() },
        shape = RoundedCornerShape(4.dp),
        color = itemBg
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    label,
                    color = if (enabled) textBright else textDim,
                    fontSize = 10.sp,
                    fontFamily = mono,
                    modifier = Modifier.alpha(alpha)
                )
                if (versionTag != null) {
                    Text(
                        versionTag,
                        color = textDim,
                        fontSize = 8.sp,
                        fontFamily = mono,
                        modifier = Modifier.alpha(alpha)
                    )
                }
            }
            Switch(
                checked = isOn,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.height(16.dp).width(28.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2E75B6),
                    uncheckedThumbColor = textDim,
                    uncheckedTrackColor = Color(0xFF1A2A3A)
                )
            )
        }
    }
}


