package com.geeksville.mesh.convoy

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HomeStatePickerScreen — the home state setup flow.
 *
 * Three panels in sequence:
 *   1. GPS-detected state → "Your Home state is Utah" → LOAD / Select different
 *   2. Full state list (if user taps "Select different")
 *   3. Progress + completion (after LOAD)
 *
 * Wired to "IMPORT OSM DATA" on the Map Features panel for testing.
 * Production: launched by the authority gate when trails.count == 0.
 */

private const val TAG = "HomeStatePicker"
private val mono = FontFamily.Monospace
private val bg = Color(0xFF0F1216)
private val cardBg = Color(0xFF1A2233)
private val border = Color(0xFF2A3444)
private val green = Color(0xFF7BB661)
private val blue = Color(0xFF4DA6FF)
private val txtDim = Color(0xFF8899AA)
private val txtLight = Color(0xFFE6EDF3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeStatePickerScreen(
    onNavigateBack: () -> Unit = {},
    // AREAWIRE-2026-08-21C: CODE RULE 1 justification -- this is a MODE DISCRIMINATOR,
    // not a shortcut. ABSENT = state mode (geocode, offer, rider picks).
    // PRESENT = area mode (bbox already drawn; detection is meaningless and the
    // screen enters at its running phase). Order is S, W, N, E.
    areaBbox: DoubleArray? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── State ────────────────────────────────────────────────────
    var phase by remember { mutableStateOf("detecting") } // detecting | confirm | list | running | done
    var allStates by remember { mutableStateOf(emptyList<StatePickerItem>()) }
    var detectedState by remember { mutableStateOf<GeofabrikState?>(null) }
    var selectedItem by remember { mutableStateOf<StatePickerItem?>(null) }
    var searchText by remember { mutableStateOf("") }
    val progress by HomeStateImportController.progress.collectAsState()

    // ── Detect on launch ─────────────────────────────────────────
    LaunchedEffect(Unit) {
        // AREAWIRE-2026-08-21C: AREA MODE -- skip detection entirely. The states are
        // resolved from the DRAWN bbox; their own Geofabrik bboxes are only the
        // reference used to select them, never the area imported.
        if (areaBbox != null) {
            val all = withContext(Dispatchers.IO) { GeofabrikCatalog.load(context) }
            val hits = GeofabrikCatalog.findByBbox(
                all, areaBbox[0], areaBbox[1], areaBbox[2], areaBbox[3]
            )
            Log.i(TAG, "AREA import: bbox S=${areaBbox[0]} W=${areaBbox[1]} " +
                "N=${areaBbox[2]} E=${areaBbox[3]} -> ${hits.size} state(s): " +
                hits.joinToString(", ") { it.slug })
            phase = "running"
            // An empty state list is NOT an error -- catalog sources may still
            // intersect the box. The manifest records exactly what was resolved.
            HomeStateImportController.executeArea(
                context, areaBbox[0], areaBbox[1], areaBbox[2], areaBbox[3], hits
            )
            phase = "done"
            return@LaunchedEffect
        }
        val states = withContext(Dispatchers.IO) { GeofabrikCatalog.load(context) }
        allStates = GeofabrikCatalog.displayList(states)
        val detected = withContext(Dispatchers.IO) { GeofabrikCatalog.detectHomeState(context) }
        detectedState = detected
        if (detected != null) {
            selectedItem = allStates.firstOrNull { it.entries.any { e -> e.slug == detected.slug } }
            phase = "confirm"
        } else {
            phase = "list"
        }
        Log.i(TAG, "Detected: ${detected?.name ?: "none"}, ${allStates.size} states loaded")
    }

    // ── Launch import ────────────────────────────────────────────
    fun startImport(item: StatePickerItem) {
        phase = "running"
        scope.launch {
            // For California, process both sub-regions sequentially
            for (entry in item.entries) {
                HomeStateImportController.execute(context, entry)
            }
            phase = "done"
        }
    }

    // ── UI ───────────────────────────────────────────────────────
    Column(
        modifier = Modifier.fillMaxSize().background(bg)
    ) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cardBg,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (phase == "running") "Importing Trail Data"
                    else if (phase == "done") "Trail Data Loaded"
                    else "Welcome to GroupTrack",
                    color = green, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when (phase) {
                        "detecting" -> "Detecting your location..."
                        "confirm" -> "Let's load trail data so your first map has everything"
                        "list" -> "Pick your Home state to load trail data"
                        "running" -> "Processing sources for ${selectedItem?.displayName ?: ""}..."
                        "done" -> "Your maps are ready"
                        else -> ""
                    },
                    color = txtDim, fontSize = 12.sp
                )
            }
        }

        when (phase) {
            "detecting" -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = green)
                }
            }

            "confirm" -> ConfirmPanel(
                state = detectedState!!,
                onLoad = { startImport(selectedItem!!) },
                onSelectDifferent = { phase = "list" }
            )

            "list" -> StateListPanel(
                states = allStates,
                searchText = searchText,
                onSearchChange = { searchText = it },
                selectedItem = selectedItem,
                onSelect = { selectedItem = it },
                onLoad = { selectedItem?.let { startImport(it) } },
                onBack = if (detectedState != null) {{ phase = "confirm" }} else null
            )

            "running" -> ProgressPanel(progress = progress)

            "done" -> CompletionPanel(
                progress = progress,
                onDone = onNavigateBack
            )
        }
    }
}

