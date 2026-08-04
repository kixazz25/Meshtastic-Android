package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OsmImportPanel -- the container the four components drop into.
 *
 * Four rows, one green. Row state is DERIVED FROM DISK every refresh; nothing
 * here stores a stage. The ledger is written by the components and read only
 * for display -- it never decides what runs next.
 *
 * THE ONE HARD RULE: EXTRACT cannot launch unless a zip has been identified as
 * existing. Tapping it earlier is harmless and simply reports that nothing was
 * found.
 *
 * C2 / C3 / C4 are not built. Their actions call stubs so the panel and its
 * derivation can be exercised by moving files around before any component
 * exists -- push a dummy zip and EXTRACT goes green; delete it and DOWNLOAD
 * goes green again.
 */

private const val PANEL_TAG = "OsmPanel"

private const val GEOFABRIK_URL =
    "https://download.geofabrik.de/north-america/us.html"

private val GREEN = Color(0xFF3FB950)
private val DIM = Color(0xFF8B949E)

private data class RowSpec(
    val number: Int,
    val title: String,
    val detail: String,
    val done: Boolean,
    val actionLabel: String?,
    val enabled: Boolean,
    val isNext: Boolean
)

/**
 * OSM-C3B-SCOPE-2026-07-29: which of the two values on row 3's launch line is
 * ticked. UI state only.
 *
 * ⚠ NOT the domain type. `ImportScope` (sealed, OsmImportStage.kt:28) is the
 * domain type and stays untouched. This exists because the sealed type
 * deliberately CANNOT represent the state row 3 needs: AREA chosen but not yet
 * drawn -- `ImportScope.Area` requires coordinates, and at selection time there
 * are none. Resolution to a bbox happens at action time, not here.
 *
 * CODE RULE 1 -- the null is NOT a shortcut. The choice is genuinely absent
 * until the user makes it, and "absent" is exactly what disables IMPORT. A
 * default would mean importing a state the user never chose.
 *
 * `ledgerValue` matches OsmImportWorker:101, which defaults pendingScope to
 * "state".
 */
private enum class Row3Choice(val ledgerValue: String, val label: String) {
    WHOLE_STATE("state", "FULL STATE"),
    AREA("area", "SELECTED AREA")
}

