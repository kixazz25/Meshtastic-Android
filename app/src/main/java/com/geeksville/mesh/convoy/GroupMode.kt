package com.geeksville.mesh.convoy

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.grouptrack.shared.settings.GtGroupMode

/*
 * GRPMODE-2026-10-13 (Fred 10-12 / 10-13) -- group awareness ON (a Meshtastic radio, the group on the map) or OFF (this tablet only).
 * GroupTrack's own setting. OFF: the group controls are greyed (not hidden); tapping one shows ACTIVATE_MESSAGE; the tick uses this
 * tablet only; GroupTrack makes no radio calls to the comm module (the comm side is told once: CommLink.setActive(false)).
 * Stored in grouptrack_group_mode. Not stored yet (an upgrading rider): a saved "no" to the first-start radio question -> off,
 * anything else -> on (unchanged behaviour). The first-start question (RadioSetupPrompt) sets it.
 *
 * GRPMODESHARED-2026-11-20: THE SETTING AND THE STATE NOW LIVE IN SHARED [GtGroupMode] -- one source for Android
 * and the iPad, which is what let the groupOnly launch modules (RADIO SETUP among them) reach the iPad at all.
 * This object is the ANDROID FACE of it and is deliberately unchanged in shape: `on`, `askActivate`,
 * `ACTIVATE_MESSAGE`, `load(ctx)`, `set(ctx, value)` and `askToActivate()` all behave exactly as before, so every
 * existing call site (20+ in ConvoyViewModel, 7 in GroupTrackScreen) is untouched. The store name and key are
 * unchanged too, so a rider's saved setting is read exactly where it already is -- no migration, no data change.
 * What stays HERE is the one thing shared code cannot know: the Android-only RadioSetupState fallback.
 */
object GroupMode {
    const val ACTIVATE_MESSAGE = GtGroupMode.ACTIVATE_MESSAGE

    /** ON = group awareness with a Meshtastic radio. The SHARED state -- the same MutableState the iPad reads. */
    val on get() = GtGroupMode.on

    /** A greyed group control was tapped -- the popup is showing. */
    val askActivate get() = GtGroupMode.askActivate

    /** Android's default when nothing is stored: the first-start radio answer. RadioSetupState is Android-only,
     *  which is precisely why GtGroupMode.load takes the default from its caller rather than inventing one. */
    fun load(ctx: Context) =
        GtGroupMode.load(
            com.geeksville.mesh.grouptrack.GtSettingsAndroid(ctx),
            defaultWhenUnset = RadioSetupState.answer(ctx) != RadioSetupState.NO,
        )

    fun set(ctx: Context, value: Boolean) =
        GtGroupMode.set(com.geeksville.mesh.grouptrack.GtSettingsAndroid(ctx), value)

    /** A greyed group control was tapped: say how to turn group mode on. */
    fun askToActivate() = GtGroupMode.askToActivate()
}

/** The popup (shown over everything -- Main.kt draws it). */
@Composable
fun GroupModeActivatePopup() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { GroupMode.load(ctx) }
    if (GroupMode.askActivate.value) AlertDialog(
        onDismissRequest = { GroupMode.askActivate.value = false },
        title = { Text("Group awareness is off") },
        text = { Text(GroupMode.ACTIVATE_MESSAGE) },
        confirmButton = { TextButton(onClick = { GroupMode.askActivate.value = false }) { Text("OK") } },
    )
}
