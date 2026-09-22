package com.geeksville.mesh.convoy

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * RIDECREATE-2026-09-22 — local rides, first pass.
 *
 * A ride is a package of map, route and waypoint data with a leader and a date.
 * It is useful with NO network at all -- rides.config_id stays null until the
 * transport work lands (CODE RULE 1: a ride legitimately has no network, so the
 * reference is optional by design, not as a shortcut).
 *
 * Nothing here talks to AWS. Rides sit locally; the sync queue is 3.0 work.
 */
data class RouteSummary(
    val routeId: String,
    val name: String,
    val geomHash: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

data class RideRow(
    val rideId: String,
    val rideName: String,
    val rideDate: String,
    val startTime: String,
    val routeId: String,
    val routeName: String,
    val organizerName: String,
    val createdAt: String
)

object ConvoyRideStore {

    private const val TAG = "ConvoyRideStore"

    private fun nowUtc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /** Every stored route, newest first -- what the picker lists. */
    fun listRoutes(limit: Int = 200): List<RouteSummary> {
        val db = SpatialDbManager.getSpatialDb() ?: return emptyList()
        val out = mutableListOf<RouteSummary>()
        try {
            db.rawQuery(
                "SELECT route_id, name, geom_hash, min_lat, max_lat, min_lon, max_lon " +
                    "FROM routes ORDER BY updated_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        RouteSummary(
                            routeId = c.getString(0) ?: continue,
                            name = c.getString(1) ?: "Not Named",
                            geomHash = c.getString(2) ?: "",
                            minLat = c.getDouble(3), maxLat = c.getDouble(4),
                            minLon = c.getDouble(5), maxLon = c.getDouble(6)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listRoutes failed: ${e.message}")
        }
        return out
    }

    /** The route's stored geometry (WKT LINESTRING) -- the vignette draws this. */
    fun routeGeometry(routeId: String): String? {
        val db = SpatialDbManager.getSpatialDb() ?: return null
        return try {
            db.rawQuery("SELECT geometry FROM routes WHERE route_id = ? LIMIT 1", arrayOf(routeId))
                .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.w(TAG, "routeGeometry failed: ${e.message}"); null
        }
    }

    /** Rename a route in place -- the name travels, so it is editable where it is chosen. */
    fun renameRoute(routeId: String, name: String): Boolean {
        val db = SpatialDbManager.getSpatialDb() ?: return false
        return try {
            db.execSQL(
                "UPDATE routes SET name = ?, updated_at = ? WHERE route_id = ?",
                arrayOf(name.trim().ifBlank { "Not Named" }, nowUtc(), routeId)
            ); true
        } catch (e: Exception) {
            Log.w(TAG, "renameRoute failed: ${e.message}"); false
        }
    }

    /**
     * Writes the ride. NOTHING is written until this is called -- an abandoned
     * screen leaves no row (no ghosts). The leader is always this tablet's rider.
     */
    fun saveRide(
        rideName: String,
        rideDate: String,
        startTime: String,
        description: String,
        zipCode: String,
        isPublic: Boolean,
        routeId: String
    ): String? {
        val db = SpatialDbManager.getExtensionDb() ?: return null
        val me = ConvoyProfileStore.load() ?: run {
            Log.w(TAG, "saveRide refused: no rider profile"); return null
        }
        val leaderName = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }
            .joinToString(" ").ifBlank { me.callsign }
        val now = nowUtc()
        val id = UUID.randomUUID().toString()
        return try {
            db.execSQL(
                "INSERT INTO rides (ride_id, organizer_id, organizer_name, route_id, " +
                    "ride_name, ride_date, start_time, description, zip_code, is_public, " +
                    "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(
                    id, me.userId, leaderName, routeId,
                    rideName.trim(), rideDate.trim(), startTime.trim(), description.trim(),
                    zipCode.trim(), if (isPublic) 1 else 0, now, now
                )
            )
            Log.i(TAG, "RIDECREATE-2026-09-22: ride saved $id name=$rideName route=$routeId")
            id
        } catch (e: Exception) {
            Log.e(TAG, "saveRide failed: ${e.message}"); null
        }
    }

    /** Rides on this tablet, newest first. */
    fun listRides(limit: Int = 100): List<RideRow> {
        val db = SpatialDbManager.getExtensionDb() ?: return emptyList()
        val out = mutableListOf<RideRow>()
        try {
            db.rawQuery(
                "SELECT ride_id, ride_name, ride_date, COALESCE(start_time,''), " +
                    "COALESCE(route_id,''), COALESCE(organizer_name,''), created_at " +
                    "FROM rides ORDER BY created_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        RideRow(
                            rideId = c.getString(0) ?: continue,
                            rideName = c.getString(1) ?: "",
                            rideDate = c.getString(2) ?: "",
                            startTime = c.getString(3) ?: "",
                            routeId = c.getString(4) ?: "",
                            routeName = "",
                            organizerName = c.getString(5) ?: "",
                            createdAt = c.getString(6) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listRides failed: ${e.message}")
        }
        return out
    }

    /** WKT LINESTRING -> points, for the vignette. Returns lon/lat pairs. */
    fun parseWktLine(wkt: String): List<Pair<Double, Double>> {
        val inner = wkt.substringAfter('(', "").substringBeforeLast(')', "")
        if (inner.isBlank()) return emptyList()
        return inner.split(',').mapNotNull { p ->
            val t = p.trim().split(' ').filter { it.isNotBlank() }
            if (t.size < 2) null else {
                val x = t[0].toDoubleOrNull(); val y = t[1].toDoubleOrNull()
                if (x == null || y == null) null else Pair(x, y)
            }
        }
    }
}
