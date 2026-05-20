package com.geeksville.mesh.convoy

import android.annotation.SuppressLint
import android.location.Geocoder
import android.webkit.WebView
import android.webkit.JavascriptInterface
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box

/**
 * Standalone map viewer with trail overlays and track display.
 * V2.4 -- independent from convoy map. Uses grouptrack_map.html.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConvoyMapViewerScreen(
    onBack: () -> Unit,
    onNavigateToTrackExport: () -> Unit = {},
    onNavigateToTrackImport: () -> Unit = {},
    onNavigateToTrailSources: () -> Unit = {},
    convoyViewModel: ConvoyViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Clean up WebView when leaving Planning Map
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }
    var activeSource by remember { mutableStateOf("SAT") }
    var trailsOn by remember { mutableStateOf(false) }
    var trailsLoaded by remember { mutableStateOf(false) }
    var showTrackPanel by remember { mutableStateOf(false) }
    var showTrackMenu by remember { mutableStateOf(false) }
    var trackFileList by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadedTracks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var trackSearchText by remember { mutableStateOf("") }
    val trackColors = listOf("#39FF14")
    var nextColorIdx by remember { mutableIntStateOf(0) }
    // Action menu state — for rename/delete/share/move/fix-date
    var actionTarget by remember { mutableStateOf<java.io.File?>(null) }
    var renameTarget by remember { mutableStateOf<java.io.File?>(null) }
    var deleteTarget by remember { mutableStateOf<java.io.File?>(null) }
    var actionStatusMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val refreshTracks: () -> Unit = {
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            val files = scanTrackDir(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { trackFileList = files }
        }
    }
    val unloadIfLoaded: (String) -> Unit = { trackName ->
        if (loadedTracks.any { it.first == trackName }) {
            val safe = trackName.replace("'", "\\'")
            webViewRef?.evaluateJavascript("removeTrackFile('$safe')", null)
            loadedTracks = loadedTracks.filterNot { it.first == trackName }
        }
    }
    var searchText by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // Download controls state
    var showDownloadPanel by remember { mutableStateOf(false) }
    var queueExpanded by remember { mutableStateOf(false) }
    var pmTracksOn by remember { mutableStateOf(false) }
    var pmTracksLoaded by remember { mutableStateOf(false) }
    var pmTrailsOn by remember { mutableStateOf(false) }
    var pmQueuesOpen by remember { mutableStateOf(false) }
    var pmDownloadedOn by remember { mutableStateOf(false) }
    var pmActiveSource by remember { mutableStateOf(ConvoyConfig.ACTIVE_TILE_SOURCE) }
    var mapZoomLevel by remember { mutableStateOf(ConvoyConfig.DOWNLOAD_ZOOM.toFloat()) }
    var showDownloaded by remember { mutableStateOf(false) }
    var scanningDownloaded by remember { mutableStateOf(false) }
    var fabOffsetX by remember { mutableStateOf(0f) }
    var fabOffsetY by remember { mutableStateOf(0f) }
    var downloadBbox by remember { mutableStateOf(DownloadBbox()) }
    var isDrawingArea by remember { mutableStateOf(false) }
    val downloadState by convoyViewModel.downloadState.collectAsState()

    // Tile sources from map_sources.json — single source of truth
    MapSourceManager.init(context)
    val tileSources = MapSourceManager.getSlotSources()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E14))) {
        // -- Header --
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF131820))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "BACK",
                color = Color(0xFF4DA6FF),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }.padding(8.dp)
            )
            Text(
                "PLANNING MAP",
                color = Color(0xFFE8EEF5),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            // TRACKS + QUEUES (grouped right)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // TRACKS button (opens track picker)
                Surface(
                    modifier = Modifier.clickable {
                            showTrackPanel = showTrackPanel.not()
                            if (showTrackPanel) refreshTracks()
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = if (showTrackPanel) Color(0xFF39FF14).copy(alpha = 0.2f) else Color(0xFF2A3545)
                ) {
                    Text("TRACKS",
                        color = if (showTrackPanel) Color(0xFF39FF14) else Color(0xFF7A8DA0),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                // QUEUES — V2.5 scaffold launch point
                Surface(
                    modifier = Modifier.clickable { pmQueuesOpen = !pmQueuesOpen },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2A3545)
                ) {
                    Text("QUEUES",
                        color = Color(0xFF1CF0A0),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }

        // -- Search bar (collapsible) --
        if (showSearch) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2030))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(Color(0xFF4DA6FF)),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0A1020), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { innerTextField ->
                        if (searchText.isEmpty()) {
                            Text("City, park, trail area...", color = Color(0xFF445566),
                                fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        }
                        innerTextField()
                    }
                )
                Surface(
                    modifier = Modifier.clickable {
                        if (searchText.isNotBlank()) {
                            coroutineScope.launch {
                                try {
                                    val results = withContext(Dispatchers.IO) {
                                        @Suppress("DEPRECATION")
                                        Geocoder(context).getFromLocationName(searchText, 5)
                                    }
                                    if (!results.isNullOrEmpty()) {
                                        val loc = results[0]
                                        webViewRef?.evaluateJavascript(
                                            "setView(" + loc.latitude + ", " + loc.longitude + ", 13)", null
                                        )
                                        webViewRef?.evaluateJavascript(
                                            "showSearchCenter(" + loc.latitude + ", " + loc.longitude + ")", null
                                        )
                                    } else {
                                        android.widget.Toast.makeText(context,
                                            "Location not found. Try adding state (e.g. Zion UT)",
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context,
                                        "Search error",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2E75B6)
                ) {
                    Text("FIND", color = Color.White, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }

        // -- Track panel --
        if (showTrackPanel) {
            val filtered = if (trackSearchText.isBlank()) trackFileList
                else trackFileList.filter { it.contains(trackSearchText, ignoreCase = true) }
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2030))
                    .heightIn(max = 220.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // Search
                BasicTextField(
                    value = trackSearchText,
                    onValueChange = { trackSearchText = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(Color(0xFF39FF14)),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF0A1020), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    decorationBox = { inner ->
                        if (trackSearchText.isEmpty()) Text("Search tracks...", color = Color(0xFF445566), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        inner()
                    }
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${filtered.size} of ${trackFileList.size} tracks",
                        color = Color(0xFF4A6080), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FIT", color = Color(0xFF4DA6FF), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { webViewRef?.evaluateJavascript("fitAllTrackFiles()", null) }.padding(4.dp))
                        Text("CLEAR", color = Color(0xFFFF4444), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                webViewRef?.evaluateJavascript("clearAllTrackFiles()", null)
                                loadedTracks = emptyList(); nextColorIdx = 0
                            }.padding(4.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtered.size) { idx ->
                        val name = filtered[idx]
                        val loaded = loadedTracks.any { it.first == name }
                        val displayColor = loadedTracks.firstOrNull { it.first == name }?.second
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).clickable {
                                    if (loaded) {
                                        val safe = name.replace("'", "\\'")
                                        webViewRef?.evaluateJavascript("removeTrackFile('$safe')", null)
                                        loadedTracks = loadedTracks.filterNot { it.first == name }
                                    } else {
                                        val color = trackColors[nextColorIdx % trackColors.size]
                                        nextColorIdx++
                                        loadTrackOnMap(context, name, color, webViewRef)
                                        loadedTracks = loadedTracks + Pair(name, color)
                                    }
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(10.dp),
                                    shape = RoundedCornerShape(2.dp),
                                    color = if (loaded) Color(android.graphics.Color.parseColor(displayColor ?: "#666666")) else Color(0xFF2A3040)
                                ) {}
                                Spacer(Modifier.width(8.dp))
                                Text(name, color = if (loaded) Color.White else Color(0xFF667788),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                            Text("\u22EE", color = Color(0xFF7A8DA0), fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable {
                                        actionTarget = java.io.File(ConvoyTrackOps.tracksDir(), name)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }

        // -- WebView --
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onAreaSelected(north: Double, south: Double, east: Double, west: Double) {
                                android.util.Log.i("DownloadPanel", "onAreaSelected: n=$north s=$south e=$east w=$west")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    downloadBbox = DownloadBbox(north = north, south = south, east = east, west = west)
                                    isDrawingArea = false
                                    android.util.Log.i("DownloadPanel", "downloadBbox updated: valid=${downloadBbox.isValid}")
                                }
                            }
                            @JavascriptInterface
                            fun onMapBoundsReady(n: Double, s: Double, e: Double, w: Double) {}
                        }, "Android")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
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
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val satUrl = tileSources[0].third
                                view?.evaluateJavascript(
                                    "setTileUrl('" + satUrl + "', 'SAT')", null
                                )
                                val initOverlayJson = MapSourceManager.getOverlayJson("SAT")
                                if (initOverlayJson != "[]") {
                                    view?.evaluateJavascript(
                                        "setOverlayLayers('" + initOverlayJson.replace("'", "\'") + "')", null
                                    )
                                }
                                // Trails loaded on demand via TRAILS button
                            }
                        }
                        loadUrl("file:///android_asset/grouptrack_map.html")
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── Download progress bar ─────────────────────────────────────
            // -- Download queue panel (replaces simple progress bar) --
            // -- FIXED SOURCE BAR --
            ConvoyMapBar(
                navLabel = "CONVOY",
                navIsBack = true,
                onNavigate = onBack,
                activeSource = pmActiveSource,
                isOffline = false,
                onSourceChange = { label ->
                    pmActiveSource = label; ConvoyConfig.ACTIVE_TILE_SOURCE = label
                    val url = MapSourceManager.getSlotSources().find { it.first == label }?.third ?: ""
                    webViewRef?.evaluateJavascript("setTileUrl('" + url + "', '" + label + "')", null)
                    val ovJson = MapSourceManager.getOverlayJson(label)
                    if (ovJson != "[]") {
                        webViewRef?.evaluateJavascript("setOverlayLayers('" + ovJson.replace("'", "\'") + "')", null)
                    }
                },
                onOfflineToggle = { _ -> },
                modifier = Modifier
                    .padding(start = 50.dp, end = 8.dp, top = 2.dp)
                    .fillMaxWidth()
            )
            // -- QUEUES PANEL (V2.5 scaffold) --
            if (pmQueuesOpen) {
                ConvoyQueuesPanel(onDismiss = { pmQueuesOpen = false })
            }

            // -- WORK WITH ARTIFACTS (V2.5 scaffold) --
            ConvoyArtifactsPanel(
                isConvoyMap = false,
                onImport = { typeName ->
                    when (typeName) {
                        "Trails" -> onNavigateToTrailSources()
                        else -> { /* TODO: wire other import types */ }
                    }
                }
            )

            // -- FLOATING DISPLAY PANEL --
            ConvoyDisplayPanel(
                tracksOn = pmTracksOn,
                onTracksToggle = {
                    pmTracksOn = pmTracksOn.not()
                    if (pmTracksOn) {
                        if (pmTracksLoaded) {
                            webViewRef?.evaluateJavascript("showAllTrackFiles()", null)
                        } else {
                            pmTracksLoaded = true
                            val wv = webViewRef
                            kotlinx.coroutines.MainScope().launch {
                                val colors = listOf("#39FF14")
                                val dir = java.io.File(
                                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                                    "my_tracks")
                                val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
                                }
                                files.forEachIndexed { idx, name ->
                                    val color = colors[idx % colors.size]
                                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            val file = java.io.File(dir, name)
                                            if (!file.exists()) return@withContext null
                                            val text = file.readText()
                                            val coords = if (name.lowercase().endsWith(".gpx")) parseGpx(text) else parseKml(text)
                                            if (coords.isEmpty()) return@withContext null
                                            coords.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
                                        } catch (e: Exception) { null }
                                    }
                                    if (result != null) {
                                        val safe = name.replace("'", "\\'")
                                        wv?.evaluateJavascript("loadTrackFile('$safe', '$result', '$color')", null)
                                    }
                                }
                            }
                        }
                    } else {
                        webViewRef?.evaluateJavascript("hideAllTrackFiles()", null)
                    }
                },
                trailsOn = pmTrailsOn,
                onTrailsToggle = {
                    pmTrailsOn = pmTrailsOn.not()
                    if (pmTrailsOn) {
                        Thread {
                            try {
                                val json = context.assets.open("utah_trails_stgeorge.geojson").bufferedReader().use { it.readText() }
                                webViewRef?.post { webViewRef?.evaluateJavascript("loadTrails(" + json + "); showTrails();", null) }
                            } catch (e: Exception) { }
                        }.start()
                    } else {
                        webViewRef?.evaluateJavascript("toggleTrails()", null)
                    }
                },
                downloadedOn = pmDownloadedOn,
                onDownloadedToggle = {
                    if (pmDownloadedOn) {
                        pmDownloadedOn = false
                        webViewRef?.evaluateJavascript("clearDownloadedAreas()", null)
                    } else {
                        val tilesDir = java.io.File(ConvoyConfig.TILE_DIR, "SAT/14")
                        Thread {
                            val bounds = mutableListOf<String>()
                            if (tilesDir.exists()) {
                                val z = 14; val n = 1 shl z
                                tilesDir.listFiles()?.forEach { xDir ->
                                    val x = xDir.name.toLongOrNull() ?: return@forEach
                                    xDir.listFiles()?.forEach { yFile ->
                                        val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                        val tN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n))))
                                        val tS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / n))))
                                        val tW = x.toDouble() / n * 360.0 - 180.0
                                        val tE = (x + 1).toDouble() / n * 360.0 - 180.0
                                        bounds.add("{\"n\":" + tN + ",\"s\":" + tS + ",\"e\":" + tE + ",\"w\":" + tW + "}")
                                    }
                                }
                            }
                            val json = "[" + bounds.joinToString(",") + "]"
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                webViewRef?.evaluateJavascript("showDownloadedAreas(" + json + ")", null)
                                pmDownloadedOn = true
                            }
                        }.start()
                    }
                },
                scanningDownloaded = false,
                modifier = Modifier.padding(start = 8.dp, top = 56.dp)
            )
            DownloadQueuePanel(
                expanded = queueExpanded,
                onToggle = { queueExpanded = !queueExpanded },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp).width(280.dp)
            )

            // ── Download panel (above FAB) ────────────────────────────────
            if (showDownloadPanel) {
                ConvoyDownloadPanel(
                    bbox = downloadBbox,
                    tileEstimate = if (downloadBbox.isValid) {
                        val est = ConvoyTileCalculator.quickEstimate(
                            downloadBbox.north, downloadBbox.south,
                            downloadBbox.east, downloadBbox.west)
                        "~${est.tileCount} tiles \u00b7 ${est.estimatedMB} MB"
                    } else "",
                    trailSourceCount = 0,
                    isDrawing = isDrawingArea,
                    onDrawArea = {
                        isDrawingArea = true
                        webViewRef?.evaluateJavascript("activateDrawMode()", null)
                    },
                    onClearArea = {
                        downloadBbox = DownloadBbox()
                        webViewRef?.evaluateJavascript("clearAreaBoundary()", null)
                    },
                    onExecuteDownload = { tiles, trails, removeTiles ->
                        android.util.Log.i("DownloadPanel", "onExecuteDownload: tiles=$tiles trails=$trails remove=$removeTiles bbox.valid=${downloadBbox.isValid} n=${downloadBbox.north} s=${downloadBbox.south}")
                        if (tiles && downloadBbox.isValid) {
                            val bb = downloadBbox
                            Thread {
                                val estimate = ConvoyTileCalculator.quickEstimate(bb.north, bb.south, bb.east, bb.west)
                                val pending = ConvoyViewModel.PendingDownload(
                                    tileCount = estimate.tileCount, sizeMB = estimate.estimatedMB,
                                    withinCeiling = estimate.withinCeiling,
                                    north = bb.north, south = bb.south, east = bb.east, west = bb.west,
                                    sourceName = ConvoyConfig.ACTIVE_TILE_SOURCE,
                                    sourceUrl = ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: ""
                                )
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    convoyViewModel.startDownload(context, pending)
                                }
                            }.start()
                        }
                    },
                    onNavigateToTrailSources = { bbox ->
                        android.util.Log.i("DownloadPanel", "onNavigateToTrailSources: n=${bbox.north} s=${bbox.south} e=${bbox.east} w=${bbox.west} valid=${bbox.isValid}")
                        TrailImporter.writePendingArea(bbox.north, bbox.south, bbox.east, bbox.west)
                        android.util.Log.i("DownloadPanel", "writePendingArea called, navigating...")
                        onNavigateToTrailSources()
                    },
                    onFlyoverZoomChange = { zoom ->
                        ConvoyConfig.SEARCH_FLY_ZOOM = zoom
                    },
                    onShowDownloadedMaps = { show ->
                        if (show) {
                            if (!scanningDownloaded) {
                                scanningDownloaded = true
                                val wv = webViewRef
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
                                }
                            }
                        } else {
                            showDownloaded = false
                            webViewRef?.evaluateJavascript("clearDownloadedAreas()", null)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 80.dp)
                )
            }

            // ── Floating draggable download FAB ───────────────────────────
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .offset { IntOffset(fabOffsetX.roundToInt(), fabOffsetY.roundToInt()) }
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            fabOffsetX += dragAmount.x
                            fabOffsetY += dragAmount.y
                        }
                    }
                    .clickable { showDownloadPanel = !showDownloadPanel },
                shape = RoundedCornerShape(24.dp),
                color = if (showDownloadPanel) Color(0xFF2E75B6) else Color(0xFF1A2E4A),
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("↕", color = Color.White, fontSize = 18.sp)
                }
            }
        }

        // -- Legend bar --
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF131820))
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(Color(0xFF00AAFF), "OHV")
            LegendDot(Color(0xFFFF8800), "Hike+Bike")
            LegendDot(Color(0xFFFFCC00), "Hike")
            LegendDot(Color(0xFFAA44FF), "Bike")
            LegendDot(Color(0xFF39FF14), "My Track")
        }
    }
    // ── Track action menu integration ──
    TrackActionDialog(
        file = actionTarget,
        onDismiss = { actionTarget = null },
        onRename = { renameTarget = actionTarget; actionTarget = null },
        onDelete = { deleteTarget = actionTarget; actionTarget = null },
        onShare = {
            val f = actionTarget; actionTarget = null
            f?.let { ConvoyTrackOps.shareTrack(context, it) }
        },
        onMoveToDownloads = {
            val f = actionTarget; actionTarget = null
            f?.let {
                scope.launch {
                    val ok = ConvoyTrackOps.copyToDownloads(it)
                    actionStatusMsg = if (ok) "Copied to Downloads" else "Copy failed"
                }
            }
        },
        onFixDate = {
            val f = actionTarget; actionTarget = null
            f?.let {
                scope.launch {
                    val ok = ConvoyTrackOps.fixDateFromContent(it)
                    actionStatusMsg = if (ok) "Date updated" else "No <time> found"
                    refreshTracks()
                }
            }
        }
    )
    RenameTrackDialog(
        file = renameTarget,
        onDismiss = { renameTarget = null },
        onConfirm = { newName ->
            val f = renameTarget; renameTarget = null
            f?.let {
                unloadIfLoaded(it.name)
                scope.launch {
                    val result = ConvoyTrackOps.renameTrack(it, newName)
                    actionStatusMsg = when (result) {
                        is ConvoyTrackOps.RenameResult.Success -> "Renamed"
                        is ConvoyTrackOps.RenameResult.NameExists -> "Name already exists"
                        is ConvoyTrackOps.RenameResult.Failed -> "Rename failed"
                    }
                    refreshTracks()
                }
            }
        }
    )
    DeleteTrackDialog(
        file = deleteTarget,
        onDismiss = { deleteTarget = null },
        onConfirm = {
            val f = deleteTarget; deleteTarget = null
            f?.let {
                unloadIfLoaded(it.name)
                scope.launch {
                    val ok = ConvoyTrackOps.deleteTrack(it)
                    actionStatusMsg = if (ok) "Deleted" else "Delete failed"
                    refreshTracks()
                }
            }
        }
    )
    actionStatusMsg?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            actionStatusMsg = null
        }
    }
}

