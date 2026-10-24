package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState   // GTOFFGRID-2026-10-23
import com.geeksville.mesh.convoy.DownloadQueueManager   // GTOFFGRID-2026-10-23
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.DownloadBbox

/**
 * GTAREA2-2026-10-23 (2.7c, Fred 10:40): ONE way to give a function an area -- draw a box, then launch.
 * The Map menu row says what the box is FOR; nothing else is asked before the box is drawn.
 */
object GtArea {
    /** The function waiting for a box: "download" | "remove" | "import". "" = none (CODE RULE 1: no null). */
    val purpose = mutableStateOf("")
    /** The function a box was drawn for ("" = none) and the box. */
    val ready = mutableStateOf("")
    val box = mutableStateOf(DownloadBbox())
    fun label(p: String): String = when (p) {
        "download" -> "DOWNLOAD MAPS"
        "remove" -> "REMOVE MAPS"
        "import" -> "IMPORT TRAILS"
        else -> ""
    }
    /** Called from the map's onAreaSelected (any thread). false = nothing was waiting -- the caller keeps its own path. */
    fun take(n: Double, s: Double, e: Double, w: Double): Boolean {
        val p = purpose.value
        if (p.isEmpty()) return false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            box.value = DownloadBbox(north = n, south = s, east = e, west = w)
            purpose.value = ""
            ready.value = p
        }
        return true
    }
}

/**
 * The draw bar while a box is awaited, then the module the box was drawn for. [js] runs a call on the map page.
 * Every module is its own screen (MapScreens.kt) -- this only chooses which one.
 */
@Composable
fun GtAreaHost(js: (String) -> Unit, barModifier: Modifier) {
    GtOffGrid.js = js   // GTOFFGRID-2026-10-23: the off-grid overlays draw on this page
    // GTOFFGRID: "Downloading" follows the queue while it is ticked.
    val qNow = DownloadQueueManager.queue.collectAsState().value
    LaunchedEffect(qNow) { if (GtOffGrid.downloading.value) GtOffGrid.setDownloading(true) }
    val p = GtArea.purpose.value
    if (p.isNotEmpty()) {
        val s = GtScheme.current.value
        Row(
            modifier = barModifier.background(s.panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Draw the area \u2014 " + GtArea.label(p) + (if (p == "import") "\nPart of a state, or across state borders \u2014 " +
                "every state the box touches is used; only the trails inside the box are loaded." else ""),   // AREAIMPORT-2026-10-24
                color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            GtButton("CLEAR", { js("activateDrawMode()") })
            GtButton("CANCEL", { GtArea.purpose.value = ""; js("cancelDrawMode()") })
        }
    }
    val done = { GtArea.ready.value = "" }
    when (GtArea.ready.value) {
        "download" -> GtDownloadAreaScreen(GtArea.box.value, done)
        "remove" -> GtRemoveAreaScreen(GtArea.box.value, done)
        "import" -> GtImportAreaScreen(GtArea.box.value, done)
        "recipes" -> GtRecipesScreen(done)   // GTRECIPES-2026-10-23
        "state" -> GtImportStateScreen(done)   // GTIMPORTS-2026-10-23
        "gpx" -> GtImportGpxScreen(done)   // GTIMPORTS-2026-10-23
        "rider" -> GtRiderTrailsScreen(done)   // GTIMPORTS-2026-10-23
        "sync" -> GtSyncTracksScreen(done)   // GTSYNC-2026-10-23
        "radius" -> GtAreaRadiusScreen(done)   // GTRADIUS-2026-10-23
        "rmtrack" -> GtRemoveTrackScreen(done)   // GTHEAVY-2026-10-23
        "refresh" -> GtRefreshSourceScreen(done)   // GTHEAVY-2026-10-23
        "tracks" -> GtDownloadTracksScreen(done)   // GTTRACKS-2026-10-23: no box -- opened straight from the menu
    }
    // The drawn box goes when no module is using it and no box is awaited.
    LaunchedEffect(GtArea.ready.value, GtArea.purpose.value) {
        if (GtArea.ready.value.isEmpty() && GtArea.purpose.value.isEmpty()) js("clearAreaBoundary()")
    }
}