// ── Panel 1: GPS Confirmed ───────────────────────────────────────

@Composable
private fun ConfirmPanel(
    state: GeofabrikState,
    onLoad: () -> Unit,
    onSelectDifferent: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("\uD83D\uDCCD", fontSize = 48.sp) // 📍
        Spacer(Modifier.height(8.dp))
        Text("Based on your location, your Home state is",
            color = txtDim, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text(state.name, color = green, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        state.description?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = txtDim, fontSize = 11.sp)
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onLoad,
                colors = ButtonDefaults.buttonColors(containerColor = green),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(52.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOAD ${state.name.uppercase()} TRAIL DATA",
                        color = Color(0xFF0D1117), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Downloads all available sources",
                        color = Color(0xFF0D1117).copy(alpha = 0.7f), fontSize = 10.sp)
                }
            }

            OutlinedButton(
                onClick = onSelectDifferent,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(52.dp)
            ) {
                Text("Select a different\nHome state",
                    color = blue, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Internet required · Est. 7 minutes per source",
            color = txtDim, fontSize = 10.sp)
    }
}

// ── Panel 2: State List ──────────────────────────────────────────

@Composable
private fun StateListPanel(
    states: List<StatePickerItem>,
    searchText: String,
    onSearchChange: (String) -> Unit,
    selectedItem: StatePickerItem?,
    onSelect: (StatePickerItem) -> Unit,
    onLoad: () -> Unit,
    onBack: (() -> Unit)?
) {
    val filtered = if (searchText.isBlank()) states
        else states.filter { it.displayName.contains(searchText, ignoreCase = true) }

    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Left: search + list
        Column(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchChange,
                placeholder = { Text("Search states...", color = txtDim) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = blue,
                    unfocusedBorderColor = border,
                    focusedTextColor = txtLight,
                    unfocusedTextColor = txtLight,
                    cursorColor = blue
                )
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered) { item ->
                    val isSelected = item.displayName == selectedItem?.displayName
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(item) },
                        color = if (isSelected) Color(0xFF1A3A2A) else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.displayName, color = txtLight, fontSize = 13.sp)
                            Text("${item.entries.size} region${if (item.entries.size > 1) "s" else ""}",
                                color = txtDim, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Right: preview + buttons
        Column(
            modifier = Modifier.width(260.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Preview
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = cardBg,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (selectedItem != null) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(selectedItem.displayName, color = green,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        selectedItem.description?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = txtDim, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("${selectedItem.entries.size} region${if (selectedItem.entries.size > 1) "s" else ""} to process",
                            color = blue, fontSize = 12.sp)
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a state", color = txtDim, fontSize = 13.sp)
                    }
                }
            }

            // Load button
            Button(
                onClick = onLoad,
                enabled = selectedItem != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = green,
                    disabledContainerColor = Color(0xFF334433)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("LOAD TRAIL DATA", fontWeight = FontWeight.Bold,
                    color = if (selectedItem != null) Color(0xFF0D1117) else Color(0xFF556655))
            }

            // Back to detected
            if (onBack != null) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("← Back to detected state", color = txtDim, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Panel 3: Progress ────────────────────────────────────────────

@Composable
private fun ProgressPanel(progress: ImportProgress?) {
    if (progress == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = green)
        }
        return
    }

    val dlLive by HomeStateImportController.downloadDetailFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Remember when we started for the running clock
        val startTimeMs = remember { System.currentTimeMillis() }
        var clockTick by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                clockTick = System.currentTimeMillis()
            }
        }

        // Blinking "Do not close" banner — TOP of panel, unmissable
        val blink = (clockTick / 800) % 2 == 0L
        Surface(
            color = if (blink) Color(0xFF1A3A1A) else Color(0xFF2A1A1A),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text(
                "Do not close GroupTrack while importing",
                color = if (blink) green else Color(0xFFFF8844),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }

        // Source count + time estimate
        Text("Processing ${progress.totalSources} sources for ${progress.stateName}",
            color = blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Est. ~${progress.estimatedMinutesRemaining} minutes remaining",
            color = txtDim, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(progress.sources.size) { i ->
                val src = progress.sources[i]
                val isCurrent = src.status == "in_progress"
                val isDone = src.status == "completed"
                val isFailed = src.status == "failed"

                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    // Source header
                    Text(
                        "Source ${i + 1} of ${progress.totalSources} — ${src.name}",
                        color = if (isCurrent) blue
                            else if (isDone) green
                            else if (isFailed) Color(0xFFCC4444)
                            else txtDim,
                        fontSize = 13.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    )

                    // Steps (simplified — real steps from the controller's currentStep)
                    if (isDone) {
                        Text("  ✅ Complete — ${src.imported} records",
                            color = green, fontSize = 11.sp)
                    } else if (isCurrent) {
                        val liveDetail = dlLive ?: src.stepDetail
                        Text("  ► ${src.currentStep ?: "Processing"}${
                            liveDetail?.let { " — $it" } ?: ""
                        }", color = blue, fontSize = 11.sp)
                    } else if (isFailed) {
                        Text("  ✗ Failed", color = Color(0xFFCC4444), fontSize = 11.sp)
                    } else {
                        Text("  ○ Waiting", color = txtDim, fontSize = 11.sp)
                    }
                }
            }
        }

        // Elapsed time (calculated from clock, not progress snapshot)
        val elSec = (System.currentTimeMillis() - startTimeMs) / 1000
        Text("Elapsed: ${elSec / 60}m ${elSec % 60}s",
            color = txtDim, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center)
    }
}

