package com.geeksville.mesh.convoy

/**
 * BODIES-2026-11-19: moved VERBATIM out of GeofabrikCatalog.kt, keeping its package so every
 * reference resolves unchanged (LandAreas.kt, SearchModule.kt, the catalogue itself).
 *
 * It is a pure data class -- name, slug, four bbox doubles, containsPoint, intersectsBbox -- so it
 * is shared code. Only GeofabrikCatalog.load(context) is platform-bound (it reads the asset), and
 * that stays in app/. geofabrik_states.json is already one of the 13 bundled iOS assets.
 */
data class GeofabrikState(
    val name: String,
    val slug: String,
    val bboxWest: Double,
    val bboxSouth: Double,
    val bboxEast: Double,
    val bboxNorth: Double,
    val gpkgUrl: String,
    // PBFURL-2026-08-30: REQUIRED, not optional. The PBF carries the access
    // tags Geofabrik strips from the GeoPackage, so an import without it
    // classifies by shape alone -- silently. A row that cannot say where its
    // PBF is should fail at catalogue load, not at download time on a rider's
    // phone in a state nobody tested.
    val pbfUrl: String,
    // FETCHFIRST-2026-11-02: the state's PAD-US zip (USGS). REQUIRED in the constructor (CODE RULE 1); "" = this row has no
    //  PAD-US file -- then no land row is fetched for it. landCode is the two-letter code; California's two halves share "CA".
    val landCode: String,
    val landUrl: String,
    val description: String? = null,
    val parentState: String? = null,
) {
    /** True if the given point falls inside this state's bbox. */
    fun containsPoint(lat: Double, lon: Double): Boolean =
        lat in bboxSouth..bboxNorth && lon in bboxWest..bboxEast

    /** True if the given bbox intersects this state's bbox. */
    fun intersectsBbox(south: Double, west: Double, north: Double, east: Double): Boolean =
        !(east < bboxWest || west > bboxEast || north < bboxSouth || south > bboxNorth)

    /** Display name for the state picker — "Utah", "California North", etc. */
    val displayName: String
        get() = name
}
