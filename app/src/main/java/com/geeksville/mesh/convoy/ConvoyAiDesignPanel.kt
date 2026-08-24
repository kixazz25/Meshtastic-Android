package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI DESIGN PANEL — ROUTEAI-2026-08-23
 *
 * Full-screen overlay, opened when the rider picks AI DESIGN w/ RIDER GUIDANCE
 * on the Route+ toolbar. Fred, 08-23: "should be a separate panel that closes so
 * we do not hog real estate while explaining options."
 *
 * IT IS THE SAME PANEL ON THE WAY IN AND ON THE WAY OUT.
 *   PHASE_SETUP   — mode, name, mileage, speed. Closes on FIND MY RIDES.
 *   PHASE_RESULTS — how many routes were created, their names, and that they
 *                   are WIP to be kept or discarded. CONTINUE returns to the
 *                   route in progress.
 *
 * ── WHAT THE PANEL PROMISES, AND WHY ────────────────────────────────────────
 *
 * MILEAGE AND SPEED ARE THE TWO THROTTLES. Time is always DERIVED, never set.
 * Fred, 08-23: "I suggest we always enter mileage and calc time. mileage and
 * speed are the throttles." That removes the contradiction two independent
 * inputs would allow.
 *
 * THEY ARE NOT SYMMETRIC, and the panel says so:
 *   - MILEAGE is the real constraint. Wide gives MORE CHOICES. Measured 08-22:
 *     a narrow band strangled the search and four routes collapsed to one.
 *   - SPEED changes nothing about which routes come back. It only sharpens the
 *     time estimate, so narrowing it costs nothing.
 *
 * THE SPEED ALREADY INCLUDES STOPS. It was measured from 88 recorded tracks,
 * filtered to 31 clean ones, as total distance over total ELAPSED time. That is
 * why the band is 12-18 and not 25-35 — the machines go faster than that, but
 * the RIDES average out this way once stops are counted. So the estimate is the
 * whole day out, not moving time. And it is tunable: a group that runs 11 sets
 * it once and every future estimate fits them.
 *
 * TWO TIME CALCULATIONS, NOT ONE:
 *   - HERE, on the panel: an ENVELOPE from the mileage range. Every route that
 *     comes back falls inside it. Two ranges compound, so it is wide.
 *   - LATER, per route: the real one, from that route's actual mileage. One
 *     range instead of two, and much tighter. That is the number on the card.
 *
 * ⚠ IT HAS NOT SEEN THE GROUND. The data knows a trail is THERE. It does not
 * know how wide it is, what the surface is, or what is on it — surface is
 * populated on ~30% of trails, width is not in the data at all, and motorized
 * designation is empty in every source imported. That is a safety line on an
 * OHV route, not a disclaimer, which is why it is stated in terms of what the
 * app does NOT know rather than "ride at your own risk".
 */

private val aiBg      = Color(0xFF0F1419)
private val aiPanel   = Color(0xFF131A24)
private val aiCard    = Color(0xFF0F1720)
private val aiLine    = Color(0xFF24313F)
private val aiGreen   = Color(0xFF7BB661)
private val aiBlue    = Color(0xFF4DA6FF)
private val aiAmber   = Color(0xFFE3B341)
private val aiTxt     = Color(0xFFE6EDF3)
private val aiDim     = Color(0xFF8899AA)
private val aiFaint   = Color(0xFF667788)
/** AIPANEL-2026-08-24E: input fields only.
 *
 *  Distinctly lighter than aiBg so a text field reads as somewhere you TYPE
 *  rather than as more background. aiCard is three points away from aiBg,
 *  which is right for a panel that should barely separate and wrong for an
 *  input that must obviously be one. */
private val aiField   = Color(0xFF1E2A38)
private val aiMono    = FontFamily.Monospace

const val AI_MODE_EXPLORE = 0   // start point + goals, the app picks everything
const val AI_MODE_INCLUDE = 1   // the same, plus places the rider drops

private const val PHASE_SETUP   = 0
private const val PHASE_RESULTS = 1

/** One suggested route, as the results phase shows it. */
data class AiRouteResult(
    val name: String,
    val miles: Double,
    val hoursLow: Double,
    val hoursHigh: Double,
    val features: Int,
    val featureMix: String,
)

