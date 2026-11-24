package com.geeksville.mesh.grouptrack

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.route.GtRoute
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

/**
 * AIFLOW-2026-11-23 (2.8b): the AI route flow's STATE, in shared code, so BOTH platforms run the
 * one flow with the one set of panels.
 *
 * \u26d4 THIS IS NOT A SECOND FLOW. Android's RouteModule holds these same values by DELEGATION
 * (`val aiStep get() = GtAiFlow.step`), so there is exactly one copy of each and Android's call
 * sites, behaviour and step numbering are unchanged. The panels [GtAiLaunchPanel] and
 * [GtAiStepPanel] are already shared (AISTEPSHARED-2026-11-23); this is the state they draw from.
 *
 * WHAT IS HERE: the launch step, the rider's distance and speed answers, and the ONE evaluation the
 * launch panel precedes. Nothing else of the route process moves -- the map steps (trailhead, pins,
 * assess, search) stay in RouteModule, because they drive the map.
 */
object GtAiFlow {

    /** The step values RouteModule has always used; it now takes them from here. */
    const val STEP_LAUNCH = -3
    const val STEP_WELCOME = -2
    const val STEP_DISTANCE = -1
    const val STEP_NONE = 0

    private val log = gtLogger("GtAiFlow")

    val step = mutableIntStateOf(STEP_NONE)
    val skipOverview = mutableStateOf(false)

    // RouteModule.kt:207-210's defaults, unchanged.
    val miLow = mutableIntStateOf(55)
    val miHigh = mutableIntStateOf(80)
    val mphLow = mutableIntStateOf(12)
    val mphHigh = mutableIntStateOf(18)

    // AISTATE-2026-11-24: the AI pin state, moved out of RouteModule (Android-only) so the
    // guided builder and GtAiStepScreen can be reached from the iPad as well. Android keeps
    // its own names as delegating getters -- the AIFLOW-2026-11-23 pattern, one copy, here.
    // Same fields, same initial values, nothing redesigned.
    const val STEP_TRAILHEAD = 1
    const val STEP_RETURN    = 2
    const val STEP_ENDPOINT  = 3
    const val STEP_ASK       = 4
    const val STEP_INCLUDE   = 5
    const val STEP_SUMMARY   = 6
    const val STEP_SEARCH    = 7

    val aiBusy = mutableStateOf(false)
    val aiProgress = mutableStateOf("")
    val pinNotice = mutableStateOf("")
    val pinTrailName = mutableStateOf("")
    val pinTrailLat = mutableStateOf(0.0)
    val pinTrailLon = mutableStateOf(0.0)
    val pinRideName = mutableStateOf("")
    val pinIsLoop = mutableStateOf<Boolean?>(null)
    val pinEndLat = mutableStateOf(0.0)
    val pinEndLon = mutableStateOf(0.0)
    val pinPoints = mutableStateOf(listOf<Pair<Double, Double>>())

    /** AISTATE-2026-11-24: the feasibility the HOST computed, as the already-shared
     *  GtRoute.PinFeasibility (GtRoute.kt:1019) -- field for field Android's own
     *  RouteExplorer copy (RouteExplorer.kt:91). A SEAM, not a move: the assessor stays
     *  on the host, and the builder reads only onFragment / unreachable / totalMiles /
     *  overMiles / underMiles off it, which is exactly what ConvoyPinMileageHud already
     *  takes as plain parameters. null = the assessor is still working. */
    var pinFeas: () -> GtRoute.PinFeasibility? = { null }

    // ---- ROUTEMANUAL-2026-11-23: the route-building mode, shared ----------------
    /** RouteDraftStore.UNNAMED (:703) -- the unnamed-draft literal. ONE COPY: Android's const now
     *  reads from here. \u26d4 Live rider data: never reworded. */
    const val UNNAMED = GtRouteLaunch.UNNAMED   // ROUTELAUNCH-2026-11-23: one copy, in GtRouteLaunch

