package com.geeksville.mesh.convoy

/**
 * TrailClassifier — source values in, our four fields out. CLASSIFY4-2026-09-02.
 *
 * ⛔⛔ WHY THIS FILE EXISTS, AND IT IS NOT A HAPPY REASON. The four-field design
 * was settled on 08-31 and applied to Droid 2 BY A PYTHON SCRIPT
 * (classify4_2026-08-31_v1.py), whose own header called itself "THE
 * SPECIFICATION for Kotlin". It was never ported. So land_status, use_type and
 * carto_code_source existed on ONE DEVICE, in no schema, written by nothing --
 * and on 09-02 Droid 1 crashed on the first viewport query with "no such
 * column: land_status" because the filter referenced them.
 *
 * ⭐ THE DESIGN, restated because it is what the code must obey:
 *
 *   carto_code_source   the RAW value the source supplied, kept verbatim
 *   carto_code          OUR CATEGORY -- nothing about ownership, nothing about
 *                       whether it is motorized
 *   land_status         PUBLIC | PRIVATE
 *   use_type            MOTORIZED | NON-MOTORIZED
 *
 * ⛔ OWNERSHIP IS A SEPARATE FIELD, NOT A CATEGORY (Fred, 08-31). Folding it in
 * is what made step 8 overwrite identity -- 201 rows could no longer say what
 * they used to be. `R - Residential Roads` is therefore NOT produced here; a
 * private road keeps its category and answers PRIVATE on land_status.
 */
object TrailClassifier {

    const val MOTORIZED = "MOTORIZED"
    const val NON_MOTORIZED = "NON-MOTORIZED"

    /**
     * category -> use_type. ⭐ THE ONLY PLACE THE TWO ARE RELATED, and keeping
     * it that way is what lets the category vocabulary change without anyone
     * re-deciding what counts as rideable.
     *
     * ⚠ "shape only" IS MOTORIZED (Fred, 08-31): "shape needs to start in
     * motorized, then we can drill down to trails and zoom into sat images."
     * It is also what the router already does, so nothing regresses.
     * ⚠ And "unknown" likewise -- an unmapped value is more likely a road than
     * a footpath, and hiding it would lose ground rather than clutter.
     */
    private val USE_OF = mapOf(
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
    )

    fun useOf(category: String): String = USE_OF[category] ?: MOTORIZED

    /**
     * The category, from what the source said.
     *
     * @param srcVal  carto_code as the SOURCE wrote it
     * @param uses    designated_uses -- for OSM rows this is the classifier's
     *                own finer type, which beats carto_code where they differ
     *
     * ⚠ ORDER MATTERS. OSM's designated_uses is tested FIRST because it is
     * finer than the carto value; UGRC's numbered vocabulary second; agency
     * free text last.
     */
    fun categoryOf(srcVal: String?, uses: String?): String {
        val c = (srcVal ?: "").trim()
        val u = (uses ?: "").trim().lowercase()

        // ── OSM: the classifier's own type ──────────────────────────────
        if (u.startsWith("ohv") || u.startsWith("atv") || u.startsWith("4wd"))
            return "OHV"
        if (u.startsWith("motorized track") || u.startsWith("track"))
            return "track"
        if (u.startsWith("unclassified motorized")) return "forestry/access road"
        if (u.startsWith("hiking and biking")) return "hiking and biking"
        if (u.startsWith("hiking")) return "hiking"
        if (u.startsWith("biking")) return "biking"
        if (u.startsWith("equestrian")) return "equestrian"
        if (u.startsWith("non-motorized")) return "hiking and biking"
        // ⚠ NOTHING WAS SAID about this way. It is in on shape alone.
        if (u.startsWith("unclassified unknown")) return "shape only"

        // ── UGRC's numbered vocabulary ──────────────────────────────────
        // ⭐ 4 (road-concurrent) is MOTORIZED: the trail shares a road
        // corridor, so vehicles are on it.
        if (c.startsWith("4 -")) return "forestry/access road"
        if (c.startsWith("0 -")) return "forestry/access road"
        // ⭐ 3 (paved shared use) and 9 (link) FOLD INTO hiking-and-biking:
        // "shared use" means pedestrians and cyclists, NOT sharing with
        // vehicles. 13,493 of 13,740 are private -- urban paths.
        if (c.startsWith("2 -")) return "hiking and biking"
        if (c.startsWith("3 -")) return "hiking and biking"
        if (c.startsWith("9 -")) return "hiking and biking"
        if (c.startsWith("1 -")) return "hiking"
        if (c.startsWith("5 -")) return "biking"
        if (c.startsWith("6 -")) return "equestrian"
        if (c.startsWith("7 -") || c.startsWith("8 -")) return "steps/bridge"

        // ── agency free text ────────────────────────────────────────────
        val up = c.uppercase()
        if (up == "TERRA" || up == "STANDARD TERRA TRAIL" || up == "TERRA TRAIL")
            return "shape only"
        if (up == "SNOW" || up == "SNOW TRAIL") return "shape only"
        if (up.startsWith("TRANSPORTATION SYSTEM")) return "shape only"
        if (up == "WASH" || up == "ROAD" || up == "CANAL") return "shape only"

        // ⚠ Ours from a PREVIOUS run of the old step 8, whose identity was
        // destroyed when ownership was folded into the category. Nothing can
        // recover what it used to be -- which is exactly why ownership is its
        // own field now.
        if (c.startsWith("R -")) return "shape only"

        if (c.isEmpty() || c == "null") return "shape only"
        return "unknown"
    }
}
