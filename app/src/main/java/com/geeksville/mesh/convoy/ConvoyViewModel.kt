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
// T6A-2026-10-20: T6a clean-up (diagnostic logs, lead-only plumbing, TICK_MS) done. TICKREMOVE-2026-10-20 (T5.5): the 5-second tick is gone -- every display duty runs on the message that brings it (the board).
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
    /** PRESETPANEL-2026-10-20: the radio's preset now, by name. CODE RULE 1: null = not read yet (or custom settings). */
    val reportingPreset: StateFlow<String?> by lazy { commMessaging.currentPreset.stateIn(viewModelScope, SharingStarted.Eagerly, null) }

    // ── Lead lock state ──────────────────────────────────────────────────
    private var _leadLockedFlag: Boolean = false
    // TICKREMOVE-2026-10-20 (T5.5): the tick's per-node maps (distance, 60-s speed window) went with the tick -- the board keeps boardSpeedStart/Last.
    // HEARDBOARD-2026-10-14: THE CART BOARD's fields -- HERE, before init, because init starts the board's listener and on the main thread
    // it runs at once (10-14: a board used before its fields existed crashed the app at start-up).
    // Thread-safe maps: messages arrive on the main thread, tablet fixes possibly from the recording service.
    @Volatile private var boardMyLastMs = 0L          // BOARDSHOW2-2026-10-15: when my cart last got a board report (one per reporting interval)
    private var boardShownSig: List<List<Any?>>? = null  // BOARDSHOW2-2026-10-15: what the screen shows now. CODE RULE 1: null = nothing shown yet
    // LEADCLEAR-2026-10-15: the lead the board took FROM A LEADER REPORT. CODE RULE 1: null = the lead (if any) did not come from a report.
    @Volatile private var reportedLeadId: String? = null
    @Volatile private var radioTrackLastFixMs = 0L   // RADIOTRACK-2026-10-15: the last radio fix fed to the recorder (new fixes only)
    // TICKSMALL-2026-10-15: channel use per radio (node number as text), from each received heard record. CODE RULE 1: null = not reported.
    private val boardUtil = java.util.concurrent.ConcurrentHashMap<String, Float?>()
    // MYCARTMSG-2026-10-15: the tablet's GPS drives my cart. myTabletFixMs 0 = no tablet fix yet; myFixLat/Lon = the last position sent.
    @Volatile private var myTabletFixMs = 0L
    // MYCARTRADIO-2026-10-18: when my radio last gave a FRESH position (its own time within 60 s). 0 = none yet -> the tablet is the fallback.
    @Volatile private var myRadioFixMs = 0L
    // ROLEPOS-2026-10-19: my radio's own last fresh position (valid when myRadioFixMs != 0). The role report sends THIS -- everybody else sees the radio.
    @Volatile private var myRadioLat = 0.0
    @Volatile private var myRadioLon = 0.0
    @Volatile private var myFixLat = 0.0
    @Volatile private var myFixLon = 0.0
    // LEADTRACKBOARD-2026-10-18 (build 1): the RECEIVING QUEUE -- every real report the board takes goes in, stamped with its received time; ONE
    // consumer (in init) runs the LEAD CART routine (TRACKRULES-2026-10-19, TrackRules.kt) + off-track and marks the ride-JSON hook.
    // Declared ABOVE init. CODE RULE 1: no nullable fields here.
    private sealed class TrackEntry {
        data class Real(val cartId: String, val p: TrackPoint, val rxMs: Long, val msgMs: Long, val isLead: Boolean, val isMine: Boolean) : TrackEntry()
        object Reset : TrackEntry()
        data class Poly(val cartId: String, val poly: List<Pair<TrackPoint, Long>>, val key: String) : TrackEntry()   // POLYLINE-2026-10-20
    }
    // POLYLINE-2026-10-20 THE LEAD'S SIDE: its own radio fixes (last 30 min) and when it last heard ANOTHER cart; the repeat still owed.
    private val polyOwnFixes = java.util.ArrayDeque<Triple<Long, Double, Double>>()
    @Volatile private var polyLastHeardMs = 0L
    private var polyRepeat: com.grouptrack.comm.GapPolyline? = null   // CODE RULE 1: null = no repeat owed
    private var polyRepeatAfterMs = 0L
    private var trimLogMs = 0L   // TRIM-2026-10-20: the trim's log line at most every 30 s
    private val polyApplied = java.util.Collections.synchronizedSet(HashSet<String>())   // receiver: polylines already applied (the repeat)
    private val _leadDots = MutableStateFlow<List<TrackPoint>>(emptyList())   // receiver: gap-polyline points that fit no line
    val leadDots: StateFlow<List<TrackPoint>> = _leadDots.asStateFlow()
    private val trackQueue = kotlinx.coroutines.channels.Channel<TrackEntry>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    // LEADEST-2026-10-20: the silent lead's estimated position (LeadEstimate.kt) -- the screen draws it; Ride statistics keep its error
    private val leadEst = LeadEstimate().also { e ->   // ESTGUIDE-2026-10-21: the known tracks and trails near the lead (~1.3 km around it)
        e.linesNear = { p -> val d = 0.012; val src = TrackGrounds.source
            src.tracksNear(p.lat - d, p.lon - d * 1.4, p.lat + d, p.lon + d * 1.4) + src.trailsNear(p.lat - d, p.lon - d * 1.4, p.lat + d, p.lon + d * 1.4) }
    }
    private val _leadEstimate = MutableStateFlow<LeadEstimate.Est?>(null)   // CODE RULE 1: null = the lead is not silent
    val leadEstimate: StateFlow<LeadEstimate.Est?> = _leadEstimate.asStateFlow()
    @Volatile private var estRoute: Pair<String, Line?>? = null   // CODE RULE 1: null = not looked up yet
    /** The DISPLAYED route as a Line (cached per id). CODE RULE 1: null = no route displayed, or it is gone from the database. */
    private fun estRouteNow(): Line? {
        val id = TrackGrounds.displayedRouteId ?: return null
        estRoute?.let { if (it.first == id) return it.second }
        val l = TrackGrounds.source.route(id); estRoute = Pair(id, l); return l
    }
    private val leadCart = LeadCartProcess(updateFrequency = 1)   // TRACKRULES-2026-10-19: the LEAD CART routine (TrackRules.kt) -- was LeadTrack (hold + trim)
    // TRACKORDER-2026-10-18: the newest report time taken into the queue per cart -- an older position never draws a track backwards. Above init.
    private val trackLastMsgT = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // TRACKRULES-2026-10-19: MyTrack (my cart's reports -> a track nobody drew) removed -- my track is the recorder's WRITTEN points (MyCartProcess).
    private val boardCarts = java.util.concurrent.ConcurrentHashMap<String, ConvoyNode>()
    private val boardSpeedStart = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Double, Double>>()
    private val boardSpeedLast = java.util.concurrent.ConcurrentHashMap<String, Float>()
    @Volatile private var boardState = ConvoyEngine.ConvoyState.empty()

    // The locked lead node ID — set by user via lead selection dialog before ride start
    private var lockedLeadNodeId: String? = null
    private val _leadLocked = MutableStateFlow(false)
    val leadLocked: StateFlow<Boolean> = _leadLocked.asStateFlow()


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
    private var phoneLocationListener: TabletPosition.Listener? = null   // ONEFEED-2026-10-19: my cart listens to the ONE feed
    /** ONEFEED-2026-10-19 (Fred 10-19 12:05): this tablet has GPS -> Android, never the radio, gives my cart's position. */
    private val tabletHasGps: Boolean by lazy { TabletPosition.hasGps(appContext) }
    /** GPSLAMP-2026-10-19 (Fred 10-19 13:50): the light ABOVE REC. Tablet with GPS: green when the chip uses 4+ satellites in its fix.
     *  Tablet without GPS (my cart from the radio): green when my radio sent a fresh fix in the last 45 s. first = green, second = label. */
    fun gpsLamp(): Pair<Boolean, String> = if (tabletHasGps) {
        val n = TabletPosition.satsInFix()
        Pair(n >= 4, if (n >= 4) "DROID GPS " + n else "NO DROID GPS")   // DROIDGPS-2026-10-25 (Fred 10-24 13:48): the tablet's own GPS
    } else {
        val ok = System.currentTimeMillis() - myRadioFixMs < 45_000L
        Pair(ok, if (ok) "RADIO GPS" else "NO RADIO GPS")
    }

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
     * TICKREMOVE-2026-10-20: the HARD state was set only by the tick's readLiveNodes(), removed at T5.5 -- it stays false; its dialog has been off since
     * DISPLAYFIX-2026-10-15. The SOFT state follows each tablet fix (onBoardMyFix).
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

        // NETLOC-2026-09-15: keep the BETTER fix, not the latest one. Registering several
        // providers against one listener means fixes of very different quality
        // arrive interleaved; last-write-wins would let a 30 m network fix
        // overwrite a 3 m GPS fix two seconds later.
        // ONEFEED-2026-10-19 (Fred 10-19 12:21): ONE source now -- every feed position is my cart's CoT message (onMyTabletFix -> the board),
        // RECORD on or off. (The NETLOC keep-the-better-fix filter was for mixed GPS + network listeners; with one feed it would hold my
        // cart still for up to 15 s whenever accuracy dips -- removed.)
        val listener = TabletPosition.Listener { loc ->
            livePhoneLocation = loc; onMyTabletFix(loc.latitude, loc.longitude, loc)   // MYFIX-2026-10-14
        }
        phoneLocationListener = listener


        if (TabletPosition.add(appContext, listener)) android.util.Log.i("ConvoyVM", "ONEFEED-2026-10-19: my cart listens to the position feed")
        else { android.util.Log.e("ConvoyVM", "ONEFEED-2026-10-19: the position feed did not start (permission?)"); phoneLocationListener = null }
    }

    private fun stopPhoneGps() {
        phoneLocationListener?.let { listener ->
            try {
                TabletPosition.remove(appContext, listener)   // ONEFEED-2026-10-19
            } catch (_: Exception) {}
        }
        phoneLocationListener = null
    }

    fun startGroupTrack() {
        // COMMNAMES-2026-10-11: the name table is kept (and written on every real change) by the comm side -- no save at RECORD.
        _routeTrailSegments.value = emptyList()
        _leadTrackSegments.value = emptyList()
        _gpsTrailSegments.value = emptyList()
        trackLastMsgT.clear()   // TRACKORDER-2026-10-18
        trackQueue.trySend(TrackEntry.Reset)   // LEADTRACKBOARD-2026-10-18: a new ride starts a new lead track
        rideStartTimeMs = System.currentTimeMillis()

        // IDENTITY: Set _myCartId ONCE — my radio node from Meshtastic, or !phone if no radio
        val myNum = myRadioNum()   // COMMIDENT-2026-10-09
        _myCartId.value = if (myNum != null) "!%08x".format(myNum) else "!phone"
        val nodes = boardCarts.values.toList()   // TICKREMOVE-2026-10-20: the board's carts (the carts the screen shows) -- was the tick's readLiveNodes()

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
        _leadLocked.value = false
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
                    // ROLEPOS-2026-10-19 (Fred 10-19 13:07 "everybody else sees the radio"): the report carries MY RADIO'S own last position, never the
                    // tablet's (since ONEFEED my cart = the tablet -> it gave every other tablet a second, different position for my cart).
                    if (myRadioFixMs == 0L) { android.util.Log.w("ROLEREPORT", "ROLEPOS-2026-10-19 ROLEREPORT #$n: no radio position yet -- skipped"); return@runCatching }
                    val lat = myRadioLat
                    val lon = myRadioLon
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
    private var rideStartTimeMs: Long = 0L  // Unix ms when startGroupTrack() was called

    private val _routeRecording = MutableStateFlow(false)
    val routeRecording: StateFlow<Boolean> = _routeRecording.asStateFlow()
    private val _offTrackNodes = MutableStateFlow<List<ConvoyNode>>(emptyList())
    val offTrackNodes: StateFlow<List<ConvoyNode>> = _offTrackNodes.asStateFlow()
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
        // LEADTRACKBOARD-2026-10-18: the receiving queue's ONE consumer (background). The truth (last heard, status, lead) is already on the board.
        viewModelScope.launch(Dispatchers.Default) {
            for (e in trackQueue) try { trackConsume(e) } catch (ex: Exception) { android.util.Log.w("LEADTRACK", "LEADTRACKBOARD-2026-10-18: entry failed: " + ex.message) }
        }
        // POLYLINE-2026-10-20: the POLYTEST pop-up listener is gone -- a gap polyline now arrives as a comm event (onGapPolylineReceived)
        // LEADEST-2026-10-20: every second -- the silent lead's estimate (only while group awareness is on)
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(1_000L)
                try {
                    // ESTREC-2026-10-20 (Fred 15:51): only while REC is on -- the lead's reports reach the estimate only through the lead-track queue
                    // (fed while REC is on); REC off = cleared at once and its memory reset (was: blinked forever after REC stopped)
                    val recOn = GroupMode.on.value && _trackActive.value
                    if (!recOn && _leadEstimate.value != null) { leadEst.reset(); android.util.Log.i("LEADEST", "ESTREC-2026-10-20 REC off -- estimate cleared") }
                    val est = if (recOn) leadEst.tick(System.currentTimeMillis(), estRouteNow()) else null
                    if (est != null && _leadEstimate.value == null) android.util.Log.i("LEADEST", "LEADEST-2026-10-20 lead " + est.leadId + " silent " + est.silentMs / 1000 + " s -- " + (if (est.onRoute && est.moving) "moving along the route" else "blinking in place"))
                    if (est != null && (est.silentMs / 1000) % 15 == 0L) android.util.Log.i("LEADEST", "ESTVIEW-2026-10-20 vm: " + est.leadId + " est " + est.lat + "," + est.lon +
                        " last " + est.lastLat + "," + est.lastLon + " silent " + est.silentMs / 1000 + " s " + (if (est.onRoute) "on route" else "no route") + " " + (est.speedMps * 2.237).toInt() + " mph")   // diagnostic
                    _leadEstimate.value = est
                } catch (ex: Exception) { android.util.Log.w("LEADEST", "LEADEST-2026-10-20 tick failed: " + ex.message) }
            }
        }
        // HEARDBOARD-2026-10-14: the cart board's ONLY radio input -- a message received from a radio (MemberHeard). Nothing at start-up.
        viewModelScope.launch {
            // COTBOARD-2026-10-15 (T5.4a-2): the board reads the CoT message (Received); MemberHeard no longer feeds it
            commMessaging.events.collect { e ->
                if (e is com.grouptrack.comm.CommEvent.PolylineReceived) { onGapPolylineReceived(e.polyline); return@collect }   // POLYLINE-2026-10-20
                if (e is com.grouptrack.comm.CommEvent.Received) onBoardMessage(e.message)
                else if (e is com.grouptrack.comm.CommEvent.MemberHeard) {
                    // TICKSMALL-2026-10-15: channel use (not CoT -- link quality) -> the average over the board's carts, as the tick did
                    boardUtil[e.member.id] = e.member.channelUtilPct
                    val utils = boardCarts.keys.mapNotNull { id -> id.removePrefix("!").toLongOrNull(16)?.toInt()?.toString()?.let { boardUtil[it] } }
                    _avgChannelUtil.value = if (utils.isEmpty()) 0f else utils.average().toFloat().takeIf { !it.isNaN() } ?: 0f
                    // STATSPANEL-2026-10-20: the ride statistics keep every report -- only while group awareness is on (Fred 10-20)
                    if (GroupMode.on.value) {
                        val fresh = System.currentTimeMillis() - myRadioFixMs < 120_000L
                        RideStats.onHeard(e.member, myRadioNum()?.toString(), if (fresh) myRadioLat else null, if (fresh) myRadioLon else null)
                    }
                }
            }
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
        // TICKSMALL-2026-10-15: radio talking / the GRP Awareness light / the tablet's GPS -- on the radio's health event (was every tick)
        viewModelScope.launch {
            commLink.health.collect { h ->
                radioTalkingNow = GroupMode.on.value && h.talking
                GrpAwarenessLauncher.talking.value = radioTalkingNow
                // CHIPALWAYS-2026-10-18 (Fred 10-18 17:11): the tablet's GPS chip runs ALL THE TIME, both modes -- was only while the radio was not
                // talking, so in group mode REC started a cold chip (no fix for minutes without internet). startPhoneGps() is once-only.
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        appContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    startPhoneGps()   // the chip always on (was: Android mode / Android data only)
                }
            }
        }
        // COMMNAMES-2026-10-11: the name table (load, the node-list names) moved to the comm side (MeshCommLink).
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
        _routeTrailSegments.value = emptyList()             // the drawn lead track
        trackQueue.trySend(TrackEntry.Reset)                 // LEADTRACKBOARD-2026-10-18: Android mode -- the board's lead track too
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
    // STATSPANEL-2026-10-20: Settings -> Group awareness -> Ride statistics
    /** POLYTEST-2026-10-20 (Fred 14:13): ONE fixed polyline to prove TAK V2 delivery -- 20 points on a ~400 m square around my radio, 30 s apart,
     *  starting 10 min ago. The receiver logs it whole (TAKDUMP V2). Returns what happened, for the Settings line. */
    fun sendTestPolyline(done: (String) -> Unit) {
        // the REAL Long Fast gap 1 (Droid 1 11:12:46-11:20:26, 460 s) -- gt_gap_polyline_model_2026-10-20.py, the test target
        val lats = listOf(42.7954083, 42.7923040, 42.7897953, 42.7875197, 42.7857813, 42.7842233, 42.7843220, 42.7862126, 42.7886974, 42.7907664,
            42.7927863, 42.7936231, 42.7968046, 42.8000065, 42.8012579, 42.8039844, 42.8055270, 42.8060717, 42.8071402, 42.8086935)
        val lons = listOf(-71.2772086, -71.2792966, -71.2827386, -71.2865197, -71.2906323, -71.2948318, -71.2986707, -71.2987069, -71.2984514, -71.3012102,
            -71.3041991, -71.3047036, -71.3039747, -71.3036906, -71.3035923, -71.3002903, -71.2957910, -71.2908897, -71.2864581, -71.2814826)
        val cs = checkIn.value?.callsign?.trim()?.ifEmpty { null } ?: profileCallsign.ifEmpty { "GroupTrack" }
        val p = com.grouptrack.comm.GapPolyline((myRadioNum() ?: 0).toString(), cs, System.currentTimeMillis() - 460_000L, 24_234L, lats, lons)
        viewModelScope.launch {
            val r = commMessaging.deliverPolyline(p)
            done(when (r) {
                is com.grouptrack.comm.CommResult.Ok -> "Sent: 20 points as a TAK u-d-f polyline at " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + ". Watch the other tablet for the pop-up."
                is com.grouptrack.comm.CommResult.Failed -> "Not sent: " + r.reason
                else -> "Not sent: " + r
            })
        }
    }
    fun rideStatsJson(): String = RideStats.json(System.currentTimeMillis())   // RIDESTATSSEND-2026-10-26
    fun leadTrackWritten(): List<WPoint> = leadCart.writtenPoints()             // RIDESTATSSEND-2026-10-26
    fun rideStatsReport(): String = RideStats.report(System.currentTimeMillis(), reportingPreset.value, reportingNowSecs.value, _avgChannelUtil.value)
    fun resetRideStats() = RideStats.reset()

    fun applyLocationInterval(secs: Int, preset: String) {   // PRESETPANEL-2026-10-20 (Fred 10-20): + the preset, through the radio SETUP (one process)
        if (_gpsApplyBusy.value) return
        if (secs !in 3..10) { _gpsApply.value = "Enter a number from 3 to 10."; return }
        if (GpsPresets.choices.none { it.first == preset }) { _gpsApply.value = "Choose Medium Fast or Long Fast."; return }
        if (link.value != com.grouptrack.comm.Link.CONNECTED) { _gpsApply.value = "Connect your radio first (GRP Awareness)."; return }
        val pw = GpsPresets.label(preset)
        _gpsApplyBusy.value = true
        _gpsApply.value = "Writing every $secs s on $pw to the radio..."
        // Runs on viewModelScope so closing the panel cannot cut it off in the middle of the radio's restart.
        viewModelScope.launch {
            // the setup's own progress lines while it runs (write, restart, wait for the radio, read every value back)
            val progress = launch {
                commConfig.applyReport.collect { r -> if (r != null && r.running) r.log.lastOrNull()?.let { _gpsApply.value = it } }
            }
            try {
                val r = commConfig.applyRadioChange(preset, secs)
                _gpsApply.value = when {
                    r is com.grouptrack.comm.CommResult.Applied && r.verified -> {
                        _currentIntervalSecs.value = secs
                        "Done -- every $secs s while moving, on $pw. Checked on the radio."
                    }
                    r is com.grouptrack.comm.CommResult.Applied -> "Written, but the check found a difference: " + r.failedChecks.joinToString(", ")
                    r is com.grouptrack.comm.CommResult.Failed && r.reason == "NO_RADIO" -> "Connect your radio first (GRP Awareness)."
                    r is com.grouptrack.comm.CommResult.Failed && r.reason == "STOPPED" ->
                        commConfig.applyReport.value?.let { "Stopped at ${it.stoppedAt}: ${it.stopReason}" } ?: "Stopped -- the radio did not come back."
                    r is com.grouptrack.comm.CommResult.Failed -> "Could not apply: ${r.reason}"
                    else -> "Could not apply."
                }
            } finally {
                progress.cancel()
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
    @Volatile private var lastGpsLat: Double? = null   // TRACKRULES-2026-10-19: the last WRITTEN point of my track (set on the recorder's worker)
    @Volatile private var lastGpsLon: Double? = null

    private fun bindGpsService(context: android.content.Context, onBound: (ConvoyGpsService) -> Unit) {
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
                val svc = (service as ConvoyGpsService.LocalBinder).getService()
                gpsService = svc
                svc.onSleepTriggered = { recordingState.value = RecordingState.SLEEPING }
                // TRACKRULES-2026-10-19 (Fred 10-19): my track = the points the recorder WROTE (MyCartProcess) -- the GPX and the screen are the same points.
                svc.onTrackWritten = { written ->
                    val add = ArrayList<ConvoyEngine.LeadTrackSegment>()
                    for (w in written) {
                        val pLat = lastGpsLat; val pLon = lastGpsLon
                        if (pLat != null && pLon != null) add.add(ConvoyEngine.LeadTrackSegment(startLat = pLat, startLon = pLon, endLat = w.lat, endLon = w.lon, color = "#2E75B6"))
                        lastGpsLat = w.lat; lastGpsLon = w.lon
                    }
                    if (add.isNotEmpty()) _gpsTrailSegments.value = _gpsTrailSegments.value + add
                }
                svc.onLocationUpdate = { lat, lon, _ ->
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
    // Bluetooth after 25 min without movement (ZZZ25-2026-10-16; was 10 -- one timer, ConvoyGpsService) on purpose, before the
    // T1000-E's own sleep. Held in memory only, as before.
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
        onBoardMyFix(lat, lon, loc)   // HEARDBOARD-2026-10-14 -- TICKREMOVE-2026-10-20: the only duty left (the board owns my cart; the per-fix screen write is gone)
    }

    // === HEARDBOARD-2026-10-14 (tick replacement T3+T4): THE CART BOARD -- fed by RECEIVED PAYLOADS only, compared with the tick, shown nowhere yet ===
    // The board's fields are declared before init (beside the tick's maps) -- see there.


    /** A tablet GPS fix (T2's hook). CODE RULE 1: loc null = the recording service's fix (position only). */
    private fun onBoardMyFix(lat: Double, lon: Double, loc: android.location.Location?) {
        // MYCARTMSG-2026-10-15: my cart ALWAYS uses the tablet's GPS (was: radio talking -> my cart is the radio's record, which never moved)
        if (lat == 0.0 && lon == 0.0) return
        val now = System.currentTimeMillis()
        // BOARDSHOW2-2026-10-15 (Fred 09:49): an Android fix is the tablet's sensor, not a received message -- my cart gets ONE board report
        // per reporting interval, from the latest fix, like every other cart. CODE RULE 1: interval null = not read from the radio yet -> 6 s.
        // TICKSMALL-2026-10-15: the network-position warning follows each tablet fix (network = will not work on the trail)
        if (loc != null) _networkPositionWarning.value = loc.provider == android.location.LocationManager.NETWORK_PROVIDER ||
            (loc.hasAccuracy() && loc.accuracy > 30f)   // ONEFEED-2026-10-19: fused has no "network" label -- worse than 30 m = a Wi-Fi/cell guess
        val everyMs = (reportingNowSecs.value ?: 6) * 1000L
        myTabletFixMs = now
        // MYCARTMSG-2026-10-15 (Fred 17:46): a message only when the GPS position CHANGED -- else one keep-alive per reporting interval
        val moved = lat != myFixLat || lon != myFixLon
        if (!moved && now - boardMyLastMs < everyMs) return
        boardMyLastMs = now; myFixLat = lat; myFixLon = lon
        val myNumNow = myRadioNum()
        if (myNumNow != null && GroupMode.on.value) {
            // my cart as a CoT message, through the SAME path as every other cart (Fred 17:16)
            val myId = "!%08x".format(myNumNow)
            val prevMine = boardCarts[myId]
            val fixMs = loc?.time?.takeIf { it > 0L } ?: now
            val msg = com.grouptrack.comm.CommMessage(
                uid = "MESHTASTIC-" + myId, type = "a-f-G-U-C", how = "m-g",
                timeMs = fixMs, startMs = fixMs, staleAtMs = now + everyMs,
                point = com.grouptrack.comm.CotPoint(lat, lon, haeM = loc?.takeIf { it.hasAltitude() }?.altitude, ceM = null, leM = null),
                // CODE RULE 1 (callsign): the board's name for my cart, else the radio name table, else the id
                callsign = prevMine?.callsign ?: (commMessaging.memberName(myNumNow.toString()) ?: myId),
                role = com.grouptrack.comm.Role.RIDER,   // display role comes from the role store / check-in (cartFromMessage)
                team = "Cyan",
                track = loc?.takeIf { it.hasSpeed() && it.hasBearing() }?.let { com.grouptrack.comm.CotTrack(it.speed.toDouble(), it.bearing.toDouble()) },
                batteryPct = prevMine?.battery_pct,   // the radio's battery, as last shown
                groupTrack = null,
            )
            onBoardMessage(msg, fromTablet = true)
            return
        }
        // no radio known, or Android only (group awareness off): the existing tablet cart below
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
        // MYTRACKMSG-2026-10-18: the tablet-only path (no radio known / group awareness off) feeds the queue too -- a rider without a radio sees his track
        if (_trackActive.value) trackQueue.trySend(TrackEntry.Real(cart.nodeId, TrackPoint(lat, lon), now, now, cart.nodeId == lockedLeadNodeId, true))
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
        run {   // BOARDSHOW2-2026-10-15: the screen draws from the board -- updated only when something VISIBLE changed (TICKREMOVE-2026-10-20: no flag)
            val sig = boardState.nodes.sortedBy { it.nodeId }.map { listOf<Any?>(it.nodeId, it.latitude, it.longitude, it.status, it.isLead, it.callsign, it.rideRole) }
            if (sig != boardShownSig) { boardShownSig = sig; _convoyState.value = boardState }
            GrpAwarenessLauncher.ridingGroup.value = boardState.nodes.any { !it.isMyCart }   // TICKSMALL-2026-10-15: the power-cycle banner's condition
            // PANELBOARD-2026-10-15 (T5.4d, the tick's refresh moved): an open cart panel shows that cart's latest values on every payload
            if (_hudMode.value == HudMode.NODE && _selectedNode.value != null) {
                _selectedNode.value = boardState.nodes.firstOrNull { it.nodeId == _selectedNode.value?.nodeId }
            }
        }
    }







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



    /** TRACKRULES-2026-10-19: the receiving queue's consumer -- the LEAD CART routine (TrackRules.kt) and off-track. One coroutine: no locking.
     *  My track is the recorder's (MyCartProcess in ConvoyGpsService); my cart's reports move my cart icon only. */
    private fun trackConsume(e: TrackEntry) {
        when (e) {
            is TrackEntry.Reset -> { leadCart.reset(); _offTrackIds.value = emptySet(); trackPublish(); leadEst.reset(); _leadEstimate.value = null }   // LEADEST-2026-10-20
            is TrackEntry.Poly -> {   // POLYLINE-2026-10-20: the lead's gap polyline -> the lead track is pinned to it
                val ok = leadCart.onGapPolyline(e.cartId, e.poly)
                android.util.Log.i("POLYLINE", "POLYLINE-2026-10-20 lead " + e.cartId + " polyline " + e.key + " (" + e.poly.size + " points): " +
                    (if (ok) "lead track pinned, " + leadCart.dots().size + " dot(s)" else "NOT applied -- not this lead's track, or no track yet"))
                if (ok) trackPublish()
            }
            is TrackEntry.Real -> {
                // 3 RECORDER: the ride detail JSON is written HERE (RIDEDETAIL, after the tick) -- the real report only, never my tablet's own GPS.
                if (e.isLead && !e.isMine) {
                    if (leadCart.onLeadReport(e.cartId, e.p, e.rxMs)) { trackPublish(); android.util.Log.d("LEADTRACK", "TRACKRULES-2026-10-19: lead " + e.cartId + " written") }
                    // LEADEST-2026-10-20: heard -> the estimate (if one was showing) ends; its error to the ride statistics
                    leadEst.onReport(e.cartId, e.p, e.rxMs, estRouteNow())?.let { o ->
                        android.util.Log.i("LEADEST", "LEADEST-2026-10-20 heard after " + o.silentMs / 1000 + " s: estimate off by " + o.errorM.toInt() + " m (" + (if (o.onRoute) "on route" else "in place") + ", " + (o.speedMps * 2.237).toInt() + " mph)")
                        if (GroupMode.on.value) RideStats.onEstimate(o)
                    }
                    _leadEstimate.value = null
                } else if (e.isMine) {
                    // TRIM-2026-10-20 (Fred 10-20 14:49-14:56): my cart replaces the lead track behind me -- the shown lead track is cut by time
                    if (leadCart.trimTo(e.p)) {
                        trackPublish()
                        if (e.rxMs - trimLogMs >= 30_000L) { trimLogMs = e.rxMs; android.util.Log.i("LEADTRACK", "TRIM-2026-10-20 lead track shown from " + leadCart.trimmedAtMs() + " (UTC ms), " + leadCart.segments().size + " segs") }
                    }
                } else {
                    // off-track: THAT cart against the lead track, when its own report arrives (Settings -> OFF_TRACK_MILES)
                    val d = leadCart.metresFromTrack(e.p)
                    val off = d != null && d > ConvoyConfig.OFF_TRACK_MILES.toDouble() * 1609.344
                    val cur = _offTrackIds.value
                    if (off != (e.cartId in cur)) _offTrackIds.value = if (off) cur + e.cartId else cur - e.cartId
                }
            }
        }
    }
    /** TRACKRULES-2026-10-19: the map gets the WHOLE written lead track (R1: no location test, no trim). The cart icon is the board's. */
    private fun trackPublish() {
        val segs = leadCart.segments()
        _routeTrailSegments.value = segs
        _leadTrackSegments.value = segs
        _leadDots.value = leadCart.dots()   // POLYLINE-2026-10-20
    }

    /** POLYLINE-2026-10-20 (Fred 10-20 13:46-14:45) THE LEAD'S SIDE. Dead air = the time the lead hears NO other cart (one cart heard = the hops
     *  relay to the rest). After at least 60 s of it, the first report heard from another cart sends ONE gap polyline: 20 points at EQUAL
     *  TIME steps across [last heard .. heard again], from the lead's own radio fixes; one repeat at the next report heard >= 10 s later.
     *  Not the lead: nothing sent. A stored report replayed at connect is not "heard". */
    private fun polyOnMessage(num: Int, myNum: Int?, msg: com.grouptrack.comm.CommMessage, now: Long) {
        if (myNum == null || msg.timeMs < now - 60_000L) return
        if (num == myNum) {
            val p = msg.point ?: return
            polyOwnFixes.addLast(Triple(now, p.latitude, p.longitude))
            while ((polyOwnFixes.peekFirst()?.first ?: now) < now - 1_800_000L) polyOwnFixes.pollFirst()
            return
        }
        val last = polyLastHeardMs
        polyLastHeardMs = now
        val iLead = (checkIn.value?.role ?: "") == "leader" || "!%08x".format(myNum) == lockedLeadNodeId
        if (!iLead) { polyRepeat = null; return }
        if (last > 0L && now - last >= POLY_DEAD_AIR_MS) {
            val p = polyBuild(myNum, last, now) ?: return
            polyRepeat = p; polyRepeatAfterMs = now + 10_000L
            polySend(p, "sent")
        } else {
            val r = polyRepeat
            if (r != null && now >= polyRepeatAfterMs) { polyRepeat = null; polySend(r, "repeat") }
        }
    }
    private val POLY_DEAD_AIR_MS = 60_000L   // POLYLINE-2026-10-20 (Fred 14:45 "60 seconds is a good even number"): a real loss
    /** POLYLINE-2026-10-20: 20 equal-time points across [t0, t1] from my own radio fixes (linear between fixes). CODE RULE 1: null = fewer than 2
     *  of my own fixes inside the window (my radio was not reporting) -- nothing honest to send. */
    private fun polyBuild(myNum: Int, t0: Long, t1: Long): com.grouptrack.comm.GapPolyline? {
        val fx = polyOwnFixes.toList()
        val inside = fx.count { it.first in t0..t1 }
        if (inside < 2) {
            android.util.Log.w("POLYLINE", "POLYLINE-2026-10-20 lead: dead air " + (t1 - t0) / 1000 + " s but only " + inside + " own fix(es) in it -- nothing sent")
            return null
        }
        val step = (t1 - t0) / 19
        fun at(t: Long): Pair<Double, Double> {
            if (t <= fx.first().first) return Pair(fx.first().second, fx.first().third)
            if (t >= fx.last().first) return Pair(fx.last().second, fx.last().third)
            val i = fx.indexOfFirst { it.first >= t }
            val a = fx[i - 1]; val b = fx[i]
            val f = if (b.first == a.first) 0.0 else (t - a.first).toDouble() / (b.first - a.first)
            return Pair(a.second + (b.second - a.second) * f, a.third + (b.third - a.third) * f)
        }
        val pts = (0 until 20).map { at(t0 + it * step) }
        val cs = checkIn.value?.callsign?.trim()?.ifEmpty { null } ?: profileCallsign.ifEmpty { "GroupTrack" }
        return com.grouptrack.comm.GapPolyline(myNum.toString(), cs, t0, step, pts.map { it.first }, pts.map { it.second })
    }
    private fun polySend(p: com.grouptrack.comm.GapPolyline, what: String) {
        val secs = p.stepMs * 19 / 1000
        viewModelScope.launch {
            val r = commMessaging.deliverPolyline(p)
            android.util.Log.i("POLYLINE", "POLYLINE-2026-10-20 lead " + what + ": dead air " + secs + " s, " + p.lats.size + " points -> " + r)
            if (what == "sent" && r is com.grouptrack.comm.CommResult.Ok)
                android.widget.Toast.makeText(appContext, "Gap polyline sent: " + (secs / 60) + ":" + "%02d".format(secs % 60) + " of dead air", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    /** POLYLINE-2026-10-20 THE RECEIVER: another cart's gap polyline. Shown as a pop-up (the field test); when it is the LEAD's and REC is on, the
     *  lead track is pinned to it (TrackRules pin). The repeat is recognised (same sender + start) and not applied twice. */
    private fun onGapPolylineReceived(p: com.grouptrack.comm.GapPolyline) {
        val num = p.senderUid.toIntOrNull() ?: return
        if (num == myRadioNum()) return
        val id = "!%08x".format(num)
        val key = p.senderUid + "-" + p.t0Ms / 1000
        val first = polyApplied.add(key)
        val n = minOf(p.lats.size, p.lons.size)
        val secs = p.stepMs * (n - 1) / 1000
        val isLead = id == lockedLeadNodeId
        android.util.Log.i("POLYLINE", "POLYLINE-2026-10-20 received from " + id + " (" + p.callsign + "): " + n + " points over " + secs + " s" +
            (if (first) "" else " -- the repeat, already have it") + " lead=" + isLead + " rec=" + _trackActive.value + " group=" + GroupMode.on.value +
            " | lead's last point " + (System.currentTimeMillis() - (p.t0Ms + (n - 1) * p.stepMs)) + " ms before my clock (UTC ms: delivery + clock difference) " + "TRIM-2026-10-20")
        if (!first) return
        android.widget.Toast.makeText(appContext, "Gap polyline from " + p.callsign.ifBlank { id } + ": " + n + " points, " + (secs / 60) + ":" + "%02d".format(secs % 60), android.widget.Toast.LENGTH_LONG).show()
        if (!GroupMode.on.value || !_trackActive.value || !isLead || n < 2) return
        trackQueue.trySend(TrackEntry.Poly(id, (0 until n).map { Pair(TrackPoint(p.lats[it], p.lons[it]), p.t0Ms + it * p.stepMs) }, key))
    }

    /** COTBOARD-2026-10-15 (T5.4a-2): a CoT message was received -- the board's input (the twin of onBoardHeard, which T5.5 removes). */
    private fun onBoardMessage(msg: com.grouptrack.comm.CommMessage, fromTablet: Boolean = false) {   // MYCARTMSG-2026-10-15: fromTablet = my cart from the tablet's GPS
        if (!GroupMode.on.value) return                                  // Android only: no radios
        // the radio number from the uid (MESHTASTIC-!<node id>, the comm side's rule). CODE RULE 1: null = not a radio we know how to read
        val num = msg.uid.removePrefix("MESHTASTIC-!").toLongOrNull(16)?.toInt() ?: return
        val myNum = myRadioNum()
        // MYCARTRADIO-2026-10-18 (Fred 10-18 09:34): MY RADIO'S FIX ALWAYS WINS; the tablet is the fallback when there is no radio fix.
        // (was MYCARTMSG-2026-10-15: any tablet fix in the last 15 s -- network fixes included -- threw my radio's report away.)
        val nowMine = System.currentTimeMillis()
        if (!fromTablet && num == myNum) {
            // a stored position replayed at connect is OLD (A3) -> not a fix; the report still counts as heard on the board below
            val rp = msg.point   // ROLEPOS-2026-10-19: keep the position with the time
            if (rp != null && msg.timeMs > nowMine - 60_000L) { myRadioFixMs = nowMine; myRadioLat = rp.latitude; myRadioLon = rp.longitude
                if (GroupMode.on.value) RideStats.onMine(nowMine, rp.latitude, rp.longitude) }   // STATSPANEL-2026-10-20
        }
        if (!fromTablet) polyOnMessage(num, myNum, msg, nowMine)   // POLYLINE-2026-10-20: the lead's dead air + its own fixes
        if (fromTablet && myNum != null && !tabletHasGps && commLink.health.value.talking && nowMine - myRadioFixMs < 45_000L) return   // the radio is driving my cart (ONEFEED-2026-10-19: only on a tablet without GPS)
        val id = "!%08x".format(num)
        val now = System.currentTimeMillis()
        // ONEFEED-2026-10-19 (Fred 10-19 12:05/12:21): on a tablet WITH GPS my radio's own CoT does not move my cart (it is still heard; on a tablet
        // without GPS it still feeds the recorder below). My cart is the Android feed's CoT message (fromTablet).
        val skipMine = !fromTablet && num == myNum && tabletHasGps
        val cart = if (skipMine) null else cartFromMessage(msg, num, now, myNum, getPhoneLocation(), { boardCarts[it] }, boardSpeedStart, boardSpeedLast)
        if (!skipMine) { if (cart == null) boardCarts.remove(id) else boardCarts[id] = cart }
        // LEADTRACKBOARD-2026-10-18: the REAL report into the receiving queue (REC on -- as the tick's lead track). A report without a point is not a move.
        val qp = msg.point
        if (cart != null && qp != null && _trackActive.value && msg.timeMs > (trackLastMsgT[id] ?: 0L)) {   // TRACKORDER-2026-10-18: newer only
            trackLastMsgT[id] = msg.timeMs
            trackQueue.trySend(TrackEntry.Real(id, TrackPoint(qp.latitude, qp.longitude), now, msg.timeMs, id == lockedLeadNodeId, id == resolveMyCartId()))
        }
        // RADIOTRACK-2026-10-15 (T5.4c, the tick's feed moved): my radio's NEW fix -> the track recorder (tablet without GPS, REC on), altitude in METRES
        val rp = msg.point
        if (num == myNum && rp != null && msg.timeMs != radioTrackLastFixMs && gpsService?.useRadioGps == true && _routeRecording.value) {
            radioTrackLastFixMs = msg.timeMs
            gpsService?.onRadioPosition(rp.latitude, rp.longitude, rp.haeM ?: 0.0)   // CODE RULE 1: haeM null = no altitude sent -> 0
        }
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
    }
}