// ── Panel 4: Completion ──────────────────────────────────────────

@Composable
private fun RecapLine(label: String, value: Int) {
    // MANIFESTUI-2026-08-21: -1 is NOT zero. A counter the source never reported
    // must say so rather than imply a clean run.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = txtLight, fontSize = 11.sp)
        Text(
            if (value < 0) "not reported" else "$value",
            color = if (value < 0) Color(0xFF888888) else txtLight,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun CompletionPanel(progress: ImportProgress?, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("✅", fontSize = 48.sp)
        Spacer(Modifier.height(8.dp))
        Text("${progress?.stateName ?: ""} — Import Complete",
            color = green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // Results table
        Surface(color = cardBg, shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(16.dp).widthIn(min = 300.dp)) {
                progress?.sources?.forEach { src ->
                    // MANIFESTUI-2026-08-21: the summary line is unchanged -- records
                    // added, one number. Detail is behind a twisty so the recap
                    // does not overwhelm the rider.
                    var showDetail by remember(src.id) { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { showDetail = !showDetail }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                (if (showDetail) "\u25BE  " else "\u25B8  ") + src.name,
                                color = txtLight, fontSize = 13.sp
                            )
                            Text(
                                if (src.status == "completed") "${src.imported}" else "failed",
                                color = if (src.status == "completed") green else Color(0xFFCC4444),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        if (showDetail) {
                            Column(modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) {
                                RecapLine("Records processed", src.processed)
                                RecapLine("Records selected", src.selected)
                                RecapLine("Duplicates", src.dupes)
                                RecapLine("Adds", src.adds)
                                RecapLine("Unprocessed errors", src.errors)
                            }
                        }
                    }
                }

                Divider(color = border, modifier = Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total", color = txtLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("${progress?.sources?.sumOf { it.imported } ?: 0}",
                        color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        val elapsed = (progress?.elapsedMs ?: 0) / 1000
        Text("Completed in ${elapsed / 60}m ${elapsed % 60}s",
            color = txtDim, fontSize = 11.sp)

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = green),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Text("OPEN RIDE MAP", color = Color(0xFF0D1117),
                fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
