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
    var clearResult by remember { mutableStateOf<String?>(null) }

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
                title = { Text("Set up $slot") },
                text = {
                    Column {
                        val rec = ConvoySourceMigration.inProgress().firstOrNull { f ->
                            ConvoySourceMigration.read(f)?.optString("slot") == slot
                        }
                        val root = rec?.let { ConvoySourceMigration.read(it) }
                        val mb = (root?.optLong("from_bytes_total", 0L) ?: 0L) / (1024L * 1024L)
                        val dirs = root?.optJSONArray("from_cache_dirs")
                        val nStores = dirs?.length() ?: 0

                        Text(
                            "Your stored tiles came from the previous source and cannot "
                            + "be converted.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        // SOURCEPANELFIX-2026-08-06: advisory leads, mechanism follows.
                        Text(
                            "Please be advised: no matter which option you select, you "
                            + "will lose Esri place and road label information.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The new source does not fetch label overlays, so those "
                            + "stores are removed either way and their space is "
                            + "reclaimed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "We recommend CLEAR when moving from Esri to Google Hybrid, "
                            + "unless you are already running corridor-based tiles.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "If you are not sure, choose KEEP. Your tiles stay exactly as "
                            + "they are and you can refresh on demand later with Download "
                            + "Tiles by Area or by Track.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("CLEAR removes $nStores store(s), about $mb MB.")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This cannot be undone. There is no backup. Your download "
                            + "history for this column is cleared too, so note any areas "
                            + "you want to redraw.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Nothing downloads now either way. Reload options and "
                            + "replace-in-place arrive in the next build; until then use "
                            + "Download Tiles by Area or by Track when you are ready.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Column {
                        TextButton(onClick = {
                            val record = ConvoySourceMigration.inProgress().firstOrNull { f ->
                                ConvoySourceMigration.read(f)?.optString("slot") == slot
                            }
                            if (record == null) {
                                clearResult = "No migration record for $slot, so nothing was "
                                    .plus("changed. Tiles are only removed when the removal ")
                                    .plus("can be recorded.")
                                refreshEnqueued = true
                            } else {
                                // SOURCEPANELFIX-2026-08-06: detached. This ran on
                                // rememberCoroutineScope(), which is cancelled when the
                                // screen leaves composition - navigating away mid-delete
                                // could kill a GB-scale operation partway through.
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
                            }
                        }) { Text("CLEAR TILES") }

                        TextButton(onClick = {
                            val record = ConvoySourceMigration.inProgress().firstOrNull { f ->
                                ConvoySourceMigration.read(f)?.optString("slot") == slot
                            }
                            if (record == null) {
                                clearResult = "Tiles kept. No migration record was open."
                                refreshEnqueued = true
                            } else {
                                // SOURCEPANELFIX-2026-08-06: detached, same reason.
                                // Orphaned label stores are reclaimed here too -
                                // they are dead the moment the source changes.
                                ConvoySourceClear.removeOrphanedStoresDetached(record) { r ->
                                    ConvoySourceMigration.noteReloadChoice(record, "kept_existing_tiles")
                                    ConvoySourceMigration.complete(record)
                                    clearResult = when (r) {
                                        is ConvoySourceClear.Result.Success ->
                                            if (r.deletedDirs.isEmpty())
                                                "Tiles kept. Nothing was removed."
                                            else
                                                "Tiles kept. Removed ${r.deletedDirs.size} unused "
                                                    .plus("label store(s), about ")
                                                    .plus("${r.bytesFreed / (1024L * 1024L)} MB freed.")
                                        is ConvoySourceClear.Result.Failed ->
                                            "Tiles kept. Label stores not removed: ${r.reason}"
                                    }
                                    refreshEnqueued = true
                                }
                            }
                        }) { Text("KEEP TILES") }
                    }
                }
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
