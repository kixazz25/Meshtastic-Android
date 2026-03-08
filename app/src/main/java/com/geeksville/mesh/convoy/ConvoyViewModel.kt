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

    fun toggleRouteRecorder() {
        _routeRecording.value = !_routeRecording.value
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
