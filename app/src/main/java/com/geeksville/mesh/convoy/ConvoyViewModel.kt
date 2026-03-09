package com.geeksville.mesh.convoy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.meshtastic.core.repository.NodeRepository
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
    private val nodeRepository: NodeRepository,
    private val settingsRepository: ConvoySettingsRepository
) : ViewModel() {

    // ── Convoy state ──────────────────────────────────────────────────────

    private val _convoyState = MutableStateFlow(ConvoyEngine.ConvoyState.empty())
    val convoyState: StateFlow<ConvoyEngine.ConvoyState> = _convoyState.asStateFlow()

    // ── HUD mode ──────────────────────────────────────────────────────────

    private val _hudMode = MutableStateFlow(HudMode.GROUP)
    val hudMode: StateFlow<HudMode> = _hudMode.asStateFlow()

    /** Node currently shown in NODE detail HUD — set on marker tap */
    private val _selectedNode = MutableStateFlow<ConvoyNode?>(null)
    val selectedNode: StateFlow<ConvoyNode?> = _selectedNode.asStateFlow()

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
                val num = info?.myNodeNum
                if (num != null && !_simulationMode.value) {
                    _myCartId.value = num.toString()
                }
            }
        }
        startTick()
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
            _leadTrackSegments.value = _leadTrackSegments.value + newSeg
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
        // Build lead track segments from sorted node positions
        val sortedNodes = state.nodes
            .filter { it.status != ConvoyStatus.LOST }
            .sortedBy { it.convoyPosition }
        val rawSegments = sortedNodes.zipWithNext { a, b ->
            ConvoyEngine.LeadTrackSegment(
                startLat = a.latitude,
                startLon = a.longitude,
                endLat = b.latitude,
                endLon = b.longitude,
                color = a.markerColor
            )
        }
        _leadTrackSegments.value = if (ConvoyConfig.TRACK_MULTICOLOR) {
            ConvoyEngine.computeLeadTrackColors(
                segments = rawSegments,
                nodes = state.nodes,
                lead = state.lead,
                tail = state.tail,
                headingDeg = state.convoyHeading
            )
        } else {
            rawSegments.map { it.copy(color = "#000000") }
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
