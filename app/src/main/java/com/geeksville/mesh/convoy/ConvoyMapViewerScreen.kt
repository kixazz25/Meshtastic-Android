package com.geeksville.mesh.convoy
// [V2.6a-WEBP] read intercepts serve image/webp

import android.annotation.SuppressLint
import android.location.Geocoder
import android.webkit.WebView
import android.webkit.JavascriptInterface
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.ExperimentalComposeUiApi
// RECREATE-2026-08-11D: imports for the Recreate results dialog.
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.window.Popup
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box

/**
 * Standalone map viewer with trail overlays and track display.
 * V2.4 -- independent from convoy map. Uses grouptrack_map.html.
 */
// Three-state display: OFF=0, ON=1, SELECTED=2
private const val DS_OFF = 0
// Canonical 12 waypoint types (B1 + rally). label shown in picker; key stored in DB.
// GUIDEDPIN-2026-08-24C ── the guided pin flow's steps ──────────────────────
/* AISTEPS-2026-08-28: the overview and the parameters are STATES ON THIS
 * MACHINE, not a parallel counter.
 *
 * ⭐ Fred, 08-28: the checklist "becomes the background process evoking all new
 * panels -- becomes a screenless process at the end." One machine drives both
 * the old rows and the new panels, so they cannot drift apart, and when the
 * last row is ported nothing is left over to delete.
 *
 * ⚠ NEGATIVE, so that every existing ordering comparison still holds. The
 * constants below are compared by order -- "pinStep < PIN_STEP_RETURN" --
 * and renumbering to make room at the front would mean touching all of them
 * for no behaviour.
 */
/* FLOWTRACE-2026-08-28: the flow, readable in one log.
 *
 * ⚠ NAMES, NOT NUMBERS. "TRAILHEAD -> ASK" is readable; "1 -> 4" means opening
 * this file to decode it, which is what makes a log go unread.
 */
private fun pinStepName(v: Int): String = when (v) {
    PIN_STEP_WELCOME   -> "WELCOME"
    PIN_STEP_DISTANCE  -> "DISTANCE"
    PIN_STEP_NONE      -> "NONE"
    PIN_STEP_TRAILHEAD -> "TRAILHEAD"
    PIN_STEP_RETURN    -> "RETURN"
    PIN_STEP_ENDPOINT  -> "ENDPOINT"
    PIN_STEP_ASK       -> "ASK"
    PIN_STEP_INCLUDE   -> "INCLUDE"
    PIN_STEP_SUMMARY   -> "SUMMARY"
    PIN_STEP_SEARCH    -> "SEARCH"
    else               -> "?" + v
}

private const val PIN_STEP_WELCOME   = -2
private const val PIN_STEP_DISTANCE  = -1
private const val PIN_STEP_NONE      = 0
private const val PIN_STEP_TRAILHEAD = 1
private const val PIN_STEP_RETURN    = 2
// ROUTEASSIST-2026-08-25B1: REVIEW retired -- the include step shows the
// floor mileage, the order and each pin's cost as they accumulate, so a
// review afterwards repeats what the rider already watched.
private const val PIN_STEP_ENDPOINT  = 3
/* ROUTEASSIST-2026-08-25S1: DO YOU WANT TO DROP PINS AT ALL?
 *
 * Without this the checklist has no bypass and explore mode is
 * unreachable: DONE is absent while pinFeas is null, and with zero pins
 * assess() returns null, so the rider could only Start Over.
 *
 * The NO path needs nothing in the engine -- Request.includePoints already
 * documents empty as explore mode.
 */
private const val PIN_STEP_ASK       = 4
private const val PIN_STEP_INCLUDE   = 5
// ROUTEASSIST-2026-08-25B1b: SUMMARY is the DECISION state -- the prose,
// PROCEED and START OVER. SEARCH is the same card working, with no
// buttons. B1 collapsed the two and lost the decision surface.
private const val PIN_STEP_SUMMARY   = 6

/* ROUTEASSIST-2026-08-25B2 -- ten is the cap because it is what bounds the
 * Held-Karp table and the Dijkstra count, not because ten is a nice number.
 * An eleventh tap SAYS SO rather than being ignored: a tap that does
 * nothing reads as a broken map, not as a limit.
 *
 * PIN_REMOVE_MI is the toggle radius -- a tap this close to a pin removes
 * it. ~800 ft, comfortably bigger than a fingertip at trail zoom and
 * smaller than the distance between two places worth visiting.
 */
/* PINCAP-2026-08-26: FIVE, and it is a COMPUTE BOUND.
 *
 * MEASURED 08-26: ten pins took the tested ceiling from FOUR (explore mode,
 * same ground) to ELEVEN, and the route build ran five minutes and was still
 * slowing.
 *
 * The pins are WHY the pair table could not prune it. All ten sat within a
 * 14.9-mile tour of each other (assess: floor=14.9 mi), so no pair was
 * impossible, the table had nothing to reject, the clique came back at 19, and
 * orderTour brute-forces permutations for any set of eight or fewer.
 *
 * NOT a judgement about riding. Fred's better answer, not yet built: over five
 * pins the pin set is not a variable at all -- every route contains all of them
 * -- so take assess()'s optimal tour as the skeleton and ENRICH it. No
 * combinations, no clique. Until that exists, this bound holds.
 */
private const val PIN_MAX = 5
private const val PIN_REMOVE_MI = 0.15
private const val PIN_STEP_SEARCH    = 7

/** A trailhead is a large physical area -- trucks and trailers. Two riders
 *  pinning opposite ends of the same gravel lot are 400 ft apart and BOTH
 *  CORRECT, so identity here is PROXIMITY, not geom_hash. Fred, 08-24:
 *  "a trailhead is huge to staging trailers and carts ... a quarter mile is
 *  generous enough." */
private const val TRAILHEAD_DEDUP_MI = 0.25

/** How close a tap has to be to COUNT AS pointing at a trailhead. ~100 m, the
 *  same tolerance onProximityTap already uses for every other artifact.
 *
 *  ⚠ DELIBERATELY NOT the dedup radius. Selection is the rider pointing at one
 *  specific pin and a quarter mile would grab the wrong one on a busy map.
 *  Dedup is asking whether two coordinates mean the same parking area, where a
 *  quarter mile is barely generous. Same question, two tolerances. */
private const val TRAILHEAD_SELECT_MI = 0.062

/** Miles between two lat/lon, flat-earth. Good to a fraction of a percent at
 *  quarter-mile scale and it costs one sqrt -- haversine here would be
 *  precision nobody can use. */
private fun gpMilesBetween(
    lat1: Double, lon1: Double, lat2: Double, lon2: Double
): Double {
    val dLat = (lat2 - lat1) * 69.0
    val dLon = (lon2 - lon1) * 69.0 *
        kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2.0))
    return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
}

/** "POINT(lon lat)" -> [lat, lon], or null.
 *  ⚠ WKT IS LON LAT and this app stores lat lon. Getting that backwards puts
 *  a Utah trailhead in the Indian Ocean, and it would look like a dedup bug
 *  rather than a parse bug. */
private fun gpParsePointWkt(wkt: String?): DoubleArray? {
    if (wkt.isNullOrBlank()) return null
    val m = Regex("-?\\d+\\.?\\d*").findAll(wkt).map { it.value }.toList()
    if (m.size < 2) return null
    val lon = m[0].toDoubleOrNull() ?: return null
    val lat = m[1].toDoubleOrNull() ?: return null
    return doubleArrayOf(lat, lon)
}

/**
 * The nearest EXISTING trailhead within a quarter mile, or null.
 *
 * Reuses queryWaypointsByViewport rather than adding a DB function: a quarter
 * mile is a tiny box and the type filter is cheap in Kotlin. A second spatial
 * query would be a second place that decides what "near" means.
 *
 * Background thread only -- it opens the spatial DB.
 */
