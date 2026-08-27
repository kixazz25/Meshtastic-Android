package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * BATCHGRID-2026-08-27 — the AI batch, listed.
 *
 * ⭐ THIS IS A LEGEND, NOT A RESULTS LIST. Each row is a route's name in the
 * SAME COLOUR as its line on the map. That tie is the panel's whole job: a
 * colour cannot be spoken about or listed, and a name cannot be found on a
 * map. Both, or the comparison does not work.
 *
 * ⚠ NO HIDE CONTROL. All routes are always drawn; narrowing happens by ticking
 * two or three and pressing COMPARE. One way to do it, not two.
 *
 * ⚠ Styled to match ConvoyDisplayPanel — its panelBg/accentBlue/mono are
 * private to that file, so they are redeclared here rather than moved. Making
 * them shared would touch three panels and is a separate refactor.
 */

private val panelBg = Color(0xEE131820)
private val accentBlue = Color(0xFF4DA6FF)
private val dimText = Color(0xFF9AA4B2)
private val mono = FontFamily.Monospace

data class BatchRow(
    val name: String,
    val colour: String,
    val miles: Double,
    val hoursLow: Double,
    val hoursHigh: Double,
)

@Composable
fun ConvoyBatchGridPanel(
    batchName: String,
    rows: List<BatchRow>,
    compareTicks: Set<String>,
    saveTicks: Set<String>,
    onCompareTick: (String, Boolean) -> Unit,
    onSaveTick: (String, Boolean) -> Unit,
    onCompare: () -> Unit,
    onSaveSelected: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            },
        shape = RoundedCornerShape(10.dp),
        color = panelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(8.dp).width(268.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(if (expanded) "v" else ">", color = accentBlue,
                    fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
                Text(batchName.uppercase(), color = accentBlue, fontSize = 10.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("${rows.size}", color = dimText, fontSize = 10.sp, fontFamily = mono)
            }

            if (expanded) {
                // column hints — without these the two ticks are unlabelled and
                // a rider has to guess which is which
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CMP", color = dimText, fontSize = 8.sp, fontFamily = mono,
                        modifier = Modifier.width(34.dp))
                    Spacer(Modifier.weight(1f))
                    Text("SAVE", color = dimText, fontSize = 8.sp, fontFamily = mono)
                }

                rows.forEach { r ->
                    val cmp = r.name in compareTicks
                    // ⚠ ceiling of three: more than three lines over the same
                    // ground cannot be told apart, and the compare table has
                    // three columns.
                    val cmpEnabled = cmp || compareTicks.size < 3
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = cmp,
                            enabled = cmpEnabled,
                            onCheckedChange = { onCompareTick(r.name, it) },
                            colors = CheckboxDefaults.colors(checkedColor = accentBlue),
                            modifier = Modifier.size(30.dp).alpha(if (cmpEnabled) 1f else 0.4f)
                        )
                        // ⭐ the tie to the map: same colour as the line
                        Box(
                            Modifier.size(11.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(parseColour(r.colour))
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                shortName(r.name, batchName),
                                color = parseColour(r.colour),
                                fontSize = 12.sp, fontFamily = mono,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "%.1f mi  %.1f-%.1f h".format(r.miles, r.hoursLow, r.hoursHigh),
                                color = dimText, fontSize = 9.sp, fontFamily = mono
                            )
                        }
                        // ⚠ FAR END OF THE ROW, deliberately. Compare and save
                        // mean entirely different things and a mis-tap is
                        // expensive — one is reading, the other is what is kept.
                        Checkbox(
                            checked = r.name in saveTicks,
                            onCheckedChange = { onSaveTick(r.name, it) },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4ADE80)),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                val canCompare = compareTicks.size in 2..3
                GridButton(
                    if (canCompare) "COMPARE (${compareTicks.size})" else "TICK 2 OR 3 TO COMPARE",
                    accentBlue, canCompare, Modifier.fillMaxWidth()
                ) { onCompare() }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GridButton("SAVE SELECTED", Color(0xFF4ADE80), true, Modifier.weight(1f)) {
                        onSaveSelected()
                    }
                    GridButton("EXIT", dimText, true, Modifier.weight(1f)) { onExit() }
                }
            }
        }
    }
}

/** ⭐ "broken ridge Route 3" -> "Route 3". The batch name is already in the header. */
private fun shortName(name: String, batchName: String): String =
    if (name.startsWith(batchName)) name.removePrefix(batchName).trim().ifBlank { name }
    else name

private fun parseColour(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(accentBlue)

@Composable
private fun GridButton(
    label: String, tint: Color, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    /* GRIDFIX-2026-08-27: THE CLICKABLE MOVES OFF THE SURFACE.
     *
     * ⛔ It was on the Surface's own modifier. Material3 Surface handles
     * pointer input for its shape and elevation, and a clickable in that chain
     * can be swallowed before it fires -- COMPARE did nothing at all.
     *
     * ⭐ The inner Box is a plain layout node and does not compete for the
     * gesture.
     */
    Surface(
        modifier = modifier.height(30.dp).alpha(if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF1D2430)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
        ) {
            Text(label, color = tint, fontSize = 9.sp, fontFamily = mono,
                fontWeight = FontWeight.Bold)
        }
    }
}
