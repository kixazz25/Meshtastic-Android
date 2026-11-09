package com.grouptrack.shared.text

/**
 * RXFIX-2026-11-09 (Fred 09:52 "fix first"): every match of a pattern in a text, in order -- what Kotlin's Regex.findAll gives, made
 * LINEAR on Android. Shared code calls GtRx.findAll, never Regex.findAll, on file text.
 *
 * Why: Kotlin's findAll makes a NEW java.util.regex.Matcher for every next match. On a desktop JVM that is free; on ANDROID each new
 * Matcher copies the WHOLE text into native memory (ICU), so N matches in a text of size S cost N x S. Found 11-09 when the 15b cert
 * crawled for over an hour on TrailsAndPathways.kmz (a 40 MB KML, 17,147 placemarks: about 1.4 TB copied per conversion) -- the same
 * KML converts in 1.9 s on a JVM. The Droid log showed a "NativeAlloc" GC every second throughout. 2.7e has it too (a big KML/KMZ
 * through the import panel takes many minutes); 2.7e is not changed -- 2.8 replaces it.
 *
 * Same results: one Matcher run straight through with find() visits exactly the matches Kotlin's findAll does (next search from the
 * match end, one further after an empty match). Proved 11-09 on a JVM over the 45 15b test files (52 texts incl. each KMZ's KML),
 * every whole-text pattern of the 15b port: 2,276,091 matches, 0 differences (perf/Proof.java). The cert proves it on the Droid and iPad.
 *
 * On the iPad Kotlin's own Regex is used (Kotlin/Native's engine copies nothing per match).
 */
class GtRxMatch(val value: String, val groupValues: List<String>, val range: IntRange)

object GtRx {
    /** The matches of [regex] in [input], in order. groupValues as Kotlin gives them: "" for a group that did not take part. */
    fun findAll(regex: Regex, input: CharSequence): Sequence<GtRxMatch> = gtRxFindAll(regex, input)
}

expect fun gtRxFindAll(regex: Regex, input: CharSequence): Sequence<GtRxMatch>