@Composable
fun OsmImportPanel(
    onNavigateBack: () -> Unit = {}
) {
    // OSM-GENERIC-2026-07-28: the state is UNKNOWN until a file is adopted, so it is
    // DERIVED from disk like everything else here -- never passed in. Wording
    // stays generic until there is something real to name.
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var slug by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf(OsmStage.ACQUIRE) }
    var permissionOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    // OSM-C3B-SCOPE-2026-07-29: row 3's launch-line choice. Null until chosen,
    // and null is what keeps IMPORT disabled.
    var importScope by remember { mutableStateOf<Row3Choice?>(null) }
    // OSM-C3B-GATE-2026-07-29: the recap shown after the pending record is
    // written on the SELECTED AREA path. CANCEL-only.
    var gateRecap by remember { mutableStateOf<String?>(null) }
    // OSM-C3C-PROGRESS-2026-07-29: import progress.
    //
    // ⭐ VISIBILITY IS DERIVED FROM WORKMANAGER, NOT STORED. importRunning is
    // only a nudge to start observing; the observer below sets it from the
    // worker's real state, so navigating away and back re-attaches instead of
    // showing an empty panel. A stored flag would be the routeMode failure.
    var importRunning by remember { mutableStateOf(false) }
    var importPhase by remember { mutableStateOf("") }
    var importCounts by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var importFinal by remember { mutableStateOf<String?>(null) }
    // OSM-C4-2026-07-29: derived from imports[] on every refresh.
    var importsDone by remember { mutableStateOf(false) }
    // OSM-C3C-LATCH-2026-07-29: the "at" of the last import already shown.
    // imports[] is PERMANENT, so "have I shown this?" cannot be answered by a
    // nullable dialog string -- dismissing it is what makes it null. The
    // record's own timestamp is the only stable answer.
    var lastShownImportAt by remember { mutableStateOf<String?>(null) }
    var cleanupRecap by remember { mutableStateOf<String?>(null) }

    // OSM-C3C-PROGRESS-2026-07-29: follow the import worker.
    //
    // Keyed on slug + refreshTick so ARRIVING at the panel while an import is
    // already running re-attaches to it. 89,536 rows takes minutes; the user
    // will leave and come back, and an empty panel would look like failure.
    LaunchedEffect(slug, refreshTick) {
        val s = slug ?: return@LaunchedEffect
        val wm = androidx.work.WorkManager.getInstance(ctx)
        while (true) {
            val infos = withContext(Dispatchers.IO) {
                try {
                    wm.getWorkInfosForUniqueWork(OsmImportWorker.uniqueName(s)).get()
                } catch (e: Exception) {
                    null
                }
            }
            val wi = infos?.firstOrNull()
            if (wi == null) {
                importRunning = false
            } else if (wi.state == androidx.work.WorkInfo.State.RUNNING ||
                wi.state == androidx.work.WorkInfo.State.ENQUEUED
            ) {
                importRunning = true
                // OSM-C3C-PROGRESS-2026-07-29: the worker publishes ONE
                // STRING under "osm_extract_progress", the shape C2 already
                // established:
                //   [{"id":"trails","label":"...","done":N,"total":N,
                //     "complete":true,"eta_sec":-1}, ...]
                // Parsed here rather than through OsmExtractProgress so a
                // function-name guess cannot cost a build. A format change
                // degrades the label; it cannot crash.
                val raw = wi.progress.getString("osm_extract_progress")
                if (raw.isNullOrBlank()) {
                    importPhase = "Importing"
                    importCounts = emptyList()
                } else {
                    try {
                        val arr = org.json.JSONArray(raw)
                        val items = ArrayList<Pair<String, Int>>()
                        var current = "Importing"
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val lbl = o.optString("label", o.optString("id", "?"))
                            val done = o.optInt("done", 0)
                            val total = o.optInt("total", 0)
                            val complete = o.optBoolean("complete", false)
                            items.add(Pair(lbl + (if (total > 0) " / $total" else ""), done))
                            // The item being serviced = first not complete.
                            if (!complete && current == "Importing") current = lbl
                        }
                        importPhase = current
                        importCounts = items
                    } catch (e: Exception) {
                        Log.w(PANEL_TAG, "progress unparseable: ${e.javaClass.simpleName}")
                        importPhase = "Importing"
                    }
                }
            } else {
                // ⚠ WorkManager DISCARDS progress Data on a terminal state, so
                // the final counters are NOT readable from getProgress(). They
                // come from imports[] in the ledger, which appendImport writes
                // before the worker returns. Live from progress, final from disk.
                importRunning = false
                if (wi.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                    val last = withContext(Dispatchers.IO) {
                        OsmImportLedger.read(ctx, s)
                            ?.optJSONArray("imports")
                            ?.let { arr ->
                                if (arr.length() == 0) null
                                else arr.optJSONObject(arr.length() - 1)
                            }
                    }
                    // OSM-C3C-LATCH-2026-07-29: once per RECORD, keyed on its
                    // own "at". imports[] is permanent, so a nullable dialog
                    // string cannot answer "have I shown this" -- dismissing it
                    // is what makes it null, and the next refresh raises it
                    // again. A new import brings a new "at" and shows once.
                    val at = last?.optString("at", "") ?: ""
                    // OSM-C4-ARM-2026-07-30: arm row 4 the moment the import
                    // lands, not when the recap is dismissed. Fred: "button
                    // goes green after imports."
                    //
                    // STILL DERIVED, NOT A FLAG: the observer has just read
                    // imports[] off the ledger, so a non-null record IS the
                    // derivation -- the same disk source the stage effect uses,
                    // read at a different moment. That effect is keyed on
                    // refreshTick, and nothing bumps the tick when a worker
                    // finishes, so it never re-ran and the row stayed grey.
                    //
                    // OUTSIDE THE LATCH ON PURPOSE: lastShownImportAt answers
                    // "have I shown this recap?", which is not the same question
                    // as "has an import landed?". Inside the latch, row 4 would
                    // fail to arm on any poll after the dialog had been shown.
                    if (last != null) importsDone = true
                    if (last != null && at.isNotEmpty() && at != lastShownImportAt) {
                        lastShownImportAt = at
                        importFinal = last.toString(2)
                        Log.i(PANEL_TAG, "import complete ($at)")
                    }
                } else if (wi.state == androidx.work.WorkInfo.State.FAILED) {
                    val err = wi.outputData.getString(OsmImportWorker.KEY_ERROR)
                    Log.w(PANEL_TAG, "import FAILED: $err")
                    if (importFinal == null) importFinal = "IMPORT FAILED\n\n" + (err ?: "no detail")
                }
                break
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // OSM-C3C-PROGRESS-2026-07-29: the overlay. Fred 07-29: "display the
    // import item being serviced with the current state recap."
    if (importRunning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("IMPORTING") },
            text = {
                Column {
                    Text(importPhase, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    if (importCounts.isEmpty()) {
                        Text("starting\u2026", fontSize = 12.sp)
                    } else {
                        importCounts.forEach { (k, v) ->
                            Text("$k: $v", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Runs in the background \u2014 leaving this screen is safe.",
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = { }
        )
    }

    cleanupRecap?.let { body ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { cleanupRecap = null },
            title = { Text("CLEANUP") },
            text = {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    Text(body, fontSize = 11.sp)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // OSM-C4-CLOSE-2026-07-29: acknowledging cleanup ends the
                    // four-step process, so the panel closes itself.
                    //
                    // \u2b50 Also correct rather than merely convenient: cleanup
                    // removed osm/<slug>/, so statesInFlight() is now empty and
                    // the panel would derive back to row 1 ACQUIRE. Closing
                    // matches what it would show.
                    //
                    // \u26a0 CLEANUP's OK only. The import recap keeps dismissing
                    // to the panel, because row 4 still has to be reachable.
                    cleanupRecap = null
                    onNavigateBack()
                }) { Text("OK") }
            }
        )
    }

    importFinal?.let { body ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { importFinal = null },
            title = { Text("IMPORT COMPLETE") },
            text = {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    Text(body, fontSize = 11.sp)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // No refreshTick++ -- the latch above decides whether this
                    // ever shows again, and re-deriving here only restarted the
                    // observer that raised it.
                    importFinal = null
                }) { Text("OK") }
            }
        )
    }

    // OSM-C3B-GATE-2026-07-29: CANCEL-only. Dismiss does NOT clear the pending
    // record -- it stays on disk so the panel can be reopened to confirm the
    // rule re-derives off the same values, and so the JSON can be read.
    gateRecap?.let { body ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { gateRecap = null },
            title = { Text("IMPORT \u2014 GATED (C3 not wired)") },
            text = { Text(body, fontSize = 12.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { gateRecap = null }) {
                    Text("CANCEL")
                }
            }
        )
    }

    // OSM-C2-WIRING-2026-07-28: C2 runs in a worker, so its progress is not this
    // panel's state -- it is READ from WorkManager. handledExtractId exists to
    // stop the terminal state being acted on more than once; see the polling
    // effect below.
    var extracting by remember { mutableStateOf(false) }
    var extractItems by remember {
        mutableStateOf<List<OsmExtractProgress.Item>>(emptyList())
    }
    var handledExtractId by remember { mutableStateOf<String?>(null) }

    // R5: derivation runs at MOMENTS. This is one of them.
    LaunchedEffect(refreshTick) {
        permissionOk = OsmImportStage.hasAllFilesAccess()
        val found = withContext(Dispatchers.IO) { OsmImportStage.statesInFlight(ctx) }
        slug = found.firstOrNull()
        stage = if (slug == null) {
            OsmStage.ACQUIRE
        } else {
            withContext(Dispatchers.IO) { OsmImportStage.stageOf(ctx, slug!!) }
        }
        Log.i(PANEL_TAG, "derived: slug=$slug stage=$stage permission=$permissionOk inFlight=${found.size}")

        // OSM-C4-2026-07-29: row 4 arms once an import has landed. Fred:
        // "it has to be selected to run after imports. button goes green after
        // imports." DERIVED from the ledger (R1), never a flag -- so it
        // survives navigation and reflects what actually happened.
        importsDone = if (slug == null) false else withContext(Dispatchers.IO) {
            (OsmImportLedger.read(ctx, slug!!)?.optJSONArray("imports")?.length() ?: 0) > 0
        }

        // OSM-C3B-LAUNCH-2026-07-29: THE PANEL'S RULE, ONE LINE.
        //
        // Fred 07-29: "code will always be proceed to update if json has bbox
        // values or launch area downloads if it does not... button selection
        // will be bypassed if we have bbox values."
        //
        // ⚠ THIS ALSO CLOSES A REAL BUG: importScope is remember{}, so
        // reopening after a draw resets it to null. Without this, re-ticking
        // SELECTED AREA would call setPendingImport(bbox = null) and OVERWRITE
        // the bbox the map just filled in -- looping forever, silently.
        //
        // R1: derived from disk at a MOMENT (R5). No flag crosses the gap.
        val s0 = slug
        if (s0 != null) {
            val ready = withContext(Dispatchers.IO) {
                OsmImportLedger.pendingBbox(ctx, s0)
            }
            if (ready != null) {
                val sc0 = withContext(Dispatchers.IO) {
                    OsmImportLedger.pendingScope(ctx, s0)
                } ?: "state"
                val n0 = withContext(Dispatchers.IO) {
                    OsmImportStage.countTrailsInBbox(
                        ctx, s0, ready[0], ready[1], ready[2], ready[3]
                    )
                }
                Log.i(PANEL_TAG, "pending bbox present -- LAUNCH (scope=$sc0 " +
                    "S${ready[0]} W${ready[1]} N${ready[2]} E${ready[3]} n=$n0)")
                // OSM-C3C-WIRE-2026-07-29: this was the gate. It is now the
                // launch, which is what building it gated bought us -- a swap,
                // not a rewrite.
                //
                // ⚠ NO UNDO EXISTS IN-APP. Removing a bad import is a laptop
                // operation (DELETE ... WHERE source_id='osm'), so the count
                // goes in the dialog and the user confirms it.
                // OSM-C3C-LOOP-2026-07-29: ASK WORKMANAGER BEFORE PROMPTING.
                //
                // pending_import DELIBERATELY survives the run -- that is what
                // makes a failed or cancelled import reprocess unattended, and
                // appendImport clears it only on success. So the bbox is still
                // there while the worker runs, and without this check the
                // derive effect raises the dialog again on every refresh.
                //
                // \u2b50 The overlay derives visibility from the same source, so
                // the dialog and the overlay cannot disagree about whether an
                // import is in flight.
                val inFlight = withContext(Dispatchers.IO) {
                    try {
                        androidx.work.WorkManager.getInstance(ctx)
                            .getWorkInfosForUniqueWork(OsmImportWorker.uniqueName(s0))
                            .get()
                            .any { !it.state.isFinished }
                    } catch (e: Exception) {
                        Log.w(PANEL_TAG, "work state unreadable: ${e.javaClass.simpleName}")
                        false
                    }
                }
                if (inFlight) {
                    Log.i(PANEL_TAG, "import already in flight for $s0 -- no prompt")
                    importRunning = true
                } else {
                    val label = if (sc0 == "state") "the whole state" else "this area"
                    confirm = ("Import $n0 " + OsmImportStage.displayName(s0) +
                        " trails ($label) into your map database?\n\n" +
                        "S ${ready[0]}   N ${ready[2]}\n" +
                        "W ${ready[1]}   E ${ready[3]}\n\n" +
                        "Places and natural features import whole-state " +
                        "regardless of area. There is no undo.") to {
                        Log.i(PANEL_TAG, "enqueue import $s0 scope=$sc0 n=$n0")
                        OsmImportWorker.enqueue(ctx, s0)
                        // No refreshTick++ -- the observer picks the worker up
                        // on its own poll. Bumping the tick forced an immediate
                        // re-derive, which is what made the loop tight.
                        importRunning = true
                    }
                }
            }
        }
    }

    /**
     * OSM-C2-WIRING-2026-07-28: follow the extract worker.
     *
     * Keyed on slug + refreshTick so that ARRIVING at the panel while an
     * extract is already running re-attaches to it. C2 runs for minutes; the
     * user will leave and come back, and finding an empty panel would look
     * like the work had been lost.
     *
     * Polled rather than observed as LiveData -- observeAsState needs a Compose
     * artifact I could not confirm is on this project's classpath, and a 2s
     * poll needs nothing new. The worker forces a publish at every type start
     * and completion, so transitions show up at once despite the 30s tick.
     *
     * ⚠ handledExtractId is what stops the loop: bumping refreshTick on
     * SUCCEEDED restarts this effect, which would see SUCCEEDED again and bump
     * again, forever.
     */
    LaunchedEffect(slug, refreshTick) {
        val s = slug
        if (s == null) {
            extracting = false
            extractItems = emptyList()
            return@LaunchedEffect
        }
        val wm = WorkManager.getInstance(ctx)
        val name = OsmExtractWorker.uniqueName(s)
        while (true) {
            val infos = withContext(Dispatchers.IO) {
                try {
                    wm.getWorkInfosForUniqueWork(name).get()
                } catch (e: Exception) {
                    Log.w(PANEL_TAG, "work query failed: ${e.javaClass.simpleName}")
                    null
                }
            }
            val info = infos?.firstOrNull()
            if (info == null) {
                extracting = false
                break
            }
            val json = info.progress.getString(OsmExtractProgress.KEY)
            if (json != null) extractItems = OsmExtractProgress.fromJson(json)

            if (info.state.isFinished) {
                extracting = false
                val id = info.id.toString()
                if (handledExtractId != id) {
                    handledExtractId = id
                    if (info.state == WorkInfo.State.FAILED) {
                        message = info.outputData.getString(OsmExtractWorker.KEY_ERROR)
                            ?: "Extract failed. See log."
                        extractItems = emptyList()
                    } else if (info.state == WorkInfo.State.SUCCEEDED) {
                        // OSM-C2-PROGRESS-COUNTERS-2026-07-28: WorkManager DISCARDS progress Data
                        // on a terminal state -- getProgress() is empty once the
                        // worker finishes. The worker's final forced publish is
                        // real but unreadable, which is why the LAST layer never
                        // ticked while earlier ones did: a later pass published
                        // while still RUNNING and carried their completed state
                        // with it. Nothing follows trails.
                        //
                        // Do not wait for data that will never arrive: a
                        // succeeded extract means every layer completed.
                        extractItems = extractItems.map {
                            it.copy(complete = true, done = if (it.total > 0) it.total else it.done)
                        }
                    }
                    Log.i(PANEL_TAG, "extract finished: ${info.state}")
                    refreshTick++
                }
                break
            }
            extracting = true
            delay(2_000)
        }
    }

    val zipExists = stage == OsmStage.REDUCE || stage == OsmStage.IMPORT
    val skinnyExists = stage == OsmStage.IMPORT

    val rows = listOf(
        RowSpec(
            number = 1,
            title = "DOWNLOAD",
            detail = if (zipExists && slug != null) {
                "${OsmImportStage.displayName(slug!!)} extract in place"
            } else {
                "Pick a state at Geofabrik and download its .gpkg.zip"
            },
            done = zipExists,
            actionLabel = if (zipExists) null else "OPEN GEOFABRIK",
            enabled = !busy,
            isNext = !zipExists
        ),
        RowSpec(
            number = 2,
            title = "EXTRACT",
            detail = when {
                skinnyExists -> "Trail database ready"
                zipExists -> "Unzip and select trails"
                else -> "Waiting for a downloaded file"
            },
            done = skinnyExists,
            actionLabel = if (skinnyExists) null else "EXTRACT",
            // THE ONE HARD RULE
            enabled = !busy && zipExists && !extracting,
            isNext = zipExists && !skinnyExists
        ),
        RowSpec(
            number = 3,
            title = "IMPORT TO SPATIAL",
            detail = if (!skinnyExists) "Waiting"
                else when (importScope) {
                    null -> "Choose a scope below"
                    Row3Choice.WHOLE_STATE -> "Whole state"
                    Row3Choice.AREA -> "Draw an area on the planning map"
                },
            done = false,
            actionLabel = "IMPORT",
            // OSM-C3B-SCOPE-2026-07-29: one of the two values must be selected
            // before IMPORT does anything.
            enabled = !busy && skinnyExists && importScope != null,
            isNext = skinnyExists
        ),
        RowSpec(
            number = 4,
            title = "CLEANUP",
            detail = if (!skinnyExists) "Waiting"
                else if (importsDone) "Archive the record and remove working files"
                else "Waiting for an import",
            done = false,
            actionLabel = "CLEAN UP",
            // OSM-C4-2026-07-29: arms only after an import has landed.
            enabled = !busy && skinnyExists && importsDone,
            isNext = importsDone
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = if (slug == null) "IMPORT OSM TRAIL DATA"
            else "OSM TRAIL DATA - ${OsmImportStage.displayName(slug!!).uppercase()}",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Data (c) OpenStreetMap contributors, ODbL",
            fontSize = 11.sp,
            color = DIM
        )
        Spacer(Modifier.height(12.dp))

        if (!permissionOk) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "GroupTrack cannot see your downloads",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "All files access is off. Settings > Apps > Special app " +
                            "access > All files access > GroupTrack. " +
                            "Clearing app storage switches this off silently.",
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        rows.forEach { row ->
            StageRow(
                row = row,
                onAction = {
                    when (row.number) {
                        1 -> openGeofabrik(ctx)
                        2 -> confirm = "Unzip the extract and build the trail " +
                            "database? This takes several minutes and needs " +
                            "free space for the unzipped file." to {
                            val s = slug
                            if (s == null) {
                                message = "No state in flight. Tap REFRESH."
                            } else {
                                extractItems = emptyList()
                                handledExtractId = null
                                extracting = true
                                OsmExtractWorker.enqueue(ctx, s)
                                refreshTick++
                            }
                        }
                        // OSM-C3B-WIRING-2026-07-29: writes the pending_import
                        // record, then STOPS at a recap. Nothing enqueues.
                        3 -> {
                            val s = slug
                            val sc = importScope
                            if (s == null || sc == null) {
                                message = "No state in flight, or no scope chosen."
                            } else {
                                scope.launch {
                                    busy = true; busyLabel = "Resolving scope"
                                    val recap = withContext(Dispatchers.IO) {
                                        val bbox = if (sc == Row3Choice.WHOLE_STATE) {
                                            OsmImportStage.trailExtent(ctx, s)
                                        } else null
                                        OsmImportLedger.setPendingImport(
                                            ctx, s, sc.ledgerValue, bbox
                                        )
                                        // Read BACK from disk. The gate must show
                                        // what actually landed, not what we meant
                                        // to write -- that is the whole point.
                                        val wrote = OsmImportLedger.pendingBbox(ctx, s)
                                        val wroteScope =
                                            OsmImportLedger.pendingScope(ctx, s) ?: "(none)"
                                        buildString {
                                            append("PENDING RECORD WRITTEN\n\n")
                                            append("Scope:  ").append(sc.label)
                                            append("   (ledger: ").append(wroteScope).append(")\n")
                                            append("State:  ")
                                            append(OsmImportStage.displayName(s)).append("\n\n")
                                            if (wrote == null) {
                                                append("  bbox: (awaiting area draw)\n\n")
                                                append("Row 3 closes and the planning map\n")
                                                append("fills this in. Map handoff NOT wired.\n")
                                            } else {
                                                append("  S  ").append(wrote[0]).append("\n")
                                                append("  W  ").append(wrote[1]).append("\n")
                                                append("  N  ").append(wrote[2]).append("\n")
                                                append("  E  ").append(wrote[3]).append("\n\n")
                                                val n = OsmImportStage.countTrailsInBbox(
                                                    ctx, s, wrote[0], wrote[1], wrote[2], wrote[3]
                                                )
                                                append("Trails overlapping: ").append(n).append("\n")
                                                if (sc == Row3Choice.WHOLE_STATE) {
                                                    append("(must equal the full row count)\n")
                                                }
                                                append("\n")
                                            }
                                            append("Nothing imported. C3 is not wired.")
                                        }
                                    }
                                    busy = false; busyLabel = ""
                                    Log.i(PANEL_TAG, "pending written: $recap")
                                    // OSM-UNGATE-2026-07-29: ONE LAUNCH POINT.
                                    //
                                    // AREA closes the panel and hands off to
                                    // the map, which fills the bbox and reopens
                                    // this panel. FULL STATE already has its
                                    // bbox, so it just bumps the tick -- the
                                    // derive effect finds the bbox and raises
                                    // the confirm dialog. Both scopes arrive at
                                    // the SAME launch, and neither stops at a
                                    // recap.
                                    if (sc == Row3Choice.AREA) {
                                        onNavigateBack()
                                    } else {
                                        refreshTick++
                                    }
                                }
                            }
                        }
                        // OSM-C4-2026-07-29: no screen -- a process that runs.
                        4 -> confirm = ("Archive the import record and remove the " +
                            "working files?\n\nThe download and extract are deleted. " +
                            "Re-importing this state means downloading it again.") to {
                            val s = slug
                            if (s == null) {
                                message = "No state in flight."
                            } else {
                                scope.launch {
                                    busy = true; busyLabel = "Cleaning up"
                                    val recap = withContext(Dispatchers.IO) {
                                        // 1. Size what is about to go.
                                        val dir = OsmImportStage.dirFor(ctx, s)
                                        val files = dir.listFiles()
                                            ?.filter { !it.isDirectory }
                                            ?.map { Pair(it.name, it.length()) }
                                            ?: emptyList()
                                        val bytes = files.sumOf { it.second }
                                        // 2. ARCHIVE BY RENAME -- BEFORE the sweep.
                                        //    discardState deletes ledger.json too, so
                                        //    afterwards there is nothing to rename.
                                        //    history/ sits OUTSIDE osm/<slug>/, so the
                                        //    sweep cannot reach it.
                                        val archived = OsmImportLedger.archiveLedger(ctx, s)
                                        // 3. SWEEP -- but ONLY if the record is safe.
                                        //    \u26a0 Losing the run record to keep a cleanup
                                        //    on schedule is the wrong trade. The files
                                        //    can always be removed on the next pass.
                                        //    \u2b50 OPTIONAL-RETAIN SEAM: to keep the ZIP
                                        //    (a re-import becomes a 40-second re-extract
                                        //    instead of a 3-minute download), filter it
                                        //    out here instead of calling discardState.
                                        val swept = if (archived != null) {
                                            OsmImportStage.discardState(ctx, s)
                                        } else {
                                            Log.e(PANEL_TAG, "archive FAILED -- sweep skipped")
                                            false
                                        }
                                        buildString {
                                            if (archived == null) {
                                                append("CLEANUP DID NOT RUN\n\n")
                                                append("The import record could not be ")
                                                append("archived, so nothing was removed.\n")
                                                append("Your files are untouched.")
                                            } else {
                                                append("CLEANUP COMPLETE\n\n")
                                                append("Import record kept as:\n  ")
                                                append(archived.name).append("\n\n")
                                                append("Removed ").append(files.size)
                                                append(" file(s), ")
                                                append(bytes / 1048576L).append(" MB:\n")
                                                files.forEach { (n2, b) ->
                                                    append("  ").append(n2).append("  ")
                                                    append(b / 1048576L).append(" MB\n")
                                                }
                                                if (!swept) {
                                                    append("\n\u26a0 Some files could not be ")
                                                    append("removed \u2014 see the log.")
                                                }
                                            }
                                        }
                                    }
                                    busy = false; busyLabel = ""
                                    cleanupRecap = recap
                                    refreshTick++
                                }
                            }
                        }
                    }
                }
            )

            // OSM-C3B-SCOPE-2026-07-29: row 3's launch line. Rendered here
            // rather than inside StageRow so StageRow stays untouched -- it is
            // shared by all four rows and only row 3 has a choice to offer.
            if (row.number == 3 && skinnyExists && !busy) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                ) {
                    Row3Choice.values().forEach { opt ->
                        Text(
                            text = (if (importScope == opt) "\u2611 " else "\u2610 ") + opt.label,
                            fontSize = 12.sp,
                            // SCOPEVIS-2026-08-04: this was the ONLY Text in the panel with no
                            // explicit colour. It inherited LocalContentColor, and IN DARK
                            // MODE that resolved to the dark surface - so the options
                            // rendered INVISIBLY while still occupying space and taking
                            // taps. Confirmed on device: turning dark mode off made them
                            // appear. Tapping the blank line selected a scope, which is
                            // how this was found.
                            // The box glyph is part of this same string (U+2610/U+2611
                            // prepended to the label), so box and label vanish together.
                            // ⭐ RED IS INTENTIONAL, NOT DIAGNOSTIC. The scope is a
                            // REQUIRED selection that row 3 IMPORT is gated on, and it is
                            // easy to miss even when visible. A hardcoded colour also
                            // cannot be overridden by any theme. DO NOT replace this with
                            // a theme colour.
                            // ⚠ STILL OWED: 1,316 Text call sites under convoy/ share
                            // this exposure. Fix at the panel/theme root, not per Text.
                            color = androidx.compose.ui.graphics.Color.Red,
                            modifier = Modifier
                                .clickable {
                                    importScope = if (importScope == opt) null else opt
                                }
                                .padding(end = 18.dp, top = 2.dp, bottom = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // OSM-C2-WIRING-2026-07-28: per-type progress belongs UNDER the row that
            // started it, not in the panel's shared busy area at the bottom --
            // the bottom spinner is for short foreground actions, and C2 is
            // neither short nor foreground.
            if (row.number == 2 && extractItems.isNotEmpty()) {
                ExtractProgressList(extractItems)
                Spacer(Modifier.height(8.dp))
            }

            // OSM-GENERIC-2026-07-28: the check gate sits BETWEEN step 1 and
            // step 2, where the disjointed hand-off actually happens -- the
            // user leaves for the browser after step 1 and returns here.
            // Tapping it IS the poll (R5); there is no file watcher.
            if (row.number == 1 && !zipExists) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            busyLabel = "Looking for a downloaded file"
                            val probe = withContext(Dispatchers.IO) {
                                OsmImportStage.probeDownloads(ctx)
                            }
                            when (probe) {
                                is OsmImportStage.AcquireProbe.NotFound -> {
                                    busy = false
                                    message = "No Geofabrik extract found in your " +
                                        "downloads. If the download is still running, " +
                                        "try again when it finishes."
                                }
                                is OsmImportStage.AcquireProbe.Several -> {
                                    busy = false
                                    message = "Found ${probe.all.size} matching files. " +
                                        "Delete the ones you don't want and try again."
                                }
                                is OsmImportStage.AcquireProbe.BadFile -> {
                                    busy = false
                                    message = "Found ${probe.candidate.displayName}, but " +
                                        "it is incomplete or not a Geofabrik extract."
                                }
                                is OsmImportStage.AcquireProbe.Found -> {
                                    busyLabel = "Copying ${probe.candidate.displayName}"
                                    val c = probe.candidate
                                    val ok = withContext(Dispatchers.IO) {
                                        OsmImportStage.sweepDebris(ctx, c.slug)
                                        OsmImportLedger.create(
                                            ctx, c.slug,
                                            OsmImportStage.displayName(c.slug),
                                            "Geofabrik ${c.displayName} (user download)",
                                            OsmImportLedger.priorImports(ctx, c.slug)
                                        )
                                        OsmImportStage.adoptCandidate(ctx, c) { copied, total ->
                                            if (total > 0) progress = copied.toFloat() / total
                                        }
                                    }
                                    if (ok) {
                                        withContext(Dispatchers.IO) {
                                            OsmImportLedger.recordAcquire(
                                                ctx, c.slug, c.displayName, c.bytes, "MediaStore"
                                            )
                                        }
                                        message = "Copied ${c.displayName}."
                                    } else {
                                        message = "Could not copy ${c.displayName}. See log."
                                    }
                                    busy = false
                                    progress = 0f
                                    refreshTick++
                                }
                            }
                        }
                    },
                    enabled = !busy && permissionOk,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("CLICK HERE WHEN DOWNLOAD HAS COMPLETED", fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { refreshTick++ },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("REFRESH")
        }

        // OSM-CANCEL-BUTTON-2026-07-28: the way out.
        //
        // At the BOTTOM, not beside EXTRACT: a destructive control next to the
        // primary action is a misfire waiting to happen, and moving it away
        // removes that risk rather than mitigating it.
        //
        // Shown only when there is something to cancel, so it does not sit
        // there inviting a tap on an empty panel.
        if (slug != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val s = slug!!
                    val name = OsmImportStage.displayName(s)
                    // The confirm names the CONSEQUENCE, not just the action.
                    // "Delete" alone reads as tidying up; the download has to
                    // be repeated, and that belongs before the tap rather than
                    // after it.
                    confirm = (if (extracting) {
                        "Stop extracting $name and delete the download? " +
                            "You will have to download it again."
                    } else {
                        "Delete the downloaded $name extract and start over? " +
                            "You will have to download it again."
                    }) to {
                        scope.launch {
                            busy = true
                            busyLabel = "Cancelling"
                            // Order matters: stop the worker BEFORE removing
                            // files, or it writes into a directory that is
                            // being deleted underneath it. The worker checks
                            // isStopped in both its unzip and row loops and
                            // unwinds through its finally block, so the delay
                            // is to let that finish rather than to hope.
                            withContext(Dispatchers.IO) {
                                WorkManager.getInstance(ctx)
                                    .cancelUniqueWork(OsmExtractWorker.uniqueName(s))
                            }
                            delay(1_000)
                            val ok = withContext(Dispatchers.IO) {
                                OsmImportStage.discardState(ctx, s)
                            }
                            extracting = false
                            extractItems = emptyList()
                            handledExtractId = null
                            busy = false
                            if (!ok) message = "Some files could not be removed. See log."
                            refreshTick++
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (extracting) "CANCEL EXTRACT" else "CANCEL AND DELETE DOWNLOAD",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (busy) {
            Spacer(Modifier.height(12.dp))
            Text(busyLabel, fontSize = 12.sp, color = DIM)
            Spacer(Modifier.height(4.dp))
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("OSM import") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("OK") }
            }
        )
    }

    confirm?.let { (text, action) ->
        // OSM-CONFIRMTRACE-2026-07-30: DIAGNOSTIC. Fred saw CONTINUE need two
        // presses on two different panels (Arizona run, 07-30).
        //
        // The dialog itself is correct -- it clears `confirm` then runs
        // action() -- so the first press works and dismisses. Something
        // re-raises a dialog that looks identical.
        //
        // Keyed on `text`: the let-block exits on dismiss and re-enters on a
        // re-raise, so a duplicate prints a second SHOWN with the same text.
        //   two SHOWN, one CONTINUE -> RE-RAISED. Suspect the derive effect at
        //       :453 -- its in-flight guard covers the IMPORT worker only, and
        //       row 2 is the EXTRACT worker.
        //   one SHOWN, two CONTINUE -> not dismissing; look at recomposition.
        androidx.compose.runtime.LaunchedEffect(text) {
            Log.i(PANEL_TAG, "CONFIRM SHOWN: " + text.take(70).replace("\n", " "))
        }
        AlertDialog(
            onDismissRequest = {
                Log.i(PANEL_TAG, "CONFIRM dismissed (tap outside)")
                confirm = null
            },
            title = { Text("Confirm") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    Log.i(PANEL_TAG, "CONFIRM -> CONTINUE pressed")
                    confirm = null
                    action()
                    Log.i(PANEL_TAG, "CONFIRM -> action() returned")
                }) { Text("CONTINUE") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.i(PANEL_TAG, "CONFIRM -> CANCEL pressed")
                    confirm = null
                }) { Text("CANCEL") }
            }
        )
    }
}

