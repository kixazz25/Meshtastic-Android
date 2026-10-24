package com.geeksville.mesh.grouptrack

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
import com.geeksville.mesh.convoy.GuidedStep
import com.geeksville.mesh.convoy.GP_STATE_CURRENT

/** GTAI4-2026-10-23: ConvoyAiStepScreen (the guided step banner) and ConvoyGuidedSummaryPanel (the plan and the search), COPIED
 *  for ONE MAP with the HELP X standard. GuidedStep and GP_STATE_* stay the planner's (shared data). The planner keeps its copy. */

private val gpBg: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF0F1419 
private val gpPanel: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF131A24 
private val gpCard: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF0F1720 
private val gpLine: Color get() = Gt.line  // STD1-2026-10-24 was 0xFF24313F 
private val gpGreen: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF7BB661 
private val gpBlue: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF4DA6FF 
private val gpAmber: Color get() = Gt.warn  // STD1-2026-10-24 was 0xFFE3B341 
private val gpTxt: Color get() = Gt.text  // STD1-2026-10-24 was 0xFFE6EDF3 
private val gpDim: Color get() = Gt.dim  // STD1-2026-10-24 was 0xFF8899AA 
private val gpFaint: Color get() = Gt.dim  // STD1-2026-10-24 was 0xFF667788 
private val gpMono  = FontFamily.Monospace

@Composable
fun GtGuidedSummaryPanel(
    title: String,
    body: String,
    working: Boolean,
    progress: String,
    onProceed: () -> Unit,
    onStartOver: () -> Unit,
    /** AICANCEL-2026-10-08: leave the AI flow completely. Required -- one caller, wired. */
    onCancelBuild: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Gt.panel),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = gpPanel,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).padding(18.dp)
        ) {
            Column(Modifier.padding(18.dp, 16.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {   // GTAI4-2026-10-23: the HELP X standard
                    Text(
                        if (working) "BUILDING YOUR RIDES" else "HERE IS THE PLAN",
                        color = if (working) gpBlue else gpGreen,
                        fontSize = 12.sp, fontFamily = gpMono,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
                        modifier = Modifier.weight(1f)
                    )
                    GtHelpButton("route.ai")
                    GtCloseButton(onCancelBuild, enabled = !working)
                }
                if (title.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(title, color = gpDim, fontSize = 12.sp)
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
                        color = gpFaint, fontSize = 12.sp, lineHeight = 15.sp
                    )
                } else {
                    Spacer(Modifier.height(18.dp))
                    // ⭐ says where it goes: the compare table, not a vague "proceed"
                    GpButton("CONTINUE TO COMPARE", primary = true,
                        onClick = onProceed)
                    Spacer(Modifier.height(7.dp))
                    GpButton("START OVER", primary = false, onClick = onStartOver)
                    Spacer(Modifier.height(7.dp))
                    GpButton("CANCEL AI BUILD", primary = false, onClick = onCancelBuild)   // AICANCEL-2026-10-08
                }
            }
        }
    }
}

