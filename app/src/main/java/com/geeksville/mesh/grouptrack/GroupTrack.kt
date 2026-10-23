package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background   // GRPONEMAP-2026-10-22
import androidx.compose.foundation.clickable   // GRPONEMAP-2026-10-22
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState   // MAPTARGET4-2026-10-23
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.GroupMode

/**
 * SHELL-2026-10-22 (2.7c ONE MAP, Fred 10-22): the launch layer -- the new start screen's scaffolding, drawn over the map.
 *
 * Fred 09:30: "remove everything from the convoy screen as a launch point -- a blank panel essentially -- add all final navigation
 * items and launch points as placeholders, then attach the placeholders to the new scaffolding." Fred 09:33: "a module for each
 * launch point; items move as modules called into the scaffolding"; the last step retires ConvoyScreen.kt and GroupTrack.kt
 * becomes the start screen.
 *
 * FOR NOW the map, recording, radio and dialogs still run in ConvoyScreen; with Settings > Layout = ONE MAP it hides its own
 * controls and draws this layer instead (GtLayout). Layout (the prototype's final design, 10-21):
 *   top centre  -- GPS / zoom information, RECORD under it
 *   left        -- MENU, then zoom + / -
 *   right       -- MODE (ANDROID ONLY / GROUP), INTERNET, KEY, FILTER, CENTRE ON ME
 *   bottom left -- the readout; bottom bar -- Map, Rides, Search, Group (Group layout only)
 * Landscape = riding (panels open from the right edge); portrait = panning (panels open above the bottom bar). Both workable.
 *
 * @param onHelp      opens the docs viewer at a document key ("manual#m-checkin") -- ConvoyScreen's docsOpen.
 * @param onZoom      +1 / -1 to the map (attached in the shell: the map's own zoom).
 * @param mapType     the map type showing (SAT, TOPO, ...) -- sets the colours (SHELL2-2026-10-22).
 * @param online      the ride map's internet check -- the INTERNET button reads Status: Online / Offline (SHELL4-2026-10-22).
 */
