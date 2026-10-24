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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
@Composable
fun ConvoyRideCreateScreen(
    initialRouteId: String? = null,
    // EDITRIDE-2026-09-27 (Fred): CODE RULE 1 -- null is a real choice: a NEW ride (every existing caller); a ride id opens
    // the form FILLED IN from that ride, and SAVE updates it. Only the Rides list's Edit passes one.
    editRideId: String? = null,
    onRideCreated: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current

    var rideName by remember { mutableStateOf("") }
    var rideDate by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }   // CALDATE-2026-09-24
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
    var rideNetwork by remember { mutableStateOf<com.grouptrack.core.NetworkConfig?>(null) }
    // EDITRIDE-2026-09-27 (Fred): edit mode -- the form opens FILLED IN from the ride; a SENT ride may only change its date.
    val editSent = remember(editRideId) { editRideId != null && ConvoyRideStore.isDistributed(editRideId) }
    LaunchedEffect(editRideId) {
        val e = editRideId?.let { ConvoyRideStore.rideForEdit(it) } ?: return@LaunchedEffect
        rideName = e.name; rideDate = e.date; startTime = e.startTime; description = e.description
        isPrivate = !e.isPublic; newNetworkForRide = e.unique
        useDefaultNetwork = e.configId == com.grouptrack.core.NetworkGenerator.DEFAULT_CHANNEL   // DEFAULTNET-2026-10-10
        if (e.unique) rideNetwork = e.configId?.let { ConvoyNetworkStore.load(it) }
    }
    // CODE RULE 1: null is a real state -- this rider has no network yet; their first ride creates it.
    // RIDENETWORDS2-2026-09-27: the rider's own network is made when the form opens, so it always exists and has a name.
    val myNetworkName = remember { ConvoyRideStore.ensureMyNetworkName() }

    var routes by remember { mutableStateOf<List<RouteSummary>>(emptyList()) }
    var routeId by remember { mutableStateOf(initialRouteId ?: "") }
    var routeName by remember { mutableStateOf("") }
    var routePts by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(initialRouteId == null) }
    var status by remember { mutableStateOf("") }
    // RIDETH-2026-09-23 (Fred): a ride cannot be built without a trailhead.
    var thChoices by remember { mutableStateOf<List<SpatialDbManager.TrailheadCandidate>>(emptyList()) }
    var trailhead by remember { mutableStateOf<SpatialDbManager.TrailheadCandidate?>(null) }
    // THTEXT-2026-09-27 (Fred): INTERIM -- when no trailhead waypoint is near the route's start, the organizer describes
    // it; it goes at the FRONT of the ride's description. Goes away once every route carries its trailhead.
    var thText by remember { mutableStateOf("") }
    var thFromRecipe by remember { mutableStateOf(false) }

    val me = remember { ConvoyProfileStore.load() }

    // RIDESEARCH-2026-10-24: the map area from Create a ride (south, north, west, east); empty = opened another way -- every route.
    val area = remember { com.geeksville.mesh.grouptrack.GtRideStart.takeArea() }
    var showAll by remember { mutableStateOf(area.isEmpty()) }
    LaunchedEffect(Unit) {
        routes = ConvoyRideStore.listRoutes(if (area.isEmpty()) 200 else 5000)   // RIDESEARCH-2026-10-24: an area may hold older routes
        zipCode = ConvoySessionManager.getZipCode(context)
    }
    LaunchedEffect(routeId) {
        if (routeId.isBlank()) {
            routePts = emptyList(); thChoices = emptyList(); trailhead = null; thFromRecipe = false
            return@LaunchedEffect
        }
        routeName = routes.firstOrNull { it.routeId == routeId }?.name ?: routeName
        routePts = ConvoyRideStore.routeGeometry(routeId)
            ?.let { ConvoyRideStore.parseWktLine(it) } ?: emptyList()
        // Recipe anchor first; otherwise trailheads within 1/2 mile of the FIRST point.
        // ⚠ parseWktLine gives (LON, LAT) -- WKT order.
        val start = routePts.firstOrNull()
        val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rec = SpatialDbManager.routeRecipe(routeId)
            if (rec != null && rec.has("anchorLat") && rec.has("anchorLon")) {
                Pair(true, listOf(SpatialDbManager.TrailheadCandidate(
                    "", rec.optString("anchorName", "").ifBlank { "Trailhead" },
                    rec.optDouble("anchorLat"), rec.optDouble("anchorLon"), 0.0)))
            } else {
                Pair(false, start?.let { SpatialDbManager.trailheadsNear(it.second, it.first) } ?: emptyList())
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
            val notes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SpatialDbManager.readRouteNotes(routeId) }
            val nar = notes?.optJSONObject("narrative")
            val headline = nar?.optString("headline", "")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val about = nar?.optString("description", "")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val converted = notes?.optString("source", "") == "convertroute"
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
        val zip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (!android.location.Geocoder.isPresent()) null
                else @Suppress("DEPRECATION")
                    android.location.Geocoder(context, java.util.Locale.US)
                        .getFromLocation(th.lat, th.lon, 1)?.firstOrNull()?.postalCode
            } catch (e: Exception) {
                android.util.Log.w("ConvoyRideCreate", "ZIPFROMTH: geocoder failed: ${e.message}")
                null
            }
        }
        if (!zip.isNullOrBlank()) zipCode = zip
    }

    // SAVEPLAN-2026-09-24 (Fred): a ride starts as a PLAN -- the NAME is the only minimum to save.
    // Anything else missing is reported after saving (in progress), never a reason to refuse.
    val canSave = rideName.isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        Row(
            modifier = Modifier.fillMaxWidth().background(Gt.panel)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // GTRIDES-2026-10-22: the standard top bar -- title, HELP ✕ (the ← BACK is gone; ✕ goes back)
            Text("CREATE A RIDE", color = Gt.text, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            com.geeksville.mesh.grouptrack.GtHelpButton("rides.ride")
            com.geeksville.mesh.grouptrack.GtCloseButton(onBack)
        }
        HorizontalDivider(thickness = 2.dp, color = GroupTrackColors.SkyBlue)

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .imePadding().navigationBarsPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // EDITRIDE-2026-09-27: a sent ride says what can still change.
            if (editSent) Text("This ride has been sent \u2014 only its date can change. Send it again after saving.",
                color = GroupTrackColors.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        checkedThumbColor = GroupTrackColors.SkyBlue,
                        checkedTrackColor = Gt.panel,
                        uncheckedThumbColor = Gt.text,
                        uncheckedTrackColor = Gt.panel))
            }

            // FORMLAYOUT-2026-09-27 (Fred): the radio network is a CHOICE; "Network assigned" shows the one in use.
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Gt.panel).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("RADIO NETWORK", color = GroupTrackColors.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                // DEFAULTNET-2026-10-10 / THREENET-2026-10-10 (Fred): three choices, the default first and selected --
                // 0 = the GroupTrack default network (the channel every radio already has from install: no radio setup),
                // 1 = the creator's own network, shown as "Creator's name · network id" (reused for all their rides),
                // 2 = a private network generated for this ride only.
                val netChoice = if (useDefaultNetwork) 0 else if (newNetworkForRide) 2 else 1
                listOf(0 to "GroupTrack default network",
                    1 to (myNetworkName ?: "My network \u2014 not available, check your rider profile"),   // THREENET-2026-10-10
                    2 to "Create a private network for this ride").forEach { (opt, label) ->
                    Text((if (netChoice == opt) "\u25C9  " else "\u25CB  ") + label,
                        color = if (netChoice == opt) GroupTrackColors.SkyBlue else Gt.accent, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = editRideId == null) {   // EDITRIDE: a ride's network is not changed here
                            useDefaultNetwork = opt == 0
                            newNetworkForRide = opt == 2
                            if (opt == 2 && rideNetwork == null) rideNetwork = ConvoyRideStore.createRideNetwork()   // generated now, used only when the ride is added
                        }.padding(vertical = 6.dp))
                }
                Text("Network assigned: " + when (netChoice) {
                        0 -> "GroupTrack default"
                        2 -> rideNetwork?.let { "Private \u2013 " + it.configId } ?: "\u26a0 not created \u2014 check your rider profile"
                        else -> myNetworkName ?: "\u26a0 not available \u2014 check your rider profile"
                    },
                    color = Gt.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text(when (netChoice) {
                        0 -> "The channel every GroupTrack radio already has \u2014 riders need no radio setup. Anyone running GroupTrack within radio range shares it."
                        2 -> "Used only by this ride. Every rider sets up their radio for it, even if they have ridden with you before."
                        else -> "Used for all your rides \u2014 riders who joined any of your rides are already on it."
                    },
                    color = Gt.warn, fontSize = 12.sp)
            }

            // FORMTRIM-2026-09-27 (Fred): the organizer's name and email are not shown (still recorded from the profile);
            // only the no-profile warning remains -- without a profile a ride cannot be saved.
            if (me == null) Text("\u26a0 No rider profile on this tablet \u2014 Settings \u2192 Your Profile",
                color = GroupTrackColors.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            // FORMLAYOUT-2026-09-27 (Fred): date and rollout time on ONE line.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    RideLabel("Date *")
                    Text(rideDate.ifBlank { "Tap to choose" },
                        color = if (rideDate.isBlank()) Gt.dim else Gt.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(vertical = 10.dp))
                }
                Box(modifier = Modifier.weight(1f)) { RideField("Rollout time", startTime, "8:00 AM") { startTime = it } }
            }
            if (showDatePicker) RideDatePickerDialog(rideDate,
                onPick = { rideDate = it; showDatePicker = false },
                onDismiss = { showDatePicker = false })
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
                        color = GroupTrackColors.Green, fontSize = 12.sp)
                    else -> Column {
                        Text("Choose the trailhead:", color = Gt.text, fontSize = 12.sp)
                        thChoices.forEach { c ->
                            Text((if (trailhead == c) "\u25C9  " else "\u25CB  ") + c.name +
                                    "  \u00b7  " + "%.2f".format(c.miles) + " mi",
                                color = if (trailhead == c) GroupTrackColors.SkyBlue else Gt.text,
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
                            Text("\u203a", color = GroupTrackColors.SkyBlue, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                // PROTECTROUTE-2026-09-24 (Fred): the route's name belongs to the route -- display only.
                // FORMLAYOUT-2026-09-27: the route's name directly over its picture.
                Text(routeName.ifBlank { "\u2014" }, color = Gt.text, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                // RIDEPREVIEW-2026-09-24: the planner map, fitted and captured at ADD A RIDE -- square.
                val routePreview = remember(routeId) { RidePreview.load(context, routeId) }
                if (routePreview != null) {
                    androidx.compose.foundation.Image(
                        bitmap = routePreview.asImageBitmap(), contentDescription = "Route map",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
                } else RouteVignette(routePts)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Gt.panel)
                        // ROUTELOCK-2026-09-24 (Fred): a ride's route is fixed -- say why, don't open a picker.
                        .clickable {
                            android.widget.Toast.makeText(context, "A ride's route can't be changed. To use a " +
                                "different route, delete this ride and create a new one from the route on the " +
                                "planning map.", android.widget.Toast.LENGTH_LONG).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("CHANGE ROUTE", color = GroupTrackColors.SkyBlue, fontSize = 12.sp,
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
                    Text(status, color = GroupTrackColors.Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (canSave) Gt.panel else Gt.panel)
                .clickable {
                    // SAVEWHY-2026-09-23: the high-visibility palette hides a disabled button, so Save
                    // is always tappable and SAYS what is missing instead of silently doing nothing.
                    if (!canSave) {
                        status = "\u2717 Cannot save yet \u2014 a ride needs a name."   // SAVEPLAN-2026-09-24
                        android.util.Log.w("ConvoyRideCreate", "SAVEWHY: $status")
                        return@clickable
                    }
                    android.util.Log.i("ConvoyRideCreate", "SAVEWHY: save tapped, all conditions met")
                    // PROTECTROUTE-2026-09-24: Save never renames the route.
                    // RIDETH-2026-09-23: a trailhead found by the 1/2-mile search goes into the route's RECIPE.
                    trailhead?.let { th ->
                        if (!thFromRecipe) SpatialDbManager.setRouteAnchor(routeId, th.lat, th.lon, th.name)
                    }
                    // ONEDEFAULT-2026-09-26: the new ride's radio values = the GroupTrack default, read now.
                    com.grouptrack.core.NetworkGenerator.MeshDefaults.loadFrom(context)
                    // THTEXT-2026-09-27: the typed trailhead goes at the FRONT of the description (once).
                    if (routeId.isNotBlank() && thChoices.isEmpty() && thText.isNotBlank() &&
                        !description.trimStart().startsWith("Trailhead:"))
                        description = "Trailhead: " + thText.trim() + "\n\n" + description
                    // EDITRIDE-2026-09-27 (Fred): edit mode UPDATES the ride -- everything but the route and network while not
                    // sent; only the date once sent (the store's rule; either way the 30 days restart from the date). The ride
                    // file is rewritten so a re-send carries the change.
                    if (editRideId != null) {
                        val ok = if (editSent) ConvoyRideStore.updateRideDate(editRideId, rideDate)
                                 else ConvoyRideStore.updateRide(editRideId, rideName, rideDate, startTime, description, zipCode, !isPrivate, routeId)
                        android.util.Log.i("ConvoyRideCreate", "EDITRIDE: $editRideId sent=$editSent ok=$ok")
                        if (ok) {
                            ConvoyRideJsonWriter.save(context, editRideId)
                            status = "\u2713 Ride updated" + (if (editSent) " \u2014 send it again so riders get the new date" else "")
                            onRideCreated(editRideId)
                        } else status = "\u2717 Not saved"
                        return@clickable
                    }
                    val wasOrganizer = ConvoyRideStore.isOrganizer()   // RIDENET-2026-09-27: first ride -> say so once
                    val id = ConvoyRideStore.saveRide(
                        rideName = rideName, rideDate = rideDate, startTime = startTime,
                        description = description, zipCode = zipCode,
                        isPublic = !isPrivate,
                        rideNetworkId = if (useDefaultNetwork) com.grouptrack.core.NetworkGenerator.DEFAULT_CHANNEL   // DEFAULTNET-2026-10-10
                            else if (newNetworkForRide) rideNetwork?.configId else null,   // FORMLAYOUT-2026-09-27
                        routeId = routeId
                    )
                    if (id != null) {
                        // RIDEFILE-2026-09-23: the ride JSON is stored on every Save.
                        val missing = ConvoyRideJsonWriter.save(context, id)
                        RidePreview.adopt(context, routeId, id)   // RIDEPREVIEW-2026-09-24
                        status = when {
                            missing == null -> "\u2713 Ride saved \u2014 \u2717 ride file not written"
                            missing.isEmpty() -> "\u2713 Ride saved \u2014 ready to send"
                            else -> "\u2713 Saved in progress \u2014 missing: " + missing.joinToString(", ")
                        }
                        if (!newNetworkForRide) rideNetwork?.let { ConvoyNetworkStore.deleteUnused(it.configId); rideNetwork = null }   // FORMLAYOUT: unused
                        if (!wasOrganizer && !useDefaultNetwork && !newNetworkForRide) status += "\n\u2713 You're now an organizer \u2014 your rides use your own network: " +
                            (ConvoyRideStore.myNetworkName() ?: "created with this ride") + "."   // RIDENET-2026-09-27
                        onRideCreated(id)
                    }
                    else status = "\u2717 Could not save \u2014 is there a rider profile?"
                }
                .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text("SAVE RIDE",
                    color = if (canSave) GroupTrackColors.SkyBlue else Gt.text,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            // RIDECANCEL-2026-09-26 (Fred): leave the form. NOTHING is written -- a ride is saved only by SAVE RIDE
            // (no ghosts), so Cancel simply returns to where the form was opened from.
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().clickable {
                    rideNetwork?.let { ConvoyNetworkStore.deleteUnused(it.configId) }   // FORMLAYOUT-2026-09-27: no ghost network
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
        java.time.LocalDate.parse(initial.trim()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (e: Exception) { null }
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val ms = state.selectedDateMillis
                if (ms != null) {
                    onPick(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())
                } else onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
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
    Text(label, color = GroupTrackColors.SkyBlue, fontSize = 12.sp,
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
            focusedBorderColor = GroupTrackColors.SkyBlue,
            unfocusedBorderColor = Gt.line,
            focusedTextColor = Gt.text,
            unfocusedTextColor = Gt.text,
            focusedLabelColor = GroupTrackColors.SkyBlue,
            unfocusedLabelColor = Gt.text,
            cursorColor = GroupTrackColors.SkyBlue))
}
