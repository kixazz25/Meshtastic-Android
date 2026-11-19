/*
 * PANELGLASS-2026-11-19 (2.8b) -- A-I and M on the iPad's glass.
 *
 * GtVecMap draws the 2.7e vector map; GroupTrackLaunchLayer (shared since PANELMOVE2) draws
 * A.MENU, C.Mode, D.GRP Awareness, E.Status, F.Map view, G.MAP KEY, H.FILTER, I.CENTRE ON ME
 * and M.Search OVER it. This file is the HOST: it supplies the chrome, the two bridges and the
 * state the shell is told rather than reads.
 *
 * Nothing here is shared code. Every Android-only decision stayed on the Android host; this is
 * its iOS counterpart, and the two are deliberately the same shape.
 */
package com.grouptrack.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

import com.geeksville.mesh.grouptrack.GroupTrackLaunchLayer
import com.geeksville.mesh.grouptrack.Gt
import com.geeksville.mesh.grouptrack.GtFilterBridge
import com.geeksville.mesh.grouptrack.GtPanelChrome
import com.geeksville.mesh.grouptrack.GtSearchBridge
import com.geeksville.mesh.grouptrack.GtTheme
import com.geeksville.mesh.grouptrack.LocalGtPanelChrome
import com.geeksville.mesh.convoy.GeofabrikState
import com.geeksville.mesh.convoy.parseGeofabrikStates
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.platform.iosBundleAsset
import com.grouptrack.shared.platform.GtIosLocation
import com.grouptrack.shared.platform.iosFindPlaces
import com.grouptrack.shared.settings.GtConfig
import com.grouptrack.shared.filter.TrailFilterState
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.platform.GtVecMap
import com.grouptrack.shared.platform.iosStorage
import com.grouptrack.shared.spatial.GtRead
import com.geeksville.mesh.convoy.GtRiderProfilePanel   // IOSPROFILE-2026-11-19: A.MENU > menu.account
import com.grouptrack.shared.net.GtProfileStore
import com.grouptrack.shared.net.GtRiderProfile
import com.grouptrack.shared.platform.IosClock
import com.grouptrack.shared.platform.IosIds
import com.grouptrack.shared.text.GtTime
import kotlinx.cinterop.ExperimentalForeignApi   // IOSHELP-2026-11-19: CGRectMake
import com.geeksville.mesh.grouptrack.GtDocsChooser   // IOSHELP-2026-11-19: A.MENU > menu.help
import com.geeksville.mesh.grouptrack.GtAlertSettingsPanel   // IOSSETTINGS-2026-11-19: A.MENU > menu.settings
import com.geeksville.mesh.grouptrack.GtRecordButton   // IOSREC-2026-11-19: the top-centre RECORD control
import com.geeksville.mesh.grouptrack.GtGpsHudRow   // IOSHUD-2026-11-19: the HUD's GPS line
import com.geeksville.mesh.convoy.RecordingState
import com.grouptrack.shared.settings.GtRideSettings
import com.grouptrack.shared.platform.IosSettings
import com.geeksville.mesh.grouptrack.docsAssetFor
import com.grouptrack.shared.platform.iosBundleAssetPath
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.WebKit.WKWebViewConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.WebKit.WKWebView
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The iPad's panel chrome.
 *
 * GtPanelChrome has NO default on purpose (SKIPLOUD): a host that forgets it fails at once. The
 * three services iOS cannot do yet are therefore STUBS THAT SAY SO, in the codebase's own
 * "not attached yet" idiom, so a missing control is visible during certification instead of
 * being an empty space nobody questions.
 */
object GtPanelChromeIos : GtPanelChrome {

    private val log = gtLogger("GtChromeIos")

    /** No docs viewer on iOS yet. Logged rather than silent, so a tapped OVERVIEW leaves a trace. */
    override fun openHelp(id: String) {
        log.i { "PANELGLASS-2026-11-19: openHelp(" + id + ") -- no iOS docs viewer yet" }
    }

    /** Drawn dim and inert: present, so its absence is a visible gap, not a missing control. */
    @Composable
    override fun IssueButton(id: String, color: Color) {
        Text("ISSUE", color = Gt.dim, fontSize = 12.sp)
    }

    /** Android draws the report panel and the CAPTURE bar here. iOS has neither yet. */
    @Composable
    override fun IssueHost() {
    }

    /** The interface's own note: Android-only today, iOS draws nothing. */
    @Composable
    override fun UtahPrivatePanel() {
    }

    /** J.Map, round 2. Said out loud at the top of the Map menu rather than left blank. */
    @Composable
    override fun QueueSummary(onOpen: () -> Unit, modifier: Modifier) {
        Text("Map downloads  -  not attached yet", color = Gt.dim, fontSize = 13.sp, modifier = modifier)
    }

    /** J.Map, round 2. */
    @Composable
    override fun DownloadSettingsPanel() {
        Text("Download settings  -  not attached yet (J)", color = Gt.dim, fontSize = 13.sp)
    }
}

