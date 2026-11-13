package com.grouptrack.shared.help
// STEP17G2G-2026-11-13: the ONE fixture both sides of G2g read

/**
 * ⭐ ONE FIXTURE, TWO PATHS: the Droid hands this text to 2.7e's real org.json, the shared side to
 * GtOrgJson. Everything either side can decide comes from here.
 */
object GtHelpFixture {

    /** The real shape the manual rebuild writes. */
    const val GOOD = """{"links":{"map":"manual#map","map.dl_area":"manual#download-area","route":"manual#route","route.notes":"manual#route-notes","rides.carts":"manual#carts"}}"""

    /** ⚠ No "links" key at all -- getJSONObject throws, runCatching swallows, the table is empty. */
    const val NO_LINKS = """{"other":{"map":"manual#map"}}"""

    /** Clearly malformed on BOTH parsers -- not a leniency edge, so the two must agree. */
    const val MALFORMED = """{"links":{"map":"""

    /** An empty but valid table. */
    const val EMPTY = """{"links":{}}"""

    /**
     * ⚠ A NON-STRING VALUE. 2.7e calls o.getString(k); the shared side calls GtOrgJson.valueText.
     * Scripted deliberately so the certification ANSWERS whether they agree, rather than me
     * assuming the asset only ever holds strings.
     */
    const val NUMBER_VALUE = """{"links":{"map":"manual#map","route":7}}"""

    /** The ids every scenario is probed with: exact hit, module fallback, no match, an empty id. */
    val PROBE_IDS = listOf("map", "map.dl_area", "map.nope", "route", "route.notes", "route.nope", "nothere", "nothere.sub", "")

    val CASES = listOf(
        "good" to GOOD,
        "nolinks" to NO_LINKS,
        "malformed" to MALFORMED,
        "empty" to EMPTY,
        "number" to NUMBER_VALUE,
    )
}
