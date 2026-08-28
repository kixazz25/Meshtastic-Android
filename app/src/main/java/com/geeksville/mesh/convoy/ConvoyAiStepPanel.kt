package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * AISTEPS-2026-08-28 — the front of the AI flow: overview, then parameters.
 *
 * These two REPLACE the AI design panel's PHASE_SETUP. They are full screen and
 * carry no map, because neither is map work — Fred, 08-28: "we are not at the
 * map point in step two, we are setting end state parameters for the ride
 * creation. Maps are a distraction."
 *
 * ⭐ ONE FUNCTION FOR BOTH. The two steps differ in their words and in whether
 * they carry sliders; everything else — the header, the step counter, the
 * scroll, the two buttons — is identical. Building them as two composables
 * would be two copies of one layout, which is the disease the overriding rule
 * exists to prevent.
 *
 * ⚠ WHAT IS DELIBERATELY NOT HERE: no guided-tour mode, no per-step teaching
 * variants, no app-wide "show me how" flag. Fred, 08-28: "guided tour belongs
 * in the new quickstart." The checkbox on step 1 means exactly what it says and
 * nothing more.
 */

private val stPanel = Color(0xFF0F1216)
private val stCard  = Color(0xFF131820)
private val stInk   = Color(0xFFE6EDF3)
private val stDim   = Color(0xFF9AA4B2)
private val stBlue  = Color(0xFF4DA6FF)
private val stGreen = Color(0xFF4ADE80)
private val stTrack = Color(0xFF2A3038)
private val stMuted = Color(0xFF5A6472)
private val stMono  = FontFamily.Monospace

/**
 * @param step          AI_STEP_WELCOME or AI_STEP_DISTANCE.
 * @param dontShowAgain step 1 only — the checkbox state.
 * @param hoursText     step 2 only — the live duration, already rounded.
 */
