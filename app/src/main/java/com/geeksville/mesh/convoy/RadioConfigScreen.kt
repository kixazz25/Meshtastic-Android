package com.geeksville.mesh.convoy

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.runtime.collectAsState
// SAVEDCFG-2026-10-12: RadioBackups no longer used here -- a preselected save's title comes from CommConfig.savedSetups()
import com.grouptrack.comm.ApplyRequest
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.ConfigChange

/*
 * RADIOCFG4-2026-09-25 (GroupTrack 2.7) -- the radio configurator, part 4: the SCREEN.
 * [SAVETITLE-2026-09-25: saves titled with the ride they were applied from; CONFIGREVIEW preselect]
 * Work with Rides -> "Apply ride to radio / Nucleus" opens it (RadioConfigLauncher; Main.kt shows it as an overlay).
 *
 *   PICK     the target: the GroupTrack DEFAULT first, then rides, then this radio's backups
 *   PREVIEW  the managed changes (part 1) + what changed on the radio since its last backup (part 3)
 *   APPLY    part 2's writer, live log; CLOSE IS DISABLED while it runs (never walk away mid-transaction)
 *   RESULT   the 19 checks; the backup saved as "<config> -- <date>" (or "... unverified")
 * COMMCONFIG-2026-10-12 (P4.2): the plan, the writer, the verify and the saves are the COMM SIDE's (CommConfig); this screen
 *   sends the ride file + the callsign and shows the change list, the log, the checks and the save it reports.
 * Backups: convoy_backups/<radio id>/<id>_<yyyyMMdd>_<HHmmss>_<label>.cfg. If the radio's folder has no .cfg yet,
 * its state is saved FIRST as "as-found". Radios GroupTrack configures only (T1000-E / app-attached);
 * Nucleus radios are Natak's (the "/ Nucleus" half of this entry comes with Nathan's API).
 */

object RadioConfigLauncher {
    var showing by mutableStateOf(false)
    /** CONFIGREVIEW-2026-09-25: a save chosen in Saved configs -> Apply; cleared once used or on close. */
    var preselect: String? = null   // SAVEDCFG-2026-10-12: the save's id (CommConfig.savedSetups) -- CODE RULE 1: null = none chosen
    /** CHECKINAPPLY-2026-09-28 (Fred): opened by CHECK IN -- apply THIS ride straight away, then report back.
     *  CODE RULE 1: null = normal use (the rider picks). */
    var autoRideId: String? = null
    /** CHECKINAPPLY: told the result of that apply: true = done and verified. Null = nobody waiting. */
    var onAutoResult: ((Boolean) -> Unit)? = null
    fun open() { showing = true }
    fun close() { showing = false; preselect = null }
}

private class RadioTarget(val name: String, val detail: String, val json: JSONObject?, val savedId: String?, val rideId: String? = null)

private val BG = Color(0xF20F1216)
private val INK = Color(0xFFE8EEF5)
private val DIM = Color(0xFF8B949E)
private val GOOD = Color(0xFF3FB950)
private val BAD = Color(0xFFF85149)
private val ACCENT = Color(0xFF58A6FF)

/** COMMCONFIG-2026-10-12: a change line as the configurator wrote it ("field: old -> new", or a whole line such as "channel key: changed"). */
private fun ConfigChange.line(): String = if (from.isEmpty() && to.isEmpty()) field else "$field: $from -> $to"

