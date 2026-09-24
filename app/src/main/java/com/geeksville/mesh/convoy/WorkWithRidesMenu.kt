package com.geeksville.mesh.convoy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * WORKWITHRIDES-2026-09-24 — the WORK WITH RIDES menu (Fred 09-24).
 *
 * ONE component, called from BOTH maps (the ride map's menu and the planner). It replaces the
 * "Meshtastic Radio Setup" menu. Creating a ride is NOT here — that is planner-only, from a route's
 * detail panel. Everything else about rides is.
 *
 * Entries and their state in this build:
 *   Edit ride ........................ PLACEHOLDER (date, start time, description only)
 *   Send a ride via email ............ WORKS — picks a ride, ConvoyRideSend.send (refuses incomplete, locks)
 *   Import a ride .................... PLACEHOLDER (format-3 import)
 *   Apply ride to radio / Nucleus .... PLACEHOLDER (the new minimal write, Nathan's model)
 *   Apply ride to standalone T1000-E . EXISTING write, via onApplyToT1000 (to be fed by the ride JSON)
 *   Review / apply saved configs ..... EXISTING archive/restore, via onReviewSavedConfigs (to become review/compare/apply)
 *   Long-press the title ............. the developer panel (moved here from the old menu, unchanged)
 *
 * A placeholder SAYS it is not built yet when tapped — never a silent or disabled-looking entry
 * (the high-visibility palette hides a disabled look; lesson of 09-23).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkWithRidesMenu(
    onDismiss: () -> Unit,
    onApplyToT1000: () -> Unit,
    onReviewSavedConfigs: () -> Unit,
    onDeveloperSettings: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var includeRecent by remember { mutableStateOf(false) }   // DATEFILTER-2026-09-24

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = GroupTrackColors.Navy) {
            Column(modifier = Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {

                // Title — long press opens the developer panel (moved from the old menu title).
                Text("WORK WITH RIDES", color = Color(0xFF97D5A5), fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .combinedClickable(onClick = {}, onLongClick = { onDismiss(); onDeveloperSettings() }))

                if (!picking) {
                    WwrEntry("Edit ride", built = false) { status = notBuilt("Edit ride") }
                    WwrEntry("Send a ride via email", built = true) { status = ""; picking = true }
                    WwrEntry("Import a ride", built = false) { status = notBuilt("Import a ride") }
                    WwrEntry("Apply ride to radio / Nucleus", built = false) { status = notBuilt("Apply ride to radio / Nucleus") }
                    WwrEntry("Apply ride to standalone T1000-E", built = true) { onDismiss(); onApplyToT1000() }
                    WwrEntry("Review / apply saved configs", built = true) { onDismiss(); onReviewSavedConfigs() }
                } else {
                    Text("Choose the ride to send", color = Color(0xFFE8EEF5), fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                    // DATEFILTER-2026-09-24: today or later; tick to include the last 30 days.
                    Text((if (includeRecent) "\u2611" else "\u2610") + "  Include rides from the last 30 days",
                        color = Color(0xFFE8EEF5), fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { includeRecent = !includeRecent }.padding(vertical = 8.dp))
                    val rides = remember(includeRecent) {
                        loadRideChoices(context).filter { RideApplySource.isCurrent(it.date, includeRecent) }
                    }
                    if (rides.isEmpty()) {
                        Text("No rides on this tablet yet. Create one from a route on the planning map.",
                            color = Color(0xFFE8A33D), fontSize = 13.sp)
                    }
                    rides.forEach { r ->
                        WwrEntry(r.name + "  \u00b7  " + r.date + "  \u00b7  " + stateLabel(r), built = true) {
                            val why = ConvoyRideSend.send(context, r.id)
                            if (why == null) { onDismiss() } else { status = "\u2717 $why" }
                        }
                    }
                    WwrEntry("\u2190 Back", built = true) { picking = false; status = "" }
                }

                if (status.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(status, color = Color(0xFFE8A33D), fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * WWRLAUNCH-2026-09-24 -- how either map OPENS the menu without new parameters (the MeshNavFold pattern).
 * A map's "Work with Rides" label calls open(); ui/Main.kt shows WorkWithRidesMenu while [showing].
 */
object WorkWithRidesLauncher {
    var showing by mutableStateOf(false)
        private set
    fun open() { showing = true }
    fun close() { showing = false }
}

private fun notBuilt(what: String) = "$what is not built yet \u2014 coming in 2.7."

/** One menu row. An unbuilt entry says so in its own text, not only by colour. */
@Composable
private fun WwrEntry(label: String, built: Boolean, onClick: () -> Unit) {
    Text(
        text = if (built) label else "$label  (coming in 2.7)",
        color = if (built) Color(0xFFE8EEF5) else Color(0xFF8B938A),
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp)
    )
}

private data class RideChoice(val id: String, val name: String, val date: String, val sent: Boolean,
                              val complete: Boolean)   // RIDESTATE-2026-09-24

/** RIDESTATE-2026-09-24: the label shown for a ride. "Sent" is information, never a lock. */
private fun stateLabel(r: RideChoice) = when {
    r.sent -> "sent previously"
    r.complete -> "completed"
    else -> "in progress"
}

/** Rides on this tablet, newest first. Read-only; "sent" comes from ConvoyRideStore (state is derived). */
private fun loadRideChoices(context: android.content.Context): List<RideChoice> {
    val db = SpatialDbManager.getExtensionDb() ?: return emptyList()
    return try {
        db.rawQuery("SELECT ride_id, ride_name, ride_date FROM rides ORDER BY created_at DESC LIMIT 100", null)
            .use { c ->
                val out = mutableListOf<RideChoice>()
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    out += RideChoice(id, c.getString(1) ?: "Ride", c.getString(2) ?: "",
                        ConvoyRideStore.isDistributed(id),
                        // RIDESTATE-2026-09-24: complete = the writer's own check, the one Send uses.
                        ConvoyRideJsonWriter.build(context, id)?.missing?.isEmpty() == true)
                }
                out
            }
    } catch (e: Exception) {
        android.util.Log.w("WorkWithRides", "loadRideChoices failed: ${e.message}")
        emptyList()
    }
}