    /** RouteModule.kt:90-95's four. Same names, same types, same initial values; RouteModule points
     *  at these, so its call sites are untouched and the two platforms cannot disagree. */
    // ROUTELAUNCH-2026-11-23: the route-building state is GtRouteLaunch's -- these point at it, so the
    // iPad host's existing reads and writes are unchanged and both platforms share one copy.
    val routeMode get() = GtRouteLaunch.routeMode
    val addPointMode get() = GtRouteLaunch.addPointMode
    val routeName get() = GtRouteLaunch.routeName
    val routeEntryNonce get() = GtRouteLaunch.routeEntryNonce

    /** The row's action: raise the launch panel and stop. */
    fun launch() { step.intValue = STEP_LAUNCH }

    /**
     * ROUTERECIPE-2026-11-23: ROW 3 HANDS THE FLOW A RECIPE. [recipeJson] is a route's notes.recipe,
     * read by the caller through the existing service (RideSpatial.routeRecipe).
     * \u26a0 ANDROID'S VALIDATION, unchanged (RouteModule.kt:1836-1840): a recipe is recognised by
     *   anchorLat AND milesHigh, bare or under "recipe". False = "that is not a GroupTrack route
     *   recipe", which the caller reports.
     * It loads ONLY what this flow holds -- the distance and speed answers -- and stops. The anchor,
     * pins and finish belong to the map steps, which are not driven here yet.
     */
    fun applyRecipe(recipeJson: String): Boolean {
        val o = (runCatching { GtOrgJson.parse(recipeJson) }.getOrNull()) as? JsonObject ?: return false
        val r = (o["recipe"] as? JsonObject) ?: o
        if (r["anchorLat"] == null || r["milesHigh"] == null) return false
        fun intOf(key: String, fallback: Int): Int {
            val d = GtOrgJson.optDouble(r, key, Double.NaN)
            return if (d.isNaN()) fallback else d.toInt()
        }
        miLow.intValue = intOf("milesLow", miLow.intValue)
        miHigh.intValue = intOf("milesHigh", miHigh.intValue)
        mphLow.intValue = intOf("mphLow", mphLow.intValue)
        mphHigh.intValue = intOf("mphHigh", mphHigh.intValue)
        step.intValue = STEP_DISTANCE
        log.i { "ROUTERECIPE-2026-11-23: recipe loaded -- " + miLow.intValue + "-" + miHigh.intValue +
            " mi at " + mphLow.intValue + "-" + mphHigh.intValue + " mph" }
        return true
    }

    /**
     * ROW 1's ACTION -- RouteModule.startNew's state work, for a host that has no RouteModule.
     * \u26a0 Android still runs its own startNew (frozen, and it also sets the method and lifecycle
     *   ints and calls its two host lambdas). This is the same five steps in the same order; the
     *   duplication closes when RouteModule moves.
     * [js] is the host's map channel -- Android's GtRoute.js, the iPad's own js(), same script.
     */
    fun manualLaunch(js: (String) -> Unit) {
        log.i { "ROUTEMANUALLOG-2026-11-23: manualLaunch ENTERED" }   // ROUTEMANUALLOG-2026-11-23
        routeName.value = UNNAMED
        routeEntryNonce.intValue++
        addPointMode.value = true        // taps are vertices, not pins
        routeMode.value = true           // the planner's outer gate
        js("window.__routeMode=true;setRouteMode(true)")
        log.i { "ROUTEMANUALLOG-2026-11-23: state set routeMode=" + routeMode.value +
            " addPointMode=" + addPointMode.value + " name=" + routeName.value +
            " nonce=" + routeEntryNonce.intValue }   // ROUTEMANUALLOG-2026-11-23
    }

    /** CANCEL on any of the panels. */
    fun cancel() { step.intValue = STEP_NONE }

    /**
     * THE EVALUATION THE LAUNCH PANEL PRECEDES (Fred 12:11: "it either launches ai planning or the
     * compare function. it just preceded the existing eval").
     * Returns TRUE when a prior AI batch is open, which is the caller's cue to say so -- the compare
     * table is Android's today, so the iPad reports it rather than pretending.
     */
    fun eval(): Boolean {
        if (hasOpenBatch()) { step.intValue = STEP_NONE; return true }
        step.intValue = STEP_WELCOME
        return false
    }

