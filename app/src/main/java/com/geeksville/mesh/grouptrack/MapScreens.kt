package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState   // GTQSUM-2026-10-23
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geeksville.mesh.convoy.ConvoyDownloadConfirm
import com.geeksville.mesh.convoy.ConvoyTileCalculator
import com.geeksville.mesh.convoy.DownloadBbox
import com.geeksville.mesh.convoy.DownloadQueueManager
import com.geeksville.mesh.convoy.HomeStatePickerScreen
import com.geeksville.mesh.convoy.MapSourceManager
import com.geeksville.mesh.convoy.SlotDisplayInfo

/*
 * GTAREA2-2026-10-23 (2.7c, Fred 10:53): THE MAP FUNCTIONS AS SEPARATE, CALLABLE SCREEN MODULES.
 * Each takes its values and an onDone, and calls TODAY'S engine exactly as the planner / ride map does -- the underlying
 * functions are NOT changed. Copies, not moves: the planner keeps its own until it is retired (09:06 method).
 */

/** Download maps by area: the standard confirm (sources, estimate, replace) -> submitDownload, one job per source. */
@Composable
fun GtDownloadAreaScreen(bbox: DownloadBbox, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val estimate = remember(bbox) { ConvoyTileCalculator.quickEstimate(bbox.north, bbox.south, bbox.east, bbox.west) }
    val slots = remember { MapSourceManager.getSlotSources().map { (legacyKey, shortLabel, _) ->
        SlotDisplayInfo(slotName = legacyKey, sourceName = shortLabel, directory = legacyKey, preSelected = true) } }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ConvoyDownloadConfirm(
            estimatedTiles = estimate.tileCount,
            estimatedMB = estimate.estimatedMB,
            areaDesc = String.format("%.3f\u00b0N to %.3f\u00b0N", bbox.south, bbox.north),
            bbox = bbox,
            slots = slots,
            onProceed = { b, selectedSlots, replace ->
                onDone()
                Thread { DownloadQueueManager.submitDownload(ctx, b.north, b.south, b.east, b.west, selectedSlots, replace) }.start()
                // MAPOPEN-2026-10-23 (Fred 11:08): say what happened -- the box alone did not.
                android.widget.Toast.makeText(ctx, "Download queued \u2014 ${estimate.tileCount} tiles from " + selectedSlots.joinToString(", ") +
                    ". See MAP DOWNLOADS.", android.widget.Toast.LENGTH_LONG).show()
            },
            onCancel = onDone,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/** Remove maps by area: the planner's confirm, as is (all sources, an upper bound, cannot be undone) -> submitDelete. */
@Composable
fun GtRemoveAreaScreen(bbox: DownloadBbox, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val delTiles = remember(bbox) { ConvoyTileCalculator.calculateTiles(bbox.north, bbox.south, bbox.east, bbox.west).size }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Remove tiles?", fontFamily = FontFamily.Monospace) },
        text = {
            Text("Delete downloaded map tiles in this area from ALL sources.\n\n" +
                String.format("%.3f\u00b0N to %.3f\u00b0N\n", bbox.south, bbox.north) +
                "Up to " + delTiles + " tiles per layer.\n\nThis cannot be undone.",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        },
        confirmButton = {
            Text("REMOVE TILES", color = Color(0xFFf85149), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    onDone()
                    Thread { DownloadQueueManager.submitDelete(ctx, bbox.north, bbox.south, bbox.east, bbox.west) }.start()
                    android.widget.Toast.makeText(ctx, "Removing tiles in this area \u2014 see MAP DOWNLOADS.",   // MAPOPEN-2026-10-23
                        android.widget.Toast.LENGTH_LONG).show()
                }.padding(12.dp))
        },
        dismissButton = {
            Text("CANCEL", color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onDone() }.padding(12.dp))
        },
        containerColor = Color(0xFF131820)
    )
}

/** Import trails by area: the trail import in area mode, as the planner opens it (S, W, N, E). */
@Composable
fun GtImportAreaScreen(bbox: DownloadBbox, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1216))) {
            HomeStatePickerScreen(onNavigateBack = onDone, areaBbox = doubleArrayOf(bbox.south, bbox.west, bbox.north, bbox.east))
        }
    }
}

/** GTQSUM-2026-10-23 (Fred 11:04): the downloads summary, ALWAYS shown -- the existing summary while maps download,
 *  else "No maps downloading". Tapping either opens the downloads panel (onOpen). */
