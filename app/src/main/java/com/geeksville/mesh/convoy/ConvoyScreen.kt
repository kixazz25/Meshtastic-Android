package com.geeksville.mesh.convoy
// [V2.6a-WEBP] read intercepts serve image/webp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
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
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
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

// RMTRACE-2026-10-01: diagnostic -- logs every route-mode transition with the site that made it. Remove with the fix.
private fun rmTrace(v: Boolean, site: String): Boolean { android.util.Log.i("ROUTEMODE", "$site -> $v"); return v }

/**
 * ConvoyScreen — IMP-001 Task 4.2 + 5.1 + 5.2 + 5.3 + 5.4
 * Full-screen WebView/Leaflet map + HUD strip.
 */
enum class RecordingState { CHECK_IN, IDLE, RECORDING, PAUSED, SLEEPING }   // CHECKIN-2026-09-27: CHECK_IN first

// Display state constants for spatial DB artifacts
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

// RIDEMAPSTATE-2026-10-05 (Fred): the ride map's state lives as long as its WebView (viewModel.persistentWebView), not as long as
// one composition of this screen. The kept WebView's JavaScript keeps calling the FIRST visit's bridge (Android swaps a
// JavaScript interface in only on the next page load), so every value a bridge writes must be shared by all visits.
@Suppress("UNCHECKED_CAST")
private fun <T> ConvoyViewModel.rideMapState(key: String, init: () -> T): androidx.compose.runtime.MutableState<T> =
    convoyRideMapStates.getOrPut(key) { androidx.compose.runtime.mutableStateOf(init()) } as androidx.compose.runtime.MutableState<T>

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoyScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToCreateEvent: () -> Unit = {},
    onNavigateToSettingsPanel: () -> Unit = {},
    onNavigateToTrackExport: () -> Unit = {},
    onNavigateToTrackImport: () -> Unit = {},
    onNavigateToMapViewer: () -> Unit = {},
    // TRACKRIDE-CONVOY-2026-10-05 (Fred): opens the ride form for a route id. REQUIRED (CODE RULE 1) -- no default, so the compiler
    // checks every caller wires it; the ride map's detail panel uses it for ADD A RIDE and CREATE RIDE.
    onAddRide: (String, String) -> Unit,
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
    var showConfirmDelete by remember { mutableStateOf(false) }  // unnamed-track delete confirm
    var showStoragePermissionDialog by remember { mutableStateOf(false) }  // 2.6f: authority owned by ConvoyAuthorityGate; launch prompt neutered

    // FT-01 FIX: Check all-files access on EVERY resume (not just first composition)
    // Re-checks when user returns from Settings after Grant button, and on every app restart
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // 2.6f: launch-time all-files prompt neutered - ConvoyAuthorityGate owns authority
            if (false) {
                showStoragePermissionDialog = true
            }
        }
        onPauseOrDispose { }
    }
    var pendingTrackName by viewModel.pendingTrackName
    val context = LocalContext.current
    // PLANGATE-2026-08-12C: live internet state for the PLAN button.
    //
    // The planning map cannot serve offline tiles, so opening it without a
    // connection gives a blank screen. Rather than let that happen, the button
    // reports the state and refuses.
    //
    // ⚠ A CALLBACK, NOT A ONE-TIME READ: a rider who loses signal mid-ride has
    // to see the button change without restarting. Registered and unregistered
    // with the composable.
    var hasInternet by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        fun probe(): Boolean {
            val net = cm?.activeNetwork ?: return false
            return cm.getNetworkCapabilities(net)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        hasInternet = probe()
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: android.net.Network) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { hasInternet = probe() }
            }
            override fun onLost(n: android.net.Network) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { hasInternet = probe() }
            }
        }
        try { cm?.registerDefaultNetworkCallback(cb) } catch (e: Exception) {
            android.util.Log.e("PlanGate", "PLANGATE-2026-08-12C register failed: ${e.message}")
        }
        onDispose { try { cm?.unregisterNetworkCallback(cb) } catch (e: Exception) { } }
    }
    MapSourceManager.init(context)
    SpatialDbManager.init(context)

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
    var queuesOpen by remember { mutableStateOf(false) }
    // CORRIDOR-WIRING-2026-07-24: see the planning screen - non-null means the pending
    // confirm is a CORRIDOR job. Cleared on proceed and on cancel.
    var pendingCorridorHash by remember { mutableStateOf<String?>(null) }
    var trailsLoaded by remember { mutableStateOf(false) }
    var tracksOn by remember { mutableStateOf(true) }
    var showConvoyTrackPicker by remember { mutableStateOf(false) }
    // [V2.6a-CONVOY-DLPANEL] standard download-confirm panel state (mirror of viewer)
    var downloadBbox by remember { mutableStateOf(DownloadBbox()) }
    var showDownloadConfirm by remember { mutableStateOf(false) }
    var convoyTrackFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var convoyLoadedTracks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var convoyTrackSearch by remember { mutableStateOf("") }

    // MAPKEYS-2026-09-01: the convoy map's own key state, and the saved filter
    // state read BEFORE the map draws. ⛔ Fred, 09-01: "not just a frame,
    // everything until something changes that causes the filter to recalc" --
    // an unloaded filter shows everything for the whole session, and a rider
    // whose panel says private-off over a map full of private roads is a
    // support call. Idempotent: the planner may have loaded it already.
    var mapKeysOpen by remember { mutableStateOf(false) }
    // TRAILSELECT-2026-09-02: the category filter, opened from Map
    // Features > Trails > SELECT -- the same place every other artifact
    // type has always chosen what shows.
    var showTrailFilter by remember { mutableStateOf(false) }
    remember { TrailFilterState.load(); true }
        var showMapSettings by remember { mutableStateOf(false) }
        // Spatial DB display states — per-map state from MapStateStore (independent of planning map)
        val cmSeed = remember { MapStateStore.readMap("convoy") }
        var trailState by viewModel.rideMapState("trailState") { cmSeed.types["Trails"]?.state ?: DS_OFF }   // RIDEMAPSTATE-2026-10-05
        var trackState by viewModel.rideMapState("trackState") { cmSeed.types["Tracks"]?.state ?: DS_OFF }   // RIDEMAPSTATE-2026-10-05
        var waypointState by viewModel.rideMapState("waypointState") { cmSeed.types["Waypoints"]?.state ?: DS_OFF }   // RIDEMAPSTATE-2026-10-05
        var routeState by viewModel.rideMapState("routeState") { cmSeed.types["Routes"]?.state ?: DS_OFF }   // RIDEMAPSTATE-2026-10-05
        var searchResults by remember { mutableStateOf(emptyList<ArtifactResult>()) }
        var pendingDetailId by viewModel.rideMapState<String?>("pendingDetailId") { null }   // RIDEMAPSTATE-2026-10-05
        var pendingDetailType by viewModel.rideMapState<String?>("pendingDetailType") { null }   // RIDEMAPSTATE-2026-10-05
        var pendingWaypoint by viewModel.rideMapState<Pair<Double, Double>?>("pendingWaypoint") { null }   // RIDEMAPSTATE-2026-10-05

        // CONVOY-LONGPRESS-2026-07-31: long-press detection state.
        //
        // ⚠ ARRAYS, NOT MutableState, DELIBERATELY. The touch listener is
        // installed inside `update = { }`, which re-runs on every
        // recomposition. MutableState mutated from a touch event would trigger
        // recomposition -> re-run update -> reinstall the listener MID-GESTURE.
        // Arrays are stable references whose contents change without
        // recomposing.
        val lpHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
        val lpRunnable = remember { arrayOfNulls<Runnable>(1) }
        val lpDown = remember { FloatArray(2) }
        // ROUTE BUILDER: route mode active -> Route+ toolbar shown (read by next patch)
        var routeMode by viewModel.rideMapState("routeMode") { false }   // RIDEMAPSTATE-2026-10-05
        var routeMethod by remember { mutableStateOf(ROUTE_METHOD_P2P) }
        var routeName by remember { mutableStateOf("") }
        var showRouteNameDialog by remember { mutableStateOf(false) }
        // route lifecycle (Layer 2): launch state fixed at New / Select-In-Progress
        var routeLifecycleState by remember { mutableStateOf(ROUTE_LS_NEW) }
        var showSaveChoice by remember { mutableStateOf(false) }
        var showDiscardChoice by remember { mutableStateOf(false) }
        var showInProgressPicker by remember { mutableStateOf(false) }
        var showEntryChoice by remember { mutableStateOf(false) }
        var routeNameTaken by remember { mutableStateOf(false) }
        // live In-Progress list: real draft names from RouteDraftStore (refreshed on draftListTick)
        var draftListTick by remember { mutableStateOf(0) }
        val emulatedDrafts = remember(draftListTick) { RouteDraftStore.listDrafts().map { it.name } }
        // WPTTAP-2026-09-30 (Fred): a new waypoint starts as a TRAILHEAD (routes and rides need one); Other asks once.
        var newWaypointType by remember { mutableStateOf("trailhead") }
        var otherConfirm by remember { mutableStateOf(false) }
        var newWaypointName by remember { mutableStateOf("") }

        var lastViewportSouth by viewModel.rideMapState("lastViewportSouth") { 0.0 }   // RIDEMAPSTATE-2026-10-05
        var lastViewportWest by viewModel.rideMapState("lastViewportWest") { 0.0 }   // RIDEMAPSTATE-2026-10-05
        var lastViewportNorth by viewModel.rideMapState("lastViewportNorth") { 0.0 }   // RIDEMAPSTATE-2026-10-05
        var lastViewportEast by viewModel.rideMapState("lastViewportEast") { 0.0 }   // RIDEMAPSTATE-2026-10-05
        var artifactList by remember { mutableStateOf<List<Map<String, String?>>>(emptyList()) }
        var selectedArtifactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var activeListType by remember { mutableStateOf<String?>(null) }
        var trailCheckedIds by viewModel.rideMapState("trailCheckedIds") { MapStateStore.checkedIdsFor(cmSeed, "Trails") }   // RIDEMAPSTATE-2026-10-05
        var trackCheckedIds by viewModel.rideMapState("trackCheckedIds") { MapStateStore.checkedIdsFor(cmSeed, "Tracks") }   // RIDEMAPSTATE-2026-10-05
        var waypointCheckedIds by viewModel.rideMapState("waypointCheckedIds") { MapStateStore.checkedIdsFor(cmSeed, "Waypoints") }   // RIDEMAPSTATE-2026-10-05
        var routeCheckedIds by viewModel.rideMapState("routeCheckedIds") { MapStateStore.checkedIdsFor(cmSeed, "Routes") }   // RIDEMAPSTATE-2026-10-05
        // [convoy-routemode-reset 2026-08-01] One-shot log flag. The route-mode reset fires on
        // every viewport event; this keeps the trace to one line per entry. remember{} scope
        // means it clears on re-entry, so each convoy entry logs exactly once.
        var routeModeResetLogged by remember { mutableStateOf(false) }
        // Persist convoy map state to JSON. Checkboxes are state-controlled (rows carry
        // per-item checked status). Geometry is refreshed by the viewport query separately.
        // Convoy map has no download checkboxes -> default PanelBoxes.
        fun saveConvoyState() {
            // [convoy-savestate-reset 2026-08-01] The JSON write is the proof the panel
            // refreshed. Route mode is forced OFF here, riding along with that event --
            // convoy has no per-entry hook (persistent composable, page loads once), so
            // this is the repeating event that exists. WRITE-ONLY: never read back.
            // NOTE: no JS reset here -- webViewRef is declared below this fun and cannot
            // be forward-referenced. The planner's entry reset covers the trip back.
            // [Fix1] Mirror planning: SELECTED rows from persistent per-type checked-id set
            // (NOT activeListType-gated artifactList) -> no clobber of non-active types.
            fun rowsFor(type: String): List<MapStateStore.Row> {
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
            // [Fix1] Save the current frame (lastViewport* = the frame at save time, during
            // active use) so drawPersistedState can restore it on re-entry.
            MapStateStore.saveMap("convoy", MapStateStore.MapSnapshot(types, MapStateStore.PanelBoxes(), MapStateStore.BBox(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast), null))
        }
    // [viewport-save 2026-08-13] debounced viewport-settle save (mirror planning MVS:260-261/540) —
    // convoy previously never persisted the frame on pan/zoom; this writes the current bbox on settle.
    val viewportSaveHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val viewportSaveRunnable = remember { Runnable { saveConvoyState() } }
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
    var tracksLoaded by remember { mutableStateOf(false) }
    var scanningDownloaded by remember { mutableStateOf(false) }
    val autoPan by viewModel.autoPan.collectAsStateWithLifecycle()
    // "?" help: which bundled doc is open ("manual" | "notes" | null = chooser/closed)
    var docsView by remember { mutableStateOf<String?>(null) }
    var showDocsChooser by remember { mutableStateOf(false) }

    // DOCSTACK-2026-09-06: ⭐ DOCUMENTS STACK. Fred: "keep document open with
    // new doc launched on top so when we close we can return from where we
    // exited."
    // ⚠ Tapping a task in the Quick Start opens the manual. Closing it should
    // put the rider back on the Quick Start where they were, not on the map --
    // otherwise working through a checklist means reopening Help every time.
    val docsStack = remember { mutableStateListOf<String>() }
    fun docsOpen(name: String) {
        docsView?.let { docsStack.add(it) }
        docsView = name
    }
    fun docsBack() {
        docsView = if (docsStack.isNotEmpty()) docsStack.removeAt(docsStack.size - 1)
                   else null
    }

    // DOCLAUNCH-2026-09-05: ⭐ SHOW THE RIGHT DOCUMENT, ONCE.
    // A new install opens the Quick Start; an update opens the release notes.
    // Never both, and never twice.
    // ⚠ LaunchedEffect(Unit) so it runs once per entry rather than on every
    // recomposition -- and MeshNavFold.docToShow writes its marker as it
    // answers, so even a second call would return NONE.
    // ⚠ It sets the SAME `docsView` the Help chooser uses, so the viewer and
    // its Close button are the ones already proven. Nothing new renders.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        when (com.geeksville.mesh.convoy.MeshNavFold.docToShow(
            context, com.geeksville.mesh.BuildConfig.VERSION_NAME
        )) {
            com.geeksville.mesh.convoy.MeshNavFold.DocToShow.QUICKSTART ->
                docsView = "quickstart"
            com.geeksville.mesh.convoy.MeshNavFold.DocToShow.RELEASE_NOTES ->
                docsView = "notes"
            else -> {}
        }
    }
    var showArtifactsPanel by remember { mutableStateOf(false) }   // FAB closed-state vs panel open-state
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
                val label = node.callsign.ifEmpty { node.nodeId.takeLast(4) } +   // TICKDATA-2026-09-28 (Fred): callsign \u00b7 role
                    (if (node.rideRole.isBlank()) "" else " \u00b7 " + when (node.rideRole) {
                        "leader" -> "Leader"; "middle" -> "Middle"; "tail_gunner" -> "Tail gunner"; else -> "Rider" })
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
            CartPickerLauncher.close()   // CARTLIST2-2026-09-28 (Fred): the ride has started
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

    // CHECKIN-2026-09-27 (Fred): the pre-ride CHECK-IN (its own file) -- the first state of the recording sequence.
    var showCheckIn by CheckInLauncher.showing   // CHECKINAPPLY-2026-09-28: shared -- the check-in reopens after a radio setup that failed
    if (showCheckIn) {
        CheckInSheet(
            // HELDROLE-2026-09-29 (Fred): the roles already held -- the SAME node list the group check-in panel (SELECT CART) shows,
            // other carts only (my own cart excluded, so re-checking in with my own role is never a conflict).
            heldBy = convoyState.nodes
                .filter { !it.isMyCart && it.rideRole in setOf("leader", "middle", "tail_gunner") }
                .associate { it.rideRole to it.callsign.ifBlank { it.nodeId } },
            onDone = { ci, showOnMap ->
                viewModel.checkIn.value = ci
                viewModel.startRoleReports(ci.role)   // ROLEANYRIDE-2026-09-29 (Fred): broadcast on ANY ride, scheduled or not   // ROLEREPORT-2026-09-29 (Fred): the test run
                // NOCARTPOP-2026-09-29 (Fred): SELECT CART no longer pops up after check-in -- it opens from the
                // CHECKIN / SELECT CART button.
                // CHECKINMAP-2026-09-28 (Fred): the ride map shows THIS ride's route and its trailhead -- done the way FIT
                // does it: every type OFF, the route + its trailhead waypoint SELECTED, the frame fitted to both, saved.
                if (showOnMap && ci.rideId != null) {
                    val rid = ConvoyRideStore.rideForEdit(ci.rideId)?.routeId?.takeIf { it.isNotBlank() }
                    if (rid != null) {
                        trailState = DS_OFF; trailCheckedIds = null
                        trackState = DS_OFF; trackCheckedIds = null
                        waypointState = DS_OFF; waypointCheckedIds = null
                        routeState = DS_SELECTED; routeCheckedIds = setOf(rid)
                        RidePreview.trailheadWaypointId(rid)?.let { waypointState = DS_SELECTED; waypointCheckedIds = setOf(it) }
                        SpatialDbManager.bboxForArtifact("Routes", rid)?.let { bb ->
                            val a = RidePreview.anchorOf(rid)
                            val s = minOf(bb[0], a?.first ?: bb[0]); val w = minOf(bb[1], a?.second ?: bb[1])
                            val n = maxOf(bb[2], a?.first ?: bb[2]); val e = maxOf(bb[3], a?.second ?: bb[3])
                            val latPad = (n - s).let { if (it > 0.0) it * 0.10 else 0.01 }
                            val lonPad = (e - w).let { if (it > 0.0) it * 0.10 else 0.01 }
                            lastViewportSouth = s - latPad; lastViewportWest = w - lonPad
                            lastViewportNorth = n + latPad; lastViewportEast = e + lonPad
                            webViewRef.value?.evaluateJavascript("fitBounds([" + lastViewportSouth + "," + lastViewportNorth + "],[" +
                                lastViewportWest + "," + lastViewportEast + "])", null)
                        }
                        saveConvoyState()
                        webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        android.util.Log.i("ConvoyScreen", "CHECKINMAP: ride ${ci.rideId} -> route $rid + its trailhead on the ride map")
                    }
                }
                showCheckIn = false
                recordingState = RecordingState.IDLE   // checked in: the button now reads REC (a second tap records)
            },
            onCancel = { showCheckIn = false },
        )
    }
    if (showNameDialog) {
        // CHECKIN-2026-09-27: the END-OF-RIDE FORM (its own file) replaces the old "Save Track" dialog. It reads the check-in:
        // a ride's track is named after the ride; the survey and the share choice only on a public ride.
        EndOfRideForm(
            name = pendingTrackName,
            onNameChange = { pendingTrackName = it },
            checkIn = viewModel.endingCheckIn,
            onSave = { survey ->
                showNameDialog = false
                viewModel.pendingSurvey = survey
                viewModel.finalizeTrack(pendingTrackName.trim(), context)
            },
            onDelete = { showConfirmDelete = true },
        )
    }
    if (showConfirmDelete) {
        AlertDialog(
            // HARDENED: non-cancelable.
            onDismissRequest = { },
            title = { Text("No name given") },
            text = { Text("Delete this track? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDelete = false
                    showNameDialog = false
                    viewModel.deleteTempTrack()
                }) { Text("YES, DELETE") }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Back to naming — track is NOT deleted.
                    showConfirmDelete = false
                }) { Text("NO, GO BACK") }
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
                        fun onMapTap(lat: Double, lon: Double) {
                            android.util.Log.d("RouteBridge", "onMapTap lat=$lat lon=$lon")
                            kotlinx.coroutines.MainScope().launch {
                                val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    SpatialDbManager.init(context)
                                    val trails = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                    val tracks = SpatialDbManager.queryTracksByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                    val s = RouteManager.snap(lat, lon, trails, tracks, 30.0)
                                    if (s != null) RouteManager.snapToVertex(s) else RouteManager.freeVertex(lat, lon)
                                }
                                RouteManager.addVertex(v)
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
                                webViewRef.value?.evaluateJavascript("drawBuildLine('" + pts + "')", null)
                            }
                        }
                        @android.webkit.JavascriptInterface
                        fun onMapLongPress(lat: Double, lon: Double) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                // WAYPOINT-TRACE-2026-07-31
                                android.util.Log.d("ConvoyTap",
                                    "onMapLongPress [UPDATE :571] lat=$lat lon=$lon")
                                pendingWaypoint = Pair(lat, lon)
                            }
                        }
                        @android.webkit.JavascriptInterface
                        fun onViewportChanged(north: Double, south: Double, east: Double, west: Double, zoom: Double) {
                            lastViewportSouth = south; lastViewportWest = west; lastViewportNorth = north; lastViewportEast = east
                            viewportSaveHandler.removeCallbacks(viewportSaveRunnable); viewportSaveHandler.postDelayed(viewportSaveRunnable, 400)
                            val wv = webViewRef.value
                            Thread {
                                val rs = MapStateStore.readMap("convoy")
                                val states = mapOf(
                                    "Trails" to (rs.types["Trails"]?.state ?: DS_OFF),
                                    "Tracks" to (rs.types["Tracks"]?.state ?: DS_OFF),
                                    "Waypoints" to (rs.types["Waypoints"]?.state ?: DS_OFF),
                                    "Routes" to (rs.types["Routes"]?.state ?: DS_OFF)
                                )
                                val selectLists = mapOf(
                                    "Trails" to MapStateStore.checkedIdsFor(rs, "Trails"),
                                    "Tracks" to MapStateStore.checkedIdsFor(rs, "Tracks"),
                                    "Waypoints" to MapStateStore.checkedIdsFor(rs, "Waypoints"),
                                    "Routes" to MapStateStore.checkedIdsFor(rs, "Routes")
                                )
                                SpatialDisplayManager.processViewport(south, west, north, east, zoom.toInt(), states, selectLists, wv, context)
                            }.start()
                        }
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
                        // RIDEROUTETAP-2026-09-08: the ride map had NO route tap at
                        // all -- no handler here and only a console.log on the map
                        // side. A route is an artifact we own, so it opens the shared
                        // detail panel, exactly as tracks and trails do.
                        // ⚠ No addPointMode test: there is no route building on this
                        // screen, so there is no flag to suppress against.
                        @android.webkit.JavascriptInterface
                        fun onRouteModeOffRequested() {
                            // RMBADGE-2026-10-01 (Fred): the red ROUTE+ badge was tapped -- the ride map is never in route mode.
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                routeMode = rmTrace(false, "RM@badge")
                                webViewRef.value?.evaluateJavascript("window.__routeMode=false;setRouteMode(false)", null)
                            }
                        }
                        @android.webkit.JavascriptInterface
                        fun onRouteTap(id: String) {
                            android.util.Log.d("RouteTap", "CONVOY bridge id=$id")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                pendingDetailType = "Routes"
                                pendingDetailId = id
                            }
                        }
                        // STYLEONREADY-2026-09-11: the rider's palette, at the
                        // one moment the map can take it. \u26a0 On BOTH interface
                        // objects -- a method on one is invisible to the other.
                        @android.webkit.JavascriptInterface
                        fun onMapReady(n: Double, s: Double, e: Double, w: Double) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                webViewRef.value?.evaluateJavascript(
                                    "setTrailStyles(" + TrailFilterState.styleJson() + ")", null)
                            }
                        }
                        @android.webkit.JavascriptInterface
                        fun onWaypointTap(id: String) {
                            // WPTTAP-2026-09-30 (Fred): mirrors onTrailTap. There are TWO bridge objects in this file
                            // (reuse and create); this method is on both, or it is invisible to one of them.
                            android.util.Log.d("WaypointTap", "CONVOY bridge id=$id")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                pendingDetailType = "Waypoints"
                                pendingDetailId = id
                            }
                        }
                        @android.webkit.JavascriptInterface
                        fun onTrailTap(id: String) {
                            // CONVOYTRAILTAP-2026-09-03: mirrors onTrackTap. ⚠ There
                            // are TWO bridge objects in this file -- reuse and create
                            // -- and a method on one is invisible to the other.
                            android.util.Log.d("TrailTap", "CONVOY(reuse) bridge id=$id")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                pendingDetailType = "Trails"
                                pendingDetailId = id
                            }
                        }
                        // TRACKTAPANNO-2026-09-10: ⛔ THIS WAS MISSING and the
                        // method was therefore invisible to JavaScript -- the tap
                        // was made, nothing happened, nothing was logged.
                        @android.webkit.JavascriptInterface
                        fun onTrackTap(id: String) {
                            // [2026-07-02] track tap -> open the shared ArtifactDetailPanel (metrics + SAVE MAPS).
                            android.util.Log.d("TrackTap", "CONVOY(reuse :652) bridge id=$id")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.util.Log.d("TrackTap", "CONVOY(reuse) post -> setting state id=$id")
                                pendingDetailType = "Tracks"
                                pendingDetailId = id
                            }
                        }
                    }, "Android")
                    webViewRef.value = existing
                    existing
                } else {
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                                    // HTMLVER-2026-08-13B: never serve a cached copy of a
                                    // bundled asset. ⚠ A cache-buster on the URL is NOT
                                    // used - WebView treats file:///android_asset/x.html
                                    // as a filename, so a query string risks a 404.
                                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.domStorageEnabled = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            // HTMLVER-2026-08-13B: read the HTML's own version back so settings
                            // can show it. Cheap, once per page load.
                            view?.evaluateJavascript("window.__htmlVersion || ''") { v ->
                                val clean = v?.trim('"') ?: ""
                                if (clean.isNotBlank() && clean != "null") {
                                    ConvoyConfig.MAP_HTML_VERSION = clean
                                    android.util.Log.i("HtmlVer", "HTMLVER-2026-08-13B loaded $clean")
                                }
                            }

                                view?.evaluateJavascript("setRouteMode(false)", null)
                                // Auto-sense connectivity: use local tiles if no internet
                                val ctx = view?.context ?: return
                                val cm = ctx?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                                val hasInternet = cm?.activeNetwork?.let { net ->
                                    cm.getNetworkCapabilities(net)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                                } ?: false
                                val tileUrl = if (hasInternet) {
                                    ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: return
                                } else {
                                    android.util.Log.d("ConvoyMap", "No internet — auto-switching to local tiles")
                                    ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"
                                }
                                if (!hasInternet) {
                                    // Auto-set offline mode so source button taps also use local tiles
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        viewModel.setLocalTiles(true)
                                        viewModel.setOfflineMode(true)
                                    }
                                }
                                view?.postDelayed({
                                    view.evaluateJavascript("setTileUrl('$tileUrl', '${ConvoyConfig.ACTIVE_TILE_SOURCE}')", null)
                                    val overlayJson = MapSourceManager.getOverlayJson(ConvoyConfig.ACTIVE_TILE_SOURCE)
                                    if (overlayJson != "[]") {
                                        view.evaluateJavascript("setOverlayLayers('${overlayJson.replace("'", "\'")}')", null)
                                    }
                                    // [Fix2] Entry restore vs GPS. Re-read FRESH (cmSeed remembered
                                    // from first compose; stale on re-entry). bbox present = in-session
                                    // re-entry -> restore saved frame; absent = cold launch -> GPS.
                                    val rsEntry = MapStateStore.readMap("convoy")
                                    val bbEntry = rsEntry.bbox
                                    if (bbEntry != null) {
                                        // SEED lastViewport* BEFORE draw — closes the stale window for
                                        // other readers (route-snap, save) until onViewportChanged fires.
                                        lastViewportSouth = bbEntry.south; lastViewportWest = bbEntry.west
                                        lastViewportNorth = bbEntry.north; lastViewportEast = bbEntry.east
                                        view.evaluateJavascript("fitBounds([${bbEntry.south},${bbEntry.north}],[${bbEntry.west},${bbEntry.east}])", null)
                                        android.util.Log.d("ConvoyMap", "Restored persisted frame")
                                        SpatialDisplayManager.drawPersistedState("convoy", view, context)
                                    } else {
                                        // Trails loaded on demand via TRAILS button
                                        // Center map on device last known location
                                        try {
                                            val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                                            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                            // RIDEENTRY-2026-10-03 (Fred): entering the ride map ALWAYS positions it. My cart from the TICK first (radio GPS
                                            // or tablet GPS, whichever the tick is using); then Android's cached fix; else wait for the first fix
                                            // (every 2 s, up to 2 minutes) instead of leaving the map at its default view.
                                            val meNow = viewModel.convoyState.value.nodes.firstOrNull { it.isMyCart && it.latitude != 0.0 && it.longitude != 0.0 }
                                            if (meNow != null) {
                                                android.util.Log.d("ConvoyMap", "RIDEENTRY: centring on my cart from the tick: ${meNow.latitude}, ${meNow.longitude}")
                                                view.evaluateJavascript("setView(${meNow.latitude}, ${meNow.longitude}, 15)", null)
                                            } else if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                                                android.util.Log.d("ConvoyMap", "Centering map on device GPS: ${loc.latitude}, ${loc.longitude}")
                                                view.evaluateJavascript("setView(${loc.latitude}, ${loc.longitude}, 15)", null)
                                            } else {
                                                android.util.Log.w("ConvoyMap", "RIDEENTRY: no position yet -- centring on the first fix")
                                                val wv: android.webkit.WebView = view
                                                fun centreOnFirstFix(triesLeft: Int) {
                                                    wv.postDelayed({
                                                        val m = viewModel.convoyState.value.nodes.firstOrNull { it.isMyCart && it.latitude != 0.0 && it.longitude != 0.0 }
                                                        if (m != null) {
                                                            android.util.Log.d("ConvoyMap", "RIDEENTRY: first fix -- centring: ${m.latitude}, ${m.longitude}")
                                                            wv.evaluateJavascript("setView(${m.latitude}, ${m.longitude}, 15)", null)
                                                        } else if (triesLeft > 0) {
                                                            centreOnFirstFix(triesLeft - 1)
                                                        }
                                                    }, 2000)
                                                }
                                                centreOnFirstFix(60)
                                            }
                                        } catch (e: SecurityException) {
                                            android.util.Log.w("ConvoyMap", "Location permission not granted — map stays at default view")
                                        }
                                    }
                                }, 600)
                                mapReady++
                            }
                            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                // OFFTRACE-2026-08-11M: log EVERY request the WebView makes. If no
                                // convoy:// line ever appears, the map is still asking
                                // for https and the interceptor is irrelevant.
                                if (url.contains("tile") || url.startsWith("convoy://")) {
                                    android.util.Log.i("OFFTRACE", "OFFTRACE-2026-08-11M convoy-map REQ $url")
                                }
                                if (url.startsWith("convoy://tiles/")) {
                                    android.util.Log.i("OFFTRACE", "OFFTRACE-2026-08-11M convoy-map MATCHED convoy://")
                                    // [V2.6-PASS1-READ] base tile from MBTiles. Path = <type>/<z>/<x>/<y>.png
                                    // Split from the RIGHT: last 3 = z/x/y; everything before = type (keeps TOPO+).
                                    val tilePath = url.removePrefix("convoy://tiles/")
                                    val seg = tilePath.split("/")
                                    if (seg.size >= 4) {
                                        val y = seg[seg.size - 1].substringBefore('.').toIntOrNull()
                                        val x = seg[seg.size - 2].toIntOrNull()
                                        val z = seg[seg.size - 3].toIntOrNull()
                                        val type = seg.subList(0, seg.size - 3).joinToString("/")
                                        if (z != null && x != null && y != null) {
                                            val bytes = MBTilesStore.readTile(type, z, x, y)
                                            android.util.Log.d("ConvoyIntercept", "TILE mbtiles hit=${bytes != null} type=$type z$z/$x/$y")
                                            if (bytes != null) return android.webkit.WebResourceResponse("image/webp", null, java.io.ByteArrayInputStream(bytes))
                                        }
                                    }
                                }
                                // Intercept Esri label tiles for offline serving
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
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.d("ConvoyJS", "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                                return true
                            }
                        }
                        // LASTPATHS-2026-09-12: ⛔ ConvoyConfig.migrateTiles(ctx) was
                        // called here. It moved tiles from app-private storage OUT
                        // to shared Documents -- the pre-MBTiles migration, and
                        // exactly backwards from where storage is now going. Once
                        // tileRoot() resolves internal, its source and destination
                        // would be the SAME directory. Deleted, not disabled.
                        loadUrl("file:///android_asset/convoy_map.html")
                        addJavascriptInterface(object : Any() {
                            @android.webkit.JavascriptInterface
                            fun onMapTap(lat: Double, lon: Double) {
                                android.util.Log.d("RouteBridge", "onMapTap lat=$lat lon=$lon")
                                kotlinx.coroutines.MainScope().launch {
                                    val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        SpatialDbManager.init(context)
                                        val trails = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                        val tracks = SpatialDbManager.queryTracksByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                        val s = RouteManager.snap(lat, lon, trails, tracks, 30.0)
                                        if (s != null) RouteManager.snapToVertex(s) else RouteManager.freeVertex(lat, lon)
                                    }
                                    RouteManager.addVertex(v)
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
                                    webViewRef.value?.evaluateJavascript("drawBuildLine('" + pts + "')", null)
                                }
                            }
                            @android.webkit.JavascriptInterface
                            fun onMapLongPress(lat: Double, lon: Double) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    // WAYPOINT-TRACE-2026-07-31
                                    android.util.Log.d("ConvoyTap",
                                        "onMapLongPress [FACTORY :788] lat=$lat lon=$lon")
                                    pendingWaypoint = Pair(lat, lon)
                                }
                            }
                            @android.webkit.JavascriptInterface
                            fun onMarkerTapped(nodeId: String) {
                                val node = viewModel.convoyState.value.nodes.firstOrNull { it.nodeId == nodeId }
                                if (node != null) viewModel.onMarkerTapped(node)
                            }

                            @android.webkit.JavascriptInterface
                            fun onViewportChanged(north: Double, south: Double, east: Double, west: Double, zoom: Double) {
                                lastViewportSouth = south; lastViewportWest = west; lastViewportNorth = north; lastViewportEast = east
                                viewportSaveHandler.removeCallbacks(viewportSaveRunnable); viewportSaveHandler.postDelayed(viewportSaveRunnable, 400)
                                // GATE: reseed convoy state from JSON only if active map changed since last refresh.
                                run {   // [refresh-restore 2026-07-01] gate removed: reseed convoy state EVERY viewport event (automatic refresh). Reads convoy JSON only -> map-independence preserved.
                                    val rs = MapStateStore.readMap("convoy")
                                    android.util.Log.e("JSONDIAG", "READ(gate fired) Tr=${rs.types["Trails"]?.state} Tk=${rs.types["Tracks"]?.state} bbox=${rs.bbox} TrChecked=${MapStateStore.checkedIdsFor(rs, "Trails")?.size}")
                                    trailState = rs.types["Trails"]?.state ?: DS_OFF
                                    trackState = rs.types["Tracks"]?.state ?: DS_OFF
                                    waypointState = rs.types["Waypoints"]?.state ?: DS_OFF
                                    routeState = rs.types["Routes"]?.state ?: DS_OFF
                                    trailCheckedIds = MapStateStore.checkedIdsFor(rs, "Trails")
                                    trackCheckedIds = MapStateStore.checkedIdsFor(rs, "Tracks")
                                    waypointCheckedIds = MapStateStore.checkedIdsFor(rs, "Waypoints")
                                    routeCheckedIds = MapStateStore.checkedIdsFor(rs, "Routes")
                                    // [convoy-routemode-reset 2026-08-01] Route mode is an ACTIVITY, not restorable
                                    // state. Convoy can never arm it (the panel anchor is hidden), so it is forced
                                    // OFF here, on the same cadence the display state is reseeded. WRITE-ONLY --
                                    // this value is never read back from JSON. Do not "fix" that asymmetry.
                                }
                                val z = zoom.toInt()
                                // [Fix1] Unify with path A: draw through the shared SpatialDisplayManager.
                                // State from convoy's LIVE local vars (reseed gate above populated them).
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
                                MapStateStore.lastMapProcessed = "convoy"
                                Thread {
                                    SpatialDisplayManager.processViewport(south, west, north, east, z, states, selectLists, webViewRef.value, context)
                                }.start()
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
                                        run {
                                            // [V2.6-PASS1-S4] DB-backed min/max coverage (raw z/x/y at z18)
                                            val z = 18
                                            val n = 1 shl z
                                            var xMin = Long.MAX_VALUE; var xMax = Long.MIN_VALUE
                                            var yMin = Long.MAX_VALUE; var yMax = Long.MIN_VALUE
                                            for ((x, y) in MBTilesStore.xyAtZoom("SAT", z)) {
                                                if (x < xMin) xMin = x; if (x > xMax) xMax = x
                                                if (y < yMin) yMin = y; if (y > yMax) yMax = y
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
                            // RIDEROUTETAP-2026-09-08: the SECOND interface object.
                            // ⛔ Both get it or the tap works on one WebView and not
                            // the other -- the failure this codebase keeps recording.
                            @android.webkit.JavascriptInterface
                            fun onRouteModeOffRequested() {
                                // RMBADGE-2026-10-01 (Fred): the red ROUTE+ badge was tapped -- the ride map is never in route mode.
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    routeMode = rmTrace(false, "RM@badge")
                                    webViewRef.value?.evaluateJavascript("window.__routeMode=false;setRouteMode(false)", null)
                                }
                            }
                            @android.webkit.JavascriptInterface
                            fun onRouteTap(id: String) {
                                android.util.Log.d("RouteTap", "CONVOY bridge id=$id")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    pendingDetailType = "Routes"
                                    pendingDetailId = id
                                }
                            }
                            // STYLEONREADY-2026-09-11: the second interface object.
                            @android.webkit.JavascriptInterface
                            fun onMapReady(n: Double, s: Double, e: Double, w: Double) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    webViewRef.value?.evaluateJavascript(
                                        "setTrailStyles(" + TrailFilterState.styleJson() + ")", null)
                                }
                            }
                            @android.webkit.JavascriptInterface
                            fun onWaypointTap(id: String) {
                                // WPTTAP-2026-09-30 (Fred): mirrors onTrailTap. There are TWO bridge objects in this file
                                // (reuse and create); this method is on both, or it is invisible to one of them.
                                android.util.Log.d("WaypointTap", "CONVOY bridge id=$id")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    pendingDetailType = "Waypoints"
                                    pendingDetailId = id
                                }
                            }
                            @android.webkit.JavascriptInterface
                            fun onTrailTap(id: String) {
                                // CONVOYTRAILTAP-2026-09-03: the create-path bridge.
                                android.util.Log.d("TrailTap", "CONVOY(create) bridge id=$id")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    pendingDetailType = "Trails"
                                    pendingDetailId = id
                                }
                            }
                            // TRACKTAPANNO-2026-09-10: ⚠ THE SECOND OBJECT. Both
                            // get it or the tap works on one WebView and not the
                            // other -- the failure this codebase keeps recording.
                            @android.webkit.JavascriptInterface
                            fun onTrackTap(id: String) {
                                // [2026-07-02] track tap -> open the shared ArtifactDetailPanel (metrics + SAVE MAPS).
                                android.util.Log.d("TrackTap", "CONVOY(create :916) bridge id=$id")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.util.Log.d("TrackTap", "CONVOY(create) post -> setting state id=$id")
                                    pendingDetailType = "Tracks"
                                    pendingDetailId = id
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
                    // CONVOY-LONGPRESS-2026-07-31: detect long-press HERE rather than in
                    // Leaflet.
                    //
                    // convoy_map.html:303 uses map.on('contextmenu'), and
                    // Leaflet 1.9 dropped its own tap handler — it depends on
                    // the BROWSER firing a native contextmenu. Installing this
                    // OnTouchListener intercepts before View.onTouchEvent, and
                    // onTouchEvent is what starts the long-press timer. So the
                    // WebView never synthesises one and Leaflet's handler never
                    // runs. Device-confirmed 07-31: seven long-presses, ZERO
                    // ConvoyJS output, while taps logged normally.
                    //
                    // Rather than fight that, detect it in the listener that is
                    // already here and reuse the screen-coordinate path
                    // findNearestMarker already proves works.
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            lpDown[0] = event.x
                            lpDown[1] = event.y
                            val lx = event.x.toInt()
                            val ly = event.y.toInt()
                            lpRunnable[0]?.let { lpHandler.removeCallbacks(it) }
                            val r = Runnable {
                                android.util.Log.i("ConvoyTap", "LONGPRESS at x=$lx y=$ly")
                                view.evaluateJavascript("longPressAt($lx, $ly)", null)
                            }
                            lpRunnable[0] = r
                            lpHandler.postDelayed(r, 500L)
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            // A drag is not a press. Slop, not zero, or the
                            // gesture cancels on the smallest finger tremor.
                            if (kotlin.math.abs(event.x - lpDown[0]) > 20f ||
                                kotlin.math.abs(event.y - lpDown[1]) > 20f) {
                                lpRunnable[0]?.let { lpHandler.removeCallbacks(it) }
                            }
                        }
                        android.view.MotionEvent.ACTION_POINTER_DOWN,
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            lpRunnable[0]?.let { lpHandler.removeCallbacks(it) }
                        }
                    }
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

        // ── NOGPSMSG-2026-09-15: position-source messages ────────────────────────────
        // Both gated on !showSplash: a dialog raised under a full-screen image
        // is the 09-13 storage-prompt trap (rendered and dismissed before input).
        val noPositionError by viewModel.noPositionError.collectAsStateWithLifecycle()
        val networkPositionWarning by viewModel.networkPositionWarning.collectAsStateWithLifecycle()
        val nogpsContext = androidx.compose.ui.platform.LocalContext.current
        // Own prefs file: ConvoyDevSeeder clears a LIST of pref files, and a
        // rider acknowledgement must not be collateral damage of a dev reset.
        var netPosAck by remember {
            mutableStateOf(
                nogpsContext.getSharedPreferences(
                    "grouptrack_device", android.content.Context.MODE_PRIVATE
                ).getBoolean("net_pos_ack", false)
            )
        }
        // Not persisted. The hard error returns every app start while it holds.
        var noPosDismissed by remember { mutableStateOf(false) }

        if (!showSplash && noPositionError && !noPosDismissed) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { noPosDismissed = true },
                title = { androidx.compose.material3.Text("No location available") },
                text = {
                    androidx.compose.material3.Text(
                        "GroupTrack cannot determine your location.\n\n" +
                        "There is no GPS position, no mesh radio supplying one, " +
                        "and no network position available.\n\n" +
                        "Connect a mesh radio, or move somewhere with network " +
                        "coverage, for the app to know where you are."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { noPosDismissed = true }
                    ) { androidx.compose.material3.Text("OK") }
                }
            )
        } else if (!showSplash && networkPositionWarning && !netPosAck) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { netPosAck = true },
                title = { androidx.compose.material3.Text("Not a standalone trail device") },
                text = {
                    androidx.compose.material3.Text(
                        "As equipped, this device will not function as a standalone " +
                        "Android trail device.\n\n" +
                        "It has no GPS of its own right now, so it can only find your " +
                        "location through a network connection \u2014 and there is no " +
                        "network on the trail.\n\n" +
                        "It requires a mesh radio to function off-grid. We recommend " +
                        "the Seeed SenseCAP Card Tracker T1000-E, which integrates " +
                        "tightly with GroupTrack."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { netPosAck = true }
                    ) { androidx.compose.material3.Text("OK") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            nogpsContext.getSharedPreferences(
                                "grouptrack_device", android.content.Context.MODE_PRIVATE
                            ).edit().putBoolean("net_pos_ack", true).apply()
                            netPosAck = true
                        }
                    ) { androidx.compose.material3.Text("Don't show again") }
                }
            )
        }

                    // WAYPOINT-TRACE-2026-07-31: does this gate even evaluate?
                    // ⚠ Logging in composition is a side effect and comes OUT
                    // once this path is understood. It is here because nothing
                    // else distinguishes "state never written" from "state
                    // written into a scope this composition does not observe".
                    if (pendingWaypoint != null) android.util.Log.d(
                        "ConvoyTap", "DIALOG gate pendingWaypoint=$pendingWaypoint")
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
                                                    otherConfirm = false
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
                                    if (newWaypointType == "other" && !otherConfirm) { otherConfirm = true; return@TextButton }   // WPTTAP-2026-09-30
                                    val nm = if (newWaypointName.isBlank()) (WAYPOINT_TYPES.firstOrNull { it.first == newWaypointType }?.second?.substringAfter(" ") ?: "Waypoint") else newWaypointName
                                    val ty = newWaypointType
                                    Thread {
                                        try {
                                            SpatialDbManager.init(context)
                                            SpatialDbManager.insertWaypoint(nm, wLat, wLon, ty)
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("WptCreate", "insert failed: " + e.message)
                                        }
                                    }.start()
                                    pendingWaypoint = null
                                    newWaypointName = ""
                                    newWaypointType = "trailhead"; otherConfirm = false
                                }) { androidx.compose.material3.Text(if (otherConfirm) "Save as Other?" else "Create") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    pendingWaypoint = null
                                    newWaypointName = ""
                                    newWaypointType = "trailhead"; otherConfirm = false
                                }) { androidx.compose.material3.Text("Cancel") }
                            }
                        )
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
                        androidx.compose.material3.Text("CANCEL", color = androidx.compose.ui.graphics.Color(0xFFFFFFFF))
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
                            RecordingState.CHECK_IN -> {   // CHECKIN-2026-09-27: no recording before check-in
                                // SOLOREC-2026-09-28 (Fred): THE BUTTON FOLLOWS THE RADIO. A radio -> the check-in. No radio -> a
                                // solo ride: a silent "No scheduled ride" check-in (no survey, no sharing), then REC's own solo path.
                                if (GrpAwarenessLauncher.connected.value) showCheckIn = true
                                else {
                                    ConvoyRideStore.checkIn(null, "", "rider")?.let { viewModel.checkIn.value = it }
                                    recordingState = RecordingState.IDLE
                                    val soloBg = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                                        androidx.core.content.ContextCompat.checkSelfPermission(
                                            context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (soloBg) {   // as REC does for one cart or none: auto-assign the lead and record
                                        viewModel.setLeadCart(viewModel.convoyState.value.nodes.firstOrNull()?.nodeId ?: "!phone")
                                        recordingState = RecordingState.RECORDING
                                        viewModel.startRecording(context)
                                        viewModel.startGroupTrack()
                                    }   // else it stays at REC: that tap asks for the permission, as always
                                    android.util.Log.i("ConvoyScreen", "SOLOREC: no radio -> solo ride (no check-in), recording=$soloBg")
                                }
                            }
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
                        RecordingState.CHECK_IN -> Color(0xFF8B0000)   // ORDERFIX-2026-09-27 (Fred): red, like REC
                        RecordingState.IDLE -> Color(0xFF8B0000)
                        RecordingState.RECORDING -> Color(0xFFCC0000)
                        RecordingState.PAUSED -> Color(0xFF994400)
                        RecordingState.SLEEPING -> Color(0xFFCC8800)
                    },
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = when (recordingState) {
                            RecordingState.CHECK_IN -> if (GrpAwarenessLauncher.connected.value) "✔  CHK IN › REC" else "⏺  REC"   // SOLOREC-2026-09-28
                            RecordingState.IDLE -> "⏺  REC"
                            RecordingState.RECORDING -> "⏸  PAUSE"
                            RecordingState.PAUSED -> "⏺  RESUME"
                            RecordingState.SLEEPING -> "ZZZ  ASLEEP"
                        },
                        color = Color.White,
                        fontSize = if (recordingState == RecordingState.CHECK_IN && GrpAwarenessLauncher.connected.value) 12.sp else 15.sp,   // SOLOREC   // ORDERFIX-2026-09-27: the longer label fits
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
                            recordingState = RecordingState.CHECK_IN   // CHECKIN-2026-09-27: each recording starts with its own check-in
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
          if (recordingState != RecordingState.IDLE && recordingState != RecordingState.CHECK_IN) {   // CHECKIN-2026-09-27
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
            // PLAINCTRL-2026-08-17: plain language for the planning entry point.
            navLabel = "Ride Planning",
            // PLANGATE-2026-08-12C: green with a connection, yellow without. NOT red - the
            // record button beside this one is red, and a second red control
            // reads as a second recording state.
            navTint = if (hasInternet) Color(0xFF2E7D32) else Color(0xFFB8860B),
            onNavigate = {
                if (hasInternet) onNavigateToMapViewer()
                else android.widget.Toast.makeText(
                    context, "Planning Map requires internet access",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            },
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
                val overlayJsonSc = MapSourceManager.getOverlayJson(label)
                if (overlayJsonSc != "[]") { webViewRef.value?.evaluateJavascript("setOverlayLayers('${overlayJsonSc.replace("'", "\\'")}')", null) }
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
                .padding(start = 110.dp, end = 8.dp, top = 8.dp)
                .fillMaxWidth()
                
        )

        // -- UNIFIED SEARCH FAB (2026-06-19) -- stacked above "?" to start the icon column --
        // Self-contained search beacon: Area/Track/Route/Trail/Waypoint. Routes artifact
        // results to the existing detail path (pendingDetailType/Id -> ArtifactDetailPanel).
        // Old ConvoyArtifactsPanel search remains in place this step (removed later).
        UnifiedSearch(
            mapContext = "convoy",
            webView = webViewRef.value,
            context = context,
            onOpenDetail = { type, id ->
                pendingDetailType = type
                pendingDetailId = id
            },
            // SEARCHPAN-2026-09-15: a search moved the map -- stop following the cart, or the next
            // convoyState tick drags the rider back off the place they searched
            // for. Tapping MY CART re-arms it (:2764 sets autoPan true) and snaps
            // back deliberately.
            onMapRepositioned = { viewModel.setAutoPan(false) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 120.dp, end = 12.dp)
        )

        // -- "?" HELP BUTTON (ported from planning 2026-06-18; TopStart to clear QUEUES) --
        // MAPKEYS-2026-09-01: Map Keys goes BETWEEN Map Features (152) and Help,
        // so Help moves 184 -> 216. The column is absolute top padding, not a
        // stack, so inserting a control means moving the ones below it.
        androidx.compose.material3.Surface(
            onClick = { mapKeysOpen = true },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = androidx.compose.ui.graphics.Color(0xFFFF00FF),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 184.dp, end = 12.dp)
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                // PLAINCTRL-2026-08-17: words, not a glyph -- riders are 65-75
                // and icon literacy cannot be assumed. The white blur shadow is
                // what keeps it readable over bright satellite.
                // NAVPLAIN-2026-09-29 (Fred): the navigation words are PLAIN coloured text -- no glow, no outline.
                androidx.compose.material3.Text(
                    "Map Keys",
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
        }
        // MAPKEYS-2026-09-01: the panel itself. Same composable as the planner
        // -- ⛔ the convoy map has had NO key at all until now, and a second
        // copy is how the three trailColor functions drifted apart.
        if (showTrailFilter) {
            androidx.compose.ui.window.Popup(
                onDismissRequest = { showTrailFilter = false }
            ) {
                TrailFilterPanel(
                    onDismiss = { showTrailFilter = false },
                    onFilterChanged = {
                        webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                    }
                )
            }
        }
        if (mapKeysOpen) {
            androidx.compose.ui.window.Popup(
                onDismissRequest = { mapKeysOpen = false }
            ) {
                MapKeysPanel(
                    onDismiss = { mapKeysOpen = false },
                    onStyleChanged = {
                        webViewRef.value?.evaluateJavascript("setTrailStyles(" + TrailFilterState.styleJson() + ")", null)
                    },
                    onFilterChanged = {
                        webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                    }
                )
            }
        }

        // FROZENEXCEPTION-WWR-2026-09-24: (Fred approved) the ONLY change to the frozen screen -- WORK WITH RIDES below Mesh, always shown.
        androidx.compose.material3.Surface(
            onClick = { com.geeksville.mesh.convoy.WorkWithRidesLauncher.open() },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            color = Color.Transparent,
            contentColor = Color(0xFFFF00FF),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 280.dp, end = 12.dp)
        ) {
            androidx.compose.material3.Text(
                "Work with Rides",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
            )
        }
        // MESHBTN-2026-09-04: ⭐ BRING THE RADIO NAVIGATION BACK, from the column
        // where every other GroupTrack control already lives.
        // ⛔ The folded strip on the left edge could not be seen -- it rendered
        // inside the scaffold's content and the map WebView drew over it. Fred:
        // "how do I bring it back... looked good with it missing."
        // ⭐ Here it cannot be covered, and it sits where a rider already looks.
        // ⚠ ONLY WHILE FOLDED (Fred): no dead control when the rail is showing.
        if (true) {   // GRPAWARE-2026-09-28 (Fred): GRP Awareness is ALWAYS shown (was: Mesh, only while the rail was folded)
            androidx.compose.material3.Surface(
                onClick = {
                    com.geeksville.mesh.convoy.GrpAwarenessLauncher.open()   // GRPAWARE: the panel (MESHTASTIC in it = the old unfold)
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                color = Color.Transparent,
                contentColor = Color(0xFFFF00FF),
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = 248.dp, end = 12.dp)
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Text(
                        "GRP Awareness",
                        color = Color.White,   // GRPBOX2-2026-09-29 (Fred): white text -- the status is the green/red box behind it
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(com.geeksville.mesh.convoy.grpAwarenessColor(),   // GRPBOX2-2026-09-29: green/red box, pulsing
                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
            }
        }

        androidx.compose.material3.Surface(
            onClick = { showDocsChooser = true },
            // PLAINCTRL3-2026-08-18B: circle + fixed size dropped so the word renders in full;
            // top padding tightened from 252 to 184 to close the gap in the text column.
            // MAPKEYS-2026-09-01: 184 -> 216, Map Keys took 184.
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = androidx.compose.ui.graphics.Color(0xFFFF00FF),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 216.dp, end = 12.dp)
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                // PLAINCTRL2-2026-08-17: the word, for the same reason as the others.
                androidx.compose.material3.Text(
                    "Help",
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
        }
        // -- ARTIFACTS FAB (opens WORK WITH ARTIFACTS expanded; hidden while panel open) --
        if (!showArtifactsPanel) {
            // PLAINCTRL-2026-08-17: surface dropped (option A) -- bare clickable words over the
            // map instead of a 40dp filled circle. Colour is carried by contentColor on a
            // transparent surface so the control still reports as clickable to a11y.
            androidx.compose.material3.Surface(
                onClick = { showArtifactsPanel = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = androidx.compose.ui.graphics.Color(0xFFFF00FF),
                // PLAINCTRL3-2026-08-18B: top padding tightened from 200 to 152 to even the column.
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 152.dp, end = 12.dp)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    // PLAINCTRL-2026-08-17: words, not a glyph. Riders are 65-75 and icon
                    // literacy cannot be assumed. White blur shadow matches GroupHud so the
                    // text survives bright satellite imagery now that the dark fill is gone.
                    androidx.compose.material3.Text(
                        "Map Features",
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
            }
        }
        if (showDocsChooser) {
            // DOCSCHOOSER-2026-09-02: ⛔ THIS DIALOG HAD NO WAY OUT. Material3's
            // AlertDialog gives exactly two slots, and both were spent on the
            // two documents -- so `dismissButton` was "Full Manual", not a
            // cancel. The only exits were a back press or a tap outside, and
            // neither is visible. Fred, 09-01: "it is insane trying to get back
            // to the desktop once we have things open."
            // ⭐ THE DOCUMENTS MOVE INTO THE BODY as a vertical list, which
            // frees dismissButton for a real Cancel and reads as a MENU rather
            // than three buttons competing for the same row.
            // ⚠ And it scales: a fourth document is one more row, not a layout
            // problem. Quick Start is a STUB this release (Fred) -- the row is
            // here so the layout is settled before the asset lands.
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDocsChooser = false },
                title = { androidx.compose.material3.Text("Help & Info") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text(
                            "Which document would you like?",
                            fontSize = 13.sp
                        )
                        androidx.compose.foundation.layout.Spacer(
                            Modifier.height(10.dp))
                        // ⚠ INLINED, not a shared helper: a `private fun` in
                        // another file is invisible here -- Kotlin's private is
                        // FILE-scoped, not package-scoped. Caught before
                        // shipping; it would have been a compile failure.
                        for ((key, title, sub) in listOf(
                            Triple("quickstart", "Quick Start",
                                "The short version \u2014 set up and go"),
                            Triple("notes", "Release Notes",
                                "What changed, newest first"),
                            Triple("manual", "Full Manual",
                                "Everything, in detail")
                        )) {
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        showDocsChooser = false; docsOpen(key)
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                androidx.compose.material3.Text(
                                    title, fontSize = 15.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = androidx.compose.ui.graphics.Color(0xFF58A6FF)
                                )
                                androidx.compose.material3.Text(
                                    sub, fontSize = 11.sp,
                                    color = androidx.compose.ui.graphics.Color(0xFF8B949E)
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showDocsChooser = false }
                    ) {
                        androidx.compose.material3.Text("Cancel")
                    }
                }
            )
        }
        if (docsView != null) {
            // DOCLAUNCH-2026-09-05: ⚠ the stub note that was here is gone --
            // all three documents ship as real assets now.
            // DOCSTACK-2026-09-06: ⚠ docsView may now carry a FRAGMENT --
            // "manual#mapkeys" -- so a task in the Quick Start lands on the
            // section that teaches it rather than the top of a 7 MB document.
            val docKey = docsView!!.substringBefore("#")
            val docFrag = docsView!!.substringAfter("#", "")
            val assetFile = when (docKey) {
                "notes" -> "grouptrack_release_notes.html"
                "quickstart" -> "grouptrack_quickstart.html"
                else -> "grouptrack_manual.html"
            } + (if (docFrag.isNotEmpty()) "#$docFrag" else "")
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = androidx.compose.ui.graphics.Color(0xFF10130F)
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Row(
                        // DOCSTACK-2026-09-06: ⛔ CLOSE WAS TOP RIGHT, UNDER
                        // QUEUES. Fred: "queues overlays the text box." Centre
                        // is the one place on this bar nothing else claims.
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        androidx.compose.material3.TextButton(onClick = { docsBack() }) {
                            androidx.compose.material3.Text("Close")
                        }
                    }
                    androidx.compose.ui.viewinterop.AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                    // HTMLVER-2026-08-13B: never serve a cached copy of a
                                    // bundled asset. ⚠ A cache-buster on the URL is NOT
                                    // used - WebView treats file:///android_asset/x.html
                                    // as a filename, so a query string risks a 404.
                                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                                settings.allowFileAccess = true
                                @Suppress("DEPRECATION")
                                settings.allowFileAccessFromFileURLs = true
                                // DOCSTACK-2026-09-06: ⭐ A LINK TO ANOTHER
                                // DOCUMENT PUSHES ONTO THE STACK instead of
                                // navigating this WebView. That is what lets
                                // Close come back to the Quick Start at the
                                // task the rider was on.
                                // ⚠ A link WITHIN the same document -- an
                                // anchor -- is left alone, or every jump would
                                // stack.
                                webViewClient = object : android.webkit.WebViewClient() {
                                    @Deprecated("Deprecated in Java")
                                    override fun shouldOverrideUrlLoading(
                                        view: android.webkit.WebView?, url: String?
                                    ): Boolean {
                                        val u = url ?: return false
                                        if (!u.startsWith("file:///android_asset/")) return false
                                        val name = u.removePrefix("file:///android_asset/")
                                        val f = name.substringAfter("#", "")
                                        val target = when {
                                            name.startsWith("grouptrack_manual") -> "manual"
                                            name.startsWith("grouptrack_quickstart") -> "quickstart"
                                            name.startsWith("grouptrack_release_notes") -> "notes"
                                            else -> return false
                                        }
                                        if (target == docKey) return false   // same doc: let it scroll
                                        docsOpen(if (f.isEmpty()) target else "$target#$f")
                                        return true
                                    }
                                }
                                loadUrl("file:///android_asset/" + assetFile)
                            }
                        },
                        update = { it.loadUrl("file:///android_asset/" + assetFile) }
                    )
                }
            }
        }
        // -- QUEUES button (LOCKED top-right, like planning map; drag removed 2026-06-03) --
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
                .clickable { queuesOpen = !queuesOpen },
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF2A3545)
        ) {
            Text("QUEUES",
                color = Color(0xFF1CF0A0),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
        }
        if (queuesOpen) {
            // Real download-queue monitor (matches planning "DOWNLOAD QUEUES").
            // Locked under the fixed top-right QUEUES button. 2.6: add
            // ALL|TILE|UPLOAD|DOWNLOAD selector when multiple queues exist.
            androidx.compose.material3.Surface(
                // CONVOY-QUEUES-WIDTH-2026-07-22: widened to match the Planning
                // queues panel (was TopEnd + fixed .width(260.dp), which
                // squeezed the shared DownloadQueuePanel content).
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
                    .fillMaxWidth(0.90f),
                shape = RoundedCornerShape(10.dp),
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
                            modifier = Modifier.clickable { queuesOpen = false }.padding(8.dp))
                    }
                    DownloadQueuePanel(
                        expanded = true,
                        onToggle = { queuesOpen = false }
                    )
                    val queueState = DownloadQueueManager.queue.collectAsState()
                    if (queueState.value.isEmpty()) {
                        Text("No downloads in queue",
                            color = Color(0xFFFFFFFF), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 52.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

                // QUEUES panel moved above maps bar

                // -- WORK WITH ARTIFACTS (V2.5 scaffold) -- FAB-gated, opens expanded --
                if (showArtifactsPanel) {
                ConvoyArtifactsPanel(
                    startExpanded = true,
                    onDismiss = { showArtifactsPanel = false },
                    isConvoyMap = true,
                    onCreateRoute = {
                        // +ROUTE -> choose New vs In-Progress BEFORE the toolbar opens.
                        showEntryChoice = true
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
                        when(typeName) {
                            "Trails" -> { trailState = newState }
                            "Tracks" -> { trackState = newState }
                            "Waypoints" -> { waypointState = newState }
                            "Routes" -> { routeState = newState }
                        }
                        saveConvoyState()
                        val wv = webViewRef.value ?: return@ConvoyArtifactsPanel
                        if (newState == DS_OFF) {
                            wv.evaluateJavascript("hide" + typeName + "()", null)
                        } else {
                            wv.evaluateJavascript("show" + typeName + "()", null)
                            wv.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        }
                    },
                    onEditDisplay = { typeName ->
                        // TRAILSELECT-2026-09-02: SELECT on Trails opens the
                        // CATEGORY filter, as on the planner. Fred, 09-02: all
                        // the selects in one place.
                        if (typeName == "Trails") {
                            showTrailFilter = true
                            return@ConvoyArtifactsPanel
                        }
                        val table = when(typeName) { "Tracks"->"tracks"; "Trails"->"trails"; "Waypoints"->"waypoints"; "Routes"->"routes"; else->return@ConvoyArtifactsPanel }
                        // [viewport fix] Query the SELECT list against the LIVE map bounds (the displayed
                        // frame) -- not stale lastViewport* (cache can lag/hold GPS point -> empty list).
                        val wvb = webViewRef.value ?: return@ConvoyArtifactsPanel
                        wvb.evaluateJavascript(
                            "(function(){try{var b=map.getBounds();return b.getSouth()+','+b.getWest()+','+b.getNorth()+','+b.getEast();}catch(e){return '';}})()"
                        ) { raw ->
                            val parts = (raw?.trim('"') ?: "").split(",")
                            if (parts.size != 4) { android.widget.Toast.makeText(context, "Map not ready", android.widget.Toast.LENGTH_SHORT).show(); return@evaluateJavascript }
                            val s = parts[0].toDoubleOrNull(); val w = parts[1].toDoubleOrNull(); val n = parts[2].toDoubleOrNull(); val e = parts[3].toDoubleOrNull()
                            if (s == null || w == null || n == null || e == null) { android.widget.Toast.makeText(context, "Map not ready", android.widget.Toast.LENGTH_SHORT).show(); return@evaluateJavascript }
                            kotlinx.coroutines.MainScope().launch {
                                val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    SpatialDbManager.init(context)
                                    SpatialDbManager.queryArtifactList(table, s, w, n, e)
                                }
                                if (list.isNotEmpty()) {
                                    artifactList = list
                                    // mirror planning: restore saved checked ids for SELECTED; all for ALL; none otherwise
                                    val curState = when(typeName) { "Trails"->trailState; "Tracks"->trackState; "Waypoints"->waypointState; "Routes"->routeState; else->DS_OFF }
                                    val curChecked = when(typeName) { "Trails"->trailCheckedIds; "Tracks"->trackCheckedIds; "Waypoints"->waypointCheckedIds; "Routes"->routeCheckedIds; else->null }
                                    selectedArtifactIds = when {
                                        curState == DS_SELECTED && curChecked != null -> curChecked
                                        curState == DS_ON -> list.mapNotNull { it["id"] }.toSet()
                                        else -> emptySet()
                                    }
                                    activeListType = typeName
                                } else {
                                    android.widget.Toast.makeText(context, "No $typeName in current view", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
                }
                if (showEntryChoice) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showEntryChoice = false },
                        title = { androidx.compose.material3.Text("Start a route") },
                        text = { androidx.compose.material3.Text("Begin a new route, or resume one in progress?") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showEntryChoice = false
                                routeLifecycleState = ROUTE_LS_NEW
                                routeMethod = ROUTE_METHOD_P2P
                                routeName = ""
                                routeNameTaken = false
                                showRouteNameDialog = true
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
                            webViewRef.value?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                            routeMode = rmTrace(false, "RM@ConvoyScreen:2291")
                            webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        } else {
                            android.widget.Toast.makeText(context, "Need at least 2 points to save", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (showRouteNameDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showRouteNameDialog = false },
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
                                "Enter a unique route name",
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
                                    showRouteNameDialog = false
                                    routeMode = rmTrace(true, "RM@ConvoyScreen:2329")
                                    webViewRef.value?.evaluateJavascript("setRouteMode(true)", null)  // arm tap-to-place
                                }
                            }) { androidx.compose.material3.Text("Start") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showRouteNameDialog = false
                            }) { androidx.compose.material3.Text("Cancel") }
                        }
                    )
                }
                if (routeMode) {
                    ConvoyRouteToolbar(
                        isConvoyMap = true,
                        vertexCount = RouteManager.routeVertexCount(),
                        selectedMethod = routeMethod,
                        onSelectMethod = { routeMethod = it },
                        onNewRoute = {
                            routeLifecycleState = ROUTE_LS_NEW
                            routeName = ""
                            routeNameTaken = false
                            showRouteNameDialog = true
                        },
                        onAddModeChanged = { _ ->
                            webViewRef.value?.evaluateJavascript("setRouteMode(true)", null)
                        },
                        onUndo = {
                            RouteManager.undoVertex()
                            val pts = RouteManager.routeVertices().joinToString(",", "[", "]") { "[${it.lat},${it.lon}]" }
                            webViewRef.value?.evaluateJavascript("drawBuildLine('" + pts + "')", null)
                        },
                        onSaveCompleted = saveCompleted,
                        routeLifecycleState = routeLifecycleState,
                        onSaveRequested = { showSaveChoice = true },
                        onDiscardRequested = { showDiscardChoice = true },
                        onSelectInProgress = { showInProgressPicker = true },
                        onExit = {
                            RouteManager.clearRoute()
                            webViewRef.value?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                            routeMode = rmTrace(false, "RM@ConvoyScreen:2369")
                        }
                    )
                }

                // ---- Route lifecycle dialogs (Layer 2; store calls stubbed) ----
                if (showSaveChoice) {
                    val pts = RouteManager.routeVertexCount()
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showSaveChoice = false },
                        title = { androidx.compose.material3.Text("Save route") },
                        text = { androidx.compose.material3.Text(
                            if (routeLifecycleState == ROUTE_LS_RESUMED)
                                "Graduate to a saved route, or keep editing as in-progress."
                            else "Save as a completed route (needs 2+ points), or keep as in-progress."
                        ) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showSaveChoice = false
                                if (pts >= 2) saveCompleted()
                                else android.widget.Toast.makeText(context, "Need at least 2 points", android.widget.Toast.LENGTH_SHORT).show()
                            }) { androidx.compose.material3.Text("Save as completed route") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showSaveChoice = false
                                val methodStr = when (routeMethod) { ROUTE_METHOD_DRAW -> "draw"; ROUTE_METHOD_SUGGEST -> "suggest"; else -> "point" }
                                if (routeLifecycleState == ROUTE_LS_RESUMED) RouteDraftStore.overwriteDraft(routeName, methodStr)
                                else RouteDraftStore.writeDraft(routeName, methodStr)
                                draftListTick++
                                RouteManager.clearRoute()
                                webViewRef.value?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                                routeMode = rmTrace(false, "RM@ConvoyScreen:2401")
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
                                val rbPts = RouteManager.routeVertices().joinToString(",", "[", "]") { "[${it.lat},${it.lon}]" }
                                webViewRef.value?.evaluateJavascript("drawBuildLine('" + rbPts + "')", null)
                            }) { androidx.compose.material3.Text("Roll back") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showDiscardChoice = false
                                RouteDraftStore.deleteDraft(routeName)
                                draftListTick++
                                RouteManager.clearRoute()
                                webViewRef.value?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                                routeMode = rmTrace(false, "RM@ConvoyScreen:2428")
                            }) { androidx.compose.material3.Text("Delete in-progress") }
                        }
                    )
                }

                if (showInProgressPicker) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showInProgressPicker = false },
                        title = { androidx.compose.material3.Text("Resume in-progress route") },
                        text = {
                            androidx.compose.foundation.layout.Column {
                                if (emulatedDrafts.isEmpty()) {
                                    androidx.compose.material3.Text("No in-progress routes",
                                        fontSize = 12.sp,
                                        color = androidx.compose.ui.graphics.Color(0xFF8A8A8A))
                                }
                                emulatedDrafts.forEach { d ->
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
                                            routeMode = rmTrace(true, "RM@ConvoyScreen:2457")
                                            val rsPts = RouteManager.routeVertices().joinToString(",", "[", "]") { "[${it.lat},${it.lon}]" }
                                            webViewRef.value?.evaluateJavascript("setRouteMode(true); drawBuildLine('" + rsPts + "')", null)
                                        }) { androidx.compose.material3.Text(d) }
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
                        confirmButton = {},
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showInProgressPicker = false }) {
                                androidx.compose.material3.Text("Cancel")
                            }
                        }
                    )
                }
                if (activeListType != null) {
                    ArtifactListPanel(
                        artifactType = activeListType!!,
                        artifacts = artifactList,
                        selectedIds = selectedArtifactIds,
                        onDismiss = {
                            val allIds = artifactList.mapNotNull { it["id"] }.toSet()
                            val checked = selectedArtifactIds
                            val newState = when { checked.isEmpty() -> DS_OFF; checked.containsAll(allIds) && allIds.size == checked.size -> DS_ON; else -> DS_SELECTED }
                            when (activeListType) {
                                "Trails" -> { trailState = newState; trailCheckedIds = if (newState == DS_SELECTED) checked else null }
                                "Tracks" -> { trackState = newState; trackCheckedIds = if (newState == DS_SELECTED) checked else null }
                                "Waypoints" -> { waypointState = newState; waypointCheckedIds = if (newState == DS_SELECTED) checked else null }
                                "Routes" -> { routeState = newState; routeCheckedIds = if (newState == DS_SELECTED) checked else null }
                            }
                            saveConvoyState()
                            activeListType = null
                            webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        },
                        onToggleItem = { id, checked -> selectedArtifactIds = if (checked) selectedArtifactIds + id else selectedArtifactIds - id },
                        onSelectAll = { selectedArtifactIds = artifactList.mapNotNull { it["id"] }.toSet() },
                        onDeselectAll = { selectedArtifactIds = emptySet() },
                        mapKey = "convoy",
                        fitWebView = webViewRef.value,
                        onOpenDetail = { t, id -> activeListType = null; pendingDetailType = t; pendingDetailId = id }
                    )
                }

                // [V2.6a-CONVOY-DLPANEL] standard source-select + replace confirm panel
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
                    // CONFIRM-BOX-2026-07-24: this dialog USED to render wherever its
                    // parent Column placed it and OVERFLOWED THE BOTTOM of the
                    // screen, putting its buttons out of reach unless the device
                    // was rotated. Planning centres the same dialog because
                    // planning's sits in a Box and can use Modifier.align().
                    // Convoy's sits in a COLUMN, where align() does not exist -
                    // so instead of borrowing scope, bring a Box: fillMaxSize
                    // makes it the whole viewport and contentAlignment centres
                    // the child without needing the scope extension.
                    // CONFIRM-WINDOW-2026-07-25: THIRD attempt, first to address the
                    // real cause. This dialog lives inside the Column at ~:1495,
                    // which is .align(Alignment.TopEnd) + .statusBarsPadding() +
                    // .padding(top = 52.dp, end = 8.dp). fillMaxSize() means "all
                    // the space MY PARENT OFFERS" - and that space already STARTS
                    // 52dp + status bar below the top. So patch J's Box centred
                    // the dialog in a region whose midpoint sits ~40-50dp BELOW
                    // true screen centre, and the bottom overflowed by that much.
                    // Planning renders correctly only because its dialog is NOT in
                    // a Column - it sits in the full-screen Box
                    // (ConvoyMapViewerScreen:417) and can use .align(Center).
                    // Patch J was the right KIND of fix in the WRONG PARENT.
                    //
                    // Moving this block to root scope would match planning most
                    // faithfully, but `estimate` and `slots` are computed locally
                    // just above, INSIDE this Column - moving the dialog alone
                    // breaks their scope. So: give the dialog its OWN WINDOW. A
                    // Dialog is not laid out in the parent composition at all; it
                    // gets a platform window centred on the SCREEN, so the
                    // Column's offset and padding stop mattering - and it stays
                    // correct if that Column is ever changed again.
                    //
                    // usePlatformDefaultWidth = false preserves existing sizing.
                    //
                    // onDismissRequest MIRRORS onCancel: back-press and outside-tap
                    // must also clear pendingCorridorHash, or a dismissed prompt
                    // leaves a stale hash and the NEXT area download is submitted
                    // as a CORRIDOR job.
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = {
                            showDownloadConfirm = false
                            pendingCorridorHash = null
                        },
                        properties = androidx.compose.ui.window.DialogProperties(
                            usePlatformDefaultWidth = false
                        )
                    ) {
                    ConvoyDownloadConfirm(
                        estimatedTiles = estimate.tileCount,
                        estimatedMB = estimate.estimatedMB,
                        areaDesc = String.format("%.3f deg N to %.3f deg N", downloadBbox.south, downloadBbox.north),
                        bbox = downloadBbox,
                        slots = slots,
                        onProceed = { bbox, selectedSlots, replace ->
                            showDownloadConfirm = false
                            // CORRIDOR-WIRING-2026-07-24: non-null hash = corridor job, ONE
                            // ENTRY PER SOURCE. Without this branch the convoy
                            // corridor button would open the prompt and then
                            // SILENTLY SUBMIT AN AREA DOWNLOAD - worse than
                            // having no button at all.
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
                        // modifier LEFT ALONE - it never needed changing, only the
                        // dialog's PLACEMENT was wrong (see CONFIRM-BOX-2026-07-24 above).
                        modifier = Modifier.padding(16.dp)
                    )
                    }   // CONFIRM-WINDOW-2026-07-25: close the Dialog window
                }

                // -- OLD DISPLAY PANEL (disabled for spatial DB) --
                if (false) { ConvoyDisplayPanel(
                    tracksOn = tracksVisible,
                    onTracksToggle = {
                        tracksVisible = !tracksVisible
                        if (tracksVisible) {
                            if (tracksLoaded) {
                                webViewRef.value?.evaluateJavascript("showTracks()", null)
                            } else {
                                tracksLoaded = true
                                val wv = webViewRef.value
                                kotlinx.coroutines.MainScope().launch {
                                    val trackColor = "#39FF14"
                                    val dir = ConvoyTrackOps.tracksDir()   // MYTRACKSUI-2026-09-12
                                    val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
                                    }
                                    files.forEach { name ->
                                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                val file = java.io.File(dir, name)
                                                if (!file.exists()) return@withContext null
                                                val text = file.readText()
                                                val coords = if (name.lowercase().endsWith(".gpx")) convoyParseGpx(text) else convoyParseKml(text)
                                                if (coords.isEmpty()) return@withContext null
                                                coords.joinToString(",", "[", "]") { p -> "[${p.first},${p.second}]" }
                                            } catch (e: Exception) { null }
                                        }
                                        if (result != null) {
                                            val safe = name.replace("'", "\\'")
                                            wv?.evaluateJavascript("loadTrackFile('" + safe + "', '" + result + "', '" + trackColor + "')", null)
                                        }
                                    }
                                }
                            }
                        } else {
                            webViewRef.value?.evaluateJavascript("hideTracks()", null)
                        }
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
                                run {
                                    // [V2.6-PASS1-S4] DB-backed coverage (raw z/x/y at z14)
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
                            } // wv != null
                        } // not scanning
                        }
                    },
                    scanningDownloaded = scanningDownloaded
                ) }


            // REC button placeholder — large button added as map overlay below

            // SIM/LIVE toggle removed (V2.5 cleanup)
        }

        // GATEMOVE-2026-09-10: ⛔ THIS BLOCK USED TO LIVE INSIDE THE
        // map-control Column above (align(TopEnd), padded, sized to its
        // children). ArtifactDetailPanel is a FULL-SCREEN detail view --
        // it does not belong in a narrow right-hand overlay, and the
        // planner has always had its gate out here, one level shallower.
        // ⚠ The symptom that sent us looking: the bridge fired and set
        // pendingDetailType/Id, and the unconditional Log.d below NEVER
        // RAN -- while the planner's identical gate logs continuously.
        // Bridge, asset version, braces, exceptions and today's patches
        // were all ruled out first. ⚠ THE MOVE IS CORRECT REGARDLESS; if
        // the taps still fail, the log we finally get is the next clue.
            android.util.Log.d(
                "DetailGate",
                "CONVOY gate type=$pendingDetailType id=$pendingDetailId"
            )
            if (pendingDetailId != null && pendingDetailType != null) {
                ArtifactDetailPanel(
                    // TRACKRIDE-CONVOY-2026-10-05 (Fred): ADD A RIDE (routes) and CREATE RIDE (tracks) on the RIDE map too -- the SAME panel
                    // actions as the planner (ConvoyMapViewerScreen), the same track->route conversion, the same ride form.
                    // No picture capture here: it would fit the map to the route, moving the rider's view away from the
                    // carts; the ride form falls back to the drawn route line (RidePreview: "never blocks").
                    onAddRide = { a: String, b: String -> onAddRide(a, b) },
                    onCreateRideFromTrack = { tid: String, name: String, desc: String, th: ConvoyArtifactOps.RouteTrailhead ->
                        coroutineScope.launch {
                            val rid = ConvoyArtifactOps.trackToRoute(context, tid, name, desc, th)
                            if (rid == null) {
                                android.widget.Toast.makeText(context, "Could not make a route from this track", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                pendingDetailId = null; pendingDetailType = null
                                onAddRide("Route", rid)
                            }
                        }
                    },
                    artifactType = pendingDetailType!!,
                    id = pendingDetailId!!,
                    mapKey = "convoy",
                    fitWebView = webViewRef.value,
                    onLoadDetail = { t, did -> SpatialDbManager.getArtifactDetail(t, did) },
                    onLoadAliases = { t, did -> SpatialDbManager.getAliasesFor(t, did) },
                    // [2026-06-20] Full action parity on convoy. Handlers mirror planning
                    // (ConvoyMapViewerScreen) verbatim; the ONLY divergence is the table is
                    // keyed off pendingDetailType (detail can open from SEARCH, where
                    // activeListType is null), not activeListType. Refresh uses convoy's
                    // existing onViewportChanged JS round-trip.
                    onRename = { id, newName ->
                        val capType = pendingDetailType
                        if (capType != null) coroutineScope.launch { ConvoyArtifactOps.rename(context, capType, id, newName); webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null) }
                    },
                    onDelete = { id ->
                        val capType = pendingDetailType
                        if (capType != null) coroutineScope.launch {
                            ConvoyArtifactOps.delete(context, capType, id)
                            artifactList = artifactList.filter { it["id"] != id }
                            selectedArtifactIds = selectedArtifactIds - id
                            webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        }
                    },
                    onShare = { id -> val capType = pendingDetailType; if (capType != null) coroutineScope.launch { ConvoyArtifactOps.share(context, capType, id) } },
                    onExport = { id -> val capType = pendingDetailType; if (capType != null) coroutineScope.launch { ConvoyArtifactOps.export(context, capType, id) } },
                    onDownloadMaps = { hash ->
                        // [V2.6a-CONVOY-DLPANEL] invoke the standard confirm panel (was old direct-queue)
                        Thread {
                            val bb = SpatialDbManager.getTrackBbox(context, hash)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (bb != null && bb.isValid) {
                                    pendingDetailId = null; pendingDetailType = null  // [V2.6a-DLPANEL-CLOSE] close detail when panel opens (mirror viewer)
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
                    // CORRIDOR-WIRING-2026-07-24: same prompt as area, different
                    // submission. The bbox is for DISPLAY in the dialog only.
                    onDownloadCorridor = { hash ->
                        Thread {
                            // ROUTECORR-2026-08-10C: tracks then routes - a route needs a box too.
                            // The onDownloadMaps lambda above is the AREA path and
                            // stays tracks-only.
                            val bb = SpatialDbManager.getCorridorBbox(context, hash)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (bb != null && bb.isValid) {
                                    pendingDetailId = null; pendingDetailType = null
                                    pendingCorridorHash = hash
                                    downloadBbox = bb
                                    showDownloadConfirm = true
                                } else {
                                    android.widget.Toast.makeText(context,
                                        "No geometry stored for this item",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    },
                    onChangeType = { id, newType ->
                        coroutineScope.launch { ConvoyArtifactOps.changeType(context, id, newType); webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null) }
                    },
                    onDeleteAlias = { aliasId -> coroutineScope.launch { ConvoyArtifactOps.deleteAlias(context, aliasId) } },
                    onDismiss = { fittedType, fittedId ->
                        if (fittedType != null && fittedId != null) {
                            // [FIT 2026-06-18] Emulate a manual row-select on the LIVE vars
                            // (mirror of the ArtifactListPanel select at ~1669): set this type
                            // SELECTED with exactly the fitted id. saveConvoyState then reads the
                            // populated live var (no empty-row clobber) and the SEL/EDIT panel
                            // reflects it. FIT = one artifact by definition.
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
                                    webViewRef.value?.evaluateJavascript("fitBounds(["+_fS+","+_fN+"],["+_fW+","+_fE+"])", null)
                                }
                            }
                            saveConvoyState()
                            webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        } else {
                            // Non-FIT dismiss (CLOSE/rename/etc.): reflect persisted state.
                            val rs = MapStateStore.readMap("convoy")
                            trailState = rs.types["Trails"]?.state ?: DS_OFF
                            trackState = rs.types["Tracks"]?.state ?: DS_OFF
                            waypointState = rs.types["Waypoints"]?.state ?: DS_OFF
                            routeState = rs.types["Routes"]?.state ?: DS_OFF
                            trailCheckedIds = MapStateStore.checkedIdsFor(rs, "Trails")
                            trackCheckedIds = MapStateStore.checkedIdsFor(rs, "Tracks")
                            waypointCheckedIds = MapStateStore.checkedIdsFor(rs, "Waypoints")
                            routeCheckedIds = MapStateStore.checkedIdsFor(rs, "Routes")
                        }
                        pendingDetailId = null; pendingDetailType = null
                    }
                )
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
        var showCartPicker by CartPickerLauncher.showing   // CARTLIST2-2026-09-28: shared -- CartPickerLauncher.open()/close() from anywhere
        ConvoyButtonBar(
            hudMode = hudMode,
            onModeChange = { viewModel.setHudMode(it); viewModel.setAutoPan(true) },
            onNavigateToSettings = onNavigateToSettings,
            onSelectCart = { showCartPicker = true },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // ROLECHANGE-2026-09-29 (Fred): CHANGE MY ROLE from the group panel -- MY OWN cart only; nobody changes anyone else's.
        // A special role someone holds is refused (released first); Rider releases mine. The Leader is the last assignment.
        var showRoleChange by remember { mutableStateOf(false) }
        if (showRoleChange) viewModel.checkIn.value?.let { rci ->
            RoleChangeDialog(
                currentRole = rci.role,
                heldBy = convoyState.nodes
                    .filter { !it.isMyCart && it.rideRole in setOf("leader", "middle", "tail_gunner") }
                    .associate { it.rideRole to it.callsign.ifBlank { it.nodeId } },
                onApply = { newRole ->
                    viewModel.checkIn.value = rci.copy(role = newRole)
                    viewModel.startRoleReports(newRole)   // ROLEANYRIDE-2026-09-29 (Fred): broadcast on ANY ride, scheduled or not
                    showRoleChange = false
                },
                onDismiss = { showRoleChange = false }
            )
        }
        // ── Cart Picker Panel (Phase 0 stub) ──────────────────────────────
        if (showCartPicker) {
            // CARTPICKER-2026-09-28: the ride and this tablet's role, from the check-in.
            val pickCi = viewModel.checkIn.value
            val pickRide = remember(pickCi?.rideId) { pickCi?.rideId?.let { ConvoyRideStore.rideForEdit(it) } }
            CartPickerPanel(
                removed = viewModel.removedCarts.collectAsStateWithLifecycle().value,   // CARTACTIVE-2026-09-30
                onToggleActive = { id, cs ->
                    val removing = !viewModel.removedCarts.value.containsKey(id)
                    viewModel.toggleCartActive(id, cs)
                    if (removing) webViewRef.value?.evaluateJavascript("removeMarker('$id')", null)   // as the old REMOVE did
                },
                nodes = convoyState.nodes,
                rideTitle = pickRide?.name ?: (if (pickCi != null) "No scheduled ride" else ""),
                rideSub = pickRide?.let { r -> listOf(r.date, if (r.startTime.isNotBlank()) "rollout " + r.startTime else "")
                    .filter { it.isNotBlank() }.joinToString(" \u00b7 ") } ?: "",
                myRole = pickCi?.role ?: "",
                onSelect = { selectedNode ->
                    // ROLECHANGE-2026-09-29: MY OWN cart (checked in) -> change my role; any other cart -> its information, as before
                    if (selectedNode.isMyCart && viewModel.checkIn.value != null) showRoleChange = true
                    else viewModel.onMarkerTapped(selectedNode)
                    showCartPicker = false
                },
                onDismiss = { showCartPicker = false },
                onChangeMyRole = { showRoleChange = true; showCartPicker = false }   // ROLEBTN-2026-09-29
            )
        }

        val avgChannelUtil by viewModel.avgChannelUtil.collectAsStateWithLifecycle()
        val currentIntervalSecs by viewModel.currentIntervalSecs.collectAsStateWithLifecycle()
        // GPSPANEL-2026-09-29 (Fred): the GPS button's panel -- "Sent every XX seconds" (3-10), applied to the radio ONCE.
        val gpsLocalCfg by channelViewModel.localConfig.collectAsStateWithLifecycle()
        val gpsNowSecs = gpsLocalCfg.position?.broadcast_smart_minimum_interval_secs ?: 0   // proto optional: 0 = not read yet
        val gpsStatus by viewModel.gpsApply.collectAsStateWithLifecycle()
        val gpsBusy by viewModel.gpsApplyBusy.collectAsStateWithLifecycle()
        var showGpsPanel by remember { mutableStateOf(false) }
        if (showGpsPanel) GpsIntervalDialog(
            currentSecs = gpsNowSecs, channelUtil = avgChannelUtil, carts = convoyState.nodes.size,
            status = gpsStatus, busy = gpsBusy,
            onApply = { secs -> viewModel.applyLocationInterval(uiViewModel, secs) },
            onDismiss = { showGpsPanel = false; viewModel.clearGpsApplyStatus() }
        )
        // HUDSCHEME-2026-09-29 (Fred): the HUD's colour scheme follows the map -- SAT (or a hybrid) is dark imagery.
        val hudOnDark = mapTypeLabel.uppercase().let { it.startsWith("SAT") || it.contains("HYB") }
        // ── HUD strip ─────────────────────────────────────────────────────
        Box(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 48.dp)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalHudOnDark provides hudOnDark) {   // HUDSCHEME-2026-09-29
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { // GPSPANEL-2026-09-29
                if (hudMode != HudMode.COLLAPSED) GpsLocationRow(intervalSecs = gpsNowSecs, channelUtil = avgChannelUtil,
                    onOpen = { viewModel.clearGpsApplyStatus(); showGpsPanel = true })
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
            } // GPSPANEL-2026-09-29: end of the column (GPS row on top of the HUD)
            } // HUDSCHEME-2026-09-29: end of the HUD's colour scheme
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
        // === HUDINTGATE-2026-08-17 ===
        // Only rendered when there is a group. Dragging this slider calls setGpsInterval ->
        // channelViewModel.setConfig, and with no radio attached the binder throws
        // RemoteException("Not connected to radio") on the main thread and kills the app.
        // setGpsInterval's own try/catch does NOT catch it: setConfig returns before the
        // throw, so the exception fires outside that block. Not rendering the control is
        // the reliable fix. It is also the correct behaviour on its own terms — with a
        // single node there is nobody to broadcast to, so the interval is meaningless.
        // NOTE: node count infers radio presence rather than testing it. The direct signal
        // is ConvoyViewModel.myNodeInfo != null (what ConvoyApplyRadioScreen uses). If
        // convoy state is ever populated without a radio (see simulationMode), guard
        // setGpsInterval too.
        if (false) { // GPSPANEL-2026-09-29: the slider is retired -- the GPS button's panel replaces it
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
        } // HUDINTGATE-2026-08-17: end group-only interval slider
        HudCard {
            HiVisText("GROUP", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            // Row 1: SPAN big + CH% color block
            Row(verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    HiVisText("SPAN", color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        modifier = Modifier.padding(end = 4.dp, bottom = 6.dp))
                    HiVisText("%.1f".format(state.span_miles),
                        color = Color.White,
                        fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
                    HiVisText(" mi", color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                if (false) /* GPSPANEL-2026-09-29: CH% moved to the GPS row on top of the HUD */ Column(horizontalAlignment = Alignment.CenterHorizontally,
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
        HiVisText("My Cart  ★ ${myCart?.callsign ?: myCartId.takeLast(8)}", color = Color.White, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 6.dp))
        if (myCart == null) {
            HiVisText("MY CART not found", color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
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
                    HiVisText("Speed", color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        HiVisText("%.0f".format(myCart.speed_mph), color = Color.White,
                            fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
                        HiVisText(" mph", color = Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
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
    onDismiss: () -> Unit,
    onRemove: (ConvoyNode) -> Unit = {},
    onSetLead: (ConvoyNode) -> Unit = {}
) {
    HudCard {
        // Title — cart callsign
        HiVisText(node.callsign, color = Color.White, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 6.dp))
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
        // CARTACTIVE-2026-09-30 (Fred): SET AS LEAD and REMOVE FROM RIDE removed -- the lead comes from the roles, and a
        // cart is removed or reactivated in SELECT CART (REMOVE FROM RIDE never stuck: the next tick put the cart back).
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
fun HudStat(label: String, value: String, valueColor: Color = Color.White) {   // HUDTEXT-2026-09-29: default white
    Column(horizontalAlignment = Alignment.Start) {
        HiVisText(label, color = Color.White, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        HiVisText(value, color = valueColor, fontSize = 16.sp,
            fontWeight = FontWeight.Black)
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


// ── CART PICKER PANEL ─────────────────────────────────────────────────────────
// CARTPICKER-2026-09-28 (Fred): SELECT CART design v4 -- the ride on top; the radios on this network, each with its
// status dot, callsign and role. Everything shown comes from each radio's own reports; until the roles piece, only THIS
// tablet's check-in is known, so this cart is green with its role and every other radio is red, "not checked in", Rider.
@Composable
fun CartPickerPanel(
    nodes: List<com.geeksville.mesh.convoy.ConvoyNode>,
    rideTitle: String,   // the checked-in ride's name, "No scheduled ride", or "" when not checked in
    rideSub: String,     // its date and rollout, or ""
    myRole: String,      // this tablet's check-in role ("leader"...), or "" when not checked in
    onSelect: (com.geeksville.mesh.convoy.ConvoyNode) -> Unit,
    onDismiss: () -> Unit,
    removed: Map<String, String>,              // CARTACTIVE-2026-09-30: nodeId -> callsign, removed on this tablet today
    onToggleActive: (String, String) -> Unit,  // CARTACTIVE-2026-09-30: one tap -- Active <-> Removed
    onChangeMyRole: () -> Unit   // ROLEBTN-2026-09-29 (Fred): opens "Change my role" -- my own cart only
) {
    fun roleLabel(r: String) = when (r) { "leader" -> "Leader"; "middle" -> "Middle"; "tail_gunner" -> "Tail gunner"; else -> "Rider" }
    val green = Color(0xFF35C46A); val red = Color(0xFFE0453A); val dim = Color(0xFF8FA3B8)
    var listOpen by remember { mutableStateOf(true) }   // CARTPICKER3-2026-09-28 (Fred): the twistie -- opens unfolded
    // CARTPICKER2-2026-09-28 (Fred): shown OVER the map -- no dark curtain, a semi-transparent panel, and the map stays
    // usable (taps outside the panel reach the map); the list closes with CLOSE or REC.
    Box(
        modifier = Modifier
            .fillMaxSize()   // CARTBTN-2026-09-30: fill the screen so BottomCenter places it above the bar
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color(0x8C1A2E4A)   // CARTPICKER4-2026-09-28 (Fred): ~55%, the map clearly visible
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // The ride, on top.
                if (rideTitle.isNotBlank()) {
                    Text(rideTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (rideSub.isNotBlank()) Text(rideSub, color = dim, fontSize = 12.sp)
                } else Text("Not checked in to a ride", color = dim, fontSize = 13.sp)
                // ROLEBTN-2026-09-29 (Fred): change MY role from here -- whether or not my own cart is in the list.
                if (rideTitle.isNotBlank()) androidx.compose.material3.TextButton(onClick = { onChangeMyRole() }) {
                    Text("CHANGE MY ROLE \u00b7 now " + roleLabel(myRole), color = Color(0xFFFFD166),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                // The title and the list's own CLOSE (tapping outside still closes it too).
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (listOpen) "\u25BE" else "\u25B8", color = Color(0xFF67EA94), fontSize = 18.sp,   // CARTPICKER3
                        modifier = Modifier.clickable { listOpen = !listOpen }.padding(end = 8.dp))
                    Text("SELECT CART \u00b7 ${nodes.size} radio" + (if (nodes.size == 1) "" else "s") + " on this network",
                        color = Color(0xFF67EA94), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { onDismiss() }) {
                        Text("CLOSE", color = Color(0xFFCAC4D0), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
                if (listOpen) {   // CARTPICKER3: the radios and the legend fold away
                if (nodes.isEmpty()) {
                    Text("No radios heard yet", color = Color(0xFF7A8DA0), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                } else {
                    // CARTPICKER5-2026-09-28 (Fred): Leader, Middle, Tail gunner first, then Riders -- checked-in Riders before bare
                    // radios; alphabetical within each. (Until the roles piece only this cart can show a special role.)
                    fun rank(n: com.geeksville.mesh.convoy.ConvoyNode): Int {
                        val r = n.rideRole.ifBlank { if (n.isMyCart) myRole else "" }   // TICKDATA: the node's own role
                        val ci = r.isNotBlank()
                        return when (if (ci) r else "rider") { "leader" -> 0; "middle" -> 1; "tail_gunner" -> 2; else -> if (ci) 3 else 4 }
                    }
                    nodes.sortedWith(compareBy<com.geeksville.mesh.convoy.ConvoyNode>({ rank(it) }, { it.callsign.lowercase() })).forEach { node ->
                        // TICKDATA-2026-09-28 (Fred): each cart's OWN role, from the tick (your own cart falls back to your check-in).
                        val nodeRole = node.rideRole.ifBlank { if (node.isMyCart) myRole else "" }
                        val checkedIn = nodeRole.isNotBlank()
                        val role = if (checkedIn) roleLabel(nodeRole) else "Rider"
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { onSelect(node) },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x802A3545)   // CARTPICKER4: ~50%
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("\u25CF", color = if (checkedIn) green else red, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(node.callsign, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text((if (checkedIn) "checked in" else if (node.isMyCart) "not checked in" else "bare radio") + (if (node.isMyCart) " \u00b7 you" else ""),
                                        color = dim, fontSize = 11.sp)
                                }
                                Text(role.uppercase(), color = Color(0xFF9CC7F5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                if (!node.isMyCart) {   // CARTACTIVE-2026-09-30 (Fred): one tap -- Active -> Removed (my own cart: never)
                                    Text("REMOVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,   // CARTBTN-2026-09-30: the action
                                        modifier = Modifier.padding(start = 10.dp).background(red, RoundedCornerShape(6.dp))
                                            .clickable { onToggleActive(node.nodeId, node.callsign) }.padding(horizontal = 10.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }
                }
                // CARTACTIVE-2026-09-30 (Fred): carts removed on this tablet -- off the map and out of the group; one tap reactivates.
                removed.filterKeys { k -> nodes.none { it.nodeId == k } }.toList().sortedBy { it.second.lowercase() }.forEach { (rid, rcall) ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(8.dp), color = Color(0x802A3545)) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("\u25CB", color = dim, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rcall, color = dim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("removed \u00b7 off the map", color = dim, fontSize = 11.sp)
                            }
                            Text("ACTIVATE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,   // CARTBTN-2026-09-30: the action
                                modifier = Modifier.padding(start = 10.dp).background(green, RoundedCornerShape(6.dp))
                                    .clickable { onToggleActive(rid, rcall) }.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                }
                Text("\u25CF checked in    \u25CF bare radio \u2014 no GroupTrack role in its reports: counted as Rider. " +
                    "Checked-in riders show green once the roles update carries their role.", color = dim, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp))
                }   // CARTPICKER3: end of the folding list
            }
        }
    }
}

// ── CONVOY BUTTON BAR ─────────────────────────────────────────────────────────

@Composable
fun ConvoyButtonBar(
    hudMode: HudMode,
    onModeChange: (HudMode) -> Unit,
    onNavigateToSettings: () -> Unit,
    onSelectCart: () -> Unit = {},
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
        // SELECT CART button — Phase 0 scaffolding
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Transparent)
                .clickable { onSelectCart() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "CHECKIN /\nSELECT\nCART",   // CARTBTN-2026-09-28 (Fred): who is checked in, and a cart to select
                color = Color(0xFFCAC4D0),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 9.sp   // CARTBTN: three lines in the same button
            )
        }
        // HIDE button
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Transparent)
                .clickable { onModeChange(HudMode.COLLAPSED) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "HIDE",
                color = Color(0xFFCAC4D0),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
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
    val dir = ConvoyTrackOps.tracksDir()   // MYTRACKSUI-2026-09-12
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
        val dir = ConvoyTrackOps.tracksDir()   // MYTRACKSUI-2026-09-12
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
        val dir = ConvoyTrackOps.tracksDir()   // MYTRACKSUI-2026-09-12
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

// === GPSPANEL-2026-09-29 (Fred): the GPS button + congestion meter (on top of the HUD, left edge), and its panel ===
// Crisp text on solid dark backgrounds (no blurred halo -- see the outdoor-legibility work).
private fun gpsChColor(util: Float): Color = when {
    util > 40f -> Color(0xFFFF4444)      // red: a congestion problem
    util > 25f -> Color(0xFFFFAA00)      // yellow: the range to adjust in
    else       -> Color(0xFF00CC44)      // green: leave it
}

@Composable
fun GpsLocationRow(intervalSecs: Int, channelUtil: Float, onOpen: () -> Unit) {
    val c = gpsChColor(channelUtil)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 4.dp)) {
        // the satellite with "GPS" across it -- tap to open "Sent every XX seconds"
        Box(modifier = Modifier.size(54.dp)
                .background(Color(0xE6111820), RoundedCornerShape(10.dp))
                .clickable { onOpen() },
            contentAlignment = Alignment.Center) {
            Text("\uD83D\uDEF0", fontSize = 28.sp)
            Text("GPS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.background(Color(0xDD2E75B6), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
        }
        // the meter: the interval, and the channel use in its colour
        // GPSMETER-CLEAR-2026-09-29: transparent -- no tile; crisp dark outline instead of a background or a blurred halo
        Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
            GpsOutlinedText(if (intervalSecs > 0) "${intervalSecs} s" else "-- s", Color.White, 18, FontWeight.Black)   // GPSMETER-LABEL-2026-09-29
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(12.dp)
                    .background(Color(0xFF111111), RoundedCornerShape(3.dp))
                    .padding(1.5.dp)
                    .background(c, RoundedCornerShape(2.dp)))
                GpsOutlinedText("%.0f%%".format(channelUtil), c, 18, FontWeight.Black)
            }
        }
    }
}

@Composable
fun GpsIntervalDialog(currentSecs: Int, channelUtil: Float, carts: Int, status: String?, busy: Boolean,
                      onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (currentSecs in 3..10) currentSecs.toString() else "") }
    // GPSTIMING-2026-09-30 (Fred): the second reconnect's timing -- 20 s / 4 s from the bench (Nathan's default too),
    // kept under a twistie so it can be raised on the trail if a cart ever fails to reappear. Defaults are in the code.
    var timingOpen by remember { mutableStateOf(false) }
    var waitText by remember { mutableStateOf(GpsReconnectTiming.waitSecs.toString()) }
    var gapText by remember { mutableStateOf(GpsReconnectTiming.gapSecs.toString()) }
    val secs = text.trim().toIntOrNull()
    val valid = secs != null && secs in 3..10
    val done = status?.startsWith("Done") == true
    val c = gpsChColor(channelUtil)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sent every " + (if (currentSecs > 0) "$currentSecs" else "--") + " seconds", fontWeight = FontWeight.Bold) },   // GPSMETER-LABEL-2026-09-29
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Now: every " + (if (currentSecs > 0) "$currentSecs s" else "--") + " while moving")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Channel use ")
                    Text("%.0f%%".format(channelUtil), color = c, fontWeight = FontWeight.Black)
                    Text("  \u00b7  $carts carts")
                }
                Text("Green: leave it.  Yellow: send less often (a higher number).  Red: congestion.", fontSize = 12.sp)
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(2) },
                    label = { Text("Seconds (3 to 10)") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
                if (text.isNotEmpty() && !valid) Text("Enter a number from 3 to 10.", color = Color(0xFFFF6B6B))
                // GPSTIMING-2026-09-30: the twistie -- collapsed, it shows the values in use.
                Text((if (timingOpen) "\u25BE" else "\u25B8") + "  Reconnect timing  \u00b7  wait ${GpsReconnectTiming.waitSecs} s  \u00b7  gap ${GpsReconnectTiming.gapSecs} s",
                    fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { timingOpen = !timingOpen }.padding(vertical = 4.dp))
                if (timingOpen) {
                    Text("Raise the wait if your cart does not reappear after APPLY.", fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = waitText,
                            onValueChange = { v -> waitText = v.filter { it.isDigit() }.take(2)
                                waitText.toIntOrNull()?.takeIf { it in 1..60 }?.let { GpsReconnectTiming.waitSecs = it } },
                            label = { Text("Wait after connected (s)", fontSize = 11.sp) },
                            singleLine = true, enabled = !busy, modifier = Modifier.weight(1f),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = gapText,
                            onValueChange = { v -> gapText = v.filter { it.isDigit() }.take(2)
                                gapText.toIntOrNull()?.takeIf { it in 1..60 }?.let { GpsReconnectTiming.gapSecs = it } },
                            label = { Text("Gap (s)", fontSize = 11.sp) },
                            singleLine = true, enabled = !busy, modifier = Modifier.weight(1f),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                    }
                }
                if (status != null) Text(status, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { secs?.let(onApply) },
                enabled = valid && !busy && !done && secs != currentSecs) { Text("APPLY") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !busy) { Text(if (done) "CLOSE" else "CANCEL") }
        }
    )
}

// GPSTIMING-2026-09-30 (Fred): the GPS apply's second reconnect -- the wait after the first reconnect is Connected,
// and the gap between its disconnect and reconnect. Bench 09-30: 10 s failed, 15 s failed 1 of 2, 20 s worked 3 of 3
// (Nathan's default too). The defaults live here; the GPS panel's twistie can raise them for the session.
object GpsReconnectTiming {
    const val DEFAULT_WAIT_SECS = 20
    const val DEFAULT_GAP_SECS = 4
    var waitSecs by mutableStateOf(DEFAULT_WAIT_SECS)
    var gapSecs by mutableStateOf(DEFAULT_GAP_SECS)
}

// GPSMETER-CLEAR-2026-09-29: text that stays readable over the map with no background -- a dark STROKE drawn under the fill.
// A crisp edge, not a blur (the blurred white halo is what reads as haze outdoors).
@Composable
private fun GpsOutlinedText(text: String, color: Color, sizeSp: Int, weight: FontWeight) {
    Box {
        Text(text, color = Color(0xFF111111), fontSize = sizeSp.sp, fontWeight = weight,
            style = androidx.compose.ui.text.TextStyle(
                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 6f, join = androidx.compose.ui.graphics.StrokeJoin.Round)))
        Text(text, color = color, fontSize = sizeSp.sp, fontWeight = weight)
    }
}

// === ROLECHANGE-2026-09-29 (Fred): "Change my role" -- opened from MY OWN cart in SELECT CART ===
@Composable
fun RoleChangeDialog(currentRole: String, heldBy: Map<String, String>, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    val roles = listOf("leader" to "Leader", "rider" to "Rider", "middle" to "Middle", "tail_gunner" to "Tail gunner")
    val now = currentRole.ifBlank { "rider" }
    var role by remember { mutableStateOf(now) }
    var heldWarn by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change my role", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Now: " + (roles.firstOrNull { it.first == now }?.second ?: "Rider"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    roles.forEach { (v, l) ->
                        androidx.compose.material3.FilterChip(selected = role == v,
                            onClick = { if (heldBy[v] != null) heldWarn = v else role = v }, label = { Text(l) })
                    }
                }
                Text("To give up a role, choose Rider. A role someone holds must be released first. The Leader is changed last.",
                    fontSize = 12.sp, color = Color(0xFF8899AA))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = role != now, onClick = { onApply(role) }) { Text("APPLY") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
    heldWarn?.let { w ->
        val label = roles.firstOrNull { it.first == w }?.second ?: w
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { heldWarn = null },
            containerColor = Color(0xFFB3261E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("\u26D4 " + label + " is taken", fontWeight = FontWeight.Black, fontSize = 22.sp) },
            text = { Text(label + " is held by " + (heldBy[w] ?: "another cart") + ".\n\n" +
                "It must be released first: its holder changes to Rider.", fontSize = 17.sp) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { heldWarn = null }) {
                Text("OK", color = Color.White, fontWeight = FontWeight.Black) } }
        )
    }
}

// === HUDTEXT-2026-09-29 (Fred): OUTDOOR LEGIBILITY -- the shared high-visibility text ===
// The fill drawn over a CRISP DARK STROKE -- no blur (the white 6-10 px blurred glow read as haze over SAT imagery).
// Readable over SAT (the light fill) and over TOPO / TOPO+ (the dark edge). Same parameters as Text. No colour given ->
// the surrounding content colour, as Text does. Nullable parameters mirror Text's own API (null = inherit) -- CODE RULE 1.
// HUDEDGE-2026-09-29 (Fred): the edge SCALES WITH THE TEXT -- a fixed 6 px flooded the small letters. Tune outdoors, here only.
const val HIVIS_EDGE_RATIO = 0.06f    // edge = 6% of the font size in pixels
const val HIVIS_EDGE_MIN_PX = 1.5f    // never thinner than this
// HUDSCHEME-2026-09-29 (Fred): NO edge -- plain text, white on the dark map (SAT), dark on the light maps (TOPO / TOPO+).
const val HIVIS_EDGE_ON = false       // the outline, kept for the field: set true to bring it back
/** HUDSCHEME-2026-09-29: true when the HUD sits over a DARK map (SAT). Provided around the HUD strip by the ride map. */
val LocalHudOnDark = androidx.compose.runtime.compositionLocalOf { true }

@Composable
fun HiVisText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    edgeColor: Color = Color(0xFF111111),
) {
    val onDark = LocalHudOnDark.current   // HUDSCHEME-2026-09-29
    val fill = when {
        color == Color.Unspecified -> androidx.compose.material3.LocalContentColor.current
        color == Color.White && !onDark -> Color(0xFF111111)   // labels: dark on the light maps
        else -> color                                           // colours unchanged
    }
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) {
        (if (fontSize.isSp) fontSize else 14.sp).toPx()
    }
    val edgePx = maxOf(HIVIS_EDGE_MIN_PX, sizePx * HIVIS_EDGE_RATIO)   // HUDEDGE-2026-09-29
    Box(modifier) {
        if (HIVIS_EDGE_ON) androidx.compose.material3.Text(text, color = edgeColor, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textAlign = textAlign,
            lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
            style = androidx.compose.ui.text.TextStyle(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                width = edgePx, join = androidx.compose.ui.graphics.StrokeJoin.Round)))
        androidx.compose.material3.Text(text, color = fill, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textAlign = textAlign,
            lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines)
    }
}
