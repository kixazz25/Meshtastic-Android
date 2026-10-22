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
 *   PRIVATE LAND      "Include private land" (Fred 09-02: public always shows; there is no private-only case).
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
            modifier = Modifier.fillMaxWidth(), emphasis = if (f.hidden) GtColors.Bad else Color.Unspecified)

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

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            .clickable { TrailFilterState.setLand(if (TrailFilterState.land == "ALL") "PUBLIC" else "ALL"); tick++; f.trailsChanged() }) {
            Checkbox(checked = TrailFilterState.land == "ALL", onCheckedChange = null, colors = boxColors())
            Column {
                Text("Include private land", color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Public trails always show.", color = s.panelDim, fontSize = 12.sp)
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
    Box(Modifier.background(if (on) Color(0xFF1565C0) else Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 12.dp)) {
        Text(label, color = if (on) Color.White else s.panelText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun boxColors() = CheckboxDefaults.colors(checkedColor = Color(0xFF35C46A), uncheckedColor = GtColors.Dim)
