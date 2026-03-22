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
 * ConvoyViewModel — IMP-001 Tasks 3.2 + 4.1
 *
 * Drives the convoy tick loop every 5 seconds.
 * In simulation mode: reads from ConvoySimulation.
 * In live mode: reads from NodeRepository.nodeDBbyNum (existing Meshtastic data).
 *
 * NO new BLE connections. NO direct radio access.
 * The existing Meshtastic app handles all radio/BLE work.
 */
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

    // ── Convoy state ──────────────────────────────────────────────────────

    private val _convoyState = MutableStateFlow(ConvoyEngine.ConvoyState.empty())
    val convoyState: StateFlow<ConvoyEngine.ConvoyState> = _convoyState.asStateFlow()

    private val _myNodeInfo = MutableStateFlow<MyNodeInfo?>(null)
    val myNodeInfo: StateFlow<MyNodeInfo?> = _myNodeInfo.asStateFlow()

    // Our node from NodeRepository — exposes user.long_name
    val ourNodeInfo: StateFlow<org.meshtastic.core.model.Node?> = nodeRepository.ourNodeInfo

    // ── HUD mode ──────────────────────────────────────────────────────────

    private val _hudMode = MutableStateFlow(HudMode.GROUP)
    val hudMode: StateFlow<HudMode> = _hudMode.asStateFlow()

    // Pending import banner — set when a new ride is imported from email
    private val _pendingImportBanner = MutableStateFlow<String?>(null)
    val pendingImportBanner: StateFlow<String?> = _pendingImportBanner.asStateFlow()
    fun clearImportBanner() { _pendingImportBanner.value = null }

    /** Node currently shown in NODE detail HUD — set on marker tap */
    private val _selectedNode = MutableStateFlow<ConvoyNode?>(null)
    val selectedNode: StateFlow<ConvoyNode?> = _selectedNode.asStateFlow()

    private val _offTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val offTrackIds: StateFlow<Set<String>> = _offTrackIds.asStateFlow()

    private val _trackActive = MutableStateFlow(false)
    val trackActive: StateFlow<Boolean> = _trackActive.asStateFlow()

    private val _trackLeadOnly = MutableStateFlow(true)
    val trackLeadOnly: StateFlow<Boolean> = _trackLeadOnly.asStateFlow()

    fun startGroupTrack() {
        _routeTrailSegments.value = emptyList()
        _leadTrackSegments.value = emptyList()
        _gpsTrailSegments.value = emptyList()
        _trackActive.value = true
    }

    fun stopGroupTrack() {
        _trackActive.value = false
    }

    fun toggleLeadOnly() {
        _trackLeadOnly.value = !_trackLeadOnly.value
    }

    // ── UI state — persists across navigation ────────────────────────────
    var recordingState = androidx.compose.runtime.mutableStateOf(com.geeksville.mesh.convoy.RecordingState.IDLE)
    var pendingTrackName = androidx.compose.runtime.mutableStateOf("")
    var showRecMenu = androidx.compose.runtime.mutableStateOf(false)
    var pendingEnrollmentEmail = androidx.compose.runtime.mutableStateOf("")
    var hasSeenNodes = androidx.compose.runtime.mutableStateOf(false)

    // ── Persistent WebView ───────────────────────────────────────────────
    // ── Working config for radio write sequence ───────────────────────────
    private val _workingConfig = MutableStateFlow<WorkingConfig?>(null)
    val workingConfig: StateFlow<WorkingConfig?> = _workingConfig.asStateFlow()

    fun setWorkingConfig(config: WorkingConfig) {
        _workingConfig.value = config
    }

    // ── Profile Export / Import / Install ─────────────────────────────────────

    /** Current radio DeviceProfile as a flow — live from radio */
    val deviceProfileFlow = radioConfigRepository.deviceProfileFlow

    /**
     * Export current radio DeviceProfile to a file.
     * Used for: archive before write, master.cfg capture, ride binary creation.
     * File location is hardwired — no user prompt.
     */
    suspend fun exportProfileToFile(context: android.content.Context, file: java.io.File): Result<Unit> = runCatching {
        val profile = radioConfigRepository.deviceProfileFlow.first()
        file.parentFile?.mkdirs()
        file.outputStream().use { outputStream ->
            exportProfileUseCase(outputStream, profile)
                .getOrElse { throw it }
        }
        android.util.Log.i("ConvoyProfile", "Exported profile to: ${file.absolutePath}")
    }

    /**
     * Import a DeviceProfile binary from a file.
     * Returns the decoded DeviceProfile — does NOT write to radio.
     */
    fun importProfileFromFile(file: java.io.File): Result<DeviceProfile> = runCatching {
        file.inputStream().use { inputStream ->
            importProfileUseCase(inputStream).getOrElse { throw it }
        }
    }

    /**
     * Install a DeviceProfile binary to the connected radio.
     * This is the atomic write — all 47 fields, one operation.
     * Caller must handle reboot and reconnect after this completes.
     */
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

    /**
     * Hardwired file paths for convoy profile artifacts.
     * All paths are deterministic — no user interaction required.
     */
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

    fun clearWorkingConfig() {
        _workingConfig.value = null
    }

    var persistentWebView: android.webkit.WebView? = null

    // ── Simulation mode ───────────────────────────────────────────────────

    private val _simulationMode = MutableStateFlow(false)
    val simulationMode: StateFlow<Boolean> = _simulationMode.asStateFlow()

    // ── MY CART config ────────────────────────────────────────────────────

    private val _myCartId = MutableStateFlow(ConvoySimulation.MY_CART_ID)
    val myCartId: StateFlow<String> = _myCartId.asStateFlow()

    // ── Lead track visibility ─────────────────────────────────────────────

    private val _showLeadTrack = MutableStateFlow(true)
    val showLeadTrack: StateFlow<Boolean> = _showLeadTrack.asStateFlow()

    // ── Lead track segments (REQ-109) ───────────────────────────────────────

    private val _leadTrackSegments = MutableStateFlow<List<ConvoyEngine.LeadTrackSegment>>(emptyList())
    val leadTrackSegments: StateFlow<List<ConvoyEngine.LeadTrackSegment>> = _leadTrackSegments.asStateFlow()
    private val _gpsTrailSegments = MutableStateFlow<List<ConvoyEngine.LeadTrackSegment>>(emptyList())
    val gpsTrailSegments: StateFlow<List<ConvoyEngine.LeadTrackSegment>> = _gpsTrailSegments.asStateFlow()
    private val _routeTrailSegments = MutableStateFlow<List<ConvoyEngine.LeadTrackSegment>>(emptyList())
    val routeTrailSegments: StateFlow<List<ConvoyEngine.LeadTrackSegment>> = _routeTrailSegments.asStateFlow()
    private var lastLeadLat: Double? = null
    private var lastLeadLon: Double? = null
    private val lastNodePositions = mutableMapOf<String, Pair<Double, Double>>()

    // ── Route recorder (REQ-111) ──────────────────────────────────────────

    private val _routeRecording = MutableStateFlow(false)
    val routeRecording: StateFlow<Boolean> = _routeRecording.asStateFlow()

    // ── Off-track alert (REQ-NEW-01) ──────────────────────────────────────

    private val _offTrackNodes = MutableStateFlow<List<ConvoyNode>>(emptyList())
    val offTrackNodes: StateFlow<List<ConvoyNode>> = _offTrackNodes.asStateFlow()

    private val lastKnownPosition = mutableMapOf<String, Pair<Double, Double>>()
    private var tickJob: Job? = null
    private var admissionWindowHours: Int = 1

    // ── Init ──────────────────────────────────────────────────────────────

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
                // Scan Downloads via MediaStore (works on Android 13+)
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
                val files = importDir.listFiles { f -> f.extension == "convoy" || f.extension == "json" }
                    ?: return
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
                                android.util.Log.i("ConvoyImport", "Imported ride: ${event.eventName}")
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
                    val msg = if (importCount == 1) "NEW RIDE IMPORTED: $lastImportedName"
                              else "$importCount NEW RIDES IMPORTED"
                    _pendingImportBanner.value = msg
                }
            } catch (e: Exception) {
            android.util.Log.e("ConvoyImport", "Import scan failed: ${e.message}")
        }
    }

    // ── Public API — HUD ─────────────────────────────────────────────────

    fun setHudMode(mode: HudMode) {
        _hudMode.value = mode
    }

    fun onMarkerTapped(node: ConvoyNode) {
        _selectedNode.value = node
        _hudMode.value = HudMode.NODE
    }

    fun dismissNodeHud() {
        _selectedNode.value = null
        _hudMode.value = HudMode.GROUP
    }

    // ── Public API — simulation ───────────────────────────────────────────

    fun setSimulationMode(enabled: Boolean) {
        _simulationMode.value = enabled
        if (enabled) ConvoySimulation.start()
    }

    fun setMyCartId(nodeId: String) {
        _myCartId.value = nodeId
    }

    fun toggleLeadTrack() {
        _showLeadTrack.value = !_showLeadTrack.value
    }

    fun setShowLeadTrack(visible: Boolean) {
        _showLeadTrack.value = visible
    }


    // ── Route recorder ────────────────────────────────────────────────────
    private var kmlWriter: java.io.BufferedWriter? = null
    private var kmlFile: java.io.File? = null
    private var locationManager: android.location.LocationManager? = null
    private var gpsListener: android.location.LocationListener? = null

    fun startRecording(name: String, context: android.content.Context) {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ), "my_tracks"
        )
        if (!dir.exists()) dir.mkdirs()
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val fileName = "${name.replace(" ", "_")}_${sdf.format(java.util.Date())}.kml"
        val file = java.io.File(dir, fileName)
        kmlFile = file
        val writer = java.io.BufferedWriter(java.io.FileWriter(file))
        kmlWriter = writer
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        writer.write("<Document>\n")
        writer.write("<name>${name}</name>\n")
        writer.write("<Placemark><name>Track</name><LineString><coordinates>\n")
        writer.flush()
        _routeRecording.value = true
        startGps(context)
    }

    fun pauseRecording() {
        stopGps()
        _routeRecording.value = false
    }

    fun resumeRecording(context: android.content.Context) {
        _routeRecording.value = true
        startGps(context)
    }

    fun stopRecording() {
        lastGpsLat = null
        lastGpsLon = null
        stopGps()
        try {
            kmlWriter?.write("</coordinates></LineString></Placemark>\n")
            kmlWriter?.write("</Document>\n</kml>\n")
            kmlWriter?.flush()
            kmlWriter?.close()
        } catch (e: Exception) { /* ignore */ }
        kmlWriter = null
        kmlFile = null
        _routeRecording.value = false
    }

    private fun startGps(context: android.content.Context) {
        try {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
                as android.location.LocationManager
            locationManager = lm
            val listener = android.location.LocationListener { loc ->
                writeKmlPoint(loc.latitude, loc.longitude, loc.altitude)
            }
            gpsListener = listener
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                5000L, 0f, listener
            )
        } catch (e: SecurityException) { /* permission not granted */ }
    }

    private fun stopGps() {
        try {
            gpsListener?.let { locationManager?.removeUpdates(it) }
        } catch (e: Exception) { /* ignore */ }
        gpsListener = null
    }

    private var lastGpsLat: Double? = null
    private var lastGpsLon: Double? = null

    private fun writeKmlPoint(lat: Double, lon: Double, alt: Double) {
        try {
            kmlWriter?.write("$lon,$lat,$alt\n")
            kmlWriter?.flush()
        } catch (e: Exception) { /* ignore */ }
        val prevLat = lastGpsLat
        val prevLon = lastGpsLon
        if (prevLat != null && prevLon != null) {
            val newSeg = ConvoyEngine.LeadTrackSegment(
                startLat = prevLat, startLon = prevLon,
                endLat = lat, endLon = lon,
                color = "#2E75B6"
            )
            _gpsTrailSegments.value = _gpsTrailSegments.value + newSeg
        }
        lastGpsLat = lat
        lastGpsLon = lon
    }


    // ── Tick loop ─────────────────────────────────────────────────────────

    private fun startTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                tick()
                delay(ConvoySimulation.TICK_MS)
            }
        }
    }

    private fun pointToSegmentDistanceMiles(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Float {
        val dx = bLon - aLon
        val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) {
            val dlat = pLat - aLat
            val dlon = pLon - aLon
            return (Math.sqrt(dlat * dlat + dlon * dlon) * 69.0).toFloat()
        }
        val t = ((pLon - aLon) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)
        val nearLat = aLat + clampedT * dy
        val nearLon = aLon + clampedT * dx
        val dlat = pLat - nearLat
        val dlon = pLon - nearLon
        return (Math.sqrt(dlat * dlat + dlon * dlon) * 69.0).toFloat()
    }

    private fun tick() { try {
        val nowMs = System.currentTimeMillis()
        val nodes: List<ConvoyNode> = if (_simulationMode.value) {
            ConvoySimulation.tick(nowMs)
        } else {
            readLiveNodes(nowMs)
        }
        val state = ConvoyEngine.compute(
            nodes = nodes,
            myCartId = _myCartId.value,
            nowMs = nowMs
        )
        _convoyState.value = state
        // Accumulate route trail — lead only or all carts
        if (_trackLeadOnly.value) {
            val leadNode = state.lead
            if (leadNode != null) {
                val prevLat = lastLeadLat
                val prevLon = lastLeadLon
                if (prevLat != null && prevLon != null &&
                    (prevLat != leadNode.latitude || prevLon != leadNode.longitude)) {
                    val seg = ConvoyEngine.LeadTrackSegment(
                        startLat = prevLat, startLon = prevLon,
                        endLat = leadNode.latitude, endLon = leadNode.longitude,
                        color = "#000000"
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
                        color = "#000000"
                    ))
                }
                lastNodePositions[node.nodeId] = Pair(node.latitude, node.longitude)
            }
            if (_trackActive.value && newSegs.isNotEmpty()) {
                _routeTrailSegments.value = _routeTrailSegments.value + newSegs
            }
        }
        // Compute off-track nodes — any node > OFF_TRACK_MILES from nearest trail segment
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
        // Color route trail segments by cart positions
        _leadTrackSegments.value = if (ConvoyConfig.TRACK_MULTICOLOR && !_trackLeadOnly.value) {
            ConvoyEngine.computeLeadTrackColors(
                segments = _routeTrailSegments.value,
                nodes = state.nodes,
                lead = state.lead,
                tail = state.tail,
                headingDeg = state.convoyHeading
            )
        } else {
            _routeTrailSegments.value.map { it.copy(color = "#000000") }
        }

        // Refresh selected node if NODE HUD is open
        if (_hudMode.value == HudMode.NODE && _selectedNode.value != null) {
            val refreshed = state.nodes.firstOrNull {
                it.nodeId == _selectedNode.value?.nodeId
            }
            _selectedNode.value = refreshed
        }
    } catch (e: Exception) { /* suppress tick errors */ } }

    // ── Live node reading ─────────────────────────────────────────────────

    /**
     * Read nodes from NodeRepository — the existing Meshtastic NodeDB.
     * Maps Meshtastic Node fields to our ConvoyNode properties.
     * Read-only. Writes nothing to the database.
     */
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
        val filterInput = allNodes.map { it.nodeId to (it.lastSeenMs / 1000L) }
        val allowedIds = ConvoyNodeFilter.filter(
            nodes = filterInput,
            removedCartIds = emptySet(),
            admissionWindowHours = admissionWindowHours
        ).toSet()
        return allNodes.filter { it.nodeId in allowedIds }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
    }
}
