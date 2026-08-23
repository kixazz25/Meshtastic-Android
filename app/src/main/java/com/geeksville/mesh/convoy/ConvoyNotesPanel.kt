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
)

@Composable
fun ConvoyNotesPanel(
    title: String,
    subtitle: String? = null,
    sections: List<NoteSection>,
    /** Shown at the foot of every notes panel. Null to omit. */
    footer: String? =
        "\u26a0 GroupTrack knows a trail is there. It does not know how wide it is, " +
        "what the surface is like, or what is on it. Zoom in on the satellite view " +
        "before you commit.",
    onClose: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    val shown = sections.filter { it.lines.isNotEmpty() || it.pairs.isNotEmpty() }

    Box(Modifier.fillMaxSize().background(npBg)) {
        Column(Modifier.fillMaxSize()) {

            // header stays put while the body scrolls
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 14.dp, 12.dp, 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title.ifBlank { "Details" }, color = npGreen, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(6.dp), color = npCard,
                    modifier = Modifier.clickable { onClose() }) {
                    Text("CLOSE", color = npDim, fontSize = 11.sp, fontFamily = npMono,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(13.dp, 8.dp))
                }
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = npDim, fontSize = 12.5.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 6.dp))
            }

            if (shown.isEmpty()) {
                Text("Nothing recorded for this yet.",
                    color = npFaint, fontSize = 12.5.sp,
                    modifier = Modifier.padding(16.dp, 10.dp))
                return@Column
            }

            Column(
                Modifier.weight(1f).verticalScroll(scroll)
                    .padding(16.dp, 8.dp, 16.dp, 22.dp)
            ) {
                shown.forEach { s ->
                    s.heading?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = npFaint, fontSize = 9.5.sp, fontFamily = npMono,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                    }
                    when (s.style) {
                        NoteStyle.STATS ->
                            Surface(shape = RoundedCornerShape(7.dp), color = npCard,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(13.dp, 11.dp)) {
                                    s.pairs.forEach { (k, v) ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                            Text(k, color = npDim, fontSize = 12.sp,
                                                modifier = Modifier.weight(1f))
                                            Text(v, color = npTxt, fontSize = 12.sp,
                                                fontFamily = npMono,
                                                fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        NoteStyle.WARN ->
                            s.lines.forEach {
                                Surface(shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF241D0E),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                                    Text(it, color = Color(0xFFD8CBA8), fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(11.dp, 10.dp))
                                }
                            }
                        NoteStyle.QUIET ->
                            s.lines.forEach {
                                Text(it, color = npFaint, fontSize = 11.5.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(bottom = 4.dp))
                            }
                        NoteStyle.PLAIN ->
                            s.lines.forEach {
                                Text(it, color = npDim, fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(bottom = 3.dp))
                            }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                footer?.takeIf { it.isNotBlank() }?.let {
                    Surface(shape = RoundedCornerShape(7.dp), color = npCard,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(it, color = npFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
                            modifier = Modifier.padding(12.dp, 11.dp))
                    }
                }
            }
        }
    }
}

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

    fun arr(key: String, heading: String, style: NoteStyle = NoteStyle.PLAIN) {
        val a = nar?.optJSONArray(key) ?: return
        val l = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        if (l.isNotEmpty()) out.add(NoteSection(heading, l, style = style))
    }
    arr("what_you_will_see", "WHAT YOU WILL SEE")
    arr("stops_in_order", "IN ORDER")

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
