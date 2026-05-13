package com.geeksville.mesh.convoy

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.graphicsLayer
import com.geeksville.mesh.ui.sharing.ChannelViewModel
import com.geeksville.mesh.model.UIViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.vectorResource
import org.meshtastic.core.resources.Res
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * ConvoyScreen — IMP-001 Task 4.2 + 5.1 + 5.2 + 5.3 + 5.4
 * Full-screen WebView/Leaflet map + HUD strip.
 */
enum class RecordingState { IDLE, RECORDING, PAUSED, SLEEPING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoyScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToCreateEvent: () -> Unit = {},
    onNavigateToSettingsPanel: () -> Unit = {},
    onNavigateToTrackExport: () -> Unit = {},
    onNavigateToTrackImport: () -> Unit = {},
    onNavigateToMapViewer: () -> Unit = {},
    viewModel: ConvoyViewModel = hiltViewModel()
) {
    val channelViewModel: ChannelViewModel = hiltViewModel()
    val uiViewModel: UIViewModel = hiltViewModel()
    val convoyState by viewModel.convoyState.collectAsStateWithLifecycle()
    val hudMode by viewModel.hudMode.collectAsStateWithLifecycle()
    val selectedNode by viewModel.selectedNode.collectAsStateWithLifecycle()
    val trackActive by viewModel.trackActive.collectAsStateWithLifecycle()
    val trackLeadOnly by viewModel.trackLeadOnly.collectAsStateWithLifecycle()
    val offTrackIds by viewModel.offTrackIds.collectAsStateWithLifecycle()
    val simulationMode by viewModel.simulationMode.collectAsStateWithLifecycle()
    val showLeadTrack by viewModel.showLeadTrack.collectAsStateWithLifecycle()
    var recordingState by viewModel.recordingState
    var showLocationPermissionDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var showStoragePermissionDialog by remember { mutableStateOf(!android.os.Environment.isExternalStorageManager()) }

    // FT-01 FIX: Check all-files access on EVERY resume (not just first composition)
    // Re-checks when user returns from Settings after Grant button, and on every app restart
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                showStoragePermissionDialog = true
            }
        }
        onPauseOrDispose { }
    }
    var pendingTrackName by viewModel.pendingTrackName
    val context = LocalContext.current
    MapSourceManager.init(context)

    val bgLocationLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            android.widget.Toast.makeText(context, "Location: Allow all the time — granted", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // Keep screen on while Convoy is active — prevents GPS dropout during recording
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var showLayerMenu by remember { mutableStateOf(false) }
    val mapTypeLabel by viewModel.mapTypeLabel.collectAsStateWithLifecycle()
    val isLocalTiles by viewModel.isLocalTiles.collectAsStateWithLifecycle()
    var trailsOn by remember { mutableStateOf(false) }
    var trailsLoaded by remember { mutableStateOf(false) }
    var tracksOn by remember { mutableStateOf(true) }
    var showConvoyTrackPicker by remember { mutableStateOf(false) }
    var convoyTrackFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var convoyLoadedTracks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var convoyTrackSearch by remember { mutableStateOf("") }
        var showMapSettings by remember { mutableStateOf(false) }
    var showConvoyMenu by remember { mutableStateOf(false) }
        var pendingImportNav by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var showImportSplash by remember { mutableStateOf(false) }
    val pendingImportBanner by viewModel.pendingImportBanner.collectAsStateWithLifecycle()
    val pendingDownload by viewModel.pendingDownload.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()

    // Show import splash when menu opens if there are pending imports

    val convoyMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mapZoomLevel by remember { mutableStateOf(18f) }
    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()
    var showDownloaded by remember { mutableStateOf(false) }
    var tracksVisible by remember { mutableStateOf(false) }
    var scanningDownloaded by remember { mutableStateOf(false) }
    val autoPan by viewModel.autoPan.collectAsStateWithLifecycle()
    var locationSearchQuery by remember { mutableStateOf("") }
    var locationSearchResults by remember { mutableStateOf<List<android.location.Address>>(emptyList()) }
    var locationSearchError by remember { mutableStateOf("") }
    var mapInitialized by remember { mutableStateOf(false) }
    var showRecMenu by viewModel.showRecMenu
    var showLeadDialog by remember { mutableStateOf(false) }

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
    val initialViewSet = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(convoyState) {
        if (initialViewSet.value) return@LaunchedEffect
        val wv = webViewRef.value ?: return@LaunchedEffect
        val validNodes = convoyState.nodes.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        if (validNodes.isNotEmpty()) {
            val lats = validNodes.joinToString(",") { it.latitude.toString() }
            val lons = validNodes.joinToString(",") { it.longitude.toString() }
            wv.evaluateJavascript("fitBounds([$lats], [$lons])", null)
            initialViewSet.value = true
        }
    }
    // FT-03 BOUNCE FIX: when recording starts, immediately center on user's cart
    // Eliminates the 1-3 second gap between RECORD press and next tick painting the cart
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            val wv = webViewRef.value ?: return@LaunchedEffect
            val myCartId = viewModel.myCartId.value
            val myCart = convoyState.nodes.firstOrNull { it.nodeId == myCartId }
            myCart?.let {
                if (it.latitude != 0.0 && it.longitude != 0.0) {
                    wv.evaluateJavascript("setView(${it.latitude}, ${it.longitude}, ${ConvoyConfig.MAP_CART_ZOOM})", null)
                }
            }
        }
    }

    LaunchedEffect(hudMode, selectedNode, mapReady, autoPan, if (autoPan) convoyState else null) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        if (!autoPan) return@LaunchedEffect
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
                    wv.evaluateJavascript("setView(${it.latitude}, ${it.longitude}, ${ConvoyConfig.MAP_CART_ZOOM})", null)
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

    // ── All-files storage permission dialog ─────────────────────────────
    if (showStoragePermissionDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStoragePermissionDialog = false },
            title = { Text("Storage Access Required") },
            text = { Text("GroupTrack needs file access to store map tiles and trail data for offline use on the trail. Tap Grant to open Settings and enable access.") },
            confirmButton = {
                TextButton(onClick = {
                    showStoragePermissionDialog = false
                    try {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + context.packageName)
                        )
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                        )
                        context.startActivity(intent)
                    }
                }) { Text("Grant") }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePermissionDialog = false }) { Text("Later") }
            }
        )
    }

    // ── Download size estimation dialogs ─────────────────────────────────
    pendingDownload?.let { pending ->
        if (!pending.withinCeiling) {
            AlertDialog(
                onDismissRequest = { viewModel.clearPendingDownload() },
                title = { Text("Area Too Large") },
                text = {
                    Text(
                        "Estimated ${String.format("%.0f", pending.sizeMB)} MB " +
                        "exceeds the 500 MB limit.\n\nReduce the selected area and try again."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearPendingDownload() }) {
                        Text("OK")
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { viewModel.clearPendingDownload() },
                title = { Text("Download Map Area?") },
                text = {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
                    ) {
                        val widthMi = run {
                            val dLon = Math.toRadians(pending.east - pending.west)
                            val lat = Math.toRadians((pending.north + pending.south) / 2.0)
                            3958.8 * Math.acos(Math.sin(lat).let { s -> s * s + Math.cos(lat).let { c -> c * c * Math.cos(dLon) } })
                        }
                        val heightMi = 3958.8 * Math.toRadians(pending.north - pending.south)
                        Text("${"%.1f".format(widthMi)} mi × ${"%.1f".format(heightMi)} mi")
                        Text("${pending.tileCount} tiles — ${"%.1f".format(pending.sizeMB)} MB estimated")
                        Text("Source: ${pending.sourceName.uppercase()}")
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.height(4.dp)
                        )
                        Text(
                            "This may take several minutes on a slow connection.",
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.startDownload(context, pending)
                        coroutineScope.launch { convoyMenuSheetState.hide() }
                        android.widget.Toast.makeText(context, "Downloading map tiles — keep app open", android.widget.Toast.LENGTH_LONG).show()
                    }) {
                        Text("DOWNLOAD")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.clearPendingDownload() }) {
                        Text("CANCEL")
                    }
                }
            )
        }
    }

    if (downloadState is ConvoyViewModel.DownloadState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDownload() },
            title = { Text("Download Error") },
            text = { Text((downloadState as ConvoyViewModel.DownloadState.Error).message) },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelDownload() }) { Text("OK") }
            }
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = {
                showNameDialog = false
                viewModel.finalizeTrack("convoy_track", context)
            },
            title = { Text("Save Track") },
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
                    val name = if (pendingTrackName.isNotBlank()) pendingTrackName.trim() else "convoy_track"
                    viewModel.finalizeTrack(name, context)
                }) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    viewModel.deleteTempTrack()
                }) { Text("SKIP") }
            }
        )
    }
    LaunchedEffect(downloadState) {
        if (downloadState is ConvoyViewModel.DownloadState.Complete) {
            val summary = (downloadState as ConvoyViewModel.DownloadState.Complete).summary
            android.widget.Toast.makeText(context, "Map download complete — ${summary.downloaded} tiles", android.widget.Toast.LENGTH_LONG).show()
            // Tile download complete — user controls online/offline via switch
        }
    }

    Scaffold { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

        // ── WebView/Leaflet map ───────────────────────────────────
        AndroidView(
            factory = { ctx ->
                val existing = viewModel.persistentWebView
                if (existing != null) {
                    existing.addJavascriptInterface(object : Any() {
                        @android.webkit.JavascriptInterface
                        fun onMarkerTapped(nodeId: String) {
                            val node = viewModel.convoyState.value.nodes.firstOrNull { it.nodeId == nodeId }
                            if (node != null) viewModel.onMarkerTapped(node)
                        }

                        @android.webkit.JavascriptInterface
                        fun onAreaSelected(north: Double, south: Double, east: Double, west: Double) {
                            android.util.Log.i("ConvoyDownload", "onAreaSelected N=$north S=$south E=$east W=$west zoom=${ConvoyConfig.DOWNLOAD_ZOOM_MIN}-${ConvoyConfig.DOWNLOAD_ZOOM}")
                            val estimate = ConvoyTileCalculator.quickEstimate(north, south, east, west)
                            android.util.Log.i("ConvoyDownload", "estimate tiles=${estimate.tileCount} mb=${estimate.estimatedMB}")
                            val pending = ConvoyViewModel.PendingDownload(
                                tileCount     = estimate.tileCount,
                                sizeMB        = estimate.estimatedMB,
                                withinCeiling = estimate.withinCeiling,
                                north         = north,
                                south         = south,
                                east          = east,
                                west          = west,
                                sourceName    = ConvoyConfig.ACTIVE_TILE_SOURCE,
                                sourceUrl     = ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: ""
                            )
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                viewModel.setPendingDownload(pending)
                            }
                        }
                    }, "Android")
                    webViewRef.value = existing
                    existing
                } else {
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                val tileUrl = ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: return
                                view?.postDelayed({
                                    view.evaluateJavascript("setTileUrl('$tileUrl', '${ConvoyConfig.ACTIVE_TILE_SOURCE}')", null)
                                    // Trails loaded on demand via TRAILS button
                                    // Center map on device last known location
                                    try {
                                        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                                        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                        if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                                            android.util.Log.d("ConvoyMap", "Centering map on device GPS: ${loc.latitude}, ${loc.longitude}")
                                            view.evaluateJavascript("setView(${loc.latitude}, ${loc.longitude}, 15)", null)
                                        }
                                    } catch (e: SecurityException) {
                                        android.util.Log.w("ConvoyMap", "Location permission not granted — map stays at default view")
                                    }
                                }, 600)
                                mapReady++
                            }
                            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                if (url.startsWith("convoy://tiles/")) {
                                    val tilePath = url.removePrefix("convoy://tiles/")
                                    val file = java.io.File(ConvoyConfig.TILE_DIR, tilePath)
                                    android.util.Log.d("ConvoyIntercept", "TILE exists=${file.exists()} path=${file.absolutePath}")
                                    if (file.exists()) return android.webkit.WebResourceResponse("image/png", "utf-8", file.inputStream())
                                }
                                // Intercept Esri label tiles for offline serving
                                // Esri URL is tile/z/y/x but local storage is source/z/x/y.png
                                if (url.contains("/Reference/World_Transportation/MapServer/tile/")) {
                                    val parts = url.split("/tile/").lastOrNull()?.split("/")
                                    if (parts != null && parts.size >= 3) {
                                        val file = java.io.File(ConvoyConfig.TILE_DIR, "SAT_LABELS_TRANSPORT/${parts[0]}/${parts[2]}/${parts[1]}.png")
                                        if (file.exists()) {
                                            return android.webkit.WebResourceResponse("image/png", null, java.io.FileInputStream(file))
                                        }
                                    }
                                }
                                if (url.contains("/Reference/World_Boundaries_and_Places/MapServer/tile/")) {
                                    val parts = url.split("/tile/").lastOrNull()?.split("/")
                                    if (parts != null && parts.size >= 3) {
                                        val file = java.io.File(ConvoyConfig.TILE_DIR, "SAT_LABELS_PLACES/${parts[0]}/${parts[2]}/${parts[1]}.png")
                                        if (file.exists()) {
                                            return android.webkit.WebResourceResponse("image/png", null, java.io.FileInputStream(file))
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.d("ConvoyJS", "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                                return true
                            }
                        }
                        // One-time tile migration: old package dir → shared Documents
                        ConvoyConfig.migrateTiles(ctx)
                        loadUrl("file:///android_asset/convoy_map.html")
                        addJavascriptInterface(object : Any() {
                            @android.webkit.JavascriptInterface
                            fun onMarkerTapped(nodeId: String) {
                                val node = viewModel.convoyState.value.nodes.firstOrNull { it.nodeId == nodeId }
                                if (node != null) viewModel.onMarkerTapped(node)
                            }

                            @android.webkit.JavascriptInterface
                            fun onAreaSelected(north: Double, south: Double, east: Double, west: Double) {
                                android.util.Log.i("ConvoyDownload", "onAreaSelected N=$north S=$south E=$east W=$west zoom=${ConvoyConfig.DOWNLOAD_ZOOM_MIN}-${ConvoyConfig.DOWNLOAD_ZOOM}")
                            val estimate = ConvoyTileCalculator.quickEstimate(north, south, east, west)
                            android.util.Log.i("ConvoyDownload", "estimate tiles=${estimate.tileCount} mb=${estimate.estimatedMB}")
                                val pending = ConvoyViewModel.PendingDownload(
                                    tileCount     = estimate.tileCount,
                                    sizeMB        = estimate.estimatedMB,
                                    withinCeiling = estimate.withinCeiling,
                                    north         = north,
                                    south         = south,
                                    east          = east,
                                    west          = west,
                                    sourceName    = ConvoyConfig.ACTIVE_TILE_SOURCE,
                                    sourceUrl     = ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: ""
                                )
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    viewModel.setPendingDownload(pending)
                                }
                            }
                            @android.webkit.JavascriptInterface
                            fun onMapBoundsReady(north: Double, south: Double, east: Double, west: Double) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    val wv = webViewRef.value ?: return@post
                                    if (!ConvoyConfig.SHOW_DOWNLOADED_ON_OPEN) return@post
                                    val tilesDir = java.io.File(ConvoyConfig.TILE_DIR, "SAT/18")
                                    Thread {
                                        val bounds = mutableListOf<String>()
                                        if (tilesDir.exists()) {
                                            val z = 18
                                            val n = 1 shl z
                                            // Find perimeter of downloaded region from min/max tile coords
                                            // One bounding rectangle — accurate at all zoom levels
                                            var xMin = Long.MAX_VALUE; var xMax = Long.MIN_VALUE
                                            var yMin = Long.MAX_VALUE; var yMax = Long.MIN_VALUE
                                            tilesDir.listFiles()?.forEach { xDir: java.io.File ->
                                                val x = xDir.name.toLongOrNull() ?: return@forEach
                                                xDir.listFiles()?.forEach { yFile: java.io.File ->
                                                    val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                                    if (x < xMin) xMin = x; if (x > xMax) xMax = x
                                                    if (y < yMin) yMin = y; if (y > yMax) yMax = y
                                                }
                                            }
                                            if (xMin != Long.MAX_VALUE) {
                                                val tileN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * yMin / n))))
                                                val tileS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (yMax + 1) / n))))
                                                val tileW = xMin.toDouble() / n * 360.0 - 180.0
                                                val tileE = (xMax + 1).toDouble() / n * 360.0 - 180.0
                                                bounds.add("{\"n\":$tileN,\"s\":$tileS,\"e\":$tileE,\"w\":$tileW}")
                                            }
                                        }
                                        val json = "[${bounds.joinToString(",")}]"
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            wv.evaluateJavascript("showDownloadedAreas($json)", null)
                                        }
                                    }.start()
                                }
                            }
                        }, "Android")
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
                view.setOnTouchListener { v, event ->
                    if (event.action == android.view.MotionEvent.ACTION_MOVE ||
                        event.action == android.view.MotionEvent.ACTION_POINTER_DOWN ||
                        event.action == android.view.MotionEvent.ACTION_UP) { viewModel.setAutoPan(false) }
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        val x = event.x.toInt()
                        val y = event.y.toInt()
                        android.util.Log.i("ConvoyTap", "Touch UP at x=$x y=$y")
                        view.evaluateJavascript("findNearestMarker($x, $y)") { result ->
                            android.util.Log.i("ConvoyTap", "findNearestMarker result=$result")
                            val nodeId = result?.trim('"') ?: ""
                            if (nodeId.isNotEmpty()) {
                                val node = viewModel.convoyState.value.nodes.firstOrNull { it.nodeId == nodeId }
                                android.util.Log.i("ConvoyTap", "Node found: $node")
                                if (node != null) viewModel.onMarkerTapped(node)
                            }
                        }
                    }
                    false
                }
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
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        id = com.geeksville.mesh.R.drawable.grouptrack_splash
                    ),
                    contentDescription = "GroupTrack",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (showLocationPermissionDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showLocationPermissionDialog = false },
                title = { androidx.compose.material3.Text("Location Permission Required",
                    color = androidx.compose.ui.graphics.Color.White) },
                text = { androidx.compose.material3.Text(
                    "GPS track recording requires \"Allow all the time\" location access.\n\nTap SETTINGS then Location > Allow all the time.",
                    color = androidx.compose.ui.graphics.Color(0xFFAABBCC)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showLocationPermissionDialog = false
                        bgLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }) { androidx.compose.material3.Text("SETTINGS", color = androidx.compose.ui.graphics.Color(0xFF4AB8E8)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showLocationPermissionDialog = false }) {
                        androidx.compose.material3.Text("CANCEL", color = androidx.compose.ui.graphics.Color(0xFF445566))
                    }
                },
                containerColor = androidx.compose.ui.graphics.Color(0xFF0F2035)
            )
        }
        // -- Lead Selection Dialog --
        if (showLeadDialog) {
            val dialogNodes = convoyState.nodes
            AlertDialog(
                onDismissRequest = { showLeadDialog = false },
                title = {
                    androidx.compose.material3.Text(
                        "Select Lead Cart",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        androidx.compose.material3.Text(
                            "Tap the lead cart to start the ride:",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        dialogNodes.forEach { node ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setLeadCart(node.nodeId)
                                        showLeadDialog = false
                                        recordingState = RecordingState.RECORDING
                                        viewModel.startRecording(context)
                                        viewModel.startGroupTrack()
                                        android.widget.Toast.makeText(
                                            context,
                                            node.callsign + " set as Lead Cart",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF2A3040),
                                shadowElevation = 2.dp
                            ) {
                                Text(
                                    node.callsign,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showLeadDialog = false }) {
                        Text("CANCEL")
                    }
                },
                containerColor = Color(0xFF1E252F)
            )
        }

        Box(modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            if (!showRecMenu) {
                // Main REC button
                Surface(
                    modifier = Modifier.clickable {
                        when (recordingState) {
                            RecordingState.IDLE -> {
                                val bgGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (bgGranted) {
                                    if (viewModel.leadLocked.value) {
                                        // Lead already set via SET AS LEAD
                                        recordingState = RecordingState.RECORDING
                                        viewModel.startRecording(context)
                                        viewModel.startGroupTrack()
                                    } else {
                                        val meshNodes = viewModel.convoyState.value.nodes
                                        if (meshNodes.size <= 1) {
                                            // Solo/standalone -- auto-assign and go
                                            val soloNode = meshNodes.firstOrNull()
                                            viewModel.setLeadCart(soloNode?.nodeId ?: "!phone")
                                            recordingState = RecordingState.RECORDING
                                            viewModel.startRecording(context)
                                            viewModel.startGroupTrack()
                                        } else {
                                            // Multiple carts -- show lead selection dialog
                                            showLeadDialog = true
                                        }
                                    }
                                } else {
                                    showLocationPermissionDialog = true
                                }
                            }
                            RecordingState.RECORDING -> { showRecMenu = true }
                            RecordingState.PAUSED -> showRecMenu = true
                            RecordingState.SLEEPING -> { /* overlay handles wake */ }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = when (recordingState) {
                        RecordingState.IDLE -> Color(0xFF8B0000)
                        RecordingState.RECORDING -> Color(0xFFCC0000)
                        RecordingState.PAUSED -> Color(0xFF994400)
                        RecordingState.SLEEPING -> Color(0xFFCC8800)
                    },
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = when (recordingState) {
                            RecordingState.IDLE -> "⏺  REC"
                            RecordingState.RECORDING -> "⏸  PAUSE"
                            RecordingState.PAUSED -> "⏺  RESUME"
                            RecordingState.SLEEPING -> "ZZZ  ASLEEP"
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
                            showNameDialog = true
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


          // ── Distance odometer -- bottom right, only when recording ─────
          val distanceMiles by viewModel.distanceMiles.collectAsStateWithLifecycle()
          if (recordingState != RecordingState.IDLE) {
              Column(
                  modifier = Modifier
                      .align(Alignment.BottomEnd)
                      .padding(end = 16.dp, bottom = 64.dp),
                  horizontalAlignment = androidx.compose.ui.Alignment.End
              ) {
                  Text("Distance", color = Color(0xFFFF0000).copy(alpha = 0.75f), fontSize = 11.sp,
                      fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                      letterSpacing = 1.sp)
                  Row(verticalAlignment = Alignment.Bottom) {
                      Text("%.2f".format(distanceMiles), color = Color(0xFFFF0000).copy(alpha = 0.75f),
                          fontSize = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                          lineHeight = 48.sp)
                      Text(" mi", color = Color(0xFFFF0000).copy(alpha = 0.75f), fontSize = 16.sp,
                          fontFamily = FontFamily.Monospace,
                          modifier = Modifier.padding(bottom = 6.dp))
                  }
              }
          }

          // -- SLEEPING overlay -- BLE disconnect on sleep, reconnect on wake --
          var savedDeviceAddress by remember { mutableStateOf("") }

          // Auto-disconnect BLE when sleep triggers
          LaunchedEffect(recordingState) {
              if (recordingState == RecordingState.SLEEPING) {
                  savedDeviceAddress = uiViewModel.getDeviceAddress() ?: ""
                  uiViewModel.setDeviceAddress("n")
                  android.util.Log.i("ConvoyScreen", "SLEEP: BLE disconnected")
              }
          }

          if (recordingState == RecordingState.SLEEPING) {
              val infiniteTransition = rememberInfiniteTransition(label = "sleep")
              val alpha by infiniteTransition.animateFloat(
                  initialValue = 0.3f, targetValue = 1.0f,
                  animationSpec = infiniteRepeatable(
                      animation = tween(800), repeatMode = RepeatMode.Reverse
                  ), label = "sleepAlpha"
              )
              Surface(
                  modifier = Modifier.align(Alignment.Center)
                      .clickable {
                          coroutineScope.launch {
                              uiViewModel.setDeviceAddress(savedDeviceAddress)
                              viewModel.wakeFromSleep(context)
                              recordingState = RecordingState.RECORDING
                              android.util.Log.i("ConvoyScreen", "WAKE: BLE reconnecting")
                          }
                      },
                  shape = RoundedCornerShape(16.dp),
                  color = Color(0xFFCC8800).copy(alpha = alpha),
                  shadowElevation = 8.dp
              ) {
                  Column(
                      horizontalAlignment = Alignment.CenterHorizontally,
                      modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
                  ) {
                      Text("Track Recording Asleep",
                          color = Color.White, fontSize = 18.sp,
                          fontWeight = FontWeight.Bold)
                      Spacer(modifier = Modifier.height(8.dp))
                      Text("Press to Resume",
                          color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                  }
              }
          }
        // ── Task 5.3: Show Lead Track toggle + Task 5.4: Route Recorder ──
        // -- FIXED SOURCE BAR --
        ConvoyMapBar(
            navLabel = "PLAN",
            onNavigate = onNavigateToMapViewer,
            activeSource = mapTypeLabel,
            isOffline = isOfflineMode,
            onSourceChange = { label ->
                viewModel.setMapTypeLabel(label)
                ConvoyConfig.ACTIVE_TILE_SOURCE = label
                val url = if (isLocalTiles)
                    ConvoyConfig.LOCAL_TILE_BASE + label + "/{z}/{x}/{y}.png"
                else
                    MapSourceManager.getSlotSources().find { it.first == label }?.third ?: ""
                viewModel.setLocalTiles(isLocalTiles)
                webViewRef.value?.evaluateJavascript("setTileUrl('$url', '$label')", null)
            },
            onOfflineToggle = { goOffline ->
                viewModel.setOfflineMode(goOffline)
                val url = if (goOffline)
                    ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"
                else
                    ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: ""
                webViewRef.value?.evaluateJavascript("setTileUrl('$url', '${ConvoyConfig.ACTIVE_TILE_SOURCE}')", null)
            },
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 80.dp, end = 8.dp, top = 8.dp)
                .fillMaxWidth()
                
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 52.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
                // -- MAP BAR + DISPLAY PANEL (rebuilt) --
                ConvoyDisplayPanel(
                    tracksOn = tracksVisible,
                    onTracksToggle = {
                        tracksVisible = !tracksVisible
                        android.util.Log.i("ConvoyDisplay", "toggleTracks called, webViewRef=" + (webViewRef.value != null))
                        webViewRef.value?.evaluateJavascript("toggleTracks()", null)

                    },
                    trailsOn = trailsOn,
                    onTrailsToggle = {
                        trailsOn = !trailsOn
                        if (trailsOn && !trailsLoaded) {
                            trailsLoaded = true
                            Thread {
                                try {
                                    val json = context.assets.open("utah_trails_stgeorge.geojson").bufferedReader().use { it.readText() }
                                    webViewRef.value?.post {
                                        webViewRef.value?.evaluateJavascript("loadTrails($json); showTrails();", null)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ConvoyMap", "Trail load error: ${e.message}")
                                }
                            }.start()
                        } else {
                            android.util.Log.i("ConvoyDisplay", "toggleTrails called, webViewRef=" + (webViewRef.value != null))
                        webViewRef.value?.evaluateJavascript("toggleTrails()", null)
                        }
                    },
                    downloadedOn = showDownloaded,
                    onDownloadedToggle = {
                        if (showDownloaded) {
                            showDownloaded = false
                            webViewRef.value?.evaluateJavascript("clearDownloadedAreas()", null)
                        } else {
                            if (scanningDownloaded) { /* already scanning */ } else {
                            scanningDownloaded = true
                            val wv = webViewRef.value
                            if (wv != null) {
                            val tilesDir = java.io.File(ConvoyConfig.TILE_DIR, "SAT/14")
                            Thread {
                                val bounds = mutableListOf<String>()
                                if (tilesDir.exists()) {
                                    val z = 14; val n = 1 shl z
                                    tilesDir.listFiles()?.forEach { xDir: java.io.File ->
                                        val x = xDir.name.toLongOrNull() ?: return@forEach
                                        xDir.listFiles()?.forEach { yFile: java.io.File ->
                                            val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                            val tN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n))))
                                            val tS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / n))))
                                            val tW = x.toDouble() / n * 360.0 - 180.0
                                            val tE = (x + 1).toDouble() / n * 360.0 - 180.0
                                            bounds.add("{\"n\":$tN,\"s\":$tS,\"e\":$tE,\"w\":$tW}")
                                        }
                                    }
                                }
                                val json = "[" + bounds.joinToString(",") + "]"
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    wv.evaluateJavascript("showDownloadedAreas($json)", null)
                                    showDownloaded = true
                                    scanningDownloaded = false
                                }
                            }.start()
                            } // wv != null
                        } // not scanning
                        }
                    },
                    scanningDownloaded = scanningDownloaded
                )


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
        // Import splash — shown for 3 seconds when new rides have been imported
        if (showConvoyMenu && showImportSplash && pendingImportBanner != null) {
            androidx.compose.ui.window.Dialog(onDismissRequest = {
                showImportSplash = false
                viewModel.clearImportBanner()
            }) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = androidx.compose.ui.graphics.Color(0xFF0D2010)
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.padding(32.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.Text("✓",
                            color = androidx.compose.ui.graphics.Color(0xFF97D5A5),
                            fontSize = 48.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                        androidx.compose.material3.Text(pendingImportBanner!!,
                            color = androidx.compose.ui.graphics.Color(0xFF97D5A5),
                            fontSize = 14.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
                if (showConvoyMenu) {
            ConvoySubMenu(
                sheetState                = convoyMenuSheetState,
                onDismiss                 = { showConvoyMenu = false },
                onCreateEventRide         = { showConvoyMenu = false },
                onTransferConfig          = { showConvoyMenu = false },
                onNavigateToCreateEvent   = onNavigateToCreateEvent,
                onNavigateToSettingsPanel = onNavigateToSettingsPanel,
                onNavigateToTrackExport   = onNavigateToTrackExport,
                onNavigateToTrackImport   = { showConvoyMenu = false; pendingImportNav = true },
                onNavigateToMapViewer     = onNavigateToMapViewer,

            )
        }

        androidx.compose.runtime.LaunchedEffect(pendingImportNav) {
            if (pendingImportNav) { pendingImportNav = false; onNavigateToTrackImport() }
        }
        // ── Button bar ────────────────────────────────────────────────────
        ConvoyButtonBar(
            hudMode = hudMode,
            onModeChange = { viewModel.setHudMode(it); viewModel.setAutoPan(true) },
            onNavigateToSettings = onNavigateToSettings,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        val avgChannelUtil by viewModel.avgChannelUtil.collectAsStateWithLifecycle()
        val currentIntervalSecs by viewModel.currentIntervalSecs.collectAsStateWithLifecycle()
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
                    onToggleLeadOnly = { viewModel.toggleLeadOnly() },
                        avgChannelUtil = avgChannelUtil,
                        currentIntervalSecs = currentIntervalSecs,
                        onIntervalChange = { secs -> viewModel.setGpsInterval(secs, channelViewModel) },
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
                        onDismiss = { viewModel.dismissNodeHud() },
                        onRemove = { n ->
                            viewModel.removeNode(n.nodeId)
                            webViewRef.value?.evaluateJavascript("removeMarker('${n.nodeId}')", null)
                        },
                        onSetLead = { n ->
                            viewModel.setLeadCart(n.nodeId)
                            android.widget.Toast.makeText(
                                context,
                                n.callsign + " set as Lead Cart",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
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
    onToggleLeadOnly: () -> Unit = {},
    avgChannelUtil: Float = 0f,
    currentIntervalSecs: Int = 5,
    onIntervalChange: (Int) -> Unit = {}
) {
    val chColor = when {
        avgChannelUtil > 40f -> Color(0xFFFF4444)
        avgChannelUtil > 25f -> Color(0xFFFFAA00)
        else                 -> Color(0xFF00CC44)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        // Vertical interval slider — flush against HudCard
        Column(
            modifier = Modifier.padding(0.dp).offset(x = (-12).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("${currentIntervalSecs}s", color = Color(0xFF111111), fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 6f)))
            androidx.compose.material3.Slider(
                value = currentIntervalSecs.toFloat(),
                onValueChange = { onIntervalChange(it.toInt()) },
                valueRange = 2f..8f,
                steps = 5,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF2E75B6),
                    inactiveTrackColor = Color(0xFFFFFFFF).copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .height(80.dp)
                    .graphicsLayer { rotationZ = -90f }
                    .width(80.dp)
            )
            Text("INT", color = Color(0xFF111111), fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 6f)))
        }
        HudCard {
            Text("GROUP", color = Color(0xFF111111), fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 4.dp),
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
            // Row 1: SPAN big + CH% color block
            Row(verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("SPAN", color = Color(0xFF111111), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        modifier = Modifier.padding(end = 4.dp, bottom = 6.dp),
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
                    Text("%.1f".format(state.span_miles),
                        color = Color(0xFF111111),
                        fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 10f)))
                    Text(" mi", color = Color(0xFF111111), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp),
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 6.dp)) {
                    Text("CH%", color = Color(0xFF111111), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(modifier = Modifier.size(8.dp).background(chColor,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                        Text("%.0f%%".format(avgChannelUtil), color = chColor,
                            fontSize = 13.sp, fontWeight = FontWeight.Black,
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // Row 2: Carts · Active · Lost · Lead · Tail
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom) {
                HudStat("Carts",       "${state.nodes.size}")
                HudStat("Active", "${state.activeCount}", Color(0xFF00CC44))
                HudStat("Lost", "${state.lostCount}", Color(0xFFFF4444))
                HudStat("▲ Lead", state.lead?.callsign ?: "--", Color(0xFF1CF0A0))
                HudStat("▽ Tail", state.tail?.callsign ?: "--", Color(0xFFFF8C42))
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
        Text("My Cart  ★ ${myCart?.callsign ?: myCartId.takeLast(8)}", color = Color(0xFF111111), fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 6.dp),
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
        if (myCart == null) {
            Text("MY CART not found", color = Color(0xFF111111), fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
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
                    Text("Speed", color = Color(0xFF111111), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.0f".format(myCart.speed_mph), color = Color(0xFF111111),
                            fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp,
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 10f)))
                        Text(" mph", color = Color(0xFF111111), fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp),
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
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
    onDismiss: () -> Unit,
    onRemove: (ConvoyNode) -> Unit = {},
    onSetLead: (ConvoyNode) -> Unit = {}
) {
    HudCard {
        // Title — cart callsign
        Text(node.callsign, color = Color(0xFF111111), fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 6.dp),
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HudStat("STATUS", node.status.name,
                when (node.status) {
                    ConvoyStatus.LOST        -> Color(0xFFF44336)
                    ConvoyStatus.SIGNAL_DROP -> Color(0xFFFFFF00)
                    ConvoyStatus.ACTIVE      -> Color(0xFF00CC44)
                })
            HudStat("SPD", "%.0f mph".format(node.speed_mph))
            HudStat("BAT", "${node.battery_pct}%")
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HudStat("POS", "#${node.convoyPosition}")
            HudStat("HDG", "%.0f°".format(node.heading_deg))
            HudStat("ALT", "${node.altitude_m}m")
            HudStat("SEEN", node.lastSeenAgo)
        }
        Spacer(Modifier.height(8.dp))
        // SET AS LEAD -- local fallback for manual correction
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.clickable { onSetLead(node); onDismiss() },
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF006633)
            ) {
                Text("SET AS LEAD", color = Color.White, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.clickable { onRemove(node); onDismiss() },
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF8B0000)
            ) {
                Text("REMOVE FROM RIDE", color = Color.White, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
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
            .padding(start = 0.dp, bottom = 12.dp),
        content = content
    )
}

@Composable
fun HudStat(label: String, value: String, valueColor: Color = Color(0xFF111111)) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = Color(0xFF111111), fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
        Text(value, color = valueColor, fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = androidx.compose.ui.graphics.Color.White, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 8f)))
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

// ── Convoy track file helpers ─────────────────────────────────────
fun convoyListTracks(context: android.content.Context): List<String> {
    val dir = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS
        ), "my_tracks"
    )
    if (!dir.exists()) return emptyList()
    return dir.listFiles()
        ?.filter { f ->
            val ext = f.extension.lowercase()
            (ext == "kml" || ext == "gpx") &&
            !f.name.startsWith(".") &&
            !f.name.startsWith("convoy_track_temp")
        }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.name }
        ?: emptyList()
}

/** IO-safe: reads file and parses coords, returns JSON string or null. No WebView call. */
fun convoyLoadTrackData(
    context: android.content.Context,
    fileName: String
): String? {
    return try {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ), "my_tracks"
        )
        val file = java.io.File(dir, fileName)
        if (!file.exists()) return null
        val text = file.readText()
        val coords = if (fileName.lowercase().endsWith(".gpx")) convoyParseGpx(text) else convoyParseKml(text)
        if (coords.isEmpty()) return null
        coords.joinToString(",", "[", "]") { p -> "[${p.first},${p.second}]" }
    } catch (e: Exception) {
        null
    }
}

