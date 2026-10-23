package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    val saveOrigName = mutableStateOf("")
    val thPrompt = mutableStateOf(false)
    val thName = mutableStateOf("")
    val draftTick = mutableIntStateOf(0)
    /** true = the in-progress picker's "+ new route" asked for the Route panel's choices (GroupTrack opens it). */
    val wantChooser = mutableStateOf(false)

    /** RouteState for the host map's save: open while Route+ runs (crash recovery reads it). */
    fun routeState(): MapStateStore.RouteState? =
        if (routeMode.value) MapStateStore.RouteState(open = true, status = "draft", draftName = routeName.value) else null

    /**
     * The Route button, BEFORE its choices (Fred 13:18). true = something pending was shown -- do not open the choices.
     */
    fun precheck(): Boolean {
        if (routeMode.value) return true   // already building -- the toolbar is up
        if (RouteDraftStore.hasOpenBatch()) {
            notice.value = "An AI compare set is waiting for a decision. It stays as it is -- open it on the planning map " +
                "(Map › Open the planning map › Route+) to keep or discard those routes. OK continues to Build it myself."
            return true
        }
        return manualChecks()
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
fun GtRouteHost(toolbarModifier: Modifier) {   // GTROUTE1D-2026-10-23: where the toolbar opens -- the caller's map decides
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

    // ── the toolbar: LOCKED to the launched method (one mode; the other is not shown) ──
    if (R.routeMode.value) {
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
                    if (err.value.isNotEmpty()) Text(err.value, color = Color(0xFFE86B6B))
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
                    if (R.recoveryDetected.value) Text("A route was left open from your last session.", fontSize = 13.sp, color = Color(0xFFE3B341))
                    if (drafts.isEmpty()) Text("No routes in progress.")
                    drafts.forEach { di ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(modifier = Modifier.weight(1f), onClick = {
                                R.showPicker.value = false; R.resumeReq.value = di.name   // GTROUTE1F-2026-10-23: the one resume path
                            }) { Column { Text(di.name); Text("${di.pointCount} points", fontSize = 11.sp, color = Color(0xFF8B949E)) } }
                            TextButton(onClick = {
                                if (RouteDraftStore.isInOpenBatch(di.name)) {
                                    android.widget.Toast.makeText(ctx, "This route is part of an open compare set -- keep or discard it from the compare table.", android.widget.Toast.LENGTH_LONG).show()
                                } else { RouteDraftStore.deleteDraft(di.name); R.draftTick.intValue++ }
                            }) { Text("Delete", color = Color(0xFFE86B6B)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (RouteDraftStore.draftExists(RouteDraftStore.UNNAMED)) {
                        android.widget.Toast.makeText(ctx, "Rename or delete \"" + RouteDraftStore.UNNAMED + "\" before starting a new route.", android.widget.Toast.LENGTH_LONG).show()
                    } else { R.showPicker.value = false; R.startNew(ROUTE_METHOD_P2P) }   // GTROUTE1C-2026-10-23: straight into Build it myself
                }) { Text("+ Plan a new route") }
            }
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

    // ── notice (an open AI compare set) ──
    if (R.notice.value.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { R.notice.value = "" },
            title = { GtDialogTitle("Route", "route", { R.notice.value = "" }) },
            text = { Text(R.notice.value) },
            // GTROUTE1B-2026-10-23 (Fred 13:58): OK takes the no-compare-set path until stage 2; the set is left alone.
            confirmButton = { TextButton(onClick = { R.notice.value = ""; if (!R.manualChecks()) R.startNew(ROUTE_METHOD_P2P) }   /* GTROUTE1C-2026-10-23 */) { Text("OK") } }
        )
    }
}
