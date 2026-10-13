package com.geeksville.mesh.convoy

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/*
 * GRPMODE-2026-10-13 (Fred 10-12 / 10-13) -- group awareness ON (a Meshtastic radio, the group on the map) or OFF (this tablet only).
 * GroupTrack's own setting. OFF: the group controls are greyed (not hidden); tapping one shows ACTIVATE_MESSAGE; the tick uses this
 * tablet only; GroupTrack makes no radio calls to the comm module (the comm side is told once: CommLink.setActive(false)).
 * Stored in grouptrack_group_mode. Not stored yet (an upgrading rider): a saved "no" to the first-start radio question -> off,
 * anything else -> on (unchanged behaviour). The first-start question (RadioSetupPrompt) sets it.
 */
object GroupMode {
    private const val PREFS = "grouptrack_group_mode"
    private const val KEY = "group_awareness"
    const val ACTIVATE_MESSAGE = "Group mode needs to be activated in Settings. Do not activate unless your mesh device is available."

    /** ON = group awareness with a Meshtastic radio. Read by the screens (greying) and by the tick. */
    val on = mutableStateOf(true)
    /** A greyed group control was tapped -- the popup is showing. */
    val askActivate = mutableStateOf(false)
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        on.value = if (p.contains(KEY)) p.getBoolean(KEY, true) else RadioSetupState.answer(ctx) != RadioSetupState.NO
        loaded = true
        android.util.Log.i("GroupMode", "GRPMODE-2026-10-13: group awareness " + (if (on.value) "ON" else "OFF") +
            (if (p.contains(KEY)) "" else " (not set yet -- from the first-start answer)"))
    }

    fun set(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        on.value = value; loaded = true
        android.util.Log.i("GroupMode", "GRPMODE-2026-10-13: group awareness set " + (if (value) "ON" else "OFF"))
    }

    /** A greyed group control was tapped: say how to turn group mode on. */
    fun askToActivate() { askActivate.value = true }
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
