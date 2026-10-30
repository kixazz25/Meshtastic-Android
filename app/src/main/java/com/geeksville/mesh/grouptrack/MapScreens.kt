package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll   // GTRECIPES-2026-10-23
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column   // GTHEAVY-2026-10-23
import androidx.compose.foundation.layout.Row   // GTHEAVY
import androidx.compose.foundation.layout.Spacer   // GTHEAVY
import androidx.compose.foundation.layout.fillMaxWidth   // GTHEAVY
import androidx.compose.foundation.layout.height   // GTHEAVY
import androidx.compose.foundation.layout.width   // GTHEAVY
import androidx.compose.foundation.rememberScrollState   // GTHEAVY
import androidx.compose.material3.MaterialTheme   // GTHEAVY
import androidx.compose.material3.TextButton   // GTHEAVY
import kotlinx.coroutines.launch   // GTHEAVY
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
      Column(modifier = Modifier.padding(16.dp).background(Gt.panel).verticalScroll(rememberScrollState())) {   // GTSTD
        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Download maps by area", color = Gt.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            GtHelpButton("map.dl_area")
            GtCloseButton(onDone)
        }
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
}

/** Remove maps by area: the planner's confirm, as is (all sources, an upper bound, cannot be undone) -> submitDelete. */
@Composable
fun GtRemoveAreaScreen(bbox: DownloadBbox, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val delTiles = remember(bbox) { ConvoyTileCalculator.calculateTiles(bbox.north, bbox.south, bbox.east, bbox.west).size }
    AlertDialog(
        onDismissRequest = onDone,
        title = { GtDialogTitle("Remove tiles?", "map.rm_area", onDone) },   // GTSTD
        text = {
            Text("Delete downloaded map tiles in this area from ALL sources.\n\n" +
                String.format("%.3f\u00b0N to %.3f\u00b0N\n", bbox.south, bbox.north) +
                "Up to " + delTiles + " tiles per layer.\n\nThis cannot be undone.",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        },
        confirmButton = {
            Text("REMOVE TILES", color = Gt.bad, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    onDone()
                    Thread { DownloadQueueManager.submitDelete(ctx, bbox.north, bbox.south, bbox.east, bbox.west) }.start()
                    android.widget.Toast.makeText(ctx, "Removing tiles in this area \u2014 see MAP DOWNLOADS.",   // MAPOPEN-2026-10-23
                        android.widget.Toast.LENGTH_LONG).show()
                }.padding(12.dp))
        },
        dismissButton = {
            Text("CANCEL", color = Gt.dim, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onDone() }.padding(12.dp))
        },
        containerColor = Gt.panel
    )
}

