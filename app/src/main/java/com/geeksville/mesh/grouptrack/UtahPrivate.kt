package com.geeksville.mesh.grouptrack

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geeksville.mesh.convoy.HomeStateImportController
import com.geeksville.mesh.convoy.OwnershipReclass
import com.geeksville.mesh.convoy.SpatialDbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UTAHPRIV-2026-10-24 (Fred 12:17-12:19): Map > IMPORT MAP FEATURES > Assign Utah private property.
 * The state import's step 8 on its own: every trail gets its type, use and public / private land from the Utah land
 * ownership data (SITLA surface ownership). Town street grids on private land become "R - Residential Roads".
 * The data is downloaded first when it is not on this tablet, or when the rider asks for the latest.
 * It was Settings > Classify trails again (STEP8PAGED-2026-10-23) -- the same run, now in the import section.
 * The work runs in its own scope: closing the panel does not stop it, and opening it again shows where it is.
 */
object GtUtahPrivate {
    val open = mutableStateOf(false)
    val busy = mutableStateOf(false)
    /** what is happening now ("" when idle). */
    val step = mutableStateOf("")
    /** progress 0..1 while classifying, -1 when not known. */
    val progress = mutableStateOf(-1f)
    /** the result of the last run ("" before one). */
    val result = mutableStateOf("")
    /** UTAHONCESHOW-2026-10-25: true while the panel shows the one-time 2.7c run. */
    val once = mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** the land data on this tablet: a non-Utah import places an EMPTY file on purpose, so under 1 MB = not there. */
    // PRUNEUGRC-2026-11-02: the UGRC file is gone; land is moving to the public-lands data (PAD-US) that comes with each state import.
    fun haveData(): Boolean = false
    fun dataLine(): String {
        return "Land assignment is moving to the public-lands data (PAD-US) that comes with each state import \u2014 not available in this build."   // PRUNEUGRC-2026-11-02
    }
    fun start(download: Boolean) {
        if (busy.value) return
        busy.value = true; result.value = ""; progress.value = -1f
        scope.launch {
            result.value = try { work(download) } catch (t: OutOfMemoryError) { "FAILED: out of memory." }
                           catch (t: Exception) { "FAILED: " + t.message }
            step.value = ""; busy.value = false
        }
    }

    // PRUNEUGRC-2026-11-02: the one-time 2.7c Utah run (UTAHONCE) removed -- code rule 3, "OUT when 2.7d is cut".
    private fun work(download: Boolean): String {
        // PRUNEUGRC-2026-11-02: held until step 8 reads PAD-US -- a full pass now would make every trail PUBLIC
        if (!download || download) return "Not available in this build: land assignment is moving to the public-lands data (PAD-US)."
        val note = ""
        val s = SpatialDbManager.getSpatialDb()
        val e = SpatialDbManager.getExtensionDb()
        if (s == null || e == null) return "FAILED: the trail databases are not open."
        step.value = "Assigning private property\u2026 this can take a few minutes."
        // STEP8EMPTY-2026-10-24: the full pass -- every trail (STEP8EMPTYFIX-2026-10-24: comment moved off the code line)
        val n = OwnershipReclass.run(s, e, onlyUnassigned = false) { done, total -> if (total > 0) progress.value = done.toFloat() / total }
        if (n < 0) return "FAILED: " + OwnershipReclass.lastError
        return "$n trails classified, ${OwnershipReclass.lastPrivate} on private land." + note
    }
}

@Composable
fun GtUtahPrivatePanel() {
    if (!GtUtahPrivate.open.value) return
    val busy = GtUtahPrivate.busy.value
    val result = GtUtahPrivate.result.value
    val have = remember(busy, result) { GtUtahPrivate.haveData() }
    val line = remember(busy, result) { GtUtahPrivate.dataLine() }
    var latest by remember { mutableStateOf(false) }
    val close = { GtUtahPrivate.open.value = false; GtUtahPrivate.once.value = false }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).width(380.dp).background(Gt.panel, RoundedCornerShape(14.dp))
                .border(1.dp, Gt.line, RoundedCornerShape(14.dp)).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Assign Utah private property", color = Gt.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GtHelpButton("map.utahprivate")
                GtCloseButton(close)
            }
            if (GtUtahPrivate.once.value) Text("One-time update for 2.7c: your Utah trails are being marked public or private from " +   // UTAHONCESHOW-2026-10-25
                "Utah's land ownership data. This runs once and takes about 10 minutes for the whole state \u2014 keep GroupTrack open.", color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Marks which trails are on private land, from Utah's land ownership data. Town street grids on private land " +
                "become Residential Roads. Every trail also gets its type and use again. Nothing is deleted.",
                color = Gt.text, fontSize = 14.sp)
            Text(line, color = Gt.text, fontSize = 13.sp)
            if (busy) {
                Text(GtUtahPrivate.step.value, color = Gt.text, fontSize = 14.sp)
                val p = GtUtahPrivate.progress.value
                if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Gt.text)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gt.text)
                Text("You can close this panel \u2014 the work carries on. Keep the app open.", color = Gt.text, fontSize = 12.sp)
                GtButton("CLOSE", close)
            } else if (result.isNotEmpty()) {
                Text(result, color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GtButton("OK", { GtUtahPrivate.result.value = ""; close() })
                    GtButton("RUN AGAIN", { GtUtahPrivate.result.value = "" })
                }
            } else {
                if (have && false) Row(Modifier.fillMaxWidth().clickable { latest = !latest }.padding(vertical = 6.dp),   // PRUNEUGRC-2026-11-02: no download
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GtCheck(latest)
                    Text("Download the latest land ownership data first", color = Gt.text, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GtButton("ASSIGN", { GtUtahPrivate.start(latest) })
                    GtButton("CANCEL", close)
                }
            }
        }
    }
}
