package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * ARTIFACT NOTES PANEL — NOTESPANEL-2026-08-23
 *
 * ⭐⭐ ONE PANEL, MANY CALLERS. Fred, 08-23: "is the read and display of the
 * narrative the same feature we are adding to artifact details? ... is the
 * function callable and receives a blob for display so we can use the same
 * display features on the group db read." — Yes, and this is that panel.
 *
 * ⚠ IT TAKES RENDERED CONTENT, NOT A DATA SOURCE. An earlier draft of this
 * took a JSONObject and reached inside for `narrative`, `summary`,
 * `what_you_will_see` — which made it a WIP-notes panel, not a notes panel, and
 * the saved-route case would have needed its own copy. The JSON parsing belongs
 * with whoever OWNS the data:
 *
 *     WIP route      -> RouteDraftStore.readNotes()  -> notesFromDraft()  -> here
 *     saved route    -> route_notes rows by seq      -> (builder)         -> here
 *     trail          -> trail_properties + observations                   -> here
 *     server (3.0)   -> whatever comes down the wire                      -> here
 *
 * Same body every time. Fred, 08-22: "I wonder if the route artifact panel IS
 * the notes — that is what we are after."
 *
 * ⚠ SCROLLABLE, NOT AUTO-SCROLLING (Fred corrected this on 08-23). Entries are
 * mile-prefixed — "mile 28  Oak Spring and Aspen Spring" — and read badly if
 * they wrap mid-entry, so the body is sized to fit one line.
 *
 * ⚠ SECTIONS RENDER DEFENSIVELY. An empty section is skipped rather than
 * leaving a heading with nothing under it. Half a narrative is more useful than
 * a panel that refuses to open.
 */

private val npBg    = Color(0xFF0F1419)
private val npCard  = Color(0xFF131A24)
private val npGreen = Color(0xFF7BB661)
private val npTxt   = Color(0xFFE6EDF3)
private val npDim   = Color(0xFF8899AA)
private val npFaint = Color(0xFF667788)
private val npMono  = FontFamily.Monospace

/** How a section is drawn. The CALLER picks; the panel does not infer. */
enum class NoteStyle {
    /** Ordinary lines. Stops, features, prose. */
    PLAIN,
    /** Label/value pairs, monospace values. Distance, time, counts. */
    STATS,
    /** Amber blocks. Warnings the rider should not skim past. */
    WARN,
    /** Dim italic. Provenance, caveats, "not reported". */
    QUIET,
}

/**
 * One block of the panel. `lines` for PLAIN/WARN/QUIET; `pairs` for STATS.
 * A section with neither is skipped.
 */
data class NoteSection(
    val heading: String? = null,
    val lines: List<String> = emptyList(),
    val pairs: List<Pair<String, String>> = emptyList(),
    val style: NoteStyle = NoteStyle.PLAIN,
    /**
     * NOTESTWISTY-2026-09-03: start folded, opened by tapping the heading.
     *
     * ⭐ Fred, 09-03, on why this panel needs it: the route narrative is long --
     * a headline, four stats, ten features, ten stops in order, ground covered
     * and a warning. All of it worth having, and all of it at once buries the
     * three things a rider decides on.
     * ⚠ A section with NO HEADING cannot fold -- there would be nothing to tap.
     * The headline is one of those, which is correct: it is the first thing you
     * should see.
     */
    val collapsed: Boolean = false,
)

// DEADCODE2-2026-10-23: ConvoyNotesPanel (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyNotesPanel.pieces.txt

// ─────────────────────────────────────────────────────────────────────────
// BUILDERS — one per data source. These know the shapes; the panel does not.
// ⚠ Adding a source means adding a builder here, NOT changing the panel.
// ─────────────────────────────────────────────────────────────────────────

/**
 * NOTESPANEL-2026-08-23: a WIP draft's `notes` block -> sections.
 * The narrative is written by the route generator, so this mirrors what it
 * emits. A missing key yields an empty section and is skipped by the panel.
 */
