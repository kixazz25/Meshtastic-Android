package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private val panelBg = Color(0xEE131820)
private val accentBlue = Color(0xFF4DA6FF)
private val dimText = Color(0xFF9AA4B2)
private val faint = Color(0xFF6B7481)
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

@Composable
fun ConvoyBatchGridPanel(
    batchName: String,
    rows: List<BatchRow>,
    hidden: Set<String>,
    saveTicks: Set<String>,
    onToggleShown: (String) -> Unit,
    onSaveTick: (String, Boolean) -> Unit,
    onSaveSelected: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    // ⭐ ROWS ARE THE UNION across every route, in first-appearance order, so a
    // feature only one route reaches still gets a line of its own.
    /* TABLEPOLISH-2026-08-27: SORTED BY TOTAL ACROSS ALL ROUTES.
     *
     * ⭐⭐ Fred, 08-27: "the most used trail will appear first ... we can spot
     * similar backbones very quickly."
     *
     * The trails at the top are what these routes are MADE OF; the ones at the
     * bottom are what DISTINGUISHES them. Two routes carrying high mileage on
     * the same top three trails are variations on one ride, and it reads off
     * the screen in a second.
     *
     * ⚠ It does visually what dedupe pass 3 does numerically — the 75%
     * edge-overlap rule catches duplicates the search PRODUCED; this shows the
     * rider the same thing about the ones it KEPT.
     *
     * ⚠ The totals drive the order but are not shown: the sequence speaks for
     * itself and the panel stays narrow.
     */
    val featureRows = remember(rows) {
        val n = HashMap<String, Int>()
        rows.forEach { r -> r.features.keys.forEach { n[it] = (n[it] ?: 0) + 1 } }
        n.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
            .thenBy { it.key }).map { it.key }
    }
    val trailRows = remember(rows) {
        val m = HashMap<String, Double>()
        rows.forEach { r -> r.trails.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v } }
        m.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }
            .thenBy { it.key }).map { it.key }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = panelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(batchName.uppercase(), color = accentBlue, fontSize = 10.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp))

            // ── header: colour, tap to show/hide, tick to save ──────────
            Row(verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.width(LABEL_W))
                rows.forEach { r ->
                    val shown = r.name !in hidden
                    Column(
                        modifier = Modifier
                            .width(COL_W)
                            // ⛔ hides the LINE, never the column
                            .clickable { onToggleShown(r.name) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            shortName(r.name, batchName),
                            color = parseColour(r.colour),
                            fontSize = 10.sp, fontFamily = mono,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.alpha(if (shown) 1f else 0.35f)
                        )
                        Box(
                            Modifier.padding(top = 2.dp).width(COL_W - 8.dp).height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(parseColour(r.colour))
                                .alpha(if (shown) 1f else 0.25f)
                        )
                        Checkbox(
                            checked = r.name in saveTicks,
                            onCheckedChange = { onSaveTick(r.name, it) },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4ADE80)),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            /* ⚠ HEIGHT IS THE CONSTRAINT, NOT WIDTH. Twenty rows will not fit,
             * so the table scrolls inside a capped height and the map keeps the
             * rest of the screen. */
            /* ⚠ A VISIBLE BAR, because the section is capped and has buttons
             * underneath it — without one there is nothing to say the list
             * continues, and twenty rows will not fit. */
            val tableScroll = rememberScrollState()
            Row {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 260.dp)
                        .verticalScroll(tableScroll)
                ) {
                DataRow("Miles", rows) { "%.1f".format(it.miles) }
                DataRow("Hours", rows) { "%.1f".format(it.hoursLow) }

                if (featureRows.isNotEmpty()) {
                    SectionRow("FEATURES")
                    /* ⭐ THE COUNT. Fred, 08-27: "there are no totals." The table
                     * showed WHICH features each route reached and never HOW
                     * MANY, so "Route 1 gets seven, Route 4 gets three" could not
                     * be seen at a glance. */
                    DataRow("", rows, bold = true) { r -> r.features.size.toString() }
                    featureRows.forEach { f ->
                        /* ⭐ A CHECK OR NOTHING. The mile marker was noise: in a
                         * COMPARISON the only question a feature row answers is
                         * whether this route reaches it. "Mile 29" is a fact
                         * about one route read alone and tells the eye nothing
                         * when it is scanning five columns.
                         * ⚠ Absence is BLANK, not a dash — a column of dashes
                         * draws the eye to what is missing. */
                        DataRow(f, rows) { r ->
                            if (r.features.containsKey(f)) "\u2713" else ""
                        }
                    }
                }
                if (trailRows.isNotEmpty()) {
                    SectionRow("TRAILS")
                    DataRow("", rows, bold = true) { r -> r.trails.size.toString() }
                    trailRows.forEach { t ->
                        DataRow(t, rows) { r ->
                            val mi = r.trails[t]
                            // ⚠ mileage STAYS on trails — 12 miles of a trail is
                            // a different ride from 2, and that IS the comparison
                            if (mi != null && mi > 0) "%.1f".format(mi) else ""
                        }
                    }
                }
                }
                /* SAVESELECTED-2026-08-27: the bar was invisible.
                 * ⚠ 3dp wide at 13% white on a near-black panel is nothing. It
                 * WAS drawing -- Fred could scroll and saw no bar. Wider, and
                 * the track is now visible in its own right. */
                if (tableScroll.maxValue > 0) {
                    val frac = tableScroll.value.toFloat() /
                        tableScroll.maxValue.toFloat().coerceAtLeast(1f)
                    Box(
                        Modifier.padding(start = 4.dp).width(6.dp).height(260.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x55000000))
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(56.dp)
                                .offset(y = ((260 - 56) * frac).dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(accentBlue)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TableButton("SAVE SELECTED", Color(0xFF4ADE80), Modifier.weight(1f)) {
                    onSaveSelected()
                }
                TableButton("EXIT", dimText, Modifier.weight(1f)) { onExit() }
            }
        }
    }
}

private val LABEL_W = 130.dp
private val COL_W = 52.dp

@Composable
private fun SectionRow(label: String) {
    Text(label, color = faint, fontSize = 8.sp, fontFamily = mono,
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
        Text(label, color = dimText, fontSize = 9.5.sp, fontFamily = mono,
            maxLines = 1, modifier = Modifier.width(LABEL_W))
        rows.forEach { r ->
            val v = cell(r)
            Text(
                v,
                // ⭐ a check is green and unmistakable; everything else takes the
                // route's own colour so the column stays tied to its line
                color = if (v == "\u2713") Color(0xFF4ADE80) else parseColour(r.colour),
                fontSize = if (v == "\u2713") 13.sp else 9.5.sp,
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
        color = Color(0xFF1D2430)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().clickable { onClick() }
        ) {
            Text(label, color = tint, fontSize = 9.sp, fontFamily = mono,
                fontWeight = FontWeight.Bold)
        }
    }
}
