package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.SolidColor
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
    onSearch: (String, String) -> Unit = { _, _ -> },
    onResultClick: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    searchResults: List<ArtifactResult> = emptyList(),
    onDismiss: () -> Unit = {},
    startExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(startExpanded) }
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
                    .clickable { onDismiss() }   // FAB model: tapping the bar closes the panel entirely (FAB returns)
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
                    modifier = Modifier.clickable { expanded = false; onCreateRoute() },
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

                    // search removed 2026-06-19 -- now the UnifiedSearch FAB (both maps)
                    Spacer(modifier = Modifier.height(4.dp))

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
private fun SearchBlock(onSearch: (String, String) -> Unit) {
    val aMono = FontFamily.Monospace
    // selType holds the lowercase TABLE name passed to searchByName ("trails"/"tracks"/
    // "waypoints"/"routes"); null = no pick yet (Enter-search disabled).
    var selType by remember { mutableStateOf<String?>(null) }
    var term by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val types = listOf(
        "Trail" to ("trails" to aGreen),
        "Track" to ("tracks" to aBlue),
        "Waypoint" to ("waypoints" to aOrange),
        "Route" to ("routes" to aPurple)
    )
    val selLabel = types.firstOrNull { it.second.first == selType }?.first
    val selColor = types.firstOrNull { it.second.first == selType }?.second?.second ?: aTxtD

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Type + name on one row ──
        Box(modifier = Modifier.width(108.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { menuOpen = true },
                shape = RoundedCornerShape(3.dp),
                color = Color(0xFF0D1520)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        selLabel ?: "Select type…",
                        color = if (selLabel != null) selColor else aTxtD,
                        fontSize = 11.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("v", color = aTxtD, fontSize = 10.sp, fontFamily = aMono)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                types.forEach { (label, pair) ->
                    val table = pair.first
                    val col = pair.second
                    DropdownMenuItem(
                        text = {
                            Text(label, color = col, fontSize = 12.sp,
                                fontFamily = aMono, fontWeight = FontWeight.Bold)
                        },
                        onClick = { selType = table; menuOpen = false }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        // ── Name field — Enter (ImeAction.Search) runs the search; no FIND button ──
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(3.dp),
            color = Color(0xFF0D1520)
        ) {
            BasicTextField(
                value = term,
                onValueChange = { term = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = aTxtB, fontSize = 11.sp, fontFamily = aMono
                ),
                cursorBrush = SolidColor(aBlue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { selType?.let { onSearch(it, term) } }
                ),
                decorationBox = { inner ->
                    Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                        if (term.isEmpty()) {
                            Text(
                                if (selType == null) "pick a type first" else "search name, then Enter",
                                color = aTxtD, fontSize = 11.sp, fontFamily = aMono
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

@Composable
private fun ResultsList(
    results: List<ArtifactResult>,
    onResultClick: (String, String, String, String) -> Unit
) {
    val aMono = FontFamily.Monospace
    Spacer(modifier = Modifier.height(4.dp))
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 200.dp),
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF0D1520)
    ) {
        if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text("pick a type, then FIND", color = aTxtD, fontSize = 9.sp, fontFamily = aMono)
            }
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
                if (results.size >= 200) {
                    Text("showing first 200 — refine",
                        color = aOrange, fontSize = 8.sp, fontFamily = aMono,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                results.forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onResultClick(r.type, r.id, r.geomHash, r.name) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.name, color = aTxtB, fontSize = 10.sp, fontFamily = aMono,
                            modifier = Modifier.weight(1f))
                        Text("#${r.seq}", color = aBlue, fontSize = 9.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(r.id.take(8), color = aTxtD, fontSize = 8.sp, fontFamily = aMono)
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
                color = if (state == 0) Color(0xFF2266CC) else Color(0xFF2D8B2D),
                modifier = Modifier.clickable { onSetState(typeName, 0) }.padding(2.dp)) {
                Text("OFF", color = if (state == 0) Color.White else Color(0xFF39FF14),
                    fontSize = 10.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
            Spacer(Modifier.width(4.dp))
            // ALL button
            Surface(shape = RoundedCornerShape(3.dp),
                color = if (state == 1) Color(0xFF2266CC) else Color(0xFF2D8B2D),
                modifier = Modifier.clickable { onSetState(typeName, 1) }.padding(2.dp)) {
                Text("ALL", color = if (state == 1) Color.White else Color(0xFF39FF14),
                    fontSize = 10.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
            Spacer(Modifier.width(4.dp))
            // SEL/EDIT button
            Surface(shape = RoundedCornerShape(3.dp),
                color = if (state == 2) Color(0xFF2266CC) else Color(0xFF2D8B2D),
                modifier = Modifier.clickable { onEditDisplay(typeName) }.padding(2.dp)) {
                Text("SEL/EDIT", color = if (state == 2) Color.White else Color(0xFF39FF14),
                    fontSize = 10.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}
