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
    var signalDropMinutes by remember { mutableStateOf(2f) }
    var lostMinutes by remember { mutableStateOf(10f) }
    var offTrackMiles by remember { mutableStateOf(0.5f) }
    var mapInitialized by remember { mutableStateOf(false) }
    var showRecMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // ── Renderer (stable across recompositions) ───────────────────────────
    val renderer = remember { ConvoyMarkerRenderer(context, onNodeTapped = viewModel::onMarkerTapped) }

    // ── OSMDroid MapView ──────────────────────────────────────────────────
    val mapView = remember {
        MapView(context).apply {
            Configuration.getInstance().userAgentValue = context.packageName
            // Increase tile cache for offline use
            // 1GB tile cache — tiles cached as you browse online
            org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheMaxBytes = 1024L * 1024 * 1024
            org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheTrimBytes = 900L * 1024 * 1024
            // Esri WorldImagery supports zoom 19, better detail than USGS_SAT
            val esriSat = org.osmdroid.tileprovider.tilesource.XYTileSource(
                "Esri.WorldImagery", 1, 19, 256, ".jpg",
                arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
            )
            setTileSource(esriSat)
            setMultiTouchControls(true)
            isVerticalMapRepetitionEnabled = false
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 20.0
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)
            // Center on device GPS location using LocationManager
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val lastKnown = try {
                lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) { null }
            val startCenter = if (lastKnown != null)
                GeoPoint(lastKnown.latitude, lastKnown.longitude)
            else
                GeoPoint(37.4691, -113.6215)
            controller.setCenter(startCenter)
            // Mark initialized after 3 seconds to allow GPS center to settle
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // mapInitialized set via LaunchedEffect below
            }, 3000)
            // Mark initialized after 3 seconds to allow GPS center to settle
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // mapInitialized set via LaunchedEffect below
            }, 3000)
            setDestroyMode(false)
        }
    }

    // ── My location overlay ──────────────────────────────────────────────────
    val myLocationOverlay = remember(mapView) {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            enableMyLocation()
            // Draw a small arrowhead pointing up (OSMDroid rotates it with GPS heading)
            val sizePx = (ConvoyConfig.MARKER_SIZE_MEDIUM_DP * context.resources.displayMetrics.density).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(230, 33, 150, 243)
                style = android.graphics.Paint.Style.FILL
            }
            val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = sizePx * 0.1f
            }
            val cx = sizePx / 2f
            val path = android.graphics.Path().apply {
                moveTo(cx, 0f)                          // tip (north)
                lineTo(sizePx * 0.8f, sizePx * 0.9f)   // bottom right
                lineTo(cx, sizePx * 0.65f)              // inner notch
                lineTo(sizePx * 0.2f, sizePx * 0.9f)   // bottom left
                close()
            }
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
            setPersonIcon(bmp)
            setPersonAnchor(0.5f, 0.5f)
            // Do not enableFollowLocation() - we handle zoom manually per HUD mode
        }
    }

    // ── Attach renderer to map ────────────────────────────────────────────
    DisposableEffect(mapView) {
        renderer.attach(mapView)
        mapView.overlays.add(myLocationOverlay)
        onDispose {
            myLocationOverlay.disableMyLocation()
        }
    }

    // ── Lifecycle: pause/resume map ───────────────────────────────────────
    // Set mapInitialized after 3s so GPS center settles before auto-zoom kicks in
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        mapInitialized = true
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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

    Scaffold { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

        // ── Task 5.1: Real OSMDroid map ───────────────────────────────────
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                // Task 5.2: update markers + track on every recomposition tick
                renderer.update(convoyState.nodes, trackSegments)
                // Smart zoom based on HUD mode — skip first tick to allow GPS open
                if (convoyState.nodes.isNotEmpty() && mapInitialized) {
                    when (hudMode) {
                        HudMode.MY_CART -> {
                            // Zoom to MY CART (HOTEL-10)
                            val myCart = convoyState.nodes.firstOrNull { it.isMyCart }
                            myCart?.let {
                                mv.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                                mv.controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)
                            }
                        }
                        HudMode.NODE -> {
                            // Zoom to selected node
                            selectedNode?.let {
                                mv.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                                mv.controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)
                            }
                        }
                        else -> {
                            // GROUP / COLLAPSED — zoom to fit full convoy span (LEAD to TAIL)
                            val lead = convoyState.lead
                            val tail = convoyState.tail
                            val nodes = convoyState.nodes
                            when {
                                lead != null && tail != null && lead.nodeId != tail.nodeId -> {
                                    val points = listOf(
                                        GeoPoint(lead.latitude, lead.longitude),
                                        GeoPoint(tail.latitude, tail.longitude)
                                    )
                                    val box = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
                                    mv.zoomToBoundingBox(box.increaseByScale(ConvoyConfig.MAP_GROUP_ZOOM_PADDING), true)
                                }
                                nodes.size == 1 -> {
                                    // Single node — center on it at cart zoom
                                    mv.controller.animateTo(GeoPoint(nodes[0].latitude, nodes[0].longitude))
                                    mv.controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)
                                }
                                nodes.size >= 2 -> {
                                    val pts = nodes.map { GeoPoint(it.latitude, it.longitude) }
                                    val box = org.osmdroid.util.BoundingBox.fromGeoPoints(pts)
                                    mv.zoomToBoundingBox(box.increaseByScale(ConvoyConfig.MAP_GROUP_ZOOM_PADDING), true)
                                }
                            }
                        }
                    }
                }
            }
        )

        // ── REC button — upper left ──────────────────────────────────────────
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
                Column(modifier = Modifier.padding(8.dp).width(180.dp)) {
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
                                        mapView.setTileSource(src)
                                        mapView.invalidate()
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
                            onValueChangeFinished = { mapView.controller.setZoom(mapZoomLevel.toDouble()); mapView.invalidate() },
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
                                    mapView.post {
                                        mapView.setUseDataConnection(!it)
                                        mapView.invalidate()
                                    }
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

                        Spacer(Modifier.height(10.dp))

                        // ── Alert thresholds ─────────────────────────────
                        Text("ALERTS", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))

                        Text("SIGNAL DROP  ${signalDropMinutes.toInt()} min", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(value = signalDropMinutes, onValueChange = { signalDropMinutes = it },
                            valueRange = 1f..10f, steps = 8, modifier = Modifier.fillMaxWidth())

                        Text("LOST  ${lostMinutes.toInt()} min", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(value = lostMinutes, onValueChange = { lostMinutes = it },
                            valueRange = 5f..30f, steps = 4, modifier = Modifier.fillMaxWidth())

                        Text("OFF TRACK  ${"%.1f".format(offTrackMiles)} mi", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(value = offTrackMiles, onValueChange = { offTrackMiles = it },
                            valueRange = 0.1f..2f, steps = 18, modifier = Modifier.fillMaxWidth())
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
                    onModeChange = { viewModel.setHudMode(it) }
                )
                HudMode.MY_CART -> MyCartHud(
                    state = convoyState,
                    myCartId = viewModel.myCartId.collectAsStateWithLifecycle().value,
                    onModeChange = { viewModel.setHudMode(it) }
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
    onModeChange: (HudMode) -> Unit
) {
    HudCard {
        HudModeRow(current = HudMode.GROUP, onModeChange = onModeChange)
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
    onModeChange: (HudMode) -> Unit
) {
    val myCart = state.nodes.firstOrNull { it.isMyCart }
    HudCard {
        HudModeRow(current = HudMode.MY_CART, onModeChange = onModeChange)
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
fun HudModeRow(current: HudMode, onModeChange: (HudMode) -> Unit) {
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
    }
}
