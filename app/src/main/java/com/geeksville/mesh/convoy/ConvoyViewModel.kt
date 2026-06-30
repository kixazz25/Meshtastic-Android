package com.geeksville.mesh.convoy

import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.meshtastic.core.model.MyNodeInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.meshtastic.core.domain.usecase.settings.ExportProfileUseCase
import org.meshtastic.core.domain.usecase.settings.ImportProfileUseCase
import org.meshtastic.core.domain.usecase.settings.InstallProfileUseCase
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.proto.DeviceProfile
import javax.inject.Inject

/**
 * HUD display modes — IMP-001 Task 4.1
 */
enum class HudMode {
    GROUP,      // UNITS · ACTIVE · LOST · SPAN · LEAD · TAIL
    MY_CART,    // Speed · Heading · Battery · Altitude · Proximity
    NODE,       // All 22 properties for tapped marker
    COLLAPSED   // Pill: node count + LOST count. Map maximized.
}

/**
 * RideState — three operating modes controlling map display, survey, and track donation.
 */
enum class RideState {
    SOLO,
    CONVOY,
    ORGANIZED
}

@HiltViewModel
class ConvoyViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val nodeRepository: NodeRepository,
    private val settingsRepository: ConvoySettingsRepository,
    private val radioConfigRepository: RadioConfigRepository,
    private val importProfileUseCase: ImportProfileUseCase,
    private val exportProfileUseCase: ExportProfileUseCase,
    private val installProfileUseCase: InstallProfileUseCase,
) : ViewModel() {

    private val _convoyState = MutableStateFlow(ConvoyEngine.ConvoyState.empty())
    val convoyState: StateFlow<ConvoyEngine.ConvoyState> = _convoyState.asStateFlow()
    private val _rideState = MutableStateFlow(RideState.SOLO)
    val rideState: StateFlow<RideState> = _rideState.asStateFlow()
    private val _activeRide = MutableStateFlow<ConvoyEventConfig?>(null)
    val activeRide: StateFlow<ConvoyEventConfig?> = _activeRide.asStateFlow()
    private val _myNodeInfo = MutableStateFlow<MyNodeInfo?>(null)
    val myNodeInfo: StateFlow<MyNodeInfo?> = _myNodeInfo.asStateFlow()
    val ourNodeInfo: StateFlow<org.meshtastic.core.model.Node?> = nodeRepository.ourNodeInfo

    private val _hudMode = MutableStateFlow(HudMode.GROUP)
    val hudMode: StateFlow<HudMode> = _hudMode.asStateFlow()
    private val _pendingImportBanner = MutableStateFlow<String?>(null)
    val pendingImportBanner: StateFlow<String?> = _pendingImportBanner.asStateFlow()
    fun clearImportBanner() { _pendingImportBanner.value = null }
    private val _selectedNode = MutableStateFlow<ConvoyNode?>(null)
    val selectedNode: StateFlow<ConvoyNode?> = _selectedNode.asStateFlow()
    private val _offTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val offTrackIds: StateFlow<Set<String>> = _offTrackIds.asStateFlow()
    private val _trackActive = MutableStateFlow(false)
    val trackActive: StateFlow<Boolean> = _trackActive.asStateFlow()

    // ── Lead lock state ──────────────────────────────────────────────────
    private var _leadLockedFlag: Boolean = false
    // Per-node distance accumulators — key = nodeId, value = miles traveled
    private var nodeDistanceAccum: MutableMap<String, Float> = mutableMapOf()
    private var nodeLastLat: MutableMap<String, Double> = mutableMapOf()
    private var nodeLastLon: MutableMap<String, Double> = mutableMapOf()
    // 60-second fixed window speed computation per node
    private val nodeSpeedWindowStart: MutableMap<String, Triple<Long, Double, Double>> = mutableMapOf()
    private val nodeLastComputedSpeed: MutableMap<String, Float> = mutableMapOf()
    // The locked lead node ID — set by user via lead selection dialog before ride start
    private var lockedLeadNodeId: String? = null
    private val _leadLocked = MutableStateFlow(false)
    val leadLocked: StateFlow<Boolean> = _leadLocked.asStateFlow()

    private val _trackLeadOnly = MutableStateFlow(true)
    val trackLeadOnly: StateFlow<Boolean> = _trackLeadOnly.asStateFlow()

    private val _isOfflineMode = MutableStateFlow(false)
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()
    fun setOfflineMode(offline: Boolean) { _isOfflineMode.value = offline }
    private val _mapTypeLabel = MutableStateFlow("SAT")
    val mapTypeLabel: StateFlow<String> = _mapTypeLabel.asStateFlow()
    fun setMapTypeLabel(label: String) { _mapTypeLabel.value = label }
    private val _isLocalTiles = MutableStateFlow(false)
    val isLocalTiles: StateFlow<Boolean> = _isLocalTiles.asStateFlow()
    fun setLocalTiles(local: Boolean) { _isLocalTiles.value = local }
    private val _autoPan = MutableStateFlow(false)
    val autoPan: StateFlow<Boolean> = _autoPan.asStateFlow()
    fun setAutoPan(pan: Boolean) { _autoPan.value = pan }

    // Active phone GPS for standalone (no radio) users
    @Volatile private var livePhoneLocation: android.location.Location? = null
    private var phoneLocationListener: android.location.LocationListener? = null

    private fun startPhoneGps() {
        if (phoneLocationListener != null) return
        try {
            val lm = appContext.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return
            phoneLocationListener = android.location.LocationListener { loc ->
                livePhoneLocation = loc
            }
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                2000L,  // 2 second interval
                1f,     // 1 meter minimum distance
                phoneLocationListener!!,
                android.os.Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e("ConvoyVM", "Phone GPS permission denied: ${e.message}")
        }
    }

    private fun stopPhoneGps() {
        phoneLocationListener?.let { listener ->
            try {
                val lm = appContext.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
                lm?.removeUpdates(listener)
            } catch (_: Exception) {}
        }
        phoneLocationListener = null
    }

    fun startGroupTrack() {
        _routeTrailSegments.value = emptyList()
        leadActualLat = null
        leadActualLon = null
        leadStaleTicks = 0
        proxySnapped = false
        _leadTrackSegments.value = emptyList()
        _gpsTrailSegments.value = emptyList()
        rideStartTimeMs = System.currentTimeMillis()
        lastKnownPosition.clear()
        lastNodePositions.clear()
        lastLeadLat = null
        lastLeadLon = null
        nodeSpeedWindowStart.clear()
        nodeLastComputedSpeed.clear()

        // IDENTITY: Set _myCartId ONCE — my radio node from Meshtastic, or !phone if no radio
        val myNum = _myNodeInfo.value?.myNodeNum
        _myCartId.value = if (myNum != null) "!%08x".format(myNum) else "!phone"
        val nodes = readLiveNodes(System.currentTimeMillis())

        // LEAD: Assign if not already set by ConvoyScreen dialog
        if (lockedLeadNodeId == null) {
            when {
                nodes.size == 1 -> setLeadCart(nodes[0].nodeId)
                nodes.isEmpty() -> setLeadCart(_myCartId.value)
                else -> setLeadCart(_myCartId.value)
            }
        }

        // TRACK: Activate AFTER identity and lead are locked
        _trackActive.value = true
        _autoPan.value = true   // autoPan ON during recording
        convoyLog("TRACK START: myCart=${_myCartId.value} lead=$lockedLeadNodeId nodes=${nodes.size}")
    }

    fun stopGroupTrack() {
        _trackActive.value = false
        _autoPan.value = false   // autoPan OFF when recording stops
        _leadLockedFlag = false
        lockedLeadNodeId = null
        nodeDistanceAccum.clear()
        nodeLastLat.clear()
        nodeLastLon.clear()
        _leadLocked.value = false
        lastKnownPosition.clear()
        lastNodePositions.clear()
        lastLeadLat = null
        lastLeadLon = null
        nodeSpeedWindowStart.clear()
        nodeLastComputedSpeed.clear()
        rideStartTimeMs = 0L
    }

    fun setLeadCart(nodeId: String?) {
        if (nodeId != null) {
            lockedLeadNodeId = nodeId
            _leadLockedFlag = true
            _leadLocked.value = true
            val name = _convoyState.value.nodes.firstOrNull { it.nodeId == nodeId }?.callsign ?: nodeId
            convoyLog("LEAD SET: $name manually assigned as lead cart")
        } else {
            lockedLeadNodeId = null
            _leadLockedFlag = false
            _leadLocked.value = false
            convoyLog("LEAD CLEARED: no lead assigned")
        }
    }

    private val _radioInactive = MutableStateFlow(false)
    val radioInactive: StateFlow<Boolean> = _radioInactive.asStateFlow()
    private var lastMovementMs: Long = 0L
    private val RADIO_INACTIVE_TIMEOUT_MS = 5 * 60 * 1000L
    fun reconnectRadio() {
        _radioInactive.value = false
        lastMovementMs = System.currentTimeMillis()
    }

    fun toggleLeadOnly() {
        _trackLeadOnly.value = !_trackLeadOnly.value
    }

    var recordingState = androidx.compose.runtime.mutableStateOf(com.geeksville.mesh.convoy.RecordingState.IDLE)
    var pendingTrackName = androidx.compose.runtime.mutableStateOf("")
    var showRecMenu = androidx.compose.runtime.mutableStateOf(false)
    var pendingEnrollmentEmail = androidx.compose.runtime.mutableStateOf("")
    var hasSeenNodes = androidx.compose.runtime.mutableStateOf(false)

    private val _workingConfig = MutableStateFlow<WorkingConfig?>(null)
    val workingConfig: StateFlow<WorkingConfig?> = _workingConfig.asStateFlow()
    fun setWorkingConfig(config: WorkingConfig) { _workingConfig.value = config }

    val deviceProfileFlow = radioConfigRepository.deviceProfileFlow

    suspend fun exportProfileToFile(context: android.content.Context, file: java.io.File): Result<Unit> = runCatching {
        val profile = radioConfigRepository.deviceProfileFlow.first()
        file.parentFile?.mkdirs()
        file.outputStream().use { outputStream ->
            exportProfileUseCase(outputStream, profile).getOrElse { throw it }
        }
        android.util.Log.i("ConvoyProfile", "Exported profile to: ${file.absolutePath}")
    }

    fun importProfileFromFile(file: java.io.File): Result<DeviceProfile> = runCatching {
        file.inputStream().use { inputStream ->
            importProfileUseCase(inputStream).getOrElse { throw it }
        }
    }

    fun installProfileToRadio(destNum: Int, profile: DeviceProfile) {
        val currentUser = nodeRepository.ourNodeInfo.value?.user
        viewModelScope.launch {
            try {
                android.util.Log.i("ConvoyProfile", "Installing profile to radio: ${"!%08x".format(destNum)}")
                installProfileUseCase(destNum, profile, currentUser)
                android.util.Log.i("ConvoyProfile", "Profile install complete — radio will reboot")
            } catch (e: Exception) {
                android.util.Log.e("ConvoyProfile", "Profile install failed: ${e.message}")
            }
        }
    }

    object ConvoyProfilePaths {
        fun masterCfg(context: android.content.Context) =
            java.io.File(context.filesDir, "master.cfg")
        fun archiveCfg(context: android.content.Context, nodeId: String, label: String): java.io.File {
            val dir = java.io.File(context.filesDir, "convoy_backups/$nodeId")
            dir.mkdirs()
            val ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            return java.io.File(dir, "${label}_${ts}.cfg")
        }
        fun rideCfg(context: android.content.Context, rideName: String, rideDate: String): java.io.File {
            val dir = java.io.File(context.filesDir, "convoy_rides")
            dir.mkdirs()
            val safeName = rideName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            return java.io.File(dir, "${safeName}_${rideDate}.cfg")
        }
        fun assetsMasterCfg(context: android.content.Context): java.io.File =
            java.io.File(context.filesDir, "master.cfg")
    }

    fun clearWorkingConfig() { _workingConfig.value = null }

    var persistentWebView: android.webkit.WebView? = null

    private val _simulationMode = MutableStateFlow(false)
    val simulationMode: StateFlow<Boolean> = _simulationMode.asStateFlow()

    private val _myCartId = MutableStateFlow(ConvoySimulation.MY_CART_ID)
    private fun resolveMyCartId(): String {
        val num = nodeRepository.myNodeInfo.value?.myNodeNum
        return if (num != null) "!%08x".format(num) else _myCartId.value
    }
    val myCartId: StateFlow<String> = _myCartId.asStateFlow()

    private val _showLeadTrack = MutableStateFlow(true)
    val showLeadTrack: StateFlow<Boolean> = _showLeadTrack.asStateFlow()

    private val _leadTrackSegments = MutableStateFlow<List<ConvoyEngine.LeadTrackSegment>>(emptyList())
    val leadTrackSegments: StateFlow<List<ConvoyEngine.LeadTrackSegment>> = _leadTrackSegments.asStateFlow()
    private val _gpsTrailSegments = MutableStateFlow<List<ConvoyEngine.LeadTrackSegment>>(emptyList())
    val gpsTrailSegments: StateFlow<List<ConvoyEngine.LeadTrackSegment>> = _gpsTrailSegments.asStateFlow()
    private val _routeTrailSegments = MutableStateFlow<List<ConvoyEngine.LeadTrackSegment>>(emptyList())
    val routeTrailSegments: StateFlow<List<ConvoyEngine.LeadTrackSegment>> = _routeTrailSegments.asStateFlow()
    private var lastLeadLat: Double? = null
    private var lastLeadLon: Double? = null
    private var rideStartTimeMs: Long = 0L  // Unix ms when startGroupTrack() was called
    private var currentLeadNodeId: String? = null
    private var leadActualLat: Double? = null    // Lead's real GPS (never substituted)
    private var leadActualLon: Double? = null
    private var leadStaleTicks: Int = 0          // Ticks without new lead position
    private var proxySnapped: Boolean = false    // True after first proxy snap
    private val lastNodePositions = mutableMapOf<String, Pair<Double, Double>>()

    private val _routeRecording = MutableStateFlow(false)
    val routeRecording: StateFlow<Boolean> = _routeRecording.asStateFlow()
    private val _offTrackNodes = MutableStateFlow<List<ConvoyNode>>(emptyList())
    val offTrackNodes: StateFlow<List<ConvoyNode>> = _offTrackNodes.asStateFlow()
    private val lastKnownPosition = mutableMapOf<String, Pair<Double, Double>>()
    private var tickJob: Job? = null
    private var admissionWindowHours: Int = 1

    init {
        viewModelScope.launch {
            settingsRepository.admissionWindowHours.collect { admissionWindowHours = it }
        }

        viewModelScope.launch {
            nodeRepository.myNodeInfo.collect { info ->
                _myNodeInfo.value = info
                val num = info?.myNodeNum
                // _myCartId no longer set here — set ONCE in startGroupTrack()
                // resolveMyCartId() reads radio info directly for pre-RECORD display
            }
        }
        startTick()
        viewModelScope.launch(Dispatchers.IO) { scanImportDirectory() }
    }

    suspend fun scanImportDirectory() {
        try {
            val importDir = java.io.File(appContext.filesDir, "convoy_import").also { it.mkdirs() }
            // MediaStore.Downloads query removed — was legacy code replaced by ConvoyFileReceiver.
            // The query blocked main thread on devices with large Downloads (tiles/tracks).
            if (!importDir.exists()) return
            val files = importDir.listFiles { f -> f.extension == "convoy" || f.extension == "json" } ?: return
            var importCount = 0
            var lastImportedName = ""
            for (file in files) {
                try {
                    val json = org.json.JSONObject(file.readText())
                    val docType = json.optString("convoyDocType", "convoy_ride")
                    when (docType) {
                        "convoy_ride" -> {
                            val event = ConvoyEventConfig.fromJson(json)
                            ConvoyEventStore.save(appContext, event)
                            lastImportedName = event.eventName
                            importCount++
                        }
                        else -> android.util.Log.w("ConvoyImport", "Unknown convoy doc type: $docType")
                    }
                    file.delete()
                } catch (e: Exception) {
                    android.util.Log.e("ConvoyImport", "Failed to process ${file.name}: ${e.message}")
                    file.delete()
                }
            }
            if (importCount > 0) {
                val msg = if (importCount == 1) "NEW RIDE IMPORTED: $lastImportedName" else "$importCount NEW RIDES IMPORTED"
                _pendingImportBanner.value = msg
            }
        } catch (e: Exception) {
            android.util.Log.e("ConvoyImport", "Import scan failed: ${e.message}")
        }
    }

    fun setHudMode(mode: HudMode) { _hudMode.value = mode }
    fun onMarkerTapped(node: ConvoyNode) { _selectedNode.value = node; _hudMode.value = HudMode.NODE }
    fun dismissNodeHud() { _selectedNode.value = null; _hudMode.value = HudMode.GROUP }

    private val _currentIntervalSecs = MutableStateFlow(5)
    val currentIntervalSecs: StateFlow<Int> = _currentIntervalSecs.asStateFlow()

    fun setGpsInterval(secs: Int, channelViewModel: com.geeksville.mesh.ui.sharing.ChannelViewModel) {
        _currentIntervalSecs.value = secs
        viewModelScope.launch {
            try {
                channelViewModel.setConfig(
                    org.meshtastic.proto.Config(
                        position = org.meshtastic.proto.Config.PositionConfig(
                            gps_update_interval = secs,
                            gps_mode = org.meshtastic.proto.Config.PositionConfig.GpsMode.ENABLED
                        )
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("ConvoyGPS", "Failed to set GPS interval: ${e.message}")
            }
        }
    }

    fun removeNode(nodeId: String) {
        _selectedNode.value = null
        _hudMode.value = HudMode.GROUP
        val current = _convoyState.value
        _convoyState.value = current.copy(nodes = current.nodes.filter { it.nodeId != nodeId })
    }

    fun setSimulationMode(enabled: Boolean) {
        _simulationMode.value = enabled
        if (enabled) ConvoySimulation.start()
    }

    fun setMyCartId(nodeId: String) { _myCartId.value = nodeId }
    fun toggleLeadTrack() { _showLeadTrack.value = !_showLeadTrack.value }
    fun setShowLeadTrack(visible: Boolean) { _showLeadTrack.value = visible }

    private var gpsServiceConn: android.content.ServiceConnection? = null
    private val _distanceMiles = MutableStateFlow(0.0)
    val distanceMiles: StateFlow<Double> = _distanceMiles.asStateFlow()
    private val _avgChannelUtil = MutableStateFlow(0f)
    val avgChannelUtil: StateFlow<Float> = _avgChannelUtil.asStateFlow()
    private var gpsService: ConvoyGpsService? = null
    private var pendingTempFile: java.io.File? = null
    private var lastGpsLat: Double? = null
    private var lastGpsLon: Double? = null

    private fun bindGpsService(context: android.content.Context, onBound: (ConvoyGpsService) -> Unit) {
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
                val svc = (service as ConvoyGpsService.LocalBinder).getService()
                gpsService = svc
                svc.onSleepTriggered = { recordingState.value = RecordingState.SLEEPING }
                svc.onLocationUpdate = { lat, lon, _ ->
                    val prevLat = lastGpsLat
                    val prevLon = lastGpsLon
                    if (prevLat != null && prevLon != null) {
                        val seg = ConvoyEngine.LeadTrackSegment(
                            startLat = prevLat, startLon = prevLon,
                            endLat = lat, endLon = lon, color = "#2E75B6"
                        )
                        _gpsTrailSegments.value = _gpsTrailSegments.value + seg
                    }
                    lastGpsLat = lat
                    lastGpsLon = lon
                    _distanceMiles.value = svc.totalDistanceMiles
                }
                onBound(svc)
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) { gpsService = null }
        }
        gpsServiceConn = conn
        val intent = android.content.Intent(context, ConvoyGpsService::class.java)
        context.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE)
    }

    fun startRecording(context: android.content.Context) {
        ConvoyGpsService.start(context)
        bindGpsService(context) { svc -> svc.startTrack(); _routeRecording.value = true }
    }
    fun pauseRecording() { gpsService?.pauseTrack(); _routeRecording.value = false }
    fun wakeFromSleep(context: android.content.Context) {
        gpsService?.wakeSleep()
        resumeRecording(context)
    }
    fun resumeRecording(context: android.content.Context) {
        if (gpsService == null) {
            bindGpsService(context) { svc -> svc.resumeTrack(); _routeRecording.value = true }
        } else { gpsService?.resumeTrack(); _routeRecording.value = true }
    }
    fun stopRecording() {
        pendingTempFile = gpsService?.stopTrack()
    }

    fun deleteTempTrack() {
        val temp = pendingTempFile ?: return
        if (temp.exists()) {
            temp.delete()
            android.util.Log.d("ConvoyVM", "Deleted temp track: ${temp.name}")
        }
        pendingTempFile = null
        lastGpsLat = null; lastGpsLon = null; _distanceMiles.value = 0.0; _routeRecording.value = false
    }
    fun finalizeTrack(name: String, context: android.content.Context) {
        val temp = pendingTempFile ?: return
        // Finalize the temp file to a clean {name}.gpx (GpsService no longer timestamps).
        val finalized = gpsService?.finalizeTrack(temp, name)
        pendingTempFile = null
        gpsServiceConn?.let { context.unbindService(it) }
        gpsServiceConn = null; gpsService = null
        // CREATE: inline insert into spatial DB + normalize file to <hash>.gpx.
        // Self-contained (does NOT call sync). Shares only insertTrackToDb.
        try {
            val f = finalized ?: return
            var text = f.readText()
            // Inject the user's typed name into the GPX <trk><name> (recorder hardcodes "Convoy Track").
            val esc = name.trim()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            text = text.replace("<trk><name>Convoy Track</name>", "<trk><name>$esc</name>")
            f.writeText(text)
            val coords = com.geeksville.mesh.convoy.SpatialDbManager.parseGpxTrackPoints(text)
            if (coords.isEmpty()) { android.util.Log.w("ConvoyVM", "finalizeTrack: no geometry, not a track: ${f.name}"); return }
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            for ((lon, lat) in coords) {
                if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
            }
            // UNIFIED ADD: the resolver rereads `f`, inserts, and resolves
            // INSERT / DROP_NAME / DROP_ALIAS / ALIAS -- it owns the rename to
            // <hash>.gpx, the source-file delete, the metric feed, and aliasing.
            val outcome = com.geeksville.mesh.convoy.SpatialDbManager.resolveTrackAdd(name.trim(), f)
            android.util.Log.i("ConvoyVM", "finalizeTrack resolveTrackAdd: $outcome name='${name.trim()}'")
        } catch (e: Exception) {
            android.util.Log.e("ConvoyVM", "finalizeTrack insert failed: ${e.message}")
        }
    }

    // ── File logger ───────────────────────────────────────────────────────
    private val convoyLogFile: java.io.File by lazy {
        java.io.File(appContext.filesDir, "convoy_debug.log").also {
            it.writeText("=== GroupTrack Debug Log ${System.currentTimeMillis()} ===\n")
        }
    }
    private fun convoyLog(msg: String) {
        try { convoyLogFile.appendText("${System.currentTimeMillis() % 100000} $msg\n") }
        catch (e: Exception) { /* ignore */ }
    }

    // ── Tick loop ─────────────────────────────────────────────────────────

    private fun startTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) { tick(); delay(ConvoySimulation.TICK_MS) }
        }
    }

    private fun pointToSegmentDistanceMiles(
        pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double
    ): Float {
        val dx = bLon - aLon; val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) {
            val dlat = pLat - aLat; val dlon = pLon - aLon
            return (Math.sqrt(dlat * dlat + dlon * dlon) * 69.0).toFloat()
        }
        val t = ((pLon - aLon) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)
        val nearLat = aLat + clampedT * dy; val nearLon = aLon + clampedT * dx
        val dlat = pLat - nearLat; val dlon = pLon - nearLon
        return (Math.sqrt(dlat * dlat + dlon * dlon) * 69.0).toFloat()
    }

    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 3958.8
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon/2) * Math.sin(dLon/2)
        return (R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))).toFloat()
    }

    private fun tick() { try {
        // Guard: skip tick until location permission is granted
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }
        val nowMs = System.currentTimeMillis()
        val nodes: List<ConvoyNode> = if (_simulationMode.value) {
            ConvoySimulation.tick(nowMs)
        } else {
            readLiveNodes(nowMs)
        }

        // V2.4: Lead assigned manually via dialog before ride start -- no auto-lock
        // Lead assignment REMOVED from tick — only through setLeadCart()
        // Lead assigned at RECORD time in startGroupTrack() or via cart HUD
        val tailNodeId: String? = null

        val state = ConvoyEngine.compute(
            nodes = nodes,
            myCartId = resolveMyCartId(),
            nowMs = nowMs,
            leadLocked = _leadLockedFlag,
            lockedLeadNodeId = lockedLeadNodeId,
            tailNodeId = tailNodeId
        )
        _convoyState.value = state

        // ── Feed radio position to GPS service if no hardware GPS ────────────
        if (gpsService?.useRadioGps == true && _routeRecording.value) {
            val myCart = state.nodes.firstOrNull { it.isMyCart }
            if (myCart != null && myCart.latitude != 0.0 && myCart.longitude != 0.0) {
                gpsService?.onRadioPosition(myCart.latitude, myCart.longitude, myCart.altitude_m.toDouble())
            }
        }
        // ── Debug log every tick ──────────────────────────────────────────────
        if (_trackActive.value) {
            val leadOut = state.lead?.callsign ?: "NONE"
            val trackFrom = currentLeadNodeId ?: "NONE"
            val accumStr = nodeDistanceAccum.entries.joinToString(" ") { (id, d) ->
                val name = nodes.firstOrNull { it.nodeId == id }?.callsign ?: id.takeLast(4)
                "$name=${String.format("%.3f", d)}mi"
            }
            convoyLog("tick | lead=$leadOut | trackFrom=$trackFrom | locked=$_leadLockedFlag | [$accumStr]")
        }

        // Accumulate route trail — lead only or all carts

        // ── Resolve cart positions: tail to lead ──────────────────────────
        // For each cart: if reporting, use actual position. If silent, use
        // whichever is further along: own last known OR furthest trailing cart.
        // Process tail→lead so each resolution feeds the next cart forward.
        val resolvedPositions = mutableMapOf<String, Pair<Double, Double>>()
        val headingRad = Math.toRadians(state.convoyHeading.toDouble())
        val hdx = kotlin.math.sin(headingRad)
        val hdy = kotlin.math.cos(headingRad)
        var furthestProjection = Double.MIN_VALUE
        var furthestLat = 0.0
        var furthestLon = 0.0

        // Sort by convoyPosition descending = tail first
        val tailToLead = state.nodes.sortedByDescending { it.convoyPosition }
        for (node in tailToLead) {
            val isReporting = node.latitude != 0.0 && node.longitude != 0.0
                    && node.status != ConvoyStatus.LOST
            val ownLat = if (isReporting) node.latitude
                         else (lastNodePositions[node.nodeId]?.first ?: 0.0)
            val ownLon = if (isReporting) node.longitude
                         else (lastNodePositions[node.nodeId]?.second ?: 0.0)
            val ownProj = ownLat * hdy + ownLon * hdx

            // Use whichever is further along: own position or furthest trailing cart
            // OFF-TRACK carts keep their own last known position — never substitute
            val isOffTrack = _offTrackIds.value.contains(node.nodeId)
            val resolvedLat: Double
            val resolvedLon: Double
            if (isOffTrack) {
                // Off-track cart: keep own position so operator can find it
                resolvedLat = ownLat
                resolvedLon = ownLon
            } else if (ownLat != 0.0 && ownProj >= furthestProjection) {
                resolvedLat = ownLat
                resolvedLon = ownLon
            } else if (furthestLat != 0.0 && furthestProjection > ownProj) {
                resolvedLat = furthestLat
                resolvedLon = furthestLon
                if (_trackActive.value) {
                    convoyLog("CART SUB: ${node.callsign} silent — using trailing cart pos (${String.format("%.5f",resolvedLat)},${String.format("%.5f",resolvedLon)})")
                }
            } else {
                resolvedLat = ownLat
                resolvedLon = ownLon
            }

            if (resolvedLat != 0.0 && resolvedLon != 0.0) {
                resolvedPositions[node.nodeId] = Pair(resolvedLat, resolvedLon)
                lastNodePositions[node.nodeId] = Pair(resolvedLat, resolvedLon)
                val resolvedProj = resolvedLat * hdy + resolvedLon * hdx
                if (resolvedProj > furthestProjection) {
                    furthestProjection = resolvedProj
                    furthestLat = resolvedLat
                    furthestLon = resolvedLon
                }
            }
        }

        android.util.Log.w("TRACK-DBG", "TICK: leadOnly=${_trackLeadOnly.value} leadId=$lockedLeadNodeId segs=${_routeTrailSegments.value.size} nodes=${state.nodes.size}")
        // ── Build trail segments — lead actual GPS + proxy snap ──────────
        if (_trackLeadOnly.value) {
            // Get lead's ACTUAL position from node (never use resolved/substituted)
            val leadNode = state.nodes.firstOrNull { it.nodeId == lockedLeadNodeId }
            val leadIsReporting = leadNode != null && leadNode.latitude != 0.0
                && leadNode.longitude != 0.0 && leadNode.status != ConvoyStatus.LOST
            val leadHasNewPos = leadIsReporting
                && (leadNode!!.latitude != leadActualLat || leadNode.longitude != leadActualLon)

            // Handle lead node change
            if (lockedLeadNodeId != currentLeadNodeId) {
                currentLeadNodeId = lockedLeadNodeId
                lastLeadLat = null
                lastLeadLon = null
                leadStaleTicks = 0
                proxySnapped = false
                leadActualLat = null
                leadActualLon = null
            }

            val trackLat: Double?
            val trackLon: Double?

            if (leadHasNewPos) {
                // ── Lead reported new actual position — always use it ──
                leadStaleTicks = 0
                proxySnapped = false
                leadActualLat = leadNode!!.latitude
                leadActualLon = leadNode.longitude
                trackLat = leadNode.latitude
                trackLon = leadNode.longitude
            } else {
                // ── Lead position unchanged — check for proxy ──
                leadStaleTicks++
                if (leadStaleTicks > 1 && leadActualLat != null && leadActualLon != null) {
                    // Lead missed 1+ cycles — find cart that reached lead's actual position
                    // Uses haversine distance (actual GPS), not projection
                    var proxyLat: Double? = null
                    var proxyLon: Double? = null
                    var closestDist = Double.MAX_VALUE
                    for (n in state.nodes) {
                        if (n.nodeId == lockedLeadNodeId) continue
                        if (n.latitude == 0.0 || n.longitude == 0.0) continue
                        if (n.status == ConvoyStatus.LOST) continue
                        if (_offTrackIds.value.contains(n.nodeId)) continue
                        // Haversine from cart to lead's ACTUAL last reported position
                        val dist = ConvoyEngine.haversineMiles(
                            leadActualLat!!, leadActualLon!!,
                            n.latitude, n.longitude)
                        // Cart has reached lead's position (within ~250 feet)
                        if (dist < 0.05 && dist < closestDist) {
                            closestDist = dist.toDouble()
                            proxyLat = n.latitude
                            proxyLon = n.longitude
                        }
                    }
                    if (proxyLat != null && proxyLon != null) {
                        if (!proxySnapped) {
                            // SNAP: first proxy segment connects from lead's last actual pos
                            // lastLeadLat/Lon is already lead's actual — segment starts there
                            proxySnapped = true
                            if (_trackActive.value) {
                                convoyLog("PROXY SNAP: lead stale $leadStaleTicks ticks")
                            }
                        }
                        trackLat = proxyLat
                        trackLon = proxyLon
                    } else {
                        // No cart has reached lead's position yet — wait
                        trackLat = null
                        trackLon = null
                    }
                } else {
                    // Within grace period or no lead position recorded yet
                    trackLat = null
                    trackLon = null
                }
            }

            // Build segment if we have a valid track position
            if (trackLat != null && trackLon != null) {
                val prevLat = lastLeadLat
                val prevLon = lastLeadLon
                if (prevLat != null && prevLon != null &&
                    (prevLat != trackLat || prevLon != trackLon)) {
                    val segDist = ConvoyEngine.haversineMiles(prevLat, prevLon, trackLat, trackLon)
                    if (segDist > 0.25f) {
                        convoyLog("LEAD SEGMENT: dist=${String.format("%.3f",segDist)}mi${if (proxySnapped) " (PROXY)" else ""}")
                    }
                    val seg = ConvoyEngine.LeadTrackSegment(
                        startLat = prevLat, startLon = prevLon,
                        endLat = trackLat, endLon = trackLon,
                        color = "#000000",
                        nodeId = lockedLeadNodeId ?: ""
                    )
                    if (_trackActive.value) {
                        android.util.Log.w("TRACK-DBG", "SEG-WRITE: node=${seg.nodeId} from=${String.format("%.5f,%.5f",seg.startLat,seg.startLon)} to=${String.format("%.5f,%.5f",seg.endLat,seg.endLon)} proxy=$proxySnapped total=${_routeTrailSegments.value.size+1}")
                        _routeTrailSegments.value = _routeTrailSegments.value + seg
                    }
                }
                lastLeadLat = trackLat
                lastLeadLon = trackLon
            }
        }
        // Branch 2 (multi-cart segments) removed

if (_trackActive.value && _routeTrailSegments.value.isNotEmpty()) {
            val threshold = ConvoyConfig.OFF_TRACK_MILES
            val offTrack = state.nodes.filter { node ->
                if (node.latitude == 0.0 && node.longitude == 0.0) return@filter false
                val minDist = _routeTrailSegments.value.minOf { seg ->
                    pointToSegmentDistanceMiles(node.latitude, node.longitude,
                        seg.startLat, seg.startLon, seg.endLat, seg.endLon)
                }
                minDist > threshold
            }.map { it.nodeId }.toSet()
            _offTrackIds.value = offTrack
        } else {
            _offTrackIds.value = emptySet()
        }

        // LEAD ONLY: single black line from locked lead node
        // MULTI TRACK: each node draws its own trail in its own markerColor
        _leadTrackSegments.value = if (_trackLeadOnly.value) {
            _routeTrailSegments.value.map { it.copy(color = "#000000") }
        } else {
            ConvoyEngine.colorSegmentsByNode(
                segments = _routeTrailSegments.value,
                nodes = state.nodes
            )
        }

        if (_hudMode.value == HudMode.NODE && _selectedNode.value != null) {
            val refreshed = state.nodes.firstOrNull { it.nodeId == _selectedNode.value?.nodeId }
            _selectedNode.value = refreshed
        }
    } catch (e: Exception) { /* suppress tick errors */ } }

    // Phone GPS helper -- used when radio has no GPS fix or no radio connected
    private fun getPhoneLocation(): android.location.Location? {
        // Prefer live GPS from ConvoyGpsService (fresh fixes via requestLocationUpdates)
        val svcLat = gpsService?.lastLat
        val svcLon = gpsService?.lastLon
        if (svcLat != null && svcLon != null && svcLat != 0.0 && svcLon != 0.0) {
            val loc = android.location.Location("gps")
            loc.latitude = svcLat
            loc.longitude = svcLon
            return loc
        }
        // Active phone GPS listener (standalone mode)
        val live = livePhoneLocation
        if (live != null && live.latitude != 0.0 && live.longitude != 0.0) {
            return live
        }
        // Fallback: system GPS cache (may be stale)
        return try {
            val lm = appContext.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
            lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) { null }
    }

    private fun readLiveNodes(nowMs: Long): List<ConvoyNode> {
        val nodeMap = try { nodeRepository.nodeDBbyNum.value } catch (e: Exception) { emptyMap() }
        // No radio — device IS a node. Phone GPS only after permission granted.
        if (nodeMap.isEmpty()) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startPhoneGps()
            }
            // _myCartId no longer set here — set ONCE in startGroupTrack()
            val loc = getPhoneLocation()
            val lat = loc?.latitude ?: 0.0
            val lon = loc?.longitude ?: 0.0
            val alt = ((loc?.altitude ?: 0.0) * 3.28084).toInt()
            val spd = (loc?.speed ?: 0f) * 2.23694f
            val hdg = loc?.bearing ?: 0f
            return listOf(ConvoyNode(
                nodeId = "!phone",
                callsign = android.os.Build.MODEL,
                latitude = lat,
                longitude = lon,
                altitude_m = alt,
                speed_mph = spd,
                heading_deg = hdg,
                battery_pct = 100,
                lastSeenMs = nowMs,
                status = ConvoyStatus.ACTIVE
            ))
        }
        val allNodes = nodeMap.values.mapNotNull { node ->
            val user = node.user
            val pos = node.position
            val callsign = user.long_name.ifBlank { user.short_name }.ifBlank { "!${node.num}" }
            val nodeId = "!%08x".format(node.num)
            val hasPos = (pos.latitude_i != 0 || pos.longitude_i != 0)
            val latLon = if (hasPos) {
                val lat = (pos.latitude_i ?: 0) * 1e-7
                val lon = (pos.longitude_i ?: 0) * 1e-7
                // Reject stale rebroadcast packets: pos.time is the GPS fix timestamp
                // (Unix seconds). If it predates ride start it is a relay-rebroadcast
                // of a packet the node sent from a prior location — use lastKnownPosition.
                val fixTimeMs = (pos.time ?: 0).toLong() * 1000L
                val isStale = rideStartTimeMs > 0L && fixTimeMs > 0L && fixTimeMs < rideStartTimeMs
                if (isStale) {
                    convoyLog("STALE POS REJECTED: node=$callsign fixTime=$fixTimeMs rideStart=$rideStartTimeMs age=${(rideStartTimeMs - fixTimeMs)/1000}s lat=$lat lon=$lon → using lastKnown")
                    lastKnownPosition[nodeId] ?: return@mapNotNull null
                } else {
                    lastKnownPosition[nodeId] = Pair(lat, lon)
                    if (_trackActive.value) {
                        convoyLog("POS ACCEPTED: node=$callsign fixTime=$fixTimeMs lat=$lat lon=$lon")
                    }
                    Pair(lat, lon)
                }
            } else {
                val fallback = lastKnownPosition[nodeId]
                if (fallback != null) {
                    convoyLog("ZERO POS: node=$callsign — using lastKnown lat=${fallback.first} lon=${fallback.second}")
                    fallback
                } else {
                    // V2.4: No radio GPS and no lastKnown -- fall back to phone GPS
                    val phoneLoc = getPhoneLocation()
                    if (phoneLoc != null && phoneLoc.latitude != 0.0 && phoneLoc.longitude != 0.0) {
                        convoyLog("ZERO POS: node=$callsign — using phone GPS lat=${phoneLoc.latitude} lon=${phoneLoc.longitude}")
                        Pair(phoneLoc.latitude, phoneLoc.longitude)
                    } else {
                        convoyLog("ZERO POS: node=$callsign — no lastKnown, no phone GPS, dropping node this tick")
                        return@mapNotNull null
                    }
                }
            }
            val lastSeenMs = node.lastHeard.toLong() * 1000L
            ConvoyNode(
                nodeId = nodeId,
                callsign = callsign,
                latitude = latLon.first,
                longitude = latLon.second,
                altitude_m = ((pos.altitude ?: 0) * 3.28084f).toInt(),
                speed_mph = run {
                    // 60-second fixed window: distance from window start to now * 60 = mph
                    val nowMs = System.currentTimeMillis()
                    val windowStart = nodeSpeedWindowStart[nodeId]
                    if (windowStart == null) {
                        // Open first window
                        nodeSpeedWindowStart[nodeId] = Triple(nowMs, latLon.first, latLon.second)
                        0f
                    } else {
                        val (startMs, startLat, startLon) = windowStart
                        val elapsedMs = nowMs - startMs
                        if (elapsedMs >= 60_000L) {
                            // Window complete — compute speed and reset
                            val distMiles = ConvoyEngine.haversineMiles(startLat, startLon, latLon.first, latLon.second)
                            val speed = distMiles * 60f  // miles per minute * 60 = mph
                            nodeLastComputedSpeed[nodeId] = speed
                            nodeSpeedWindowStart[nodeId] = Triple(nowMs, latLon.first, latLon.second)
                            speed
                        } else {
                            // Hold last computed speed until window completes
                            nodeLastComputedSpeed[nodeId] ?: 0f
                        }
                    }
                },
                heading_deg = (((pos.ground_track ?: 0).toFloat() / 100000f) % 360f + 360f) % 360f,
                battery_pct = node.deviceMetrics.battery_level ?: 0,
                snr_db = node.snr,
                lastSeenMs = lastSeenMs,
                cotType = "a-f-G-U-C",
                timestampUtc = java.time.Instant.ofEpochMilli(lastSeenMs).toString()
            )
        }
        val avgUtil = if (allNodes.isEmpty()) 0f else
            allNodes.mapNotNull { node ->
                try {
                    val nodeRaw = nodeRepository.nodeDBbyNum.value[node.nodeId.removePrefix("!").toLong(16).toInt()]
                    nodeRaw?.deviceMetrics?.channel_utilization
                } catch (e: Exception) { null }
            }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        _avgChannelUtil.value = avgUtil
        val filterInput = allNodes.map { it.nodeId to (it.lastSeenMs / 1000L) }
        val allowedIds = ConvoyNodeFilter.filter(
            nodes = filterInput,
            removedCartIds = emptySet(),
            admissionWindowHours = admissionWindowHours
        ).toSet()
        return allNodes.filter { it.nodeId in allowedIds }
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val downloaded: Int, val total: Int, val failCount: Int) : DownloadState()
        data class Complete(val summary: DownloadSummary) : DownloadState()
        object Cancelled : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    data class PendingDownload(
        val tileCount: Int, val sizeMB: Float, val withinCeiling: Boolean,
        val north: Double, val south: Double, val east: Double, val west: Double,
        val sourceName: String, val sourceUrl: String
    )

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    private val _pendingDownload = MutableStateFlow<PendingDownload?>(null)
    val pendingDownload: StateFlow<PendingDownload?> = _pendingDownload.asStateFlow()
    private var activeEntryId: String? = null
    var downloadStartTime: Long = 0L

    fun setPendingDownload(pending: PendingDownload) { _pendingDownload.value = pending }
    fun clearPendingDownload() { _pendingDownload.value = null }

    fun startDownload(context: android.content.Context, pending: PendingDownload) {
        clearPendingDownload()
        downloadStartTime = System.currentTimeMillis()
        // Enqueue to background download queue instead of inline coroutine
        val entry = DownloadQueueManager.enqueue(
            context, pending.north, pending.south, pending.east, pending.west,
            label = pending.sourceName
        )
        activeEntryId = entry.id
        observeQueueEntry(entry.id)
    }

    /** Observe a queue entry and bridge its progress to the existing DownloadState flow. */
    private fun observeQueueEntry(entryId: String) {
        viewModelScope.launch {
            DownloadQueueManager.queue.collect { entries ->
                val entry = entries.find { it.id == entryId } ?: return@collect
                _downloadState.value = when (entry.status) {
                    QueueStatus.QUEUED -> DownloadState.Downloading(0, entry.totalTiles, 0)
                    QueueStatus.DOWNLOADING -> DownloadState.Downloading(
                        entry.downloadedTiles, entry.totalTiles, entry.failedTiles
                    )
                    QueueStatus.COMPLETE -> {
                        val avgBytes = 15_360L
                        val mb = (entry.downloadedTiles * avgBytes) / (1024f * 1024f)
                        DownloadState.Complete(DownloadSummary(entry.downloadedTiles, entry.failedTiles, mb))
                    }
                    QueueStatus.FAILED -> DownloadState.Error("Download failed after ${entry.retryCount} retries")
                    QueueStatus.CANCELLED -> DownloadState.Cancelled
                    QueueStatus.PAUSED -> DownloadState.Downloading(
                        entry.downloadedTiles, entry.totalTiles, entry.failedTiles
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        activeEntryId?.let { DownloadQueueManager.cancel(it) }
        _downloadState.value = DownloadState.Cancelled
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_000L)
            _downloadState.value = DownloadState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
    }
}
