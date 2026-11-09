package com.grouptrack.shared.text

/** RXFIX-2026-11-09: ONE java.util.regex.Matcher, find() to the end -- the text is copied into native memory once, not per match. */
actual fun gtRxFindAll(regex: Regex, input: CharSequence): Sequence<GtRxMatch> = sequence {
    val m = regex.toPattern().matcher(input)
    while (m.find()) yield(GtRxMatch(m.group(), (0..m.groupCount()).map { m.group(it) ?: "" }, m.start() until m.end()))
}
