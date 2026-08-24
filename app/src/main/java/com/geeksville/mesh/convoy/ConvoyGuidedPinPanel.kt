package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
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

/**
 * ConvoyGuidedPinPanel -- GUIDEDPIN-2026-08-24A
 *
 * The checklist that guides a rider through picking a trailhead and starting an
 * AI route search. Fred, 08-24: "it is a guided process. The checklist is a
 * controlling feature -- it explains where you are in the process and what is
 * left."
 *
 * THIS FILE IS PRESENTATION ONLY. It owns no state, touches no database, and
 * knows nothing about waypoints, the map, or the search. Every value it shows
 * arrives as a parameter and every action it offers leaves as a callback. That
 * is deliberate: the caller (ConvoyMapViewerScreen) already owns the map, the
 * WebView bridge and pendingWaypoint, and this panel must not grow a second
 * copy of any of it.
 *
 * THE CHECKLIST IS NOT A RECEIPT. It shows every step -- done, current, and
 * still to come -- so the rider always knows how much further there is to go:
 *
 *   [x]  1. Navigate to your area        <- done, shows its ANSWER
 *            Panguitch, UT
 *   [>]  2. Set your trailhead           <- current, shows its INSTRUCTION
 *            Long press at the start of the trail...
 *   [ ]  3. Does the ride return there?  <- remaining, TITLE ONLY
 *
 * Completed steps carry their answer, which is the verification. A long press
 * that did not register is otherwise invisible -- the rider presses again and
 * gets two waypoints. Remaining steps carry a title and nothing else, which is
 * what keeps the panel short when Phase 2 adds include points: that step will
 * report "3 places included", not ten lines.
 *
 * PHASE 1 IS THREE STEPS. Phase 2 fills STUB:ENDPOINT and adds a fourth. The
 * step list is a parameter, so adding a step is a change at the CALL SITE and
 * not in here.
 *
 * AUTO OPEN / AUTO COLLAPSE is the caller's decision too, for the same reason:
 * the caller knows when a step advanced and when something unexpected happened.
 * This panel just renders `expanded` and reports taps on the header.
 */

// Palette copied from ConvoyAiDesignPanel.kt so the two panels are visually one
// surface. NOT imported -- those are private to that file. If a third panel
// needs them, extract to a shared file then; two copies is not yet a pattern.
private val gpBg    = Color(0xFF0F1419)
private val gpPanel = Color(0xFF131A24)
private val gpCard  = Color(0xFF0F1720)
private val gpLine  = Color(0xFF24313F)
private val gpGreen = Color(0xFF7BB661)
private val gpBlue  = Color(0xFF4DA6FF)
private val gpAmber = Color(0xFFE3B341)
private val gpTxt   = Color(0xFFE6EDF3)
private val gpDim   = Color(0xFF8899AA)
private val gpFaint = Color(0xFF667788)
private val gpMono  = FontFamily.Monospace

const val GP_STATE_TODO    = 0
const val GP_STATE_CURRENT = 1
const val GP_STATE_DONE    = 2

/**
 * One line of the checklist.
 *
 * @param title      always shown, in every state.
 * @param instruction shown only while CURRENT. What to do, in plain language.
 * @param answer     shown only when DONE. What the rider chose or created --
 *                   this is the verification, so it must be the real value
 *                   (a waypoint name, a place name, a count) and never a
 *                   generic "completed".
 */
data class GuidedStep(
    val title: String,
    val instruction: String = "",
    val answer: String = "",
    val state: Int = GP_STATE_TODO
)

/**
 * @param steps        the whole process, in order. Phase 1 passes three.
 * @param expanded     caller-owned. See the note above on auto-open.
 * @param onToggle     header tapped.
 * @param onStartOver  always offered, on every step. Fred, 08-24: every step
 *                     has exactly two exits, PROCEED or START OVER. There is no
 *                     BACK and no per-step redo -- every edit path is a branch
 *                     and branches go stale.
 * @param actions      the buttons for the CURRENT step. Empty means the step is
 *                     waiting on a map gesture rather than a tap, which is the
 *                     normal case for the trailhead step.
 * @param notice       an unexpected result the rider must see -- no routable
 *                     trail nearby, an existing trailhead used instead of a new
 *                     one. Rendered in amber above the actions. The caller
 *                     force-expands when it sets this: a warning nobody sees is
 *                     worse than a panel that reappears.
 */
