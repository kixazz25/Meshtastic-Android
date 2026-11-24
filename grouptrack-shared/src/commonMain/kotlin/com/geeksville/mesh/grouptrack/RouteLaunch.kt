package com.geeksville.mesh.grouptrack

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.geeksville.mesh.convoy.ROUTE_LS_NEW
import com.geeksville.mesh.convoy.ROUTE_METHOD_P2P
import com.geeksville.mesh.convoy.RouteManager
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.route.GtRoute
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * ROUTELAUNCH-2026-11-23: Route+'s LAUNCH and TAP, moved out of app/ RouteModule.kt so the iPad
 * runs THE SAME CODE. ONE CODE BASE was the whole point (Fred 15:50), so nothing here is rewritten:
 * startNew/close/endBuild are RouteModule.kt:855-881 and onTap is :922-945, body for body.
 *
 * THE HOST'S PRIMITIVES ARE GLOBAL VARS, as they already were on the Android object, and the tap
 * handler reads them directly -- no bridge, no parameters (Fred 15:50: "that should be global
 * variables and available to the tap handler").
 *
 * Android assigns them in GroupTrackScreen's Route+ run{} block and in GtRoute.onTap; the iPad
 * assigns them in GtIosPanels. Neither platform's call sites changed.
 */
object GtRouteLaunch {
    private val log = gtLogger("RouteBridge")   // the tag Android's onTap already logged under

    // ---- THE HOST'S PRIMITIVES (were RouteModule.kt:66, 68, 70, 228) --------------------------
    /** The host map's page; set by the host -- runs one JS call. */
    var js: (String) -> Unit = {}
    /** The host map's last view: S, W, N, E, zoom -- set by the host's onViewportChanged. */
    var vp: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 15.0)
    /** The host map's state save (writes RouteState under the map's own key). */
    var persist: () -> Unit = {}
    /** ROUTEFILTER-2026-11-03: Build it myself shows the current layer selection first. */
    var layersOnForAi: () -> Unit = {}

    // ---- the four onTap needed, which were direct Android calls ------------------------------
    /** The draft write after every point. Android: RouteDraftStore. iPad: the route json primitives. */
    var autosave: () -> Unit = {}
    /** The build line from the current points, against the trails and tracks in view (IO). */
    var buildLine: suspend () -> String = { "" }
    /** The trails in the current view. Android: SpatialDbManager.queryTrailsByViewport. iPad: GtRead. */
    var trailsInView: suspend (DoubleArray) -> List<Map<String, String?>> = { emptyList() }
    /** FIRSTPOINT log only -- the original uses nothing but the count, so the seam returns it. */
    var trailheadCountNear: suspend (Double, Double) -> Int = { _, _ -> 0 }

    // ---- IOSMANUAL-2026-11-24: what the RECHECK and the RESUME need that is the HOST's ---------
    // The bodies below are RouteModule.kt's, unchanged. Only the platform's own capabilities are
    // seams, so Android keeps its CERTIFIED store and map state and the iPad supplies its own.
    /** The in-progress drafts as (name, pointCount) -- all the rules read. Android:
     *  RouteDraftStore.listDrafts() mapped at the door. iPad: GtRouteDraft.listDrafts(). */
    var listDrafts: () -> List<Pair<String, Int>> = { emptyList() }
    /** TWOCHECKS-2026-11-24: a draft belongs to the open AI compare set, so the MANUAL list excludes
     *  it (GTROUTE1C-2026-10-23). The DEFAULT is the manual check's own read, below, over the json
     *  primitive -- it does not call into the AI flow. Android overrides it with its certified
     *  RouteDraftStore.isInOpenBatch. */
    var isInOpenBatch: (String) -> Boolean = { n -> draftIsInOpenBatch(n) }
    /** A route was left open in the map's saved state. Android: MapStateStore. iPad: false. */
    var recoveryOpen: () -> Boolean = { false }
    /** Show the WIP picker. Android: draftTick++/showPicker. iPad: not ported -> no-op. */
    var showPicker: () -> Unit = {}
    /** Load a draft into RouteManager; returns its method or null. Android:
     *  RouteDraftStore.loadIntoRouteManager(name)?.method. iPad: GtRouteDraft's. */
    var loadDraft: (String) -> String? = { null }
    /** The build line BY LINE ID, not by viewport: a resumed route can be outside the view, where
     *  the viewport read returns nothing (GTROUTE1G-2026-10-23). Falls back to buildLine. */
    var buildLineById: suspend () -> String = { buildLine() }
    /** A transient message. Android: Toast. iPad: onMessage -> the status strip. */
    var toast: (String) -> Unit = {}

    // ---- THE STATE (was RouteModule.kt:90-96) ------------------------------------------------
    /** RouteDraftStore.UNNAMED -- the unnamed-draft literal. Live rider data: never reworded. */
    const val UNNAMED = "Auto Saved In Progress"
    val routeMode = mutableStateOf(false)
    val addPointMode = mutableStateOf(false)
    val routeName = mutableStateOf("")
    val routeEntryNonce = mutableIntStateOf(0)
    val routeMethod = mutableIntStateOf(ROUTE_METHOD_P2P)
    val routeLifecycleState = mutableIntStateOf(ROUTE_LS_NEW)
    /** IOSMANUAL-2026-11-24: a draft to resume ("" = none). Was RouteModule.kt:108; hosts delegate. */
    val resumeReq = mutableStateOf("")
    /** IOSMANUAL-2026-11-24: a route was left open last session. Was RouteModule.kt:109. */
    val recoveryDetected = mutableStateOf(false)

    /** RouteModule.kt:873 methodStr() -- the draft's "method" field. AUTOSAVE-2026-11-23 */
    fun methodStr(): String = when (routeMethod.intValue) {
        com.geeksville.mesh.convoy.ROUTE_METHOD_DRAW -> "draw"
        com.geeksville.mesh.convoy.ROUTE_METHOD_SUGGEST -> "suggest"
        else -> "point"
    }

    /** Launch Route+ in ONE mode, locked (stage 1: Build it myself). A new route starts as the UNNAMED draft. */
    fun startNew(method: Int) {
        log.i { "ROUTETRACE-2026-11-24 startNew ENTER method=" + method + " INHERITED_VERTS=" + RouteManager.routeVertices().size + " routeMode=" + routeMode.value + " name='" + routeName.value + "' ls=" + routeLifecycleState.intValue }
        routeLifecycleState.intValue = ROUTE_LS_NEW
        routeMethod.intValue = method
        routeName.value = UNNAMED
        routeEntryNonce.intValue++
        addPointMode.value = true
        routeMode.value = true
        layersOnForAi()   // ROUTEFILTER-2026-11-03 (Fred 18:27): Build it myself shows the current selection first too
        js("window.__routeMode=true;setRouteMode(true)")
        persist()
    }

    /**
     * CLOSEENDS-2026-11-24 (Fred 09:54: "on X exit clear the route and turn route mode off"):
     * THE TOOLBAR X ENDS ROUTE ADD.
     *
     * It used to turn route mode off and leave the vertices and the drawn line in place
     * (ROUTECLOSE-2026-09-02, "nothing is torn down"). That stranded the rider: precheck()'s first
     * line is `if (routeMode.value) return true`, which returns BEFORE manualChecks() -- so with
     * route mode still on, the next ADD ROUTE press opens nothing and shows nothing, and the
     * autosaved draft is unreachable until a cold start (routeMode is per-process state that
     * nothing reads back at launch, so a warm relaunch keeps it).
     *
     * THE DRAFT ON DISK IS KEPT: Android's onExit deletes it (RouteModule.kt:1151), the X does not,
     * so the autosave surfaces next session as incomplete and needs a real resolution. Removal has
     * its own path -- ADD ROUTE row 4, "Clear leftover routes".
     */
    fun close() {
        RouteManager.clearRoute()
        endBuild()
    }

    /**
     * TWOCHECKS-2026-11-24 (Fred 13:08: "should be two unique checks"): THE MANUAL ROUTE CHECK, and
     * ONLY the manual one. The AI flow keeps its own (RouteModule.startAi():251 "an open compare set
     * first, always"; GtAiFlow.eval()). A manual request is never refused because an AI batch is
     * unfinished, and an AI request is never refused because a manual draft is in progress.
     *
     * The rules are RouteModule.kt:854-861's, unchanged:
     *   already building      -> true, nothing opens
     *   the autosave present  -> resume it (GTROUTE1F-2026-10-23)
     *   any other draft       -> the WIP picker
     *   nothing               -> false, so the manual start begins a new route
     */
    fun manualChecks(): Boolean {
        if (routeMode.value) return true
        val drafts = listDrafts().filter { !isInOpenBatch(it.first) }   // GTROUTE1C-2026-10-23
        recoveryDetected.value = recoveryOpen()
        // GTROUTE1F-2026-10-23: the unnamed route is loaded and building carries on -- no prompt.
        if (drafts.any { it.first == UNNAMED }) { resumeReq.value = UNNAMED; return true }
        if (drafts.isNotEmpty() || recoveryDetected.value) { showPicker(); return true }
        return false
    }

    /**
     * IOSMANUAL-2026-11-24: THE RESUME -- RouteModule.kt's resume effect as one body, plus the two
     * things Fred asked for on 11-24: the map CENTRED ON THE ROUTE at z10 (it can be 100 miles from
     * where the map was left, so it came back invisibly), and the recovery SAID OUT LOUD.
     * Suspends: the build line is an IO read through the host.
     */
    suspend fun resumeDraft(name: String) {
        if (name.isEmpty()) return
        val method = loadDraft(name)
        routeName.value = name
        routeMethod.intValue = when (method) {
            "draw" -> com.geeksville.mesh.convoy.ROUTE_METHOD_DRAW
            "suggest" -> com.geeksville.mesh.convoy.ROUTE_METHOD_SUGGEST
            else -> ROUTE_METHOD_P2P
        }
        routeLifecycleState.intValue = com.geeksville.mesh.convoy.ROUTE_LS_RESUMED
        routeEntryNonce.intValue++
        addPointMode.value = true
        routeMode.value = true
        persist()
        val pts = buildLineById()
        js("window.__routeMode=true;setRouteMode(true); drawBuildLine('" + pts + "')")
        val vs = RouteManager.routeVertices()
        if (vs.isNotEmpty()) {
            var fS = vs[0].lat; var fN = vs[0].lat; var fW = vs[0].lon; var fE = vs[0].lon
            for (v in vs) {
                if (v.lat < fS) fS = v.lat
                if (v.lat > fN) fN = v.lat
                if (v.lon < fW) fW = v.lon
                if (v.lon > fE) fE = v.lon
            }
            // ROUTEZ15-2026-11-24 (Fred 13:25: "I now suggest z15 as it registers clicks
            // consistently"): a STATED zoom, not a fit -- a 1- or 2-point draft has no extent to fit,
            // and fitBounds zoomed to maximum on it. Shared, so both devices frame recovery alike.
            // ⚠ THE ZOOM IS ALSO A TAP FIX: the page swallows a map click within 400 ms of a MARKER
            //   tap (vec_spatial.js:258, set by vec_carts.js:31 and vec_spatial.js:178, both with
            //   stopPropagation). Zoomed out, the carts and waypoints sit on top of the route and eat
            //   the taps; at 15 they separate and every tap registers. Do not lower this without
            //   moving that guard BELOW the route-mode branch.
            js("setView(" + ((fS + fN) / 2.0) + "," + ((fW + fE) / 2.0) + ",15)")
            toast("Recovered an in-progress route - " + vs.size + (if (vs.size == 1) " point" else " points") +
                  ". Save it or discard it.")
        }
        // GTROUTE1G-2026-10-23: the second draw. Re-read, because a host whose build line is
        // viewport-based has only just had the map moved onto the route.
        kotlinx.coroutines.delay(400)
        js("drawBuildLine('" + buildLineById() + "')")
        log.i { "IOSMANUAL-2026-11-24 resumed '" + name + "' verts=" + vs.size + " method=" + (method ?: "null") }
    }

    /**
     * TWOCHECKS-2026-11-24: the manual check's OWN read of the AI batch file -- RouteDraftStore
     * isInOpenBatch (:306-310) as the rules have it. READ ONLY: no heal, no delete, because this is
     * called once per draft while the manual list is filtered. It reaches the file through the json
     * primitive, not through the AI flow.
     */
    private fun draftIsInOpenBatch(name: String): Boolean {
        if (name.isEmpty()) return false
        val text = GtRoute.env.jsonRead("batch/open_batch.json") ?: return false
        val b = (runCatching { GtOrgJson.parse(text) }.getOrNull()) as? JsonObject ?: return false
        val arr = b["routes"] as? JsonArray ?: return false
        for (i in 0 until arr.size) {
            val n = (arr.getOrNull(i) as? JsonObject)?.let { GtOrgJson.optString(it, "name") }.orEmpty()
            if (n == name) return true
        }
        return false
    }

    /** Route finished or discarded: clear the build line, route mode off. */
    fun endBuild() {
        log.i { "ROUTETRACE-2026-11-24 endBuild ENTER verts=" + RouteManager.routeVertices().size + " name='" + routeName.value + "'" }
        js("setRouteMode(false); clearBuildLine();")
        addPointMode.value = false
        routeMode.value = false
        persist()
    }

    /**
     * VERTEXDROP-2026-11-23: THE BUILD LINE'S ASSEMBLY -- RouteModule.kt:890-894, verbatim.
     * PURE: the rows are the host's (Android SpatialDbManager, iPad GtSpatial), the assembly is
     * one body for both platforms. Android's buildLine(ctx) keeps its signature and calls this.
     */
    fun buildLinePts(trails: List<Map<String, String?>>, tracks: List<Map<String, String?>>): String {
        val byId = HashMap<String, String>()
        for (m in trails) { val id = m["trail_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
        for (m in tracks) { val id = m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
        return RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
            .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
    }

    /**
     * THE TAP -- RouteModule.kt:922-945, body for body. The host owns the dispatch, so the two
     * reads are suspend seams and this is suspend (IOSFIX-2026-11-19: Dispatchers.IO is internal
     * on Kotlin/Native, so the HOST dispatches, exactly as SearchModule already does).
     */
    suspend fun onTap(lat: Double, lon: Double) {
        log.i { "ROUTETRACE-2026-11-24 onTap GATE routeMode=" + routeMode.value + " addPointMode=" + addPointMode.value + " verts=" + RouteManager.routeVertices().size + " ls=" + routeLifecycleState.intValue + " -> " + (if (!routeMode.value || !addPointMode.value) "IGNORED" else "ACCEPTED") }
        if (!routeMode.value || !addPointMode.value) return
        val b = vp
        val trails = trailsInView(b)
        val mPerPx = 156543.03392 * cos(lat * PI / 180.0) / 2.0.pow(b[4])
        val snapRadiusM = (44.0 * mPerPx).coerceIn(15.0, 150.0)
        val s = RouteManager.snapTrailsOnly(lat, lon, trails, snapRadiusM)
        log.d { "GTROUTE1 tap " + lat + "," + lon + " zoom=" + b[4] + " radius=" + snapRadiusM.toInt() +
            "m trails=" + trails.size + " -> " + (if (s != null) "SNAPPED " + s.lineType + " " + s.lineId else "FREE") }
        val v = if (s != null) RouteManager.snapToVertex(s) else RouteManager.freeVertex(lat, lon)
        if (RouteManager.routeVertices().isEmpty()) {
            val nearby = trailheadCountNear(lat, lon)
            log.i { "GTROUTE1 FIRSTPOINT at " + lat + "," + lon + " -- trailheads near: " + nearby }
        }
        RouteManager.addVertex(v)
        autosave()
        log.i { "ROUTETRACE-2026-11-24 onTap DREW verts=" + RouteManager.routeVertices().size + " name='" + routeName.value + "' (autosave written from memory)" }
        js("drawBuildLine('" + buildLine() + "')")
    }
}
