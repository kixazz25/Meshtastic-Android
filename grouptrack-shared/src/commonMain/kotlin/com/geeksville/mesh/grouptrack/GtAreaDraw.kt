package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grouptrack.shared.text.GtFmt

/*
 * AREADL-2026-11-25 (2.8b, map row 3, map.dl_area): ONE way to give a function an area -- draw a box, then launch -- SHARED.
 * Moved from Android's AreaDraw.kt (GTAREA2-2026-10-23, Fred 10:40) and MapScreens.kt (GtDownloadAreaScreen). Android's
 * GtAreaHost still chooses the module a box was drawn for; the iPad draws the ones it has.
 */

/** The drawn box (Android's DownloadBbox's four numbers). */
data class GtAreaBox(val north: Double = 0.0, val south: Double = 0.0, val east: Double = 0.0, val west: Double = 0.0)

/**
 * GTAREA2-2026-10-23 (2.7c, Fred 10:40): ONE way to give a function an area -- draw a box, then launch.
 * The Map menu row says what the box is FOR; nothing else is asked before the box is drawn.
 */
object GtArea {
    /** The function waiting for a box: "download" | "remove" | "import". "" = none (CODE RULE 1: no null). */
    val purpose = mutableStateOf("")
    /** The function a box was drawn for ("" = none) and the box. */
    val ready = mutableStateOf("")
    val box = mutableStateOf(GtAreaBox())
    fun label(p: String): String = when (p) {
        "download" -> "DOWNLOAD MAPS"
        "remove" -> "REMOVE MAPS"
        "rmfeat" -> "REMOVE TRACKS AND TRAILS"   // TRAILCLEAN-2026-11-04
        "import" -> "IMPORT TRAILS"
        else -> ""
    }
    /** Called from the map's onAreaSelected. false = nothing was waiting -- the caller keeps its own path.
     *  [post] runs the change on the main thread: Android's Handler(mainLooper).post; the iPad's relay is already there. */
    fun take(n: Double, s: Double, e: Double, w: Double, post: (() -> Unit) -> Unit): Boolean {
        val p = purpose.value
        if (p.isEmpty()) return false
        post {
            box.value = GtAreaBox(north = n, south = s, east = e, west = w)
            purpose.value = ""
            ready.value = p
        }
        return true
    }
}

/** The draw bar while a box is awaited (CLEAR restarts it, CANCEL ends it), and the drawn box goes when no module is using
 *  it and no box is awaited. [js] runs a call on the map page. Moved from GtAreaHost (AreaDraw.kt). */
@Composable
fun GtAreaDrawBar(js: (String) -> Unit, barModifier: Modifier) {
    val p = GtArea.purpose.value
    if (p.isNotEmpty()) {
        val s = GtScheme.current.value
        Row(
            modifier = barModifier.background(s.panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Draw the area — " + GtArea.label(p) + (if (p == "import") "\nPart of a state, or across state borders — " +
                "next you see every state and source it touches and untick any; only the trails inside the box are loaded." else ""),   // AREAIMPORT-2026-10-24
                color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            GtButton("CLEAR", { js("activateDrawMode()") })
            GtButton("CANCEL", { GtArea.purpose.value = ""; js("cancelDrawMode()") })
        }
    }
    LaunchedEffect(GtArea.ready.value, GtArea.purpose.value) {
        if (GtArea.ready.value.isEmpty() && GtArea.purpose.value.isEmpty()) js("clearAreaBoundary()")
    }
}

/**
 * Download maps by area -- the confirm (2.7e GtDownloadAreaScreen + ConvoyDownloadConfirm, one map): the estimate, the box,
 * the download settings in one line with CHANGE (THIS download only; it starts from Map > Download settings), PROCEED.
 * [estimatedTiles] is the host's (Android's ConvoyTileCalculator.quickEstimate, unchanged). PROCEED hands [onProceed] the
 * Replace choice; the host queues it and says so.
 */
@Composable
fun GtDownloadAreaConfirm(box: GtAreaBox, estimatedTiles: Int, replace: Boolean, onProceed: (Boolean) -> Unit, onCancel: () -> Unit) {
    val chrome = LocalGtPanelChrome.current
    var rep by remember { mutableStateOf(replace) }
    var changeOpen by remember { mutableStateOf(false) }
    val mono = FontFamily.Monospace
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            GtPanel(
                title = "Download maps by area",
                helpId = "map.dl_area",
                onHelp = { id -> chrome.openHelp(id) },
                onDone = onCancel,
                stays = false,
                width = if (maxWidth < 360.dp) maxWidth else 360.dp,
                maxHeight = maxHeight,
            ) {
                Text("DOWNLOAD TILES", color = Gt.text, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
                if (estimatedTiles > 0) Text("$estimatedTiles tiles", color = Gt.text, fontSize = 12.sp, fontFamily = mono)
                Text(GtFmt.f(box.south, 3) + "°N to " + GtFmt.f(box.north, 3) + "°N", color = Gt.text, fontSize = 12.sp, fontFamily = mono)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Download: the map and its elevation  ·  Replace existing tiles: " + (if (rep) "Yes" else "No"),
                        color = Gt.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (changeOpen) "DONE" else "CHANGE", color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { changeOpen = !changeOpen }.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                if (changeOpen) {
                    Text("For this download only — the defaults are set in Map › Download settings.", color = Gt.text, fontSize = 12.sp)
                    Row(Modifier.clickable { rep = !rep }, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GtCheck(rep)
                        Text("Replace existing tiles", color = Gt.text, fontSize = 12.sp, fontFamily = mono)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GtButton("CANCEL", onCancel)
                    GtButton("PROCEED", { onProceed(rep) })
                }
                if (estimatedTiles > 0) Text("~$estimatedTiles tiles", color = Gt.text, fontSize = 12.sp, fontFamily = mono)
            }
        }
    }
}