@Composable
fun ConvoyGuidedPinPanel(
    steps: List<GuidedStep>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStartOver: () -> Unit,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    notice: String = "",
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return

    val currentIdx = steps.indexOfFirst { it.state == GP_STATE_CURRENT }
    val stepNo = if (currentIdx >= 0) currentIdx + 1 else steps.size
    val currentTitle = if (currentIdx >= 0) steps[currentIdx].title else ""

    Surface(
        color = gpPanel,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {

            // ---- header: progress at a glance, survives collapse ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "\u25BE" else "\u25B8",
                    color = gpFaint, fontSize = 12.sp, fontFamily = gpMono,
                    modifier = Modifier.padding(end = 9.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "DESIGNING YOUR RIDE",
                        color = gpTxt, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp
                    )
                    if (!expanded && currentTitle.isNotBlank()) {
                        Text(
                            currentTitle,
                            color = gpDim, fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Text(
                    "step $stepNo of ${steps.size}",
                    color = gpBlue, fontSize = 10.5.sp, fontFamily = gpMono
                )
            }

            if (expanded) {
                Spacer(
                    Modifier.fillMaxWidth().height(1.dp).background(gpLine)
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 13.dp, vertical = 10.dp)
                ) {
                    steps.forEachIndexed { i, s -> GuidedStepRow(i + 1, s) }
                }
            }

            if (notice.isNotBlank()) NoticeBlock(notice)

            // ---- actions: current step's buttons, then START OVER ----
            Column(Modifier.fillMaxWidth().padding(13.dp, 4.dp, 13.dp, 13.dp)) {
                actions.forEach { (label, onClick) ->
                    GpButton(label, primary = true, onClick = onClick)
                    Spacer(Modifier.height(7.dp))
                }
                GpButton("START OVER", primary = false, onClick = onStartOver)
            }
        }
    }
}

@Composable
private fun GuidedStepRow(number: Int, s: GuidedStep) {
    val marker = when (s.state) {
        GP_STATE_DONE    -> "[x]"
        GP_STATE_CURRENT -> "[>]"
        else             -> "[ ]"
    }
    val markerColor = when (s.state) {
        GP_STATE_DONE    -> gpGreen
        GP_STATE_CURRENT -> gpBlue
        else             -> gpFaint
    }
    val titleColor = when (s.state) {
        GP_STATE_CURRENT -> gpTxt
        GP_STATE_DONE    -> gpDim
        else             -> gpFaint
    }

    Row(Modifier.fillMaxWidth().padding(bottom = 11.dp)) {
        Text(
            marker,
            color = markerColor, fontSize = 12.sp, fontFamily = gpMono,
            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                "$number. ${s.title}",
                color = titleColor, fontSize = 13.sp,
                fontWeight = if (s.state == GP_STATE_CURRENT)
                    FontWeight.Bold else FontWeight.Normal
            )
            // CURRENT shows how to do it. DONE shows what was done. TODO shows
            // neither -- that is what keeps the list short.
            if (s.state == GP_STATE_CURRENT && s.instruction.isNotBlank()) {
                Text(
                    s.instruction,
                    color = gpDim, fontSize = 11.8.sp, lineHeight = 16.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            if (s.state == GP_STATE_DONE && s.answer.isNotBlank()) {
                Text(
                    s.answer,
                    color = gpGreen, fontSize = 11.8.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun NoticeBlock(text: String) {
    Surface(
        color = Color(0xFF241D0E),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().padding(13.dp, 2.dp, 13.dp, 0.dp)
    ) {
        Row(Modifier.padding(11.dp)) {
            Text(
                "!", color = gpAmber, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, fontFamily = gpMono,
                modifier = Modifier.padding(end = 9.dp)
            )
            Text(text, color = gpAmber, fontSize = 11.8.sp, lineHeight = 16.5.sp)
        }
    }
}

@Composable
private fun GpButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (primary) Color(0xFF16301C) else gpCard,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Text(
            label,
            color = if (primary) gpGreen else gpDim,
            fontSize = 12.5.sp, fontFamily = gpMono,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 1.1.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)
        )
    }
}
