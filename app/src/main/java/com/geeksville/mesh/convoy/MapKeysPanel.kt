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
 * MapKeysPanel — the map key, and the filter. REWRITTEN CLEAN 2026-09-02.
 *
 * ⛔ THIS FILE WAS REWRITTEN FROM SCRATCH after eight builds of patches that
 * each fixed a symptom and created the next one. Fred, 09-02: "this thing is a
 * mess, delete it and start over -- why are we chasing this ridiculous thing
 * for 8 builds?" He was right. What follows is written once, whole.
 *
 * ⭐⭐ THE RULES (Fred, 09-02), and every one of them is DERIVED STATE:
 *
 *   1. Two categories: MOTORIZED and NON-MOTORIZED.
 *   2. A row checkbox selects that row.
 *   3. THE CATEGORY CHECKBOX REFLECTS ITS ROWS. Any row on -> category shows
 *      on. No rows on -> category shows off. ⛔ It is NOT a separate flag.
 *   4. Tapping the category checkbox turns ALL its rows on; tapping it again
 *      when they are all on turns them all off.
 *   5. Turning ONE row on turns its category on, because of rule 3.
 *   6. Both categories can be on at once -- they are independent.
 *
 * ⚠ Rows are the only truth. Nothing else stores on/off, so nothing else can
 * disagree with them. Every earlier version of this panel kept a header flag
 * beside the rows and they drifted apart.
 *
 * ⛔⛔ THE BUG THAT COST THE DAY, recorded so it is not repeated:
 * TrailFilterState is a plain singleton, NOT Compose state. Toggling a category
 * changes nothing Compose watches. The panel bumps [tick] to force
 * recomposition -- and EVERY composable that reads TrailFilterState MUST TAKE
 * tick AS A PARAMETER, or Compose skips it as unchanged and the screen does not
 * move while the state underneath changes. KeyColumn took it; KeyRowItem did
 * not; the headings worked and the rows appeared dead.
 */
@Composable
fun MapKeysPanel(
    onDismiss: () -> Unit,
    onFilterChanged: () -> Unit = {},
    onStyleChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableIntStateOf(0) }
    var styling by remember { mutableStateOf<MapKeyRow?>(null) }

    fun changed() { tick++; onFilterChanged() }

    // ⭐ The style panel REPLACES this one and returns to it on apply, so the
    // rider works one category at a time and lands back in the list.
    val s = styling
    if (s != null) {
        TrailStylePanel(
            row = s,
            onCancel = { styling = null },
            onApply = { c, d, w ->
                TrailFilterState.setStyle(s.name, c, d, w)
                styling = null
                tick++
                onStyleChanged()
            },
            modifier = modifier
        )
        return
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xF2000000),
        shadowElevation = 8.dp,
        modifier = modifier.width(360.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("MAP KEY / FILTERS", color = Color(0xFF8FD0FF),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f))
                Box(contentAlignment = Alignment.Center,
                    modifier = Modifier.width(28.dp).height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1B2027))
                        .border(1.dp, Color(0xFF47505A), RoundedCornerShape(4.dp))
                        .clickable { onDismiss() }) {
                    Text("\u2715", color = Color(0xFFE6EDF3), fontSize = 14.sp,
                        fontWeight = FontWeight.Bold)
                }
            }

            // ── LAND: ONE checkbox ────────────────────────────────────────
            // ⭐⭐ Fred, 09-02: "just keep public on -- the only thing we are
            // toggling is private." And, asked whether private-only is ever
            // wanted: "no, never private alone."
            // ⭐ So this is not a three-way slice and never was. Public ground
            // is always shown; the only question is whether private comes with
            // it -- which is the near-town case, where a private road is how
            // you get from one piece of public land to the next.
            // ⚠ It cannot reach an empty map, because it cannot turn public off.
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                val includePrivate = TrailFilterState.land == "ALL"
                LandCheck("Include private land", includePrivate, tick,
                    Modifier.fillMaxWidth()) {
                    TrailFilterState.setLand(if (includePrivate) "PUBLIC" else "ALL")
                    changed()
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Category("MOTORIZED", MOTORIZED_ROWS, tick, ::changed,
                    { styling = it }, Modifier.weight(1f))
                Spacer(Modifier.width(1.dp).height(150.dp)
                    .background(Color(0xFF30363D)))
                Category("NON-MOTORIZED", NON_MOTORIZED_ROWS, tick, ::changed,
                    { styling = it }, Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30363D)))
            Spacer(Modifier.height(6.dp))

            // ⚠ The rider's own tracks and routes. No checkbox -- they are not
            // source data and the key is not where they are turned off.
            ARTIFACT_ROWS.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Spacer(Modifier.width(18.dp))
                    LineSwatch(r, true)
                    Spacer(Modifier.width(8.dp))
                    Text(r.label, color = Color(0xFFDDE3E9), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(6.dp))
            Text("Tap a row to show or hide it. Tap its name to change the colour.",
                color = Color(0xFF8B949E), fontSize = 10.sp)
        }
    }
}

/**
 * One category column. ⭐ Its checkbox is DERIVED: on when any row is on.
 * Tapping it turns everything on, or everything off if it is already all on.
 */
