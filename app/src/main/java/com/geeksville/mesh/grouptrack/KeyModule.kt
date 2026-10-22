package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.geeksville.mesh.convoy.ARTIFACT_ROWS
import com.geeksville.mesh.convoy.MOTORIZED_ROWS
import com.geeksville.mesh.convoy.MapKeyRow
import com.geeksville.mesh.convoy.NON_MOTORIZED_ROWS
import com.geeksville.mesh.convoy.TrailFilterState
import com.geeksville.mesh.convoy.TrailStylePanel

/**
 * GTKEY-2026-10-22 (2.7c, Fred 12:25-12:34): MAP KEY = what you are LOOKING AT and how it looks. NO on/off here -- that is FILTER.
 *   TRAILS (motorized, non-motorized), YOURS (my tracks, my routes): each line as the map draws it (the rider's saved style, else
 *   the shipped one) + STYLE ›, which opens today's style editor (TrailStylePanel) in place and returns here on apply.
 *   WHILE YOU RIDE: my track (blue, dashed, arrows), the lead track (the leader's path, arrows show direction).
 *   GROUP RIDING: carts, lead, tail -- their symbols are added when the cart markers are reviewed (placeholder, says so).
 * Waypoint symbols: not here yet (Fred to decide).
 */
@Composable
fun GtKeyBody(onStyleChanged: () -> Unit) {
    val s = GtScheme.current.value
    var tick by remember { mutableIntStateOf(0) }
    var styling by remember { mutableStateOf<MapKeyRow?>(null) }   // the row being styled; null = the key itself (as MapKeysPanel)
    remember { TrailFilterState.load(); true }
    @Suppress("UNUSED_EXPRESSION") tick
    val st = styling
    if (st != null) {
        TrailStylePanel(row = st, onCancel = { styling = null }, onApply = { c, d, w ->
            TrailFilterState.setStyle(st.name, c, d, w); styling = null; tick++; onStyleChanged()
        })
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("TRAILS — MOTORIZED") { MOTORIZED_ROWS.forEach { r -> StyleRow(r) { styling = r } } }
        Section("TRAILS — NON-MOTORIZED") { NON_MOTORIZED_ROWS.forEach { r -> StyleRow(r) { styling = r } } }
        Section("YOURS") { ARTIFACT_ROWS.forEach { r -> StyleRow(r) { styling = r } } }
        Section("WHILE YOU RIDE") {
            FixedRow("My track", "Your ride as you record it — arrows show direction", Color(0xFF1E88FF), 4, dashed = true)
            FixedRow("Lead track", "The leader's path — arrows show direction", Color(0xFF1E88FF), 5, dashed = false)
        }
        Section("GROUP RIDING") {
            Text("Carts, lead and tail — their symbols are added here when the cart markers are reviewed.", color = s.panelDim, fontSize = 12.sp)
        }
        Text("To turn things on or off, use FILTER.", color = s.panelDim, fontSize = 12.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val s = GtScheme.current.value
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = s.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

/** A styleable line: the rider's saved style, else the shipped one. */
@Composable
private fun StyleRow(r: MapKeyRow, onStyle: () -> Unit) {
    val s = GtScheme.current.value
    val saved = TrailFilterState.style[r.name]
    val color = saved?.first?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: r.color
    val dashed = if (saved != null) saved.second != null else r.dashed
    val weight = saved?.third ?: r.weight
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onStyle).padding(vertical = 8.dp)) {
        Sample(color, weight, dashed)
        Text(r.label, color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 10.dp))
        Text("STYLE ›", color = GtColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FixedRow(label: String, meaning: String, color: Color, weight: Int, dashed: Boolean) {
    val s = GtScheme.current.value
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Sample(color, weight, dashed)
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, color = s.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(meaning, color = s.panelDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Sample(color: Color, weight: Int, dashed: Boolean) {
    Canvas(Modifier.width(40.dp).height(16.dp)) {
        drawLine(color, Offset(2f, size.height / 2), Offset(size.width - 2f, size.height / 2), strokeWidth = weight.dp.toPx() * 0.8f,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 9f)) else null)
    }
}
