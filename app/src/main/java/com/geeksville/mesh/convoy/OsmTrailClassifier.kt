package com.geeksville.mesh.convoy

/**
 * OsmTrailClassifier — turns OSM access tags into a trail type and a carto
 * code. TRAILCLASS-2026-08-30.
 *
 * THE PROBLEM IT SOLVES, in osm_layers.json's own words: *"the access tags
 * that would answer this properly (ohv, motor_vehicle, access) are NOT in
 * Geofabrik's GeoPackage at all — the roads layer has no access field of any
 * kind — so fclass answers by SHAPE, not by permission."* That produces both
 * faults seen on the ground: community roads arriving as trails (`unclassified`
 * is OSM's tag for a minor public road, so a paved subdivision lane matches
 * exactly), and rideable dirt roads not arriving at all (tagged residential,
 * service or tertiary). [OsmPbfTagReader] supplies the missing tags; this
 * decides what they mean.
 *
 * ⭐⭐ MEASURED, NOT PROPOSED. These exact rules were run against Utah on
 * 2026-08-29/30 — the GeoPackage joined to the PBF on `osm_id`, 424,951 of
 * 699,537 rows matched:
 *
 *   KEPT          77,340   in today's filter, rules agree
 *   ADDED         48,491   rejected today, rideable under the rules
 *   DROPPED        9,739   in today's filter, excluded now
 *   TOTAL IN     125,831   (today: 87,079)
 *   CLASSIFIED    83,916   67% — a type from real tags (today: 0)
 *   UNCLASSIFIED  41,915   33% — in on shape alone
 *
 * Every drop was defensible: 5,837 legally shut (`access=private` 5,451,
 * `access=no` 386) and 3,902 paved. No rideable surface was dropped at all.
 * Named fixes: Main Street, Center Street, 6000 West, Thermo Road and
 * Manderfield Road left; Mountain Spring Road (way 10113390 —
 * `highway=tertiary`, `surface=dirt`, ridden, never in the table) arrived.
 *
 * ⭐ NOTHING IS EXCLUDED FROM THE TABLE. Fred 08-30: leave all trails in the
 * file and select what to view and route on. So [classify] returns a type for
 * every row — including `NON_MOTORIZED` kinds and `UNCLASSIFIED_UNKNOWN` — and
 * the legend filter decides what is drawn and what the router may use. A wrong
 * label is visible and correctable; a missing trail is a coverage gap nothing
 * downstream can work around.
 *
 * ⚠ TWO UNKNOWNS, AND THEY ARE NOT THE SAME. `unclassified motorized` is
 * motorized CONFIRMED, kind unknown — ground a rider can trust. `unclassified
 * unknown` is nothing said anything — in on shape, exactly as today. The
 * distinction matters because 87% of tracks are unnamed: a rider reading
 * "37 of 77 miles is unnamed track" cannot otherwise tell a maintained forest
 * road from a hiking path someone tagged loosely.
 */
object OsmTrailClassifier {

    /**
     * ⚠⚠ THE CARTO CODE IS KEYED ON ITS FIRST CHARACTER, NOT THE STRING.
     * `trailColor()` and `trailWeight()` in both convoy_map.html:474 and
     * grouptrack_map.html:41 switch on `cartoCode.charAt(0)`. So the code MUST
     * STAY A SINGLE DIGIT — a two-character code would silently match on its
     * first digit only.
     *
     * ⭐ SETTLED 2026-08-30: the digit is ZERO. Every digit 1-9 was already
     * taken in real data -- 1 Hiking Only, 2 Hiking and Biking, 3 Paved Shared
     * Use, 4 Road-concurrent, 5 Biking Only, 6 Equestrian Primary, 7 Steps,
     * 8 Bridge/Tunnel, 9 Link. ⚠ And 3, 6, 7, 8, 9 have NO colour case in
     * either map file, so they draw in the cyan fallback today.
     */
    // CARTO0-2026-08-30: ZERO, not 6. Measured against Droid 2's real
    // trail_properties: every digit 1-9 is already in use, and 6 is
    // "6 - Equestrian Primary" (72 rows). A collision would have been SILENT --
    // trailColor switches on charAt(0), so motorized trails would simply have
    // drawn as equestrian. 0 is free. Fred's call.
    const val CARTO_MOTORIZED = "0 - Motorized Access"
    const val CARTO_HIKING = "1 - Hiking Only"
    const val CARTO_HIKE_BIKE = "2 - Hiking and Biking Allowed"
    const val CARTO_EQUESTRIAN = "3 - Equestrian"
    const val CARTO_BIKING = "5 - Biking Only"

    // ── the vocabulary. Kept as strings because it is what ships in the
    // ── skinny, crosses into trail_properties, and drives the legend.
    const val OHV_DESIGNATED = "OHV designated"
    const val FOUR_WD_ONLY = "4wd only"
    const val UNCLASSIFIED_MOTORIZED = "unclassified motorized"
    const val UNCLASSIFIED_UNKNOWN = "unclassified unknown"
    const val HIKING_ONLY = "hiking only"
    const val BIKING_ONLY = "biking only"
    const val HIKING_AND_BIKING = "hiking and biking"
    const val EQUESTRIAN = "equestrian"
    const val NON_MOTORIZED = "non-motorized"
    const val PRIVATE = "private"

    private val RIDEABLE_SURFACE = setOf(
        "dirt", "ground", "gravel", "unpaved", "compacted", "fine_gravel",
        "sand", "earth", "grass", "rock", "mud", "pebblestone", "woodchips",
    )
    private val PAVED_SURFACE = setOf(
        "asphalt", "concrete", "paved", "concrete:plates", "paving_stones",
        "sett", "cobblestone",
    )
    private val YES = setOf("yes", "designated")