/**
 * OSM-C2-WIRING-2026-07-28: one row per extracted type, each with its OWN
 * denominator.
 *
 * SHAPE SET BY FRED 2026-07-28: "a simple counter per type, what % of that
 * type has processed, check box when type is completed", and "time is
 * preferred just relative to each type being transferred."
 *
 * So there is deliberately NO blended bar and NO whole-job estimate. Unzip
 * moves bytes and the passes move rows; per-row cost differs by orders of
 * magnitude between a 1,581-row places pass and a 134,242-row trails pass. A
 * single bar would sit still through the unzip, sprint through places, then
 * crawl -- and a whole-run average would be dominated by whichever type
 * happened to run first.
 *
 * The rows come from the worker, which builds them from the catalog, so
 * enabling "pois" in osm_layers.json makes a POIs row appear here with no edit
 * to this file.
 *
 * ⚠ These use checkbox glyphs at Fred's request. StageRow deliberately does
 * not -- see its comment about a checkbox reading as an INPUT on the panel
 * next door. Both styles now live in one screen; swap the glyphs below if the
 * consistency matters more.
 */
@Composable
private fun ExtractProgressList(items: List<OsmExtractProgress.Item>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            items.forEachIndexed { idx, item ->
                if (idx > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.complete) "\u2611" else "\u2610",
                        color = if (item.complete) GREEN else DIM,
                        fontSize = 15.sp,
                        modifier = Modifier.width(24.dp)
                    )
                    Text(
                        text = item.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${item.percent}%",
                        fontSize = 13.sp,
                        color = if (item.complete) GREEN else DIM
                    )
                }
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { item.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp)
                )
                val counts = if (item.total > 0L) {
                    "${grouped(item.done)} / ${grouped(item.total)}"
                } else {
                    grouped(item.done)
                }
                val eta = OsmExtractProgress.etaText(item.etaSec)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.complete || eta.isEmpty()) counts else "$counts   $eta",
                    fontSize = 11.sp,
                    color = DIM,
                    modifier = Modifier.padding(start = 24.dp)
                )
            }
        }
    }
}