fun convoyLoadTrack(
    context: android.content.Context,
    fileName: String,
    color: String,
    webView: android.webkit.WebView?
) {
    try {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ), "my_tracks"
        )
        val file = java.io.File(dir, fileName)
        if (!file.exists()) return
        val text = file.readText()
        val coords = if (fileName.lowercase().endsWith(".gpx")) convoyParseGpx(text) else convoyParseKml(text)
        if (coords.isEmpty()) return
        val json = coords.joinToString(",", "[", "]") { p -> "[${p.first},${p.second}]" }
        val safe = fileName.replace("'", "\\'")
        webView?.evaluateJavascript("loadTrackFile('$safe', '$json', '$color')", null)
    } catch (e: Exception) {
        android.util.Log.e("ConvoyTracks", "Load error $fileName: ${e.message}")
    }
}

fun convoyParseKml(text: String): List<Pair<Double, Double>> {
    val coords = mutableListOf<Pair<Double, Double>>()
    val pattern = Regex("""<coordinates>([\s\S]*?)</coordinates>""")
    pattern.findAll(text).forEach { match ->
        match.groupValues[1].trim().lines().forEach { line ->
            val parts = line.trim().split(",")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lon != null && lat != null && lat != 0.0 && lon != 0.0) {
                    coords.add(Pair(lat, lon))
                }
            }
        }
    }
    return coords
}

fun convoyParseGpx(text: String): List<Pair<Double, Double>> {
    val coords = mutableListOf<Pair<Double, Double>>()
    val pattern = Regex("""<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)"""")
    pattern.findAll(text).forEach { match ->
        val lat = match.groupValues[1].toDoubleOrNull()
        val lon = match.groupValues[2].toDoubleOrNull()
        if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
            coords.add(Pair(lat, lon))
        }
    }
    return coords
}
