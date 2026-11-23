package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.net.GtNetworkGenerator
import com.grouptrack.shared.ride.GtRideJson
import com.grouptrack.shared.ride.GtRideStore
import com.grouptrack.shared.ride.RideFiles
import com.grouptrack.shared.ride.RideNet
import com.grouptrack.shared.ride.RouteSummary
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtFmt
import com.grouptrack.shared.text.GtTime
import kotlinx.serialization.json.JsonObject

/**
 * RIDECREATE-2026-09-22 — create a ride. FIRST PASS (shell).
 *
 * TWO ENTRY PATHS, one screen:
 *   - from the ride panel: opens empty, the rider picks a route by name
 *   - from the PLANNER's route detail panel: the route arrives already chosen
 * (CODE RULE 1: routeId is optional because BOTH callers are real -- the ride
 *  panel legitimately opens with no route, the planner legitimately supplies one.)
 *
 * NO MAP HERE. The planner is the map; this screen shows a vignette of the chosen
 * route -- drawn from stored geometry, no tiles, instant and offline.
 *
 * NOT YET: network (waits on the transport work), organization and leader setup
 * (backfilled later), the .convoy file, enrollments.
 */
/**
 * RIDESCONV-2026-11-23: what the ride form needs from its HOST. Every entry is a PLATFORM seam --
 * none of them is a redesign, and the screen's body is the Android original.
 *
 * NOT here, because the shared code already provides them:
 *   the route's recipe and notes  -> GtRideStore.env.spatial.routeRecipe / .routeNotes (org.json
 *                                   TEXT, parsed with GtOrgJson -- both platforms implement
 *                                   RideSpatial already)
 *   the zip code's STORE          -> GtSettings.getZipCode()
 *   every ride read and write     -> GtRideStore
 *   the default channel           -> GtNetworkGenerator.DEFAULT_CHANNEL
 *
 * CODE RULE 1: no defaults -- a host that cannot do one of these has to say so out loud.
 * ⚠ trailheadsNear and setRouteAnchor belong in RideSpatial by that interface's own
 * description ("the SpatialDbManager helpers the ride package uses"). They are bridge entries
 * HERE only so this pass stays a pure conversion; carded to move into RideSpatial.
 */
// RIDESCONVFIX-2026-11-23
class RideCreateBridge(
    /** The rides folder. GtRideJson.save takes it. Android: GtRidesAndroid.Files(context). */
    val files: RideFiles,
    /** HELP -- GtPanelChrome: two of the three standard controls need the host (Android's issue
     *  capture needs LocalView). GtCloseButton is shared and is called directly. */
    val help: @Composable (String) -> Unit,
    /** A transient message. Android: Toast.makeText(...).show(). */
    val toast: (String) -> Unit,
    /** The zip the rider's SESSION already holds. GtSession is an instance class over the
     *  platform's own settings store (Android: GtSettingsAndroid(context)), so the host owns it.
     *  Android called ConvoySessionManager.getZipCode(context) here, unchanged. */
    val storedZipCode: () -> String,
    /** ZIPFROMTH-2026-09-24: the zip DERIVED from the trailhead, never typed. Android: Geocoder;
     *  iOS: CLGeocoder. CODE RULE 1: null is a real answer -- no connection, or no postal code --
     *  and the form then leaves the zip empty, which is not a completion rule. */
    val zipFor: suspend (lat: Double, lon: Double) -> String?,
    /** RIDEPREVIEW-2026-09-24: the captured planner map. The bitmap is a PLATFORM image, so the
     *  host draws it; [fallback] is the shared vignette, drawn when the host has no picture. */
    val routePreview: @Composable (routeId: String, fallback: @Composable () -> Unit) -> Unit,
    /** RIDEPREVIEW-2026-09-24: the route's captured picture becomes the new ride's. */
    val adoptPreview: (routeId: String, rideId: String) -> Unit,
    /** ONEDEFAULT-2026-09-26: re-read the GroupTrack default radio values before a ride is added.
     *  Android reads its asset; iOS's RideNetworks already re-reads them inside create(). */
    val refreshMeshDefaults: () -> Unit,
)