private fun gpNearestTrailhead(
    lat: Double, lon: Double, withinMi: Double = TRAILHEAD_DEDUP_MI
): Map<String, String?>? {
    val dLat = withinMi / 69.0
    val dLon = withinMi /
        (69.0 * kotlin.math.max(0.2, kotlin.math.cos(Math.toRadians(lat))))
    val found = SpatialDbManager.queryWaypointsByViewport(
        lat - dLat, lon - dLon, lat + dLat, lon + dLon, 200
    )
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

private val WAYPOINT_TYPES: List<Pair<String, String>> = listOf(
    "hazard" to "☠ Hazard",
    "gate" to "⛔ Gate",
    "water" to "💧 Water",
    "fuel" to "⛽ Fuel",
    "shelter" to "🏠 Shelter",
    "trailhead" to "🥾 Trailhead",
    "viewpoint" to "👁 Viewpoint",
    "campsite" to "⛺ Campsite",
    "parking" to "P Parking",
    "junction" to "Y Junction",
    "rally" to "🚩 Rally",
    "other" to "• Other"
)

private const val DS_ON = 1
private const val DS_SELECTED = 2

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConvoyMapViewerScreen(
    onBack: () -> Unit,
    onNavigateToTrackExport: () -> Unit = {},
    onNavigateToTrackImport: () -> Unit = {},
    onNavigateToTrailSources: () -> Unit = {},
    convoyViewModel: ConvoyViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // MAPKEYS-2026-09-01: read the saved Map Key state BEFORE the WebView
    // exists. ⛔ NOT a cosmetic ordering point -- Fred, 09-01: "not just a
    // frame, everything until something changes that causes the filter to
    // recalc." An unloaded filter shows EVERYTHING for the whole session, so a
    // rider who set private off, closed the app and reopened it would see their
    // filter apparently ignored: "I will get bombed with questions if they
    // open, the state is right but the display is wrong."
    // ⭐ Here rather than at splash: this runs AFTER the authority gate, and
    // the file is in shared storage which nothing may touch before the gate
    // passes. It is a small file read during the GPS fix -- dead time anyway.
    remember { TrailFilterState.load(); true }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Clean up WebView when leaving Planning Map
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }
    var activeSource by remember { mutableStateOf("SAT") }
    var trailsOn by remember { mutableStateOf(false) }
    var trailsLoaded by remember { mutableStateOf(false) }
    var showTrackPanel by remember { mutableStateOf(false) }
    var showTrackMenu by remember { mutableStateOf(false) }
    var trackFileList by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadedTracks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var trackSearchText by remember { mutableStateOf("") }
    val trackColors = listOf("#39FF14")
    var nextColorIdx by remember { mutableIntStateOf(0) }
    // Action menu state — for rename/delete/share/move/fix-date
    var actionTarget by remember { mutableStateOf<java.io.File?>(null) }
    var renameTarget by remember { mutableStateOf<java.io.File?>(null) }
    var deleteTarget by remember { mutableStateOf<java.io.File?>(null) }
    var actionStatusMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val refreshTracks: () -> Unit = {
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            val files = scanTrackDir(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { trackFileList = files }
        }
    }
    // ── GPX Import: scan Downloads directory ──
    var showImportList by remember { mutableStateOf(false) }
    var lastViewportSouth by remember { mutableStateOf(37.0) }
    var lastViewportWest by remember { mutableStateOf(-114.0) }
    var lastViewportNorth by remember { mutableStateOf(38.0) }
    var lastViewportEast by remember { mutableStateOf(-113.0) }
    // SNAPRADIUS-2026-07-30: zoom has always been arriving on
    // onViewportChanged(n, s, e, w, zoom) and was being discarded. Snap needs
    // it to express its radius in the same units the popup hit test uses.
    // Default 16.0 is a mid-range planning zoom; it is overwritten by the
    // first viewport event, which fires on load.
    var lastViewportZoom by remember { mutableStateOf(16.0) }
    var activeListType by remember { mutableStateOf<String?>(null) }
    var artifactList by remember { mutableStateOf<List<Map<String, String?>>>(emptyList()) }
    var selectedArtifactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingWaypoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // ROUTE BUILDER: route mode active -> Route+ toolbar shown (read by next patch)
    // ADDPOINTMODE-DEFAULT-2026-07-31: was mutableStateOf(true).
    //
    // ⭐ DEVICE-CONFIRMED 07-31: with the default true, EVERY planner track tap
    // was suppressed by the guard at :640 — "PLANNER SUPPRESSED by
    // addPointMode" — with no route ever drawn. Leaflet's bindPopup showed
    // instead, which is why the planner gave a popup where convoy gave the
    // detail panel. (Convoy's onTrackTap has no such guard.)
    //
    // The old comment said "consumed by onMapTap next build". That build never
    // came, and the placeholder default shipped ON.
    //
    // ⭐ THE GUARD IS CORRECT AND STAYS — suppressing a detail panel
    // mid-route-draw is reasonable. Only the starting value was wrong. The one
    // assignment in this file, :1256 `addPointMode = armed`, sets it true when
    // route mode arms and false when it unarms, so the value still comes from
    // arming; it just no longer STARTS armed.
    //
    // ⚠ NOT seeded from the state JSON, deliberately. routeMode two lines below
    // is "LIVE session state ... Recovery launches in onPageFinished after
    // render, not here" — arming before the map renders is the failure that
    // decision prevents. false is simply the honest initial state: on launch
    // you are not adding route points.
    //
    // ⚠ :553 (onMapLongPress) also reads this and has been seeing true since
    // launch. Planner long-press behaviour may change — check it.
    var addPointMode by remember { mutableStateOf(false) }  // Tap:Route(on)/Artifact(off)
    var routeMethod by remember { mutableStateOf(ROUTE_METHOD_P2P) }
    var routeName by remember { mutableStateOf("") }
    // Isolated second read of the saved route-open flag (independent of pmSeed@224,
    // which is read later than this declaration). Gives routeMode its persisted value
    // BEFORE the back-gate at ~204 uses it, so a crash-left-open route restores on launch.
    val routeSeedOpen = remember { MapStateStore.readMap("planning").routeState?.open == true }
    var routeMode by remember { mutableStateOf(false) }
    // ROUTEAI-2026-08-23P: the AI Design panel is a full-screen overlay, not part of
    // the floating toolbar -- it carries a name field, two modes, two ranges and
    // the results list, which the toolbar has no room for. Same pattern as
    // showHomeStatePicker.
    var showAiDesign by remember { mutableStateOf(false) }

    // GUIDEDPIN-2026-08-24C ── guided pin collection ─────────────────────────
    // Which checklist step is live. PIN_STEP_NONE means the flow is not
    // running and long press keeps its ordinary meaning.
    var pinStep by remember { mutableStateOf(PIN_STEP_NONE) }
    var pinExpanded by remember { mutableStateOf(true) }
    // An unexpected result the rider must see. The panel force-expands when
    // this is set: a warning nobody sees is worse than a panel that reappears.
    var pinNotice by remember { mutableStateOf("") }

    // The collected trailhead. Durable -- the waypoint survives START OVER and
    // survives promotion, because the place where road meets trail does not
    // stop being true when a route is saved.
    var pinTrailName by remember { mutableStateOf("") }
    var pinTrailLat by remember { mutableStateOf(0.0) }
    var pinTrailLon by remember { mutableStateOf(0.0) }

    // Stashed from the AI panel so the search can run after pin collection.
    var pinRideName by remember { mutableStateOf("") }
    var pinMiLow by remember { mutableIntStateOf(55) }
    var pinMiHigh by remember { mutableIntStateOf(80) }
    var pinMphLow by remember { mutableIntStateOf(12) }
    var pinMphHigh by remember { mutableIntStateOf(18) }

    /* ROUTEASSIST-2026-08-25B1 -- the ride's SHAPE and the rider's points.
     *
     * pinIsLoop is NULLABLE and that is the point: null means UNANSWERED,
     * which is a third state the UI must show differently from either
     * answer. Same reasoning as Row3Choice? in OsmImportPanel -- absent is
     * exactly what keeps the next step from arming, and a default would
     * mean choosing a shape the rider never picked. (CODE RULE 1.)
     *
     * pinPoints is a plain immutable List in mutableStateOf rather than a
     * mutableStateListOf, so this patch introduces no Compose symbol that
     * is not already imported in this file. Reassignment is the update.
     */
    var pinIsLoop by remember { mutableStateOf<Boolean?>(null) }
    var pinEndLat by remember { mutableStateOf(0.0) }
    var pinEndLon by remember { mutableStateOf(0.0) }
    var pinPoints by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }

    /* ROUTEASSIST-2026-08-25B2 -- the latest feasibility result, and the
     * sequence number that decides whether an arriving result is still the
     * newest. assess() takes 1.5-2.4 s; a rider taps faster than that, so
     * runs overlap and can finish out of order. Without this the panel can
     * settle on the answer for a pin set the rider has already changed.
     */
    // PINCAP-2026-08-26: which assess() request the panel is showing.
    // BATCHGRID-2026-08-27
    var batchGridOpen by remember { mutableStateOf(false) }
    var batchName by remember { mutableStateOf("") }
    var batchRows by remember { mutableStateOf<List<BatchRow>>(emptyList()) }
    // COMPARETABLE-2026-08-27: hidden, not 'compare' — every route is
    // always in the table; tapping a header hides only its LINE.
    var batchHidden by remember { mutableStateOf<Set<String>>(emptySet()) }
    // TABLEPOLISH-2026-08-27: what the layers were before the table opened.
    var batchPrevLayers by remember { mutableStateOf<List<Int>?>(null) }
    /* AISTEPS-2026-08-28: the overview is skippable; the values are not.
     * ⚠ Not persisted yet — it resets each session until there is a
     * settings home for it. */
    var aiSkipOverview by remember { mutableStateOf(false) }
    // AISTEPSCREEN-2026-08-28: the map furniture is set up once per flow
    var aiMapReady by remember { mutableStateOf(false) }
    // SAVESELECTED-2026-08-27
    var batchAreaPrompt by remember { mutableStateOf(false) }
    var batchAreaName by remember { mutableStateOf("") }
    var batchDeleteConfirm by remember { mutableStateOf(false) }
    var batchSaving by remember { mutableStateOf(false) }
    var batchSave by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pinFeasSeq by remember { mutableStateOf(0) }
    var pinFeas by remember {
        mutableStateOf<RouteExplorer.PinFeasibility?>(null)
    }
    var pinAssessSeq by remember { mutableIntStateOf(0) }

    /**
     * Recompute the shortest ride through the rider's pins.
     *
     * ⚠ EVERY PIECE OF STATE IS READ HERE, ON THE MAIN THREAD, and only
     * the captured copies cross into the worker. Reading Compose state
     * inside the thread would be the same defect as calling a main-thread
     * API from a bridge method -- it works until it does not.
     */
    fun gpRunAssess() {
        val aLat = pinTrailLat
        val aLon = pinTrailLon
        if (aLat == 0.0 && aLon == 0.0) { pinFeas = null; return }
        val loop = pinIsLoop
        val eLat = pinEndLat
        val eLon = pinEndLon
        val pts = pinPoints
        val nm = pinRideName
        val mLo = pinMiLow.toDouble()
        val mHi = pinMiHigh.toDouble()
        val sLo = pinMphLow.toDouble()
        val sHi = pinMphHigh.toDouble()
        pinAssessSeq += 1
        val seq = pinAssessSeq
        Thread {
            var out: RouteExplorer.PinFeasibility? = null
            try {
                SpatialDbManager.init(context)
                val db = SpatialDbManager.getSpatialDb()
                if (db != null) {
                    val shape = if (loop == false) {
                        RouteExplorer.RouteShape.PointToPoint(eLat, eLon)
                    } else {
                        RouteExplorer.RouteShape.Loop
                    }
                    out = RouteExplorer.assess(
                        db,
                        RouteExplorer.Request(
                            anchorLat = aLat, anchorLon = aLon,
                            name = nm,
                            milesLow = mLo, milesHigh = mHi,
                            mphLow = sLo, mphHigh = sHi
                        ),
                        pts,
                        shape
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("GuidedPin", "assess failed: " + e.message)
            }
            val res = out
            android.os.Handler(
                android.os.Looper.getMainLooper()
            ).post {
                // Stale results are dropped, not shown. See the note above.
                /* PINCAP-2026-08-26: KEEP A STALE RESULT.
                 *
                 * ⛔ This discarded anything that was not the newest request.
                 * assess() takes 1.5-2.4 s and ten pins were dropped in NINE
                 * SECONDS on 08-26, so every result came back stale, pinFeas
                 * stayed null, and DONE never rendered -- while the log showed
                 * assess() succeeding on every single drop.
                 *
                 * ⭐ A stale result is still a TRUE measurement of the pins it
                 * was given. It is merely not the newest. Keep it; a newer one
                 * overwrites it a second later, and in the meantime the rider
                 * has a mileage figure and a DONE button instead of nothing.
                 *
                 * ⚠ The guard still has a job -- an OLDER result must not
                 * overwrite a NEWER one -- so compare sequences instead.
                 */
                if (res != null && seq >= pinFeasSeq) {
                    pinFeasSeq = seq
                    pinFeas = res
                        /* SATFIXES-2026-08-29 (8): what the engine ACTUALLY did.
                         * ⚠ Two pins were accepted on 08-28 and neither was
                         * reached, with `unreachable` empty — so the engine is
                         * likely routing to a SUBSET and reporting no failure.
                         * `order` is the pins actually in the tour. */
                        if (res != null) android.util.Log.i("PanelTrace",
                            "ASSESS -> order=" + res.order +
                            " unreachable=" + res.unreachable +
                            " onFragment=" + res.onFragment +
                            " over=" + res.overMiles)
                        /* PATCHB-2026-08-28 (11): ACT ON WHAT ASSESS ALREADY KNOWS.
                         *
                         * ⭐⭐ Fred, 08-28: "we test reachability already — we
                         * build a route to the launch point to measure distance.
                         * The work is already done, we just need to monitor the
                         * result and take action."
                         *
                         * PinFeasibility has carried `unreachable`, `onFragment`
                         * and `overMiles` all along and nothing read them. This
                         * is not new logic.
                         *
                         * ⚠ NO DIALOG. Fred: "not routable, bye bye pin." A pin
                         * argued over has already been numbered, and removing it
                         * later renumbers everything after it under the rider's
                         * finger.
                         */
                        if (res != null && pinPoints.isNotEmpty()) {
                            val last = pinPoints.size - 1
                            val noRoute = res.unreachable.contains(last) ||
                                res.onFragment.contains(last)
                            if (noRoute) {
                                pinPoints = pinPoints.dropLast(1)
                                pinNotice = "No route to this pin."
                                android.util.Log.i("PanelTrace",
                                    "PIN removed: no route")
                            } else if (res.overMiles > 0.0) {
                                pinPoints = pinPoints.dropLast(1)
                                pinNotice = "That pin puts the ride past your distance."
                                android.util.Log.i("PanelTrace",
                                    "PIN removed: over by " + res.overMiles + " mi")
                            }
                        }
                }
            }
        }.start()
    }

    /** Clears the SELECTION, never the waypoint. Fred, 08-24: "only thing
     *  saved for start over is the trailhead waypoint but it still must be
     *  selected to restart the process." The waypoint is ground truth; the
     *  selection of it is not, and nothing carries forward implicitly. */
    val pinReset = {
        pinTrailName = ""; pinTrailLat = 0.0; pinTrailLon = 0.0
        pinNotice = ""; pinExpanded = true
        // ROUTEASSIST-2026-08-25B1: start over means the SHAPE is unanswered
        // again, not silently a loop, and every dropped point is gone.
        pinIsLoop = null
        pinEndLat = 0.0; pinEndLon = 0.0
        pinPoints = emptyList()
        pinFeas = null
        android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> TRAILHEAD"); pinStep = PIN_STEP_TRAILHEAD
    }

    // PINSELECT-2026-08-24G: mirror pinStep into the map as __pinSelect.
    //
    // True ONLY while the checklist is asking which trailhead. The JS waypoint
    // handler branches on this BEFORE its __routeMode check, so the tap reaches
    // onProximityTap instead of falling through to the vertex path.
    //
    // ⚠ Driven by an effect rather than set at each transition. pinStep moves
    // from four places already -- proceed, select, start over, back -- and a
    // flag written at each of them is a flag that gets missed at the fifth.
    androidx.compose.runtime.LaunchedEffect(pinStep) {
        // ROUTEASSIST-2026-08-25B2: the finish point and the include points
        // are taps on open ground, so the bridge has to be live for those
        // steps too -- not only while a trailhead marker is being picked.
        val on = pinStep == PIN_STEP_TRAILHEAD ||
            pinStep == PIN_STEP_ENDPOINT ||
            pinStep == PIN_STEP_INCLUDE
        webViewRef?.evaluateJavascript("window.__pinSelect=" + on + ";", null)
        android.util.Log.i("GuidedPin", "__pinSelect=" + on + " (pinStep=" + pinStep + ")")
    }

    /* PINDRAW-2026-08-25P2b: the dropped points, drawn.
     *
     * ⛔ WITHOUT THIS A DROP IS INVISIBLE. Fred, 08-25: "since there is no
     * pin you cannot tell if your point registered visibly." The mileage is
     * the only feedback, and a point that adds little does not move it
     * perceptibly. No silent processes.
     *
     * ⭐ KEYED ON THE LIST, NOT HUNG OFF THE THREE MUTATION SITES. pinPoints
     * changes in pinReset(), in the remove branch and in the add branch; an
     * effect on the list covers all three by construction, and a fourth
     * mutation added later cannot silently fail to draw.
     */
    androidx.compose.runtime.LaunchedEffect(pinPoints) {
        val json = pinPoints.joinToString(",", "[", "]") {
            "[%.6f,%.6f]".format(it.first, it.second)
        }
        webViewRef?.evaluateJavascript("renderPins('" + json + "')", null)
        android.util.Log.i("GuidedPin", "renderPins " + pinPoints.size + " point(s)")
    }

    /* AIMODE-2026-08-25B4b: ENTERING THE AI PANEL RESETS BOTH MODES.
     *
     * Fred, 08-25: reset on entry, "you then have to enter route mode to
     * do anything and the natural progression takes over -- that's what we
     * do with route mode."
     *
     * ⛔ NOTHING HERE ARMS ROUTE MODE, deliberately. An earlier draft did,
     * and a blast analysis found six consumers that would have moved with
     * it: the Route+ toolbar render, the WIP-notes button, two action
     * blocks including the tile-source bar, and persistence into
     * planning_panel.json, which the unnamed-draft resolver reads on the
     * next launch. The rider arms route mode; we only ever clear.
     *
     * ⭐ THIS IS ALSO THE CRASH RECOVERY. A crash or abrupt exit mid-flow
     * leaves stale flags behind; re-entering the panel clears them before
     * anything can act on them. It is the one case the entry and exit
     * rules do not otherwise cover, and it needs no extra mechanism.
     *
     * Keyed on showAiDesign rather than hung off the three sites that set
     * it true -- one seam, and a fourth entry point cannot miss it.
     */
    androidx.compose.runtime.LaunchedEffect(showAiDesign) {
        if (showAiDesign) {
            // AIMODE-2026-08-25B4c: AI STATE ONLY. Route mode is NOT ours to clear.
            //
            // ⛔ B4b cleared it here and that was the defect: the AI chip lives
            // inside the route toolbar, which renders on `routeMode`, so route
            // mode is ALREADY ON before the chip can be reached. Clearing it
            // shut the tap handler's outer gate, and every rider tap fell
            // through to Leaflet as an artifact popup instead of a pin.
            //
            // Fred, 08-25: "it should be armed as it is only accessible through
            // the route+ panel which sets routes on."
            //
            // ⭐ Nothing in the AI flow arms route mode, so nothing in the AI
            // flow disarms it. The crash-recovery purpose is unaffected: stale
            // AI flags are what a re-entry must clear, and route mode is not a
            // stale flag -- it is the session the rider is already in.
            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> NONE"); pinStep = PIN_STEP_NONE
            pinFeas = null
            webViewRef?.evaluateJavascript("window.__pinSelect=false;", null)
            android.util.Log.i("GuidedPin", "AI panel entry -- AI state reset, route mode untouched")
        }
    }
    // WIPNOTES-2026-08-23S: the WIP narrative panel. Only reachable while a draft is
    // open, and only when that draft actually carries notes.
    var showWipNotes by remember { mutableStateOf(false) }
    // ROUTEEXPLORE-2026-08-23T: the explorer runs off-thread and reports back through
    // these. The panel renders whatever aiResults holds.
    var aiBusy by remember { mutableStateOf(false) }
    var aiProgress by remember { mutableStateOf("") }
    var aiResults by remember { mutableStateOf<List<AiRouteResult>>(emptyList()) }
    // ROUTETAP-2026-08-23Z: a SAVED route's narrative, read from route_notes. Distinct
    // from showWipNotes, which reads the draft file -- two sources, one panel.
    var savedNotesRouteId by remember { mutableStateOf<String?>(null) }   // LIVE session state (back-gate). Recovery launches in onPageFinished after render, not here.
    var showNameDialog by remember { mutableStateOf(false) }
    var routeEntryNonce by remember { mutableStateOf(0) }   // ++ on every route-mode entry; re-arms toolbar build controls
    // route lifecycle (Layer 2): launch state fixed at New / Select-In-Progress
    var routeLifecycleState by remember { mutableStateOf(ROUTE_LS_NEW) }
    var showSaveChoice by remember { mutableStateOf(false) }
    var showDiscardChoice by remember { mutableStateOf(false) }
    var showInProgressPicker by remember { mutableStateOf(false) }
    var showEntryChoice by remember { mutableStateOf(false) }
    var draftRenameTarget by remember { mutableStateOf<String?>(null) }
    var draftRenameText by remember { mutableStateOf("") }
    var draftRenameErr by remember { mutableStateOf("") }
    // OSM-IMPORT-2026-07-28: OSM import overlay. No nav route on purpose -- the
    // panel is planner-only, and every row's state is DERIVED FROM DISK, so
    // there is nothing to preserve across recomposition.
    var showOsmPanel by remember { mutableStateOf(false) }
    var showHomeStatePicker by remember { mutableStateOf(false) }
    // AREAWIRE-2026-08-21C: non-null holds the drawn bbox (S,W,N,E) AND is the
    // "area import overlay is open" flag. One piece of state, not two -- two
    // flags for one concept is the 00f defect this codebase already carries.
    var areaImportBbox by remember { mutableStateOf<DoubleArray?>(null) }
    // TRAILSELECT-2026-08-21D: the Import Trails selector, and the any-state picker
    // it drills down to. Two booleans because they are two different screens,
    // not two names for one state.
    var showTrailImportSelector by remember { mutableStateOf(false) }
    var showAnyStatePicker by remember { mutableStateOf(false) }
    var recoveryDetected by remember { mutableStateOf(false) }
    var saveOrigName by remember { mutableStateOf("") }   // draft's on-disk name captured when Save panel opens (rename source)
    var recoveryLaunched by remember { mutableStateOf(false) }   // one-shot: recovery detected this session (in onPageFinished)
    var recoveryPending by remember { mutableStateOf(false) }   // show the recovery notice popup after settle
    // [draft-resolver 2026-08-01] Unnamed-draft resolver. Runs at planner entry and at
    // Route+ launch. File ops ONLY -- never arms route mode, never loads geometry.
    var showDraftResolve by remember { mutableStateOf(false) }
    var draftResolvePts by remember { mutableStateOf(0) }
    var draftResolveName by remember { mutableStateOf("") }
    var draftResolveErr by remember { mutableStateOf("") }
    var pendingInventory by remember { mutableStateOf(false) }
    var resolverRan by remember { mutableStateOf(false) }
    var routeNameTaken by remember { mutableStateOf(false) }
    // live In-Progress list: real draft names from RouteDraftStore (refreshed on draftListTick)
    var draftListTick by remember { mutableStateOf(0) }
    // [route-panel 2026-08-02] ALL drafts including the unnamed auto-save. The auto-save
    // belongs in the picker -- it is renamed or deleted there, and New Route is blocked
    // until it is. Sorted oldest-first by createdAt for the list display.
    // DISCARDWINS-2026-08-13C: keyed on draftListTick, which was WRITE-ONLY.
    //
    // The tick is incremented after a delete and after a rename, and was read
    // nowhere. Compose only recomposes when a state it READS changes, so the
    // increment fired into nothing, the list never rebuilt, and a deleted draft
    // stayed on screen to be deleted again.
    //
    // ⚠ It was also a bare val, so it re-read the draft directory from disk on
    // EVERY recomposition, on the main thread. Keying a remember on the tick
    // fixes both: the write causes a rebuild, and a rebuild happens only then.
    val emulatedDrafts = remember(draftListTick) {
        RouteDraftStore.listDrafts().sortedBy { it.createdAt }
    }
    var newWaypointType by remember { mutableStateOf("other") }
    var newWaypointName by remember { mutableStateOf("") }

    var importFileList by remember { mutableStateOf<List<String>>(emptyList()) }
    var importingFile by remember { mutableStateOf<String?>(null) }
    val scanDownloadsForGpx: () -> Unit = {
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            val files = dir.listFiles()?.filter { f ->
                val ext = f.extension.lowercase()
                (ext == "gpx" || ext == "kml") && !f.name.startsWith(".")
            }?.sortedByDescending { it.lastModified() }?.map { it.name } ?: emptyList()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                importFileList = files
                showImportList = true
            }
        }
    }
    val runImport: (String) -> Unit = { fileName ->
        scope.launch {
            importingFile = fileName
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                val sourceFile = java.io.File(downloadsDir, fileName)
                val summary = ConvoyTrackOps.importGpxAllArtifacts(sourceFile, context)
                val msg = buildString {
                    append("Imported: ")
                    val parts = mutableListOf<String>()
                    if (summary.trackCount > 0) parts.add("${summary.trackCount} track${if (summary.trackCount > 1) "s" else ""}")
                    if (summary.waypointCount > 0) parts.add("${summary.waypointCount} waypoint${if (summary.waypointCount > 1) "s" else ""}")
                    if (summary.routeCount > 0) parts.add("${summary.routeCount} route${if (summary.routeCount > 1) "s" else ""}")
                    if (parts.isEmpty()) append("no artifacts found")
                    else append(parts.joinToString(", "))
                    if (summary.errors.isNotEmpty()) append(" (${summary.errors.size} error${if (summary.errors.size > 1) "s" else ""})")
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                refreshTracks()
                webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                // Refresh the import list (file should be gone now)
                scanDownloadsForGpx()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Import error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            importingFile = null
        }
    }

        val unloadIfLoaded: (String) -> Unit = { trackName ->
        if (loadedTracks.any { it.first == trackName }) {
            val safe = trackName.replace("'", "\\'")
            webViewRef?.evaluateJavascript("removeTrackFile('$safe')", null)
            loadedTracks = loadedTracks.filterNot { it.first == trackName }
        }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler {
        if (routeMode) {
            android.widget.Toast.makeText(context, "Save or discard your in-progress route first", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            onBack()
        }
    }
    val coroutineScope = rememberCoroutineScope()

    // Download controls state
    var showDownloadPanel by remember { mutableStateOf(false) }
    var panelTilesChecked by remember { mutableStateOf(false) }
    var showDownloadConfirm by remember { mutableStateOf(false) }
    var downloadReplaceExisting by remember { mutableStateOf(false) }
    var panelTrailsChecked by remember { mutableStateOf(false) }
    var panelRemoveTilesChecked by remember { mutableStateOf(false) }
    // DELETE-AREA-2026-07-25: gated confirm for tile removal. Deleting is
    // IRREVERSIBLE and those tiles cost real Esri requests, so the red button
    // asks before acting - same discipline as the queue panel's scoped
    // CANCEL/CLEAR. (Source selection is deliberately NOT asked: a delete
    // clears the area across every source.)
    var showRemoveTilesConfirm by remember { mutableStateOf(false) }
    var panelFlyoverZoom by remember { mutableStateOf(18) }
    var queueExpanded by remember { mutableStateOf(false) }
    var pmTracksOn by remember { mutableStateOf(false) }
    var pmTracksLoaded by remember { mutableStateOf(false) }
    // Three-state display — per-map state from MapStateStore (independent of convoy map)
    val pmSeed = remember { MapStateStore.readMap("planning") }
    var trailState by remember { mutableStateOf(pmSeed.types["Trails"]?.state ?: DS_OFF) }
    var trackState by remember { mutableStateOf(pmSeed.types["Tracks"]?.state ?: DS_OFF) }
    var waypointState by remember { mutableStateOf(pmSeed.types["Waypoints"]?.state ?: DS_OFF) }
    var routeState by remember { mutableStateOf(pmSeed.types["Routes"]?.state ?: DS_OFF) }
    var searchResults by remember { mutableStateOf(emptyList<ArtifactResult>()) }
    var pendingDetailId by remember { mutableStateOf<String?>(null) }
    var pendingDetailType by remember { mutableStateOf<String?>(null) }
    var trailCheckedIds by remember { mutableStateOf(MapStateStore.checkedIdsFor(pmSeed, "Trails")) }
    var trackCheckedIds by remember { mutableStateOf(MapStateStore.checkedIdsFor(pmSeed, "Tracks")) }
    var waypointCheckedIds by remember { mutableStateOf(MapStateStore.checkedIdsFor(pmSeed, "Waypoints")) }
    var routeCheckedIds by remember { mutableStateOf(MapStateStore.checkedIdsFor(pmSeed, "Routes")) }

    // Persist this map's state to JSON. Checkboxes are state-controlled: rows carry
    // the per-item checked status (the real last state). Geometry is refreshed by the
    // viewport query separately and is not part of this save. Rows for the active list
    // type are built from artifactList + selectedArtifactIds when a list is open.
    fun savePlanningState() {
        fun rowsFor(type: String): List<MapStateStore.Row> {
            // [3.1c] Source SELECTED rows from the persistent per-type checked-id set,
            // NOT the activeListType-gated artifactList. SELECT persists regardless of
            // which panel is open; ALL/OFF have null CheckedIds -> empty rows (query
            // rebuilds the list on entry, state flag sets on/off).
            val checkedIds = when (type) {
                "Trails" -> trailCheckedIds
                "Tracks" -> trackCheckedIds
                "Waypoints" -> waypointCheckedIds
                "Routes" -> routeCheckedIds
                else -> null
            } ?: return emptyList()
            return checkedIds.map { id -> MapStateStore.Row(id, "", true) }
        }
        val types = mapOf(
            "Trails" to MapStateStore.TypeState(trailState, rowsFor("Trails")),
            "Tracks" to MapStateStore.TypeState(trackState, rowsFor("Tracks")),
            "Waypoints" to MapStateStore.TypeState(waypointState, rowsFor("Waypoints")),
            "Routes" to MapStateStore.TypeState(routeState, rowsFor("Routes"))
        )
        val panel = MapStateStore.PanelBoxes(panelTilesChecked, panelTrailsChecked, panelRemoveTilesChecked)
        MapStateStore.saveMap("planning", MapStateStore.MapSnapshot(types, panel, MapStateStore.BBox(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast), null, MapStateStore.RouteState(routeMode, "draft", routeName)))
    }
    // [3.1] debounced viewport-settle save: persist frame on pan/zoom/search settle
    val viewportSaveHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val viewportSaveRunnable = remember { Runnable { savePlanningState() } }
    var pmQueuesOpen by remember { mutableStateOf(false) }
    // CORRIDOR-WIRING-2026-07-24: survives from the corridor button to onProceed, the
    // same way downloadBbox does. NON-NULL MEANS "this is a corridor job" -
    // that is what tells onProceed which branch to take. Cleared on BOTH
    // proceed and cancel, or the next AREA download would be treated as one.
    var pendingCorridorHash by remember { mutableStateOf<String?>(null) }
    // CORRIDORWIRE-2026-08-05: corridor download by track selection.
    // corridorChecked / showCorridorPicker are rememberSaveable so a rotation does
    // not drop them -- the 07-25 defect on the download-confirm dialog was exactly
    // that (plain remember, rotate to reach the buttons, lose the selections).
    // corridorTracks is a plain remember: List<TrackPickInfo> is not Bundle-saveable
    // and it is cheap to reload on open.
    var corridorChecked by rememberSaveable { mutableStateOf(false) }
    var showCorridorPicker by rememberSaveable { mutableStateOf(false) }
    // CORRMIGRATE-SCOPE-2026-08-07J: corridor-delete migration state.
    // SCREEN level on purpose -- these were first written inside
    // `if (showDownloadPanel) {`, where they were BOTH forward-referenced by
    // the dialog 300 lines above AND destroyed whenever the panel closed. The
    // gate must outlive the panel, so it belongs here with showDownloadPanel
    // itself.
    // remember, not rememberSaveable: a migration half-restored across process
    // death would re-open the gate with stale counts. Losing the dialog on
    // process death is correct -- nothing is destroyed before PROCEED.
    var removeTrackChecked by remember { mutableStateOf(false) }
    // RECREATE-2026-08-11A: Recreate Tiles by Source -- scan-and-report, queues nothing yet.
    var recreateSourceChecked by remember { mutableStateOf(false) }
    // RECREATE-2026-08-11B: scan results, shown before anything is queued.
    // rows = one (zoom, count) pair per level actually present in the store.
    var recreateRows by remember { mutableStateOf(listOf<Pair<Int, Int>>()) }
    var recreateTotal by remember { mutableStateOf(0L) }
    var recreateSlot by remember { mutableStateOf("") }
    var recreateScanning by remember { mutableStateOf(false) }
    // RECREATE-2026-08-11B: live progress while the scan runs -- which level it is
    // reading and the running tile count. A COUNT per level is fast, but
    // on a large store it is not instant, and a dialog that says nothing
    // for several seconds reads as hung.
    var recreateProgress by remember { mutableStateOf("") }
    var showRecreateResults by remember { mutableStateOf(false) }

    // RECREATE-2026-08-11F: the scan runs HERE, keyed on the chosen slot, rather than inside
    // the tick handler. Ticking the row sets the slot and opens the panel;
    // picking a different slot inside the panel re-keys this effect and it
    // scans again. One path, so there is no rescan branch to keep in step.
    LaunchedEffect(recreateSlot, showRecreateResults) {
        val slot = recreateSlot
        if (!showRecreateResults || slot.isBlank()) return@LaunchedEffect
        recreateRows = emptyList()
        recreateTotal = 0L
        recreateProgress = ""
        recreateScanning = true
        // Off the main thread deliberately: a large store holds millions of
        // rows, and walking work on the UI thread is what ANR'd the 3,287-job
        // cancel on 08-11.
        Thread {
            val rows = ArrayList<Pair<Int, Int>>()
            var total = 0L
            try {
                val levels = MBTilesStore.zoomLevelsPresent(slot)
                for ((idx, z) in levels.withIndex()) {
                    val n = MBTilesStore.countAtZoom(slot, z)
                    rows.add(z to n)
                    total += n
                    android.util.Log.i("Recreate", "RECREATE-2026-08-11F $slot z$z $n tile(s)")
                    val soFar = ArrayList(rows)
                    val runTotal = total
                    val line = "level ${idx + 1} of ${levels.size} - $runTotal tile(s) so far"
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (recreateSlot == slot) {
                            recreateRows = soFar
                            recreateTotal = runTotal
                            recreateProgress = line
                        }
                    }
                }
                val jobs = if (total <= 0L) 0 else ((total + 50000 - 1) / 50000).toInt()
                android.util.Log.i("Recreate",
                    "RECREATE-2026-08-11F $slot TOTAL $total tile(s) in ${rows.size} level(s) " +
                    "-> $jobs job(s) of up to 50000")
            } catch (e: Exception) {
                android.util.Log.e("Recreate", "RECREATE-2026-08-11F scan failed: ${e.message}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // RECREATE-2026-08-11F: a slower scan of the PREVIOUS slot must not overwrite
                // the results of the one the user has since picked.
                if (recreateSlot == slot) {
                    recreateRows = rows
                    recreateTotal = total
                    recreateProgress = ""
                    recreateScanning = false
                }
            }
        }.start()
    }

    // RECREATE-2026-08-11B: the results dialog. Placed here rather than in the layout tree --
    // an AlertDialog renders in its own window, so it does not need to sit
    // inside a Box or Column, and anchoring at the state declarations avoids
    // guessing at a position in a very large composable.
    if (showRecreateResults) {
        val jobs = if (recreateTotal <= 0L) 0
                   else ((recreateTotal + 50000 - 1) / 50000).toInt()
        AlertDialog(
            onDismissRequest = { showRecreateResults = false },
            title = { Text("Recreate $recreateSlot") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // RECREATE-2026-08-11F: which source. Slot keys come from
                    // getSlotSources(), the same list the tile-source picker
                    // uses, so this cannot offer a slot the app does not have.
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MapSourceManager.getSlotSources().forEach { s ->
                            val key = s.first
                            TextButton(
                                enabled = !recreateScanning && key != recreateSlot,
                                onClick = { recreateSlot = key }
                            ) {
                                Text(
                                    if (key == recreateSlot) "[$key]" else key,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (recreateScanning) {
                        Text("Reading the store...")
                        if (recreateProgress.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(recreateProgress,
                                 style = MaterialTheme.typography.bodySmall)
                        }
                        if (recreateRows.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            recreateRows.forEach { (z, n) ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text("z$z", modifier = Modifier.width(48.dp),
                                         style = MaterialTheme.typography.bodySmall)
                                    Text(n.toString(),
                                         style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else if (recreateRows.isEmpty()) {
                        Text("No tiles are stored for $recreateSlot, so there is " +
                             "nothing to recreate.")
                    } else {
                        Text("These are the tiles this source actually holds. " +
                             "Only these are re-downloaded -- ground with no tiles " +
                             "is never requested.",
                             style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        recreateRows.forEach { (z, n) ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text("z$z", modifier = Modifier.width(48.dp),
                                     style = MaterialTheme.typography.bodyMedium)
                                Text(n.toString(),
                                     style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("$recreateTotal tile(s) in ${recreateRows.size} zoom level(s)",
                             style = MaterialTheme.typography.bodyMedium)
                        Text("$jobs job(s) of up to 50000 tiles",
                             style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Your maps keep working while this runs -- each tile is " +
                             "replaced in place, so nothing is removed first.",
                             style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !recreateScanning && recreateRows.isNotEmpty(),
                    onClick = {
                        showRecreateResults = false
                        recreateSourceChecked = false
                        // RECREATE-2026-08-11G: submit. Off the main thread -- the quadtree
                        // runs a COUNT per quadrant per level, and walking work
                        // on the UI thread is what ANR'd the 3,287-job cancel.
                        val slot = recreateSlot
                        Thread {
                            // RECREATE-2026-08-11H: the composable's Context is `context`.
                            val queued = DownloadQueueManager
                                .enqueueRecreateSource(context, slot)
                            android.util.Log.i("Recreate",
                                "RECREATE-2026-08-11G PROCEED $slot -> $queued job(s) queued")
                        }.start()
                    }
                ) { Text("PROCEED TO COPY") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRecreateResults = false
                    recreateSourceChecked = false
                }) { Text("CANCEL") }
            }
        )
    }
    var showMigrateGate by remember { mutableStateOf(false) }
    var migrateBusy by remember { mutableStateOf(false) }
    var migrateSteps by remember { mutableStateOf(listOf<String>()) }
    var migratePreview by remember {
        mutableStateOf<ConvoyCorridorDelete.PreviewResult?>(null)
    }
    var migrateDone by remember { mutableStateOf(false) }
    // CORRPROGRESS-2026-08-07K: free-text line under the checkmarks, and the
    // 0f..1f bar fraction. -1f means "no determinate progress" (the scan,
    // which cannot know its total until it has read the tracks).
    var migrateProgress by remember { mutableStateOf("") }
    var migrateFraction by remember { mutableFloatStateOf(-1f) }
    var corridorTracks by remember { mutableStateOf<List<TrackPickInfo>>(emptyList()) }
    // "?" help: which bundled doc is open ("manual" | "notes" | null = chooser/closed)
    var docsView by remember { mutableStateOf<String?>(null) }
    var showDocsChooser by remember { mutableStateOf(false) }
    var showArtifactsPanel by remember { mutableStateOf(false) }   // FAB closed-state vs panel open-state
    var pmDownloadedOn by remember { mutableStateOf(false) }
    var pmActiveSource by remember { mutableStateOf(ConvoyConfig.ACTIVE_TILE_SOURCE) }
    var mapZoomLevel by remember { mutableStateOf(ConvoyConfig.DOWNLOAD_ZOOM.toFloat()) }
    var showDownloaded by remember { mutableStateOf(false) }
    var scanningDownloaded by remember { mutableStateOf(false) }
    var fabOffsetX by remember { mutableStateOf(0f) }
    var fabOffsetY by remember { mutableStateOf(0f) }
    var legendOffsetX by remember { mutableStateOf(0f) }
    var legendOffsetY by remember { mutableStateOf(0f) }
    var legendExpanded by remember { mutableStateOf(false) }
    // TRAILSELECT-2026-09-02: the category filter, opened from Map Features >
    // Trails > SELECT. ⚠ Separate from legendExpanded: one panel chooses WHAT
    // SHOWS, the other shows the key and changes HOW IT LOOKS.
    var showTrailFilter by remember { mutableStateOf(false) }
    var downloadBbox by remember { mutableStateOf(DownloadBbox()) }
    var isDrawingArea by remember { mutableStateOf(false) }
    // OSM-C3B-AREA-2026-07-29: the IMPORT OSM checkbox on the download panel.
    // Preselected when a pending_import is awaiting a draw -- derived from
    // disk at panel open (R1), never passed across the panel gap.
    var panelOsmChecked by remember { mutableStateOf(false) }
    // The slug the pending draw belongs to. Null means no draw is expected,
    // which is what stops a stray area draw from filling someone's record.
    var osmAwaitingSlug by remember { mutableStateOf<String?>(null) }
    val downloadState by convoyViewModel.downloadState.collectAsState()

    // Tile sources from map_sources.json — single source of truth
    MapSourceManager.init(context)
    val tileSources = MapSourceManager.getSlotSources()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E14))) {
        // -- Combined header: BACK | sources | QUEUES --
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xCC000000))
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // PLAINCTRL-2026-08-17: says where it goes, not just that it goes back.
            Text("Back to Ride", color = Color(0xFF4DA6FF),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    if (routeMode) {
                        android.widget.Toast.makeText(context, "Save or discard your in-progress route first", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        onBack()
                    }
                }.padding(horizontal = 14.dp, vertical = 14.dp))
            Spacer(Modifier.width(12.dp))
            tileSources.forEach { (label, _, _) ->
                val isActive = pmActiveSource == label
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isActive) Color(0xFF2266CC) else Color.Transparent,
                    modifier = Modifier.clickable {
                        pmActiveSource = label; ConvoyConfig.ACTIVE_TILE_SOURCE = label
                        val url = MapSourceManager.getSlotSources().find { it.first == label }?.third ?: ""
                        webViewRef?.evaluateJavascript("setTileUrl('" + url + "', '" + label + "')", null)
                        val ovJson = MapSourceManager.getOverlayJson(label)
                        if (ovJson != "[]") {
                            webViewRef?.evaluateJavascript("setOverlayLayers('" + ovJson.replace("'", "\\'") + "')", null)
                        }
                    }
                ) {
                    Text(label, color = if (isActive) Color.White else Color(0xFF7A8DA0),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("QUEUES", color = Color(0xFF1CF0A0),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { pmQueuesOpen = !pmQueuesOpen }
                    .padding(horizontal = 14.dp, vertical = 14.dp))
        }
        // Track panel removed
        // -- Back navigation guard --
                    // GUIDEDPIN-2026-08-24D: C's trailhead-capture effect stood
                    // here and NEVER RAN. This block is above `// -- WebView --`
                    // and is not live while the map is in use -- the indent drop
                    // from 20 spaces to 8 at its close is the tell.
                    //
                    // Selection now happens on TAP, in onProximityTap, which is a
                    // plain @JavascriptInterface callback with none of this
                    // problem. Long press keeps its ONE meaning: create a waypoint.
                    pendingWaypoint?.let { (wLat, wLon) ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pendingWaypoint = null },
                            title = { androidx.compose.material3.Text("New Waypoint") },
                            text = {
                                androidx.compose.foundation.layout.Column {
                                    androidx.compose.foundation.layout.FlowRow {
                                        WAYPOINT_TYPES.forEach { (key, label) ->
                                            androidx.compose.material3.FilterChip(
                                                selected = newWaypointType == key,
                                                onClick = {
                                                    newWaypointType = key
                                                    if (newWaypointName.isBlank() || WAYPOINT_TYPES.any { it.second.substringAfter(" ") == newWaypointName }) {
                                                        newWaypointName = label.substringAfter(" ")
                                                    }
                                                },
                                                label = { androidx.compose.material3.Text(label) },
                                                modifier = androidx.compose.ui.Modifier.padding(2.dp)
                                            )
                                        }
                                    }
                                    androidx.compose.material3.OutlinedTextField(
                                        value = newWaypointName,
                                        onValueChange = { newWaypointName = it },
                                        label = { androidx.compose.material3.Text("Name (optional)") },
                                        singleLine = true
                                    )
                                }
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    val nm = if (newWaypointName.isBlank()) "Waypoint" else newWaypointName
                                    val ty = newWaypointType
                                    Thread {
                                        try {
                                            SpatialDbManager.init(context)
                                            SpatialDbManager.insertWaypoint(nm, wLat, wLon, ty)
                                            /* SATFIXES-2026-08-29 (2): creating one
                                             * SELECTS it.
                                             *
                                             * ⛔ Selection is a proximity search
                                             * (gpNearestTrailhead). Creating wrote the
                                             * waypoint and stopped, so the step sat
                                             * waiting for a selection that never came.
                                             *
                                             * ⭐ NOT by re-running the search — the
                                             * name and coordinates are already in hand
                                             * here, and searching for a row we just
                                             * wrote would race the commit.
                                             */
                                            if (ty == "trailhead" &&
                                                pinStep == PIN_STEP_TRAILHEAD) {
                                                pinTrailName = nm
                                                pinTrailLat = wLat
                                                pinTrailLon = wLon
                                                pinNotice = ""
                                                android.util.Log.i("PanelTrace",
                                                    "TRAILHEAD created and selected: " + nm)
                                            }
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                webViewRef?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("WptCreate", "insert failed: " + e.message)
                                        }
                                    }.start()
                                    pendingWaypoint = null
                                    newWaypointName = ""
                                    newWaypointType = "other"
                                }) { androidx.compose.material3.Text("Create") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    pendingWaypoint = null
                                    newWaypointName = ""
                                    newWaypointType = "other"
                                }) { androidx.compose.material3.Text("Cancel") }
                            }
                        )
                    }

        // exit-confirm removed: back returns directly when no route active;
        // when a route is active the BackHandler toast-gates instead (FIX 8).

                // -- WebView --
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        // TAPTRACE-2026-07-30: the planner had NO WebChromeClient,
                        // so every console.log in grouptrack_map.html has gone
                        // nowhere. Convoy has had one since :777 (ConvoyJS).
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(
                                cm: android.webkit.ConsoleMessage
                            ): Boolean {
                                android.util.Log.d(
                                    "MapJS", cm.message() + "  @" + cm.lineNumber()
                                )
                                return true
                            }
                        }
                        settings.javaScriptEnabled = true
                                    // HTMLVER-2026-08-13B: never serve a cached copy of a
                                    // bundled asset. ⚠ A cache-buster on the URL is NOT
                                    // used - WebView treats file:///android_asset/x.html
                                    // as a filename, so a query string risks a 404.
                                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.allowFileAccessFromFileURLs = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onMapTap(lat: Double, lon: Double) {
                                android.util.Log.d("RouteBridge", "onMapTap lat=$lat lon=$lon")
                                kotlinx.coroutines.MainScope().launch {
                                    val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        SpatialDbManager.init(context)
                                        val trails = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                        // SNAP2-TRAILS-ONLY-2026-07-30 (Fred, 2.6e): route planning
                                        // snaps to TRAILS only. No trail in range -> plain point.
                                        // Tracks still render and stay tappable for details; they
                                        // are simply not snap targets.
                                        //
                                        // The queryTracksByViewport that stood here fed nothing but
                                        // the snap call, so removing it also drops one viewport query
                                        // from EVERY map tap.
                                        //
                                        // The by-id redraw below still queries tracks, which is what
                                        // lets routes saved before this change keep drawing. Harmless
                                        // to keep; becomes dead code once old routes are gone.
                                        // SNAPRADIUS-2026-07-30 (Fred): "we need to open
                                        // distance for snap 2 to same distance as popup."
                                        //
                                        // The popup hit test is Leaflet's renderer tolerance --
                                        // 44 PIXELS (L.svg({tolerance: 44}) on the map
                                        // constructor). Snap was a fixed 30 METRES. Pixels scale
                                        // with zoom and metres do not, so the two only agreed
                                        // near z17: at z16 the popup reached ~85 m while snap
                                        // refused past 30 m. That is why a tap could pop a
                                        // trail's name and still free-place the vertex.
                                        //
                                        // Deriving from Leaflet's own tolerance means a retune
                                        // there keeps the two in step instead of silently
                                        // drifting apart again.
                                        //
                                        // Clamp 15..150 m (Fred: "capped 150"). CEILING: at z14
                                        // the raw figure is ~340 m and, trail-first being
                                        // absolute but NEAREST deciding among trails, OSM density
                                        // would start snapping to a parallel trail. FLOOR: at z18
                                        // the raw figure is ~21 m, below today's 30 m -- without
                                        // it, close-in drawing would get worse.
                                        val mPerPx = 156543.03392 *
                                            kotlin.math.cos(Math.toRadians(lat)) /
                                            Math.pow(2.0, lastViewportZoom)
                                        val snapRadiusM = (44.0 * mPerPx).coerceIn(15.0, 150.0)
                                        val s = RouteManager.snapTrailsOnly(lat, lon, trails, snapRadiusM)
                                        android.util.Log.d(
                                            "RouteBridge",
                                            "onMapTap lat=$lat lon=$lon zoom=$lastViewportZoom " +
                                                "radius=${snapRadiusM.toInt()}m trails=${trails.size} -> " +
                                                if (s != null) "SNAPPED ${s.lineType} ${s.lineId}"
                                                else if (trails.isEmpty()) "FREE (no candidates)"
                                                else "FREE (nearest beyond radius)"
                                        )
                                        if (s != null) RouteManager.snapToVertex(s) else RouteManager.freeVertex(lat, lon)
                                    }
                                    RouteManager.addVertex(v)
                                    // AUTO-CHECKPOINT: persist in-progress draft after every point
                                    // so a teardown mid-build loses nothing (recover via start-route+ picker).
                                    if (routeName.isNotBlank()) {
                                        val methodStr = when (routeMethod) { ROUTE_METHOD_DRAW -> "draw"; ROUTE_METHOD_SUGGEST -> "suggest"; else -> "point" }
                                        runCatching {
                                            if (RouteDraftStore.draftExists(routeName)) RouteDraftStore.overwriteDraft(routeName, methodStr)
                                            else RouteDraftStore.writeDraft(routeName, methodStr)
                                        }
                                    }
                                    val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        SpatialDbManager.init(context)
                                        val tl = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                        val tk = SpatialDbManager.queryTracksByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                        val byId = HashMap<String, String>()
                                        for (m in tl) { val id = m["trail_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                        for (m in tk) { val id = m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                        RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                                            .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
                                    }
                                    val vs = RouteManager.routeVertices()
                                    webViewRef?.evaluateJavascript("drawBuildLine('" + pts + "')", null)
                                }
                            }
                            @JavascriptInterface
                            fun onMapLongPress(lat: Double, lon: Double) {
                                // WAYPOINT-ALWAYS-2026-07-30 (Fred): "waypoints
                                // (long press) should always register to create
                                // waypoints." The guard removed here was added
                                // because popups were not responding, so long
                                // presses were frustrated attempts at a popup that
                                // dropped waypoints instead. The cause is fixed in
                                // this same build (bind always + stopPropagation).
                                android.util.Log.d(
                                    "LongPress",
                                    "onMapLongPress lat=$lat lon=$lon addPointMode=$addPointMode"
                                )
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    pendingWaypoint = Pair(lat, lon)
                                }
                            }
                            @JavascriptInterface
                            fun onAreaSelected(north: Double, south: Double, east: Double, west: Double) {
                                android.util.Log.i("DownloadPanel", "onAreaSelected: n=$north s=$south e=$east w=$west")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    downloadBbox = DownloadBbox(north = north, south = south, east = east, west = west)
                                    isDrawingArea = false
                                    android.util.Log.i("DownloadPanel", "downloadBbox updated: valid=${downloadBbox.isValid}")
                                }
                            }
                            @JavascriptInterface
                            fun onMapBoundsReady(n: Double, s: Double, e: Double, w: Double) {}
                            @JavascriptInterface
                            fun onProximityTap(lat: Double, lon: Double) {
                                // TAPTRACE-2026-07-30: silent until now. The four
                                // display states are logged because every branch
                                // below is gated on them -- a DS_OFF type
                                // contributes nothing and looks like a dead tap.
                                android.util.Log.d(
                                    "ProxTap",
                                    "onProximityTap lat=$lat lon=$lon " +
                                        "trail=$trailState track=$trackState " +
                                        "wpt=$waypointState route=$routeState"
                                )
                                // GUIDEDPIN-2026-08-24D: SELECT A TRAILHEAD.
                                //
                                // ⚠ BEFORE the routeMode guard on the next line, not
                                // after it. The checklist runs DURING route mode, so a
                                // branch below that guard would never be reached -- the
                                // same mistake that killed Patch C, one line lower.
                                //
                                // Returns immediately: no proximity popup, no artifact
                                // panel. While the checklist is asking which trailhead,
                                // a tap means that and nothing else.
                                // ROUTEASSIST-2026-08-25B2: same placement rule as
                                // the trailhead branch below -- BEFORE the routeMode
                                // guard, because the checklist runs during route
                                // mode and anything after it is never reached.
                                //
                                // ⚠ This method is on a background thread. Every
                                // state write goes through the main looper; the
                                // work itself is inside gpRunAssess's own Thread.
                                if (pinStep == PIN_STEP_ENDPOINT) {
                                    android.os.Handler(
                                        android.os.Looper.getMainLooper()
                                    ).post {
                                        pinEndLat = lat
                                        pinEndLon = lon
                                        pinNotice = ""
                                        pinExpanded = true
                                        android.util.Log.i("PanelTrace",
                                "ASSESS " + pinPoints.size + " pin(s), ceiling " +
                                pinMiHigh + " mi")
                            gpRunAssess()
                                    }
                                    return
                                }
                                if (pinStep == PIN_STEP_INCLUDE) {
                                    android.os.Handler(
                                        android.os.Looper.getMainLooper()
                                    ).post {
                                        val cosLat = Math.cos(Math.toRadians(lat))
                                        val hit = pinPoints.indexOfFirst { p ->
                                            val dy = (p.first - lat) * 69.0
                                            val dx = (p.second - lon) * 69.0 * cosLat
                                            Math.hypot(dy, dx) < PIN_REMOVE_MI
                                        }
                                        if (hit >= 0) {
                                            pinPoints = pinPoints.filterIndexed { i, _ ->
                                                i != hit
                                            }
                                            pinNotice = ""
                                        } else if (pinPoints.size >= PIN_MAX) {
                                            android.util.Log.i("PanelTrace",
                                                "PIN refused: at cap " + PIN_MAX)
                                            pinNotice = "Ten places is the most a ride " +
                                                "can be planned around. Tap one of your " +
                                                "pins to remove it first."
                                        } else {
                                            pinPoints = pinPoints + (lat to lon)
                                            pinNotice = ""
                                        }
                                        pinExpanded = true
                                        // ROUTEASSIST-2026-08-25C: the count is
                                        // an EVENT, not a status. It matters as
                                        // the pin lands and not after, so it
                                        // toasts rather than holding a line in
                                        // the HUD.
                                        //
                                        // ⚠ INSIDE this post, never outside it.
                                        // Toast.makeText throws off the main
                                        // thread, and onProximityTap is a
                                        // @JavascriptInterface method -- that
                                        // exact throw killed convoy's artifact
                                        // draw on 2026-08-01.
                                        android.widget.Toast.makeText(
                                            context,
                                            pinPoints.size.toString() + " of " +
                                                PIN_MAX + " places",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        android.util.Log.i("PanelTrace",
                                "ASSESS " + pinPoints.size + " pin(s), ceiling " +
                                pinMiHigh + " mi")
                            gpRunAssess()
                                    }
                                    return
                                }
                                if (pinStep == PIN_STEP_TRAILHEAD) {
                                    Thread {
                                        var nm: String? = null
                                        var la = 0.0
                                        var lo = 0.0
                                        try {
                                            SpatialDbManager.init(context)
                                            val hit = gpNearestTrailhead(
                                                lat, lon, TRAILHEAD_SELECT_MI
                                            )
                                            if (hit != null) {
                                                nm = hit["name"] ?: "Trailhead"
                                                gpParsePointWkt(hit["geometry"])?.let {
                                                    la = it[0]; lo = it[1]
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("GuidedPin",
                                                "select failed: " + e.message)
                                        }
                                        val fnm = nm
                                        android.os.Handler(
                                            android.os.Looper.getMainLooper()
                                        ).post {
                                            if (fnm == null) {
                                                // ⚠ SAY SO. A tap that does nothing is
                                                // indistinguishable from a broken app,
                                                // and this one misses often -- the
                                                // rider is aiming at a small marker.
                                                pinNotice = "No trailhead there. Tap the " +
                                                    "trailhead marker itself, or add one " +
                                                    "with a long press."
                                                pinExpanded = true
                                            } else {
                                                pinTrailName = fnm
                                                pinTrailLat = la
                                                pinTrailLon = lo
                                                pinNotice = ""
                                                pinExpanded = true
                                                android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> RETURN"); pinStep = PIN_STEP_RETURN
                                            }
                                        }
                                    }.start()
                                    return
                                }

                                // Query all artifact types near tap point from spatial DB
                                if (routeMode) return  // ROUTETAP-2026-08-02: placing route points -- no artifact popups
                                val radius = 0.0009 // ~100 meters in degrees (ROUTETAP-2026-08-02: was 0.002/200m)
                                val south = lat - radius; val north = lat + radius
                                val west = lon - radius; val east = lon + radius
                                val nlTrail = trailState; val nlTrack = trackState
                                val nlWaypoint = waypointState; val nlRoute = routeState
                                Thread {
                                    try {
                                        SpatialDbManager.init(context)
                                        val names = mutableListOf<String>()
                                        // Trails
                                        if (nlTrail != DS_OFF) {
                                            val trails = SpatialDbManager.queryTrailsByViewport(south, west, north, east, 50)
                                            trails.forEach { t -> t["name"]?.let { names.add("Trail: " + it) } }
                                        }
                                        // Tracks
                                        if (nlTrack != DS_OFF) {
                                            val tracks = SpatialDbManager.queryTracksByViewport(south, west, north, east, 50)
                                            tracks.forEach { t -> t["name"]?.let { names.add("Track: " + it) } }
                                        }
                                        // Waypoints
                                        if (nlWaypoint != DS_OFF) {
                                            val wpts = SpatialDbManager.queryWaypointsByViewport(south, west, north, east, 50)
                                            wpts.forEach { w -> w["name"]?.let { names.add("Waypoint: " + it) } }
                                        }
                                        // Routes
                                        if (nlRoute != DS_OFF) {
                                            val routes = SpatialDbManager.queryRoutesByViewport(south, west, north, east, 50)
                                            routes.forEach { r -> r["name"]?.let { names.add("Route: " + it) } }
                                        }
                                        if (names.isNotEmpty()) {
                                            val html = names.joinToString("<br>")
                                            val escaped = html.replace("'", "\\'")
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                webViewRef?.evaluateJavascript(
                                                    "showProximityPopup(" + lat + "," + lon + ",'" + escaped + "')", null)
                                            }
                                        }
                                    } catch (ex: Exception) {
                                        android.util.Log.e("Proximity", "Query failed: " + ex.message)
                                    }
                                }.start()
                            }
                            @JavascriptInterface
                            fun onTrackTap(id: String) {
                                // [2026-07-02] track tap -> open the shared ArtifactDetailPanel (metrics + SAVE MAPS).
                                //
                                // TAPTRACE-2026-07-30: addPointMode is a KOTLIN flag,
                                // distinct from the JS window.__routeMode. The 07-30
                                // logs proved __routeMode=false at tap time; they say
                                // nothing about this one. If it is stuck true, the
                                // return below is silent -- no log, no panel, no
                                // crash. The guard is KEPT (suppressing a detail
                                // panel mid-route-draw is reasonable) but is now
                                // visible.
                                android.util.Log.d(
                                    "TrackTap", "PLANNER bridge id=$id addPointMode=$addPointMode"
                                )
                                if (addPointMode) {
                                    android.util.Log.d("TrackTap", "PLANNER SUPPRESSED by addPointMode")
                                    return
                                }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.util.Log.d("TrackTap", "PLANNER post -> setting state id=$id")
                                    pendingDetailType = "Tracks"
                                    pendingDetailId = id
                                }
                            }
                            @JavascriptInterface
                            fun onTrailTap(id: String) {
                                // TRAILTAP-2026-09-02: mirrors onTrackTap and
                                // onRouteTap exactly -- copied, not invented, so
                                // the three behave alike.
                                // ⚠ SAME addPointMode SUPPRESSION. Opening a
                                // detail panel mid-draw would interrupt route
                                // building, and the tap has already been allowed
                                // through to place a vertex.
                                android.util.Log.d(
                                    "TrailTap", "PLANNER bridge id=$id addPointMode=$addPointMode"
                                )
                                if (addPointMode) {
                                    android.util.Log.d("TrailTap", "PLANNER SUPPRESSED by addPointMode")
                                    return
                                }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    pendingDetailType = "Trails"
                                    pendingDetailId = id
                                }
                            }
                            @JavascriptInterface
                            fun onRouteTap(id: String) {
                                // ROUTETAP-2026-08-23Z: mirrors onTrackTap above. A route
                                // is an artifact we own, so it opens the shared detail
                                // panel; trails and waypoints keep their popups.
                                android.util.Log.d(
                                    "RouteTap", "PLANNER bridge id=$id addPointMode=$addPointMode"
                                )
                                // ⚠ SAME SUPPRESSION AS TRACKS. Opening a detail panel
                                // mid-draw would interrupt route building, and the tap
                                // has already been allowed through to place a vertex.
                                if (addPointMode) {
                                    android.util.Log.d("RouteTap", "PLANNER SUPPRESSED by addPointMode")
                                    return
                                }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    pendingDetailType = "Routes"
                                    pendingDetailId = id
                                }
                            }
                            @JavascriptInterface
                            fun onMapReady(n: Double, s: Double, e: Double, w: Double) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    val wv = webViewRef ?: return@post
                                    val src = ConvoyConfig.ACTIVE_TILE_SOURCE
                                    val url = MapSourceManager.getSlotSources().find { it.first == src }?.third
                                        ?: tileSources.firstOrNull()?.third ?: return@post
                                    wv.evaluateJavascript("setTileUrl('" + url + "', '" + src + "')", null)
                                    val ovJson = MapSourceManager.getOverlayJson(src)
                                    if (ovJson != "[]") {
                                        wv.evaluateJavascript("setOverlayLayers('" + ovJson.replace("'", "\'") + "')", null)
                                    }
                                }
                            }
                            @JavascriptInterface
                            fun onViewportChanged(north: Double, south: Double, east: Double, west: Double, zoom: Double) {
                                lastViewportSouth = south; lastViewportWest = west; lastViewportNorth = north; lastViewportEast = east
                                lastViewportZoom = zoom   // SNAPRADIUS-2026-07-30: was discarded
                                viewportSaveHandler.removeCallbacks(viewportSaveRunnable); viewportSaveHandler.postDelayed(viewportSaveRunnable, 400)
                                // GATE: reseed this map's local state from JSON only if the active
                                // map changed since last refresh (else live vars stay authoritative).
                                if (MapStateStore.lastMapProcessed != "planning") {
                                    // ⚠ setRouteMode(false), NOT `__routeMode = false` -- the
                                    // function also reasserts consequences (the popup
                                    // unbind/rebind). The raw flag would clear the value and
                                    // leave whatever the previous state applied, which is
                                    // exactly the "mode off, popups still gone" seen 07-31.
                                    // VIEWPORTMAIN-2026-08-05: was a DIRECT evaluateJavascript on the JavaBridge thread.
                                    // @JavascriptInterface methods run off-main; WebView methods must run on
                                    // main and THROW otherwise -- and the throw aborted the rest of this
                                    // method, so the state reads, lastMapProcessed and processViewport below
                                    // never ran. Post to main and continue. Mirrors SpatialDisplayManager,
                                    // which posts every evaluateJavascript through main.post.
                                    webViewRef?.let { _wv ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            _wv.evaluateJavascript("setRouteMode(false)", null)
                                        }
                                    }
                                    val rs = MapStateStore.readMap("planning")
                                    trailState = rs.types["Trails"]?.state ?: DS_OFF
                                    trackState = rs.types["Tracks"]?.state ?: DS_OFF
                                    waypointState = rs.types["Waypoints"]?.state ?: DS_OFF
                                    routeState = rs.types["Routes"]?.state ?: DS_OFF
                                    trailCheckedIds = MapStateStore.checkedIdsFor(rs, "Trails")
                                    trackCheckedIds = MapStateStore.checkedIdsFor(rs, "Tracks")
                                    waypointCheckedIds = MapStateStore.checkedIdsFor(rs, "Waypoints")
                                    routeCheckedIds = MapStateStore.checkedIdsFor(rs, "Routes")
                                }
                                // Always query — data preloaded, toggle controls visibility
                                val z = zoom.toInt()
                                val limit = if (z < 14) 500 else 2000
                                // [Stage 2] Route the draw through the shared SpatialDisplayManager
                                // instead of the inline copy. State comes from planning's LIVE local
                                // vars (the reseed gate above just populated them from JSON).
                                val states = mapOf(
                                    "Trails" to trailState,
                                    "Tracks" to trackState,
                                    "Waypoints" to waypointState,
                                    "Routes" to routeState
                                )
                                val selectLists = mapOf(
                                    "Trails" to trailCheckedIds,
                                    "Tracks" to trackCheckedIds,
                                    "Waypoints" to waypointCheckedIds,
                                    "Routes" to routeCheckedIds
                                )
                                MapStateStore.lastMapProcessed = "planning"
                                Thread {
                                    SpatialDisplayManager.processViewport(south, west, north, east, z, states, selectLists, webViewRef, context)
                                }.start()
                            }
                        }, "Android")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                // OFFTRACE-2026-08-11M: log EVERY request. ⚠ This interceptor has NO
                                // convoy:// branch at all -- only the two Esri overlay
                                // URLs below -- so a convoy:// request falls through to
                                // super and the WebView cannot resolve the scheme.
                                // Logging first, before assuming that is the whole story.
                                if (url.contains("tile") || url.startsWith("convoy://")) {
                                    android.util.Log.i("OFFTRACE", "OFFTRACE-2026-08-11M planner REQ $url")
                                }
                                // Esri URL is tile/z/y/x but local storage is source/z/x/y.png
                                if (url.contains("/Reference/World_Transportation/MapServer/tile/")) {
                                    // [V2.6-PASS1-READ] Transportation overlay from MBTiles (raw z/x/y)
                                    val parts = url.split("/tile/").lastOrNull()?.split("/")
                                    if (parts != null && parts.size >= 3) {
                                        val z = parts[0].toIntOrNull(); val y = parts[1].toIntOrNull(); val x = parts[2].substringBefore('.').toIntOrNull()
                                        if (z != null && x != null && y != null) {
                                            val bytes = MBTilesStore.readTile("SAT_LABELS_TRANSPORT", z, x, y)
                                            if (bytes != null) {
                                                return android.webkit.WebResourceResponse("image/webp", null, java.io.ByteArrayInputStream(bytes))
                                            }
                                        }
                                    }
                                }
                                if (url.contains("/Reference/World_Boundaries_and_Places/MapServer/tile/")) {
                                    // [V2.6-PASS1-READ] Places overlay from MBTiles (raw z/x/y)
                                    val parts = url.split("/tile/").lastOrNull()?.split("/")
                                    if (parts != null && parts.size >= 3) {
                                        val z = parts[0].toIntOrNull(); val y = parts[1].toIntOrNull(); val x = parts[2].substringBefore('.').toIntOrNull()
                                        if (z != null && x != null && y != null) {
                                            val bytes = MBTilesStore.readTile("SAT_LABELS_PLACES", z, x, y)
                                            if (bytes != null) {
                                                return android.webkit.WebResourceResponse("image/webp", null, java.io.ByteArrayInputStream(bytes))
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                            // HTMLVER-2026-08-13B: read the HTML's own version back so settings
                            // can show it. Cheap, once per page load.
                            view?.evaluateJavascript("window.__htmlVersion || ''") { v ->
                                val clean = v?.trim('"') ?: ""
                                if (clean.isNotBlank() && clean != "null") {
                                    ConvoyConfig.MAP_HTML_VERSION = clean
                                    android.util.Log.i("HtmlVer", "HTMLVER-2026-08-13B loaded $clean")
                                }
                            }

                                super.onPageFinished(view, url)
                                MapSourceManager.init(view?.context ?: return)
                                // Initialize spatial DB for map drawing (sync moved to the dedicated control screen)
                                kotlinx.coroutines.MainScope().launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            SpatialDbManager.init(view?.context ?: return@withContext)
                                        } catch (e: Exception) {
                                            android.util.Log.e("TrackSync", "DB init error: " + e.message)
                                        }
                                    }
                                }
                                // SAT tile URL now set via onMapReady callback (no race condition)
                                val initOverlayJson = MapSourceManager.getOverlayJson("SAT")
                                if (initOverlayJson != "[]") {
                                    view?.evaluateJavascript(
                                        "setOverlayLayers('" + initOverlayJson.replace("'", "\'") + "')", null
                                    )
                                }
                                // Trails loaded on demand via TRAILS button
                                // Center map on device GPS position (matches Convoy Map approach)
                                view?.postDelayed({
                                    // [draft-resolver 2026-08-01] PLANNER ENTRY caller. Outside the
                                    // pmbb branch: a cold launch with no persisted frame must resolve too.
                                    if (!resolverRan) {
                                        resolverRan = true
                                        val u = RouteDraftStore.listDrafts().firstOrNull { it.name == RouteDraftStore.UNNAMED }
                                        pendingInventory = true
                                    }
                                    // [3.1] persisted-frame-open: planning restores last-session bbox
                                    // PLANNERSEED-2026-08-05: was pmSeed.bbox -- pmSeed is a remember block captured
                                    // at FIRST COMPOSE (MVS:287) and is stale on re-entry. Re-read FRESH.
                                    // Mirrors convoy [Fix2] (ConvoyScreen.kt:728).
                                    val pmbb = MapStateStore.readMap("planning").bbox
                                    if (pmbb != null) {
                                        // PLANNERSEED-2026-08-05: SEED lastViewport* BEFORE draw -- closes the stale
                                        // window for every artifact query that reads these fields until
                                        // onViewportChanged fires. Mirrors ConvoyScreen.kt:730-733.
                                        lastViewportSouth = pmbb.south; lastViewportWest = pmbb.west
                                        lastViewportNorth = pmbb.north; lastViewportEast = pmbb.east
                                        view?.evaluateJavascript(
                                            "fitBounds([" + pmbb.south + "," + pmbb.north + "],[" + pmbb.west + "," + pmbb.east + "])", null
                                        )
                                        android.util.Log.i("PlanMap", "Restored persisted frame: " + pmbb.south + "," + pmbb.west + " .. " + pmbb.north + "," + pmbb.east)
                                        // [Stage 3] Deterministic artifact restore from JSON (not relying on
                                        // fitBounds->moveend->onViewportChanged to draw).
                                        SpatialDisplayManager.drawPersistedState("planning", view, context)
                                        // Crash recovery: a prior session left a route open. Do NOT auto-launch
                                        // (races render). Flag it — an informational popup shows after settle
                                        // telling the user to resume manually via +ROUTE -> In Progress.
                                        // [draft-resolver 2026-08-01] Retired: the resolver above acts on
                                        // the draft directory directly instead of showing a notice that tells
                                        // the user to go do it themselves.
                                        if (routeSeedOpen && !recoveryLaunched) {
                                            recoveryLaunched = true
                                        }
                                        return@postDelayed
                                    }
                                    try {
                                        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                                        @Suppress("MissingPermission")
                                        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                        if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                                            view?.evaluateJavascript(
                                                "setView(" + loc.latitude + ", " + loc.longitude + ", 15)", null
                                            )
                                            android.util.Log.i("PlanMap", "Centered on GPS: " + loc.latitude + ", " + loc.longitude)
                                        } else {
                                            android.util.Log.w("PlanMap", "GPS: getLastKnownLocation returned null — no cached position")
                                        }
                                    } catch (e: SecurityException) {
                                        android.util.Log.w("PlanMap", "GPS: Location permission not granted")
                                    } catch (e: Exception) {
                                        android.util.Log.e("PlanMap", "GPS: Error getting location: " + e.message)
                                    }
                                }, 600)
                                // Detect max offline zoom from tile directory
                                Thread {
                                    try {
                                        val sourceDir = java.io.File(ConvoyConfig.TILE_DIR, pmActiveSource)
                                        var maxZ = 0
                                        if (sourceDir.exists()) {
                                            sourceDir.listFiles()?.forEach { zDir ->
                                                val z = zDir.name.toIntOrNull()
                                                if (z != null && z > maxZ && zDir.isDirectory) maxZ = z
                                            }
                                        }
                                        if (maxZ > 0) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                view?.evaluateJavascript("setMaxOfflineZoom($maxZ)", null)
                                            }
                                            android.util.Log.i("PlanMap", "Max offline zoom for $pmActiveSource: z$maxZ")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("PlanMap", "Tile scan error: " + e.message)
                                    }
                                }.start()
                                // Trigger initial data load for all artifact types
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                                    android.util.Log.i("PlanMap", "Initial artifact data load triggered")
                                }, 1000)
                            }
                        }
                        loadUrl("file:///android_asset/grouptrack_map.html")
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── Download progress bar ─────────────────────────────────────
            // -- Download queue panel (replaces simple progress bar) --
            // -- SOURCE BAR (accordion with search) --
            // ConvoyMapBar removed — sources now in header bar
            // -- UNIFIED SEARCH FAB (2026-06-19) -- below the "?" (top-right), stacks DOWN --
            // Shared component, planning context. webViewRef is a plain WebView? here
            // (planning does not wrap in MutableState). Artifact results route to the
            // existing detail path (pendingDetailType/Id -> ArtifactDetailPanel).
            // Old planning area-search field remains in place this step (removed later).
            UnifiedSearch(
                mapContext = "planning",
                webView = webViewRef,
                context = context,
                onOpenDetail = { type, id ->
                    pendingDetailType = type
                    pendingDetailId = id
                },
                stackDown = true,
                // ⭐ open on arriving at the trailhead step — that is where the
                // rider finds their area, and the banner tells them to search
                startOpen = pinStep == PIN_STEP_TRAILHEAD,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp)
            )

            // PLANNERKEYS-2026-09-02: Map Keys goes BETWEEN Map Features (64)
            // and Help, so Help moves 116 -> 168. ⚠ The column is absolute top
            // padding, not a stack: inserting a control means moving the ones
            // below it, and anything added later pays the same cost.
            // ⭐ The convoy map got this button on 09-01; the planner kept its
            // old legend FAB because the panel was not proven yet. It is now.
            androidx.compose.material3.Surface(
                onClick = { legendExpanded = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                color = Color.Transparent,
                contentColor = Color(0xFFFF00FF),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 116.dp, end = 12.dp)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    // PLAINCTRL-2026-08-17: words, not a glyph -- riders are
                    // 65-75 and icon literacy cannot be assumed. The white blur
                    // shadow is what keeps it readable over bright satellite.
                    androidx.compose.material3.Text(
                        "Map Keys",
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = androidx.compose.ui.graphics.Color.White,
                                offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                                blurRadius = 6f
                            )
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
            }
            // -- "?" HELP BUTTON (opens bundled release notes / manual) --
            androidx.compose.material3.Surface(
                onClick = { showDocsChooser = true },
                // PLAINCTRL3-2026-08-18B: circle + fixed size dropped so the word renders in full.
                // PLANNERKEYS-2026-09-02: 116 -> 168, Map Keys took 116.
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                color = Color.Transparent,
                contentColor = Color(0xFFFF00FF),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 168.dp, end = 12.dp)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    // PLAINCTRL2-2026-08-17: the word, for the same reason as the others.
                    androidx.compose.material3.Text(
                        "Help",
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = androidx.compose.ui.graphics.Color.White,
                                offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                                blurRadius = 6f
                            )
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
            }
            // -- ARTIFACTS FAB (opens WORK WITH ARTIFACTS expanded; hidden while panel open) --
            if (!showArtifactsPanel) {
                androidx.compose.material3.Surface(
                    onClick = { showArtifactsPanel = true },
                    // PLAINCTRL2-2026-08-17: the planner has its OWN artifacts FAB -- round 1
                    // changed only the convoy one on the assumption it was shared.
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    // PLAINCTRL3-2026-08-18B: fill + size dropped, glyph replaced with words,
                    // matching the convoy hamburger treatment from round 1.
                    color = Color.Transparent,
                    contentColor = Color(0xFFFF00FF),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp)
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Text(
                            "Map Features",
                            fontSize = 13.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = androidx.compose.ui.graphics.Color.White,
                                    offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                                    blurRadius = 6f
                                )
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            // -- "?" CHOOSER: Release Notes / Full Manual --
            if (showDocsChooser) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showDocsChooser = false },
                    title = { androidx.compose.material3.Text("Help & Info") },
                    text = { androidx.compose.material3.Text("View the release notes or the full user manual.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { showDocsChooser = false; docsView = "notes" }) {
                            androidx.compose.material3.Text("Release Notes")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showDocsChooser = false; docsView = "manual" }) {
                            androidx.compose.material3.Text("Full Manual")
                        }
                    }
                )
            }
            // -- DOC VIEWER: full-screen WebView loading the bundled asset --
            if (docsView != null) {
                val assetFile = if (docsView == "notes") "grouptrack_release_notes.html" else "grouptrack_manual.html"
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF10130F)
                ) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                        ) {
                            androidx.compose.material3.TextButton(onClick = { docsView = null }) {
                                androidx.compose.material3.Text("Close")
                            }
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    // HTMLVER-2026-08-13B: never serve a cached copy of a
                                    // bundled asset. ⚠ A cache-buster on the URL is NOT
                                    // used - WebView treats file:///android_asset/x.html
                                    // as a filename, so a query string risks a 404.
                                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                                    settings.allowFileAccess = true
                                    settings.allowFileAccessFromFileURLs = true
                                    webViewClient = WebViewClient()
                                    loadUrl("file:///android_asset/" + assetFile)
                                }
                            },
                            update = { it.loadUrl("file:///android_asset/" + assetFile) }
                        )
                    }
                }
            }
            // -- QUEUES PANEL (live download queue) --
            if (pmQueuesOpen) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth(0.90f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xEE131820),
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("DOWNLOAD QUEUES", color = Color(0xFF1CF0A0),
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                            Text("CLOSE", color = Color(0xFF7A8DA0),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { pmQueuesOpen = false }.padding(8.dp))
                        }
                        DownloadQueuePanel(
                            expanded = true,
                            onToggle = { pmQueuesOpen = false }
                        )
                        // Show message if queue is empty
                        val queueState = DownloadQueueManager.queue.collectAsState()
                        if (queueState.value.isEmpty()) {
                            Text("No downloads in queue",
                                color = Color(0xFF4A6080), fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }

            /* BATCHFORK-2026-08-27: the batch grid.
             *
             * ⭐ A sibling of the artifacts panel inside the map Box, aligned
             * TopStart -- clear of the Distance HUD at TopEnd, and draggable
             * away from anything it does cover.
             */
            if (batchGridOpen && batchRows.isNotEmpty()) {
                android.util.Log.i("PanelTrace", "BATCH renders rows=" + batchRows.size)
                ConvoyBatchGridPanel(
                    batchName = batchName,
                    rows = batchRows,
                    hidden = batchHidden,
                    saveTicks = batchSave,
                    onToggleShown = { n ->
                        val nowHidden = n !in batchHidden
                        batchHidden = if (nowHidden) batchHidden + n else batchHidden - n
                        val safe = n.replace("\\", "\\\\").replace("'", "\\'")
                        webViewRef?.evaluateJavascript(
                            "showBatchRoute('" + safe + "', " + (!nowHidden) + ", 5)", null)
                    },
                    onSaveTick = { n, on ->
                        batchSave = if (on) batchSave + n else batchSave - n
                    },
                    onSaveSelected = {
                        /* ⚠ TICKING NONE IS A LEGITIMATE ANSWER -- they looked and
                         * liked none of them -- but it deletes everything, so it
                         * asks first. ⛔ And "start over" is literal: the recipe
                         * lives in the batch file, so keeping one route keeps the
                         * parameters and keeping none loses them. */
                        if (batchSave.isEmpty()) batchDeleteConfirm = true
                        else { batchAreaName = ""; batchAreaPrompt = true }
                    },
                    onExit = {
                        /* ⭐ EXIT LEAVES EVERYTHING — the batch stays open and
                         * Route+ reopens exactly this. */
                        android.util.Log.i("PanelTrace", "BATCH <- false"); batchGridOpen = false
                        webViewRef?.evaluateJavascript("clearBatchRoutes()", null)
                        // ⛔ put them back exactly as they were
                        batchPrevLayers?.let { prev ->
                            listOf("Trails", "Tracks", "Waypoints", "Routes")
                                .forEachIndexed { i, ly ->
                                    if (prev[i] != DS_OFF) {
                                        webViewRef?.evaluateJavascript("show" + ly + "()", null)
                                    }
                                }
                            webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                        }
                        batchPrevLayers = null
                    },
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(8.dp).fillMaxWidth(0.75f)
                )
            }
            // -- WORK WITH ARTIFACTS (V2.5 scaffold) -- FAB-gated, opens expanded --
            if (showArtifactsPanel) {
            ConvoyArtifactsPanel(
                startExpanded = true,
                onDismiss = { showArtifactsPanel = false },
                isConvoyMap = false,
                onCreateRoute = {
                    /* BATCHFORK-2026-08-27: AN OPEN BATCH TAKES PRECEDENCE.
                     *
                     * ⛔ Nothing below runs while a batch is unresolved -- not
                     * the UNNAMED resolver, not addPointMode, not routeMode,
                     * not the picker. The rider is taken to the six routes
                     * already waiting for a decision.
                     *
                     * ⭐ ONLY BATCHES ARE FORCED. A batch is six routes the app
                     * made unasked, so it is the app's mess to clear, and a
                     * second batch on top would be twelve. A hand-drawn draft
                     * is one route the rider chose to start and can leave as
                     * long as they like.
                     *
                     * ⭐ IT REDIRECTS RATHER THAN REFUSING -- the rider is put
                     * in front of the thing in the way, with the tools to clear
                     * it.
                     */
                    if (RouteDraftStore.hasOpenBatch()) {
                        /* FORKGUARD-2026-08-27: THE LOCK HOLDS EVEN IF THE DRAW
                         * FAILS.
                         *
                         * ⛔ On 08-27 drawBatch threw -- a WebView call off the
                         * main thread -- the throw was swallowed upstream, and
                         * Route+ fell through into the old In-Progress picker.
                         * The batch and all five drafts were on disk and
                         * correct; the guard simply stopped guarding.
                         *
                         * ⭐ hasOpenBatch() decides. Nothing below runs whatever
                         * the drawing does, so a failure costs a picture rather
                         * than the lock.
                         */
                        runCatching {
                            batchRows = RouteDraftStore.drawBatch(webViewRef)
                            batchName = RouteDraftStore.readBatch()
                                ?.optString("batchName") ?: ""
                        }.onFailure {
                            // ⚠ VISIBLE. An empty grid with no explanation is
                            // worse than the fall-through was -- at least the
                            // picker was a screen the rider recognised.
                            android.util.Log.e("BatchGrid",
                                "batch draw failed: " + it.message, it)
                        }
                        batchHidden = emptySet()
                        batchSave = emptySet()
                        android.util.Log.i("PanelTrace", "BATCH <- true"); batchGridOpen = true
                        /* ⚠ ROUTE MODE STAYS OFF. With it live, every tap on a
                         * drawn route -- which is how the rider inspects them --
                         * becomes a vertex on a route they never meant to edit.
                         */
                        webViewRef?.evaluateJavascript("setRouteMode(false)", null)
                        /* TABLEPOLISH-2026-08-27: LAYERS OFF, AND REMEMBERED.
                         *
                         * Five coloured routes over a full trail layer is
                         * unreadable.
                         *
                         * ⛔ RESTORED TO WHAT THEY WERE, not to all-on. A rider
                         * who had trails off must not come back to them on, and
                         * one who had them on must not lose them. The table is a
                         * DETOUR — anything changed to show it gets undone.
                         *
                         * ⚠ Skipping this is the failure that gets reported as
                         * "the app lost my trails".
                         */
                        batchPrevLayers = listOf(trailState, trackState,
                            waypointState, routeState)
                        listOf("Trails", "Tracks", "Waypoints", "Routes").forEach { ly ->
                            webViewRef?.evaluateJavascript("hide" + ly + "()", null)
                        }
                        // ⚠ Route+ lives inside this panel; leaving it open
                        // would put the grid behind it.
                        showArtifactsPanel = false
                        return@ConvoyArtifactsPanel
                    }
                    // +ROUTE -> choose New vs In-Progress BEFORE the toolbar opens.
                    // Recovery test: if the SAVED state still had a route open, a prior
                    // session left it open (crash/kill never closed cleanly) = recovery.
                    // Test BEFORE setting routeMode on this session.
                    recoveryDetected = (pmSeed.routeState?.open == true)
                    // [routeplus-resolver 2026-08-01] ROUTE+ caller. Resolve any unnamed
                    // draft BEFORE the New/In-Progress choice -- New Route reuses the
                    // UNNAMED filename, so an unresolved remnant would be overwritten.
                    run {
                        val u = RouteDraftStore.listDrafts().firstOrNull { it.name == RouteDraftStore.UNNAMED }
                        if (u == null) {
                            // nothing to resolve
                        }
                    }
                    // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                    addPointMode = true
                    routeMode = true   // route-add selected: panel has no cancel, both picks build a route
                    android.util.Log.i("PanelTrace", "PICKER <- true"); showInProgressPicker = true
                },
                onSearch = { type, term ->
                    coroutineScope.launch {
                        val raw = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            SpatialDbManager.init(context)
                            SpatialDbManager.searchByName(type, term)
                        }
                        searchResults = assignNameSequence(raw)
                    }
                },
                onResultClick = { type, id, geomHash, name ->
                    val cap = type.replaceFirstChar { it.uppercase() }
                    pendingDetailType = cap
                    pendingDetailId = id
                },
                searchResults = searchResults,
                displayStates = mapOf("Trails" to trailState, "Tracks" to trackState, "Waypoints" to waypointState, "Routes" to routeState),
                onSetState = { typeName, newState ->
                    // Write to ConvoyConfig (synchronous, visible to Thread) AND compose state (UI)
                    when(typeName) {
                        "Trails" -> { trailState = newState; if (newState != DS_SELECTED) { trailCheckedIds = null } }
                        "Tracks" -> { trackState = newState; if (newState != DS_SELECTED) { trackCheckedIds = null } }
                        "Waypoints" -> { waypointState = newState; if (newState != DS_SELECTED) { waypointCheckedIds = null } }
                        "Routes" -> { routeState = newState; if (newState != DS_SELECTED) { routeCheckedIds = null } }
                    }
                    if (newState == DS_OFF) {
                        webViewRef?.evaluateJavascript("hide" + typeName + "()", null)
                    } else {
                        webViewRef?.evaluateJavascript("show" + typeName + "()", null)
                        webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                    }
                    savePlanningState()
                },
                onEditDisplay = { typeName ->
                    // TRAILSELECT-2026-09-02: ⭐ SELECT on Trails opens the
                    // CATEGORY filter, not a per-trail list. Fred, 09-02: "now
                    // all the selects for all features are in one place, not
                    // sometimes here and sometimes there."
                    // ⛔ The per-trail list was a trap -- SpatialDisplayManager
                    // filters to checkedIds in the SELECTED state, so a trail
                    // imported after that list was saved silently never drew.
                    // And nobody could build such a list from ~146,000 mostly
                    // unnamed trails anyway.
                    if (typeName == "Trails") {
                        showTrailFilter = true
                        return@ConvoyArtifactsPanel
                    }
                    val table = when (typeName) {
                        "Tracks" -> "tracks"
                        "Trails" -> "trails"
                        "Waypoints" -> "waypoints"
                        "Routes" -> "routes"
                        else -> return@ConvoyArtifactsPanel
                    }
                    scope.launch {
                        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            SpatialDbManager.init(context)
                            SpatialDbManager.queryArtifactList(table, lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                        }
                        // LIFECYCLE-2026-09-01 stage 3: what came back to the
                        // caller, and the viewport it asked for.
                        // ⚠ lastViewport* are only written by onViewportChanged.
                        // If the list is opened before one fires, or after a
                        // screen change, these are STALE and the box is not what
                        // is on screen.
                        android.util.Log.i("LIFECYCLE",
                            "3 caller got ${list.size} for $typeName; box S=" +
                                "$lastViewportSouth W=$lastViewportWest N=" +
                                "$lastViewportNorth E=$lastViewportEast; '" +
                                SpatialDbManager.LIFECYCLE_NAME + "' = " +
                                list.count { (it["name"] ?: "")
                                    .contains(SpatialDbManager.LIFECYCLE_NAME, true) })
                        if (list.isNotEmpty()) {
                            artifactList = list
                            val curState = when(typeName) { "Trails"->trailState; "Tracks"->trackState; "Waypoints"->waypointState; "Routes"->routeState; else->DS_OFF }
                            val curChecked = when(typeName) { "Trails"->trailCheckedIds; "Tracks"->trackCheckedIds; "Waypoints"->waypointCheckedIds; "Routes"->routeCheckedIds; else->null }
                            selectedArtifactIds = when {
                                curState == DS_SELECTED && curChecked != null -> curChecked
                                curState == DS_ON -> list.mapNotNull { it["id"] }.toSet()
                                else -> emptySet()
                            }
                            activeListType = typeName
                            // LIFECYCLE-2026-09-01 stage 4: after assignment and
                            // auto-selection. A row present but UNSELECTED will
                            // not draw, so both numbers matter.
                            android.util.Log.i("LIFECYCLE",
                                "4 artifactList=${artifactList.size} " +
                                    "selected=${selectedArtifactIds.size} " +
                                    "state=$curState checked=${curChecked?.size ?: -1}")
                        } else {
                            android.widget.Toast.makeText(context, "No " + typeName + " in current view", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onImport = { typeName ->
                    when (typeName) {
                        // TRAILSELECT-2026-08-21D: was onNavigateToTrailSources() -- the old
                        // source-SELECTION screen. Now the BY STATE / BY AREA selector.
                        "Trails" -> showTrailImportSelector = true
                        "Artifacts" -> onNavigateToTrackImport()
                        // OSM-IMPORT-2026-07-28
                        // TRAILSELECT-2026-08-21D: "Import OSM Data" REMOVED (design §2). OSM is
                        // no longer a separate concept -- it is one source inside
                        // Import Trails. Screenshots captured 08-21 before removal.
                        "OSM" -> { /* removed - see Import Trails */ }
                        else -> onNavigateToTrackImport()
                    }
                }
            )
            }

            // OSM-IMPORT-2026-07-28: OSM import overlay (planner only).
            // Full-screen so the four-stage panel owns the surface while open.
            // BackHandler closes it -- there is no back-stack entry to pop.
            // HOME-STATE-PICKER-2026-08-20: test harness, wired to IMPORT OSM DATA
            // ROUTEAI-2026-08-23P: AI Design overlay. Opens on the method change,
            // closes on confirm so it does not hog the map while explaining itself.
            // WIPNOTES-2026-08-23S: DETAILS. Circular like the MAPS FAB so it costs
            // almost no room. TopEnd under Help (Search 12, ? 64, Help 116).
            // ⚠ Shown ONLY when the open draft HAS notes -- its presence is what
            // tells the rider this route came with a description.
            if (routeMode && !showAiDesign && !showWipNotes &&
                routeName.isNotBlank() && RouteDraftStore.hasNotes(routeName)) {
                // NARRBTN-2026-08-23Y: "ROUTE DETAILS", not "DETAIL". The short
                // label was an abbreviation to fit a 44dp circle; the real one
                // does not, so the shape follows the text. Same position.
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    color = Color(0xEE131820),
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(top = 168.dp, end = 12.dp)
                        .clickable { showWipNotes = true }
                ) {
                    androidx.compose.material3.Text(
                        "ROUTE DETAILS",
                        color = Color(0xFFBC8CFF),
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)
                    )
                }
            }

            // WIPNOTES-2026-08-23S: the narrative itself. A scrollable window with a
            // scrollbar -- NOT auto-scrolling. The entries are mile-prefixed lines
            // and they read badly if they wrap mid-entry, so the body is sized to
            // fit one.
            // ROUTETAP-2026-08-23Z: the saved-route narrative. Same panel as the WIP
            // one; only the builder differs.
            savedNotesRouteId?.let { rid ->
                androidx.activity.compose.BackHandler(enabled = true) { savedNotesRouteId = null }
                ConvoyNotesPanel(
                    title = "Route details",
                    sections = notesFromRouteId(rid),
                    onClose = { savedNotesRouteId = null }
                )
            }

            if (showWipNotes) {
                androidx.activity.compose.BackHandler(enabled = true) { showWipNotes = false }
                ConvoyNotesPanel(
                    title = routeName,
                    subtitle = "Work in progress — not saved yet",
                    sections = notesFromDraft(RouteDraftStore.readNotes(routeName)),
                    onClose = { showWipNotes = false }
                )
            }

            if (showAiDesign) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showAiDesign = false
                    routeMethod = ROUTE_METHOD_P2P
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0F1419))
                ) {
                    ConvoyAiDesignPanel(
                        anchorName = "",
                        results = if (aiBusy || aiResults.isEmpty()) null else aiResults,
                        // ROUTEEXPLORE-2026-08-23T: the search stub is now wired.
                        // ⚠ OFF THE MAIN THREAD. Building the graph is the heavy
                        // step -- ~27,000 edges for a corridor this size. It is
                        // cached per corridor afterwards, but the FIRST run in an
                        // area takes real time and would freeze the map.
                        // GUIDEDPIN-2026-08-24C: PROCEED TO MAP no longer searches.
                        // It stashes what the rider typed, closes this panel, and
                        // hands over to pin collection. The search runs from the
                        // checklist's FIND MY RIDES, with the trailhead they drop.
                        //
                        // ⚠ The panel's own parameter is still called onFindRides
                        // and its `mode` argument is ignored -- mode is DERIVED from
                        // the pins now. Renaming the parameter would mean editing a
                        // file Patch B rewrote this morning, for no behaviour.
                        onFindRides = { _, rname, miLow, miHigh, mphLow, mphHigh ->
                            pinRideName = rname
                            pinMiLow = miLow; pinMiHigh = miHigh
                            pinMphLow = mphLow; pinMphHigh = mphHigh
                            showAiDesign = false
                            aiResults = emptyList()
                            aiProgress = ""
                            pinReset()
                        },
                        onContinue = {
                            // CLEANUP-2026-08-24H: to the WIP list, not the map.
                            //
                            // The results panel explains that the routes are Work
                            // in Progress; the WIP list is where they are kept or
                            // discarded. Dropping to the map left the rider to go
                            // and find them.
                            //
                            // ⚠ draftListTick++ IS LOAD-BEARING. The explorer
                            // writes drafts through writeRawDraft, which is not one
                            // of the ten sites that bump this -- so the list is
                            // built from a stale remember() and shows nothing.
                            // Fred: "WIP in process did not show. I had to exit and
                            // return from planning map." Same cause as
                            // SAVEWIP-LISTTICK-2026-08-17.
                            showAiDesign = false
                            draftListTick++
                            /* KILLWIPPICKER-2026-08-27: THE TABLE IS THE RESULTS NOW.
                             *
                             * ⛔ This line put the old WIP picker on top of the
                             * compare table. It is how the rider reached the
                             * results BEFORE batches existed, and it was still
                             * firing after the seam had drawn the batch and
                             * opened the grid.
                             *
                             * ⚠ Gating the picker's render would have hidden the
                             * symptom and left this path running. Fred: "gating
                             * the screen is just deferring the problem."
                             *
                             * ⚠ KEPT FOR THE NO-BATCH CASE. If the search
                             * produced nothing, or writeBatch failed, the picker
                             * is the rider's only way to whatever WAS produced.
                             * Removing it outright would strand them.
                             */
                            if (!RouteDraftStore.hasOpenBatch()) {
                                android.util.Log.i("PanelTrace", "PICKER <- true"); showInProgressPicker = true
                            }
                        },
                        onClose = {
                            showAiDesign = false
                            // Nothing was built, so do not leave the rider in a mode
                            // that does nothing.
                            routeMethod = ROUTE_METHOD_P2P
                        }
                    )
                }
            }

            // GUIDEDPIN-2026-08-24C ── the checklist ─────────────────────────
            //
            // THE CHECKLIST IS THE CONTROLLING FEATURE. Fred, 08-24: "it explains
            // where you are in the process and what is left." Completed steps
            // carry their ANSWER, which is the verification -- a long press that
            // did not register is otherwise invisible and the rider presses again
            // and gets two waypoints.
            /* ⚠ "> NONE", not "!= NONE". The two new states are NEGATIVE, so
             * the old test was true for them and the checklist would have
             * rendered underneath the overview and the parameter panel. */
            if (pinStep > PIN_STEP_NONE) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> NONE"); pinStep = PIN_STEP_NONE
                    showAiDesign = true
                }

                // GUIDEDPIN-2026-08-24D: the search finished while the checklist
                // was streaming its progress. Hand back to the AI panel -- results
                // are populated NOW, so it opens on PHASE_RESULTS rather than
                // asking the rider everything a second time.
                //
                // ⚠ MOVED HERE FROM THE DIALOG BLOCK, where C put it and where it
                // could not run. This block composes whenever the checklist is up,
                // which is exactly when this needs to be watching.
                androidx.compose.runtime.LaunchedEffect(aiBusy, pinStep) {
                    if (pinStep == PIN_STEP_SEARCH && !aiBusy) {
                        android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> NONE"); pinStep = PIN_STEP_NONE
                        showAiDesign = true
                    }
                }

                val hrLo = pinMiLow.toDouble() / pinMphHigh.toDouble()
                val hrHi = pinMiHigh.toDouble() / pinMphLow.toDouble()

                // ⚠ ROUNDED TO WHOLE HOURS, deliberately. This envelope compounds
                // TWO ranges; a decimal would imply a precision that does not
                // exist. The per-route card can be tighter because it has one real
                // mileage.
                val summary =
                    "I am about to design a route beginning at " +
                        pinTrailName.ifBlank { "your trailhead" } +
                        ", extending for between " + pinMiLow + " and " + pinMiHigh +
                        (if (pinIsLoop == false) " miles and finishing where you " +
                            "marked the end. The ride duration " else
                            " miles and returning to where it started. The ride duration ") +
                        "is estimated at " + Math.round(hrLo) + " to " + Math.round(hrHi) +
                        " hours at " + pinMphLow + " to " + pinMphHigh + " mph, which " +
                        "already includes stops and break pauses.\n\n" +
                        // ⭐ CLOSEPLANNER-2026-08-29: said once, and honest.
                        // What comes back depends on the trails this device
                        // happens to hold.
                        "Results depend on the trails available on this device.\n\n" +
                        "I will favour rides that pass named features and that avoid " +
                        "riding the same trail twice, and will bring back up to four " +
                        "alternatives as Work in Progress for you to review."

                val steps = listOf(
                    GuidedStep(
                        // (13) both actions in the title. The banner shows
                        // ONE row, and the "add one if none exists" row that
                        // follows never gets its turn — so the long press has
                        // to be named here or it is invisible.
                        title = "Select your trailhead / long press to add the trailhead",
                        /* RINGTEXT-2026-08-29: the circle needs saying.
                         * ⚠ Unexplained it is decoration — the rider has to
                         * infer that it bounds anything, then infer that panning
                         * changes what is in it. ⭐ Said, it is an instruction. */
                        // STATEHINT-2026-08-29: a wrong-state match is not an
                        // obvious failure — the map moves, trails draw, the circle
                        // finds starts. The rider is simply somewhere else.
                        instruction = "Use search to navigate to your area \u2014 " +
                            "follow the area name with the two-character state, " +
                            "since names repeat across states.\n\n" +
                            "The blue circle shows the trails that can " +
                            "start your route. Move the map if the trail you want " +
                            "to launch from is not inside it.\n\n" +
                            "Tap the trailhead you are starting from, or press and " +
                            "hold for a second to add one.",
                        answer = pinTrailName,
                        state = if (pinStep == PIN_STEP_TRAILHEAD) GP_STATE_CURRENT
                                else GP_STATE_DONE
                    ),
                    // ⭐ TWO STEPS, NOT ONE. Creating a trailhead and choosing one
                    // are different acts, and most riders only do the second --
                    // they are starting from ground they have ridden before. This
                    // step stays visible so the rider on NEW ground knows what to
                    // do, and is simply skipped by everyone else.
                    GuidedStep(
                        title = "Add one if none exists",
                        instruction = "Long press at the start of the trail, choose " +
                            "Trailhead, and give it a name. Then tap it to select it.",
                        answer = if (pinTrailName.isBlank()) "" else "Not needed",
                        state = if (pinStep == PIN_STEP_TRAILHEAD) GP_STATE_CURRENT
                                else GP_STATE_DONE
                    ),
                    GuidedStep(
                        title = "Does the ride return there?",
                        instruction = "Answer yes for a loop back to your trailhead.",
                        answer = if (pinTrailName.isBlank()) ""
                                 else "Returns to " + pinTrailName,
                        state = when {
                            pinStep < PIN_STEP_RETURN  -> GP_STATE_TODO
                            pinStep == PIN_STEP_RETURN -> GP_STATE_CURRENT
                            else                       -> GP_STATE_DONE
                        }
                    ),
                    GuidedStep(
                        title = "Where does the ride finish?",
                        instruction = "Tap the place you want the ride to end.",
                        answer = if (pinIsLoop == false && pinEndLat != 0.0)
                                     "Finish point set" else "",
                        state = when {
                            pinIsLoop != false          -> GP_STATE_TODO
                            pinStep == PIN_STEP_ENDPOINT -> GP_STATE_CURRENT
                            pinStep > PIN_STEP_ENDPOINT  -> GP_STATE_DONE
                            else                         -> GP_STATE_TODO
                        }
                    ),
                    // ROUTEASSIST-2026-08-25S1: the bypass, as a question.
                    GuidedStep(
                        title = "Add your own places?",
                        instruction = "Answer yes to drop pins on places the ride " +
                            "should reach. Answer no and the ride is designed for you.",
                        answer = when {
                            pinStep <= PIN_STEP_ASK -> ""
                            pinPoints.isEmpty()     -> "Designed for you"
                            else                    -> "Adding your own places"
                        },
                        state = when {
                            pinStep < PIN_STEP_ASK  -> GP_STATE_TODO
                            pinStep == PIN_STEP_ASK -> GP_STATE_CURRENT
                            else                    -> GP_STATE_DONE
                        }
                    ),
                    GuidedStep(
                        title = "Places to pass through",
                        instruction = "Tap the closest trail to each place you want " +
                            "the ride to reach \u2014 up to " + PIN_MAX + ". Tap a pin " +
                            "again to remove it. I will favour rides that pass named " +
                            "points of interest.",
                        answer = if (pinPoints.isEmpty()) ""
                                 else pinPoints.size.toString() + " place" +
                                     (if (pinPoints.size == 1) "" else "s") + " included",
                        state = when {
                            pinStep < PIN_STEP_INCLUDE  -> GP_STATE_TODO
                            pinStep == PIN_STEP_INCLUDE -> GP_STATE_CURRENT
                            else                        -> GP_STATE_DONE
                        }
                    ),
                    GuidedStep(
                        title = "Review and search",
                        instruction = if (pinStep == PIN_STEP_SEARCH)
                            (aiProgress.ifBlank { "Working\u2026" }) else summary,
                        answer = "",
                        state = when {
                            pinStep < PIN_STEP_SUMMARY -> GP_STATE_TODO
                            else                      -> GP_STATE_CURRENT
                        }
                    )
                )

                val actions: List<Pair<String, () -> Unit>> = when (pinStep) {
                    PIN_STEP_RETURN -> listOf(
                        "YES \u2014 LOOP" to {
                            pinNotice = ""
                            pinExpanded = true
                            // ROUTEASSIST-2026-08-25B1: a loop needs no finish
                            // point -- the trailhead is both ends.
                            pinIsLoop = true
                            // S1: ask before assuming they want to drop pins.
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> ASK"); pinStep = PIN_STEP_ASK
                        },
                        "DROP ENDPOINT PIN NOW" to {
                            // STUB:ENDPOINT -- point-to-point rides. The step is asked
                            // in its FINAL POSITION so Phase 2 fills this in rather
                            // than inserting a step and moving everything below it.
                            //
                            // ⚠ IT MUST SAY SOMETHING. An unresponsive button reads as
                            // a bug, and the rider answered honestly -- they are owed
                            // an honest answer back.
                            // ROUTEASSIST-2026-08-25B1: STUB:ENDPOINT is closed.
                            // A point-to-point ride is a different SHAPE, not a
                            // loop with a caveat -- RouteExplorer.RouteShape and
                            // its own corridor box handle it.
                            pinNotice = ""
                            pinExpanded = true
                            pinIsLoop = false
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> ENDPOINT"); pinStep = PIN_STEP_ENDPOINT
                        }
                    )
                    PIN_STEP_ENDPOINT -> listOf(
                        "CONTINUE" to {
                            pinNotice = ""
                            pinExpanded = true
                            // S1: ask before assuming they want to drop pins.
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> ASK"); pinStep = PIN_STEP_ASK
                        }
                    )
                    /* ROUTEASSIST-2026-08-25S1: THE BYPASS.
                     *
                     * NO goes straight to the summary with no points, which is
                     * explore mode -- the four-route behaviour the checklist had
                     * accidentally made unreachable.
                     */
                    PIN_STEP_ASK -> listOf(
                        "YES \u2014 I'LL DROP PINS" to {
                            pinNotice = ""
                            pinExpanded = true
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> INCLUDE"); pinStep = PIN_STEP_INCLUDE
                        },
                        "NO \u2014 DESIGN IT FOR ME" to {
                            pinNotice = ""
                            pinExpanded = true
                            pinPoints = emptyList()
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> SUMMARY"); pinStep = PIN_STEP_SUMMARY
                        }
                    )
                    // ROUTEASSIST-2026-08-25C: DONE EXISTS ONLY WHEN THE PIN
                    // SET FITS. Fred, 08-25: only a feasible pin set proceeds.
                    // Absent beats present-and-scolding -- and while assess()
                    // is still running there is no answer yet, so no button.
                    // Zero include points is feasible: that is explore mode.
                    /* DONEALWAYS-2026-08-26: DONE IS ALWAYS THERE.
                     *
                     * It was hidden when pinFeas was null or the pins busted
                     * the ceiling. That ignored what the button IS -- the only
                     * way out of the step. Fred, 08-26: "if i drop 4 pins i
                     * don't want to drop six more to exit."
                     *
                     * pinFeas == null could mean assess() has not answered
                     * yet, or its result was discarded as stale, or something
                     * reset it on the next drop -- and the rider sees the same
                     * nothing for all three.
                     *
                     * Over the ceiling belongs in the notice beside the
                     * button, not in its absence. And PROCEED is not blocked:
                     * the engine already says "there are N features here but
                     * none fit that distance", which tells the rider what
                     * happened.
                     */
                    PIN_STEP_INCLUDE -> listOf(
                        "DONE" to {
                            pinNotice = ""
                            pinExpanded = true
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> SUMMARY"); pinStep = PIN_STEP_SUMMARY
                        }
                    )
                    // ⚠ THE SEARCH LAUNCH BELONGS TO SUMMARY, not to
                    // INCLUDE. ConvoyGuidedSummaryPanel's onProceed calls
                    // actions.firstOrNull() -- SUMMARY-2026-08-24I put the
                    // launch in exactly one place on purpose, and moving it
                    // to a checklist button would make PROCEED a no-op.
                    PIN_STEP_SUMMARY -> listOf(
                        "FIND MY RIDES" to {
                            pinNotice = ""
                            pinExpanded = true
                            /* ⭐ (6) THE MAP IS HANDED OVER CLEAN. The compare
                             * table draws six coloured routes; trails, tracks and
                             * waypoints under them make it unreadable, and Map
                             * Features belongs to the rider's own arrangement,
                             * not to the table.
                             *
                             * ⚠ The table captures the layer states on entry and
                             * restores them on exit, so this runs BEFORE it opens
                             * — otherwise it would capture "off" and give the
                             * rider back nothing.
                             */
                            /* SATFIXES-2026-08-29 (4): the STATE and the JS
                             * move together. The hide() calls ran and the states
                             * still said DS_ON, so anything re-reading them put
                             * the layers straight back. */
                            trailState = DS_OFF
                            trackState = DS_OFF
                            waypointState = DS_OFF
                            routeState = DS_OFF
                            listOf("Trails", "Tracks", "Waypoints", "Routes")
                                .forEach { ly ->
                                    webViewRef?.evaluateJavascript(
                                        "hide" + ly + "()", null)
                                }
                            showArtifactsPanel = false
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> SEARCH"); pinStep = PIN_STEP_SEARCH
                            aiBusy = true
                            aiResults = emptyList()
                            aiProgress = "Starting"
                            val aLat = pinTrailLat
                            val aLon = pinTrailLon
                            val rn = pinRideName
                            val mLo = pinMiLow; val mHi = pinMiHigh
                            val sLo = pinMphLow; val sHi = pinMphHigh
                            Thread {
                                try {
                                    val db = SpatialDbManager.getSpatialDb()
                                    if (db == null) {
                                        aiProgress = "No trail data on this device yet"
                                        aiBusy = false
                                        return@Thread
                                    }
                                    val out = RouteExplorer.explore(
                                        db,
                                        RouteExplorer.Request(
                                            anchorLat = aLat, anchorLon = aLon,
                                            name = rn,
                                            milesLow = mLo.toDouble(),
                                            milesHigh = mHi.toDouble(),
                                            mphLow = sLo.toDouble(),
                                            mphHigh = sHi.toDouble(),
                                            // USERPOI-2026-08-25W1: THE PINS REACH THE SEARCH.
                                            //
                                            // Without this the Request took the default
                                            // emptyList(), so patch E ran against no points
                                            // and the routes had no reason to go near the
                                            // rider's places. assess() gets its points
                                            // through a separate argument, which is why the
                                            // mileage readout worked all along and hid this.
                                            includePoints = pinPoints
                                        )
                                    ) { p ->
                                        aiProgress = p.step +
                                            (if (p.detail.isNotBlank()) " \u2014 " + p.detail else "")
                                    }
                                    aiResults = out.map {
                                        AiRouteResult(it.name, it.miles, it.hoursLow,
                                            it.hoursHigh, it.featureCount, it.featureMix)
                                    }
                                    /* BATCHJSON-2026-08-27: THE SEAM.
                                     *
                                     * ⚠ The WIP creation above is untouched. This
                                     * records WHICH drafts this search produced and
                                     * the parameters that built them, then returns.
                                     *
                                     * ⭐ Route+ will fork on this file (item 3), and
                                     * COMPARE (item 2) opens it. Writing it here
                                     * means compare can be built and tested against
                                     * a hand-written file with no engine run.
                                     */
                                    RouteDraftStore.writeBatch(
                                        batchName = rn,
                                        draftNames = out.map { it.draftName },
                                        miles = out.map { it.miles },
                                        hoursLow = out.map { it.hoursLow },
                                        hoursHigh = out.map { it.hoursHigh },
                                        anchorLat = aLat, anchorLon = aLon,
                                        // ⚠ Nothing stores the tapped waypoint's NAME
                                        // today. Capturing it belongs with item 3; the
                                        // rebuild uses the coordinates regardless.
                                        anchorName = null,
                                        milesLow = mLo.toDouble(),
                                        milesHigh = mHi.toDouble(),
                                        mphLow = sLo.toDouble(),
                                        mphHigh = sHi.toDouble(),
                                        pins = pinPoints,
                                        // ⭐ null IS a real answer -- "come back to where
                                        // I parked". Storing (0.0, 0.0) instead would aim
                                        // a rebuild at the Gulf of Guinea.
                                        finish = if (pinIsLoop == false)
                                            pinEndLat to pinEndLon else null,
                                    )
                                    /* BATCHGRID-2026-08-27: DRAW THE BATCH.
                                     *
                                     * ⛔ openDraft, NOT loadIntoRouteManager.
                                     * The store's own comment: openDraft "does
                                     * NOT mutate RouteManager".
                                     * loadIntoRouteManager is the RESUME path --
                                     * using it here would leave RouteManager
                                     * holding the last of six routes the rider
                                     * was only looking at.
                                     */
                                    batchRows = RouteDraftStore.drawBatch(webViewRef)
                                    batchName = rn
                                    batchGridOpen = batchRows.isNotEmpty()
                                } catch (e: Exception) {
                                    android.util.Log.e("RouteExplorer", "explore failed", e)
                                    aiProgress = "Could not build rides: " +
                                        (e.message ?: "error")
                                } finally {
                                    aiBusy = false
                                }
                            }.start()
                        }
                    )
                    else -> emptyList()
                }

                // SUMMARY-2026-08-24I: the PANEL swaps, the BLOCK does not.
                //
                // ⚠ Gating the whole block on pinStep would kill the search-done
                // LaunchedEffect above -- and that is exactly how Patch C's
                // trailhead capture came to never fire. Only the render changes.
                // ROUTEASSIST-2026-08-25C: the readout lives on the MAP,
                // not in the panel. The rider is looking at the ground
                // deciding where to tap next, and that is where the number
                // has to be. Only while pins are actually being placed.
                if (pinStep == PIN_STEP_ENDPOINT || pinStep == PIN_STEP_INCLUDE) {
                    val f = pinFeas
                    val problem = when {
                        f == null -> ""
                        f.onFragment.any { it in 1..pinPoints.size } ->
                            "One place sits on a trail that does not join " +
                            "the rest of the network."
                        f.unreachable.any { it in 1..pinPoints.size } ->
                            "One place is too far from any trail to reach."
                        else -> ""
                    }
                    ConvoyPinMileageHud(
                        floorMiles = f?.totalMiles ?: 0.0,
                        overMiles = f?.overMiles ?: 0.0,
                        underMiles = f?.underMiles ?: 0.0,
                        floorBand = pinMiLow,
                        ceiling = pinMiHigh,
                        problem = problem,
                        busy = f == null,
                        onCeilingChange = { d ->
                            // The ceiling never drops under the floor.
                            pinMiHigh = (pinMiHigh + d).coerceIn(pinMiLow + 5, 200)
                            android.util.Log.i("PanelTrace",
                                "ASSESS " + pinPoints.size + " pin(s), ceiling " +
                                pinMiHigh + " mi")
                            gpRunAssess()
                        },
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
                if (pinStep < PIN_STEP_SUMMARY) {
                    /* AISTEPSCREEN-2026-08-28: one screen per step.
                     *
                     * Same inputs, same call site — the steps, the actions, the
                     * notice, START OVER. Only what is DRAWN changes: the
                     * current step full screen with its own buttons, instead of
                     * seven rows in a half-width panel under a header.
                     *
                     * expanded/onToggle are gone: there is nothing to collapse
                     * on a screen that opens and closes around the map gesture.
                     */
                    ConvoyAiStepScreen(
                        steps = steps,
                        onStartOver = { pinReset() },
                        actions = actions,
                        notice = pinNotice,
                        /* ⚠ The half-width, hard-left rule was for a panel
                         * that sat PERMANENTLY over a live map, where the space
                         * alongside was the only place a pin landing could be
                         * reported. This screen opens and closes around the
                         * gesture, so it has the whole screen and the notice has
                         * room. */
                        // ⚠ (1) BOTTOM. At the top it covered the search
                        // button, which is the one control that step needs.
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                } else {
                    ConvoyGuidedSummaryPanel(
                        title = pinRideName,
                        body = summary,
                        working = pinStep == PIN_STEP_SEARCH,
                        progress = aiProgress,
                        // ⚠ ONE DEFINITION. This invokes the same FIND MY RIDES
                        // lambda the checklist's action list already holds, rather
                        // than a second copy of the search launch. Two copies of
                        // that Thread block is the duplicate this project has a
                        // standing rule against.
                        onProceed = { actions.firstOrNull()?.second?.invoke() },
                        // Fred, 08-24: START OVER returns to the CHECKLIST, not to
                        // the setup panel. A rider changing their mind here is
                        // changing the trailhead or the loop answer, not the name
                        // or the mileage.
                        onStartOver = { pinReset() }
                    )
                }
            }

            if (showHomeStatePicker) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showHomeStatePicker = false
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216))
                ) {
                    HomeStatePickerScreen(
                        onNavigateBack = { showHomeStatePicker = false }
                    )
                }
            }

            // AREAWIRE-2026-08-21C: AREA import overlay. Same screen as the state
            // picker -- it already owns the running/done phases, the step
            // indicators, the do-not-close banner and the completion recap.
            // Building a second progress UI would be two implementations of one
            // thing, which is the rule this release is meant to enforce.
            // TRAILSELECT-2026-08-21D: IMPORT TRAILS -- the selector. Selection UI only;
            // it decides which entry point runs and does no work itself.
            if (showTrailImportSelector) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showTrailImportSelector = false
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216)),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Import Trails",
                            color = Color(0xFF7BB661),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                        Text(
                            "Load trails, scenic points and places from every\n" +
                            "available source for a whole state or a drawn area.",
                            color = Color(0xFF8899AA),
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(28.dp))

                        // BY STATE -> any-state list. No GPS, no pre-selection.
                        androidx.compose.material3.Button(
                            onClick = {
                                showTrailImportSelector = false
                                showAnyStatePicker = true
                            },
                            modifier = Modifier.width(260.dp)
                        ) {
                            Text("BY STATE", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text(
                            "A whole state - a trip to Colorado",
                            color = Color(0xFF667788), fontSize = 11.sp
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))

                        // BY AREA -> download panel, row pre-checked, rider draws.
                        androidx.compose.material3.Button(
                            onClick = {
                                showTrailImportSelector = false
                                panelTrailsChecked = true
                                showDownloadPanel = true
                            },
                            modifier = Modifier.width(260.dp)
                        ) {
                            Text("BY AREA", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text(
                            "Draw a box - may cross state lines",
                            color = Color(0xFF667788), fontSize = 11.sp
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))

                        androidx.compose.material3.TextButton(
                            onClick = { showTrailImportSelector = false }
                        ) {
                            Text("Cancel", color = Color(0xFF8899AA), fontSize = 13.sp)
                        }
                    }
                }
            }

            // TRAILSELECT-2026-08-21D: ANY-STATE picker. Same screen and same import
            // process as Home State; it simply enters at the list.
            if (showAnyStatePicker) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showAnyStatePicker = false
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216))
                ) {
                    HomeStatePickerScreen(
                        onNavigateBack = { showAnyStatePicker = false },
                        anyState = true
                    )
                }
            }

            val areaBb = areaImportBbox
            if (areaBb != null) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    areaImportBbox = null
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216))
                ) {
                    HomeStatePickerScreen(
                        onNavigateBack = { areaImportBbox = null },
                        areaBbox = areaBb
                    )
                }
            }
            if (showOsmPanel) {
                androidx.activity.compose.BackHandler(enabled = true) {
                    showOsmPanel = false
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1216))
                ) {
                    OsmImportPanel(
                        onNavigateBack = { showOsmPanel = false }
                    )
                }
            }
            // OSM-C3B-AREA-2026-07-29: closing the OSM panel is the handoff.
            //
            // R1 -- nothing is passed. The panel closed; we read DISK to find
            // out whether it closed because row 3 chose SELECTED AREA. A flag
            // crossing the gap is the routeMode failure.
            LaunchedEffect(showOsmPanel) {
                if (!showOsmPanel) {
                    val pend = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        val s = OsmImportStage.statesInFlight(context).firstOrNull()
                        if (s == null) null
                        else if (OsmImportLedger.pendingScope(context, s) == null) null
                        else if (OsmImportLedger.pendingBbox(context, s) != null) null
                        else Pair(s, OsmImportStage.trailExtent(context, s))
                    }
                    val slug = pend?.first
                    val ext = pend?.second
                    if (slug != null && ext != null) {
                        // ext is [s, w, n, e]
                        val fS = ext[0]; val fW = ext[1]; val fN = ext[2]; val fE = ext[3]
                        // OSM-C3B-FIT-2026-07-29: NO PADDING. Fred 07-29:
                        // "if we remove your 5% of use fit we have no issues."
                        //
                        // MVS:1475 is the FIX 6 loop in the open --
                        // "bbox+10% pad -> lastViewport -> fitBounds" puts the
                        // PADDED box into lastViewport*, savePlanningState
                        // persists it, and the next restore pads THAT. Passing
                        // the raw extent keeps the OSM path out of that loop.
                        // ⚠ Removes our contribution, not the mechanism.
                        android.util.Log.i(
                            "OsmArea",
                            "awaiting draw for $slug -- fit S$fS N$fN W$fW E$fE (no pad)"
                        )
                        webViewRef?.evaluateJavascript(
                            "fitBounds([" + fS + "," + fN + "],[" + fW + "," + fE + "])", null
                        )
                        // PERSIST THE FRAME. savePlanningState() reads
                        // lastViewport*, and ONLY onViewportChanged (MVS:559)
                        // sets those -- it then debounces 400ms and saves.
                        // MVS:669 says fitBounds normally reaches it via
                        // moveend, so this is either the fix or a duplicate the
                        // debounce coalesces. It reads getBounds() AFTER the
                        // move, so it cannot persist a stale frame.
                        kotlinx.coroutines.delay(700)
                        webViewRef?.evaluateJavascript(
                            "try{var b=map.getBounds();" +
                                "Android.onViewportChanged(b.getNorth(),b.getSouth()," +
                                "b.getEast(),b.getWest(),map.getZoom())}catch(e){}",
                            null
                        )
                        // Let the 400ms debounce fire before anything else
                        // touches the map.
                        kotlinx.coroutines.delay(500)
                        android.util.Log.i("OsmArea", "viewport reported + saved")
                        osmAwaitingSlug = slug
                        panelOsmChecked = true
                        downloadBbox = DownloadBbox()
                        // OPEN THE DRAW PANEL LAST. Its own LaunchedEffect sets
                        // pmDownloadedOn = true, which redraws -- previously
                        // racing a map move that had not been persisted yet.
                        showDownloadPanel = true
                    }
                }
            }

            /* SAVESELECTED-2026-08-27: resolving the batch.
             *
             * ⛔ ORDER IS LOAD-BEARING, and the existing graduation path says so
             * in its own comment: the id does not exist until insertRoute
             * returns, and the draft must not be deleted until the notes have
             * landed, or the narrative is gone with no way back.
             *
             *   per route: load -> buildWkt -> insertRoute -> notes -> delete
             *   then:      delete the unticked
             *   LAST:      delete the batch file
             *
             * ⚠ THE BATCH FILE GOES LAST because its absence IS "resolved". If
             * it went first and a promotion then failed, the lock would be clear
             * and the drafts stranded.
             *
             * ⭐ Same calls, same order as saveCompleted -- but sequential, with
             * names as LOCALS. saveCompleted reads routeName from Compose state
             * and is fire-and-forget: five of them would race on RouteManager,
             * and the second load would clear the route the first was saving.
             */
            val runBatchSave: (String) -> Unit = { area ->
                batchSaving = true
                val keep = batchRows.filter { it.name in batchSave }
                val drop = batchRows.filter { it.name !in batchSave }
                kotlinx.coroutines.MainScope().launch {
                    val saved = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO) {
                        SpatialDbManager.init(context)
                        /* THREEFIX-2026-08-27: BY ID, NOT BY VIEWPORT.
                         *
                         * ⛔ Fred, this morning: "cannot rely on viewport." He was
                         * right and I argued the opposite -- buildWktAndBbox does
                         * need trail geometry, or the route saves as chords
                         * between vertices instead of following the switchbacks,
                         * but the viewport is not how to get it.
                         *
                         * ⭐ queryGeomByIds already exists and the resume path
                         * uses it: "fetch the snap-referenced geometry by the
                         * lineIds the vertices carry (NOT by viewport) so reload
                         * draws the snapped shape regardless of where the map is
                         * looking. Fixes resume chords."
                         *
                         * ⚠ Same bug, already fixed once, in this file. It worked
                         * only because zoom 11 happens to show the whole route.
                         */
                        var ok = 0
                        for (r in keep) {
                            if (RouteDraftStore.loadIntoRouteManager(r.name) == null) {
                                android.util.Log.e("BatchGrid", "load failed: " + r.name)
                                continue
                            }
                            // ⭐ the vertices carry their own lineIds -- nothing
                            // needs to be on screen
                            val verts = RouteManager.routeVertices()
                            val byId = HashMap<String, String>()
                            byId.putAll(SpatialDbManager.queryGeomByIds(
                                verts.filter { it.snapped && it.lineType == "trail" }
                                    .mapNotNull { it.lineId }, "trail"))
                            byId.putAll(SpatialDbManager.queryGeomByIds(
                                verts.filter { it.snapped && it.lineType == "track" }
                                    .mapNotNull { it.lineId }, "track"))
                            val built = RouteManager.buildWktAndBbox { lineId ->
                                byId[lineId]?.let { RouteManager.parseWktLine(it) }
                            }
                            if (built == null) {
                                android.util.Log.e("BatchGrid", "build failed: " + r.name)
                                continue
                            }
                            val (wkt, bbox) = built
                            /* ⭐ "Panguitch Mi. 79 Pts. 7" -- both numbers mean
                             * something, and two routes from one batch differ in
                             * both, so they stay unique without the rider naming
                             * each one. */
                            val nm = area.trim() + " Mi. " + Math.round(r.miles) +
                                " Pts. " + r.features.size
                            val id = SpatialDbManager.insertRoute(
                                nm, wkt, bbox[0], bbox[1], bbox[2], bbox[3])
                            // ⛔ notes BEFORE the delete -- the narrative and the
                            // recipe both live in them
                            RouteDraftStore.readNotes(r.name)?.let { nts ->
                                SpatialDbManager.writeRouteNotes(id, nts)
                            }
                            RouteDraftStore.deleteDraft(r.name)
                            ok++
                        }
                        // the ones nobody kept
                        for (r in drop) RouteDraftStore.deleteDraft(r.name)
                        // ⛔ LAST
                        RouteDraftStore.clearBatch()
                        ok
                    }
                    RouteManager.clearRoute()
                    RouteDraftStore.deleteDraft(RouteDraftStore.UNNAMED)
                    draftListTick++
                    batchSaving = false
                    android.util.Log.i("PanelTrace", "BATCH <- false"); batchGridOpen = false
                    batchRows = emptyList()
                    batchSave = emptySet()
                    batchHidden = emptySet()
                    webViewRef?.evaluateJavascript("clearBatchRoutes()", null)
                    // ⛔ the layers go back exactly as they were
                    batchPrevLayers?.let { prev ->
                        listOf("Trails", "Tracks", "Waypoints", "Routes")
                            .forEachIndexed { i, ly ->
                                if (prev[i] != DS_OFF) {
                                    webViewRef?.evaluateJavascript("show" + ly + "()", null)
                                }
                            }
                        webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                    }
                    batchPrevLayers = null
                    android.widget.Toast.makeText(context,
                        if (saved > 0) "Saved " + saved + " route(s)" else "Batch cleared",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            if (batchAreaPrompt) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { batchAreaPrompt = false },
                    title = { androidx.compose.material3.Text("Name the area") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.OutlinedTextField(
                                value = batchAreaName,
                                onValueChange = { batchAreaName = it },
                                singleLine = true,
                                label = { androidx.compose.material3.Text("Area") }
                            )
                            androidx.compose.material3.Text(
                                "Saved as \"" + batchAreaName.trim().ifBlank { "Area" } +
                                    " Mi. 79 Pts. 7\"",
                                fontSize = 11.sp,
                                color = Color(0xFF9AA4B2),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            enabled = batchAreaName.isNotBlank() && !batchSaving,
                            onClick = {
                                batchAreaPrompt = false
                                runBatchSave(batchAreaName)
                            }) { androidx.compose.material3.Text("Save") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { batchAreaPrompt = false }
                        ) { androidx.compose.material3.Text("Cancel") }
                    }
                )
            }
            if (batchDeleteConfirm) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { batchDeleteConfirm = false },
                    title = { androidx.compose.material3.Text("Delete all routes?") },
                    text = {
                        androidx.compose.material3.Text(
                            "No routes selected. All " + batchRows.size +
                                " will be deleted. If you proceed you must start over."
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            batchDeleteConfirm = false
                            runBatchSave("")
                        }) { androidx.compose.material3.Text("Delete") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { batchDeleteConfirm = false }
                        ) { androidx.compose.material3.Text("Cancel") }
                    }
                )
            }
            /* AISTEPS-2026-08-28: on entering the flow.
             *
             * ⚠ THIS WAS ALMOST LOST. It lived inside the inline Welcome block
             * that this patch replaces, so removing that block took the portrait
             * lock, the layer defaults and the Map Features panel with it. The
             * guard that caught it now asserts all three survive.
             *
             * ⭐ THE LAYERS ARE A STARTING DEFAULT, NOT A SETTING. Riders toggle
             * off and on as they research, and select individual tracks to
             * narrow down — whatever they leave it as is their work.
             */
            androidx.compose.runtime.LaunchedEffect(pinStep) {
                // ⚠ invisible either way, and it must be set before the
                // rider is deep in the flow
                if (pinStep == PIN_STEP_WELCOME) {
                    (context as? android.app.Activity)?.requestedOrientation =
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                /* ⛔ AT THE TRAILHEAD, NOT THE OVERVIEW. Map Features is the panel
                 * Route+ lives in, and opening it at the overview put it over the
                 * full-screen panel. The map first matters here.
                 *
                 * ⛔ AND ONCE PER FLOW. LaunchedEffect re-runs on every key
                 * change, so returning to this step forced the panel open again
                 * over whatever layer arrangement the rider had made — and they
                 * were told to use it to toggle layers off and pick tracks.
                 */
                // ⚠ (5) on for the trailhead step, off for every other
                webViewRef?.evaluateJavascript(
                    "showAimRing(" + (pinStep == PIN_STEP_TRAILHEAD) + ")", null)
                if (pinStep == PIN_STEP_TRAILHEAD && !aiMapReady) {
                    aiMapReady = true
                    if (trailState == DS_OFF) trailState = DS_ON
                    if (trackState == DS_OFF) trackState = DS_ON
                    if (waypointState == DS_OFF) waypointState = DS_ON
                    listOf("Trails", "Tracks", "Waypoints").forEach { ly ->
                        webViewRef?.evaluateJavascript("show" + ly + "()", null)
                    }
                    webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                    showArtifactsPanel = true
                }
                // the next run gets its defaults; this one never fights the rider
                if (pinStep == PIN_STEP_NONE) aiMapReady = false
            }
            /* AISTEPS-2026-08-28: the front of the flow — overview, then
             * parameters. Full screen and no map behind either: neither is map
             * work, and Fred was explicit that the map is a distraction while
             * end-state parameters are being set.
             */
            if (pinStep == PIN_STEP_WELCOME || pinStep == PIN_STEP_DISTANCE) {
                ConvoyAiStepPanel(
                    isOverview = pinStep == PIN_STEP_WELCOME,
                    // ⚠ the label is the SCREEN's business, not the panel's
                    stepLabel = if (pinStep == PIN_STEP_WELCOME) "1 / 6" else "2 / 6",
                    dontShowAgain = aiSkipOverview,
                    onDontShowAgain = { aiSkipOverview = it },
                    miLow = pinMiLow, miHigh = pinMiHigh,
                    mphLow = pinMphLow, mphHigh = pinMphHigh,
                    onMiles = { lo, hi -> pinMiLow = lo; pinMiHigh = hi },
                    onSpeed = { lo, hi -> pinMphLow = lo; pinMphHigh = hi },
                    /* ⚠ WHOLE HOURS. This envelope compounds TWO ranges and a
                     * decimal would imply a precision that does not exist —
                     * the same reasoning the checklist summary already uses. */
                    hoursText = run {
                        val lo = Math.round(
                            pinMiLow.toDouble() / pinMphHigh.toDouble().coerceAtLeast(1.0))
                        val hi = Math.round(
                            pinMiHigh.toDouble() / pinMphLow.toDouble().coerceAtLeast(1.0))
                        "$lo to $hi hours"
                    },
                    onCancel = { android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> NONE"); pinStep = PIN_STEP_NONE },
                    onContinue = {
                        if (pinStep == PIN_STEP_WELCOME) {
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> DISTANCE"); pinStep = PIN_STEP_DISTANCE
                        } else {
                            /* ⭐ EXACTLY WHAT onFindRides DID — stash the values,
                             * clear the last run, hand to pin collection. The
                             * search itself still runs from the checklist's own
                             * FIND MY RIDES with the trailhead the rider drops.
                             */
                            pinRideName = ""
                            showAiDesign = false
                            aiResults = emptyList()
                            aiProgress = ""
                            pinReset()
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> TRAILHEAD"); pinStep = PIN_STEP_TRAILHEAD
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            /* AISTEPS-2026-08-28: portrait is released when the screen leaves
             * composition, not at a named step.
             *
             * ⛔ Tied to the last step, abandoning at the trailhead — back
             * button, cancel, the screen being lost — would leave the device
             * locked in portrait with nothing to unlock it. No step has to
             * remember; the lock cannot outlive the flow.
             */
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    (context as? android.app.Activity)?.requestedOrientation =
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            // -- ARTIFACT LIST PANEL (SELECT/EDIT) --
            if (showEntryChoice) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showEntryChoice = false },
                    title = { androidx.compose.material3.Text("Start a route") },
                    text = { androidx.compose.material3.Text(if (recoveryDetected) "Recovery detected. Begin a new route, or resume one in progress?" else "Begin a new route, or resume one in progress?") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showEntryChoice = false
                            // [routeplus-resolver 2026-08-01] Guard: New Route writes to the
                            // UNNAMED filename. If a remnant is still there (resolver dialog not
                            // yet answered), starting fresh would silently overwrite it.
                            if (RouteDraftStore.draftExists(RouteDraftStore.UNNAMED)) {
                                android.widget.Toast.makeText(context,
                                    "A route is already in progress -- resolve it first",
                                    android.widget.Toast.LENGTH_LONG).show()
                                android.util.Log.w("RouteModeTrace", "RESOLVER: New Route blocked, unnamed draft present")
                                return@TextButton
                            }
                            routeLifecycleState = ROUTE_LS_NEW
                            routeMethod = ROUTE_METHOD_P2P
                            routeName = "Auto Saved In Progress"
                            routeNameTaken = false
                            routeEntryNonce++
                            // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                            addPointMode = true
                            routeMode = true
                            webViewRef?.evaluateJavascript("window.__routeMode=true;setRouteMode(true)", null)  // arm tap-to-place (no name prompt)
                        }) { androidx.compose.material3.Text("New Route") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showEntryChoice = false
                            android.util.Log.i("PanelTrace", "PICKER <- true"); showInProgressPicker = true
                        }) { androidx.compose.material3.Text("In Progress") }
                    }
                )
            }
            // hoisted verbatim from the toolbar's onSaveCompleted (Option 1):
            // one proven completed-save path, called by BOTH the toolbar and the Save-choice dialog.
            val saveCompleted: () -> Unit = {
                val sLat = lastViewportSouth; val wLon = lastViewportWest
                val nLat = lastViewportNorth; val eLon = lastViewportEast
                kotlinx.coroutines.MainScope().launch {
                    val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        SpatialDbManager.init(context)
                        val lines = SpatialDbManager.queryTrailsByViewport(sLat, wLon, nLat, eLon) +
                                SpatialDbManager.queryTracksByViewport(sLat, wLon, nLat, eLon)
                        val byId = HashMap<String, String>()
                        for (m in lines) {
                            val id = m["trail_id"] ?: m["track_id"]
                            val g = m["geometry"]
                            if (id != null && g != null) byId[id] = g
                        }
                        val built = RouteManager.buildWktAndBbox { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                        if (built != null) {
                            val (wkt, bbox) = built
                            // ROUTENOTES-2026-08-23X: capture the id -- every existing
                            // call site discarded it, and the notes need it.
                            val newRouteId = SpatialDbManager.insertRoute(
                                routeName.ifBlank { "Route " + System.currentTimeMillis() },
                                wkt, bbox[0], bbox[1], bbox[2], bbox[3])
                            // ⛔ ORDER IS LOAD-BEARING. The id does not exist until
                            // insertRoute returns, and the draft below must NOT be
                            // deleted until these rows have landed -- otherwise the
                            // narrative is gone with no way back.
                            RouteDraftStore.readNotes(routeName)?.let { nts ->
                                SpatialDbManager.writeRouteNotes(newRouteId, nts)
                            }
                            true
                        } else false
                    }
                    if (res) {
                        RouteManager.clearRoute()
                        // === GRADUATE-DELETEDRAFT-2026-08-17 ===
                        // Graduation is a TRANSITION, not a copy: RouteDraftStore's own header
                        // states a route lives as a draft JSON or a spatial-DB row, never both,
                        // and ConvoyRouteToolbar documents this path as "insertRoute (DB row) +
                        // delete draft if any". The delete was missing, so a graduated route
                        // stayed in route_drafts/ and in the In-Progress picker, and reopening
                        // that stale draft forked the route into two diverging versions.
                        // Both names are removed: the named WIP this graduated from, and the
                        // per-point autosave -- which exists whether or not the route was ever
                        // named, and is the file left behind when routeName is blank, because
                        // insertRoute substitutes a generated name for the DB row only.
                        // deleteDraft returns true for an absent file, so a miss is a no-op.
                        RouteDraftStore.deleteDraft(routeName)
                        RouteDraftStore.deleteDraft(RouteDraftStore.UNNAMED)
                        draftListTick++
                        webViewRef?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                        // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                        addPointMode = false
                        routeMode = false
                        webViewRef?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                    } else {
                        android.widget.Toast.makeText(context, "Need at least 2 points to save", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (showNameDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showNameDialog = false },
                    title = { androidx.compose.material3.Text("Name this route") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = routeName,
                            onValueChange = { routeName = it; routeNameTaken = false },
                            singleLine = true,
                            isError = routeNameTaken,
                            label = { androidx.compose.material3.Text("Route name") }
                        )
                        if (routeNameTaken) androidx.compose.material3.Text(
                            "That name is taken — choose a unique name",
                            color = androidx.compose.ui.graphics.Color(0xFFE86B6B),
                            fontSize = 11.sp
                        )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            // name is REQUIRED -- blank is rejected (no auto-fill).
                            if (routeName.isBlank()) {
                                routeNameTaken = true   // reuse hint slot as 'name required'
                            } else if (RouteDraftStore.isNameTaken(routeName)) {
                                routeNameTaken = true
                            } else {
                                routeNameTaken = false
                                routeLifecycleState = ROUTE_LS_NEW
                                showNameDialog = false
                                routeEntryNonce++
                                // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                                addPointMode = true
                                routeMode = true
                                webViewRef?.evaluateJavascript("window.__routeMode=true;setRouteMode(true)", null)  // arm tap-to-place
                            }
                        }) { androidx.compose.material3.Text("Start") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showNameDialog = false
                        }) { androidx.compose.material3.Text("Cancel") }
                    }
                )
            }
            // ROUTEAI-2026-08-23Q: the AI panel is full-screen; the toolbar drew
            // AFTER it and sat on top. Hiding it is better than reordering --
            // a floating draggable toolbar over a full-screen panel is confusing
            // even with the z-order right, and its controls do nothing there.
            /* PATCHB-2026-08-28 (4): the new panels hide it too.
             *
             * ⚠ TRACED, NOT GUESSED. PanelTrace showed the picker closing and
             * never rendering, so the thing on screen was neither it nor the
             * artifacts panel — "ROUTE +" is this toolbar.
             *
             * ⭐ Extending the SAME gate rather than tearing route mode down:
             * six sites arm it, three already tear it down, and a fourth
             * divergent copy is how the autosave-discard bug happened. The
             * rider armed draw mode deliberately, so the arm stays.
             */
            /* SATFIXES-2026-08-29 (3): >= was true for EVERY positive step,
             * so the toolbar hid during the two negative ones and came back from
             * the trailhead onward. == PIN_STEP_NONE means "no AI flow running",
             * which is the actual condition. */
            if (routeMode && !showAiDesign && pinStep == PIN_STEP_NONE) {
                ConvoyRouteToolbar(
                    isConvoyMap = false,
                    vertexCount = RouteManager.routeVertexCount(),
                    routeEntryNonce = routeEntryNonce,
                    selectedMethod = routeMethod,
                    onSelectMethod = {
                        routeMethod = it
                        // ROUTEAI-2026-08-23P: STUB:AIDESIGN is satisfied here. The
                        // toolbar already reports the change, so no new callback is
                        // needed on the shared component.
                        if (it == ROUTE_METHOD_SUGGEST) {
                            // CHIPLIVE-2026-08-24J2: A FRESH SESSION STARTS EMPTY.
                            //
                            // The panel decides its phase from `results` at first
                            // composition, so a previous run's routes still sitting
                            // in aiResults reopened it straight onto PHASE_RESULTS
                            // -- last time's answer, dressed as this time's.
                            //
                            // onFindRides clears these already, but it fires at
                            // PROCEED, by which point the panel has read them.
                            // Clear where the session BEGINS.
                            //
                            // ⚠ pinTrailName and its coordinates live out here
                            // rather than in the panel, so they survive a reopen
                            // too -- a second run would have started from the first
                            // run's trailhead without asking. Same bug, one step on.
                            aiResults = emptyList()
                            aiProgress = ""
                            aiBusy = false
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> NONE"); pinStep = PIN_STEP_NONE
                            pinTrailName = ""
                            pinTrailLat = 0.0
                            pinTrailLon = 0.0
                            pinNotice = ""

                            // PINSELECT-2026-08-24G: trails and waypoints ON.
                            //
                            // Step 1 of the checklist asks the rider to TAP a
                            // trailhead. With waypoints switched off it is not
                            // drawn, so there is nothing to tap -- which is exactly
                            // what happened on the first device run, and the error
                            // message ("No trailhead there") was misleading because
                            // one WAS there.
                            //
                            // Set HERE rather than at pin collection, per Fred:
                            // upstream of everything, so search results and every
                            // later draw already have them on and nothing
                            // downstream has to re-assert it.
                            //
                            // ⚠ SEVENTH COPY OF THIS WRITE. Six others live in this
                            // file (panel, list editor, select-all/none, two restore
                            // paths). The four steps below are what onSetState does
                            // and they have to match it or the layer state and the
                            // map disagree. Consolidating into one
                            // setDisplayState(type, state) is owed -- it is not this
                            // patch's job.
                            if (trailState == DS_OFF) {
                                trailState = DS_ON
                                trailCheckedIds = null
                                webViewRef?.evaluateJavascript("showTrails()", null)
                            }
                            if (waypointState == DS_OFF) {
                                waypointState = DS_ON
                                waypointCheckedIds = null
                                webViewRef?.evaluateJavascript("showWaypoints()", null)
                            }
                            webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                            savePlanningState()
                            /* AICLOSES-2026-08-27: the panel that launched the
                             * flow closes when the flow starts.
                             *
                             * ⛔ It did not, so the picker sat behind the whole
                             * AI flow and then ON TOP OF THE COMPARE TABLE --
                             * covering the header where the route names and the
                             * save ticks are, which made the table unusable.
                             *
                             * ⚠ Every other exit from this panel already closes
                             * it: both dismiss buttons and the + Plan a New Route
                             * confirm. The AI option was the one path that
                             * forgot.
                             */
                            android.util.Log.i("PanelTrace", "PICKER <- false"); showInProgressPicker = false
                            /* AIWELCOME-2026-08-28: the banner comes first.
                             * ⚠ PROCEED sets showAiDesign, so everything past
                             * this point is unchanged — if the banner is wrong,
                             * the old flow still works. */
                            /* CLOSEROUTEPANEL-2026-08-28: the panel that
                             * launched the flow closes when the flow starts.
                             * ⚠ Every other exit from it already does this —
                             * both dismiss buttons and the + Plan a New Route
                             * confirm. This path forgot, and the overview
                             * cannot be read through it. */
                            android.util.Log.i("PanelTrace", "PICKER <- false"); showInProgressPicker = false
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> WELCOME"); pinStep = PIN_STEP_WELCOME
                        }
                    },
                    onNewRoute = {
                        routeLifecycleState = ROUTE_LS_NEW
                        routeName = "Auto Saved In Progress"
                        routeNameTaken = false
                    },
                    // ARMSTATE-2026-08-13F: the highlight is the real armed state.
                    addArmed = addPointMode,
                    onAddModeChanged = { armed ->
                        addPointMode = armed
                        // AIMODE-2026-08-25B4b: choosing Draw ENDS the AI flow.
                        // The rider has said they want to place vertices by hand.
                        //
                        // ⚠ armed == true ONLY. Per the note below, selecting
                        // Artifact ends draw mode but NOT the route session, so
                        // treating both alike would throw away a rider's pins
                        // for tapping the wrong chip.
                        if (armed && pinStep != PIN_STEP_NONE) {
                            android.util.Log.i("PanelTrace", "STEP " + pinStepName(pinStep) + " -> NONE"); pinStep = PIN_STEP_NONE
                            pinFeas = null
                            android.util.Log.i("GuidedPin", "ADD selected -- AI flow ended")
                        }
                        // `armed` true = Draw selected, false = Artifact selected.
                        // Per Fred: selecting Artifact ends DRAW MODE but the route
                        // SESSION continues -- it is not an exit.
                        webViewRef?.evaluateJavascript("window.__routeMode=" + armed + ";setRouteMode(" + armed + ")", null)
                    },
                    onUndo = {
                        RouteManager.undoVertex()
                        if (routeName.isNotBlank()) {
                            val methodStr = when (routeMethod) { ROUTE_METHOD_DRAW -> "draw"; ROUTE_METHOD_SUGGEST -> "suggest"; else -> "point" }
                            runCatching {
                                if (RouteDraftStore.draftExists(routeName)) RouteDraftStore.overwriteDraft(routeName, methodStr)
                                else RouteDraftStore.writeDraft(routeName, methodStr)
                            }
                        }
                        scope.launch {
                            val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SpatialDbManager.init(context)
                                val tl = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                val tk = SpatialDbManager.queryTracksByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                val byId = HashMap<String, String>()
                                for (m in tl) { val id = m["trail_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                for (m in tk) { val id = m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                                    .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
                            }
                            webViewRef?.evaluateJavascript("drawBuildLine('" + pts + "')", null)
                        }
                    },
                    onSaveCompleted = saveCompleted,
                    routeLifecycleState = routeLifecycleState,
                    onSaveRequested = { saveOrigName = routeName; showSaveChoice = true },
                    onDiscardRequested = { showDiscardChoice = true },
                    onSelectInProgress = { android.util.Log.i("PanelTrace", "PICKER <- true"); showInProgressPicker = true },
                    // ROUTECLOSE-2026-09-02: close, not discard. ⭐ Both sides
                    // together -- the Kotlin flag AND the JS -- copied verbatim
                    // from the in-progress picker's dismiss at :3931, which is
                    // the one place that already turns Route+ off correctly.
                    // ⚠ Setting only `routeMode = false` leaves the map still
                    // in route mode; setRouteMode(false) also reasserts the
                    // popup unbind/rebind, which the raw flag does not.
                    onClose = {
                        android.util.Log.i("PanelTrace", "ROUTE+ <- closed by X")
                        routeMode = false
                        webViewRef?.evaluateJavascript(
                            "window.__routeMode=false;setRouteMode(false)", null)
                    },
                    onExit = {
                        RouteManager.clearRoute()
                        // ONEXITDELETE-2026-08-13: NEW-route Discard routes here (toolbar sends
                        // ROUTE_LS_NEW to onExit, not onDiscardRequested). The per-point autosave
                        // already wrote "Auto Saved In Progress.json" to disk, so onExit must delete
                        // it too or the draft survives the discard.
                        RouteDraftStore.deleteDraft(routeName)
                        draftListTick++
                        webViewRef?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                        // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                        addPointMode = false
                        routeMode = false
                    }
                )
            }

            // ---- Route lifecycle dialogs (Layer 2; store calls stubbed) ----
            if (showSaveChoice) {
                val pts = RouteManager.routeVertexCount()
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showSaveChoice = false },
                    title = { androidx.compose.material3.Text("Save route") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text(
                                if (routeLifecycleState == ROUTE_LS_RESUMED)
                                    "Graduate to a saved route, or keep editing as in-progress."
                                else "Save as a completed route (needs 2+ points), or keep as in-progress."
                            )
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = if (routeName == "Auto Saved In Progress") "" else routeName,
                                onValueChange = { routeName = it },
                                singleLine = true,
                                label = { androidx.compose.material3.Text("Route name (required)") }
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val nm = routeName.trim()
                            if (nm.isBlank() || nm == "Auto Saved In Progress") {
                                android.widget.Toast.makeText(context, "Enter a route name", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (pts < 2) {
                                android.widget.Toast.makeText(context, "Need at least 2 points", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                showSaveChoice = false
                                val oldNm = saveOrigName
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        if (oldNm != nm) RouteDraftStore.renameDraft(oldNm, nm)
                                    }
                                    routeName = nm
                                    kotlinx.coroutines.delay(400)
                                    saveCompleted()
                                }
                            }
                        }) { androidx.compose.material3.Text("Save as completed route") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val nm = routeName.trim()
                            if (nm.isBlank() || nm == "Auto Saved In Progress") {
                                android.widget.Toast.makeText(context, "Enter a route name", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                showSaveChoice = false
                                val oldNm = saveOrigName
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        if (oldNm != nm) RouteDraftStore.renameDraft(oldNm, nm)
                                    }
                                    routeName = nm
                                    RouteManager.clearRoute()
                                    // SAVEWIP-LISTTICK-2026-08-17: refresh the In-Progress list. Save-as-WIP
                                    // wrote the draft but never bumped the tick, and the list is
                                    // built in remember(draftListTick) — so the new draft did not
                                    // appear until the user left the map and came back, which
                                    // rebuilt the screen. (Correctly NO deleteDraft here: save-WIP
                                    // is a rename, so the file persists under its new name.)
                                    draftListTick++
                                    webViewRef?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                                    // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                                    addPointMode = false
                                    routeMode = false
                                    // [saveinprogress-trace 2026-08-01] This path disarmed silently -- no trace,
                                    // no toast. Every route-mode write announces itself while instrumentation is in.
                                }
                            }
                        }) { androidx.compose.material3.Text("Save as in progress") }
                    }
                )
            }

            if (showDiscardChoice) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showDiscardChoice = false },
                    title = { androidx.compose.material3.Text("Discard in-progress route") },
                    text = { androidx.compose.material3.Text("Roll back to the last saved draft, or delete this in-progress route entirely.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showDiscardChoice = false
                            // true roll-back: reload the saved draft, drop this session's edits, KEEP building
                            RouteDraftStore.loadIntoRouteManager(routeName)
                            scope.launch {
                                val rbPts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    SpatialDbManager.init(context)
                                    val tl = SpatialDbManager.queryTrailsByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                    val tk = SpatialDbManager.queryTracksByViewport(lastViewportSouth, lastViewportWest, lastViewportNorth, lastViewportEast)
                                    val byId = HashMap<String, String>()
                                    for (m in tl) { val id = m["trail_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                    for (m in tk) { val id = m["track_id"]; val g = m["geometry"]; if (id != null && g != null) byId[id] = g }
                                    RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                                        .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
                                }
                                webViewRef?.evaluateJavascript("drawBuildLine('" + rbPts + "')", null)
                            }
                        }) { androidx.compose.material3.Text("Roll back") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showDiscardChoice = false
                            // DISCARDWINS-2026-08-13C: ORDER MATTERS, AND SO DOES THE NAME.
                            //
                            // Autosave is a crash net. A discard is a deliberate
                            // choice to throw the work away, so it has to win
                            // unconditionally - which means killing the autosave
                            // path for the session, not just deleting the file.
                            //
                            // Clear the route FIRST: deleting while vertices are
                            // still held left something that a later redraw could
                            // save straight back.
                            //
                            // Then blank the name. Both per-point save sites are
                            // guarded by routeName.isNotBlank(), and that guard was
                            // never disarmed - so the name survived the discard and
                            // any later save wrote the draft back under it. That is
                            // why a fresh autosave appeared on the way out.
                            RouteManager.clearRoute()
                            RouteDraftStore.deleteDraft(routeName)
                            // LISTTICK-2026-08-13G: the In-Progress list is keyed on this tick. Without the
                            // bump the file is deleted and the row stays on screen, so a
                            // delete that already worked looks broken and gets repeated.
                            draftListTick++
                            webViewRef?.evaluateJavascript("setRouteMode(false); clearBuildLine();", null)
                            // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                            addPointMode = false
                            routeMode = false
                            routeName = ""
                        }) { androidx.compose.material3.Text("Delete in-progress") }
                    }
                )
            }

            if (showDraftResolve) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { },
                    title = { androidx.compose.material3.Text("Unfinished route") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text(
                                "A route with " + draftResolvePts + " points was left unnamed by your last session. Name it to keep it, or discard it."
                            )
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = draftResolveName,
                                onValueChange = { draftResolveName = it; draftResolveErr = "" },
                                singleLine = true,
                                isError = draftResolveErr.isNotEmpty(),
                                label = { androidx.compose.material3.Text("Route name") }
                            )
                            if (draftResolveErr.isNotEmpty()) {
                                androidx.compose.material3.Text(
                                    draftResolveErr,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val nm = draftResolveName.trim()
                            if (nm.isBlank()) {
                                draftResolveErr = "Enter a name"
                            } else if (RouteDraftStore.isNameTaken(nm)) {
                                draftResolveErr = "That name is already used"
                            } else if (RouteDraftStore.renameDraft(RouteDraftStore.UNNAMED, nm)) {
                                android.util.Log.i("RouteModeTrace", "RESOLVER: renamed unnamed draft -> " + nm)
                                showDraftResolve = false
                                pendingInventory = true
                            } else {
                                draftResolveErr = "Rename failed"
                            }
                        }) { androidx.compose.material3.Text("Keep") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            RouteDraftStore.deleteDraft(RouteDraftStore.UNNAMED)
                            // LISTTICK-2026-08-13G: the In-Progress list is keyed on this tick. Without the
                            // bump the file is deleted and the row stays on screen, so a
                            // delete that already worked looks broken and gets repeated.
                            draftListTick++
                            android.util.Log.i("RouteModeTrace", "RESOLVER: discarded unnamed draft")
                            showDraftResolve = false
                            pendingInventory = true
                        }) { androidx.compose.material3.Text("Discard") }
                    }
                )
            }
            // [draft-resolver 2026-08-01] Inventory toast -- runs only after the resolver
            // has finished, so the unnamed draft is gone and a just-named one appears on
            // its own merits. 600ms lets the rename/delete land on disk first.
            if (pendingInventory) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(600)
                    val list = RouteDraftStore.listDrafts()
                        .sortedBy { it.createdAt }
                    val msg = if (list.isEmpty()) "No unfinished routes"
                        else list.size.toString() + " unfinished route" + (if (list.size == 1) "" else "s") + (if (list.any { it.name == RouteDraftStore.UNNAMED }) " - 1 needs a name" else "") + " - tap +ROUTE" 
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    android.util.Log.i("RouteModeTrace", "RESOLVER: inventory " + list.size + " draft(s)")
                    pendingInventory = false
                }
            }
            if (recoveryPending) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { recoveryPending = false },
                    title = { androidx.compose.material3.Text("Route recovery") },
                    text = { androidx.compose.material3.Text("A route was left open from your last session. To resume it, tap +ROUTE and choose \"In Progress\".") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            recoveryPending = false
                        }) { androidx.compose.material3.Text("OK") }
                    }
                )
            }

            /* THREEFIX-2026-08-27: A PICKER WITH NOTHING TO PICK.
             *
             * ⛔ After the first successful SAVE SELECTED the batch had resolved
             * and route_drafts/ was empty -- and Route+ still raised this dialog
             * with an empty list, offering Cancel and + Plan a New Route.
             *
             * ⭐ Guarded at the RENDER, not at the three setters: "is there
             * anything to pick" is a property of the list, not of any caller,
             * and three guards would be three places to forget.
             *
             * ⚠ It does not merely hide the dialog -- it runs what + Plan a New
             * Route does, so the tap starts a route. A silently ignored tap
             * would be worse than the empty dialog.
             */
            if (showInProgressPicker && emulatedDrafts.isEmpty()) {
                androidx.compose.runtime.LaunchedEffect(routeEntryNonce, showInProgressPicker) {
                    android.util.Log.i("PanelTrace", "PICKER <- false"); showInProgressPicker = false
                    routeLifecycleState = ROUTE_LS_NEW
                    routeMethod = ROUTE_METHOD_P2P
                    routeName = RouteDraftStore.UNNAMED
                    routeNameTaken = false
                    routeEntryNonce++
                    addPointMode = true
                    routeMode = true
                    webViewRef?.evaluateJavascript(
                        "window.__routeMode=true;setRouteMode(true)", null)
                }
            }
            if (showInProgressPicker && emulatedDrafts.isNotEmpty()) {
                // ⚠ logs the RENDER, so a flag that will not clear can
                // be told apart from a render that ignores it
                android.util.Log.i("PanelTrace", "PICKER renders")
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { android.util.Log.i("PanelTrace", "PICKER <- false"); showInProgressPicker = false; routeMode = false; webViewRef?.evaluateJavascript("window.__routeMode=false;setRouteMode(false)", null) },
                    title = { androidx.compose.material3.Text("Continue editing or create a new route") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            emulatedDrafts.forEach { di -> val d = di.name
                                androidx.compose.foundation.layout.Row(
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        val od = RouteDraftStore.loadIntoRouteManager(d)
                                        routeName = d
                                        routeMethod = when (od?.method) { "draw" -> ROUTE_METHOD_DRAW; "suggest" -> ROUTE_METHOD_SUGGEST; else -> ROUTE_METHOD_P2P }
                                        routeLifecycleState = ROUTE_LS_RESUMED
                                        android.util.Log.i("PanelTrace", "PICKER <- false"); showInProgressPicker = false
                                        routeEntryNonce++
                                        // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                                        addPointMode = true
                                        routeMode = true
                                        savePlanningState()   // stamp open:true on In-Progress resume (matches New Route @872)
                                        scope.launch {
                                            val rsPts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                SpatialDbManager.init(context)
                                                // BY-ID rederive: fetch the snap-referenced trail/track geometry by the
                                                // lineIds the vertices carry (NOT by viewport) so reload draws the snapped
                                                // shape regardless of where the map is looking. Fixes resume chords.
                                                val verts = RouteManager.routeVertices()
                                                val trailIds = verts.filter { it.snapped && it.lineType == "trail" }.mapNotNull { it.lineId }
                                                val trackIds = verts.filter { it.snapped && it.lineType == "track" }.mapNotNull { it.lineId }
                                                val byId = HashMap<String, String>()
                                                byId.putAll(SpatialDbManager.queryGeomByIds(trailIds, "trail"))
                                                byId.putAll(SpatialDbManager.queryGeomByIds(trackIds, "track"))
                                                RouteManager.buildSegments { lineId -> byId[lineId]?.let { RouteManager.parseWktLine(it) } }
                                                    .joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }
                                            }
                                            webViewRef?.evaluateJavascript("window.__routeMode=true;setRouteMode(true); drawBuildLine('" + rsPts + "')", null)
                                            // Second draw after the map settles: the first drawBuildLine can render
                                            // before the build layer is ready (angular); redraw the SAME shape so the
                                            // snapped line shows without needing a manual edit. (Fred: only an edit fixed it.)
                                            webViewRef?.postDelayed({
                                                webViewRef?.evaluateJavascript("drawBuildLine('" + rsPts + "')", null)
                                            }, 400)
                                        }
                                    }) { androidx.compose.foundation.layout.Column { androidx.compose.material3.Text(d); androidx.compose.material3.Text((if (di.createdAt.length >= 10) di.createdAt.substring(0, 10) else di.createdAt) + "  ·  " + di.pointCount + " pts", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) } }
                                    // THREEFIX-2026-08-27: rename removed. ⭐ A draft's
                                    // name is a working label -- the real name is
                                    // chosen at save, and the batch names its own
                                    // routes. ⛔ Renaming a draft that a batch file
                                    // names BY STRING would break the batch
                                    // silently: the file would still list
                                    // "x Route 3" and no such draft would exist.
                                    androidx.compose.material3.TextButton(onClick = {
                                        RouteDraftStore.deleteDraft(d)
                                        draftListTick++   // refresh the picker list
                                    }) { androidx.compose.material3.Text(
                                        "Delete",
                                        color = androidx.compose.ui.graphics.Color(0xFFE86B6B)
                                    ) }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            if (RouteDraftStore.draftExists(RouteDraftStore.UNNAMED)) {
                                android.widget.Toast.makeText(context, "Rename or delete \"" + RouteDraftStore.UNNAMED + "\" before starting a new route.", android.widget.Toast.LENGTH_LONG).show()
                                return@TextButton
                            }
                            android.util.Log.i("PanelTrace", "PICKER <- false"); showInProgressPicker = false
                            routeLifecycleState = ROUTE_LS_NEW
                            routeMethod = ROUTE_METHOD_P2P
                            routeName = RouteDraftStore.UNNAMED
                            routeNameTaken = false
                            routeEntryNonce++
                            // ARMSTATE-2026-08-13F: keep the armed state in step with the session.
                            addPointMode = true
                            routeMode = true
                            webViewRef?.evaluateJavascript("window.__routeMode=true;setRouteMode(true)", null)
                        }) { androidx.compose.material3.Text("+ Plan a New Route") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { android.util.Log.i("PanelTrace", "PICKER <- false"); showInProgressPicker = false; routeMode = false; webViewRef?.evaluateJavascript("window.__routeMode=false;setRouteMode(false)", null) }) {
                            androidx.compose.material3.Text("Cancel")
                        }
                    }
                )
            }
            // THREEFIX-2026-08-27: the rename dialog is gone with its button.
            // ⚠ draftRenameTarget/Text/Err stay declared -- unused state is
            // harmless, and chasing every reference at the end of a long day is
            // how a small change becomes a big one.
            if (activeListType != null) {
                ArtifactListPanel(
                    mapKey = "planning",
                    fitWebView = webViewRef,
                    artifactType = activeListType!!,
                    artifacts = artifactList,
                    selectedIds = selectedArtifactIds,
                    onDismiss = {
                        val allIds = artifactList.mapNotNull { it["id"] }.toSet()
                        val checked = selectedArtifactIds
                        val newState = when {
                            checked.isEmpty() -> DS_OFF
                            checked.containsAll(allIds) && allIds.size == checked.size -> DS_ON
                            else -> DS_SELECTED
                        }
                        val t = activeListType ?: ""
                        when(t) {
                            "Trails" -> { trailState = newState; trailCheckedIds = if (newState == DS_SELECTED) checked else null }
                            "Tracks" -> { trackState = newState; trackCheckedIds = if (newState == DS_SELECTED) checked else null }
                            "Waypoints" -> { waypointState = newState; waypointCheckedIds = if (newState == DS_SELECTED) checked else null }
                            "Routes" -> { routeState = newState; routeCheckedIds = if (newState == DS_SELECTED) checked else null }
                        }
                        if (newState == DS_OFF) {
                            webViewRef?.evaluateJavascript("hide" + t + "()", null)
                        } else {
                            webViewRef?.evaluateJavascript("show" + t + "()", null)
                            webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                        }
                        savePlanningState()
                        activeListType = null
                    },
                    onToggleItem = { id, selected ->
                        selectedArtifactIds = if (selected) selectedArtifactIds + id else selectedArtifactIds - id
                    },
                    onSelectAll = {
                        selectedArtifactIds = artifactList.mapNotNull { it["id"] }.toSet()
                    },
                    onDeselectAll = {
                        selectedArtifactIds = emptySet()
                    },
                    onOpenDetail = { t, id -> activeListType = null; pendingDetailType = t; pendingDetailId = id }
                )
            }
            android.util.Log.d(
                "DetailGate",
                "PLANNER gate type=$pendingDetailType id=$pendingDetailId"
            )
            if (pendingDetailId != null && pendingDetailType != null) {
                ArtifactDetailPanel(
                    artifactType = pendingDetailType!!,
                    id = pendingDetailId!!,
                    mapKey = "planning",
                    fitWebView = webViewRef,
                    onLoadDetail = { t, did -> SpatialDbManager.getArtifactDetail(t, did) },
                    onLoadAliases = { t, did -> SpatialDbManager.getAliasesFor(t, did) },
                    // [2026-06-20] Full action parity. Logic lifted from planning's
                    // ArtifactListPanel handlers; keyed off pendingDetailType (detail can
                    // open from SEARCH where activeListType is null). Refresh: triggerViewportUpdate().
                    onRename = { id, newName ->
                        scope.launch { ConvoyArtifactOps.rename(context, (pendingDetailType ?: return@launch), id, newName); webViewRef?.evaluateJavascript("triggerViewportUpdate()", null) }
                    },
                    onDelete = { id ->
                        scope.launch { ConvoyArtifactOps.delete(context, (pendingDetailType ?: return@launch), id); webViewRef?.evaluateJavascript("triggerViewportUpdate()", null) }
                    },
                    onShare = { id -> scope.launch { ConvoyArtifactOps.share(context, (pendingDetailType ?: return@launch), id) } },
                    onExport = { id -> scope.launch { ConvoyArtifactOps.export(context, (pendingDetailType ?: return@launch), id) } },
                    // ROUTETAP-2026-08-23Z: Y declared the NARRATIVE button but nothing
                    // passed the lambda, so it never appeared. This supplies it.
                    // ⚠ ROUTES ONLY for now -- tracks and waypoints have no narrative
                    // to show, and a button that opens an empty panel is worse than
                    // no button.
                    /* SATFIXES-2026-08-29: shown only when a recipe exists.
                     * ⭐ One press loads the recipe's parameters and lands on the
                     * SUMMARY — the same panel the flow ends on — so the rider
                     * reads back what is about to be built before committing. */
                    onBuildFromRecipe = if (pendingDetailType == "Routes" &&
                        pendingDetailId?.let {
                            SpatialDbManager.routeRecipe(it) != null } == true) {
                        { rid: String ->
                            SpatialDbManager.routeRecipe(rid)?.let { r ->
                                val aLat = r.optDouble("anchorLat", 0.0)
                                val aLon = r.optDouble("anchorLon", 0.0)
                                pinTrailLat = aLat
                                pinTrailLon = aLon
                                /* ⭐ THE NAME COMES BACK BY LOOKUP. The anchor IS
                                 * the waypoint's own coordinates, stored when the
                                 * rider tapped it — so this resolves on every
                                 * recipe already written, including the ones whose
                                 * anchorName was never captured. */
                                pinTrailName = SpatialDbManager
                                    .waypointNameAt(aLat, aLon) ?: ""
                                pinMiLow = r.optInt("milesLow", pinMiLow)
                                pinMiHigh = r.optInt("milesHigh", pinMiHigh)
                                pinMphLow = r.optInt("mphLow", pinMphLow)
                                pinMphHigh = r.optInt("mphHigh", pinMphHigh)
                                val fin = r.optJSONObject("finish")
                                pinIsLoop = fin == null
                                if (fin != null) {
                                    pinEndLat = fin.optDouble("lat", 0.0)
                                    pinEndLon = fin.optDouble("lon", 0.0)
                                }
                                val ps = r.optJSONArray("pins")
                                pinPoints = if (ps == null) emptyList() else
                                    (0 until ps.length()).mapNotNull { i ->
                                        ps.optJSONObject(i)?.let { p ->
                                            Pair(p.optDouble("lat", 0.0),
                                                p.optDouble("lon", 0.0))
                                        }
                                    }
                                pendingDetailType = null
                                pendingDetailId = null
                                android.util.Log.i("PanelTrace",
                                    "RECIPE loaded: " + pinMiLow + "-" + pinMiHigh +
                                    " mi, " + pinPoints.size + " pin(s)")
                                android.util.Log.i("PanelTrace",
                                    "STEP " + pinStepName(pinStep) + " -> SUMMARY")
                                /* CLOSEPLANNER-2026-08-29: QUALIFY THE PINS.
                                 *
                                 * ⛔ The normal flow validates every pin as it is
                                 * dropped. The recipe loads them wholesale and
                                 * validated nothing — so pins whose trails are not
                                 * on THIS device sent the search hunting for
                                 * ground it cannot reach.
                                 *
                                 * ⚠ A PINLESS RECIPE NEEDS NO CHECK: it explores
                                 * what is there and returns fewer routes if the
                                 * ground is thin. That is the common case and it
                                 * proceeds untouched.
                                 */
                                if (pinPoints.isNotEmpty()) gpRunAssess()
                                pinStep = PIN_STEP_SUMMARY
                            }
                            Unit
                        }
                    } else null,
                    onShowNotes = if (pendingDetailType == "Routes") {
                        { rid -> savedNotesRouteId = rid }
                    } else null,
                    onDownloadMaps = { hash ->
                        Thread {
                            val bb = SpatialDbManager.getTrackBbox(context, hash)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (bb != null && bb.isValid) {
                                    pendingDetailId = null
                                    pendingDetailType = null
                                    downloadBbox = bb
                                    showDownloadConfirm = true
                                } else {
                                    android.widget.Toast.makeText(context,
                                        "No map area for this track",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    },
                    // CORRIDOR-WIRING-2026-07-24: same prompt as area, different submission.
                    // The bbox below is shown to the dialog ONLY so it has
                    // something to display - it is the AREA figure, i.e. exactly
                    // the number the corridor is meant to beat. The real
                    // corridor count is logged by enqueueCorridor.
                    onDownloadCorridor = { hash ->
                        Thread {
                            // ROUTECORR-2026-08-10C: tracks then routes - a route needs a box too.
                            // The onDownloadMaps lambda above is the AREA path and
                            // stays tracks-only.
                            val bb = SpatialDbManager.getCorridorBbox(context, hash)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (bb != null && bb.isValid) {
                                    pendingDetailId = null
                                    pendingDetailType = null
                                    pendingCorridorHash = hash
                                    downloadBbox = bb
                                    showDownloadConfirm = true
                                } else {
                                    android.widget.Toast.makeText(context,
                                        "No geometry stored for this item",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    },
                    onChangeType = { id, newType ->
                        scope.launch { ConvoyArtifactOps.changeType(context, id, newType); webViewRef?.evaluateJavascript("triggerViewportUpdate()", null) }
                    },
                    onDeleteAlias = { aliasId -> scope.launch { ConvoyArtifactOps.deleteAlias(context, aliasId) } },
                    onDismiss = { fittedType, fittedId ->
                        if (fittedType != null && fittedId != null) {
                            // [FIT 2026-06-18] Emulate manual row-select on LIVE vars (parity with
                            // convoy). Set this type SELECTED with the fitted id; saveConvoyState
                            // then writes the row (no clobber) and the SEL/EDIT panel reflects it.
                            val sel = setOf(fittedId)
                            // FIT = one artifact: all other types OFF, fitted type SELECTED.
                            trailState = DS_OFF; trailCheckedIds = null
                            trackState = DS_OFF; trackCheckedIds = null
                            waypointState = DS_OFF; waypointCheckedIds = null
                            routeState = DS_OFF; routeCheckedIds = null
                            when (fittedType) {
                                "Trails"    -> { trailState = DS_SELECTED; trailCheckedIds = sel }
                                "Tracks"    -> { trackState = DS_SELECTED; trackCheckedIds = sel }
                                "Waypoints" -> { waypointState = DS_SELECTED; waypointCheckedIds = sel }
                                "Routes"    -> { routeState = DS_SELECTED; routeCheckedIds = sel }
                            }
                            // [FIT recenter 2026-06-20] Restore-to-artifact: bbox+10% pad -> lastViewport -> fitBounds,
                            // so save + the getBounds() redraw below both use the artifact frame (no stale clobber).
                            run {
                                val _bb = SpatialDbManager.bboxForArtifact(fittedType, fittedId)
                                if (_bb != null) {
                                    val _s=_bb[0]; val _w=_bb[1]; val _n=_bb[2]; val _e=_bb[3]
                                    val _latPad=((_n-_s).let{ if(it>0.0) it*0.10 else 0.01 })
                                    val _lonPad=((_e-_w).let{ if(it>0.0) it*0.10 else 0.01 })
                                    val _fS=_s-_latPad; val _fN=_n+_latPad; val _fW=_w-_lonPad; val _fE=_e+_lonPad
                                    lastViewportSouth=_fS; lastViewportWest=_fW; lastViewportNorth=_fN; lastViewportEast=_fE
                                    webViewRef?.evaluateJavascript("fitBounds(["+_fS+","+_fN+"],["+_fW+","+_fE+"])", null)
                                }
                            }
                            savePlanningState()
                            webViewRef?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        } else {
                            val rs = MapStateStore.readMap("planning")
                            trailState = rs.types["Trails"]?.state ?: DS_OFF
                            trackState = rs.types["Tracks"]?.state ?: DS_OFF
                            waypointState = rs.types["Waypoints"]?.state ?: DS_OFF
                            routeState = rs.types["Routes"]?.state ?: DS_OFF
                            trailCheckedIds = MapStateStore.checkedIdsFor(rs, "Trails")
                            trackCheckedIds = MapStateStore.checkedIdsFor(rs, "Tracks")
                            waypointCheckedIds = MapStateStore.checkedIdsFor(rs, "Waypoints")
                            routeCheckedIds = MapStateStore.checkedIdsFor(rs, "Routes")
                        }
                        pendingDetailId = null; if (fittedType != null) pendingDetailType = null
                    }
                )
            }

                        // -- IMPORT LIST PANEL (scan Downloads for GPX/KML) --
            if (showImportList) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xEE131820),
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("IMPORT ARTIFACTS", color = Color(0xFF4DA6FF),
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold)
                            Text("CLOSE", color = Color(0xFF7A8DA0),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showImportList = false }.padding(4.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        // RESYNC TRACKS button
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        SpatialDbManager.init(context)
                                        SpatialDbManager.syncTracksFromFiles(context)
                                    }
                                    android.widget.Toast.makeText(context, "Sync: ${r.processed} processed, ${r.addedRenamed} added, ${r.renamed} renamed (see track_sync.log)", android.widget.Toast.LENGTH_LONG).show()
                                    webViewRef?.evaluateJavascript("triggerViewportUpdate()", null)
                                }
                            },
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0D1520)
                        ) {
                            Text("RESYNC TRACKS TO SPATIAL DB", color = Color(0xFF39FF14),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("GPX/KML files in Downloads:", color = Color(0xFF4A6080),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        if (importFileList.isEmpty()) {
                            Text("No GPX or KML files found in Downloads",
                                color = Color(0xFF667788), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 16.dp))
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                items(importFileList.size) { idx ->
                                    val name = importFileList[idx]
                                    val isImporting = importingFile == name
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                            .clickable(enabled = !isImporting) { runImport(name) },
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isImporting) Color(0xFF2E75B6).copy(alpha = 0.3f) else Color(0xFF1A2233)
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (isImporting) "⏳" else "⬇",
                                                fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                                            Text(name, color = Color.White, fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace, maxLines = 1,
                                                modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Tap file to import. Tracks, waypoints, and routes are extracted automatically.",
                            color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // DisplayPanel removed

            // PHASE0-QUEUE-PANEL-2026-07-24: this bottom-left render is the RUNNING-JOBS
            // INDICATOR only - active count, queued count, tiles remaining.
            // It no longer expands in place (expanded = false); tapping it
            // OPENS THE QUEUE MANAGER, which is where every control lives.
            DownloadQueuePanel(
                expanded = false,
                onToggle = { pmQueuesOpen = true },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp).width(280.dp)
            )

            // ── Download confirm overlay ──────────────────────────────────
            if (showDownloadConfirm && downloadBbox.isValid) {
                val slotSources = remember { MapSourceManager.getSlotSources() }
                val estimate = remember(downloadBbox) { ConvoyTileCalculator.quickEstimate(
                    downloadBbox.north, downloadBbox.south,
                    downloadBbox.east, downloadBbox.west) }
                val slots = remember(slotSources) { slotSources.map { (legacyKey, shortLabel, _) ->
                    SlotDisplayInfo(
                        slotName = legacyKey,
                        sourceName = shortLabel,
                        directory = legacyKey,
                        tileCount = 0,
                        sizeMB = 0f,
                        preSelected = true
                    )
                } }
                ConvoyDownloadConfirm(
                    estimatedTiles = estimate.tileCount,
                    estimatedMB = estimate.estimatedMB,
                    areaDesc = String.format("%.3f\u00b0N to %.3f\u00b0N", downloadBbox.south, downloadBbox.north),
                    bbox = downloadBbox,
                    slots = slots,
                    onProceed = { bbox, selectedSlots, replace ->
                        showDownloadConfirm = false
                        showDownloadPanel = false
                        // CORRIDOR-WIRING-2026-07-24: non-null hash = corridor job. ONE ENTRY
                        // PER SOURCE, matching how area jobs already appear - so
                        // progress is per-source and cancelling SAT leaves TOPO.
                        val corrHash = pendingCorridorHash
                        pendingCorridorHash = null
                        Thread {
                            if (corrHash != null) {
                                for (slot in selectedSlots) {
                                    DownloadQueueManager.enqueueCorridor(
                                        context, corrHash, slot, replace
                                    )
                                }
                            } else {
                                DownloadQueueManager.submitDownload(
                                    context, bbox.north, bbox.south, bbox.east, bbox.west,
                                    selectedSlots, replace
                                )
                            }
                        }.start()
                    },
                    // CORRIDOR-WIRING-2026-07-24: clear on cancel too, or the NEXT area
                    // download would be treated as a corridor job.
                    onCancel = { showDownloadConfirm = false; pendingCorridorHash = null },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            }
            // ── CORRIDORWIRE-2026-08-05: corridor track picker ────────────
            // Sibling to the download-confirm overlay above, same container idiom.
            // NOT gated on downloadBbox.isValid -- a corridor has no bbox, which is
            // the whole reason the picker collects its own sources.
            if (showCorridorPicker) {
                val corridorSlots = remember {
                    MapSourceManager.getSlotSources().map { (legacyKey, shortLabel, _) ->
                        SlotDisplayInfo(
                            slotName = legacyKey,
                            sourceName = shortLabel,
                            directory = legacyKey,
                            preSelected = true
                        )
                    }
                }
                ConvoyCorridorPicker(
                    tracks = corridorTracks,
                    slots = corridorSlots,
                    onProceed = { hashes, slotsSel, replace ->
                        showCorridorPicker = false
                        corridorChecked = false
                        showDownloadPanel = false
                        // ⛔ scope.launch is MAIN-dispatched; only the enqueue goes to
                        // IO. enqueueCorridorBatch derives per track per slot and must
                        // be off-main; Toast.makeText requires main and THROWS
                        // otherwise, taking the rest of the block with it silently.
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                DownloadQueueManager.enqueueCorridorBatch(
                                    context, hashes, slotsSel, replace
                                )
                            }
                            val msg = if (r.skipped > 0)
                                "Queued ${r.jobs} job(s), ${r.tiles} tiles - " +
                                "${r.skipped} track(s) skipped (no geometry)"
                            else
                                "Queued ${r.jobs} job(s), ${r.tiles} tiles"
                            android.widget.Toast.makeText(context, msg,
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    onCancel = { showCorridorPicker = false; corridorChecked = false },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            }
            // ── CORRMIGRATE-2026-08-07H: migration roll-up + permanent gate ──
            if (showMigrateGate) {
                val p = migratePreview
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { },
                    containerColor = Color(0xEE131820),
                    title = {
                        Text(
                            if (migrateDone) "Refresh started" else "Refresh map tiles",
                            color = Color(0xFFE6EDF3), fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    text = {
                        Column {
                            migrateSteps.forEach { s ->
                                Text(
                                    "\u2713 " + s,
                                    color = Color(0xFF3fb950), fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(3.dp))
                            }
                            if (migrateBusy) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (migrateProgress.isBlank()) "Working..."
                                    else migrateProgress,
                                    color = Color(0xFFd29922), fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                // CORRPROGRESS-2026-08-07K: determinate bar only
                                // when a fraction is known. An indeterminate bar
                                // during the scan would imply progress it cannot
                                // measure.
                                if (migrateFraction >= 0f) {
                                    Spacer(Modifier.height(4.dp))
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { migrateFraction },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF3fb950),
                                        trackColor = Color(0xFF30363d)
                                    )
                                }
                            }
                            if (!migrateDone && !migrateBusy && p != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Remove ${p.onDiskTotal} track tiles and rebuild " +
                                        "from the new source?",
                                    color = Color(0xFFf85149), fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Track coverage is removed and re-downloaded. " +
                                        "Tracks reappear one at a time as each finishes - " +
                                        "track coverage is queued first.\n\n" +
                                        "Your area maps stay visible the whole time. Old " +
                                        "tiles are replaced as new ones arrive.\n\n" +
                                        "Downloads continue in the background and resume " +
                                        "if the app or device restarts.",
                                    color = Color(0xFFE6EDF3), fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (migrateDone) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Downloads continue in the background and resume " +
                                        "if the app or device restarts. Your area maps " +
                                        "stay visible while tiles are replaced. Track " +
                                        "coverage returns first.",
                                    color = Color(0xFFE6EDF3), fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    },
                    confirmButton = {
                        if (migrateDone) {
                            androidx.compose.material3.TextButton(onClick = {
                                showMigrateGate = false
                                removeTrackChecked = false
                                migrateDone = false
                                migrateSteps = listOf()
                                migratePreview = null
                            }) {
                                Text("DONE", color = Color(0xFF3fb950), fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold)
                            }
                        } else if (!migrateBusy && p != null) {
                            androidx.compose.material3.TextButton(onClick = {
                                // PAST THIS POINT THERE ARE NO MORE GATES.
                                // Stopping after the delete would strand the user
                                // with removed coverage and nothing queued.
                                scope.launch {
                                    migrateBusy = true
                                    val hashes = withContext(Dispatchers.IO) {
                                        SpatialDbManager.allTrackGeomHashes().map { it.first }
                                    }
                                    val del = withContext(Dispatchers.IO) {
                                        ConvoyCorridorDelete.deleteAllTrackCorridors(
                                            context, "SAT"
                                        ) { done, total, name ->
                                            // ⛔ This lambda runs on IO. Compose
                                            // state must be written on main --
                                            // a direct write here is the fault
                                            // that killed convoy's artifact
                                            // draw twice.
                                            scope.launch(Dispatchers.Main) {
                                                migrateProgress =
                                                    "Deleting track $done of $total - $name"
                                                migrateFraction =
                                                    if (total > 0) done.toFloat() / total else -1f
                                            }
                                        }
                                    }
                                    migrateProgress = ""
                                    migrateFraction = -1f
                                    migrateSteps = migrateSteps + (
                                        "Deleted ${del.tilesRemoved} tiles from " +
                                            "${del.tracksProcessed} tracks")
                                    val batch = withContext(Dispatchers.IO) {
                                        DownloadQueueManager.enqueueCorridorBatch(
                                            context, hashes, listOf("SAT"), true
                                        )
                                    }
                                    migrateSteps = migrateSteps + (
                                        "Queued ${batch.jobs} track corridors " +
                                            "(${batch.tiles} tiles)")
                                    val cells = withContext(Dispatchers.IO) {
                                        DownloadQueueManager.enqueueRefresh(
                                            context, "SAT", "SAT"
                                        )
                                    }
                                    migrateSteps = migrateSteps + "Queued area refresh ($cells cells)"
                                    withContext(Dispatchers.IO) {
                                        DownloadQueueManager.resumeQueue()
                                    }
                                    migrateSteps = migrateSteps + "Queue released"
                                    migrateBusy = false
                                    migrateDone = true
                                }
                            }) {
                                Text("PROCEED", color = Color(0xFFf85149), fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    dismissButton = {
                        if (!migrateDone && !migrateBusy) {
                            androidx.compose.material3.TextButton(onClick = {
                                // Nothing has been destroyed. Release the queue so
                                // backing out does not leave it held.
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        DownloadQueueManager.resumeQueue()
                                    }
                                    showMigrateGate = false
                                    removeTrackChecked = false
                                    migrateSteps = listOf()
                                    migratePreview = null
                                }
                            }) {
                                Text("CANCEL", color = Color(0xFF4A6080), fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                )
            }

            // ── Auto show/hide download overlays when panel opens/closes ──
            LaunchedEffect(showDownloadPanel) {
                if (showDownloadPanel) {
                    pmDownloadedOn = true
                    // OSM-C3B-AREA-2026-07-29: derive the preselect from disk
                    // at panel open (R5 -- derivation runs at MOMENTS). Covers
                    // arriving here by any route, not just from row 3.
                    val await = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        val s = OsmImportStage.statesInFlight(context).firstOrNull()
                        if (s != null &&
                            OsmImportLedger.pendingScope(context, s) != null &&
                            OsmImportLedger.pendingBbox(context, s) == null
                        ) s else null
                    }
                    if (await != null) {
                        osmAwaitingSlug = await
                        panelOsmChecked = true
                    }
                } else {
                    pmDownloadedOn = false
                }
            }
            // OSM-C3B-AREA-2026-07-29: THE DRAW COMPLETING IS THE SUBMIT.
            //
            // There is no OSM execute button. Fred 07-29: "relaunch import and
            // close area selection when the area is processed." onAreaSelected
            // is a @JavascriptInterface with no slug and no scope, and disk I/O
            // there would run on the JS thread -- so it keeps doing only what it
            // does today (set downloadBbox) and the reaction happens here.
            LaunchedEffect(downloadBbox, panelOsmChecked, osmAwaitingSlug) {
                val slug = osmAwaitingSlug
                if (slug != null && panelOsmChecked && downloadBbox.isValid) {
                    val ok = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        OsmImportLedger.setPendingBbox(
                            context, slug,
                            s = downloadBbox.south,
                            w = downloadBbox.west,
                            n = downloadBbox.north,
                            e = downloadBbox.east
                        )
                        // Read back. The gate must show what LANDED.
                        OsmImportLedger.pendingBbox(context, slug) != null
                    }
                    android.util.Log.i(
                        "OsmArea",
                        "drawn bbox written for $slug ok=$ok " +
                            "S${downloadBbox.south} W${downloadBbox.west} " +
                            "N${downloadBbox.north} E${downloadBbox.east}"
                    )
                    // Close the draw panel, reopen the import panel. Row 3 now
                    // finds a bbox and gates.
                    osmAwaitingSlug = null
                    panelOsmChecked = false
                    showDownloadPanel = false
                    showOsmPanel = true
                }
            }
            // ── Floating download execute button (outside panel for landscape) ──
            if (showDownloadPanel && downloadBbox.isValid &&
                (panelTilesChecked || panelTrailsChecked || panelRemoveTilesChecked)) {
                val execLabel = if (panelRemoveTilesChecked && !panelTilesChecked && !panelTrailsChecked)
                    "Remove Tiles" else "Download Selected"
                val execColor = if (panelRemoveTilesChecked && !panelTilesChecked && !panelTrailsChecked)
                    Color(0xFFf85149) else Color(0xFF3fb950)
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xEE131820),
                    shadowElevation = 8.dp
                ) {
                    Text(
                        execLabel,
                        color = execColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                if (panelTrailsChecked && downloadBbox.isValid) {
                                    // TRAILSELECT-2026-08-21D: THE EXIT THAT ACTUALLY FIRES.
                                    // Patch C replaced the panel's own callback; this
                                    // EXECUTE-label path is the second exit to the same
                                    // old screen and was still live. Both now run the
                                    // area import -- every intersecting source, no
                                    // selection step (design §4: no checklist, ever).
                                    // ⚠ launchMode/writePendingArea are vestigial here;
                                    // remove in the cleanup pass after device verify.
                                    android.util.Log.i("DownloadPanel",
                                        "AREA IMPORT (exec): S=${downloadBbox.south} " +
                                        "W=${downloadBbox.west} N=${downloadBbox.north} " +
                                        "E=${downloadBbox.east}")
                                    areaImportBbox = doubleArrayOf(
                                        downloadBbox.south, downloadBbox.west,
                                        downloadBbox.north, downloadBbox.east)
                                    showDownloadPanel = false
                                }
                                if (panelTilesChecked && downloadBbox.isValid) {
                                    showDownloadConfirm = true; showDownloadPanel = false
                                }
                                // DELETE-AREA-2026-07-25: the red "Remove Tiles" label
                                // existed but had no handler - the button did nothing.
                                if (panelRemoveTilesChecked && downloadBbox.isValid) {
                                    showRemoveTilesConfirm = true; showDownloadPanel = false
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                    )
                }
            }
            // DELETE-AREA-2026-07-25: gated confirm. States the area and an upper
            // bound on what will go. The count shown is the GEOMETRY count
            // (tiles the box covers x layers) - what COULD be there - because a
            // live COUNT(*) on the main thread would jank the UI. The worker
            // reports what was actually removed.
            if (showRemoveTilesConfirm && downloadBbox.isValid) {
                val delTiles = ConvoyTileCalculator
                    .calculateTiles(downloadBbox.north, downloadBbox.south,
                                    downloadBbox.east, downloadBbox.west).size
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showRemoveTilesConfirm = false },
                    title = { Text("Remove tiles?", fontFamily = FontFamily.Monospace) },
                    text = {
                        Text(
                            "Delete downloaded map tiles in this area from ALL sources.\n\n" +
                            String.format("%.3f\u00b0N to %.3f\u00b0N\n",
                                downloadBbox.south, downloadBbox.north) +
                            "Up to " + delTiles + " tiles per layer.\n\n" +
                            "This cannot be undone.",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp
                        )
                    },
                    confirmButton = {
                        Text("REMOVE TILES",
                            color = Color(0xFFf85149),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                showRemoveTilesConfirm = false
                                panelRemoveTilesChecked = false
                                val bb = downloadBbox
                                Thread {
                                    DownloadQueueManager.submitDelete(
                                        context, bb.north, bb.south, bb.east, bb.west)
                                }.start()
                            }.padding(12.dp))
                    },
                    dismissButton = {
                        Text("CANCEL",
                            color = Color(0xFF8B949E),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                showRemoveTilesConfirm = false
                            }.padding(12.dp))
                    },
                    containerColor = Color(0xFF131820)
                )
            }
            // ── Download panel (above FAB) ────────────────────────────────
            if (showDownloadPanel) {
                ConvoyDownloadPanel(
                    bbox = downloadBbox,
                    tilesChecked = panelTilesChecked,
                    onTilesCheckedChange = { panelTilesChecked = it },
                    // CORRIDORWIRE-2026-08-05: corridor routes from its own checkbox
                    // (the OSM idiom), not through onExecuteDownload -- that
                    // callback's booleans are all bbox-driven and a corridor has none.
                    corridorChecked = corridorChecked,
                    onCorridorCheckedChange = { corridorChecked = it },
                    onSelectCorridorTracks = {
                        scope.launch {
                            val rows = withContext(Dispatchers.IO) {
                                SpatialDbManager.queryAllTracksForCorridor()
                            }
                            corridorTracks = trackPickRowsFrom(rows)
                            if (corridorTracks.isEmpty()) {
                                // Same shape as the ":1136" empty-result toast: an
                                // empty list is a real state, not an error.
                                android.widget.Toast.makeText(context,
                                    "No tracks available - record or import a track first",
                                    android.widget.Toast.LENGTH_LONG).show()
                                corridorChecked = false
                            } else {
                                showCorridorPicker = true
                            }
                        }
                    },
                    trailsChecked = panelTrailsChecked,
                    onTrailsCheckedChange = { panelTrailsChecked = it },
                    removeTilesChecked = panelRemoveTilesChecked,
                    onRemoveTilesCheckedChange = { panelRemoveTilesChecked = it },
                    // REMOVETRACK-PREVIEW-2026-08-07F
                    // State is remember{} (declared below the panel), NOT lifted
                    // into MapStateStore like removeTilesChecked is. A PERSISTED
                    // checkbox that launches an action would re-fire on every
                    // panel load -- this is an action, not a state, so it unticks
                    // itself when the preview returns.
                    recreateSourceChecked = recreateSourceChecked,
                    onRecreateSourceCheckedChange = { ticked ->
                        recreateSourceChecked = ticked
                        if (!ticked) return@ConvoyDownloadPanel
                        // RECREATE-2026-08-11A: SCAN ONLY. Reads what the store holds and
                        // reports the jobs it WOULD build. Queues nothing.
                        //
                        // Off the main thread deliberately: a 15 GB store can
                        // hold millions of rows, and walking the queue on the UI
                        // thread is what ANR'd the 3,287-job cancel on 08-11.
                        // RECREATE-2026-08-11F: open the panel and let the effect above scan.
                        // The slot starts at whatever the map is showing and the
                        // user can change it in the panel.
                        recreateSlot = ConvoyConfig.ACTIVE_TILE_SOURCE
                        showRecreateResults = true
                        recreateSourceChecked = false
                    },
                    removeTrackChecked = removeTrackChecked,
                    onRemoveTrackCheckedChange = { ticked ->
                        removeTrackChecked = ticked
                        if (ticked) {
                            // CORRMIGRATE-2026-08-07H step 1-3: hold, clear, scan.
                            // All reversible -- nothing is destroyed until the gate.
                            scope.launch {
                                migrateBusy = true
                                // CORRPROGRESS-2026-08-07K: the scan measured
                                // 33-61s on a 5.3 GB store. Say so, or the
                                // dialog looks stalled before it even starts.
                                migrateProgress = "Scanning tracks - this can take a minute"
                                migrateSteps = listOf("Holding queue and clearing pending work")
                                val preview = withContext(Dispatchers.IO) {
                                    DownloadQueueManager.holdQueue()
                                    DownloadQueueManager.cancelAll()
                                    // The slot IS the store: column 1 is SAT
                                    // whatever source is assigned to it.
                                    ConvoyCorridorDelete.previewAllTracks(context, "SAT")
                                }
                                migratePreview = preview
                                migrateSteps = listOf(
                                    "Queue held, pending work cleared (history kept)",
                                    "Scanned ${preview.tracks.size} tracks - " +
                                        "${preview.onDiskTotal} tiles on disk"
                                )
                                migrateProgress = ""
                                migrateBusy = false
                                showMigrateGate = true
                            }
                        }
                    },
                    osmChecked = panelOsmChecked,
                    onOsmCheckedChange = { panelOsmChecked = it },
                    flyoverZoom = panelFlyoverZoom,
                    onFlyoverZoomChange = { panelFlyoverZoom = it; ConvoyConfig.SEARCH_FLY_ZOOM = it },
                    tileEstimate = if (downloadBbox.isValid) {
                        val est = ConvoyTileCalculator.quickEstimate(
                            downloadBbox.north, downloadBbox.south,
                            downloadBbox.east, downloadBbox.west)
                        "~${est.tileCount} tiles \u00b7 ${est.estimatedMB} MB"
                    } else "",
                    trailSourceCount = 0,
                    isDrawing = isDrawingArea,
                    onDrawArea = {
                        isDrawingArea = true
                        webViewRef?.evaluateJavascript("activateDrawMode()", null)
                    },
                    onClearArea = {
                        downloadBbox = DownloadBbox()
                        webViewRef?.evaluateJavascript("clearAreaBoundary()", null)
                    },
                    onExecuteDownload = { tiles, trails, removeTiles ->
                        android.util.Log.i("DownloadPanel", "onExecuteDownload: tiles=$tiles trails=$trails remove=$removeTiles bbox.valid=${downloadBbox.isValid} n=${downloadBbox.north} s=${downloadBbox.south}")
                        if (tiles && downloadBbox.isValid) {
                            showDownloadConfirm = true; showDownloadPanel = false
                        }
                    },
                    onNavigateToTrailSources = { bbox ->
                        android.util.Log.i("DownloadPanel", "onNavigateToTrailSources: n=${bbox.north} s=${bbox.south} e=${bbox.east} w=${bbox.west} valid=${bbox.isValid}")
                        TrailImporter.launchMode = TrailImporter.LaunchMode.BY_AREA
                        TrailImporter.writePendingArea(bbox.north, bbox.south, bbox.east, bbox.west)
                        // AREAWIRE-2026-08-21C: THE DEVIATION POINT. The bbox handoff above
                        // is unchanged and already proven. What changes is the
                        // destination: no source SELECTION screen. Every source that
                        // intersects the box runs, because running them all is what
                        // makes the upsert enrichment work (design spec §4).
                        // ⚠ writePendingArea/launchMode above are now vestigial for
                        // this path -- remove in the cleanup pass, AFTER device verify.
                        android.util.Log.i("DownloadPanel",
                            "AREA IMPORT: launching for bbox S=${bbox.south} W=${bbox.west} " +
                            "N=${bbox.north} E=${bbox.east}")
                        areaImportBbox = doubleArrayOf(
                            bbox.south, bbox.west, bbox.north, bbox.east)
                        showDownloadPanel = false
                    },

                    onShowDownloadedMaps = { show ->
                        if (show) {
                            if (!scanningDownloaded) {
                                scanningDownloaded = true
                                val wv = webViewRef
                                if (wv != null) {
                                    val tilesDir = java.io.File(ConvoyConfig.TILE_DIR, "SAT/14")
                                    Thread {
                                        val bounds = mutableListOf<String>()
                                        run {
                                            // OVERLAYZ16-2026-08-12E: z16, and MERGED INTO RUNS.
                                            //
                                            // Was z14. The funnel made that
                                            // misleading: at z14 the buffer is
                                            // 4.87 miles either side, so it drew
                                            // a ten-mile swath reading "I have
                                            // this whole area" when at z18 you
                                            // hold 0.6. It showed the WIDEST
                                            // part of the funnel as the coverage.
                                            //
                                            // z16 answers "where do I have
                                            // DETAIL" instead of "where do I
                                            // have something" - the question
                                            // that matters when planning an area.
                                            //
                                            // ⭐ MERGING IS WHAT MAKES z16
                                            // VIABLE, not an optimisation.
                                            // Measured: 3,139 rects at z14 but
                                            // 33,650 at z16, and Leaflet degrades
                                            // past ~10-20k. The band is 9 tiles
                                            // wide, so one rect per row of
                                            // contiguous x gives ~3,700.
                                            val z = 16; val n = 1 shl z
                                            val byRow = HashMap<Long, MutableList<Long>>()
                                            for ((x, y) in MBTilesStore.xyAtZoom("SAT", z)) {
                                                byRow.getOrPut(y) { ArrayList() }.add(x)
                                            }
                                            for ((ry, xs) in byRow) {
                                                xs.sort()
                                                var i = 0
                                                while (i < xs.size) {
                                                    var j = i
                                                    while (j + 1 < xs.size && xs[j + 1] == xs[j] + 1) j++
                                                    val tN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * ry / n))))
                                                    val tS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (ry + 1) / n))))
                                                    val tW = xs[i].toDouble() / n * 360.0 - 180.0
                                                    val tE = (xs[j] + 1).toDouble() / n * 360.0 - 180.0
                                                    bounds.add("{\"n\":$tN,\"s\":$tS,\"e\":$tE,\"w\":$tW}")
                                                    i = j + 1
                                                }
                                            }
                                        }
                                        android.util.Log.i("Overlay",
                                            "OVERLAYZ16-2026-08-12E z16 merged -> ${bounds.size} rect(s)")
                                        val json = "[" + bounds.joinToString(",") + "]"
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            wv.evaluateJavascript("showDownloadedAreas($json)", null)
                                            showDownloaded = true
                                            scanningDownloaded = false
                                        }
                                    }.start()
                                }
                            }
                        } else {
                            showDownloaded = false
                            webViewRef?.evaluateJavascript("clearDownloadedAreas()", null)
                        }
                    },
                    onShowMapsInQueue = { show ->
                        val wv = webViewRef
                        if (show && wv != null) {
                            val q = DownloadQueueManager.queue.value
                            val pending = q.filter { it.status == QueueStatus.DOWNLOADING || it.status == QueueStatus.QUEUED }
                            val bounds = pending.map { e ->
                                // OVERLAYZ16-2026-08-12E: geomHash non-empty means CORRIDOR, and a
                                // corridor's bbox is the box around a WINDING TRACK -
                                // a huge rectangle for a job that fetches a narrow band
                                // inside it. Flagged so it can be drawn as an estimate
                                // rather than as coverage.
                                "{" + "\"n\":" + e.north + ",\"s\":" + e.south + ",\"e\":" + e.east + ",\"w\":" + e.west + ",\"corridor\":" + (e.geomHash.isNotEmpty()) + ",\"label\":\"" + e.label.replace("\"", "") + "\"}"
                            }
                            val json = "[" + bounds.joinToString(",") + "]"
                            wv.evaluateJavascript("showQueuedAreas(" + json + ")", null)
                        } else {
                            webViewRef?.evaluateJavascript("clearQueuedAreas()", null)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 80.dp)
                )
            }

            // ── Floating draggable download FAB ───────────────────────────
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .offset { IntOffset(fabOffsetX.roundToInt(), fabOffsetY.roundToInt()) }
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            fabOffsetX += dragAmount.x
                            fabOffsetY += dragAmount.y
                        }
                    }
                    .clickable { showDownloadPanel = !showDownloadPanel },
                shape = RoundedCornerShape(24.dp),
                color = if (showDownloadPanel) Color(0xFF2E75B6) else Color(0xFF1A2E4A),
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    // === MAPSFAB-2026-08-17B ===
                    // The word above the arrow. This button opens the transfers panel, whose
                    // header flips by mode -- so naming the SUBJECT covers both directions,
                    // and the arrow already says "in and out".
                    // The circle, its size, its offset and its drag handling are deliberately
                    // untouched: this FAB is dragged around the map by hand, so its bounds
                    // must not change. It is the one control that keeps an icon shape.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("MAPS", color = Color.White, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 10.sp)
                        Text("↕", color = Color.White, fontSize = 14.sp,
                            lineHeight = 15.sp)
                    }
                }
            }
        }

        // -- Legend (key button + popup; popup escapes Column width) --
        Box(
            modifier = Modifier
                .align(Alignment.Start)
                .padding(start = 10.dp, bottom = 12.dp)
                .navigationBarsPadding()
        ) {
            // NOFAB-2026-09-02: the key ICON is gone. Map Keys is a word in
            // the launcher column now, and two ways into one panel is one too
            // many. ⭐ Fred, 09-02: "there is less issues aligning text" -- the
            // same reason PLAINCTRL-2026-08-17 chose words over glyphs.
            // ⚠ The Box stays: both popups live in it, and it exists so a popup
            // can escape the Column's width.
            if (showTrailFilter) {
                Popup(onDismissRequest = { showTrailFilter = false }) {
                    TrailFilterPanel(
                        onDismiss = { showTrailFilter = false },
                        onFilterChanged = {
                            webViewRef?.evaluateJavascript(
                                "try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}",
                                null)
                        }
                    )
                }
            }
            if (legendExpanded) {
                // MAPKEYS-2026-09-01: the hardcoded legend is gone. One shared
                // composable now serves BOTH maps -- the old one lived only
                // here on the planner and the convoy map had none at all.
                // ⛔ It also LIED: it said orange was "Hiking & Biking" while
                // the map drew motorized in that colour, and trailColor never
                // had a case for '2' at all, so 7,513 hiking-and-biking rows
                // always drew cyan. A key that cannot disagree with the map is
                // the point of sharing one definition.
                Popup(onDismissRequest = { legendExpanded = false }) {
                    MapKeysPanel(
                        onDismiss = { legendExpanded = false },
                        onStyleChanged = {
                            webViewRef?.evaluateJavascript("setTrailStyles(" + TrailFilterState.styleJson() + ")", null)
                        },
                        // MAPKEYS-2026-09-01: same refetch Work with Map
                        // Features uses -- a synthetic viewport event, so
                        // the next query picks up the new predicate.
                        onFilterChanged = {
                            webViewRef?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
                        }
                    )
                }
            }
        }
    }
    // ── Track action menu integration ──
    TrackActionDialog(
        file = actionTarget,
        onDismiss = { actionTarget = null },
        onRename = { renameTarget = actionTarget; actionTarget = null },
        onDelete = { deleteTarget = actionTarget; actionTarget = null },
        onShare = {
            val f = actionTarget; actionTarget = null
            f?.let { ConvoyTrackOps.shareTrack(context, it) }
        },
        onMoveToDownloads = {
            val f = actionTarget; actionTarget = null
            f?.let {
                scope.launch {
                    val ok = ConvoyTrackOps.copyToDownloads(it)
                    actionStatusMsg = if (ok) "Copied to Downloads" else "Copy failed"
                }
            }
        },
        onFixDate = {
            val f = actionTarget; actionTarget = null
            f?.let {
                scope.launch {
                    val ok = ConvoyTrackOps.fixDateFromContent(it)
                    actionStatusMsg = if (ok) "Date updated" else "No <time> found"
                    refreshTracks()
                }
            }
        }
    )
    RenameTrackDialog(
        file = renameTarget,
        onDismiss = { renameTarget = null },
        onConfirm = { newName ->
            val f = renameTarget; renameTarget = null
            f?.let {
                unloadIfLoaded(it.name)
                scope.launch {
                    val result = ConvoyTrackOps.renameTrack(it, newName)
                    actionStatusMsg = when (result) {
                        is ConvoyTrackOps.RenameResult.Success -> "Renamed"
                        is ConvoyTrackOps.RenameResult.NameExists -> "Name already exists"
                        is ConvoyTrackOps.RenameResult.Failed -> "Rename failed"
                    }
                    refreshTracks()
                }
            }
        }
    )
    DeleteTrackDialog(
        file = deleteTarget,
        onDismiss = { deleteTarget = null },
        onConfirm = {
            val f = deleteTarget; deleteTarget = null
            f?.let {
                unloadIfLoaded(it.name)
                scope.launch {
                    val ok = ConvoyTrackOps.deleteTrack(it)
                    actionStatusMsg = if (ok) "Deleted" else "Delete failed"
                    refreshTracks()
                }
            }
        }
    )
    actionStatusMsg?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            actionStatusMsg = null
        }
    }
}

