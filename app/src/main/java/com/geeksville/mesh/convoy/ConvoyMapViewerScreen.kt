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
    // ── GPX Import: scan Downloads directory ──
    var showImportList by remember { mutableStateOf(false) }
    var lastViewportSouth by remember { mutableStateOf(37.0) }
    var lastViewportWest by remember { mutableStateOf(-114.0) }
    var lastViewportNorth by remember { mutableStateOf(38.0) }
    var lastViewportEast by remember { mutableStateOf(-113.0) }
    var activeListType by remember { mutableStateOf<String?>(null) }
    var artifactList by remember { mutableStateOf<List<Map<String, String?>>>(emptyList()) }
    var selectedArtifactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importFileList by remember { mutableStateOf<List<String>>(emptyList()) }
    var importingFile by remember { mutableStateOf<String?>(null) }
    val scanDownloadsForGpx: () -> Unit = {
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            val files = dir.listFiles()?.filter { f ->
                val ext = f.extension.lowercase()
                (ext == "gpx" || ext == "kml") && !f.name.startsWith(".")
            }?.sortedByDescending { it.lastModified() }?.map { it.name } ?: emptyList()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                importFileList = files
                showImportList = true
            }
        }
    }
    val runImport: (String) -> Unit = { fileName ->
        scope.launch {
            importingFile = fileName
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                val sourceFile = java.io.File(downloadsDir, fileName)
                val summary = ConvoyTrackOps.importGpxAllArtifacts(sourceFile, context)
                val msg = buildString {
                    append("Imported: ")
                    val parts = mutableListOf<String>()
                    if (summary.trackCount > 0) parts.add("${summary.trackCount} track${if (summary.trackCount > 1) "s" else ""}")
                    if (summary.waypointCount > 0) parts.add("${summary.waypointCount} waypoint${if (summary.waypointCount > 1) "s" else ""}")
                    if (summary.routeCount > 0) parts.add("${summary.routeCount} route${if (summary.routeCount > 1) "s" else ""}")
                    if (parts.isEmpty()) append("no artifacts found")
                    else append(parts.joinToString(", "))
                    if (summary.errors.isNotEmpty()) append(" (${summary.errors.size} error${if (summary.errors.size > 1) "s" else ""})")
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                refreshTracks()
                webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                // Refresh the import list (file should be gone now)
                scanDownloadsForGpx()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Import error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            importingFile = null
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
    var panelTilesChecked by remember { mutableStateOf(false) }
    var showDownloadConfirm by remember { mutableStateOf(false) }
    var downloadReplaceExisting by remember { mutableStateOf(false) }
    var panelTrailsChecked by remember { mutableStateOf(false) }
    var panelRemoveTilesChecked by remember { mutableStateOf(false) }
    var panelFlyoverZoom by remember { mutableStateOf(18) }
    var queueExpanded by remember { mutableStateOf(false) }
    var pmTracksOn by remember { mutableStateOf(false) }
    var pmTracksLoaded by remember { mutableStateOf(false) }
    var pmTrailsOn by remember { mutableStateOf(false) }
    var pmTracksLazyOn by remember { mutableStateOf(false) }
    var pmWaypointsOn by remember { mutableStateOf(false) }
    var pmRoutesOn by remember { mutableStateOf(false) }
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
        // -- Header (transparent) --
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0x88000000))
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "BACK",
                color = Color(0xFF4DA6FF),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }.padding(4.dp)
            )
            Text(
                "PLANNING MAP",
                color = Color(0xFFE8EEF5),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            // TRACKS + QUEUES (grouped right)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {

                // QUEUES — V2.5 scaffold launch point
                Text("QUEUES",
                    color = Color(0xFF1CF0A0),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { pmQueuesOpen = !pmQueuesOpen }
                        .padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }

        // -- Search toggle + collapsible bar --
        if (!showSearch) {
            Surface(
                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    .clickable { showSearch = true },
                shape = RoundedCornerShape(6.dp),
                color = Color(0xAA1A2030)
            ) {
                Text("\u25BC  SEARCH + MAP", color = Color(0xFF4DA6FF),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        if (showSearch) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0x44000000))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(Color(0xFF4DA6FF)),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0A1020), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    decorationBox = { innerTextField ->
                        if (searchText.isEmpty()) {
                            Text("City, park, trail area...", color = Color(0xFF445566),
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        innerTextField()
                    }
                )
                // Hide bar button
                Surface(
                    modifier = Modifier.clickable { showSearch = false },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2A3545)
                ) {
                    Text("\u25B2", color = Color(0xFF4DA6FF), fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                }
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

        // Track panel removed

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
                            @JavascriptInterface
                            fun onViewportChanged(north: Double, south: Double, east: Double, west: Double, zoom: Double) {
                                // Always query — data preloaded, toggle controls visibility
                                val z = zoom.toInt()
                                val limit = if (z < 14) 500 else 2000
                                Thread {
                                    try {
                                        SpatialDbManager.init(context)
                                        val trails = if (z >= 8) SpatialDbManager.queryTrailsByViewport(south, west, north, east, limit) else emptyList()
                                        val json = SpatialDbManager.buildTrailGeoJson(trails)
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            webViewRef?.evaluateJavascript("updateTrails(" + json + ")", null)
                                        }
                                        // -- Track lazy load --
                                        if (true) { // Always query tracks
                                            val trackResults = SpatialDbManager.queryTracksByViewport(south, west, north, east, if (zoom >= 12) 200 else 50)
                                            if (trackResults.isNotEmpty()) {
                                                val trackJson = SpatialDbManager.buildTrackGeoJson(trackResults)
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    webViewRef?.evaluateJavascript("updateTracks(" + trackJson + ")", null)
                                                }
                                            }
                                        }
                                        // -- Waypoint lazy load --
                                        if (true) { // Always query waypoints
                                            val wptResults = SpatialDbManager.queryWaypointsByViewport(south, west, north, east, limit)
                                            if (wptResults.isNotEmpty()) {
                                                val wptJson = SpatialDbManager.buildWaypointGeoJson(wptResults)
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    webViewRef?.evaluateJavascript("updateWaypoints(" + wptJson + ")", null)
                                                }
                                            }
                                        }
                                        // -- Route lazy load --
                                        if (true) { // Always query routes
                                            val routeResults = SpatialDbManager.queryRoutesByViewport(south, west, north, east, limit)
                                            if (routeResults.isNotEmpty()) {
                                                val routeJson = SpatialDbManager.buildRouteGeoJson(routeResults)
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    webViewRef?.evaluateJavascript("updateRoutes(" + routeJson + ")", null)
                                                }
                                            }
                                        }
                                    } catch (ex: Exception) {
                                        android.util.Log.e("TrailLazy", "Viewport query failed: " + ex.message)
                                    }
                                }.start()
                            }
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
                                MapSourceManager.init(view?.context ?: return)
                                // Sync tracks from GPX files on first load
                                kotlinx.coroutines.MainScope().launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            SpatialDbManager.init(view?.context ?: return@withContext)
                                            SpatialDbManager.syncTracksFromFiles(view?.context ?: return@withContext)
                                        } catch (e: Exception) {
                                            android.util.Log.e("TrackSync", "Sync error: " + e.message)
                                        }
                                    }
                                }
                                view?.postDelayed({
                                    val satUrl = tileSources[0].third
                                    view?.evaluateJavascript(
                                        "setTileUrl('" + satUrl + "', 'SAT')", null
                                    )
                                }, 300)
                                val initOverlayJson = MapSourceManager.getOverlayJson("SAT")
                                if (initOverlayJson != "[]") {
                                    view?.evaluateJavascript(
                                        "setOverlayLayers('" + initOverlayJson.replace("'", "\'") + "')", null
                                    )
                                }
                                // Trails loaded on demand via TRAILS button
                                // Center map on device GPS position (matches Convoy Map approach)
                                view?.postDelayed({
                                    try {
                                        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                                        @Suppress("MissingPermission")
                                        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                        if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                                            view?.evaluateJavascript(
                                                "setView(" + loc.latitude + ", " + loc.longitude + ", 15)", null
                                            )
                                            android.util.Log.i("PlanMap", "Centered on GPS: " + loc.latitude + ", " + loc.longitude)
                                        } else {
                                            android.util.Log.w("PlanMap", "GPS: getLastKnownLocation returned null — no cached position")
                                        }
                                    } catch (e: SecurityException) {
                                        android.util.Log.w("PlanMap", "GPS: Location permission not granted")
                                    } catch (e: Exception) {
                                        android.util.Log.e("PlanMap", "GPS: Error getting location: " + e.message)
                                    }
                                }, 600)
                                // Detect max offline zoom from tile directory
                                Thread {
                                    try {
                                        val sourceDir = java.io.File(ConvoyConfig.TILE_DIR, pmActiveSource)
                                        var maxZ = 0
                                        if (sourceDir.exists()) {
                                            sourceDir.listFiles()?.forEach { zDir ->
                                                val z = zDir.name.toIntOrNull()
                                                if (z != null && z > maxZ && zDir.isDirectory) maxZ = z
                                            }
                                        }
                                        if (maxZ > 0) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                view?.evaluateJavascript("setMaxOfflineZoom($maxZ)", null)
                                            }
                                            android.util.Log.i("PlanMap", "Max offline zoom for $pmActiveSource: z$maxZ")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("PlanMap", "Tile scan error: " + e.message)
                                    }
                                }.start()
                                // Trigger initial data load for all artifact types
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                                    android.util.Log.i("PlanMap", "Initial artifact data load triggered")
                                }, 1000)
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
            // -- SOURCE BAR (accordion with search) --
            if (showSearch) {
            ConvoyMapBar(
                navLabel = "",
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
            } // end showSearch accordion
            // -- QUEUES PANEL (live download queue) --
            if (pmQueuesOpen) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth(0.90f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xEE131820),
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("DOWNLOAD QUEUES", color = Color(0xFF1CF0A0),
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                            Text("CLOSE", color = Color(0xFF7A8DA0),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { pmQueuesOpen = false }.padding(8.dp))
                        }
                        DownloadQueuePanel(
                            expanded = true,
                            onToggle = { pmQueuesOpen = false }
                        )
                        // Show message if queue is empty
                        val queueState = DownloadQueueManager.queue.collectAsState()
                        if (queueState.value.isEmpty()) {
                            Text("No downloads in queue",
                                color = Color(0xFF4A6080), fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }

            // -- WORK WITH ARTIFACTS (V2.5 scaffold) --
            ConvoyArtifactsPanel(
                isConvoyMap = false,
                onDisplayToggle = { typeName ->
                    when (typeName) {
                        "Tracks" -> {
                            pmTracksLazyOn = !pmTracksLazyOn
                            webViewRef?.evaluateJavascript(
                                if (pmTracksLazyOn) "showTracks()" else "hideTracks()", null)
                        }
                        "Trails" -> {
                            pmTrailsOn = !pmTrailsOn
                            webViewRef?.evaluateJavascript(
                                if (pmTrailsOn) "showTrails()" else "hideTrails()", null)
                        }
                        "Waypoints" -> {
                            pmWaypointsOn = !pmWaypointsOn
                            webViewRef?.evaluateJavascript(
                                if (pmWaypointsOn) "showWaypoints()" else "hideWaypoints()", null)
                        }
                        "Routes" -> {
                            pmRoutesOn = !pmRoutesOn
                            webViewRef?.evaluateJavascript(
                                if (pmRoutesOn) "showRoutes()" else "hideRoutes()", null)
                        }
                    }
                },
                onEditDisplay = { typeName ->
                    val table = when (typeName) {
                        "Tracks" -> "tracks"
                        "Trails" -> "trails"
                        "Waypoints" -> "waypoints"
                        "Routes" -> "routes"
                        else -> return@ConvoyArtifactsPanel
                    }
                    scope.launch {
                        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            SpatialDbManager.init(context)
                            SpatialDbManager.queryArtifactList(table, lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                        }
                        if (list.isNotEmpty()) {
                            artifactList = list
                            selectedArtifactIds = list.mapNotNull { it["id"] }.toSet()
                            activeListType = typeName
                        } else {
                            android.widget.Toast.makeText(context, "No " + typeName + " in current view", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onImport = { typeName ->
                    when (typeName) {
                        "Trails" -> onNavigateToTrailSources()
                        "Artifacts" -> { scanDownloadsForGpx() }
                        else -> { scanDownloadsForGpx() }
                    }
                }
            )

            // -- ARTIFACT LIST PANEL (SELECT/EDIT) --
            if (activeListType != null) {
                ArtifactListPanel(
                    artifactType = activeListType!!,
                    artifacts = artifactList,
                    selectedIds = selectedArtifactIds,
                    onDismiss = { activeListType = null },
                    onToggleItem = { id, selected ->
                        val newIds = if (selected) selectedArtifactIds + id else selectedArtifactIds - id
                        selectedArtifactIds = newIds
                        // Push filtered GeoJSON to map
                        scope.launch {
                            val filtered = artifactList.filter { it["id"] in newIds }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SpatialDbManager.init(context)
                                val json = when (activeListType) {
                                    "Waypoints" -> SpatialDbManager.buildWaypointGeoJson(filtered)
                                    "Routes" -> SpatialDbManager.buildRouteGeoJson(filtered)
                                    "Tracks" -> SpatialDbManager.buildTrackGeoJson(filtered)
                                    "Trails" -> SpatialDbManager.buildTrailGeoJson(filtered)
                                    else -> return@withContext
                                }
                                val fn = when (activeListType) {
                                    "Waypoints" -> "updateWaypoints"
                                    "Routes" -> "updateRoutes"
                                    "Tracks" -> "updateTracks"
                                    "Trails" -> "updateTrails"
                                    else -> return@withContext
                                }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    webViewRef?.evaluateJavascript("$fn($json)", null)
                                }
                            }
                        }
                    },
                    onSelectAll = {
                        selectedArtifactIds = artifactList.mapNotNull { it["id"] }.toSet()
                        // Show all on map
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SpatialDbManager.init(context)
                                val json = when (activeListType) {
                                    "Waypoints" -> SpatialDbManager.buildWaypointGeoJson(artifactList)
                                    "Routes" -> SpatialDbManager.buildRouteGeoJson(artifactList)
                                    "Tracks" -> SpatialDbManager.buildTrackGeoJson(artifactList)
                                    "Trails" -> SpatialDbManager.buildTrailGeoJson(artifactList)
                                    else -> return@withContext
                                }
                                val fn = when (activeListType) {
                                    "Waypoints" -> "updateWaypoints"; "Routes" -> "updateRoutes"
                                    "Tracks" -> "updateTracks"; "Trails" -> "updateTrails"
                                    else -> return@withContext
                                }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    webViewRef?.evaluateJavascript("$fn($json)", null)
                                }
                            }
                        }
                    },
                    onDeselectAll = {
                        selectedArtifactIds = emptySet()
                        // Clear from map
                        val fn = when (activeListType) {
                            "Waypoints" -> "clearWaypoints"; "Routes" -> "clearRoutes"
                            "Tracks" -> "clearTracks"; "Trails" -> "clearTrails"
                            else -> null
                        }
                        if (fn != null) webViewRef?.evaluateJavascript("$fn()", null)
                    },
                    onRename = { id, newName ->
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SpatialDbManager.init(context)
                                when (activeListType) {
                                    "Waypoints" -> SpatialDbManager.renameWaypoint(id, newName)
                                    "Routes" -> SpatialDbManager.renameRoute(id, newName)
                                    "Tracks" -> SpatialDbManager.renameTrackInDb(id, newName)
                                }
                            }
                            // Refresh list
                            val table = when (activeListType) {
                                "Tracks" -> "tracks"; "Waypoints" -> "waypoints"
                                "Routes" -> "routes"; else -> "trails"
                            }
                            val bounds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SpatialDbManager.getArtifactBounds(table)
                            }
                            if (bounds != null) {
                                artifactList = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    SpatialDbManager.queryArtifactList(table, bounds[0], bounds[1], bounds[2], bounds[3])
                                }
                            }
                            webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                        }
                    },
                    onDelete = { id ->
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SpatialDbManager.init(context)
                                when (activeListType) {
                                    "Waypoints" -> SpatialDbManager.deleteWaypoint(id)
                                    "Routes" -> SpatialDbManager.deleteRoute(id)
                                    "Tracks" -> SpatialDbManager.deleteTrackFromDb(id)
                                }
                            }
                            artifactList = artifactList.filter { it["id"] != id }
                            selectedArtifactIds = selectedArtifactIds - id
                            webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                        }
                    },
                    onChangeType = if (activeListType == "Waypoints") { id, newType ->
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SpatialDbManager.init(context)
                                SpatialDbManager.changeWaypointType(id, newType)
                            }
                            webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                        }
                    } else null
                )
            }

                        // -- IMPORT LIST PANEL (scan Downloads for GPX/KML) --
            if (showImportList) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xEE131820),
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("IMPORT ARTIFACTS", color = Color(0xFF4DA6FF),
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold)
                            Text("CLOSE", color = Color(0xFF7A8DA0),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showImportList = false }.padding(4.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        // RESYNC TRACKS button
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        SpatialDbManager.init(context)
                                        SpatialDbManager.syncTracksFromFiles(context)
                                    }
                                    android.widget.Toast.makeText(context, "Track resync complete", android.widget.Toast.LENGTH_SHORT).show()
                                    webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                                }
                            },
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0D1520)
                        ) {
                            Text("RESYNC TRACKS TO SPATIAL DB", color = Color(0xFF39FF14),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("GPX/KML files in Downloads:", color = Color(0xFF4A6080),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        if (importFileList.isEmpty()) {
                            Text("No GPX or KML files found in Downloads",
                                color = Color(0xFF667788), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 16.dp))
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                items(importFileList.size) { idx ->
                                    val name = importFileList[idx]
                                    val isImporting = importingFile == name
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                            .clickable(enabled = !isImporting) { runImport(name) },
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isImporting) Color(0xFF2E75B6).copy(alpha = 0.3f) else Color(0xFF1A2233)
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (isImporting) "⏳" else "⬇",
                                                fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                                            Text(name, color = Color.White, fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace, maxLines = 1,
                                                modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Tap file to import. Tracks, waypoints, and routes are extracted automatically.",
                            color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // DisplayPanel removed

                        DownloadQueuePanel(
                expanded = queueExpanded,
                onToggle = { queueExpanded = !queueExpanded },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp).width(280.dp)
            )

            // ── Download confirm overlay ──────────────────────────────────
            if (showDownloadConfirm && downloadBbox.isValid) {
                val slotSources = remember { MapSourceManager.getSlotSources() }
                val estimate = remember(downloadBbox) { ConvoyTileCalculator.quickEstimate(
                    downloadBbox.north, downloadBbox.south,
                    downloadBbox.east, downloadBbox.west) }
                val slots = remember(slotSources) { slotSources.map { (legacyKey, shortLabel, _) ->
                    SlotDisplayInfo(
                        slotName = legacyKey,
                        sourceName = shortLabel,
                        directory = legacyKey,
                        tileCount = 0,
                        sizeMB = 0f,
                        preSelected = true
                    )
                } }
                ConvoyDownloadConfirm(
                    estimatedTiles = estimate.tileCount,
                    estimatedMB = estimate.estimatedMB,
                    areaDesc = String.format("%.3f\u00b0N to %.3f\u00b0N", downloadBbox.south, downloadBbox.north),
                    slots = slots,
                    onProceed = { selectedSlots, replace ->
                        showDownloadConfirm = false
                        showDownloadPanel = false
                        Thread {
                            val bb = downloadBbox
                            for (slotName in selectedSlots) {
                                DownloadQueueManager.enqueueArea(
                                    context, slotName,
                                    bb.north, bb.south, bb.east, bb.west,
                                    replace
                                )
                            }
                        }.start()
                    },
                    onCancel = { showDownloadConfirm = false },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            }
            // ── Download panel (above FAB) ────────────────────────────────
            if (showDownloadPanel) {
                ConvoyDownloadPanel(
                    bbox = downloadBbox,
                    tilesChecked = panelTilesChecked,
                    onTilesCheckedChange = { panelTilesChecked = it },
                    trailsChecked = panelTrailsChecked,
                    onTrailsCheckedChange = { panelTrailsChecked = it },
                    removeTilesChecked = panelRemoveTilesChecked,
                    onRemoveTilesCheckedChange = { panelRemoveTilesChecked = it },
                    flyoverZoom = panelFlyoverZoom,
                    onFlyoverZoomChange = { panelFlyoverZoom = it; ConvoyConfig.SEARCH_FLY_ZOOM = it },
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
                            showDownloadConfirm = true; showDownloadPanel = false
                        }
                    },
                    onNavigateToTrailSources = { bbox ->
                        android.util.Log.i("DownloadPanel", "onNavigateToTrailSources: n=${bbox.north} s=${bbox.south} e=${bbox.east} w=${bbox.west} valid=${bbox.isValid}")
                        TrailImporter.writePendingArea(bbox.north, bbox.south, bbox.east, bbox.west)
                        android.util.Log.i("DownloadPanel", "writePendingArea called, navigating...")
                        onNavigateToTrailSources()
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
            modifier = Modifier.fillMaxWidth().background(Color(0x88000000))
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(Color(0xFF00AAFF), "OHV", "line")
            LegendItem(Color(0xFF00FFFF), "Trail", "line")
            LegendItem(Color(0xFF39FF14), "Track", "dash")
            LegendItem(Color(0xFFFFD700), "Route", "dash")
            LegendItem(Color(0xFF2ECC40), "Wpt", "pin")
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
private fun LegendItem(color: Color, label: String, style: String = "line") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(width = 14.dp, height = 8.dp)) {
            val y = size.height / 2
            when (style) {
                "line" -> drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 3f)
                "dash" -> {
                    val d = size.width / 4
                    drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(d, y), strokeWidth = 3f)
                    drawLine(color, androidx.compose.ui.geometry.Offset(d * 2, y), androidx.compose.ui.geometry.Offset(d * 3, y), strokeWidth = 3f)
                }
                "pin" -> {
                    drawCircle(color, radius = size.minDimension / 2)
                    drawCircle(Color.White, radius = size.minDimension / 4)
                }
            }
        }
        Spacer(Modifier.width(3.dp))
        Text(label, color = Color(0xFFAABBCC), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}