@Composable
fun GtQueueSummary(onOpen: () -> Unit, modifier: Modifier) {
    val q = DownloadQueueManager.queue.collectAsState().value
    val busy = q.any { it.status == com.geeksville.mesh.convoy.QueueStatus.DOWNLOADING || it.status == com.geeksville.mesh.convoy.QueueStatus.QUEUED }
    if (busy) com.geeksville.mesh.convoy.DownloadQueuePanel(expanded = false, onToggle = onOpen, modifier = modifier)
    else androidx.compose.foundation.layout.Row(
        modifier = modifier.background(Color(0xE6131820), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text("No maps downloading", color = Color(0xFFCCDDEE), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Text("DETAILS \u203a", color = Color(0xFF1CF0A0), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** GTTRACKS-2026-10-23: Download maps along tracks -- the corridor picker as is (ALL or pick tracks, sources, replace)
 *  -> enqueueCorridorBatch, one job per track per source. Tracks with no line are skipped and counted. */
@Composable
fun GtDownloadTracksScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val tracks = remember { androidx.compose.runtime.mutableStateOf<List<com.geeksville.mesh.convoy.TrackPickInfo>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        tracks.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.geeksville.mesh.convoy.trackPickRowsFrom(com.geeksville.mesh.convoy.SpatialDbManager.queryAllTracksForCorridor())
        }
    }
    val slots = remember { MapSourceManager.getSlotSources().map { (legacyKey, shortLabel, _) ->
        SlotDisplayInfo(slotName = legacyKey, sourceName = shortLabel, directory = legacyKey, preSelected = true) } }
    val list = tracks.value ?: return   // still reading the tracks
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.geeksville.mesh.convoy.ConvoyCorridorPicker(
            tracks = list,
            slots = slots,
            onProceed = { hashes, slotsSel, replace ->
                onDone()
                Thread {
                    val r = DownloadQueueManager.enqueueCorridorBatch(ctx, hashes, slotsSel, replace)
                    val msg = if (r.skipped > 0) "Queued ${r.jobs} job(s), ${r.tiles} tiles - ${r.skipped} track(s) skipped (no geometry)"
                              else "Queued ${r.jobs} job(s), ${r.tiles} tiles"
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }.start()
            },
            onCancel = onDone,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/**
 * GTOFFGRID-2026-10-23 (Fred 11:11): FILTER > OFF-GRID MAPS. The planner's two Show-downloads overlays, copied as is.
 * [js] is the ONE MAP page, set by GtAreaHost.
 */
object GtOffGrid {
    var js: (String) -> Unit = {}
    val available = androidx.compose.runtime.mutableStateOf(false)
    val downloading = androidx.compose.runtime.mutableStateOf(false)

    /** Available: SAT tiles at z16, merged into runs per row (OVERLAYZ16-2026-08-12E) -- where you hold DETAIL. */
    fun setAvailable(on: Boolean) {
        available.value = on
        if (!on) { js("clearDownloadedAreas()"); return }
        Thread {
            val bounds = mutableListOf<String>()
            val z = 16; val n = 1 shl z
            val byRow = HashMap<Long, MutableList<Long>>()
            for ((x, y) in com.geeksville.mesh.convoy.MBTilesStore.xyAtZoom("SAT", z)) byRow.getOrPut(y) { ArrayList() }.add(x)
            for ((ry, xs) in byRow) {
                xs.sort()
                var i = 0
                while (i < xs.size) {
                    var j = i
                    while (j + 1 < xs.size && xs[j + 1] == xs[j] + 1) j++
                    val tN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * ry / n))))
                    val tS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (ry + 1) / n))))
                    val tW = xs[i].toDouble() / n * 360.0 - 180.0
                    val tE = (xs[j] + 1).toDouble() / n * 360.0 - 180.0
                    bounds.add("{\"n\":$tN,\"s\":$tS,\"e\":$tE,\"w\":$tW}")
                    i = j + 1
                }
            }
            android.util.Log.i("Overlay", "GTOFFGRID available: ${bounds.size} rect(s)")
            val json = "[" + bounds.joinToString(",") + "]"
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (available.value) js("showDownloadedAreas($json)") }
        }.start()
    }

    /** Downloading: every DOWNLOADING / QUEUED job's box; a corridor's box is an estimate, drawn hollow. */
    fun setDownloading(on: Boolean) {
        downloading.value = on
        if (!on) { js("clearQueuedAreas()"); return }
        val pending = DownloadQueueManager.queue.value.filter {
            it.status == com.geeksville.mesh.convoy.QueueStatus.DOWNLOADING || it.status == com.geeksville.mesh.convoy.QueueStatus.QUEUED }
        val bounds = pending.map { e ->
            "{" + "\"n\":" + e.north + ",\"s\":" + e.south + ",\"e\":" + e.east + ",\"w\":" + e.west +
                ",\"corridor\":" + (e.geomHash.isNotEmpty()) + ",\"label\":\"" + e.label.replace("\"", "") + "\"}"
        }
        js("showQueuedAreas([" + bounds.joinToString(",") + "])")
    }
}
