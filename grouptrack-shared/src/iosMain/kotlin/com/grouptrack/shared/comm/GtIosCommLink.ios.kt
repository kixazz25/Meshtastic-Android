/*
 * Copyright (c) 2026 GroupTrack
 */
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)   // Kable's Scanner.services still requires it (SDK does the same)

package com.grouptrack.shared.comm

import com.grouptrack.comm.CommLink
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.Link
import com.grouptrack.comm.LinkHealth
import com.grouptrack.comm.RadioEntry
import com.grouptrack.shared.log.gtLogger
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.isInProgress
import org.meshtastic.sdk.isUsable
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.ble.BleTransport

/**
 * IOSCOMM-2026-11-20 (Path B step 2) -- the iPad's implementation of GroupTrack's [CommLink], over the
 * official Meshtastic KMP SDK (org.meshtastic:sdk-core 0.2.0, pinned).
 *
 * ONE BOUNDARY (Fred 11-20 07:14): GroupTrack asks this; this asks the SDK. Group mode, link state and the
 * radio-setup menu are not separate host-local flags -- they are all this contract, which is why group
 * awareness went to shared GtGroupMode and why the radio panels call through here.
 *
 * The Android side (MeshCommLink) implements the SAME contract over the Meshtastic Android service, so the two
 * platforms are two implementations of one interface rather than two designs. Where iOS genuinely cannot do
 * what Android does, this returns [CommResult.NotSupported] or the contract's documented null -- never a false
 * Ok. Fred 11-19 10:48 sets the standard the other way too: a function is WIRED even where it cannot EXECUTE,
 * and on the simulator none of this can execute (no Bluetooth) -- which is expected, not a gap.
 *
 * @param scope the caller's scope; every job started here is a child of it, so the host cancels this cleanly.
 */
class GtIosCommLink(private val scope: CoroutineScope) : CommLink {

    private val log = gtLogger("GtIosCommLink")

    private val _link = MutableStateFlow(Link.DISCONNECTED)
    override val link: StateFlow<Link> = _link.asStateFlow()

    private val _health = MutableStateFlow(LinkHealth(talking = false, recovering = false, attempts = 0, reason = "NOT_CONNECTED"))
    override val health: StateFlow<LinkHealth> = _health.asStateFlow()

    // IOSCOMM-2026-11-20: no Wi-Fi box path on iOS yet. CODE RULE 1: null = not on a box's Wi-Fi, which is the
    // contract's own meaning ("no Wi-Fi path -- LoRa only"), so this is accurate rather than unimplemented.
    private val _onBoxWifi = MutableStateFlow<String?>(null)
    override val onBoxWifi: StateFlow<String?> = _onBoxWifi.asStateFlow()

    private val _radios = MutableStateFlow<List<RadioEntry>>(emptyList())
    override val radios: StateFlow<List<RadioEntry>> = _radios.asStateFlow()

    private val _selectedRadioId = MutableStateFlow<String?>(null)
    override val selectedRadioId: StateFlow<String?> = _selectedRadioId.asStateFlow()

    private val _scanProblem = MutableStateFlow<String?>(null)
    override val scanProblem: StateFlow<String?> = _scanProblem.asStateFlow()

    private var scanJob: Job? = null
    private var linkJob: Job? = null
    private var client: RadioClient? = null

    /** GRPMODE-2026-10-13: group awareness off = the transport goes quiet, and the radio in use is REMEMBERED. */
    private var active = true
    private var rememberedRadioId: String? = null

