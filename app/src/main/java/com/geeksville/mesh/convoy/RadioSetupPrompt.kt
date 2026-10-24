package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
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
 * RADIOSETUPSTART-2026-10-01 (GroupTrack 2.7a) -- the startup radio-setup question.
 * 2.7a moves GroupTrack to TAK messaging, so every radio used with it needs the GroupTrack configuration.
 * Fred 10-01: the app cannot tell whether the rider has a radio, so it ASKS -- once, at install.
 *   Shown by the startup gate (AuthorityState.NeedRadioSetup) after background location, before the trail check.
 *   YES -> GRP Awareness: connect, select, apply.   NO -> set it up later in GRP Awareness, before the first ride.
 *   NOT asked when: answered NO; an apply finished verified (RadioConfigScreen marks DONE); a saved config exists
 *   (a 2.7 configurator save = .cfg + .json companion); or already answered in this session (the gate re-checks on
 *   every resume). An uninstall empties app storage, so a reinstall asks again.
 * (Tested 10-01 as a GRP Awareness entry, RADIOSETUPTEST-2026-10-01; that entry is removed by this patch.)
 */
object RadioSetupState {
    private const val PREFS = "grouptrack_radio_setup"
    private const val KEY = "answer"
    const val NO = "no"
    const val DONE = "done"

    /** In memory only: answered (YES or NO) in this app session. YES is not stored -- it asks again next launch
     *  unless the apply finished verified, which leaves a saved config and marks DONE. */
    @Volatile var answeredThisSession = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "" = not answered (or YES not completed yet), "no", or "done". */
    fun answer(ctx: Context): String = prefs(ctx).getString(KEY, "") ?: ""

    /** A save made by the 2.7 configurator: a .cfg in convoy_backups/<radio>/ with its .json companion. */
    fun hasSavedConfig(ctx: Context): Boolean = com.geeksville.mesh.comm.CommAccess.config(ctx).hasAnySavedSetup()   // COMMSAVES-2026-10-14

    fun needsAsking(ctx: Context): Boolean {
        if (answeredThisSession) return false
        val a = answer(ctx)
        if (a.isNotEmpty()) return false
        if (hasSavedConfig(ctx)) { log("saved config present -- not asked"); return false }
        return true
    }

    fun markYes() { answeredThisSession = true; log("answer = yes (this session; GRP Awareness opened)") }

    fun markNo(ctx: Context) { answeredThisSession = true; prefs(ctx).edit().putString(KEY, NO).apply(); log("answer = no") }

    /** Called by the standard apply when it finishes VERIFIED. */
    fun markDone(ctx: Context) { prefs(ctx).edit().putString(KEY, DONE).apply(); log("answer = done (verified apply)") }

    private fun log(s: String) = android.util.Log.i("RadioSetup", "RADIOSETUP: " + s)
}

@Composable
fun RadioSetupPrompt(
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { },   // a startup question: answered with YES or NO, not dismissed
        title = { Text("Will you use a Meshtastic radio to see your group on the map?") },   // GRPMODE-2026-10-13 (G1)
        text = {
            Column {
                Text("GroupTrack uses TAK messaging. Any radio used with GroupTrack must be set up with the new " +
                    "GroupTrack configuration.")
                Spacer(Modifier.height(10.dp))
                Text("YES", fontWeight = FontWeight.Bold)
                Text("Confirm you have the radio and it is now powered on. Then connect, select the GroupTrack " +
                    "default, and apply.")
                Text("If your tablet asks for a pairing code: 123456 is the default. If your radio has a screen and " +
                    "shows a different code, use that one.", fontSize = 12.sp, color = Gt.dim)
                Text("Your user-profile callsign becomes the radio's name.")
                Text("More than one radio? Repeat this for each one: connect it in GRP Awareness and apply the " +
                    "GroupTrack default to it. Change the callsign for each additional radio so it does not inherit yours.",
                    fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("NO", fontWeight = FontWeight.Bold)
                Text("You can change this later in Settings (Group awareness).", fontWeight = FontWeight.Bold)   // GRPMODE-2026-10-13
                Text("You will set up your radio later. Before your first ride, use GRP Awareness to connect, " +
                    "select a ride, and apply the new configuration.")
            }
        },
        confirmButton = { TextButton(onClick = onYes) { Text("YES, I HAVE A RADIO") } },
        dismissButton = { TextButton(onClick = onNo) { Text("NO, THIS TABLET ONLY") } },
    )
}
