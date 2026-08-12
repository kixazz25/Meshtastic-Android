package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------
// ConvoyDownloadPanel -- V2.5 Full Implementation
// Bidirectional area transfer panel: LOCAL imports + NET (V2.6 stubs)
// Sections: Flyover, Artifact Types, Draw Area, Show Downloads
// Source: ScreenReference v5, Download Panel Design May 20 2026
// ----------------------------------------------------------------

data class DownloadBbox(
    val north: Double = 0.0,
    val south: Double = 0.0,
    val east: Double = 0.0,
    val west: Double = 0.0
) {
    val isValid get() = north != 0.0 || south != 0.0
    fun toDisplayN() = String.format("%.2f\u00b0N", north)
    fun toDisplayS() = String.format("%.2f\u00b0N", south)
    fun toDisplayE() = String.format("%.2f\u00b0W", kotlin.math.abs(east))
    fun toDisplayW() = String.format("%.2f\u00b0W", kotlin.math.abs(west))
}

@Composable
fun ConvoyDownloadPanel(
    bbox: DownloadBbox = DownloadBbox(),
    tilesChecked: Boolean = false,
    onTilesCheckedChange: (Boolean) -> Unit = {},
    // PANELROWS-2026-08-05: corridor downloads. Defaulted so the other callers
    // (ConvoyScreen, ConvoyTrackImportScreen) compile untouched.
    // Deliberately NOT folded into onExecuteDownload: that callback's three
    // booleans are all bbox-driven and a corridor has no bbox. Same reasoning
    // as the OSM comment below -- routing lives in the checkbox.
    corridorChecked: Boolean = false,
    onCorridorCheckedChange: (Boolean) -> Unit = {},
    onSelectCorridorTracks: () -> Unit = {},
    trailsChecked: Boolean = false,
    onTrailsCheckedChange: (Boolean) -> Unit = {},
    removeTilesChecked: Boolean = false,
    onRemoveTilesCheckedChange: (Boolean) -> Unit = {},
    // REMOVETRACK-PREVIEW-2026-08-07F: corridor-shaped tile removal. Routing
    // lives in the checkbox (the corridor/OSM idiom) because this operation
    // takes NO AREA and NO SELECTION -- it is all tracks, always -- so it
    // cannot use the tick-then-Draw-Area flow the rows around it use.
    // Deliberately NOT added to onExecuteDownload: that callback's three
    // booleans are all bbox-driven.
    removeTrackChecked: Boolean = false,
    onRemoveTrackCheckedChange: (Boolean) -> Unit = {},
    // RECREATE-2026-08-11A: Recreate Tiles by Source. Takes the slot the greyed
    // Clear Tile Source row held -- that capability lives inside the
    // source-replace flow and needs no direct entry point here.
    recreateSourceChecked: Boolean = false,
    onRecreateSourceCheckedChange: (Boolean) -> Unit = {},
    // OSM-C3B-AREA-2026-07-29: IMPORT OSM. Routing lives in the checkbox, which
    // is why no AreaDrawPurpose is needed. Deliberately NOT part of
    // onExecuteDownload -- OSM must never become a QueueEntry.
    osmChecked: Boolean = false,
    onOsmCheckedChange: (Boolean) -> Unit = {},
    flyoverZoom: Int = 18,
    onFlyoverZoomChange: (Int) -> Unit = {},
    tileEstimate: String = "",
    trailSourceCount: Int = 0,
    isDrawing: Boolean = false,
    onDrawArea: () -> Unit = {},
    onClearArea: () -> Unit = {},
    onExecuteDownload: (tiles: Boolean, trails: Boolean, removeTiles: Boolean) -> Unit = { _, _, _ -> },
    onNavigateToTrailSources: (DownloadBbox) -> Unit = {},
    onShowDownloadedMaps: (Boolean) -> Unit = {},
    onShowMapsInQueue: (Boolean) -> Unit = {},
    onShowDownloadedTrails: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val mono = FontFamily.Monospace
    val blue = Color(0xFF4DA6FF)
    val green = Color(0xFF3fb950)
    val red = Color(0xFFf85149)
    val orange = Color(0xFFd29922)
    val purple = Color(0xFFbc8cff)
    val dimText = Color(0xFF4A6080)
    val panelBg = Color(0xEE131820)
    val rowBg = Color(0xFF1A2A3A)
    val sectionBorder = Color(0xFF30363d)

    // Internal state
    var isNetMode by remember { mutableStateOf(false) }
    // tilesChecked, trailsChecked, removeTilesChecked, flyoverZoom — lifted to parent
    var flyoverMileage by remember { mutableFloatStateOf(0f) }
    var showMaps by remember { mutableStateOf(true) }
    var expandMapControls by remember { mutableStateOf(false) }
    var expandSelectTypes by remember { mutableStateOf(false) }
    var expandDrawArea by remember { mutableStateOf(false) }
    var expandShowDownloads by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(true) }
    // showTrails removed — not needed

    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(10.dp),
        color = panelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {

            // ── HEADER with LOCAL/NET toggle ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isNetMode) "\u2195 TRANSFERS" else "\u2195 DOWNLOADS",
                    color = blue, fontSize = 10.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isNetMode) "NET" else "LOCAL",
                        color = if (isNetMode) purple else blue,
                        fontSize = 8.sp, fontFamily = mono, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = isNetMode,
                        onCheckedChange = { isNetMode = it },
                        modifier = Modifier.height(20.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = purple,
                            checkedTrackColor = purple.copy(alpha = 0.3f),
                            uncheckedThumbColor = blue,
                            uncheckedTrackColor = blue.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            // ── MAP CONTROLS (collapsible) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandMapControls = !expandMapControls },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandMapControls) "▼" else "▶", color = blue, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("MAP CONTROLS", color = blue, fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandMapControls) {
            PanelSectionHeader("FLYOVER", sectionBorder)
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                // Mileage slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mileage", color = dimText, fontSize = 9.sp,
                        fontFamily = mono, modifier = Modifier.width(52.dp))
                    Slider(
                        value = flyoverMileage,
                        onValueChange = { flyoverMileage = it; flyoverMileage = it },
                        valueRange = 0f..200f,
                        modifier = Modifier.weight(1f).height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = blue, activeTrackColor = blue
                        )
                    )
                    Text(
                        String.format("%.1f mi", flyoverMileage),
                        color = Color.White, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.width(42.dp)
                    )
                }
                // Zoom slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom", color = dimText, fontSize = 9.sp,
                        fontFamily = mono, modifier = Modifier.width(52.dp))
                    Slider(
                        value = flyoverZoom.toFloat(),
                        onValueChange = { onFlyoverZoomChange(it.toInt()) },
                        valueRange = 8f..18f,
                        steps = 9,
                        modifier = Modifier.weight(1f).height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = blue, activeTrackColor = blue
                        )
                    )
                    Text(
                        "Z$flyoverZoom",
                        color = Color.White, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.width(42.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── ARTIFACT TYPES (Step 1) ──
            } // end expandMapControls
            // ── SELECT TILES/ARTIFACTS (collapsible) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandSelectTypes = !expandSelectTypes },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandSelectTypes) "▼" else "▶", color = green, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("SELECT TILES / ARTIFACTS", color = green, fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandSelectTypes) {
            Spacer(Modifier.height(4.dp))

            if (!isNetMode) {
                // LOCAL MODE
                // PANELROWS-2026-08-05: ordered live -> stub -> set-elsewhere.
                // The two greyed imports sit LAST on purpose: the next patch adds
                // origin-dependent expansion (BUTTON expands the actionable rows,
                // a TRAIL/OSM import expands those), which wants them contiguous.
                ArtifactCheckRow("Download Tiles by Area", blue, tilesChecked, true) { onTilesCheckedChange(it) }
                ArtifactCheckRow("Download Tiles by Corridor", blue, corridorChecked, true) {
                    onCorridorCheckedChange(it)
                    // Routing lives in the checkbox: ticking it opens the track
                    // picker, the way the OSM import routes from its own checkbox.
                    if (it) onSelectCorridorTracks()
                }
                ArtifactCheckRow("Remove Tiles by Area", red, removeTilesChecked, true) { onRemoveTilesCheckedChange(it) }
                // REMOVETRACK-PREVIEW-2026-08-07F: ACTIVATED. The corridor-shaped
                // deletion this row was reserved for is now built --
                // MBTilesStore.deleteTiles (a0675be42) + the corridor derivation
                // in ConvoyCorridorDelete (0a40ec07e / 733164e12).
                // ⚠ PATCH 1 SCOPE: ticking this runs the PREVIEW and logs counts.
                // It deletes nothing. Patch 2 adds the roll-up panel and the
                // permanent confirm at the point of no return.
                ArtifactCheckRow("Remove Tiles by Track", red, removeTrackChecked, true) {
                    onRemoveTrackCheckedChange(it)
                }
                // V2.7 -- Clear Tile Source drops the whole SAT column (base +
                // both label stores) and is destructive enough to need its own
                // confirm. ⚠ Its warning must travel with it: in the migration
                // flow the user has been told they lose labels until tiles are
                // replaced; as a bare panel row it has none of that framing.
                // Wires to ConvoySourceClear (27dfb433d), which exists.
                // The row itself is the untouched line below.
                // RECREATE-2026-08-11A: RECREATE TILES BY SOURCE -- replaces the greyed
                // Clear Tile Source row.
                //
                // Re-downloads every tile the store already holds, in place.
                // It never asks what a bbox COULD cover, only what the store
                // HAS -- so ground with no tiles never becomes work, disjoint
                // coverage stays disjoint, and corridor shape cannot be lost
                // because the tiles being refreshed ARE the corridor.
                //
                // With INSERT OR REPLACE the old tile stays until the new one
                // lands, so coverage never drops while it runs.
                //
                // *** ITERATION 1: ticking this runs the SCAN and logs what it
                // WOULD submit. It queues nothing and downloads nothing. ***
                ArtifactCheckRow("Recreate Tiles by Source", green, recreateSourceChecked, true) {
                    onRecreateSourceCheckedChange(it)
                }
                Spacer(Modifier.height(2.dp))
                // V3.0 -- artifact downloads from the CONSOLIDATED ALL-USER
                // DATABASE (server-side release). NOT V2.6: these were mislabelled,
                // and a stub promising the wrong release is worse than no badge.
                ArtifactCheckRow("Waypoints", dimText, false, false, "V3.0") {}
                ArtifactCheckRow("Tracks", dimText, false, false, "V3.0") {}
                ArtifactCheckRow("Routes", dimText, false, false, "V3.0") {}
                Spacer(Modifier.height(2.dp))
                // SET ELSEWHERE -- both are driven from the ARTIFACT side and only
                // populate this state. Greyed rather than hidden: a tickable box
                // would offer control that does not exist here, but hiding them
                // would create state that is set and never rendered. They show
                // their real checked value and are simply not interactive.
                ArtifactCheckRow("Import Trails by Area", green, trailsChecked, false) {}
                ArtifactCheckRow("Import OSM by Area", purple, osmChecked, false) {}
            } else {
                // NET MODE - all V2.6 stubs
                ArtifactCheckRow("\u2195 Tiles", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("\u2195 Trails", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("\u2195 Waypoints", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("\u2195 Tracks", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("\u2195 Routes", dimText, false, false, "V2.6") {}
            }

            } // end expandSelectTypes

            // ── DRAW AREA (collapsible) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandDrawArea = !expandDrawArea },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandDrawArea) "▼" else "▶", color = blue, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("DRAW AREA", color = blue, fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandDrawArea) {
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelActionButton(
                    label = if (isDrawing) "Drawing..." else "Draw Area",
                    color = blue,
                    enabled = !isNetMode && (tilesChecked || trailsChecked || removeTilesChecked || osmChecked),
                    modifier = Modifier.weight(1f)
                ) { onDrawArea() }
                PanelActionButton(
                    label = "Clear",
                    color = dimText,
                    enabled = bbox.isValid,
                    modifier = Modifier.weight(0.6f)
                ) { onClearArea() }
            }

            // Bbox display
            if (bbox.isValid) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = rowBg
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Text(
                            "${bbox.toDisplayS()} \u2192 ${bbox.toDisplayN()}  ${bbox.toDisplayW()} \u2192 ${bbox.toDisplayE()}",
                            color = dimText, fontSize = 8.sp, fontFamily = mono
                        )
                        if (tileEstimate.isNotEmpty()) {
                            Text(
                                "Tiles: $tileEstimate",
                                color = Color.White, fontSize = 8.sp, fontFamily = mono
                            )
                        }
                        if (trailsChecked && trailSourceCount > 0) {
                            Text(
                                "Trails: $trailSourceCount sources found",
                                color = green, fontSize = 8.sp, fontFamily = mono
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            } // end expandDrawArea

            // ── RECIPEPANEL-2026-08-12K: MAP RECOVERY (collapsible) ──
            //
            // DISPLAY ONLY - this is how you SEE that the daily recipe writer
            // is running. Today's date under ARCHIVED means it ran.
            //
            // Two sections because they mean different things:
            //   ARCHIVED = this device's own saves
            //   DOWNLOAD = the inbox, where a mailed recipe lands
            //
            // ⚠ APPLY and EMAIL are the recovery ACTIONS and are not wired yet.
            var expandRecovery by remember { mutableStateOf(false) }
            var archivedRows by remember { mutableStateOf(listOf<String>()) }
            var inboxRows by remember { mutableStateOf(listOf<String>()) }
            LaunchedEffect(expandRecovery) {
                if (!expandRecovery) return@LaunchedEffect
                fun scan(d: java.io.File): List<String> {
                    val out = ArrayList<String>()
                    try {
                        // NEWEST FIRST - the name carries the date, so a plain
                        // descending sort by name is chronological.
                        val fs = d.listFiles()?.filter {
                            it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps")
                        }?.sortedByDescending { it.name } ?: emptyList()
                        for (f in fs) {
                            val date = f.name.removePrefix("grouptrack_maps_")
                                .removeSuffix(".gtmaps")
                            try {
                                // ⚠ best effort: a half-written or hand-edited
                                // file reads as unreadable, never breaks the panel.
                                val o = org.json.JSONObject(f.readText())
                                val a = o.getJSONArray("slots")
                                var tiles = 0L
                                for (i in 0 until a.length()) tiles += a.getJSONObject(i).optLong("tiles")
                                out.add("$date   ${a.length()} slot(s)   $tiles tiles   ${f.length() / 1024} KB")
                            } catch (e: Exception) {
                                out.add("$date   unreadable (${f.length()} bytes)")
                            }
                        }
                    } catch (e: Exception) {
                        out.add("could not read: ${e.message}")
                    }
                    return out
                }
                val ext = android.os.Environment.getExternalStorageDirectory()
                archivedRows = scan(java.io.File(ext, "Documents/GroupTrack/recipes"))
                inboxRows = scan(android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS))
            }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandRecovery = !expandRecovery },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandRecovery) "▼" else "▶", color = Color(0xFF8b949e), fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("MAP RECOVERY", color = Color(0xFF8b949e), fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandRecovery) {
                Spacer(Modifier.height(4.dp))
                Text("A daily record of what your maps cover. Kept 60 days.",
                    color = Color(0xFF8b949e), fontSize = 9.sp, fontFamily = mono,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                Spacer(Modifier.height(4.dp))
                Text("ARCHIVED GTMAPS", color = Color(0xFF4DA6FF), fontSize = 9.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp))
                if (archivedRows.isEmpty()) {
                    Text("none yet - written once a day at startup",
                        color = Color(0xFF8b949e), fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp))
                } else archivedRows.forEach { r ->
                    Text(r, color = Color(0xFFc9d1d9), fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text("DOWNLOAD GTMAPS", color = Color(0xFF4DA6FF), fontSize = 9.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp))
                if (inboxRows.isEmpty()) {
                    Text("none - recipes sent to you arrive here",
                        color = Color(0xFF8b949e), fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp))
                } else inboxRows.forEach { r ->
                    Text(r, color = Color(0xFFc9d1d9), fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp))
                }
                Spacer(Modifier.height(6.dp))
            }
            // ── SHOW DOWNLOADS (collapsible) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandShowDownloads = !expandShowDownloads },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandShowDownloads) "▼" else "▶", color = Color(0xFF8b949e), fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("SHOW DOWNLOADS", color = Color(0xFF8b949e), fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandShowDownloads) {
            Spacer(Modifier.height(4.dp))
            OverlayToggleRow("Downloaded maps area", blue, showMaps) {
                showMaps = it; onShowDownloadedMaps(it)
            }
            OverlayToggleRow("Maps in queue", red, showQueue) {
                showQueue = it; onShowMapsInQueue(it)
            }
            } // end expandShowDownloads
            // Refresh button (always visible, outside accordion)
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    onShowDownloadedMaps(true)
                    onShowMapsInQueue(true)
                },
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1A2A3A)
            ) {
                Text("REFRESH DOWNLOADS", color = Color(0xFF4DA6FF),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        .fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

// ── Helper composables ──

@Composable
private fun PanelSectionHeader(title: String, borderColor: Color) {
    Text(
        title,
        color = Color(0xFF8b949e),
        fontSize = 8.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth()
            .border(width = 0.5.dp, color = borderColor, shape = RoundedCornerShape(0.dp))
            .padding(vertical = 2.dp)
    )
}

@Composable
private fun ArtifactCheckRow(
    label: String,
    color: Color,
    checked: Boolean,
    enabled: Boolean,
    badge: String = "",
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            modifier = Modifier.size(18.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = color,
                uncheckedColor = color.copy(alpha = 0.4f),
                checkmarkColor = Color.Black
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = if (enabled) color else color.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (badge.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = Color(0xFF1A2A3A)
            ) {
                Text(
                    badge,
                    color = Color(0xFF4A6080),
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun PanelActionButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onShowDownloadedMaps: (Boolean) -> Unit = {},
    onShowMapsInQueue: (Boolean) -> Unit = {},
    onShowDownloadedTrails: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(4.dp),
        color = if (enabled) color.copy(alpha = 0.15f) else Color(0xFF1A2A3A).copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            if (enabled) color.copy(alpha = 0.5f) else Color(0xFF30363d)
        )
    ) {
        Text(
            label,
            color = if (enabled) color else Color(0xFF4A6080),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
                .fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun OverlayToggleRow(
    label: String,
    color: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(18.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = color,
                uncheckedColor = color.copy(alpha = 0.4f),
                checkmarkColor = Color.Black
            )
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier.size(10.dp)
                .background(color.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = Color(0xFF8b949e),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
