package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState   // GTOFFGRID-2026-10-23
import com.geeksville.mesh.convoy.DownloadQueueManager   // GTOFFGRID-2026-10-23
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.DownloadBbox

// AREADL-2026-11-25: GtArea (purpose, ready, box, label, take) MOVED to commonMain -- GtAreaDraw.kt, same package.

/**
 * The draw bar while a box is awaited, then the module the box was drawn for. [js] runs a call on the map page.
 * Every module is its own screen (MapScreens.kt) -- this only chooses which one.
 */
@Composable
fun GtAreaHost(js: (String) -> Unit, barModifier: Modifier) {
    GtOffGrid.js = js   // GTOFFGRID-2026-10-23: the off-grid overlays draw on this page
    // GTOFFGRID: "Downloading" follows the queue while it is ticked.
    val qNow = DownloadQueueManager.queue.collectAsState().value
    LaunchedEffect(qNow) { if (GtOffGrid.downloading.value) GtOffGrid.setDownloading(true) }
    GtAreaDrawBar(js, barModifier)   // AREADL-2026-11-25: the SHARED draw bar (and its clear-the-box rule)
    val done = { GtArea.ready.value = "" }
    val bb = GtArea.box.value.let { DownloadBbox(north = it.north, south = it.south, east = it.east, west = it.west) }   // AREADL-2026-11-25
    when (GtArea.ready.value) {
        "download" -> GtDownloadAreaScreen(bb, done)
        "remove" -> GtRemoveAreaScreen(bb, done)
        "rmfeat" -> GtRemoveFeaturesScreen(bb, done)   // TRAILCLEAN-2026-11-04
        "trailsrc" -> GtTrailSourcesScreen(done)   // TRAILCLEAN-2026-11-04
        "import" -> GtImportAreaScreen(bb, done)
        "recipes" -> GtRecipesScreen(done)   // GTRECIPES-2026-10-23
        "emergency" -> GtEmergencyScreen(done)   // VECEMERGENCY-2026-10-31
        "state" -> GtImportStateScreen(done)   // GTIMPORTS-2026-10-23
        "gpx" -> GtImportGpxScreen(done)   // GTIMPORTS-2026-10-23
        "rider" -> GtRiderTrailsScreen(done)   // GTIMPORTS-2026-10-23
        "gpxtrails" -> GtImportGpxTrailsScreen(done)   // MAPIMPORTS-2026-10-30
        "convert" -> GtConvertGpxScreen(done)   // MAPIMPORTS-2026-10-30
        "sync" -> GtSyncTracksScreen(done)   // GTSYNC-2026-10-23
        "radius" -> GtAreaRadiusScreen(done)   // GTRADIUS-2026-10-23
        "rmtrack" -> GtRemoveTrackScreen(done)   // GTHEAVY-2026-10-23
        "refresh" -> GtRefreshSourceScreen(done)   // GTHEAVY-2026-10-23
        "exportall" -> GtExportFeaturesScreen(done)   // EXPORTONE-2026-11-18
        "features" -> GtSavedFeaturesScreen(done)   // SAVEDFEAT-2026-11-18
        "bystate" -> GtStateCountsScreen(done)   // AREASKIP-2026-10-24
        "tracks" -> GtDownloadTracksScreen(done)   // GTTRACKS-2026-10-23: no box -- opened straight from the menu
    }
    // AREADL-2026-11-25: the drawn box's clear rule moved into GtAreaDrawBar with the bar.
}
