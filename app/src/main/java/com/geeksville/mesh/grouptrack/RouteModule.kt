package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable   // GTAI5E-2026-10-23
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding   // GTAI2-2026-10-23
import androidx.compose.foundation.layout.fillMaxSize   // GTAI3-2026-10-23
import androidx.compose.foundation.layout.fillMaxHeight   // GTAI5-2026-10-23
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width   // PLANTABLE-2026-11-03
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.ConvoyRouteToolbar
import com.geeksville.mesh.convoy.MapStateStore
import com.geeksville.mesh.convoy.ROUTE_LS_NEW
import com.geeksville.mesh.convoy.ROUTE_LS_RESUMED
import com.geeksville.mesh.convoy.ROUTE_METHOD_DRAW
import com.geeksville.mesh.convoy.ROUTE_METHOD_P2P
import com.geeksville.mesh.convoy.ROUTE_METHOD_SUGGEST
import com.geeksville.mesh.convoy.RouteDraftStore
import com.geeksville.mesh.convoy.RouteManager
import com.geeksville.mesh.convoy.SpatialDbManager
import kotlinx.coroutines.launch

/*
 * GTROUTE1-2026-10-23 (2.7c, Fred 13:09-13:35): ROUTE+ STAGE 1 -- "Build it myself" on ONE MAP.
 *
 * Route+ lived ONLY inside the planner screen (ConvoyMapViewerScreen: ~40 locals + every dialog). This is the planner's
 * manual Route+, COPIED (the planner keeps its own until it is retired -- 09-06 method), as ONE module a map calls with
 * its page ([GtRoute.js]) and its view ([GtRoute.vp]). Every engine call is the planner's: RouteManager (points, trail
 * snap, segments), RouteDraftStore (SHARED drafts -- Fred 13:16: no map name), SpatialDbManager (save, notes, anchor,
 * trailheads). The open/draft STATE is per map (MapStateStore.RouteState, written by the host map under its own key).
 *
 * ENTRY ORDER (Fred 13:18, 13:35) -- the checks come BEFORE the manual / AI choice; leftover AI routes are NEVER deleted
 * here (only from the compare table):
 *   1. an open AI compare set           -> says so (the compare table itself comes with stage 2: AI route design)
 *   2. an unnamed draft                 -> the unnamed-draft resolver (Keep with a name / Discard)
 *   3. any in-progress draft or a route left open by a crash -> the in-progress picker (resume / delete / new)
 *   4. nothing pending                  -> the Route panel's choices; Build it myself launches LOCKED (one mode, the other
 *                                          not shown -- ConvoyRouteToolbar methodLocked).
 */