/** 134242 -> "134,242". Long counts are unreadable without separators. */
private fun grouped(v: Long): String =
    String.format(java.util.Locale.US, "%,d", v)

@Composable
private fun StageRow(row: RowSpec, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status glyph, NOT a checkbox. On the download panel next door a
            // checkbox is an INPUT; here it would be an OUTPUT. Same control,
            // opposite meaning, adjacent screens -- so this is deliberately not
            // a checkbox and has no touch target.
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (row.done) "\u2713" else "${row.number}",
                    color = if (row.done) GREEN else DIM,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (row.isNext) GREEN
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(text = row.detail, fontSize = 12.sp, color = DIM)
            }

            row.actionLabel?.let { label ->
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onAction,
                    enabled = row.enabled,
                    colors = if (row.isNext) {
                        ButtonDefaults.buttonColors(containerColor = GREEN)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(label, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun openGeofabrik(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(GEOFABRIK_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Log.e(PANEL_TAG, "could not open browser: ${e.javaClass.simpleName} ${e.message}")
    }
}

/**
 * Placeholder for C2 / C3 / C4. Logs, waits, clears. Replaced one at a time as
 * each component lands.
 */
private fun runStub(
    scope: kotlinx.coroutines.CoroutineScope,
    what: String,
    setBusy: (Boolean) -> Unit
) {
    scope.launch {
        Log.i(PANEL_TAG, "STUB: $what would run here")
        setBusy(true)
        kotlinx.coroutines.delay(600)
        setBusy(false)
    }
}
