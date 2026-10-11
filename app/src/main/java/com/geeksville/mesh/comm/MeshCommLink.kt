package com.geeksville.mesh.comm

import com.grouptrack.comm.CommLink
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.Link
import com.grouptrack.comm.RadioEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.ServiceRepository
import javax.inject.Inject

/**
 * COMMLINK-2026-10-11 (2.7b cycle 1 P2.1) -- the MESH implementation of CommLink: the radio link.
 * Lives in the app module (the comm side); GroupTrack only sees the contract (com.grouptrack.comm).
 *
 * link: Meshtastic's own connection state, translated --
 *   Connected -> CONNECTED · Connecting -> CONNECTING · Disconnected / DeviceSleep -> DISCONNECTED.
 * (GroupTrack treated only Connected as connected and only Connecting as "still connecting" before this step.)
 *
 * P2.1 moves the STATE only. The requests (listRadios, connect, disconnect, forget, reconnect) answer NotSupported
 * until P2.2 implements them here; nothing calls them yet (Comm API rule R3: every call may answer NotSupported).
 * onBoxWifi: null = not on a box's Wi-Fi -- there is no Nucleus/IP implementation yet (CODE RULE 1: null documented).
 */
class MeshCommLink @Inject constructor(
    serviceRepository: ServiceRepository,
) : CommLink {
    // Held by the owner (ConvoyViewModel, application-long); Eagerly so the first read is the real state.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val link: StateFlow<Link> = serviceRepository.connectionState
        .map { toLink(it) }
        .stateIn(scope, SharingStarted.Eagerly, toLink(serviceRepository.connectionState.value))

    private val _onBoxWifi = MutableStateFlow<String?>(null)
    override val onBoxWifi: StateFlow<String?> = _onBoxWifi.asStateFlow()

    override suspend fun listRadios(): List<RadioEntry> = emptyList()           // P2b
    override suspend fun connect(radioId: String): CommResult = CommResult.NotSupported   // P2.2
    override suspend fun disconnect(): CommResult = CommResult.NotSupported             // P2.2
    override suspend fun forget(radioId: String): CommResult = CommResult.NotSupported  // P2.2
    override suspend fun reconnect(): CommResult = CommResult.NotSupported              // P2.2

    private fun toLink(s: ConnectionState): Link = when (s) {
        ConnectionState.Connected -> Link.CONNECTED
        ConnectionState.Connecting -> Link.CONNECTING
        else -> Link.DISCONNECTED   // Disconnected, DeviceSleep
    }
}