object GtRoute {
    /** The host map's page; set by the host (ConvoyScreen) -- runs one JS call. */
    var js: (String) -> Unit = {}
    /** The host map's last view: S, W, N, E, zoom -- set by the host's onViewportChanged. */
    @Volatile var vp: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 15.0)
    /** The host map's state save (writes RouteState under the map's own key). */
    var persist: () -> Unit = {}
    // GTAI2-2026-10-23: set by the map that hosts Route+. wv -- the map's WebView: null until the page exists (CODE RULE 1: the
    // planner's drawBatch takes WebView?, and before the page loads there is nothing to draw on). layersNow -- the map's four layer
    // switches (Trails, Tracks, Waypoints, Routes), so the compare table can put them back exactly as they were.
    var wv: () -> android.webkit.WebView? = { null }
    var layersNow: () -> List<Int> = { emptyList() }
    // GTAI2-2026-10-23: the compare table -- the planner's batch grid state, in one place.
    val batchOpen = mutableStateOf(false)
    val batchName = mutableStateOf("")
    val batchRows = mutableStateOf<List<com.geeksville.mesh.convoy.BatchRow>>(emptyList())
    val batchHidden = mutableStateOf<Set<String>>(emptySet())
    val batchSave = mutableStateOf<Set<String>>(emptySet())
    val batchPrevLayers = mutableStateOf<List<Int>>(emptyList())   // empty = nothing hidden ("" / empty = none, CODE RULE 1)
    val batchAreaPrompt = mutableStateOf(false)
    val batchAreaName = mutableStateOf("")
    val batchDeleteConfirm = mutableStateOf(false)
    val batchSaving = mutableStateOf(false)
    val batchPrevView = mutableStateOf(DoubleArray(0))   // GTAI2B-2026-10-23: the view before the table (S, W, N, E, zoom); empty = none
    private val LAYERS = listOf("Trails", "Tracks", "Waypoints", "Routes")

    val routeMode = mutableStateOf(false)
    val addPointMode = mutableStateOf(false)
    val routeMethod = mutableIntStateOf(ROUTE_METHOD_P2P)
    val routeName = mutableStateOf("")
    val routeLifecycleState = mutableIntStateOf(ROUTE_LS_NEW)
    val routeEntryNonce = mutableIntStateOf(0)
    val showSaveChoice = mutableStateOf(false)
    val showDiscardChoice = mutableStateOf(false)
    val showPicker = mutableStateOf(false)
    val showResolver = mutableStateOf(false)
    val resumeReq = mutableStateOf("")   // GTROUTE1F-2026-10-23: a draft to resume ("" = none); served by GtRouteHost
    val recoveryDetected = mutableStateOf(false)
    val notice = mutableStateOf("")            // "" = none (CODE RULE 1)
    val noticeFilter = mutableStateOf(false)   // AIFAIL-2026-11-03: the notice is a failed AI build -- OPEN FILTER beside OK
    val noticeAllLand = mutableStateOf(false)  // AIFAIL-2026-11-03 v4: the failed build had a land type hidden -- SHOW ALL LAND AND SEARCH AGAIN
    val selection = mutableStateOf("")        // AIFAIL-2026-11-03 v5: the plan's trails selected / left out ("" = not counted yet)
    val checks = mutableStateOf("")           // AIFAILCHECK-2026-11-03: the plan's CHECKS BEFORE THE SEARCH ("" = not run)
    val trailheadOk = mutableStateOf(true)     // AIFAILCHECK-2026-11-03: a shown trail within 1/2 mile -- false = the search may not start
    val selTable = mutableStateOf(com.geeksville.mesh.convoy.RouteExplorer.SelTable())   // PLANTABLE-2026-11-03: the plan's trail table
    val filterPeek = mutableStateOf(false)     // AIFAIL-2026-11-03 v3: FILTER open over the AI plan -- the plan waits behind BACK TO THE PLAN
    val saveOrigName = mutableStateOf("")
    val thPrompt = mutableStateOf(false)
    val thName = mutableStateOf("")
    val draftTick = mutableIntStateOf(0)
    /** true = the in-progress picker's "+ new route" asked for the Route panel's choices (GroupTrack opens it). */
    val wantChooser = mutableStateOf(false)

    /** RouteState for the host map's save: open while Route+ runs (crash recovery reads it). */
    fun routeState(): MapStateStore.RouteState? =
        if (routeMode.value && !aiActive()) MapStateStore.RouteState(open = true, status = "draft", draftName = routeName.value) else null   // GTAI3: a hand-built route only

    /**
     * The Route button, BEFORE its choices (Fred 13:18). true = something pending was shown -- do not open the choices.
     */
    fun precheck(): Boolean {
        if (routeMode.value) return true   // already building -- the toolbar is up
        if (RouteDraftStore.hasOpenBatch()) { openCompare(); return true }   // GTAI2-2026-10-23: an open AI compare set -> the compare table
        return manualChecks()
    }

    /** GTAI2-2026-10-23: the planner's BATCHFORK branch, copied. Route mode stays OFF (a tap on a drawn route must not become a
     *  vertex); the layers are hidden and REMEMBERED -- the table is a detour, anything changed to show it is undone. Nothing is
     *  deleted here: leftover AI routes are only ever removed from the compare table (Fred 13:35). */
    fun openCompare() {
        runCatching {
            batchRows.value = RouteDraftStore.drawBatch(wv())
            batchName.value = RouteDraftStore.readBatch()?.optString("batchName") ?: ""
        }.onFailure { android.util.Log.e("BatchGrid", "batch draw failed: " + it.message, it) }
        batchHidden.value = emptySet()
        batchSave.value = emptySet()
        RouteDraftStore.traceCompare("Route+", "ONE MAP compare table OPENED with " + batchRows.value.size + " row(s)")
        if (batchRows.value.isEmpty()) {
            // VISIBLE: an empty table with no explanation is worse than none. The set is left as it is.
            notice.value = "The AI compare set could not be drawn -- its routes are missing. It is left as it is."
            return
        }
        js("setRouteMode(false)")
        hideAll()   // GTAI4B-2026-10-23 (Fred 15:43): the compare table closes EVERYTHING in the filter (its own Hide everything) + off-grid areas
        // GTAI2B-2026-10-23 (Fred 15:10): show the routes -- fit AFTER the draws (drawBatch POSTS them, so this is posted too).
        batchPrevView.value = vp.copyOf()
        wv()?.let { v -> v.post { runCatching { v.evaluateJavascript("fitBatchRoutes()", null) } } }
        // AICMPCTX-2026-11-11 (Fred 10:07-10:08): the rider's pins and (a loop) the planning perimeter stay on the map with the rides --
        //  from the compare set's own record, so they match the rides shown
        runCatching {
            val bt = RouteDraftStore.readBatch()
            val b = bt?.optJSONObject("recipe") ?: bt   // AICMPCTXFIX-2026-11-11: the pins, trailhead and ceiling live under "recipe"
            if (b != null) {
                val pa = b.optJSONArray("pins")
                val pj = StringBuilder("[")
                if (pa != null) for (i in 0 until pa.length()) { val o = pa.optJSONObject(i) ?: continue
                    if (pj.length > 1) pj.append(","); pj.append("[%.6f,%.6f]".format(java.util.Locale.US, o.optDouble("lat"), o.optDouble("lon"))) }
                pj.append("]")
                val la = b.optDouble("anchorLat", 0.0); val lo = b.optDouble("anchorLon", 0.0); val hi = b.optDouble("milesHigh", 0.0)
                val loopRide = b.isNull("finish") && !(la == 0.0 && lo == 0.0) && hi > 0.0
                val box = if (!loopRide) "null,null,null,null" else {
                    val dLat = hi / 3.0 / 69.0; val dLon = hi / 3.0 / (69.0 * maxOf(0.2, Math.cos(Math.toRadians(la))))
                    "%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.US, la + dLat, la - dLat, lo + dLon, lo - dLon)
                }
                js("if (window.showCompareContext) showCompareContext('" + pj + "'," + box + ")")
            }
        }.onFailure { android.util.Log.e("BatchGrid", "AICMPCTX-2026-11-11: " + it.message) }
        batchOpen.value = true
    }

    /** GTAI2-2026-10-23: leave the table -- the routes come off the map and the layers go back EXACTLY as they were. */
    fun closeCompare() {
        batchOpen.value = false
        batchRows.value = emptyList()
        batchSave.value = emptySet()
        batchHidden.value = emptySet()
        js("clearBatchRoutes(); if (window.clearCompareContext) clearCompareContext();")   // AICMPCTX-2026-11-11
        val prev = batchPrevLayers.value
        restoreAll()   // GTAI4B-2026-10-23: the filter and the off-grid areas back exactly as they were
        batchPrevLayers.value = emptyList()
        // GTAI2B-2026-10-23: the view goes back where it was (zeros = the page never reported a view -- leave it).
        val pv = batchPrevView.value
        if (pv.size == 5 && !(pv[0] == 0.0 && pv[2] == 0.0)) {
            // VIEWBACK-2026-11-03: the page's setView(lat, lon, z) -- map.setView([..]) is Leaflet and threw on the vector map (17:45, 17:46),
            // so leaving the AI compare table never put the view back
            js("setView(" + ((pv[0] + pv[2]) / 2) + ", " + ((pv[1] + pv[3]) / 2) + ", " + pv[4].toInt() + ")")
        }
        batchPrevView.value = DoubleArray(0)
    }

    // ── GTAI3-2026-10-23: AI ROUTE DESIGN -- the planner's guided flow (ConvoyMapViewerScreen pinStep / pin* / gpRunAssess /
    //    onProximityTap / FIND MY RIDES), COPIED into one place. AI either opens the compare table or starts a new build (Fred 14:43).
    val aiStep = mutableIntStateOf(PIN_STEP_NONE)
    val aiBusy = mutableStateOf(false)
    val aiProgress = mutableStateOf("")
    val aiSkipOverview = mutableStateOf(false)
    val pinNotice = mutableStateOf("")
    // AIPERIMCOUNT-2026-11-11 v6 (Fred 09:19): a poor area for AI route planning -> a pop-up the rider must answer (drop pins, or design it anyway)
    val askWarn = mutableStateOf("")
    val pinTrailName = mutableStateOf("")
    val pinTrailLat = mutableStateOf(0.0)
    val pinTrailLon = mutableStateOf(0.0)
    val pinRideName = mutableStateOf("")
    val pinMiLow = mutableIntStateOf(55)
    val pinMiHigh = mutableIntStateOf(80)
    val pinMphLow = mutableIntStateOf(12)
    val pinMphHigh = mutableIntStateOf(18)
    // null = not answered yet -- the planner's three states (loop / point-to-point / unasked), which the steps read (CODE RULE 1).
    val pinIsLoop = mutableStateOf<Boolean?>(null)
    val pinEndLat = mutableStateOf(0.0)
    val pinEndLon = mutableStateOf(0.0)
    val pinPoints = mutableStateOf(listOf<Pair<Double, Double>>())
    // null = not assessed yet -- the mileage HUD shows "working" until the first answer (the planner's, CODE RULE 1).
    val pinFeas = mutableStateOf<com.geeksville.mesh.convoy.RouteExplorer.PinFeasibility?>(null)
    private var pinFeasSeq = 0
    private var pinAssessSeq = 0
    // AIPINWAIT-2026-11-11 (Fred 01:29): ONE pin check at a time -- each builds the whole search-area network (the 00:25 out-of-memory)
    @Volatile private var pinChecking = false
    private var pinCheckAgain = false
    var aiMapReady = false
    // set by the host map: turn Trails, Tracks and Waypoints on for the trailhead step (the planner's AISTEPS layer defaults).
    var layersOnForAi: () -> Unit = {}
    // GTAI4B-2026-10-23 (Fred 15:43): set by the host map -- the compare table closes EVERYTHING in the filter and puts it back.
    var hideAll: () -> Unit = {}
    var restoreAll: () -> Unit = {}
    var compareHid = false                 // the table (not the rider) hid everything -- only then does exit restore it
    var compareOffGrid = BooleanArray(0)   // off-grid areas (Available, Downloading) before the table; empty = none

    fun aiActive(): Boolean = aiStep.intValue != PIN_STEP_NONE

    /** GTAI3: Route panel › AI route design, or the selector's "+ AI route design". NOTHING IS DELETED (Fred 13:35, 14:34) --
     *  the planner cleared leftover AI routes and the unnamed draft here; the AI search does not use them. */
    fun startAi() {
        filterPeek.value = false   // AIFAIL-2026-11-03 v3
        if (routeMode.value) return
        if (RouteDraftStore.hasOpenBatch()) { openCompare(); return }   // an open compare set first, always
        aiProgress.value = ""
        aiBusy.value = false
        clearPins()
        routeMethod.intValue = ROUTE_METHOD_SUGGEST
        addPointMode.value = false        // taps are pins, never vertices
        routeEntryNonce.intValue++
        routeMode.value = true            // the planner's outer gate: route mode on keeps popups off and taps coming to the app
        js("window.__routeMode=true;setRouteMode(true)")
        layersOnForAi()   // GTAI4-2026-10-23 (Fred 15:36): trails on for the AI build -- from the start, not at the trailhead step
        aiMapReady = true
        aiStep.intValue = PIN_STEP_WELCOME
    }

    private fun clearPins() {
        pinTrailName.value = ""; pinTrailLat.value = 0.0; pinTrailLon.value = 0.0
        pinNotice.value = ""; pinIsLoop.value = null; pinEndLat.value = 0.0; pinEndLon.value = 0.0
        pinPoints.value = emptyList(); pinFeas.value = null
    }

    /** the planner's pinReset (Start over): back to the trailhead step. */
    fun pinReset() {
        filterPeek.value = false   // AIFAIL-2026-11-03 v3
        clearPins()
        aiStep.intValue = PIN_STEP_TRAILHEAD
    }

    /** the planner's aiCancelBuild: leave the AI flow completely. Nothing on disk is touched. */
    fun aiCancel() {
        filterPeek.value = false   // AIFAIL-2026-11-03 v3
        clearPins()
        aiStep.intValue = PIN_STEP_NONE
        aiBusy.value = false
        routeMode.value = false
        addPointMode.value = false
        routeMethod.intValue = ROUTE_METHOD_P2P
        js("window.__pinSelect=false;showAimRing(false);clearPins();window.__routeMode=false;setRouteMode(false)")
    }

    /** the planner's gpRunAssess, copied: is every pin reachable, and what does the ride come to? */
    fun runAssess(ctx: android.content.Context) {
        val aLat = pinTrailLat.value
        val aLon = pinTrailLon.value
        if (aLat == 0.0 && aLon == 0.0) { pinFeas.value = null; return }
        val loop = pinIsLoop.value
        val eLat = pinEndLat.value
        val eLon = pinEndLon.value
        val pts = pinPoints.value
        val nm = pinRideName.value
        val mLo = pinMiLow.intValue.toDouble()
        val mHi = pinMiHigh.intValue.toDouble()
        val sLo = pinMphLow.intValue.toDouble()
        val sHi = pinMphHigh.intValue.toDouble()
        if (pinChecking) { pinCheckAgain = true; return }   // AIPINWAIT-2026-11-11: runs when the check in progress finishes
        pinChecking = true
        pinAssessSeq += 1
        val seq = pinAssessSeq
        Thread {
            var out: com.geeksville.mesh.convoy.RouteExplorer.PinFeasibility? = null
            try {
                SpatialDbManager.init(ctx)
                val db = SpatialDbManager.getSpatialDb()
                if (db != null) {
                    val shape = if (loop == false) com.geeksville.mesh.convoy.RouteExplorer.RouteShape.PointToPoint(eLat, eLon)
                                else com.geeksville.mesh.convoy.RouteExplorer.RouteShape.Loop
                    out = com.geeksville.mesh.convoy.RouteExplorer.assess(db,
                        com.geeksville.mesh.convoy.RouteExplorer.Request(anchorLat = aLat, anchorLon = aLon, name = nm,
                            milesLow = mLo, milesHigh = mHi, mphLow = sLo, mphHigh = sHi), pts, shape)
                }
            } catch (e: Exception) {
                android.util.Log.e("GuidedPin", "assess failed: " + e.message)
            }
            val res = out
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                pinChecking = false   // AIPINWAIT-2026-11-11
                if (pinCheckAgain) { pinCheckAgain = false; android.os.Handler(android.os.Looper.getMainLooper()).post { runAssess(ctx) } }
                if (res != null && seq >= pinFeasSeq) {
                    pinFeasSeq = seq
                    pinFeas.value = res
                    if (pinPoints.value.isNotEmpty()) {
                        // PININDEX-2026-10-28: assess numbers the trailhead 0 and the pins 1..n -- the newest pin is pinPoints.size.
                        // Was size - 1: the first pin was never checked and each later drop checked the PREVIOUS pin.
                        val last = pinPoints.value.size
                        val noRoute = res.unreachable.contains(last) || res.onFragment.contains(last)
                        if (noRoute) {
                            pinPoints.value = pinPoints.value.dropLast(1)
                            pinNotice.value = "No route to this pin."
                        } else if (res.overMiles > 0.0) {
                            pinPoints.value = pinPoints.value.dropLast(1)
                            pinNotice.value = "That pin puts the ride past your distance."
                        }
                    }
                }
            }
        }.start()
    }

    // AICONNECTED-2026-11-11 (Fred 12:41): draw the trails connected to the trailhead while pins are dropped -- run like a pin check
    //  (AIPINWAIT), so a tap waits and two network builds never run at once
    fun showConnected(ctx: android.content.Context) {
        val aLat = pinTrailLat.value; val aLon = pinTrailLon.value
        if (aLat == 0.0 && aLon == 0.0) return
        if (pinChecking) return
        pinChecking = true
        val loop = pinIsLoop.value; val eLat = pinEndLat.value; val eLon = pinEndLon.value
        val mLo = pinMiLow.intValue.toDouble(); val mHi = pinMiHigh.intValue.toDouble()
        val sLo = pinMphLow.intValue.toDouble(); val sHi = pinMphHigh.intValue.toDouble()
        Thread {
            val sb = StringBuilder("[")
            try {
                SpatialDbManager.init(ctx)
                val db = SpatialDbManager.getSpatialDb()
                if (db != null) {
                    val shape = if (loop == false) com.geeksville.mesh.convoy.RouteExplorer.RouteShape.PointToPoint(eLat, eLon)
                                else com.geeksville.mesh.convoy.RouteExplorer.RouteShape.Loop
                    val lines = com.geeksville.mesh.convoy.RouteExplorer.connectedLines(db,
                        com.geeksville.mesh.convoy.RouteExplorer.Request(anchorLat = aLat, anchorLon = aLon, name = pinRideName.value,
                            milesLow = mLo, milesHigh = mHi, mphLow = sLo, mphHigh = sHi), shape)
                    for (ln in lines) {
                        if (ln.size < 2) continue
                        if (sb.length > 1) sb.append(',')
                        sb.append('[')
                        for ((pi, p) in ln.withIndex()) { if (pi > 0) sb.append(','); sb.append("[%.5f,%.5f]".format(java.util.Locale.US, p[0], p[1])) }
                        sb.append(']')
                    }
                }
            } catch (e: Exception) { android.util.Log.e("GuidedPin", "AICONNECTED-2026-11-11: failed: " + e.message) }
            sb.append(']')
            val json = sb.toString()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                pinChecking = false
                if (pinCheckAgain) { pinCheckAgain = false; android.os.Handler(android.os.Looper.getMainLooper()).post { runAssess(ctx) } }
                if (aiStep.intValue == PIN_STEP_INCLUDE) js("if (window.showConnected) showConnected('" + json + "')")
            }
        }.start()
    }

    /** the planner's onProximityTap, copied: a tap while the flow asks for a place (the page sends it only while __pinSelect). */
    fun onPinTap(ctx: android.content.Context, lat: Double, lon: Double) {
        val step = aiStep.intValue
        if ((step == PIN_STEP_ENDPOINT || step == PIN_STEP_INCLUDE) && pinChecking) {   // AIPINWAIT-2026-11-11: the last pin is still being checked
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, "Checking your last pin -- one moment.", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (step == PIN_STEP_ENDPOINT) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                pinEndLat.value = lat
                pinEndLon.value = lon
                pinNotice.value = ""
                runAssess(ctx)
            }
            return
        }
        if (step == PIN_STEP_INCLUDE) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val cosLat = Math.cos(Math.toRadians(lat))
                val hit = pinPoints.value.indexOfFirst { p ->
                    val dy = (p.first - lat) * 69.0
                    val dx = (p.second - lon) * 69.0 * cosLat
                    Math.hypot(dy, dx) < PIN_REMOVE_MI
                }
                if (hit >= 0) {
                    pinPoints.value = pinPoints.value.filterIndexed { i, _ -> i != hit }
                    pinNotice.value = ""
                } else if (pinPoints.value.size >= PIN_MAX) {
                    pinNotice.value = "Five places is the most a ride can be planned around. Tap one of your pins to remove it first."
                } else {
                    pinPoints.value = pinPoints.value + (lat to lon)
                    pinNotice.value = ""
                }
                android.widget.Toast.makeText(ctx, pinPoints.value.size.toString() + " of " + PIN_MAX + " places",
                    android.widget.Toast.LENGTH_SHORT).show()
                runAssess(ctx)
            }
            return
        }
        if (step == PIN_STEP_TRAILHEAD) {
            Thread {
                var nm: String? = null
                var la = 0.0
                var lo = 0.0
                try {
                    SpatialDbManager.init(ctx)
                    val hit = gpNearestTrailhead(lat, lon, TRAILHEAD_SELECT_MI)
                    if (hit != null) {
                        nm = hit["name"] ?: "Trailhead"
                        gpParsePointWkt(hit["geometry"])?.let { la = it[0]; lo = it[1] }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GuidedPin", "select failed: " + e.message)
                }
                val fnm = nm
                val trailsOff = layersNow().getOrElse(0) { 0 } == 0   // 0 = DS_OFF (the planner's trailState == DS_OFF)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (fnm == null) {
                        pinNotice.value = "No trailhead there. Tap the trailhead marker itself, or add one with a long press."
                    } else if (trailsOff || SpatialDbManager.getSpatialDb()?.let {
                            com.geeksville.mesh.convoy.RouteExplorer.nearestShownTrailMeters(it, la, lo) } == null) {
                        // AIFAILCHECK-2026-11-03 (Fred 07:16-07:18): 1/2 mile; FILTER, not the map key; a hidden trail inside the ring is named
                        val hid = SpatialDbManager.getSpatialDb()?.let { com.geeksville.mesh.convoy.RouteExplorer.nearestShownTrailMeters(it, la, lo,
                            com.geeksville.mesh.convoy.RouteExplorer.TRAILHEAD_CHECK_M, "") }
                        pinNotice.value = "No trail you are showing is within 1/2 mile of this trailhead (the circle). " +
                            (if (hid != null) "A trail FILTER is hiding is ${"%.0f".format(hid)} m away: turn its land or trail type on in FILTER, " +
                                "or choose a trailhead within 1/2 mile of a trail."
                            else "Choose a trailhead within 1/2 mile of a trail.")
                    } else {
                        pinTrailName.value = fnm
                        pinTrailLat.value = la
                        pinTrailLon.value = lo
                        pinNotice.value = ""
                        aiStep.intValue = PIN_STEP_RETURN
                    }
                }
            }.start()
        }
    }

    // HOME1-2026-10-23 (Fred 21:21 -- the planner-only functions get their home before the planner goes):
    //   a saved route's narrative ("" = none open), the work-in-progress narrative, and the host map's detail card opener.
    val savedNotesRouteId = mutableStateOf("")
    val showWipNotes = mutableStateOf(false)
    var openDetail: (String, String) -> Unit = { _, _ -> }
    var addRide: (String) -> Unit = { _ -> }   // ROUTERIDE-2026-11-05: the host's CREATE A RIDE for a route id

    /** HOME1-2026-10-23: the planner's onBuildFromRecipe, copied -- a saved route's recipe (trailhead, distance, speed, finish,
     *  pins) loads into the AI flow and opens at the SUMMARY, so the rider reads back what is about to be built. An open AI
     *  compare set comes first, as always. Nothing is deleted. */
    fun buildFromRecipe(ctx: android.content.Context, rid: String) {
        val r = SpatialDbManager.routeRecipe(rid) ?: return
        // RECIPEFILTER-2026-11-03 (Fred 19:09): the route's own FILTER comes with it -- same ground, same trails, same routes
        //  (a route saved before the 11-03 AM build recorded none: today's filter, as before)
        buildFromRecipeJson(ctx, r, true)
    }

    /**
     * AIFAIL-2026-11-03: the same from a recipe JSON -- a saved route's (its own FILTER stays: [applyFilter] false), or a recipe file
     * someone sent with an ISSUE report (Route > Build from a recipe file: [applyFilter] true -- the recipe's FILTER values are set
     * first, so the search runs on the same trails).
     */
    fun buildFromRecipeJson(ctx: android.content.Context, r: org.json.JSONObject, applyFilter: Boolean) {
        if (routeMode.value) return
        filterPeek.value = false   // AIFAIL-2026-11-03 v3
        if (applyFilter) applyRecipeFilter(r)
        if (RouteDraftStore.hasOpenBatch()) { openCompare(); return }
        clearPins()
        val aLat = r.optDouble("anchorLat", 0.0)
        val aLon = r.optDouble("anchorLon", 0.0)
        pinTrailLat.value = aLat
        pinTrailLon.value = aLon
        pinTrailName.value = SpatialDbManager.waypointNameAt(aLat, aLon) ?: ""
        pinMiLow.intValue = r.optInt("milesLow", pinMiLow.intValue)
        pinMiHigh.intValue = r.optInt("milesHigh", pinMiHigh.intValue)
        pinMphLow.intValue = r.optInt("mphLow", pinMphLow.intValue)
        pinMphHigh.intValue = r.optInt("mphHigh", pinMphHigh.intValue)
        val fin = r.optJSONObject("finish")
        pinIsLoop.value = fin == null
        if (fin != null) { pinEndLat.value = fin.optDouble("lat", 0.0); pinEndLon.value = fin.optDouble("lon", 0.0) }
        val ps = r.optJSONArray("pins")
        pinPoints.value = if (ps == null) emptyList() else
            (0 until ps.length()).mapNotNull { i -> ps.optJSONObject(i)?.let { p -> Pair(p.optDouble("lat", 0.0), p.optDouble("lon", 0.0)) } }
        aiProgress.value = ""
        aiBusy.value = false
        routeMethod.intValue = ROUTE_METHOD_SUGGEST
        addPointMode.value = false
        routeEntryNonce.intValue++
        routeMode.value = true
        js("window.__routeMode=true;setRouteMode(true)")
        layersOnForAi()   // Trails and Waypoints ON for the AI build (GTAI4B)
        if (applyFilter) applyRecipeFilter(r)   // ROUTEFILTER-2026-11-03: a recipe file's own FILTER wins -- the reported search, exactly
        aiMapReady = true
        // CLOSEPLANNER-2026-08-29: a recipe's pins were never validated on THIS device -- qualify them first
        if (pinPoints.value.isNotEmpty()) runAssess(ctx)
        aiStep.intValue = PIN_STEP_SUMMARY
    }

    /** AIFAIL-2026-11-03: the recipe of the search about to run -- every value the rider chose (the batch recipe's keys, so
     *  Build from recipe reads it), the start's name, and the FILTER panel's values beside the predicate. [finish] null = a loop
     *  (writeBatch's own convention). */
    fun currentRecipe(aLat: Double, aLon: Double, aName: String, mLo: Int, mHi: Int, sLo: Int, sHi: Int,
                      pins: List<Pair<Double, Double>>, finish: Pair<Double, Double>?): org.json.JSONObject {
        val f = com.geeksville.mesh.convoy.TrailFilterState
        return org.json.JSONObject()
            .put("anchorLat", aLat).put("anchorLon", aLon).put("anchorName", aName)
            .put("milesLow", mLo.toDouble()).put("milesHigh", mHi.toDouble())
            .put("mphLow", sLo.toDouble()).put("mphHigh", sHi.toDouble())
            .put("pins", org.json.JSONArray().also { a -> pins.forEach { p -> a.put(org.json.JSONObject().put("lat", p.first).put("lon", p.second)) } })
            .put("finish", if (finish == null) org.json.JSONObject.NULL else org.json.JSONObject().put("lat", finish.first).put("lon", finish.second))
            .put("trailFilter", f.whereOrEmpty())
            .put("filterPanel", org.json.JSONObject().put("land", f.land).put("restricted", f.restricted).put("use", f.use)
                .put("off", org.json.JSONArray(f.off.toList().sorted())))
    }

    /** AIFAIL-2026-11-03: a recipe's FILTER panel values set on this tablet (a recipe with none leaves FILTER as it is). */
    fun applyRecipeFilter(r: org.json.JSONObject) {
        val fp = r.optJSONObject("filterPanel") ?: return
        val f = com.geeksville.mesh.convoy.TrailFilterState
        f.setLand(fp.optString("land", "PUBLIC"))
        f.setRestricted(fp.optBoolean("restricted", true))
        f.setUse(fp.optString("use", "ALL"))
        f.setGroup(f.off.toList(), true)
        val off = fp.optJSONArray("off")
        if (off != null) f.setGroup((0 until off.length()).map { off.optString(it) }, false)
        android.util.Log.i("RouteExplorer", "AIFAIL: recipe FILTER applied -- ${f.whereOrEmpty()}")
    }

    /** AIFAIL-2026-11-03 (Fred 06:48): every value of a recipe as text -- start (name + GPS), miles, mph, finish, places, trail selection. */
    fun recipeText(r: org.json.JSONObject): String {
        val la = r.optDouble("anchorLat", 0.0); val lo = r.optDouble("anchorLon", 0.0)
        fun ll(a: Double, b: Double) = "%.5f, %.5f".format(a, b)
        val nm = r.optString("anchorName", "").takeIf { it.isNotEmpty() && it != "null" } ?: (SpatialDbManager.waypointNameAt(la, lo) ?: "unnamed point")
        val fin = r.optJSONObject("finish")
        val ps = r.optJSONArray("pins")
        val pinTxt = if (ps == null || ps.length() == 0) "none" else
            (0 until ps.length()).joinToString("; ") { i -> ps.optJSONObject(i)?.let { ll(it.optDouble("lat", 0.0), it.optDouble("lon", 0.0)) } ?: "?" }
        val fp = r.optJSONObject("filterPanel")
        val sel = if (fp == null) "filter " + r.optString("trailFilter", "not recorded") else selectionText(fp)
        return "Start: $nm (${ll(la, lo)})\n" +
            "Distance: ${r.optInt("milesLow")}-${r.optInt("milesHigh")} miles\n" +
            "Speed: ${r.optInt("mphLow")}-${r.optInt("mphHigh")} mph\n" +
            "Finish: " + (if (fin == null) "back at the start (loop)" else "at " + ll(fin.optDouble("lat", 0.0), fin.optDouble("lon", 0.0))) + "\n" +
            "Places to include: $pinTxt\n" +
            "Trail selection: $sel"
    }

    /** AIFAIL-2026-11-03 v3: the FILTER panel's values as words (a recipe's, or this tablet's now). */
    fun selectionText(fp: org.json.JSONObject): String {
        run {
            val land = fp.optString("land", "PUBLIC")
            val shown = listOfNotNull(if (land != "PRIVATE") "Public" else null, if (land != "PUBLIC") "Private" else null,
                if (fp.optBoolean("restricted", true)) "Restricted" else null)
            val offA = fp.optJSONArray("off")
            val hidden = (if (offA == null) emptyList() else (0 until offA.length()).map { offA.optString(it) })
                .map { if (it == com.geeksville.mesh.convoy.TrailFilterState.ROW_UNOFFICIAL) "unofficial / uncertain" else it }.sorted()
            return "land shown " + shown.joinToString(", ").ifEmpty { "none" } + "; trail types hidden " + hidden.joinToString(", ").ifEmpty { "none" } +
                (if (fp.optString("use", "ALL") != "ALL") "; " + fp.optString("use").lowercase() + " only" else "")
        }
    }

    /** AIFAIL-2026-11-03 v4 (Fred 06:52): one tap -- Public, Private and Restricted on, the map redrawn, the same search again. */
    fun showAllLandAndSearch(ctx: android.content.Context) {
        val f = com.geeksville.mesh.convoy.TrailFilterState
        f.setLand("ALL"); f.setRestricted(true)
        GtOpenModule.trailsTick.intValue++
        notice.value = ""; noticeFilter.value = false; noticeAllLand.value = false
        android.util.Log.i("RouteExplorer", "AIFAIL: all land on, searching again -- ${f.whereOrEmpty()}")
        findRides(ctx)
    }

    /** AIFAIL-2026-11-03 v5 (Fred 06:54): count the plan's trails selected / left out for the current start, distance and FILTER. */
    fun refreshSelection() {
        val req = com.geeksville.mesh.convoy.RouteExplorer.Request(anchorLat = pinTrailLat.value, anchorLon = pinTrailLon.value,
            name = pinRideName.value, milesLow = pinMiLow.intValue.toDouble(), milesHigh = pinMiHigh.intValue.toDouble(),
            mphLow = pinMphLow.intValue.toDouble(), mphHigh = pinMphHigh.intValue.toDouble(), includePoints = pinPoints.value)
        checks.value = "CHECKS BEFORE THE SEARCH\nchecking…"; trailheadOk.value = true   // AIFAILCHECK-2026-11-03
        Thread {
            val db = SpatialDbManager.getSpatialDb()
            selection.value = if (db == null) "" else com.geeksville.mesh.convoy.RouteExplorer.selectionBreakdown(db, req)
            selTable.value = if (db == null) com.geeksville.mesh.convoy.RouteExplorer.SelTable() else com.geeksville.mesh.convoy.RouteExplorer.selectionTable(db, req)   // PLANTABLE-2026-11-03
            // AIFAILCHECK-2026-11-03 (Fred 07:14-07:18): the two common failures, before the search
            if (db != null) try {
                val RX = com.geeksville.mesh.convoy.RouteExplorer
                val near = RX.nearestShownTrailMeters(db, req.anchorLat, req.anchorLon)
                val c1 = if (near != null) "✓ Trailhead is ${"%.0f".format(near)} m from a trail you are showing" else {
                    val hid = RX.nearestShownTrailMeters(db, req.anchorLat, req.anchorLon, RX.TRAILHEAD_CHECK_M, "")
                    "✗ No trail you are showing is within 1/2 mile of the trailhead -- " +
                        (if (hid != null) "a trail FILTER is hiding is ${"%.0f".format(hid)} m away (SEE / CHANGE FILTER)" else "START OVER with another trailhead")
                }
                // AICHECKNONET-2026-11-10 (Fred 22:48 "why build before the final selection"): no trail network before the search -- the
                //  connected-miles check built the whole network on every plan / step / FILTER change, beside the search's own build
                //  (three Moab networks at once = OutOfMemoryError, 11-10 22:12). -1 = not checked here: c2 below stays empty; the
                //  search builds the network ONCE, on the final selection, and reports a network too short for the distance itself.
                val mi = -1.0
                val lo = req.milesLow.toInt(); val hi = req.milesHigh.toInt()
                val c2 = when {
                    near == null || mi < 0 -> ""
                    mi >= req.milesLow -> "✓ Connected trails from the trailhead: ${mi.toInt()} mi"
                    mi >= req.milesLow / 2 -> "⚠ Connected trails from the trailhead: ${mi.toInt()} mi -- short for $lo-$hi mi; rides may repeat trails or none may fit"
                    else -> "✗ Connected trails from the trailhead: ${mi.toInt()} mi -- not enough for $lo-$hi mi. Lower the distance, or SEE / CHANGE FILTER"
                }
                trailheadOk.value = near != null
                checks.value = "CHECKS BEFORE THE SEARCH\n$c1" + (if (c2.isNotEmpty()) "\n$c2" else "")
            } catch (e: Exception) { checks.value = ""; trailheadOk.value = true; android.util.Log.w("RouteExplorer", "AIFAILCHECK: ${e.message}") }
        }.start()
    }

    /** AIFAILCHECK-2026-11-03: the AI plan ("I am about to design ...") is on screen -- not searching. */
    fun planShowing(): Boolean = routeMode.value && aiStep.intValue == PIN_STEP_SUMMARY && !aiBusy.value

    /** AIFAILCHECK-2026-11-03: the plan on screen as a recipe -- the same keys as a search's. */
    fun planRecipe(): org.json.JSONObject = currentRecipe(pinTrailLat.value, pinTrailLon.value, pinTrailName.value,
        pinMiLow.intValue, pinMiHigh.intValue, pinMphLow.intValue, pinMphHigh.intValue, pinPoints.value,
        if (pinIsLoop.value == false) pinEndLat.value to pinEndLon.value else null)

    private fun planText(): String = recipeText(planRecipe()) + (if (checks.value.isNotEmpty()) "\n" + checks.value else "") +
        (if (selection.value.isNotEmpty()) "\n" + selection.value else "")

    /** AIFAILCHECK-2026-11-03 (Fred 06:59): what an ISSUE from the Route screens carries -- the plan on screen, else the last search. */
    fun issueText(): String = if (planShowing())
        "AI route plan on screen (not searched yet):\n" + planText() + "\n\n" + RouteDraftStore.lastSearchText()
        else RouteDraftStore.lastSearchText()

    /** AIFAILCHECK-2026-11-03: the recipe file an ISSUE email attaches -- the plan on screen, else the last search's record. */
    fun issueRecipeJson(): String = if (planShowing())
        org.json.JSONObject().put("plan", "on screen, not searched yet").put("at", java.time.Instant.now().toString())
            .put("searched", planText()).put("recipe", planRecipe()).toString(2)
        else RouteDraftStore.lastSearchJson()

    /** PLANTABLE-2026-11-03 v2 (Fred 07:43): the plan's table changes FILTER directly -- the map redraws, the table is counted again. */
    private fun filterChanged() { GtOpenModule.trailsTick.intValue++; refreshSelection() }
    fun toggleType(t: String) { com.geeksville.mesh.convoy.TrailFilterState.toggleCategory(t); filterChanged() }
    fun toggleUse(label: String) {
        val f = com.geeksville.mesh.convoy.TrailFilterState
        val motor = label == "Motorized"
        if (!motor && label != "Non-motorized") return
        val mOn = f.use != "NON-MOTORIZED"; val nOn = f.use != "MOTORIZED"
        val m2 = if (motor) !mOn else mOn; val n2 = if (motor) nOn else !nOn
        if (!m2 && !n2) return   // at least one use stays on
        f.setUse(if (m2 && n2) "ALL" else if (m2) "MOTORIZED" else "NON-MOTORIZED"); filterChanged()
    }
    fun toggleLand(pub: Boolean) {
        val f = com.geeksville.mesh.convoy.TrailFilterState
        val p = f.land != "PRIVATE"; val q = f.land != "PUBLIC"
        val p2 = if (pub) !p else p; val q2 = if (pub) q else !q
        if (!p2 && !q2) return   // at least one land type stays on (as FILTER itself)
        f.setLand(if (p2 && q2) "ALL" else if (p2) "PUBLIC" else "PRIVATE"); filterChanged()
    }
    fun toggleRestricted() { com.geeksville.mesh.convoy.TrailFilterState.setRestricted(!com.geeksville.mesh.convoy.TrailFilterState.restricted); filterChanged() }

    /** AIFAIL-2026-11-03 v3: this tablet's FILTER now, as words. */
    fun filterNow(): String {
        val f = com.geeksville.mesh.convoy.TrailFilterState
        return selectionText(org.json.JSONObject().put("land", f.land).put("restricted", f.restricted).put("use", f.use)
            .put("off", org.json.JSONArray(f.off.toList())))
    }

    /** AIFAIL-2026-11-03: what the last search worked on. */
    fun statsText(): String {
        val st = com.geeksville.mesh.convoy.RouteExplorer.lastStats
        fun n(v: Int) = if (v < 0) "not reached" else "%,d".format(v)
        return "Search area: ${st.areaMi.ifEmpty { "not reached" }}\n" +
            "Trails read: ${n(st.trails)}\n" +
            "Trail network from the start: ${n(st.junctions)} junctions in ${n(st.networks)} network(s)\n" +
            "Features in reach: ${n(st.features)}" +
            (if (st.omitted.isNotEmpty()) "\nLeft out by FILTER (trails in the search area; a trail can count twice): ${st.omitted}" else "") +
            (if (st.stop.isNotEmpty()) "\nStopped: ${st.stop}" else "")
    }

    /** the planner's FIND MY RIDES, copied: the search builds the routes and records the compare set; then the COMPARE TABLE
     *  opens on them (ONE MAP goes straight there -- the planner's results panel in between is not copied). */
    fun findRides(ctx: android.content.Context) {
        if (pinChecking) {   // AIPINWAIT-2026-11-11: the search builds its own network -- not while a pin check is building one
            android.widget.Toast.makeText(ctx, "Checking your last pin -- one moment, then press again.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pinNotice.value = ""
        aiStep.intValue = PIN_STEP_SEARCH
        aiBusy.value = true
        aiProgress.value = "Starting"
        val aLat = pinTrailLat.value
        val aLon = pinTrailLon.value
        val rn = pinRideName.value
        val aName = pinTrailName.value   // AIFAIL-2026-11-03
        val mLo = pinMiLow.intValue; val mHi = pinMiHigh.intValue
        val sLo = pinMphLow.intValue; val sHi = pinMphHigh.intValue
        val pins = pinPoints.value
        val loop = pinIsLoop.value
        val eLat = pinEndLat.value; val eLon = pinEndLon.value
        Thread {
            var made = 0
            var err = ""
            var summary = ""   // AIFAIL-2026-11-03
            var lowUsed = mLo   // AIFLOOR10-2026-11-11: the floor the shown rides were found with
            try {
                SpatialDbManager.init(ctx)
                val db = SpatialDbManager.getSpatialDb()
                if (db == null) {
                    err = "No trail data on this device yet"
                } else {
                    fun search(lo: Int) = com.geeksville.mesh.convoy.RouteExplorer.explore(db,
                        com.geeksville.mesh.convoy.RouteExplorer.Request(anchorLat = aLat, anchorLon = aLon, name = rn,
                            milesLow = lo.toDouble(), milesHigh = mHi.toDouble(), mphLow = sLo.toDouble(), mphHigh = sHi.toDouble(),
                            includePoints = pins)
                    ) { p -> aiProgress.value = p.step + (if (p.detail.isNotBlank()) " — " + p.detail else "") }
                    var out = search(mLo)
                    // AIFLOOR10-2026-11-11 (Fred 08:54 "drop the floor to 10 miles and rerun the compare function. our goal is to get trails"):
                    //  nothing in the band -> once more from 10 miles (same ceiling; the network is cached). Not after a cancel.
                    if (out.isEmpty() && mLo > AI_RETRY_FLOOR && aiStep.intValue == PIN_STEP_SEARCH) {
                        android.util.Log.i("RouteExplorer", "AIFLOOR10-2026-11-11: no $mLo-$mHi mile ride -- searching again from $AI_RETRY_FLOOR miles")
                        aiProgress.value = "No $mLo-$mHi mile rides here — looking from $AI_RETRY_FLOOR miles"
                        out = search(AI_RETRY_FLOOR)
                        lowUsed = AI_RETRY_FLOOR
                    }
                    RouteDraftStore.writeBatch(
                        batchName = rn,
                        draftNames = out.map { it.draftName },
                        miles = out.map { it.miles },
                        hoursLow = out.map { it.hoursLow },
                        hoursHigh = out.map { it.hoursHigh },
                        anchorLat = aLat, anchorLon = aLon,
                        anchorName = null,
                        milesLow = lowUsed.toDouble(), milesHigh = mHi.toDouble(),   // AIFLOOR10-2026-11-11: the floor the rides were found with
                        mphLow = sLo.toDouble(), mphHigh = sHi.toDouble(),
                        pins = pins,
                        finish = if (loop == false) eLat to eLon else null,
                    )
                    RouteDraftStore.recordSearchOutcome(out.size, RouteDraftStore.readBatch()?.optString("batchName") == rn, null)
                    RouteDraftStore.traceCompare("AI search end (ONE MAP)", "made " + out.size + " route(s) for '" + rn + "'")
                    made = out.size
                    // AIFAIL-2026-11-03: the recipe and what the search worked on -- kept and logged, success or not
                    val recipe = currentRecipe(aLat, aLon, aName, mLo, mHi, sLo, sHi, pins, if (loop == false) eLat to eLon else null)
                    summary = recipeText(recipe) + "\n" + statsText()
                    RouteDraftStore.recordSearchDetail(summary, recipe)
                    android.util.Log.i("RouteExplorer", "AISEARCH: ${out.size} route(s) | " + summary.replace("\n", " | "))
                }
            } catch (e: Exception) {
                android.util.Log.e("RouteExplorer", "explore failed", e)
                RouteDraftStore.recordSearchOutcome(-1, false, e.message ?: e.toString())
                runCatching {   // AIFAIL-2026-11-03: a failed search keeps its recipe too
                    val recipe = currentRecipe(aLat, aLon, aName, mLo, mHi, sLo, sHi, pins, if (loop == false) eLat to eLon else null)
                    summary = recipeText(recipe) + "\n" + statsText()
                    RouteDraftStore.recordSearchDetail(summary, recipe)
                    android.util.Log.i("RouteExplorer", "AISEARCH: error | " + summary.replace("\n", " | "))
                }
                err = "Could not build rides: " + (e.message ?: "error")
            }
            val fMade = made
            val fLow = lowUsed   // AIFLOOR10-2026-11-11
            val fErr = err
            val fSum = summary   // AIFAIL-2026-11-03
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                aiBusy.value = false
                if (fMade > 0 && RouteDraftStore.hasOpenBatch()) {
                    if (fLow < mLo) android.widget.Toast.makeText(ctx, "No $mLo-$mHi mile rides from this trailhead — showing rides from $fLow miles.",
                        android.widget.Toast.LENGTH_LONG).show()   // AIFLOOR10-2026-11-11
                    // the flow ends; the compare table takes over (it turns route mode off and fits the routes)
                    aiStep.intValue = PIN_STEP_NONE
                    routeMode.value = false
                    routeMethod.intValue = ROUTE_METHOD_P2P
                    js("window.__pinSelect=false;showAimRing(false);clearPins()")
                    clearPins()
                    openCompare()
                } else {
                    // VISIBLE: back to the summary with the reason -- Start over or Cancel build from there.
                    aiStep.intValue = PIN_STEP_SUMMARY
                    // AIFAIL-2026-11-03 (Fred 11-02): the full recipe and what was searched; with any land type hidden, lead with the one
                    //  change that guarantees results -- Public, Private and Restricted on ("private on guarantees results"); OPEN FILTER.
                    val tf = com.geeksville.mesh.convoy.TrailFilterState
                    val allLand = tf.land != "PUBLIC" && tf.land != "PRIVATE" && tf.restricted
                    noticeFilter.value = true
                    noticeAllLand.value = !allLand   // AIFAIL-2026-11-03 v4
                    // AIFEWFEAT-2026-11-10 (Fred 23:27): stopped for lack of features (and no pins dropped) -> say so, with the count, and how to
                    //  get rides: drop points on the trails -- the planner builds around them (dropped points are features to it).
                    val fst = com.geeksville.mesh.convoy.RouteExplorer.lastStats
                    val fewFeatures = fErr.isEmpty() && pins.isEmpty() &&
                        (fst.stop.startsWith("no features") || fst.stop.contains("features here, but no ride"))
                    val nf = if (fst.features < 0) 0 else fst.features
                    // AIFAILNOTICE-2026-11-11 (Fred 08:23): say what this trailhead can reach -- features AND miles of connected trail
                    val tmi = fst.trailMiles
                    val reach = "from this trailhead the planner can reach " + (if (nf == 1) "1 feature" else "$nf features") +
                        (if (tmi >= 0.0) " on " + Math.round(tmi) + " miles of connected trail" else "")
                    // AIFAILLONGEST-2026-11-11 (Fred 08:24): what IS possible from here -- the longest loop the search solved, when under the floor
                    val lmi = fst.longestMiles
                    val best = if (lmi > 0.0 && lmi < mLo) "The longest ride the planner can build here is about " + Math.round(lmi) +
                        " miles (through " + fst.longestFeatures + " features). Over rough ground a ride that long can be a full day -- " +
                        "set the distance to about " + Math.round(lmi) + " miles to see it.\n\n" else ""
                    // AIFAILSHORT-2026-11-11 (Fred 08:29 "the message should be smaller"): one line of what is here, Fred's line, one line of what to do
                    val here = (if (nf == 1) "1 feature" else "$nf features") +
                        (if (tmi >= 0.0) " on " + Math.round(tmi) + " miles of connected trail" else "") + " from this trailhead." +
                        (if (lmi > 0.0 && lmi < mLo) " The longest ride here is about " + Math.round(lmi) + " miles." else "")
                    notice.value = (if (fErr.isNotEmpty()) fErr + "\n\n"
                        else if (fewFeatures || fst.features >= 0) "Not a good area for a $mLo-$mHi mile ride: " + here + "\n\n"
                        else "No rides could be built from that trailhead and distance.\n\n") +
                        (if (!allLand) "Some land types are hidden. Turn on Public, Private and Restricted in FILTER and search again: " +
                            "every trail is then searched, and the map shows which parts cross private land and which are closed to " +
                            "motor vehicles.\n\n"
                        else "") +
                        // AIFAILNOTICE-2026-11-11 (Fred 08:22): the instruction on every failed search
                        "With so few features and trail connections, the manual route planner is the better choice here. Or START OVER: " +   // AIFAILSHORT-2026-11-11
                        "pick a trailhead with more connected trails inside the orange rectangle, or drop pins along connected trails.\n\n" +
                        "What was searched:\n" + fSum.ifEmpty { "(not recorded)" } +
                        (if (selection.value.isNotEmpty()) "\n\nTrails in this search:\n" + selection.value else "") +
                        (if (!allLand) "\n\nSHOW ALL LAND AND SEARCH AGAIN does that in one tap. " else "\n\n") +
                        "OPEN FILTER to see or change the trail selection, then BACK TO THE PLAN to search again. " +
                        "To report it, tap ISSUE: the report carries this search and its recipe."
                }
            }
        }.start()
    }

    /** GTROUTE1B-2026-10-23: the manual checks after the AI check. true = something pending was shown. */
    /** GTROUTE1C-2026-10-23 (Fred 14:05): drafts the rider is building -- an open AI compare set's routes are left out. */
    fun manualDrafts() = RouteDraftStore.listDrafts().filter { !RouteDraftStore.isInOpenBatch(it.name) }

    fun manualChecks(): Boolean {
        val drafts = manualDrafts()   // GTROUTE1C-2026-10-23: the AI compare set's drafts are not in-progress routes
        recoveryDetected.value = MapStateStore.readMap(MapStateStore.rideKey()).routeState?.open == true
        // GTROUTE1F-2026-10-23 (Fred 14:33): the unnamed route is loaded and building carries on -- no prompt.
        if (drafts.any { it.name == RouteDraftStore.UNNAMED }) { resumeReq.value = RouteDraftStore.UNNAMED; return true }
        if (drafts.isNotEmpty() || recoveryDetected.value) { draftTick.intValue++; showPicker.value = true; return true }
        return false
    }

    /** Launch Route+ in ONE mode, locked (stage 1: Build it myself). A new route starts as the UNNAMED draft. */
    fun startNew(method: Int) {
        routeLifecycleState.intValue = ROUTE_LS_NEW
        routeMethod.intValue = method
        routeName.value = RouteDraftStore.UNNAMED
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

    private fun methodStr(): String = when (routeMethod.intValue) { ROUTE_METHOD_DRAW -> "draw"; ROUTE_METHOD_SUGGEST -> "suggest"; else -> "point" }

    /** Auto-save the draft (after every point, as the planner does). */
    fun autosave() {
        val nm = routeName.value
        if (nm.isBlank()) return
        runCatching {
            if (RouteDraftStore.draftExists(nm)) RouteDraftStore.overwriteDraft(nm, methodStr()) else RouteDraftStore.writeDraft(nm, methodStr())
        }
    }

    /** The build line from the current points, against the trails and tracks in view (IO). */
    fun buildLine(ctx: android.content.Context): String {
        val v = vp
        SpatialDbManager.init(ctx)
        val tl = SpatialDbManager.queryTrailsByViewport(v[0], v[1], v[2], v[3])
        val tk = SpatialDbManager.queryTracksByViewport(v[0], v[1], v[2], v[3])
        val byId = HashMap<String, String>()
        for (m in tl) { val id = m["trail_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
        for (m in tk) { val id = m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
        return RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
            .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
    }

    /** The build line for a RESUMED draft: the snapped lines by id (it may lie outside the view). (IO) */
    fun buildLineById(ctx: android.content.Context): String {
        SpatialDbManager.init(ctx)
        val verts = RouteManager.routeVertices()
        val byId = HashMap<String, String>()
        byId.putAll(SpatialDbManager.queryGeomByIds(verts.filter { it.snapped && it.lineType == "trail" }.mapNotNull { it.lineId }, "trail"))
        byId.putAll(SpatialDbManager.queryGeomByIds(verts.filter { it.snapped && it.lineType == "track" }.mapNotNull { it.lineId }, "track"))
        return RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
            .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
    }

    /**
     * A map tap while Route+ runs -- the planner's onMapTap, as is: snap to TRAILS ONLY within 44 px (15-150 m) at this
     * zoom, else a free point; log trailheads near the first point; add it; auto-save; redraw.
     */
    fun onTap(ctx: android.content.Context, lat: Double, lon: Double) {
        if (!routeMode.value || !addPointMode.value) return
        kotlinx.coroutines.MainScope().launch {
            val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SpatialDbManager.init(ctx)
                val b = vp
                val trails = SpatialDbManager.queryTrailsByViewport(b[0], b[1], b[2], b[3])
                val mPerPx = 156543.03392 * kotlin.math.cos(Math.toRadians(lat)) / Math.pow(2.0, b[4])
                val snapRadiusM = (44.0 * mPerPx).coerceIn(15.0, 150.0)
                val s = RouteManager.snapTrailsOnly(lat, lon, trails, snapRadiusM)
                android.util.Log.d("RouteBridge", "GTROUTE1 tap $lat,$lon zoom=${b[4]} radius=${snapRadiusM.toInt()}m trails=${trails.size} -> " +
                    if (s != null) "SNAPPED ${s.lineType} ${s.lineId}" else "FREE")
                if (s != null) RouteManager.snapToVertex(s) else RouteManager.freeVertex(lat, lon)
            }
            if (RouteManager.routeVertices().isEmpty()) {
                val nearby = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SpatialDbManager.trailheadsNear(lat, lon) }
                android.util.Log.i("RouteBridge", "GTROUTE1 FIRSTPOINT at $lat,$lon -- trailheads near: ${nearby.size}")
            }
            RouteManager.addVertex(v)
            autosave()
            val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { buildLine(ctx) }
            js("drawBuildLine('$pts')")
        }
    }
}

/**
 * GTROUTE1-2026-10-23: everything Route+ shows on the host map -- the locked toolbar, save / discard, the in-progress picker,
 * the unnamed-draft resolver, the missing-trailhead prompt, the notice. HELP and X on each (10-22 standard).
 */
@Composable
fun GtRouteHost(toolbarModifier: Modifier, search: GtSearchBridge) {   // GTAI5-2026-10-23: + the map's search (the AI trailhead step)   // GTROUTE1D-2026-10-23: where the toolbar opens -- the caller's map decides
    val ctx = LocalContext.current
    val R = GtRoute
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // The planner's saveCompleted, as is: trailhead within 0.1 mi of the start (else the prompt), then the route, its
    // notes and its trailhead anchor; drafts deleted; route mode off.
    val saveCompleted: () -> Unit = {
        val b = R.vp
        val name = R.routeName.value
        kotlinx.coroutines.MainScope().launch {
            val thFirst = RouteManager.routeVertices().firstOrNull()
            val thNear = thFirst?.let { f ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    SpatialDbManager.init(ctx); SpatialDbManager.trailheadsNear(f.lat, f.lon, 0.1)
                }
            } ?: emptyList()
            if (thFirst != null && thNear.isEmpty()) {
                android.util.Log.i("RouteSave", "GTROUTE1 save stopped -- no trailhead near the start")
                R.thName.value = name.ifBlank { "Route" } + " trailhead"
                R.thPrompt.value = true
                return@launch
            }
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SpatialDbManager.init(ctx)
                val lines = SpatialDbManager.queryTrailsByViewport(b[0], b[1], b[2], b[3]) +
                    SpatialDbManager.queryTracksByViewport(b[0], b[1], b[2], b[3])
                val byId = HashMap<String, String>()
                for (m in lines) { val id = m["trail_id"] ?: m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                val built = RouteManager.buildWktAndBbox { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                if (built != null) {
                    val (wkt, bbox) = built
                    val newRouteId = SpatialDbManager.insertRoute(name.ifBlank { "Route " + System.currentTimeMillis() },
                        wkt, bbox[0], bbox[1], bbox[2], bbox[3])
                    RouteDraftStore.readNotes(name)?.let { nts -> SpatialDbManager.writeRouteNotes(newRouteId, nts) }
                    RouteManager.routeVertices().firstOrNull()?.let { first ->
                        SpatialDbManager.trailheadsNear(first.lat, first.lon, 0.1).firstOrNull()?.let { th ->
                            val ok = SpatialDbManager.setRouteAnchor(newRouteId, th.lat, th.lon, th.name)
                            android.util.Log.i("RouteSave", "GTROUTE1 anchor '${th.name}' -> $newRouteId ok=$ok")
                        }
                    }
                    true
                } else false
            }
            if (res) {
                RouteManager.clearRoute()
                RouteDraftStore.deleteDraft(name)
                RouteDraftStore.deleteDraft(RouteDraftStore.UNNAMED)
                R.draftTick.intValue++
                R.endBuild()
                R.js("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}")
                android.widget.Toast.makeText(ctx, "Route “$name” saved.", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(ctx, "Need at least 2 points to save", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // GTROUTE1F-2026-10-23: THE resume path (selector row, the unnamed route) -- the picker's body, moved unchanged.
    androidx.compose.runtime.LaunchedEffect(R.resumeReq.value) {
        val name = R.resumeReq.value
        if (name.isNotEmpty()) {
            R.resumeReq.value = ""
            val od = RouteDraftStore.loadIntoRouteManager(name)
            R.routeName.value = name
            R.routeMethod.intValue = when (od?.method) { "draw" -> ROUTE_METHOD_DRAW; "suggest" -> ROUTE_METHOD_SUGGEST; else -> ROUTE_METHOD_P2P }
            R.routeLifecycleState.intValue = ROUTE_LS_RESUMED
            R.routeEntryNonce.intValue++
            R.addPointMode.value = true
            R.routeMode.value = true
            R.persist()
            // GTROUTE1G-2026-10-23: the host's scope -- clearing resumeReq restarts this effect and would cancel the draw.
            scope.launch {
                val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { R.buildLineById(ctx) }
                R.js("window.__routeMode=true;setRouteMode(true); drawBuildLine('$p')")
                kotlinx.coroutines.delay(400)
                R.js("drawBuildLine('$p')")
            }
        }
    }

    // ── GTAI3-2026-10-23: the AI flow's map effects (the planner's LaunchedEffect(pinStep) / LaunchedEffect(pinPoints), copied) ──
    androidx.compose.runtime.LaunchedEffect(R.aiStep.intValue) {
        val step = R.aiStep.intValue
        val on = step == PIN_STEP_TRAILHEAD || step == PIN_STEP_ENDPOINT || step == PIN_STEP_INCLUDE
        R.js("window.__pinSelect=" + on + ";")
        if (step == PIN_STEP_WELCOME) {
            (ctx as? android.app.Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        R.js("showAimRing(" + (step == PIN_STEP_TRAILHEAD) + ")")
        // GTAI4B-2026-10-23 (Fred 15:44): Trails and Waypoints STAY ON through the build -- set again at every trailhead step.
        if (step == PIN_STEP_TRAILHEAD) {
            R.aiMapReady = true
            R.layersOnForAi()
        }
        if (step == PIN_STEP_NONE) {
            R.aiMapReady = false
            // the lock cannot outlive the flow (the planner's DisposableEffect)
            (ctx as? android.app.Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    androidx.compose.runtime.LaunchedEffect(R.pinPoints.value) {
        val json = R.pinPoints.value.joinToString(",", "[", "]") { "[%.6f,%.6f]".format(it.first, it.second) }
        R.js("renderPins('" + json + "')")
    }
    // AICONNECTED-2026-11-11 (Fred 12:41): the connected trails, in green, while pins are dropped; cleared on every other step
    androidx.compose.runtime.LaunchedEffect(R.aiStep.intValue, R.pinTrailLat.value, R.pinTrailLon.value, R.pinMiHigh.intValue, R.pinIsLoop.value) {
        if (R.aiStep.intValue == PIN_STEP_INCLUDE) R.showConnected(ctx) else R.js("if (window.clearConnected) clearConnected();")
    }
    // AIPERIMETER-2026-11-11 (Fred 07:48): the planning perimeter while pins are added -- the SAME box as the search's corridorBox
    //  (ceiling / 3 each way from the trailhead). Loop rides only; cleared on every other step.
    androidx.compose.runtime.LaunchedEffect(R.aiStep.intValue, R.pinTrailLat.value, R.pinTrailLon.value, R.pinMiHigh.intValue, R.pinIsLoop.value) {
        val st = R.aiStep.intValue
        val la = R.pinTrailLat.value; val lo = R.pinTrailLon.value
        val show = (st == PIN_STEP_ASK || st == PIN_STEP_INCLUDE) && R.pinIsLoop.value != false && !(la == 0.0 && lo == 0.0)   // AIPERIMCOUNT-2026-11-11 v3: from the ASK step
        if (!show) { R.js("if (window.clearPerimeter) clearPerimeter();") } else {
            val rMi = R.pinMiHigh.intValue.toDouble() / 3.0
            val dLat = rMi / 69.0
            val dLon = rMi / (69.0 * maxOf(0.2, Math.cos(Math.toRadians(la))))
            // AIPERIMCOUNT-2026-11-11 (Fred 08:36 / 09:09-09:10): the features inside the rectangle and how far out they reach -- one query,
            //  the search's own feature rule (no network). Reach decides: a ride is a loop through features, so they must reach out.
            val nb = la + dLat; val sb = la - dLat; val eb = lo + dLon; val wb = lo - dLon
            val kx = 69.0 * Math.cos(Math.toRadians(la))
            val fr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var c = -1; var far = 0.0
                try {
                    SpatialDbManager.init(ctx)
                    SpatialDbManager.getSpatialDb()?.rawQuery(
                        "SELECT lat, lon FROM reference_points WHERE name IS NOT NULL AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
                            "AND COALESCE(fclass, 'other') NOT IN ('county','city','town','tree','village','locality','hamlet','suburb','farm')",
                        arrayOf(sb.toString(), nb.toString(), wb.toString(), eb.toString()))?.use { cur ->
                        c = 0
                        while (cur.moveToNext()) {
                            c++
                            val dy = (cur.getDouble(0) - la) * 69.0; val dx = (cur.getDouble(1) - lo) * kx
                            far = maxOf(far, Math.sqrt(dx * dx + dy * dy))
                        }
                    }
                } catch (e: Exception) { android.util.Log.e("GuidedPin", "AIPERIMCOUNT-2026-11-11: count failed: " + e.message) }
                c to far
            }
            val nf = fr.first; val farMi = fr.second
            val mLoAsk = R.pinMiLow.intValue
            android.util.Log.i("GuidedPin", "AIPERIMCOUNT-2026-11-11: " + nf + " feature(s) in the planning perimeter, the farthest " +
                "%.1f".format(farMi) + " mi from the trailhead (minimum asked " + mLoAsk + " mi)")
            if (st == PIN_STEP_ASK && nf >= 0) {   // AIPERIMCOUNT-2026-11-11 v5 (Fred 09:10): good candidate, or drop pins -- decided on REACH
                val nfs = "approximately " + nf + (if (nf == 1) " feature" else " features") +
                    (if (nf > 0) ", the farthest about " + Math.round(farMi) + " miles from the trailhead" else "")
                val goodAi = nf > 0 && farMi * AI_REACH_FACTOR >= mLoAsk
                R.pinNotice.value = if (goodAi) "This area contains " + nfs +
                        " and is a good candidate for the AI build. Answer NO and the ride is designed for you, or drop pins to steer it."
                    else "This area is not a good candidate for AI route planning because of the lack of connected trails and features " +
                        "(" + nfs + ")." + (if (mLoAsk > AI_RETRY_FLOOR) " The minimum ride distance has been lowered from " + mLoAsk + " to " +
                        AI_RETRY_FLOOR + " miles so the planner builds what this network can carry." else "") +
                        " We suggest you add pins: each adds a feature and the trails it is on, to extend the ride network. " +
                        "Pins can only be dropped on trails connected to this trailhead's trail network: a pin with no route to connect it is not accepted."
                R.askWarn.value = if (goodAi) "" else R.pinNotice.value   // AIPERIMCOUNT-2026-11-11 v6: the pop-up
                // AIPERIMCOUNT-2026-11-11 v11 (Fred 09:34 "do not require the failure before we lower trail distance"): a poor area lowers the
                //  request's minimum NOW, so the first search already runs from 10 miles (the review screen shows it)
                if (!goodAi && mLoAsk > AI_RETRY_FLOOR) {
                    R.pinMiLow.intValue = AI_RETRY_FLOOR
                    android.util.Log.i("GuidedPin", "AIPERIMCOUNT-2026-11-11: poor area -- minimum lowered from " + mLoAsk + " to " + AI_RETRY_FLOOR + " mi")
                }
            }
            R.js("if (window.showPerimeter) showPerimeter(" + "%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.US,
                nb, sb, eb, wb) + "," + nf + ");")
        }
    }

    // ── the toolbar: LOCKED to the launched method (one mode; the other is not shown) ──
    if (R.routeMode.value && !R.aiActive()) {   // GTAI3-2026-10-23: the AI flow has its own screens
        // The back button does not drop a route being built; the toolbar X always leaves (the 10-22 stranding fix).
        androidx.activity.compose.BackHandler(enabled = true) {
            android.widget.Toast.makeText(ctx, "Use X on the route panel to leave route building.", android.widget.Toast.LENGTH_SHORT).show()
        }
        ConvoyRouteToolbar(
            isConvoyMap = false,
            vertexCount = RouteManager.routeVertexCount(),
            routeEntryNonce = R.routeEntryNonce.intValue,
            presetMethod = R.routeMethod.intValue,
            methodLocked = true,
            modifier = toolbarModifier,   // GTROUTE1D-2026-10-23
            selectedMethod = R.routeMethod.intValue,
            onNewRoute = { R.routeLifecycleState.intValue = ROUTE_LS_NEW; R.routeName.value = RouteDraftStore.UNNAMED },
            addArmed = R.addPointMode.value,
            onAddModeChanged = { armed ->
                R.addPointMode.value = armed
                R.js("window.__routeMode=$armed;setRouteMode($armed)")
            },
            onUndo = {
                RouteManager.undoVertex()
                R.autosave()
                scope.launch {
                    val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { R.buildLine(ctx) }
                    R.js("drawBuildLine('$pts')")
                }
            },
            onSaveCompleted = saveCompleted,
            routeLifecycleState = R.routeLifecycleState.intValue,
            onSaveRequested = { R.saveOrigName.value = R.routeName.value; R.showSaveChoice.value = true },
            onDiscardRequested = { R.showDiscardChoice.value = true },
            onSelectInProgress = { R.draftTick.intValue++; R.showPicker.value = true },
            onClose = { R.close() },
            onExit = {
                RouteManager.clearRoute()
                RouteDraftStore.deleteDraft(R.routeName.value)
                R.draftTick.intValue++
                R.endBuild()
            }
        )
    }

    // ── GTAI3-2026-10-23: the AI flow's screens (the planner's, copied): overview + distance, the guided steps with the
    //    mileage HUD, then the summary and the search. Cancel build leaves completely; Back does not drop it.
    if (R.aiActive()) {
        androidx.activity.compose.BackHandler(enabled = true) {
            android.widget.Toast.makeText(ctx, "Use Cancel build to leave AI route design.", android.widget.Toast.LENGTH_SHORT).show()
        }
        val step = R.aiStep.intValue
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            if (step == PIN_STEP_WELCOME || step == PIN_STEP_DISTANCE) {
                GtAiStepPanel(   /* GTAI4-2026-10-23: ONE MAP's copy, HELP X */
                    isOverview = step == PIN_STEP_WELCOME,
                    stepLabel = if (step == PIN_STEP_WELCOME) "1 / 6" else "2 / 6",
                    dontShowAgain = R.aiSkipOverview.value,
                    onDontShowAgain = { R.aiSkipOverview.value = it },
                    miLow = R.pinMiLow.intValue, miHigh = R.pinMiHigh.intValue,
                    mphLow = R.pinMphLow.intValue, mphHigh = R.pinMphHigh.intValue,
                    onMiles = { lo, hi -> R.pinMiLow.intValue = lo; R.pinMiHigh.intValue = hi },
                    onSpeed = { lo, hi -> R.pinMphLow.intValue = lo; R.pinMphHigh.intValue = hi },
                    hoursText = run {
                        val lo = Math.round(R.pinMiLow.intValue.toDouble() / R.pinMphHigh.intValue.toDouble().coerceAtLeast(1.0))
                        val hi = Math.round(R.pinMiHigh.intValue.toDouble() / R.pinMphLow.intValue.toDouble().coerceAtLeast(1.0))
                        "$lo to $hi hours"
                    },
                    onCancel = { R.aiCancel() },
                    onContinue = {
                        if (step == PIN_STEP_WELCOME) R.aiStep.intValue = PIN_STEP_DISTANCE
                        else { R.pinRideName.value = ""; R.aiProgress.value = ""; R.pinReset() }
                    },
                    // GTAI5-2026-10-23 (Fred 15:57): a centred panel half the screen high -- full screen ran the text off the edges.
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.94f).fillMaxHeight(0.55f)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                )
            } else {
                val miLo = R.pinMiLow.intValue; val miHi = R.pinMiHigh.intValue
                val mphLo = R.pinMphLow.intValue; val mphHi = R.pinMphHigh.intValue
                val thName = R.pinTrailName.value
                val isLoop = R.pinIsLoop.value
                val pts = R.pinPoints.value
                val hrLo = miLo.toDouble() / mphHi.toDouble()
                val hrHi = miHi.toDouble() / mphLo.toDouble()
                // AIFAIL-2026-11-03 v5 (Fred 06:54): the trails selected / left out, counted again when the start, distance or FILTER changes
                val selKey = "" + R.pinTrailLat.value + "," + R.pinTrailLon.value + "," + miLo + "," + miHi + "," + com.geeksville.mesh.convoy.TrailFilterState.whereOrEmpty() + R.filterPeek.value
                androidx.compose.runtime.LaunchedEffect(selKey, step) { if (step >= PIN_STEP_SUMMARY) R.refreshSelection() }
                // PLANTABLE-2026-11-03 (Fred 07:39-07:41): the plan in one line, then the checks; the trail table is drawn below it
                val summary = thName.ifBlank { "Your trailhead" } + " · " + miLo + "–" + miHi + " mi · " + mphLo + "–" + mphHi +
                    " mph · about " + Math.round(hrLo) + "–" + Math.round(hrHi) + " h with stops · " +
                    (if (isLoop == false) "finishes at your end point" else "loop back to the start") +
                    (if (R.checks.value.isNotEmpty()) "\n\n" + R.checks.value else "")
                val GS = com.geeksville.mesh.convoy.GP_STATE_TODO
                val GC = com.geeksville.mesh.convoy.GP_STATE_CURRENT
                val GD = com.geeksville.mesh.convoy.GP_STATE_DONE
                val steps = listOf(
                    com.geeksville.mesh.convoy.GuidedStep(
                        title = "Select your trailhead / long press to add the trailhead",
                        instruction = "Use search to navigate to your area — follow the area name with the two-character " +
                            "state, since names repeat across states.\n\nThe blue circle shows the trails that can start your " +
                            "route. Move the map if the trail you want to launch from is not inside it.\n\nTap the trailhead " +
                            "you are starting from, or press and hold for a second to add one.",
                        answer = thName,
                        state = if (step == PIN_STEP_TRAILHEAD) GC else GD),
                    com.geeksville.mesh.convoy.GuidedStep(
                        title = "Add one if none exists",
                        instruction = "Long press at the start of the trail, choose Trailhead, and give it a name. Then tap it to select it.",
                        answer = if (thName.isBlank()) "" else "Not needed",
                        state = if (step == PIN_STEP_TRAILHEAD) GC else GD),
                    com.geeksville.mesh.convoy.GuidedStep(
                        title = "Does the ride return there?",
                        instruction = "Answer yes for a loop back to your trailhead.",
                        answer = if (thName.isBlank()) "" else "Returns to " + thName,
                        state = when { step < PIN_STEP_RETURN -> GS; step == PIN_STEP_RETURN -> GC; else -> GD }),
                    com.geeksville.mesh.convoy.GuidedStep(
                        title = "Where does the ride finish?",
                        instruction = "Tap the place you want the ride to end.",
                        answer = if (isLoop == false && R.pinEndLat.value != 0.0) "Finish point set" else "",
                        state = when { isLoop != false -> GS; step == PIN_STEP_ENDPOINT -> GC; step > PIN_STEP_ENDPOINT -> GD; else -> GS }),
                    com.geeksville.mesh.convoy.GuidedStep(
                        title = "Add your own places?",
                        instruction = "Answer yes to drop pins on places the ride should reach. Answer no and the ride is designed for you.",
                        answer = when { step <= PIN_STEP_ASK -> ""; pts.isEmpty() -> "Designed for you"; else -> "Adding your own places" },
                        state = when { step < PIN_STEP_ASK -> GS; step == PIN_STEP_ASK -> GC; else -> GD }),
                    com.geeksville.mesh.convoy.GuidedStep(
                        title = "Places to pass through",
                        instruction = "Green trails are connected to your trailhead -- drop pins on them. " +   // AICONNECTED-2026-11-11
                            "Tap the closest trail to each place you want the ride to reach — up to " + PIN_MAX +
                            ". Tap a pin again to remove it. I will favour rides that pass named points of interest.",
                        answer = if (pts.isEmpty()) "" else pts.size.toString() + " place" + (if (pts.size == 1) "" else "s") + " included",
                        state = when { step < PIN_STEP_INCLUDE -> GS; step == PIN_STEP_INCLUDE -> GC; else -> GD }),
                    com.geeksville.mesh.convoy.GuidedStep(
                        title = "Review and search",
                        instruction = if (step == PIN_STEP_SEARCH) R.aiProgress.value.ifBlank { "Working…" } else summary,
                        answer = "",
                        state = if (step < PIN_STEP_SUMMARY) GS else GC)
                )
                val actions: List<Pair<String, () -> Unit>> = when (step) {
                    PIN_STEP_RETURN -> listOf(
                        "YES — LOOP" to { R.pinNotice.value = ""; R.pinIsLoop.value = true; R.aiStep.intValue = PIN_STEP_ASK },
                        "DROP ENDPOINT PIN NOW" to { R.pinNotice.value = ""; R.pinIsLoop.value = false; R.aiStep.intValue = PIN_STEP_ENDPOINT })
                    PIN_STEP_ENDPOINT -> listOf(
                        "CONTINUE" to { R.pinNotice.value = ""; R.aiStep.intValue = PIN_STEP_ASK })
                    PIN_STEP_ASK -> listOf(
                        "YES — I'LL DROP PINS" to { R.pinNotice.value = ""; R.aiStep.intValue = PIN_STEP_INCLUDE },
                        "NO — DESIGN IT FOR ME" to { R.pinNotice.value = ""; R.pinPoints.value = emptyList(); R.aiStep.intValue = PIN_STEP_SUMMARY })
                    PIN_STEP_INCLUDE -> listOf(
                        "DONE" to { R.pinNotice.value = ""; R.aiStep.intValue = PIN_STEP_SUMMARY })
                    PIN_STEP_SUMMARY -> listOf(
                        "FIND MY RIDES" to { R.findRides(ctx) })
                    else -> emptyList()
                }
                if (step == PIN_STEP_ENDPOINT || step == PIN_STEP_INCLUDE) {
                    val f = R.pinFeas.value
                    val problem = when {
                        f == null -> ""
                        f.onFragment.any { it in 1..pts.size } -> "One place sits on a trail that does not join the rest of the network."
                        f.unreachable.any { it in 1..pts.size } -> "One place is too far from any trail to reach."
                        else -> ""
                    }
                    com.geeksville.mesh.convoy.ConvoyPinMileageHud(
                        floorMiles = f?.totalMiles ?: 0.0,
                        overMiles = f?.overMiles ?: 0.0,
                        underMiles = f?.underMiles ?: 0.0,
                        floorBand = miLo,
                        ceiling = miHi,
                        problem = problem,
                        busy = f == null,
                        onCeilingChange = { d ->
                            R.pinMiHigh.intValue = (R.pinMiHigh.intValue + d).coerceIn(R.pinMiLow.intValue + 5, 200)
                            R.runAssess(ctx)
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 110.dp)
                    )
                }
                // GTAI5B-2026-10-23 (Fred 16:07): the trailhead SEARCH lives IN the step panel (below) -- it opens and closes with it.
                if (step < PIN_STEP_SUMMARY) {
                    GtAiStepScreen(   /* GTAI4 */
                        steps = steps,
                        onStartOver = { R.pinReset() },
                        onCancelBuild = { R.aiCancel() },
                        actions = actions,
                        notice = R.pinNotice.value,
                        // GTAI5B-2026-10-23 (Fred 16:07): SEARCH inside "Select your trailhead" -- a place found moves the map, the ring follows.
                        // GTAI5D-2026-10-23 (Fred 16:26): a picked result RESETS the search -- results gone, the panel shrinks back.
                        header = {
                            if (step == PIN_STEP_TRAILHEAD) {
                                // GTAI5E-2026-10-23 (Fred 16:34): a TWISTIE -- collapsed by default; picking a result folds it again.
                                val searchOpen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                                val sc = GtScheme.current.value
                                androidx.compose.foundation.layout.Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { searchOpen.value = !searchOpen.value }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(if (searchOpen.value) "\u25be" else "\u25b8", color = sc.panelText, fontSize = 16.sp,
                                        modifier = Modifier.padding(end = 8.dp))
                                    Text("SEARCH \u2014 find your area", color = sc.panelText, fontSize = 14.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                                if (searchOpen.value) GtSearchBody(search, close = { searchOpen.value = false }, autoFocus = false)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                } else if (R.filterPeek.value) {
                    // AIFAIL-2026-11-03 v3 (Fred 06:51): FILTER is open over the map -- the plan waits; this brings it back to search again
                    GtButton("BACK TO THE PLAN", { R.filterPeek.value = false },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
                } else {
                    GtGuidedSummaryPanel(   /* GTAI4 */
                        title = R.pinRideName.value,
                        body = summary,
                        onFilter = { R.filterPeek.value = true; GtOpenModule.req.value = "filter" },   // AIFAIL-2026-11-03 v3
                        details = { GtTrailTable(R.selTable.value) },   // PLANTABLE-2026-11-03
                        working = step == PIN_STEP_SEARCH,
                        progress = R.aiProgress.value,
                        // AIFAILCHECK-2026-11-03 (Fred 07:16): no search from a trailhead with no shown trail within 1/2 mile
                        onProceed = { if (!R.aiBusy.value) {
                            if (R.trailheadOk.value) actions.firstOrNull()?.second?.invoke()
                            else { R.noticeFilter.value = true; R.noticeAllLand.value = false
                                R.notice.value = R.checks.value + "\n\nThe search cannot start: the trailhead must be within 1/2 mile of a trail you " +
                                    "are showing. OPEN FILTER to show more trails, or START OVER with another trailhead." }
                        } },
                        onStartOver = { if (!R.aiBusy.value) R.pinReset() },
                        onCancelBuild = { if (!R.aiBusy.value) R.aiCancel() }
                    )
                }
            }
        }
    }

    // HOME1-2026-10-23: a saved route's narrative (the planner's savedNotesRouteId panel) -- Build from recipe and the detail card from it.
    if (R.savedNotesRouteId.value.isNotEmpty()) {
        val rid = R.savedNotesRouteId.value
        androidx.activity.compose.BackHandler(enabled = true) { R.savedNotesRouteId.value = "" }
        val hasRecipe = androidx.compose.runtime.remember(rid) { SpatialDbManager.routeRecipe(rid) != null }
        GtNotesPanel(
            // ROUTENAME-2026-10-28 (D11, Fred 14:16 + 14:20): the panel names the route and shows its other names (aliases).
            title = androidx.compose.runtime.remember(rid) {
                val nm = SpatialDbManager.routeNameById(rid)
                val also = SpatialDbManager.getAliasesFor("route", rid).mapNotNull { it["alias"] }.filter { it != nm }
                if (nm == null) "Route details" else nm + (if (also.isEmpty()) "" else " (also: " + also.joinToString(", ") + ")") + " \u2014 route details"
            },
            sections = com.geeksville.mesh.convoy.notesFromRouteId(rid),
            onClose = { R.savedNotesRouteId.value = "" },
            onBuildFromRecipe = if (hasRecipe) ({ R.savedNotesRouteId.value = ""; R.buildFromRecipe(ctx, rid) }) else null,
            onRouteDetails = { R.savedNotesRouteId.value = ""; R.openDetail("Routes", rid) },
            onCreateRide = { R.savedNotesRouteId.value = ""; R.addRide(rid) }   // ROUTERIDE-2026-11-05
        )
    }
    // HOME1-2026-10-23: the work-in-progress narrative (the planner's WIPNOTES ROUTE DETAILS button) -- only when the open draft HAS notes.
    if (R.routeMode.value && !R.aiActive() && !R.showWipNotes.value && R.routeName.value.isNotBlank() &&
        RouteDraftStore.hasNotes(R.routeName.value)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            GtButton("ROUTE DETAILS", { R.showWipNotes.value = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
        }
    }
    if (R.showWipNotes.value) {
        androidx.activity.compose.BackHandler(enabled = true) { R.showWipNotes.value = false }
        GtNotesPanel(
            title = R.routeName.value,
            subtitle = "Work in progress \u2014 not saved yet",
            sections = com.geeksville.mesh.convoy.notesFromDraft(RouteDraftStore.readNotes(R.routeName.value)),
            onClose = { R.showWipNotes.value = false }
        )
    }

    // AIPERIMCOUNT-2026-11-11 v6 (Fred 09:19 "a pop up warning message that you must respond to: add dropped pins to add features and connected
    //  trail diversity"): no tap-outside dismiss -- the rider answers with the panel's own two choices.
    if (R.askWarn.value.isNotEmpty() && R.aiStep.intValue == PIN_STEP_ASK) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Not a good candidate for AI route planning") },
            text = { Text(R.askWarn.value) },
            confirmButton = {
                TextButton(onClick = { R.askWarn.value = ""; R.pinNotice.value = ""; R.aiStep.intValue = PIN_STEP_INCLUDE }) {
                    Text("YES — I'LL DROP PINS (RECOMMENDED)")
                }
            },
            dismissButton = {
                TextButton(onClick = { R.askWarn.value = ""; R.pinNotice.value = ""; R.pinPoints.value = emptyList(); R.aiStep.intValue = PIN_STEP_SUMMARY }) {
                    Text("NO — DESIGN IT FOR ME")
                }
            }
        )
    }

    // ── save choice (the planner's, with rename) ──
    if (R.showSaveChoice.value) {
        val pts = RouteManager.routeVertexCount()
        AlertDialog(
            onDismissRequest = { R.showSaveChoice.value = false },
            title = { GtDialogTitle("Save route", "route.save", { R.showSaveChoice.value = false }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (R.routeLifecycleState.intValue == ROUTE_LS_RESUMED) "Graduate to a saved route, or keep editing as in-progress."
                         else "Save as a completed route (needs 2+ points), or keep as in-progress.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = if (R.routeName.value == RouteDraftStore.UNNAMED) "" else R.routeName.value,
                        onValueChange = { R.routeName.value = it }, singleLine = true,
                        label = { Text("Route name (required)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val nm = R.routeName.value.trim()
                    if (nm.isBlank() || nm == RouteDraftStore.UNNAMED) android.widget.Toast.makeText(ctx, "Enter a route name", android.widget.Toast.LENGTH_SHORT).show()
                    else if (pts < 2) android.widget.Toast.makeText(ctx, "Need at least 2 points", android.widget.Toast.LENGTH_SHORT).show()
                    else {
                        R.showSaveChoice.value = false
                        val oldNm = R.saveOrigName.value
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (oldNm != nm) RouteDraftStore.renameDraft(oldNm, nm) }
                            R.routeName.value = nm
                            kotlinx.coroutines.delay(400)
                            saveCompleted()
                        }
                    }
                }) { Text("Save as completed route") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val nm = R.routeName.value.trim()
                    if (nm.isBlank() || nm == RouteDraftStore.UNNAMED) android.widget.Toast.makeText(ctx, "Enter a route name", android.widget.Toast.LENGTH_SHORT).show()
                    else {
                        R.showSaveChoice.value = false
                        val oldNm = R.saveOrigName.value
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (oldNm != nm) RouteDraftStore.renameDraft(oldNm, nm) }
                            R.routeName.value = nm
                            RouteManager.clearRoute()
                            R.draftTick.intValue++
                            R.endBuild()
                        }
                    }
                }) { Text("Save as in progress") }
            }
        )
    }

    // ── discard choice (the planner's) ──
    if (R.showDiscardChoice.value) {
        AlertDialog(
            onDismissRequest = { R.showDiscardChoice.value = false },
            title = { GtDialogTitle("Discard in-progress route", "route.discard", { R.showDiscardChoice.value = false }) },
            text = { Text("Roll back to the last saved draft, or delete this in-progress route entirely.") },
            confirmButton = {
                TextButton(onClick = {
                    R.showDiscardChoice.value = false
                    RouteDraftStore.loadIntoRouteManager(R.routeName.value)
                    scope.launch {
                        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { R.buildLine(ctx) }
                        R.js("drawBuildLine('$p')")
                    }
                }) { Text("Roll back") }
            },
            dismissButton = {
                TextButton(onClick = {
                    R.showDiscardChoice.value = false
                    RouteManager.clearRoute()
                    if (RouteDraftStore.isInOpenBatch(R.routeName.value))
                        android.widget.Toast.makeText(ctx, "This route is part of an open compare set -- keep or discard it from the compare table.", android.widget.Toast.LENGTH_LONG).show()
                    else RouteDraftStore.deleteDraft(R.routeName.value)
                    R.draftTick.intValue++
                    R.endBuild()
                    R.routeName.value = ""
                }) { Text("Delete in-progress") }
            }
        )
    }

    // ── unnamed-draft resolver (the planner's) ──
    if (R.showResolver.value) {
        val pts = remember { RouteDraftStore.listDrafts().firstOrNull { it.name == RouteDraftStore.UNNAMED }?.pointCount ?: 0 }
        val nm = remember { mutableStateOf("") }
        val err = remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { },
            title = { GtDialogTitle("Unfinished route", "route.resolve", { R.showResolver.value = false }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("A route with $pts points was left unnamed by your last session. Name it to keep it, or discard it.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = nm.value, onValueChange = { nm.value = it; err.value = "" }, singleLine = true,
                        isError = err.value.isNotEmpty(), label = { Text("Route name") })
                    if (err.value.isNotEmpty()) Text(err.value, color = Gt.bad)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = nm.value.trim()
                    if (n.isBlank()) err.value = "Enter a name"
                    else if (RouteDraftStore.isNameTaken(n)) err.value = "That name is already used"
                    else if (RouteDraftStore.renameDraft(RouteDraftStore.UNNAMED, n)) {
                        R.showResolver.value = false; R.draftTick.intValue++; R.showPicker.value = true   // then the in-progress list
                    } else err.value = "Rename failed"
                }) { Text("Keep") }
            },
            dismissButton = {
                TextButton(onClick = {
                    RouteDraftStore.deleteDraft(RouteDraftStore.UNNAMED)
                    R.showResolver.value = false; R.draftTick.intValue++
                    if (R.manualDrafts().isNotEmpty()) R.showPicker.value = true else R.wantChooser.value = true   // GTROUTE1C-2026-10-23
                }) { Text("Discard") }
            }
        )
    }

    // ── in-progress picker (the planner's): resume / delete / + a new route (-> the Route panel's choices) ──
    if (R.showPicker.value) {
        val drafts = remember(R.draftTick.intValue) { R.manualDrafts().sortedBy { it.createdAt } }   // GTROUTE1C-2026-10-23
        AlertDialog(
            onDismissRequest = { R.showPicker.value = false },
            title = { GtDialogTitle(if (R.recoveryDetected.value) "Route recovery — continue or start new" else "Continue editing or create a new route",
                "route.inprogress", { R.showPicker.value = false }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (R.recoveryDetected.value) Text("A route was left open from your last session.", fontSize = 13.sp, color = Gt.warn)
                    if (drafts.isEmpty()) Text("No routes in progress.")
                    drafts.forEach { di ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(modifier = Modifier.weight(1f), onClick = {
                                R.showPicker.value = false; R.resumeReq.value = di.name   // GTROUTE1F-2026-10-23: the one resume path
                            }) { Column { Text(di.name); Text("${di.pointCount} points", fontSize = 12.sp, color = Gt.dim) } }
                            TextButton(onClick = {
                                if (RouteDraftStore.isInOpenBatch(di.name)) {
                                    android.widget.Toast.makeText(ctx, "This route is part of an open compare set -- keep or discard it from the compare table.", android.widget.Toast.LENGTH_LONG).show()
                                } else { RouteDraftStore.deleteDraft(di.name); R.draftTick.intValue++ }
                            }) { Text("Delete", color = Gt.bad) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (RouteDraftStore.draftExists(RouteDraftStore.UNNAMED)) {
                        android.widget.Toast.makeText(ctx, "Rename or delete \"" + RouteDraftStore.UNNAMED + "\" before starting a new route.", android.widget.Toast.LENGTH_LONG).show()
                    } else { R.showPicker.value = false; R.startNew(ROUTE_METHOD_P2P) }   // GTROUTE1C-2026-10-23: straight into Build it myself
                }) { Text("+ Build it myself") }   // GTAI3-2026-10-23: the selector offers both ways to start a new route
            },
            dismissButton = { TextButton(onClick = { R.showPicker.value = false; R.startAi() }) { Text("+ AI route design") } }
        )
    }

    // ── missing trailhead (the planner's ROUTETHB prompt) ──
    if (R.thPrompt.value) {
        AlertDialog(
            onDismissRequest = { R.thPrompt.value = false },
            title = { GtDialogTitle("This route needs a trailhead", "route.trailhead", { R.thPrompt.value = false }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Every route is saved with its trailhead — where riders meet and unload. There is none within a tenth of a " +
                        "mile of the route's first point, so one will be created there.")
                    OutlinedTextField(value = R.thName.value, onValueChange = { R.thName.value = it }, singleLine = true,
                        label = { Text("New trailhead's name") })
                }
            },
            confirmButton = {
                TextButton(enabled = R.thName.value.isNotBlank(), onClick = {
                    val f = RouteManager.routeVertices().firstOrNull()
                    R.thPrompt.value = false
                    if (f != null) scope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { SpatialDbManager.insertWaypoint(R.thName.value.trim(), f.lat, f.lon, "trailhead") }.isSuccess
                        }
                        R.js("triggerViewportUpdate()")
                        android.widget.Toast.makeText(ctx, if (ok) "Trailhead added — tap SAVE again." else "The trailhead could not be added.",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text("CREATE TRAILHEAD") }
            },
            dismissButton = { TextButton(onClick = { R.thPrompt.value = false }) { Text("Cancel") } }
        )
    }

    // ── notice ──
    if (R.notice.value.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { R.notice.value = ""; R.noticeFilter.value = false },
            title = { GtDialogTitle("Route", "route", { R.notice.value = ""; R.noticeFilter.value = false }) },
            text = { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) { Text(R.notice.value) } },   // AIFAIL-2026-11-03: long text scrolls
            confirmButton = {
                val nctx = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.foundation.layout.Row {
                    // AIFAIL-2026-11-03 v4 (Fred 06:52): one tap -- all land on, search again
                    if (R.noticeAllLand.value) TextButton(onClick = { R.showAllLandAndSearch(nctx) }) { Text("SHOW ALL LAND AND SEARCH AGAIN") }
                    TextButton(onClick = { R.notice.value = ""; R.noticeFilter.value = false; R.noticeAllLand.value = false }) { Text("OK") }   // GTAI2-2026-10-23: the stage-1 OK path is gone
                }
            },
            // AIFAIL-2026-11-03: a failed AI build -- OPEN FILTER beside OK
            dismissButton = { if (R.noticeFilter.value) TextButton(onClick = { R.notice.value = ""; R.noticeFilter.value = false; R.noticeAllLand.value = false; R.filterPeek.value = true; GtOpenModule.req.value = "filter" }) { Text("OPEN FILTER") } },
        )
    }

    // ── GTAI2-2026-10-23: the compare table -- the planner's BATCHFORK grid, copied. EXIT leaves everything: the set stays open. ──
    if (R.batchOpen.value && R.batchRows.value.isNotEmpty()) {
        GtCompareTable(   /* GTAI4 */
            batchName = R.batchName.value,
            rows = R.batchRows.value,
            hidden = R.batchHidden.value,
            saveTicks = R.batchSave.value,
            onToggleShown = { n ->
                val nowHidden = n !in R.batchHidden.value
                R.batchHidden.value = if (nowHidden) R.batchHidden.value + n else R.batchHidden.value - n
                val safe = n.replace("\\", "\\\\").replace("'", "\\'")
                R.js("showBatchRoute('" + safe + "', " + (!nowHidden) + ", 5)")
            },
            onSaveTick = { n, on -> R.batchSave.value = if (on) R.batchSave.value + n else R.batchSave.value - n },
            onSaveSelected = {
                // TICKING NONE IS A LEGITIMATE ANSWER -- but it deletes everything, so it asks first.
                if (R.batchSave.value.isEmpty()) R.batchDeleteConfirm.value = true
                else { R.batchAreaName.value = ""; R.batchAreaPrompt.value = true }
            },
            onExit = { R.closeCompare() },
            modifier = toolbarModifier
        )
    }
    // the planner's runBatchSave, copied: kept routes become saved routes (BY ID, not by viewport), the rest are deleted.
    val runBatchSave: (String) -> Unit = { area ->
        R.batchSaving.value = true
        val keep = R.batchRows.value.filter { it.name in R.batchSave.value }
        val drop = R.batchRows.value.filter { it.name !in R.batchSave.value }
        val dupes = java.util.ArrayList<String>()   // ROUTEDUPE-2026-10-28 (D12): routes already saved under another name
        kotlinx.coroutines.MainScope().launch {
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SpatialDbManager.init(ctx)
                var ok = 0
                // BATCHDELETE-2026-10-07 (Fred): the batch header goes FIRST -- after this its routes are ordinary drafts.
                RouteDraftStore.clearBatch()
                for (r in keep) {
                    if (RouteDraftStore.loadIntoRouteManager(r.name) == null) {
                        android.util.Log.e("BatchGrid", "load failed: " + r.name)
                        continue
                    }
                    val verts = RouteManager.routeVertices()
                    val byId = HashMap<String, String>()
                    byId.putAll(SpatialDbManager.queryGeomByIds(
                        verts.filter { it.snapped && it.lineType == "trail" }.mapNotNull { it.lineId }, "trail"))
                    byId.putAll(SpatialDbManager.queryGeomByIds(
                        verts.filter { it.snapped && it.lineType == "track" }.mapNotNull { it.lineId }, "track"))
                    val built = RouteManager.buildWktAndBbox { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                    if (built == null) {
                        android.util.Log.e("BatchGrid", "build failed: " + r.name)
                        continue
                    }
                    val (wkt, bbox) = built
                    // ROUTESEQ-2026-09-04: name plus a sequence number (count of EXISTING saved routes with that name).
                    val base = area.trim()
                    val nm = base + " " + SpatialDbManager.nextRouteNumber(base)   // ROUTENUM-2026-10-28 (D20)
                    // ROUTEDUPE-2026-10-28 (D12): the same path is already saved -> the new name joins it as another name (as tracks do).
                    // insertRoute is INSERT OR IGNORE on UNIQUE(geom_hash): it would drop this route silently and report success.
                    val gh = SpatialDbManager.computeGeomHash(wkt)
                    val existing = SpatialDbManager.findRouteIdByHash(gh)
                    if (existing != null) {
                        val first = SpatialDbManager.routeNameById(existing) ?: "a saved route"
                        SpatialDbManager.addAlias("route", existing, nm, gh, null)
                        android.util.Log.i("BatchGrid", "ROUTEDUPE-2026-10-28: '" + nm + "' is the same route as '" + first + "' -- added as another name")
                        dupes.add("\"" + nm + "\" is the same route as \"" + first + "\"")
                        RouteDraftStore.deleteDraft(r.name)
                        continue
                    }
                    val id = SpatialDbManager.insertRoute(nm, wkt, bbox[0], bbox[1], bbox[2], bbox[3])
                    // notes BEFORE the delete -- the narrative and the recipe both live in them
                    RouteDraftStore.readNotes(r.name)?.let { nts -> SpatialDbManager.writeRouteNotes(id, nts) }
                    RouteDraftStore.deleteDraft(r.name)
                    ok++
                }
                for (r in drop) RouteDraftStore.deleteDraft(r.name)
                ok
            }
            // NOT copied: the planner's deleteDraft(UNNAMED) here -- on ONE MAP the unnamed draft is a crash pick-up (Fred 14:34).
            RouteManager.clearRoute()
            R.draftTick.intValue++
            R.batchSaving.value = false
            R.closeCompare()
            if (dupes.isNotEmpty()) {   // ROUTEDUPE-2026-10-28 (D12): said plainly, with OK -- not a toast that reads like success
                R.notice.value = (if (saved > 0) "Saved " + saved + " new route(s).\n\n" else "") +
                    "Already saved: " + dupes.joinToString("; ") + ". The new name was added to it as another name."
            } else
            android.widget.Toast.makeText(ctx, if (saved > 0) "Saved " + saved + " route(s)" else "Compare set cleared",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    if (R.batchAreaPrompt.value) {
        AlertDialog(
            onDismissRequest = { R.batchAreaPrompt.value = false },
            title = { GtDialogTitle("Name the area", "route.compare", { R.batchAreaPrompt.value = false }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = R.batchAreaName.value, onValueChange = { R.batchAreaName.value = it },
                        singleLine = true, label = { Text("Area") })
                    Text("Saved as \"" + R.batchAreaName.value.trim().ifBlank { "Area" } + " 1\", \"… 2\" for each route kept",
                        fontSize = 12.sp, color = Gt.dim, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = R.batchAreaName.value.isNotBlank() && !R.batchSaving.value, onClick = {
                    R.batchAreaPrompt.value = false
                    runBatchSave(R.batchAreaName.value)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { R.batchAreaPrompt.value = false }) { Text("Cancel") } }
        )
    }
    if (R.batchDeleteConfirm.value) {
        AlertDialog(
            onDismissRequest = { R.batchDeleteConfirm.value = false },
            title = { GtDialogTitle("Delete all routes?", "route.compare", { R.batchDeleteConfirm.value = false }) },
            text = { Text("No routes selected. All " + R.batchRows.value.size + " will be deleted. If you proceed you must start over.") },
            confirmButton = { TextButton(onClick = { R.batchDeleteConfirm.value = false; runBatchSave("") }) { Text("Delete", color = Gt.bad) } },
            dismissButton = { TextButton(onClick = { R.batchDeleteConfirm.value = false }) { Text("Cancel") } }
        )
    }
}

// ── GTAI3-2026-10-23: the planner's guided-flow constants and trailhead helpers (ConvoyMapViewerScreen, private there), copied ──
private const val PIN_STEP_WELCOME   = -2
private const val PIN_STEP_DISTANCE  = -1
private const val PIN_STEP_NONE      = 0
private const val PIN_STEP_TRAILHEAD = 1
private const val PIN_STEP_RETURN    = 2
private const val PIN_STEP_ENDPOINT  = 3
private const val PIN_STEP_ASK       = 4
private const val PIN_STEP_INCLUDE   = 5
private const val PIN_STEP_SUMMARY   = 6
private const val PIN_STEP_SEARCH    = 7
// AIPERIMCOUNT-2026-11-11 v5 (Fred 09:09-09:10): a ride is a loop through features, so they must REACH out -- the area is a good candidate when
//  the farthest feature (straight line from the trailhead) x this factor reaches the minimum asked (a loop through features spread
//  around that far is up to ~3x its radius; trail miles are longer than straight-line miles)
private const val AI_REACH_FACTOR = 3.0
// AIFLOOR10-2026-11-11 (Fred 08:54): the floor a search with no ride in the rider's band is run again from
private const val AI_RETRY_FLOOR = 10
private const val PIN_MAX = 5
private const val PIN_REMOVE_MI = 0.15
private const val TRAILHEAD_SELECT_MI = 0.062

private fun gpMilesBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * 69.0
    val dLon = (lon2 - lon1) * 69.0 * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2.0))
    return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
}

private fun gpParsePointWkt(wkt: String?): DoubleArray? {
    if (wkt.isNullOrBlank()) return null
    val m = Regex("-?\\d+\\.?\\d*").findAll(wkt).map { it.value }.toList()
    if (m.size < 2) return null
    val lon = m[0].toDoubleOrNull() ?: return null
    val lat = m[1].toDoubleOrNull() ?: return null
    return doubleArrayOf(lat, lon)
}

/** null = no trailhead within [withinMi] (the planner's; its default radius is dropped -- the one caller passes it). */
private fun gpNearestTrailhead(lat: Double, lon: Double, withinMi: Double): Map<String, String?>? {
    val dLat = withinMi / 69.0
    val dLon = withinMi / (69.0 * kotlin.math.max(0.2, kotlin.math.cos(Math.toRadians(lat))))
    val found = SpatialDbManager.queryWaypointsByViewport(lat - dLat, lon - dLon, lat + dLat, lon + dLon, 200)
    var best: Map<String, String?>? = null
    var bestMi = withinMi
    for (w in found) {
        if ((w["type"] ?: "") != "trailhead") continue
        val p = gpParsePointWkt(w["geometry"]) ?: continue
        val mi = gpMilesBetween(lat, lon, p[0], p[1])
        if (mi <= bestMi) { bestMi = mi; best = w }
    }
    return best
}

/**
 * AIFAIL-2026-11-03 (Fred 06:48): Route > Build from a recipe file -- the recipe an ISSUE report attached (issue_..._ai_recipe.json,
 * or any JSON holding a route recipe). Its FILTER values are set, the AI build opens at its summary; FIND MY RIDES repeats the search.
 */
@androidx.compose.runtime.Composable
fun rememberRecipeFile(): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val msg = try {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: throw IllegalStateException("could not read the file")
                val o = org.json.JSONObject(text)
                val r = o.optJSONObject("recipe") ?: o
                if (!r.has("anchorLat") || !r.has("milesHigh")) "That file is not a GroupTrack OG route recipe."
                else { GtRoute.buildFromRecipeJson(context, r, true); "" }
            } catch (e: Exception) {
                "Could not read the recipe: ${e.message}"
            }
            if (msg.isNotEmpty()) android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val notice = com.geeksville.mesh.convoy.rememberPickerNotice("to choose the route recipe file (.json) you were sent", "file")
    return { notice.ask { picker.launch(arrayOf("*/*")) } }
}

/**
 * PLANTABLE-2026-11-03 (Fred 07:39-07:41, mockup approved): the plan's trail table -- by use, one line per trail type with yes / no and
 * its public / private counts in the search area; GREEN = included in the search, RED = left out. SEE / CHANGE FILTER changes it.
 */
@androidx.compose.runtime.Composable
fun GtTrailTable(t: com.geeksville.mesh.convoy.RouteExplorer.SelTable) {
    // PLANCHECK-2026-11-03 (Fred 08:28): a tick box in front of every line -- ticked = included (green numbers), unticked = left out
    //  (red); the box or the line changes FILTER, the map redraws, the numbers recolour.
    val sc = GtScheme.current.value
    val inc = sc.good; val out = sc.bad; val txt = sc.panelText
    fun fmt(n: Int) = "%,d".format(n)
    val bold = androidx.compose.ui.text.font.FontWeight.Bold
    val end = androidx.compose.ui.text.style.TextAlign.End
    val gap = Modifier.width(8.dp)
    // PLANTOAST-2026-11-03 (Fred 08:30): every tap says what changed in FILTER
    val tctx = androidx.compose.ui.platform.LocalContext.current
    fun said(msg: String) = android.widget.Toast.makeText(tctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    val tf = com.geeksville.mesh.convoy.TrailFilterState
    fun landTap(pub: Boolean) {
        val before = tf.land
        GtRoute.toggleLand(pub)
        if (tf.land == before) said("At least one land type stays on")
        else said("FILTER changed: " + (if (pub) "Public" else "Private") + " land " +
            (if ((if (pub) tf.land != "PRIVATE" else tf.land != "PUBLIC")) "included" else "left out"))
    }
    fun useTap(u: String) {
        val before = tf.use
        GtRoute.toggleUse(u)
        if (tf.use == before) said("At least one use stays on")
        else said("FILTER changed: $u " + (if ((if (u == "Motorized") tf.use != "NON-MOTORIZED" else tf.use != "MOTORIZED")) "included" else "left out"))
    }
    fun typeTap(ty: String) { GtRoute.toggleType(ty); said("FILTER changed: $ty " + (if (tf.isOn(ty)) "included" else "left out")) }
    fun rstTap() { GtRoute.toggleRestricted(); said("FILTER changed: Restricted " + (if (tf.restricted) "included" else "left out")) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (t.total == 0) { Text("Counting the trails…", color = txt, fontSize = 13.sp); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TRAILS IN THE SEARCH AREA (" + t.area + ")", color = txt, fontSize = 12.sp, fontWeight = bold, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(96.dp).clickable { landTap(true) }) {
                GtCheck(t.publicOn); Spacer(gap); Text("Public", color = txt, fontSize = 12.sp, fontWeight = bold) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(96.dp).clickable { landTap(false) }) {
                GtCheck(t.privateOn); Spacer(gap); Text("Private", color = txt, fontSize = 12.sp, fontWeight = bold) }
        }
        var lastUse = ""
        for (r in t.rows) {
            if (r.use != lastUse) {
                lastUse = r.use
                val u = r.use
                val switchable = u == "Motorized" || u == "Non-motorized"
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(enabled = switchable) { useTap(u) }) {
                    if (switchable) { GtCheck(r.useOn); Spacer(gap) }
                    Text(if (switchable) u else "$u (always searched)", color = txt, fontSize = 13.sp, fontWeight = bold)
                }
            }
            val ty = r.type
            val pubIn = r.useOn && r.typeOn && t.publicOn
            val privIn = r.useOn && r.typeOn && t.privateOn
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).clickable { typeTap(ty) }) {
                Spacer(Modifier.width(24.dp))
                GtCheck(r.typeOn); Spacer(gap)
                Text(r.type, color = txt, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(fmt(r.pub), color = if (pubIn) inc else out, fontSize = 13.sp, fontWeight = bold, textAlign = end, modifier = Modifier.width(96.dp))
                Text(fmt(r.priv), color = if (privIn) inc else out, fontSize = 13.sp, fontWeight = bold, textAlign = end, modifier = Modifier.width(96.dp))
            }
        }
        // RESTRICTEDNEVER-2026-11-03 (Fred 20:18-20:20): restricted is never in a route -- information only, no tick box
        if (t.restricted > 0 || t.pieces > 0) Text("Restricted (never in a route): " + fmt(t.restricted) + " trail" + (if (t.restricted == 1) "" else "s") +
            " \u00b7 " + fmt(t.pieces) + " with restricted pieces (routed on their open parts)", color = out, fontSize = 13.sp, fontWeight = bold,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(fmt(t.searched) + " searched · " + fmt(t.total - t.searched) + " left out", color = txt, fontSize = 13.sp, fontWeight = bold,
            modifier = Modifier.padding(top = 10.dp))
        Text("Green numbers are included, red are left out. Tick a box to include those trails — SEE / CHANGE FILTER for " +
            "everything else.", color = txt, fontSize = 11.sp)
    }
}