@Composable
private fun Category(
    title: String,
    rows: List<MapKeyRow>,
    tick: Int,
    onChanged: () -> Unit,
    onStyle: (MapKeyRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_EXPRESSION") tick
    val names = rows.map { it.name }
    val anyOn = names.any { TrailFilterState.isOn(it) }
    val allOn = names.all { TrailFilterState.isOn(it) }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    // all on -> clear; otherwise -> set them all
                    TrailFilterState.setGroup(names, !allOn)
                    onChanged()
                }
                .padding(vertical = 6.dp)) {
            Tick(anyOn)
            Spacer(Modifier.width(4.dp))
            Text(title, color = if (anyOn) Color(0xFFE6EDF3) else Color(0xFF5B646E),
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30363D)))
        Spacer(Modifier.height(3.dp))
        rows.forEach { r -> KeyRow(r, tick, onChanged, onStyle) }
    }
}

/**
 * One row. ⚠ TAKES tick — see the class note. Without it Compose skips the row
 * and the tick never changes on screen even though the state did.
 */
@Composable
private fun KeyRow(
    r: MapKeyRow,
    tick: Int,
    onChanged: () -> Unit,
    onStyle: (MapKeyRow) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") tick
    val on = TrailFilterState.isOn(r.name)
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clickable { TrailFilterState.toggleCategory(r.name); onChanged() }
            .padding(vertical = 6.dp)) {
        Tick(on)
        Spacer(Modifier.width(4.dp))
        LineSwatch(r, on)
        Spacer(Modifier.width(6.dp))
        Text(r.label,
            color = if (on) Color(0xFFDDE3E9) else Color(0xFF5B646E),
            fontSize = 12.sp,
            modifier = Modifier.clickable { onStyle(r) })
    }
}

/** ⭐ Just a check mark. Present means shown, absent means hidden. */
@Composable
private fun Tick(on: Boolean) {
    Box(contentAlignment = Alignment.Center,
        modifier = Modifier.width(20.dp).height(20.dp)) {
        if (on) Text("\u2713", color = Color(0xFF58A6FF), fontSize = 17.sp,
            fontWeight = FontWeight.Bold)
    }
}

/** The line as the map draws it: colour, pattern and weight together. */
@Composable
private fun LineSwatch(r: MapKeyRow, on: Boolean) {
    val saved = TrailFilterState.style[r.name]
    val col = if (saved != null) parseHex(saved.first) else r.color
    val dash = if (saved != null) saved.second else (if (r.dashed) "9,5" else null)
    val w = saved?.third ?: r.weight
    val c = if (on) col else col.copy(alpha = 0.3f)
    Box(contentAlignment = Alignment.CenterStart,
        modifier = Modifier.width(30.dp).height(14.dp)) {
        if (dash == null) {
            Spacer(Modifier.fillMaxWidth().height(w.dp)
                .clip(RoundedCornerShape(1.dp)).background(c))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(4) {
                    Spacer(Modifier.width(5.dp).height(w.dp)
                        .clip(RoundedCornerShape(1.dp)).background(c))
                }
            }
        }
    }
}

/** ⚠ Same tick as the rows, so it reads as the same kind of control. */
@Composable
private fun LandCheck(label: String, on: Boolean, tick: Int,
                      modifier: Modifier = Modifier, onClick: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onClick() }.padding(vertical = 6.dp)) {
        Tick(on)
        Spacer(Modifier.width(4.dp))
        Text(label, color = if (on) Color(0xFFE6EDF3) else Color(0xFF5B646E),
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ── the rows ────────────────────────────────────────────────────────────
// ⚠ These names are the literal carto_code values classify4 writes. A typo
// shows a row that never lights up and never filters anything.

data class MapKeyRow(
    val name: String,
    val label: String,
    val color: Color,
    val weight: Int,
    val dashed: Boolean = false,
)

val MOTORIZED_ROWS = listOf(
    MapKeyRow("OHV", "OHV", Color(0xFF00CCFF), 5),
    MapKeyRow("track", "Track", Color(0xFF00AAFF), 5),
    MapKeyRow("forestry/access road", "Forestry / access", Color(0xFF0077DD), 4),
    MapKeyRow("shape only", "Shape only", Color(0xFF0044AA), 3, dashed = true),
    // ⚠ Acts on `status`, not carto_code -- a trail is unofficial AND a track.
    // 501 rows: too few to subset across nine categories, so one summary row.
    MapKeyRow(TrailFilterState.ROW_UNOFFICIAL, "Unofficial / uncertain",
        Color(0xFFFF2222), 3, dashed = true),
)

val NON_MOTORIZED_ROWS = listOf(
    MapKeyRow("hiking and biking", "Hiking & biking", Color(0xFF66CC66), 2),
    MapKeyRow("hiking", "Hiking", Color(0xFFFFCC00), 2),
    MapKeyRow("biking", "Biking", Color(0xFFAA44FF), 2),
    MapKeyRow("equestrian", "Equestrian", Color(0xFFCC8844), 2, dashed = true),
    MapKeyRow("steps/bridge", "Steps / bridge", Color(0xFF888888), 2, dashed = true),
)

val ARTIFACT_ROWS = listOf(
    MapKeyRow("__track", "My tracks", Color(0xFF39FF14), 3, dashed = true),
    MapKeyRow("__route", "My routes", Color(0xFFFF00FF), 3, dashed = true),
)