// ── Track file helpers ─────────────────────────────────────────────
private fun scanTrackDir(context: android.content.Context): List<String> {
    val dir = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
        "my_tracks"
    )
    if (!dir.exists()) return emptyList()
    val files = dir.listFiles()
        ?.filter { f ->
            val ext = f.extension.lowercase()
            (ext == "kml" || ext == "gpx") &&
            !f.name.startsWith(".") &&
            !f.name.startsWith("convoy_track_temp")
        }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.name }
        ?: emptyList()
    android.util.Log.d("MapViewer", "scanTrackDir found ${files.size} tracks")
    return files
}

private fun loadTrackOnMap(
    context: android.content.Context,
    fileName: String,
    color: String,
    webView: android.webkit.WebView?
) {
    // ANR FIX: file read + parse on IO, JS push on Main
    kotlinx.coroutines.MainScope().launch {
        try {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOCUMENTS
                    ), "my_tracks"
                )
                val file = java.io.File(dir, fileName)
                if (!file.exists()) return@withContext null
                val text = file.readText()
                val coords = if (fileName.lowercase().endsWith(".gpx")) parseGpx(text) else parseKml(text)
                if (coords.isEmpty()) return@withContext null
                val json = coords.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
                Pair(json, coords.size)
            }
            if (result != null) {
                val safe = fileName.replace("'", "\\'")
                webView?.evaluateJavascript("loadTrackFile('$safe', '${result.first}', '$color')", null)
                android.util.Log.d("MapViewer", "Loaded $fileName: ${result.second} points")
            }
        } catch (e: Exception) {
            android.util.Log.e("MapViewer", "Track load error $fileName: ${e.message}")
        }
    }
}

