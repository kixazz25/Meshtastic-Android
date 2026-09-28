package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll   // CHECKINORG-2026-09-28
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CHECKIN-2026-09-27 (Fred) -- the PRE-RIDE CHECK-IN: the first state of the recording sequence (CHECK_IN). You cannot
 * record until you have checked in. Resolves, for THIS ride only: the ride, the callsign and the role (defaults from the
 * profile, overridable; the profile is never changed). Stored as the enrollment (created_by 'login').
 *  - The rides: open, recent rides on this tablet (dated today or earlier, not expired), today's first. Exactly one
 *    today -> pre-selected. "No scheduled ride" -> a normal recording: no survey, no sharing, the rider names the track.
 *  - The organizer of the chosen ride -> Leader pre-selected; others -> the profile's default role.
 *  - CHECKINAPPLY-2026-09-28: the radio is compared with the ride by NETWORK; when it is not set up for the ride,
 *    CHECK IN runs the configurator's own apply on that ride first, then checks in (or reopens, choices kept).
 * Completing the check-in does NOT start recording: the button then reads REC (a second tap records).
 */
/** CHECKINAPPLY-2026-09-28 (Fred): the check-in's switch, shared -- it closes while the radio is set up for the ride and
 *  reopens with the rider's choices if that setup fails. */