    /** The duration label, as RouteModule.kt computes it at its own call site (\u00b1 the rounding of
     *  a label: Java's Math.round vs Kotlin's roundToLong differ only on an exact .5). */
    fun hoursText(): String {
        val lo = (miLow.intValue.toDouble() / mphHigh.intValue.toDouble().coerceAtLeast(1.0)).roundToLong()
        val hi = (miHigh.intValue.toDouble() / mphLow.intValue.toDouble().coerceAtLeast(1.0)).roundToLong()
        return "" + lo + " to " + hi + " hours"
    }

    /**
     * RouteDraftStore.hasOpenBatch (:153-168) over the FOUR JSON PRIMITIVES (ROUTEJSONPRIM-2026-11-23),
     * so it answers on both platforms: read the batch, count the drafts it names that still exist, and
     * clear a batch that names none (BATCHHEAL-2026-10-07).
     * \u26a0 Android still runs its own copy in RouteDraftStore -- that one is the frozen, proven path
     * and is not retouched mid-certification. Flagged for consolidation after certification.
     */
    fun hasOpenBatch(): Boolean {
        val text = GtRoute.env.jsonRead(BATCH) ?: return false
        val b = (runCatching { GtOrgJson.parse(text) }.getOrNull()) as? JsonObject ?: run {
            log.w { "batch unreadable, ignoring" }
            return false
        }
        val arr = b["routes"] as? JsonArray
        var live = 0
        if (arr != null) for (i in 0 until arr.size) {
            val n = (arr.getOrNull(i) as? JsonObject)?.let { GtOrgJson.optString(it, "name") }.orEmpty()
            if (n.isNotEmpty() && GtRoute.env.jsonRead(n + ".json") != null) live++
        }
        if (live == 0) {
            log.w { "BATCHHEAL: open batch '" + GtOrgJson.optString(b, "batchName") + "' names no surviving drafts -- cleared" }
            GtRoute.env.jsonDelete(BATCH)
            return false
        }
        return true
    }

    /** RouteDraftStore BATCH_DIR (:95) + BATCH_FILE (:94), under the route json root. */
    private const val BATCH = "batch/open_batch.json"

    // FINDRIDES-2026-11-24 (B2): the AI search, shared. RouteModule.findRides (:711-849) over
    // the SHARED engine and the B1 batch layer. Android keeps its certified copy; this is the
    // iPad's path until the two collapse.
    const val AI_RETRY_FLOOR = 10   // RouteModule.kt:1692

    /** The host's spatial connection. SEAM, and the important one: the closure showed that
     *  carrying Android's getSpatialDb would have dragged in closeLookups, getExtensionDb and
     *  a 67-line init reaching tile sources, API keys and syncColumnFile. One seam, none of it. */
    var dbConn: () -> androidx.sqlite.SQLiteConnection? = { null }

    /** RouteDraftStore.traceCompare. SEAM, and the second cut: its own tail was
     *  GroupTrackStorage -> isInternal -> chooseMode / split -> storedIn -> MBTilesStore.
     *  It is a diagnostic log, so a host that does nothing with it loses nothing load-bearing. */
    var trace: (String, String) -> Unit = { _, _ -> }

    /** RouteModule.openCompare. The compare table is Android-only (AiCompareTable.kt, 0 iPad
     *  call sites) -- Build C. Until then the iPad's wiring SAYS so rather than doing nothing. */
    var openCompare: () -> Unit = {}

    /** Android's notice / noticeFilter / noticeAllLand (RouteModule.kt:115-117) as ONE seam,
     *  rather than relocating three more host fields: (text, offerFilter, offerAllLand). */
    var showNotice: (String, Boolean, Boolean) -> Unit = { _, _, _ -> }

    /** Android's selection (RouteModule.kt:118) -- the trails this search worked on, as text. */
    var selectionNow: () -> String = { "" }

    /** The waypoint-name lookup for recipeText. iPad: GtRead.waypointNameAt (GtRead.kt:445). */
    var waypointNameAt: (Double, Double) -> String? = { _, _ -> null }

    /** RouteModule.clearPins (:276-280) -- five lines, and every field is already shared. */
    fun clearPins() {
        pinTrailName.value = ""; pinTrailLat.value = 0.0; pinTrailLon.value = 0.0
        pinNotice.value = ""; pinIsLoop.value = null; pinEndLat.value = 0.0; pinEndLon.value = 0.0
        pinPoints.value = emptyList()
    }

