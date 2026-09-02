package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TrailStylePanel — pick a colour, a pattern and a weight for one map key row.
 * TRAILSTYLE-2026-09-02.
 *
 * ⭐⭐ THE POINT IS TUNING, NOT CONFIGURATION. Fred, 09-02: "I am going to play
 * with this for a while and we will issue my palette as a shared asset default."
 * So this is the tool he uses to settle the palette, and the map_keys.json it
 * writes becomes the SHIPPED DEFAULT. That is why the values live in JSON and
 * not in Kotlin constants — settling them must not need a build.
 *
 * ⭐ CURRENT BESIDE PROPOSED, AND NEITHER ON THE MAP. Fred: "swatch for current
 * and preview of new. Not on map." Comparing two candidates is the whole job;
 * repainting the map on every tap would be noise while doing it. The map
 * changes on APPLY.
 *
 * ⭐ APPLY RETURNS TO THE MAP KEYS PANEL. Fred: "return to legend/filter panel
 * so item is changed and we can select another item to change." One category at
 * a time, back to the list, pick the next. That is the loop.
 *
 * ⚠ OPENED BY TAPPING THE ROW'S NAME, not its swatch. The swatch sits beside a
 * checkbox in a 25dp row and the two targets would fight; the name is the
 * biggest thing in the row and it is unambiguous.
 */
@Composable
fun TrailStylePanel(
    row: MapKeyRow,
    onApply: (String, String?, Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // current values: whatever the rider has saved, else the row's shipped default
    val saved = TrailFilterState.style[row.name]
    val startC = saved?.first ?: colorHex(row.color)
    val startD = saved?.second ?: (if (row.dashed) "9,5" else null)
    val startW = saved?.third ?: row.weight

    var c by remember { mutableStateOf(startC) }
    var d by remember { mutableStateOf(startD) }
    var w by remember { mutableIntStateOf(startW) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xF2000000),
        shadowElevation = 8.dp,
        modifier = modifier.width(330.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
                Text(row.label.uppercase(), color = Color(0xFF8FD0FF),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f))
                Box(contentAlignment = Alignment.Center,
                    modifier = Modifier.width(26.dp).height(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1B2027))
                        .border(1.dp, Color(0xFF47505A), RoundedCornerShape(4.dp))
                        .clickable { onCancel() }) {
                    Text("\u2715", color = Color(0xFFE6EDF3), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold)
                }
            }

            // ── current vs proposed, side by side ──────────────────────────
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Label("NOW")
                    LineSample(startC, startD, startW)
                }
                Column(Modifier.weight(1f)) {
                    Label("NEW")
                    LineSample(c, d, w)
                }
            }

            Label("COLOUR")
            Spacer(Modifier.height(4.dp))
            // two rows of seven so a 330dp panel is not crowded
            PALETTE.chunked(7).forEach { rowColours ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)) {
                    rowColours.forEach { hex ->
                        Box(modifier = Modifier.width(26.dp).height(26.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(parseHex(hex))
                            .border(2.dp,
                                if (hex.equals(c, true)) Color.White else Color.Transparent,
                                RoundedCornerShape(5.dp))
                            .clickable { c = hex })
                    }
                }
            }

            Label("PATTERN")
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                PATTERNS.forEach { (name, dash) ->
                    Chip(name, d == dash, Modifier.weight(1f)) { d = dash }
                }
            }

            Label("THICKNESS")
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                listOf(2, 3, 4, 5, 6).forEach { n ->
                    Chip(n.toString(), w == n, Modifier.weight(1f)) { w = n }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()) {
                Chip("CANCEL", false, Modifier.weight(1f)) { onCancel() }
                Chip("APPLY", true, Modifier.weight(1f)) { onApply(c, d, w) }
            }
        }
    }
}

/** ⚠ Shared with MapKeysPanel so a colour picked here is one the key can show. */
val PALETTE = listOf(
    "#00CCFF", "#00AAFF", "#0077DD", "#0044AA", "#00FFFF", "#39FF14", "#66CC66",
    "#FFCC00", "#FF8800", "#FF2222", "#FF1493", "#AA44FF", "#CC8844", "#888888"
)

/** label to dashArray. ⚠ null means solid — Leaflet wants the absence, not "0". */
val PATTERNS: List<Pair<String, String?>> = listOf(
    "SOLID" to null,
    "DASH" to "9,5",
    "DOT" to "2,4",
    "DASH-DOT" to "10,4,2,4"
)

@Composable
private fun Label(t: String) {
    Text(t, color = Color(0xFF8B949E), fontSize = 10.sp,
        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
}

/**
 * A line drawn the way the map will draw it.
 * ⚠ Compose has no dashArray, so a pattern is rendered as segments. It is an
 * approximation of Leaflet's output, not a copy of it — close enough to choose
 * between two candidates, which is what this is for.
 */
@Composable
private fun LineSample(hex: String, dash: String?, weight: Int) {
    val col = parseHex(hex)
    Box(contentAlignment = Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth().height(22.dp)) {
        if (dash == null) {
            Spacer(Modifier.fillMaxWidth().height(weight.dp)
                .clip(RoundedCornerShape(1.dp)).background(col))
        } else {
            val seg = if (dash.startsWith("2,")) 3 else if (dash.startsWith("10,")) 11 else 9
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(9) {
                    Spacer(Modifier.width(seg.dp).height(weight.dp)
                        .clip(RoundedCornerShape(1.dp)).background(col))
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, modifier: Modifier = Modifier,
                 onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center,
        modifier = modifier.clip(RoundedCornerShape(5.dp))
            .background(if (on) Color(0xFF12203A) else Color(0xFF0C1015))
            .border(1.dp, if (on) Color(0xFF58A6FF) else Color(0xFF30363D),
                RoundedCornerShape(5.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp)) {
        Text(text, color = if (on) Color(0xFF58A6FF) else Color(0xFF8B949E),
            fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** "#RRGGBB" -> Color. ⚠ Falls back to cyan rather than throwing: a bad value
 *  in a hand-edited map_keys.json must not stop the panel opening. */
fun parseHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Color(0xFF00FFFF)
}

/** Color -> "#RRGGBB", for seeding the picker from a row's shipped default. */
fun colorHex(c: Color): String {
    // ⚠ Color.value is a ULong and bit-shifting it lands in ULong arithmetic,
    // which String.format will not take. Compose exposes the channels as 0..1
    // floats -- use those and let the maths stay in Int.
    return String.format("#%02X%02X%02X",
        (c.red * 255f + 0.5f).toInt(),
        (c.green * 255f + 0.5f).toInt(),
        (c.blue * 255f + 0.5f).toInt())
}
