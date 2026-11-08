package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtAppDb
import com.grouptrack.core.OwnerType
import com.grouptrack.shared.ride.GtRideEnv
import com.grouptrack.shared.ride.RideFiles
import com.grouptrack.shared.ride.RideMe
import com.grouptrack.shared.ride.RideNet
import com.grouptrack.shared.ride.RideNetOwner
import com.grouptrack.shared.ride.RideNetworks
import com.grouptrack.shared.ride.RideSpatial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import com.grouptrack.shared.ride.GtRideStore as Shared

/*
 * STEP14-2026-11-08 (2.8): ConvoyRideStore is now the Android face of the SHARED ride model (grouptrack-shared commonMain,
 * com.grouptrack.shared.ride.GtRideStore -- a line-for-line port of this file's 2.7e body, RIDECREATE-2026-09-22 onward).
 * THE RIDE STORE LIVES THERE ONLY. 2.7e's own code is in git history (the certification runs it from ConvoyRideStoreOld.kt).
 * Nothing here decides anything: each function hands over and converts back.
 *
 * ⭐ CALLERS COMPILE UNCHANGED. RouteSummary and RideRow are top-level, so they are typealiases of the shared classes (same fields,
 *   same constructor). RideChoice / CheckIn / RideSurveyInput / EditRide are nested in this object and Kotlin has no typealias inside
 *   an object -- so they are declared here, field for field as 2.7e had them, and converted at the door. ⚠ A field added to a shared
 *   type must be added here too (the converters below are the only place).
 *
 * ⭐ THE PLATFORM'S PIECES (GtRideEnv) are set once, in GtRidesAndroid's initialiser, which every ride wrapper touches first
 *   (ConvoyRideStore, ConvoyRideJsonWriter, RideApplySource) -- before any call can reach the shared code.
 */

typealias RouteSummary = com.grouptrack.shared.ride.RouteSummary
typealias RideRow = com.grouptrack.shared.ride.RideRow

// STEP14E-2026-11-08: GtRidesAndroid (the platform pieces) is in its own file, GtRidesAndroid.kt (committed with step 14c).

object ConvoyRideStore {

    init { GtRidesAndroid.ready() }

    private fun files(context: Context) = GtRidesAndroid.Files(context)

    /** Every stored route, newest first -- what the picker lists. */
    // CODE RULE 1 (2.7e's default): the picker omits [limit] (200).
    fun listRoutes(limit: Int = 200): List<RouteSummary> = Shared.listRoutes(limit)

    /** The route's stored geometry (WKT LINESTRING) -- the vignette draws this. */
    fun routeGeometry(routeId: String): String? = Shared.routeGeometry(routeId)

    /** Rename a route in place -- the name travels, so it is editable where it is chosen. */
    fun renameRoute(routeId: String, name: String): Boolean = Shared.renameRoute(routeId, name)

    /** Writes the ride -- see GtRideStore.saveRide. */
    fun saveRide(
        rideName: String,
        rideDate: String,
        startTime: String,
        description: String,
        zipCode: String,
        isPublic: Boolean,
        // FORMLAYOUT-2026-09-27: the network SHOWN on the form. REQUIRED, no default; CODE RULE 1: null is a real
        // choice -- the organizer's own network; a value is the ride-only network generated when the rider toggled.
        rideNetworkId: String?,
        routeId: String
    ): String? = Shared.saveRide(rideName, rideDate, startTime, description, zipCode, isPublic, rideNetworkId, routeId)

    // ---- CHECKIN-2026-09-27 (Fred): the pre-ride check-in and the end of the ride (types: 2.7e's, field for field) ----
    data class RideChoice(val rideId: String, val name: String, val date: String, val startTime: String,
                          val organizerName: String, val organizerId: String, val isPublic: Boolean) {
        internal fun shared() = Shared.RideChoice(rideId, name, date, startTime, organizerName, organizerId, isPublic)
    }

    /** The check-in. CODE RULE 1: rideId null = "No scheduled ride" (a normal recording; no survey, no sharing). */
    data class CheckIn(val rideId: String?, val rideName: String?, val isPublic: Boolean, val callsign: String, val role: String)

    /** What the end-of-ride form collected on a PUBLIC ride. shareTrack is the rider's own required choice. */
    data class RideSurveyInput(val rideId: String, val rating: Int, val difficulty: String, val recommend: Boolean,
                               val notes: String, val shareTrack: Boolean) {
        internal fun shared() = Shared.RideSurveyInput(rideId, rating, difficulty, recommend, notes, shareTrack)
    }

