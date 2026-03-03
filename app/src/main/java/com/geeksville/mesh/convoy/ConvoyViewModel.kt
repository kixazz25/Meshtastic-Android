package com.geeksville.mesh.convoy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.meshtastic.core.repository.NodeRepository
import javax.inject.Inject

/**
 * ConvoyViewModel — IMP-001 Task 3.2
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
    private val nodeRepository: NodeRepository
) : ViewModel() {

    // ── State ─────────────────────────────────────────────────────────────

    private val _convoyState = MutableStateFlow(ConvoyEngine.ConvoyState.empty())
    val convoyState: StateFlow<ConvoyEngine.ConvoyState> = _convoyState.asStateFlow()

    private val _simulationMode = MutableStateFlow(false)
    val simulationMode: StateFlow<Boolean> = _simulationMode.asStateFlow()

    private val _myCartId = MutableStateFlow(ConvoySimulation.MY_CART_ID)
    val myCartId: StateFlow<String> = _myCartId.asStateFlow()

    private var tickJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        startTick()
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun setSimulationMode(enabled: Boolean) {
        _simulationMode.value = enabled
        if (enabled) {
            ConvoySimulation.start()
        }
    }

    fun setMyCartId(nodeId: String) {
        _myCartId.value = nodeId
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

    private fun tick() {
        val nowMs = System.currentTimeMillis()
        val nodes: List<ConvoyNode> = if (_simulationMode.value) {
            ConvoySimulation.tick(nowMs)
        } else {
            readLiveNodes(nowMs)
        }
        _convoyState.value = ConvoyEngine.compute(
            nodes = nodes,
            myCartId = _myCartId.value,
            nowMs = nowMs
        )
    }

    // ── Live node reading ─────────────────────────────────────────────────

    /**
     * Read nodes from NodeRepository — the existing Meshtastic NodeDB.
     * Maps Meshtastic Node fields to our ConvoyNode properties.
     * Read-only. Writes nothing to the database.
     */
    private fun readLiveNodes(nowMs: Long): List<ConvoyNode> {
        val nodeMap = nodeRepository.nodeDBbyNum.value
        return nodeMap.values.mapNotNull { node ->
            val user = node.user
            val pos = node.position
            val callsign = user.longName.ifBlank { user.shortName }.ifBlank { "!${node.num}" }

            // Skip nodes with no position data
            if (pos.latitudeI == 0 && pos.longitudeI == 0) return@mapNotNull null

            val lastSeenMs = node.lastHeard.toLong() * 1000L

            ConvoyNode(
                nodeId = "!%08x".format(node.num),
                callsign = callsign,
                latitude = pos.latitudeI * 1e-7,
                longitude = pos.longitudeI * 1e-7,
                altitude_m = pos.altitude,
                speed_mph = (pos.groundSpeed * 2.23694f), // m/s to mph
                heading_deg = pos.groundTrack.toFloat(),
                battery_pct = node.deviceMetrics.batteryLevel,
                snr_db = node.snr,
                lastSeenMs = lastSeenMs,
                cotType = "a-f-G-U-C",
                timestampUtc = java.time.Instant.ofEpochMilli(lastSeenMs).toString()
            )
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
    }
}
