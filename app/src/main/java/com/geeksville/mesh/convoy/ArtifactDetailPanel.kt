package com.geeksville.mesh.convoy

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ArtifactDetailPanel -- standalone, callable detail popup for ANY artifact.
 *
 * Self-loads its row via onLoadDetail (getArtifactDetail) -- does NOT depend on a
 * list collection. Owns FIT. Launched from: name search, select/edit row-tap,
 * and (future) map artifact popup. Dismisses on FIT or CLOSE; performs NO
 * list-state save -- it never touches convoy_panel.json except via FIT's own write.
 *
 * @param artifactType plural ("Tracks"/"Trails"/"Waypoints"/"Routes") -- drives gating + singular
 * @param id artifact id
 * @param name optional instant title before load resolves
 * @param onDismiss close the popup
 */
@Composable
fun ArtifactDetailPanel(
    artifactType: String,
    id: String,
    name: String? = null,
    mapKey: String = "convoy",
    fitWebView: android.webkit.WebView? = null,
    onLoadDetail: ((String, String) -> Map<String, String?>)? = null,
    onLoadAliases: ((String, String) -> List<Map<String, String?>>)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
    onExport: ((String) -> Unit)? = null,
    /**
     * NARRBTN-2026-08-23Y: open this artifact's narrative. Fred, 08-23: "narrative
     * is just a button on details" -- so no conditional visibility and no
     * existence check. Tap it and either the narrative appears or the notes
     * panel says nothing is recorded.
     * Nullable like its siblings: a screen that does not offer it passes nothing.
     */
    onShowNotes: ((String) -> Unit)? = null,
    /* RIDECREATE-2026-09-22: make a ride from this route. Shown only for routes
     * (and tracks, once the convert step is wired). Nullable like its siblings --
     * the PLANNER passes it and gets the button; the frozen convoy map passes
     * nothing and is untouched. Both callers are real, so the option belongs. */
    onAddRide: ((String, String) -> Unit)? = null,
    // TRACKRIDE-2026-09-26 (Fred): CREATE RIDE on a TRACK -> (track id, route name). Optional like onAddRide (CODE
    // RULE 1): only the planner, which can create rides, passes it; other callers show no button.
    // ROUTETH-2026-09-27 (Fred): + the route's TRAILHEAD, chosen in the dialog (a route is never saved without one).
    onCreateRideFromTrack: ((String, String, String, ConvoyArtifactOps.RouteTrailhead) -> Unit)? = null,   // TRACKDESC-2026-09-26: (track id, route name, description)
    /* SATFIXES-2026-08-29: build six more from this route's recipe.
     * ⚠ Unlike onShowNotes, which is offered unconditionally, this one is
     * passed only when the route DB actually holds a recipe — a hand-drawn or
     * imported route has none and simply offers nothing. */
    onBuildFromRecipe: ((String) -> Unit)? = null,
    onDownloadMaps: ((String) -> Unit)? = null,
    // CORRIDOR-WORKER-2026-07-24: side-by-side with SAVE MAPS so the same track can be
    // run both ways and compared. Nullable like its sibling - a screen that
    // does not offer corridor simply passes nothing.
    onDownloadCorridor: ((String) -> Unit)? = null,
    onChangeType: ((String, String) -> Unit)? = null,
    onDeleteAlias: ((String) -> Unit)? = null,
    onDismiss: (String?, String?) -> Unit
) {
    val aMono = FontFamily.Monospace
    val aGreen = Color(0xFF39FF14)
    val aBlue = Color(0xFF4DA6FF)
    val aOrange = Color(0xFFFF8C42)
    val aDim = Color(0xFF7A8DA0)
    val ctx = LocalContext.current

    val singular = artifactType.lowercase().removeSuffix("s")
    var showRideNameDialog by remember { mutableStateOf(false) }   // TRACKRIDE-2026-09-26
    var rideRouteName by remember { mutableStateOf("") }
    var rideRouteDesc by remember { mutableStateOf("") }   // TRACKDESC-2026-09-26
    // ROUTETH-2026-09-27: the trailhead choice -- an index into the nearby trailheads, NEW_TH = add one at the start, -1 = none yet.
    val NEW_TH = -2
    var thPick by remember { mutableStateOf(-1) }
    var thNewName by remember { mutableStateOf("") }
    val detailFields = remember(id) { onLoadDetail?.invoke(singular, id) ?: emptyMap() }
    val dName = detailFields["name"] ?: name ?: "Unnamed"

    var showRenameDialog by remember(id) { mutableStateOf(false) }
    var renameText by remember(id) { mutableStateOf("") }
    var showDeleteConfirm by remember(id) { mutableStateOf(false) }
    var showTypeChooser by remember(id) { mutableStateOf(false) }
    var showTech by remember(id) { mutableStateOf(false) }
    var aliasRows by remember(id) {
        mutableStateOf(onLoadAliases?.invoke(singular, id) ?: emptyList())
    }
    fun reloadAliases() { aliasRows = onLoadAliases?.invoke(singular, id) ?: emptyList() }

    AlertDialog(
        onDismissRequest = { onDismiss(null, null) },
        title = {
            Column {
                Text(dName, color = Color.White, fontSize = 14.sp,
                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Type: ${singular.uppercase()}", color = aDim, fontSize = 10.sp,
                    fontFamily = aMono)
            }
        },
        text = {
            Row(modifier = Modifier.fillMaxWidth()) {
                // -- LEFT RAIL: function list --
                Column(modifier = Modifier.width(118.dp)) {
                    if (artifactType != "Trails" && onRename != null) {
                        DetailActionButton("RENAME", aBlue) { renameText = dName; showRenameDialog = true }
                    }
                    // RIDERTRAILDELETE-2026-09-08: trails were excluded outright.
                    // ⭐ Now: a trail YOU created can be deleted; a surveyed one
                    // still cannot. Fred: "will only show delete as an option if
                    // it was rider created."
                    // ⚠ Deleting an OSM or UGRC trail would achieve nothing --
                    // the next clear-and-reload brings it back from the
                    // catalogue -- and an action that quietly undoes itself is
                    // worse than no action.
                    // ⚠ source_id arrives because getArtifactDetail does SELECT *
                    // on the spatial row AND trail_properties and merges them.
                    val riderMade = detailFields["source_id"] == "rides"
                    if ((artifactType != "Trails" || riderMade) && onDelete != null) {
                        DetailActionButton("DELETE", Color(0xFFFF6B6B)) { showDeleteConfirm = true }
                    }
                    if (onShare != null) { DetailActionButton("SHARE", aGreen) { onShare(id) } }
                    if (onExport != null) { DetailActionButton("EXPORT", aGreen) { onExport(id) } }
                    // NARRBTN-2026-08-23Y
                    // ⭐ RECIPEBTN-2026-08-29: a rider knows what an overview is.
                    // "Narrative" is our word for the generated prose.
                    if (onShowNotes != null) { DetailActionButton("OVERVIEW", aOrange) { onShowNotes(id) } }
                    // TRACKRIDE-2026-09-26 (Fred): a track -> a route (named, editable) -> the ride form.
                    if (onCreateRideFromTrack != null && singular == "track") {
                        DetailActionButton("CREATE RIDE", aGreen) { rideRouteName = dName; thPick = -1; thNewName = dName + " trailhead"; showRideNameDialog = true }
                    }
                    if (showRideNameDialog && onCreateRideFromTrack != null) {
                        // ROUTETH-2026-09-27: the track's start (lon, lat) and the trailheads within 1/2 mile of it, nearest first.
                        val trackStart = remember(id) { ConvoyArtifactOps.trackStart(id) }
                        val thNear = remember(trackStart) { trackStart?.let { SpatialDbManager.trailheadsNear(it.second, it.first) } ?: emptyList() }
                        val thReady = thPick in thNear.indices || (thPick == NEW_TH && thNewName.isNotBlank() && trackStart != null)
                        val taken = rideRouteName.isNotBlank() && SpatialDbManager.routeNameExists(rideRouteName.trim())
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showRideNameDialog = false },
                            title = { androidx.compose.material3.Text("Create a ride from this track" /* TRACKRIDE2-2026-09-26 */) },
                            text = {
                                androidx.compose.foundation.layout.Column {
                                    androidx.compose.material3.Text("The track becomes a route with this name (the track itself is not changed). The map then fits to the route, and the ride form opens.")
                                    androidx.compose.material3.OutlinedTextField(value = rideRouteName, onValueChange = { rideRouteName = it }, singleLine = true,
                                        label = { androidx.compose.material3.Text("Route name") })
                                    // TRACKDESC-2026-09-26 (Fred): the first line is automatic; the rider's description follows it.
                                    androidx.compose.material3.Text("First line (automatic): Created from track $dName \u2014 its length in miles", color = aDim, fontSize = 11.sp)
                                    androidx.compose.material3.OutlinedTextField(value = rideRouteDesc, onValueChange = { rideRouteDesc = it }, minLines = 3,
                                        label = { androidx.compose.material3.Text("Route description") },
                                        placeholder = { androidx.compose.material3.Text("Describe the route: skill level, terrain, highlights, cautions\u2026") })
                                    // ROUTETH-2026-09-27 (Fred): the route's TRAILHEAD -- a nearby one, or a new one at the track's start.
                                    androidx.compose.material3.Text("Trailhead (required)", fontSize = 13.sp)
                                    if (trackStart == null) androidx.compose.material3.Text("This track has no start point.", color = Color(0xFFFF6B6B))
                                    thNear.forEachIndexed { i, c ->
                                        androidx.compose.material3.TextButton(onClick = { thPick = i }) {
                                            androidx.compose.material3.Text((if (thPick == i) "\u25C9  " else "\u25CB  ") + c.name + "  \u00b7  " + "%.2f".format(c.miles) + " mi")
                                        }
                                    }
                                    if (trackStart != null) androidx.compose.material3.TextButton(onClick = { thPick = NEW_TH }) {
                                        androidx.compose.material3.Text((if (thPick == NEW_TH) "\u25C9  " else "\u25CB  ") + "Add a trailhead at the track's start")
                                    }
                                    if (thPick == NEW_TH) androidx.compose.material3.OutlinedTextField(value = thNewName, onValueChange = { thNewName = it }, singleLine = true,
                                        label = { androidx.compose.material3.Text("New trailhead's name") })
                                    if (thNear.isEmpty() && trackStart != null) androidx.compose.material3.Text("No trailhead within \u00bd mile of the track's start \u2014 add one here.", color = aDim, fontSize = 11.sp)
                                    if (taken) androidx.compose.material3.Text("A route with this name already exists \u2014 choose another.", color = Color(0xFFFF6B6B))
                                }
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(enabled = rideRouteName.isNotBlank() && !taken && thReady, onClick = {
                                    val th = if (thPick == NEW_TH) ConvoyArtifactOps.RouteTrailhead(thNewName.trim(), trackStart!!.second, trackStart.first, true)
                                             else thNear[thPick].let { c -> ConvoyArtifactOps.RouteTrailhead(c.name, c.lat, c.lon, false) }
                                    showRideNameDialog = false; onCreateRideFromTrack(id, rideRouteName.trim(), rideRouteDesc.trim(), th)
                                }) { androidx.compose.material3.Text("CREATE ROUTE & RIDE") }
                            },
                            dismissButton = { androidx.compose.material3.TextButton(onClick = { showRideNameDialog = false }) { androidx.compose.material3.Text("Cancel") } },
                        )
                    }
                    if (onAddRide != null && singular == "route") {
                        DetailActionButton("ADD A RIDE", aGreen) { onAddRide(singular, id) }
                    }
                    /* ⭐ Shown only when this route carries a recipe. Absent for
                     * hand-drawn and imported routes, and for drafts, which are
                     * not in the route DB at all — so no flag is needed. */
                    if (onBuildFromRecipe != null) {
                        DetailActionButton("BUILD ROUTES FROM RECIPE", aOrange) {
                            onBuildFromRecipe(id)
                        }
                    }
                    if (artifactType == "Waypoints" && onChangeType != null) {
                        DetailActionButton("CHANGE TYPE", aOrange) { showTypeChooser = true }
                    }
                    // CORRIDOR-CUTOVER-2026-07-24: the area SAVE MAPS button is GONE.
                    // Corridor measured ~107,000 tiles against 1,000,000+ for the
                    // same track (bar 10) - its bbox was ~95% empty desert. The
                    // side-by-side existed to produce that comparison and has.
                    // DELETED rather than flagged off: a disabled path left in
                    // place is how gridCells and downloadMapsForTrackHash both
                    // outlived their replacements and later looked live.
                    // NOTE `onDownloadMaps` stays on the SIGNATURE - both screens
                    // still pass it - but nothing renders it now, so the area
                    // path is unreachable from this panel.
                    // ROUTECORR-2026-08-10C: routes reach the same pipeline. The corridor lookups
                    // resolve either table by geom_hash, so this one callback
                    // serves both and there is nothing route-specific below it.
                    if ((artifactType == "Tracks" || artifactType == "Routes") &&
                        onDownloadCorridor != null) {
                        DetailActionButton("SAVE MAPS", aGreen) {
                            val gh = detailFields["geom_hash"]
                            if (!gh.isNullOrBlank()) onDownloadCorridor(gh)
                        }
                    }
                    DetailActionButton("FIT", aBlue) {
                        // onDismiss-only: ConvoyScreen's FIT branch owns the write (live vars +
                        // saveConvoyState). The earlier double-call to ConvoyArtifactOps.fit()
                        // wrote a second JSON that the onDismiss save then clobbered -> removed.
                        onDismiss(artifactType, id)
                    }
                }

                Spacer(Modifier.width(10.dp))

                // -- RIGHT COLUMN: badge + aliases + full-data --
                Column(modifier = Modifier.weight(1f)) {
                    Text(singular.uppercase(), color = aDim, fontSize = 8.sp,
                        fontFamily = aMono, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))

                    Text("ALIASES", color = aOrange, fontSize = 9.sp,
                        fontFamily = aMono, fontWeight = FontWeight.Bold)
                    if (aliasRows.isEmpty()) {
                        Text("none", color = aDim, fontSize = 9.sp, fontFamily = aMono)
                    } else {
                        aliasRows.forEach { a ->
                            val aId = a["alias_id"] ?: ""
                            val pref = a["is_preferred"] == "1"
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (pref) "\u2605" else "\u2606",
                                    color = if (pref) aGreen else aDim, fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        android.util.Log.i("ArtifactDetail", "Preferred name-swap not yet built")
                                    }.padding(end = 4.dp))
                                Text(a["alias"] ?: "", color = aBlue, fontSize = 9.sp,
                                    fontFamily = aMono, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text(a["source"] ?: "", color = aDim, fontSize = 7.sp,
                                    fontFamily = aMono, modifier = Modifier.padding(horizontal = 3.dp))
                                if (onDeleteAlias != null && aliasRows.size > 1) {
                                    Text("\u00d7", color = Color(0xFFFF6B6B), fontSize = 12.sp,
                                        modifier = Modifier.clickable { onDeleteAlias(aId); reloadAliases() }
                                            .padding(start = 2.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    Text("DETAILS", color = aOrange, fontSize = 9.sp,
                        fontFamily = aMono, fontWeight = FontWeight.Bold)
                    // -- CARTO TYPE (2026-06-19): translated text, colored; never the code --
                    run {
                        val (ctColor, ctLabel) = cartoStyle(detailFields["carto_code"])
                        // Full-row BAND in the carto color w/ near-black text -- survives
                        // high-visibility mode (which can flatten text color to b/w).
                        Row(modifier = Modifier.fillMaxWidth()
                            .background(ctColor, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)) {
                            Text("Carto Type", color = Color(0xCC000000), fontSize = 8.sp, fontFamily = aMono,
                                modifier = Modifier.width(96.dp))
                            Text(ctLabel, color = Color(0xFF111111), fontSize = 9.sp, fontFamily = aMono,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                    }
                    if (detailFields.isEmpty()) {
                        Text("no additional details", color = aDim, fontSize = 9.sp, fontFamily = aMono)
                    } else {
                        val techKeys = setOf("min_lat", "max_lat", "min_lon", "max_lon", "created_at", "updated_at", "geom_hash")
                        // [2026-07-01] Two-column formatted metrics grid: friendly labels + units,
                        // ordered, paired two-per-row to shrink height. carto_code handled above (band).
                        val skip = techKeys + setOf("name", "carto_code", "max_speed_mph")
                        val shownKeys = detailFields.keys
                            .filter { k -> !detailFields[k].isNullOrBlank() && k !in skip }
                            .sortedBy { k -> detailOrder(k) }
                        shownKeys.chunked(2).forEach { pair ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { k ->
                                    val vShow = formatDetailValue(k, detailFields[k] ?: "")
                                    Row(modifier = Modifier.weight(1f).padding(end = 4.dp, top = 1.dp, bottom = 1.dp)) {
                                        Text(prettyLabel(k), color = aDim, fontSize = 8.sp, fontFamily = aMono,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.width(66.dp))
                                        Text(vShow, color = Color(0xFFB8C4D4), fontSize = 8.sp,
                                            fontFamily = aMono, fontWeight = FontWeight.Bold, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        val hasTech = detailFields.any { (k, v) -> k in techKeys && !v.isNullOrBlank() }
                        if (hasTech) {
                            Text(
                                (if (showTech) "\u25be technical" else "\u25b8 technical"),
                                color = aOrange, fontSize = 8.sp, fontFamily = aMono,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showTech = !showTech }.padding(top = 2.dp)
                            )
                            if (showTech) {
                                detailFields.forEach { (k, v) ->
                                    if (v.isNullOrBlank() || k !in techKeys) return@forEach
                                    val show = if (k == "geom_hash" && v.length > 12) v.take(12) + "\u2026" else v
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Text(k, color = aDim, fontSize = 8.sp, fontFamily = aMono,
                                            modifier = Modifier.width(96.dp))
                                        Text(show, color = Color(0xFFB8C4D4), fontSize = 8.sp,
                                            fontFamily = aMono, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onDismiss(null, null) }) { Text("CLOSE") }
        }
    )

    // -- Rename Dialog --
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename $artifactType") },
            text = {
                TextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename?.invoke(id, renameText); showRenameDialog = false; onDismiss(null, null)
                }) { Text("RENAME") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("CANCEL") }
            }
        )
    }

    // -- Delete Confirmation --
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete $dName?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete?.invoke(id); showDeleteConfirm = false; onDismiss(null, null)
                }) { Text("DELETE", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("CANCEL") }
            }
        )
    }

    // -- Type Chooser (Waypoints only) --
    if (showTypeChooser) {
        AlertDialog(
            onDismissRequest = { showTypeChooser = false },
            title = { Text("Change Waypoint Type") },
            text = {
                Column {
                    listOf("trailhead", "fuel", "gate", "hazard", "scenic",
                        "water", "camp", "parking", "rally", "other").forEach { wType ->
                        TextButton(onClick = {
                            onChangeType?.invoke(id, wType); showTypeChooser = false; onDismiss(null, null)
                        }) { Text(wType.replaceFirstChar { it.uppercase() }) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTypeChooser = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun DetailActionButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp)
    )
}


/**
 * CartoCode -> (band color, type label) for the detail footer.
 *
 * PAIRED-EDIT WARNING: this mapping ALSO lives in the map JS
 * (app/src/main/assets/convoy_map.html and grouptrack_map.html), which colors the
 * trail LINES on the map. If CartoCode colors or labels change, update BOTH here
 * AND those two HTML files. (One field, two language representations -- unavoidable
 * since map rendering is JS and the detail card is Kotlin.)
 *
 * Source of truth: AllDocs manual section 9A.2.
 */
// Data stores carto_code as "N - Label" (e.g. "4 - Road-concurrent"); key off the
// LEADING DIGIT so all label variants of a code map to one color. Codes 1-8 per
// trail_properties; blank/unknown -> cyan "Unspecified" (default preserved).
// [2026-07-01] DETAILS display helpers: friendly labels, unit formatting, field order.
private fun prettyLabel(key: String): String = when (key) {
    "distance_miles"    -> "Distance"
    "duration_minutes"  -> "Duration"
    "avg_speed_mph"     -> "Avg Spd"
    "max_speed_mph"     -> "Max Spd"
    "elevation_gain_ft" -> "Elev"
    "point_count"       -> "Points"
    "recorded_at"       -> "Recorded"
    "source_format"     -> "Source"
    "shared"            -> "Shared"
    "distance"          -> "Distance"
    "length_miles"      -> "Length"

    // TRAILLABELS-2026-09-03: the trail fields. ⚠ The fallback below
    // title-cases, so these read as "Carto Code Source" and "Ugrc Trail Class"
    // without it -- not raw, but not language either.
    // ⭐ The four classification fields get names that say what they MEAN
    // rather than what the column is called.
    "carto_code"        -> "Type"
    "carto_code_source" -> "Source said"
    "land_status"       -> "Land"
    "use_type"          -> "Use"
    "source_id"         -> "Source"
    "data_source"       -> "Source"
    "smoothness"        -> "Roughness"
    "tracktype"         -> "Firmness"
    "surface"           -> "Surface"
    "surface_type"      -> "Surface"
    "designated_uses"   -> "Uses"
    "motorized_allowed" -> "Motorized"
    "horse_allowed"     -> "Horses"
    "ada_accessible"    -> "Accessible"
    "owner_steward"     -> "Managed by"
    "other_restrictions"-> "Restrictions"
    "hike_difficulty"   -> "Hiking"
    "bike_difficulty"   -> "Biking"
    "trail_class"       -> "Class"
    "system_name"       -> "System"
    "recreation_area"   -> "Area"
    "ref_code"          -> "Road number"
    "trans_network"     -> "Network"
    "geom_hash"         -> "Shape id"
    "trail_id"          -> "Id"
    "osm_id"            -> "OSM id"
    "status"            -> "Status"
    "county"            -> "County"

    else -> key.replace('_', ' ')
        .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

private fun formatDetailValue(key: String, raw: String): String {
    val v = raw.trim()
    if (v.isEmpty()) return v
    fun num(): Double? = v.toDoubleOrNull()
    fun oneDp(d: Double): String = (Math.round(d * 10.0) / 10.0).let {
        if (it == Math.floor(it)) it.toInt().toString() else it.toString()
    }
    return when (key) {
        "distance_miles", "length_miles", "distance" -> num()?.let { "${oneDp(it)} mi" } ?: v
        "duration_minutes" -> num()?.let { "${it.toInt()} min" } ?: v
        "avg_speed_mph", "max_speed_mph" -> num()?.let { "${it.toInt()} mph" } ?: v
        "elevation_gain_ft" -> num()?.let { "${it.toInt()} ft" } ?: v
        "point_count" -> num()?.let { "%,d".format(it.toInt()) } ?: v
        "shared" -> if (v == "1") "Yes" else if (v == "0") "No" else v
        "source_format" -> v.uppercase()
        "recorded_at" -> v.take(10)   // YYYY-MM-DD

        // READABLE-2026-09-02: ⛔ INTERNAL IDENTIFIERS WERE REACHING THE RIDER.
        // Fred, 09-02, tapping a trail: "trail source is a code." `osm` and
        // `ugrc_utah_trails` are how the catalogue keys its sources; they mean
        // nothing on a screen.
        // ⭐ The catalogue already carries proper names -- these mirror them.
        // ⚠ Unknown ids fall through UNCHANGED rather than being prettified by
        // a rule: a source added later shows its key, which is ugly and honest,
        // instead of a guess.
        "source_id", "data_source" -> when (v) {
            "osm" -> "OpenStreetMap"
            "ugrc_utah_trails" -> "Utah Trails and Pathways"
            "usfs_nfs_trails" -> "US Forest Service"
            "nps_public_trails" -> "National Park Service"
            "blm_gtlf_all" -> "Bureau of Land Management"
            "usgs_national_trails" -> "USGS National Trails"
            "azsp_trails" -> "Arizona State Parks"
            else -> v
        }

        // ⭐⭐ ROUGHNESS IS THE FIELD THAT CHANGES A DECISION, and OSM writes it
        // as VEHICLE CAPABILITY rather than terrain. Untranslated it is
        // meaningless -- "very_horrible" tells a rider nothing, and it is the
        // one attribute that says whether a machine physically gets through.
        // ⚠ The raw token is kept in brackets: it is the thing to search for
        // when comparing against OSM, and hiding it would cost more than the
        // few characters it takes.
        "smoothness" -> when (v) {
            "excellent" -> "Smooth \u2014 any vehicle (excellent)"
            "good" -> "Good \u2014 any vehicle (good)"
            "intermediate" -> "Fair \u2014 normal car (intermediate)"
            "bad" -> "Rough \u2014 careful in a car (bad)"
            "very_bad" -> "Rough \u2014 high clearance (very_bad)"
            "horrible" -> "Very rough \u2014 4WD (horrible)"
            "very_horrible" -> "Severe \u2014 ATV or tractor (very_horrible)"
            "impassable" -> "Impassable \u2014 nothing wheeled (impassable)"
            else -> v
        }

        // ⚠ tracktype is OSM's firmness scale, and grade1..grade5 is no more
        // self-explanatory than smoothness was.
        "tracktype" -> when (v) {
            "grade1" -> "Solid surface (grade1)"
            "grade2" -> "Mostly solid (grade2)"
            "grade3" -> "Even mix (grade3)"
            "grade4" -> "Mostly soft (grade4)"
            "grade5" -> "Soft \u2014 barely a track (grade5)"
            else -> v
        }

        // ⚠ Our own vocabulary, but SHOUTED. Title case reads as a value
        // rather than a constant.
        "land_status" -> when (v) {
            "PUBLIC" -> "Public land"
            "PRIVATE" -> "Private land"
            else -> v
        }
        "use_type" -> when (v) {
            "MOTORIZED" -> "Motorized"
            "NON-MOTORIZED" -> "Non-motorized"
            else -> v
        }

        else -> v
    }
}

// Sensible display order for the metrics grid; unknown keys sort after known ones.
private fun detailOrder(key: String): Int = listOf(
    "distance_miles", "length_miles", "distance", "duration_minutes",
    "avg_speed_mph", "max_speed_mph", "elevation_gain_ft", "point_count",
    "source_format", "shared", "recorded_at"
).indexOf(key).let { if (it < 0) 99 else it }

/**
 * CATCOLOR-2026-09-07. carto_code holds CATEGORY NAMES, not digit-prefixed
 * source values -- it has since 08-31. The previous version of this function
 * keyed off the first CHARACTER against '1'..'8', so 'OHV' fell to the else
 * branch and every trail tapped reported "Unspecified" in cyan.
 *
 * ⭐ THE COLOUR COMES FROM TrailFilterState, which owns map_keys.json and is
 * the same table both maps draw from. A fourth hand-kept copy of the palette is
 * how the panel drifted from the map in the first place.
 *
 * ⚠ The literal fallback is for a panel somehow opened before the filter has
 * loaded. It matches the SHIPPED table; a rider who has restyled a category
 * sees their own colour through the branch above.
 */
private fun cartoStyle(code: String?): Pair<Color, String> {
    val key = code?.trim().orEmpty()
    if (key.isEmpty()) return Color(0xFF00FFFF) to "Unspecified"

    // The rider's own palette, when it is loaded.
    TrailFilterState.style[key]?.let { (hex, _, _) ->
        try {
            return Color(android.graphics.Color.parseColor(hex)) to labelOfCategory(key)
        } catch (_: IllegalArgumentException) {
            // A malformed colour in the rider's file must not break the panel.
        }
    }

    val fallback = when (key) {
        "OHV" -> 0xFF00CCFF
        "track" -> 0xFF00AAFF
        "forestry/access road" -> 0xFF0077DD
        "shape only" -> 0xFF0044AA
        "rider" -> 0xFF2196F3
        "hiking and biking" -> 0xFF66CC66
        "hiking" -> 0xFFFFCC00
        "biking" -> 0xFFAA44FF
        "equestrian" -> 0xFFCC8844
        "steps/bridge" -> 0xFF888888
        else -> 0xFF00FFFF
    }
    return Color(fallback) to labelOfCategory(key)
}

/** Category value -> what a rider reads. Unknown values show verbatim rather
 *  than as "Unspecified": a value we did not anticipate is information. */
private fun labelOfCategory(key: String): String = when (key) {
    "OHV" -> "OHV"
    "track" -> "Track"
    "forestry/access road" -> "Forestry / Access Road"
    "shape only" -> "Shape Only"
    "rider" -> "Rider Trail"
    "hiking and biking" -> "Hiking & Biking"
    "hiking" -> "Hiking"
    "biking" -> "Biking"
    "equestrian" -> "Equestrian"
    "steps/bridge" -> "Steps / Bridge"
    "unknown" -> "Unknown"
    else -> key
}