object CheckInLauncher {
    val showing = androidx.compose.runtime.mutableStateOf(false)
    /** The rider's choices kept while the radio is set up. CODE RULE 1: null = none kept (a fresh check-in). */
    var pending: Pending? = null
    data class Pending(val rideId: String?, val callsign: String, val role: String, val showOnMap: Boolean, val error: String)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CheckInSheet(onDone: (ConvoyRideStore.CheckIn, Boolean) -> Unit, onCancel: () -> Unit) {   // CHECKINMAP: + show on the ride map
    val me = remember { ConvoyProfileStore.load() }
    val rides = remember { ConvoyRideStore.openRecentRides() }
    val today = remember { java.time.LocalDate.now().toString() }
    val todays = rides.filter { it.date == today }
    val roles = listOf("leader" to "Leader", "rider" to "Rider", "middle" to "Middle", "tail_gunner" to "Tail gunner")
    fun defaultRole(r: ConvoyRideStore.RideChoice?): String {
        if (r != null && me != null && r.organizerId == me.userId) return "leader"
        val d = (me?.defaultRole ?: "rider").lowercase().replace(' ', '_')
        return if (roles.any { it.first == d }) d else "rider"
    }
    // CODE RULE 1: null is a real choice -- "No scheduled ride".
    // CHECKINAPPLY-2026-09-28: a check-in reopened after a failed radio setup comes back with the rider's choices.
    val kept = remember { CheckInLauncher.pending.also { CheckInLauncher.pending = null } }
    var chosen by remember { mutableStateOf(kept?.let { k -> rides.firstOrNull { it.rideId == k.rideId } } ?: todays.singleOrNull()) }
    var picked by remember { mutableStateOf(kept != null || todays.size == 1) }   // otherwise the rider must choose
    var callsign by remember { mutableStateOf(kept?.callsign ?: me?.callsign ?: "") }
    var role by remember { mutableStateOf(kept?.role ?: defaultRole(chosen)) }
    // CHECKINMAP-2026-09-28 (Fred): show the checked-in ride's route and trailhead on the ride map (rides only).
    var showOnMap by remember { mutableStateOf(kept?.showOnMap ?: true) }
    // CHECKINAPPLY-2026-09-28 (Fred): is the connected radio on THIS ride's network? Compared by NETWORK -- a radio
    // applied from another ride on the same network (e.g. the organizer's own) is already right.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val radioOn = GrpAwarenessLauncher.connected.value
    val nodeNum = GrpAwarenessLauncher.myNodeNum.value
    val last = remember(nodeNum, radioOn) { lastAppliedRide(ctx, nodeNum) }   // (rideId, title), or null
    val chosenNet = remember(chosen?.rideId) { chosen?.rideId?.let { ConvoyRideStore.rideForEdit(it)?.configId } }
    val lastNet = remember(last) { last?.first?.let { ConvoyRideStore.rideForEdit(it)?.configId } }
    val needsSetup = chosen != null && radioOn && (lastNet == null || lastNet != chosenNet)
    val dim = Color(0xFF8899AA)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Check in") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Which ride?", fontWeight = FontWeight.Bold)
                // CHECKINRIDES-2026-09-28 (Fred): one list by date, newest first (upcoming above today, past below), in its own
                // scrolling box that opens POSITIONED AT TODAY: scroll up or down.
                // CHECKINORG-2026-09-28 (Fred): All, or one organizer -- the organizers taken from the rides themselves.
                var rideFilter by remember { mutableStateOf("") }   // "" = All; otherwise an organizer's name
                val organizers = remember(rides) { rides.map { it.organizerName }.filter { it.isNotBlank() }.distinct().sorted() }
                if (organizers.isNotEmpty()) Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = rideFilter.isEmpty(), onClick = { rideFilter = "" }, label = { Text("All") })
                    organizers.forEach { o -> FilterChip(selected = rideFilter == o, onClick = { rideFilter = o }, label = { Text(o) }) }
                }
                val shown = rides.filter { r -> rideFilter.isEmpty() || r.organizerName == rideFilter }
                val todayAt = shown.indexOfFirst { it.date <= today }.let { if (it < 0) (shown.size - 1).coerceAtLeast(0) else it }
                val rideList = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(rideFilter, shown.size) {
                    if (shown.isNotEmpty()) rideList.scrollToItem(todayAt)   // CHECKINORG: always at today
                }
                if (shown.isEmpty()) Text(if (rides.isEmpty()) "No rides on this tablet." else "No rides here.", color = dim, fontSize = 12.sp)
                else androidx.compose.foundation.lazy.LazyColumn(state = rideList, modifier = Modifier.fillMaxWidth().height(200.dp)) {
                    items(shown.size) { i ->
                        val r = shown[i]
                        FilterChip(selected = picked && chosen?.rideId == r.rideId,
                            onClick = { chosen = r; picked = true; role = defaultRole(r) },
                            label = { Text((if (r.date == today) "TODAY \u00b7 " else "") + r.name + " \u00b7 " + r.date +
                                (if (r.startTime.isNotBlank()) " " + r.startTime else "") +
                                (if (r.organizerName.isNotBlank()) " \u00b7 " + r.organizerName else "")) })
                    }
                }
                FilterChip(selected = picked && chosen == null, onClick = { chosen = null; picked = true },
                    label = { Text("No scheduled ride") })
                if (picked && chosen == null)
                    Text("A normal recording: no survey and no sharing \u2014 you name the track at the end.", color = dim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = callsign, onValueChange = { callsign = it.take(39) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Callsign for this ride") })
                if (me != null && callsign.trim() != me.callsign)
                    Text("For this ride only \u2014 your profile is not changed.", color = dim, fontSize = 12.sp)
                // CHECKINFIX2-2026-09-27 (Fred): the role is ALWAYS on this panel -- No scheduled ride included.
                Spacer(Modifier.height(8.dp))
                Text("Role", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    roles.take(2).forEach { (v, l) -> FilterChip(selected = role == v, onClick = { role = v }, label = { Text(l) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    roles.drop(2).forEach { (v, l) -> FilterChip(selected = role == v, onClick = { role = v }, label = { Text(l) }) }
                }
                if (chosen != null) Row {   // CHECKINMAP-2026-09-28
                    androidx.compose.material3.Checkbox(checked = showOnMap, onCheckedChange = { showOnMap = it })
                    Text("Show this ride's route and trailhead on the ride map", modifier = Modifier.padding(top = 12.dp), fontSize = 13.sp)
                }
                // CHECKINRADIO-2026-09-28 (Fred): NO RADIO, NO CHECK-IN -- every check-in, No scheduled ride included.
                if (!radioOn) {
                    Text("\u26D4 You can't check in without a radio. Connect it in GRP Awareness.", color = Color(0xFFF08C84), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { onCancel(); GrpAwarenessLauncher.open() }) { Text("OPEN GRP AWARENESS") }
                }
                // CHECKINAPPLY-2026-09-28 (Fred): the radio against this ride -- CHECK IN sets it up when needed.
                if (radioOn) chosen?.let { c ->
                    when {
                        needsSetup && last == null -> Text("\u26A0 Your radio has no GroupTrack configuration \u2014 it will be set up for " + c.name + " when you check in.", color = Color(0xFFE8A33D), fontSize = 13.sp)
                        needsSetup -> Text("\u26A0 Your radio is set up for " + last!!.second + " \u2014 it will be set up for " + c.name + " when you check in.", color = Color(0xFFE8A33D), fontSize = 13.sp)
                        else -> Text("\u2713 Radio ready for this ride", color = Color(0xFF35C46A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                kept?.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFFF08C84), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = picked && callsign.isNotBlank() && radioOn, onClick = {   // CHECKINRADIO: a radio is required
                if (needsSetup) {
                    // CHECKINAPPLY-2026-09-28 (Fred): set the radio up for this ride FIRST (the configurator's own apply,
                    // straight away), then check in -- or reopen with the choices kept if the setup fails.
                    val c = chosen!!
                    CheckInLauncher.pending = CheckInLauncher.Pending(c.rideId, callsign, role, showOnMap, "")
                    RadioConfigLauncher.autoRideId = c.rideId
                    RadioConfigLauncher.onAutoResult = { ok ->
                        val p = CheckInLauncher.pending
                        if (ok && p != null) {
                            CheckInLauncher.pending = null
                            ConvoyRideStore.checkIn(c, p.callsign, p.role)?.let { onDone(it, p.showOnMap) }
                        } else {
                            CheckInLauncher.pending = p?.copy(error = "The radio was not set up for " + c.name + " \u2014 CHECK IN to try again.")
                            CheckInLauncher.showing.value = true
                        }
                    }
                    onCancel()                 // the check-in closes while the radio is set up
                    RadioConfigLauncher.open()
                } else ConvoyRideStore.checkIn(chosen, callsign, role)?.let { onDone(it, chosen != null && showOnMap) }
            }) { Text("CHECK IN") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
