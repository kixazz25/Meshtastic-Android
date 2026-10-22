package com.geeksville.mesh.grouptrack

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
    online: Boolean,
    /** SHELL8-2026-10-22: true = the HUD shows GROUP. Required -- the only caller is ConvoyScreen (CODE RULE 1). */
    showingGroup: Boolean,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    GtHelp.load(ctx)
    GtScheme.current.value = GtScheme.forMap(mapType)   // SHELL2-2026-10-22: buttons and panels follow the map type
    val group = GroupMode.on.value
    var open by rememberSaveable { mutableStateOf("") }   // the open module's id; "" = none (CODE RULE 1: no null)
    val openModule = LaunchModules.all.firstOrNull { it.id == open }

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
            LaunchModules.bySlot(LaunchSlot.LEFT, group).forEach { m -> GtButton(m.label, { open = m.id }) }
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
                    "mode" -> GtButton(if (group) "Mode: Group" else "Mode: Android only", { open = m.id })   // SHELL4 (Fred 10:21)
                    "internet" -> GtButton(if (online) "Status: Online" else "Status: Offline", { open = m.id })   // SHELL4 (Fred 10:21)
                    "key" -> GtIconButton(m.label, Icons.Filled.Key, { open = m.id })
                    "filter" -> GtIconButton(m.label, Icons.Filled.FilterAlt, { open = m.id })
                    "centre" -> GtIconButton(m.label, Icons.Filled.Adjust, { open = m.id })
                    else -> GtButton(m.label, { open = m.id })
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
                if (group) GtButton(if (showingGroup) "View: Group" else "View: My Cart", onToggleView)   // SHELL8-2026-10-22: left of Map, Group only
                LaunchModules.bySlot(LaunchSlot.BOTTOM_BAR, group).forEach { m -> GtButton(m.label, { open = m.id }) }
            }
        }

        // ── the open module's panel ──────────────────────────────────────────
        if (openModule != null) {
            val panelWidth = if (landscape) 380.dp else maxWidth - 16.dp
            val panelMax = if (landscape) maxHeight - 96.dp else maxHeight * 0.55f
            Box(
                modifier = if (landscape) Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                           else Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
            ) {
                GtPanel(
                    title = openModule.label,
                    helpId = openModule.id,
                    onHelp = onHelp,
                    onDone = { open = "" },
                    stays = openModule.stays,
                    width = panelWidth,
                    maxHeight = panelMax,
                ) {
                    openModule.processes.forEach { pr ->
                        GtButton(
                            if (pr.attached) pr.label else "${pr.label}  ·  not attached yet",
                            pr.run,
                            modifier = Modifier.fillMaxWidth(),
                            emphasis = if (pr.attached) androidx.compose.ui.graphics.Color.Unspecified else GtColors.Dim,
                            enabled = pr.attached
                        )
                    }
                    if (openModule.id == "menu") {
                        // The way back while 2.7c is in progress: always attached.
                        GtButton("Classic layout (today's ride map)", { GtLayout.set(ctx, false); open = "" },
                            modifier = Modifier.fillMaxWidth(), emphasis = GtColors.Warn)
                    }
                    Text("Placeholder — 2.7c shell (SHELL-2026-10-22). Rows are attached one module at a time.",
                        color = GtScheme.current.value.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                }
            }
        }
    }
}
