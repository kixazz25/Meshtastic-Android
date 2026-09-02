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
 * TrailFilterPanel — WHICH trails show. TRAILSELECT-2026-09-02.
 *
 * ⭐⭐ IT OPENS FROM MAP FEATURES > TRAILS > SELECT, which is the point. Fred,
 * 09-02: "now all the selects for all features are in one place, not sometimes
 * here and sometimes there." Tracks, waypoints and routes have always chosen
 * what shows from that button; trails chose from a different panel entirely, so
 * a rider had to learn which control belonged to which artifact.
 *
 * ⭐ AND IT REUSES A DEAD CONTROL. SELECT was greyed out for trails on 09-02
 * because per-trail selection was a trap: SpatialDisplayManager filters to
 * checkedIds when the state is SELECTED, so a trail imported AFTER that list
 * was saved silently never drew. ⛔ With ~146,000 mostly-unnamed trails nobody
 * could build such a list deliberately anyway. The button now does something
 * that makes sense for the artifact it belongs to.
 *
 * ⚠ THE SWATCH IS HERE BUT IT IS NOT A CONTROL. Fred, 09-02: "we can leave the
 * swatch in the filter" — so you can see WHAT you are hiding while you hide it —
 * but "no link to change attributes, only from legend panel." One place to
 * choose what shows, one place to change how it looks.
 *
 * ⚠ AND THE OFF BUTTON STILL DOES THE BLUNT JOB. Fred: "I can hide all trails
 * in one click for map clarity." OFF kills the type, ALL shows everything,
 * SELECT opens this. The same three options every other artifact has.
 */
@Composable
fun TrailFilterPanel(
    onDismiss: () -> Unit,
    onFilterChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableIntStateOf(0) }
    fun changed() { tick++; onFilterChanged() }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xF2000000),
        shadowElevation = 8.dp,
        modifier = modifier.width(360.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("WHICH TRAILS SHOW", color = Color(0xFF8FD0FF),
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

            // ⚠ Public is always shown; the only question is whether private
            // comes with it. There is no private-only case (Fred, 09-02), and
            // this cannot reach an empty map.
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                val includePrivate = TrailFilterState.land == "ALL"
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable {
                            TrailFilterState.setLand(
                                if (includePrivate) "PUBLIC" else "ALL")
                            changed()
                        }
                        .padding(vertical = 6.dp)) {
                    FilterTick(includePrivate)
                    Spacer(Modifier.width(4.dp))
                    Text("Include private land",
                        color = if (includePrivate) Color(0xFFE6EDF3)
                                else Color(0xFF5B646E),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterColumn("MOTORIZED", MOTORIZED_ROWS, tick, ::changed,
                    Modifier.weight(1f))
                Spacer(Modifier.width(1.dp).height(150.dp)
                    .background(Color(0xFF30363D)))
                FilterColumn("NON-MOTORIZED", NON_MOTORIZED_ROWS, tick, ::changed,
                    Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))
            Text("Tap a row to show or hide it. Tap a heading for the whole group.",
                color = Color(0xFF8B949E), fontSize = 10.sp)
        }
    }
}

/** ⭐ Heading tick is DERIVED: on when any row is on. Never a separate flag. */
@Composable
private fun FilterColumn(
    title: String,
    rows: List<MapKeyRow>,
    tick: Int,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_EXPRESSION") tick
    val names = rows.map { it.name }
    val anyOn = names.any { TrailFilterState.isOn(it) }
    val allOn = names.all { TrailFilterState.isOn(it) }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable { TrailFilterState.setGroup(names, !allOn); onChanged() }
                .padding(vertical = 6.dp)) {
            FilterTick(anyOn)
            Spacer(Modifier.width(4.dp))
            Text(title, color = if (anyOn) Color(0xFFE6EDF3) else Color(0xFF5B646E),
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30363D)))
        Spacer(Modifier.height(3.dp))
        rows.forEach { r -> FilterRow(r, tick, onChanged) }
    }
}

/**
 * ⚠ TAKES tick. TrailFilterState is a plain singleton, not Compose state, so a
 * composable that does not take the counter is SKIPPED as unchanged and the
 * screen does not move while the state underneath does. That cost a day on
 * 09-02 in the key panel; the same rule applies here.
 */
@Composable
private fun FilterRow(r: MapKeyRow, tick: Int, onChanged: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    val on = TrailFilterState.isOn(r.name)
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clickable { TrailFilterState.toggleCategory(r.name); onChanged() }
            .padding(vertical = 6.dp)) {
        FilterTick(on)
        Spacer(Modifier.width(4.dp))
        FilterSwatch(r, on)
        Spacer(Modifier.width(6.dp))
        // ⚠ NOT clickable. Changing appearance is the legend's job.
        Text(r.label, color = if (on) Color(0xFFDDE3E9) else Color(0xFF5B646E),
            fontSize = 12.sp)
    }
}

@Composable
private fun FilterTick(on: Boolean) {
    Box(contentAlignment = Alignment.Center,
        modifier = Modifier.width(20.dp).height(20.dp)) {
        if (on) Text("\u2713", color = Color(0xFF58A6FF), fontSize = 17.sp,
            fontWeight = FontWeight.Bold)
    }
}

/** Display only — the rider's own colours, so what is here matches the map. */
@Composable
private fun FilterSwatch(r: MapKeyRow, on: Boolean) {
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
