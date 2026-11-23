package com.grouptrack.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.grouptrack.Gt
import com.grouptrack.comm.CommConfig
import com.grouptrack.comm.CommResult
import com.grouptrack.shared.comm.GtRadioApply
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.platform.IosRideFiles
import com.grouptrack.shared.platform.installIosRideEnv
import com.grouptrack.shared.ride.GtRideStore
import com.grouptrack.shared.ride.RideRow
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.platform.IosArtifactSink
import com.grouptrack.shared.ride.GtRideJson
import com.grouptrack.shared.ride.RideFiles
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * IOSMYRIDES-2026-11-22: Rides > My rides, on the iPad.
 *
 * Fred 14:27: "my rides all function exist." True -- the engines are shared and certified; what
 * was missing was a panel over them. Android's row is Send / Edit / Apply to radio / Delete
 * (LaunchModules.kt:136); THREE are here and SEND is deliberately not drawn (see the patch note:
 * UIActivityViewController cannot carry an HTML body, and the invite links to the Play Store --
 * both are product decisions, not porting work).
 *
 * ⛔ ANDROID IS NOT TOUCHED and no shared code is added. Every call below already existed.
 */
@Composable
fun GtIosMyRides(
    /** GtIosPanels:965 holds ONE GtIosCommConfig for the screen -- passed in so the apply path and
     *  the check-in path cannot diverge. */
    config: CommConfig,
    /** IOSRIDEFORM-2026-11-22: Edit hands the ride to the form the host owns. */
    onEdit: (String) -> Unit,
    onClose: () -> Unit,
    onMessage: (String, String) -> Unit,
) {
    val log = remember { gtLogger("GtIosMyRides") }
    val fs = remember { IosRideFiles() }
    val scope = rememberCoroutineScope()
    var rides by remember { mutableStateOf<List<RideRow>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<RideRow?>(null) }

    LaunchedEffect(reload) {
        // ⚠ GtRideStore.env is lateinit and is NOT set at startup -- installIosRideEnv() is called
        // only from the check-in path (GtIosPanels:1520). An uncaught Kotlin throw on Native ABORTS
        // THE PROCESS, so it is installed here before the first store call. Idempotent by its own doc.
        rides = try {
            installIosRideEnv()
            GtRideStore.listRides()
        } catch (e: Exception) {
            log.e { "IOSMYRIDES-2026-11-22: listRides failed: " + e.message }
            status = "Could not read the rides: " + e.message
            emptyList()
        }
        if (rides.isEmpty() && status.isBlank()) status = "No rides on this iPad yet."
    }

    Box(modifier = Modifier.fillMaxSize().background(Gt.panel), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp), color = Gt.panel) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("My rides", color = Gt.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // IOSRIDEHDR2-2026-11-23: the panel standard -- HELP . ISSUE . close, from the host chrome
                    // (GtPanel.kt:260-262). Was a hand-rolled X with no HELP and no ISSUE.
                    val chrome = com.geeksville.mesh.grouptrack.LocalGtPanelChrome.current
                    TextButton(onClick = { chrome.openHelp("rides.mine") }) {
                        Text("HELP", color = Gt.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    chrome.IssueButton("rides.mine", Gt.accent)
                    com.geeksville.mesh.grouptrack.GtCloseButton(onClose)
                }
                if (status.isNotBlank()) Text(status, color = Gt.warn, fontSize = 13.sp)

                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    rides.forEach { r ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(r.rideName.ifBlank { "Ride" }, color = Gt.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                (r.rideDate + (if (r.startTime.isBlank()) "" else " \u00b7 " + r.startTime)) +
                                    (if (r.organizerName.isBlank()) "" else " \u00b7 " + r.organizerName),
                                color = Gt.dim, fontSize = 12.sp,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    enabled = busy.isBlank(),
                                    onClick = {
                                        busy = r.rideId
                                        status = ""
                                        scope.launch {
                                            // RADIOAPPLY-2026-11-21. The service does its half on the iPad
                                            // already: targetFromFile reads through the RideFiles seam, and
                                            // only the comm WRITE is NotSupported until there is a radio.
                                            val said = try {
                                                val callsign = GtRideStore.env.profile()?.callsign ?: ""
                                                val target = GtRadioApply.targetFromFile(
                                                    fs = fs,
                                                    fileName = r.rideId + ".json",
                                                    callsign = callsign,
                                                    title = r.rideName,
                                                    saveTitle = r.rideName,
                                                    rideId = r.rideId,
                                                )
                                                if (target == null) {
                                                    "That ride has no saved configuration file."
                                                } else {
                                                    val res = GtRadioApply.write(config, target)
                                                    when {
                                                        GtRadioApply.verified(res) -> "Applied to the radio and verified."
                                                        GtRadioApply.noRadio(res) -> "No radio connected."
                                                        res is CommResult.NotSupported ->
                                                            "This iPad cannot write to a radio yet."
                                                        res is CommResult.Failed -> "The radio refused it (" + res.reason + ")."
                                                        res is CommResult.Applied ->
                                                            // ⚠ Applied with verified=false is WRITTEN BUT NOT
                                                            // CONFIRMED, which Android does not treat as done
                                                            // (RADIOSETUPTEST-2026-10-01).
                                                            "Written, but the radio did not confirm it."
                                                        else -> "The apply did not report a result."
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                log.e { "IOSMYRIDES-2026-11-22: apply failed: " + e.message }
                                                "Could not apply that ride: " + e.message
                                            }
                                            busy = ""
                                            status = said
                                            onMessage("rides.mine", said)
                                        }
                                    },
                                ) { Text(if (busy == r.rideId) "APPLYING\u2026" else "APPLY TO RADIO", color = Gt.accent) }
                                // MYRIDESSEND-2026-11-22: Android's Send (ConvoyRideSend.send).
                                TextButton(
                                    enabled = busy.isBlank(),
                                    onClick = {
                                        busy = r.rideId
                                        status = ""
                                        scope.launch {
                                            val said = sendRide(fs, r, log)
                                            busy = ""
                                            status = said
                                            reload += 1   // THE LOCK changes the row
                                            onMessage("rides.mine", said)
                                        }
                                    },
                                ) { Text(if (busy == r.rideId) "SENDING\u2026" else "SEND", color = Gt.accent) }
                                // IOSRIDEFORM-2026-11-22: Edit opens the ride form on this ride,
                                // as RidesScreen.kt:170 does -- the same screen Create uses.
                                TextButton(enabled = busy.isBlank(), onClick = { onEdit(r.rideId) }) {
                                    Text("EDIT", color = Gt.accent)
                                }
                                TextButton(enabled = busy.isBlank(), onClick = { confirmDelete = r }) {
                                    Text("DELETE", color = Gt.bad)
                                }
                            }
                        }
                    }
                }

                // MYRIDESSEND-2026-11-22: EDIT is the one action still not drawn -- it is
                // ConvoyRideCreateScreen (596 lines), which is Create-a-Ride work, a different row.
                Text(
                    "Sending locks the ride \u2014 after the first send only its date can change.",
                    color = Gt.dim, fontSize = 12.sp,
                )
            }
        }
    }

    // Android confirms before deleting (RidesScreen.kt:185-194); same here, same reason string.
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \u201c" + r.rideName + "\u201d?") },
            text = {
                Text(
                    "This removes the ride, its waypoints and its saved file from this iPad. " +
                        "It cannot be undone.",
                    color = Gt.text,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = try {
                        GtRideStore.deleteRide(fs, r.rideId, "deleted by the rider")
                    } catch (e: Exception) {
                        log.e { "IOSMYRIDES-2026-11-22: delete failed: " + e.message }
                        false
                    }
                    status = if (ok) "Deleted \u201c" + r.rideName + "\u201d."
                    else "Could not delete \u201c" + r.rideName + "\u201d."
                    confirmDelete = null
                    reload += 1
                    onMessage("rides.mine", status)
                }) { Text("DELETE", color = Gt.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("CANCEL") } },
            containerColor = Gt.panel,
            titleContentColor = Gt.text,
            textContentColor = Gt.text,
        )
    }
}


