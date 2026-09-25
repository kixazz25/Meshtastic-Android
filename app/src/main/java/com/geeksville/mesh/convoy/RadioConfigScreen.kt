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
import com.geeksville.mesh.model.UIViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.proto.DeviceProfile

/*
 * RADIOCFG4-2026-09-25 (GroupTrack 2.7) -- the radio configurator, part 4: the SCREEN.
 * Work with Rides -> "Apply ride to radio / Nucleus" opens it (RadioConfigLauncher; Main.kt shows it as an overlay).
 *
 *   PICK     the target: the GroupTrack DEFAULT first, then rides, then this radio's backups
 *   PREVIEW  the managed changes (part 1) + what changed on the radio since its last backup (part 3)
 *   APPLY    part 2's writer, live log; CLOSE IS DISABLED while it runs (never walk away mid-transaction)
 *   RESULT   the 19 checks; the backup saved as "<config> -- <date>" (or "... unverified")
 * Backups: convoy_backups/<radio id>/<id>_<yyyyMMdd>_<HHmmss>_<label>.cfg. If the radio's folder has no .cfg yet,
 * its state is saved FIRST as "as-found". Radios GroupTrack configures only (T1000-E / app-attached);
 * Nucleus radios are Natak's (the "/ Nucleus" half of this entry comes with Nathan's API).
 */

object RadioConfigLauncher {
    var showing by mutableStateOf(false)
    fun open() { showing = true }
    fun close() { showing = false }
}

/** Per-radio backups: one folder per radio id; names carry the date, time and the config applied. */
object RadioBackups {
    fun dir(context: android.content.Context, nodeId: String) =
        java.io.File(context.filesDir, "convoy_backups/$nodeId").also { it.mkdirs() }

    fun list(context: android.content.Context, nodeId: String): List<java.io.File> =
        dir(context, nodeId).listFiles { f -> f.isFile && f.extension == "cfg" }?.sortedByDescending { it.name.split("_").drop(1).take(2).joinToString("") }
            ?: emptyList()

    fun newFile(context: android.content.Context, nodeId: String, label: String): java.io.File {
        val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val slug = label.replace(Regex("[^A-Za-z0-9 '-]"), "").trim().replace(Regex("\\s+"), "-").take(40).ifEmpty { "config" }
        return java.io.File(dir(context, nodeId), "${nodeId}_${ts}_$slug.cfg")
    }

    /** "!0ba3deda_20260925_142011_GroupTrack-default.cfg" -> "2026-09-25 14:20 -- GroupTrack default". */
    fun describe(f: java.io.File): String {
        val p = f.nameWithoutExtension.split("_")
        return runCatching {
            val d = p[1]; val t = p[2]
            val label = p.drop(3).joinToString(" ").replace('-', ' ').ifEmpty { "backup" }
            "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)} ${t.substring(0, 2)}:${t.substring(2, 4)} \u2014 $label"
        }.getOrDefault(f.name)
    }
}

private class RadioTarget(val name: String, val detail: String, val json: JSONObject?, val backup: java.io.File?)

private val BG = Color(0xF20F1216)
private val INK = Color(0xFFE8EEF5)
private val DIM = Color(0xFF8B949E)
private val GOOD = Color(0xFF3FB950)
private val BAD = Color(0xFFF85149)
private val ACCENT = Color(0xFF58A6FF)

