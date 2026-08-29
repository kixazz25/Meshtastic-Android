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
                    if (artifactType != "Trails" && onDelete != null) {
                        DetailActionButton("DELETE", Color(0xFFFF6B6B)) { showDeleteConfirm = true }
                    }
                    if (onShare != null) { DetailActionButton("SHARE", aGreen) { onShare(id) } }
                    if (onExport != null) { DetailActionButton("EXPORT", aGreen) { onExport(id) } }
                    // NARRBTN-2026-08-23Y
                    // ⭐ RECIPEBTN-2026-08-29: a rider knows what an overview is.
                    // "Narrative" is our word for the generated prose.
                    if (onShowNotes != null) { DetailActionButton("OVERVIEW", aOrange) { onShowNotes(id) } }
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
        else -> v
    }
}

// Sensible display order for the metrics grid; unknown keys sort after known ones.
private fun detailOrder(key: String): Int = listOf(
    "distance_miles", "length_miles", "distance", "duration_minutes",
    "avg_speed_mph", "max_speed_mph", "elevation_gain_ft", "point_count",
    "source_format", "shared", "recorded_at"
).indexOf(key).let { if (it < 0) 99 else it }

private fun cartoStyle(code: String?): Pair<Color, String> = when (code?.trim()?.firstOrNull()) {
    '1' -> Color(0xFFFFCC00) to "Hiking-Only"
    '2' -> Color(0xFFFF8800) to "Hiking & Biking"
    '3' -> Color(0xFF00C2A8) to "Paved Shared Use"
    '4' -> Color(0xFF00AAFF) to "OHV / Road-Concurrent"
    '5' -> Color(0xFFAA44FF) to "Biking-Only"
    '6' -> Color(0xFFB5651D) to "Equestrian"
    '7' -> Color(0xFF9AA0A6) to "Steps"
    '8' -> Color(0xFFE0556E) to "Bridge / Tunnel"
    else -> Color(0xFF00FFFF) to "Unspecified"
}
