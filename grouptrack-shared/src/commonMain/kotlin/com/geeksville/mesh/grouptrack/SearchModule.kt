package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.ArtifactResult
import com.geeksville.mesh.convoy.assignNameSequence
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * GTSEARCH-2026-10-22 (2.7c, Fred 12:56-13:02): ONE box searches EVERYTHING -- no type to pick first (riders blew past the old
 * type chips and their first search always went to Area). "Let's not complicate this any more than we have to."
 *   * Trails, routes, tracks, waypoints: the tablet's own database (works offline). Places: the place-name lookup, ONLINE ONLY --
 *     offline it is simply left out.
 *   * SCOPE defaults to the STATE the map is in (offline state outlines, geofabrik_states.json); "Everywhere" widens it.
 *   * Results come back GROUPED with counts; a trail/route/track/waypoint frames itself and opens its detail card (as today);
 *     a place moves the map there.
 * Same engines as the old search (searchByName + assignNameSequence; Geocoder) -- nothing new underneath.
 */
class GtSearchBridge(
    /** the map's centre now: [lat, lon] (the last reported view). */
    val mapCenter: () -> DoubleArray,
    val online: Boolean,
    val openArtifact: (ArtifactResult) -> Unit,
    val goToPlace: (Double, Double) -> Unit,
    /** BODIES-2026-11-19: the state outlines, from the host. The catalogue parse needs a platform
     *  asset reader (GeofabrikCatalog.load(context)); the STATE itself is a shared data class.
     *  Required (CODE RULE 1) -- an empty list is a legitimate value (the map is outside every
     *  outline) and the search then runs unbounded, exactly as it does today. */
    val states: () -> List<com.geeksville.mesh.convoy.GeofabrikState>,
    /** BODIES-2026-11-19: the place-name lookup, ONLINE ONLY -- see the note at the top of this
     *  file. Android keeps Geocoder AND its IO dispatch; a platform without one returns empty and
     *  the panel already says "Offline -- places are left out". Required (CODE RULE 1). */
    val findPlaces: suspend (String, com.geeksville.mesh.convoy.GeofabrikState?) -> List<GtPlace>,
    /** SEARCHDB-2026-11-19: the spatial DB is OPENED by the host -- SpatialDbManager.init needs a
     *  Context, and the connection is the host's to own. Called on the IO dispatcher, in exactly
     *  the place init(ctx) stood. Required (CODE RULE 1) -- a platform with no spatial DB supplies
     *  a no-op and searchByName returns empty, which the panel already renders as no results. */
    val initSearch: suspend () -> Unit,
    /** SEARCHDB-2026-11-19: the name search. The shared GtRead.searchByName has the IDENTICAL
     *  signature -- (type, term, limit = 200) -> List<Map<String, String?>> -- so iOS supplies it
     *  directly, and Android keeps SpatialDbManager, whose searchByName already delegates to
     *  GtRead. The query rules (Q1-Q4) live with the engine; see ArtifactSearch.kt. */
    val searchByName: suspend (String, String) -> List<Map<String, String?>>,
)

/** BODIES-2026-11-19: public, because the bridge's findPlaces returns these. */
class GtPlace(val name: String, val lat: Double, val lon: Double)

