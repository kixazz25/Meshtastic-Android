package com.grouptrack.shared.osm

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmTrailClassifier (TRAILCLASS-2026-08-30), PORTED AS IS -- OSM access tags -> a trail type
 * and a carto code. Pure Kotlin in 2.7e already: the body below is 2.7e's, character for character (only the package and the name
 * changed). The measured Utah ledger, the CARTO0 single-digit rule (trailColor switches on charAt(0)), the two unknowns and the open
 * review note on untagged paths are documented in 2.7e's file and stand unchanged.
 */
object GtOsmClassifier {

    const val CARTO_MOTORIZED = "0 - Motorized Access"
    const val CARTO_HIKING = "1 - Hiking Only"
    const val CARTO_HIKE_BIKE = "2 - Hiking and Biking Allowed"
    const val CARTO_EQUESTRIAN = "3 - Equestrian"
    const val CARTO_BIKING = "5 - Biking Only"

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

    data class Result(
        val type: String,
        val carto: String?,
        val motorized: Boolean,
    )

    private fun motorizedResult(type: String) = Result(type, CARTO_MOTORIZED, true)

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

        if (access == "private" || access == "no" ||
            motorVehicle == "no" || vehicle == "no"
        ) {
            if (motorVehicle == "no" || vehicle == "no") {
                return when {
                    bicycle in YES && foot in YES ->
                        Result(HIKING_AND_BIKING, CARTO_HIKE_BIKE, false)
                    bicycle in YES -> Result(BIKING_ONLY, CARTO_BIKING, false)
                    foot in YES -> Result(HIKING_ONLY, CARTO_HIKING, false)
                    else -> Result(NON_MOTORIZED, CARTO_HIKING, false)
                }
            }
            return Result(PRIVATE, null, false)
        }

        if (ohv in YES || atv in YES) return motorizedResult(OHV_DESIGNATED)
        if (fourWdOnly == "yes") return motorizedResult(FOUR_WD_ONLY)
        if (motorVehicle in YES || motorcar in YES) {
            return motorizedResult(
                if (!tracktype.isNullOrBlank()) "motorized track $tracktype"
                else UNCLASSIFIED_MOTORIZED
            )
        }

        if (highway == "tertiary" || highway == "residential" ||
            highway == "service" || highway == "unclassified" ||
            highway == "road"
        ) {
            if (surface in RIDEABLE_SURFACE) return motorizedResult(UNCLASSIFIED_MOTORIZED)
            if (surface in PAVED_SURFACE) {
                return Result(PAVED_SURFACE_TYPE, null, false)
            }
            if (highway == "unclassified") {
                return Result(UNCLASSIFIED_UNKNOWN, null, true)
            }
            return Result(PAVED_SURFACE_TYPE, null, false)
        }

        if (highway == "track") {
            if (!tracktype.isNullOrBlank()) return motorizedResult("track $tracktype")
            if (surface in RIDEABLE_SURFACE) return motorizedResult(UNCLASSIFIED_MOTORIZED)
            return Result(UNCLASSIFIED_UNKNOWN, null, true)
        }

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

        return Result(UNCLASSIFIED_UNKNOWN, null, true)
    }

    const val PAVED_SURFACE_TYPE = "paved or restricted"
}
