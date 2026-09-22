package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PROFILE-2026-09-22 — the rider profile screen. ONE screen, two modes:
 *
 *  - FIRST LAUNCH (onCancel == null): reached from the authority gate when no profile
 *    exists. The rider cannot skip it -- the callsign has to be in every TAK packet.
 *  - EDIT (onCancel != null): reached from settings, pre-filled from the stored row.
 *
 * Required: callsign and email. Everything else seeds each ride's enrollment.
 */
private val BG = Color(0xFF101510)
private val CARD = Color(0xFF151C22)
private val LINE = Color(0xFF2E3A45)
private val GREEN = Color(0xFF97D5A5)
private val MUTED = Color(0xFF8B938A)
private val AMBER = Color(0xFFFFB74D)

private val ROLES = listOf("leader" to "Leader", "rider" to "Rider", "middle" to "Middle", "tail_gunner" to "Tail gunner")
private val TEAMS = listOf(
    "Cyan" to Color(0xFF22C1D6), "Green" to Color(0xFF3FB950), "Yellow" to Color(0xFFE3B341),
    "Orange" to Color(0xFFF0883E), "Red" to Color(0xFFF85149), "Purple" to Color(0xFFA371F7)
)

@Composable
fun ConvoyRiderProfileScreen(
    onSaved: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val editMode = onCancel != null

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var callsign by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var cell by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("rider") }
    var vehicle by remember { mutableStateOf("") }
    var team by remember { mutableStateOf("Cyan") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        ConvoyProfileStore.load()?.let { p ->
            firstName = p.firstName; lastName = p.lastName; callsign = p.callsign
            email = p.email; cell = p.cell; vehicle = p.vehicleType
            if (p.defaultRole.isNotBlank()) role = p.defaultRole
            if (p.team.isNotBlank()) team = p.team
        }
    }

    val canSave = callsign.isNotBlank() && email.contains("@") && email.length > 4

    Box(modifier = Modifier.fillMaxSize().background(BG)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                if (editMode) "YOUR RIDER PROFILE" else "SET UP YOUR RIDER PROFILE",
                color = GREEN, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                if (editMode) "Change any of it -- your callsign and email stay required."
                else "Your group sees your callsign on the map and in every radio message.",
                color = MUTED, fontSize = 13.sp
            )

            Field("First name", firstName, { firstName = it })
            Field("Last name", lastName, { lastName = it })
            Field("Callsign  · required", callsign, { callsign = it }, mono = true, highlight = callsign.isBlank())
            Text("Shown to your group. Two riders may share a callsign -- radios tell you apart.",
                color = MUTED, fontSize = 12.sp)
            Field("Email  · required", email, { email = it }, highlight = !canSave)
            Text("Used when you send a ride file, or share a ride with the community.",
                color = MUTED, fontSize = 12.sp)
            Field("Cell  · optional", cell, { cell = it })

            Spacer(Modifier.height(2.dp))
            Text("HOW YOU USUALLY RIDE", color = GREEN, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Defaults for each ride you join -- change them on any ride.", color = MUTED, fontSize = 12.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ROLES.forEach { (key, label) ->
                    val on = role == key
                    Surface(
                        modifier = Modifier.weight(1f).height(46.dp).clickable { role = key },
                        shape = RoundedCornerShape(8.dp),
                        color = if (on) Color(0xFF1C2A22) else Color(0xFF0E1317),
                        border = androidx.compose.foundation.BorderStroke(if (on) 2.dp else 1.dp, if (on) GREEN else LINE)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(label, color = if (on) Color.White else MUTED, fontSize = 14.sp)
                        }
                    }
                }
            }

            Field("Vehicle", vehicle, { vehicle = it })

            Text("Team colour on the map", color = MUTED, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TEAMS.forEach { (name, colour) ->
                    val on = team == name
                    Box(
                        modifier = Modifier.size(40.dp).clickable { team = name },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(if (on) 36.dp else 30.dp),
                            shape = CircleShape,
                            color = colour,
                            border = androidx.compose.foundation.BorderStroke(if (on) 3.dp else 0.dp, Color.White)
                        ) {}
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0E1317),
                border = androidx.compose.foundation.BorderStroke(1.dp, LINE)
            ) {
                Text(
                    "This stays on your tablet. GroupTrack uses it only to identify you to your " +
                        "group and to share rides you send.",
                    color = Color(0xFFC9D4DD), fontSize = 13.sp, modifier = Modifier.padding(14.dp)
                )
            }

            if (status.isNotBlank()) Text(status, color = AMBER, fontSize = 13.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (editMode) {
                    Surface(
                        modifier = Modifier.weight(1f).height(52.dp).clickable { onCancel?.invoke() },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0E1317),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LINE)
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("Cancel", color = MUTED, fontSize = 16.sp) }
                    }
                }
                Surface(
                    modifier = Modifier.weight(if (editMode) 1.4f else 1f).height(52.dp).clickable(enabled = canSave) {
                        val id = ConvoyProfileStore.save(
                            RiderProfile(
                                firstName = firstName.trim(), lastName = lastName.trim(),
                                callsign = callsign.trim(), email = email.trim(), cell = cell.trim(),
                                defaultRole = role, vehicleType = vehicle.trim(), team = team
                            )
                        )
                        if (id != null) onSaved() else status = "Could not save -- check the callsign and email."
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = if (canSave) GREEN else Color(0xFF2B3A31)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (editMode) "Save" else "Save & open the map",
                            color = if (canSave) Color(0xFF0E1317) else MUTED,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    mono: Boolean = false,
    highlight: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = if (highlight) AMBER else MUTED, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White, fontSize = 16.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF0E1317),
                unfocusedContainerColor = Color(0xFF0E1317),
                focusedIndicatorColor = GREEN,
                unfocusedIndicatorColor = LINE,
                cursorColor = GREEN
            )
        )
    }
}