    // ── radios nearby ───────────────────────────────────────────────────────────────────────────────────
    //
    // Only radios advertising the Meshtastic service, filtered by the SDK's own UUID rather than one copied
    // into our source. `paired` is reported FALSE for every entry: iOS does not expose the system's bonded
    // list, so claiming a radio is paired would be a guess -- and the contract's own note says iPad pairing is
    // the rider's. The rider sees what is in range; the system handles the pairing sheet on connect.
    override fun startScan(): CommResult {
        if (scanJob?.isActive == true) return CommResult.Ok
        _scanProblem.value = null
        scanJob = scope.launch {
            try {
                val scanner = Scanner { filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } } }
                scanner.advertisements.collect { ad ->
                    val id = ad.identifier.toString()
                    val entry = RadioEntry(
                        id = id,
                        name = ad.name ?: ad.peripheralName ?: id,
                        paired = false,
                        signalDbm = ad.rssi,
                    )
                    _radios.value = (_radios.value.filter { it.id != id } + entry).sortedByDescending { it.signalDbm ?: Int.MIN_VALUE }
                }
            } catch (e: Throwable) {
                // COMMBTOFF-2026-10-11: the rider needs to know WHY the list is empty. Kable throws when
                // Bluetooth is off or the permission is missing; iOS does not distinguish them as cleanly as
                // Android, so the message is carried through rather than guessed at.
                _scanProblem.value = "BLUETOOTH_UNAVAILABLE"
                log.w { "IOSCOMM-2026-11-20 scan stopped: " + e.message }
            }
        }
        return CommResult.Ok
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    override suspend fun listRadios(): List<RadioEntry> = _radios.value

    // COMMNAMES-2026-10-11: the name a radio LAST USED. Android reads RadioNameStore, which lives in
    // core/data (Android only). CODE RULE 1: null = not known, and the contract defines that as "the caller
    // shows the radio's own or Bluetooth name" -- so this is a correct answer, not a stub. A shared name
    // table is its own piece of work; half-building one here would be the parallel helper Fred ruled out.
    override fun radioName(radioId: String, memberId: String?, bluetoothName: String): String? = null

    // ── the link ────────────────────────────────────────────────────────────────────────────────────────

    override suspend fun connect(radioId: String): CommResult {
        if (!active) return CommResult.Failed("GROUP_AWARENESS_OFF")
        disconnectInternal()
        log.i { "IOSCOMM-2026-11-20 connect " + radioId }
        // The SDK's own pattern (TransportSpec.kt): scan for THIS radio, then build the transport from that
        // advertisement. Kable needs the advertisement, not just an address, so a fresh look-up is the
        // honest way -- a cached advertisement can be stale by the time the rider taps connect.
        val scanner = Scanner { filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } } }
        val ad = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            scanner.advertisements.first { it.identifier.toString() == radioId }
        } ?: run {
            setHealth(Link.DISCONNECTED, "CONNECT_TIMEOUT", 1, recovering = false)
            return CommResult.Failed("CONNECT_TIMEOUT")
        }
        return try {
            val transport = BleTransport(peripheral = Peripheral(ad), address = radioId)
            val c = RadioClient.Builder()
                .transport(transport)
                // baseDir = "" is the SDK's in-memory mode. PERSISTENCE IS THE NEXT STEP: pass GroupTrack's
                // iOS application-support directory here and the node database survives a restart.
                .storage(SqlDelightStorageProvider(baseDir = ""))
                .build()
            client = c
            _selectedRadioId.value = radioId
            rememberedRadioId = radioId
            watchConnection(c)
            c.connect()
            CommResult.Ok
        } catch (e: Throwable) {
            log.e { "IOSCOMM-2026-11-20 connect failed: " + e.message }
            setHealth(Link.DISCONNECTED, "NOT_CONNECTED", 1, recovering = false)
            CommResult.Failed(e.message ?: "CONNECT_FAILED")
        }
    }

    override suspend fun disconnect(): CommResult {
        disconnectInternal()
        return CommResult.Ok
    }

    /** A6 -- ONE implementation. Reconnects the radio in use, or the one remembered across a quiet period. */
    override suspend fun reconnect(): CommResult {
        val id = _selectedRadioId.value ?: rememberedRadioId ?: return CommResult.Failed("NOT_CONNECTED")
        return connect(id)
    }

    // A5: iOS exposes no API to unpair a peripheral -- the rider does it in iOS Settings. The contract already
    // says so ("iPad: partial -- system pairing is the rider's"), so NotSupported is the truthful answer and
    // GroupTrack can say the right thing to the rider instead of reporting a success that never happened.
    override suspend fun forget(radioId: String): CommResult {
        if (_selectedRadioId.value == radioId) disconnectInternal()
        return CommResult.NotSupported
    }

    /** GRPMODE-2026-10-13: off = no link kept and no reconnects, the radio remembered; on = use it again. */
    override suspend fun setActive(active: Boolean): CommResult {
        if (this.active == active) return CommResult.Ok
        this.active = active
        return if (!active) {
            log.i { "IOSCOMM-2026-11-20 group awareness off -- going quiet (remembered " + rememberedRadioId + ")" }
            stopScan()
            disconnectInternal()
            CommResult.Ok
        } else {
            rememberedRadioId?.let { connect(it) } ?: CommResult.Ok
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────────────────

    private suspend fun disconnectInternal() {
        linkJob?.cancel(); linkJob = null
        val c = client
        client = null
        _selectedRadioId.value = null
        if (c != null) runCatching { c.disconnect() }.onFailure { log.w { "disconnect: " + it.message } }
        setHealth(Link.DISCONNECTED, "NOT_CONNECTED", 0, recovering = false)
    }

    /**
     * COMMHEALTH-2026-10-11: keeping the link is the comm side's job and GroupTrack only reports it, so the
     * SDK's connection state is translated into the contract's OWN words here -- never passed through raw.
     * isUsable / isInProgress are the SDK's documented extensions, so this does not depend on knowing every
     * case of its sealed interface.
     */
    private fun watchConnection(c: RadioClient) {
        linkJob = scope.launch {
            c.connection.collect { st ->
                val attempts = when (st) {
                    is ConnectionState.Reconnecting -> st.attempt
                    is ConnectionState.Connecting -> st.attempt
                    else -> 0
                }
                val recovering = st is ConnectionState.Reconnecting
                when {
                    st.isUsable -> setHealth(Link.CONNECTED, null, attempts, recovering = false)
                    st.isInProgress -> setHealth(Link.CONNECTING, "CONNECTING", attempts, recovering)
                    else -> setHealth(Link.DISCONNECTED, if (recovering) "CONNECTING" else "NOT_CONNECTED", attempts, recovering)
                }
            }
        }
    }

    /**
     * IOSCONFIG-2026-11-20: the connected client, for the sibling APIs of this contract (CommConfig today;
     * CommIdentity / CommMessaging next). CODE RULE 1: null = no radio connected, which every caller must
     * handle anyway -- on the simulator it is ALWAYS null, because there is no Bluetooth.
     */
    internal fun radioClient(): RadioClient? = client

    private fun setHealth(l: Link, reason: String?, attempts: Int, recovering: Boolean) {
        _link.value = l
        _health.value = LinkHealth(talking = l == Link.CONNECTED, recovering = recovering, attempts = attempts, reason = reason)
    }

    private companion object {
        /** Long enough for a radio that is awake to advertise, short enough that the rider is not left waiting. */
        const val SCAN_TIMEOUT_MS = 10_000L
    }
}
