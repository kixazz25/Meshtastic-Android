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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ----------------------------------------------------------------
// ConvoyArtifactsPanel -- V2.5 Scaffold
// Draggable accordion: "> WORK WITH ARTIFACTS  [+ ROUTE]"
// Grid: Type | Display | Edit/Display | Import
// ----------------------------------------------------------------

private val aPanelBg  = Color(0xEE131820)
private val aRowBg    = Color(0xFF1A2233)
private val aTxtB     = Color(0xFFCCDDEE)
private val aTxtD     = Color(0xFF4A6080)
private val aBlue     = Color(0xFF4DA6FF)
private val aGreen    = Color(0xFF1CF0A0)
private val aOrange   = Color(0xFFD29922)
private val aPurple   = Color(0xFFBC8CFF)
private val aMono     = FontFamily.Monospace

@Composable
fun ConvoyArtifactsPanel(
    isConvoyMap: Boolean,
    onEditDisplay: (String) -> Unit = {},
    onImport: (String) -> Unit = {},
    onDisplayToggle: (String) -> Unit = {},
    onCreateRoute: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            },
        shape = RoundedCornerShape(10.dp),
        color = aPanelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {

            // ── Drag handle pill ──
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.width(40.dp).height(4.dp),
                    shape = RoundedCornerShape(2.dp),
                    color = aTxtD.copy(alpha = 0.5f)
                ) {}
            }
            Spacer(modifier = Modifier.height(6.dp))

            // ── Accordion title bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "v" else ">",
                    color = aBlue, fontSize = 10.sp, fontFamily = aMono, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "WORK WITH ARTIFACTS",
                    color = aBlue, fontSize = 11.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    modifier = Modifier.clickable { onCreateRoute() },
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF1A2A3A)
                ) {
                    Text(
                        "+ ROUTE", color = aPurple, fontSize = 9.sp,
                        fontFamily = aMono, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // ── Expandable grid ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 6.dp)) {

                    // Column headings
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Type", color = aTxtD, fontSize = 9.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                        Text("Display", color = aTxtD, fontSize = 9.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f),
                            textAlign = TextAlign.Center)
                        if (!isConvoyMap) {
                            Text("Select", color = aTxtD, fontSize = 9.sp, fontFamily = aMono,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f),
                                textAlign = TextAlign.Center)
                        }
                    }

                    // Artifact rows
                    ArtifactRow("Tracks",    aBlue,   isConvoyMap, onDisplayToggle, onEditDisplay, onImport)
                    ArtifactRow("Trails",    aGreen,  isConvoyMap, onDisplayToggle, onEditDisplay, onImport)
                    ArtifactRow("Waypoints", aOrange, isConvoyMap, onDisplayToggle, onEditDisplay, onImport)
                    ArtifactRow("Routes",    aPurple, isConvoyMap, onDisplayToggle, onEditDisplay, onImport)

                    if (!isConvoyMap) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                modifier = Modifier.weight(1f).clickable { onImport("Trails") },
                                shape = RoundedCornerShape(4.dp), color = Color(0xFF0D1520)
                            ) {
                                Text("IMPORT TRAILS", color = aGreen, fontSize = 9.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                            }
                            Surface(
                                modifier = Modifier.weight(1f).clickable { onImport("Artifacts") },
                                shape = RoundedCornerShape(4.dp), color = Color(0xFF0D1520)
                            ) {
                                Text("IMPORT ARTIFACTS", color = aBlue, fontSize = 9.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(
    typeName: String, typeColor: Color, isConvoyMap: Boolean,
    onDisplayToggle: (String) -> Unit,
    onEditDisplay: (String) -> Unit,
    onImport: (String) -> Unit
) {
    var displayOn by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(4.dp),
        color = aRowBg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type name with color dot
            Row(modifier = Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(10.dp), shape = RoundedCornerShape(2.dp), color = typeColor) {}
                Spacer(modifier = Modifier.width(6.dp))
                Text(typeName, color = aTxtB, fontSize = 11.sp, fontFamily = aMono)
            }

            // Display toggle
            Box(modifier = Modifier.weight(0.8f), contentAlignment = Alignment.Center) {
                Switch(
                    checked = displayOn,
                    onCheckedChange = { displayOn = it; onDisplayToggle(typeName) },
                    modifier = Modifier.height(20.dp).width(36.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF2E75B6),
                        uncheckedThumbColor = aTxtD,
                        uncheckedTrackColor = Color(0xFF1A2A3A)
                    )
                )
            }

            if (!isConvoyMap) {
                // Edit button
                Box(modifier = Modifier.weight(0.8f), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.clickable { onEditDisplay(typeName) },
                        shape = RoundedCornerShape(4.dp), color = Color(0xFF0D1520)
                    ) {
                        Text("SELECT", color = aBlue, fontSize = 8.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }

                // Import moved to bottom buttons
            }
        }
    }
}