// ── SEND (MYRIDESSEND-2026-11-22) ───────────────────────────────────────────────────────

private const val PLAY_URL =
    "https://play.google.com/store/apps/details?id=com.grouptrack.android&hl=en_US"

/** STEP14E-B-2026-11-08, verbatim from ConvoyRideSend.kt:28-29. */
private const val NO_GT_TEXT =
    "Using another navigation app? Download the two GPX attachments to your Download folder, then " +
        "import them with your app's import function: one is the route, the other is the trailhead " +
        "and the ride's waypoints."

/**
 * File-safe, as ConvoyRideSend.kt:48-50 builds it. ⚠ The date is used AS STORED: Android renders
 * "Wed 7 Oct" and falls back to the raw date when its parse fails, and the long form is not worth
 * 45 lines of date arithmetic in a panel (Fred 14:51).
 */
private fun sendLabel(name: String, date: String): String =
    (name.trim() + (if (date.isBlank()) "" else " - " + date))
        .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(60)
        .ifBlank { "GroupTrack OG ride" }

/**
 * RIDESEND-2026-09-23 on the iPad, transcribed from ConvoyRideSend.send -- which is why the copy,
 * the attachment order and THE LOCK are kept exactly.
 */
private suspend fun sendRide(
    fs: RideFiles,
    r: RideRow,
    log: co.touchlab.kermit.Logger,
): String = try {
    val missing = GtRideJson.save(fs, r.rideId)
    when {
        missing == null -> "The ride file could not be written."
        missing.isNotEmpty() -> "Not ready to send — missing: " + missing.joinToString(", ")
        else -> {
            val stored = GtRideJson.rideFile(fs, r.rideId)
            if (!fs.exists(stored)) "The ride file is missing." else {
                val text = fs.readText(stored)
                val name = r.rideName.ifBlank { "Ride" }
                val label = sendLabel(name, r.rideDate)

                // ride_send holds only THIS send, as Android empties its cache folder per send.
                val dir = fs.child(fs.ridesDir(), "ride_send")
                fs.mkdirs(dir)
                fs.list(dir)?.forEach { old -> fs.delete(fs.child(dir, old)) }

                val handles = ArrayList<String>()
                // INVITE2-2026-10-03: named after the ride; .convoy is what opens it in GroupTrack.
                val convoy = fs.child(dir, label + ".convoy")
                fs.writeText(convoy, text)
                handles.add(convoy)

                // STEP14E-2026-11-08: riders WITHOUT GroupTrack get the route and the trailhead +
                // waypoints as two GPX files, cut from the package's OWN gpx (shared splitGpx) so
                // they can never disagree with it.
                val root = GtOrgJson.parse(text) as? JsonObject
                val ride = root?.get("ride") as? JsonObject
                val rideData = root?.get("rideData") as? JsonObject
                val rawGpx = GtOrgJson.optString(rideData, "gpx", "")
                var hasGpx = false
                if (rawGpx.isNotBlank() && rawGpx != "null") {
                    val parts = GtRideJson.splitGpx(rawGpx)
                    val route = fs.child(dir, label + " - route.gpx")
                    fs.writeText(route, parts.route)
                    handles.add(route)
                    hasGpx = true
                    parts.waypoints?.let { w ->
                        val wpt = fs.child(dir, label + " - trailhead and waypoints.gpx")
                        fs.writeText(wpt, w)
                        handles.add(wpt)
                    }
                }

                // RIDEPUBLIC-2026-09-27: open rides may be forwarded; private rides may not.
                val forward = if (GtOrgJson.optBoolean(ride, "isPublic", false))
                    "This is an open ride. Feel free to forward this email to friends who would like to come."
                else
                    "This is a private ride for the riders invited. Please don't forward this email."
                val convoyWord = if (hasGpx) ".convoy " else ""
                // RIDEMAIL-2026-09-25: the email COACHES the rider. Verbatim, ConvoyRideSend.kt:72-90.
                val invite = "You're invited: " + name + " (" + r.rideDate + ")\n\n" + forward + "\n\n" +
                    "Don't be left behind! Click the link below to download GroupTrack Off Grid on your Android, " +
                    "and reap the benefits beginning with this ride!\n" + PLAY_URL + "\n\n" +
                    "If you're a GroupTrack OG user, tap the " + convoyWord +
                    "attachment below, choose GroupTrack OG, then tap \"Always\". " +
                    "This imports the route, and downloads the maps for offline use during this ride.\n\n" +
                    "Riding with a mesh radio? Check in at the trailhead and GroupTrack OG sets your radio up for this ride.\n\n" +
                    (if (hasGpx) NO_GT_TEXT + "\n\n" else "") +
                    "Enjoy the ride!"

                // UIActivityViewController drops the subject (EMAILALL-2026-11-20), so it leads the
                // body. Android also sends an HTML invite; its own note says apps that ignore HTML
                // show the plain text, so taking the plain text always is the designed fallback.
                val subject = "GroupTrack OG ride: " + name + " — " + r.rideDate
                val ok = IosArtifactSink().emailAll(handles, subject, subject + "\n\n" + invite)

                // THE LOCK (ConvoyRideSend.kt:126) -- shared, so the iPad locks as the droid does.
                val locked = GtRideStore.markDistributed(r.rideId)
                log.i {
                    "MYRIDESSEND-2026-11-22: sent " + r.rideId + " with " + handles.size +
                        " file(s), presented=" + ok + " locked=" + locked
                }
                if (ok) "Ride ready to send — " + handles.size + " file(s). The ride is now locked."
                else "Nothing took the email. The files are in the rides folder; the ride is locked."
            }
        }
    }
} catch (e: Exception) {
    log.e { "MYRIDESSEND-2026-11-22: send failed: " + e.message }
    "Send failed: " + e.message
}
