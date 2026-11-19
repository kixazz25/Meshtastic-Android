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

                // IOSRECBTN-2026-11-19 (Fred 10:43): "two step [process] first step is option[al].
                // record button always launches the second step[;] issue is is the record button
                // [or the check-in button] presented." PRESENTED, in the state Android presents for
                // this device: GroupTrackScreen.kt:1830-1831 -- group OFF and no radio connected
                // gives "REC", not "CHK IN" (SOLOREC-2026-09-28). Colour, size, shape, padding and
                // elevation are Android's (0xFFD32F2F, 22 sp monospace bold, 32/18, corner 10,
                // elevation 6).
                //
                // ⛔ INERT, AND IT SAYS SO. Neither step can run on the iPad yet:
                //   step 1  GtRideStore.checkIn needs env.profile(), and NOTHING in iosMain supplies
                //           a GtRideEnv -- measured, there is no profile() in iosMain at all. Calling
                //           it unconfigured risks a throw, and an uncaught Kotlin throw on Native
                //           ABORTS THE PROCESS rather than returning an error.
                //   step 2  there is NO shared track recorder. Android's is ConvoyGpsService, a
                //           foreground Service; iOS needs background location updates and its own.
                // So the tap reports through the status strip, exactly as every other unattached
                // launch point does (ATTACH1). A button that looked like it worked would be worse
                // than its absence.
                androidx.compose.material3.Surface(
                    modifier = Modifier.clickable {
                        onMessage("record", "not attached yet -- step 1 needs the ride env, step 2 needs an iOS recorder")
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    color = Color(0xFFD32F2F),
                    shadowElevation = 6.dp,
                ) {
                    Text("\u23FA  REC", color = Color.White, fontSize = 22.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp))
                }
            }
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
                    grpColor = Gt.dim,
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
                    group = false,
                    mapsDownloading = false,
                )
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
