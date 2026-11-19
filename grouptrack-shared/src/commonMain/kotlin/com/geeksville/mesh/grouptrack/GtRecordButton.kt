package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.RecordingState

/**
 * IOSREC-2026-11-19 (Fred 11-19 11:39 "HUD displays are not wired yet", 12:23 "added HUD display to this
 * level"): the RECORD control of the top-centre stack, shared.
 *
 * ⭐ WHAT IS SHARED AND WHAT IS NOT, and why the line falls here. Android's REC control
 * (GroupTrackScreen.kt:1760-1880) is two things wound together: a PRESENTATION -- five colours, five
 * labels, a collapsed button and an expanded PAUSE/END menu -- and a set of TRANSITIONS that are
 * thoroughly Android (ACCESS_BACKGROUND_LOCATION, ConvoyRideStore.checkIn, viewModel.setLeadCart,
 * convoyState.nodes, three dialogs). The presentation is identical on any platform and is here; the
 * transitions stay with the host behind [onTap] / [onPause] / [onEnd] (SEAMRULE-2026-11-19).
 * So this is NOT a reimplementation of the state machine -- the host still owns every decision. What it
 * guarantees is that the iPad's button says and shows exactly what Android's does for a given state.
 *
 * Transcribed colour for colour and label for label:
 *   CHECK_IN   0xFFD32F2F  "✔  CHK IN" in Group · "✔  CHK IN › REC" with a radio · "⏺  REC" alone
 *   IDLE       0xFFD32F2F  "⏺  REC"
 *   RECORDING  0xFFCC0000  "⏸  PAUSE"
 *   PAUSED     0xFF994400  "⏺  RESUME"
 *   SLEEPING   0xFFCC8800  "ZZZ  ASLEEP"
 * The expanded menu: PAUSE / CONTINUE 0xFF994400, END 0xFF4A0000 (measured at GroupTrackScreen.kt:1876 --
 * I had written a grey there from memory before checking, which is exactly the fabrication the
 * measure-before-reporting rule exists to stop).
 * SHELL2-2026-10-22: 22 sp monospace, 32/18 padding -- larger in ONE MAP. SOLOREC-2026-09-28 is the
 * three-way CHECK_IN label; ORDERFIX-2026-09-27 is why the longer one still fits.
 */
@Composable
fun GtRecordButton(
    state: RecordingState,
    /** GroupMode.on -- CHKIN1MAP-2026-10-22: the label follows GROUP mode. */
    group: Boolean,
    /** GrpAwarenessLauncher.connected -- SOLOREC-2026-09-28: a radio changes the CHECK_IN label. */
    radioConnected: Boolean,
    /** RECORDING and PAUSED open the menu instead; the host decides everything else. */
    menuOpen: Boolean,
    onTap: () -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
) {
    if (!menuOpen) {
        Surface(
            modifier = Modifier.clickable { onTap() },
            shape = RoundedCornerShape(10.dp),
            color = gtRecordColor(state),
            shadowElevation = 6.dp,
        ) {
            Text(
                gtRecordLabel(state, group, radioConnected),
                color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp),
            )
        }
    } else {
        // The expanded menu, as GroupTrackScreen.kt:1845-1880: PAUSE / CONTINUE, then END.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                modifier = Modifier.clickable { onPause() },
                shape = RoundedCornerShape(10.dp), color = Color(0xFF994400), shadowElevation = 6.dp,
            ) {
                Text(
                    if (state == RecordingState.PAUSED) "▶  CONTINUE" else "⏸  PAUSE",
                    color = Color.White, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            Surface(
                modifier = Modifier.clickable { onEnd() },
                shape = RoundedCornerShape(10.dp), color = Color(0xFF4A0000), shadowElevation = 6.dp,
            ) {
                Text(
                    "⏹  END", color = Color.White, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** SHELL2 / ORDERFIX-2026-09-27 / GRPSTATUS-2026-10-24: the five state colours, unchanged. */
fun gtRecordColor(state: RecordingState): Color = when (state) {
    RecordingState.CHECK_IN -> Color(0xFFD32F2F)
    RecordingState.IDLE -> Color(0xFFD32F2F)
    RecordingState.RECORDING -> Color(0xFFCC0000)
    RecordingState.PAUSED -> Color(0xFF994400)
    RecordingState.SLEEPING -> Color(0xFFCC8800)
}

/** SOLOREC-2026-09-28 + SHELL5-2026-10-22: CHECK_IN reads three different ways. */
fun gtRecordLabel(state: RecordingState, group: Boolean, radioConnected: Boolean): String = when (state) {
    RecordingState.CHECK_IN ->
        if (group) "✔  CHK IN"
        else if (radioConnected) "✔  CHK IN › REC"
        else "⏺  REC"
    RecordingState.IDLE -> "⏺  REC"
    RecordingState.RECORDING -> "⏸  PAUSE"
    RecordingState.PAUSED -> "⏺  RESUME"
    RecordingState.SLEEPING -> "ZZZ  ASLEEP"
}
