package com.grouptrack.shared.text

/** RXFIX-2026-11-09: Kotlin/Native's own findAll (no per-match copy on iOS). */
actual fun gtRxFindAll(regex: Regex, input: CharSequence): Sequence<GtRxMatch> =
    regex.findAll(input).map { GtRxMatch(it.value, it.groupValues, it.range) }