@Composable
fun ConvoyAiDesignPanel(
    /** Defaults the exploration name. Fred: the app appends " Route <n>". */
    anchorName: String = "",
    /** STUB:AISEARCH — the real search attaches here. Null while unbuilt. */
    results: List<AiRouteResult>? = null,
    onFindRides: (mode: Int, name: String, milesLow: Int, milesHigh: Int,
                  mphLow: Int, mphHigh: Int) -> Unit = { _, _, _, _, _, _ -> },
    onContinue: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    var phase by remember { mutableStateOf(if (results == null) PHASE_SETUP else PHASE_RESULTS) }
    var mode by remember { mutableStateOf(AI_MODE_EXPLORE) }
    var name by remember { mutableStateOf(anchorName) }

    // Defaults settled 08-23. ⚠ 55-80 mi at 12-18 mph is a WIDER ask than
    // anything measured — the 08-22 runs used an 80 mi ceiling and returned
    // 74-79 mi rides with 10-13 features. The first real run at this setting is
    // the check.
    var miLow  by remember { mutableIntStateOf(55) }
    var miHigh by remember { mutableIntStateOf(80) }
    var mphLow by remember { mutableIntStateOf(12) }
    var mphHigh by remember { mutableIntStateOf(18) }

    // AIPANEL-2026-08-24B: the two explainer twisties.
    //
    // "How does this work?" opens by default -- the first time anyone sees
    // this panel is the one time the explanation matters most. "How are trails
    // recommended?" is a different question, asked by the riders who want to
    // know why a route came back the way it did, so it starts closed.
    //
    // STUB:TWISTYPREF -- these reset to their defaults every time the panel
    // opens. Fred, 08-24: "always open to the last state." That needs a
    // preference, NOT draft state: a twisty in the draft JSON would travel
    // inside the GPX and get restored on another rider's phone.
    var twHow by remember { mutableStateOf(true) }
    var twRec by remember { mutableStateOf(false) }

    // The envelope: slowest case is the LOW mileage over the HIGH speed only if
    // you want the shortest possible; the honest bounds are low/high and
    // high/low, so every route that comes back falls inside.
    val hrLow  = miLow.toDouble() / mphHigh.toDouble()
    val hrHigh = miHigh.toDouble() / mphLow.toDouble()

    Box(Modifier.fillMaxSize().background(aiBg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp, 16.dp)
        ) {
            if (phase == PHASE_SETUP) {
                Text("AI Design", color = aiGreen, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))

                // ── 1. NAME, first ───────────────────────────────────
                // AIPANEL-2026-08-24B: was mid-panel. It names four routes,
                // so the rider decides it before anything else.
                Text("NAME THESE ROUTES BEING CREATED", color = aiFaint, fontSize = 9.5.sp,
                    fontFamily = aiMono)
                Spacer(Modifier.height(5.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = aiField,
                    modifier = Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(color = aiTxt, fontSize = 13.sp),
                        cursorBrush = SolidColor(aiGreen),
                        modifier = Modifier.fillMaxWidth().padding(11.dp, 10.dp)
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "Routes are named \u201C${name.ifBlank { anchorName.ifBlank { "Ride" } }} " +
                        "Route 1\u201D, \u201C\u2026 Route 2\u201D and so on. Rename whichever " +
                        "you keep.",
                    color = aiFaint, fontSize = 11.sp, lineHeight = 15.sp
                )
                Spacer(Modifier.height(16.dp))

                // ── 2. HOW FAR, AND HOW FAST ─────────────────────────
                Text("HOW FAR, AND HOW FAST", color = aiFaint, fontSize = 9.5.sp,
                    fontFamily = aiMono)
                Spacer(Modifier.height(7.dp))
                RangeRow("How far do you want to ride?", "$miLow \u2013 $miHigh miles")
                Spacer(Modifier.height(7.dp))
                RangeRow("Your average speed", "$mphLow \u2013 $mphHigh mph")
                Spacer(Modifier.height(11.dp))

                // ── 3. TARGETED RIDE DURATION ────────────────────────
                // Fred's wording, 08-24. "Targeted" rather than "estimated":
                // this is what the rider is aiming for, not a prediction about
                // a route that does not exist yet. The per-route card carries
                // the real estimate, from that route's own mileage.
                Surface(shape = RoundedCornerShape(6.dp), color = aiCard,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp, 10.dp)) {
                        Text("TARGETED RIDE DURATION", color = aiGreen, fontSize = 9.5.sp,
                            fontFamily = aiMono)
                        Spacer(Modifier.height(4.dp))
                        Text("%.1f \u2013 %.1f hours".format(hrLow, hrHigh),
                            color = aiTxt, fontSize = 14.sp, fontFamily = aiMono)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Calculated from the average speed and miles above. Changing " +
                                "these values will change the ride duration estimated.",
                            color = aiFaint, fontSize = 11.sp, lineHeight = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    "Adjust defaults with caution. Adjustments to the above values will " +
                        "result in fewer trail options selected.",
                    color = aiAmber, fontSize = 11.sp, lineHeight = 15.sp
                )
                Spacer(Modifier.height(16.dp))

                // ── 4. THE TWO TWISTIES ──────────────────────────────
                AiTwisty("How does this work?", twHow, aiGreen, { twHow = !twHow }) {
                    Text(
                        "You will be guided throughout this process. This option analyzes " +
                            "all the trails around you and your choices to create several " +
                            "Work in Progress ride alternatives for you to review.",
                        color = aiDim, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "You may complete and save or discard these works in progress at " +
                            "your leisure.",
                        color = aiDim, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }

                AiTwisty("How are trails recommended?", twRec, aiBlue, { twRec = !twRec }) {
                    Text(
                        "The app scores a ride on what it passes and how much of it you " +
                            "ride twice. Rides that reach named features \u2014 springs, " +
                            "canyons, overlooks, ruins, mines \u2014 score higher than rides " +
                            "that just cover ground.",
                        color = aiDim, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Riding the same trail back the way you came counts against a ride, " +
                            "so loops and new ground are favoured over out-and-backs. " +
                            "Distance is a budget, not a goal \u2014 a ride that hits your " +
                            "mileage but passes nothing interesting scores below a shorter " +
                            "one that does.",
                        color = aiDim, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(7.dp))
                    // The line that earns its keep. Panguitch's main connected
                    // component is 40,599 of 70,741 nodes -- 43% of the network
                    // is fragments joined to nothing. A rider who knows a trail
                    // is out there and never sees it used will file a bug.
                    Text(
                        "It only builds on trails that are actually connected to each other " +
                            "in the map data. Some trails in your area are recorded as " +
                            "isolated pieces with nothing joining them, and those cannot be " +
                            "used until better data exists.",
                        color = aiFaint, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
                Spacer(Modifier.height(14.dp))

                // ── 5. it has not seen the ground ────────────────────
                Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFF241D0E),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp, 11.dp)) {
                        Text("\u26A0 It has not seen the ground", color = aiAmber,
                            fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "GroupTrack knows a trail is there. It does not know how wide it " +
                                "is, what the surface is like, or what is on it \u2014 that is " +
                                "not in the trail data. Zoom in on the satellite view before " +
                                "you commit, and check your machine will fit.",
                            color = Color(0xFFD8CBA8), fontSize = 11.5.sp, lineHeight = 17.sp
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))

                // ── 6. PROCEED ───────────────────────────────────────
                // ⚠ STILL CALLS onFindRides DIRECTLY. Pin collection lands in
                // Patch C; until then this panel behaves exactly as it did and
                // the explorer keeps working. The LABEL is ahead of the wiring
                // on purpose -- it is the layout that is being reviewed.
                AiButton(
                    "PROCEED TO MAP",
                    "Dropping a trailhead pin is a required step. Dropping any other " +
                        "pins is optional."
                ) {
                    // STUB:AISEARCH -- the exploratory search attaches here. It
                    // returns 1-4 routes; the panel then switches to PHASE_RESULTS.
                    // AIPANEL-2026-08-24B: mode is no longer chosen on this panel.
                    // It is DERIVED from the pins the rider drops -- trailhead only
                    // is Explore, any include points is Assist -- and Patch C
                    // supplies it. AI_MODE_EXPLORE until then, which is what a
                    // trailhead-only search is.
                    onFindRides(AI_MODE_EXPLORE, name.ifBlank { anchorName },
                        miLow, miHigh, mphLow, mphHigh)
                    phase = PHASE_RESULTS
                }
                Spacer(Modifier.height(11.dp))
                Text("Cancel", color = aiDim, fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() })

            } else {
                // ══ RESULTS ═════════════════════════════════════════
                val list = results ?: emptyList()
                val label = name.ifBlank { anchorName.ifBlank { "Ride" } }

                Text(
                    if (list.isEmpty()) "Searching\u2026"
                    else "${list.size} route${if (list.size == 1) "" else "s"} created",
                    color = aiGreen, fontSize = 19.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Saved as work in progress under \u201C$label\u201D. Open one to see it on " +
                        "the map. Keep the one you want and discard the rest \u2014 you can " +
                        "rename it when you save it.",
                    color = aiDim, fontSize = 12.5.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(14.dp))

                list.forEach { r ->
                    Surface(shape = RoundedCornerShape(8.dp), color = aiPanel,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
                        Column(Modifier.padding(13.dp, 11.dp)) {
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom) {
                                Text(r.name, color = aiTxt, fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                                Text("%.0f mi \u00B7 %.1f\u2013%.1f hr"
                                    .format(r.miles, r.hoursLow, r.hoursHigh),
                                    color = aiGreen, fontSize = 11.5.sp, fontFamily = aiMono)
                            }
                            Spacer(Modifier.height(5.dp))
                            Text("${r.features} features \u2014 ${r.featureMix}",
                                color = aiDim, fontSize = 11.5.sp, lineHeight = 16.sp)
                        }
                    }
                }

                if (list.isEmpty()) {
                    // STUB:AISEARCH -- nothing is wired yet, so the results phase
                    // has nothing to show. This is the panel that will hold them.
                    Surface(shape = RoundedCornerShape(8.dp), color = aiPanel,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("The search is not wired yet \u2014 STUB:AISEARCH.",
                            color = aiFaint, fontSize = 12.sp,
                            modifier = Modifier.padding(13.dp, 12.dp))
                    }
                    Spacer(Modifier.height(9.dp))
                }

                Spacer(Modifier.height(8.dp))
                AiButton("CONTINUE", "Back to the route in progress") { onContinue() }
                Spacer(Modifier.height(11.dp))
                Text("Close", color = aiDim, fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() })
            }

            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun AiOption(
    title: String, body: String, hint: String, selected: Boolean, onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Color(0xFF131F18) else aiCard,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { onClick() }
    ) {
        Column(Modifier.padding(13.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) aiGreen else Color.Transparent,
                    modifier = Modifier.size(14.dp)
                ) {}
                Spacer(Modifier.width(9.dp))
                Text(title, color = aiTxt, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(7.dp))
            Text(body, color = aiDim, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 23.dp))
            Spacer(Modifier.height(6.dp))
            Text(hint, color = aiFaint, fontSize = 11.5.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(start = 23.dp))
        }
    }
}

/**
 * AIPANEL-2026-08-24B: a collapsible explainer.
 *
 * Collapsed shows only the QUESTION -- one line instead of three paragraphs.
 * A rider who already knows scrolls past in a second; one who does not taps it.
 *
 * The body is a slot rather than a String so a twisty can hold several
 * paragraphs at different weights without this function growing parameters for
 * each of them.
 */
@Composable
private fun AiTwisty(
    question: String,
    open: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp), color = aiCard,
        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }.padding(11.dp, 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (open) "\u25BE" else "\u25B8", color = aiFaint,
                    fontSize = 11.sp, fontFamily = aiMono,
                    modifier = Modifier.padding(end = 9.dp))
                Text(question, color = accent, fontSize = 12.8.sp,
                    fontWeight = FontWeight.Bold)
            }
            if (open) {
                Column(Modifier.fillMaxWidth().padding(11.dp, 0.dp, 11.dp, 11.dp)) {
                    body()
                }
            }
        }
    }
}

@Composable
private fun RangeRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label, color = aiDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = aiTxt, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AiButton(label: String, sub: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp), color = aiGreen,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color(0xFF0D1117), fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold)
            Text(sub, color = Color(0xCC0D1117), fontSize = 10.5.sp)
        }
    }
}
