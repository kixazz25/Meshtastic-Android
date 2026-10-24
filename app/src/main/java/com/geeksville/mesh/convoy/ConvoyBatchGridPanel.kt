package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
// DRAGCOMPARE-2026-09-02: the four symbols the drag introduces. ⚠ The locked
// notes record THREE compile failures from patches that added a Compose symbol
// without its import, each naming the missing import in its own output.
// Checked against this file first: layout.* already gives offset(), runtime.*
// gives remember/mutableStateOf, and clip was here.
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * COMPARETABLE-2026-08-27 — the AI batch, compared.
 *
 * ⭐ ONE PANEL. Rows are the universe — every feature and every trail across
 * every route gets a row — and a column is that route's ✓, – or mileage. The
 * columns stay narrow whatever the route names are, which is why there is
 * nothing to expand and no second screen.
 *
 * ⭐ THE HEADER DOES THREE THINGS: its colour ties the column to the line on
 * the map, tapping it shows or hides that line, and the tick beside it marks
 * the route to be saved.
 *
 * ⛔ TAPPING HIDES THE LINE, NEVER THE COLUMN. "If we remove it we cannot put
 * it back" — the tap target has to survive its own action.
 */

private val panelBg: Color get() = Gt.panel  // STD1-2026-10-24 was 0xEE131820 
private val accentBlue: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF4DA6FF 
private val dimText: Color get() = Gt.dim  // STD1-2026-10-24 was 0xFF9AA4B2 
private val faint: Color get() = Gt.dim  // STD1-2026-10-24 was 0xFF6B7481 
private val mono = FontFamily.Monospace

/** One route's column. [features] and [trails] are name -> mileage (0 = present, no mileage). */
data class BatchRow(
    val name: String,
    val colour: String,
    val miles: Double,
    val hoursLow: Double,
    val hoursHigh: Double,
    val features: Map<String, Double> = emptyMap(),
    val trails: Map<String, Double> = emptyMap(),
)

// DEADCODE2-2026-10-23: ConvoyBatchGridPanel (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyBatchGridPanel.pieces.txt

private val LABEL_W = 130.dp
private val COL_W = 52.dp

@Composable
private fun SectionRow(label: String) {
    Text(label, color = faint, fontSize = 12.sp, fontFamily = mono,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 7.dp, bottom = 2.dp))
}

@Composable
private fun DataRow(
    label: String, rows: List<BatchRow>, bold: Boolean = false,
    cell: (BatchRow) -> String,
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)) {
        Text(label, color = dimText, fontSize = 12.sp, fontFamily = mono,
            maxLines = 1, modifier = Modifier.width(LABEL_W))
        rows.forEach { r ->
            val v = cell(r)
            Text(
                v,
                // ⭐ a check is green and unmistakable; everything else takes the
                // route's own colour so the column stays tied to its line
                color = if (v == "\u2713") Gt.good else parseColour(r.colour),
                fontSize = if (v == "\u2713") 13.sp else 12.sp,
                fontFamily = mono,
                fontWeight = if (bold || v == "\u2713") FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(COL_W)
            )
        }
    }
}

/** ⭐ "broken ridge ai Route 3" -> "Route 3". The batch name is in the header. */
private fun shortName(name: String, batchName: String): String =
    if (name.startsWith(batchName)) name.removePrefix(batchName).trim().ifBlank { name }
    else name

private fun parseColour(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(accentBlue)

@Composable
private fun TableButton(
    label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(30.dp),
        shape = RoundedCornerShape(6.dp),
        color = Gt.panel
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().clickable { onClick() }
        ) {
            Text(label, color = tint, fontSize = 12.sp, fontFamily = mono,
                fontWeight = FontWeight.Bold)
        }
    }
}
