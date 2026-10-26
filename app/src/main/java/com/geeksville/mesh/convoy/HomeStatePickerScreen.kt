package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
private val bg: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF0F1216 
private val cardBg: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF1A2233 
private val border: Color get() = Gt.line  // STD1-2026-10-24 was 0xFF2A3444 
private val green: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF7BB661 
private val blue: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF4DA6FF 
private val txtDim: Color get() = Gt.dim  // STD1-2026-10-24 was 0xFF8899AA 
private val txtLight: Color get() = Gt.text  // STD1-2026-10-24 was 0xFFE6EDF3 

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeStatePickerScreen(
    onNavigateBack: () -> Unit = {},
    // AREAWIRE-2026-08-21C: CODE RULE 1 justification -- this is a MODE DISCRIMINATOR,
    // not a shortcut. ABSENT = state mode (geocode, offer, rider picks).
    // PRESENT = area mode (bbox already drawn; detection is meaningless and the
    // screen enters at its running phase). Order is S, W, N, E.
    areaBbox: DoubleArray? = null,
    // TRAILSELECT-2026-08-21D: ANY-STATE mode. Home State is the INSTALL concept --
    // GPS detect, "your Home state is X", pre-selected. This is a trip to
    // Colorado: no detection, no pre-selection, no home-state language.
    // Same screen, same import process, different entry phase.
    anyState: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── State ────────────────────────────────────────────────────
    var phase by remember { mutableStateOf("detecting") } // detecting | confirm | list | running | done
    var allStates by remember { mutableStateOf(emptyList<StatePickerItem>()) }
    var detectedState by remember { mutableStateOf<GeofabrikState?>(null) }
    var selectedItem by remember { mutableStateOf<StatePickerItem?>(null) }
    var searchText by remember { mutableStateOf("") }
    // AREAPICK-2026-10-24: the list of everything an area run will process -- states (slugs ticked) and agency sources (ids ticked).
    var areaHits by remember { mutableStateOf(emptyList<GeofabrikState>()) }
    var areaWhole by remember { mutableStateOf(emptyMap<String, String>()) }
    var areaPicked by remember { mutableStateOf(emptySet<String>()) }
    var areaCat by remember { mutableStateOf(emptyList<CatalogSource>()) }
    var areaCatPicked by remember { mutableStateOf(emptySet<String>()) }
    val progress by HomeStateImportController.progress.collectAsState()
    // KILLEDIMPORT-2026-10-02: a running import this screen did not start still ends in "done".
    LaunchedEffect(progress?.phase) {
        if (phase == "running" && !HomeStateImportController.isImporting &&
            (progress?.phase == "completed" || progress?.phase == "failed")) phase = "done"
    }

    // ── Detect on launch ─────────────────────────────────────────
    LaunchedEffect(Unit) {
        // KILLEDIMPORT-2026-10-02: an import is already running -- show it, never a list that cannot start.
        if (HomeStateImportController.isImporting) { phase = "running"; return@LaunchedEffect }
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
            // AREAPICK-2026-10-24 (Fred 12:53-12:56): THE LIST FIRST -- a rectangle can touch a state with no ground in the box.
            // Whole-imported states open unticked; everything else ticked. An empty state list is NOT an error.
            areaWhole = withContext(Dispatchers.IO) { HomeStateImportController.wholeImportedDates(context) }
            areaCat = withContext(Dispatchers.IO) {
                HomeStateImportController.areaCatalogSources(context, areaBbox[0], areaBbox[1], areaBbox[2], areaBbox[3])
            }
            areaHits = hits
            areaPicked = hits.map { it.slug }.filter { it !in areaWhole }.toSet()
            areaCatPicked = areaCat.map { it.id }.toSet()
            phase = "areapick"
            return@LaunchedEffect
        }
        val states = withContext(Dispatchers.IO) { GeofabrikCatalog.load(context) }
        allStates = GeofabrikCatalog.displayList(states)
        // TRAILSELECT-2026-08-21D: any-state entry -- straight to the list, nothing chosen.
        if (anyState) {
            phase = "list"
            Log.i(TAG, "ANY-STATE import: ${allStates.size} states, no pre-selection")
            return@LaunchedEffect
        }
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

    // AREAPICK-2026-10-24: START -- exactly what is ticked, as THE import (outlives the screen, one at a time), as startImport below.
    fun startArea(bb: DoubleArray) {
        if (com.geeksville.mesh.grouptrack.GtUtahPrivate.busy.value) { android.widget.Toast.makeText(context, "Assigning Utah private property is running \u2014 start the import when it finishes.", android.widget.Toast.LENGTH_LONG).show(); return }   // UTAHONCESHOW-2026-10-25
        if (!HomeStateImportController.beginImport()) { phase = "running"; return }
        phase = "running"
        val run = areaHits.filter { it.slug in areaPicked }
        val left = areaHits.filter { it.slug !in areaPicked }.map { st ->
            st.name + " Open Source Maps" + (areaWhole[st.slug]?.let { " (already imported whole $it)" } ?: " (left out by you)")
        } + areaCat.filter { it.id !in areaCatPicked }.map { it.name + " (left out by you)" }
        val cats = areaCatPicked
        Log.i(TAG, "AREAPICK-2026-10-24: run ${run.joinToString(", ") { it.slug }} + ${cats.size} source(s); skipped ${left.joinToString("; ")}")
        HomeStateImportController.launchImport {
            HomeStateImportController.executeArea(context, bb[0], bb[1], bb[2], bb[3], run, cats, left)
            phase = "done"
        }
    }

    // ── Launch import ────────────────────────────────────────────
    fun startImport(item: StatePickerItem) {
        if (com.geeksville.mesh.grouptrack.GtUtahPrivate.busy.value) { android.widget.Toast.makeText(context, "Assigning Utah private property is running \u2014 start the import when it finishes.", android.widget.Toast.LENGTH_LONG).show(); return }   // UTAHONCESHOW-2026-10-25
        // ⛔⛔ IMPORTOWNER-2026-09-03: TWO DEFECTS LIVED IN THE THREE LINES THIS
        // REPLACES, and the 09-03 log shows both firing at once.
        //
        // 1. NOTHING STOPPED A SECOND IMPORT. New Hampshire started at 06:02:45,
        //    Utah at 06:03:22 -- thirty-seven seconds apart, on separate
        //    threads, over the same database. That is where the two
        //    "database is locked" failures came from.
        //
        // 2. `scope` WAS rememberCoroutineScope(). It dies with the composable,
        //    so dismissing the panel ORPHANED the work rather than stopping it:
        //    the cancelled New Hampshire import went on to pull a 67 MB PBF for
        //    eight more minutes, and step 8 died mid-classification with
        //    "rememberCoroutineScope left the composition" -- ⛔ leaving trails
        //    loaded, NOTHING classified, and a manifest that read as success.
        //
        // ⭐ THE CONTROLLER OWNS THE JOB NOW. It outlives every screen, one
        // import at a time, and cancel reaches the work instead of the panel.
        if (!HomeStateImportController.beginImport()) {
            Log.w(TAG, "import already running -- showing its progress")
            phase = "running"   // KILLEDIMPORT-2026-10-02: was a silent return -- the panel that would not proceed
            return
        }
        phase = "running"
        HomeStateImportController.launchImport {
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
                    if (phase == "areapick") "Import trails by area"   // AREAPICK-2026-10-24
                    else if (phase == "running") "Importing Trail Data"
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
                        "areapick" -> "What will be processed for your box"   // AREAPICK-2026-10-24
                        "running" -> "Processing sources for ${selectedItem?.displayName ?: ""}..."
                        "done" -> "Your maps are ready"
                        else -> ""
                    },
                    color = txtDim, fontSize = 12.sp
                )
            }
        }

        // KILLEDIMPORT-2026-10-02 (Fred): an import that did not finish is removed at startup -- say so, and re-pick.
        val killedArea = HomeStateImportController.killedImportArea
        if (killedArea != null && (phase == "detecting" || phase == "confirm" || phase == "list")) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                color = Gt.warn.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    "The $killedArea state import did not finish and was removed. Select a state and process again.",
                    color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
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

            "areapick" -> AreaSourcePanel(areaHits, areaWhole, areaPicked, areaCat, areaCatPicked,   // AREAPICK-2026-10-24
                onToggle = { slug -> areaPicked = if (slug in areaPicked) areaPicked - slug else areaPicked + slug },
                onToggleCat = { id -> areaCatPicked = if (id in areaCatPicked) areaCatPicked - id else areaCatPicked + id },
                onStart = { areaBbox?.let { startArea(it) } },
                onCancel = onNavigateBack)

            "running" -> ProgressPanel(progress = progress)

            "done" -> CompletionPanel(
                progress = progress,
                onDone = onNavigateBack
            )
        }
    }
}