@Composable
fun GroupTrackLaunchLayer(
    onHelp: (String) -> Unit,
    onZoom: (Int) -> Unit,
    mapType: String,
    /** GTMAP-2026-10-22: the map bar's map-type slots, and the change it makes. Required. */
    mapTypes: List<String>,
    onMapType: (String) -> Unit,
    online: Boolean,
    /** SHELL8-2026-10-22: true = the HUD shows GROUP. Required -- the only caller is ConvoyScreen (CODE RULE 1). */
    showingGroup: Boolean,
    /** SHELL11-2026-10-22: true once checked in (Group) or recording (Android only). Required (CODE RULE 1). */
    onRide: Boolean,
    /** ATTACH1-2026-10-22 THE BRIDGE: process id -> today's function. A row with no entry stays "not attached yet". Required. */
    actions: Map<String, () -> Unit>,
    /** GRPONEMAP-2026-10-22: GRP Awareness's live status colour (green talking / red / grey off, pulsing). Required. */
    grpColor: androidx.compose.ui.graphics.Color,
    /** GTFILTER-2026-10-22: FILTER's link to the map's display state. Required. */
    filter: GtFilterBridge,
    /** GTKEY-2026-10-22: the map redraws its lines after a STYLE change in MAP KEY. Required. */
    keyStyleChanged: () -> Unit,
    /** ATTACH4-2026-10-22: CENTRE ON ME's label -- says whether the map is following. Required. */
    centreLabel: String,
    /** GTSEARCH-2026-10-22: the one-box search's link to the map. Required. */
    search: GtSearchBridge,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    GtHelp.load(ctx)
    GtScheme.current.value = GtScheme.forMap(mapType)   // SHELL2-2026-10-22: buttons and panels follow the map type
    val group = GroupMode.on.value
    var open by rememberSaveable { mutableStateOf("") }   // the open module's id; "" = none (CODE RULE 1: no null)
    var sub by rememberSaveable { mutableStateOf("") }   // MAPTARGET-2026-10-23: the open submenu's id; "" = the module's top level
    val openModule = LaunchModules.all.firstOrNull { it.id == open }
    // MAPTARGET4-2026-10-23 (Fred 09:54): maps downloading -> the Map button is GREEN with a dot; the menu's summary says so.
    val mapsDownloading = com.geeksville.mesh.convoy.DownloadQueueManager.queue.collectAsState().value.any {
        it.status == com.geeksville.mesh.convoy.QueueStatus.DOWNLOADING || it.status == com.geeksville.mesh.convoy.QueueStatus.QUEUED }
    // ATTACH1-2026-10-22 ONE-OPTION RULE (Fred 11:09): one process, attached -> the button runs it; otherwise it opens the panel.
    fun tap(m: LaunchModule) {
        val act = m.processes.singleOrNull()?.let { actions[it.id] }
        if (act != null) { open = ""; act() } else open = m.id
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight

        // ── top centre: drawn by ConvoyScreen (SHELL2-2026-10-22, Fred 10:08) ─────
        // RECORD is RE-POINTED: ConvoyScreen's own REC control moves to the top centre in ONE MAP (larger, red with white letters),
        // with the GPS light beside N · Z<zoom> above it -- one RECORD, no duplicate, the full check-in / REC / PAUSE / END logic.

        // ── left: MENU, zoom ───────────────────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LaunchModules.bySlot(LaunchSlot.LEFT, group).forEach { m -> GtButton(m.label, { tap(m) }) }
            GtButton("+", { onZoom(1) })
            GtButton("−", { onZoom(-1) })
        }

        // ── right: MODE, INTERNET, KEY, FILTER, CENTRE ON ME ─────────────────
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp),   // SHELL10-2026-10-22 (Fred 11:12): centred vertically for balance
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LaunchModules.bySlot(LaunchSlot.RIGHT, group).forEach { m ->
                // SHELL3-2026-10-22 (Fred 10:12): KEY with a key, FILTER with a funnel, CENTRE ON ME with a bullseye -- icon AND label.
                when (m.id) {
                    "grp" -> Box(   // GRPONEMAP-2026-10-22: its own status box, NOT the map scheme -- the colour IS the message
                        modifier = Modifier
                            .background(grpColor, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .clickable { tap(m) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(m.label, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    "mode" -> GtButton(if (group) "Mode: Group" else "Mode: Android only", { tap(m) })   // SHELL4 (Fred 10:21)
                    "internet" -> GtButton(if (online) "Status: Online" else "Status: Offline", { tap(m) })   // SHELL4 (Fred 10:21)
                    "maptype" -> GtButton("Map: " + mapType + " \u25be", { tap(m) })   // MAPCHIP-2026-10-23
                    "key" -> GtIconButton(m.label, Icons.Filled.Key, { tap(m) })
                    "filter" -> GtIconButton(m.label, Icons.Filled.FilterAlt, { tap(m) })
                    "centre" -> GtIconButton(centreLabel, Icons.Filled.Adjust, { tap(m) })   // ATTACH4-2026-10-22
                    else -> GtButton(m.label, { tap(m) })
                }
            }
        }

        // ── bottom: readout (left) and the bottom bar ───────────────────────
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // SHELL7-2026-10-22: no READOUT placeholder -- ConvoyScreen's HUD (GPS line + MY CART / GROUP) is the readout, bottom left.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // SHELL11-2026-10-22 (Fred 11:19): Add Route and View SHARE one slot -- Add Route before the ride, View once on it.
                LaunchModules.bySlot(LaunchSlot.BOTTOM_BAR, group).forEach { m ->
                    if (m.id == "route") {
                        if (!onRide) GtButton("Add Route", { tap(m) })
                        else if (group) GtButton(if (showingGroup) "View: Group" else "View: My Cart", onToggleView)
                    } else if (m.id == "map" && mapsDownloading) GtButton(m.label + "  \u25cf", { tap(m) }, emphasis = androidx.compose.ui.graphics.Color(0xFF35C46A))   // MAPTARGET4
                    else GtButton(m.label, { tap(m) })
                }
            }
        }

        // ── the open module's panel ──────────────────────────────────────────
        if (openModule != null) {
            val panelWidth = if (landscape) 380.dp else maxWidth - 16.dp
            val panelMax = if (openModule.id == "search") maxHeight - 130.dp   // SEARCHTOP: from under RECORD down
                           else if (landscape) maxHeight - 96.dp else maxHeight * 0.55f
            Box(
                // SEARCHTOP-2026-10-22 (Fred 13:36): SEARCH opens at the TOP (under RECORD) so the keyboard never covers it.
                modifier = if (openModule.id == "search") Modifier.align(Alignment.TopCenter).padding(top = 110.dp)
                           else if (landscape) Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                           else Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
            ) {
                val rows = openModule.processes   // MAPTARGET4-2026-10-23: sections open in place (twisties), no submenu level
                GtPanel(
                    title = openModule.label,
                    helpId = openModule.id,
                    onHelp = onHelp,
                    onDone = { open = "" },   // MAPTARGET4: the menu reopens on the last section used
                    stays = openModule.stays,
                    width = panelWidth,
                    maxHeight = panelMax,
                ) {
                    // MAPTARGET4-2026-10-23 (Fred 10:04): the queue summary FIRST -- DETAILS opens the downloads panel. MAP TYPE left the
                    // menu (Fred 10:09): it is the Map: TYPE button on the map.
                    if (openModule.id == "map") {
                        GtQueueSummary(onOpen = { open = ""; actions["map.queues"]?.invoke() }, modifier = Modifier.fillMaxWidth())   // GTQSUM-2026-10-23: always
                    }
                    // GTFILTER-2026-10-22: FILTER draws its own body; SELECT steps the panel aside for the list.
                    if (openModule.id == "filter") GtFilterBody(filter, onSelect = { t -> open = ""; filter.openSelect(t) })
                    else if (openModule.id == "key") GtKeyBody(keyStyleChanged)   // GTKEY-2026-10-22
                    else if (openModule.id == "search") GtSearchBody(search, close = { open = "" })   // GTSEARCH-2026-10-22
                    else if (openModule.id == "maptype") mapTypes.forEach { t ->   // MAPCHIP-2026-10-23: pick -> switch -> close
                        GtButton(if (t == mapType) "\u25cf  " + t else "     " + t, { onMapType(t); open = "" }, modifier = Modifier.fillMaxWidth())   // the current one carries the dot
                    }
                    else rows.forEach { pr ->
                        if (pr.children.isNotEmpty()) {   // MAPOPEN-2026-10-23 (Fred 11:08): a section TITLE in plain text, its options always open below
                            Text(pr.label, color = GtScheme.current.value.panelDim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp))
                            pr.children.forEach { ch ->
                                GtButton(
                                    if (actions[ch.id] != null) ch.label else "${ch.label}  \u00b7  not attached yet",
                                    { actions[ch.id]?.let { act -> if (!openModule.stays) open = ""; act() } },
                                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp),
                                    emphasis = if (actions[ch.id] != null) androidx.compose.ui.graphics.Color.Unspecified else GtColors.Dim,
                                    enabled = actions[ch.id] != null
                                )
                            }
                            return@forEach
                        }
                        GtButton(
                            if (actions[pr.id] != null) pr.label else "${pr.label}  ·  not attached yet",   // ATTACH1-2026-10-22: the bridge decides
                            { actions[pr.id]?.let { act -> if (!openModule.stays) open = ""; act() } },
                            modifier = Modifier.fillMaxWidth(),
                            emphasis = if (actions[pr.id] != null) androidx.compose.ui.graphics.Color.Unspecified else GtColors.Dim,
                            enabled = actions[pr.id] != null
                        )
                    }
                    if (openModule.id == "menu") {
                        // The way back while 2.7c is in progress: always attached.
                        GtButton("Classic layout (today's ride map)", { GtLayout.set(ctx, false); open = "" },
                            modifier = Modifier.fillMaxWidth(), emphasis = GtColors.Warn)
                    }
                    if (rows.any { it.children.isEmpty() && actions[it.id] == null } && openModule.id !in setOf("filter", "key", "search")) Text("Placeholder — 2.7c shell (SHELL-2026-10-22). Rows are attached one module at a time.",   // GTFILTER: only while rows are unattached
                        color = GtScheme.current.value.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                }
            }
        }
    }
}
