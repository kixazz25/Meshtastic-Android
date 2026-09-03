package com.geeksville.mesh.convoy

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

    // DRAGCOMPARE-2026-09-02: the compare panel drags, like Map Features
    // (Fred, 09-01). ⭐ COPIED, NOT INVENTED -- ConvoyArtifactsPanel:81-90 has
    // had this exact pattern working for weeks, so the panels behave alike
    // rather than each having its own feel.
    // ⚠ The offset is deliberately NOT persisted: it resets when the panel
    // closes. A panel that reopens off-screen because of where it was dragged
    // last time is worse than one that always opens where you expect.
    // SURFACECOL-2026-09-03: trail name -> surface, read ONCE when the panel
    // opens.
    // ⛔ TWO QUERIES, NOT A JOIN. `trails` is in the spatial database and
    // `trail_properties` is in the extension database -- they cannot be joined
    // in one statement. Step 8 hit exactly this and had to read
    // designated_uses into a map the same way. My first draft was a JOIN and
    // would have failed at runtime, not compile time.
    // ⚠ Keyed on NAME because that is what BatchRow.trails carries; duplicate
    // names collapse to whichever row comes first, which is fine for a label
    // and would NOT be for anything that routes.
    val surfaceOf = remember(rows) {
        val out = HashMap<String, String>()
        try {
            val names = rows.flatMap { it.trails.keys }.distinct()
            val sdb = SpatialDbManager.getSpatialDb()
            val edb = SpatialDbManager.getExtensionDb()
            if (names.isNotEmpty() && sdb != null && edb != null) {
                // 1. names -> ids, from the spatial database
                val idToName = HashMap<String, String>()
                val marks = names.joinToString(",") { "?" }
                sdb.rawQuery(
                    "SELECT trail_id, name FROM trails WHERE name IN ($marks)",
                    names.toTypedArray()
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        idToName[id] = c.getString(1) ?: continue
                    }
                }
                // 2. ids -> surface, from the extension database
                if (idToName.isNotEmpty()) {
                    val ids = idToName.keys.toList()
                    val m2 = ids.joinToString(",") { "?" }
                    edb.rawQuery(
                        "SELECT trail_id, surface_type FROM trail_properties " +
                            "WHERE trail_id IN ($m2) AND surface_type IS NOT NULL " +
                            "AND TRIM(surface_type) <> ''",
                        ids.toTypedArray()
                    ).use { c ->
                        while (c.moveToNext()) {
                            val n = idToName[c.getString(0)] ?: continue
                            if (!out.containsKey(n)) out[n] = c.getString(1)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BatchGrid", "surface lookup: ${e.message}")
        }
        out
    }

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
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
        Column(modifier = Modifier.padding(8.dp)) {
            // COMPARECLOSE-2026-09-02: ⛔ THE COMPARE PANEL HAD NO CLOSE. I
            // added the DRAG here this morning and not the exit -- Fred tested
            // it and reported "no close on compare", which is the fifth panel
            // this week with a missing or invisible one.
            // ⭐ onExit already exists as a parameter, so this is an
            // affordance, not new behaviour.
            // ⚠ Boxed and 26x22, matching Map Keys, Map Features and Route+ --
            // the earlier ones were 10sp glyphs the same colour as the title
            // beside them and nobody could find them.
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(batchName.uppercase(), color = accentBlue, fontSize = 10.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.width(26.dp).height(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1B2027))
                        .border(1.dp, Color(0xFF47505A), RoundedCornerShape(4.dp))
                        .clickable { onExit() }
                ) {
                    Text("\u2715", color = Color(0xFFE6EDF3), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
            // ⚠ (7) tapping a header is not discoverable, and the dimming only
            // makes sense once the rider knows it is a control
            Text("Click on route to hide / unhide on map",
                color = dimText, fontSize = 9.5.sp, fontFamily = mono,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))

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
                        // SURFACECOL-2026-09-03: ⭐ THE SURFACE, ON THE TRAIL'S
                        // OWN LABEL. Fred, 09-02: "just append the surface type
                        // to each trail -- that will tell us how long on this
                        // surface type."
                        // ⭐ And the table is ALREADY SORTED BY TOTAL MILEAGE, so
                        // the surfaces a rider is actually covering land at the
                        // top where they are read. No new arithmetic: the row
                        // already carries the miles.
                        // ⚠ BLANK STAYS BLANK. Surface is recorded on about 39%
                        // of the motorized set -- the best-covered attribute we
                        // have, and still a minority. A trail with none says
                        // nothing rather than guessing.
                        val label = surfaceOf[t]?.let { s -> "$t  ·  $s" } ?: t
                        DataRow(label, rows) { r ->
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
                // ⚠ the deletion is the half a rider would otherwise find out about
                // afterwards, so it is on the button
                TableButton("SAVE SELECTED / DELETE UNSELECTED",
                    Color(0xFF4ADE80), Modifier.weight(1f)) {
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
