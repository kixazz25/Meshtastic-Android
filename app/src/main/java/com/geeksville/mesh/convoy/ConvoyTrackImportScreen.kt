package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ConvoyTrackImportScreen -- V2.4 in-app file browser for track import.
 *
 * Replaces the system file picker to eliminate ANR.
 * Scans /sdcard/Download/ for .gpx and .kml files.
 * Multi-select with checkboxes, progress dialog, recap dialog.
 * Uses existing ConvoyTrackOps.importTrackFile() for all processing.
 * Date correction is built into importTrackFile -- each created file
 * gets its mtime set from the earliest GPS <time> in the track data.
 */
@Composable
fun ConvoyTrackImportScreen(
    onDismiss: () -> Unit,
    // MAPIMPORTS-2026-10-30: true = opened as "Create trails from GPX files" -- the trails switch starts ON.
    // CODE RULE 1: required -- every caller says which screen it opens.
    startAsTrails: Boolean,
    // RIDEIMPORT2-2026-09-24: files already staged (an imported ride's GPX). Empty by default -- normal use unchanged.
    preloaded: List<File> = emptyList()
) {

    // -- State --------------------------------------------------------
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // MAPSDEFAULT-2026-09-16: INVERTED. This set previously held the files INCLUDED, default
    // empty = none, which made map download default OFF and -- with the checkbox
    // invisible -- effectively unreachable. It now holds the files EXCLUDED, so
    // an empty set means EVERY file gets maps. ⭐ Inverting avoids hooking file
    // loading: the picker can stage any list and the default still holds.
    var mapsSkip by remember { mutableStateOf<Set<String>>(emptySet()) }   // per-file: EXCLUDE map download on import
    var showSourcePopup by remember { mutableStateOf(false) }
    var selectedSlots by remember { mutableStateOf<List<String>>(emptyList()) }
    var replaceExisting by remember { mutableStateOf(false) }
    var asTrails by remember { mutableStateOf(startAsTrails) }   /* MAPIMPORTS-2026-10-30 */   // IMPORTTRAILS-2026-10-30: "Import tracks as trails" -- off = the original import
    // IMPORTPICKER-2026-09-13: nothing is scanned now; this covers STAGING.
    var scanning by remember { mutableStateOf(false) }

    // Progress dialog state
    var showProgress by remember { mutableStateOf(false) }
    var progressCurrent by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf(0) }
    var progressName by remember { mutableStateOf("") }

    // Recap dialog state
    var showRecap by remember { mutableStateOf(false) }
    var recapImported by remember { mutableStateOf<List<String>>(emptyList()) }
    var recapSkipped by remember { mutableStateOf<List<String>>(emptyList()) }
    var recapFailed by remember { mutableStateOf<List<String>>(emptyList()) }
    var recapDatesCorrected by remember { mutableStateOf(0) }
    var recapNew by remember { mutableStateOf(0) }    // ADDED 2026-06-02
    var recapDupe by remember { mutableStateOf(0) }   // ADDED 2026-06-02
    var recapAliased by remember { mutableStateOf(0) }  // ADDED 2026-06-30
    var recapSkippedCount by remember { mutableStateOf(0) }  // ADDED 2026-06-30
    // Live per-record import feed (mirrors the sync control feed).
    var importLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val importListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var recapWaypoints by remember { mutableStateOf(0) }
    var recapRoutes by remember { mutableStateOf(0) }
    var processedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    // RIDEIMPORT2-2026-09-24: an imported ride's GPX arrives already staged -- listed and ticked, no picker.
    androidx.compose.runtime.LaunchedEffect(preloaded) {
        if (preloaded.isNotEmpty()) { files = preloaded; selected = preloaded.map { it.name }.toSet() }
    }

    // -- SYNC CONTROL dialog state (visible run + failure-first recap) --
    var showSyncDialog by remember { mutableStateOf(false) }
    var syncLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var syncRunning by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<SpatialDbManager.TrackSyncResult?>(null) }

    // RIDERTRAILS-2026-09-07: the same four-part shape as the sync state above.
    // ⚠ riderResult null means NOT RUN, which is a different thing from a run
    // that found nothing -- the dialog has to be able to say which.
    var showRiderDialog by remember { mutableStateOf(false) }
    var riderStatus by remember { mutableStateOf("") }
    var riderRunning by remember { mutableStateOf(false) }
    var riderResult by remember { mutableStateOf<RiderTrailWriter.Result?>(null) }
    val syncListState = rememberLazyListState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // IMPORTPICKER-2026-09-13: \u26d4 THE DOWNLOADS SCAN IS GONE.
    // It returned an EMPTY LIST once MANAGE_EXTERNAL_STORAGE left the manifest
    // -- not an error, an empty list, which reads as "you have no files".
    // \u26a0 An app may WRITE to Downloads without permission but not READ what
    // another app put there. Export still works; only this was broken.
    // \u2b50 The picker IS the permission: the rider's selection is the grant.
    var deleteOriginals by remember { mutableStateOf(true) }
    var staging by remember { mutableStateOf(false) }
    var stageNote by remember { mutableStateOf("") }

    /**
     * \u2b50 STAGING, AND IT IS SELF-CONTAINED. Copy each picked file into storage
     * the app owns, verify the size, and delete the original if asked -- all
     * while the URI grant is live. By the time the import runs the URIs are
     * finished with entirely.
     *
     * \u26a0 APP-PRIVATE, not Documents. A visible folder would need the Documents
     * tree grant, and a FRESH INSTALL never takes one because the conversion
     * skips. This needs nothing and works everywhere.
     */
    fun stageAndList(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        staging = true
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                val dir = java.io.File(context.filesDir, "gpx_staging")
                // \u26a0 CLEARED AT THE START TOO. A previous run that died leaves
                // files here, and importing yesterday's selection silently would
                // be worse than losing it.
                if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
                dir.mkdirs()
                val out = ArrayList<java.io.File>()
                for (uri in uris) {
                    val doc = androidx.documentfile.provider.DocumentFile
                        .fromSingleUri(context, uri)
                    val name = doc?.name ?: "import_${System.currentTimeMillis()}.gpx"
                    stageNote = name
                    val target = java.io.File(dir, name)
                    try {
                        context.contentResolver.openInputStream(uri).use { input ->
                            if (input == null) throw IllegalStateException("no input stream")
                            target.outputStream().use { o -> input.copyTo(o) }
                        }
                        // \u26d4 VERIFY BEFORE DELETING. A source removed on a short
                        // write is the one loss worth preventing.
                        val srcLen = doc?.length() ?: -1L
                        if (srcLen > 0 && target.length() != srcLen) {
                            android.util.Log.e("ImportPicker",
                                "$name SIZE MISMATCH $srcLen != ${target.length()}")
                            target.delete()
                            continue
                        }
                        out.add(target)
                        if (deleteOriginals) {
                            // \u26a0 NEEDS THE WRITE FLAG ON THE PICKER INTENT. Without
                            // it this fails silently and the rider thinks the
                            // original went.
                            val gone = try { doc?.delete() ?: false }
                            catch (e: Exception) {
                                android.util.Log.w("ImportPicker",
                                    "delete $name: ${e.message}"); false
                            }
                            android.util.Log.i("ImportPicker",
                                "$name staged, original " +
                                    (if (gone) "deleted" else "KEPT"))
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ImportPicker", "$name FAILED: ${e.message}")
                    }
                }
                out
            }
            files = staged
            selected = staged.map { it.name }.toSet()   // \u2b50 all selected by default
            staging = false
            stageNote = ""
        }
    }

    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<android.net.Uri> ->
        // \u26a0 Persist nothing here: these are per-file grants, used and finished
        // with inside stageAndList.
        stageAndList(uris)
    }
    // FILEPICKNOTICE-2026-10-10: the panel before the picker -- both launches below go through it.
    val pickNotice = rememberPickerNotice("to choose the track files you want to import", "files")

    // \u26a0 scanning starts FALSE -- there is nothing to scan any more.
    // STORAGECTX-2026-09-13: \u2b50 AND THE PICKER OPENS ITSELF. The entry panel
    // existed only to hold a button that launched it -- with no files there is
    // nothing to configure, so the rider went screen -> picker -> back to the
    // same screen. \u26a0 The delete choice was already defaulted on and happens
    // during staging, so asking first was the redundant step; the list reports
    // what was deleted.
    var pickerOpened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        scanning = false
        if (!pickerOpened && files.isEmpty()) {
            pickerOpened = true
            pickNotice.ask { pickFiles.launch(arrayOf(
                "application/gpx+xml", "application/vnd.google-earth.kml+xml",
                "application/octet-stream", "text/xml", "*/*")) }
        }
    }

    // -- Import handler -----------------------------------------------
    fun doImport() {
        val sel = files.filter { selected.contains(it.name) }
        if (sel.isEmpty()) return
        showProgress = true
        progressCurrent = 0
        progressTotal = sel.size
        processedFiles = sel
        scope.launch {
            val imported = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val failed = mutableListOf<String>()
            var datesCorrected = 0
            var wptTotal = 0
            var rteTotal = 0
            var newTotal = 0
            var dupeTotal = 0
            var aliasedTotal = 0
            var skippedTotal = 0
            for ((i, f) in sel.withIndex()) {
                progressCurrent = i + 1
                progressName = f.name
                importLines = importLines + "— ${f.name} —"
                try {
                    // MAPSDEFAULT-2026-09-16: maps unless this file was explicitly excluded.
                    val summary = ConvoyTrackOps.importGpxAllArtifacts(f, context, if (!mapsSkip.contains(f.name)) selectedSlots else emptyList(), replaceExisting, asTrails) { line ->
                        importLines = importLines + line
                    }
                    imported.addAll(summary.trackFiles)
                    wptTotal += summary.waypointCount
                    rteTotal += summary.routeCount
                    datesCorrected += summary.trackFiles.size
                    newTotal += summary.inserted
                    dupeTotal += summary.dropped
                    aliasedTotal += summary.aliased
                    skippedTotal += summary.skipped
                    if (summary.errors.isNotEmpty()) {
                        failed.addAll(summary.errors.map { f.name + ": " + it })
                    }
                } catch (e: Exception) {
                    failed.add(f.name + ": " + (e.message ?: "unknown error"))
                    importLines = importLines + "ERROR: ${f.name}: ${e.message ?: "unknown"}"
                }
            }
            recapImported = imported
            recapSkipped = skipped
            recapFailed = failed
            recapDatesCorrected = datesCorrected
            recapWaypoints = wptTotal
            recapRoutes = rteTotal
            recapNew = newTotal
            recapDupe = dupeTotal
            recapAliased = aliasedTotal
            recapSkippedCount = skippedTotal
            android.util.Log.i("Import", "RECAP TRIGGER: new=$newTotal dupe=$dupeTotal files=${imported.size}")
            showProgress = false
            showRecap = true
        }
    }

    // -- Dialogs ------------------------------------------------------
    if (showSourcePopup) {
        // MAPINIT-2026-09-24: load the SAVED sources first -- never the hardcoded fallback.
        val slotSources = remember { MapSourceManager.init(context.applicationContext); MapSourceManager.getSlotSources() }
        val popupSlots = remember(slotSources) {
            slotSources.map { (k, label, _) ->
                SlotDisplayInfo(
                    slotName = k, sourceName = label, directory = k,
                    tileCount = 0, sizeMB = 0f, preSelected = true
                )
            }
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { showSourcePopup = false }) {
            ConvoyDownloadConfirm(
                estimatedTiles = 0,
                estimatedMB = 0f,
                areaDesc = "Import: download maps for selected tracks",
                bbox = DownloadBbox(),
                slots = popupSlots,
                onProceed = { _, sel, replace ->
                    selectedSlots = sel
                    replaceExisting = replace
                    showSourcePopup = false
                    doImport()
                },
                onCancel = { showSourcePopup = false }
            )
        }
    }

    if (showProgress) {
        ImportProgressDialog(
            current = progressCurrent,
            total = progressTotal,
            currentName = progressName,
            feedLines = importLines,
            listState = importListState
        )
    }
    if (showRecap) {
        ImportRecapDialog(
            imported = recapImported,
            skipped = recapSkipped,
            failed = recapFailed,
            datesCorrected = recapDatesCorrected,
            waypoints = recapWaypoints,
            routes = recapRoutes,
            newCount = recapNew,
            dupeCount = recapDupe,
            aliasedCount = recapAliased,
            skippedTrkCount = recapSkippedCount,
            onFilesDelete = {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        for (f in processedFiles) {
                            try { f.delete() } catch (_: Exception) {}
                        }
                    }
                }
            },
            onDismiss = { showRecap = false; onDismiss() }
        )
    }

    // -- Main UI ------------------------------------------------------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Gt.panel)
    ) {
        // -- Top bar --
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Gt.panel)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "\u2190",
                color = Gt.accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(end = 16.dp)
            )
            Column {
                Text(
                    if (startAsTrails) "Import Trails from GPX Files" /* TRAILSNAME-2026-10-30 */ else "Import Tracks from Downloads",   // MAPIMPORTS-2026-10-30
                    color = Gt.accent,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (scanning) "Scanning Downloads folder..."
                    else "${files.size} GPX/KML files found",
                    color = Gt.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // GTSYNC-2026-10-23 (Fred 12:42): RESYNC TRACKS and ADD TRAILS FROM TRACKS moved to their own Map menu rows
        // (IMPORT MAP FEATURES > Resync tracks with their files / Create trails from tracks). Buttons removed here.
        if (showRiderDialog) {
            RiderTrailsDialog(
                status = riderStatus,
                result = riderResult,
                running = riderRunning,
                onStart = {
                    riderRunning = true
                    riderStatus = "starting\u2026"
                    riderResult = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            SpatialDbManager.init(context)
                            RiderTrailWriter.scanAll { done, total ->
                                riderStatus = "scanning track $done of $total"
                            }
                        }
                        riderResult = r
                        riderRunning = false
                        riderStatus = ""
                    }
                },
                onClose = { showRiderDialog = false }
            )
        }

        // ====================================================================
        // SYNC CONTROL DIALOG — visible run, live feed, failure-first recap.
        // ====================================================================
        if (showSyncDialog) {
            SyncTracksDialog(
                syncLines = syncLines,
                syncResult = syncResult,
                syncRunning = syncRunning,
                onStart = {
                    syncRunning = true
                    syncLines = listOf("— starting sync —")
                    syncResult = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            SpatialDbManager.init(context)
                            SpatialDbManager.syncTracksFromFiles(context) { line ->
                                syncLines = syncLines + line
                            }
                        }
                        syncResult = r
                        syncRunning = false
                    }
                },
                onClose = { showSyncDialog = false }
            )
        }

        // -- Content area --
        if (scanning) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Gt.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Scanning Downloads...",
                        color = Gt.dim,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else if (staging) {
            // IMPORTPICKER-2026-09-13: copying the picked files in.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Gt.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(stageNote.ifBlank { "Copying files..." },
                        color = Gt.dim, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        } else if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // IMPORTPICKER-2026-09-13: \u2b50 THE ENTRY POINT, not an error.
                    // The old text said no files were FOUND, which was a lie once
                    // the scan could not see them.
                    Text(
                        "Select the GPX or KML files\nyou want to import.",
                        color = Gt.dim,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    // \u26a0 ONE CHOICE FOR THE RUN, DEFAULT ON. Fred, 09-13.
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            deleteOriginals = !deleteOriginals
                        }) {
                        Text(if (deleteOriginals) "[x]" else "[ ]",
                            color = Gt.accent, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete the originals after copying",
                            color = Gt.dim, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.clickable {
                            // \u26a0 GPX and KML are frequently reported as
                            // octet-stream, so the wildcard has to be there or
                            // the rider sees an empty picker.
                            pickNotice.ask { pickFiles.launch(arrayOf(
                                "application/gpx+xml", "application/vnd.google-earth.kml+xml",
                                "application/octet-stream", "text/xml", "*/*")) }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Gt.panel
                    ) {
                        Text("  SELECT FILES  ",
                            color = Gt.accent,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 10.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.clickable { onDismiss() },
                        shape = RoundedCornerShape(10.dp),
                        color = Gt.panel
                    ) {
                        Text(
                            "  Back to Map  ",
                            color = Gt.accent,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        } else {
            // -- File list --
            // [2026-07-02] column header over the two checkbox columns + file column
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("IMPORT", color = Gt.accent, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(52.dp))
                Text("MAPS", color = Gt.accent, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(44.dp))
                Text("FILE", color = Gt.dim, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(files, key = { it.absolutePath }) { file ->
                    val checked = selected.contains(file.name)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - file.name
                                else selected + file.name
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (checked) Gt.text.copy(alpha = 0.18f) else Gt.text.copy(alpha = 0.18f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = if (it) selected + file.name
                                        else selected - file.name
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Gt.accent,
                                        uncheckedColor = Gt.text,
                                        checkmarkColor = Gt.panel
                                    )
                                )
                            }
                            // [2026-07-02] second box: include map-tile download for THIS file on import.
                            // MAPSDEFAULT-2026-09-16: DEFAULT ON (was off) and legible (was
                            // 0xFF445566, which read as texture rather than a control on
                            // a dark panel). Measured: 90 tracks with maps = 6 hours
                            // unattended, three years of riding on the tablet by morning.
                            // That is the feature; a rider who does not want it unticks.
                            Box(modifier = Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                                Checkbox(
                                    checked = !mapsSkip.contains(file.name),
                                    onCheckedChange = {
                                        mapsSkip = if (it) mapsSkip - file.name
                                        else mapsSkip + file.name
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Gt.accent,
                                        uncheckedColor = Gt.accent,
                                        checkmarkColor = Gt.panel
                                    )
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp)
                            ) {
                                Text(
                                    file.name,
                                    color = Gt.text,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        formatFileSize(file.length()),
                                        color = Gt.dim,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        formatFileDate(file.lastModified()),
                                        color = Gt.dim,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        file.extension.uppercase(),
                                        color = Gt.dim,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // -- Bottom bar -- Select All + Import button --
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                color = Gt.panel
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Select All row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (selected.size == files.size) emptySet()
                                else files.map { it.name }.toSet()
                            }
                    ) {
                        Checkbox(
                            checked = selected.size == files.size && files.isNotEmpty(),
                            onCheckedChange = { checked ->
                                selected = if (checked) files.map { it.name }.toSet()
                                else emptySet()
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Gt.accent,
                                uncheckedColor = Gt.text,
                                checkmarkColor = Gt.panel
                            )
                        )
                        Text(
                            "Select All  (${selected.size} of ${files.size})",
                            color = Gt.text,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    // IMPORTTRAILS-2026-10-30 (D31-min): the rider says this batch is a trail map.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { asTrails = !asTrails }
                    ) {
                        Checkbox(
                            checked = asTrails,
                            onCheckedChange = { asTrails = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Gt.accent,
                                uncheckedColor = Gt.text,
                                checkmarkColor = Gt.panel
                            )
                        )
                        Text(
                            "Import tracks as trails  (a trail map: every line becomes a trail)",
                            color = Gt.text,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // Import button
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            // MAPSDEFAULT-2026-09-16: open the source popup if ANY selected file still
                            // wants maps. The previous test asked whether the INCLUDED
                            // set was non-empty -- false by default, so the popup was
                            // unreachable.
                            .clickable(enabled = selected.isNotEmpty()) { if (!asTrails && selected.any { !mapsSkip.contains(it) }) showSourcePopup = true else doImport() },   // IMPORTTRAILS-2026-10-30: trails need no map sources
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected.isNotEmpty()) Gt.text.copy(alpha = 0.18f)
                        else Gt.panel
                    ) {
                        Text(
                            text = if (selected.isEmpty()) "Select files to import"
                            else "Import ${selected.size} Selected",
                            color = if (selected.isNotEmpty()) Gt.good
                            else Gt.dim,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

// -- Import Progress Dialog -------------------------------------------

@Composable
private fun ImportProgressDialog(
    current: Int,
    total: Int,
    currentName: String,
    feedLines: List<String> = emptyList(),
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    AlertDialog(
        onDismissRequest = { /* non-dismissable */ },
        confirmButton = {},
        title = {
            Text(
                "Importing Tracks",
                color = Gt.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(color = Gt.accent)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Processing $current of $total",
                    color = Gt.text,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    currentName,
                    color = Gt.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                if (feedLines.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LaunchedEffect(feedLines.size) {
                        listState.animateScrollToItem(feedLines.size - 1)
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    ) {
                        items(feedLines.size) { idx ->
                            val line = feedLines[idx]
                            val col = when {
                                line.startsWith("INSERT") -> Gt.good
                                line.startsWith("ALIAS") -> Gt.accent
                                line.startsWith("DUPLICATE") -> Gt.dim
                                line.startsWith("SKIP") -> Gt.warn
                                line.startsWith("ERROR") -> Gt.bad
                                line.startsWith("—") -> Gt.good
                                else -> Gt.text
                            }
                            Text(line, color = col, fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp, maxLines = 1,
                                modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                }
            }
        },
        containerColor = Gt.panel,
        shape = RoundedCornerShape(16.dp)
    )
}

// -- Import Recap Dialog ----------------------------------------------

@Composable
private fun ImportRecapDialog(
    imported: List<String>,
    skipped: List<String>,
    failed: List<String>,
    datesCorrected: Int,
    waypoints: Int = 0,
    routes: Int = 0,
    newCount: Int = 0,
    dupeCount: Int = 0,
    aliasedCount: Int = 0,
    skippedTrkCount: Int = 0,
    onFilesDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row {
                if (onFilesDelete != null) {
                    TextButton(onClick = { onFilesDelete(); onDismiss() }) {
                        Text(
                            "DELETE FILES & CLOSE",
                            color = Gt.bad,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        "KEEP FILES & CLOSE",
                        color = Gt.accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        },
        title = {
            Text(
                "Import Complete",
                color = Gt.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        },
        text = {
            Column {
                // Summary counts
                if (imported.isNotEmpty()) {
                    Text(
                        "${imported.size} tracks imported",
                        color = Gt.accent,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                // 4-way breakdown (2026-06-30): new / aliased / duplicate / skipped
                Text(
                    "$newCount new   ·   $aliasedCount aliased",
                    color = Gt.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "$dupeCount duplicate   ·   $skippedTrkCount skipped",
                    color = Gt.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (skipped.isNotEmpty()) {
                    Text(
                        "${skipped.size} skipped (already exist)",
                        color = Gt.warn,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (failed.isNotEmpty()) {
                    Text(
                        "${failed.size} failed",
                        color = Gt.bad,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (datesCorrected > 0) {
                    Text(
                        "Dates corrected: $datesCorrected of ${imported.size}",
                        color = Gt.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (waypoints > 0) {
                    Text(
                        "$waypoints waypoints imported",
                        color = Gt.accent,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (routes > 0) {
                    Text(
                        "$routes routes imported",
                        color = Gt.warn,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Imported track names (show up to 20)
                if (imported.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Imported:",
                        color = Gt.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    val shown = imported.take(20)
                    for (name in shown) {
                        Text(
                            "\u2022 $name",
                            color = Gt.dim,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (imported.size > 20) {
                        Text(
                            "  ...and ${imported.size - 20} more",
                            color = Gt.dim,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Failed details
                if (failed.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Failed:",
                        color = Gt.bad,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    for (msg in failed.take(10)) {
                        Text(
                            "\u2022 $msg",
                            color = Gt.bad,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        containerColor = Gt.panel,
        shape = RoundedCornerShape(16.dp)
    )
}

// -- Helper functions -------------------------------------------------

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatFileDate(millis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
