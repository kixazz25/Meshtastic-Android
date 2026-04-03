package com.geeksville.mesh.convoy

import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    // The locked lead node ID — set when first node hits 1/4 mile, never changes until RECALC
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
    private val _autoPan = MutableStateFlow(true)
    val autoPan: StateFlow<Boolean> = _autoPan.asStateFlow()
    fun setAutoPan(pan: Boolean) { _autoPan.value = pan }

    fun startGroupTrack() {
        _routeTrailSegments.value = emptyList()
        _leadTrackSegments.value = emptyList()
        _gpsTrailSegments.value = emptyList()
        _trackActive.value = true
    }

    fun stopGroupTrack() {
        _trackActive.value = false
        _leadLockedFlag = false
        lockedLeadNodeId = null
        nodeDistanceAccum.clear()
        nodeLastLat.clear()
        nodeLastLon.clear()
        _leadLocked.value = false
    }

    fun recalcLead() {
        _leadLockedFlag = false
        lockedLeadNodeId = null
        nodeDistanceAccum.clear()
        nodeLastLat.clear()
        nodeLastLon.clear()
        _leadLocked.value = false
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
    private var currentLeadNodeId: String? = null
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
                if (num != null && !_simulationMode.value) {
                    _myCartId.value = "!%08x".format(num)
                }
            }
        }
        startTick()
        viewModelScope.launch { scanImportDirectory() }
    }

    suspend fun scanImportDirectory() {
        try {
            val importDir = java.io.File(appContext.filesDir, "convoy_import").also { it.mkdirs() }
            val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                android.provider.MediaStore.Downloads._ID,
                android.provider.MediaStore.Downloads.DISPLAY_NAME
            )
            val selection = android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ?"
            val selectionArgs = arrayOf("%.convoy")
            appContext.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    try {
                        val destFile = java.io.File(importDir, name)
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        android.util.Log.i("ConvoyImport", "Copied from Downloads via MediaStore: $name")
                    } catch (e: Exception) {
                        android.util.Log.e("ConvoyImport", "Failed to copy $name: ${e.message}")
                    }
                }
            }
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
    fun resumeRecording(context: android.content.Context) {
        if (gpsService == null) {
            bindGpsService(context) { svc -> svc.resumeTrack(); _routeRecording.value = true }
        } else { gpsService?.resumeTrack(); _routeRecording.value = true }
    }
    fun stopRecording() {
        pendingTempFile = gpsService?.stopTrack()
        lastGpsLat = null; lastGpsLon = null; _distanceMiles.value = 0.0; _routeRecording.value = false
    }
    fun finalizeTrack(name: String, context: android.content.Context) {
        val temp = pendingTempFile ?: return
        gpsService?.finalizeTrack(temp, name)
        pendingTempFile = null
        gpsServiceConn?.let { context.unbindService(it) }
        gpsServiceConn = null; gpsService = null
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
        val nowMs = System.currentTimeMillis()
        val nodes: List<ConvoyNode> = if (_simulationMode.value) {
            ConvoySimulation.tick(nowMs)
        } else {
            readLiveNodes(nowMs)
        }

        // ── Per-node distance accumulator — runs before compute() ─────────────
        // Every active node accumulates distance. First to hit 1/4 mile wins lead.
        if (_trackActive.value && !_leadLockedFlag) {
            for (node in nodes) {
                if (node.status == ConvoyStatus.ACTIVE &&
                    node.latitude != 0.0 && node.longitude != 0.0) {
                    val prevLat = nodeLastLat[node.nodeId]
                    val prevLon = nodeLastLon[node.nodeId]
                    if (prevLat != null && prevLon != null) {
                        val delta = ConvoyEngine.haversineMiles(prevLat, prevLon, node.latitude, node.longitude)
                        // Ignore GPS jumps > 0.5 miles (bad fix)
                        if (delta > 0f && delta < 0.5f) {
                            nodeDistanceAccum[node.nodeId] = (nodeDistanceAccum[node.nodeId] ?: 0f) + delta
                        }
                    }
                    nodeLastLat[node.nodeId] = node.latitude
                    nodeLastLon[node.nodeId] = node.longitude
                }
            }
            // First node to hit 1/4 mile wins lead — locked forever until RECALC
            val lockCandidate = nodeDistanceAccum
                .filter { (_, dist) -> dist >= ConvoyConfig.LEAD_LOCK_DISTANCE_MILES }
                .maxByOrNull { (_, dist) -> dist }
            if (lockCandidate != null) {
                lockedLeadNodeId = lockCandidate.key
                _leadLockedFlag = true
                _leadLocked.value = true
                val leadName = nodes.firstOrNull { it.nodeId == lockedLeadNodeId }?.callsign ?: lockedLeadNodeId
                convoyLog("LOCK FIRED: $leadName locked as lead at ${String.format("%.3f", lockCandidate.value)} mi")
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, "$leadName Locked as Lead", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Tail = node with minimum distance accumulator (dynamic every tick)
        val tailNodeId: String? = if (nodeDistanceAccum.size > 1)
            nodeDistanceAccum.minByOrNull { (_, dist) -> dist }?.key
        else null

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
        if (_trackLeadOnly.value) {
            val leadNode = if (_leadLockedFlag) state.lead else null
            if (leadNode != null) {
                if (leadNode.nodeId != currentLeadNodeId) {
                    currentLeadNodeId = leadNode.nodeId
                    lastLeadLat = null
                    lastLeadLon = null
                }
                val prevLat = lastLeadLat
                val prevLon = lastLeadLon
                if (prevLat != null && prevLon != null &&
                    (prevLat != leadNode.latitude || prevLon != leadNode.longitude)) {
                    val seg = ConvoyEngine.LeadTrackSegment(
                        startLat = prevLat, startLon = prevLon,
                        endLat = leadNode.latitude, endLon = leadNode.longitude,
                        color = "#000000",
                        nodeId = leadNode.nodeId
                    )
                    if (_trackActive.value) _routeTrailSegments.value = _routeTrailSegments.value + seg
                }
                lastLeadLat = leadNode.latitude
                lastLeadLon = leadNode.longitude
            }
        } else {
            val newSegs = mutableListOf<ConvoyEngine.LeadTrackSegment>()
            for (node in state.nodes) {
                if (node.latitude == 0.0 && node.longitude == 0.0) continue
                val prev = lastNodePositions[node.nodeId]
                if (prev != null && (prev.first != node.latitude || prev.second != node.longitude)) {
                    newSegs.add(ConvoyEngine.LeadTrackSegment(
                        startLat = prev.first, startLon = prev.second,
                        endLat = node.latitude, endLon = node.longitude,
                        color = "#000000",
                        nodeId = node.nodeId
                    ))
                }
                lastNodePositions[node.nodeId] = Pair(node.latitude, node.longitude)
            }
            if (_trackActive.value && newSegs.isNotEmpty()) {
                _routeTrailSegments.value = _routeTrailSegments.value + newSegs
            }
        }

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

    private fun readLiveNodes(nowMs: Long): List<ConvoyNode> {
        val nodeMap = try { nodeRepository.nodeDBbyNum.value } catch (e: Exception) { return emptyList() }
        val allNodes = nodeMap.values.mapNotNull { node ->
            val user = node.user
            val pos = node.position
            val callsign = user.long_name.ifBlank { user.short_name }.ifBlank { "!${node.num}" }
            val nodeId = "!%08x".format(node.num)
            val hasPos = (pos.latitude_i != 0 || pos.longitude_i != 0)
            val latLon = if (hasPos) {
                val lat = (pos.latitude_i ?: 0) * 1e-7
                val lon = (pos.longitude_i ?: 0) * 1e-7
                lastKnownPosition[nodeId] = Pair(lat, lon)
                Pair(lat, lon)
            } else {
                lastKnownPosition[nodeId] ?: return@mapNotNull null
            }
            val lastSeenMs = node.lastHeard.toLong() * 1000L
            ConvoyNode(
                nodeId = nodeId,
                callsign = callsign,
                latitude = latLon.first,
                longitude = latLon.second,
                altitude_m = pos.altitude ?: 0,
                speed_mph = ((pos.ground_speed ?: 0) * 2.23694f),
                heading_deg = (pos.ground_track ?: 0).toFloat(),
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
    private var downloadJob: kotlinx.coroutines.Job? = null
    var downloadStartTime: Long = 0L

    fun setPendingDownload(pending: PendingDownload) { _pendingDownload.value = pending }
    fun clearPendingDownload() { _pendingDownload.value = null }

    fun startDownload(context: android.content.Context, pending: PendingDownload) {
        clearPendingDownload()
        downloadStartTime = System.currentTimeMillis()
        downloadJob = viewModelScope.launch {
            _downloadState.value = DownloadState.Downloading(0, pending.tileCount, 0)
            val tiles = ConvoyTileCalculator.calculateTiles(pending.north, pending.south, pending.east, pending.west)
            val result = ConvoyTileDownloader.downloadTiles(
                context = context, tiles = tiles,
                sourceUrl = pending.sourceUrl, sourceName = pending.sourceName
            ) { downloaded, total, failCount ->
                _downloadState.value = DownloadState.Downloading(downloaded, total, failCount)
            }
            result.fold(
                onSuccess = { summary ->
                    _downloadState.value = DownloadState.Complete(summary)
                    kotlinx.coroutines.delay(3_000L)
                    _downloadState.value = DownloadState.Idle
                },
                onFailure = { e -> _downloadState.value = DownloadState.Error(e.message ?: "Download failed") }
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Cancelled
        viewModelScope.launch { kotlinx.coroutines.delay(2_000L); _downloadState.value = DownloadState.Idle }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
    }
}
