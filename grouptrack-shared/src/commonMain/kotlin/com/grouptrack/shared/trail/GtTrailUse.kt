package com.grouptrack.shared.trail

/**
 * TRAILUSE-2026-11-16: the category -> use table, as DATA, shared.
 *
 * Ported VERBATIM from app/convoy/TrailClassifier.kt's USE_OF map (11 entries) so the iPad answers
 * GtTrailClass.useOf identically to the Droid. Android's TrailClassifier is DELIBERATELY NOT CHANGED
 * yet: this is additive, the harness reads both tables side by side and proves they agree, and only
 * then is switching Android a one-line patch with a passing harness already behind it. One change at
 * a time (Fred 11-10 17:51).
 *
 * Fred 11-16 10:01: "additive emulate an existing service. it may be new to ios but the underlying
 *   service using the same method should return the same results on both platforms."
 * That is exactly what this is, and because the table is data rather than logic the comparison is
 * strict: 11 known keys plus an unknown key, byte for byte, nothing marked EXPECTED.
 *
 * ⚠ THE DEFAULT IS MOTORIZED, not an error. TrailClassifier.useOf is `USE_OF[category] ?: MOTORIZED`,
 * so an unrecognised category reads MOTORIZED. Copied as-is; changing it is a product decision.
 */
object GtTrailUse {

    const val MOTORIZED = "MOTORIZED"
    const val NON_MOTORIZED = "NON-MOTORIZED"

    /**
     * ⛔ VERBATIM from TrailClassifier.USE_OF. Do not reorder, add to, or "tidy" this map without
     * changing the Android table in the same commit -- the harness compares the two and will fail,
     * which is the point.
     *
     * "rider" is ground recorded by a rider where no published trail existed (RIDERTRAILS-2026-09-07).
     * MOTORIZED is not an assumption there the way it is for "shape only" and "unknown": a UTV was
     * driven along it.
     */
    val USE_OF: Map<String, String> = mapOf(
        "OHV" to MOTORIZED,
        "track" to MOTORIZED,
        "forestry/access road" to MOTORIZED,
        "shape only" to MOTORIZED,
        "hiking and biking" to NON_MOTORIZED,
        "hiking" to NON_MOTORIZED,
        "biking" to NON_MOTORIZED,
        "equestrian" to NON_MOTORIZED,
        "steps/bridge" to NON_MOTORIZED,
        "unknown" to MOTORIZED,
        "rider" to MOTORIZED,
    )

    /** TrailClassifier.useOf(category), character for character. */
    fun useOf(category: String): String = USE_OF[category] ?: MOTORIZED

    /** CATEGORYALWAYS-2026-10-10: every category we write. */
    val CATEGORIES: Set<String> get() = USE_OF.keys
}