/**
 * M.Search's database, on the iPad.
 *
 * ⚠ ITS OWN CONNECTION, read-only, NOT the map's -- sharing one SQLiteConnection across the map's
 * spatial runner and the search would be the exact corruption GtIosRideSpatial warns about.
 * ⚠ EVERY CALL THROUGH A MUTEX, because GtSearchBody runs the four tables as parallel async jobs
 * and one connection cannot be stepped by two of them. The symptom of getting this wrong is a
 * WRONG ROW, not a crash, so it is not a detail to discover during certification.
 *
 * CODE RULE 1: `read` is nullable with meaning -- a seed that has not been copied to the iPad yet
 * has no grouptrack_spatial.db, and the panel's existing empty-result state is the right answer
 * for that, not a crash.
 */
/**
 * GEOSTATES-2026-11-19: M.Search's state picker on the iPad.
 *
 * Read ONCE and cached, as GeofabrikCatalog does on Android (its own `cached` field). iosBundleAsset
 * THROWS on a missing asset by design (the bundle contract), so the read is wrapped -- a missing
 * catalogue must degrade to "no state filter", never terminate the app.
 */
private object IosStates {
    private val log = gtLogger("IosStates")
    private var cached: List<GeofabrikState>? = null
    fun get(): List<GeofabrikState> {
        cached?.let { return it }
        val v = try {
            parseGeofabrikStates(iosBundleAsset("geofabrik_states.json"))
        } catch (e: Throwable) {
            log.e(e) { "GEOSTATES-2026-11-19: geofabrik_states.json unreadable -- no state filter" }
            emptyList()
        }
        log.i { "GEOSTATES-2026-11-19: " + v.size + " states" }
        cached = v
        return v
    }
}

private object IosSearchDb {

    private val log = gtLogger("GtIosSearch")
    private val lock = Mutex()
    private var read: GtRead? = null
    private var opened = false

    private fun reader(): GtRead? {
        if (!opened) {
            opened = true
            read = try {
                val root = iosStorage().root().toString()
                // CODE RULE 1 as in 2.7e: without grouptrack_data.db the aliases and notes are
                // absent, which is Android's behaviour when the extension db is missing.
                val data = try { gtOpenDatabase(root + "/grouptrack_data.db", true) } catch (e: Throwable) { null }
                GtRead(gtOpenDatabase(root + "/grouptrack_spatial.db", true), data)
            } catch (e: Throwable) {
                log.i { "PANELGLASS-2026-11-19: no spatial db for search -- " + e.message }
                null
            }
        }
        return read
    }

    suspend fun searchByName(type: String, term: String): List<Map<String, String?>> =
        lock.withLock { reader()?.searchByName(type, term) ?: emptyList() }
}

/**
 * The iPad's GroupTrack screen: the map, with the launch layer over it.
 *
 * [onMessage] is GtVecMap's existing channel (fn, args). The launch points that need the MAP
 * rather than a panel are reported through it for now instead of being faked -- the map-side
 * half (zoom, centre, the trail redraw after a FILTER change) is the next change, and a
 * reported no-op is honest where a silent one would look like a working control.
 */
/**
 * IOSPROFILE-2026-11-19 (Fred 11-19 11:34 "still do not attach any of the three option to menu"):
 * A.MENU > menu.account's store on the iPad. Android is ConvoyProfileStore, itself only a front door to
 * this same shared GtProfileStore (SWITCH17G2C1-2026-11-12), so both platforms reach ONE implementation.
 *
 * ⚠ THREE THINGS THE OBVIOUS CALL GETS WRONG, all taken from IosRideNetworks' header rather than guessed:
 *  1. WRITABLE. IosSearchDb above opens read-only (gtOpenDatabase(path, true)); a profile SAVE needs
 *     readOnly = false, or every save would fail at the first INSERT and look like a refused callsign.
 *  2. nowUtc is SECONDS. Android's ConvoyProfileStore is SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"),
 *     so GtTime.formatUtcSeconds -- NOT IosClock.nowInstant(), which carries MILLISECONDS and would write
 *     "...:33.456Z" into a column Android fills with "...:33Z" (IOSRIDENET-2026-11-17 trap 1).
 *  3. the id is LOWERCASE. Android is UUID.randomUUID().toString(); NSUUID().UUIDString is UPPERCASE, and
 *     IosIds.newId() already lowercases it. Using NSUUID directly here would diverge on every new profile.
 *
 * CODE RULE 1: db() null = no extension database, which GtProfileStore already treats as "refuse, do not
 * pretend" on every member -- exactly 2.7e's behaviour when the extension db is absent.
 */
private object IosProfile {
    private val log = gtLogger("GtIosProfile")
    private var conn: androidx.sqlite.SQLiteConnection? = null
    private var tried = false

    private fun db(): androidx.sqlite.SQLiteConnection? {
        if (!tried) {
            tried = true
            conn = try {
                gtOpenDatabase(iosStorage().root().toString() + "/grouptrack_data.db", false)
            } catch (e: Throwable) {
                log.i { "IOSPROFILE-2026-11-19: no extension db for the profile -- " + e.message }
                null
            }
        }
        return conn
    }