// ── Track file helpers ─────────────────────────────────────────────
private fun scanTrackDir(context: android.content.Context): List<String> {
    val dir = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
        "my_tracks"
    )
    if (!dir.exists()) return emptyList()
    val files = dir.listFiles()
        ?.filter { f ->
            val ext = f.extension.lowercase()
            (ext == "kml" || ext == "gpx") &&
            !f.name.startsWith(".") &&
            !f.name.startsWith("convoy_track_temp")
        }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.name }
        ?: emptyList()
    android.util.Log.d("MapViewer", "scanTrackDir found ${files.size} tracks")
    return files
}

private fun loadTrackOnMap(
    context: android.content.Context,
    fileName: String,
    color: String,
    webView: android.webkit.WebView?
) {
    // ANR FIX: file read + parse on IO, JS push on Main
    kotlinx.coroutines.MainScope().launch {
        try {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOCUMENTS
                    ), "my_tracks"
                )
                val file = java.io.File(dir, fileName)
                if (!file.exists()) return@withContext null
                val text = file.readText()
                val coords = if (fileName.lowercase().endsWith(".gpx")) parseGpx(text) else parseKml(text)
                if (coords.isEmpty()) return@withContext null
                val json = coords.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
                Pair(json, coords.size)
            }
            if (result != null) {
                val safe = fileName.replace("'", "\\'")
                webView?.evaluateJavascript("loadTrackFile('$safe', '${result.first}', '$color')", null)
                android.util.Log.d("MapViewer", "Loaded $fileName: ${result.second} points")
            }
        } catch (e: Exception) {
            android.util.Log.e("MapViewer", "Track load error $fileName: ${e.message}")
        }
    }
}

private fun parseKml(text: String): List<Pair<Double, Double>> {
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

private fun parseGpx(text: String): List<Pair<Double, Double>> {
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

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = RoundedCornerShape(4.dp),
            color = color
        ) {}
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            color = Color(0xFF8B938A),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
