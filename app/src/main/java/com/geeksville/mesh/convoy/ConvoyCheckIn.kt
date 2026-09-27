package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 *  - "Set up radio for this ride" -> Apply ride to radio (the configurator), closing the check-in.
 * Completing the check-in does NOT start recording: the button then reads REC (a second tap records).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CheckInSheet(onDone: (ConvoyRideStore.CheckIn) -> Unit, onCancel: () -> Unit) {
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
    var chosen by remember { mutableStateOf(todays.singleOrNull()) }
    var picked by remember { mutableStateOf(todays.size == 1) }   // otherwise the rider must choose
    var callsign by remember { mutableStateOf(me?.callsign ?: "") }
    var role by remember { mutableStateOf(defaultRole(chosen)) }
    val dim = Color(0xFF8899AA)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Check in") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Which ride?", fontWeight = FontWeight.Bold)
                rides.forEach { r ->
                    FilterChip(selected = picked && chosen?.rideId == r.rideId,
                        onClick = { chosen = r; picked = true; role = defaultRole(r) },
                        label = { Text(r.name + " \u00b7 " + r.date + (if (r.startTime.isNotBlank()) " " + r.startTime else "") +
                            (if (r.organizerName.isNotBlank()) " \u00b7 " + r.organizerName else "")) })
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
                if (chosen != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Role on this ride", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        roles.take(2).forEach { (v, l) -> FilterChip(selected = role == v, onClick = { role = v }, label = { Text(l) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        roles.drop(2).forEach { (v, l) -> FilterChip(selected = role == v, onClick = { role = v }, label = { Text(l) }) }
                    }
                    TextButton(onClick = { onCancel(); RadioConfigLauncher.open() }) { Text("Set up radio for this ride") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked && callsign.isNotBlank(), onClick = {
                ConvoyRideStore.checkIn(chosen, callsign, if (chosen == null) "rider" else role)?.let(onDone)
            }) { Text("CHECK IN") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
