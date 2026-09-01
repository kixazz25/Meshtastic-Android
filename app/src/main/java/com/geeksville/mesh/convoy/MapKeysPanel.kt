package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
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
 * MapKeysPanel — the Map Key/Filters panel. MAPKEYS-2026-09-01.
 *
 * ⭐⭐ ONE COMPOSABLE, BOTH MAPS. Fred, 09-01: "it needs to be on both maps ...
 * easier to do as a called function?" — yes, and it is the only way they cannot
 * diverge. ⛔ Three copies of `trailColor` drifted apart across the asset files
 * and one sat a version behind for two days before the field xref found it.
 * ⚠ Today the legend exists ONLY on the planner (ConvoyMapViewerScreen); the
 * convoy map (ConvoyScreen) has none. This gives both the same one.
 *
 * ⭐ THE CATEGORIES ARE NOT HARDCODED HERE. They come from [CATEGORIES] below,
 * which mirrors what classify4 writes into carto_code. A category with nothing
 * in it is still listed for now — dropping empty rows needs a live count, which
 * arrives with the filter wiring.
 *
 * ⭐ BLUE = RIDEABLE, AND THE SHADE CARRIES CONFIDENCE (Fred, 08-31): "I want to
 * look at the map and see what is potentially rideable ... that would make all
 * riding trails a shade of blue." OHV is stated in the data; shape-only means
 * nothing was said and it is in on shape alone, so it is the faintest and
 * dashed.
 *
 * ⚠ THIS BUILD IS THE PANEL ONLY — NO FILTERS YET (Fred, 09-01: "lets swap the
 * panels first with no filters being built and then come back and polish
 * filters"). Rows do not toggle and nothing reaches the SQL. TrailFilterState
 * exists and is loaded, but the two slice controls and the per-row checkboxes
 * are the next step.
 *
 * ⚠ AND THE LAUNCHER IS UNCHANGED for now (Fred: "leave the old fab launcher in
 * place until everything is working"). The text button reading
 * "Map Key/Filters", the bottom-left placement and dragging come with the
 * button swap, once the panel itself is proven.
 */

/** One row of the key. */
data class MapKeyRow(
    val name: String,
    val label: String,
    val color: Color,
    val weight: Int,
    val dashed: Boolean = false,
)

/**
 * ⚠ MUST MIRROR classify4 / OwnershipReclass. These names are the literal
 * values written into carto_code, and the panel matches on them — a typo here
 * shows a row that never lights up.
 */
val MOTORIZED_ROWS = listOf(
    MapKeyRow("OHV", "OHV", Color(0xFF00CCFF), 5),
    MapKeyRow("track", "Track", Color(0xFF00AAFF), 5),
    MapKeyRow("forestry/access road", "Forestry / access", Color(0xFF0077DD), 4),
    MapKeyRow("shape only", "Shape only", Color(0xFF0044AA), 3, dashed = true),
)

val NON_MOTORIZED_ROWS = listOf(
    MapKeyRow("hiking and biking", "Hiking & biking", Color(0xFF66CC66), 2),
    MapKeyRow("hiking", "Hiking", Color(0xFFFFCC00), 2),
    MapKeyRow("biking", "Biking", Color(0xFFAA44FF), 2),
    MapKeyRow("equestrian", "Equestrian", Color(0xFFCC8844), 2, dashed = true),
    MapKeyRow("steps/bridge", "Steps / bridge", Color(0xFF888888), 2, dashed = true),
)

/**
 * ⛔ Private is a SLICE, not a category (Fred, 08-31): "public and private and
 * motorized/non-motorized are FILTERS. Cats exist in those four subsets."
 * It is listed here so the colour is identifiable, but it is not a category the
 * classifier assigns — it is land_status, and the filter will act on that.
 */
val OTHER_ROWS = listOf(
    MapKeyRow("R - Residential Roads", "Residential (private)", Color(0xFFFF1493), 2),
    // ⭐ UNOFFICIAL / UNCERTAIN acts on `status`, not carto_code -- a trail is
    // unofficial AND a track, not one instead of the other. 501 rows on the
    // motorized set: too few to subset across nine categories (Fred, 09-01),
    // so they are grouped into one summary row.
    // ⚠ RED even though unofficial is not forbidden. Fred: "if they ask the
    // question we have done our job" -- a question beats a wrong assumption.
    MapKeyRow(TrailFilterState.ROW_UNOFFICIAL, "Unofficial / uncertain",
        Color(0xFFFF2222), 3, dashed = true),
    // ⛔ NO "Closed" ROW. Fred, 09-01: "closed never display, it is just in
    // db." Kept on import so a future reopening costs nothing, but excluded
    // from every viewport query unconditionally -- not a rider toggle, so it
    // has no key entry either.
)

/** The rider's own artifacts. ⚠ No checkbox — these are theirs, not source data. */
val ARTIFACT_ROWS = listOf(
    MapKeyRow("__track", "My tracks", Color(0xFF39FF14), 3, dashed = true),
    MapKeyRow("__route", "My routes", Color(0xFFFF00FF), 3, dashed = true),
)

@Composable
fun MapKeysPanel(
    onDismiss: () -> Unit,
    onFilterChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ⚠ TrailFilterState is a singleton, not Compose state, so a change does
    // not recompose on its own. This counter is what redraws the ticks.
    var tick by remember { mutableIntStateOf(0) }
    fun changed() { tick++; onFilterChanged() }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xF2000000),
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onDismiss() }.padding(bottom = 5.dp)
            ) {
                Text(
                    "MAP KEY / FILTERS",
                    color = Color(0xFF8FD0FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text("\u00D7", color = Color(0xFF8FD0FF), fontSize = 12.sp)
            }

            // ── the ONE slice: LAND ────────────────────────────────────
            // ⛔ NO use slice (Fred, 09-01): "drop the motorized/non-motorized
            // slice, those controls are in the column headers now." Two
            // controls doing the same job through different mechanisms is how
            // a UI starts lying about itself.
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                SliceButton("Both", TrailFilterState.land == "ALL", tick) {
                    TrailFilterState.setLand("ALL"); changed()
                }
                SliceButton("Public", TrailFilterState.land == "PUBLIC", tick) {
                    TrailFilterState.setLand("PUBLIC"); changed()
                }
                SliceButton("Private", TrailFilterState.land == "PRIVATE", tick) {
                    TrailFilterState.setLand("PRIVATE"); changed()
                }
            }

            // ⭐ Two groups SIDE BY SIDE with a divider (Fred, 08-31): "move
            // motorized and non motorized side by side with borders to
            // separate."
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                KeyColumn("MOTORIZED", MOTORIZED_ROWS, tick, ::changed, Modifier.weight(1f))
                Spacer(
                    Modifier.width(1.dp).height(78.dp)
                        .background(Color(0xFF30363D))
                )
                KeyColumn("NON-MOTORIZED", NON_MOTORIZED_ROWS, tick, ::changed, Modifier.weight(1f))
            }

            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30363D)))
            Spacer(Modifier.height(5.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                KeyColumn("OTHER", OTHER_ROWS, tick, ::changed, Modifier.weight(1f))
                Spacer(
                    Modifier.width(1.dp).height(52.dp)
                        .background(Color(0xFF30363D))
                )
                KeyColumn("MINE", ARTIFACT_ROWS, tick, null, Modifier.weight(1f))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "select a column header to turn all types on or off",
                color = Color(0xFF5B646E), fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SliceButton(
    label: String, selected: Boolean, tick: Int, onClick: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") tick   // read so the button recomposes
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(58.dp).clip(RoundedCornerShape(4.dp))
            .background(if (selected) Color(0xFF12203A) else Color(0xFF0C1015))
            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(4.dp))
            .clickable { onClick() }.padding(vertical = 4.dp)
    ) {
        Text(label,
            color = if (selected) Color(0xFF58A6FF) else Color(0xFF8B949E),
            fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckBox(on: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(12.dp).height(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (on) Color(0xFF58A6FF) else Color.Transparent)
            .border(1.dp, Color(0xFF47505A), RoundedCornerShape(3.dp))
    ) {
        if (on) Text("\u2713", color = Color(0xFF0A0D10), fontSize = 8.sp)
    }
}

@Composable
private fun KeyColumn(
    title: String,
    rows: List<MapKeyRow>,
    tick: Int,
    onChanged: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_EXPRESSION") tick
    val names = rows.map { it.name }
    // ⭐ The header tick reflects the ROWS, not a separate flag -- so checking
    // one row brings the column back on (Fred, 08-31). A header flag of its own
    // could disagree with what is under it.
    val anyOn = onChanged != null && names.any { TrailFilterState.isOn(it) }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).then(
                if (onChanged != null)
                    Modifier.clickable {
                        TrailFilterState.setGroup(names, !anyOn); onChanged()
                    }
                else Modifier
            )
        ) {
            if (onChanged != null) { CheckBox(anyOn); Spacer(Modifier.width(5.dp)) }
            // ⭐ Centred and larger than the rows (Fred, 08-31): "center column
            // headings with a font larger."
            Text(title,
                color = if (onChanged == null || anyOn) Color(0xFFE6EDF3)
                        else Color(0xFF5B646E),
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        rows.forEach { r ->
            KeyRowItem(r, onChanged)
            Spacer(Modifier.height(3.dp))
        }
    }
}

/**
 * ⭐ The LINE ITSELF carries all three presentation attributes — colour,
 * pattern and thickness (Fred, 08-31): "combine color, pattern and thickness in
 * the display for the line image." When styling lands, tapping this swatch is
 * what opens the row's details; no separate edit button.
 */
@Composable
private fun KeyRowItem(r: MapKeyRow, onChanged: (() -> Unit)?) {
    // ⚠ MINE rows pass null: the rider's own tracks and routes are theirs, not
    // source data, and have no filter toggle.
    val on = onChanged == null || TrailFilterState.isOn(r.name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onChanged != null)
            Modifier.clickable {
                TrailFilterState.toggleCategory(r.name); onChanged()
            } else Modifier
    ) {
        if (onChanged != null) { CheckBox(on); Spacer(Modifier.width(5.dp)) }
        val c = if (on) r.color else r.color.copy(alpha = 0.25f)
        if (r.dashed) {
            // ⚠ Compose has no dashArray on a Box; three short segments read as
            // a dashed line at this size and cost nothing.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) {
                    Spacer(
                        Modifier.width(5.dp).height(r.weight.dp)
                            .clip(RoundedCornerShape(1.dp)).background(c)
                    )
                }
            }
        } else {
            Spacer(
                Modifier.width(19.dp).height(r.weight.dp)
                    .clip(RoundedCornerShape(1.dp)).background(c)
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            r.label,
            color = if (on) Color(0xFFDDE3E9) else Color(0xFF4A5158),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
