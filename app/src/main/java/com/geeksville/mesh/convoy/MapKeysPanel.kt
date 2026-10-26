package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
// DEADCODE3-2026-10-25: the old Map Keys panel and its private pieces (Category, KeyRow, Tick, LineSwatch) removed -- MAP KEY is
// GtKeyBody (grouptrack/KeyModule.kt). The rows and help below are live: KEY, FILTER and the trail panels read them.

// DEADCODE2-2026-10-23: LandCheck (unused) moved to retired/2.7c_dead/pieces/convoy/MapKeysPanel.pieces.txt

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
    // MAPKEYROWS-2026-09-08: was weight 3 and dashed. The shipped asset now
    // carries it SOLID at 5 -- Fred, 09-08: "all motorized a straight line, not
    // dashes." The legend was drawing a line the map does not.
    MapKeyRow("shape only", "Shape only", Color(0xFF0044AA), 5),
    // MAPKEYROWS-2026-09-08: \u26d4 `rider` SHIPPED IN THREE OF THE FOUR PLACES A
    // CATEGORY LIVES and was missing from this one -- so it drew correctly on
    // the map and had no legend row, and could not be switched off.
    // \u2b50 Ground a rider recorded where nothing was published. Its own blue, in
    // the rideable family: a different KIND of claim from a survey.
    MapKeyRow("rider", "Rider trails", Color(0xFF2196F3), 5),
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

/**
 * CATEGORYHELP-2026-09-10: what each row actually means. Fred's wording, 09-10.
 */
val CATEGORY_HELP: List<Pair<String, String>> = listOf(
    "OHV" to "Posted trails for ATV/OHV use only",
    "Track" to "Two wheel tracks, farm, forest or open country",
    "Forestry / access" to "Access unless posted otherwise",
    "Shape only" to "Shape and route of reported trails that are otherwise unclassified",   // SHAPETEXT-2026-10-01 (Fred): was "Mapped trails but unclassified"
    "Rider trails" to "Ridden on a recorded track \u2014 UTV accessible",   // MAPKEYSVOCAB-2026-10-02: a track is a recording
    "Unofficial / uncertain" to "Not available for riding",
    "Hiking & biking" to "Non-motorized, walkers and bicycles",
    "Hiking" to "On foot only",
    "Biking" to "Bicycles only",
    "Equestrian" to "Horse trails",
    "Steps / bridge" to "Stairs and footbridges \u2014 not rideable",
    "My tracks" to "Tracks you have recorded on this device",   // MAPKEYSVOCAB-2026-10-02
    "My routes" to "Routes you have planned or been sent",
)

/** CATEGORYHELP-2026-09-10: which heading a row sits under in the dialog. */
private fun groupOf(name: String): String = when (name) {
    "OHV", "Track", "Forestry / access", "Shape only",
    "Rider trails", "Unofficial / uncertain" -> "MOTORIZED"
    "My tracks", "My routes" -> "YOURS"
    else -> "NON-MOTORIZED"
}

val ARTIFACT_ROWS = listOf(
    // MAPKEYROWS-2026-09-08: 3 -> 4, matching the map. The three tiers are
    // trails 5, tracks 4, routes 3 -- widest underneath, so each shows as a
    // fringe outside the one above it. __route is already 3 and correct.
    MapKeyRow("__track", "My tracks", Color(0xFF39FF14), 4, dashed = true),
    // ROUTESOLID-2026-09-11: routes draw solid now, like every other
    // motorized line. ⚠ __track stays dashed -- loadTracks really
    // does draw it dashed, and at 10,5 the gap is visible.
    MapKeyRow("__route", "My routes", Color(0xFFFF00FF), 3, dashed = false),
)
