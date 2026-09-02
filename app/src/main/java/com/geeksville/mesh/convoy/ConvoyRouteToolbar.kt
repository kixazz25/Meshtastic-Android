package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
// ROUTECLOSE-2026-09-02: background, border and clip for the X's box. ⭐ The
// patch's own import check caught all three MISSING and refused to write --
// which is the locked-notes rule working as an assertion instead of a memory.
// Three compile failures are on record from patches that named their missing
// import in their own output and shipped anyway.
import androidx.compose.ui.draw.clip
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
    // ARMSTATE-2026-08-13F: THE REAL ARMED STATE, passed in.
    //
    // This was a private `remember` initialised to true, so the toolbar could
    // show Draw lit while the map had stopped accepting taps. The buttons now
    // both REFLECT this value and TOGGLE it through onAddModeChanged - one
    // value, read by the display and by the logic, with nothing holding a copy.
    addArmed: Boolean = false,
    onUndo: () -> Unit = {},
    onSaveCompleted: () -> Unit = {},
    onExit: () -> Unit = {},
    // -- route lifecycle (Layer 2) --
    routeLifecycleState: Int = ROUTE_LS_NEW,
    /**
     * ROUTEMETHOD-2026-08-23R: the rider tapped the collapsed METHOD row with a route
     * underway. The CALLER raises save-or-discard -- this component is stateless
     * and has two call sites, so it must not own a dialog.
     * Default no-op so the frozen ConvoyScreen call site needs no edit.
     */
    onMethodLockedTap: () -> Unit = {},
    onSaveRequested: () -> Unit = {},
    onDiscardRequested: () -> Unit = {},
    onSelectInProgress: () -> Unit = {},
    /**
     * ROUTECLOSE-2026-09-02: CLOSE THE TOOL. Not discard.
     *
     * ⛔ Fred, 09-02: "I can exit with discard but it is not intuitive if you
     * are not doing anything with the tool. We are not discarding."
     * Opening Route+, looking at it, and wanting out is not throwing work away
     * -- and the only exit said DISCARD, which is a different and frightening
     * word.
     *
     * ⚠ THIS DOES NOT TEAR ANYTHING DOWN. Fred: "the restart functions protect
     * anything left open -- it is built to handle a cancel or a crash and
     * recover." So the X closes the panel and turns Route+ off; whatever is on
     * disk stays on disk and the resume path finds it.
     *
     * ⭐ No-op default, the same trick onMethodLockedTap uses, so the frozen
     * ConvoyScreen call site needs no edit.
     */
    onClose: () -> Unit = {},
    routeEntryNonce: Int = 0,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var building by remember { mutableStateOf(true) }   // toolbar only opens post-entry-choice; build controls live on open
    var minimized by remember { mutableStateOf(false) }
    // ROUTEMETHOD-2026-08-23R: the METHOD row folds itself once a vertex exists -- the
    // method is committed at that point and cannot change without save/discard.
    // ⚠ SEPARATE from `minimized` above, which is rider-driven and folds the
    // WHOLE panel. This one is action-driven and folds one row.
    var methodOpen by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(vertexCount > 0) {
        if (vertexCount > 0) methodOpen = false
    }
    // A new route session re-opens it -- routeEntryNonce already signals that.
    androidx.compose.runtime.LaunchedEffect(routeEntryNonce) { methodOpen = true }
    // Add button armed-state (pact): GREEN = ON (taps place points),
    // RED = OFF (taps pan/reposition). ON on entry for Point; selecting
    // Draw/Suggest sets it OFF (those methods don't tap-to-place).
    // ARMSTATE-2026-08-13F: the private copy is gone - addArmed is a parameter.
    // Re-arm build controls on every route-mode entry (NEW / RESUMED / future
    // extend). Without this, building stays false from a prior exit (line ~174)
    // and a resumed route can place points but cannot Undo/Save.
    // ARMSTATE-2026-08-13F: armed the DISPLAY on route entry without telling the
    // planner. The arm sites set the real value now.
    androidx.compose.runtime.LaunchedEffect(routeEntryNonce) { building = true }
    Surface(
        modifier = modifier
            // SUMMARY-2026-08-24I: was .width(248.dp), a hard number set before
            // ConvoyArtifactsPanel existed. That panel uses a RANGE, and it is
            // the width Fred remembers this one having. Same modifier and the
            // same numbers, so the two can no longer drift apart.
            .widthIn(min = 280.dp, max = 340.dp)
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
                // ROUTECLOSE-2026-09-02: the accordion chevron moves to the LEFT
                // corner (Fred, 09-02), beside the grip. ⭐ It leaves the right
                // corner free for the X -- and the right corner is where a
                // close belongs, so the two controls stop competing for it.
                Text(if (minimized) "v" else "^", color = rtTxtD, fontSize = 11.sp,
                    fontFamily = rtMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { minimized = !minimized }.padding(end = 6.dp))
                Text(if (minimized) "ROUTE + ($vertexCount pts)" else "ROUTE +",
                    color = rtPurple, fontSize = 12.sp,
                    fontFamily = rtMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable { minimized = !minimized })
                // ROUTECLOSE-2026-09-02: boxed so it reads as a BUTTON. ⚠ Twice
                // today a close control existed and was invisible -- Map Keys
                // and Map Features both had one at 10sp, unboxed, the same
                // colour as the text beside it. A close nobody can see is the
                // same as no close.
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(start = 8.dp)
                        .width(26.dp).height(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2A1B22))
                        .border(1.dp, Color(0xFF6B4A55), RoundedCornerShape(4.dp))
                        .clickable { onClose() }
                ) {
                    Text("\u2715", color = Color(0xFFE6EDF3), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold)
                }
            }

            // Entry buttons removed: New-vs-In-Progress is chosen at +ROUTE (entry prompt)
            // BEFORE the toolbar opens, so the toolbar starts in build mode.
            if (!minimized) {
            // ROUTEMETHOD-2026-08-23R: collapsed once a point is down. The header still
            // says WHICH method is active -- a fold should not lose information.
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    if (!methodOpen) {
                        if (vertexCount > 0) onMethodLockedTap() else methodOpen = true
                    }
                },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("METHOD", color = if (building) rtTxtD else rtDis,
                    fontSize = 9.sp, fontFamily = rtMono)
                if (!methodOpen) {
                    Text("  \u00b7  " + (if (selectedMethod == ROUTE_METHOD_SUGGEST)
                            "AI design" else "Drop points"),
                        color = rtGreen, fontSize = 9.sp, fontFamily = rtMono,
                        modifier = Modifier.weight(1f))
                    Text("\u25b8", color = rtTxtD, fontSize = 10.sp, fontFamily = rtMono)
                }
            }
            if (methodOpen) {
            // ROUTEPANEL-2026-08-23O: two methods, not three. DRAW is gone from this
            // panel -- Fred 08-23. ⚠ The TOOL is not gone: Import Trails -> BY AREA
            // on the download panel still needs box drawing. Only this entry to it.
            //
            // The labels now say WHO BUILDS THE ROUTE rather than naming a thing.
            // "Point / Draw / Suggest" left a rider to work out what each did.
            // ROUTEBAR-2026-08-24F: fillMaxWidth() was missing HERE and present on
            // every other Row in this panel, so these two chips wrapped to their
            // own labels instead of splitting the panel width -- which is why they
            // stopped lining up with the heading.
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MethodChip("DROP POINTS &\nBUILD YOURSELF",
                           selectedMethod == ROUTE_METHOD_P2P, building,
                           Modifier.weight(1f)) {
                    // tap-to-place -> Add ON
                    if (building) { onSelectMethod(ROUTE_METHOD_P2P); onAddModeChanged(true) }
                }
                MethodChip("AI DESIGN w/\nRIDER GUIDANCE",
                           selectedMethod == ROUTE_METHOD_SUGGEST, building,
                           Modifier.weight(1f)) {
                    // ROUTEBAR-2026-08-24F: NO LONGER DISARMS.
                    //
                    // This called onAddModeChanged(false), and that is the
                    // `ROUTEMODE -> false` logged 1.3s after +ROUTE armed it. With
                    // route mode off the JS artifact handlers stop returning early,
                    // so a tap opened a popup and never reached onProximityTap --
                    // which is where the guided flow selects a trailhead.
                    //
                    // The original reason was sound: in AI mode nothing is drawn by
                    // hand, so taps were better spent reading trail names. What
                    // changed is that AI mode now OPENS with a step that needs taps
                    // to reach Kotlin.
                    //
                    // ⭐ The behaviour moves to the rider rather than being deleted.
                    // The BUILD row below is visible again in this mode, so
                    // "MAP TAPS ON" is one tap away whenever they want to research
                    // the map instead of place something.
                    if (building) { onSelectMethod(ROUTE_METHOD_SUGGEST) }
                }
            }
            } // ROUTEMETHOD-2026-08-23R: end if(methodOpen)

            // ROUTEPANEL-2026-08-23O hid this row in AI mode. Fred 08-23: "I would
            // only show draw or popup when in user control mode." Correct then --
            // in AI mode nothing was being drawn, so neither control had a meaning
            // to offer.
            //
            // ROUTEBAR-2026-08-24F: SHOWN AGAIN, in every mode. Pin collection gave
            // a tap a third meaning -- select the trailhead the checklist is asking
            // for -- and the control governing that was invisible. A panel that
            // reports a state it does not display is worse than a panel with one
            // more row on it.
            //
            // ⚠ WORKAROUND, BY AGREEMENT. Fred 08-24: a floating toggle is the
            // preferred home. When it is built it REPLACES this row. "IT CANNOT BE
            // BOTH" -- two controls on one flag is exactly how they come to
            // disagree.
            Text("BUILD  ($vertexCount pts)", color = if (building) rtTxtD else rtDis, fontSize = 9.sp, fontFamily = rtMono)
            // Segmented switch: BOTH halves visible, ACTIVE half reverse-video
            // (filled block + knockout text) so it reads on a B/W device.
            // ROUTEPANEL-2026-08-23O: labels now say what a TAP DOES rather than naming a
            // thing. "Route / Artifact" named the targets; a rider had to infer the
            // behaviour. addArmed true = taps place vertices; false = taps open
            // artifact popups.
            Row(modifier = Modifier.fillMaxWidth()) {
                // ROUTEAI-2026-08-23Q: shorter, and they read as STATES rather than
                // actions -- which is what a segmented toggle is.
                SegHalf("DRAW ROUTE ON", addArmed && building, building, Modifier.weight(1f)) {
                    if (building && !addArmed) { onAddModeChanged(true) }
                }
                SegHalf("MAP TAPS ON",   !addArmed && building, building, Modifier.weight(1f)) {
                    if (building && addArmed) { onAddModeChanged(false) }
                }
            }
            // ROUTEBAR-2026-08-24F: end of the removed if(method != SUGGEST)
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
                    // ARMSTATE-2026-08-13F: closing no longer re-arms a display flag.
                    building = false
                    if (routeLifecycleState == ROUTE_LS_RESUMED) onDiscardRequested() else onExit()
                }
            }
        }
    }
}

@Composable
// CHIPLIVE-2026-08-24J2: `live` removed. It marked a method that did not work
// yet; both work now, so it printed the same word under both chips and cost a
// line of height. The PARAMETER goes with the label -- an unused flag both
// callers pass `true` to is one someone re-wires later believing it means
// something.
private fun MethodChip(label: String, selected: Boolean, enabled: Boolean,
                       modifier: Modifier = Modifier, onClick: () -> Unit) {
    // CLEANUP-2026-08-24H: takes a Modifier now, and CHAINS it rather than
    // starting from a fresh one. Without this the chip cannot be given a
    // weight, so it sizes itself from its own two-line label -- which is why
    // ROUTEBAR-F's fillMaxWidth() on the parent Row did not widen anything and
    // the panel stretched vertically instead.
    Surface(
        modifier = modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(5.dp),
        color = if (selected) rtSelBg else rtRowBg
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (selected) rtGreen else rtTxtD,
                fontSize = 10.sp, fontFamily = rtMono, fontWeight = FontWeight.Bold)
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