@Composable
fun GtSearchBody(b: GtSearchBridge, close: () -> Unit, autoFocus: Boolean) {   // GTAI5C-2026-10-23: autoFocus -- the caller decides
    val s = GtScheme.current.value
    val scope = rememberCoroutineScope()
    val c = remember { b.mapCenter() }
    val state = remember(c[0], c[1]) {
        b.states().firstOrNull { it.containsPoint(c[0], c[1]) }   // null = the map is outside every state outline
    }
    // GTSEARCH (Fred 13:06 "state can be selected, in case I am searching AR or NV"): the state is CHOSEN -- default the map's state.
    val allStates = remember { b.states().sortedBy { it.name } }
    var pick by remember { mutableStateOf(state) }   // null = no state chosen yet (the map is outside every outline)
    var inState by remember { mutableStateOf(state != null) }
    var choosing by remember { mutableStateOf(false) }
    var term by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<Pair<String, List<ArtifactResult>>>>(emptyList()) }
    var places by remember { mutableStateOf<List<GtPlace>>(emptyList()) }
    var hiddenTypes by remember { mutableStateOf<Set<String>>(emptySet()) }   // result types switched off in the display
    val focus = remember { FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current   // SEARCHKB-2026-10-22
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }   // GTAI5C: no keyboard unless the search was asked for

    fun run() {
        val t = term.trim()
        if (t.isEmpty()) return
        kb?.hide(); fm.clearFocus()   // SEARCHKB-2026-10-22 (Fred 13:20): the keyboard closes as the search runs
        val st = if (inState) pick else null
        busy = true
        scope.launch {
            val types = listOf("trails" to "Trails", "routes" to "Routes", "tracks" to "Tracks", "waypoints" to "Waypoints")
            val found = coroutineScope {   // IOSFIX-2026-11-19: Dispatchers.IO is internal on
                // native; the HOST now owns the dispatch, through the two suspend members above.
                b.initSearch()
                types.map { (table, label) ->
                    async {
                        val rows = b.searchByName(table, t)
                        val kept = if (st == null) rows else rows.filter { r ->
                            val so = r["min_lat"]?.toDoubleOrNull(); val no = r["max_lat"]?.toDoubleOrNull()
                            val we = r["min_lon"]?.toDoubleOrNull(); val ea = r["max_lon"]?.toDoubleOrNull()
                            so == null || no == null || we == null || ea == null || st.intersectsBbox(so, we, no, ea)
                        }
                        label to assignNameSequence(kept)
                    }
                }.map { it.await() }
            }
            // BODIES-2026-11-19: the place lookup is the HOST's -- Geocoder, its IO dispatch and the
            // state-name filter all moved into findPlaces, unchanged. This half stays exactly as it was:
            // offline means no places, and the panel already says so.
            val pl = if (!b.online) emptyList() else b.findPlaces(t, st)
            groups = found; places = pl; searched = t; busy = false; hiddenTypes = emptySet()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // RIDESEARCH2-2026-10-24 (Fred 10:34): Create a ride, step 1 -- say what this panel is for.
        if (GtRideStart.pending.value) Text("This panel positions you by area, track, route or waypoint. The next step displays all " +
            "routes in that area for you to select the route to build a ride from. You can turn tracks on to create a route from a track.",
            color = s.panelText, fontSize = 14.sp)
        // RIDESEARCH3-2026-10-24 (Fred 10:35): proceed when the map is where you will ride.
        if (GtRideStart.pending.value) GtButton("PROCEED TO RIDE CREATION", { GtRideStart.wantProceed.value = true }, modifier = Modifier.fillMaxWidth())
        // SEARCHTOP-2026-10-22 (Fred 13:36): the box FIRST, the state choice under it.
        OutlinedTextField(
            value = term, onValueChange = { term = it }, singleLine = true,
            placeholder = { Text("Trail, route, track, waypoint or place", fontSize = 14.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = s.panelText, unfocusedTextColor = s.panelText,
                focusedBorderColor = Gt.accent, unfocusedBorderColor = s.edge, cursorColor = Gt.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        GtButton(if (busy) "Searching…" else "SEARCH", { run() }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.border(1.dp, s.edge, RoundedCornerShape(8.dp))) {
            ScopeSeg("In ${pick?.name ?: "a state"} ▾", inState) { choosing = !choosing }
            ScopeSeg("Everywhere", !inState) { inState = false; choosing = false; if (searched.isNotEmpty()) run() }
        }
        if (choosing) Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).border(1.dp, s.edge, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())) {
            allStates.forEach { g ->
                Text(g.name, color = if (g == pick) Gt.accent else s.panelText, fontSize = 15.sp,
                    fontWeight = if (g == pick) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clickable { pick = g; inState = true; choosing = false; if (searched.isNotEmpty()) run() }
                        .padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
        if (!b.online) Text("Offline — places are left out; your trails, routes, tracks and waypoints are searched.", color = s.panelDim, fontSize = 12.sp)

        if (searched.isNotEmpty() && !busy) {
            val total = groups.sumOf { it.second.size } + places.size
            // GTSEARCH (Fred 13:04): the result TYPES as a filter on the display -- each type with its count, tap to show / hide it.
            val counts = groups.map { it.first to it.second.size } + listOf("Places" to places.size)
            // SEARCHTOP (Fred 13:38): say what the chips do
            if (total > 0) Text("Tap a type to show or hide it", color = GtScheme.current.value.panelDim, fontSize = 12.sp)
            if (total > 0) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                counts.filter { it.second > 0 }.forEach { (label, n) ->
                    TypeChip("$label $n", label !in hiddenTypes) { hiddenTypes = if (label in hiddenTypes) hiddenTypes - label else hiddenTypes + label }
                }
            }
            if (total == 0) Text("Nothing found for \"$searched\"" + (if (inState && pick != null) " in ${pick?.name} — try another state or Everywhere." else "."),
                color = s.panelText, fontSize = 14.sp)
            groups.filter { it.second.isNotEmpty() && it.first !in hiddenTypes }.forEach { (label, rows) ->
                Header("$label (${rows.size})")
                rows.take(50).forEach { r ->
                    ResultRow(r.name + if (rows.count { it.name == r.name } > 1) "  #${r.seq}" else "", r.foundAs ?: "") { close(); b.openArtifact(r) }   // FOUNDAS-2026-10-29 (D22)
                }
                if (rows.size > 50) Text("First 50 shown — add more of the name.", color = s.panelDim, fontSize = 12.sp)
            }
            if (places.isNotEmpty() && "Places" !in hiddenTypes) {
                Header("Places (${places.size})")
                places.forEach { p -> ResultRow(p.name, "") { close(); b.goToPlace(p.lat, p.lon) } }
            }
        }
    }
}

@Composable
private fun Header(t: String) {
    Text(t, color = GtScheme.current.value.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
// FOUNDAS-2026-10-29 (D22): [foundAs] = the other name the search matched; "" = matched on the main name, no second line.
// Required at every call (not a default) so each caller states it.
private fun ResultRow(label: String, foundAs: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp)) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(label, color = GtScheme.current.value.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (foundAs.isNotEmpty()) Text("found as: " + foundAs, color = GtScheme.current.value.panelDim, fontSize = 12.sp)
        }
        Text("›", color = Gt.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScopeSeg(label: String, on: Boolean, onClick: () -> Unit) {
    val s = GtScheme.current.value
    androidx.compose.foundation.layout.Box(Modifier.background(Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text((if (on) "\u2713 " else "") + label, color = if (on) Gt.text else s.panelText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** GTSEARCH: a result-type chip -- on = its group shows. */
@Composable
private fun TypeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val s = GtScheme.current.value
    androidx.compose.foundation.layout.Box(Modifier.border(1.dp, if (on) Gt.accent else s.edge, RoundedCornerShape(16.dp))
        .background(Color.Transparent, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text((if (on) "\u2713 " else "") + label, color = if (on) Gt.text else s.panelDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
