package com.geeksville.mesh.convoy

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Surface
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.MapView

/**
 * ConvoyScreen — IMP-001 Task 4.2 + 5.1 + 5.2 + 5.3 + 5.4
 * Full-screen OSMDroid map + HUD strip.
 */
enum class RecordingState { IDLE, RECORDING, PAUSED }

@Composable
fun ConvoyScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: ConvoyViewModel = hiltViewModel()
) {
    val convoyState by viewModel.convoyState.collectAsStateWithLifecycle()
    val hudMode by viewModel.hudMode.collectAsStateWithLifecycle()
    val selectedNode by viewModel.selectedNode.collectAsStateWithLifecycle()
    val simulationMode by viewModel.simulationMode.collectAsStateWithLifecycle()
    val showLeadTrack by viewModel.showLeadTrack.collectAsStateWithLifecycle()
    var recordingState by remember { mutableStateOf(RecordingState.IDLE) }
    var showLayerMenu by remember { mutableStateOf(false) }
    var mapTypeLabel by remember { mutableStateOf("SAT") }
    var showMapSettings by remember { mutableStateOf(false) }
    var mapZoomLevel by remember { mutableStateOf(18f) }
    var isOfflineMode by remember { mutableStateOf(false) }
    var mapInitialized by remember { mutableStateOf(false) }
    var showRecMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // ── Renderer (stable across recompositions) ───────────────────────────
    val renderer = remember { ConvoyMarkerRenderer(context, onNodeTapped = viewModel::onMarkerTapped) }
    val webViewRef = remember { androidx.compose.runtime.mutableStateOf<android.webkit.WebView?>(null) }

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
                wv.evaluateJavascript("addMarker('${node.nodeId}', ${node.latitude}, ${node.longitude}, '$color', '$label', $isMine)", null)
            }
        }
    }

    // ── Map zoom/center based on HUD mode ─────────────────────────────────
    LaunchedEffect(hudMode, convoyState) {
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
                // Node focus handled when node selected
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
    val trackSegments = remember(rawSegments) {
        rawSegments.map { seg ->
            TrackSegment(
                points = listOf(LatLngPoint(seg.startLat, seg.startLon), LatLngPoint(seg.endLat, seg.endLon)),
                color = seg.color
            )
        }
    }
    LaunchedEffect(trackSegments) {
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
                append(",color:  + seg.color + }") 
            }
        }
        val json = "[" + parts.joinToString(",") + "]"
        wv.evaluateJavascript("drawTrack(" + json + ")", null)
    }

    Scaffold { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

        // ── Task 5.1: Real OSMDroid map ───────────────────────────────────
        AndroidView(
            factory = { ctx ->
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
                        }
                    }
                    loadUrl("file:///android_asset/convoy_map.html")
                }.also { webViewRef.value = it }
            },
            modifier = Modifier.fillMaxSize(),
            update = { _ ->
                // OSMDroid update block cleared — JS bridge wired in A3 steps
            }
        )
        Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            if (!showRecMenu) {
                // Main REC button
                Surface(
                    modifier = Modifier.clickable {
                        when (recordingState) {
                            RecordingState.IDLE -> { recordingState = RecordingState.RECORDING; viewModel.toggleRouteRecorder() }
                            RecordingState.RECORDING -> { recordingState = RecordingState.PAUSED; showRecMenu = true }
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
                            recordingState = RecordingState.RECORDING
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
                            viewModel.toggleRouteRecorder()
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
                                        val src = org.osmdroid.tileprovider.tilesource.XYTileSource(
                                            name, 1, 19, 256, if (label == "SAT") ".jpg" else ".png", arrayOf(url))
                                        // TODO A2.5: setTileUrl via JS bridge
                                        // TODO: invalidate via JS bridge
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

        // ── HUD strip ─────────────────────────────────────────────────────
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            when (hudMode) {
                HudMode.GROUP -> GroupHud(
                    state = convoyState,
                    onModeChange = { viewModel.setHudMode(it) },
                    onNavigateToSettings = onNavigateToSettings
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
    onNavigateToSettings: () -> Unit = {}
) {
    HudCard {
        HudModeRow(current = HudMode.GROUP, onModeChange = onModeChange, onNavigateToSettings = onNavigateToSettings)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left 3/4 — stats grid
            Column(modifier = Modifier.weight(3f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    HudStat("UNITS", "${state.nodes.size}")
                    HudStat("ACTIVE", "${state.activeCount}", Color(0xFF00AA00))
                    HudStat("LOST", "${state.lostCount}", if (state.lostCount > 0) Color(0xFFF44336) else Color(0xFF7A8DA0))
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    HudStat("LEAD", state.lead?.callsign ?: "--", Color(0xFF1CF0A0))
                    HudStat("TAIL", state.tail?.callsign ?: "--", Color(0xFFFF8C42))
                }
            }
            // Right 1/4 — SPAN large
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("SPAN", color = Color(0xFF4A6080), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
                Text("%.1f".format(state.span_miles), color = Color(0xFFE8EEF5),
                    fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("mi", color = Color(0xFF4A6080), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
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
        HudModeRow(current = HudMode.MY_CART, onModeChange = onModeChange, onNavigateToSettings = onNavigateToSettings)
        Spacer(Modifier.height(8.dp))
        if (myCart == null) {
            Text("MY CART not found", color = Color(0xFF7A8DA0), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                HudStat("SPD", "%.0f mph".format(myCart.speed_mph))
                HudStat("HDG", "%.0f°".format(myCart.heading_deg))
                HudStat("BAT", "${myCart.battery_pct}%",
                    if (myCart.battery_pct <= 20) Color(0xFFFFAA00) else Color(0xFF1CF0A0))
                HudStat("ALT", "${myCart.altitude_m}m")
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                HudStat("AHEAD", "%.0f ft".format(myCart.feetToNodeAhead))
                HudStat("BEHIND", "%.0f ft".format(myCart.feetToNodeBehind))
                HudStat("TO LEAD", "%.1f mi".format(myCart.milesToLead))
                HudStat("TO TAIL", "%.1f mi".format(myCart.milesToTail))
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.clickable { onDismiss() },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2E75B6)
            ) {
                Text("RETURN", color = Color.White, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Text(node.callsign, color = Color(0xFFE8EEF5), fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("[ ${node.role} ]", color = Color(0xFF7A8DA0), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
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
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E252F)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
fun HudStat(label: String, value: String, valueColor: Color = Color(0xFFE8EEF5)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = valueColor, fontSize = 13.sp,
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
            modifier = Modifier.clickable { onModeChange(HudMode.COLLAPSED) },
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
            modifier = Modifier.clickable { onNavigateToSettings() },
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