fun notesFromDraft(notes: JSONObject?): List<NoteSection> {
    if (notes == null) return emptyList()
    val out = ArrayList<NoteSection>()
    val nar = notes.optJSONObject("narrative")
    val sum = notes.optJSONObject("summary")

    nar?.optString("headline")?.takeIf { it.isNotBlank() }?.let {
        out.add(NoteSection(lines = listOf(it)))
    }

    if (sum != null) {
        val p = ArrayList<Pair<String, String>>()
        sum.optDouble("total_miles", -1.0).takeIf { it >= 0 }
            ?.let { p.add("Distance" to "%.1f miles".format(it)) }
        sum.optJSONArray("est_hours")?.takeIf { it.length() >= 2 }
            ?.let { p.add("Riding time" to "%.1f \u2013 %.1f hours"
                .format(it.optDouble(0), it.optDouble(1))) }
        sum.optInt("features", -1).takeIf { it >= 0 }?.let { f ->
            val of = sum.optInt("of_available", 0)
            p.add("Features" to if (of > 0) "$f of $of in this area" else "$f")
        }
        sum.optDouble("retrace_pct", -1.0).takeIf { it >= 0 }
            ?.let { p.add("Doubles back" to "%.0f%%".format(it)) }
        if (p.isNotEmpty()) out.add(NoteSection(pairs = p, style = NoteStyle.STATS))
    }

    fun arr(key: String, heading: String, style: NoteStyle = NoteStyle.PLAIN,
            collapsed: Boolean = false) {
        val a = nar?.optJSONArray(key) ?: return
        val l = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        if (l.isNotEmpty())
            out.add(NoteSection(heading, l, style = style, collapsed = collapsed))
    }
    // NOTESTWISTY-2026-09-03: ⭐ THE TWO LONG ONES START FOLDED. A ten-feature
    // route lists ten under WHAT YOU WILL SEE and ten again under IN ORDER --
    // twenty lines that push the stats, the ground covered and the warning off
    // the screen. ⚠ The warning especially: BEFORE YOU GO stays open, always.
    // TRACKDESC-2026-09-26 (Fred): a route's own description (a converted track's rider-written text), shown OPEN.
    nar?.optString("description")?.takeIf { it.isNotBlank() }?.let {
        out.add(NoteSection("ABOUT THIS ROUTE", listOf(it)))
    }
    arr("what_you_will_see", "WHAT YOU WILL SEE", collapsed = true)
    arr("stops_in_order", "IN ORDER", collapsed = true)

    nar?.optString("ground_covered")?.takeIf { it.isNotBlank() }?.let {
        out.add(NoteSection("GROUND COVERED", listOf(it)))
    }
    arr("before_you_go", "BEFORE YOU GO", NoteStyle.WARN)

    return out
}

/**
 * NOTESPANEL-2026-08-23: STUB:ROUTENOTES — the saved-route builder.
 *
 * A saved route's notes live in route_notes (grouptrack_data.db), one row per
 * entry, ordered by seq, each with kind / author / body. Same panel, different
 * source. ⚠ Written when route_notes exists — the point of the builder pattern
 * is that adding it changes nothing above.
 */
fun notesFromRouteId(routeId: String): List<NoteSection> {
    // NARRBTN-2026-08-23Y: the narrative payload is stored VERBATIM in
    // route_notes.payload, so the draft builder renders it unchanged. That is
    // the point of keeping the payload rather than exploding it into columns --
    // a shape change in the generator needs no schema change and no second
    // renderer.
    val payload = SpatialDbManager.readRouteNotes(routeId) ?: return emptyList()
    return notesFromDraft(payload)
}

/**
 * ROUTEDESC-2026-10-23 (Fred 22:44): true when a saved route's narrative has something to READ -- a headline, its own
 * description, or one of the narrative lists. A row with only the summary numbers is false: the detail card already
 * shows those, so the tap opens the card instead of a panel of stats.
 */
fun routeHasDescription(routeId: String): Boolean {
    val nar = SpatialDbManager.readRouteNotes(routeId)?.optJSONObject("narrative") ?: return false
    for (k in listOf("headline", "description", "ground_covered"))
        if (nar.optString(k, "").isNotBlank()) return true
    for (k in listOf("what_you_will_see", "stops_in_order", "before_you_go")) {
        val a = nar.optJSONArray(k) ?: continue
        for (i in 0 until a.length()) if (a.optString(i, "").isNotBlank()) return true
    }
    return false
}
