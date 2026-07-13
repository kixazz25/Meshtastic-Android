package com.geeksville.mesh.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------
// ConvoyRouteToolbar -- V2.5 route builder overlay (shared, both maps)
//
// Replaces the collapsed WORK WITH ARTIFACTS panel when route mode is on.
// SCAFFOLD: layout + visual method selection are live; build actions are
// callbacks the host screen supplies. Point-to-point is the live method;
// Draw / Suggest are visible "soon" placeholders.
// ----------------------------------------------------------------

const val ROUTE_METHOD_P2P = 0      // point-to-point snap-2 (LIVE)
const val ROUTE_METHOD_DRAW = 1     // finger-trace (placeholder)
const val ROUTE_METHOD_SUGGEST = 2  // suggest from points (placeholder)

// route lifecycle launch state (fixed at New / Select-In-Progress, read by adaptive Save/Discard)
const val ROUTE_LS_NEW = 0      // building a brand-new route
const val ROUTE_LS_RESUMED = 1  // resumed an in-progress draft

private val rtPanelBg = Color(0xEE131820)
private val rtRowBg   = Color(0xFF1A2233)
private val rtSelBg   = Color(0xFF0F6E56)
private val rtPurple  = Color(0xFFBC8CFF)
private val rtGreen   = Color(0xFF1CF0A0)
private val rtBlue    = Color(0xFF4DA6FF)
private val rtTxtD    = Color(0xFF7A8DA0)
private val rtAmber   = Color(0xFFD29922)
private val rtRed     = Color(0xFFE86B6B)
private val rtMono    = FontFamily.Monospace
private val rtDis     = Color(0xFF44505C)  // disabled/greyed

@Composable
private fun EntryBtnDisabled(label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(5.dp), color = rtRowBg) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(label, color = rtDis, fontSize = 10.sp, fontFamily = rtMono, fontWeight = FontWeight.Bold)
            Text("soon", color = rtAmber, fontSize = 8.sp, fontFamily = rtMono)
        }
    }
}

