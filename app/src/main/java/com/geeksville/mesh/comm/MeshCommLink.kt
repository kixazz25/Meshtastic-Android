package com.geeksville.mesh.comm

import com.grouptrack.comm.CommLink
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.Link
import com.grouptrack.comm.LinkHealth   // COMMHEALTH-2026-10-11
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
@javax.inject.Singleton   // COMMSINGLE-2026-10-11: ONE link for the whole app (its state and, from P2.3b, the keep-alive)
class MeshCommLink @Inject constructor(
    serviceRepository: ServiceRepository,
    private val nodeRepository: org.meshtastic.core.repository.NodeRepository,   // COMMHEALTH-2026-10-11: node-list traffic, my node number
    private val radioInterfaceService: org.meshtastic.core.repository.RadioInterfaceService,   // COMMHEALTH-2026-10-11: the saved radio
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
    /** COMMHEALTH2-2026-10-11: one reconnect attempt now (the keep-alive below runs its own; nothing calls this yet). */
    override suspend fun reconnect(): CommResult {
        val saved = radioInterfaceService.currentDeviceAddressFlow.value
        if (saved.isNullOrBlank() || saved == "n") return CommResult.Failed("NO_RADIO_ID")
        radioController.setDeviceAddress("n"); delay(1_500L); radioController.setDeviceAddress(saved)
        return CommResult.Ok
    }

    // ---------------------------------------------------------------- COMMHEALTH-2026-10-11 (P2.3b-1, SHADOW)
    // The keep-alive, run HERE (the comm side's job, Fred 10-11). COMMHEALTH2-2026-10-11 (P2.3b-2): it ACTS -- GroupTrack's own heartbeat
    // and recovery loop are removed; GroupTrack reads `health`. Numbers copied from LINKFIX / LINKFIX2 / LINKHB unchanged.
    private val _health = MutableStateFlow(LinkHealth(false, false, 0, "NOT_CONNECTED"))
    override val health: StateFlow<LinkHealth> = _health.asStateFlow()

    @Volatile private var hTrafficMs = 0L                  // last change of the radio's node list (= radio traffic)
    private var hWasConnected = false
    private var hConnectedAtMs = 0L                         // grace for the first report on a fresh connection
    private var hConnectedThisSession = false
    private var hAttempts = 0
    private var hLastAttemptMs = 0L
    private var hNotTalkingSinceMs = 0L
    private var hConnectingSinceMs = 0L
    private var hPrevState: Link? = null
    private var hPrevSaved: String? = null
    private var hUnpaired = false                           // COMMHEALTH2-2026-10-11: the ghost check cleared the saved radio

    init {
        scope.launch { nodeRepository.nodeDBbyNum.collect { hTrafficMs = System.currentTimeMillis() } }
        scope.launch {
            while (true) {
                delay(5_000L)
                try { healthStep() } catch (e: Exception) { android.util.Log.w("COMMHEALTH", "step failed: ${e.message}") }
            }
        }
    }

    private fun pairedMacs(): Set<String> = try {
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.map { it.address.uppercase() }?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }

    private suspend fun healthStep() {   // COMMHEALTH2-2026-10-11: suspend -- the reconnect waits 1.5 s
        val now = System.currentTimeMillis()
        val state = link.value
        val connected = state == Link.CONNECTED
        if (connected && !hWasConnected) { hTrafficMs = now; hConnectedAtMs = now; hConnectedThisSession = true }
        hWasConnected = connected
        val myNum = nodeRepository.myNodeInfo.value?.myNodeNum
        val ownTakMs = myNum?.let { org.meshtastic.core.data.manager.TakSeenStore.lastFrom(it) }
        val talking = connected && (if (ownTakMs != null) now - maxOf(ownTakMs, hConnectedAtMs) < 45_000L
                                    else now - hTrafficMs < 120_000L)
        val needsRecovery = !talking && hConnectedThisSession
        val saved = radioInterfaceService.currentDeviceAddressFlow.value

        // ghost sync (Main.kt GRPAWARE-SYNC): the saved Bluetooth radio is no longer paired in Android
        var ghost = false
        if (saved != null && saved.startsWith("x") && saved.length > 1) {
            val paired = pairedMacs()
            ghost = paired.isNotEmpty() && saved.substring(1).uppercase() !in paired
            if (ghost) {   // COMMHEALTH2-2026-10-11: acts (was Main.kt GRPAWARE-SYNC)
                android.util.Log.i("COMMHEALTH", "ghost: saved radio $saved is not paired in Android -> no radio")
                radioController.setDeviceAddress("n")
                hUnpaired = true
            }
        }

        if (!ghost && !saved.isNullOrBlank() && saved != "n") hUnpaired = false   // another radio chosen
        // the recovery loop (was Main.kt LINKFIX / LINKFIX2 / LINKHB) -- COMMHEALTH2-2026-10-11: acts
        var stuck = false
        if (talking) {
            if (hAttempts > 0) android.util.Log.i("COMMHEALTH", "radio talking again after $hAttempts reconnect(s)")
            hAttempts = 0; hNotTalkingSinceMs = 0L; hConnectingSinceMs = 0L
        } else {
            if (state != hPrevState || saved != hPrevSaved) { hNotTalkingSinceMs = now; hPrevState = state; hPrevSaved = saved }
            if (state == Link.CONNECTING) { if (hConnectingSinceMs == 0L) hConnectingSinceMs = now } else hConnectingSinceMs = 0L
            stuck = hConnectingSinceMs != 0L && now - hConnectingSinceMs >= 45_000L
            val waitConnecting = state == Link.CONNECTING && !stuck
            if (!waitConnecting) {
                if (hNotTalkingSinceMs == 0L) hNotTalkingSinceMs = now
                val act = needsRecovery && !saved.isNullOrBlank() && saved != "n" &&
                    (stuck || now - hNotTalkingSinceMs >= 20_000L) && now - hLastAttemptMs >= 45_000L
                if (act) {
                    if (stuck) { android.util.Log.i("COMMHEALTH", "connecting for ${(now - hConnectingSinceMs) / 1000} s -- stuck"); hConnectingSinceMs = 0L }
                    if (hAttempts < 3 || now - hLastAttemptMs >= 120_000L) {
                        hAttempts++; hLastAttemptMs = now
                        android.util.Log.i("COMMHEALTH", "radio not talking -> reconnect $hAttempts ($saved)")
                        radioController.setDeviceAddress("n")
                        delay(1_500L)
                        radioController.setDeviceAddress(saved!!)   // act requires a saved radio (checked above)
                    }
                }
            }
        }
        val reason = when {
            talking -> null
            hUnpaired && !connected -> "RADIO_UNPAIRED"
            stuck -> "CONNECT_TIMEOUT"
            state == Link.CONNECTING -> "CONNECTING"   // COMMHEALTH2-2026-10-11
            hAttempts >= 3 -> "RECOVERY_EXHAUSTED"
            connected -> "RADIO_SILENT"
            else -> "NOT_CONNECTED"
        }
        val h = LinkHealth(talking = talking, recovering = !talking && hAttempts > 0, attempts = hAttempts, reason = reason)
        if (h != _health.value) {
            android.util.Log.i("COMMHEALTH", "health: talking=${h.talking} recovering=${h.recovering} attempts=${h.attempts} reason=${h.reason} (link=$state own=${ownTakMs?.let { (now - it) / 1000 }}s traffic=${(now - hTrafficMs) / 1000}s)")
            _health.value = h
        }
    }

    private fun toLink(s: ConnectionState): Link = when (s) {
        ConnectionState.Connected -> Link.CONNECTED
        ConnectionState.Connecting -> Link.CONNECTING
        else -> Link.DISCONNECTED   // Disconnected, DeviceSleep
    }
}