@Composable
fun RadioConfigScreen(
    onClose: () -> Unit,
    convoyViewModel: ConvoyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = convoyViewModel.radioConfig   // COMMCONFIG-2026-10-12: the Comm API
    var phase by remember { mutableStateOf("PICK") }
    var message by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf(listOf<RadioTarget>()) }
    var chosen by remember { mutableStateOf<RadioTarget?>(null) }
    var changes by remember { mutableStateOf<List<ConfigChange>?>(null) }   // CODE RULE 1: null = nothing previewed yet
    var drift by remember { mutableStateOf(listOf<ConfigChange>()) }
    var applied by remember { mutableStateOf<CommResult?>(null) }          // CODE RULE 1: null = not applied yet
    val report by config.applyReport.collectAsState()

    val nodeNum = convoyViewModel.radioNodeNum()
    val nodeId = nodeNum?.let { "!%08x".format(it) }
    val callsign = remember { ConvoyProfileStore.load()?.callsign?.trim().orEmpty() }
    // CALLSIGNOVR-2026-09-26 (Fred): the callsign THIS radio gets -- defaults to the profile's; type another rider's to
    // set up their radio. The profile never changes. Written to the radio's owner name and verified like the rest.
    var radioCallsign by remember { mutableStateOf(callsign) }
    val defaultJson = remember {
        runCatching { JSONObject(context.assets.open("grouptrack_default.json").bufferedReader().use { it.readText() }) }.getOrNull()
    }

    BackHandler(enabled = true) { if (phase != "APPLYING") onClose() }

    LaunchedEffect(nodeId) {
        val list = mutableListOf<RadioTarget>()
        if (defaultJson != null) list += RadioTarget("GroupTrack default", "GroupTrack's own channel -- any radio, for testing", defaultJson, null)
        val rideFiles = GroupTrackStorage.dir("rides", context).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        rideFiles.mapNotNull { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }
            .sortedByDescending { it.optJSONObject("ride")?.optString("date").orEmpty() }
            .forEach { j ->
                val r = j.optJSONObject("ride")
                val name = r?.optString("name")?.takeIf { it.isNotBlank() && it != "null" } ?: "Unnamed ride"
                val rid = r?.optString("rideId")?.takeIf { it.isNotBlank() && it != "null" }
                list += RadioTarget(name, "Ride • " + (r?.optString("date")?.takeIf { it != "null" } ?: "no date"), j, null, rid)
            }
        // SPLITLISTS-2026-09-26 (Fred): Apply ride to radio shows the DEFAULT and RIDES only -- saves are applied from
        // Saved configs (Review / apply saved configs). A save chosen THERE arrives here as the preselect: it alone.
        RadioConfigLauncher.preselect?.let { id ->   // SAVEDCFG-2026-10-12
            config.savedSetups().firstOrNull { it.id == id }?.let { s ->
                list += RadioTarget(s.title, "This radio's save • " + s.detail, null, s.id, s.rideId)
            }
        }
        targets = list
    }

    fun forThisRadio() = radioCallsign.trim().ifEmpty { callsign }
    fun saveTitle(t: RadioTarget) =
        t.name + (if (radioCallsign.trim().isNotEmpty() && radioCallsign.trim() != callsign) " — for ${radioCallsign.trim()}" else "")

    fun choose(t: RadioTarget) {
        if (phase == "APPLYING") return   // APPLYGUARD-2026-10-10: never re-prepared in the middle of an apply
        scope.launch {
            // COMMCONFIG-2026-10-12: the comm side plans against the radio as it is; GroupTrack shows the changes.
            val (c, r) = if (t.json != null) config.previewConfig(t.json.toString(), forThisRadio()) else config.previewSaved(t.savedId!!)
            if (r is CommResult.Failed) { message = "Cannot use this target: ${r.reason}"; return@launch }
            chosen = t; changes = c
            drift = config.driftSinceLastSave()
            phase = "PREVIEW"
        }
    }

    // CONFIGREVIEW-2026-09-25: opened from Saved configs -> Apply: straight to that save's preview.
    LaunchedEffect(targets) {
        val f = RadioConfigLauncher.preselect ?: return@LaunchedEffect
        val t = targets.firstOrNull { it.savedId == f } ?: return@LaunchedEffect   // SAVEDCFG-2026-10-12: by id
        RadioConfigLauncher.preselect = null
        choose(t)
    }

    fun apply() {
        val t = chosen ?: return
        if (changes == null) return
        if (nodeId == null || convoyViewModel.link.value != com.grouptrack.comm.Link.CONNECTED) { message = "No radio connected."; return }
        phase = "APPLYING"; applied = null
        scope.launch {
            // COMMCONFIG-2026-10-12: WHAT goes on the radio; the comm side does the HOW (first save, writer, verify, save) and reports.
            val r = if (t.json != null) config.applyRide(ApplyRequest(t.json.toString(), forThisRadio(), t.name, saveTitle(t), t.rideId))
                    else config.applySaved(t.savedId!!, saveTitle(t))
            applied = r
            if (r is CommResult.Applied && r.verified) RadioSetupState.markDone(context) // RADIOSETUPTEST-2026-10-01
            if (r is CommResult.Failed && r.reason == "NO_RADIO") message = "No radio connected."
            phase = "RESULT"
        }
    }

    // CHECKINAPPLY-2026-09-28 (Fred): opened by CHECK IN -- that ride, no picking, no preview: apply, then report back.
    LaunchedEffect(targets) {
        val rid = RadioConfigLauncher.autoRideId ?: return@LaunchedEffect
        if (targets.isEmpty()) return@LaunchedEffect
        // APPLYGUARD-2026-10-10: only the FIRST target list may start the automatic apply. The list is rebuilt when
        // the radio reconnects (its node id goes null and back during the apply's restart after group 1b).
        if (phase != "PICK") return@LaunchedEffect
        // NORIDEDEFAULT-2026-10-10: "GroupTrack" asks for the GroupTrack default target (it carries no ride id).
        val t = if (rid == com.grouptrack.core.NetworkGenerator.DEFAULT_CHANNEL)
            targets.firstOrNull { it.rideId == null && it.savedId == null && it.name == "GroupTrack default" }
        else targets.firstOrNull { it.rideId == rid }
        if (t == null) {
            android.util.Log.i("RadioConfig", "CHECKINAPPLY: ride $rid not found on this tablet")
            val cb = RadioConfigLauncher.onAutoResult
            RadioConfigLauncher.autoRideId = null; RadioConfigLauncher.onAutoResult = null
            onClose(); cb?.invoke(false)
        } else choose(t)
    }
    LaunchedEffect(phase) {
        if (RadioConfigLauncher.autoRideId == null) return@LaunchedEffect
        val autoDefault = RadioConfigLauncher.autoRideId == com.grouptrack.core.NetworkGenerator.DEFAULT_CHANNEL &&
            chosen?.rideId == null && chosen?.name == "GroupTrack default"   // NORIDEDEFAULT-2026-10-10
        if (phase == "PREVIEW" && (chosen?.rideId == RadioConfigLauncher.autoRideId || autoDefault)) apply()
        if (phase == "RESULT") {
            val ok = (applied as? CommResult.Applied)?.verified == true
            android.util.Log.i("RadioConfig", "CHECKINAPPLY: ride ${RadioConfigLauncher.autoRideId} applied, verified=$ok")
            kotlinx.coroutines.delay(1500)   // the result shows briefly
            val cb = RadioConfigLauncher.onAutoResult
            RadioConfigLauncher.autoRideId = null; RadioConfigLauncher.onAutoResult = null
            onClose(); cb?.invoke(ok)
        }
    }

    Box(Modifier.fillMaxSize().background(BG).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("Apply ride to radio", color = INK, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Radio ${nodeId ?: "(none)"} • callsign ${callsign.ifEmpty { "(no profile)" }}", color = DIM, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))

            val connected = convoyViewModel.link.value == com.grouptrack.comm.Link.CONNECTED   // COMMRADIOOPS-2026-10-12
            when {
                phase == "PICK" && (nodeId == null || !connected) ->
                    Text("No radio connected. Connect your radio first, then open this again.", color = BAD)
                phase == "PICK" && callsign.isEmpty() ->
                    Text("Set your rider profile first (Settings → Your Profile): your callsign becomes the radio's name.", color = BAD)
                phase == "PICK" -> {
                    // CALLSIGNOVR-2026-09-26: whose radio is this? Your callsign unless you type another rider's.
                    OutlinedTextField(value = radioCallsign, onValueChange = { radioCallsign = it.take(39) }, singleLine = true,
                        label = { Text("Callsign for this radio") }, modifier = Modifier.fillMaxWidth())
                    // CALLSIGNHINT-2026-09-27 (Fred): what the field is for, on the screen itself.
                    Text("Mainly for setting up someone else's radio at the trailhead: type their callsign, and their radio joins the ride under their name. For your own radio, leave it as it is.",
                        color = Color(0xFF8899AA), fontSize = 12.sp)
                    if (radioCallsign.trim().isNotEmpty() && radioCallsign.trim() != callsign)
                        Text("Setting up a radio for ${radioCallsign.trim()} — your own profile is not changed.", color = ACCENT, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Choose what to put on the radio:", color = INK)
                    targets.forEach { t ->
                        Column(Modifier.fillMaxWidth().clickable { choose(t) }.padding(vertical = 9.dp)) {
                            Text(t.name, color = ACCENT, fontSize = 16.sp)
                            Text(t.detail, color = DIM, fontSize = 12.sp)
                        }
                    }
                }
                phase == "PREVIEW" -> {
                    val c = changes.orEmpty()
                    Text("${chosen?.name}", color = ACCENT, fontSize = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    if (c.isEmpty()) Text("The radio already holds these values. Applying will only verify them.", color = GOOD)
                    else { Text("Will change (${c.size}):", color = INK); c.forEach { Text("  • ${it.line()}", color = INK, fontSize = 13.sp) } }
                    Spacer(Modifier.height(8.dp))
                    if (drift.isNotEmpty()) {
                        Text("Changed on this radio since its last backup (not touched):", color = DIM)
                        drift.forEach { Text("  ${it.field}: ${it.from} → ${it.to}", color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { apply() }) { Text(if (c.isEmpty()) "Verify" else "Apply") }
                        OutlinedButton(onClick = { phase = "PICK" }) { Text("Back") }
                    }
                }
                phase == "APPLYING" || phase == "RESULT" -> {
                    Text(if (phase == "APPLYING") "Applying — do not close. The radio restarts; Bluetooth reconnects after each step." else "Done", color = INK)
                    if (phase == "RESULT") Button(onClick = onClose) { Text("Done — back to the map") } // CLEANUP-2026-09-25
                    Spacer(Modifier.height(6.dp))
                    val rep = report
                    rep?.log?.forEach { Text(it, color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    if (rep != null && !rep.running) when {
                        rep.stoppedAt != null ->
                            Text("Stopped at ${rep.stoppedAt}: ${rep.stopReason}. Written before it: ${rep.groupsWritten}", color = BAD)
                        rep.checks.isNotEmpty() -> {
                            Spacer(Modifier.height(8.dp))
                            Text(if (rep.verified) "Verified: all ${rep.checks.size} settings are on the radio." else "NOT verified — see the red lines.",
                                color = if (rep.verified) GOOD else BAD, fontWeight = FontWeight.Bold)
                            rep.checks.forEach { c ->
                                Text("${if (c.ok) "✓" else "✗"} ${c.field}: ${c.actual}" + if (c.ok) "" else " (expected ${c.expected})",
                                    color = if (c.ok) GOOD else BAD, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            rep.savedAs?.let { Text("Saved as: $it", color = INK, fontSize = 13.sp) }
                        }
                        else -> {}
                    }
                }
            }
            if (message.isNotEmpty()) Text(message, color = BAD, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            if (phase != "APPLYING") OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}
