package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geeksville.mesh.convoy.DownloadQueueManager
import com.geeksville.mesh.convoy.GeofabrikCatalog
import com.geeksville.mesh.convoy.GeofabrikState
import com.geeksville.mesh.convoy.HomeStateImportController
import com.geeksville.mesh.convoy.LandAreas
import com.geeksville.mesh.convoy.OwnershipReclass
import com.geeksville.mesh.convoy.QueueStatus
import com.geeksville.mesh.convoy.SpatialDbManager
import com.geeksville.mesh.convoy.StateData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LANDSYNC-2026-11-02 (Fred 18:41-18:43): Map > IMPORT MAP FEATURES > Update public / private land, and the 2.7e startup run.
 * Every state imported on this tablet (whole or by area): its PAD-US public-lands data from the saved state data (fetched from the
 * USGS service when missing, or when the rider asks for the latest), the public / restricted land areas, then the trails classified
 * public, private or restricted (step 8). Nothing is deleted; no trails are downloaded.
 * Was "Assign Utah private property" (UTAHPRIV-2026-10-24, the Utah SITLA file -- retired by PRUNEUGRC-2026-11-02). The object and
 * panel keep their names for their callers. The work runs in its own scope: closing the panel does not stop it.
 */
object GtUtahPrivate {
    private const val TAG = "LandSync"
    val open = mutableStateOf(false)
    val busy = mutableStateOf(false)
    /** what is happening now ("" when idle). */
    val step = mutableStateOf("")
    /** progress 0..1 while known, -1 when not. */
    val progress = mutableStateOf(-1f)
    /** the result of the last run ("" before one). */
    val result = mutableStateOf("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun folderOf(gs: GeofabrikState) = gs.parentState?.lowercase()?.replace(' ', '-') ?: gs.slug

    /** The imported states with PAD-US data, one per land code (California's halves share CA). */
    private fun landStates(ctx: Context): List<GeofabrikState> {
        val slugs = HomeStateImportController.importedSlugs(ctx)
        return GeofabrikCatalog.load(ctx).filter { it.slug in slugs }
    }

    /** One line per state for the panel: its public-lands data on this tablet and its date. */
    fun dataLine(ctx: Context): String {
        val fmt = SimpleDateFormat("MM-dd-yyyy", Locale.US)
        val states = try { landStates(ctx) } catch (e: Exception) { emptyList() }
        if (states.isEmpty()) return "No states imported on this tablet yet — import trails by state or by area first."
        return states.filter { it.landCode.isNotEmpty() }.groupBy { it.landCode }.values.joinToString("\n") { rows ->
            val gs = rows.first(); val f = StateData.landPages(folderOf(gs), gs.landCode)
            (gs.parentState ?: gs.name) + ": " + if (f.exists()) "public-lands data saved ${fmt.format(Date(f.lastModified()))}" else "no public-lands data yet — downloaded when you run this"
        }
    }

    /** The menu: [fresh] = download the latest public-lands data first. Every trail is classified. */
    fun start(ctx: Context, fresh: Boolean) {
        if (busy.value) return
        val app = ctx.applicationContext
        busy.value = true; result.value = ""; progress.value = -1f
        scope.launch {
            result.value = try { work(app, fresh, all = true, startup = false) } catch (t: OutOfMemoryError) { "FAILED: out of memory." }
                           catch (t: Exception) { "FAILED: " + t.message }
            Log.i(TAG, "LANDSYNC menu: ${result.value}")
            step.value = ""; progress.value = -1f; busy.value = false
        }
    }

    /**
     * The 2.7e startup run (MainActivity) -- CODE RULE 3: no marker; it comes out when the next release is cut.
     * Follows the map conversion (Fred 18:42): waits while the conversion screen is up or its map jobs are queued or downloading,
     * while an import runs, and until the trail databases are open. Silent: the log, and the Map panel if the rider opens it.
     */
    fun startup(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(60_000L)   // the queue and the databases load first
            while (true) {
                val conv = DownloadQueueManager.queue.value.any {
                    (it.label.endsWith("· conversion") || it.label.endsWith("· emergency download")) &&
                        it.status in setOf(QueueStatus.QUEUED, QueueStatus.DOWNLOADING, QueueStatus.PAUSED)
                }
                val st = GtMapConversion.stage.value
                val ready = !conv && st != "checking" && st != "ask" && !HomeStateImportController.isImporting && !busy.value &&
                    SpatialDbManager.getSpatialDb() != null && SpatialDbManager.getExtensionDb() != null
                if (ready) break
                Log.i(TAG, "LANDSYNC startup: waiting (conversion jobs=$conv, screen='$st', importing=${HomeStateImportController.isImporting})")
                delay(30_000L)
            }
            if (com.geeksville.mesh.grouptrack.GtCertGuard.skip("LANDSYNC startup (land areas + step 8)")) return@launch   // STEP15F-FIXB-2026-11-10 (TEST-ONLY)
            busy.value = true; progress.value = -1f
            val r = try { work(app, fresh = false, all = false, startup = true) } catch (t: Throwable) { "FAILED: ${t.javaClass.simpleName} ${t.message}" }
            Log.i(TAG, "LANDSYNC startup: $r")
            step.value = ""; progress.value = -1f; busy.value = false
        }
    }

