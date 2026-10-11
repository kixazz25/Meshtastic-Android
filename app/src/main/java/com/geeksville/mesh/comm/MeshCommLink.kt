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
    private val radioController: org.meshtastic.core.model.RadioController,   // COMMLINKREQ-2026-10-11
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,   // COMMLINKREQ-2026-10-11
) : CommLink {
    // Held by the owner (ConvoyViewModel, application-long); Eagerly so the first read is the real state.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val link: StateFlow<Link> = serviceRepository.connectionState
        .map { toLink(it) }
        .stateIn(scope, SharingStarted.Eagerly, toLink(serviceRepository.connectionState.value))

    private val _onBoxWifi = MutableStateFlow<String?>(null)
    override val onBoxWifi: StateFlow<String?> = _onBoxWifi.asStateFlow()

    override suspend fun listRadios(): List<RadioEntry> = emptyList()           // P2b
    // COMMLINKREQ-2026-10-11 (2.7b P2.2): the rider's requests. Each returns at once; the link state reports the outcome.
    /** A PAIRED radio (radioId = Meshtastic's address, e.g. "x" + Bluetooth MAC). Pairing a NEW radio stays on the
     *  scanner until the headless radio list (P2b) -- it needs the scanned radio itself. */
    override suspend fun connect(radioId: String): CommResult {
        if (radioId.isBlank() || radioId == "n") return CommResult.Failed("NO_RADIO_ID")
        radioController.setDeviceAddress(radioId)
        return CommResult.Ok
    }
    override suspend fun disconnect(): CommResult {
        radioController.setDeviceAddress("n")   // Meshtastic's "no radio"
        return CommResult.Ok
    }
    /** Removes Android's pairing with a Bluetooth radio. Android has no public "unpair": the hidden
     *  BluetoothDevice.removeBond() is used (moved here from GroupTrack's GrpAwareness.forgetPairing). Android may refuse
     *  -> Failed("FORGET_REFUSED"); GroupTrack then says so and opens Bluetooth settings. Only Bluetooth radios
     *  ("x" + MAC) are Android pairings -> anything else is NotSupported. */
    override suspend fun forget(radioId: String): CommResult {
        if (!radioId.startsWith("x") || radioId.length < 2) return CommResult.NotSupported
        val ok = try {
            val dev = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.getRemoteDevice(radioId.substring(1))
            (dev?.javaClass?.getMethod("removeBond")?.invoke(dev) as? Boolean) == true
        } catch (e: Exception) { false }
        return if (ok) CommResult.Ok else CommResult.Failed("FORGET_REFUSED")
    }
    override suspend fun reconnect(): CommResult = CommResult.NotSupported   // P2.3: keeping the link is the comm side's job (Fred 10-11)

    private fun toLink(s: ConnectionState): Link = when (s) {
        ConnectionState.Connected -> Link.CONNECTED
        ConnectionState.Connecting -> Link.CONNECTING
        else -> Link.DISCONNECTED   // Disconnected, DeviceSleep
    }
}
