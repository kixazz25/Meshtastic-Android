package com.grouptrack.shared.platform

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.ride.RideSpatial
import com.grouptrack.shared.spatial.GtRead

/**
 * RIDESPATIAL-2026-11-16: the iPad's RideSpatial. All five members are GtRead, so there is no iOS-specific
 * behaviour here at all -- the SQL, the GPX text (GtGpx, "2.7e byte for byte") and the org.json serialisation
 * (GtOrgJson) are shared Kotlin that the Droid runs too.
 *
 * ⚠ A CONNECTION IS NOT THREAD-SAFE, and GtRead says so: "the platform serialises calls (Android: the lock in
 * SpatialDbManager's shared holder)". This class adds NO lock, so whatever constructs it owns that guarantee.
 * On the Droid the holder provides it; the iOS caller must either confine these calls to one queue or wrap them.
 * ⛔ Not a detail to discover later: two threads stepping one SQLiteConnection corrupts the read, and the symptom
 * is a wrong row rather than a crash.
 *
 * @param spatial grouptrack_spatial.db
 * @param data grouptrack_data.db -- CODE RULE 1: nullable exactly as 2.7e's `extensionDb ?: return`. Without it
 *   route_notes cannot be read, so routeRecipe / routeNotes answer null and routeGpx writes no extensions block,
 *   which is 2.7e's behaviour when the extension database is absent, not a degraded iOS path.
 */
class IosRideSpatial(spatial: SQLiteConnection, data: SQLiteConnection?) : RideSpatial {

    private val read = GtRead(spatial, data)

    override fun routeGpx(routeId: String): Pair<String?, String?>? =
        read.routeGpx(routeId)?.let { Pair(it.first, it.second) }

    override fun routeGeomHash(routeId: String): String? = read.routeGeomHash(routeId)

    override fun routeRecipe(routeId: String): String? = read.routeRecipeText(routeId)

    override fun waypointGpx(waypointId: String): String? = read.waypointGpx(waypointId)?.second

    override fun routeNotes(routeId: String): String? = read.routeNotesText(routeId)
}