@Composable
fun ConvoyRideCreateScreen(
    b: RideCreateBridge,
    initialRouteId: String? = null,
    // EDITRIDE-2026-09-27 (Fred): CODE RULE 1 -- null is a real choice: a NEW ride (every existing caller); a ride id opens
    // the form FILLED IN from that ride, and SAVE updates it. Only the Rides list's Edit passes one.
    editRideId: String? = null,
    onRideCreated: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    var rideName by remember { mutableStateOf("") }
    var rideDate by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }   // CALDATE-2026-09-24
    var showTimePicker by remember { mutableStateOf(false) }   // RIDETIME-2026-11-23
    var startTime by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // FORMTRIM-2026-09-27 (Fred): true when the ride's description came from the route -- the field is then not shown.
    var routeHasDescription by remember { mutableStateOf(false) }
    var zipCode by remember { mutableStateOf("") }
    // RIDENET-2026-09-27: public by default (isPrivate off); the organizer's network by default (newNetworkForRide off).
    var isPrivate by remember { mutableStateOf(false) }
    var newNetworkForRide by remember { mutableStateOf(false) }
    // DEFAULTNET-2026-10-10 (Fred): the GroupTrack default network is the first choice and the DEFAULT (THREENET: the
    // creator's own network and a private ride network follow it).
    var useDefaultNetwork by remember { mutableStateOf(true) }
    // FORMLAYOUT-2026-09-27: generated when you toggle to a new network; used only when the ride is added.
    // CODE RULE 1: null = not generated (the organizer network is chosen, or it has not been toggled yet).
    var rideNetwork by remember { mutableStateOf<RideNet?>(null) }
    // EDITRIDE-2026-09-27 (Fred): edit mode -- the form opens FILLED IN from the ride; a SENT ride may only change its date.
    val editSent = remember(editRideId) { editRideId != null && GtRideStore.isDistributed(editRideId) }
    LaunchedEffect(editRideId) {
        val e = editRideId?.let { GtRideStore.rideForEdit(it) } ?: return@LaunchedEffect
        rideName = e.name; rideDate = e.date; startTime = e.startTime; description = e.description
        isPrivate = !e.isPublic; newNetworkForRide = e.unique
        useDefaultNetwork = e.configId == GtNetworkGenerator.DEFAULT_CHANNEL   // DEFAULTNET-2026-10-10
        if (e.unique) rideNetwork = e.configId?.let { GtRideStore.env.networks.load(it) }
    }
    // CODE RULE 1: null is a real state -- this rider has no network yet; their first ride creates it.
    // RIDENETWORDS2-2026-09-27: the rider's own network is made when the form opens, so it always exists and has a name.
    val myNetworkName = remember { GtRideStore.ensureMyNetworkName() }

    var routes by remember { mutableStateOf<List<RouteSummary>>(emptyList()) }
    var routeId by remember { mutableStateOf(initialRouteId ?: "") }
    var routeName by remember { mutableStateOf("") }
    var routePts by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(initialRouteId == null) }
    var status by remember { mutableStateOf("") }
    // RIDETH-2026-09-23 (Fred): a ride cannot be built without a trailhead.
    var thChoices by remember { mutableStateOf<List<GtRead.Trailhead>>(emptyList()) }
    var trailhead by remember { mutableStateOf<GtRead.Trailhead?>(null) }
    // THTEXT-2026-09-27 (Fred): INTERIM -- when no trailhead waypoint is near the route's start, the organizer describes
    // it; it goes at the FRONT of the ride's description. Goes away once every route carries its trailhead.
    var thText by remember { mutableStateOf("") }
    var thFromRecipe by remember { mutableStateOf(false) }

    // ⚠ RIDESCONV-2026-11-23: DEAD LOCAL -- nothing in this screen reads `me` (measured 11-23).
    // Kept so the conversion changes no behaviour; flagged for the dead-code sweep, whose own rule
    // is verify-then-remove with a placeholder, not a silent drop.
    val me = remember { GtRideStore.env.profile() }

    // RIDESEARCH-2026-10-24: the map area from Create a ride (south, north, west, east); empty = opened another way -- every route.
    val area = remember { com.geeksville.mesh.grouptrack.GtRideStart.takeArea() }
    var showAll by remember { mutableStateOf(area.isEmpty()) }
    LaunchedEffect(Unit) {
        routes = GtRideStore.listRoutes(if (area.isEmpty()) 200 else 5000)   // RIDESEARCH-2026-10-24: an area may hold older routes
        zipCode = b.storedZipCode()
    }
    LaunchedEffect(routeId) {
        if (routeId.isBlank()) {
            routePts = emptyList(); thChoices = emptyList(); trailhead = null; thFromRecipe = false
            return@LaunchedEffect
        }
        routeName = routes.firstOrNull { it.routeId == routeId }?.name ?: routeName
        routePts = GtRideStore.routeGeometry(routeId)
            ?.let { GtRideStore.parseWktLine(it) } ?: emptyList()
        // Recipe anchor first; otherwise trailheads within 1/2 mile of the FIRST point.
        // ⚠ parseWktLine gives (LON, LAT) -- WKT order.
        val start = routePts.firstOrNull()
        val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // RIDESCONV-2026-11-23: RideSpatial.routeRecipe returns 2.7e's org.json TEXT, and
            // GtOrgJson answers it the org.json way -- same values, no platform JSON.
            val rec = GtRideStore.env.spatial.routeRecipe(routeId)
                ?.let { GtOrgJson.parse(it) as? JsonObject }
            if (rec != null && rec["anchorLat"] != null && rec["anchorLon"] != null) {
                Pair(true, listOf(GtRead.Trailhead(
                    "", GtOrgJson.optString(rec, "anchorName", "").ifBlank { "Trailhead" },
                    GtOrgJson.optDouble(rec, "anchorLat"), GtOrgJson.optDouble(rec, "anchorLon"), 0.0)))
            } else {
                Pair(false, start?.let { spatialRead()?.trailheadsNear(it.second, it.first) } ?: emptyList())
            }
        }
        thFromRecipe = resolved.first
        thChoices = resolved.second
        trailhead = thChoices.singleOrNull()
        if (rideName.isBlank() && routeName.isNotBlank()) rideName = routeName
        // HEADLINE-2026-09-24 (Fred): the description starts with the route's narrative HEADLINE -- one sentence
        // that already states distance and duration. Built routes only; never overwrites what was typed.
        if (description.isBlank()) {
            // FORMTRIM-2026-09-27 (Fred): the ride's description IS the route's -- its headline, plus (a converted track)
            // the rider's own description. The trail-data credit only for planner routes (a converted track is the
            // rider's own recording). A route with no description shows the (required) field instead.
            val notes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                GtRideStore.env.spatial.routeNotes(routeId)?.let { GtOrgJson.parse(it) as? JsonObject }
            }
            val nar = notes?.get("narrative") as? JsonObject
            val headline = GtOrgJson.optString(nar, "headline", "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val about = GtOrgJson.optString(nar, "description", "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val converted = GtOrgJson.optString(notes, "source", "") == "convertroute"
            if (headline != null && description.isBlank()) {
                description = listOfNotNull(headline, about).joinToString("\n\n") +
                    (if (converted) "" else "\n\nTrail data \u00a9 OpenStreetMap contributors")
                routeHasDescription = true
            }
        }
    }

    // ZIPFROMTH-2026-09-24 (Fred): the zip code is DERIVED from the trailhead, never typed. Android's own
    // geocoder, in the background; no connection or no answer leaves it empty (it is not a completion rule).
    LaunchedEffect(trailhead) {
        val th = trailhead ?: return@LaunchedEffect
        // RIDESCONV-2026-11-23: the geocoder is the OS's. The screen asks for a zip, not for a
        // geocoder, so the platform keeps its own call and its own failure handling.
        val zip = try { b.zipFor(th.lat, th.lon) } catch (e: Exception) {
            gtLogger("ConvoyRideCreate").w { "ZIPFROMTH: zipFor failed: ${e.message}" }; null
        }
        if (!zip.isNullOrBlank()) zipCode = zip
    }

    // SAVEPLAN-2026-09-24 (Fred): a ride starts as a PLAN -- the NAME is the only minimum to save.
    // Anything else missing is reported after saving (in progress), never a reason to refuse.
    val canSave = rideName.isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(Gt.panel)) {

        Row(
            modifier = Modifier.fillMaxWidth().background(Gt.panel)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // GTRIDES-2026-10-22: the standard top bar -- title, HELP ✕ (the ← BACK is gone; ✕ goes back)
            Text("CREATE A RIDE", color = Gt.text, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            b.help("rides.ride")
            com.geeksville.mesh.grouptrack.GtCloseButton(onBack)
        }
        HorizontalDivider(thickness = 2.dp, color = Gt.accent)

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .imePadding().navigationBarsPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // EDITRIDE-2026-09-27: a sent ride says what can still change.
            if (editSent) Text("This ride has been sent \u2014 only its date can change. Send it again after saving.",
                color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            // FORMLAYOUT-2026-09-27 (Fred): the ride's NAME first.
            RideField("Ride name *", rideName, "Sunday Desert Run") { rideName = it }
            // FORMTOP-2026-09-27 (Fred): public/private and the radio network are the form's FIRST decision.
            // RIDENET-2026-09-27 (Fred): PUBLIC by default, PRIVATE the override -- and the screen says what each means.
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("PRIVATE RIDE", color = Gt.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(if (isPrivate)
                            "Private \u2014 for the riders you invite; please don't forward. At the end of the ride there is no survey and no sharing: every rider's track stays on their own tablet, and no public trails are added."
                        else
                            "Public \u2014 riders may forward the invitation to friends. At the end of the ride, each rider rates it and decides whether to share their track with the community.",
                        color = Gt.dim, fontSize = 12.sp)
                }
                Switch(checked = isPrivate, onCheckedChange = { isPrivate = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Gt.accent,
                        checkedTrackColor = Gt.panel,
                        uncheckedThumbColor = Gt.text,
                        uncheckedTrackColor = Gt.panel))
            }

            // FORMLAYOUT-2026-09-27 (Fred): the radio network is a CHOICE; "Network assigned" shows the one in use.
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("RADIO NETWORK", color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                // DEFAULTNET-2026-10-10 / THREENET-2026-10-10 (Fred): three choices, the default first and selected --
                // 0 = the GroupTrack default network (the channel every radio already has from install: no radio setup),
                // 1 = the creator's own network, shown as "Creator's name · network id" (reused for all their rides),
                // 2 = a private network generated for this ride only.
                val netChoice = if (useDefaultNetwork) 0 else if (newNetworkForRide) 2 else 1
                listOf(0 to "GroupTrack default network",
                    1 to (myNetworkName ?: "My network \u2014 not available, check your rider profile"),   // THREENET-2026-10-10
                    2 to "Create a private network for this ride").forEach { (opt, label) ->
                    Text((if (netChoice == opt) "\u25C9  " else "\u25CB  ") + label,
                        color = if (netChoice == opt) Gt.accent else Gt.accent, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = editRideId == null) {   // EDITRIDE: a ride's network is not changed here
                            useDefaultNetwork = opt == 0
                            newNetworkForRide = opt == 2
                            if (opt == 2 && rideNetwork == null) rideNetwork = GtRideStore.createRideNetwork()   // generated now, used only when the ride is added
                        }.padding(vertical = 6.dp))
                }
                Text("Network assigned: " + when (netChoice) {
                        0 -> "GroupTrack default"
                        2 -> rideNetwork?.let { "Private \u2013 " + it.configId } ?: "\u26a0 not created \u2014 check your rider profile"
                        else -> myNetworkName ?: "\u26a0 not available \u2014 check your rider profile"
                    },
                    color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text(when (netChoice) {
                        0 -> "The channel every GroupTrack OG radio already has \u2014 riders need no radio setup. Anyone running GroupTrack OG within radio range shares it."
                        2 -> "Used only by this ride. Every rider sets up their radio for it, even if they have ridden with you before."
                        else -> "Used for all your rides \u2014 riders who joined any of your rides are already on it."
                    },
                    color = Gt.warn, fontSize = 12.sp)
            }

            // FORMTRIM-2026-09-27 (Fred): the organizer's name and email are not shown (still recorded from the profile);
            // only the no-profile warning remains -- without a profile a ride cannot be saved.
            if (me == null) Text("\u26a0 No rider profile on this tablet \u2014 Settings \u2192 Your Profile",
                color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            // FORMLAYOUT-2026-09-27 (Fred): date and rollout time on ONE line.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    RideLabel("Date *")
                    Text(rideDate.ifBlank { "Tap to choose" },
                        color = if (rideDate.isBlank()) Gt.dim else Gt.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(vertical = 10.dp))
                }
                // RIDETIME-2026-11-23 (Fred 07:35 "time should be a picker also no keymh am pm etc"):
                // the rollout time is a PICKER, no free text. Same display shape as the date field.
                Column(modifier = Modifier.weight(1f)) {
                    RideLabel("Rollout time")
                    Text(startTime.ifBlank { "Tap to choose" },
                        color = if (startTime.isBlank()) Gt.dim else Gt.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }.padding(vertical = 10.dp))
                }
            }
            if (showDatePicker) RideDatePickerDialog(rideDate,
                onPick = { rideDate = it; showDatePicker = false },
                onDismiss = { showDatePicker = false })
            if (showTimePicker) RideTimePickerDialog(startTime,
                onPick = { startTime = it; showTimePicker = false },
                onDismiss = { showTimePicker = false })
            // FORMTRIM-2026-09-27 (Fred): the zip is still derived from the trailhead and saved -- just not shown.
            // FORMTRIM-2026-09-27 (Fred): only when the route has no description -- then required to send the ride.
            if (!routeHasDescription || editRideId != null)   // EDITRIDE-2026-09-27: edit shows what riders get
                RideField("Leader's Notes *", description, "Meeting point, what to bring, terrain, skill level...") { description = it }   /* LEADERNOTES-2026-09-27 (Fred) */

            // RIDETH-2026-09-23: the trailhead -- one, a choice, or how to add one.
            if (routeId.isNotBlank()) {
                when {
                    thChoices.isEmpty() -> Column {
                        // THTEXT-2026-09-27 (Fred): no trailhead waypoint within 1/2 mile -- describe where to meet instead.
                        Text("No trailhead waypoint near this route's start. Describe where to meet \u2014 it goes at the top of the ride's description.",
                            color = Gt.warn, fontSize = 12.sp)
                        RideField("Trailhead *", thText, "e.g. Casto Canyon parking area, off Hwy 12") { thText = it }
                    }
                    thChoices.size == 1 -> Text("Trailhead: ${thChoices[0].name}",
                        color = Gt.good, fontSize = 12.sp)
                    else -> Column {
                        Text("Choose the trailhead:", color = Gt.text, fontSize = 12.sp)
                        thChoices.forEach { c ->
                            Text((if (trailhead == c) "\u25C9  " else "\u25CB  ") + c.name +
                                    "  \u00b7  " + GtFmt.f(c.miles, 2) + " mi",
                                color = if (trailhead == c) Gt.accent else Gt.text,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { trailhead = c }.padding(vertical = 6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            RideLabel("ROUTE")
            if (pickerOpen) {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // RIDESEARCH-2026-10-24: only the routes whose box overlaps the map area, unless SHOW ALL.
                    val shown = if (showAll || area.size != 4) routes else routes.filter { r ->
                        r.maxLat >= area[0] && r.minLat <= area[1] && r.maxLon >= area[2] && r.minLon <= area[3] }
                    if (area.size == 4) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (showAll) "All routes: ${routes.size}" else "Routes in the map area: ${shown.size}",
                            color = Gt.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(if (showAll) "MAP AREA ONLY" else "SHOW ALL", color = Gt.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { showAll = !showAll })
                    }
                    if (shown.isEmpty()) {
                        Text(if (routes.isEmpty()) "No routes yet \u2014 tap Add Route on the map, or make a route from a track."
                             else "No routes in this map area \u2014 move the map, or SHOW ALL.",
                            color = Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    shown.forEach { r ->
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(Gt.panel)
                            .clickable { routeId = r.routeId; routeName = r.name; pickerOpen = false }
                            .padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.name, color = Gt.text, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text("\u203a", color = Gt.accent, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                // PROTECTROUTE-2026-09-24 (Fred): the route's name belongs to the route -- display only.
                // FORMLAYOUT-2026-09-27: the route's name directly over its picture.
                Text(routeName.ifBlank { "\u2014" }, color = Gt.text, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                // RIDEPREVIEW-2026-09-24: the planner map, fitted and captured at ADD A RIDE -- square.
                // RIDESCONV-2026-11-23: the preview is a PLATFORM bitmap, so the host draws it;
                // the vignette fallback stays here and stays shared.
                b.routePreview(routeId) { RouteVignette(routePts) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Gt.panel)
                        // ROUTELOCK-2026-09-24 (Fred): a ride's route is fixed -- say why, don't open a picker.
                        .clickable {
                            b.toast("A ride's route can't be changed. To use a " +
                                "different route, delete this ride and create a new one from the route on the " +
                                "planning map.")
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("CHANGE ROUTE", color = Gt.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Text("${routePts.size} points", color = Gt.text, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }

            // CALDATE-2026-09-24 (Fred): the date of the ride comes from a calendar -- always yyyy-MM-dd.
            if (status.isNotBlank()) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Gt.panel).padding(12.dp)) {
                    Text(status, color = Gt.good, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (canSave) Gt.panel else Gt.panel)
                .clickable {
                    // SAVEWHY-2026-09-23: the high-visibility palette hides a disabled button, so Save
                    // is always tappable and SAYS what is missing instead of silently doing nothing.
                    if (!canSave) {
                        status = "\u2717 Cannot save yet \u2014 a ride needs a name."   // SAVEPLAN-2026-09-24
                        gtLogger("ConvoyRideCreate").w { "SAVEWHY: $status" }
                        return@clickable
                    }
                    gtLogger("ConvoyRideCreate").i { "SAVEWHY: save tapped, all conditions met" }
                    // PROTECTROUTE-2026-09-24: Save never renames the route.
                    // RIDETH-2026-09-23: a trailhead found by the 1/2-mile search goes into the route's RECIPE.
                    trailhead?.let { th ->
                        if (!thFromRecipe) spatialWrite()?.setRouteAnchor(routeId, th.lat, th.lon, th.name)
                    }
                    // ONEDEFAULT-2026-09-26: the new ride's radio values = the GroupTrack default, read now.
                    b.refreshMeshDefaults()
                    // THTEXT-2026-09-27: the typed trailhead goes at the FRONT of the description (once).
                    if (routeId.isNotBlank() && thChoices.isEmpty() && thText.isNotBlank() &&
                        !description.trimStart().startsWith("Trailhead:"))
                        description = "Trailhead: " + thText.trim() + "\n\n" + description
                    // EDITRIDE-2026-09-27 (Fred): edit mode UPDATES the ride -- everything but the route and network while not
                    // sent; only the date once sent (the store's rule; either way the 30 days restart from the date). The ride
                    // file is rewritten so a re-send carries the change.
                    if (editRideId != null) {
                        val ok = if (editSent) GtRideStore.updateRideDate(editRideId, rideDate)
                                 else GtRideStore.updateRide(editRideId, rideName, rideDate, startTime, description, zipCode, !isPrivate, routeId)
                        gtLogger("ConvoyRideCreate").i { "EDITRIDE: $editRideId sent=$editSent ok=$ok" }
                        if (ok) {
                            GtRideJson.save(b.files, editRideId)
                            status = "\u2713 Ride updated" + (if (editSent) " \u2014 send it again so riders get the new date" else "")
                            onRideCreated(editRideId)
                        } else status = "\u2717 Not saved"
                        return@clickable
                    }
                    val wasOrganizer = GtRideStore.isOrganizer()   // RIDENET-2026-09-27: first ride -> say so once
                    val id = GtRideStore.saveRide(
                        rideName = rideName, rideDate = rideDate, startTime = startTime,
                        description = description, zipCode = zipCode,
                        isPublic = !isPrivate,
                        rideNetworkId = if (useDefaultNetwork) GtNetworkGenerator.DEFAULT_CHANNEL   // DEFAULTNET-2026-10-10
                            else if (newNetworkForRide) rideNetwork?.configId else null,   // FORMLAYOUT-2026-09-27
                        routeId = routeId
                    )
                    if (id != null) {
                        // RIDEFILE-2026-09-23: the ride JSON is stored on every Save.
                        val missing = GtRideJson.save(b.files, id)
                        b.adoptPreview(routeId, id)   // RIDEPREVIEW-2026-09-24
                        status = when {
                            missing == null -> "\u2713 Ride saved \u2014 \u2717 ride file not written"
                            missing.isEmpty() -> "\u2713 Ride saved \u2014 ready to send"
                            else -> "\u2713 Saved in progress \u2014 missing: " + missing.joinToString(", ")
                        }
                        // RIDESCONVFIX2-2026-11-23: configId is String? on RideNet; the local is cleared either way, as before.
                        if (!newNetworkForRide) rideNetwork?.let { n -> n.configId?.let { GtRideStore.env.networks.deleteUnused(it) }; rideNetwork = null }   // FORMLAYOUT: unused
                        if (!wasOrganizer && !useDefaultNetwork && !newNetworkForRide) status += "\n\u2713 You're now an organizer \u2014 your rides use your own network: " +
                            (GtRideStore.myNetworkName() ?: "created with this ride") + "."   // RIDENET-2026-09-27
                        onRideCreated(id)
                    }
                    else status = "\u2717 Could not save \u2014 is there a rider profile?"
                }
                .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text("SAVE RIDE",
                    color = if (canSave) Gt.accent else Gt.text,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            // RIDECANCEL-2026-09-26 (Fred): leave the form. NOTHING is written -- a ride is saved only by SAVE RIDE
            // (no ghosts), so Cancel simply returns to where the form was opened from.
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().clickable {
                    rideNetwork?.configId?.let { GtRideStore.env.networks.deleteUnused(it) }   // FORMLAYOUT-2026-09-27: no ghost network
                    onBack()
                }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("CANCEL", color = Gt.dim, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * CALDATE-2026-09-24 -- the ride's date from a calendar. Always returns yyyy-MM-dd.
 * initialMillis is nullable BY DESIGN (CODE RULE 1): a new ride has no date yet, and the picker
 * then opens on today with nothing selected.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RideDatePickerDialog(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val initialMillis: Long? = try {
        // RIDESCONV-2026-11-23: GtTime.gregorianEpochDay is the certified shared twin of
        // LocalDate.parse(...).atStartOfDay(UTC).toEpochMilli().
        val p = initial.trim().split("-")
        GtTime.gregorianEpochDay(p[0].toLong(), p[1].toInt(), p[2].toInt()) * 86_400_000L
    } catch (e: Exception) { null }
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val ms = state.selectedDateMillis
                if (ms != null) {
                    val (yy, mm, dd) = GtTime.gregorianCivil(ms / 86_400_000L)
                    onPick(yy.toString() + "-" + two(mm) + "-" + two(dd))
                } else onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}

/**
 * RIDESCONV-2026-11-23 (Fred 07:48: "traileheads near is the std search function correct.... nothing
 * unique"): the STANDARD shared reader and writer, built from the ride env's OWN connections. Nothing
 * is added -- GtRead.trailheadsNear and GtWrite.setRouteAnchor are what Android already calls, one
 * delegation hop away (SpatialDbManager:1656 and :1663, both verified 11-23).
 *
 * CODE RULE 1: a null spatial connection is a real state -- no spatial database on this device -- and
 * the callers then behave exactly as 2.7e's SpatialDbManager did: an empty list, and false.
 */
private fun spatialRead(): GtRead? =
    GtRideStore.env.spatialDb()?.let { GtRead(it, GtRideStore.env.dataDb()) }

private fun spatialWrite(): GtWrite? =
    GtRideStore.env.spatialDb()?.let {
        GtWrite(it, GtRideStore.env.dataDb(), GtRideStore.env.newId, GtRideStore.env.nowUtc)
    }

/** RIDESCONV-2026-11-23: zero-padded minutes, platform-free. */
private fun two(v: Int): String = if (v < 10) "0" + v else v.toString()

/**
 * RIDETIME-2026-11-23 (Fred 07:35: "time should be a picker also no keymh am pm etc") -- the ride's
 * rollout time from material3's TimePicker. NO FREE TEXT.
 *
 * ⛔ IT WRITES THE SAME STRING THE FORM HAS ALWAYS STORED ("8:00 AM"), so nothing downstream
 * sees a change and NO SCHEMA MOVES (Fred 07:36: "b no db changes just function changes").
 * STEP 22 is what adds the time ZONE and HH:mm storage, with its schema 1 -> 2 and its cert.
 *
 * CODE RULE 1: [initial] may be blank -- a new ride has no time yet, and the picker then opens at
 * 8:00, which is the placeholder the free-text field used to show.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RideTimePickerDialog(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val hm = remember(initial) { parseRideTime(initial) }
    val state = androidx.compose.material3.rememberTimePickerState(
        initialHour = hm?.first ?: 8, initialMinute = hm?.second ?: 0, is24Hour = false)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onPick(formatRideTime(state.hour, state.minute))
            }) { Text("OK") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { androidx.compose.material3.TimePicker(state = state) },
    )
}

/**
 * RIDETIME-2026-11-23: read back what the form (or an older ride) stored. Handles the shapes STEP
 * 22 lists: "8:00 AM", "08:00", "8am", "8:00AM", "13:30". CODE RULE 1: null = unreadable, and the
 * picker then opens at 8:00 rather than guessing.
 */
private fun parseRideTime(s0: String): Pair<Int, Int>? {
    val s = s0.trim().uppercase()
    if (s.isEmpty()) return null
    val pm = s.contains("PM"); val am = s.contains("AM")
    val digits = s.replace("AM", "").replace("PM", "").trim()
    val parts = digits.split(":")
    val h0 = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val m = if (parts.size > 1) (parts[1].trim().toIntOrNull() ?: return null) else 0
    if (m < 0 || m > 59) return null
    var h = h0
    if (am || pm) {
        if (h < 1 || h > 12) return null
        if (h == 12) h = 0
        if (pm) h += 12
    } else if (h < 0 || h > 23) return null
    return h to m
}

/** RIDETIME-2026-11-23: the form's own stored shape -- "8:00 AM". */
private fun formatRideTime(h: Int, m: Int): String {
    val ap = if (h < 12) "AM" else "PM"
    val h12 = if (h % 12 == 0) 12 else h % 12
    return h12.toString() + ":" + two(m) + " " + ap
}

/** The vignette: the chosen route drawn from stored geometry. Confirmation, not a map. */
@Composable
private fun RouteVignette(pts: List<Pair<Double, Double>>) {
    Box(modifier = Modifier.fillMaxWidth().height(150.dp)
        .clip(RoundedCornerShape(8.dp)).background(Gt.panel)) {
        if (pts.size < 2) {
            Text("no geometry", color = Gt.text, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Center))
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val xs = pts.map { it.first }; val ys = pts.map { it.second }
                val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
                val spanX = (maxX - minX).takeIf { it > 1e-9 } ?: 1e-9
                val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1e-9
                val scale = minOf(size.width / spanX, size.height / spanY)
                val offX = (size.width - spanX * scale) / 2
                val offY = (size.height - spanY * scale) / 2
                fun at(p: Pair<Double, Double>) = Offset(
                    (offX + (p.first - minX) * scale).toFloat(),
                    (offY + (maxY - p.second) * scale).toFloat()
                )
                for (i in 0 until pts.size - 1) {
                    drawLine(color = Gt.accent, start = at(pts[i]), end = at(pts[i + 1]),
                        strokeWidth = 3f, cap = StrokeCap.Round)
                }
                drawCircle(color = Gt.accent, radius = 6f, center = at(pts.first()))
                drawCircle(color = Gt.warn, radius = 6f, center = at(pts.last()))
            }
        }
    }
}

@Composable
private fun RideLabel(label: String) {
    Text(label, color = Gt.accent, fontSize = 12.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun RideField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = Gt.text, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gt.accent,
            unfocusedBorderColor = Gt.line,
            focusedTextColor = Gt.text,
            unfocusedTextColor = Gt.text,
            focusedLabelColor = Gt.accent,
            unfocusedLabelColor = Gt.text,
            cursorColor = Gt.accent))
}