@Composable
fun RadioConfigScreen(
    onClose: () -> Unit,
    uiViewModel: UIViewModel = hiltViewModel(),
    convoyViewModel: ConvoyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("PICK") }
    var message by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf(listOf<RadioTarget>()) }
    var chosen by remember { mutableStateOf<RadioTarget?>(null) }
    var values by remember { mutableStateOf<ManagedValues?>(null) }
    var plan by remember { mutableStateOf<ConfigPlan?>(null) }
    var drift by remember { mutableStateOf(listOf<ConfigDiff>()) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var result by remember { mutableStateOf<WriteResult?>(null) }
    var savedAs by remember { mutableStateOf("") }

    val nodeNum = convoyViewModel.radioNodeNum()
    val nodeId = nodeNum?.let { "!%08x".format(it) }
    val callsign = remember { ConvoyProfileStore.load()?.callsign?.trim().orEmpty() }
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
                list += RadioTarget(name, "Ride \u2022 " + (r?.optString("date")?.takeIf { it != "null" } ?: "no date"), j, null)
            }
        if (nodeId != null) RadioBackups.list(context, nodeId).forEach { f -> list += RadioTarget(RadioBackups.describe(f), "This radio's backup", null, f) }
        targets = list
    }

    fun addLog(s: String) { android.util.Log.i("RadioConfig", s); log = log + s.removePrefix("RADIOWRITER: ") }

    fun choose(t: RadioTarget) {
        scope.launch {
            try {
                val v = if (t.json != null) RadioConfigurator.fromRideFile(t.json, requireNotNull(defaultJson) { "default asset missing" }, callsign)
                else RadioConfigurator.fromProfile(requireNotNull(convoyViewModel.importProfileFromFile(t.backup!!).getOrNull()) { "backup unreadable" })
                val current = convoyViewModel.currentProfile()
                val last = RadioBackups.list(context, nodeId!!).firstOrNull()?.let { convoyViewModel.importProfileFromFile(it).getOrNull() }
                chosen = t; values = v; plan = RadioConfigurator.build(current, v)
                drift = if (last != null) ConfigCompare.differences(last, current) else emptyList()
                phase = "PREVIEW"
            } catch (e: Exception) { message = "Cannot use this target: ${e.message}" }
        }
    }

    fun apply() {
        val ops = convoyViewModel.radioOps(uiViewModel) ?: run { message = "No radio connected."; return }
        val p = plan ?: return
        val v = values ?: return
        val t = chosen ?: return
        phase = "APPLYING"; log = emptyList()
        scope.launch {
            try {
                if (RadioBackups.list(context, nodeId!!).isEmpty()) {
                    val f = RadioBackups.newFile(context, nodeId, "as-found")
                    convoyViewModel.exportProfileToFile(context, f).getOrThrow()
                    addLog("first backup of this radio saved: as found")
                }
                val r = RadioConfigWriter(ops, ::addLog).apply(p, v)
                result = r
                if (r is WriteResult.Done) {
                    val label = t.name + if (r.verified) "" else " unverified"
                    val f = RadioBackups.newFile(context, nodeId, label)
                    convoyViewModel.exportProfileToFile(context, f).getOrThrow()
                    savedAs = RadioBackups.describe(f)
                }
            } catch (e: Exception) {
                addLog("ERROR: ${e.message}")
            }
            phase = "RESULT"
        }
    }

    Box(Modifier.fillMaxSize().background(BG).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("Apply ride to radio", color = INK, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Radio ${nodeId ?: "(none)"} \u2022 callsign ${callsign.ifEmpty { "(no profile)" }}", color = DIM, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))

            val connected = uiViewModel.connectionState.value == ConnectionState.Connected
            when {
                phase == "PICK" && (nodeId == null || !connected) ->
                    Text("No radio connected. Connect your radio first, then open this again.", color = BAD)
                phase == "PICK" && callsign.isEmpty() ->
                    Text("Set your rider profile first (Settings \u2192 Your Profile): your callsign becomes the radio's name.", color = BAD)
                phase == "PICK" -> {
                    Text("Choose what to put on the radio:", color = INK)
                    targets.forEach { t ->
                        Column(Modifier.fillMaxWidth().clickable { choose(t) }.padding(vertical = 9.dp)) {
                            Text(t.name, color = ACCENT, fontSize = 16.sp)
                            Text(t.detail, color = DIM, fontSize = 12.sp)
                        }
                    }
                }
                phase == "PREVIEW" -> {
                    val p = plan!!
                    Text("${chosen?.name}", color = ACCENT, fontSize = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    if (p.isEmpty) Text("The radio already holds these values. Applying will only verify them.", color = GOOD)
                    else { Text("Will change (${p.changes.size}):", color = INK); p.changes.forEach { Text("  \u2022 $it", color = INK, fontSize = 13.sp) } }
                    Spacer(Modifier.height(8.dp))
                    if (drift.isNotEmpty()) {
                        Text("Changed on this radio since its last backup (not touched):", color = DIM)
                        drift.forEach { Text("  ${it.path}: ${it.a} \u2192 ${it.b}", color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { apply() }) { Text(if (p.isEmpty) "Verify" else "Apply") }
                        OutlinedButton(onClick = { phase = "PICK" }) { Text("Back") }
                    }
                }
                phase == "APPLYING" || phase == "RESULT" -> {
                    Text(if (phase == "APPLYING") "Applying \u2014 do not close. The radio restarts; Bluetooth reconnects after each step." else "Done", color = INK)
                    Spacer(Modifier.height(6.dp))
                    log.forEach { Text(it, color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    when (val r = result) {
                        is WriteResult.Done -> {
                            Spacer(Modifier.height(8.dp))
                            Text(if (r.verified) "Verified: all ${r.checks.size} settings are on the radio." else "NOT verified \u2014 see the red lines.",
                                color = if (r.verified) GOOD else BAD, fontWeight = FontWeight.Bold)
                            r.checks.forEach { c ->
                                Text("${if (c.ok) "\u2713" else "\u2717"} ${c.field}: ${c.actual}" + if (c.ok) "" else " (expected ${c.expected})",
                                    color = if (c.ok) GOOD else BAD, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            if (savedAs.isNotEmpty()) Text("Saved as: $savedAs", color = INK, fontSize = 13.sp)
                        }
                        is WriteResult.Stopped -> Text("Stopped at ${r.group}: ${r.reason}. Written before it: ${r.groupsWritten}", color = BAD)
                        null -> {}
                    }
                }
            }
            if (message.isNotEmpty()) Text(message, color = BAD, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            if (phase != "APPLYING") OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}
