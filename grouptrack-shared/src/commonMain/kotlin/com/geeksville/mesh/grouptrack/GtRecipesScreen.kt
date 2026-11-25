package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grouptrack.shared.queue.GtVectorQueue
import com.grouptrack.shared.text.GtFmt
import kotlinx.coroutines.launch

/*
 * MAPRECIPESHARE-2026-11-25 (2.8b, map.recipes): Saved map recipes, SHARED. Moved from Android's GtRecipesScreen
 * (MapScreens.kt -- GTRECIPES-2026-10-23, MAPRECIPES-2026-11-03, RECIPEBTN-2026-11-03, RESTOREQUEUE-2026-11-03).
 * The file work (which folders, RESTORE, SEND) is each platform's host: Android's GtRecipes, the iPad's IosRecipes.
 */

/** One recipe on the screen: its file [path], the day it is [label]led with, and "N areas   K KB". */
class GtRecipeRow(val path: String, val label: String, val detail: String)

/** The restore estimate's arithmetic, MOVED from Android's GtEmergency (EmergencyRestore.kt) so both hosts say the same. */
object GtRecipeMath {
    private const val TILES_PER_MIN = 3200.0   // VECRATE-2026-10-31: Droid 1 clean rebuild, Wi-Fi, 53,768 tiles in 16 min 40 s

    /** Exact tiles for the z12 squares, vector z0-14 + elevation z0-12 (as GtMapConversion). */
    fun tiles(sq: Set<Long>): Long {
        fun at(zMax: Int): Long { val n = 4096L; var t = 0L
            for (z in 0..zMax) t += if (z >= 12) sq.size.toLong() * (1L shl (2 * (z - 12)))
                else { val s = 12 - z; sq.map { ((it / n) shr s) * n + ((it % n) shr s) }.toSet().size.toLong() }
            return t }
        return at(GtVectorQueue.Z_MAX) + at(GtVectorQueue.DEM_Z_MAX)
    }

    fun timeText(tiles: Long): String {
        val mins = maxOf(1, kotlin.math.ceil(tiles / TILES_PER_MIN).toInt())
        return if (mins < 90) "$mins minute${if (mins == 1) "" else "s"}" else GtFmt.f(mins / 60.0, 1) + " hours"
    }
}

/**
 * GTRECIPES-2026-10-23 (Fred 11:43): the daily map recovery recipes -- SAVED ON THIS PHONE (60 days, newest first) and
 * RECEIVED. Pick a day -> its estimate -> RESTORE AS OF <day> or SEND (email). [sentToMe] is the path of a recipe opened
 * from an email ("" = opened from the menu); it arrives PICKED. [rows] null = still reading.
 * [onRestore] returns the rider's line; one starting "Queued" goes to [onQueued] (close, say so, open MAP DOWNLOADS).
 * [onSend] returns "" when the share sheet opened, else what went wrong.
 */
@Composable
fun GtRecipesPanel(
    rows: Pair<List<GtRecipeRow>, List<GtRecipeRow>>?,
    sentToMe: String,
    estimateOf: suspend (String) -> String,
    replaceLine: String,
    onRestore: suspend (path: String, label: String) -> String,
    onQueued: (String) -> Unit,
    onSend: (path: String, label: String) -> String,
    onClose: () -> Unit,
) {
    val chrome = LocalGtPanelChrome.current
    val scope = rememberCoroutineScope()
    var sel by remember { mutableStateOf(sentToMe) }   // the picked recipe's path; "" = none (CODE RULE 1)
    var est by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(sel) {
        est = ""; msg = ""
        if (sel.isNotEmpty()) est = estimateOf(sel)
    }
    val picked = rows?.let { it.first + it.second }?.firstOrNull { it.path == sel }
    val pick = { p: String -> sel = if (sel == p) "" else p }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            GtPanel(
                title = "Saved map recipes",
                helpId = "map.recipes",
                onHelp = { id -> chrome.openHelp(id) },
                onDone = onClose,
                stays = false,
                width = if (maxWidth < 420.dp) maxWidth else 420.dp,
                maxHeight = maxHeight,
            ) {
                if (sentToMe.isNotEmpty()) Text("You were sent these maps. Tap RESTORE to download them.",
                    color = Gt.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Text("A daily record of what your maps cover, kept 60 days. Pick a day to restore your maps as of that day, or send it.",
                    color = Gt.dim, fontSize = 12.sp)
                if (rows == null) Text("Reading…", color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                else {
                    GtRecipeGroupRows("SAVED ON THIS PHONE", rows.first, "none yet — written once a day at startup", sel, pick)
                    GtRecipeGroupRows("RECEIVED", rows.second, "none — recipes sent to you arrive here", sel, pick)
                }
                if (picked != null) {
                    Text("MAPS AS OF ${picked.label}", color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 14.dp))
                    Text(est.ifEmpty { "Counting…" }, color = Gt.text, fontSize = 12.sp)
                    if (msg.isNotEmpty()) Text(msg, color = Gt.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    // RECIPEBTN-2026-11-03 (Fred 11:05): two activities, two buttons -- each says what it does
                    GtButton(if (busy) "QUEUEING…" else "RESTORE MY MAPS AS OF ${picked.label}", {
                        busy = true
                        scope.launch {
                            msg = onRestore(picked.path, picked.label)
                            busy = false
                            // RESTOREQUEUE-2026-11-03 (Fred 17:41): queued -> close, say so, open the download queue
                            if (msg.startsWith("Queued")) onQueued(msg)
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !busy && est.isNotEmpty())
                    Text(replaceLine, color = Gt.dim, fontSize = 12.sp)   // REPLACEDEFAULT-2026-11-03
                    GtButton("SEND THIS MAP RECIPE BY EMAIL", {
                        onSend(picked.path, picked.label).let { if (it.isNotEmpty()) msg = it }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text("The person you send it to taps the attachment to get the same maps.", color = Gt.dim, fontSize = 12.sp)
                }
            }
        }
    }
}

/** MAPRECIPES-2026-11-03: one group of the recipes screen -- a tick box per day; the row or the box picks it (again = unpick). */
@Composable
private fun GtRecipeGroupRows(title: String, list: List<GtRecipeRow>, empty: String, sel: String, onPick: (String) -> Unit) {
    Text(title, color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    if (list.isEmpty()) Text(empty, fontSize = 12.sp, color = Gt.dim)
    list.forEach { row ->
        Row(Modifier.fillMaxWidth().clickable { onPick(row.path) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            GtCheck(sel == row.path)
            Text("${row.label}   ${row.detail}", color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}
