package com.grouptrack.shared.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.grouptrack.Gt
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.net.GtNetworkGenerator
import com.grouptrack.shared.platform.IosRideFiles
import com.grouptrack.shared.platform.installIosRideEnv
import com.grouptrack.shared.ride.GtRideJson
import com.grouptrack.shared.ride.GtRideStore
import com.grouptrack.shared.ride.RideNet
import com.grouptrack.shared.ride.RouteSummary
import com.grouptrack.shared.text.GtTime

/**
 * IOSRIDEFORM-2026-11-22: the ride form on the iPad -- Create a ride (step 2) and Edit.
 *
 * Transcribed from ConvoyRideCreateScreen. Every store call it makes is already shared, so this
 * is a form over existing services and adds no engine. ANDROID IS NOT TOUCHED.
 *
 * ⛔ CANCEL WRITES NOTHING (RIDECANCEL-2026-09-26) -- a ride is saved only by SAVE RIDE, so there
 * are no ghosts -- and it deletes a network that was generated but never used.
 *
 * ⛔ EDITING A SENT RIDE CHANGES ONLY THE DATE. The store refuses the rest (updateRide logs
 * "refused: distributed"), so the form asks only for the date rather than offering fields that
 * will be silently dropped.
 *
 * Not here, deliberately: the trailhead search and the stored route preview -- see the patch note.
 */
