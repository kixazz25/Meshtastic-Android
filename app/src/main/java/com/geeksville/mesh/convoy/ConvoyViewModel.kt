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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted   // REPORTNOW-2026-10-13
import kotlinx.coroutines.flow.stateIn   // REPORTNOW-2026-10-13
import kotlinx.coroutines.launch
// CLEANDEAD-2026-10-14: the Meshtastic imports removed (profile use cases, node / config repositories, DeviceProfile)
// COMMREPORT-2026-10-12: RadioOps no longer used here -- the GPS interval goes through CommMessaging.setReporting (P4.4)
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
    private val settingsRepository: ConvoySettingsRepository,
    private val commConfig: com.grouptrack.comm.CommConfig, // COMMCONFIG-2026-10-12: the radio configurator, through the Comm API
    private val commMessaging: com.grouptrack.comm.CommMessaging, // COMMSEND-2026-10-08: TAK out through the Comm API
    private val commMenus: com.grouptrack.comm.CommMenus, // COMMMENUS-2026-10-08: Meshtastic menus opened by request
    private val commIdentity: com.grouptrack.comm.CommIdentity, // COMMIDENT-2026-10-09: this radio through the Comm API
    private val commLink: com.grouptrack.comm.CommLink, // COMMLINK-2026-10-11: the radio link through the Comm API
) : ViewModel() {

    private val _convoyState = MutableStateFlow(ConvoyEngine.ConvoyState.empty())
    val convoyState: StateFlow<ConvoyEngine.ConvoyState> = _convoyState.asStateFlow()
    private val _rideState = MutableStateFlow(RideState.SOLO)
    val rideState: StateFlow<RideState> = _rideState.asStateFlow()
    private val _activeRide = MutableStateFlow<ConvoyEventConfig?>(null)
    val activeRide: StateFlow<ConvoyEventConfig?> = _activeRide.asStateFlow()
    // COMMIDENT-2026-10-09 (2.7b cycle 1 P1): THIS RADIO through the Comm API (CommIdentity) -- was Meshtastic's MyNodeInfo / Node.
    // CODE RULE 1: null = no radio has reported yet (no radio, or waiting for the link) -- the phone-only state.
    private val _myDevice = MutableStateFlow<com.grouptrack.comm.DeviceInfo?>(null)
    val myDevice: StateFlow<com.grouptrack.comm.DeviceInfo?> = _myDevice.asStateFlow()
    /** COMMLINK-2026-10-11 (2.7b P2.1): the radio link -- CONNECTED / CONNECTING / DISCONNECTED -- through the Comm API (CommLink). */
    val link: StateFlow<com.grouptrack.comm.Link> = commLink.link
    /** COMMHEALTH2-2026-10-11: the link's health from the comm side (talking, recovering, attempts, reason). */
    val health: StateFlow<com.grouptrack.comm.LinkHealth> = commLink.health
    /** COMMNAMES-2026-10-11: the name a radio in GRP Awareness's list last used (the comm side's table); null = not known. */
    fun rememberedRadioName(radioId: String, memberNum: Int?, bluetoothName: String): String? =
        commLink.radioName(radioId, memberNum?.toString(), bluetoothName)
    /** COMMLINKREQ-2026-10-11 (2.7b P2.2): the rider's radio requests, through the Comm API. A request never waits for its outcome --
     *  the link state shows it. forgetRadio reports back: true = forgotten; false = Android refused or not a pairing. */
    /** COMMRADIOS-2026-10-11: CONNECT or PAIR by id; [onFail] gets the reason when it did not work (the panel says so). */
    fun connectRadio(radioId: String, onFail: (String) -> Unit) {
        viewModelScope.launch {
            val r = commLink.connect(radioId); linkResult("connect", r)
            if (r is com.grouptrack.comm.CommResult.Failed) onFail(r.reason)
        }
    }
    /** COMMRADIOS-2026-10-11: the radio list (paired first) and the radio in use, from the comm side. */
    val radios: StateFlow<List<com.grouptrack.comm.RadioEntry>> = commLink.radios
    val selectedRadioId: StateFlow<String?> = commLink.selectedRadioId
    /** COMMRADIOS-2026-10-11: GRP Awareness opened -- look for radios nearby. CODE RULE 1: null = scanning; else why not. */
    fun startRadioScan(): String? = (commLink.startScan() as? com.grouptrack.comm.CommResult.Failed)?.reason
    fun stopRadioScan() { commLink.stopScan() }
    /** COMMBTOFF-2026-10-11: why the radio list cannot look for radios, LIVE. CODE RULE 1: null = nothing wrong. */
    val scanProblem: StateFlow<String?> = commLink.scanProblem
    fun disconnectRadio() { viewModelScope.launch { linkResult("disconnect", commLink.disconnect()) } }
    fun forgetRadio(radioId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { val r = commLink.forget(radioId); linkResult("forget", r); onResult(r is com.grouptrack.comm.CommResult.Ok) }
    }
    private fun linkResult(what: String, r: com.grouptrack.comm.CommResult) {
        android.util.Log.i("CommLink", "COMMLINKREQ-2026-10-11: $what -> $r")
    }
    /** This radio's node number, while the tick still reads the radio's node list (cycle 3 retires it). On the mesh the
     *  device id is the node number as text -- the same id the Comm API uses for members. */
    private fun myRadioNum(): Int? = _myDevice.value?.id?.toIntOrNull()
    /** The ride record's radio fields (V3 screens): hardwareId = "!xxxxxxxx" (the cart id form); deviceId = the hardware's
     *  own id. "unknown" / "" with no radio -- as before. */
    fun rideHardwareId(): String = myRadioNum()?.let { "!%08x".format(it) } ?: "unknown"
    fun rideDeviceId(): String = _myDevice.value?.deviceId ?: ""

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
    /** REPORTNOW-2026-10-13 (P5.2): the radio's reporting interval now, from the comm side. CODE RULE 1: null = not read yet. */
    val reportingNowSecs: StateFlow<Int?> by lazy { commMessaging.currentReportingSecs.stateIn(viewModelScope, SharingStarted.Eagerly, null) }

    // ── Lead lock state ──────────────────────────────────────────────────
    private var _leadLockedFlag: Boolean = false
    // LEADCLEAN-2026-09-29: the per-node distance accumulator (the old quarter-mile lead finder) is removed -- it was never filled.
    private var nodeLastLat: MutableMap<String, Double> = mutableMapOf()
    private var nodeLastLon: MutableMap<String, Double> = mutableMapOf()
    // 60-second fixed window speed computation per node
    private val nodeSpeedWindowStart: MutableMap<String, Triple<Long, Double, Double>> = mutableMapOf()
    private val nodeLastComputedSpeed: MutableMap<String, Float> = mutableMapOf()
    // HEARDBOARD-2026-10-14: THE CART BOARD's fields -- HERE, before init, because init starts the board's listener and on the main thread
    // it runs at once (10-14: a board used before its fields existed crashed the app at start-up).
    // Thread-safe maps: messages arrive on the main thread, tablet fixes possibly from the recording service.
    // BOARDSHOW2-2026-10-15 (T5.3): the SCREEN draws from the board. false = the tick drives the screen again (one-line rollback).
    private val boardShows = true
    @Volatile private var boardMyLastMs = 0L          // BOARDSHOW2-2026-10-15: when my cart last got a board report (one per reporting interval)
    private var boardShownSig: List<List<Any?>>? = null  // BOARDSHOW2-2026-10-15: what the screen shows now. CODE RULE 1: null = nothing shown yet
    private var boardPublished = 0                     // BOARDSHOW2-2026-10-15: screen updates since the last tick (TICKSHADOW)
    // LEADCLEAR-2026-10-15: the lead the board took FROM A LEADER REPORT. CODE RULE 1: null = the lead (if any) did not come from a report.
    @Volatile private var reportedLeadId: String? = null
    @Volatile private var radioTrackLastFixMs = 0L   // RADIOTRACK-2026-10-15: the last radio fix fed to the recorder (new fixes only)
    private val boardCarts = java.util.concurrent.ConcurrentHashMap<String, ConvoyNode>()
    private val boardSpeedStart = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Double, Double>>()
    private val boardSpeedLast = java.util.concurrent.ConcurrentHashMap<String, Float>()
    @Volatile private var boardState = ConvoyEngine.ConvoyState.empty()
    @Volatile private var boardHeard = 0
    @Volatile private var boardMyFixes = 0
    private var shadowLastKey = ""
    private var shadowTicks = 0

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
    // LINKFIX-2026-10-03 (Fred, after the testers' field day): THE RADIO-LINK RULES.
    // "Talking" = the radio is really passing traffic -- judged on the comm side since COMMHEALTH2-2026-10-11
    // (every report the radio passes on -- its own position, other carts' reports -- changes the list).
    // Not talking, at ANY moment -> Android-only mode: my cart at the tablet's GPS under the SAME cart id, the
    // other carts held at their last tick position, GRP Awareness red. Nothing is stored.
    // COMMHEALTH2-2026-10-11: "talking" and the recovery now come from the COMM side (CommLink.health) -- the heartbeat fields moved there.
    private var radioTalkingNow = false
    private var phoneLocationListener: android.location.LocationListener? = null

    /**
     * NOGPSMSG-2026-09-15: the two position states the ride panel reports to the rider.
     *
     * HARD -- no position from any source. The device cannot function. Shown
     * every time; NOT dismissible.
     * SOFT -- position came from the phone's own NETWORK fallback, not GPS
     * hardware and not a radio. Works here, will not work on the trail.
     * Dismissible permanently (the flag itself lives in ConvoyScreen; this is
     * only the condition).
     *
     * Both are set in readLiveNodes()'s no-radio branch and cleared at the top
     * of that function, so the radio path resets them with no edit of its own.
     */
    private val _noPositionError = kotlinx.coroutines.flow.MutableStateFlow(false)
    val noPositionError: kotlinx.coroutines.flow.StateFlow<Boolean> = _noPositionError

    private val _networkPositionWarning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val networkPositionWarning: kotlinx.coroutines.flow.StateFlow<Boolean> = _networkPositionWarning

    /**
     * NETLOC-2026-09-15: ASK EVERY PROVIDER THAT EXISTS, NOT JUST GPS.
     *
     * This is the live feed that drives the cart when there is no radio. It used
     * to subscribe to GPS_PROVIDER alone. On a device with NO GPS CHIP that
     * delivers nothing, the legacy NETWORK_PROVIDER is dormant on modern Android
     * (Play Services moved network-derived positioning into FUSED), and the cart
     * ended up with a name and 0.0/0.0 -- silently, with the map left at its
     * construction default.
     *
     * Bluetooth GPS from a paired phone needs no special case: it arrives through
     * these same providers and the chain picks it up transparently.
     *
     * Each registration is guarded on its own. A provider that does not exist on
     * a given device throws, and before this one throw lost the entire feed.
     */
    private fun startPhoneGps() {
        if (phoneLocationListener != null) return
        val lm = appContext.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return

        // NETLOC-2026-09-15: keep the BETTER fix, not the latest one. Registering several
        // providers against one listener means fixes of very different quality
        // arrive interleaved; last-write-wins would let a 30 m network fix
        // overwrite a 3 m GPS fix two seconds later.
        val listener = android.location.LocationListener { loc ->
            val held = livePhoneLocation
            val staleMs = 15000L
            val take = held == null ||
                (System.currentTimeMillis() - held.time) > staleMs ||
                loc.accuracy <= held.accuracy
            if (take) { livePhoneLocation = loc; onMyTabletFix(loc.latitude, loc.longitude, loc) }   // MYFIX-2026-10-14
        }
        phoneLocationListener = listener

        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER
        )

        var registered = 0
        for (p in providers) {
            try {
                lm.requestLocationUpdates(
                    p,
                    2000L,  // 2 second interval
                    1f,     // 1 meter minimum distance
                    listener,
                    android.os.Looper.getMainLooper()
                )
                registered++
                android.util.Log.i("ConvoyVM", "NETLOC-2026-09-15: subscribed to $p")
            } catch (e: SecurityException) {
                android.util.Log.e("ConvoyVM", "NETLOC-2026-09-15: permission denied for $p: ${e.message}")
            } catch (e: Exception) {
                // Provider absent on this device. Expected, not an error.
                android.util.Log.w("ConvoyVM", "NETLOC-2026-09-15: no provider $p (${e.javaClass.simpleName})")
            }
        }
        if (registered == 0) {
            android.util.Log.e("ConvoyVM", "NETLOC-2026-09-15: NO LOCATION PROVIDER AVAILABLE -- " +
                "this device cannot determine its position without a mesh radio " +
                "or a Bluetooth-paired phone.")
            phoneLocationListener = null
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
        // COMMNAMES-2026-10-11: the name table is kept (and written on every real change) by the comm side -- no save at RECORD.
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
        val myNum = myRadioNum()   // COMMIDENT-2026-10-09
        _myCartId.value = if (myNum != null) "!%08x".format(myNum) else "!phone"
        val nodes = readLiveNodes(System.currentTimeMillis())

        // LEADCLEAN-2026-09-29 (Fred): the lead comes from a Leader report (the tick) or the rider's pick at REC -- nothing
        // else. Only a solo ride leads itself: one cart -> it, none -> my cart. Several carts and no lead -> it stays
        // EMPTY (REC asks -- RECLEAD). The old automatic "several carts -> my cart" pick is removed.
        if (lockedLeadNodeId == null) {
            when {
                nodes.size == 1 -> setLeadCart(nodes[0].nodeId)
                nodes.isEmpty() -> setLeadCart(_myCartId.value)
                else -> convoyLog("TRACK START: several carts and no lead -- left empty (REC asks)")
            }
        }

        // TRACK: Activate AFTER identity and lead are locked
        _trackActive.value = true
        _autoPan.value = true   // autoPan ON during recording
        convoyLog("TRACK START: myCart=${_myCartId.value} lead=$lockedLeadNodeId nodes=${nodes.size}")
    }

    /** COMMMENUS-2026-10-08: open one of the comm side's menus by id (e.g. "mesh-menu"). GroupTrack never names a
     *  Meshtastic screen; the comm side answers NotSupported for an id it does not offer. */
    fun openCommMenu(id: String): com.grouptrack.comm.CommResult {
        val r = commMenus.open(id)
        android.util.Log.i("CommMenus", "COMMMENUS open '$id' -> $r")
        return r
    }
    /** COMMSETTINGS-2026-10-12: the comm-side screen open now (CODE RULE 1: null = none); closing it; the menus the comm side offers. */
    val openMenu: StateFlow<String?> = commMenus.openMenu
    fun closeCommMenu(id: String): com.grouptrack.comm.CommResult {
        val r = commMenus.close(id)
        android.util.Log.i("CommMenus", "COMMSETTINGS-2026-10-12 close '$id' -> $r")
        return r
    }
    val commMenuIds: Set<String> = commMenus.items().map { it.id }.toSet()

    fun stopGroupTrack() {
        _trackActive.value = false
        _autoPan.value = false   // autoPan OFF when recording stops
        _leadLockedFlag = false
        lockedLeadNodeId = null
        if (GroupMode.on.value) commMessaging.clearRoles()   // GRPMODE-2026-10-13 · COMMRECV-2026-10-08 (via the Comm API) · LEADCLEAN-2026-09-29 (Fred): END -- the ride's roles end with the ride (the lead is cleared above)
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
        roleReportJob?.cancel()   // ROLECHANGE-2026-09-29 (Fred): END -- the role reports stop with the ride
    }

    /**
     * ROLEREPORT-2026-09-29 (Fred) -- TEST: the role travels in a standard TAK POSITION REPORT that the TABLET fills in
     * (the radio only carries it; nothing on the radio changes). Only the three special roles report: Leader -> TeamLead,
     * Middle -> RTO, Tail gunner -> ForwardObserver. Sent at check-in, then every 30 s for 3 minutes (7 reports), no ack.
     * A report, not a message: nobody answers it. Logged "ROLEREPORT sent n/7 ...".
     */
    // CODE RULE 1: null = no report run in progress (a new check-in cancels the previous run).
    private var roleReportJob: kotlinx.coroutines.Job? = null
    fun startRoleReports(rideRole: String) {
        // ROLECHANGE-2026-09-29 (Fred): every cart reports ONLY its own role. A rider who HELD a special role and now checks in (or
        // changes) as Rider RELEASES it: announced as HQ ("now Rider") so every tablet drops it. Riders otherwise never report.
        val myNum = myRadioNum()   // COMMIDENT-2026-10-09
        // COMMRECV-2026-10-08: the ride role in the Comm API's terms (LEADER/MIDDLE/TAIL_GUNNER/RIDER); the mesh translator
        // maps it to TAK (TeamLead/RTO/ForwardObserver/HQ) -- the same 2.7a table.
        val role = when (rideRole) {
            "leader" -> com.grouptrack.comm.Role.LEADER
            "middle" -> com.grouptrack.comm.Role.MIDDLE
            "tail_gunner" -> com.grouptrack.comm.Role.TAIL_GUNNER
            else -> {
                val held = myNum?.let { commMessaging.reportedRole(it.toString()) }
                if (held == com.grouptrack.comm.Role.LEADER || held == com.grouptrack.comm.Role.MIDDLE ||
                    held == com.grouptrack.comm.Role.TAIL_GUNNER) com.grouptrack.comm.Role.RIDER
                else {
                    roleReportJob?.cancel()
                    android.util.Log.i("ROLEREPORT", "ROLEREPORT: role '$rideRole' -- riders do not report")
                    return
                }
            }
        }
        // OWNROLE-2026-09-28 (Fred): MY role lives in the SAME store as everyone else's (against my own radio's number).
        if (!GroupMode.on.value) { android.util.Log.i("ROLEREPORT", "GRPMODE-2026-10-13: group awareness off -- no role reports"); return }
        myNum?.let { commMessaging.recordOwnRole(it.toString(), role) }   // COMMRECV-2026-10-08
        roleReportJob?.cancel()
        val startedAt = System.currentTimeMillis()
        roleReportJob = viewModelScope.launch {
            var n = 0
            while (true) {
                n++
                runCatching {
                    val me = _convoyState.value.nodes.firstOrNull { it.isMyCart }
                    val loc = getPhoneLocation()
                    val lat = me?.latitude?.takeIf { it != 0.0 } ?: loc?.latitude
                    val lon = me?.longitude?.takeIf { it != 0.0 } ?: loc?.longitude
                    if (lat == null || lon == null) { android.util.Log.w("ROLEREPORT", "ROLEREPORT #$n: no position -- skipped"); return@runCatching }
                    val cs = checkIn.value?.callsign?.trim()?.ifEmpty { null } ?: profileCallsign.ifEmpty { "GroupTrack" }
                    // COMMSEND-2026-10-08 (2.7b cycle 1 step 2a): the report is a CoT message handed to the Comm API; the mesh
                    // translator (app module) turns it into the same TAK V1 report on port 72 as before -- same bytes on the air.
                    val nowMs = System.currentTimeMillis()
                    val res = commMessaging.deliver(com.grouptrack.comm.CommMessage(
                        uid = "GroupTrack-" + (myNum ?: 0),   // cycle 2: the rider's GroupTrack id (V1 does not carry a uid)
                        type = "a-f-G-U-C", how = "m-g",
                        timeMs = nowMs, startMs = nowMs, staleAtMs = nowMs + 90_000L,   // stale = 3 report intervals (not on the V1 wire)
                        point = com.grouptrack.comm.CotPoint(lat, lon, null, null, null),
                        callsign = cs, role = role, team = "Cyan",
                        track = null, batteryPct = null,
                        groupTrack = com.grouptrack.comm.GroupTrackDetail(checkIn.value?.rideId ?: "",
                            com.grouptrack.comm.GroupTrackDetail.Kind.REPORT),
                    ))
                    if (res !is com.grouptrack.comm.CommResult.Ok) {
                        android.util.Log.w("ROLEREPORT", "ROLEREPORT #$n not sent: $res"); return@runCatching
                    }
                    android.util.Log.i("ROLEREPORT", "ROLEREPORT sent #$n at ${System.currentTimeMillis()}: $cs as $role")
                }.onFailure { android.util.Log.w("ROLEREPORT", "ROLEREPORT #$n not sent: ${it.message}") }
                kotlinx.coroutines.delay(if (n < 3) 5_000L else 30_000L)   // ROLEBTN-2026-09-29: the first three 5 s apart, then 30 s
                // THE SCHEDULE (Fred): until 3 minutes after REC; a change made DURING the ride: 3 minutes from the change.
                // No REC yet: keep going (capped at 4 hours). END cancels this job (stopGroupTrack).
                val recAt = rideStartTimeMs
                val endAt = if (recAt == 0L) startedAt + 4 * 3_600_000L else maxOf(recAt, startedAt) + 180_000L
                if (System.currentTimeMillis() >= endAt) break
            }
            android.util.Log.i("ROLEREPORT", "ROLEREPORT: run complete ($n reports, $role)")
        }
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

    // CHECKIN-2026-09-27 (Fred): the recording sequence starts at CHECK_IN -- you cannot record until you have checked in.
    var recordingState = androidx.compose.runtime.mutableStateOf(com.geeksville.mesh.convoy.RecordingState.CHECK_IN)
    /** The current check-in (CODE RULE 1: null = not checked in). Set by the check-in; handed to the end form at END. */
    val checkIn = androidx.compose.runtime.mutableStateOf<ConvoyRideStore.CheckIn?>(null)
    /** TICKDATA-2026-09-28 (Fred): the rider's CALLSIGN for this tablet's node -- the check-in's, else the profile's (read once,
     *  then kept: not a database read every tick), else the device model only if neither exists. */
    private val profileCallsign: String by lazy { runCatching { ConvoyProfileStore.load()?.callsign }.getOrNull()?.trim().orEmpty() }
    private fun tickCallsign(): String =
        checkIn.value?.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: profileCallsign.takeIf { it.isNotEmpty() } ?: android.os.Build.MODEL

    /** TAKROLE-2026-09-28 (Fred): a radio's RIDE ROLE from its TAK reports -- THE one mapping table (the send side uses the
     *  same). TeamMember (every radio's default), no report, or anything else = "" = a bare radio. Display only. */
    private fun takRideRole(nodeNum: Int): String = when (commMessaging.reportedRole(nodeNum.toString())) {   // COMMRECV-2026-10-08
        com.grouptrack.comm.Role.LEADER -> "leader"
        com.grouptrack.comm.Role.MIDDLE -> "middle"
        com.grouptrack.comm.Role.TAIL_GUNNER -> "tail_gunner"
        com.grouptrack.comm.Role.RIDER -> "rider"
        null -> ""
    }
    /** The check-in of the recording being ended -- read by the end form; cleared on save or delete. */
    var endingCheckIn: ConvoyRideStore.CheckIn? = null
    var pendingTrackName = androidx.compose.runtime.mutableStateOf("")
    var showRecMenu = androidx.compose.runtime.mutableStateOf(false)
    var pendingEnrollmentEmail = androidx.compose.runtime.mutableStateOf("")
    var hasSeenNodes = androidx.compose.runtime.mutableStateOf(false)

    // RETIRE26-2026-10-08: WorkingConfig (2.6 apply chain state) removed

    // CLEANDEAD-2026-10-14: deviceProfileFlow + exportProfileToFile removed (no caller; saved configs are the comm side's, P4.3).

    // SAVEDCFG-2026-10-12: importProfileFromFile removed -- the saved-configs screen asks CommConfig (P4.3).

    // RADIOCFG4-2026-09-25: the radio configurator's links to the radio (RadioConfigWriter.RadioOps).
    fun radioNodeNum(): Int? = myRadioNum()

    // SAVEDCFG-2026-10-12: currentProfile removed -- "Radio now" is CommConfig.RADIO_NOW (P4.3).

    // COMMREPORT-2026-10-12: the radio-adapter accessor removed -- its last user (the GPS interval) goes through CommMessaging.setReporting.
    /** COMMCONFIG-2026-10-12 (P4.2): applying a ride (or a save) to the radio goes through the Comm API -- the ride file in, the report out. */
    val radioConfig: com.grouptrack.comm.CommConfig get() = commConfig

    // CLEANDEAD-2026-10-14: installProfileToRadio removed (no caller -- whole-image restore was never offered).

    // COMMSAVES-2026-10-14: ConvoyProfilePaths removed (unused; the save folder belongs to the comm side)


    var persistentWebView: android.webkit.WebView? = null
    /** COSMETICS-2026-10-06 (Fred): the HUD text colour chosen with the toggle, PER MAP TYPE, for this session only
     *  (true = white text). A map type with no entry uses its default (HUDSCHEME-2026-09-29). */
    val hudTextWhiteByMap = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    /** RIDEMAPSTATE-2026-10-05 (Fred): the ride map's bridge-written state (detail target, lists, viewport, long-press, route mode),
     *  shared by every visit -- it must live as long as persistentWebView, whose JavaScript keeps calling the first bridge. */
    val convoyRideMapStates = HashMap<String, androidx.compose.runtime.MutableState<*>>()

    private val _simulationMode = MutableStateFlow(false)
    val simulationMode: StateFlow<Boolean> = _simulationMode.asStateFlow()

    private val _myCartId = MutableStateFlow("!phone")   // [2026-07-01] device-default (was ConvoySimulation.MY_CART_ID / HOTEL-10 sim ghost); radio overrides on connect via observer
    private fun resolveMyCartId(): String {
        val num = myRadioNum()
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

    // CARTACTIVE-2026-09-30 (Fred): nodeId -> callsign of carts removed on this tablet today (the settings store,
    // cleared by day). One tap in SELECT CART toggles Active <-> Removed. My own cart is never removed.
    private val _removedCarts = MutableStateFlow<Map<String, String>>(emptyMap())
    val removedCarts: StateFlow<Map<String, String>> = _removedCarts.asStateFlow()
    fun toggleCartActive(nodeId: String, callsign: String) {
        if (nodeId == resolveMyCartId()) return
        viewModelScope.launch {
            if (_removedCarts.value.containsKey(nodeId)) settingsRepository.reinstateCart(nodeId)
            else settingsRepository.removeCart(nodeId, callsign.ifBlank { nodeId }.replace("|", "/"))
        }
    }

    init {
        // GRPMODE-2026-10-13: group awareness -- read once; off -> the comm side goes quiet and the HUD shows MY CART.
        GroupMode.load(appContext)
        if (!GroupMode.on.value) { _hudMode.value = HudMode.MY_CART; viewModelScope.launch { commLink.setActive(false) }; enterAndroidMode() }   // ANDROIDMODE1-2026-10-14
        viewModelScope.launch {
            settingsRepository.admissionWindowHours.collect { admissionWindowHours = it }
        }
        // GRPMODE2-2026-10-13 (bug fix): the saved alert values were never read back -- after every restart the built-in ones applied.
        viewModelScope.launch { settingsRepository.signalDropMinutes.collect { ConvoyConfig.SIGNAL_DROP_MINUTES = it } }
        viewModelScope.launch { settingsRepository.signalLostMinutes.collect { ConvoyConfig.LOST_MINUTES = it } }
        viewModelScope.launch { settingsRepository.offTrackMilesSaved.collect { v -> if (v != null) ConvoyConfig.OFF_TRACK_MILES = v } }
        // CARTACTIVE-2026-09-30 (Fred): carts removed on this tablet today -- the tick skips them, SELECT CART lists them.
        viewModelScope.launch {
            settingsRepository.removedCartsForToday.collect { _removedCarts.value = it }
        }

        viewModelScope.launch {
            commIdentity.device.collect { d ->   // COMMIDENT-2026-10-09: this radio through the Comm API
                _myDevice.value = d
                val num = d?.id?.toIntOrNull()
                // [2026-07-01] Event-driven identity: device-default, radio overrides on connect.
                // Guard: never reassign identity mid-recording (record-lock).
                if (!_trackActive.value) {
                    _myCartId.value = if (num != null) "!%08x".format(num) else "!phone"
                }
            }
        }
        // HEARDBOARD-2026-10-14: the cart board's ONLY radio input -- a message received from a radio (MemberHeard). Nothing at start-up.
        viewModelScope.launch {
            // COTBOARD-2026-10-15 (T5.4a-2): the board reads the CoT message (Received); MemberHeard no longer feeds it
            commMessaging.events.collect { e -> if (e is com.grouptrack.comm.CommEvent.Received) onBoardMessage(e.message) }
        }
        // GROUPOFF-2026-10-15 (Fred 10-15 11:30): ANDROID ONLY (group awareness OFF) clears the other carts on EVERY copy's board at once --
        // the board's own group-off rule runs the moment the switch goes off (no radio message will come to trigger it).
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { GroupMode.on.value }.collect { on ->
                if (!on) {
                    boardRecompute(System.currentTimeMillis())
                    android.util.Log.i("TICKSHADOW", "GROUPOFF-2026-10-15: group awareness off -- board now ${boardCarts.size} cart(s)")
                }
            }
        }
        // ROLESHOW-2026-10-15: the moment this tablet checks in or ENDs, re-set every board cart's role (the same rule as cartFromMessage)
        // and republish -- otherwise a cart keeps its old role until its next message.
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { checkIn.value != null }.collect { checkedIn ->
                val myNum = myRadioNum()
                for ((id, c) in boardCarts) {
                    val num = id.removePrefix("!").toLongOrNull(16)?.toInt() ?: continue   // "!phone" (no radio): no role to set
                    val role = if (!checkedIn) "" else takRideRole(num).ifBlank { if (num == myNum) (checkIn.value?.role ?: "") else "" }
                    if (role != c.rideRole) boardCarts[id] = c.copy(rideRole = role)
                }
                boardRecompute(System.currentTimeMillis())
                android.util.Log.i("TICKSHADOW", "ROLESHOW-2026-10-15: checked in = $checkedIn -- roles " + (if (checkedIn) "shown" else "hidden"))
            }
        }
        // FAILCLEAR-2026-10-15 (T5.4e, Fred 10-14 22:21 / 10-15 14:24): the 3rd failed reconnect (not talking, attempts >= 3 -- the power-cycle
        // banner's condition) = ANDROID ONLY: the other carts and the lead are cleared; group awareness stays ON so the recovery keeps
        // trying, and the carts come back as they are heard. Once per failure -- re-armed when the radio talks again.
        viewModelScope.launch {
            var clearedForThisFailure = false
            commLink.health.collect { h ->
                if (h.talking) { clearedForThisFailure = false; return@collect }
                if (!clearedForThisFailure && h.attempts >= 3 && GroupMode.on.value) {
                    clearedForThisFailure = true
                    enterAndroidMode()   // ANDROIDMODE1: the tick's stores, the lead, the lead track; the screen keeps my cart only
                    reportedLeadId = null
                    val mine = resolveMyCartId()
                    boardCarts.keys.retainAll { it == mine }; boardSpeedStart.keys.retainAll { it == mine }; boardSpeedLast.keys.retainAll { it == mine }
                    boardRecompute(System.currentTimeMillis())
                    android.util.Log.i("ANDROIDMODE", "FAILCLEAR-2026-10-15: 3 failed reconnects -- Android only (other carts cleared, group awareness stays on; board ${boardCarts.size} cart(s))")
                }
            }
        }
        // COMMNAMES-2026-10-11: the name table (load, the node-list names) moved to the comm side (MeshCommLink).
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

    fun setHudMode(mode: HudMode) {   // GRPMODE-2026-10-13: GROUP while group awareness is off -> the popup, MY CART stays
        if (mode == HudMode.GROUP && !GroupMode.on.value) { GroupMode.askToActivate(); _hudMode.value = HudMode.MY_CART; return }
        _hudMode.value = mode
    }
    /** GRPMODE-2026-10-13 (Fred): group awareness on/off, from Settings -- any time. The comm side is told once (setActive). */
    fun setGroupMode(on: Boolean) {
        GroupMode.set(appContext, on)
        viewModelScope.launch { commLink.setActive(on) }
        if (!on && _hudMode.value == HudMode.GROUP) _hudMode.value = HudMode.MY_CART
        if (!on) { roleReportJob?.cancel(); GrpAwarenessLauncher.close(); enterAndroidMode() }   // ANDROIDMODE1-2026-10-14
    }

    /**
     * ANDROIDMODE1-2026-10-14 (tick replacement task 1, Fred 10-14): ANDROID MODE starts EMPTY -- everything the tick keeps about other carts
     * is cleared, so nothing from before can come back. Android mode = group awareness off (Settings; later also 3 failed automatic
     * reconnects). NOT called on a plain radio disconnect: Android DATA keeps the held carts until the radio reconnects.
     */
    private fun enterAndroidMode() {
        val mine = resolveMyCartId()
        val myNodes = _convoyState.value.nodes.filter { it.isMyCart || it.nodeId == mine }
        setLeadCart(null)                                   // no group, no lead (Fred 10-14)
        currentLeadNodeId = null
        _routeTrailSegments.value = emptyList()             // the drawn lead track
        lastLeadLat = null; lastLeadLon = null
        lastKnownPosition.keys.retainAll { it == mine }
        lastNodePositions.keys.retainAll { it == mine }
        nodeLastLat.keys.retainAll { it == mine }
        nodeLastLon.keys.retainAll { it == mine }
        nodeSpeedWindowStart.keys.retainAll { it == mine }
        nodeLastComputedSpeed.keys.retainAll { it == mine }
        _convoyState.value = if (myNodes.isEmpty()) ConvoyEngine.ConvoyState.empty()
            else ConvoyEngine.compute(nodes = myNodes, myCartId = mine, nowMs = System.currentTimeMillis())
        android.util.Log.i("ANDROIDMODE", "ANDROIDMODE1-2026-10-14: Android mode -- other carts cleared (kept my cart: ${myNodes.size})")
    }
    fun onMarkerTapped(node: ConvoyNode) { _selectedNode.value = node; _hudMode.value = HudMode.NODE }
    fun dismissNodeHud() { _selectedNode.value = null; _hudMode.value = if (GroupMode.on.value) HudMode.GROUP else HudMode.MY_CART }   // GRPMODE-2026-10-13

    private val _currentIntervalSecs = MutableStateFlow(5)
    val currentIntervalSecs: StateFlow<Int> = _currentIntervalSecs.asStateFlow()

    // CLEANDEAD-2026-10-14: setGpsInterval removed -- the retired HUD slider's writer (slider under if(false) since 09-29).

    // === GPSPANEL-2026-09-29 (Fred): "Sent every XX seconds" -- the location interval, written to the radio ONCE ===
    // Replaces setGpsInterval (the retired HUD slider), which wrote gps_update_interval -- the GPS FIX rate, not the
    // SENDING rate -- on every drag step, as a two-field PositionConfig that wiped the radio's other position settings.
    // This reads the radio's FULL current position section and changes ONLY broadcast_smart_minimum_interval_secs
    // (how often it sends its location while moving; the ride configuration's 4 s), with the configurator's own
    // awaited calls (radioOps): begin -> write -> commit, then 3 s -> disconnect -> 10 s -> reconnect (Fred 09-29).
    // Runs on viewModelScope so closing the panel cannot cut it off between the disconnect and the reconnect.
    // FUTURE (Fred 09-29): the LEADER's panel request broadcast in a REPORT (as the ride roles are) and applied by
    //   every cart's tablet to its own radio -- the whole group changes together; only a Leader's request honoured.
    // FUTURE (Fred 09-29): the interval set by group VOLUME at check-in (carts -> seconds, from collected field data).
    private val _gpsApply = MutableStateFlow<String?>(null)          // CODE RULE 1: null = nothing to report (idle)
    val gpsApply: StateFlow<String?> = _gpsApply.asStateFlow()
    private val _gpsApplyBusy = MutableStateFlow(false)
    val gpsApplyBusy: StateFlow<Boolean> = _gpsApplyBusy.asStateFlow()
    fun clearGpsApplyStatus() { if (!_gpsApplyBusy.value) _gpsApply.value = null }

    fun applyLocationInterval(secs: Int) {   // SLEEPCOMM-2026-10-14: the unused UIViewModel parameter removed
        if (_gpsApplyBusy.value) return
        if (secs !in 3..10) { _gpsApply.value = "Enter a number from 3 to 10."; return }
        if (link.value != com.grouptrack.comm.Link.CONNECTED) { _gpsApply.value = "Connect your radio first (GRP Awareness)."; return }
        // COMMREPORT-2026-10-12 (P4.4): WHAT -- moving every `secs` s, the ride's stationary floor (the GroupTrack default's standalone
        // positionBroadcastSecs, STATIONARY30), the field-tuned reconnect timing. The comm side does HOW and tells the steps;
        // the words below are the ones this panel always showed. Runs on viewModelScope so closing the panel cannot cut it off.
        val stationary = runCatching {
            org.json.JSONObject(appContext.assets.open("grouptrack_default.json").bufferedReader().use { it.readText() })
                .getJSONObject("network").getJSONObject("standalone").getInt("positionBroadcastSecs")
        }.getOrNull() ?: run { _gpsApply.value = "The GroupTrack default settings could not be read -- nothing written."; return }
        _gpsApplyBusy.value = true
        viewModelScope.launch {
            try {
                val request = com.grouptrack.comm.ReportingRequest(secs, null, stationary,
                    com.grouptrack.comm.ReportingRequest.Method.BROADCAST_TO_RIDE_GROUP,
                    settleWaitSecs = GpsReconnectTiming.waitSecs, reconnectGapSecs = GpsReconnectTiming.gapSecs)
                val r = commMessaging.setReporting(request) { step ->
                    _gpsApply.value = when (step) {
                        com.grouptrack.comm.ReportingStep.WRITING -> "Writing every $secs s to the radio..."
                        com.grouptrack.comm.ReportingStep.WRITTEN -> { _currentIntervalSecs.value = secs; "Written. Disconnecting in 3 s..." }
                        com.grouptrack.comm.ReportingStep.RESTARTING -> "Radio restarting. Reconnecting in 20 s..."
                        com.grouptrack.comm.ReportingStep.RECONNECTING_AGAIN -> "Reconnected. Reconnecting once more..."
                        com.grouptrack.comm.ReportingStep.UNCHANGED -> "Already every $secs s with the ride's settings -- nothing to change."
                        com.grouptrack.comm.ReportingStep.DONE -> "Done -- location sent every $secs s while moving."
                    }
                }
                if (r is com.grouptrack.comm.CommResult.Failed) _gpsApply.value = when (r.reason) {
                    "NO_RADIO" -> "Connect your radio first (GRP Awareness)."
                    "NO_DEFAULT" -> "The GroupTrack default settings could not be read -- nothing written."
                    else -> "Could not apply: ${r.reason}"
                }
            } finally {
                _gpsApplyBusy.value = false
            }
        }
    }

    fun removeNode(nodeId: String) {
        _selectedNode.value = null
        _hudMode.value = HudMode.GROUP
        val current = _convoyState.value
        boardCarts.remove(nodeId)   // BOARDSHOW2-2026-10-15: off the board too, or the next payload redraws it
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
                    onMyTabletFix(lat, lon, null)   // MYFIX-2026-10-14
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
    // SLEEPCOMM-2026-10-14 (P5.3): sleep / wake through the comm side (was ConvoyScreen -> UIViewModel.setDeviceAddress). Same calls
    // underneath: the radio dropped ("n") on sleep, the same radio put back on wake. Fred 10-14: the app's sleep drops
    // Bluetooth at 10 / 20 min on purpose, before the T1000-E's own sleep. Held in memory only, as before.
    private var radioBeforeSleep = ""
    fun sleepRadio() {
        radioBeforeSleep = commLink.selectedRadioId.value ?: ""
        viewModelScope.launch { commLink.disconnect() }
        android.util.Log.i("ConvoyScreen", "SLEEP: BLE disconnected")
    }
    fun wakeRadio() {
        val r = radioBeforeSleep
        if (r.isNotBlank()) viewModelScope.launch { commLink.resume(r) }
        android.util.Log.i("ConvoyScreen", "WAKE: BLE reconnecting")
    }
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
        // CARTRESET-2026-09-30 (Fred): END resets every cart to Active, ride or no ride -- nothing hidden carries over.
        _removedCarts.value = emptyMap()
        viewModelScope.launch { settingsRepository.clearRemovedCarts() }
        endingCheckIn = checkIn.value; checkIn.value = null   // CHECKIN-2026-09-27: the next recording needs a new check-in
        pendingTempFile = gpsService?.stopTrack()
    }

    fun deleteTempTrack() {
        endingCheckIn = null; pendingSurvey = null   // CHECKIN-2026-09-27
        val temp = pendingTempFile ?: return
        if (temp.exists()) {
            temp.delete()
            android.util.Log.d("ConvoyVM", "Deleted temp track: ${temp.name}")
        }
        pendingTempFile = null
        lastGpsLat = null; lastGpsLon = null; _distanceMiles.value = 0.0; _routeRecording.value = false
    }
    /** CHECKIN-2026-09-27: set by the end-of-ride form just before finalizeTrack; written only if the track exists.
     *  CODE RULE 1: null = no survey (a private ride, or not part of a ride). */
    var pendingSurvey: ConvoyRideStore.RideSurveyInput? = null

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
            // CHECKIN-2026-09-27: the survey is written only when the track exists (INSERT, or DROP/ALIAS onto an existing one).
            pendingSurvey?.let { sv ->
                if (outcome != com.geeksville.mesh.convoy.SpatialDbManager.AddOutcome.NO_GEOMETRY &&
                    outcome != com.geeksville.mesh.convoy.SpatialDbManager.AddOutcome.ERROR) {
                    val tid = com.geeksville.mesh.convoy.SpatialDbManager.trackIdForGpxText(text)
                    if (tid != null) ConvoyRideStore.saveSurvey(tid, sv)
                    else android.util.Log.w("ConvoyVM", "ENDRIDE: no track id -- survey not saved")
                }
            }
            pendingSurvey = null; endingCheckIn = null   // CHECKIN-2026-09-27
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

    /**
     * MYFIX-2026-10-14 (tick replacement T2): my cart follows EACH tablet GPS fix while the tablet is its source -- Android mode
     * (group awareness off) or Android data (radio not talking). With the radio talking, my cart stays on the radio (the tick).
     * CODE RULE 1: loc null = the recording service's fix (position only) -- speed and heading keep the tick's last values.
     */
    private fun onMyTabletFix(lat: Double, lon: Double, loc: android.location.Location?) {
        onBoardMyFix(lat, lon, loc)   // HEARDBOARD-2026-10-14
        if (boardShows) return   // BOARDSHOW2-2026-10-15: the board owns my cart (one report per interval); the old per-fix screen write is skipped
        if (GroupMode.on.value && radioTalkingNow) return
        if (lat == 0.0 && lon == 0.0) return
        val st = _convoyState.value
        val mine = st.nodes.firstOrNull { it.isMyCart } ?: return   // not placed yet -- the tick places it
        val now = System.currentTimeMillis()
        val moved = mine.copy(
            latitude = lat, longitude = lon, lastSeenMs = now,
            speed_mph = loc?.let { if (it.hasSpeed()) it.speed * 2.23694f else null } ?: mine.speed_mph,
            heading_deg = loc?.let { if (it.hasBearing()) it.bearing else null } ?: mine.heading_deg,
            tabletLat = lat, tabletLon = lon, tabletFixMs = loc?.let { if (it.time > 0L) it.time else now } ?: now,
        )
        val nodes = st.nodes.map { if (it.nodeId == mine.nodeId) moved else it }
        _convoyState.value = ConvoyEngine.compute(
            nodes = nodes,
            myCartId = resolveMyCartId(),
            nowMs = now,
            leadLocked = _leadLockedFlag,
            lockedLeadNodeId = lockedLeadNodeId,
            tailNodeId = nodes.firstOrNull { it.rideRole == "tail_gunner" }?.nodeId
        )
    }

    // === HEARDBOARD-2026-10-14 (tick replacement T3+T4): THE CART BOARD -- fed by RECEIVED PAYLOADS only, compared with the tick, shown nowhere yet ===
    // The board's fields are declared before init (beside the tick's maps) -- see there.

    /** A message was received from a radio (MemberHeard). recompute false = part of a batch (the caller recomputes once). */
    private fun onBoardHeard(m: com.grouptrack.comm.Member, recompute: Boolean) {
        if (!GroupMode.on.value) return                                  // Android mode: no radios (as the tick)
        val num = m.id.toIntOrNull() ?: return                           // mesh member ids are node numbers (as the tick)
        val myNum = myRadioNum()
        if (num == myNum && !commLink.health.value.talking) return       // radio not talking: my cart is the tablet's (as the tick)
        val id = "!%08x".format(num)
        val now = System.currentTimeMillis()
        val cart = cartFromMember(m, now, myNum, getPhoneLocation(), { boardCarts[it] }, boardSpeedStart, boardSpeedLast, shadow = true)
        if (cart == null) boardCarts.remove(id) else boardCarts[id] = cart
        boardHeard++
        if (recompute) boardRecompute(now)
    }

    /** A tablet GPS fix (T2's hook). CODE RULE 1: loc null = the recording service's fix (position only). */
    private fun onBoardMyFix(lat: Double, lon: Double, loc: android.location.Location?) {
        if (GroupMode.on.value && commLink.health.value.talking) return  // radio talking: my cart is the radio's (as the tick)
        if (lat == 0.0 && lon == 0.0) return
        val now = System.currentTimeMillis()
        // BOARDSHOW2-2026-10-15 (Fred 09:49): an Android fix is the tablet's sensor, not a received message -- my cart gets ONE board report
        // per reporting interval, from the latest fix, like every other cart. CODE RULE 1: interval null = not read from the radio yet -> 6 s.
        val everyMs = (reportingNowSecs.value ?: 6) * 1000L
        if (now - boardMyLastMs < everyMs) return
        boardMyLastMs = now
        val alt = ((loc?.altitude ?: 0.0) * 3.28084).toInt()
        val spd = (loc?.speed ?: 0f) * 2.23694f
        val hdg = loc?.bearing ?: 0f
        var cart = myTabletCart(lat, lon, alt, spd, hdg, loc, now)
        if (loc == null) {
            val prevMe = boardCarts[cart.nodeId]
            cart = cart.copy(speed_mph = prevMe?.speed_mph ?: 0f, heading_deg = prevMe?.heading_deg ?: 0f,
                tabletLat = lat, tabletLon = lon, tabletFixMs = now)
        }
        boardCarts[cart.nodeId] = cart
        boardMyFixes++
        boardRecompute(now)
    }

    /** EVERY message: status for every cart from the time since it was last heard, order, lead / tail, distances. No timer. */
    private fun boardRecompute(now: Long) {
        val mine = resolveMyCartId()
        if (mine != "!phone") boardCarts.remove("!phone")                // my radio identified: the tablet-only id is gone
        // Group awareness OFF = Android mode = my cart only -- a board rule, applied on EVERY payload (Fred 13:09).
        if (!GroupMode.on.value && boardCarts.keys.any { it != mine }) {
            boardCarts.keys.retainAll { it == mine }; boardSpeedStart.keys.retainAll { it == mine }; boardSpeedLast.keys.retainAll { it == mine }
            android.util.Log.i("TICKSHADOW", "board: group awareness off -- my cart only (${boardCarts.size})")
        }
        val nodes = boardCarts.values.toList()
        // LEADBOARD-2026-10-15 (T5.4b, ROLETICK moved): a cart reporting Leader is the lead -- set through setLeadCart only when it differs.
        // No Leader reported -> the lead keeps its value (as before). The tail comes from roles below (tail_gunner), as before.
        val reportedLeader = nodes.firstOrNull { it.rideRole == "leader" }?.nodeId
        if (reportedLeader != null) {
            if (reportedLeader != lockedLeadNodeId) {
                setLeadCart(reportedLeader)
                android.util.Log.i("ROLEREPORT", "LEADBOARD-2026-10-15 applied at ${System.currentTimeMillis()}: lead = " + reportedLeader)
            }
            reportedLeadId = reportedLeader   // LEADCLEAR-2026-10-15
        } else if (reportedLeadId != null && lockedLeadNodeId == reportedLeadId) {
            // LEADCLEAR-2026-10-15 (Fred 13:19): the Leader dropped the role and nobody claims it -> NO lead (a REC pick is never cleared here)
            setLeadCart(null)
            android.util.Log.i("ROLEREPORT", "LEADCLEAR-2026-10-15: no Leader reported any more -- lead " + reportedLeadId + " cleared")
            reportedLeadId = null
        }
        boardState = if (nodes.isEmpty()) ConvoyEngine.ConvoyState.empty() else ConvoyEngine.compute(
            nodes = nodes, myCartId = mine, nowMs = now,
            leadLocked = _leadLockedFlag, lockedLeadNodeId = lockedLeadNodeId,
            tailNodeId = nodes.firstOrNull { it.rideRole == "tail_gunner" }?.nodeId)
        if (boardShows) {   // BOARDSHOW2-2026-10-15: the screen draws from the board -- updated only when something VISIBLE changed
            val sig = boardState.nodes.sortedBy { it.nodeId }.map { listOf<Any?>(it.nodeId, it.latitude, it.longitude, it.status, it.isLead, it.callsign, it.rideRole) }
            if (sig != boardShownSig) { boardShownSig = sig; _convoyState.value = boardState; boardPublished++ }
            // PANELBOARD-2026-10-15 (T5.4d, the tick's refresh moved): an open cart panel shows that cart's latest values on every payload
            if (_hudMode.value == HudMode.NODE && _selectedNode.value != null) {
                _selectedNode.value = boardState.nodes.firstOrNull { it.nodeId == _selectedNode.value?.nodeId }
            }
        }
    }

    /** After each tick: the board against the tick. Logs only a CHANGE of the difference (and every 5 min the counts). */
    private fun boardCompare(tickState: ConvoyEngine.ConvoyState) {
        val bs = boardState
        val tb = tickState.nodes.associateBy { it.nodeId }
        val bb = bs.nodes.associateBy { it.nodeId }
        val key = mutableListOf<String>(); val text = mutableListOf<String>()
        for (id in tb.keys - bb.keys) { key += "only-tick $id"; text += "only on the tick: ${tb[id]?.callsign} ($id)" }
        for (id in bb.keys - tb.keys) { key += "only-board $id"; text += "only on the board: ${bb[id]?.callsign} ($id)" }
        for (id in tb.keys intersect bb.keys) {
            val x = tb.getValue(id); val y = bb.getValue(id)
            if (x.status != y.status) { key += "status $id ${x.status} ${y.status}"; text += "status ${x.callsign}: tick ${x.status}, board ${y.status}" }
            val meters = ConvoyEngine.haversineMiles(x.latitude, x.longitude, y.latitude, y.longitude) * 1609.34f
            if (meters > 15f) { key += "pos $id"; text += "position ${x.callsign}: ${meters.toInt()} m apart" }
            if (x.isLead != y.isLead) { key += "lead $id"; text += "lead ${x.callsign}: tick ${x.isLead}, board ${y.isLead}" }
        }
        val k = if (key.isEmpty()) "MATCH" else key.sorted().joinToString("|")
        shadowTicks++
        if (k != shadowLastKey || shadowTicks % 60 == 0) {
            android.util.Log.i("TICKSHADOW", "board: " + (if (text.isEmpty()) "MATCH" else text.joinToString("; ")) +
                " (carts: tick ${tb.size}, board ${bb.size}; since the last tick: $boardHeard heard, $boardMyFixes my fixes, $boardPublished screen updates)")
            shadowLastKey = k
        }
        boardHeard = 0; boardMyFixes = 0; boardPublished = 0
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
        // LINKFIX-2026-10-03: is the radio talking? (see the fields at livePhoneLocation)
        // COMMNAMES-2026-10-11: the at-connect names moved to the comm side (MeshCommLink).
        // COMMHEALTH2-2026-10-11 (Fred 10-11): keeping the link is the comm side's job; GroupTrack reports it. talking = the comm side's
        // health (the 45 s hand-off heartbeat, the 120 s traffic rule and the recovery run there, unchanged).
        radioTalkingNow = GroupMode.on.value && commLink.health.value.talking   // GRPMODE-2026-10-13: off = this tablet only
        GrpAwarenessLauncher.talking.value = radioTalkingNow
        // The power-cycle banner only while riding with a group (other carts in the tick array) -- GroupTrack's fact.
        val othersInTick = _convoyState.value.nodes.any { !it.isMyCart }
        GrpAwarenessLauncher.ridingGroup.value = othersInTick
        val nodes: List<ConvoyNode> = if (_simulationMode.value) {
            ConvoySimulation.tick(nowMs)
        } else {
            readLiveNodes(nowMs)
        }

        // V2.4: Lead assigned manually via dialog before ride start -- no auto-lock
        // Lead assignment REMOVED from tick — only through setLeadCart()
        // Lead assigned at RECORD time in startGroupTrack() or via cart HUD
        // ROLETICK-2026-09-28 (Fred): LEAD AND TAIL FROM THE RIDE ROLES, every tick -- nothing else in the tick changes.
        //  - A node reporting Leader is the lead: set through setLeadCart (the existing path -- all three lead flags, and the
        //    lead track's reset) ONLY when it differs from the current lead. No Leader reported -> the lead keeps its value.
        //  - A node reporting Tail gunner is the tail. None -> null: the engine's own (the last active cart), as before.
        // LEADBOARD-2026-10-15: the board sets the lead from Leader reports now; the tick only when it drives the screen (boardShows false)
        if (!boardShows) nodes.firstOrNull { it.rideRole == "leader" }?.nodeId?.let { lid ->
            if (lid != lockedLeadNodeId) {
                setLeadCart(lid)
                convoyLog("ROLETICK: lead = " + (nodes.firstOrNull { it.nodeId == lid }?.callsign ?: lid) + " (reports Leader)")
                android.util.Log.i("ROLEREPORT", "ROLETICK applied at ${System.currentTimeMillis()}: lead = " + lid)   // ROLECHANGE-2026-09-29
            }
        }
        val tailNodeId: String? = nodes.firstOrNull { it.rideRole == "tail_gunner" }?.nodeId

        val state = ConvoyEngine.compute(
            nodes = nodes,
            myCartId = resolveMyCartId(),
            nowMs = nowMs,
            leadLocked = _leadLockedFlag,
            lockedLeadNodeId = lockedLeadNodeId,
            tailNodeId = tailNodeId
        )
        if (!boardShows) _convoyState.value = state   // BOARDSHOW2-2026-10-15: the tick no longer drives the screen (its state still feeds its other duties)
        boardCompare(state)   // HEARDBOARD-2026-10-14

        // ── Feed radio position to GPS service if no hardware GPS ────────────
        // RADIOTRACK-2026-10-15: my radio's message feeds the recorder now; the tick only when it drives the screen (boardShows false)
        if (!boardShows && gpsService?.useRadioGps == true && _routeRecording.value) {
            val myCart = state.nodes.firstOrNull { it.isMyCart }
            if (myCart != null && myCart.latitude != 0.0 && myCart.longitude != 0.0) {
                gpsService?.onRadioPosition(myCart.latitude, myCart.longitude, myCart.altitude_m.toDouble())
            }
        }
        // ── Debug log every tick ──────────────────────────────────────────────
        if (_trackActive.value) {
            val leadOut = state.lead?.callsign ?: "NONE"
            val trackFrom = currentLeadNodeId ?: "NONE"
            convoyLog("tick | lead=$leadOut | trackFrom=$trackFrom | locked=$_leadLockedFlag")   // LEADCLEAN-2026-09-29
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

        if (!boardShows && _hudMode.value == HudMode.NODE && _selectedNode.value != null) {   // PANELBOARD-2026-10-15: the board refreshes it now
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

    // LINKFIX-2026-10-03 (Fred): in Android-only mode the other carts stay where the tick array last had them (only if they
    // were in it) and move again when the radio comes back. Nothing is invented or stored.
    private fun heldCarts(radioKnown: Boolean): List<ConvoyNode> {
        if (!radioKnown) return emptyList()
        val mine = resolveMyCartId()
        return _convoyState.value.nodes.filter { !it.isMyCart && it.nodeId != mine && (it.latitude != 0.0 || it.longitude != 0.0) }
    }

    /**
     * HEARDBOARD-2026-10-14 (tick replacement T4, Fred 10-14 option A): the tick's per-radio display rules, MOVED here unchanged from
     * readLiveNodes so the tick and the cart board apply the SAME rules. Returns the cart to show, or null = not shown.
     * prev = the caller's previous cart for an id (the tick: its last tick; the board: the board). speedStart / speedLast = the
     * caller's own 60-s speed windows. shadow = true (the board while the tick still runs): no side effects -- the tick's
     * last-known map, the tablet GPS start and the debug file stay the tick's.
     * CODE RULE 1: myNum null = no radio identified yet; myTablet null = the tablet has no fix.
     */
    private fun cartFromMember(
        m: com.grouptrack.comm.Member, nowMs: Long, myNum: Int?, myTablet: android.location.Location?,
        prev: (String) -> ConvoyNode?,
        speedStart: MutableMap<String, Triple<Long, Double, Double>>, speedLast: MutableMap<String, Float>,
        shadow: Boolean,
    ): ConvoyNode? {
        val num = m.id.toIntOrNull() ?: return null   // HEARDSWITCH-2026-10-13: mesh member ids are node numbers
        // SLIDERREAD-2026-09-25 (Fred): the hours come from the Settings slider (admissionWindowHours).
        if (num != myNum && nowMs - m.lastHeardMs > admissionWindowHours * 3_600_000L) {
            return null
        }
        // RADIONAMES-2026-10-03 (Fred): the long name LAST USED from the radio name table; the node database's first-heard name
        // only for a radio not yet in the table.
        val callsign = m.callsign   // HEARDSWITCH-2026-10-13: the same rule (name table, else long, short, !num) -- applied on the comm side
        val nodeId = "!%08x".format(num)
        if (nodeId in _removedCarts.value && num != myNum) return null   // CARTACTIVE-2026-09-30: removed on this tablet
        val hasPos = m.latitude != null && m.longitude != null   // HEARDSWITCH-2026-10-13: null = no position (was 0/0)
        val latLon = if (hasPos) {
            val lat = m.latitude ?: 0.0
            val lon = m.longitude ?: 0.0
            // Reject stale rebroadcast packets: pos.time is the GPS fix timestamp
            // (Unix seconds). If it predates ride start it is a relay-rebroadcast
            // of a packet the node sent from a prior location — use lastKnownPosition.
            val fixTimeMs = m.fixTimeMs ?: 0L
            val isStale = rideStartTimeMs > 0L && fixTimeMs > 0L && fixTimeMs < rideStartTimeMs
            if (isStale) {
                if (!shadow) convoyLog("STALE POS REJECTED: node=$callsign fixTime=$fixTimeMs rideStart=$rideStartTimeMs age=${(rideStartTimeMs - fixTimeMs)/1000}s lat=$lat lon=$lon → using lastKnown")
                // RECSTALE3-2026-09-29 (Fred): checked in = here. The cart keeps its position from the PREVIOUS TICK (the
                // group as shown a moment ago); not in it -> its own reported position. Never the last-known map, never dropped.
                prev(nodeId)
                    ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
                    ?.let { Pair(it.latitude, it.longitude) }
                    ?: Pair(lat, lon)
            } else {
                if (!shadow) lastKnownPosition[nodeId] = Pair(lat, lon)
                if (_trackActive.value) {
                    if (!shadow) convoyLog("POS ACCEPTED: node=$callsign fixTime=$fixTimeMs lat=$lat lon=$lon")
                }
                Pair(lat, lon)
            }
        } else {
            // LINKFIX-2026-10-03 (Fred): no position from this radio. Others: their PREVIOUS TICK position (the tick array,
            // never a stored map), else not shown this tick. MY radio: the tablet's GPS (switched on here).
            val prevTick = prev(nodeId)
                ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let { Pair(it.latitude, it.longitude) }
            if (num != myNum && prevTick != null) {
                prevTick
            } else if (num != myNum) {
                return null
            } else {
                if (!shadow) startPhoneGps()
                val phoneLoc = getPhoneLocation()
                if (phoneLoc != null && phoneLoc.latitude != 0.0 && phoneLoc.longitude != 0.0) {
                    if (!shadow) convoyLog("ZERO POS: node=$callsign — using phone GPS lat=${phoneLoc.latitude} lon=${phoneLoc.longitude}")
                    Pair(phoneLoc.latitude, phoneLoc.longitude)
                } else {
                    if (!shadow) convoyLog("ZERO POS: node=$callsign — no lastKnown, no phone GPS, dropping node this tick")
                    return null
                }
            }
        }
        val lastSeenMs = m.lastHeardMs
        return ConvoyNode(
            nodeId = nodeId,
            callsign = callsign,
            // TICKDATA-2026-09-28: this tablet's own radio carries the check-in's role; other radios' roles arrive from their
            // reports in the next step ("" until then = bare radio). Display only.
            // OWNROLE-2026-09-28 (Fred): EVERY radio node -- mine included -- takes its role from the store first; the check-in
            // is only the fallback for my own node (a Rider, who sends no report).
            rideRole = takRideRole(num).ifBlank { if (num == myNum) (checkIn.value?.role ?: "") else "" },
            latitude = latLon.first,
            longitude = latLon.second,
            altitude_m = ((m.altitudeM ?: 0) * 3.28084f).toInt(),
            speed_mph = run {
                // 60-second fixed window: distance from window start to now * 60 = mph
                val nowMs = System.currentTimeMillis()
                val windowStart = speedStart[nodeId]
                if (windowStart == null) {
                    // Open first window
                    speedStart[nodeId] = Triple(nowMs, latLon.first, latLon.second)
                    0f
                } else {
                    val (startMs, startLat, startLon) = windowStart
                    val elapsedMs = nowMs - startMs
                    if (elapsedMs >= 60_000L) {
                        // Window complete — compute speed and reset
                        val distMiles = ConvoyEngine.haversineMiles(startLat, startLon, latLon.first, latLon.second)
                        val speed = distMiles * 60f  // miles per minute * 60 = mph
                        speedLast[nodeId] = speed
                        speedStart[nodeId] = Triple(nowMs, latLon.first, latLon.second)
                        speed
                    } else {
                        // Hold last computed speed until window completes
                        speedLast[nodeId] ?: 0f
                    }
                }
            },
            heading_deg = m.headingDeg ?: 0f,
            battery_pct = m.batteryPct ?: 0,
            // HEARDSWITCH-2026-10-13 (Fred 10-12): MY cart records both fixes (shown as before; the display rule comes with the tick replacement).
            radioLat = if (num == myNum) m.latitude else null, radioLon = if (num == myNum) m.longitude else null,
            radioFixMs = if (num == myNum) m.fixTimeMs else null,
            tabletLat = if (num == myNum) myTablet?.latitude else null, tabletLon = if (num == myNum) myTablet?.longitude else null,
            tabletFixMs = if (num == myNum) myTablet?.let { if (it.time > 0L) it.time else nowMs } else null,
            lastSeenMs = lastSeenMs,
            cotType = "a-f-G-U-C",
            timestampUtc = java.time.Instant.ofEpochMilli(lastSeenMs).toString()
        )
    }

    /** COTBOARD-2026-10-15 (T5.4a-2): a CoT message was received -- the board's input (the twin of onBoardHeard, which T5.5 removes). */
    private fun onBoardMessage(msg: com.grouptrack.comm.CommMessage) {
        if (!GroupMode.on.value) return                                  // Android only: no radios
        // the radio number from the uid (MESHTASTIC-!<node id>, the comm side's rule). CODE RULE 1: null = not a radio we know how to read
        val num = msg.uid.removePrefix("MESHTASTIC-!").toLongOrNull(16)?.toInt() ?: return
        val myNum = myRadioNum()
        if (num == myNum && !commLink.health.value.talking) return       // radio not talking: my cart is the tablet's (as before)
        val id = "!%08x".format(num)
        val now = System.currentTimeMillis()
        val cart = cartFromMessage(msg, num, now, myNum, getPhoneLocation(), { boardCarts[it] }, boardSpeedStart, boardSpeedLast)
        if (cart == null) boardCarts.remove(id) else boardCarts[id] = cart
        // RADIOTRACK-2026-10-15 (T5.4c, the tick's feed moved): my radio's NEW fix -> the track recorder (tablet without GPS, REC on), altitude in METRES
        val rp = msg.point
        if (num == myNum && rp != null && msg.timeMs != radioTrackLastFixMs && gpsService?.useRadioGps == true && _routeRecording.value) {
            radioTrackLastFixMs = msg.timeMs
            gpsService?.onRadioPosition(rp.latitude, rp.longitude, rp.haeM ?: 0.0)   // CODE RULE 1: haeM null = no altitude sent -> 0
        }
        boardHeard++
        boardRecompute(now)
    }

    /**
     * COTBOARD-2026-10-15 (T5.4a-2, Plan v3 4a/4d): the board's display rules READING CoT -- the same rules as cartFromMember (which the tick
     * keeps until T5.5 removes both), CoT fields in. Board only: no tick logs, no last-known map, no GPS start.
     * Speed: the radio's ground_speed when sent, else the 60-s window as before. Heard = the moment the message arrived.
     * CODE RULE 1 (return): null = not shown (removed on this tablet, or no position and nothing to fall back on).
     */
    private fun cartFromMessage(
        msg: com.grouptrack.comm.CommMessage, num: Int, nowMs: Long, myNum: Int?, myTablet: android.location.Location?,
        prev: (String) -> ConvoyNode?,
        speedStart: MutableMap<String, Triple<Long, Double, Double>>, speedLast: MutableMap<String, Float>,
    ): ConvoyNode? {
        val nodeId = "!%08x".format(num)
        if (nodeId in _removedCarts.value && num != myNum) return null   // CARTACTIVE-2026-09-30: removed on this tablet
        val p = msg.point
        val prevPos = prev(nodeId)?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let { Pair(it.latitude, it.longitude) }
        val latLon: Pair<Double, Double> = if (p != null) {
            // stale rebroadcast (fix older than the ride start): keep the previous position, else the reported one (RECSTALE3)
            val isStale = rideStartTimeMs > 0L && msg.timeMs > 0L && msg.timeMs < rideStartTimeMs
            if (isStale) prevPos ?: Pair(p.latitude, p.longitude) else Pair(p.latitude, p.longitude)
        } else if (num != myNum) {
            prevPos ?: return null                                       // LINKFIX: others -- the previous position, else not shown
        } else {
            val phone = myTablet ?: return null                          // MY radio without a position: the tablet's GPS
            if (phone.latitude == 0.0 && phone.longitude == 0.0) return null
            Pair(phone.latitude, phone.longitude)
        }
        val windowMph = run {   // the 60-second window, as cartFromMember
            val windowStart = speedStart[nodeId]
            if (windowStart == null) {
                speedStart[nodeId] = Triple(nowMs, latLon.first, latLon.second); 0f
            } else {
                val (startMs, startLat, startLon) = windowStart
                if (nowMs - startMs >= 60_000L) {
                    val speed = ConvoyEngine.haversineMiles(startLat, startLon, latLon.first, latLon.second) * 60f
                    speedLast[nodeId] = speed
                    speedStart[nodeId] = Triple(nowMs, latLon.first, latLon.second)
                    speed
                } else speedLast[nodeId] ?: 0f
            }
        }
        val radioMph = msg.track?.speedMps?.let { (it * 2.23694).toFloat() }   // CODE RULE 1: null = the radio sent no ground_speed
        val mine = num == myNum
        return ConvoyNode(
            nodeId = nodeId,
            callsign = msg.callsign,
            // ROLESHOW-2026-10-15 (Fred 13:07): roles only while THIS tablet is checked in -- otherwise the cart shows without a role
            rideRole = if (checkIn.value == null) "" else takRideRole(num).ifBlank { if (mine) (checkIn.value?.role ?: "") else "" },   // OWNROLE (as cartFromMember)
            latitude = latLon.first,
            longitude = latLon.second,
            altitude_m = ((p?.haeM ?: 0.0) * 3.28084).toInt(),          // feet (the field is misnamed); radios send sea level (COTMSG log)
            speed_mph = radioMph ?: windowMph,
            heading_deg = msg.track?.courseDeg?.toFloat() ?: 0f,
            battery_pct = msg.batteryPct ?: 0,
            radioLat = if (mine) p?.latitude else null, radioLon = if (mine) p?.longitude else null,
            radioFixMs = if (mine) msg.timeMs else null,
            tabletLat = if (mine) myTablet?.latitude else null, tabletLon = if (mine) myTablet?.longitude else null,
            tabletFixMs = if (mine) myTablet?.let { if (it.time > 0L) it.time else nowMs } else null,
            lastSeenMs = nowMs,
            prevSeenMs = prev(nodeId)?.lastSeenMs ?: 0L,   // PANELGAP-2026-10-15: the previous report's time (0 = first report)
            cotType = msg.type,
            timestampUtc = java.time.Instant.ofEpochMilli(nowMs).toString()
        )
    }

    /** HEARDBOARD-2026-10-14: my cart from the TABLET (Android mode / Android data) -- MOVED unchanged from readLiveNodes' phone path so the
     *  tick and the cart board build it the same way. CODE RULE 1: loc null = no live fix (the tick's previous position is used). */
    private fun myTabletCart(lat: Double, lon: Double, alt: Int, spd: Float, hdg: Float, loc: android.location.Location?, nowMs: Long): ConvoyNode = ConvoyNode(
        nodeId = resolveMyCartId(),   // LINKFIX-2026-10-03: the SAME cart id in both modes (my radio's, else !phone)
        // TICKDATA-2026-09-28 (Fred): the rider's callsign (was the device model, e.g. "P50") and the check-in's role.
        callsign = tickCallsign(),
        rideRole = checkIn.value?.role ?: "",
        latitude = lat,
        longitude = lon,
        altitude_m = alt,
        speed_mph = spd,
        heading_deg = hdg,
        battery_pct = 100,
        lastSeenMs = nowMs,
        status = ConvoyStatus.ACTIVE,
        tabletLat = loc?.latitude, tabletLon = loc?.longitude,   // HEARDSWITCH-2026-10-13: my cart's tablet fix (recorded)
        tabletFixMs = loc?.let { if (it.time > 0L) it.time else nowMs }
    )

    private fun readLiveNodes(nowMs: Long): List<ConvoyNode> {
        // GRPMODE-2026-10-13: group awareness off -> no radio node list (this tablet only, no other carts held).
        // HEARDSWITCH-2026-10-13 (P5.1b): what the radio has heard, from the comm side (raw; the display rules below are GroupTrack's).
        val heard = if (!GroupMode.on.value) emptyList() else try { commMessaging.members() } catch (e: Exception) { emptyList() }
        // NOGPSMSG-2026-09-15: clear both here so the RADIO path resets them without its own edit.
        // A radio supplies position through a different route entirely; neither
        // message applies once nodes are present.
        _noPositionError.value = false
        _networkPositionWarning.value = false
        // No radio — device IS a node. Phone GPS only after permission granted.
        // LINKFIX-2026-10-03: Android-only mode whenever the radio is not talking (was: only when the node list was empty,
        // so a connected-but-silent radio never let the tablet's GPS take over and my cart was dropped every tick).
        if (heard.isEmpty() || !radioTalkingNow) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startPhoneGps()
            }
            // _myCartId no longer set here — set ONCE in startGroupTrack()
            val loc = getPhoneLocation()
            // LINKFIX2-2026-10-03 (Fred): no tablet fix -> my cart stays at its PREVIOUS TICK position (the tick array, never a
            // stored one) until a real position returns.
            val prevMe = if (loc == null) _convoyState.value.nodes.firstOrNull { it.isMyCart && (it.latitude != 0.0 || it.longitude != 0.0) } else null

            // NOGPSMSG-2026-09-15: report WHICH state we are in. See the declarations above.
            // provider is reliable for this: sources 1 and 3 of getPhoneLocation
            // build Location("gps") explicitly, so only the live listener can
            // report "network".
            val fromNetwork = loc != null &&
                loc.provider == android.location.LocationManager.NETWORK_PROVIDER
            if (loc == null && prevMe == null) {   // LINKFIX2-2026-10-03: the hard message only with no position at all
                if (!_noPositionError.value) {
                    android.util.Log.e("ConvoyVM",
                        "NOGPSMSG-2026-09-15: NO POSITION from any source -- device cannot function")
                }
                _noPositionError.value = true
            } else if (fromNetwork) {
                if (!_networkPositionWarning.value) {
                    android.util.Log.w("ConvoyVM",
                        "NOGPSMSG-2026-09-15: position is NETWORK-derived -- will not work on the trail")
                }
                _networkPositionWarning.value = true
            }

            val lat = loc?.latitude ?: prevMe?.latitude ?: 0.0   // LINKFIX2-2026-10-03
            val lon = loc?.longitude ?: prevMe?.longitude ?: 0.0
            val alt = ((loc?.altitude ?: 0.0) * 3.28084).toInt()
            val spd = (loc?.speed ?: 0f) * 2.23694f
            val hdg = loc?.bearing ?: 0f
            // HEARDBOARD-2026-10-14: the phone-path cart moved to myTabletCart (unchanged)
            return listOf(myTabletCart(lat, lon, alt, spd, hdg, loc, nowMs)) + heldCarts(GroupMode.on.value)   // GRPMODE-2026-10-13 · LINKFIX-2026-10-03 / LINKFIX2-2026-10-03: held whenever not talking (a disconnect empties the node list)
        }
        // STALEREAD-2026-09-25 (Fred): drop stale nodes AT THE READ -- never build a cart only to eliminate it later.
        // A node not heard for 3 hours is not on the ride (phantoms from earlier / other channels); my own radio is
        // always kept.
        val myNum = myRadioNum()
        val myTablet = getPhoneLocation()   // HEARDSWITCH-2026-10-13: what the tablet already has -- no GPS is started for it
        // HEARDBOARD-2026-10-14: the per-radio rules moved to cartFromMember (unchanged) -- the cart board applies the same rules.
        val allNodes = heard.mapNotNull { m -> cartFromMember(m, nowMs, myNum, myTablet, { id -> _convoyState.value.nodes.firstOrNull { it.nodeId == id } }, nodeSpeedWindowStart, nodeLastComputedSpeed, shadow = false) }
        // HEARDSWITCH-2026-10-13: channel use from the comm side (Member.channelUtilPct; null = not reported) -- same average as before.
        val utilById = heard.associate { it.id to it.channelUtilPct }
        val avgUtil = if (allNodes.isEmpty()) 0f else
            allNodes.mapNotNull { node ->
                node.nodeId.removePrefix("!").toLongOrNull(16)?.toInt()?.toString()?.let { utilById[it] }
            }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        _avgChannelUtil.value = avgUtil
        // ONEFILTER-2026-09-25 (Fred): staleness is decided ONCE, at the read (STALEREAD: not heard for 3 hours = no
        // cart). ConvoyNodeFilter's time rules -- "heard today" (after local midnight) and the admission window --
        // disagreed with it (a rider heard at 23:50 vanished at midnight; "once admitted, visible all day" kept
        // phantoms) and are no longer applied. Its manual-removal rule was never wired (removedCartIds = emptySet()).
        return allNodes
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