    /**
     * [fresh] download every state's public-lands data again; [all] classify every trail (else: every trail when the land data or
     * rules are new, otherwise only trails never classified); [startup] = a state with no data and no internet stops the run with
     * nothing changed (never every trail public).
     */
    private fun work(ctx: Context, fresh: Boolean, all: Boolean, startup: Boolean): String {
        if (HomeStateImportController.isImporting) return "A trail import is running — run this when it has finished."
        val s = SpatialDbManager.getSpatialDb()
        val e = SpatialDbManager.getExtensionDb()
        if (s == null || e == null) return "FAILED: the trail databases are not open."
        val states = landStates(ctx)
        if (states.isEmpty()) return "No states imported on this tablet — nothing to update."
        val missing = ArrayList<String>()
        for ((code, rows) in states.filter { it.landCode.isNotEmpty() }.groupBy { it.landCode }) {
            val gs = rows.first(); val name = gs.parentState ?: gs.name
            val f = StateData.landPages(folderOf(gs), code)
            if (!fresh && f.exists() && f.length() > 10_000L) continue
            if (!online(ctx)) { missing.add(name); continue }
            step.value = "$name: downloading public-lands data (USGS)…"
            val ok = LandAreas.fetchState(code, f, { false }) { _, got, expected ->
                step.value = "$name: public-lands data — %,d of %,d areas".format(got, expected)
                if (expected > 0) progress.value = got.toFloat() / expected
            }
            progress.value = -1f
            if (!ok && !(f.exists() && f.length() > 10_000L)) missing.add(name)
        }
        if (startup && missing.isNotEmpty()) return "skipped -- no public-lands data and no internet for ${missing.joinToString()}; the next start tries again"
        step.value = "Building the public and restricted land areas…"
        val rebuilt = LandAreas.ensure(ctx, s, states) { msg -> step.value = msg }
        val every = all || rebuilt
        step.value = if (every) "Classifying every trail… this takes a few minutes." else "Classifying new trails…"
        val n = OwnershipReclass.run(s, e, onlyUnassigned = !every) { done, total ->
            if (total > 0) {
                if (done >= total) { progress.value = -1f; step.value = "Finding restricted trail segments\u2026 about 2 minutes." }   // QUICKFIX-2026-11-03
                else progress.value = done.toFloat() / total
            }
        }
        if (n < 0) return "FAILED: " + OwnershipReclass.lastError
        val note = if (missing.isEmpty()) "" else " No public-lands data for ${missing.joinToString()} (no internet) — those trails stay public; run this again when online."
        return (if (every) "$n trails classified" else "$n new trails classified") +
            ": ${OwnershipReclass.lastPrivate} on private land, ${OwnershipReclass.lastRestricted} restricted." + note
    }
}

@Composable
fun GtUtahPrivatePanel() {
    if (!GtUtahPrivate.open.value) return
    val ctx = LocalContext.current
    val busy = GtUtahPrivate.busy.value
    val result = GtUtahPrivate.result.value
    val line = remember(busy, result) { GtUtahPrivate.dataLine(ctx) }
    var latest by remember { mutableStateOf(false) }
    val close = { GtUtahPrivate.open.value = false }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).width(380.dp).background(Gt.panel, RoundedCornerShape(14.dp))
                .border(1.dp, Gt.line, RoundedCornerShape(14.dp)).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Update public / private land", color = Gt.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GtHelpButton("map.utahprivate")
                GtCloseButton(close)
            }
            Text("Marks every trail public, private or restricted from the U.S. public-lands data (USGS PAD-US) for the states " +
                "imported on this tablet. Uses the land data saved with each state, and downloads it when it is missing. " +
                "No trails are downloaded and nothing is deleted.", color = Gt.text, fontSize = 14.sp)
            Text(line, color = Gt.text, fontSize = 13.sp)
            if (busy) {
                Text(GtUtahPrivate.step.value, color = Gt.text, fontSize = 14.sp)
                val p = GtUtahPrivate.progress.value
                if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Gt.text)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gt.text)
                Text("You can close this panel — the work carries on. Keep the app open.", color = Gt.text, fontSize = 12.sp)
                GtButton("CLOSE", close)
            } else if (result.isNotEmpty()) {
                Text(result, color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GtButton("OK", { GtUtahPrivate.result.value = ""; close() })
                    GtButton("RUN AGAIN", { GtUtahPrivate.result.value = "" })
                }
            } else {
                Row(Modifier.fillMaxWidth().clickable { latest = !latest }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GtCheck(latest)
                    Text("Download the latest public-lands data first", color = Gt.text, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GtButton("UPDATE", { GtUtahPrivate.start(ctx, latest) })
                    GtButton("CANCEL", close)
                }
            }
        }
    }
}
