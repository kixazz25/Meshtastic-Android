package com.geeksville.mesh.grouptrack

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.geeksville.mesh.convoy.ROUTE_LS_NEW
import com.geeksville.mesh.convoy.ROUTE_METHOD_P2P
import com.geeksville.mesh.convoy.RouteManager
import com.grouptrack.shared.log.gtLogger
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

    // ---- THE STATE (was RouteModule.kt:90-96) ------------------------------------------------
    /** RouteDraftStore.UNNAMED -- the unnamed-draft literal. Live rider data: never reworded. */
    const val UNNAMED = "Auto Saved In Progress"
    val routeMode = mutableStateOf(false)
    val addPointMode = mutableStateOf(false)
    val routeName = mutableStateOf("")
    val routeEntryNonce = mutableIntStateOf(0)
    val routeMethod = mutableIntStateOf(ROUTE_METHOD_P2P)
    val routeLifecycleState = mutableIntStateOf(ROUTE_LS_NEW)

    /** RouteModule.kt:873 methodStr() -- the draft's "method" field. AUTOSAVE-2026-11-23 */
    fun methodStr(): String = when (routeMethod.intValue) {
        com.geeksville.mesh.convoy.ROUTE_METHOD_DRAW -> "draw"
        com.geeksville.mesh.convoy.ROUTE_METHOD_SUGGEST -> "suggest"
        else -> "point"
    }

    /** Launch Route+ in ONE mode, locked (stage 1: Build it myself). A new route starts as the UNNAMED draft. */
    fun startNew(method: Int) {
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

    /** Leave Route+ (toolbar X): nothing is torn down -- what is on disk stays, and the picker finds it (ROUTECLOSE-2026-09-02). */
    fun close() {
        routeMode.value = false
        addPointMode.value = false
        js("window.__routeMode=false;setRouteMode(false)")
        persist()
    }

    /** Route finished or discarded: clear the build line, route mode off. */
    fun endBuild() {
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
        js("drawBuildLine('" + buildLine() + "')")
    }
}
