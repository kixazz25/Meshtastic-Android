package com.geeksville.mesh.grouptrack

import android.location.Geocoder
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.ArtifactResult
import com.geeksville.mesh.convoy.GeofabrikCatalog
import com.geeksville.mesh.convoy.SpatialDbManager
import com.geeksville.mesh.convoy.assignNameSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

private class GtPlace(val name: String, val lat: Double, val lon: Double)

@Composable
fun GtSearchBody(b: GtSearchBridge, close: () -> Unit) {
    val s = GtScheme.current.value
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember { b.mapCenter() }
    val state = remember(c[0], c[1]) {
        GeofabrikCatalog.findByLocation(GeofabrikCatalog.load(ctx), c[0], c[1])   // null = the map is outside every state outline
    }
    // GTSEARCH (Fred 13:06 "state can be selected, in case I am searching AR or NV"): the state is CHOSEN -- default the map's state.
    val allStates = remember { GeofabrikCatalog.load(ctx).sortedBy { it.name } }
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
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun run() {
        val t = term.trim()
        if (t.isEmpty()) return
        kb?.hide(); fm.clearFocus()   // SEARCHKB-2026-10-22 (Fred 13:20): the keyboard closes as the search runs
        val st = if (inState) pick else null
        busy = true
        scope.launch {
            val types = listOf("trails" to "Trails", "routes" to "Routes", "tracks" to "Tracks", "waypoints" to "Waypoints")
            val found = withContext(Dispatchers.IO) {
                SpatialDbManager.init(ctx)
                types.map { (table, label) ->
                    async {
                        val rows = SpatialDbManager.searchByName(table, t)
                        val kept = if (st == null) rows else rows.filter { r ->
                            val so = r["min_lat"]?.toDoubleOrNull(); val no = r["max_lat"]?.toDoubleOrNull()
                            val we = r["min_lon"]?.toDoubleOrNull(); val ea = r["max_lon"]?.toDoubleOrNull()
                            so == null || no == null || we == null || ea == null || st.intersectsBbox(so, we, no, ea)
                        }
                        label to assignNameSequence(kept)
                    }
                }.map { it.await() }
            }
            val pl = if (!b.online) emptyList() else withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val a = if (st != null) Geocoder(ctx).getFromLocationName(t, 6, st.bboxSouth, st.bboxWest, st.bboxNorth, st.bboxEast)
                            else Geocoder(ctx).getFromLocationName(t, 6)
                    // GTSEARCH (Fred 13:06 "Smithtown exists in a lot of places"): a state outline is a RECTANGLE that overlaps its
                    // neighbours, so a place must also NAME that state ("California North" starts with "California").
                    a.orEmpty().filter { p -> st == null || p.adminArea.isNullOrBlank() || st.name.startsWith(p.adminArea!!, ignoreCase = true) }
                        .map { GtPlace(listOfNotNull(it.featureName, it.locality, it.adminArea).distinct().joinToString(", "), it.latitude, it.longitude) }
                }.getOrDefault(emptyList())
            }
            groups = found; places = pl; searched = t; busy = false; hiddenTypes = emptySet()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // SEARCHTOP-2026-10-22 (Fred 13:36): the box FIRST, the state choice under it.
        OutlinedTextField(
            value = term, onValueChange = { term = it }, singleLine = true,
            placeholder = { Text("Trail, route, track, waypoint or place", fontSize = 14.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = s.panelText, unfocusedTextColor = s.panelText,
                focusedBorderColor = GtColors.Accent, unfocusedBorderColor = s.edge, cursorColor = GtColors.Accent),
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
                Text(g.name, color = if (g == pick) GtColors.Accent else s.panelText, fontSize = 15.sp,
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
                    ResultRow(r.name + if (rows.count { it.name == r.name } > 1) "  #${r.seq}" else "") { close(); b.openArtifact(r) }
                }
                if (rows.size > 50) Text("First 50 shown — add more of the name.", color = s.panelDim, fontSize = 12.sp)
            }
            if (places.isNotEmpty() && "Places" !in hiddenTypes) {
                Header("Places (${places.size})")
                places.forEach { p -> ResultRow(p.name) { close(); b.goToPlace(p.lat, p.lon) } }
            }
        }
    }
}

@Composable
private fun Header(t: String) {
    Text(t, color = GtScheme.current.value.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ResultRow(label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp)) {
        Text(label, color = GtScheme.current.value.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("›", color = GtColors.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScopeSeg(label: String, on: Boolean, onClick: () -> Unit) {
    val s = GtScheme.current.value
    androidx.compose.foundation.layout.Box(Modifier.background(if (on) Color(0xFF1565C0) else Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(label, color = if (on) Color.White else s.panelText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** GTSEARCH: a result-type chip -- on = its group shows. */
@Composable
private fun TypeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val s = GtScheme.current.value
    androidx.compose.foundation.layout.Box(Modifier.border(1.dp, if (on) Color(0xFF1565C0) else s.edge, RoundedCornerShape(16.dp))
        .background(if (on) Color(0xFF1565C0) else Color.Transparent, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = if (on) Color.White else s.panelDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
