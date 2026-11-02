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
import androidx.compose.ui.graphics.drawscope.clipRect   // LANDAREAS-2026-11-02
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
        // LANDAREAS-2026-11-02 (Fred 14:09): the land overlays and the restricted trail line, as the map draws them
        Section("LAND") {
            FixedRow("Restricted trail", "Motorized, wholly inside an area closed to motor vehicles", Color(0xFFE53935), 3, false)
            AreaRow("Public land", "Federal, state, local or district land (PAD-US)", Color(0xFF2EA043), Color(0xFF2E7D32), true)
            AreaRow("Restricted area", "Public land closed to motor vehicles: wilderness, wilderness study area", Color(0xFFD32F2F), Color(0xFFC62828), false)
            AreaRow("Private land", "No stripes: land that is not public", Color.Transparent, Color(0xFF9E9E9E), true)
        }
        Section("YOURS") { ARTIFACT_ROWS.forEach { r -> StyleRow(r) { styling = r } } }
        Section("WHILE YOU RIDE") {
            // VECRIDESTYLE-2026-10-30 (Fred 19:42): styleable, like My tracks / My routes (were fixed rows in the accent colour -- white)
            RIDE_ROWS.forEach { r -> StyleRow(r) { styling = r } }
        }
        Section("GROUP RIDING") {
            Text("Carts, lead and tail — their symbols are added here when the cart markers are reviewed.", color = s.panelDim, fontSize = 12.sp)
        }
        Text("To turn things on or off, use FILTER.", color = s.panelDim, fontSize = 12.sp)
    }
}

/** VECRIDESTYLE-2026-10-30: the lines drawn while you ride -- styleable; shipped look = the map's (blue, my track dashed). */
private val RIDE_ROWS = listOf(
    MapKeyRow("__mytrack", "My track — as you record it", Color(0xFF1E88FF), 4, dashed = true),
    MapKeyRow("__lead", "Lead track — the leader's path", Color(0xFF1E88FF), 5, dashed = false),
)

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
        Text("STYLE ›", color = Gt.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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

/** LANDAREAS-2026-11-02: an area swatch -- stripes (up = public's direction) and an edge, as the map draws them. */
@Composable
private fun AreaRow(label: String, meaning: String, stripe: Color, edge: Color, up: Boolean) {
    val s = GtScheme.current.value
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Canvas(Modifier.width(40.dp).height(22.dp)) {
            val w = size.width; val h = size.height; val step = 7.dp.toPx()
            if (stripe != Color.Transparent) clipRect {
                var x = -h
                while (x < w) {
                    if (up) drawLine(stripe.copy(alpha = 0.6f), Offset(x, h), Offset(x + h, 0f), strokeWidth = 1.6.dp.toPx())
                    else drawLine(stripe.copy(alpha = 0.6f), Offset(x, 0f), Offset(x + h, h), strokeWidth = 1.6.dp.toPx())
                    x += step
                }
            }
            drawRect(edge, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        }
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