private fun parseKml(text: String): List<Pair<Double, Double>> {
    val coords = mutableListOf<Pair<Double, Double>>()
    val pattern = Regex("""<coordinates>([\s\S]*?)</coordinates>""")
    pattern.findAll(text).forEach { match ->
        match.groupValues[1].trim().lines().forEach { line ->
            val parts = line.trim().split(",")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lon != null && lat != null && lat != 0.0 && lon != 0.0) {
                    coords.add(Pair(lat, lon))
                }
            }
        }
    }
    return coords
}

private fun parseGpx(text: String): List<Pair<Double, Double>> {
    val coords = mutableListOf<Pair<Double, Double>>()
    val pattern = Regex("""<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)"""")
    pattern.findAll(text).forEach { match ->
        val lat = match.groupValues[1].toDoubleOrNull()
        val lon = match.groupValues[2].toDoubleOrNull()
        if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
            coords.add(Pair(lat, lon))
        }
    }
    return coords
}

@Composable
private fun LegendItem(color: Color, label: String, style: String = "line") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(width = 14.dp, height = 8.dp)) {
            val y = size.height / 2
            when (style) {
                "line" -> drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 3f)
                "dash" -> {
                    val d = size.width / 4
                    drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(d, y), strokeWidth = 3f)
                    drawLine(color, androidx.compose.ui.geometry.Offset(d * 2, y), androidx.compose.ui.geometry.Offset(d * 3, y), strokeWidth = 3f)
                }
                "pin" -> {
                    drawCircle(color, radius = size.minDimension / 2)
                    drawCircle(Color.White, radius = size.minDimension / 4)
                }
            }
        }
        Spacer(Modifier.width(3.dp))
        Text(label, color = Color(0xFFAABBCC), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}
