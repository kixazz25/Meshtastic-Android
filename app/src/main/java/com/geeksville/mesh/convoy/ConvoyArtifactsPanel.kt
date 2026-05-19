package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
// ConvoyArtifactsPanel -- V2.5 Scaffold (Pass 1)
//
// Accordion panel: "> WORK WITH ARTIFACTS  [+ ROUTE]"
// Grid: Type | Display | Edit/Display | Import
// Source: ScreenReference v5 section 4 + layout revision
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

    Surface(
        modifier = modifier.width(300.dp),
        shape = RoundedCornerShape(10.dp),
        color = aPanelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {

            // ── Accordion title bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "v" else ">",
                    color = aBlue, fontSize = 11.sp, fontFamily = aMono, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "WORK WITH ARTIFACTS",
                    color = aBlue, fontSize = 10.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    modifier = Modifier.clickable { onCreateRoute() },
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF1A2A3A)
                ) {
                    Text(
                        "+ ROUTE", color = aPurple, fontSize = 8.sp,
                        fontFamily = aMono, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            // ── Expandable grid ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 4.dp)) {

                    // Column headings
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Type", color = aTxtD, fontSize = 8.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Display", color = aTxtD, fontSize = 8.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp),
                            textAlign = TextAlign.Center)
                        Text("Edit/Disp", color = aTxtD, fontSize = 8.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp),
                            textAlign = TextAlign.Center)
                        Text("Import", color = aTxtD, fontSize = 8.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp),
                            textAlign = TextAlign.Center)
                    }

                    // Artifact rows
                    ArtifactRow("Tracks",    aBlue,   isConvoyMap, onDisplayToggle, onEditDisplay, onImport)
                    ArtifactRow("Trails",    aGreen,  isConvoyMap, onDisplayToggle, onEditDisplay, onImport)
                    ArtifactRow("Waypoints", aOrange, isConvoyMap, onDisplayToggle, onEditDisplay, onImport)
                    ArtifactRow("Routes",    aPurple, isConvoyMap, onDisplayToggle, onEditDisplay, onImport)
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
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        shape = RoundedCornerShape(3.dp),
        color = aRowBg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type name
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(8.dp), shape = RoundedCornerShape(2.dp), color = typeColor) {}
                Spacer(modifier = Modifier.width(4.dp))
                Text(typeName, color = aTxtB, fontSize = 10.sp, fontFamily = aMono)
            }

            // Display toggle
            Switch(
                checked = false,
                onCheckedChange = { onDisplayToggle(typeName) },
                modifier = Modifier.height(16.dp).width(28.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2E75B6),
                    uncheckedThumbColor = aTxtD,
                    uncheckedTrackColor = Color(0xFF1A2A3A)
                )
            )
            Spacer(modifier = Modifier.width(4.dp))

            // Edit/Display button
            Surface(
                modifier = Modifier.clickable { onEditDisplay(typeName) },
                shape = RoundedCornerShape(3.dp), color = Color(0xFF0D1520)
            ) {
                Text("SELECT", color = aBlue, fontSize = 7.sp, fontFamily = aMono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))

            // Import button
            Surface(
                modifier = Modifier.clickable(enabled = !isConvoyMap) { onImport(typeName) },
                shape = RoundedCornerShape(3.dp),
                color = if (isConvoyMap) Color(0xFF0D1520).copy(alpha = 0.3f) else Color(0xFF0D1520)
            ) {
                Text("IMPORT",
                    color = if (isConvoyMap) aTxtD.copy(alpha = 0.3f) else aGreen,
                    fontSize = 7.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            }
        }
    }
}