@Composable
fun ConvoyAiStepPanel(
    /* AISTEPS-DECOUPLE-2026-08-28: the panel does NOT take a pinStep value.
     * ⭐ Which of the two screens to draw, and how far through the flow the
     * rider is, are facts the SCREEN knows. Passing the state constant in would
     * couple this UI to a machine it has no business reading — and would break
     * it again when the remaining checklist steps are ported and the numbering
     * shifts. */
    isOverview: Boolean,
    stepLabel: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    dontShowAgain: Boolean = false,
    onDontShowAgain: (Boolean) -> Unit = {},
    miLow: Int = 0,
    miHigh: Int = 0,
    mphLow: Int = 0,
    mphHigh: Int = 0,
    onMiles: (Int, Int) -> Unit = { _, _ -> },
    onSpeed: (Int, Int) -> Unit = { _, _ -> },
    hoursText: String = "",
) {
    Surface(modifier = modifier, color = stPanel) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isOverview) "AI ROUTE PLANNER" else "HOW LONG A DAY?",
                    color = stBlue, fontSize = 12.sp, fontFamily = stMono,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Text(stepLabel, color = stDim, fontSize = 11.sp, fontFamily = stMono)
            }
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
            ) {
                if (isOverview) {
                    Body("Together we develop the criteria to build six rides that " +
                        "satisfy your stated requirements. Your answers provide " +
                        "much-needed focus while I analyse the area's trails \u2014 up " +
                        "to 10,000 in some areas \u2014 for every route I develop.")
                    /* ⛔ THE JUSTIFICATION FOR DISCARDING FIVE RIDES, said before the
                     * rider commits rather than after they are asked to throw work
                     * away. ⚠ The save panel repeats it, so a rider who dismisses
                     * this overview is not caught out. */
                    Body("At the end you compare the six side by side, each drawn in " +
                        "its own colour, and keep the ones you want. The rest are " +
                        "deleted \u2014 but the values used to build them are saved " +
                        "with the routes you keep, and can generate six again at any " +
                        "time.")
                    // ⚠ so the standard download prompt after saving is not a surprise
                    Body("When you save, I will offer to download the map tiles along " +
                        "your routes so the ground you plan to ride is on the device " +
                        "before you leave.")
                    Body("Search for a town, region or feature, or open one of your own " +
                        "tracks or waypoints. Centre the map where you will start. Use " +
                        "Map Features to turn layers off or pick individual tracks " +
                        "until you can see the ground you want.")
                    Note("Planning stays in portrait throughout \u2014 the comparison " +
                        "needs the height.")
                } else {
                    Body("Set a range of distance and an average speed. Together they " +
                        "calculate a ride duration range. Throttle the mileage up or " +
                        "down until the duration matches the window of time you want " +
                        "to spend on the trail this ride.")
                    Spacer(Modifier.height(6.dp))

                    SliderRow("SHORTEST RIDE", "$miLow mi", miLow.toFloat(), 5f..200f,
                        stBlue) { v ->
                        // ⚠ the pair must not cross
                        onMiles(v.roundToInt().coerceAtMost(miHigh - 5), miHigh)
                    }
                    SliderRow("FURTHEST RIDE", "$miHigh mi", miHigh.toFloat(), 5f..200f,
                        stBlue) { v ->
                        onMiles(miLow, v.roundToInt().coerceAtLeast(miLow + 5))
                    }
                    /* ⭐ SPEED IS GREY, NOT BLUE. It reads as shown-not-yours while
                     * staying adjustable — matching the caution below it. Halving it
                     * makes the same mileage an impossible day. */
                    SliderRow("AVERAGE SPEED", "$mphLow \u2013 $mphHigh mph",
                        mphLow.toFloat(), 3f..40f, stMuted) { v ->
                        onSpeed(v.roundToInt().coerceAtMost(mphHigh - 1), mphHigh)
                    }
                    SliderRow("", "", mphHigh.toFloat(), 3f..40f, stMuted) { v ->
                        onSpeed(mphLow, v.roundToInt().coerceAtLeast(mphLow + 1))
                    }

                    /* ⭐ THE NUMBER THEY ARE AIMING AT, and it moves as they drag —
                     * "throttle until the duration matches" is not an instruction
                     * anyone can follow against a static figure. */
                    Surface(shape = RoundedCornerShape(9.dp), color = stCard,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Column(Modifier.padding(14.dp, 12.dp)) {
                            Text("RIDE DURATION", color = stDim, fontSize = 11.sp,
                                fontFamily = stMono)
                            Spacer(Modifier.height(4.dp))
                            Text(hoursText, color = stGreen, fontSize = 26.sp,
                                fontFamily = stMono, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Note("Leave the average speed alone until you have a few rides " +
                        "behind you. It is set from measured riding on this kind of " +
                        "ground, and changing it changes every estimate I make.")
                }
            }

            if (isOverview) {
                Surface(shape = RoundedCornerShape(8.dp), color = stCard,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onDontShowAgain(!dontShowAgain) }
                            .padding(12.dp, 11.dp)
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (dontShowAgain) stGreen else stCard),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!dontShowAgain) {
                                Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                                    .background(stMuted))
                                Box(Modifier.size(13.dp).clip(RoundedCornerShape(2.dp))
                                    .background(stCard))
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        Text("I understand this process \u2014 do not show the overview " +
                            "panel again.", color = stDim, fontSize = 12.5.sp,
                            lineHeight = 17.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StepButton("CANCEL", stDim, Color(0xFF1D2430), Modifier.weight(1f),
                    onCancel)
                StepButton(if (isOverview) "PROCEED" else "CONTINUE",
                    stGreen, Color(0xFF14532D), Modifier.weight(2f), onContinue)
            }
        }
    }
}

@Composable
private fun Body(t: String) {
    Text(t, color = stInk, fontSize = 14.sp, lineHeight = 21.sp,
        modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun Note(t: String) {
    Text(t, color = stDim, fontSize = 13.sp, lineHeight = 19.sp,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SliderRow(
    label: String, value: String, pos: Float, range: ClosedFloatingPointRange<Float>,
    tint: Color, onChange: (Float) -> Unit,
) {
    if (label.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
            Text(label, color = stDim, fontSize = 11.sp, fontFamily = stMono,
                modifier = Modifier.weight(1f))
            Text(value, color = tint, fontSize = 13.sp, fontFamily = stMono)
        }
    }
    Slider(
        value = pos, onValueChange = onChange, valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = tint, activeTrackColor = tint, inactiveTrackColor = stTrack
        ),
        modifier = Modifier.fillMaxWidth().height(28.dp)
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun StepButton(
    label: String, tint: Color, bg: Color, modifier: Modifier, onClick: () -> Unit,
) {
    Surface(modifier = modifier.height(42.dp), shape = RoundedCornerShape(7.dp),
        color = bg) {
        Box(contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().clickable { onClick() }) {
            Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