@Composable
fun GtAiStepScreen(
    steps: List<GuidedStep>,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    notice: String = "",
    onStartOver: () -> Unit,
    /** AICANCEL-2026-10-08: leave the AI flow completely. Required -- one caller, wired. */
    onCancelBuild: () -> Unit,
    /** GTAI5B-2026-10-23 (Fred 16:07): drawn under the title row -- the trailhead step puts ONE MAP's SEARCH here. Required: the caller
     *  says what (if anything) the current step adds. */
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    // GTAI5B-2026-10-23 (Fred 16:08): ONE MAP's colour scheme (white/blue on satellite, blue/white on topo) -- matches the search inside it.
    val sc = GtScheme.current.value
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
    /* PATCHA-2026-08-28: EVERY step is a banner now.
     *
     * ⭐ Buttons no longer force a full screen. The rider is looking
     * at the map for the whole flow — the loop answer and the pins
     * question are decisions made ABOUT what is on the map, so
     * covering it to ask them was wrong.
     *
     * ⚠ Only the summary keeps the full screen, and it earns it: it
     * is the last thing before a build that takes real time.
     */
    if (true) {
        Surface(
            color = sc.panel,   // GTAI5B (was gpPanel)
            shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(13.dp, 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cur.title.uppercase(),
                        color = sc.panelText, fontSize = 14.sp, fontFamily = gpMono,   // GTAI5B
                        fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text("step $stepNo of ${steps.size}", color = sc.panelDim,   // GTAI5B
                        fontSize = 12.sp, fontFamily = gpMono)
                    GtHelpButton("route.ai", color = sc.panelText)   // GTAI4-2026-10-23: the HELP X standard (GTAI5B: scheme colour)
                    GtCloseButton(onCancelBuild, color = sc.panelText)
                }
                // GTAI5B-2026-10-23: the step's own content (the trailhead SEARCH), height-capped so the steps stay on screen
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { header() }
                Spacer(Modifier.height(5.dp))
                Text(cur.instruction, color = sc.panelText, fontSize = 14.sp,   // GTAI5B
                    lineHeight = 20.sp)
                // ⚠ what has been captured, so a tap that did not register is
                // visible rather than silent
                if (cur.answer.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(cur.answer, color = sc.panelText, fontSize = 14.sp, fontWeight = FontWeight.Bold)   // GTAI5B
                }
                if (notice.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    NoticeBlock(notice)
                }
                // ⚠ the step's own buttons, in the banner
                actions.forEach { (label, onClick) ->
                    Spacer(Modifier.height(8.dp))
                    GtStepButton(label, primary = true, onClick = onClick)
                }
                // STARTOVER-2026-09-02: ⛔ WITH NO ACTIONS, "START OVER" WAS THE
                // ONLY BUTTON ON SCREEN -- so it read as the only option and
                // riders pressed it before reading anything else. Fred, 09-02:
                // "when you arrive in the function it reads as your only option
                // is to start over."
                // ⚠ MY FIRST ATTEMPT WAS A PARAGRAPH ABOVE THE BUTTON. Wrong.
                // Fred: "waypoints was not supposed to be a paragraph ... the
                // bigger the paragraph the less it is read. I have now lost a
                // lot of real estate."
                // ⭐ THE INSTRUCTION GOES IN THE LABEL. One control, no extra
                // height, and it cannot be pressed without reading it.
                // ⚠ actions.isEmpty() is the same signal the banner form
                // already uses to know a step is worked ON THE MAP.
                Spacer(Modifier.height(9.dp))
                GtStepButton(
                    if (actions.isEmpty())
                        "Select/Add Trailhead waypoint on the Map above" +
                            "  \u2014  or Click Here to Start Over"
                    else "START OVER",
                    primary = false, onClick = onStartOver
                )
                Spacer(Modifier.height(7.dp))
                GtStepButton("CANCEL AI BUILD", primary = false, onClick = onCancelBuild)   // AICANCEL-2026-10-08
            }
        }
        return
    }
}


// GTAI5B-2026-10-23: the step panel's buttons in ONE MAP's scheme (GtButton), full width; the primary/secondary call shape kept.
@Composable
private fun GtStepButton(label: String, primary: Boolean, onClick: () -> Unit) {
    GtButton(label, onClick, modifier = Modifier.fillMaxWidth(), emphasis = if (primary) androidx.compose.ui.graphics.Color.Unspecified else GtScheme.current.value.panelDim)
}

@Composable
private fun NoticeBlock(text: String) {
    Surface(
        color = Gt.panel,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().padding(13.dp, 2.dp, 13.dp, 0.dp)
    ) {
        Row(Modifier.padding(11.dp)) {
            Text(
                "!", color = gpAmber, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, fontFamily = gpMono,
                modifier = Modifier.padding(end = 9.dp)
            )
            Text(text, color = gpAmber, fontSize = 12.sp, lineHeight = 16.5.sp)
        }
    }
}

@Composable
private fun GpButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (primary) Gt.text.copy(alpha = 0.18f) else gpCard,
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