    /**
     * @param type the vocabulary value, never null — every row gets one.
     * @param carto the USGS code, or null where nothing said what it is.
     * @param motorized true when a rider may take a machine there. This is the
     *        value the route builder filters on; the legend filters on [type].
     */
    data class Result(
        val type: String,
        val carto: String?,
        val motorized: Boolean,
    )

    private fun motorizedResult(type: String) = Result(type, CARTO_MOTORIZED, true)

    /**
     * Classify one way from its tags. Tag names are as OSM writes them, except
     * `4wd_only`, which [OsmPbfTagReader] stores as `four_wd_only` because a
     * SQLite identifier cannot start with a digit.
     *
     * ORDER IS THE DESIGN. Exclusions are settled first because they are the
     * confident part; then motorized confirmation; then surface for the road
     * classes; then tracks on shape; then paths as non-motorized unless a tag
     * says otherwise.
     */
    fun classify(
        highway: String?,
        surface: String?,
        tracktype: String?,
        access: String?,
        motorVehicle: String?,
        vehicle: String?,
        fourWdOnly: String?,
        ohv: String?,
        atv: String?,
        motorcar: String?,
        foot: String?,
        bicycle: String?,
    ): Result {

        // ── EXCLUSIONS FIRST, and they are the confident part ────────────
        // access=private is on 34,511 Utah ways — the single best-populated
        // signal in the extract.
        if (access == "private" || access == "no" ||
            motorVehicle == "no" || vehicle == "no"
        ) {
            // ⭐ motor_vehicle=no is NOT "unclassified" — it is a
            // CLASSIFICATION, and it is the one that stops the planner routing
            // a side-by-side down a hiking trail.
            if (motorVehicle == "no" || vehicle == "no") {
                return when {
                    bicycle in YES && foot in YES ->
                        Result(HIKING_AND_BIKING, CARTO_HIKE_BIKE, false)
                    bicycle in YES -> Result(BIKING_ONLY, CARTO_BIKING, false)
                    foot in YES -> Result(HIKING_ONLY, CARTO_HIKING, false)
                    else -> Result(NON_MOTORIZED, CARTO_HIKING, false)
                }
            }
            // ⚠ Legally shut. Kept in the table and labelled, not dropped —
            // a rider can see why it is not offered, and a later import can
            // correct it. It is never routable.
            return Result(PRIVATE, null, false)
        }

        // ── MOTORIZED, CONFIRMED ─────────────────────────────────────────
        if (ohv in YES || atv in YES) return motorizedResult(OHV_DESIGNATED)
        if (fourWdOnly == "yes") return motorizedResult(FOUR_WD_ONLY)
        if (motorVehicle in YES || motorcar in YES) {
            return motorizedResult(
                if (!tracktype.isNullOrBlank()) "motorized track $tracktype"
                else UNCLASSIFIED_MOTORIZED
            )
        }

        // ── SURFACE DECIDES THE ROADS ────────────────────────────────────
        // ⭐ This is Mountain Spring Road: tertiary + dirt is rideable,
        // tertiary + asphalt is a highway. The tag exists on 80% of
        // tertiaries — the best coverage in the survey.
        if (highway == "tertiary" || highway == "residential" ||
            highway == "service" || highway == "unclassified" ||
            highway == "road"
        ) {
            if (surface in RIDEABLE_SURFACE) return motorizedResult(UNCLASSIFIED_MOTORIZED)
            if (surface in PAVED_SURFACE) {
                // ⛔ The subdivision problem, solved. Kept and labelled so the
                // filter can hide it; never routable.
                return Result(PAVED_SURFACE_TYPE, null, false)
            }
            if (highway == "unclassified") {
                // As today, in on shape alone.
                return Result(UNCLASSIFIED_UNKNOWN, null, true)
            }
            // A residential street with no surface tag.
            return Result(PAVED_SURFACE_TYPE, null, false)
        }

        // ── TRACKS: in on shape, as today, graded where OSM says so ──────
        if (highway == "track") {
            if (!tracktype.isNullOrBlank()) return motorizedResult("track $tracktype")
            if (surface in RIDEABLE_SURFACE) return motorizedResult(UNCLASSIFIED_MOTORIZED)
            return Result(UNCLASSIFIED_UNKNOWN, null, true)
        }

        // ── PATHS, CYCLEWAYS, BRIDLEWAYS ─────────────────────────────────
        // Non-motorized unless something says otherwise.
        // ⚠ NOTE FOR REVIEW (raised 08-30, not yet settled): the fall-through
        // below claims HIKING_ONLY for an untagged path, while an untagged
        // TRACK falls through to UNCLASSIFIED_UNKNOWN — honest about absence.
        // `highway=path` is heavily used in the western US for exactly the
        // two-track a side-by-side rides, so this default removes those from
        // routing on the strength of nothing. Left as the prototype had it so
        // the device numbers stay comparable; revisit with the A/B result.
        if (highway == "path" || highway == "cycleway" || highway == "bridleway") {
            return when {
                bicycle in YES && foot in YES ->
                    Result(HIKING_AND_BIKING, CARTO_HIKE_BIKE, false)
                highway == "cycleway" || bicycle in YES ->
                    Result(BIKING_ONLY, CARTO_BIKING, false)
                highway == "bridleway" -> Result(EQUESTRIAN, CARTO_EQUESTRIAN, false)
                else -> Result(HIKING_ONLY, CARTO_HIKING, false)
            }
        }

        // Anything else in the widened net that reached here said nothing.
        return Result(UNCLASSIFIED_UNKNOWN, null, true)
    }

    /** Paved or otherwise unrideable, kept in the table and filtered out. */
    const val PAVED_SURFACE_TYPE = "paved or restricted"
}