@Composable
fun GtIosRideForm(
    /** CODE RULE 1: null = no route chosen yet. */
    initialRouteId: String?,
    /** CODE RULE 1: null = creating a ride; a value = editing that one. */
    editRideId: String?,
    onRideCreated: (String) -> Unit,
    onBack: () -> Unit,
    onMessage: (String, String) -> Unit,
) {
    val log = remember { gtLogger("GtIosRideForm") }
    val fs = remember { IosRideFiles() }

    var rideName by remember { mutableStateOf("") }
    var rideDate by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // ⚠ EMPTY on the iPad: Android seeds this from ConvoySessionManager.getZipCode(context),
    // which has no iOS source and is not in RideMe. The field is editable and saveRide takes it.
    var zipCode by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var useDefaultNetwork by remember { mutableStateOf(true) }
    var newNetworkForRide by remember { mutableStateOf(false) }
    var rideNetwork by remember { mutableStateOf<RideNet?>(null) }
    var routeId by remember { mutableStateOf(initialRouteId ?: "") }
    var routes by remember { mutableStateOf<List<RouteSummary>>(emptyList()) }
    var routePts by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var myNetworkName by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var editSent by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            installIosRideEnv()
            // RIDESEARCH-2026-10-24: an area may hold older routes, so the area case asks for more.
            routes = GtRideStore.listRoutes(200)
            myNetworkName = GtRideStore.ensureMyNetworkName()
        } catch (e: Exception) {
            log.e { "IOSRIDEFORM-2026-11-22: load failed: " + e.message }
            status = "Could not read the routes: " + e.message
        }
    }

    LaunchedEffect(editRideId) {
        val rid = editRideId ?: return@LaunchedEffect
        try {
            editSent = GtRideStore.isDistributed(rid)
            val e = GtRideStore.rideForEdit(rid) ?: return@LaunchedEffect
            // IOSRIDEFORMFIX-2026-11-22: EditRide's fields are name/date (GtRides.kt:938).
            rideName = e.name
            rideDate = e.date
            startTime = e.startTime
            description = e.description
            isPrivate = !e.isPublic
            routeId = e.routeId
            // DEFAULTNET-2026-10-10: the default network is recognised by its channel id.
            useDefaultNetwork = e.configId == GtNetworkGenerator.DEFAULT_CHANNEL
        } catch (ex: Exception) {
            log.e { "IOSRIDEFORM-2026-11-22: rideForEdit failed: " + ex.message }
        }
    }

    LaunchedEffect(routeId) {
        routePts = try {
            if (routeId.isBlank()) emptyList()
            else GtRideStore.routeGeometry(routeId)?.let { GtRideStore.parseWktLine(it) } ?: emptyList()
        } catch (e: Exception) {
            log.w { "IOSRIDEFORM-2026-11-22: geometry failed: " + e.message }
            emptyList()
        }
    }

    val canSave = rideName.isNotBlank()
    val netChoice = if (useDefaultNetwork) 0 else if (newNetworkForRide) 2 else 1

    Box(modifier = Modifier.fillMaxSize().background(Gt.panel), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(14.dp), color = Gt.panel) {
            Column(
                modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (editRideId == null) "Create a ride" else "Edit ride",
                        color = Gt.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) { Text("✕", color = Gt.accent, fontSize = 18.sp) }
                }
                if (editSent) Text(
                    "This ride has been sent — only its date can change.",
                    color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )

                field("Ride name", rideName, enabled = !editSent) { rideName = it }

                Text("Date", color = Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    rideDate.ifBlank { "tap to choose" },
                    color = if (rideDate.isBlank()) Gt.warn else Gt.text, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(vertical = 10.dp),
                )
                if (showDatePicker) RideDatePicker(
                    initial = rideDate,
                    onPick = { rideDate = it; showDatePicker = false },
                    onDismiss = { showDatePicker = false },
                )

                if (!editSent) {
                    field("Start time", startTime) { startTime = it }
                    field("Description", description) { description = it }
                    field("Zip code", zipCode) { zipCode = it }

                    Text(
                        (if (isPrivate) "◉  " else "○  ") + "Private ride (not forwarded)",
                        color = Gt.accent, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { isPrivate = !isPrivate }.padding(vertical = 6.dp),
                    )

                    // THREENET-2026-10-10: the three network choices, in Android's order and words.
                    Text("Network", color = Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    listOf(
                        0 to "GroupTrack default network",
                        1 to (myNetworkName ?: "My network — not available, check your rider profile"),
                        2 to "Create a private network for this ride",
                    ).forEach { (opt, label) ->
                        Text(
                            (if (netChoice == opt) "◉  " else "○  ") + label,
                            color = if (netChoice == opt) Gt.accent else Gt.text, fontSize = 13.sp,
                            // EDITRIDE: a ride's network is not changed here.
                            modifier = Modifier.fillMaxWidth().clickable(enabled = editRideId == null) {
                                useDefaultNetwork = opt == 0
                                newNetworkForRide = opt == 2
                                // generated now, used only when the ride is added
                                if (opt == 2 && rideNetwork == null) {
                                    rideNetwork = try { GtRideStore.createRideNetwork() } catch (e: Exception) {
                                        log.e { "IOSRIDEFORM-2026-11-22: createRideNetwork failed: " + e.message }; null
                                    }
                                }
                            }.padding(vertical = 6.dp),
                        )
                    }
                    Text(
                        "Network assigned: " + when (netChoice) {
                            0 -> "GroupTrack default"
                            2 -> rideNetwork?.configId?.let { "Private – " + it }
                                ?: "⚠ not created — check your rider profile"
                            else -> myNetworkName ?: "⚠ not available — check your rider profile"
                        },
                        color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )

                    Text("Route", color = Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                        if (routes.isEmpty()) Text("No routes on this iPad yet.", color = Gt.warn, fontSize = 12.sp)
                        routes.forEach { r ->
                            Text(
                                (if (r.routeId == routeId) "◉  " else "○  ") + r.name,
                                color = if (r.routeId == routeId) Gt.accent else Gt.text, fontSize = 13.sp,
                                // PROTECTROUTE-2026-09-24: a ride's route is not changed on edit.
                                modifier = Modifier.fillMaxWidth().clickable(enabled = editRideId == null) {
                                    routeId = r.routeId
                                }.padding(vertical = 5.dp),
                            )
                        }
                    }
                    if (routeId.isNotBlank()) RouteVignette(routePts)
                }

                if (status.isNotBlank()) Text(status, color = Gt.warn, fontSize = 13.sp)

                Spacer(Modifier.height(4.dp))
                Text(
                    if (editRideId == null) "SAVE RIDE" else "SAVE CHANGES",
                    color = if (canSave) Gt.accent else Gt.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = canSave) {
                        status = save(
                            fs = fs, editRideId = editRideId, editSent = editSent,
                            rideName = rideName, rideDate = rideDate, startTime = startTime,
                            description = description, zipCode = zipCode, isPrivate = isPrivate,
                            useDefaultNetwork = useDefaultNetwork, newNetworkForRide = newNetworkForRide,
                            rideNetwork = rideNetwork, routeId = routeId, log = log,
                            onCreated = onRideCreated,
                        )
                        onMessage("rides.ride.form", status)
                    }.padding(vertical = 12.dp),
                )
                // RIDECANCEL-2026-09-26: nothing is written; a generated-but-unused network goes.
                Text(
                    "CANCEL", color = Gt.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clickable {
                        rideNetwork?.configId?.let { id ->
                            runCatching { GtRideStore.env.networks.deleteUnused(id) }
                        }
                        onBack()
                    }.padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun field(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    Text(label, color = Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = label != "Description",
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(color = Gt.text, fontSize = 15.sp),
    )
}

/**
 * ConvoyRideCreateScreen:480's dialog. material3's DatePicker is in Compose Multiplatform, so only
 * the java.time conversions are replaced -- with GtTime, which is certified shared math:
 * gregorianEpochDay for the day number in, gregorianCivil for the civil date out.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)  // IOSRIDEFORMFIX-2026-11-22: rememberDatePickerState is experimental
private fun RideDatePicker(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val initialMillis: Long? = remember(initial) {
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(initial.trim())?.let { m ->
            val (y, mo, d) = m.destructured
            val month = mo.toIntOrNull()
            val day = d.toIntOrNull()
            if (month == null || day == null) null
            else GtTime.gregorianEpochDay(y.toLong(), month, day) * 86_400_000L
        }
    }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val ms = state.selectedDateMillis
                if (ms == null) onDismiss() else {
                    val (y, m, d) = GtTime.gregorianCivil(ms / 86_400_000L)
                    onPick(y.toString() + "-" + two(m) + "-" + two(d))
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

private fun two(v: Int): String = if (v < 10) "0" + v else v.toString()

/** ConvoyRideCreateScreen:502 -- the vignette: the chosen route drawn from stored geometry. */
@Composable
private fun RouteVignette(pts: List<Pair<Double, Double>>) {
    Box(modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)).background(Gt.panel)) {
        if (pts.size < 2) {
            Text(
                "no geometry", color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val xs = pts.map { it.first }
                val ys = pts.map { it.second }
                val minX = xs.min(); val maxX = xs.max()
                val minY = ys.min(); val maxY = ys.max()
                val spanX = (maxX - minX).takeIf { it > 1e-9 } ?: 1e-9
                val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1e-9
                val scale = minOf(size.width / spanX, size.height / spanY)
                val offX = (size.width - spanX * scale) / 2
                val offY = (size.height - spanY * scale) / 2
                fun at(p: Pair<Double, Double>) = Offset(
                    (offX + (p.first - minX) * scale).toFloat(),
                    (offY + (maxY - p.second) * scale).toFloat(),
                )
                for (i in 0 until pts.size - 1) {
                    drawLine(color = Gt.accent, start = at(pts[i]), end = at(pts[i + 1]), strokeWidth = 3f)
                }
            }
        }
    }
}

