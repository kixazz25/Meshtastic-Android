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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * CHECKIN-2026-09-27 (Fred) -- the END-OF-RIDE FORM, reading the CHECK-IN (the ride is resolved before recording).
 *  - A ride: the track is NAMED AFTER THE RIDE -- fixed, not editable (the server names a ride's track after the ride and
 *    keeps ONE track per ride, the first shared to arrive). PUBLIC ride: rating, difficulty, recommend (required),
 *    notes (optional), "Share this ride's track with the community?" Yes/No (REQUIRED, no default). PRIVATE ride: no
 *    survey, no sharing.
 *  - No scheduled ride: the rider names the track; no survey, no sharing.
 *  - SAVE only when everything required is done. Non-cancelable; DELETE keeps the old confirmation.
 * The frozen convoy map calls this in place of its old "Save Track" dialog.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EndOfRideForm(
    name: String,
    onNameChange: (String) -> Unit,
    checkIn: ConvoyRideStore.CheckIn?,                         // CODE RULE 1: null = no check-in (e.g. after a restart)
    onSave: (ConvoyRideStore.RideSurveyInput?) -> Unit,        // null = no survey
    onDelete: () -> Unit,
) {
    val rideId = checkIn?.rideId
    val rideName = checkIn?.rideName
    LaunchedEffect(rideId) { if (rideId != null && rideName != null) onNameChange(rideName) }
    val survey = rideId != null && checkIn?.isPublic == true
    var rating by remember { mutableStateOf(0) }
    var difficulty by remember { mutableStateOf("") }
    var recommend by remember { mutableStateOf<Boolean?>(null) }   // CODE RULE 1: null = not answered yet
    var notes by remember { mutableStateOf("") }
    var share by remember { mutableStateOf<Boolean?>(null) }       // CODE RULE 1: null = not answered yet (required)
    val surveyDone = !survey || (rating in 1..5 && difficulty.isNotEmpty() && recommend != null && share != null)
    val canSave = surveyDone && name.isNotBlank()
    val dim = Color(0xFF8899AA)

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Save your ride") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (rideId != null) {
                    Text("Ride: ${rideName ?: ""}", fontWeight = FontWeight.Bold)
                    if (!survey) Text("A private ride: no survey and no sharing \u2014 your track stays on this tablet.", color = dim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                if (survey) {
                    Text("How was it?", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        (1..5).forEach { n ->
                            TextButton(onClick = { rating = n }) {
                                Text(if (n <= rating) "\u2605" else "\u2606", fontSize = 26.sp,
                                    color = if (n <= rating) Color(0xFFFFC107) else dim)
                            }
                        }
                    }
                    Text("Difficulty", color = dim, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("easy" to "Easy", "moderate" to "Moderate", "hard" to "Hard").forEach { (v, l) ->
                            FilterChip(selected = difficulty == v, onClick = { difficulty = v }, label = { Text(l) })
                        }
                    }
                    Text("Would you recommend it?", color = dim, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = recommend == true, onClick = { recommend = true }, label = { Text("Yes") })
                        FilterChip(selected = recommend == false, onClick = { recommend = false }, label = { Text("No") })
                    }
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Notes (optional)") }, minLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Text("Share this ride's track with the community?", fontWeight = FontWeight.Bold)
                    Text("Your track helps map the trails. Only you decide.", color = dim, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = share == true, onClick = { share = true }, label = { Text("Yes, share it") })
                        FilterChip(selected = share == false, onClick = { share = false }, label = { Text("No, keep it") })
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(value = name, onValueChange = { if (rideId == null) onNameChange(it) },
                    readOnly = rideId != null, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (rideId != null) "Track name (the ride's name)" else "Track name") })
                if (!canSave) {
                    val missing = buildList {
                        if (survey && rating !in 1..5) add("a rating")
                        if (survey && difficulty.isEmpty()) add("the difficulty")
                        if (survey && recommend == null) add("recommend yes or no")
                        if (survey && share == null) add("share yes or no")
                        if (name.isBlank()) add("a track name")
                    }
                    Text("To save: " + missing.joinToString(", "), color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                onSave(if (survey && rideId != null) ConvoyRideStore.RideSurveyInput(
                    rideId = rideId, rating = rating, difficulty = difficulty, recommend = recommend == true,
                    notes = notes.trim(), shareTrack = share == true) else null)
            }) { Text("SAVE") }
        },
        dismissButton = { TextButton(onClick = onDelete) { Text("DELETE") } },
    )
}
