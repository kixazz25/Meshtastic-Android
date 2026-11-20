/*
 * NOTESMOVE-2026-11-19 (2.8b). NoteStyle and NoteSection, moved app -> commonMain with the
 * SAME package name, so every Android consumer compiles untouched -- the RecordingState
 * pattern (one symbol, no typealias, no second copy to drift).
 *
 * They were in ConvoyNotesPanel.kt beside the builders, which still use org.json and stay
 * app-side for now. These two carry no dependency at all: two plain declarations.
 *
 * Fred 15:36: "never object under this must be built. we cannot stop arbitrarily we need to
 * finish." The route tap and the detail panel's notes button both end here, so this is the
 * first piece of finishing them.
 */
package com.geeksville.mesh.convoy

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