/**
 * SAVE. Create inserts; edit updates -- and a SENT ride takes only its date, which the store
 * enforces (updateRide refuses a distributed ride) rather than this form guessing.
 * RIDEFILE-2026-09-23: the ride JSON is stored on every save, so a re-send carries the change.
 */
private fun save(
    fs: com.grouptrack.shared.ride.RideFiles,
    editRideId: String?,
    editSent: Boolean,
    rideName: String,
    rideDate: String,
    startTime: String,
    description: String,
    zipCode: String,
    isPrivate: Boolean,
    useDefaultNetwork: Boolean,
    newNetworkForRide: Boolean,
    rideNetwork: RideNet?,
    routeId: String,
    log: co.touchlab.kermit.Logger,
    onCreated: (String) -> Unit,
): String = try {
    if (editRideId != null) {
        val ok = if (editSent) GtRideStore.updateRideDate(editRideId, rideDate)
        else GtRideStore.updateRide(
            editRideId, rideName, rideDate, startTime, description, zipCode, !isPrivate, routeId,
        )
        if (!ok) "✗ Could not save the change." else {
            val missing = GtRideJson.save(fs, editRideId)
            when {
                missing == null -> "✓ Saved — ✗ ride file not written"
                missing.isEmpty() -> "✓ Saved — ready to send"
                else -> "✓ Saved in progress — missing: " + missing.joinToString(", ")
            }
        }
    } else {
        // RIDENET-2026-09-27: first ride -> say so once.
        val wasOrganizer = GtRideStore.isOrganizer()
        val id = GtRideStore.saveRide(
            rideName = rideName, rideDate = rideDate, startTime = startTime,
            description = description, zipCode = zipCode, isPublic = !isPrivate,
            // DEFAULTNET-2026-10-10 / FORMLAYOUT-2026-09-27
            rideNetworkId = if (useDefaultNetwork) GtNetworkGenerator.DEFAULT_CHANNEL
            else if (newNetworkForRide) rideNetwork?.configId else null,
            routeId = routeId,
        )
        if (id == null) "✗ Could not save — is there a rider profile?" else {
            val missing = GtRideJson.save(fs, id)
            var out = when {
                missing == null -> "✓ Ride saved — ✗ ride file not written"
                missing.isEmpty() -> "✓ Ride saved — ready to send"
                else -> "✓ Saved in progress — missing: " + missing.joinToString(", ")
            }
            // FORMLAYOUT: a network generated but not chosen is removed, so there is no ghost.
            if (!newNetworkForRide) rideNetwork?.configId?.let { cid ->
                runCatching { GtRideStore.env.networks.deleteUnused(cid) }
            }
            if (!wasOrganizer && !useDefaultNetwork && !newNetworkForRide) {
                out += "\n✓ You're now an organizer — your rides use your own network: " +
                    (GtRideStore.myNetworkName() ?: "created with this ride") + "."
            }
            log.i { "IOSRIDEFORM-2026-11-22: saved ride " + id }
            onCreated(id)
            out
        }
    }
} catch (e: Exception) {
    log.e { "IOSRIDEFORM-2026-11-22: save failed: " + e.message }
    "✗ Save failed: " + e.message
}