/** Import trails by area: the trail import in area mode, as the planner opens it (S, W, N, E). */
@Composable
fun GtImportAreaScreen(bbox: DownloadBbox, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GtImportFrame(onDone) {   // GTSTD
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
        modifier = modifier.background(Gt.panel, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text("No maps downloading", color = Gt.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Text("DETAILS \u203a", color = Gt.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    val all = tracks.value ?: return   // still reading the tracks
    // GTFIX1-2026-10-23 (Fred 11:24): search by track name -- narrows the list the picker is given.
    val find = remember { androidx.compose.runtime.mutableStateOf("") }
    val list = if (find.value.isBlank()) all else all.filter { (it.name ?: "").contains(find.value.trim(), ignoreCase = true) }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp).background(Gt.panel)) {
        // GTFIX3-2026-10-23 (Fred 11:53): the standard header -- HELP and X are always reachable, keyboard or not.
        androidx.compose.foundation.layout.Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Download maps along tracks", color = Gt.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            GtHelpButton("map.dl_tracks")
            GtCloseButton(onDone)
        }
        androidx.compose.material3.OutlinedTextField(
            value = find.value, onValueChange = { find.value = it }, singleLine = true,
            label = { Text("Search tracks by name") },
            modifier = Modifier.padding(12.dp)
        )
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
}

/**
 * GTOFFGRID-2026-10-23 (Fred 11:11): FILTER > OFF-GRID MAPS. The planner's two Show-downloads overlays, copied as is.
 * [js] is the ONE MAP page, set by GtAreaHost.
 */
object GtOffGrid {
    var js: (String) -> Unit = {}
    val available = androidx.compose.runtime.mutableStateOf(false)
    val downloading = androidx.compose.runtime.mutableStateOf(false)

    /** Available: SAT tiles at z16, merged into runs per row (OVERLAYZ16-2026-08-12E) -- where you hold DETAIL.
     *  GTFIX2-2026-10-23 (Fred 11:36): RESTORED -- this view was working; GTFIX1 changed it by mistake. */
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

/** GTRECIPES-2026-10-23 (Fred 11:43): the daily map recovery recipes -- the planner's Map Recovery list, copied as is.
 *  Read only: SAVED ON THIS PHONE (60 days, newest first) and RECEIVED in Downloads. */
@Composable
fun GtRecipesScreen(onDone: () -> Unit) {
    // VECSOURCE-2026-10-30 (2.7e step 1): the vector download button
    val vecCtx = LocalContext.current
    val vecScope = androidx.compose.runtime.rememberCoroutineScope()
    val vecMsg = remember { androidx.compose.runtime.mutableStateOf("") }
    val vecBusy = remember { androidx.compose.runtime.mutableStateOf(false) }
    val rows = remember { androidx.compose.runtime.mutableStateOf<Pair<List<String>, List<String>>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        rows.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fun scan(d: java.io.File): List<String> {
                val out = ArrayList<String>()
                try {
                    val fs = d.listFiles()?.filter { it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps") }
                        ?.sortedByDescending { it.name } ?: emptyList()
                    for (f in fs) {
                        val date = f.name.removePrefix("grouptrack_maps_").removeSuffix(".gtmaps")
                        try {
                            val o = org.json.JSONObject(f.readText())
                            val a = o.getJSONArray("slots")
                            var tiles = 0L
                            for (i in 0 until a.length()) tiles += a.getJSONObject(i).optLong("tiles")
                            out.add("$date   ${a.length()} slot(s)   $tiles tiles   ${f.length() / 1024} KB")
                        } catch (e: Exception) { out.add("$date   unreadable (${f.length()} bytes)") }
                    }
                } catch (e: Exception) { out.add("could not read: ${e.message}") }
                return out
            }
            Pair(scan(com.geeksville.mesh.convoy.GroupTrackStorage.dir("recipes")),
                scan(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)))
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { GtDialogTitle("Saved map recipes", "map.recipes", onDone) },   // GTSTD
        text = {
            androidx.compose.foundation.layout.Column(modifier = Modifier.verticalScrollSafe()) {
                Text("A daily record of what your maps cover, kept 60 days -- what Restore will use.",
                    color = Gt.dim, fontSize = 12.sp)
                val r = rows.value
                if (r == null) Text("Reading\u2026", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                else {
                    Text("SAVED ON THIS PHONE", color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp))
                    if (r.first.isEmpty()) Text("none yet \u2014 written once a day at startup", fontSize = 12.sp, color = Gt.dim)
                    r.first.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    Text("RECEIVED (Downloads)", color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp))
                    if (r.second.isEmpty()) Text("none \u2014 recipes sent to you arrive here", fontSize = 12.sp, color = Gt.dim)
                    r.second.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    // VECSOURCE-2026-10-30 (Fred 10:12): the fourth group, from the newest recipe, through the download queues.
                    Text("VECTOR MAPS (2.7e, testing)", color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp))
                    Text(vecMsg.value.ifEmpty { "Downloads the vector map (OpenFreeMap, zoom 0\u201314) for the first 5 boxes of the newest recipe, through the download queues." },
                        fontSize = 12.sp, color = Gt.dim)
                    Text(if (vecBusy.value) "QUEUEING\u2026" else "DOWNLOAD VECTOR \u2014 FIRST 5 BOXES", color = Gt.accent,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = !vecBusy.value) {
                            vecBusy.value = true
                            vecScope.launch {
                                vecMsg.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.geeksville.mesh.convoy.VectorTiles.queueFromRecipe(vecCtx.applicationContext, 5)
                                }
                                vecBusy.value = false
                            }
                        }.padding(vertical = 10.dp))
                    // VECALL-2026-10-30 (Fred 11:11): every box of the newest recipe
                    Text(if (vecBusy.value) "" else "DOWNLOAD VECTOR \u2014 WHOLE RECIPE", color = Gt.accent,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = !vecBusy.value) {
                            vecBusy.value = true
                            vecScope.launch {
                                vecMsg.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.geeksville.mesh.convoy.VectorTiles.queueFromRecipe(vecCtx.applicationContext, Int.MAX_VALUE)
                                }
                                vecBusy.value = false
                            }
                        }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { Text("CLOSE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onDone() }.padding(12.dp)) },
        containerColor = Gt.panel
    )
}

@Composable
private fun Modifier.verticalScrollSafe(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

/** GTIMPORTS-2026-10-23: Import trails by state -- the trail import in STATE mode (no box), as the planner opens it. */
@Composable
fun GtImportStateScreen(onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GtImportFrame(onDone) { HomeStatePickerScreen(onNavigateBack = onDone) }   // GTSTD
    }
}

/** GTIMPORTS-2026-10-23: Import GPX -- the track import screen as is (tracks, waypoints, routes; RESYNC and rider trails inside). */
@Composable
fun GtImportGpxScreen(onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GtScreenFrame("Import GPX", "map.gpx", onDone) { com.geeksville.mesh.convoy.ConvoyTrackImportScreen(onDismiss = onDone, startAsTrails = false) }   // GTSTD
    }
}

/** MAPIMPORTS-2026-10-30 (Fred 08:30): Create trails from GPX files -- the same import screen, the trails switch ON. */
@Composable
fun GtImportGpxTrailsScreen(onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GtScreenFrame("Import trails from GPX files", "map.gpxtrails", onDone)   /* TRAILSNAME-2026-10-30 */ { com.geeksville.mesh.convoy.ConvoyTrackImportScreen(onDismiss = onDone, startAsTrails = true) }   // GTSTD
    }
}

/** MAPIMPORTS-2026-10-30 (Fred 08:33): Convert files to GPX -- KML / KMZ / GeoJSON to a GPX in Download; imports nothing. */
@Composable
fun GtConvertGpxScreen(onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GtScreenFrame("Convert files to GPX", "map.convert", onDone) { com.geeksville.mesh.convoy.ConvertToGpxScreen(onDone) }   // GTSTD
    }
}

