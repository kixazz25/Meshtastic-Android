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
 * SUMMARY-2026-08-24I -- the decision, and then the work.
 *
 * Shown once the checklist is answered. Scrim plus a centred card, because
 * this is the last thing between the rider and a build that takes real time
 * and it deserves to be read rather than skimmed past in a bottom bar.
 *
 * ONE PANEL, TWO STATES:
 *   working = false   the prose, PROCEED and START OVER
 *   working = true    the same prose, a spinner and the build's own progress,
 *                     and NO BUTTONS
 *
 * Dropping the buttons while it works is deliberate. Fred, 08-24: "start over
 * shows on the bottom with no idea what is happening." A control offered
 * during work that cannot safely be taken is worse than no control.
 *
 * Presentation only, like everything else in this file: the prose arrives as
 * a string, the actions leave as callbacks. It does not know what a route is.
 */
@Composable
fun ConvoyGuidedSummaryPanel(
    title: String,
    body: String,
    working: Boolean,
    progress: String,
    onProceed: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xCC090C10)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = gpPanel,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).padding(18.dp)
        ) {
            Column(Modifier.padding(18.dp, 16.dp)) {

                Text(
                    if (working) "BUILDING YOUR RIDES" else "HERE IS THE PLAN",
                    color = if (working) gpBlue else gpGreen,
                    fontSize = 11.sp, fontFamily = gpMono,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp
                )
                if (title.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(title, color = gpDim, fontSize = 11.5.sp)
                }
                Spacer(Modifier.height(12.dp))

                Column(
                    Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(body, color = gpTxt, fontSize = 13.sp, lineHeight = 19.sp)
                }

                if (working) {
                    Spacer(Modifier.height(16.dp))
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(gpLine))
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = gpBlue,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(11.dp))
                        Text(
                            progress.ifBlank { "Working\u2026" },
                            color = gpBlue, fontSize = 12.sp, fontFamily = gpMono
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This can take a minute on ground the app has not seen before.",
                        color = gpFaint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                } else {
                    Spacer(Modifier.height(18.dp))
                    GpButton("PROCEED", primary = true, onClick = onProceed)
                    Spacer(Modifier.height(7.dp))
                    GpButton("START OVER", primary = false, onClick = onStartOver)
                }
            }
        }
    }
}

/**
 * ROUTEASSIST-2026-08-25C -- the Target Distance HUD.
 *
 * Modelled on the ride map's RECORDING readout, ConvoyScreen.kt:1390-1406:
 * a label, a big number, a small "mi", all Color(0xFFFF0000) at 0.75 alpha,
 * right-aligned, no card behind it. Fred, 08-25: "big transparent and tucked
 * out of sight."
 *
 * ⛔ The convoy map is FROZEN and is not touched. Same TREATMENT, no shared
 * code -- one call site on frozen code becoming two is how this project
 * acquired a second saveCompleted.
 *
 * ⭐ The alpha is doing real work: at 0.75 the trail UNDER the number stays
 * visible, and the rider is reading it while deciding where to tap next.
 *
 * ⭐ THE COUNT IS NOT HERE. "3 of 10 places" is an EVENT, not a status -- it
 * matters at the moment a pin lands and not for the ten seconds after. It
 * toasts on each drop instead of taking a permanent line.
 *
 * Presentation only. Handed numbers, hands back taps.
 */