@Composable
fun ConvoyRouteToolbar(
    isConvoyMap: Boolean,
    vertexCount: Int = 0,
    selectedMethod: Int = ROUTE_METHOD_P2P,
    onSelectMethod: (Int) -> Unit = {},
    onNewRoute: () -> Unit = {},
    onAddModeChanged: (Boolean) -> Unit = {},
    onUndo: () -> Unit = {},
    onSaveCompleted: () -> Unit = {},
    onExit: () -> Unit = {},
    // -- route lifecycle (Layer 2) --
    routeLifecycleState: Int = ROUTE_LS_NEW,
    onSaveRequested: () -> Unit = {},
    onDiscardRequested: () -> Unit = {},
    onSelectInProgress: () -> Unit = {},
    routeEntryNonce: Int = 0,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var building by remember { mutableStateOf(true) }   // toolbar only opens post-entry-choice; build controls live on open
    var minimized by remember { mutableStateOf(false) }
    // Add button armed-state (pact): GREEN = ON (taps place points),
    // RED = OFF (taps pan/reposition). ON on entry for Point; selecting
    // Draw/Suggest sets it OFF (those methods don't tap-to-place).
    var addArmed by remember { mutableStateOf(true) }
    // Re-arm build controls on every route-mode entry (NEW / RESUMED / future
    // extend). Without this, building stays false from a prior exit (line ~174)
    // and a resumed route can place points but cannot Undo/Save.
    androidx.compose.runtime.LaunchedEffect(routeEntryNonce) { building = true; addArmed = true }
    Surface(
        modifier = modifier
            .width(248.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) },
        shape = RoundedCornerShape(10.dp),
        color = rtPanelBg,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // -- Header: grip handle (drag ONLY here -- never a tap control, avoids
            //    the QUEUES tap-vs-drag collision) + title (accordion) --
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("\u2725", color = rtTxtD, fontSize = 13.sp, fontFamily = rtMono,
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                            }
                        }
                        .padding(end = 6.dp))
                Text(if (minimized) "ROUTE + ($vertexCount pts)" else "ROUTE +",
                    color = rtPurple, fontSize = 12.sp,
                    fontFamily = rtMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable { minimized = !minimized })
                Text(if (minimized) "v" else "^", color = rtTxtD, fontSize = 11.sp,
                    fontFamily = rtMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { minimized = !minimized }.padding(start = 6.dp))
            }

            // Entry buttons removed: New-vs-In-Progress is chosen at +ROUTE (entry prompt)
            // BEFORE the toolbar opens, so the toolbar starts in build mode.
            if (!minimized) {
            Text("METHOD", color = if (building) rtTxtD else rtDis, fontSize = 9.sp, fontFamily = rtMono)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MethodChip("Point", selectedMethod == ROUTE_METHOD_P2P, true, building) {
                    // Point = tap-to-place -> Add ON
                    if (building) { onSelectMethod(ROUTE_METHOD_P2P); addArmed = true; onAddModeChanged(true) }
                }
                MethodChip("Draw", selectedMethod == ROUTE_METHOD_DRAW, false, false) {
                    // Draw/Suggest don't tap-to-place -> Add OFF (placeholder methods)
                    addArmed = false
                }
                MethodChip("Suggest", selectedMethod == ROUTE_METHOD_SUGGEST, false, false) {
                    addArmed = false
                }
            }

            Text("BUILD  ($vertexCount pts)", color = if (building) rtTxtD else rtDis, fontSize = 9.sp, fontFamily = rtMono)
            // Segmented Route|Artifact switch: BOTH halves visible, ACTIVE half
            // reverse-video (filled block + knockout text) so it reads on a B/W
            // device. Route = addArmed true (taps place vertices);
            // Artifact = addArmed false (taps do artifact/waypoint).
            Row(modifier = Modifier.fillMaxWidth()) {
                SegHalf("Route",    addArmed && building, building, Modifier.weight(1f)) {
                    if (building && !addArmed) { addArmed = true;  onAddModeChanged(true) }
                }
                SegHalf("Artifact", !addArmed && building, building, Modifier.weight(1f)) {
                    if (building && addArmed) { addArmed = false; onAddModeChanged(false) }
                }
            }
            // Undo on its own row, centered.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                BuildBtn("Undo", if (building) rtBlue else rtDis, Modifier.width(120.dp)) { if (building) onUndo() }
            }
            } // end if(!minimized)

            // -- Exit row: Save + Discard ALWAYS visible (single level, survives
            //    collapse -- frequently-used exits per pact). --
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                // SAVE -- adaptive (per UI PACT / RouteSaveDiscard_Rules 2026-06-04):
                //   New, >=2 pts     -> Save as completed: buildWktAndBbox -> insertRoute (DB row) + delete draft if any [graduation]
                //   New              -> Save in-progress: create draft JSON          [soon - draft I/O pass]
                //   Resumed, >=2 pts -> Save as completed (same graduation)
                //   Resumed          -> Save with changes: overwrite draft JSON      [soon - draft I/O pass]
                //   ONLY writes: create draft JSON / overwrite draft JSON / insertRoute(+delete draft).
                //   FIRST-PASS LIVE leg: Save as completed only (the onSaveCompleted callback).
                BuildBtn(
                    "Save",
                    if (building) rtGreen else rtDis,
                    Modifier.weight(1f)
                ) { if (building) onSaveRequested() }
                // DISCARD -- adaptive (per pact):
                //   New (no draft on disk) -> remove unsaved route from screen; NO write (onExit clears)
                //   Resumed                -> PROMPT: [Roll back to original] (drop session changes, keep draft; NO write)
                //                                     | [Delete entire in-progress] (remove draft JSON) [soon - draft I/O pass]
                //   NEVER writes on: roll back, discard, remove-unsaved.
                //   FIRST-PASS LIVE leg: remove-unsaved (what onExit does today).
                BuildBtn("Discard", rtRed, Modifier.weight(1f)) {
                    building = false; addArmed = true
                    if (routeLifecycleState == ROUTE_LS_RESUMED) onDiscardRequested() else onExit()
                }
            }
        }
    }
}

@Composable
private fun MethodChip(label: String, selected: Boolean, live: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(5.dp),
        color = if (selected) rtSelBg else rtRowBg
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (selected) rtGreen else rtTxtD,
                fontSize = 10.sp, fontFamily = rtMono, fontWeight = FontWeight.Bold)
            Text(if (live) "live" else "soon",
                color = if (live) rtGreen else rtAmber,
                fontSize = 8.sp, fontFamily = rtMono)
        }
    }
}

@Composable
private fun SegHalf(
    label: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Reverse-video active state: active -> filled bg + knockout (bg-colored) text.
    val bg = if (!enabled) rtRowBg else if (active) rtTxtD else rtRowBg
    val fg = if (!enabled) rtDis   else if (active) rtPanelBg else rtTxtD
    Surface(
        modifier = modifier.clickable(enabled = enabled) { onClick() },
        color = bg,
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, color = fg, fontSize = 11.sp, fontFamily = rtMono, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BuildBtn(label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(5.dp),
        color = rtRowBg
    ) {
        Text(label, color = tint, fontSize = 10.sp, fontFamily = rtMono,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp))
    }
}