    /**
     * RouteModule.findRides (:711-849). Named runSearch because findRides is already the host
     * seam above. Android's Thread + Handler.post becomes the caller's dispatch, as the onMapTap
     * relay already does for GtRouteLaunch.onTap.
     *
     * ⚠ THE SEARCH RUNS ON THE CALLER'S THREAD. Android gives it its own Thread and its own db
     *   handle. The iPad's dbConn hands back the MAP's connection (IosWriteDb.conns, whose own
     *   comment says it reuses the pair deliberately because two connections to one file can
     *   disagree), and that pair is not guarded for concurrent use -- so running this off the
     *   main thread would race the map. Blocking for the length of a search is the safer of the
     *   two failures; the real fix is a second db handle for the search, not a dispatcher.
     */
    suspend fun runSearch() {
        if (pinBusy()) {   // AIPINWAIT-2026-11-11
            GtRouteLaunch.toast("Checking your last pin -- one moment, then press again.")
            return
        }
        pinNotice.value = ""
        step.intValue = STEP_SEARCH
        aiBusy.value = true
        aiProgress.value = "Starting"
        val aLat = pinTrailLat.value
        val aLon = pinTrailLon.value
        val rn = pinRideName.value
        val aName = pinTrailName.value   // AIFAIL-2026-11-03
        val mLo = miLow.intValue; val mHi = miHigh.intValue
        val sLo = mphLow.intValue; val sHi = mphHigh.intValue
        val pins = pinPoints.value
        val loop = pinIsLoop.value
        val eLat = pinEndLat.value; val eLon = pinEndLon.value
        var made = 0
        var err = ""
        var summary = ""   // AIFAIL-2026-11-03
        var lowUsed = mLo   // AIFLOOR10-2026-11-11
        // SEARCHOFFMAIN-2026-11-24: the search, the batch write and the recipe run OFF the
        // main thread, against the host's OWN read-only connection -- Android does the same on
        // its own Thread. The state writes inside are Compose snapshot state, which Android's
        // version also writes from its Thread (aiProgress, RouteModule.kt:744).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        try {
            val db = dbConn()
            if (db == null) {
                err = "No trail data on this device yet"
            } else {
                fun search(lo: Int) = com.grouptrack.shared.route.GtRoute.explore(
                    db,
                    com.grouptrack.shared.route.GtRoute.Request(
                        anchorLat = aLat, anchorLon = aLon, name = rn,
                        milesLow = lo.toDouble(), milesHigh = mHi.toDouble(),
                        mphLow = sLo.toDouble(), mphHigh = sHi.toDouble(),
                        includePoints = pins),
                ) { p -> aiProgress.value = p.step + (if (p.detail.isNotBlank()) " \u2014 " + p.detail else "") }
                var out = search(mLo)
                // AIFLOOR10-2026-11-11 (Fred 08:54): nothing in the band -> once more from 10
                //  miles (same ceiling; the network is cached). Not again after that.
                if (out.isEmpty() && mLo > AI_RETRY_FLOOR && step.intValue == STEP_SEARCH) {
                    aiProgress.value = "No $mLo-$mHi mile rides here \u2014 looking from $AI_RETRY_FLOOR miles"
                    out = search(AI_RETRY_FLOOR)
                    lowUsed = AI_RETRY_FLOOR
                }
                com.geeksville.mesh.convoy.GtRouteBatch.writeBatch(
                    batchName = rn,
                    draftNames = out.map { it.draftName },
                    miles = out.map { it.miles },
                    hoursLow = out.map { it.hoursLow },
                    hoursHigh = out.map { it.hoursHigh },
                    anchorLat = aLat, anchorLon = aLon,
                    anchorName = null,
                    milesLow = lowUsed.toDouble(), milesHigh = mHi.toDouble(),
                    mphLow = sLo.toDouble(), mphHigh = sHi.toDouble(),
                    pins = pins,
                    finish = if (loop == false) eLat to eLon else null,
                )
                com.geeksville.mesh.convoy.GtRouteBatch.recordSearchOutcome(out.size, com.geeksville.mesh.convoy.GtRouteBatch.readBatch() != null, null)
                trace("AI search end (iPad)", "made " + out.size + " route(s) for '" + rn + "'")
                made = out.size
                val recipe = com.geeksville.mesh.convoy.GtRouteBatch.currentRecipe(aLat, aLon, aName, mLo, mHi, sLo, sHi, pins,
                    if (loop == false) eLat to eLon else null)
                summary = com.geeksville.mesh.convoy.GtRouteBatch.recipeText(recipe) { la, lo -> waypointNameAt(la, lo) } + "\n" + com.geeksville.mesh.convoy.GtRouteBatch.statsText()
                com.geeksville.mesh.convoy.GtRouteBatch.recordSearchDetail(summary, recipe)
            }
        } catch (e: Exception) {
            com.geeksville.mesh.convoy.GtRouteBatch.recordSearchOutcome(-1, false, e.message ?: e.toString())
            runCatching {   // AIFAIL-2026-11-03: a failed search keeps its recipe too
                val recipe = com.geeksville.mesh.convoy.GtRouteBatch.currentRecipe(aLat, aLon, aName, mLo, mHi, sLo, sHi, pins,
                    if (loop == false) eLat to eLon else null)
                summary = com.geeksville.mesh.convoy.GtRouteBatch.recipeText(recipe) { la, lo -> waypointNameAt(la, lo) } + "\n" + com.geeksville.mesh.convoy.GtRouteBatch.statsText()
                com.geeksville.mesh.convoy.GtRouteBatch.recordSearchDetail(summary, recipe)
            }
            err = "Could not build rides: " + (e.message ?: "error")
        }
        }
        val fMade = made
        val fLow = lowUsed
        val fErr = err
        val fSum = summary
        aiBusy.value = false
        if (fMade > 0 && hasOpenBatch()) {
            if (fLow < mLo) GtRouteLaunch.toast(
                "No $mLo-$mHi mile rides from this trailhead \u2014 showing rides from $fLow miles")
            // the flow ends; the compare table takes over (it turns route mode off and fits the routes)
            step.intValue = STEP_NONE
            routeMode.value = false
            GtRouteLaunch.routeMethod.intValue = com.geeksville.mesh.convoy.ROUTE_METHOD_P2P
            GtRouteLaunch.js("window.__pinSelect=false;showAimRing(false);clearPins()")
            clearPins()
            openCompare()
        } else {
            // VISIBLE: back to the summary with the reason -- Start over or Cancel build from there.
            step.intValue = STEP_SUMMARY
            val tf = com.grouptrack.shared.filter.TrailFilterState
            val allLand = tf.land != "PUBLIC" && tf.land != "PRIVATE" && tf.restricted
            val fst = com.grouptrack.shared.route.GtRoute.lastStats
            // AIFEWFEAT-2026-11-10 (Fred 23:27)
            val fewFeatures = fErr.isEmpty() && pins.isEmpty() &&
                (fst.stop.startsWith("no features") || fst.stop.contains("features here, but no ride"))
            val nf = if (fst.features < 0) 0 else fst.features
            // AIFAILNOTICE-2026-11-11 (Fred 08:23) / AIFAILLONGEST-2026-11-11 (Fred 08:24)
            val tmi = fst.trailMiles
            val lmi = fst.longestMiles
            // AIFAILSHORT-2026-11-11 (Fred 08:29 "the message should be smaller")
            val here = (if (nf == 1) "1 feature" else "$nf features") +
                (if (tmi >= 0.0) " on " + com.grouptrack.shared.text.GtJava.round(tmi) +
                    " miles of connected trail" else "") + " from this trailhead." +
                (if (lmi > 0.0 && lmi < mLo) " The longest ride here is about " +
                    com.grouptrack.shared.text.GtJava.round(lmi) + " miles." else "")
            val text = (if (fErr.isNotEmpty()) fErr + "\n\n"
                else if (fewFeatures || fst.features >= 0)
                    "Not a good area for a $mLo-$mHi mile ride: " + here + "\n\n"
                else "No rides could be built from that trailhead and distance.\n\n") +
                (if (!allLand) "Some land types are hidden. Turn on Public, Private and Restricted in FILTER and search again: " +
                    "every trail is then searched, and the map shows which parts cross private land and which are closed to " +
                    "motor vehicles.\n\n"
                else "") +
                // AIFAILNOTICE-2026-11-11 (Fred 08:22): the instruction on every failed search
                "With so few features and trail connections, the manual route planner is the better choice here. Or START OVER: " +
                "pick a trailhead with more connected trails inside the orange rectangle, or drop pins along connected trails.\n\n" +
                "What was searched:\n" + fSum.ifEmpty { "(not recorded)" } +
                (if (selectionNow().isNotEmpty()) "\n\nTrails in this search:\n" + selectionNow() else "") +
                (if (!allLand) "\n\nSHOW ALL LAND AND SEARCH AGAIN does that in one tap. " else "\n\n") +
                "OPEN FILTER to see or change the trail selection, then BACK TO THE PLAN to search again. " +
                "To report it, tap ISSUE: the report carries this search and its recipe."
            showNotice(text, true, !allLand)
        }
    }

    // TRAILTABLE-2026-11-24: the plan's trail table state, and the ONE host act a FILTER change
    // needs -- the redraw. Android: GtOpenModule.trailsTick++ then refreshSelection. iPad:
    // js(viewportJs) then refreshSelection. CODE RULE 1: the default does nothing, which means an
    // unwired host changes the filter and does not redraw -- visibly stale, not silently wrong.
    val selTable = mutableStateOf(com.grouptrack.shared.route.GtRoute.SelTable())
    var filterChanged: () -> Unit = {}

    // AIPIN-2026-11-24: THE AI PIN TAP, shared so the iPad can receive it. vec_spatial.js:180
    // and :264 already send it on BOTH platforms, gated on window.__pinSelect, and the 11-19
    // cert recorded that all 14 names ARRIVE on the iPad -- onProximityTap simply never had a
    // branch in the relay. Android's own onPinTap (RouteModule.kt:398) is NOT touched here: it
    // certified on Droid 1 today, threading included. The two collapse once the iPad proves.
    const val PIN_REMOVE_MI = 0.15          // RouteModule.kt:1690
    const val TRAILHEAD_SELECT_MI = 0.062   // RouteModule.kt:1691

    /** CODE RULE 1 -- every default below is the BLOCKING answer, deliberately. An unwired host
     *  then shows Android's own "No trailhead there" / "No trail you are showing" notice rather
     *  than silently accepting a trailhead it never checked. A permissive default would pass the
     *  check by doing nothing, which is the failure that cannot be seen. */
    var pinBusy: () -> Boolean = { false }
    var nearestTrailheadAt: suspend (Double, Double) -> Pair<String, DoubleArray>? = { _, _ -> null }
    var trailsShown: () -> Boolean = { false }
    var shownTrailMetersAt: suspend (Double, Double) -> Double? = { _, _ -> null }
    var hiddenTrailMetersAt: suspend (Double, Double) -> Double? = { _, _ -> null }

    /** RouteModule.kt:398 onPinTap, moved verbatim. Math is com.grouptrack.shared.text.GtMath / com.grouptrack.shared.text.GtJava (the TrackRules.kt:52
     *  idiom, proven against the Droid on 1.5M values); the toast is GtRouteLaunch.toast; the
     *  dispatch belongs to the caller, as the onMapTap relay branch already does. */
    suspend fun onPinTap(lat: Double, lon: Double) {
        val s = step.intValue
        if ((s == STEP_ENDPOINT || s == STEP_INCLUDE) && pinBusy()) {   // AIPINWAIT-2026-11-11
            GtRouteLaunch.toast("Checking your last pin -- one moment.")
            return
        }
        if (s == STEP_ENDPOINT) {
            pinEndLat.value = lat
            pinEndLon.value = lon
            pinNotice.value = ""
            runAssess()
            return
        }
        if (s == STEP_INCLUDE) {
            val cosLat = com.grouptrack.shared.text.GtMath.cos(com.grouptrack.shared.text.GtJava.toRadians(lat))
            val hit = pinPoints.value.indexOfFirst { p ->
                val dy = (p.first - lat) * 69.0
                val dx = (p.second - lon) * 69.0 * cosLat
                com.grouptrack.shared.text.GtMath.hypot(dy, dx) < PIN_REMOVE_MI
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
            GtRouteLaunch.toast(pinPoints.value.size.toString() + " of " + PIN_MAX + " places")
            runAssess()
            return
        }
        if (s == STEP_TRAILHEAD) {
            val found = nearestTrailheadAt(lat, lon)
            if (found == null) {
                pinNotice.value = "No trailhead there. Tap the trailhead marker itself, or add one with a long press."
                return
            }
            val la = found.second.getOrElse(0) { 0.0 }
            val lo = found.second.getOrElse(1) { 0.0 }
            // AIFAILCHECK-2026-11-03 (Fred 07:16-07:18): 1/2 mile; FILTER, not the map key.
            if (!trailsShown() || shownTrailMetersAt(la, lo) == null) {
                val hid = hiddenTrailMetersAt(la, lo)
                pinNotice.value = "No trail you are showing is within 1/2 mile of this trailhead (the circle). " +
                    (if (hid != null) "A trail FILTER is hiding is " + com.grouptrack.shared.text.GtJava.round(hid) + " m away: turn its land or trail type on in FILTER, " +
                        "or choose a trailhead within 1/2 mile of a trail."
                    else "Choose a trailhead within 1/2 mile of a trail.")
                return
            }
            pinTrailName.value = found.first
            pinTrailLat.value = la
            pinTrailLon.value = lo
            pinNotice.value = ""
            step.intValue = STEP_RETURN
        }
    }

    // AIGUIDED-2026-11-24: the guided plan's SUMMARY / STEPS / ACTIONS, moved here verbatim
    // from RouteModule.kt:1213-1287 so the iPad draws the same steps 3-6 from the same lists.
    // Same strings, same state rules, same order. The one substitution is Math.round ->
    // GtJava.round (GtJava.kt:26) = java.lang.Math.round exactly (ties toward +infinity,
    // NaN -> 0, saturating), so the Droid's label is unchanged and the iPad now matches it.
    // The panels themselves do NOT move -- each host keeps its own header and its own help.

    /** PLANTABLE-2026-11-03 (Fred 07:39-07:41): the plan in one line, then the checks. */
    fun summaryText(): String {
        val miLo = miLow.intValue; val miHi = miHigh.intValue
        val mphLo = mphLow.intValue; val mphHi = mphHigh.intValue
        val thName = pinTrailName.value
        val isLoop = pinIsLoop.value
        val hrLo = miLo.toDouble() / mphHi.toDouble()
        val hrHi = miHi.toDouble() / mphLo.toDouble()
        return thName.ifBlank { "Your trailhead" } + " · " + miLo + "–" + miHi + " mi · " + mphLo + "–" + mphHi +
            " mph · about " + com.grouptrack.shared.text.GtJava.round(hrLo) + "–" + com.grouptrack.shared.text.GtJava.round(hrHi) + " h with stops · " +
            (if (isLoop == false) "finishes at your end point" else "loop back to the start") +
            (if (checks.value.isNotEmpty()) "\n\n" + checks.value else "")
    }

    fun guidedSteps(): List<com.geeksville.mesh.convoy.GuidedStep> {
        val s = step.intValue
        val thName = pinTrailName.value
        val isLoop = pinIsLoop.value
        val pts = pinPoints.value
        val GS = com.geeksville.mesh.convoy.GP_STATE_TODO
        val GC = com.geeksville.mesh.convoy.GP_STATE_CURRENT
        val GD = com.geeksville.mesh.convoy.GP_STATE_DONE
        return listOf(
            com.geeksville.mesh.convoy.GuidedStep(
                title = "Select your trailhead / long press to add the trailhead",
                instruction = "Use search to navigate to your area — follow the area name with the two-character " +
                    "state, since names repeat across states.\n\nThe blue circle shows the trails that can start your " +
                    "route. Move the map if the trail you want to launch from is not inside it.\n\nTap the trailhead " +
                    "you are starting from, or press and hold for a second to add one.",
                answer = thName,
                state = if (s == STEP_TRAILHEAD) GC else GD),
            com.geeksville.mesh.convoy.GuidedStep(
                title = "Add one if none exists",
                instruction = "Long press at the start of the trail, choose Trailhead, and give it a name. Then tap it to select it.",
                answer = if (thName.isBlank()) "" else "Not needed",
                state = if (s == STEP_TRAILHEAD) GC else GD),
            com.geeksville.mesh.convoy.GuidedStep(
                title = "Does the ride return there?",
                instruction = "Answer yes for a loop back to your trailhead.",
                answer = if (thName.isBlank()) "" else "Returns to " + thName,
                state = when { s < STEP_RETURN -> GS; s == STEP_RETURN -> GC; else -> GD }),
            com.geeksville.mesh.convoy.GuidedStep(
                title = "Where does the ride finish?",
                instruction = "Tap the place you want the ride to end.",
                answer = if (isLoop == false && pinEndLat.value != 0.0) "Finish point set" else "",
                state = when { isLoop != false -> GS; s == STEP_ENDPOINT -> GC; s > STEP_ENDPOINT -> GD; else -> GS }),
            com.geeksville.mesh.convoy.GuidedStep(
                title = "Add your own places?",
                instruction = "Answer yes to drop pins on places the ride should reach. Answer no and the ride is designed for you.",
                answer = when { s <= STEP_ASK -> ""; pts.isEmpty() -> "Designed for you"; else -> "Adding your own places" },
                state = when { s < STEP_ASK -> GS; s == STEP_ASK -> GC; else -> GD }),
            com.geeksville.mesh.convoy.GuidedStep(
                title = "Places to pass through",
                instruction = "Green trails are connected to your trailhead -- drop pins on them. " +   // AICONNECTED-2026-11-11
                    "Tap the closest trail to each place you want the ride to reach — up to " + PIN_MAX +
                    ". Tap a pin again to remove it. I will favour rides that pass named points of interest.",
                answer = if (pts.isEmpty()) "" else pts.size.toString() + " place" + (if (pts.size == 1) "" else "s") + " included",
                state = when { s < STEP_INCLUDE -> GS; s == STEP_INCLUDE -> GC; else -> GD }),
            com.geeksville.mesh.convoy.GuidedStep(
                title = "Review and search",
                instruction = if (s == STEP_SEARCH) aiProgress.value.ifBlank { "Working…" } else summaryText(),
                answer = "",
                state = if (s < STEP_SUMMARY) GS else GC)
        )
    }

    fun guidedActions(): List<Pair<String, () -> Unit>> = when (step.intValue) {
        STEP_RETURN -> listOf(
            "YES — LOOP" to { pinNotice.value = ""; pinIsLoop.value = true; step.intValue = STEP_ASK },
            "DROP ENDPOINT PIN NOW" to { pinNotice.value = ""; pinIsLoop.value = false; step.intValue = STEP_ENDPOINT })
        STEP_ENDPOINT -> listOf(
            "CONTINUE" to { pinNotice.value = ""; step.intValue = STEP_ASK })
        STEP_ASK -> listOf(
            "YES — I'LL DROP PINS" to { pinNotice.value = ""; step.intValue = STEP_INCLUDE },
            "NO — DESIGN IT FOR ME" to { pinNotice.value = ""; pinPoints.value = emptyList(); step.intValue = STEP_SUMMARY })
        STEP_INCLUDE -> listOf(
            "DONE" to { pinNotice.value = ""; step.intValue = STEP_SUMMARY })
        STEP_SUMMARY -> listOf(
            "FIND MY RIDES" to { findRides() })
        else -> emptyList()
    }

    // AIBUILD-2026-11-24: the rest of the state the guided builder reads, and the HOST
    // CALLS it makes. Same relocation as AISTATE-2026-11-24. The five seams carry the
    // Android functions that take an android.content.Context, which commonMain cannot
    // name -- the function bodies do not move, only the call becomes reachable.
    val checks = mutableStateOf("")
    val filterPeek = mutableStateOf(false)
    const val PIN_MAX = 5

    var refreshSelection: () -> Unit = {}
    var findRides: () -> Unit = {}
    var runAssess: () -> Unit = {}
    var pinReset: () -> Unit = {}
    var aiCancel: () -> Unit = {}
}