    val store = GtProfileStore(
        { db() },
        { GtTime.formatUtcSeconds(IosClock.epochMs()) },   // ⚠ trap 2 -- seconds, not nowInstant()
        { IosIds.newId() },                                 // ⚠ trap 3 -- lowercased UUID
    )
}

/**
 * IOSHELP-2026-11-19: A.MENU > menu.help's document viewer. Its own function so
 * @OptIn(ExperimentalForeignApi::class) covers CGRectMake alone -- GtVecMap.ios.kt annotates per
 * function for the same reason, and putting it on GtGroupTrackScreen would opt the whole screen in.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun IosDocView(key: String, onClose: () -> Unit) {
                // IOSHELP-2026-11-19: the document, read from the BUNDLE by file path and shown in a
                // WKWebView -- the UIKitView + WKWebView pattern GtVecMap.ios.kt already proves.
                // ⚠ loadFileURL, not loadHTMLString: the manual is 10.8 MB of base64 screenshots.
                // ⚠ A FRAGMENT is dropped. Android's docsView can carry "manual#figure"
                //   (DOCSTACK-2026-09-06) and scrolls to it; the iPad opens the document at the top.
                //   Said here rather than discovered later -- nothing on the iPad passes a fragment yet,
                //   because only the manual's own cross-links do and those are inside the page.
                val asset = docsAssetFor(key.substringBefore("#"))
                val path = if (asset == null) null else iosBundleAssetPath(asset)
                Box(Modifier.fillMaxSize().background(Gt.panel)) {
                    if (path == null) {
                        // CODE RULE 1: a build made before gt_iosres_2026-11-19_v2.py ran has no
                        // documents in its bundle. Say so; do not pretend and do not throw.
                        Text(
                            "This document is not in this build.\n\nRun gt_iosres_2026-11-19_v2.py --apply and rebuild.",
                            color = Gt.text, fontSize = 15.sp,
                            modifier = Modifier.statusBarsPadding().padding(24.dp),
                        )
                    } else {
                        UIKitView(
                            factory = {
                                val web = WKWebView(
                                    frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                                    configuration = WKWebViewConfiguration(),
                                )
                                val u = NSURL.fileURLWithPath(path)
                                web.loadFileURL(u, allowingReadAccessToURL = u)
                                web
                            },
                            modifier = Modifier.fillMaxSize().statusBarsPadding(),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                    ) {
                        // DOCSCHOOSER-2026-09-02's lesson: a document must have a visible way out.
                        TextButton(onClick = onClose) {
                            Text("CLOSE", color = Gt.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
}

@Composable
fun GtGroupTrackScreen(onMessage: (String, String) -> Unit) {

    // TrailFilterState is a PLATFORM INPUT. GtIosHkEnv:173 normally sets it during housekeeping;
    // this makes the screen self-sufficient if it is opened before that has run. Same root as the
    // databases and the map, so it cannot point at a second map_keys.json.
    remember {
        if (runCatching { TrailFilterState.pathProvider() }.isFailure) {
            TrailFilterState.pathProvider = {
                iosStorage().root().toString() + "/" + TrailFilterState.FILE_NAME
            }
        }
        true
    }

    // MAPWIRE-2026-11-19: the host's handle on the page. Everything the Android host does with
    // webViewRef it now does with this, using Android's own scripts.
    val web = remember { mutableStateOf<WKWebView?>(null) }
    // SEARCHFIT2-2026-11-19: E.Status. GtVecServer.offline already means "stored tiles only,
    // even with internet" -- Android's VecOnline.localOnly. Nothing set it until now.
    val offline = remember { mutableStateOf(false) }
    fun js(script: String) { web.value?.evaluateJavaScript(script, null) }

    // GroupTrackScreen.kt:3178 verbatim -- asks the page to re-report its viewport, which is what
    // makes the trails redraw after a FILTER or MAP KEY change.
    val viewportJs = "try{var b=map.getBounds();Android.onViewportChanged(" +
        "b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}"

    // The map's own state, from the page. GtMapView holds these on Android (MapEngine.kt:52).
    val centre = remember { mutableStateOf(doubleArrayOf(0.0, 0.0)) }
    // IOSLOC-2026-11-19: asks permission and starts the updates once, on first composition.
    remember { GtIosLocation.start(); true }
    // IOSREADOUT-2026-11-19: the page reports zoom as onViewportChanged's FIFTH value, which the
    // relay was discarding. Android polls map.getZoom() once a second; this needs no poll.
    val zoom = remember { mutableIntStateOf(0) }
    // GRPIOS-2026-11-19: the radio link's two states, as Android's GrpAwarenessLauncher reports them
    // (connected, and TALKING -- they are not the same thing, LINKFIX-2026-10-03). BUILT and read by the
    // GRP colour above; there is no iOS radio yet, so both stay false and the button reads grey.
    // GRPIOS-2026-11-19: MODE. Android's GroupMode (app-side, SharedPreferences-backed) has no iOS source yet,
    // so this is the host's own state: BUILT and driving both the GRP colour and the launch layer's group flag,
    // false until the iPad has a radio. Android is untouched.
    val group = remember { mutableStateOf(false) }
    // IOSPROFILE-2026-11-19: menu.account's panel. Android navigates to a screen (onNavigateToProfile);
    // the iPad host has no navigator, so the panel is an overlay over the map, as the other panels are.
    val profileOpen = remember { mutableStateOf(false) }
    // IOSHELP-2026-11-19: menu.help. Android keeps a docsView + a back stack (GroupTrackScreen.kt:397);
    // the iPad shows ONE document at a time over the map, which is all the chooser can reach.
    val helpOpen = remember { mutableStateOf(false) }
    val docView = remember { mutableStateOf("") }
    // IOSSETTINGS-2026-11-19: menu.settings. GtRideSettings is SHARED and already certified on the iPad
    // (GtRideSettingsMacTest); IosSettings puts it on NSUserDefaults, suite "convoy_settings", the same
    // keys Android writes through GtSettingsAndroid. So this panel saves the rider's REAL settings.
    // ⚠ epochDay must be the LOCAL date, because Android is LocalDate.now().toEpochDay(). GtTime's
    // gregorianEpochDay over IosClock.localNow() is that; IosClock.epochMs() / 86_400_000 would be the
    // UTC day and would disagree with Android for the hours either side of local midnight.
    val rideSettings = remember {
        GtRideSettings(IosSettings(), GtRideSettings.STORE) {
            val d = IosClock.localNow()
            GtTime.gregorianEpochDay(d.year.toLong(), d.month, d.day)
        }
    }
    val settingsOpen = remember { mutableStateOf(false) }
    // IOSSETTINGS-2026-11-19: ISSUE-2026-10-24's choice. Android persists it through GtIssue.sendsEmail
    // (a Context read); here it is host state so the row works, and WHERE the report goes is D work.
    val issueEmail = remember { mutableStateOf(true) }
    // IOSSETTINGS-2026-11-19: Android reads this from ConvoyViewModel.showLeadTrack (GRPMODE2-2026-10-13).
    // The iPad has no convoy view model yet, so it is host state -- BUILT and driving the switch.
    val showLeadTrack = remember { mutableStateOf(true) }
    // IOSREC-2026-11-19: the record control's state. CHECK_IN first (CHECKIN-2026-09-27).
    val recState = remember { mutableStateOf(RecordingState.CHECK_IN) }
    val recMenu = remember { mutableStateOf(false) }
    // IOSHUD-2026-11-19: the HUD's text colour. Android keeps one per MAP TYPE
    // (viewModel.hudTextWhiteByMap[hudMapKey], COSMETICS-2026-10-06); the iPad has one map type, so one
    // flag. HUDSWITCH-2026-11-03 (Fred 11:15): DARK BY DEFAULT -- true = white text on a dark backing.
    val hudOnDark = remember { mutableStateOf(true) }
    val radioConnected = remember { mutableStateOf(false) }
    val radioTalking = remember { mutableStateOf(false) }
    val tilt = remember { mutableStateOf(false) }
    val headingUp = remember { mutableStateOf(false) }

    // H.FILTER's per-type display state. 0 = OFF, 1 = ALL, 2 = SELECTED, as on Android.
    val trails = remember { mutableIntStateOf(1) }
    val tracks = remember { mutableIntStateOf(1) }
    val routes = remember { mutableIntStateOf(1) }
    val waypoints = remember { mutableIntStateOf(1) }
    val hidden = remember { mutableStateOf(false) }
    val offAvailable = remember { mutableStateOf(false) }
    val offDownloading = remember { mutableStateOf(false) }

    fun slot(t: String) = when (t) {
        "Trails" -> trails
        "Tracks" -> tracks
        "Waypoints" -> waypoints
        else -> routes
    }

    // MAPWIRE-2026-11-19: the page's onViewportChanged is the ONLY thing that knows where the map
    // is. The SHIM posts JSON.stringify(arguments), so args is "[north,south,east,west,zoom]" --
    // parsed exactly as SpatialRunner.request parses it. Centre = ((n+s)/2, (e+w)/2), which is what
    // the Android host passes as mapCenter.
    val relay: (String, String) -> Unit = { name, args ->
        if (name == "onViewportChanged") {
            val v = args.trim().removePrefix("[").removeSuffix("]")
                .split(",").mapNotNull { it.trim().toDoubleOrNull() }
            if (v.size >= 4) centre.value = doubleArrayOf((v[0] + v[1]) / 2.0, (v[2] + v[3]) / 2.0)
            if (v.size >= 5) zoom.intValue = v[4].toInt()   // IOSREADOUT-2026-11-19
        }
        onMessage(name, args)
    }

    val filter = GtFilterBridge(
        state = { t -> slot(t).intValue },
        setState = { t, v -> slot(t).intValue = v; js(viewportJs); onMessage("setDisplay", t + "=" + v) },
        openSelect = { t -> onMessage("editDisplay", t) },
        hidden = hidden.value,
        toggleHide = { hidden.value = !hidden.value; js(viewportJs); onMessage("hideEverything", hidden.value.toString()) },
        // GroupTrackScreen.kt:3178 -- the trails re-read after a category or land change.
        trailsChanged = { js(viewportJs) },
        // GtOffGrid is Android's (Thread, MBTilesStore, VectorTiles, GtMapEngine), so the iPad
        // keeps the ticks' STATE and the overlay itself waits for J.
        offGridAvailable = { offAvailable.value },
        setOffGridAvailable = { on -> offAvailable.value = on; onMessage("offGridAvailable", on.toString()) },
        offGridDownloading = { offDownloading.value },
        setOffGridDownloading = { on -> offDownloading.value = on; onMessage("offGridDownloading", on.toString()) },
    )

    val search = GtSearchBridge(
        // MAPWIRE-2026-11-19: the real centre now, from the page -- so the distance column means
        // something. 0,0 only until the first onViewportChanged arrives.
        mapCenter = { centre.value },
        // SEARCHFIT2-2026-11-19: follows the STATUS toggle. See the note at internet.switch --
        // this says "the map may use the internet", not "the internet is reachable".
        online = !offline.value,
        // SEARCHFIT2-2026-11-19: the result MOVES THE MAP, as GroupTrackScreen:2970 does. This is
        // how the map gets positioned over real features without any location services, which is
        // what the FILTER / MAP KEY / offline tests need (Fred 09:51).
        openArtifact = { r ->
            val s0 = r.minLat; val n0 = r.maxLat; val w0 = r.minLon; val e0 = r.maxLon
            if (s0 != null && n0 != null && w0 != null && e0 != null) {
                // fitBounds takes [lats] then [lons] -- GroupTrackScreen.kt:1081 verbatim.
                js("fitBounds([" + s0 + "," + n0 + "],[" + w0 + "," + e0 + "])")
            }
            // CODE RULE 1: the four are nullable WITH MEANING -- an alias hit carries no bounds,
            // and Android skips the fit in that case rather than guessing a frame.
            onMessage("searchOpen", r.type + "|" + r.id)
        },
        // SEARCHFIT2-2026-11-19: GroupTrackScreen:3000. Android reads the zoom from
        // GtSearchZoom.get(context) (the rider's saved Area Search Radius); that setting has no
        // iOS home yet, so 13 is used -- the value Android's own comment records as the previous
        // default. A chosen constant, stated, not a ported setting.
        goToPlace = { lat, lon ->
            js("setView(" + lat + ", " + lon + ", 13)")
            onMessage("searchPlace", lat.toString() + "," + lon.toString())
        },
        // GEOSTATES-2026-11-19: the real catalogue, from the bundled asset. An empty result is
        // still legitimate -- the search then runs unbounded, as Android does outside every outline.
        states = { IosStates.get() },
        // IOSPLACES-2026-11-19: the real geocoder. Android's filter and its limit of 6 are
        // transcribed; only the region HINT differs (CLGeocoder's is circular, Android's a
        // rectangle). Online only, and the caller already gates on b.online.
        findPlaces = { t, st -> iosFindPlaces(t, st) },
        initSearch = { },
        // MAPWIRE-2026-11-19: OFF THE MAIN THREAD. The Android host wraps this in
        // withContext(Dispatchers.IO); Dispatchers.IO is internal on native, so Default is the
        // one that exists. Without this a LIKE scan over four tables blocked the UI.
        searchByName = { table, term ->
            withContext(Dispatchers.Default) { IosSearchDb.searchByName(table, term) }
        },
    )

    // ATTACH1: a row with no entry stays "not attached yet" AND DISABLED (GroupTrack.kt:339).
    // These three are the launch points whose whole function is one line of the page's JS, so they
    // are transcription; everything else stays unattached until its function is ported.
    val actions = mapOf<String, () -> Unit>(
        // GroupTrackScreen.kt:3125-3126
        "view.3d" to {
            tilt.value = !tilt.value
            js("try{if(window.gtSet3D)gtSet3D(" + tilt.value + ")}catch(e){}")
        },
        // GroupTrackScreen.kt:3128-3130 -- the compass tap: north up, and My dir off if it was on.
        "view.north" to {
            if (headingUp.value) {
                headingUp.value = false
                js("try{if(window.gtSetHeadingUp)gtSetHeadingUp(false)}catch(e){}")
            } else js("try{if(window.gtNorthUp)gtNorthUp()}catch(e){}")
        },
        // SEARCHFIT2-2026-11-19: E.Status -- GroupTrackScreen.kt:3147-3150. Android also refuses
        // when there is no internet AND no offline mode; iOS has no reachability check yet, so
        // this is a straight toggle of "stored tiles only".
        "internet.switch" to {
            offline.value = !offline.value
            js(viewportJs)
            onMessage("offline", offline.value.toString())
        },
        // IOSLOC-2026-11-19: GroupTrackScreen.kt:3134-3142. MAP_CART_ZOOM is SHARED, so this is
        // Android's own zoom. No position yet -> say so; Android shows a "No position yet" toast
        // and the iPad has no toast, so it goes to the status strip instead of being silent.
        // IOSPROFILE-2026-11-19: Android is "menu.account" to { onNavigateToProfile() }
        // (GroupTrackScreen.kt:3063). Same row, same id, an overlay instead of a navigation.
        "menu.account" to { profileOpen.value = true },
        // IOSHELP-2026-11-19: Android is "menu.help" to { showDocsChooser = true }
        // (GroupTrackScreen.kt:3062). Same row, same id, the SHARED chooser.
        "menu.help" to { helpOpen.value = true },
        // IOSSETTINGS-2026-11-19: Android is "menu.settings" to { onNavigateToSettings() }
        // (GroupTrackScreen.kt:3061). The iPad opens the SHARED settings panel.
        "menu.settings" to { settingsOpen.value = true },
        "centre.me" to {
            val h = GtIosLocation.here()
            if (h != null) js("setView(" + h[0] + ", " + h[1] + ", " + GtConfig.MAP_CART_ZOOM + ")")
            else onMessage("centre", "no position yet")
        },
        // GroupTrackScreen.kt:3131-3132
        "view.heading" to {
            headingUp.value = !headingUp.value
            js("try{if(window.gtSetHeadingUp)gtSetHeadingUp(" + headingUp.value + ")}catch(e){}")
        },
    )

    // IOSLOC-2026-11-19: my course + speed to the page, as GroupTrackScreen.kt:486 does every
    // tick (VECHEADING-2026-10-30). Re-registered when the web view arrives, because js()
    // is useless before then. This is what gives "My dir" something to point at.
    androidx.compose.runtime.LaunchedEffect(web.value) {
        GtIosLocation.onHeading = { h, s ->
            js("try{if(window.gtMyHeading)gtMyHeading(" + h + ", " + s + ")}catch(e){}")
        }
    }

    Box(Modifier.fillMaxSize()) {
        GtVecMap(Modifier.fillMaxSize(), relay, onWeb = { web.value = it },
            offline = offline.value)
        // IOSREADOUT-2026-11-19: the top-centre stack, transcribed from GroupTrackScreen.kt:1747-1757.
        // RECORD is NOT here: lines 1758+ are the check-in / recording engine (B.Check in), not a
        // button. ⚠ This belongs in GroupTrackLaunchLayer's TOP slot eventually -- it is here
        // because Android's gpsLamp and gtZoom live inside a nested Column ~1,300 lines above the
        // layer's call site, and hoisting them is surgery on the shipping screen, its own change.
        run {
            val lamp = GtIosLocation.lamp()
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                    .statusBarsPadding().padding(8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                ) {
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = if (lamp.first) Color(0xFF1B7F2A) else Color(0xFFB00020),
                        shadowElevation = 4.dp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Text("\u25CF " + lamp.second, color = Color.White, fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = Color(0x99000000),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Text("Z" + zoom.intValue, color = Color(0xFF4DA6FF), fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }

                // IOSREC-2026-11-19 (Fred 11-19 12:26): "build panel and w[i]th no radio functions built
                // underneath. not wired yet. we are after look and feel and functionality will be
                // backfilled if it is missing. per my list above." So: the CONTROL is real -- Android's
                // five colours, five labels, collapsed button and expanded PAUSE/END menu, from the
                // shared GtRecordButton -- and the STATE MACHINE cycles, so every state can be seen and
                // judged. What is deliberately absent is the machinery under it.
                //
                // ⛔ WHAT IS NOT BUILT, and why each is a real absence rather than laziness:
                //   the check-in   GtRideStore.checkIn needs env.profile(), and there is NO GtRideEnv in
                //                  iosMain (measured). An uncaught Kotlin throw on Native ABORTS THE
                //                  PROCESS, so calling it unconfigured is worse than not calling it.
                //   the recorder   there is no shared track recorder. Android's is ConvoyGpsService, a
                //                  foreground Service; the iPad needs background location and its own.
                //   the radio      no iOS radio, so radioConnected stays false -- which is why the
                //                  CHECK_IN label reads "REC" here and not "CHK IN" (SOLOREC-2026-09-28).
                // Each transition reports through the status strip, so a tap is never silent.
                //
                // THE TRANSITIONS, Android's shape (GroupTrackScreen.kt:1762-1816) minus what it gates on:
                //   CHECK_IN  -> IDLE      Android's SOLOREC path with no radio and no group: it checks in
                //                          silently, then records if ACCESS_BACKGROUND_LOCATION is held.
                //                          Here it stops at IDLE, because neither half exists.
                //   IDLE      -> RECORDING Android also picks a lead (one cart = auto-assign); nothing to pick.
                //   RECORDING -> the menu   PAUSED -> the menu   SLEEPING -> nothing (Android: the overlay wakes it)
                GtRecordButton(
                    state = recState.value,
                    group = group.value,
                    radioConnected = radioConnected.value,
                    menuOpen = recMenu.value,
                    onTap = {
                        when (recState.value) {
                            RecordingState.CHECK_IN -> {
                                recState.value = RecordingState.IDLE
                                onMessage("record", "checked in (the ride env is not built on the iPad yet)")
                            }
                            RecordingState.IDLE -> {
                                recState.value = RecordingState.RECORDING
                                onMessage("record", "recording (no iOS recorder yet -- nothing is written)")
                            }
                            RecordingState.RECORDING -> recMenu.value = true
                            RecordingState.PAUSED -> recMenu.value = true
                            RecordingState.SLEEPING -> { }
                        }
                    },
                    onPause = {
                        recState.value =
                            if (recState.value == RecordingState.PAUSED) RecordingState.RECORDING
                            else RecordingState.PAUSED
                        recMenu.value = false
                        onMessage("record", if (recState.value == RecordingState.PAUSED) "paused" else "resumed")
                    },
                    onEnd = {
                        // CHECKIN-2026-09-27: each recording starts with its own check-in, so END returns
                        // to CHECK_IN -- not IDLE. Android also opens the name dialog here; that is B work.
                        recState.value = RecordingState.CHECK_IN
                        recMenu.value = false
                        onMessage("record", "ended (the track-name dialog is not built on the iPad yet)")
                    },
                )
            }
        }

        // IOSHUD-2026-11-19: the HUD, bottom-left, 72 dp up -- SHELL7-2026-10-22 ("the HUD (GPS line +
        // MY CART / GROUP) shows in ONE MAP too, above the bottom bar"), the same 72 dp the launch layer
        // clears for the bottom row.
        // HUDRIDE-2026-11-01 (Fred 18:56-19:31): the HUD is a RIDE feature -- Android's gate is
        // `recordingState != RecordingState.CHECK_IN`, so it appears once the rider has checked in or
        // started recording, and the light/dark button shows alone before that.
        // ⛔ NOT BUILT: the GROUP / NODE readouts below this line on Android (HudMode's GroupHud and the
        // tapped-cart readout). Both are rendered from convoyState -- the carts, their roles, their signal
        // -- and there is no convoy state on the iPad. That is Fred's level B / G work, not a stub to fake:
        // an empty cart list would look like a ride with nobody in it.
        Box(
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                .padding(bottom = 72.dp),
        ) {
            GtGpsHudRow(
                showGps = recState.value != RecordingState.CHECK_IN,
                // the mesh's two numbers. No radio on the iPad, so 0 and 0f -- which Android renders as
                // "-- s" and a green 0%, not as an error.
                intervalSecs = 0,
                channelUtil = 0f,
                group = group.value,
                onOpen = { onMessage("gps", "the GPS interval panel is not built on the iPad yet") },
                onToggleText = { hudOnDark.value = !hudOnDark.value },
            )
        }

        // ABOVE GtTheme, as GtPanelChrome's own message instructs -- GtTheme draws IssueHost.
        CompositionLocalProvider(LocalGtPanelChrome provides GtPanelChromeIos) {
            GtTheme {
                GroupTrackLaunchLayer(
                    onHelp = { id -> GtPanelChromeIos.openHelp(id) },
                    // GroupTrackScreen.kt:3184
                    onZoom = { d -> js(if (d > 0) "map.zoomIn()" else "map.zoomOut()") },
                    mapType = "VEC",
                    mapTypes = listOf("VEC"),
                    onMapType = { t -> onMessage("mapType", t) },
                    online = !offline.value,
                    viewLabel = "View: No HUD",
                    onRide = false,
                    actions = actions,
                    // No bearing message exists yet, so the compass has nothing real to show and
                    // D.GRP Awareness has no radio in this batch.
                    // GRPIOS-2026-11-19 (Fred 11:39 "Grp awareness button is a white space now"): it was
                    // Gt.dim -- a ROLE colour. GroupTrack.kt:221 draws the label in WHITE on this colour, and
                    // on satellite the scheme's panel IS white, so the text was invisible rather than missing.
                    // Transcribed from Android's grpAwarenessColor() (GrpAwareness.kt), colour for colour:
                    //   GroupMode OFF        -> 0xFF5A6470 grey   (GRPMODE-2026-10-13)
                    //   connected + talking  -> 0xFF35C46A green  (LINKFIX-2026-10-03: green only while TALKING)
                    //   otherwise            -> 0xFFE0453A red    (connected but silent is RED)
                    // SETTLED 11-19 11:44 (Fred): "stay with grey". He had asked at 11:39 for RED when My Device
                    // is selected; measured against GrpAwareness.kt, Android gives GREY there and reserves RED for
                    // GROUP MODE WITH A SILENT RADIO (LINKFIX-2026-10-03) -- which is the distinction the colour
                    // exists to carry. Android's rule stands on both platforms. Nothing was changed, because this
                    // was transcribed from Android rather than from the request.
                    // No pulse here: the alpha pulse reads GrpAwarenessLauncher.pulseAt, which has no iOS source
                    // yet, so the steady 0.62f of Android's unlit state is used.
                    grpColor = (if (!group.value) Color(0xFF5A6470)
                        else if (radioConnected.value && radioTalking.value) Color(0xFF35C46A)
                        else Color(0xFFE0453A)).copy(alpha = 0.62f),
                    filter = filter,
                    // GroupTrackScreen.kt:3156 -- as Map Keys does.
                    keyStyleChanged = { js("setTrailStyles(" + TrailFilterState.styleJson() + ")") },
                    centreLabel = "CENTRE ON ME",
                    search = search,
                    onToggleView = { onMessage("toggleView", "") },
                    // VIEWSTATE: the host always knows these. bearing stays 0 because NOTHING
                    // reports the page's bearing yet -- a fabricated value would be worse.
                    bearing = 0f,
                    tilt = tilt.value,
                    headingUp = headingUp.value,
                    routePrecheck = { false },
                    group = group.value,   // GRPIOS-2026-11-19: was the literal false
                    mapsDownloading = false,
                )
                if (settingsOpen.value) {
                    // IOSSETTINGS-2026-11-19: the four alert thresholds and the lead-track switch, saving
                    // to the rider's real store. The three TOOLS on Android's settings screen (the
                    // conversion record, the trail check and the issue report) are NOT here -- each ends
                    // in Intent.ACTION_SEND, which is round two's email / share work.
                    // help = {} as menu.account: HELP needs GtHelp's link table.
                    GtAlertSettingsPanel(
                        rs = rideSettings,
                        showLeadTrack = showLeadTrack.value,
                        onShowLeadTrack = { v -> showLeadTrack.value = v },
                        // FUNCTIONAL: the same flag the GRP Awareness colour and the launch layer read, so
                        // flipping it here changes the button and the group-only rows at once.
                        group = group.value,
                        onGroup = { v -> group.value = v },
                        // FUNCTIONAL as a setting; Android also writes it through GtIssue.setSendsEmail,
                        // which needs a Context. Where ISSUE reports actually go on the iPad is D work.
                        issueEmail = issueEmail.value,
                        onIssueEmail = { v -> issueEmail.value = v },
                        // ⛔ NOT BUILT -- each is an Android subsystem, and three of them end in
                        // Intent.ACTION_SEND, which Fred's list puts at D ("including ios email solution").
                        onConversionRecord = { onMessage("settings", "the conversion record is Android's GroupTrackConversion -- not on the iPad yet") },
                        onRemoveTrailData = { onMessage("settings", "removing trail data is not built on the iPad yet") },
                        onCheckTrail = { onMessage("settings", "the trail check is Android's TrailCheck -- not on the iPad yet") },
                        onMapCredits = { onMessage("settings", "the map source credits screen is not built on the iPad yet") },
                        // FUNCTIONAL: straight into the profile panel that menu.account opens.
                        onProfile = { settingsOpen.value = false; profileOpen.value = true },
                        onClose = { settingsOpen.value = false },
                        help = {},
                    )
                }
                if (helpOpen.value) {
                    // IOSHELP-2026-11-19: the SHARED chooser. VIDEOS opens the site through
                    // LocalUriHandler inside it and needs nothing from the host.
                    GtDocsChooser(
                        onOpen = { key -> docView.value = key },
                        onClose = { helpOpen.value = false },
                    )
                }
                // IOSHELP-2026-11-19: extracted so the cinterop opt-in stays on ONE small function
                // rather than the whole screen -- the shape GtVecMap.ios.kt uses (@OptIn per function).
                if (docView.value.isNotEmpty()) IosDocView(docView.value) { docView.value = "" }
                if (profileOpen.value) {
                    // IOSPROFILE-2026-11-19: the SHARED panel (PANELMOVE4-2026-11-19). onCancel non-null
                    // puts it in EDIT mode, which is right here -- the iPad reaches it from the menu, not
                    // from the authority gate, so it must have a way out (TOUCHSTD-2026-10-22).
                    // help = {} for now: HELP is GtHelpButton, which needs GtHelp's link table -- that
                    // arrives with menu.help. The row is BUILT and the panel is attached; HELP is the stub.
                    GtRiderProfilePanel(
                        onSaved = { profileOpen.value = false; onMessage("profile", "saved") },
                        onCancel = { profileOpen.value = false },
                        load = { IosProfile.store.load() },
                        save = { p: GtRiderProfile -> IosProfile.store.save(p) },
                        help = {},
                    )
                }
            }
        }
    }
}

/**
 * Swift entry point, beside gtIpadProbeController() and gtVecMapController():
 *
 *     GtIosPanelsKt.gtGroupTrackController(onMessage:)
 *
 * CMPPLISTSKIP-2026-11-18: enforceStrictPlistSanityCheck stays false until a real Info.plist
 * carries CADisableMinimumFrameDurationOnPhone. PROBE-ONLY, and it goes when the plist lands.
 */
fun gtGroupTrackController(onMessage: (String, String) -> Unit): UIViewController =
    ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {
        GtGroupTrackScreen(onMessage)
    }
