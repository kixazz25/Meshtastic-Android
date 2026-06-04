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
    onAddPointModeArmed: () -> Unit = {},
    onUndo: () -> Unit = {},
    onSaveCompleted: () -> Unit = {},
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var building by remember { mutableStateOf(false) }
    var minimized by remember { mutableStateOf(false) }
    // Add button armed-state (pact): GREEN = ON (taps place points),
    // RED = OFF (taps pan/reposition). ON on entry for Point; selecting
    // Draw/Suggest sets it OFF (those methods don't tap-to-place).
    var addArmed by remember { mutableStateOf(true) }
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

            // Entry gate: build controls stay disabled until New Route is selected.
            if (!minimized) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                BuildBtn("New Route", rtPurple, Modifier.weight(1f)) {
                    building = true
                    addArmed = true
                    onSelectMethod(ROUTE_METHOD_P2P)
                    onAddPointModeArmed()
                    onNewRoute()
                }
                EntryBtnDisabled("In Progress", Modifier.weight(1f))
            }

            Text("METHOD", color = if (building) rtTxtD else rtDis, fontSize = 9.sp, fontFamily = rtMono)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MethodChip("Point", selectedMethod == ROUTE_METHOD_P2P, true, building) {
                    // Point = tap-to-place -> Add ON
                    if (building) { onSelectMethod(ROUTE_METHOD_P2P); addArmed = true; onAddPointModeArmed() }
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
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                // Add = single GREEN/RED self-toggle. Press flips armed state.
                // GREEN(ON)=taps place, RED(OFF)=taps pan. (pan/place wiring lands with snap-2.)
                BuildBtn(
                    if (addArmed) "Add ON" else "Add OFF",
                    if (!building) rtDis else if (addArmed) rtGreen else rtRed,
                    Modifier.weight(1f)
                ) { if (building) { addArmed = !addArmed; onAddPointModeArmed() } }
                BuildBtn("Undo", if (building) rtBlue else rtDis, Modifier.weight(1f)) { if (building) onUndo() }
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
                    if (vertexCount >= 2) "Save" else "Save (2+)",
                    if (building && vertexCount >= 2) rtGreen else rtDis,
                    Modifier.weight(1f)
                ) { if (building && vertexCount >= 2) onSaveCompleted() }
                // DISCARD -- adaptive (per pact):
                //   New (no draft on disk) -> remove unsaved route from screen; NO write (onExit clears)
                //   Resumed                -> PROMPT: [Roll back to original] (drop session changes, keep draft; NO write)
                //                                     | [Delete entire in-progress] (remove draft JSON) [soon - draft I/O pass]
                //   NEVER writes on: roll back, discard, remove-unsaved.
                //   FIRST-PASS LIVE leg: remove-unsaved (what onExit does today).
                BuildBtn("Discard", rtRed, Modifier.weight(1f)) {
                    building = false; addArmed = true; onExit()
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