/** GTIMPORTS-2026-10-23: Create trails from tracks -- RiderTrailsDialog + RiderTrailWriter.scanAll, copied from the import screen. */
@Composable
fun GtRiderTrailsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val status = remember { androidx.compose.runtime.mutableStateOf("") }
    val running = remember { androidx.compose.runtime.mutableStateOf(false) }
    val result = remember { androidx.compose.runtime.mutableStateOf<com.geeksville.mesh.convoy.RiderTrailWriter.Result?>(null) }
    com.geeksville.mesh.convoy.RiderTrailsDialog(
        status = status.value,
        result = result.value,
        running = running.value,
        onStart = {
            running.value = true
            status.value = "starting\u2026"
            result.value = null
            Thread {
                com.geeksville.mesh.convoy.SpatialDbManager.init(ctx)
                val r = com.geeksville.mesh.convoy.RiderTrailWriter.scanAll { done, total ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { status.value = "scanning track $done of $total" }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    result.value = r; running.value = false; status.value = ""
                }
            }.start()
        },
        onClose = onDone
    )
}

/**
 * GTHEAVY-2026-10-23: Remove maps by track -- the planner's "Remove Tiles by Track" (REMOVETRACK-PREVIEW-2026-08-07F +
 * CORRMIGRATE-2026-08-07H), copied as is. Opening it = the planner's tick: hold, clear, scan (all reversible). Nothing is
 * destroyed before PROCEED. CANCEL releases the queue.
 */
