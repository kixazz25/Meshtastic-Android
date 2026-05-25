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
private val aTxtD     = Color(0xFF7A8DA0)
private val aBlue     = Color(0xFF4DA6FF)
private val aGreen    = Color(0xFF1CF0A0)
private val aOrange   = Color(0xFFD29922)
private val aPurple   = Color(0xFFBC8CFF)
private val aMono     = FontFamily.Monospace

@Composable
fun ConvoyArtifactsPanel(
    isConvoyMap: Boolean,
    displayStates: Map<String, Int> = emptyMap(),
    onEditDisplay: (String) -> Unit = {},
    onImport: (String) -> Unit = {},
    onSetState: (String, Int) -> Unit = { _, _ -> },
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

                        if (!isConvoyMap) {

                        }
                    }

                    // Artifact rows
                    ArtifactRow("Tracks",    aBlue,   isConvoyMap, onSetState, onEditDisplay, onImport, displayStates)
                    ArtifactRow("Trails",    aGreen,  isConvoyMap, onSetState, onEditDisplay, onImport, displayStates)
                    ArtifactRow("Waypoints", aOrange, isConvoyMap, onSetState, onEditDisplay, onImport, displayStates)
                    ArtifactRow("Routes",    aPurple, isConvoyMap, onSetState, onEditDisplay, onImport, displayStates)

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
    onSetState: (String, Int) -> Unit,
    onEditDisplay: (String) -> Unit,
    onImport: (String) -> Unit,
    displayStates: Map<String, Int> = emptyMap()
) {
    val aMono = FontFamily.Monospace
    val state = displayStates[typeName] ?: 0
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF1A2233)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(typeName, color = typeColor, fontSize = 11.sp, fontFamily = aMono,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // OFF button
            Surface(shape = RoundedCornerShape(3.dp),
                color = if (state == 0) Color(0xFF2266CC) else Color.Transparent,
                modifier = Modifier.clickable { onSetState(typeName, 0) }.padding(2.dp)) {
                Text("OFF", color = if (state == 0) Color.White else Color(0xFF39FF14),
                    fontSize = 8.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
            Spacer(Modifier.width(4.dp))
            // ALL button
            Surface(shape = RoundedCornerShape(3.dp),
                color = if (state == 1) Color(0xFF2266CC) else Color.Transparent,
                modifier = Modifier.clickable { onSetState(typeName, 1) }.padding(2.dp)) {
                Text("ALL", color = if (state == 1) Color.White else Color(0xFF39FF14),
                    fontSize = 8.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
            Spacer(Modifier.width(4.dp))
            // SEL/EDIT button
            Surface(shape = RoundedCornerShape(3.dp),
                color = if (state == 2) Color(0xFF2266CC) else Color.Transparent,
                modifier = Modifier.clickable { onEditDisplay(typeName) }.padding(2.dp)) {
                Text("SEL/EDIT", color = if (state == 2) Color.White else Color(0xFF39FF14),
                    fontSize = 8.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}
