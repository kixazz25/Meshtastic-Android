package com.geeksville.mesh.convoy

import com.grouptrack.shared.osm.GtOsmClassifier

/**
 * OsmTrailClassifier -- OSM access tags -> a trail type and a carto code (TRAILCLASS-2026-08-30).
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR of the certified shared GtOsmClassifier (2.8 step 15g, 57cd2a8a7: Droid 687,564 / 0 DIFF,
 * iPad 231,365 / 0 DIFF) -- whose body is 2.7e's, character for character. The public API is 2.7e's, unchanged. The 2.7e file (its
 * measured Utah ledger, the CARTO0 single-digit rule, the review note on untagged paths) is in git history (57cd2a8a7) and in the
 * TEST-ONLY OsmTrailClassifierCertOld.
 */
object OsmTrailClassifier {
    const val CARTO_MOTORIZED = GtOsmClassifier.CARTO_MOTORIZED
    const val CARTO_HIKING = GtOsmClassifier.CARTO_HIKING
    const val CARTO_HIKE_BIKE = GtOsmClassifier.CARTO_HIKE_BIKE
    const val CARTO_EQUESTRIAN = GtOsmClassifier.CARTO_EQUESTRIAN
    const val CARTO_BIKING = GtOsmClassifier.CARTO_BIKING
    const val OHV_DESIGNATED = GtOsmClassifier.OHV_DESIGNATED
    const val FOUR_WD_ONLY = GtOsmClassifier.FOUR_WD_ONLY
    const val UNCLASSIFIED_MOTORIZED = GtOsmClassifier.UNCLASSIFIED_MOTORIZED
    const val UNCLASSIFIED_UNKNOWN = GtOsmClassifier.UNCLASSIFIED_UNKNOWN
    const val HIKING_ONLY = GtOsmClassifier.HIKING_ONLY
    const val BIKING_ONLY = GtOsmClassifier.BIKING_ONLY
    const val HIKING_AND_BIKING = GtOsmClassifier.HIKING_AND_BIKING
    const val EQUESTRIAN = GtOsmClassifier.EQUESTRIAN
    const val NON_MOTORIZED = GtOsmClassifier.NON_MOTORIZED
    const val PRIVATE = GtOsmClassifier.PRIVATE
    const val PAVED_SURFACE_TYPE = GtOsmClassifier.PAVED_SURFACE_TYPE

    data class Result(val type: String, val carto: String?, val motorized: Boolean)

    fun classify(
        highway: String?, surface: String?, tracktype: String?, access: String?, motorVehicle: String?, vehicle: String?,
        fourWdOnly: String?, ohv: String?, atv: String?, motorcar: String?, foot: String?, bicycle: String?,
    ): Result = GtOsmClassifier.classify(highway, surface, tracktype, access, motorVehicle, vehicle, fourWdOnly, ohv, atv, motorcar, foot, bicycle)
        .let { Result(it.type, it.carto, it.motorized) }
}