@Composable
fun GtRemoveTrackScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val busy = remember { androidx.compose.runtime.mutableStateOf(true) }
    val steps = remember { androidx.compose.runtime.mutableStateOf(listOf("Holding queue and clearing pending work")) }
    val preview = remember { androidx.compose.runtime.mutableStateOf<com.geeksville.mesh.convoy.ConvoyCorridorDelete.PreviewResult?>(null) }
    val done = remember { androidx.compose.runtime.mutableStateOf(false) }
    val progress = remember { androidx.compose.runtime.mutableStateOf("Scanning tracks - this can take a minute") }
    val fraction = remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // CORRMIGRATE-2026-08-07H step 1-3: hold, clear, scan. All reversible -- nothing is destroyed until the gate.
        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DownloadQueueManager.holdQueue()
            DownloadQueueManager.cancelAll()
            com.geeksville.mesh.convoy.ConvoyCorridorDelete.previewAllTracks(context, "SAT")
        }
        preview.value = p
        steps.value = listOf("Queue held, pending work cleared (history kept)",
            "Scanned ${p.tracks.size} tracks - ${p.onDiskTotal} tiles on disk")
        progress.value = ""
        busy.value = false
    }
    val p = preview.value
    AlertDialog(
        onDismissRequest = { },
        containerColor = Gt.panel,
        title = {   // GTSTD: "Remove maps by track" (was the planner's "Refresh map tiles"); X = CANCEL, off while working
            GtDialogTitle(if (done.value) "Removal started" else "Remove maps for all tracks", "map.rm_track",   /* RMALLTRACKS-2026-10-23 */
                onClose = { if (done.value) onDone() else scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DownloadQueueManager.resumeQueue() }
                    onDone() } },
                closeEnabled = !busy.value)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                steps.value.forEach { s ->
                    Text("✓ " + s, color = Gt.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(3.dp))
                }
                if (busy.value) {
                    Spacer(Modifier.height(4.dp))
                    Text(if (progress.value.isBlank()) "Working..." else progress.value, color = Gt.warn, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                    if (fraction.floatValue >= 0f) {
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.LinearProgressIndicator(progress = { fraction.floatValue },
                            modifier = Modifier.fillMaxWidth(), color = Gt.accent, trackColor = Gt.panel)
                    }
                }
                if (!done.value && !busy.value && p != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Remove ${p.onDiskTotal} track tiles and rebuild from the new source?", color = Gt.bad,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Track coverage is removed and re-downloaded. Tracks reappear one at a time as each finishes - " +
                        "track coverage is queued first.\n\nYour area maps stay visible the whole time. Old tiles are replaced " +
                        "as new ones arrive.\n\nDownloads continue in the background and resume if the app or device restarts.",
                        color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                if (done.value) {
                    Spacer(Modifier.height(8.dp))
                    Text("Downloads continue in the background and resume if the app or device restarts. Your area maps stay " +
                        "visible while tiles are replaced. Track coverage returns first.",
                        color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            if (done.value) {
                TextButton(onClick = onDone) {
                    Text("DONE", color = Gt.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            } else if (!busy.value && p != null) {
                TextButton(onClick = {
                    // PAST THIS POINT THERE ARE NO MORE GATES. Stopping after the delete would strand the user with removed
                    // coverage and nothing queued.
                    scope.launch {
                        busy.value = true
                        val hashes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.geeksville.mesh.convoy.SpatialDbManager.allTrackGeomHashes().map { it.first }
                        }
                        val del = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.geeksville.mesh.convoy.ConvoyCorridorDelete.deleteAllTrackCorridors(context, "SAT") { d, total, name ->
                                // This lambda runs on IO -- Compose state is written on main.
                                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    progress.value = "Deleting track $d of $total - $name"
                                    fraction.floatValue = if (total > 0) d.toFloat() / total else -1f
                                }
                            }
                        }
                        progress.value = ""
                        fraction.floatValue = -1f
                        steps.value = steps.value + ("Deleted ${del.tilesRemoved} tiles from ${del.tracksProcessed} tracks")
                        val batch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            DownloadQueueManager.enqueueCorridorBatch(context, hashes, listOf("SAT"), true)
                        }
                        steps.value = steps.value + ("Queued ${batch.jobs} track corridors (${batch.tiles} tiles)")
                        val cells = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            DownloadQueueManager.enqueueRefresh(context, "SAT", "SAT")
                        }
                        steps.value = steps.value + "Queued area refresh ($cells cells)"
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DownloadQueueManager.resumeQueue() }
                        steps.value = steps.value + "Queue released"
                        busy.value = false
                        done.value = true
                    }
                }) {
                    Text("PROCEED", color = Gt.bad, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!done.value && !busy.value) {
                TextButton(onClick = {
                    // Nothing has been destroyed. Release the queue so backing out does not leave it held.
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DownloadQueueManager.resumeQueue() }
                        onDone()
                    }
                }) {
                    Text("CANCEL", color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    )
}

/**
 * GTHEAVY-2026-10-23: Refresh maps by source -- the planner's "Recreate Tiles by Source" (RECREATE-2026-08-11), copied as is.
 * Re-downloads every tile the store HOLDS for the slot, in place; ground with no tiles is never requested.
 */
@Composable
fun GtRefreshSourceScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val slot = remember { androidx.compose.runtime.mutableStateOf(com.geeksville.mesh.convoy.ConvoyConfig.ACTIVE_TILE_SOURCE) }
    val rows = remember { androidx.compose.runtime.mutableStateOf(listOf<Pair<Int, Int>>()) }
    val total = remember { androidx.compose.runtime.mutableStateOf(0L) }
    val scanning = remember { androidx.compose.runtime.mutableStateOf(false) }
    val progress = remember { androidx.compose.runtime.mutableStateOf("") }
    // RECREATE-2026-08-11F: the scan runs keyed on the chosen slot; picking another slot re-keys it and it scans again.
    androidx.compose.runtime.LaunchedEffect(slot.value) {
        val s = slot.value
        if (s.isBlank()) return@LaunchedEffect
        rows.value = emptyList(); total.value = 0L; progress.value = ""; scanning.value = true
        Thread {
            val rs = ArrayList<Pair<Int, Int>>()
            var t = 0L
            try {
                val levels = com.geeksville.mesh.convoy.MBTilesStore.zoomLevelsPresent(s)
                for ((idx, z) in levels.withIndex()) {
                    val n = com.geeksville.mesh.convoy.MBTilesStore.countAtZoom(s, z)
                    rs.add(z to n); t += n
                    val soFar = ArrayList(rs); val runTotal = t
                    val line = "level ${idx + 1} of ${levels.size} - $runTotal tile(s) so far"
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (slot.value == s) { rows.value = soFar; total.value = runTotal; progress.value = line }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Recreate", "GTHEAVY recreate scan failed: ${e.message}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // a slower scan of the PREVIOUS slot must not overwrite the results of the one since picked
                if (slot.value == s) { rows.value = rs; total.value = t; progress.value = ""; scanning.value = false }
            }
        }.start()
    }
    val jobs = if (total.value <= 0L) 0 else ((total.value + 50000 - 1) / 50000).toInt()
    AlertDialog(
        onDismissRequest = onDone,
        title = { GtDialogTitle("Refresh maps", "map.repair", onDone) /* ONESOURCEUI-2026-10-30 */ },   // GTSTD
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MapSourceManager.getSlotSources().takeIf { it.size > 1 }?.forEach { src ->   // ONESOURCEUI-2026-10-30: no picker for one
                        val key = src.first
                        TextButton(enabled = !scanning.value && key != slot.value, onClick = { slot.value = key }) {
                            Text(if (key == slot.value) "[$key]" else key, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (scanning.value) {
                    Text("Reading the store...")
                    if (progress.value.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(progress.value, style = MaterialTheme.typography.bodySmall) }
                    rows.value.forEach { (z, n) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("z$z", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
                            Text(n.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (rows.value.isEmpty()) {
                    Text("No tiles are stored for ${slot.value}, so there is nothing to recreate.")
                } else {
                    Text("These are the tiles this source actually holds. Only these are re-downloaded -- ground with no tiles " +
                        "is never requested.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    rows.value.forEach { (z, n) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("z$z", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(n.toString(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${total.value} tile(s) in ${rows.value.size} zoom level(s)", style = MaterialTheme.typography.bodyMedium)
                    Text("$jobs job(s) of up to 50000 tiles", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Your maps keep working while this runs -- each tile is replaced in place, so nothing is removed first.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !scanning.value && rows.value.isNotEmpty(), onClick = {
                val s = slot.value
                onDone()
                Thread {
                    val queued = DownloadQueueManager.enqueueRecreateSource(context, s)
                    android.util.Log.i("Recreate", "GTHEAVY PROCEED $s -> $queued job(s) queued")
                }.start()
            }) { Text("PROCEED TO COPY") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("CANCEL") } }
    )
}

/** GTSTD-2026-10-23: the standard header for a full-screen module -- title, [extra] buttons, HELP, X; [content] below. */
@Composable
fun GtScreenFrame(title: String, helpId: String, onClose: () -> Unit, note: String = "",
                  extra: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Gt.panel)) {
        Row(modifier = Modifier.fillMaxWidth().background(Gt.panel).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Gt.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (note.isNotEmpty()) Text(note, color = Gt.dim, fontSize = 12.sp)
            }
            extra()
            GtHelpButton(helpId)
            GtCloseButton(onClose)
        }
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/** GTSTD-2026-10-23: the trail import in the standard frame, with CANCEL IMPORT while one runs (the real cancel). */
@Composable
fun GtImportFrame(onClose: () -> Unit, content: @Composable () -> Unit) {
    com.geeksville.mesh.convoy.HomeStateImportController.progress.collectAsState().value   // recompose as the import moves
    val importing = com.geeksville.mesh.convoy.HomeStateImportController.isImporting
    GtScreenFrame("Import trails", "map.trails_state", onClose,
        note = if (importing) "Importing \u2014 X closes this view, the import keeps running" else "",
        extra = { if (importing) TextButton(onClick = { com.geeksville.mesh.convoy.HomeStateImportController.cancelImport() }) {
            Text("CANCEL IMPORT", color = Gt.bad, fontWeight = FontWeight.Bold) } },
        content = content)
}

/** GTSTD-2026-10-23: a dialog title meeting the standard -- the title, then HELP and X. */
@Composable
fun GtDialogTitle(title: String, helpId: String, onClose: () -> Unit, closeEnabled: Boolean = true) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        GtHelpButton(helpId)
        GtCloseButton(onClose, enabled = closeEnabled)
    }
}

/** GTSYNC-2026-10-23 (Fred 12:42): Resync tracks with their files -- SyncTracksDialog + syncTracksFromFiles, copied from the
 *  Import GPX screen as is (visible run, live feed, failures first). */
@Composable
fun GtSyncTracksScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember { androidx.compose.runtime.mutableStateOf(listOf<String>()) }
    val running = remember { androidx.compose.runtime.mutableStateOf(false) }
    val result = remember { androidx.compose.runtime.mutableStateOf<com.geeksville.mesh.convoy.SpatialDbManager.TrackSyncResult?>(null) }
    com.geeksville.mesh.convoy.SyncTracksDialog(
        syncLines = lines.value,
        syncResult = result.value,
        syncRunning = running.value,
        onStart = {
            running.value = true
            lines.value = listOf("\u2014 starting sync \u2014")
            result.value = null
            Thread {
                com.geeksville.mesh.convoy.SpatialDbManager.init(ctx)
                val r = com.geeksville.mesh.convoy.SpatialDbManager.syncTracksFromFiles(ctx) { line ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { lines.value = lines.value + line }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.value = r; running.value = false }
            }.start()
        },
        onClose = onDone
    )
}

/** GTRADIUS-2026-10-23: the Area Search Radius -- the zoom ONE MAP's search flies to. 13 = today's behaviour. */
object GtSearchZoom {
    private const val PREFS = "grouptrack_search"
    private const val KEY = "fly_zoom"
    fun get(ctx: android.content.Context): Int =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt(KEY, 13).coerceIn(12, 18)
    fun set(ctx: android.content.Context, z: Int) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putInt(KEY, z.coerceIn(12, 18)).apply()
    }
}

/** GTRADIUS-2026-10-23 (Fred 12:46-12:48): Area Search Radius -- how much of the map a search shows around a place. */
@Composable
fun GtAreaRadiusScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val z = remember { androidx.compose.runtime.mutableFloatStateOf(GtSearchZoom.get(ctx).toFloat()) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { GtDialogTitle("Area Search Radius", "map.flyover", onDone) },
        text = {
            Column {
                Text("How much of the map a search shows around the place it finds. A lower number shows a wider area, " +
                    "a higher number a closer one.", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text("Z" + z.floatValue.toInt() + (if (z.floatValue.toInt() == 13) "  (standard)" else ""),
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                androidx.compose.material3.Slider(value = z.floatValue, onValueChange = { z.floatValue = it },
                    valueRange = 12f..18f, steps = 5)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Z12  wider", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Z18  closer", fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { GtSearchZoom.set(ctx, z.floatValue.toInt()); onDone() }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDone) { Text("CANCEL") } },
        containerColor = Gt.panel
    )
}
