package com.geeksville.mesh.convoy

import android.content.Context
import org.json.JSONObject
import com.grouptrack.shared.ride.GtRideJson as Shared

/*
 * STEP14-2026-11-08 (2.8): ConvoyRideJsonWriter is now the Android face of the SHARED ride package builder
 * (com.grouptrack.shared.ride.GtRideJson -- a line-for-line port of this file's 2.7e body, RIDEJSON-2026-09-23). THE BUILD LIVES
 * THERE ONLY; 2.7e's code is in git history.
 *  - SpatialDbManager.init(context) stays here: it was build()'s first line (and so save()'s), so it runs just before the shared call.
 *  - Result.json is the org.json JSONObject 2.7e handed out, made from the shared JSON text (same keys, same order, same values as
 *    org.json reads them).
 *  - The stored FILE is written by the shared save() through GtRideEnv.packageText = org.json's toString(2) -- 2.7e's bytes.
 */
object ConvoyRideJsonWriter {

    init { GtRidesAndroid.ready() }

    const val KIND = Shared.KIND
    const val FORMAT_VERSION = Shared.FORMAT_VERSION
    const val COT_SA = Shared.COT_SA        // Nathan 09-22: ATAK situational-awareness multicast
    const val COT_CHAT = Shared.COT_CHAT    // Nathan 09-22: ATAK chat multicast

    /**
     * ⚠ OPEN (Fred): the narrative payload carries OSM feature names and classes. Until that is
     * decided it is NOT written. The recipe is cleared: every field is a value the rider chose.
     */
    const val INCLUDE_NARRATIVE = Shared.INCLUDE_NARRATIVE

    data class Result(val json: JSONObject, val missing: List<String>) {
        val complete: Boolean get() = missing.isEmpty()
    }

    fun build(context: Context, rideId: String): Result? {
        SpatialDbManager.init(context)
        return Shared.build(GtRidesAndroid.Files(context), rideId)?.let { r -> Result(JSONObject(r.text), r.missing) }
    }

    /** GroupTrack storage / rides / <rideId>.json -- one file per ride, on every device that holds it. */
    fun rideFile(context: Context, rideId: String): java.io.File =
        java.io.File(Shared.rideFile(GtRidesAndroid.Files(context), rideId))

    /** Builds the ride JSON and STORES it (atomic: .tmp, then rename). Returns what is missing, or null when nothing was written. */
    fun save(context: Context, rideId: String): List<String>? {
        SpatialDbManager.init(context)   // 2.7e: build()'s first line, reached through save()
        return Shared.save(GtRidesAndroid.Files(context), rideId)
    }

    /** One GPX: waypoints go after <metadata> (or the <gpx> tag), before the route — GPX order. */
    internal fun mergeWpts(routeGpx: String, wpts: String): String = Shared.mergeWpts(routeGpx, wpts)
}
