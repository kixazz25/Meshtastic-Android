package com.geeksville.mesh.convoy

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.vectorResource
import org.meshtastic.core.resources.Res

/**
 * ConvoyScreen — IMP-001 Task 4.2 + 5.1 + 5.2 + 5.3 + 5.4
 * Full-screen WebView/Leaflet map + HUD strip.
 */
enum class RecordingState { IDLE, RECORDING, PAUSED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoyScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToCreateEvent: () -> Unit = {},
    onNavigateToSettingsPanel: () -> Unit = {},
    viewModel: ConvoyViewModel = hiltViewModel()
) {
    val convoyState by viewModel.convoyState.collectAsStateWithLifecycle()
    val hudMode by viewModel.hudMode.collectAsStateWithLifecycle()
    val selectedNode by viewModel.selectedNode.collectAsStateWithLifecycle()
    val trackActive by viewModel.trackActive.collectAsStateWithLifecycle()
    val trackLeadOnly by viewModel.trackLeadOnly.collectAsStateWithLifecycle()
    val offTrackIds by viewModel.offTrackIds.collectAsStateWithLifecycle()
    val simulationMode by viewModel.simulationMode.collectAsStateWithLifecycle()
    val showLeadTrack by viewModel.showLeadTrack.collectAsStateWithLifecycle()
    var recordingState by viewModel.recordingState
    var showNameDialog by remember { mutableStateOf(false) }
    var pendingTrackName by viewModel.pendingTrackName
    val context = LocalContext.current
    var showLayerMenu by remember { mutableStateOf(false) }
    var mapTypeLabel by remember { mutableStateOf("SAT") }
    var showMapSettings by remember { mutableStateOf(false) }
    var showConvoyMenu by remember { mutableStateOf(false) }
    val convoyMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mapZoomLevel by remember { mutableStateOf(18f) }
    var isOfflineMode by remember { mutableStateOf(false) }
    var mapInitialized by remember { mutableStateOf(false) }
    var showRecMenu by viewModel.showRecMenu

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // ── Renderer (stable across recompositions) ───────────────────────────
    val renderer = remember { ConvoyMarkerRenderer(context, onNodeTapped = viewModel::onMarkerTapped) }
    val webViewRef = remember { androidx.compose.runtime.mutableStateOf<android.webkit.WebView?>(null) }
    var mapReady by remember { mutableStateOf(0) } // increments each time map page finishes loading

    // ── Push node markers to Leaflet map ────────────────────────────────────
    LaunchedEffect(convoyState) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val validNodes = convoyState.nodes.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        wv.post {
            wv.evaluateJavascript("clearMarkers()", null)
            validNodes.forEach { node ->
                val color = node.markerColor
                val label = node.callsign.ifEmpty { node.nodeId.takeLast(4) }
                val isMine = node.isMyCart
                val isOffTrack = offTrackIds.contains(node.nodeId)
                wv.evaluateJavascript("addMarker('${node.nodeId}', ${node.latitude}, ${node.longitude}, '$color', '$label', $isMine, $isOffTrack)", null)
            }
        }
    }

    // ── Map zoom/center based on HUD mode ─────────────────────────────────
    LaunchedEffect(hudMode, convoyState, selectedNode) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val nodes = convoyState.nodes
        when (hudMode) {
            HudMode.MY_CART -> {
                val myCart = nodes.firstOrNull { it.isMyCart }
                myCart?.let {
                    wv.evaluateJavascript("setView(${it.latitude}, ${it.longitude}, ${ConvoyConfig.MAP_CART_ZOOM})", null)
                }
            }
            HudMode.NODE -> {
                selectedNode?.let {
                    wv.evaluateJavascript("setView(\${it.latitude}, \${it.longitude}, \${ConvoyConfig.MAP_CART_ZOOM})", null)
                }
            }
            else -> {
                // GROUP / COLLAPSED — fit all nodes with valid GPS only
                val validNodes = nodes.filter { it.latitude != 0.0 && it.longitude != 0.0 }
                if (validNodes.isNotEmpty()) {
                    val lats = validNodes.joinToString(",") { it.latitude.toString() }
                    val lons = validNodes.joinToString(",") { it.longitude.toString() }
                    wv.evaluateJavascript("fitBounds([$lats], [$lons])", null)
                }
            }
        }
    }

    // ── Push convoy data to renderer on each state change ─────────────────
    // Task 5.2: wire renderer to live data
    val rawSegments by viewModel.leadTrackSegments.collectAsStateWithLifecycle()
    val gpsTrail by viewModel.gpsTrailSegments.collectAsStateWithLifecycle()
    val routeTrail by viewModel.routeTrailSegments.collectAsStateWithLifecycle()
    val trackSegments = remember(rawSegments, gpsTrail, routeTrail, trackLeadOnly) {
        // Apply lead-only filter — if trackLeadOnly, skip routeTrail (all-cart overlay)
        val activeSegments = if (trackLeadOnly) rawSegments else (rawSegments + routeTrail)
        // Apply color setting — if not multicolor, force all segments to black
        activeSegments.map { seg ->
            TrackSegment(
                points = listOf(LatLngPoint(seg.startLat, seg.startLon), LatLngPoint(seg.endLat, seg.endLon)),
                color = if (ConvoyConfig.TRACK_MULTICOLOR) seg.color else "#000000"
            )
        }
    }
    LaunchedEffect(trackSegments, mapReady) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val parts = trackSegments.map { seg ->
            val s = seg.points.first()
            val e = seg.points.last()
            buildString {
                append("{startLat:")
                append(s.latitude)
                append(",startLon:")
                append(s.longitude)
                append(",endLat:")
                append(e.latitude)
                append(",endLon:")
                append(e.longitude)
                append(",color:'" + seg.color + "'}") 
            }
        }
        val json = "[" + parts.joinToString(",") + "]"
        wv.evaluateJavascript("drawTrack(" + json + ")", null)
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Name this track") },
            text = {
                OutlinedTextField(
                    value = pendingTrackName,
                    onValueChange = { pendingTrackName = it },
                    label = { Text("Track name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    if (pendingTrackName.isNotBlank()) {
                        recordingState = RecordingState.RECORDING
                        viewModel.startRecording(pendingTrackName.trim(), context)
                    }
                }) { Text("START") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("CANCEL") }
            }
        )
    }
    Scaffold { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

        // ── WebView/Leaflet map ───────────────────────────────────
        AndroidView(
            factory = { ctx ->
                val existing = viewModel.persistentWebView
                if (existing != null) {
                    webViewRef.value = existing
                    existing
                } else {
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                val tileUrl = ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: return
                                view?.postDelayed({
                                    view.evaluateJavascript("setTileUrl('$tileUrl')", null)
                                }, 600)
                                mapReady++
                            }
                        }
                        loadUrl("file:///android_asset/convoy_map.html")
                    }.also {
                        viewModel.persistentWebView = it
                        webViewRef.value = it
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.visibility = if (viewModel.hasSeenNodes.value)
                    android.view.View.VISIBLE else android.view.View.GONE
            }
        )
        // ── Convoy splash screen — 3 second timer on cold start ─────────
        val showSplash = !viewModel.hasSeenNodes.value
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(3000)
            viewModel.hasSeenNodes.value = true
        }
        if (showSplash) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A2E)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "⬡",
                        color = Color(0xFF67EA94),
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "CONVOY",
                        color = Color(0xFF67EA94),
                        fontSize = 36.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Waiting for mesh nodes...",
                        color = Color(0xFF888888),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                    Text(
                        text = "Developed by: Fred Kix",
                        color = Color(0xFF888888),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "Developed by: Fred Kix",
                    color = Color(0xFF888888),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp)
                )
            }
        }

        Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            if (!showRecMenu) {
                // Main REC button
                Surface(
                    modifier = Modifier.clickable {
                        when (recordingState) {
                            RecordingState.IDLE -> { pendingTrackName = ""; showNameDialog = true; viewModel.startGroupTrack(); android.widget.Toast.makeText(context, "Group track started", android.widget.Toast.LENGTH_LONG).show() }
                            RecordingState.RECORDING -> { showRecMenu = true }
                            RecordingState.PAUSED -> showRecMenu = true
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = when (recordingState) {
                        RecordingState.IDLE -> Color(0xFF8B0000)
                        RecordingState.RECORDING -> Color(0xFFCC0000)
                        RecordingState.PAUSED -> Color(0xFF994400)
                    },
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = when (recordingState) {
                            RecordingState.IDLE -> "⏺  REC"
                            RecordingState.RECORDING -> "⏸  PAUSE"
                            RecordingState.PAUSED -> "⏺  RESUME"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
                // Expanded menu: RESUME and END
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        modifier = Modifier.clickable {
                            if (recordingState == RecordingState.PAUSED) {
                                recordingState = RecordingState.RECORDING
                                viewModel.resumeRecording(context)
                            } else {
                                recordingState = RecordingState.PAUSED
                                viewModel.pauseRecording()
                            }
                            showRecMenu = false
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF994400),
                        shadowElevation = 6.dp
                    ) {
                        Text(if (recordingState == RecordingState.PAUSED) "▶  CONTINUE" else "⏸  PAUSE", color = Color.White, fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                    Surface(
                        modifier = Modifier.clickable {
                            recordingState = RecordingState.IDLE
                            showRecMenu = false
                            pendingTrackName = ""
                            viewModel.stopRecording()
                            viewModel.stopGroupTrack()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF4A0000),
                        shadowElevation = 6.dp
                    ) {
                        Text("⏹  END", color = Color.White, fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }

        // ── CONTACT LOST banner ───────────────────────────────────────────
        if (convoyState.hasLost && hudMode != HudMode.COLLAPSED) {
            val lostNames = convoyState.nodes
                .filter { it.status == ConvoyStatus.LOST }
                .map { it.callsign }
            ContactLostBanner(
                lostCount = convoyState.lostCount,
                lostNames = lostNames,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // NODE mode RETURN is inside NodeDetailHud panel

        // ── Task 5.3: Show Lead Track toggle + Task 5.4: Route Recorder ──
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── MAP SETTINGS PANEL ──────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xEE1E252F),
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(8.dp).width(200.dp).verticalScroll(rememberScrollState())) {
                    // Header row — always visible, tap to expand/collapse
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showMapSettings = !showMapSettings },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Layers, contentDescription = "Settings",
                                tint = Color(0xFF2E75B6), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("MAP  $mapTypeLabel", color = Color(0xFF2E75B6), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Text(if (showMapSettings) "▲" else "▼", color = Color(0xFF4A6080), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }

                    // Expanded settings
                    if (showMapSettings) {
                        Spacer(Modifier.height(8.dp))

                        // ── Tile source buttons ──────────────────────────
                        Text("LAYER", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                Triple("SAT", "Satellite", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
                                Triple("HYB", "Hybrid", "https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"),
                                Triple("TOPO", "Topo", "https://tile.opentopomap.org/"),
                                Triple("RD", "Road", "https://tile.openstreetmap.org/")
                            ).forEach { (label, name, url) ->
                                Surface(
                                    modifier = Modifier.weight(1f).clickable {
                                        mapTypeLabel = label
                                        webViewRef.value?.evaluateJavascript("setTileUrl('$url')", null)
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (mapTypeLabel == label) Color(0xFF2E75B6) else Color(0xFF2A3545)
                                ) {
                                    Text(label, color = Color.White, fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // ── Zoom slider ──────────────────────────────────
                        Text("ZOOM  ${mapZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(
                            value = mapZoomLevel,
                            onValueChange = { mapZoomLevel = it },
                            onValueChangeFinished = {
                                ConvoyConfig.DOWNLOAD_ZOOM = mapZoomLevel.toInt()
                                        // TODO: invalidate via JS bridge
                            },
                            valueRange = 16f..19f,
                            steps = 2,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(6.dp))

                        // ── Online/Offline toggle ────────────────────────
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (isOfflineMode) "OFFLINE" else "ONLINE", color = if (isOfflineMode) Color(0xFFFFAA00) else Color(0xFF1CF0A0),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = isOfflineMode,
                                onCheckedChange = {
                                    isOfflineMode = it
                                    // TODO A2.5: setOffline via JS bridge
                                }
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // ── Download Region button ───────────────────────
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                // TODO: trigger tile pre-fetch for current bounding box at mapZoomLevel
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2A3545)
                        ) {
                            Text("⬇  DOWNLOAD REGION", color = Color(0xFF7A8DA0), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }

                    }
                }
            }


            // REC button placeholder — large button added as map overlay below

            // Sim mode toggle (dev only)
            TextButton(
                onClick = { viewModel.setSimulationMode(!simulationMode) },
                modifier = Modifier.padding(0.dp)
            ) {
                Text(
                    text = if (simulationMode) "SIM" else "LIVE",
                    color = if (simulationMode) Color(0xFFF9C835) else Color(0xFF4A6080),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Convoy submenu bottom sheet ───────────────────────────────────────────────

        // ── Convoy submenu bottom sheet ───────────────────────────────────────────────

        // ── Convoy submenu bottom sheet ───────────────────────────────────────────────

        // ── Convoy submenu bottom sheet ───────────────────────────────────────────────
        if (showConvoyMenu) {
            ConvoySubMenu(
                sheetState                = convoyMenuSheetState,
                onDismiss                 = { showConvoyMenu = false },
                onCreateEventRide         = { showConvoyMenu = false },
                onTransferConfig          = { showConvoyMenu = false },
                onNavigateToCreateEvent   = onNavigateToCreateEvent,
                onNavigateToSettingsPanel = onNavigateToSettingsPanel
            )
        }

        // ── Button bar ────────────────────────────────────────────────────
        ConvoyButtonBar(
            hudMode = hudMode,
            onModeChange = { viewModel.setHudMode(it) },
            onNavigateToSettings = onNavigateToSettings,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // ── HUD strip ─────────────────────────────────────────────────────
        Box(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 48.dp)) {
            when (hudMode) {
                HudMode.GROUP -> GroupHud(
                    state = convoyState,
                    onModeChange = { viewModel.setHudMode(it) },
                    onNavigateToSettings = onNavigateToSettings,
                    trackActive = trackActive,
                    trackLeadOnly = trackLeadOnly,
                    onStartTrack = { viewModel.startGroupTrack() },
                    onStopTrack = { viewModel.stopGroupTrack() },
                    onToggleLeadOnly = { viewModel.toggleLeadOnly() }
                )
                HudMode.MY_CART -> MyCartHud(
                    state = convoyState,
                    myCartId = viewModel.myCartId.collectAsStateWithLifecycle().value,
                    onModeChange = { viewModel.setHudMode(it) },
                    onNavigateToSettings = onNavigateToSettings
                )
                HudMode.NODE -> selectedNode?.let { node ->
                    NodeDetailHud(
                        node = node,
                        onDismiss = { viewModel.dismissNodeHud() }
                    )
                }
                HudMode.COLLAPSED -> CollapsedPill(
                    totalNodes = convoyState.nodes.size,
                    lostCount = convoyState.lostCount,
                    hasLost = convoyState.hasLost,
                    onExpand = { viewModel.setHudMode(HudMode.GROUP) }
                )
            }
        }
    }
    }
}

// ── GROUP HUD ─────────────────────────────────────────────────────────────────

@Composable
fun GroupHud(
    state: ConvoyEngine.ConvoyState,
    onModeChange: (HudMode) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    trackActive: Boolean = false,
    trackLeadOnly: Boolean = true,
    onStartTrack: () -> Unit = {},
    onStopTrack: () -> Unit = {},
    onToggleLeadOnly: () -> Unit = {}
) {
    HudCard {
        // Title
        Text("GROUP", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 6.dp))
        // Row 1: Carts · Active · Lost
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            HudStat("Carts", "${state.nodes.size}")
            HudStat("Active", "${state.activeCount}")
            HudStat("Lost", "${state.lostCount}")
        }
        Spacer(Modifier.height(4.dp))
        // Row 2: Span big + Lead + Tail
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Column {
                Text("SPAN", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.1f".format(state.span_miles), color = Color(0xFFFF0000).copy(alpha = 1f),
                        fontSize = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        lineHeight = 48.sp)
                    Text(" mi", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                Text("▲ Lead", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(state.lead?.callsign ?: "--", color = Color(0xFF1CF0A0),
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                Text("▽ Tail", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(state.tail?.callsign ?: "--", color = Color(0xFFFF8C42),
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── MY CART HUD ───────────────────────────────────────────────────────────────

@Composable
fun MyCartHud(
    state: ConvoyEngine.ConvoyState,
    myCartId: String,
    onModeChange: (HudMode) -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val myCart = state.nodes.firstOrNull { it.isMyCart }
    HudCard {
        // Title
        Text("My Cart  ★ HOTEL-10", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (myCart == null) {
            Text("MY CART not found", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace)
        } else {
            // Row 1: Heading · Battery · Altitude
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HudStat("Heading", "%.0f°".format(myCart.heading_deg))
                HudStat("Battery", "${myCart.battery_pct}%")
                HudStat("Altitude", "${myCart.altitude_m} ft")
            }
            Spacer(Modifier.height(4.dp))
            // Row 2: Speed big + 2x2 grid
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Column {
                    Text("Speed", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.0f".format(myCart.speed_mph), color = Color(0xFFFF0000).copy(alpha = 1f),
                            fontSize = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            lineHeight = 48.sp)
                        Text(" mph", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HudStat("↑↑ To Lead", "%.1f mi".format(myCart.milesToLead))
                        HudStat("↓↓ To Tail", "%.1f mi".format(myCart.milesToTail))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HudStat("↑ Gap Ahead", "%.0f ft".format(myCart.feetToNodeAhead))
                        HudStat("↓ Gap Behind", "%.0f ft".format(myCart.feetToNodeBehind))
                    }
                }
            }
        }
    }
}

// ── NODE DETAIL HUD ───────────────────────────────────────────────────────────

@Composable
fun NodeDetailHud(
    node: ConvoyNode,
    onDismiss: () -> Unit
) {
    HudCard {
        // Title — cart name in its color + RETURN tap
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Text(node.callsign, color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp)
            Surface(
                modifier = Modifier.clickable { onDismiss() },
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF2E75B6)
            ) {
                Text("RETURN", color = Color.White, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            HudStat("STATUS", node.status.name,
                when (node.status) {
                    ConvoyStatus.LOST        -> Color(0xFFF44336)
                    ConvoyStatus.SIGNAL_DROP -> Color(0xFFFFFF00)
                    ConvoyStatus.ACTIVE      -> Color(0xFF00AA00)
                })
            HudStat("SPD", "%.0f mph".format(node.speed_mph))
            HudStat("BAT", "${node.battery_pct}%")
            HudStat("SNR", "%.1f dB".format(node.snr_db))
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            HudStat("POS", "#${node.convoyPosition}")
            HudStat("HDG", "%.0f°".format(node.heading_deg))
            HudStat("ALT", "${node.altitude_m}m")
            HudStat("SEEN", node.lastSeenAgo)
        }
    }
}

// ── COLLAPSED PILL ────────────────────────────────────────────────────────────

@Composable
fun CollapsedPill(
    totalNodes: Int,
    lostCount: Int,
    hasLost: Boolean,
    onExpand: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pill_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = if (hasLost) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink"
    )
    Surface(
        modifier = Modifier
            .padding(bottom = 16.dp)
            .clickable { onExpand() },
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1E252F),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$totalNodes UNITS", color = Color(0xFFE8EEF5), fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (lostCount > 0) {
                Spacer(Modifier.width(12.dp))
                Text("$lostCount LOST", color = Color(0xFFF44336), fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.alpha(alpha))
            }
        }
    }
}

// ── CONTACT LOST BANNER ───────────────────────────────────────────────────────

@Composable
fun ContactLostBanner(lostCount: Int, lostNames: List<String> = emptyList(), modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "banner_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink"
    )
    val nameStr = if (lostNames.isNotEmpty()) lostNames.joinToString(", ") else ""
    Surface(
        modifier = modifier.padding(top = 8.dp).alpha(alpha),
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFFF44336)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = "CONTACT LOST  $lostCount NODE${if (lostCount > 1) "S" else ""}",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            if (nameStr.isNotEmpty()) {
                Text(
                    text = nameStr,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── SHARED COMPOSABLES ────────────────────────────────────────────────────────

@Composable
fun HudCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .wrapContentWidth()
            .padding(start = 16.dp, bottom = 12.dp),
        content = content
    )
}

@Composable
fun HudStat(label: String, value: String, valueColor: Color = Color(0xFFFF0000).copy(alpha = 1f)) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp)
        Text(value, color = valueColor, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HudModeRow(current: HudMode, onModeChange: (HudMode) -> Unit, onNavigateToSettings: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // GROUP button
        Surface(
            modifier = Modifier.weight(1f).clickable { onModeChange(HudMode.GROUP) },
            shape = RoundedCornerShape(10.dp),
            color = if (current == HudMode.GROUP) Color(0xFF2E75B6) else Color(0xFF2A3545)
        ) {
            Text(
                text = "GROUP",
                color = if (current == HudMode.GROUP) Color.White else Color(0xFF7A8DA0),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
        // MY CART button
        Surface(
            modifier = Modifier.weight(1f).clickable { onModeChange(HudMode.MY_CART) },
            shape = RoundedCornerShape(10.dp),
            color = if (current == HudMode.MY_CART) Color(0xFF2E75B6) else Color(0xFF2A3545)
        ) {
            Text(
                text = "MY CART",
                color = if (current == HudMode.MY_CART) Color.White else Color(0xFF7A8DA0),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
        // HIDE button
        Surface(
            modifier = Modifier.weight(1f).clickable { onModeChange(HudMode.COLLAPSED) },
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF2A3545)
        ) {
            Text(
                text = "HIDE",
                color = Color(0xFF7A8DA0),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        // SETTINGS gear button
        Surface(
            modifier = Modifier.weight(1f).clickable { onNavigateToSettings() },
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF2A3545)
        ) {
            Text(
                text = "⚙",
                color = Color(0xFF7A8DA0),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

// ── CONVOY BUTTON BAR ─────────────────────────────────────────────────────────

@Composable
fun ConvoyButtonBar(
    hudMode: HudMode,
    onModeChange: (HudMode) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF2B2930))
            .drawBehind {
                drawLine(
                    color = Color(0xFF67EA94),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 2f
                )
            },
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        listOf(
            Triple("GROUP", HudMode.GROUP, { onModeChange(HudMode.GROUP) }),
            Triple("MY CART", HudMode.MY_CART, { onModeChange(HudMode.MY_CART) }),
            Triple("HIDE", HudMode.COLLAPSED, { onModeChange(HudMode.COLLAPSED) }),
        ).forEach { (label, mode, action) ->
            val isActive = hudMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isActive) Color(0x1A67EA94) else Color.Transparent)
                    .clickable { action() }
                    .drawBehind {
                        drawLine(
                            color = Color(0xFF49454F),
                            start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                            strokeWidth = 1f
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isActive) Color(0xFF67EA94) else Color(0xFFCAC4D0),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
        // GEAR button
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Transparent)
                .clickable { onNavigateToSettings() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚙",
                color = Color(0xFFCAC4D0),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
