package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.RadioButton

/**
 * ConvoyMapSourceScreen — assign tile sources to the 3 fixed map slots.
 *
 * Reads from MapSourceManager (map_sources.json).
 * Writes slot changes to external Documents/GroupTrack/map_sources.json.
 * API keys stored in Documents/GroupTrack/api_keys.json.
 *
 * RULES:
 *   - Three fixed slots: SAT, TOPO, TOPO+  (legacy directory names, never change)
 *   - Any source can go in any slot
 *   - Sources requiring API key are LOCKED until key is entered
 *   - Key entry + test tile validation unlocks source
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoyMapSourceScreen(
    onNavigateBack: () -> Unit
) {
    val allSources = remember { MapSourceManager.getAllSources() }
    val context = LocalContext.current
    val slots = remember { MapSourceManager.getSlotAssignments() }
    var selectedSlot by remember { mutableStateOf<String?>(null) }
    var showApiKeyDialog by remember { mutableStateOf<String?>(null) }

    // Refresh state
    var refreshSlot by remember { mutableStateOf<String?>(null) }
    var refreshTileCount by remember { mutableStateOf(0) }
    var refreshEnqueued by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Track current assignments as mutable state
    var satSourceId by remember {
        mutableStateOf(slots.find { it.legacyKey == "SAT" }?.sourceId ?: "")
    }
    var topoSourceId by remember {
        mutableStateOf(slots.find { it.legacyKey == "TOPO" }?.sourceId ?: "")
    }
    var topoPlusSourceId by remember {
        mutableStateOf(slots.find { it.legacyKey == "TOPO+" }?.sourceId ?: "")
    }

    fun currentSourceId(slot: String): String = when (slot) {
        "SAT" -> satSourceId
        "TOPO" -> topoSourceId
        "TOPO+" -> topoPlusSourceId
        else -> ""
    }

    fun applySource(slot: String, sourceId: String) {
        // SOURCEPANEL-2026-08-06: open the migration record BEFORE the slot
        // assignment changes. Afterwards currentSourceId(slot) returns the NEW
        // source and the OUTGOING source's cache dirs exist nowhere in live
        // state - that is how SAT_LABELS_PLACES and SAT_LABELS_TRANSPORT would
        // become orphans nothing knows to remove.
        // If the record cannot be written the source change still proceeds but
        // no clear is offered: an unrecorded GB-scale delete is exactly what
        // the record exists to prevent, so degrading to "no clear" is safe.
        val fromSourceId = currentSourceId(slot)
        if (fromSourceId.isNotEmpty() && fromSourceId != sourceId) {
            val fromDirs = allSources.find { it.id == fromSourceId }?.allCacheDirs ?: emptyList()
            val toDirs = allSources.find { it.id == sourceId }?.allCacheDirs ?: emptyList()
            if (fromDirs.isNotEmpty()) {
                ConvoySourceMigration.begin(slot, fromSourceId, fromDirs, sourceId, toDirs)
            }
        }
        MapSourceManager.updateSlotSource(slot, sourceId)
        when (slot) {
            "SAT" -> satSourceId = sourceId
            "TOPO" -> topoSourceId = sourceId
            "TOPO+" -> topoPlusSourceId = sourceId
        }
        selectedSlot = null
        // Check for existing tiles to offer refresh (async to avoid ANR)
        val checkSlot = slot
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                ConvoyTileDownloader.scanTilesToKeys(checkSlot).size
            }
            if (count > 0) {
                refreshSlot = checkSlot
                refreshTileCount = count
                refreshEnqueued = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map Sources") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            if (selectedSlot == null) {
                // ── SLOT OVERVIEW ──────────────────────────────────────────
                SectionHeader("Current Assignments")
                Text(
                    "Three fixed map slots. Tap to change source.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(8.dp))

                listOf("SAT", "TOPO", "TOPO+").forEach { slotKey ->
                    val sourceId = currentSourceId(slotKey)
                    val source = allSources.find { it.id == sourceId }
                    SlotCard(
                        slotName = slotKey,
                        sourceName = source?.name ?: "Unknown",
                        producer = source?.producer ?: "",
                        mapType = source?.mapType ?: "",
                        onClick = { selectedSlot = slotKey }
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(16.dp))

                // ── API KEYS ──────────────────────────────────────────────
                SectionHeader("API Keys")
                Text(
                    "Sources requiring API keys. Enter key to unlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(8.dp))

                allSources.filter { it.requiresKey }.forEach { source ->
                    val hasKey = MapSourceManager.getApiKey(source.id).isNotEmpty()
                    ApiKeyRow(
                        sourceName = source.name,
                        hasKey = hasKey,
                        onClick = { showApiKeyDialog = source.id }
                    )
                }

                Spacer(Modifier.height(24.dp))

            } else {
                // ── SOURCE SELECTION FOR SLOT ──────────────────────────────
                val slotKey = selectedSlot!!
                val currentId = currentSourceId(slotKey)

                SectionHeader("Select Source for $slotKey")
                TextButton(
                    onClick = { selectedSlot = null },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("< Back to slots") }
                Spacer(Modifier.height(8.dp))

                // Group by map_type
                val grouped = allSources.groupBy { it.mapType }
                val typeOrder = listOf("HYB", "SAT", "TOPO", "STREET", "TERRAIN", "OUTDOOR")
                val typeLabels = mapOf(
                    "HYB" to "HYBRID", "SAT" to "SATELLITE", "TOPO" to "TOPOGRAPHIC",
                    "STREET" to "STREET", "TERRAIN" to "TERRAIN", "OUTDOOR" to "OUTDOOR"
                )

                typeOrder.forEach { type ->
                    val sourcesInType = grouped[type] ?: return@forEach
                    Text(
                        text = typeLabels[type] ?: type,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    sourcesInType.forEach { source ->
                        val isSelected = source.id == currentId
                        val isAvailable = MapSourceManager.isSourceAvailable(source.id)
                        SourceRow(
                            name = source.name,
                            producer = source.producer,
                            requiresKey = source.requiresKey,
                            isAvailable = isAvailable,
                            isSelected = isSelected,
                            onClick = {
                                if (isAvailable) {
                                    applySource(slotKey, source.id)
                                } else {
                                    showApiKeyDialog = source.id
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // SOURCEPANEL-2026-08-06: three phases - intro, panel, result.
    var showPanel by remember { mutableStateOf(false) }
    // MIGSCREEN-2026-08-06: "", "delete", "auto" or "manual".
    var migChoice by remember { mutableStateOf("") }
    var clearResult by remember { mutableStateOf<String?>(null) }
    // MIGOPT2-2026-08-08D: option 2 sub-selections. Independent -- users are
    // one or the other (a corridor-based map has few areas, an area-based map
    // has few tracks), so the panel lets them say which rather than guessing.
    var optTracks by remember { mutableStateOf(false) }
    var optAreas by remember { mutableStateOf(false) }
    // Option 3's optional immediate corridor refresh: Replace ON, NO delete.
    var optManualCorridors by remember { mutableStateOf(false) }
    // Roll-up state. remember, not rememberSaveable: a half-restored migration
    // would re-open mid-sequence with stale counts.
    var migRunning by remember { mutableStateOf(false) }
    var migSteps by remember { mutableStateOf(listOf<String>()) }
    var migProgress by remember { mutableStateOf("") }
    // MIGSURFACE-2026-08-08Q: the growing step list. Each entry is
    // (label, done) -- rendered in place under the options, OSM-import style,
    // rather than in a second dialog.
    var migLog by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }
    var migFraction by remember { mutableFloatStateOf(-1f) }

    // ── Source Change Panel ──────────────────────────────────────
    refreshSlot?.let { slot ->
        if (!refreshEnqueued && !showPanel) {
            // ── PHASE 1: intro ───────────────────────────────────
            // SOURCEPANEL-2026-08-06: no silent exit. "Later" was a dismissal
            // with no owner - it left the column pointing at a new source over
            // a store full of the old one, with nothing recording the mismatch.
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Map Source Changed") },
                text = {
                    Column {
                        Text("$slot is now using the new source.")
                        Spacer(Modifier.height(8.dp))
                        Text("$refreshTileCount stored tiles came from the previous source.")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Stored tiles cannot be converted. The next step explains "
                            + "your options and what each one costs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPanel = true }) { Text("NEXT STEP") }
                }
            )
        } else if (!refreshEnqueued) {
            // ── PHASE 2: THE PANEL ───────────────────────────────
            // One surface for the whole decision. Reload options and
            // replace-in-place are named but not built yet; the panel says so
            // rather than pretending they are absent.
            AlertDialog(
                onDismissRequest = { },
                title = {
                    // MIGSCREEN-2026-08-06: larger and bold.
                    Text(
                        "Source Map Migration - $slot",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        val rec = ConvoySourceMigration.inProgress().firstOrNull { f ->
                            ConvoySourceMigration.read(f)?.optString("slot") == slot
                        }
                        val root = rec?.let { ConvoySourceMigration.read(it) }
                        val mb = (root?.optLong("from_bytes_total", 0L) ?: 0L) / (1024L * 1024L)
                        val dirs = root?.optJSONArray("from_cache_dirs")
                        val nStores = dirs?.length() ?: 0

                        Text(
                            "Google Hybrid carries roads and place names inside the "
                            + "satellite imagery, so it needs one map store instead of "
                            + "three and shows street names even with no signal.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Your stored Esri tiles cannot be converted as they are. "
                            + "Road labels, place names and feature names will no longer "
                            + "show on them. The separate name files those come from are "
                            + "removed either way, whichever option you choose.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))

                        // ---- option rows ----
                        MigrationOption(
                            selected = migChoice == "delete",
                            enabled = true,
                            onSelect = { migChoice = "delete" },
                            heading = "Delete and start over",
                            body = "Clear these maps and rebuild around your tracks. "
                                + "Downloading along your tracks uses about two thirds less "
                                + "storage than boxed areas. Frees $nStores store(s), about "
                                + "$mb MB. This cannot be undone and there is no backup - "
                                + "your download history for this slot is cleared too."
                        )
                        MigrationOption(
                            selected = migChoice == "auto",
                            enabled = true,
                            onSelect = { migChoice = "auto" },
                            heading = "Move everything to the new source",
                            body = "Everything is submitted at once and runs in the "
                                + "background. Your maps stay usable throughout - each "
                                + "image is replaced as its new version arrives. Choose "
                                + "what to move below. Best if you rely on road and place "
                                + "names, and you can leave it running."
                        )
                        // MIGOPT2-2026-08-08D: sub-choices, shown only when the
                        // option is selected. Indented to read as belonging to it.
                        if (migChoice == "auto") {
                            Column(modifier = Modifier.padding(start = 40.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = optTracks,
                                        onCheckedChange = { optTracks = it }
                                    )
                                    Text(
                                        "Refresh track corridors",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    // ⚠ The honest trade. Not selecting this does NOT
                                    // lose the tracks -- the area refresh covers that
                                    // ground as RECTANGLES, which loads far more tiles
                                    // for the same trails. Duration is a working
                                    // estimate (Fred 08-08: under an hour typically,
                                    // two at the extreme), so it is stated as a range
                                    // and never as a promise.
                                    // MIGSURFACE-2026-08-08Q: the figure is measured,
                                    // not estimated. 08-08: 30 tracks swept up as
                                    // areas cost 2M+ tiles on one source; 89 tracks
                                    // as proper corridors cost 1.5M across three.
                                    // Corridors were designed for ~90% fewer tiles
                                    // and that holds.
                                    "Your track maps are removed and rebuilt along your "
                                    + "tracks. Leave this off and the same ground is "
                                    + "covered as boxed areas instead, which downloads "
                                    + "roughly ten times as many tiles for it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = optAreas,
                                        onCheckedChange = { optAreas = it }
                                    )
                                    Text(
                                        "Refresh areas",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    "Your boxed map areas are replaced from the new source. "
                                    + "Time depends on how much area you hold.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                                )
                                if (!optTracks && !optAreas) {
                                    Text(
                                        "Choose at least one.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        }
                        MigrationOption(
                            selected = migChoice == "manual",
                            enabled = true,
                            onSelect = { migChoice = "manual" },
                            heading = "I'll move tracks and areas on my schedule",
                            body = "Nothing is downloaded or removed. Your existing maps stay "
                                + "as they are, so you always have something to fall back on. "
                                + "Refresh ground as you need it from the downloads panel, "
                                + "with Replace tiles ticked. Ground you have not refreshed "
                                + "stays without road and place names."
                        )
                        // MIGOPT2-2026-08-08D: the path most heavy-track users take
                        // (Fred 08-08) -- leave the maps in place, but ADD corridor
                        // maps for the tracks from the new source now. Replace ON,
                        // NO delete: nothing is being torn down, these tiles are
                        // added. Areas stay on the original source until refreshed.
                        if (migChoice == "manual") {
                            Column(modifier = Modifier.padding(start = 40.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = optManualCorridors,
                                        onCheckedChange = { optManualCorridors = it }
                                    )
                                    Text(
                                        "Add track maps now",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    "Downloads map coverage along your tracks from the new "
                                    + "source, so your trails have road and place names "
                                    + "straight away. Nothing is removed. Your areas are "
                                    + "left alone until you refresh them yourself.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    // MIGSCREEN-2026-08-06: ONE action. Disabled until a row is
                    // selected - nobody makes a delete decision without having seen
                    // the alternatives.
                    TextButton(
                        // MIGOPT2-2026-08-08D: option 2 additionally needs at least
                        // one box ticked -- "move everything" that moves nothing is
                        // not a state worth allowing.
                        enabled = migChoice.isNotEmpty() &&
                            (migChoice != "auto" || optTracks || optAreas),
                        onClick = {
                            val record = ConvoySourceMigration.inProgress().firstOrNull { f ->
                                ConvoySourceMigration.read(f)?.optString("slot") == slot
                            }
                            if (record == null) {
                                clearResult = "No migration record for $slot, so nothing was "
                                    .plus("changed. Tiles are only removed when the removal ")
                                    .plus("can be recorded.")
                                refreshEnqueued = true
                            } else if (migChoice == "auto") {
                                // MIGSURFACE-2026-08-08Q: runs ON SCREEN, in place.
                                migRunning = true
                                migLog = listOf()
                                scope.launch {
                                    try {
                                        ConvoySourceMigration.noteReloadChoice(record, "auto")
                                        // ── STEP 0: what did the handler ACTUALLY get? ──
                                        // On 08-08 the delete silently did not run because
                                        // optTracks was false here while the box appeared
                                        // ticked. Recording it makes a repeat one line on
                                        // screen instead of an evening of inference.
                                        android.util.Log.i("MIGSURFACE",
                                            "MIGSURFACE-2026-08-08Q start: tracks=$optTracks "
                                            + "areas=$optAreas slot=$slot")
                                        migLog = migLog + Pair(
                                            "Selected: " +
                                                (if (optTracks) "track maps" else "") +
                                                (if (optTracks && optAreas) " + " else "") +
                                                (if (optAreas) "areas" else ""),
                                            true
                                        )

                                        // ── Quiet the queue. Deleting from a store a
                                        // running job is writing to is the one ordering
                                        // that cannot be allowed. ──
                                        migProgress = "Holding queue"
                                        withContext(Dispatchers.IO) {
                                            DownloadQueueManager.holdQueue()
                                            DownloadQueueManager.cancelAll()
                                        }
                                        migLog = migLog + Pair(
                                            "Queue held, pending work cleared", true)

                                        if (optTracks) {
                                            migLog = migLog + Pair("Scanning track maps", false)
                                            migProgress = "Reading tracks - this can take a minute"
                                            val preview = withContext(Dispatchers.IO) {
                                                ConvoyCorridorDelete.previewAllTracks(context, "SAT")
                                            }
                                            migLog = migLog.dropLast(1) + Pair(
                                                "Scanned ${preview.tracks.size} tracks - " +
                                                "${preview.onDiskTotal} tiles", true)

                                            // ⛔ THE DELETE. Not optional when corridors
                                            // are selected: leaving these tiles in place
                                            // is what makes the area pass re-cover the
                                            // same ground as boxes, at ~10x the tiles.
                                            migLog = migLog + Pair("Removing track maps", false)
                                            val del = withContext(Dispatchers.IO) {
                                                ConvoyCorridorDelete.deleteAllTrackCorridors(
                                                    context, "SAT"
                                                ) { done, total, name ->
                                                    // ⛔ fires on IO -- hop to main.
                                                    scope.launch(Dispatchers.Main) {
                                                        migProgress =
                                                            "Removing corridor map $done of $total - $name"
                                                        migFraction =
                                                            if (total > 0) done.toFloat() / total
                                                            else -1f
                                                    }
                                                }
                                            }
                                            migProgress = ""
                                            migFraction = -1f
                                            migLog = migLog.dropLast(1) + Pair(
                                                "Removed ${del.tilesRemoved} tiles from " +
                                                "${del.tracksProcessed} track maps", true)

                                            migProgress = "Submitting track maps"
                                            val hashes = withContext(Dispatchers.IO) {
                                                SpatialDbManager.allTrackGeomHashes()
                                                    .map { h -> h.first }
                                            }
                                            val batch = withContext(Dispatchers.IO) {
                                                DownloadQueueManager.enqueueCorridorBatch(
                                                    context, hashes, listOf("SAT"), true
                                                )
                                            }
                                            migLog = migLog + Pair(
                                                "Submitted ${batch.jobs} track maps " +
                                                "(${batch.tiles} tiles)", true)
                                        }

                                        if (optAreas) {
                                            // What is LEFT after the corridor delete. On a
                                            // corridor-only map that is nothing, and zero
                                            // is a legitimate answer -- submit nothing and
                                            // say so, rather than queueing jobs against
                                            // ground that is not there (08-08: 172 of them).
                                            migProgress = "Checking remaining map areas"
                                            val cells = withContext(Dispatchers.IO) {
                                                DownloadQueueManager.enqueueRefresh(
                                                    context, "SAT", "SAT")
                                            }
                                            migLog = migLog + Pair(
                                                if (cells > 0)
                                                    "Submitted map areas ($cells jobs)"
                                                else
                                                    "No map areas to process",
                                                true
                                            )
                                        }

                                        migProgress = ""
                                        withContext(Dispatchers.IO) {
                                            DownloadQueueManager.resumeQueue()
                                        }
                                        migLog = migLog + Pair("Queue released", true)
                                        ConvoySourceMigration.complete(record)
                                        clearResult = "Everything is submitted. Downloads run " +
                                            "in the background and can be watched in the " +
                                            "download queue. They resume by themselves if the " +
                                            "app or device restarts."
                                    } catch (e: Exception) {
                                        // ⚠ 08-08: a throw here left the panel up with no
                                        // way out. Release the queue and report, always.
                                        android.util.Log.e("MIGSURFACE",
                                            "MIGSURFACE-2026-08-08Q failed: ${e.message}", e)
                                        withContext(Dispatchers.IO) {
                                            DownloadQueueManager.resumeQueue()
                                        }
                                        migLog = migLog + Pair("Stopped: ${e.message}", true)
                                        clearResult = "The move stopped partway: ${e.message}. " +
                                            "The queue has been released. Anything already " +
                                            "submitted is still in the download queue."
                                    } finally {
                                        // Terminal state cannot be skipped by a throw.
                                        migProgress = ""
                                        migFraction = -1f
                                        migRunning = false
                                        refreshEnqueued = true
                                    }
                                }
                            } else if (migChoice == "auto_unreachable") {
                                // MIGOPT2-2026-08-08D: runs ON SCREEN, not detached.
                                // The delete is an inline loop with no queue entry --
                                // killing the process mid-run strands the user with
                                // removed coverage and nothing scheduled to rebuild it.
                                migRunning = true
                                migSteps = listOf()
                                scope.launch {
                                    ConvoySourceMigration.noteReloadChoice(record, "auto")
                                    // Step 1: quiet the queue. Deleting from a store a
                                    // running job is writing to is the one ordering
                                    // that cannot be allowed.
                                    migProgress = "Holding queue"
                                    withContext(Dispatchers.IO) {
                                        DownloadQueueManager.holdQueue()
                                        DownloadQueueManager.cancelAll()
                                    }
                                    migSteps = migSteps +
                                        "Queue held, pending work cleared (history kept)"

                                    if (optTracks) {
                                        // Step 2: scan. Read-only -- nothing is
                                        // destroyed until the delete below.
                                        migProgress = "Scanning tracks - this can take a minute"
                                        val preview = withContext(Dispatchers.IO) {
                                            ConvoyCorridorDelete.previewAllTracks(context, "SAT")
                                        }
                                        migSteps = migSteps +
                                            "Scanned ${preview.tracks.size} tracks - " +
                                            "${preview.onDiskTotal} tiles"

                                        // Step 3: THE DELETE. The long one.
                                        val del = withContext(Dispatchers.IO) {
                                            ConvoyCorridorDelete.deleteAllTrackCorridors(
                                                context, "SAT"
                                            ) { done, total, name ->
                                                // ⛔ fires on IO -- Compose state must
                                                // be written on main.
                                                scope.launch(Dispatchers.Main) {
                                                    migProgress =
                                                        "Removing corridor map $done of $total - $name"
                                                    migFraction =
                                                        if (total > 0) done.toFloat() / total
                                                        else -1f
                                                }
                                            }
                                        }
                                        migProgress = ""
                                        migFraction = -1f
                                        migSteps = migSteps +
                                            "Removed ${del.tilesRemoved} tiles from " +
                                            "${del.tracksProcessed} track maps"

                                        // Step 4: requeue. Seconds -- the queue does
                                        // the downloading afterwards.
                                        migProgress = "Submitting track maps"
                                        val hashes = withContext(Dispatchers.IO) {
                                            SpatialDbManager.allTrackGeomHashes().map { h -> h.first }
                                        }
                                        val batch = withContext(Dispatchers.IO) {
                                            DownloadQueueManager.enqueueCorridorBatch(
                                                context, hashes, listOf("SAT"), true
                                            )
                                        }
                                        migSteps = migSteps +
                                            "Submitted ${batch.jobs} track maps (${batch.tiles} tiles)"
                                    }

                                    if (optAreas) {
                                        migProgress = "Submitting areas"
                                        val cells = withContext(Dispatchers.IO) {
                                            DownloadQueueManager.enqueueRefresh(context, "SAT", "SAT")
                                        }
                                        migSteps = migSteps + "Submitted area refresh ($cells cells)"
                                    }

                                    migProgress = ""
                                    withContext(Dispatchers.IO) {
                                        DownloadQueueManager.resumeQueue()
                                    }
                                    migSteps = migSteps + "Queue released"
                                    ConvoySourceMigration.complete(record)
                                    migRunning = false
                                    clearResult = "Everything is submitted. Downloads are " +
                                        "running in the background and can be monitored in " +
                                        "the download queue. They resume by themselves if " +
                                        "the app or device restarts."
                                    refreshEnqueued = true
                                }
                            } else if (migChoice == "delete") {
                                ConvoySourceClear.clearColumnDetached(record) { r ->
                                    clearResult = when (r) {
                                        is ConvoySourceClear.Result.Success ->
                                            "Cleared ${r.deletedDirs.size} store(s), about "
                                                .plus("${r.bytesFreed / (1024L * 1024L)} MB freed. ")
                                                .plus("Draw areas or import tracks when ready.")
                                        is ConvoySourceClear.Result.Failed ->
                                            "Clear failed: ${r.reason}"
                                    }
                                    refreshEnqueued = true
                                }
                            } else {
                                ConvoySourceClear.removeOrphanedStoresDetached(record) { r ->
                                    ConvoySourceMigration.noteReloadChoice(record, migChoice)
                                    ConvoySourceMigration.complete(record)
                                    // MIGOPT2-2026-08-08D: option 3's optional
                                    // immediate corridor refresh. Replace ON, no
                                    // delete -- corridors are ADDED from the new
                                    // source. Enqueue only, so it is seconds; the
                                    // queue downloads afterwards.
                                    if (optManualCorridors) {
                                        scope.launch {
                                            val added = withContext(Dispatchers.IO) {
                                                val hs = SpatialDbManager.allTrackGeomHashes()
                                                    .map { h -> h.first }
                                                DownloadQueueManager.enqueueCorridorBatch(
                                                    context, hs, listOf("SAT"), true
                                                )
                                            }
                                            clearResult = (clearResult ?: "") +
                                                " Submitted ${added.jobs} track maps " +
                                                "(${added.tiles} tiles) - monitor them in " +
                                                "the download queue."
                                        }
                                    }
                                    clearResult = when (r) {
                                        is ConvoySourceClear.Result.Success ->
                                            if (r.deletedDirs.isEmpty())
                                                "Your maps are unchanged."
                                            else
                                                "Your maps are unchanged. Removed "
                                                    .plus("${r.deletedDirs.size} unused label ")
                                                    .plus("store(s), about ")
                                                    .plus("${r.bytesFreed / (1024L * 1024L)} MB freed.")
                                        is ConvoySourceClear.Result.Failed ->
                                            "Your maps are unchanged. Label stores not "
                                                .plus("removed: ${r.reason}")
                                    }
                                    refreshEnqueued = true
                                }
                            }
                        }
                    ) { Text("CONTINUE") }
                }
            )
        } else if (migRunning) {
            // MIGSURFACE-2026-08-08Q: see the step list built below. This branch
            // is retained so the panel has a surface while work runs.
            MigrationSteps(
                steps = migLog,
                progress = migProgress,
                fraction = migFraction
            )
        } else if (false) {
            // ── MIGOPT2-2026-08-08D: PHASE 2b - the roll-up ──────
            // No dismiss, no buttons. The delete cannot be interrupted safely,
            // and the enqueues take seconds. The user watches or waits.
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Moving your maps") },
                text = {
                    Column {
                        migSteps.forEach { s ->
                            Text("\u2713  " + s, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(3.dp))
                        }
                        if (migProgress.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                migProgress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (migFraction >= 0f) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { migFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Please leave this on screen until it finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = { }
            )
        } else {
            // Confirmation that refresh was queued
            AlertDialog(
                onDismissRequest = { refreshSlot = null },
                title = { Text("Done") },
                text = {
                    // SOURCEPANEL-2026-08-06: reports the actual outcome.
                    Text(clearResult ?: "No changes were made.")
                },
                confirmButton = {
                    // SOURCEPANELFIX-2026-08-06: reset showPanel too. It is declared
                    // below applySource, so applySource cannot clear it - without this
                    // the NEXT source change with tiles present skips the intro and
                    // opens straight on the panel.
                    TextButton(onClick = {
                        showPanel = false
                        clearResult = null
                        refreshSlot = null
                    }) { Text("OK") }
                }
            )
        }
    }

    // ── API Key Dialog ────────────────────────────────────────────
    showApiKeyDialog?.let { sourceId ->
        val source = allSources.find { it.id == sourceId }
        ApiKeyDialog(
            sourceName = source?.name ?: sourceId,
            currentKey = MapSourceManager.getApiKey(sourceId),
            registrationUrl = source?.attribution ?: "",
            onSave = { key ->
                MapSourceManager.saveApiKey(sourceId, key)
                showApiKeyDialog = null
            },
            onDismiss = { showApiKeyDialog = null }
        )
    }
}

/**
 * MIGSURFACE-2026-08-08Q: the growing step list, modelled on the OSM import
 * panel's ExtractProgressList. Steps accumulate in place and everything below
 * slides down -- one surface, not a chain of dialogs.
 */