    /** EDITRIDE-2026-09-27: what the edit form shows. unique = the ride has its own network (config_id).
     *  CODE RULE 1 (2.7e's): configId null = the ride has no config_id. */
    data class EditRide(val name: String, val date: String, val startTime: String, val description: String,
                        val isPublic: Boolean, val routeId: String, val unique: Boolean, val configId: String?)

    /** CHECKINRIDES-2026-09-28: every ride not yet removed, newest first -- see GtRideStore.openRecentRides. */
    fun openRecentRides(): List<RideChoice> = Shared.openRecentRides().map { r ->
        RideChoice(r.rideId, r.name, r.date, r.startTime, r.organizerName, r.organizerId, r.isPublic)
    }

    /** Checks in. CODE RULE 1 (2.7e's): ride null = "No scheduled ride". Null only on failure. */
    fun checkIn(ride: RideChoice?, callsign: String, role: String): CheckIn? =
        Shared.checkIn(ride?.shared(), callsign, role)?.let { c -> CheckIn(c.rideId, c.rideName, c.isPublic, c.callsign, c.role) }

    /** v2 (Fred): the survey belongs to the RIDE -- see GtRideStore.saveSurvey. */
    fun saveSurvey(trackId: String, s: RideSurveyInput): Boolean = Shared.saveSurvey(trackId, s.shared())

    /** For the ride form: "Fred K · GroupAB12CD". CODE RULE 1: null is a real state -- no network yet. */
    fun myNetworkName(): String? = Shared.myNetworkName()

    /** RIDENETWORDS2-2026-09-27. CODE RULE 1: null only when there is no rider profile or the network could not be made. */
    fun ensureMyNetworkName(): String? = Shared.ensureMyNetworkName()

    /** FORMLAYOUT-2026-09-27: a ride-only network. Null only on failure. The shared code hands back the RideNet this file made
     *  (GtRidesAndroid.Net -- the only RideNet on Android), so its NetworkConfig is returned as 2.7e returned it. */
    fun createRideNetwork(): com.grouptrack.core.NetworkConfig? = (Shared.createRideNetwork() as GtRidesAndroid.Net?)?.cfg

    /** True once this rider has created a ride (users.is_organizer). */
    fun isOrganizer(): Boolean = Shared.isOrganizer()

    /** RIDESTATUS-2026-09-30: marks every OPEN ride past its expiry as 'expired' (nothing is deleted). */
    fun expireRides() = Shared.expireRides()

    /** RIDEHEAL-2026-09-26: reconciles the ride FILES with the rides TABLE -- see GtRideStore.healFromFiles. */
    fun healFromFiles(context: Context) = Shared.healFromFiles(files(context))

    /** RIDEEXPIRE-2026-09-26: removes a ride COMPLETELY -- see GtRideStore.deleteRide. */
    fun deleteRide(context: Context, rideId: String, reason: String): Boolean = Shared.deleteRide(files(context), rideId, reason)

    /** EDITRIDE-2026-09-27: one ride, for the edit form. Null only if it is not on this tablet. */
    fun rideForEdit(rideId: String): EditRide? = Shared.rideForEdit(rideId)?.let { e ->
        EditRide(e.name, e.date, e.startTime, e.description, e.isPublic, e.routeId, e.unique, e.configId)
    }

    /** In progress = distributed_at empty. DERIVED from the column, never a second flag. */
    fun isDistributed(rideId: String): Boolean = Shared.isDistributed(rideId)

    /** Changes an IN-PROGRESS ride. Refused once distributed (then only the date may change). */
    fun updateRide(
        rideId: String, rideName: String, rideDate: String, startTime: String,
        description: String, zipCode: String, isPublic: Boolean, routeId: String
    ): Boolean = Shared.updateRide(rideId, rideName, rideDate, startTime, description, zipCode, isPublic, routeId)

    /** The ONLY change allowed after distribution. Moves the expiry with it. */
    fun updateRideDate(rideId: String, rideDate: String): Boolean = Shared.updateRideDate(rideId, rideDate)

    /** Sending is the lock. Called when the ride is first distributed; later calls change nothing. */
    fun markDistributed(rideId: String): Boolean = Shared.markDistributed(rideId)

    /** Replaces the ride's waypoint list, in order. IN PROGRESS only. */
    fun setRideWaypoints(rideId: String, waypointIds: List<String>): Boolean = Shared.setRideWaypoints(rideId, waypointIds)

    /** Rides on this tablet, newest first. CODE RULE 1 (2.7e's default): no caller passes [limit]. */
    fun listRides(limit: Int = 100): List<RideRow> = Shared.listRides(limit)

    /** WKT LINESTRING -> points, for the vignette. Returns lon/lat pairs. */
    fun parseWktLine(wkt: String): List<Pair<Double, Double>> = Shared.parseWktLine(wkt)
}
