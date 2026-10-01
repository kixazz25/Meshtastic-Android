package com.geeksville.mesh.convoy

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * RADIOSETUPTEST-2026-10-01 (GroupTrack 2.7a) -- the startup radio-setup question.
 * 2.7a moves GroupTrack to TAK messaging, so every radio used with it needs the GroupTrack configuration.
 * Fred 10-01: the app cannot tell whether the rider has a radio, so it ASKS.
 *   YES -> connect, select, apply (GRP Awareness).  NO -> set it up later in GRP Awareness, before the first ride.
 *   Not asked again after NO, or after an apply that finished verified. YES without a verified apply asks again.
 * TEST STAGE: opened from GRP Awareness. Next: the same prompt as NeedRadioSetup in the startup gate,
 * just before the state-imports test, and the GRP Awareness entry removed.
 */
object RadioSetupState {
    private const val PREFS = "grouptrack_radio_setup"
    private const val KEY = "answer"
    const val NO = "no"
    const val DONE = "done"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "" = not answered (or YES not completed yet), "no", or "done". */
    fun answer(ctx: Context): String = prefs(ctx).getString(KEY, "") ?: ""

    /** The startup question is needed while there is no answer that settles it. */
    fun needsAsking(ctx: Context): Boolean = answer(ctx).isEmpty()

    fun markNo(ctx: Context) { prefs(ctx).edit().putString(KEY, NO).apply(); log("answer = no") }

    /** Called by the standard apply when it finishes VERIFIED. */
    fun markDone(ctx: Context) { prefs(ctx).edit().putString(KEY, DONE).apply(); log("answer = done (verified apply)") }

    /** TEST ONLY: clear the answer so the question can be re-tested. */
    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply(); log("answer cleared (test)") }

    private fun log(s: String) = android.util.Log.i("RadioSetup", "RADIOSETUP: " + s)

    /** Shown in GRP Awareness while testing. */
    var showing = androidx.compose.runtime.mutableStateOf(false)
}

@Composable
fun RadioSetupPrompt(
    ctx: Context,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stored = RadioSetupState.answer(ctx)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Do you have a mesh radio to use with GroupTrack?") },
        text = {
            Column {
                Text("GroupTrack uses TAK messaging. Any radio used with GroupTrack must be set up with the new " +
                    "GroupTrack configuration.")
                Spacer(Modifier.height(10.dp))
                Text("YES", fontWeight = FontWeight.Bold)
                Text("Confirm you have the radio and it is now powered on. Then connect, select the GroupTrack " +
                    "default, and apply.")
                Text("If your tablet asks for a pairing code: 123456 is the default. If your radio has a screen and " +
                    "shows a different code, use that one.", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                // RADIOSETUPTEXT2-2026-10-01 (Fred): the callsign, and additional radios.
                Text("Your user-profile callsign becomes the radio's name.")
                Text("More than one radio? Repeat this for each one: connect it in GRP Awareness and apply the " +
                    "GroupTrack default to it. Change the callsign for each additional radio so it does not inherit yours.",
                    fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("NO", fontWeight = FontWeight.Bold)
                Text("You will set up your radio later. Before your first ride, use GRP Awareness to connect, " +
                    "select a ride, and apply the new configuration.")
                Spacer(Modifier.height(10.dp))
                Text("TEST: stored answer = " + (if (stored.isEmpty()) "(none -- would ask at startup)" else stored),
                    fontSize = 11.sp, color = Color(0xFFE8C27A))
            }
        },
        confirmButton = { TextButton(onClick = onYes) { Text("YES") } },
        dismissButton = {
            Column {
                TextButton(onClick = onNo) { Text("NO") }
                TextButton(onClick = { RadioSetupState.clear(ctx); onDismiss() }) {
                    Text("CLEAR ANSWER (test)", color = Color(0xFFF08C84), fontSize = 11.sp)
                }
            }
        },
    )
}