// AREAPICK-2026-10-24: the list of everything an area run will process. STATES (Open Source Maps) with each state's own agency
// sources under it; OTHER SOURCES (national). A check box per item; unticked = left off the run.
private fun stateBase(st: GeofabrikState): String = st.name.removeSuffix(" North").removeSuffix(" South")

private fun scopeLabel(c: CatalogSource): String = when (c.scope) {
    "national_parks" -> "national parks"
    "national_motorized" -> "national, motorized"
    "state" -> "state"
    else -> "national"
}

@Composable
private fun AreaItemRow(checked: Boolean, label: String, indent: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(start = if (indent) 34.dp else 0.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        com.geeksville.mesh.grouptrack.GtCheck(checked)
        Text(label, color = Gt.text, fontSize = if (indent) 14.sp else 15.sp, fontWeight = if (indent) FontWeight.Normal else FontWeight.Medium)
    }
}

@Composable
private fun AreaSourcePanel(hits: List<GeofabrikState>, whole: Map<String, String>, picked: Set<String>,
                            cat: List<CatalogSource>, catPicked: Set<String>,
                            onToggle: (String) -> Unit, onToggleCat: (String) -> Unit, onStart: () -> Unit, onCancel: () -> Unit) {
    // a state-scope source goes under the listed state its name or agency names; anything else is an OTHER source
    val home = cat.associate { c ->
        c.id to (if (c.scope == "state") hits.firstOrNull { st -> c.name.contains(stateBase(st)) || c.agency.contains(stateBase(st)) }?.slug ?: "" else "")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Everything ticked is processed for your box. Untick anything to leave it out.", color = Gt.text, fontSize = 14.sp)
        Text("STATES \u2014 Open Source Maps: the whole state is downloaded (several hundred MB), only your box is kept",
            color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("A state is listed when the rectangle around it touches your box, so one can be listed with no ground in it " +
            "(California North on the Nevada line) \u2014 untick it. A state already imported whole starts unticked.",
            color = Gt.text, fontSize = 13.sp)
        if (hits.isEmpty()) Text("No state touches this box.", color = Gt.text, fontSize = 14.sp)
        hits.forEach { st ->
            AreaItemRow(st.slug in picked, st.name + " Open Source Maps" + (whole[st.slug]?.let { " \u2014 already imported whole ($it)" } ?: ""), false) { onToggle(st.slug) }
            cat.filter { home[it.id] == st.slug }.forEach { c -> AreaItemRow(c.id in catPicked, c.name, true) { onToggleCat(c.id) } }
        }
        val others = cat.filter { home[it.id].isNullOrEmpty() }
        if (others.isNotEmpty()) {
            Text("OTHER SOURCES \u2014 only your box is loaded", color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp))
            others.forEach { c -> AreaItemRow(c.id in catPicked, c.name + "  \u00b7  " + scopeLabel(c), false) { onToggleCat(c.id) } }
        }
        val any = picked.isNotEmpty() || catPicked.isNotEmpty()
        if (!any) Text("Nothing is ticked \u2014 there is nothing to process.", color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            com.geeksville.mesh.grouptrack.GtButton("START", onStart, enabled = any)
            com.geeksville.mesh.grouptrack.GtButton("CANCEL", onCancel)
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
            Text(it, color = txtDim, fontSize = 12.sp)
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onLoad,
                colors = ButtonDefaults.buttonColors(containerColor = green.copy(alpha = 0.18f)),
                shape = RoundedCornerShape(8.dp),
                // PICKERFIX-2026-08-21K: was height(52.dp) -- a FIXED box, so the second
                // line ("Downloads all available sources") was sliced mid-glyph.
                // heightIn keeps the minimum and lets the button grow to its content.
                modifier = Modifier.heightIn(min = 52.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOAD ${state.name.uppercase()} TRAIL DATA",
                        color = Gt.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Downloads all available sources",
                        color = Gt.text.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }

            OutlinedButton(
                onClick = onSelectDifferent,
                shape = RoundedCornerShape(8.dp),
                // PICKERFIX-2026-08-21K: same fixed-height crop as the button beside it.
                modifier = Modifier.heightIn(min = 52.dp)
            ) {
                Text("Select a different\nHome state",
                    color = blue, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Internet required · Est. 7 minutes per source",
            color = txtDim, fontSize = 12.sp)
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
                        color = Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text((if (isSelected) "\u2713 " else "") + item.displayName, color = txtLight, fontSize = 13.sp)
                            Text("${item.entries.size} region${if (item.entries.size > 1) "s" else ""}",
                                color = txtDim, fontSize = 12.sp)
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
                            Text(it, color = txtDim, fontSize = 12.sp)
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
                    containerColor = green.copy(alpha = 0.18f),
                    disabledContainerColor = Gt.panel
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("LOAD TRAIL DATA", fontWeight = FontWeight.Bold,
                    color = if (selectedItem != null) Gt.text else Gt.dim)
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

    // Remember when we started for the running clock
    // IMPORTSCROLL-2026-10-24: held ABOVE the list -- state inside a list item restarts when the item scrolls away.
    val startTimeMs = remember { System.currentTimeMillis() }
    var clockTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            clockTick = System.currentTimeMillis()
        }
    }

    // IMPORTSCROLL-2026-10-24 (Fred 13:28): ONE list -- banner, counts, every source, elapsed -- so the whole tracker scrolls.
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {

        // Blinking "Do not close" banner — TOP of panel, unmissable
        val blink = (clockTick / 800) % 2 == 0L
        Surface(
            color = if (blink) Gt.text.copy(alpha = 0.18f) else Gt.text.copy(alpha = 0.18f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text(
                "Do not close GroupTrack while importing",
                color = if (blink) green else Gt.warn,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }

        // Source count + time estimate
        Text("Processing ${progress.totalSources} sources for ${progress.stateName}",
            color = blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (HomeStateImportController.lastSkipped.isNotEmpty())   // AREASKIP-2026-10-24
            Text("Already imported whole \u2014 skipped: " + HomeStateImportController.lastSkipped.joinToString("; "),
                color = Gt.text, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
        Text("Est. ~${progress.estimatedMinutesRemaining} minutes remaining",
            color = txtDim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        }
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
                            else if (isFailed) Gt.bad
                            else txtDim,
                        fontSize = 13.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    )

                    // Steps (simplified — real steps from the controller's currentStep)
                    if (isDone) {
                        Text("  ✅ Complete — ${src.imported} records",
                            color = green, fontSize = 12.sp)
                    } else if (isCurrent) {
                        val liveDetail = dlLive ?: src.stepDetail
                        Text("  ► ${src.currentStep ?: "Processing"}${
                            liveDetail?.let { " — $it" } ?: ""
                        }", color = blue, fontSize = 12.sp)
                    } else if (isFailed) {
                        Text("  ✗ Failed", color = Gt.bad, fontSize = 12.sp)
                    } else {
                        Text("  ○ Waiting", color = txtDim, fontSize = 12.sp)
                    }
                }
            }
        item {
        // Elapsed time (calculated from clock, not progress snapshot)
        val elSec = (clockTick - startTimeMs) / 1000   // IMPORTSCROLL-2026-10-24: reads the clock, so this item redraws every second
        Text("Elapsed: ${elSec / 60}m ${elSec % 60}s",
            color = txtDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center)
        }
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
        Text(label, color = txtLight, fontSize = 12.sp)
        Text(
            if (value < 0) "not reported" else "$value",
            color = if (value < 0) Gt.dim else txtLight,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun CompletionPanel(progress: ImportProgress?, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),   // IMPORTSCROLL-2026-10-24
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("✅", fontSize = 48.sp)
        Spacer(Modifier.height(8.dp))
        Text("${progress?.stateName ?: ""} — Import Complete",
            color = green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (HomeStateImportController.lastSkipped.isNotEmpty())   // AREASKIP-2026-10-24
            Text("Already imported whole \u2014 skipped: " + HomeStateImportController.lastSkipped.joinToString("; "),
                color = Gt.text, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))

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
                            // PICKERFIX-2026-08-21K: adds stays the headline number. When
                            // duplicates are non-zero the line says so, because a
                            // re-import that found everything already present would
                            // otherwise read exactly like a total failure.
                            Text(
                                if (src.status != "completed") "failed"
                                else if (src.dupes > 0) "${src.imported} \u00B7 ${src.dupes} already had"
                                else "${src.imported}",
                                color = if (src.status == "completed") green else Gt.bad,
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
                    // PICKERFIX-2026-08-21K: same treatment as the source lines. A run whose
                    // records were all already present shows "0" without this, and
                    // "Import Complete / Total 0" teaches the rider that a working
                    // import looks like a broken one.
                    Text(
                        run {
                            val added = progress?.sources?.sumOf { it.imported } ?: 0
                            val had = progress?.sources?.sumOf { if (it.dupes > 0) it.dupes else 0 } ?: 0
                            if (had > 0) "$added \u00B7 $had already had" else "$added"
                        },
                        color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        val elapsed = (progress?.elapsedMs ?: 0) / 1000
        Text("Completed in ${elapsed / 60}m ${elapsed % 60}s",
            color = txtDim, fontSize = 12.sp)

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = green.copy(alpha = 0.18f)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Text("OPEN RIDE MAP", color = Gt.text,
                fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