@Composable
fun ConvoyPinMileageHud(
    floorMiles: Double,
    overMiles: Double,
    underMiles: Double,
    floorBand: Int,
    ceiling: Int,
    problem: String,
    busy: Boolean,
    onCeilingChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val red = Color(0xFFFF0000).copy(alpha = 0.75f)

    // ⭐ BLINK ONLY WHEN IT IS EXCEEDED. A readout that always blinks is
    // wallpaper within a minute; one that starts blinking is an event. Driven
    // by a LaunchedEffect keyed on the condition, so it does not run at all
    // while the ride fits.
    var blinkOn by remember { mutableStateOf(true) }
    val exceeded = overMiles > 0.0
    LaunchedEffect(exceeded) {
        blinkOn = true
        while (exceeded) {
            kotlinx.coroutines.delay(650)
            blinkOn = !blinkOn
        }
    }

    Column(
        modifier = modifier.padding(end = 14.dp, top = 10.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            "Target Distance  " + floorBand + "\u2013" + ceiling + " mi",
            color = red, fontSize = 11.sp, fontWeight = FontWeight.Bold
        )

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (busy) "\u2026" else "%.1f".format(floorMiles),
                color = red, fontSize = 44.sp, fontWeight = FontWeight.Black,
                lineHeight = 44.sp
            )
            Text(
                " mi", color = red, fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // THREE STATES, because assess() reports three.
        when {
            exceeded -> {
                Text(
                    "REMOVE POINT(S) \u2014 MILEAGE EXCEEDED",
                    color = if (blinkOn) red else Color.Transparent,
                    fontSize = 12.sp, fontWeight = FontWeight.Black
                )
                Text(
                    "or Start Over to drop points again",
                    color = red, fontSize = 11.sp
                )
            }
            underMiles > 0.0 -> Text(
                "Fits, %.0f mi to spare".format(underMiles),
                color = red, fontSize = 11.5.sp
            )
            else -> Text("Fits your ride", color = red, fontSize = 11.5.sp)
        }

        if (problem.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(problem, color = red, fontSize = 11.sp, lineHeight = 14.sp)
        }

        Spacer(Modifier.height(6.dp))

        // The other half of the trade. A rider seeing 93 miles may raise the
        // ceiling rather than drop a point, and cannot if the control was left
        // behind on the setup panel.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "\u2212", color = red, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
                    .clickable { onCeilingChange(-5) }
            )
            Text("up to " + ceiling + " mi", color = red, fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
            Text(
                "+", color = red, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
                    .clickable { onCeilingChange(5) }
            )
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
/**
 * AISTEPSCREEN-2026-08-28 — ONE SCREEN PER STEP.
 *
 * A drop-in for ConvoyGuidedPinPanel at the same call site, taking the same
 * inputs. The difference is what it draws: the CURRENT step only, full screen,
 * with its own buttons — not a scrolling list of seven rows in a half-width
 * panel with a header above them.
 *
 * ⭐ WHY THE HALF-WIDTH RULE NO LONGER APPLIES. That panel is 0.5f wide because
 * the space alongside was where a pin landing got reported, and a full-width
 * panel left nowhere to say it. That was true when the panel sat permanently
 * over a live map. These screens OPEN AND CLOSE around the map gesture, so the
 * map is not competing with them and the notice has the whole screen.
 *
 * ⚠ PRESENTATION ONLY, like the file it sits in. The prose arrives as a string,
 * the actions leave as callbacks. It does not know what a route is, does not
 * touch pinStep, and does not decide when it is shown.
 */
@Composable
fun ConvoyAiStepScreen(
    steps: List<GuidedStep>,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    notice: String = "",
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ⚠ the CURRENT step, or the last one — the same rule the header used
    val idx = steps.indexOfFirst { it.state == GP_STATE_CURRENT }
    val cur = if (idx >= 0) steps[idx] else steps.lastOrNull() ?: return
    val stepNo = if (idx >= 0) idx + 1 else steps.size
    /* AISTEPBANNER-2026-08-28: TWO FORMS, decided by the step itself.
     *
     * ⛔ A gesture-only step full screen is a DEAD END. The trailhead waits on
     * a map tap, so its actions list is empty, so the screen showed the
     * instruction and START OVER and nothing else — the rider is told to go to
     * the map with no way to reach it.
     *
     * ⭐ actions.isEmpty() ALREADY SAYS WHICH FORM IS RIGHT. No new state and
     * no flag: a step with buttons is a step where the rider is choosing, and
     * it takes the screen. A step without them is worked on the map, and the
     * instruction becomes a banner over it.
     */
    if (actions.isEmpty()) {
        Surface(
            color = gpPanel,
            shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(13.dp, 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cur.title.uppercase(),
                        color = gpGreen, fontSize = 11.sp, fontFamily = gpMono,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text("step $stepNo of ${steps.size}", color = gpBlue,
                        fontSize = 10.sp, fontFamily = gpMono)
                }
                Spacer(Modifier.height(5.dp))
                Text(cur.instruction, color = gpTxt, fontSize = 13.sp,
                    lineHeight = 18.sp)
                // ⚠ what has been captured, so a tap that did not register is
                // visible rather than silent
                if (cur.answer.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(cur.answer, color = gpGreen, fontSize = 13.sp)
                }
                if (notice.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    NoticeBlock(notice)
                }
                Spacer(Modifier.height(9.dp))
                GpButton("START OVER", primary = false, onClick = onStartOver)
            }
        }
        return
    }


    Box(
        modifier.fillMaxSize().background(Color(0xF2090C10)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cur.title.uppercase(),
                    color = gpGreen, fontSize = 12.sp, fontFamily = gpMono,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    modifier = Modifier.weight(1f)
                )
                Text("step $stepNo of ${steps.size}", color = gpBlue,
                    fontSize = 10.5.sp, fontFamily = gpMono)
            }
            Spacer(Modifier.height(16.dp))

            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(cur.instruction, color = gpTxt, fontSize = 14.sp,
                    lineHeight = 21.sp)

                /* ⭐ THE ANSWER IS THE VERIFICATION. Its original comment: a long
                 * press that did not register is otherwise invisible, and the
                 * rider presses again and gets two waypoints. */
                if (cur.answer.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(color = gpCard, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(13.dp, 11.dp)) {
                            Text("SO FAR", color = gpFaint, fontSize = 9.5.sp,
                                fontFamily = gpMono)
                            Spacer(Modifier.height(3.dp))
                            Text(cur.answer, color = gpGreen, fontSize = 14.sp)
                        }
                    }
                }

                /* ⚠ A WARNING NOBODY SEES IS WORSE THAN A PANEL THAT REAPPEARS —
                 * the old panel force-expanded for this. Here it simply has the
                 * room. */
                if (notice.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    NoticeBlock(notice)
                }
            }

            /* ⚠ EMPTY ACTIONS MEANS THE STEP IS WAITING ON A MAP GESTURE, which
             * is the normal case for the trailhead. The rider is told to go to
             * the map rather than left looking at a screen with no way on. */
            Spacer(Modifier.height(12.dp))
            actions.forEach { (label, onClick) ->
                GpButton(label, primary = true, onClick = onClick)
                Spacer(Modifier.height(7.dp))
            }
            // ⚠ every step has exactly two exits: proceed, or start over
            GpButton("START OVER", primary = false, onClick = onStartOver)
        }
    }
}
