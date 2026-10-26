package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.CATEGORY_HELP
import com.geeksville.mesh.convoy.MOTORIZED_ROWS
import com.geeksville.mesh.convoy.MapKeyRow
import com.geeksville.mesh.convoy.NON_MOTORIZED_ROWS
import com.geeksville.mesh.convoy.TrailFilterState

/**
 * GTFILTER-2026-10-22 (2.7c, Fred 12:30 "ok lets build it" -- the FILTER mock of 12:28). What is ON the map, in one panel:
 *   HIDE EVERYTHING   one toggle: clears the map; again = restores EXACTLY what was on (filter settings survive).
 *   ON THE MAP        Tracks, Routes, Waypoints: OFF / ALL / SELECT › (SELECT opens today's list for the map view).
 *                     Trails: OFF / ALL -- WHICH trails is the category list below (Trails' old SELECT opened that same filter).
 *   LAND              [x] Public  [x] Private (FILTERLAND-2026-10-25: was "Include private land"; Fred 21:25 -- private only is a case now).
 *   MOTORIZED / NON-MOTORIZED  every trail type with its line, its checkbox and its MEANING (Fred 12:25: the type definitions
 *                     live in the filter). Category box = rule 3 of 09-02: on if any row is on; tap = all on, or all off when all on.
 * The panel never owns the map's state: ConvoyScreen hands it a [GtFilterBridge] (the same functions the old Map Features used).
 * TrailFilterState is the trail truth (not Compose state -- [tick] forces the redraw, as MapKeysPanel does).
 */
class GtFilterBridge(
    /** 0 = OFF, 1 = ALL, 2 = SELECTED (DS_OFF / DS_ON / DS_SELECTED). */
    val state: (String) -> Int,
    val setState: (String, Int) -> Unit,
    val openSelect: (String) -> Unit,
    val hidden: Boolean,
    val toggleHide: () -> Unit,
    /** the map re-reads its trails (after a category or land change). */
    val trailsChanged: () -> Unit,
)

@Composable
fun GtFilterBody(f: GtFilterBridge, onSelect: (String) -> Unit) {
    val s = GtScheme.current.value
    var tick by remember { mutableIntStateOf(0) }
    remember { TrailFilterState.load(); true }
    @Suppress("UNUSED_EXPRESSION") tick
    val help = remember { CATEGORY_HELP.toMap() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GtButton(if (f.hidden) "SHOW AGAIN — restore what was on" else "HIDE EVERYTHING", f.toggleHide,
            modifier = Modifier.fillMaxWidth(), emphasis = if (f.hidden) Gt.bad else Color.Unspecified)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ON THE MAP", color = s.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            listOf("Trails", "Tracks", "Routes", "Waypoints").forEach { t ->
                val st = f.state(t)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(t, color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Row(Modifier.border(1.dp, s.edge, RoundedCornerShape(8.dp))) {
                        Seg("OFF", st == 0) { f.setState(t, 0) }
                        Seg("ALL", st == 1) { f.setState(t, 1) }
                        if (t != "Trails") Seg("SELECT ›", st == 2) { onSelect(t) }
                    }
                }
            }
            Text("SELECT opens the list for the map view — tick the ones to show.", color = s.panelDim, fontSize = 12.sp)
        }

        // FILTERAREAS2-2026-10-23 (Fred 10:22): off-grid map areas, before Include private land. Placeholders until the
        // planner's area drawing (showDownloadedAreas / showQueuedAreas) is copied to ONE MAP's page.
        // Fred 10:23: ONE line (real estate). Greyed = not attached yet.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("OFF-GRID MAPS", color = s.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // GTOFFGRID-2026-10-23: WIRED -- tick to show on the map at once, untick to clear.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { GtOffGrid.setAvailable(!GtOffGrid.available.value) }) {
                Checkbox(checked = GtOffGrid.available.value, onCheckedChange = null, colors = boxColors())
                Text("Available", color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { GtOffGrid.setDownloading(!GtOffGrid.downloading.value) }) {
                Checkbox(checked = GtOffGrid.downloading.value, onCheckedChange = null, colors = boxColors())
                Text("Downloading", color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // FILTERLAND-2026-10-25 (Fred 21:25): two ticks -- Public off shows only the private trails. At least one stays ticked.
        val pubOn = TrailFilterState.land != "PRIVATE"
        val privOn = TrailFilterState.land != "PUBLIC"
        fun setLandTicks(p: Boolean, q: Boolean) {
            if (!p && !q) return   // at least one -- Trails OFF hides every trail
            TrailFilterState.setLand(if (p && q) "ALL" else if (p) "PUBLIC" else "PRIVATE"); tick++; f.trailsChanged()
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("LAND", color = s.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { setLandTicks(!pubOn, privOn) }) {
                Checkbox(checked = pubOn, onCheckedChange = null, colors = boxColors())
                Text("Public", color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { setLandTicks(pubOn, !privOn) }) {
                Checkbox(checked = privOn, onCheckedChange = null, colors = boxColors())
                Text("Private", color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        TrailGroup("MOTORIZED", MOTORIZED_ROWS, help, f) { tick++ }
        TrailGroup("NON-MOTORIZED", NON_MOTORIZED_ROWS, help, f) { tick++ }
    }
}

@Composable
private fun TrailGroup(title: String, rows: List<MapKeyRow>, help: Map<String, String>, f: GtFilterBridge, bump: () -> Unit) {
    val s = GtScheme.current.value
    val names = rows.map { it.name }
    val anyOn = names.any { TrailFilterState.isOn(it) }
    val allOn = names.all { TrailFilterState.isOn(it) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            .clickable { TrailFilterState.setGroup(names, !allOn); bump(); f.trailsChanged() }) {
            Checkbox(checked = anyOn, onCheckedChange = null, colors = boxColors())
            Text(title, color = s.panelText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 18.dp)
                .clickable { TrailFilterState.toggleCategory(r.name); bump(); f.trailsChanged() }) {
                Checkbox(checked = TrailFilterState.isOn(r.name), onCheckedChange = null, colors = boxColors())
                Canvas(Modifier.width(34.dp).height(14.dp)) {
                    drawLine(r.color, Offset(2f, size.height / 2), Offset(size.width - 2f, size.height / 2), strokeWidth = r.weight.dp.toPx() * 0.8f,
                        pathEffect = if (r.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null)
                }
                Column(Modifier.padding(start = 8.dp)) {
                    Text(r.label, color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    help[r.label]?.let { Text(it, color = s.panelDim, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun Seg(label: String, on: Boolean, onClick: () -> Unit) {
    val s = GtScheme.current.value
    Box(Modifier.background(Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 12.dp)) {
        Text((if (on) "\u2713 " else "") + label, color = if (on) Gt.text else s.panelText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun boxColors() = CheckboxDefaults.colors(checkedColor = Gt.accent, uncheckedColor = Gt.dim)
