package com.geeksville.mesh.convoy
// [V2.6a-WEBP] read intercepts serve image/webp

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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.window.Popup
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
// Three-state display: OFF=0, ON=1, SELECTED=2
private const val DS_OFF = 0
// Canonical 12 waypoint types (B1 + rally). label shown in picker; key stored in DB.
private val WAYPOINT_TYPES: List<Pair<String, String>> = listOf(
    "hazard" to "☠ Hazard",
    "gate" to "⛔ Gate",
    "water" to "💧 Water",
    "fuel" to "⛽ Fuel",
    "shelter" to "🏠 Shelter",
    "trailhead" to "🥾 Trailhead",
    "viewpoint" to "👁 Viewpoint",
    "campsite" to "⛺ Campsite",
    "parking" to "P Parking",
    "junction" to "Y Junction",
    "rally" to "🚩 Rally",
    "other" to "• Other"
)

private const val DS_ON = 1
private const val DS_SELECTED = 2

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
    // SNAPRADIUS-2026-07-30: zoom has always been arriving on
    // onViewportChanged(n, s, e, w, zoom) and was being discarded. Snap needs
    // it to express its radius in the same units the popup hit test uses.
    // Default 16.0 is a mid-range planning zoom; it is overwritten by the
    // first viewport event, which fires on load.
    var lastViewportZoom by remember { mutableStateOf(16.0) }
    var activeListType by remember { mutableStateOf<String?>(null) }
    var artifactList by remember { mutableStateOf<List<Map<String, String?>>>(emptyList()) }
    var selectedArtifactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingWaypoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // ROUTE BUILDER: route mode active -> Route+ toolbar shown (read by next patch)
    // ADDPOINTMODE-DEFAULT-2026-07-31: was mutableStateOf(true).
    //
    // ⭐ DEVICE-CONFIRMED 07-31: with the default true, EVERY planner track tap
    // was suppressed by the guard at :640 — "PLANNER SUPPRESSED by
    // addPointMode" — with no route ever drawn. Leaflet's bindPopup showed
    // instead, which is why the planner gave a popup where convoy gave the
    // detail panel. (Convoy's onTrackTap has no such guard.)
    //
    // The old comment said "consumed by onMapTap next build". That build never
    // came, and the placeholder default shipped ON.
    //
    // ⭐ THE GUARD IS CORRECT AND STAYS — suppressing a detail panel
    // mid-route-draw is reasonable. Only the starting value was wrong. The one
    // assignment in this file, :1256 `addPointMode = armed`, sets it true when
    // route mode arms and false when it unarms, so the value still comes from
    // arming; it just no longer STARTS armed.
    //
    // ⚠ NOT seeded from the state JSON, deliberately. routeMode two lines below
    // is "LIVE session state ... Recovery launches in onPageFinished after
    // render, not here" — arming before the map renders is the failure that
    // decision prevents. false is simply the honest initial state: on launch
    // you are not adding route points.
    //
    // ⚠ :553 (onMapLongPress) also reads this and has been seeing true since
    // launch. Planner long-press behaviour may change — check it.
    var addPointMode by remember { mutableStateOf(false) }  // Tap:Route(on)/Artifact(off)
    var routeMethod by remember { mutableStateOf(ROUTE_METHOD_P2P) }
    var routeName by remember { mutableStateOf("") }
    // Isolated second read of the saved route-open flag (independent of pmSeed@224,
    // which is read later than this declaration). Gives routeMode its persisted value
    // BEFORE the back-gate at ~204 uses it, so a crash-left-open route restores on launch.
    val routeSeedOpen = remember { MapStateStore.readMap("planning").routeState?.open == true }
    var routeMode by remember { mutableStateOf(false) }   // LIVE session state (back-gate). Recovery launches in onPageFinished after render, not here.
    var showNameDialog by remember { mutableStateOf(false) }
    var routeEntryNonce by remember { mutableStateOf(0) }   // ++ on every route-mode entry; re-arms toolbar build controls
    // route lifecycle (Layer 2): launch state fixed at New / Select-In-Progress
    var routeLifecycleState by remember { mutableStateOf(ROUTE_LS_NEW) }
    var showSaveChoice by remember { mutableStateOf(false) }
    var showDiscardChoice by remember { mutableStateOf(false) }
    var showInProgressPicker by remember { mutableStateOf(false) }
    var showEntryChoice by remember { mutableStateOf(false) }
    var draftRenameTarget by remember { mutableStateOf<String?>(null) }
    var draftRenameText by remember { mutableStateOf("") }
    var draftRenameErr by remember { mutableStateOf("") }
    // OSM-IMPORT-2026-07-28: OSM import overlay. No nav route on purpose -- the
    // panel is planner-only, and every row's state is DERIVED FROM DISK, so
    // there is nothing to preserve across recomposition.
    var showOsmPanel by remember { mutableStateOf(false) }
    var recoveryDetected by remember { mutableStateOf(false) }
    var saveOrigName by remember { mutableStateOf("") }   // draft's on-disk name captured when Save panel opens (rename source)
    var recoveryLaunched by remember { mutableStateOf(false) }   // one-shot: recovery detected this session (in onPageFinished)
    var recoveryPending by remember { mutableStateOf(false) }   // show the recovery notice popup after settle
    // [draft-resolver 2026-08-01] Unnamed-draft resolver. Runs at planner entry and at
    // Route+ launch. File ops ONLY -- never arms route mode, never loads geometry.
    var showDraftResolve by remember { mutableStateOf(false) }
    var draftResolvePts by remember { mutableStateOf(0) }
    var draftResolveName by remember { mutableStateOf("") }
    var draftResolveErr by remember { mutableStateOf("") }
    var pendingInventory by remember { mutableStateOf(false) }
    var resolverRan by remember { mutableStateOf(false) }
    var routeNameTaken by remember { mutableStateOf(false) }
    // live In-Progress list: real draft names from RouteDraftStore (refreshed on draftListTick)
    var draftListTick by remember { mutableStateOf(0) }
    // [route-panel 2026-08-02] ALL drafts including the unnamed auto-save. The auto-save
    // belongs in the picker -- it is renamed or deleted there, and New Route is blocked
    // until it is. Sorted oldest-first by createdAt for the list display.
    val emulatedDrafts = RouteDraftStore.listDrafts().sortedBy { it.createdAt }
    var newWaypointType by remember { mutableStateOf("other") }
    var newWaypointName by remember { mutableStateOf("") }

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
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler {
        if (routeMode) {
            android.widget.Toast.makeText(context, "Save or discard your in-progress route first", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            onBack()
        }
    }
    val coroutineScope = rememberCoroutineScope()

    // Download controls state
    var showDownloadPanel by remember { mutableStateOf(false) }
    var panelTilesChecked by remember { mutableStateOf(false) }
    var showDownloadConfirm by remember { mutableStateOf(false) }
    var downloadReplaceExisting by remember { mutableStateOf(false) }
    var panelTrailsChecked by remember { mutableStateOf(false) }
    var panelRemoveTilesChecked by remember { mutableStateOf(false) }
    // DELETE-AREA-2026-07-25: gated confirm for tile removal. Deleting is
    // IRREVERSIBLE and those tiles cost real Esri requests, so the red button
    // asks before acting - same discipline as the queue panel's scoped
    // CANCEL/CLEAR. (Source selection is deliberately NOT asked: a delete
    // clears the area across every source.)
    var showRemoveTilesConfirm by remember { mutableStateOf(false) }
    var panelFlyoverZoom by remember { mutableStateOf(18) }
    var queueExpanded by remember { mutableStateOf(false) }
    var pmTracksOn by remember { mutableStateOf(false) }
    var pmTracksLoaded by remember { mutableStateOf(false) }
    // Three-state display — per-map state from MapStateStore (independent of convoy map)
    val pmSeed = remember { MapStateStore.readMap("planning") }
    var trailState by remember { mutableStateOf(pmSeed.types["Trails"]?.state ?: DS_OFF) }
    var trackState by remember { mutableStateOf(pmSeed.types["Tracks"]?.state ?: DS_OFF) }
    var waypointState by remember { mutableStateOf(pmSeed.types["Waypoints"]?.state ?: DS_OFF) }
    var routeState by remember { mutableStateOf(pmSeed.types["Routes"]?.state ?: DS_OFF) }
    var searchResults by remember { mutableStateOf(emptyList<ArtifactResult>()) }
    var pendingDetailId by remember { mutableStateOf<String?>(null) }
    var pendingDetailType by remember { mutableStateOf<String?>(null) }
    var trailCheckedIds by remember { mutableStateOf(MapStateStore.checkedIdsFor(pmSeed, "Trails")) }
    var trackCheckedIds by remember { mutableStateOf(MapStateStore.checkedIdsFor(pmSeed, "Tracks")) }
    var waypointCheckedIds by remember { mutableStateOf(MapStateStore.checkedIdsFor(pmSeed, "Waypoints")) }
    var routeCheckedIds by remember { mutableStateOf(MapStateStore.checkedIdsFor(pmSeed, "Routes")) }

    // Persist this map's state to JSON. Checkboxes are state-controlled: rows carry
    // the per-item checked status (the real last state). Geometry is refreshed by the
    // viewport query separately and is not part of this save. Rows for the active list
    // type are built from artifactList + selectedArtifactIds when a list is open.
    fun savePlanningState() {
        fun rowsFor(type: String): List<MapStateStore.Row> {
            // [3.1c] Source SELECTED rows from the persistent per-type checked-id set,
            // NOT the activeListType-gated artifactList. SELECT persists regardless of
            // which panel is open; ALL/OFF have null CheckedIds -> empty rows (query
            // rebuilds the list on entry, state flag sets on/off).
            val checkedIds = when (type) {
                "Trails" -> trailCheckedIds
                "Tracks" -> trackCheckedIds
                "Waypoints" -> waypointCheckedIds
                "Routes" -> routeCheckedIds
                else -> null
            } ?: return emptyList()
            return checkedIds.map { id -> MapStateStore.Row(id, "", true) }
        }
        val types = mapOf(
            "Trails" to MapStateStore.TypeState(trailState, rowsFor("Trails")),
            "Tracks" to MapStateStore.TypeState(trackState, rowsFor("Tracks")),
            "Waypoints" to MapStateStore.TypeState(waypointState, rowsFor("Waypoints")),
            "Routes" to MapStateStore.TypeState(routeState, rowsFor("Routes"))
        )
        val panel = MapStateStore.PanelBoxes(panelTilesChecked, panelTrailsChecked, panelRemoveTilesChecked)
        MapStateStore.saveMap("planning", MapStateStore.MapSnapshot(types, panel, MapStateStore.BBox(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast), null, MapStateStore.RouteState(routeMode, "draft", routeName)))
    }
    // [3.1] debounced viewport-settle save: persist frame on pan/zoom/search settle
    val viewportSaveHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val viewportSaveRunnable = remember { Runnable { savePlanningState() } }
    var pmQueuesOpen by remember { mutableStateOf(false) }
    // CORRIDOR-WIRING-2026-07-24: survives from the corridor button to onProceed, the
    // same way downloadBbox does. NON-NULL MEANS "this is a corridor job" -
    // that is what tells onProceed which branch to take. Cleared on BOTH
    // proceed and cancel, or the next AREA download would be treated as one.
    var pendingCorridorHash by remember { mutableStateOf<String?>(null) }
    // "?" help: which bundled doc is open ("manual" | "notes" | null = chooser/closed)
    var docsView by remember { mutableStateOf<String?>(null) }
    var showDocsChooser by remember { mutableStateOf(false) }
    var showArtifactsPanel by remember { mutableStateOf(false) }   // FAB closed-state vs panel open-state
    var pmDownloadedOn by remember { mutableStateOf(false) }
    var pmActiveSource by remember { mutableStateOf(ConvoyConfig.ACTIVE_TILE_SOURCE) }
    var mapZoomLevel by remember { mutableStateOf(ConvoyConfig.DOWNLOAD_ZOOM.toFloat()) }
    var showDownloaded by remember { mutableStateOf(false) }
    var scanningDownloaded by remember { mutableStateOf(false) }
    var fabOffsetX by remember { mutableStateOf(0f) }
    var fabOffsetY by remember { mutableStateOf(0f) }
    var legendOffsetX by remember { mutableStateOf(0f) }
    var legendOffsetY by remember { mutableStateOf(0f) }
    var legendExpanded by remember { mutableStateOf(false) }
    var downloadBbox by remember { mutableStateOf(DownloadBbox()) }
    var isDrawingArea by remember { mutableStateOf(false) }
    // OSM-C3B-AREA-2026-07-29: the IMPORT OSM checkbox on the download panel.
    // Preselected when a pending_import is awaiting a draw -- derived from
    // disk at panel open (R1), never passed across the panel gap.
    var panelOsmChecked by remember { mutableStateOf(false) }
    // The slug the pending draw belongs to. Null means no draw is expected,
    // which is what stops a stray area draw from filling someone's record.
    var osmAwaitingSlug by remember { mutableStateOf<String?>(null) }
    val downloadState by convoyViewModel.downloadState.collectAsState()

    // Tile sources from map_sources.json — single source of truth
    MapSourceManager.init(context)
    val tileSources = MapSourceManager.getSlotSources()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E14))) {
        // -- Combined header: BACK | sources | QUEUES --
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xCC000000))
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BACK", color = Color(0xFF4DA6FF),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    if (routeMode) {
                        android.widget.Toast.makeText(context, "Save or discard your in-progress route first", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        onBack()
                    }
                }.padding(horizontal = 14.dp, vertical = 14.dp))
            Spacer(Modifier.width(12.dp))
            tileSources.forEach { (label, _, _) ->
                val isActive = pmActiveSource == label
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isActive) Color(0xFF2266CC) else Color.Transparent,
                    modifier = Modifier.clickable {
                        pmActiveSource = label; ConvoyConfig.ACTIVE_TILE_SOURCE = label
                        val url = MapSourceManager.getSlotSources().find { it.first == label }?.third ?: ""
                        webViewRef?.evaluateJavascript("setTileUrl('" + url + "', '" + label + "')", null)
                        val ovJson = MapSourceManager.getOverlayJson(label)
                        if (ovJson != "[]") {
                            webViewRef?.evaluateJavascript("setOverlayLayers('" + ovJson.replace("'", "\\'") + "')", null)
                        }
                    }
                ) {
                    Text(label, color = if (isActive) Color.White else Color(0xFF7A8DA0),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("QUEUES", color = Color(0xFF1CF0A0),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { pmQueuesOpen = !pmQueuesOpen }
                    .padding(horizontal = 14.dp, vertical = 14.dp))
        }
        // Track panel removed
        // -- Back navigation guard --
                    pendingWaypoint?.let { (wLat, wLon) ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pendingWaypoint = null },
                            title = { androidx.compose.material3.Text("New Waypoint") },
                            text = {
                                androidx.compose.foundation.layout.Column {
                                    androidx.compose.foundation.layout.FlowRow {
                                        WAYPOINT_TYPES.forEach { (key, label) ->
                                            androidx.compose.material3.FilterChip(
                                                selected = newWaypointType == key,
                                                onClick = {
                                                    newWaypointType = key
                                                    if (newWaypointName.isBlank() || WAYPOINT_TYPES.any { it.second.substringAfter(" ") == newWaypointName }) {
                                                        newWaypointName = label.substringAfter(" ")
                                                    }
                                                },
                                                label = { androidx.compose.material3.Text(label) },
                                                modifier = androidx.compose.ui.Modifier.padding(2.dp)
                                            )
                                        }
                                    }
                                    androidx.compose.material3.OutlinedTextField(
                                        value = newWaypointName,
                                        onValueChange = { newWaypointName = it },
                                        label = { androidx.compose.material3.Text("Name (optional)") },
                                        singleLine = true
                                    )
                                }
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    val nm = if (newWaypointName.isBlank()) "Waypoint" else newWaypointName
                                    val ty = newWaypointType
                                    Thread {
                                        try {
                                            SpatialDbManager.init(context)
                                            SpatialDbManager.insertWaypoint(nm, wLat, wLon, ty)
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                webViewRef?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("WptCreate", "insert failed: " + e.message)
                                        }
                                    }.start()
                                    pendingWaypoint = null
                                    newWaypointName = ""
                                    newWaypointType = "other"
                                }) { androidx.compose.material3.Text("Create") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    pendingWaypoint = null
                                    newWaypointName = ""
                                    newWaypointType = "other"
                                }) { androidx.compose.material3.Text("Cancel") }
                            }
                        )
                    }

        // exit-confirm removed: back returns directly when no route active;
        // when a route is active the BackHandler toast-gates instead (FIX 8).

                // -- WebView --
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        // TAPTRACE-2026-07-30: the planner had NO WebChromeClient,
                        // so every console.log in grouptrack_map.html has gone
                        // nowhere. Convoy has had one since :777 (ConvoyJS).
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(
                                cm: android.webkit.ConsoleMessage
                            ): Boolean {
                                android.util.Log.d(
                                    "MapJS", cm.message() + "  @" + cm.lineNumber()
                                )
                                return true
                            }
                        }
                        settings.javaScriptEnabled = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onMapTap(lat: Double, lon: Double) {
                                android.util.Log.d("RouteBridge", "onMapTap lat=$lat lon=$lon")
                                kotlinx.coroutines.MainScope().launch {
                                    val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        SpatialDbManager.init(context)
                                        val trails = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                        // SNAP2-TRAILS-ONLY-2026-07-30 (Fred, 2.6e): route planning
                                        // snaps to TRAILS only. No trail in range -> plain point.
                                        // Tracks still render and stay tappable for details; they
                                        // are simply not snap targets.
                                        //
                                        // The queryTracksByViewport that stood here fed nothing but
                                        // the snap call, so removing it also drops one viewport query
                                        // from EVERY map tap.
                                        //
                                        // The by-id redraw below still queries tracks, which is what
                                        // lets routes saved before this change keep drawing. Harmless
                                        // to keep; becomes dead code once old routes are gone.
                                        // SNAPRADIUS-2026-07-30 (Fred): "we need to open
                                        // distance for snap 2 to same distance as popup."
                                        //
                                        // The popup hit test is Leaflet's renderer tolerance --
                                        // 44 PIXELS (L.svg({tolerance: 44}) on the map
                                        // constructor). Snap was a fixed 30 METRES. Pixels scale
                                        // with zoom and metres do not, so the two only agreed
                                        // near z17: at z16 the popup reached ~85 m while snap
                                        // refused past 30 m. That is why a tap could pop a
                                        // trail's name and still free-place the vertex.
                                        //
                                        // Deriving from Leaflet's own tolerance means a retune
                                        // there keeps the two in step instead of silently
                                        // drifting apart again.
                                        //
                                        // Clamp 15..150 m (Fred: "capped 150"). CEILING: at z14
                                        // the raw figure is ~340 m and, trail-first being
                                        // absolute but NEAREST deciding among trails, OSM density
                                        // would start snapping to a parallel trail. FLOOR: at z18
                                        // the raw figure is ~21 m, below today's 30 m -- without
                                        // it, close-in drawing would get worse.
                                        val mPerPx = 156543.03392 *
                                            kotlin.math.cos(Math.toRadians(lat)) /
                                            Math.pow(2.0, lastViewportZoom)
                                        val snapRadiusM = (44.0 * mPerPx).coerceIn(15.0, 150.0)
                                        val s = RouteManager.snapTrailsOnly(lat, lon, trails, snapRadiusM)
                                        android.util.Log.d(
                                            "RouteBridge",
                                            "onMapTap lat=$lat lon=$lon zoom=$lastViewportZoom " +
                                                "radius=${snapRadiusM.toInt()}m trails=${trails.size} -> " +
                                                if (s != null) "SNAPPED ${s.lineType} ${s.lineId}"
                                                else if (trails.isEmpty()) "FREE (no candidates)"
                                                else "FREE (nearest beyond radius)"
                                        )
                                        if (s != null) RouteManager.snapToVertex(s) else RouteManager.freeVertex(lat, lon)
                                    }
                                    RouteManager.addVertex(v)
                                    // AUTO-CHECKPOINT: persist in-progress draft after every point
                                    // so a teardown mid-build loses nothing (recover via start-route+ picker).
                                    if (routeName.isNotBlank()) {
                                        val methodStr = when (routeMethod) { ROUTE_METHOD_DRAW -> "draw"; ROUTE_METHOD_SUGGEST -> "suggest"; else -> "point" }
                                        runCatching {
                                            if (RouteDraftStore.draftExists(routeName)) RouteDraftStore.overwriteDraft(routeName, methodStr)
                                            else RouteDraftStore.writeDraft(routeName, methodStr)
                                        }
                                    }
                                    val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        SpatialDbManager.init(context)
                                        val tl = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                        val tk = SpatialDbManager.queryTracksByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                        val byId = HashMap<String, String>()
                                        for (m in tl) { val id = m["trail_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                        for (m in tk) { val id = m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                        RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                                            .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
                                    }
                                    val vs = RouteManager.routeVertices()
                                    webViewRef?.evaluateJavascript("drawBuildLine('" + pts + "')", null)
                                }
                            }
                            @JavascriptInterface
                            fun onMapLongPress(lat: Double, lon: Double) {
                                // WAYPOINT-ALWAYS-2026-07-30 (Fred): "waypoints
                                // (long press) should always register to create
                                // waypoints." The guard removed here was added
                                // because popups were not responding, so long
                                // presses were frustrated attempts at a popup that
                                // dropped waypoints instead. The cause is fixed in
                                // this same build (bind always + stopPropagation).
                                android.util.Log.d(
                                    "LongPress",
                                    "onMapLongPress lat=$lat lon=$lon addPointMode=$addPointMode"
                                )
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    pendingWaypoint = Pair(lat, lon)
                                }
                            }
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
                            fun onProximityTap(lat: Double, lon: Double) {
                                // TAPTRACE-2026-07-30: silent until now. The four
                                // display states are logged because every branch
                                // below is gated on them -- a DS_OFF type
                                // contributes nothing and looks like a dead tap.
                                android.util.Log.d(
                                    "ProxTap",
                                    "onProximityTap lat=$lat lon=$lon " +
                                        "trail=$trailState track=$trackState " +
                                        "wpt=$waypointState route=$routeState"
                                )
                                // Query all artifact types near tap point from spatial DB
                                if (routeMode) return  // ROUTETAP-2026-08-02: placing route points -- no artifact popups
                                val radius = 0.0009 // ~100 meters in degrees (ROUTETAP-2026-08-02: was 0.002/200m)
                                val south = lat - radius; val north = lat + radius
                                val west = lon - radius; val east = lon + radius
                                val nlTrail = trailState; val nlTrack = trackState
                                val nlWaypoint = waypointState; val nlRoute = routeState
                                Thread {
                                    try {
                                        SpatialDbManager.init(context)
                                        val names = mutableListOf<String>()
                                        // Trails
                                        if (nlTrail != DS_OFF) {
                                            val trails = SpatialDbManager.queryTrailsByViewport(south, west, north, east, 50)
                                            trails.forEach { t -> t["name"]?.let { names.add("Trail: " + it) } }
                                        }
                                        // Tracks
                                        if (nlTrack != DS_OFF) {
                                            val tracks = SpatialDbManager.queryTracksByViewport(south, west, north, east, 50)
                                            tracks.forEach { t -> t["name"]?.let { names.add("Track: " + it) } }
                                        }
                                        // Waypoints
                                        if (nlWaypoint != DS_OFF) {
                                            val wpts = SpatialDbManager.queryWaypointsByViewport(south, west, north, east, 50)
                                            wpts.forEach { w -> w["name"]?.let { names.add("Waypoint: " + it) } }
                                        }
                                        // Routes
                                        if (nlRoute != DS_OFF) {
                                            val routes = SpatialDbManager.queryRoutesByViewport(south, west, north, east, 50)
                                            routes.forEach { r -> r["name"]?.let { names.add("Route: " + it) } }
                                        }
                                        if (names.isNotEmpty()) {
                                            val html = names.joinToString("<br>")
                                            val escaped = html.replace("'", "\\'")
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                webViewRef?.evaluateJavascript(
                                                    "showProximityPopup(" + lat + "," + lon + ",'" + escaped + "')", null)
                                            }
                                        }
                                    } catch (ex: Exception) {
                                        android.util.Log.e("Proximity", "Query failed: " + ex.message)
                                    }
                                }.start()
                            }
                            @JavascriptInterface
                            fun onTrackTap(id: String) {
                                // [2026-07-02] track tap -> open the shared ArtifactDetailPanel (metrics + SAVE MAPS).
                                //
                                // TAPTRACE-2026-07-30: addPointMode is a KOTLIN flag,
                                // distinct from the JS window.__routeMode. The 07-30
                                // logs proved __routeMode=false at tap time; they say
                                // nothing about this one. If it is stuck true, the
                                // return below is silent -- no log, no panel, no
                                // crash. The guard is KEPT (suppressing a detail
                                // panel mid-route-draw is reasonable) but is now
                                // visible.
                                android.util.Log.d(
                                    "TrackTap", "PLANNER bridge id=$id addPointMode=$addPointMode"
                                )
                                if (addPointMode) {
                                    android.util.Log.d("TrackTap", "PLANNER SUPPRESSED by addPointMode")
                                    return
                                }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.util.Log.d("TrackTap", "PLANNER post -> setting state id=$id")
                                    pendingDetailType = "Tracks"
                                    pendingDetailId = id
                                }
                            }
                            @JavascriptInterface
                            fun onMapReady(n: Double, s: Double, e: Double, w: Double) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    val wv = webViewRef ?: return@post
                                    val src = ConvoyConfig.ACTIVE_TILE_SOURCE
                                    val url = MapSourceManager.getSlotSources().find { it.first == src }?.third
                                        ?: tileSources.firstOrNull()?.third ?: return@post
                                    wv.evaluateJavascript("setTileUrl('" + url + "', '" + src + "')", null)
                                    val ovJson = MapSourceManager.getOverlayJson(src)
                                    if (ovJson != "[]") {
                                        wv.evaluateJavascript("setOverlayLayers('" + ovJson.replace("'", "\'") + "')", null)
                                    }
                                }
                            }
                            @JavascriptInterface
                            fun onViewportChanged(north: Double, south: Double, east: Double, west: Double, zoom: Double) {
                                lastViewportSouth = south; lastViewportWest = west; lastViewportNorth = north; lastViewportEast = east
                                lastViewportZoom = zoom   // SNAPRADIUS-2026-07-30: was discarded
                                viewportSaveHandler.removeCallbacks(viewportSaveRunnable); viewportSaveHandler.postDelayed(viewportSaveRunnable, 400)
                                // GATE: reseed this map's local state from JSON only if the active
                                // map changed since last refresh (else live vars stay authoritative).
                                if (MapStateStore.lastMapProcessed != "planning") {
                                    // ⚠ setRouteMode(false), NOT `__routeMode = false` -- the
                                    // function also reasserts consequences (the popup
                                    // unbind/rebind). The raw flag would clear the value and
                                    // leave whatever the previous state applied, which is
                                    // exactly the "mode off, popups still gone" seen 07-31.
                                    // VIEWPORTMAIN-2026-08-05: was a DIRECT evaluateJavascript on the JavaBridge thread.
                                    // @JavascriptInterface methods run off-main; WebView methods must run on
                                    // main and THROW otherwise -- and the throw aborted the rest of this
                                    // method, so the state reads, lastMapProcessed and processViewport below
                                    // never ran. Post to main and continue. Mirrors SpatialDisplayManager,
                                    // which posts every evaluateJavascript through main.post.
                                    webViewRef?.let { _wv ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            _wv.evaluateJavascript("setRouteMode(false)", null)
                                        }
                                    }
                                    val rs = MapStateStore.readMap("planning")
                                    trailState = rs.types["Trails"]?.state ?: DS_OFF
                                    trackState = rs.types["Tracks"]?.state ?: DS_OFF
                                    waypointState = rs.types["Waypoints"]?.state ?: DS_OFF
                                    routeState = rs.types["Routes"]?.state ?: DS_OFF
                                    trailCheckedIds = MapStateStore.checkedIdsFor(rs, "Trails")
                                    trackCheckedIds = MapStateStore.checkedIdsFor(rs, "Tracks")
                                    waypointCheckedIds = MapStateStore.checkedIdsFor(rs, "Waypoints")
                                    routeCheckedIds = MapStateStore.checkedIdsFor(rs, "Routes")
                                }
                                // Always query — data preloaded, toggle controls visibility
                                val z = zoom.toInt()
                                val limit = if (z < 14) 500 else 2000
                                // [Stage 2] Route the draw through the shared SpatialDisplayManager
                                // instead of the inline copy. State comes from planning's LIVE local
                                // vars (the reseed gate above just populated them from JSON).
                                val states = mapOf(
                                    "Trails" to trailState,
                                    "Tracks" to trackState,
                                    "Waypoints" to waypointState,
                                    "Routes" to routeState
                                )
                                val selectLists = mapOf(
                                    "Trails" to trailCheckedIds,
                                    "Tracks" to trackCheckedIds,
                                    "Waypoints" to waypointCheckedIds,
                                    "Routes" to routeCheckedIds
                                )
                                MapStateStore.lastMapProcessed = "planning"
                                Thread {
                                    SpatialDisplayManager.processViewport(south, west, north, east, z, states, selectLists, webViewRef, context)
                                }.start()
                            }
                        }, "Android")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                // Esri URL is tile/z/y/x but local storage is source/z/x/y.png
                                if (url.contains("/Reference/World_Transportation/MapServer/tile/")) {
                                    // [V2.6-PASS1-READ] Transportation overlay from MBTiles (raw z/x/y)
                                    val parts = url.split("/tile/").lastOrNull()?.split("/")
                                    if (parts != null && parts.size >= 3) {
                                        val z = parts[0].toIntOrNull(); val y = parts[1].toIntOrNull(); val x = parts[2].substringBefore('.').toIntOrNull()
                                        if (z != null && x != null && y != null) {
                                            val bytes = MBTilesStore.readTile("SAT_LABELS_TRANSPORT", z, x, y)
                                            if (bytes != null) {
                                                return android.webkit.WebResourceResponse("image/webp", null, java.io.ByteArrayInputStream(bytes))
                                            }
                                        }
                                    }
                                }
                                if (url.contains("/Reference/World_Boundaries_and_Places/MapServer/tile/")) {
                                    // [V2.6-PASS1-READ] Places overlay from MBTiles (raw z/x/y)
                                    val parts = url.split("/tile/").lastOrNull()?.split("/")
                                    if (parts != null && parts.size >= 3) {
                                        val z = parts[0].toIntOrNull(); val y = parts[1].toIntOrNull(); val x = parts[2].substringBefore('.').toIntOrNull()
                                        if (z != null && x != null && y != null) {
                                            val bytes = MBTilesStore.readTile("SAT_LABELS_PLACES", z, x, y)
                                            if (bytes != null) {
                                                return android.webkit.WebResourceResponse("image/webp", null, java.io.ByteArrayInputStream(bytes))
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                MapSourceManager.init(view?.context ?: return)
                                // Initialize spatial DB for map drawing (sync moved to the dedicated control screen)
                                kotlinx.coroutines.MainScope().launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            SpatialDbManager.init(view?.context ?: return@withContext)
                                        } catch (e: Exception) {
                                            android.util.Log.e("TrackSync", "DB init error: " + e.message)
                                        }
                                    }
                                }
                                // SAT tile URL now set via onMapReady callback (no race condition)
                                val initOverlayJson = MapSourceManager.getOverlayJson("SAT")
                                if (initOverlayJson != "[]") {
                                    view?.evaluateJavascript(
                                        "setOverlayLayers('" + initOverlayJson.replace("'", "\'") + "')", null
                                    )
                                }
                                // Trails loaded on demand via TRAILS button
                                // Center map on device GPS position (matches Convoy Map approach)
                                view?.postDelayed({
                                    // [draft-resolver 2026-08-01] PLANNER ENTRY caller. Outside the
                                    // pmbb branch: a cold launch with no persisted frame must resolve too.
                                    if (!resolverRan) {
                                        resolverRan = true
                                        val u = RouteDraftStore.listDrafts().firstOrNull { it.name == RouteDraftStore.UNNAMED }
                                        if (u == null) {
                                            pendingInventory = true
                                        } else if (u.pointCount < 2) {
                                            RouteDraftStore.deleteDraft(RouteDraftStore.UNNAMED)
                                            android.util.Log.i("RouteModeTrace",
                                                "RESOLVER: deleted unnamed draft, " + u.pointCount + " pts (crash remnant)")
                                            pendingInventory = true
                                        } else {
                                            pendingInventory = true
                                        }
                                    }
                                    // [3.1] persisted-frame-open: planning restores last-session bbox
                                    // PLANNERSEED-2026-08-05: was pmSeed.bbox -- pmSeed is a remember block captured
                                    // at FIRST COMPOSE (MVS:287) and is stale on re-entry. Re-read FRESH.
                                    // Mirrors convoy [Fix2] (ConvoyScreen.kt:728).
                                    val pmbb = MapStateStore.readMap("planning").bbox
                                    if (pmbb != null) {
                                        // PLANNERSEED-2026-08-05: SEED lastViewport* BEFORE draw -- closes the stale
                                        // window for every artifact query that reads these fields until
                                        // onViewportChanged fires. Mirrors ConvoyScreen.kt:730-733.
                                        lastViewportSouth = pmbb.south; lastViewportWest = pmbb.west
                                        lastViewportNorth = pmbb.north; lastViewportEast = pmbb.east
                                        view?.evaluateJavascript(
                                            "fitBounds([" + pmbb.south + "," + pmbb.north + "],[" + pmbb.west + "," + pmbb.east + "])", null
                                        )
                                        android.util.Log.i("PlanMap", "Restored persisted frame: " + pmbb.south + "," + pmbb.west + " .. " + pmbb.north + "," + pmbb.east)
                                        // [Stage 3] Deterministic artifact restore from JSON (not relying on
                                        // fitBounds->moveend->onViewportChanged to draw).
                                        SpatialDisplayManager.drawPersistedState("planning", view, context)
                                        // Crash recovery: a prior session left a route open. Do NOT auto-launch
                                        // (races render). Flag it — an informational popup shows after settle
                                        // telling the user to resume manually via +ROUTE -> In Progress.
                                        // [draft-resolver 2026-08-01] Retired: the resolver above acts on
                                        // the draft directory directly instead of showing a notice that tells
                                        // the user to go do it themselves.
                                        if (routeSeedOpen && !recoveryLaunched) {
                                            recoveryLaunched = true
                                        }
                                        return@postDelayed
                                    }
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
            // ConvoyMapBar removed — sources now in header bar
            // -- UNIFIED SEARCH FAB (2026-06-19) -- below the "?" (top-right), stacks DOWN --
            // Shared component, planning context. webViewRef is a plain WebView? here
            // (planning does not wrap in MutableState). Artifact results route to the
            // existing detail path (pendingDetailType/Id -> ArtifactDetailPanel).
            // Old planning area-search field remains in place this step (removed later).
            UnifiedSearch(
                mapContext = "planning",
                webView = webViewRef,
                context = context,
                onOpenDetail = { type, id ->
                    pendingDetailType = type
                    pendingDetailId = id
                },
                stackDown = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp)
            )

            // -- "?" HELP BUTTON (opens bundled release notes / manual) --
            androidx.compose.material3.Surface(
                onClick = { showDocsChooser = true },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color(0xEE131820),
                contentColor = Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 116.dp, end = 12.dp).size(40.dp)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text("?", fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
            // -- ARTIFACTS FAB (opens WORK WITH ARTIFACTS expanded; hidden while panel open) --
            if (!showArtifactsPanel) {
                androidx.compose.material3.Surface(
                    onClick = { showArtifactsPanel = true },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Color(0xEE131820),
                    contentColor = Color.White,
                    shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp).size(40.dp)
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Text("\u2630", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
            // -- "?" CHOOSER: Release Notes / Full Manual --
            if (showDocsChooser) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showDocsChooser = false },
                    title = { androidx.compose.material3.Text("Help & Info") },
                    text = { androidx.compose.material3.Text("View the release notes or the full user manual.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { showDocsChooser = false; docsView = "notes" }) {
                            androidx.compose.material3.Text("Release Notes")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showDocsChooser = false; docsView = "manual" }) {
                            androidx.compose.material3.Text("Full Manual")
                        }
                    }
                )
            }
            // -- DOC VIEWER: full-screen WebView loading the bundled asset --
            if (docsView != null) {
                val assetFile = if (docsView == "notes") "grouptrack_release_notes.html" else "grouptrack_manual.html"
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF10130F)
                ) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                        ) {
                            androidx.compose.material3.TextButton(onClick = { docsView = null }) {
                                androidx.compose.material3.Text("Close")
                            }
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.allowFileAccess = true
                                    settings.allowFileAccessFromFileURLs = true
                                    webViewClient = WebViewClient()
                                    loadUrl("file:///android_asset/" + assetFile)
                                }
                            },
                            update = { it.loadUrl("file:///android_asset/" + assetFile) }
                        )
                    }
                }
            }
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

            // -- WORK WITH ARTIFACTS (V2.5 scaffold) -- FAB-gated, opens expanded --
            if (showArtifactsPanel) {
            ConvoyArtifactsPanel(
                startExpanded = true,
                onDismiss = { showArtifactsPanel = false },
                isConvoyMap = false,
                onCreateRoute = {
                    // +ROUTE -> choose New vs In-Progress BEFORE the toolbar opens.
                    // Recovery test: if the SAVED state still had a route open, a prior
                    // session left it open (crash/kill never closed cleanly) = recovery.
                    // Test BEFORE setting routeMode on this session.
                    recoveryDetected = (pmSeed.routeState?.open == true)
                    // [routeplus-resolver 2026-08-01] ROUTE+ caller. Resolve any unnamed
                    // draft BEFORE the New/In-Progress choice -- New Route reuses the
                    // UNNAMED filename, so an unresolved remnant would be overwritten.
                    run {
                        val u = RouteDraftStore.listDrafts().firstOrNull { it.name == RouteDraftStore.UNNAMED }
                        if (u == null) {
                            // nothing to resolve
                        } else if (u.pointCount < 2) {
                            RouteDraftStore.deleteDraft(RouteDraftStore.UNNAMED)
                            android.util.Log.i("RouteModeTrace",
                                "RESOLVER: deleted unnamed draft at Route+, " + u.pointCount + " pts (crash remnant)")
                        }
                    }
                    routeMode = true   // route-add selected: panel has no cancel, both picks build a route
                    showInProgressPicker = true
                },
                onSearch = { type, term ->
                    coroutineScope.launch {
                        val raw = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            SpatialDbManager.init(context)
                            SpatialDbManager.searchByName(type, term)
                        }
                        searchResults = assignNameSequence(raw)
                    }
                },
                onResultClick = { type, id, geomHash, name ->
                    val cap = type.replaceFirstChar { it.uppercase() }
                    pendingDetailType = cap
                    pendingDetailId = id
                },
                searchResults = searchResults,
                displayStates = mapOf("Trails" to trailState, "Tracks" to trackState, "Waypoints" to waypointState, "Routes" to routeState),
                onSetState = { typeName, newState ->
                    // Write to ConvoyConfig (synchronous, visible to Thread) AND compose state (UI)
                    when(typeName) {
                        "Trails" -> { trailState = newState; if (newState != DS_SELECTED) { trailCheckedIds = null } }
                        "Tracks" -> { trackState = newState; if (newState != DS_SELECTED) { trackCheckedIds = null } }
                        "Waypoints" -> { waypointState = newState; if (newState != DS_SELECTED) { waypointCheckedIds = null } }
                        "Routes" -> { routeState = newState; if (newState != DS_SELECTED) { routeCheckedIds = null } }
                    }
                    if (newState == DS_OFF) {
                        webViewRef?.evaluateJavascript("hide" + typeName + "()", null)
                    } else {
                        webViewRef?.evaluateJavascript("show" + typeName + "()", null)
                        webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                    }
                    savePlanningState()
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
                            val curState = when(typeName) { "Trails"->trailState; "Tracks"->trackState; "Waypoints"->waypointState; "Routes"->routeState; else->DS_OFF }
                            val curChecked = when(typeName) { "Trails"->trailCheckedIds; "Tracks"->trackCheckedIds; "Waypoints"->waypointCheckedIds; "Routes"->routeCheckedIds; else->null }
                            selectedArtifactIds = when {
                                curState == DS_SELECTED && curChecked != null -> curChecked
                                curState == DS_ON -> list.mapNotNull { it["id"] }.toSet()
                                else -> emptySet()
                            }
                            activeListType = typeName
                        } else {
                            android.widget.Toast.makeText(context, "No " + typeName + " in current view", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onImport = { typeName ->
                    when (typeName) {
                        "Trails" -> onNavigateToTrailSources()
                        "Artifacts" -> onNavigateToTrackImport()
                        // OSM-IMPORT-2026-07-28
                        "OSM" -> showOsmPanel = true
                        else -> onNavigateToTrackImport()
                    }
                }
            )
            }

            // OSM-IMPORT-2026-07-28: OSM import overlay (planner only).
            // Full-screen so the four-stage panel owns the surface while open.
            // BackHandler closes it -- there is no back-stack entry to pop.
            if (showOsmPanel) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showOsmPanel = false
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216))
                ) {
                    OsmImportPanel(
                        onNavigateBack = { showOsmPanel = false }
                    )
                }
            }
            // OSM-C3B-AREA-2026-07-29: closing the OSM panel is the handoff.
            //
            // R1 -- nothing is passed. The panel closed; we read DISK to find
            // out whether it closed because row 3 chose SELECTED AREA. A flag
            // crossing the gap is the routeMode failure.
            LaunchedEffect(showOsmPanel) {
                if (!showOsmPanel) {
                    val pend = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        val s = OsmImportStage.statesInFlight(context).firstOrNull()
                        if (s == null) null
                        else if (OsmImportLedger.pendingScope(context, s) == null) null
                        else if (OsmImportLedger.pendingBbox(context, s) != null) null
                        else Pair(s, OsmImportStage.trailExtent(context, s))
                    }
                    val slug = pend?.first
                    val ext = pend?.second
                    if (slug != null && ext != null) {
                        // ext is [s, w, n, e]
                        val fS = ext[0]; val fW = ext[1]; val fN = ext[2]; val fE = ext[3]
                        // OSM-C3B-FIT-2026-07-29: NO PADDING. Fred 07-29:
                        // "if we remove your 5% of use fit we have no issues."
                        //
                        // MVS:1475 is the FIX 6 loop in the open --
                        // "bbox+10% pad -> lastViewport -> fitBounds" puts the
                        // PADDED box into lastViewport*, savePlanningState
                        // persists it, and the next restore pads THAT. Passing
                        // the raw extent keeps the OSM path out of that loop.
                        // ⚠ Removes our contribution, not the mechanism.
                        android.util.Log.i(
                            "OsmArea",
                            "awaiting draw for $slug -- fit S$fS N$fN W$fW E$fE (no pad)"
                        )
                        webViewRef?.evaluateJavascript(
                            "fitBounds([" + fS + "," + fN + "],[" + fW + "," + fE + "])", null
                        )
                        // PERSIST THE FRAME. savePlanningState() reads
                        // lastViewport*, and ONLY onViewportChanged (MVS:559)
                        // sets those -- it then debounces 400ms and saves.
                        // MVS:669 says fitBounds normally reaches it via
                        // moveend, so this is either the fix or a duplicate the
                        // debounce coalesces. It reads getBounds() AFTER the
                        // move, so it cannot persist a stale frame.
                        kotlinx.coroutines.delay(700)
                        webViewRef?.evaluateJavascript(
                            "try{var b=map.getBounds();" +
                                "Android.onViewportChanged(b.getNorth(),b.getSouth()," +
                                "b.getEast(),b.getWest(),map.getZoom())}catch(e){}",
                            null
                        )
                        // Let the 400ms debounce fire before anything else
                        // touches the map.
                        kotlinx.coroutines.delay(500)
                        android.util.Log.i("OsmArea", "viewport reported + saved")
                        osmAwaitingSlug = slug
                        panelOsmChecked = true
                        downloadBbox = DownloadBbox()
                        // OPEN THE DRAW PANEL LAST. Its own LaunchedEffect sets
                        // pmDownloadedOn = true, which redraws -- previously
                        // racing a map move that had not been persisted yet.
                        showDownloadPanel = true
                    }
                }
            }

            // -- ARTIFACT LIST PANEL (SELECT/EDIT) --
            if (showEntryChoice) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showEntryChoice = false },
                    title = { androidx.compose.material3.Text("Start a route") },
                    text = { androidx.compose.material3.Text(if (recoveryDetected) "Recovery detected. Begin a new route, or resume one in progress?" else "Begin a new route, or resume one in progress?") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showEntryChoice = false
                            // [routeplus-resolver 2026-08-01] Guard: New Route writes to the
                            // UNNAMED filename. If a remnant is still there (resolver dialog not
                            // yet answered), starting fresh would silently overwrite it.
                            if (RouteDraftStore.draftExists(RouteDraftStore.UNNAMED)) {
                                android.widget.Toast.makeText(context,
                                    "A route is already in progress -- resolve it first",
                                    android.widget.Toast.LENGTH_LONG).show()
                                android.util.Log.w("RouteModeTrace", "RESOLVER: New Route blocked, unnamed draft present")
                                return@TextButton
                            }
                            routeLifecycleState = ROUTE_LS_NEW
                            routeMethod = ROUTE_METHOD_P2P
                            routeName = "Auto Saved In Progress"
                            routeNameTaken = false
                            routeEntryNonce++
                            routeMode = true
                            webViewRef?.evaluateJavascript("window.__routeMode=true;setRouteMode(true)", null)  // arm tap-to-place (no name prompt)
                        }) { androidx.compose.material3.Text("New Route") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showEntryChoice = false
                            showInProgressPicker = true
                        }) { androidx.compose.material3.Text("In Progress") }
                    }
                )
            }
            // hoisted verbatim from the toolbar's onSaveCompleted (Option 1):
            // one proven completed-save path, called by BOTH the toolbar and the Save-choice dialog.
            val saveCompleted: () -> Unit = {
                val sLat = lastViewportSouth; val wLon = lastViewportWest
                val nLat = lastViewportNorth; val eLon = lastViewportEast
                kotlinx.coroutines.MainScope().launch {
                    val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        SpatialDbManager.init(context)
                        val lines = SpatialDbManager.queryTrailsByViewport(sLat, wLon, nLat, eLon) +
                                SpatialDbManager.queryTracksByViewport(sLat, wLon, nLat, eLon)
                        val byId = HashMap<String, String>()
                        for (m in lines) {
                            val id = m["trail_id"] ?: m["track_id"]
                            val g = m["geometry"]
                            if (id != null && g != null) byId[id] = g
                        }
                        val built = RouteManager.buildWktAndBbox { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                        if (built != null) {
                            val (wkt, bbox) = built
                            SpatialDbManager.insertRoute(routeName.ifBlank { "Route " + System.currentTimeMillis() }, wkt, bbox[0], bbox[1], bbox[2], bbox[3])
                            true
                        } else false
                    }
                    if (res) {
                        RouteManager.clearRoute()
                        webViewRef?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                        routeMode = false
                        webViewRef?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                    } else {
                        android.widget.Toast.makeText(context, "Need at least 2 points to save", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (showNameDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showNameDialog = false },
                    title = { androidx.compose.material3.Text("Name this route") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = routeName,
                            onValueChange = { routeName = it; routeNameTaken = false },
                            singleLine = true,
                            isError = routeNameTaken,
                            label = { androidx.compose.material3.Text("Route name") }
                        )
                        if (routeNameTaken) androidx.compose.material3.Text(
                            "That name is taken — choose a unique name",
                            color = androidx.compose.ui.graphics.Color(0xFFE86B6B),
                            fontSize = 11.sp
                        )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            // name is REQUIRED -- blank is rejected (no auto-fill).
                            if (routeName.isBlank()) {
                                routeNameTaken = true   // reuse hint slot as 'name required'
                            } else if (RouteDraftStore.isNameTaken(routeName)) {
                                routeNameTaken = true
                            } else {
                                routeNameTaken = false
                                routeLifecycleState = ROUTE_LS_NEW
                                showNameDialog = false
                                routeEntryNonce++
                                routeMode = true
                                webViewRef?.evaluateJavascript("window.__routeMode=true;setRouteMode(true)", null)  // arm tap-to-place
                            }
                        }) { androidx.compose.material3.Text("Start") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showNameDialog = false
                        }) { androidx.compose.material3.Text("Cancel") }
                    }
                )
            }
            if (routeMode) {
                ConvoyRouteToolbar(
                    isConvoyMap = false,
                    vertexCount = RouteManager.routeVertexCount(),
                    routeEntryNonce = routeEntryNonce,
                    selectedMethod = routeMethod,
                    onSelectMethod = { routeMethod = it },
                    onNewRoute = {
                        routeLifecycleState = ROUTE_LS_NEW
                        routeName = "Auto Saved In Progress"
                        routeNameTaken = false
                    },
                    onAddModeChanged = { armed ->
                        addPointMode = armed
                        // `armed` true = Draw selected, false = Artifact selected.
                        // Per Fred: selecting Artifact ends DRAW MODE but the route
                        // SESSION continues -- it is not an exit.
                        webViewRef?.evaluateJavascript("window.__routeMode=" + armed + ";setRouteMode(" + armed + ")", null)
                    },
                    onUndo = {
                        RouteManager.undoVertex()
                        if (routeName.isNotBlank()) {
                            val methodStr = when (routeMethod) { ROUTE_METHOD_DRAW -> "draw"; ROUTE_METHOD_SUGGEST -> "suggest"; else -> "point" }
                            runCatching {
                                if (RouteDraftStore.draftExists(routeName)) RouteDraftStore.overwriteDraft(routeName, methodStr)
                                else RouteDraftStore.writeDraft(routeName, methodStr)
                            }
                        }
                        scope.launch {
                            val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SpatialDbManager.init(context)
                                val tl = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                val tk = SpatialDbManager.queryTracksByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                val byId = HashMap<String, String>()
                                for (m in tl) { val id = m["trail_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                for (m in tk) { val id = m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                                    .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
                            }
                            webViewRef?.evaluateJavascript("drawBuildLine('" + pts + "')", null)
                        }
                    },
                    onSaveCompleted = saveCompleted,
                    routeLifecycleState = routeLifecycleState,
                    onSaveRequested = { saveOrigName = routeName; showSaveChoice = true },
                    onDiscardRequested = { showDiscardChoice = true },
                    onSelectInProgress = { showInProgressPicker = true },
                    onExit = {
                        RouteManager.clearRoute()
                        webViewRef?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                        routeMode = false
                    }
                )
            }

            // ---- Route lifecycle dialogs (Layer 2; store calls stubbed) ----
            if (showSaveChoice) {
                val pts = RouteManager.routeVertexCount()
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showSaveChoice = false },
                    title = { androidx.compose.material3.Text("Save route") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text(
                                if (routeLifecycleState == ROUTE_LS_RESUMED)
                                    "Graduate to a saved route, or keep editing as in-progress."
                                else "Save as a completed route (needs 2+ points), or keep as in-progress."
                            )
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = if (routeName == "Auto Saved In Progress") "" else routeName,
                                onValueChange = { routeName = it },
                                singleLine = true,
                                label = { androidx.compose.material3.Text("Route name (required)") }
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val nm = routeName.trim()
                            if (nm.isBlank() || nm == "Auto Saved In Progress") {
                                android.widget.Toast.makeText(context, "Enter a route name", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (pts < 2) {
                                android.widget.Toast.makeText(context, "Need at least 2 points", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                showSaveChoice = false
                                val oldNm = saveOrigName
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        if (oldNm != nm) RouteDraftStore.renameDraft(oldNm, nm)
                                    }
                                    routeName = nm
                                    kotlinx.coroutines.delay(400)
                                    saveCompleted()
                                }
                            }
                        }) { androidx.compose.material3.Text("Save as completed route") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val nm = routeName.trim()
                            if (nm.isBlank() || nm == "Auto Saved In Progress") {
                                android.widget.Toast.makeText(context, "Enter a route name", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                showSaveChoice = false
                                val oldNm = saveOrigName
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        if (oldNm != nm) RouteDraftStore.renameDraft(oldNm, nm)
                                    }
                                    routeName = nm
                                    RouteManager.clearRoute()
                                    webViewRef?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                                    routeMode = false
                                    // [saveinprogress-trace 2026-08-01] This path disarmed silently -- no trace,
                                    // no toast. Every route-mode write announces itself while instrumentation is in.
                                }
                            }
                        }) { androidx.compose.material3.Text("Save as in progress") }
                    }
                )
            }

            if (showDiscardChoice) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showDiscardChoice = false },
                    title = { androidx.compose.material3.Text("Discard in-progress route") },
                    text = { androidx.compose.material3.Text("Roll back to the last saved draft, or delete this in-progress route entirely.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showDiscardChoice = false
                            // true roll-back: reload the saved draft, drop this session's edits, KEEP building
                            RouteDraftStore.loadIntoRouteManager(routeName)
                            scope.launch {
                                val rbPts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    SpatialDbManager.init(context)
                                    val tl = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                    val tk = SpatialDbManager.queryTracksByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                    val byId = HashMap<String, String>()
                                    for (m in tl) { val id = m["trail_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                    for (m in tk) { val id = m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                    RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                                        .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
                                }
                                webViewRef?.evaluateJavascript("drawBuildLine('" + rbPts + "')", null)
                            }
                        }) { androidx.compose.material3.Text("Roll back") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showDiscardChoice = false
                            RouteDraftStore.deleteDraft(routeName)
                            RouteManager.clearRoute()
                            webViewRef?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                            routeMode = false
                        }) { androidx.compose.material3.Text("Delete in-progress") }
                    }
                )
            }

            if (showDraftResolve) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { },
                    title = { androidx.compose.material3.Text("Unfinished route") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text(
                                "A route with " + draftResolvePts + " points was left unnamed by your last session. Name it to keep it, or discard it."
                            )
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = draftResolveName,
                                onValueChange = { draftResolveName = it; draftResolveErr = "" },
                                singleLine = true,
                                isError = draftResolveErr.isNotEmpty(),
                                label = { androidx.compose.material3.Text("Route name") }
                            )
                            if (draftResolveErr.isNotEmpty()) {
                                androidx.compose.material3.Text(
                                    draftResolveErr,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val nm = draftResolveName.trim()
                            if (nm.isBlank()) {
                                draftResolveErr = "Enter a name"
                            } else if (RouteDraftStore.isNameTaken(nm)) {
                                draftResolveErr = "That name is already used"
                            } else if (RouteDraftStore.renameDraft(RouteDraftStore.UNNAMED, nm)) {
                                android.util.Log.i("RouteModeTrace", "RESOLVER: renamed unnamed draft -> " + nm)
                                showDraftResolve = false
                                pendingInventory = true
                            } else {
                                draftResolveErr = "Rename failed"
                            }
                        }) { androidx.compose.material3.Text("Keep") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            RouteDraftStore.deleteDraft(RouteDraftStore.UNNAMED)
                            android.util.Log.i("RouteModeTrace", "RESOLVER: discarded unnamed draft")
                            showDraftResolve = false
                            pendingInventory = true
                        }) { androidx.compose.material3.Text("Discard") }
                    }
                )
            }
            // [draft-resolver 2026-08-01] Inventory toast -- runs only after the resolver
            // has finished, so the unnamed draft is gone and a just-named one appears on
            // its own merits. 600ms lets the rename/delete land on disk first.
            if (pendingInventory) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(600)
                    val list = RouteDraftStore.listDrafts()
                        .sortedBy { it.createdAt }
                    val msg = if (list.isEmpty()) "No unfinished routes"
                        else list.size.toString() + " unfinished route" + (if (list.size == 1) "" else "s") + (if (list.any { it.name == RouteDraftStore.UNNAMED }) " - 1 needs a name" else "") + " - tap +ROUTE" 
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    android.util.Log.i("RouteModeTrace", "RESOLVER: inventory " + list.size + " draft(s)")
                    pendingInventory = false
                }
            }
            if (recoveryPending) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { recoveryPending = false },
                    title = { androidx.compose.material3.Text("Route recovery") },
                    text = { androidx.compose.material3.Text("A route was left open from your last session. To resume it, tap +ROUTE and choose \"In Progress\".") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            recoveryPending = false
                        }) { androidx.compose.material3.Text("OK") }
                    }
                )
            }

            if (showInProgressPicker) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showInProgressPicker = false; routeMode = false; webViewRef?.evaluateJavascript("window.__routeMode=false;setRouteMode(false)", null) },
                    title = { androidx.compose.material3.Text("Start a new route") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            emulatedDrafts.forEach { di -> val d = di.name
                                androidx.compose.foundation.layout.Row(
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        val od = RouteDraftStore.loadIntoRouteManager(d)
                                        routeName = d
                                        routeMethod = when (od?.method) { "draw" -> ROUTE_METHOD_DRAW; "suggest" -> ROUTE_METHOD_SUGGEST; else -> ROUTE_METHOD_P2P }
                                        routeLifecycleState = ROUTE_LS_RESUMED
                                        showInProgressPicker = false
                                        routeEntryNonce++
                                        routeMode = true
                                        savePlanningState()   // stamp open:true on In-Progress resume (matches New Route @872)
                                        scope.launch {
                                            val rsPts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                SpatialDbManager.init(context)
                                                // BY-ID rederive: fetch the snap-referenced trail/track geometry by the
                                                // lineIds the vertices carry (NOT by viewport) so reload draws the snapped
                                                // shape regardless of where the map is looking. Fixes resume chords.
                                                val verts = RouteManager.routeVertices()
                                                val trailIds = verts.filter { it.snapped && it.lineType == "trail" }.mapNotNull { it.lineId }
                                                val trackIds = verts.filter { it.snapped && it.lineType == "track" }.mapNotNull { it.lineId }
                                                val byId = HashMap<String, String>()
                                                byId.putAll(SpatialDbManager.queryGeomByIds(trailIds, "trail"))
                                                byId.putAll(SpatialDbManager.queryGeomByIds(trackIds, "track"))
                                                RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                                                    .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
                                            }
                                            webViewRef?.evaluateJavascript("window.__routeMode=true;setRouteMode(true); drawBuildLine('" + rsPts + "')", null)
                                            // Second draw after the map settles: the first drawBuildLine can render
                                            // before the build layer is ready (angular); redraw the SAME shape so the
                                            // snapped line shows without needing a manual edit. (Fred: only an edit fixed it.)
                                            webViewRef?.postDelayed({
                                                webViewRef?.evaluateJavascript("drawBuildLine('" + rsPts + "')", null)
                                            }, 400)
                                        }
                                    }) { androidx.compose.foundation.layout.Column { androidx.compose.material3.Text(d); androidx.compose.material3.Text((if (di.createdAt.length >= 10) di.createdAt.substring(0, 10) else di.createdAt) + "  ·  " + di.pointCount + " pts", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) } }
                                    androidx.compose.material3.TextButton(onClick = { draftRenameTarget = d; draftRenameText = if (d == RouteDraftStore.UNNAMED) "" else d; draftRenameErr = "" }) { androidx.compose.material3.Text("Rename") }
                                    androidx.compose.material3.TextButton(onClick = {
                                        RouteDraftStore.deleteDraft(d)
                                        draftListTick++   // refresh the picker list
                                    }) { androidx.compose.material3.Text(
                                        "Delete",
                                        color = androidx.compose.ui.graphics.Color(0xFFE86B6B)
                                    ) }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            if (RouteDraftStore.draftExists(RouteDraftStore.UNNAMED)) {
                                android.widget.Toast.makeText(context, "Rename or delete \"" + RouteDraftStore.UNNAMED + "\" before starting a new route.", android.widget.Toast.LENGTH_LONG).show()
                                return@TextButton
                            }
                            showInProgressPicker = false
                            routeLifecycleState = ROUTE_LS_NEW
                            routeMethod = ROUTE_METHOD_P2P
                            routeName = RouteDraftStore.UNNAMED
                            routeNameTaken = false
                            routeEntryNonce++
                            routeMode = true
                            webViewRef?.evaluateJavascript("window.__routeMode=true;setRouteMode(true)", null)
                        }) { androidx.compose.material3.Text("+ Plan a New Route") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showInProgressPicker = false; routeMode = false; webViewRef?.evaluateJavascript("window.__routeMode=false;setRouteMode(false)", null) }) {
                            androidx.compose.material3.Text("Cancel")
                        }
                    }
                )
            }
            if (draftRenameTarget != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { draftRenameTarget = null },
                    title = { androidx.compose.material3.Text("Rename route") },
                    text = { androidx.compose.foundation.layout.Column { androidx.compose.material3.OutlinedTextField(value = draftRenameText, onValueChange = { draftRenameText = it; draftRenameErr = "" }, singleLine = true, isError = draftRenameErr.isNotEmpty(), label = { androidx.compose.material3.Text("Route name") }); if (draftRenameErr.isNotEmpty()) androidx.compose.material3.Text(draftRenameErr, color = androidx.compose.material3.MaterialTheme.colorScheme.error) } },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { val nm = draftRenameText.trim(); val old = draftRenameTarget; if (nm.isBlank()) draftRenameErr = "Enter a name" else if (RouteDraftStore.isNameTaken(nm)) draftRenameErr = "That name is already used" else if (old != null && RouteDraftStore.renameDraft(old, nm)) { draftRenameTarget = null; draftListTick++ } else draftRenameErr = "Rename failed" }) { androidx.compose.material3.Text("Save") } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { draftRenameTarget = null }) { androidx.compose.material3.Text("Cancel") } }
                )
            }
            if (activeListType != null) {
                ArtifactListPanel(
                    mapKey = "planning",
                    fitWebView = webViewRef,
                    artifactType = activeListType!!,
                    artifacts = artifactList,
                    selectedIds = selectedArtifactIds,
                    onDismiss = {
                        val allIds = artifactList.mapNotNull { it["id"] }.toSet()
                        val checked = selectedArtifactIds
                        val newState = when {
                            checked.isEmpty() -> DS_OFF
                            checked.containsAll(allIds) && allIds.size == checked.size -> DS_ON
                            else -> DS_SELECTED
                        }
                        val t = activeListType ?: ""
                        when(t) {
                            "Trails" -> { trailState = newState; trailCheckedIds = if (newState == DS_SELECTED) checked else null }
                            "Tracks" -> { trackState = newState; trackCheckedIds = if (newState == DS_SELECTED) checked else null }
                            "Waypoints" -> { waypointState = newState; waypointCheckedIds = if (newState == DS_SELECTED) checked else null }
                            "Routes" -> { routeState = newState; routeCheckedIds = if (newState == DS_SELECTED) checked else null }
                        }
                        if (newState == DS_OFF) {
                            webViewRef?.evaluateJavascript("hide" + t + "()", null)
                        } else {
                            webViewRef?.evaluateJavascript("show" + t + "()", null)
                            webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                        }
                        savePlanningState()
                        activeListType = null
                    },
                    onToggleItem = { id, selected ->
                        selectedArtifactIds = if (selected) selectedArtifactIds + id else selectedArtifactIds - id
                    },
                    onSelectAll = {
                        selectedArtifactIds = artifactList.mapNotNull { it["id"] }.toSet()
                    },
                    onDeselectAll = {
                        selectedArtifactIds = emptySet()
                    },
                    onOpenDetail = { t, id -> activeListType = null; pendingDetailType = t; pendingDetailId = id }
                )
            }
            android.util.Log.d(
                "DetailGate",
                "PLANNER gate type=$pendingDetailType id=$pendingDetailId"
            )
            if (pendingDetailId != null && pendingDetailType != null) {
                ArtifactDetailPanel(
                    artifactType = pendingDetailType!!,
                    id = pendingDetailId!!,
                    mapKey = "planning",
                    fitWebView = webViewRef,
                    onLoadDetail = { t, did -> SpatialDbManager.getArtifactDetail(t, did) },
                    onLoadAliases = { t, did -> SpatialDbManager.getAliasesFor(t, did) },
                    // [2026-06-20] Full action parity. Logic lifted from planning's
                    // ArtifactListPanel handlers; keyed off pendingDetailType (detail can
                    // open from SEARCH where activeListType is null). Refresh: triggerViewportUpdate().
                    onRename = { id, newName ->
                        scope.launch { ConvoyArtifactOps.rename(context, (pendingDetailType ?: return@launch), id, newName); webViewRef?.evaluateJavascript("triggerViewportUpdate()", null) }
                    },
                    onDelete = { id ->
                        scope.launch { ConvoyArtifactOps.delete(context, (pendingDetailType ?: return@launch), id); webViewRef?.evaluateJavascript("triggerViewportUpdate()", null) }
                    },
                    onShare = { id -> scope.launch { ConvoyArtifactOps.share(context, (pendingDetailType ?: return@launch), id) } },
                    onExport = { id -> scope.launch { ConvoyArtifactOps.export(context, (pendingDetailType ?: return@launch), id) } },
                    onDownloadMaps = { hash ->
                        Thread {
                            val bb = SpatialDbManager.getTrackBbox(context, hash)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (bb != null && bb.isValid) {
                                    pendingDetailId = null
                                    pendingDetailType = null
                                    downloadBbox = bb
                                    showDownloadConfirm = true
                                } else {
                                    android.widget.Toast.makeText(context,
                                        "No map area for this track",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    },
                    // CORRIDOR-WIRING-2026-07-24: same prompt as area, different submission.
                    // The bbox below is shown to the dialog ONLY so it has
                    // something to display - it is the AREA figure, i.e. exactly
                    // the number the corridor is meant to beat. The real
                    // corridor count is logged by enqueueCorridor.
                    onDownloadCorridor = { hash ->
                        Thread {
                            val bb = SpatialDbManager.getTrackBbox(context, hash)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (bb != null && bb.isValid) {
                                    pendingDetailId = null
                                    pendingDetailType = null
                                    pendingCorridorHash = hash
                                    downloadBbox = bb
                                    showDownloadConfirm = true
                                } else {
                                    android.widget.Toast.makeText(context,
                                        "No geometry for this track",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    },
                    onChangeType = { id, newType ->
                        scope.launch { ConvoyArtifactOps.changeType(context, id, newType); webViewRef?.evaluateJavascript("triggerViewportUpdate()", null) }
                    },
                    onDeleteAlias = { aliasId -> scope.launch { ConvoyArtifactOps.deleteAlias(context, aliasId) } },
                    onDismiss = { fittedType, fittedId ->
                        if (fittedType != null && fittedId != null) {
                            // [FIT 2026-06-18] Emulate manual row-select on LIVE vars (parity with
                            // convoy). Set this type SELECTED with the fitted id; saveConvoyState
                            // then writes the row (no clobber) and the SEL/EDIT panel reflects it.
                            val sel = setOf(fittedId)
                            // FIT = one artifact: all other types OFF, fitted type SELECTED.
                            trailState = DS_OFF; trailCheckedIds = null
                            trackState = DS_OFF; trackCheckedIds = null
                            waypointState = DS_OFF; waypointCheckedIds = null
                            routeState = DS_OFF; routeCheckedIds = null
                            when (fittedType) {
                                "Trails"    -> { trailState = DS_SELECTED; trailCheckedIds = sel }
                                "Tracks"    -> { trackState = DS_SELECTED; trackCheckedIds = sel }
                                "Waypoints" -> { waypointState = DS_SELECTED; waypointCheckedIds = sel }
                                "Routes"    -> { routeState = DS_SELECTED; routeCheckedIds = sel }
                            }
                            // [FIT recenter 2026-06-20] Restore-to-artifact: bbox+10% pad -> lastViewport -> fitBounds,
                            // so save + the getBounds() redraw below both use the artifact frame (no stale clobber).
                            run {
                                val _bb = SpatialDbManager.bboxForArtifact(fittedType, fittedId)
                                if (_bb != null) {
                                    val _s=_bb[0]; val _w=_bb[1]; val _n=_bb[2]; val _e=_bb[3]
                                    val _latPad=((_n-_s).let{ if(it>0.0) it*0.10 else 0.01 })
                                    val _lonPad=((_e-_w).let{ if(it>0.0) it*0.10 else 0.01 })
                                    val _fS=_s-_latPad; val _fN=_n+_latPad; val _fW=_w-_lonPad; val _fE=_e+_lonPad
                                    lastViewportSouth=_fS; lastViewportWest=_fW; lastViewportNorth=_fN; lastViewportEast=_fE
                                    webViewRef?.evaluateJavascript("fitBounds(["+_fS+","+_fN+"],["+_fW+","+_fE+"])", null)
                                }
                            }
                            savePlanningState()
                            webViewRef?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        } else {
                            val rs = MapStateStore.readMap("planning")
                            trailState = rs.types["Trails"]?.state ?: DS_OFF
                            trackState = rs.types["Tracks"]?.state ?: DS_OFF
                            waypointState = rs.types["Waypoints"]?.state ?: DS_OFF
                            routeState = rs.types["Routes"]?.state ?: DS_OFF
                            trailCheckedIds = MapStateStore.checkedIdsFor(rs, "Trails")
                            trackCheckedIds = MapStateStore.checkedIdsFor(rs, "Tracks")
                            waypointCheckedIds = MapStateStore.checkedIdsFor(rs, "Waypoints")
                            routeCheckedIds = MapStateStore.checkedIdsFor(rs, "Routes")
                        }
                        pendingDetailId = null; if (fittedType != null) pendingDetailType = null
                    }
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
                                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        SpatialDbManager.init(context)
                                        SpatialDbManager.syncTracksFromFiles(context)
                                    }
                                    android.widget.Toast.makeText(context, "Sync: ${r.processed} processed, ${r.addedRenamed} added, ${r.renamed} renamed (see track_sync.log)", android.widget.Toast.LENGTH_LONG).show()
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

            // PHASE0-QUEUE-PANEL-2026-07-24: this bottom-left render is the RUNNING-JOBS
            // INDICATOR only - active count, queued count, tiles remaining.
            // It no longer expands in place (expanded = false); tapping it
            // OPENS THE QUEUE MANAGER, which is where every control lives.
            DownloadQueuePanel(
                expanded = false,
                onToggle = { pmQueuesOpen = true },
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
                    bbox = downloadBbox,
                    slots = slots,
                    onProceed = { bbox, selectedSlots, replace ->
                        showDownloadConfirm = false
                        showDownloadPanel = false
                        // CORRIDOR-WIRING-2026-07-24: non-null hash = corridor job. ONE ENTRY
                        // PER SOURCE, matching how area jobs already appear - so
                        // progress is per-source and cancelling SAT leaves TOPO.
                        val corrHash = pendingCorridorHash
                        pendingCorridorHash = null
                        Thread {
                            if (corrHash != null) {
                                for (slot in selectedSlots) {
                                    DownloadQueueManager.enqueueCorridor(
                                        context, corrHash, slot, replace
                                    )
                                }
                            } else {
                                DownloadQueueManager.submitDownload(
                                    context, bbox.north, bbox.south, bbox.east, bbox.west,
                                    selectedSlots, replace
                                )
                            }
                        }.start()
                    },
                    // CORRIDOR-WIRING-2026-07-24: clear on cancel too, or the NEXT area
                    // download would be treated as a corridor job.
                    onCancel = { showDownloadConfirm = false; pendingCorridorHash = null },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            }
            // ── Auto show/hide download overlays when panel opens/closes ──
            LaunchedEffect(showDownloadPanel) {
                if (showDownloadPanel) {
                    pmDownloadedOn = true
                    // OSM-C3B-AREA-2026-07-29: derive the preselect from disk
                    // at panel open (R5 -- derivation runs at MOMENTS). Covers
                    // arriving here by any route, not just from row 3.
                    val await = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        val s = OsmImportStage.statesInFlight(context).firstOrNull()
                        if (s != null &&
                            OsmImportLedger.pendingScope(context, s) != null &&
                            OsmImportLedger.pendingBbox(context, s) == null
                        ) s else null
                    }
                    if (await != null) {
                        osmAwaitingSlug = await
                        panelOsmChecked = true
                    }
                } else {
                    pmDownloadedOn = false
                }
            }
            // OSM-C3B-AREA-2026-07-29: THE DRAW COMPLETING IS THE SUBMIT.
            //
            // There is no OSM execute button. Fred 07-29: "relaunch import and
            // close area selection when the area is processed." onAreaSelected
            // is a @JavascriptInterface with no slug and no scope, and disk I/O
            // there would run on the JS thread -- so it keeps doing only what it
            // does today (set downloadBbox) and the reaction happens here.
            LaunchedEffect(downloadBbox, panelOsmChecked, osmAwaitingSlug) {
                val slug = osmAwaitingSlug
                if (slug != null && panelOsmChecked && downloadBbox.isValid) {
                    val ok = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        OsmImportLedger.setPendingBbox(
                            context, slug,
                            s = downloadBbox.south,
                            w = downloadBbox.west,
                            n = downloadBbox.north,
                            e = downloadBbox.east
                        )
                        // Read back. The gate must show what LANDED.
                        OsmImportLedger.pendingBbox(context, slug) != null
                    }
                    android.util.Log.i(
                        "OsmArea",
                        "drawn bbox written for $slug ok=$ok " +
                            "S${downloadBbox.south} W${downloadBbox.west} " +
                            "N${downloadBbox.north} E${downloadBbox.east}"
                    )
                    // Close the draw panel, reopen the import panel. Row 3 now
                    // finds a bbox and gates.
                    osmAwaitingSlug = null
                    panelOsmChecked = false
                    showDownloadPanel = false
                    showOsmPanel = true
                }
            }
            // ── Floating download execute button (outside panel for landscape) ──
            if (showDownloadPanel && downloadBbox.isValid &&
                (panelTilesChecked || panelTrailsChecked || panelRemoveTilesChecked)) {
                val execLabel = if (panelRemoveTilesChecked && !panelTilesChecked && !panelTrailsChecked)
                    "Remove Tiles" else "Download Selected"
                val execColor = if (panelRemoveTilesChecked && !panelTilesChecked && !panelTrailsChecked)
                    Color(0xFFf85149) else Color(0xFF3fb950)
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xEE131820),
                    shadowElevation = 8.dp
                ) {
                    Text(
                        execLabel,
                        color = execColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                if (panelTrailsChecked && downloadBbox.isValid) {
                                    // LAUNCHMODE-FIX-2026-07-27: the panel callback at ~:1711
                                    // sets this; THIS path never did, so the trail screen opened
                                    // on SELECT_SOURCE and ignored the area just written.
                                    TrailImporter.launchMode = TrailImporter.LaunchMode.BY_AREA
                                    TrailImporter.writePendingArea(
                                        downloadBbox.north, downloadBbox.south,
                                        downloadBbox.east, downloadBbox.west)
                                    onNavigateToTrailSources()
                                }
                                if (panelTilesChecked && downloadBbox.isValid) {
                                    showDownloadConfirm = true; showDownloadPanel = false
                                }
                                // DELETE-AREA-2026-07-25: the red "Remove Tiles" label
                                // existed but had no handler - the button did nothing.
                                if (panelRemoveTilesChecked && downloadBbox.isValid) {
                                    showRemoveTilesConfirm = true; showDownloadPanel = false
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                    )
                }
            }
            // DELETE-AREA-2026-07-25: gated confirm. States the area and an upper
            // bound on what will go. The count shown is the GEOMETRY count
            // (tiles the box covers x layers) - what COULD be there - because a
            // live COUNT(*) on the main thread would jank the UI. The worker
            // reports what was actually removed.
            if (showRemoveTilesConfirm && downloadBbox.isValid) {
                val delTiles = ConvoyTileCalculator
                    .calculateTiles(downloadBbox.north, downloadBbox.south,
                                    downloadBbox.east, downloadBbox.west).size
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showRemoveTilesConfirm = false },
                    title = { Text("Remove tiles?", fontFamily = FontFamily.Monospace) },
                    text = {
                        Text(
                            "Delete downloaded map tiles in this area from ALL sources.\n\n" +
                            String.format("%.3f\u00b0N to %.3f\u00b0N\n",
                                downloadBbox.south, downloadBbox.north) +
                            "Up to " + delTiles + " tiles per layer.\n\n" +
                            "This cannot be undone.",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp
                        )
                    },
                    confirmButton = {
                        Text("REMOVE TILES",
                            color = Color(0xFFf85149),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                showRemoveTilesConfirm = false
                                panelRemoveTilesChecked = false
                                val bb = downloadBbox
                                Thread {
                                    DownloadQueueManager.submitDelete(
                                        context, bb.north, bb.south, bb.east, bb.west)
                                }.start()
                            }.padding(12.dp))
                    },
                    dismissButton = {
                        Text("CANCEL",
                            color = Color(0xFF8B949E),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                showRemoveTilesConfirm = false
                            }.padding(12.dp))
                    },
                    containerColor = Color(0xFF131820)
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
                    osmChecked = panelOsmChecked,
                    onOsmCheckedChange = { panelOsmChecked = it },
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
                        TrailImporter.launchMode = TrailImporter.LaunchMode.BY_AREA
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
                                        run {
                                            // [V2.6-PASS1-S4-VIEWER] DB-backed coverage (raw z/x/y at z14)
                                            val z = 14; val n = 1 shl z
                                            for ((x, y) in MBTilesStore.xyAtZoom("SAT", z)) {
                                                val tN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n))))
                                                val tS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / n))))
                                                val tW = x.toDouble() / n * 360.0 - 180.0
                                                val tE = (x + 1).toDouble() / n * 360.0 - 180.0
                                                bounds.add("{\"n\":$tN,\"s\":$tS,\"e\":$tE,\"w\":$tW}")
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
                    onShowMapsInQueue = { show ->
                        val wv = webViewRef
                        if (show && wv != null) {
                            val q = DownloadQueueManager.queue.value
                            val pending = q.filter { it.status == QueueStatus.DOWNLOADING || it.status == QueueStatus.QUEUED }
                            val bounds = pending.map { e ->
                                "{" + "\"n\":" + e.north + ",\"s\":" + e.south + ",\"e\":" + e.east + ",\"w\":" + e.west + ",\"label\":\"" + e.label.replace("\"", "") + "\"}"
                            }
                            val json = "[" + bounds.joinToString(",") + "]"
                            wv.evaluateJavascript("showQueuedAreas(" + json + ")", null)
                        } else {
                            webViewRef?.evaluateJavascript("clearQueuedAreas()", null)
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

        // -- Legend (key button + popup; popup escapes Column width) --
        Box(
            modifier = Modifier
                .align(Alignment.Start)
                .padding(start = 10.dp, bottom = 12.dp)
                .navigationBarsPadding()
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xCC1A2E4A),
                modifier = Modifier.size(32.dp).clickable { legendExpanded = !legendExpanded }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.VpnKey,
                        contentDescription = "Legend",
                        tint = Color(0xFFAABBCC),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (legendExpanded) {
                Popup(onDismissRequest = { legendExpanded = false }) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xF2000000),
                        shadowElevation = 8.dp
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { legendExpanded = false }
                                    .padding(bottom = 4.dp)
                            ) {
                                Text("Legend", color = Color(0xFF8FD0FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.width(6.dp))
                                Text("\u00D7", color = Color(0xFF8FD0FF), fontSize = 12.sp)
                            }
                            LegendItem(Color(0xFF00AAFF), "OHV / Road", "line")
                            Spacer(Modifier.height(3.dp))
                            LegendItem(Color(0xFFFF8800), "Hiking & Biking", "line")
                            Spacer(Modifier.height(3.dp))
                            LegendItem(Color(0xFFFFCC00), "Hiking Only", "line")
                            Spacer(Modifier.height(3.dp))
                            LegendItem(Color(0xFFAA44FF), "Biking Only", "line")
                            Spacer(Modifier.height(3.dp))
                            LegendItem(Color(0xFF00FFFF), "Paved / other", "line")
                            Spacer(Modifier.height(5.dp))
                            LegendItem(Color(0xFF39FF14), "Track", "dash")
                            Spacer(Modifier.height(3.dp))
                            LegendItem(Color(0xFFFF00FF), "Route", "dash")
                            Spacer(Modifier.height(3.dp))
                            LegendItem(Color(0xFF2ECC40), "Waypoint", "pin")
                        }
                    }
                }
            }
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
