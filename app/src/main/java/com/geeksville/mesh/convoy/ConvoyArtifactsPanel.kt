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
import androidx.compose.foundation.background
// CLOSEBTN-2026-09-02: border + clip for the close button's box. ⚠ The locked
// notes record THREE compile failures from patches that introduced a Compose
// symbol without its import, each one naming the missing import in its own
// output. Checked against the file's existing imports before shipping this
// time: RoundedCornerShape, background and layout.* (Box) were already here.
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip

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
                // CLEANUP-2026-08-24H: a CLOSE glyph, not a chevron.
                //
                // The whole Row is one clickable calling onDismiss(), so v/>
                // promised expand-and-collapse and delivered close -- and the
                // collapsed bar then hid under other panels while the FAB that
                // would reopen it stayed hidden, because the panel was still
                // technically open.
                //
                // Fred: "wasted functionality -- open it when you need it, close
                // it otherwise."
                // CLOSEBTN-2026-09-02: ⛔ THE X WAS ALREADY HERE and nobody
                // could see it. 10sp, unboxed, the same blue as the title
                // beside it -- it read as decoration, not a control. Fred,
                // 09-01: "it is insane trying to get back to the desktop once
                // we have things open."
                // ⭐ The lesson generalises: a close action that EXISTS but is
                // not legible as a button is the same as no close action. Three
                // panels have now had this shape.
                // ⚠ The whole Row is still clickable, so this is about making
                // the affordance visible, not about adding behaviour.
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.width(26.dp).height(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1B2027))
                        .border(1.dp, Color(0xFF47505A), RoundedCornerShape(4.dp))
                ) {
                    Text(
                        "\u2715",
                        color = Color(0xFFE6EDF3), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    // PLAINCTRL2-2026-08-17: matches the FAB that opens this panel.
                    "WORK WITH MAP FEATURES",
                    color = aBlue, fontSize = 11.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (!isConvoyMap) {
                Surface(
                    // CLEANUP-2026-08-24H: dismiss, not collapse. Leaving a stub
                    // behind was what made the panel unfindable afterwards.
                    modifier = Modifier.clickable { onDismiss(); onCreateRoute() },
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
            }

            // ── Expandable grid ──
            // CLEANUP-2026-08-24H: always visible. `expanded` is retained so the
            // AnimatedVisibility and startExpanded parameter stay wired, but
            // nothing sets it false any more -- the panel is open or gone.
            AnimatedVisibility(
                visible = true,
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
                        // PLAINLABELS-2026-08-17D: plain language. "Type" told a new rider nothing.
                        Text("Map Feature", color = aTxtD, fontSize = 9.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                        // PLAINLABELS-2026-08-17D: labels the three display buttons to the right. The
                        // colour convention is otherwise unguessable -- the ACTIVE state draws a
                        // blue surface (0xFF2266CC), the others green (0xFF2D8B2D). Unconditional:
                        // the buttons appear on BOTH maps, so the header does too.
                        Text("Display Action", color = aTxtD, fontSize = 9.sp, fontFamily = aMono,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (!isConvoyMap) {

                        }
                    }
                    // PLAINCTRL2-2026-08-17: the colour convention is otherwise unguessable.
                    // PLAINCTRL3-2026-08-18B: colour blocks so the convention is visible, not
                    // just described. Small Box with the actual surface colour inline.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(
                            Color(0xFF2266CC),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                        Text(" blue = on   ", color = aTxtD, fontSize = 8.sp, fontFamily = aMono)
                        Box(modifier = Modifier.size(8.dp).background(
                            Color(0xFF2D8B2D),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                        Text(" green = available", color = aTxtD, fontSize = 8.sp, fontFamily = aMono)
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
                            // OSM-IMPORT-2026-07-28: three-across. Font 9->8sp and
                            // horizontal padding 8->4dp on ALL THREE so they share
                            // the row evenly rather than a new style beside two old ones.
                            Surface(
                                modifier = Modifier.weight(1f).clickable { onImport("Trails") },
                                shape = RoundedCornerShape(4.dp), color = Color(0xFF0D1520)
                            ) {
                                // PANELBTN-2026-08-21E: LABEL ONLY. The clickable above
                                // passes a dispatch key matched by string in
                                // ConvoyMapViewerScreen -- it must not change with this
                                // label. Two lines written explicitly so the break lands
                                // where we chose it, not where the renderer wraps.
                                Text("IMPORT TRAILS BY\nSTATE OR AREA", color = aGreen, fontSize = 8.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    // BTNLINE-2026-08-21J: the default lineHeight comes from the type
                                    // scale and does not shrink with fontSize, so two 8sp lines got
                                    // a body-text line box. 10.sp is ~1.2x -- the lines flow like a
                                    // wrapped sentence and the button loses the excess height.
                                    lineHeight = 10.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
                            }
                            Surface(
                                modifier = Modifier.weight(1f).clickable { onImport("Artifacts") },
                                shape = RoundedCornerShape(4.dp), color = Color(0xFF0D1520)
                            ) {
                                // PLAINLABELS-2026-08-17D: label ONLY. The onImport argument on the
                                // clickable above is a dispatch key matched in
                                // ConvoyMapViewerScreen -- it MUST NOT change with this label.
                                // PANELBTN-2026-08-21E: names what it imports instead of the
                                // category word. "Features" and "Trails" read as the same
                                // thing to a rider; tracks/waypoints/routes do not.
                                Text("IMPORT TRACKS,\nWAYPOINTS & ROUTES", color = aBlue, fontSize = 8.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                                    // BTNLINE-2026-08-21J: same tight leading as the button beside it --
                                    // both must shrink together or the row goes lopsided.
                                    lineHeight = 10.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
                            }
                            // PANELBTN-2026-08-21E: "IMPORT OSM DATA" REMOVED (design spec §2).
                            // OSM is no longer a separate concept -- it is one source inside
                            // Import Trails, run automatically with every other source that
                            // covers the chosen state or area. Patch D killed its handler;
                            // the button itself survived and rendered as a dead control.
                            // Screenshots of the old flow were captured 2026-08-21 for the
                            // manual before removal.
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
        // SELCAP-2026-09-01: the LIST height, not the row cap -- left alone.
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
                if (results.size >= 2000) {
                    Text("showing first 2000 — refine",
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
    // SELCAP-2026-09-01: ⛔ TRAILS HAVE NO PER-TRAIL SELECTION. Fred, 09-01:
    // "I cannot think of a reason why a trail would be selected since we have
    // so many unnamed" -- ~69% of rows are the literal string "Not Named", so
    // a rider cannot meaningfully pick from that list.
    // ⚠ AND IT WAS A TRAP: SpatialDisplayManager filters to checkedIds when the
    // state is SELECTED, so a trail imported after that list was saved silently
    // never drew. That is the shape of the missing-trail problem.
    // ⭐ A saved SELECTED reads as ALL for trails, so a device already stuck in
    // that state recovers on its own rather than showing an empty map.
    val noSelect = typeName == "Trails"
    val rawState = displayStates[typeName] ?: 0
    val state = if (noSelect && rawState == 2) 1 else rawState
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
            // SELCAP-2026-09-01: greyed and inert for Trails. It still SHOWS so
            // the four rows stay aligned and the panel reads consistently --
            // Fred: "if you grey out the box as not selectable then the only
            // two choices are on and off."
            Surface(shape = RoundedCornerShape(3.dp),
                color = when {
                    noSelect -> Color(0xFF2A3038)
                    state == 2 -> Color(0xFF2266CC)
                    else -> Color(0xFF2D8B2D)
                },
                modifier = (if (noSelect) Modifier
                            else Modifier.clickable { onEditDisplay(typeName) })
                    .padding(2.dp)) {
                // PLAINLABELS-2026-08-17D: plain-language label for the third toggle.
                // These three are ACTIONS you tap, not states you read.
                Text("SELECT", color = when {
                        noSelect -> Color(0xFF5B646E)
                        state == 2 -> Color.White
                        else -> Color(0xFF39FF14)
                    },
                    fontSize = 10.sp, fontFamily = aMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}