@Composable
private fun MigrationSteps(
    steps: List<Pair<String, Boolean>>,
    progress: String,
    fraction: Float
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Moving your maps") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                steps.forEach { (label, done) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (done) "\u2611" else "\u2610",
                            fontSize = 15.sp,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (progress.isNotBlank()) {
                    Text(
                        progress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp)
                    )
                }
                if (fraction >= 0f) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Please leave this on screen until it finishes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { }
    )
}

// ── Composables ───────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SlotCard(
    slotName: String,
    sourceName: String,
    producer: String,
    mapType: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Slot badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = when (slotName) {
                    "SAT" -> Color(0xFF1565C0)
                    "TOPO" -> Color(0xFF2E7D32)
                    else -> Color(0xFFE65100)
                },
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = slotName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(sourceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "$producer | $mapType",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Change",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun SourceRow(
    name: String,
    producer: String,
    requiresKey: Boolean,
    isAvailable: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (isSelected) 4.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Radio indicator
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(producer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (requiresKey && !isAvailable) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "API Key Required",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            } else if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ApiKeyRow(
    sourceName: String,
    hasKey: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (hasKey) Icons.Default.Check else Icons.Default.Lock,
                contentDescription = null,
                tint = if (hasKey) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(sourceName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (hasKey) "Key configured" else "Key required — tap to enter",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasKey) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(
    sourceName: String,
    currentKey: String,
    registrationUrl: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyText by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API Key: $sourceName") },
        text = {
            Column {
                Text(
                    "Enter your API key. Get one from the provider's website.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (keyText.isNotBlank()) onSave(keyText.trim()) },
                enabled = keyText.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


// MIGSCREEN-2026-08-06: one selectable option row for the migration panel.
// Heading is deliberately larger and bold so the three choices read as choices
// rather than as more paragraphs. A disabled row stays readable - the user can
// see what they are choosing against even when it cannot be picked yet.
@Composable
private fun MigrationOption(
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    heading: String,
    body: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = if (enabled) onSelect else null
        )
        Column(modifier = Modifier.padding(start = 4.dp, top = 10.dp)) {
            Text(
                heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
