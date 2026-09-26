package com.geeksville.mesh.convoy

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import com.grouptrack.core.OwnerType
import java.time.LocalDate

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
        // RIDECFG-2026-09-23: the ride's OWN channel config -- channel id, key and WiFi password,
        // generated together. Simple path: config_mode 'unique'. Org / organizer inheritance later.
        val cfg = ConvoyNetworkStore.create(OwnerType.RIDE, id, rideName.trim()) ?: run {
            Log.w(TAG, "saveRide refused: the ride's channel config was not created"); return null
        }
        val expires = expiresFor(rideDate)
        return try {
            db.execSQL(
                "INSERT INTO rides (ride_id, organizer_id, organizer_name, route_id, " +
                    "ride_name, ride_date, start_time, description, zip_code, is_public, " +
                    "config_mode, config_id, expires_at, " +
                    "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    id, me.userId, leaderName, routeId,
                    rideName.trim(), rideDate.trim(), startTime.trim(), description.trim(),
                    zipCode.trim(), if (isPublic) 1 else 0,
                    "unique", cfg.configId, expires, now, now
                )
            )
            Log.i(TAG, "RIDECREATE-2026-09-22: ride saved $id name=$rideName route=$routeId")
            id
        } catch (e: Exception) {
            Log.e(TAG, "saveRide failed: ${e.message}")
            ConvoyNetworkStore.deleteUnused(cfg.configId)   // RIDECFG: no ghost config
            null
        }
    }

    // ---- RIDEHEAL-2026-09-26 (Fred): the ride library heals itself ----------------------------------
    /**
     * Reconciles the ride FILES (rides/<id>.json) with the rides TABLE before any ride list is shown.
     * ADDS OR REPAIRS -- and deletes ONLY on EXPIRY (RIDEEXPIRE: 30 days after the ride date, the ride's own
     * published expiry). Never guesses at ghosts (Fred: a profile can be recreated; any other deletion is the
     * rider's own Delete). Everything is observable: every repair and expiry is logged (RIDEHEAL / RIDEDELETE).
     *  - a file with no row  -> a row created as an INCOMPLETE ride of its CREATOR (the file's originator
     *    userId + name; distributed_at empty); the route matched by name; config_mode 'file' + the file's
     *    network id (a received ride's network lives ONLY in its file).
     *  - a short date (2026-10-5) -> padded to yyyy-MM-dd in the file and the row; the expiry recomputed.
     *  - a row with no file  -> LOGGED only (a file is rebuilt from a row only for this tablet's own rides).
     * Idempotent: a second run finds nothing to do.
     */
    fun healFromFiles(context: android.content.Context) {
        ensureSchema()
        val db = SpatialDbManager.getExtensionDb() ?: return
        val files = GroupTrackStorage.dir("rides", context).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        val rows = mutableSetOf<String>()
        try {
            db.rawQuery("SELECT ride_id FROM rides", null).use { c -> while (c.moveToNext()) c.getString(0)?.let { rows += it } }
        } catch (e: Exception) { Log.w(TAG, "RIDEHEAL: cannot read rides: ${e.message}"); return }
        val routes by lazy { listRoutes(500) }
        for (f in files) {
            val j = try { org.json.JSONObject(f.readText()) } catch (e: Exception) { Log.w(TAG, "RIDEHEAL: unreadable ${f.name}"); continue }
            if (j.optString("kind") != "grouptrack.ride") continue
            val ride = j.optJSONObject("ride") ?: continue
            val id = ride.optString("rideId").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val s = { k: String -> ride.optString(k).takeIf { it != "null" }.orEmpty() }
            val rawDate = s("date")
            val date = padDate(rawDate)
            if (date != rawDate) {
                ride.put("date", date).put("expiresAt", expiresFor(date) ?: org.json.JSONObject.NULL)
                try { f.writeText(j.toString(2)); Log.i(TAG, "RIDEHEAL: $id date $rawDate -> $date (file)") }
                catch (e: Exception) { Log.w(TAG, "RIDEHEAL: $id date not rewritten: ${e.message}") }
                if (id in rows) try {
                    db.execSQL("UPDATE rides SET ride_date=?, expires_at=?, updated_at=? WHERE ride_id=?", arrayOf<Any?>(date, expiresFor(date), nowUtc(), id))
                    Log.i(TAG, "RIDEHEAL: $id date $rawDate -> $date (row)")
                } catch (e: Exception) { Log.w(TAG, "RIDEHEAL: $id row date not updated: ${e.message}") }
            }
            if (id in rows) continue
            val o = j.optJSONObject("originator")
            val creatorId = o?.optString("userId")?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val creatorName = o?.optString("name")?.takeIf { it != "null" }.orEmpty()
            val routeName = j.optJSONObject("rideData")?.optJSONObject("route")?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
            val routeId = routeName?.let { n -> routes.firstOrNull { it.name == n }?.routeId }
            try {
                db.execSQL(
                    "INSERT INTO rides (ride_id, organizer_id, organizer_name, route_id, " +
                        "ride_name, ride_date, start_time, description, zip_code, is_public, " +
                        "config_mode, config_id, expires_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        id, creatorId, creatorName, routeId, s("name"), date, s("startTime"), s("description"), s("zipCode"), 0,
                        "file", j.optJSONObject("network")?.optString("id")?.takeIf { it.isNotBlank() && it != "null" },
                        expiresFor(date), s("createdAt").ifEmpty { nowUtc() }, nowUtc()
                    )
                )
                rows += id
                Log.i(TAG, "RIDEHEAL: $id row created from ${f.name} -- incomplete ride of '$creatorName'" +
                    (if (creatorId.isEmpty()) " (no creator id in the file)" else "") +
                    ", route " + (routeId ?: "NOT FOUND ('$routeName')"))
            } catch (e: Exception) { Log.w(TAG, "RIDEHEAL: $id row not created: ${e.message}") }
        }
        rows.filter { r -> files.none { it.nameWithoutExtension == r } }
            .forEach { Log.w(TAG, "RIDEHEAL: row $it has no ride file (logged only)") }
        // RIDEEXPIRE-2026-09-26 (Fred): a ride's data is deleted 30 days after its ride date (its own published
        // expiry: expires_at = date + 30). A ride with no readable date is never expired -- it is logged instead.
        try {
            val today = LocalDate.now().toString()
            val expired = mutableListOf<String>()
            db.rawQuery("SELECT ride_id, ride_date, expires_at FROM rides", null).use { c ->
                while (c.moveToNext()) {
                    val rid = c.getString(0) ?: continue
                    val exp = c.getString(2)?.takeIf { it.isNotBlank() } ?: expiresFor(padDate(c.getString(1) ?: ""))
                    if (exp == null) { Log.w(TAG, "RIDEEXPIRE: $rid has no readable ride date -- kept"); continue }
                    if (exp < today) expired += rid
                }
            }
            expired.forEach { deleteRide(context, it, "expired: 30 days after its ride date") }
        } catch (e: Exception) { Log.w(TAG, "RIDEEXPIRE: sweep failed: ${e.message}") }
    }

    /**
     * RIDEEXPIRE-2026-09-26: removes a ride COMPLETELY -- its row, its waypoint links, its network when no other
     * ride uses it, its ride file and its picture. Used by the expiry sweep AND by the rider's Delete (one
     * delete, so a ride can never be half-removed). NEVER touches the route, the waypoints themselves, or radio
     * backups (their ride titles live in the backups' own companion files). Logged (RIDEDELETE).
     */
    fun deleteRide(context: android.content.Context, rideId: String, reason: String): Boolean {
        val db = SpatialDbManager.getExtensionDb() ?: return false
        return try {
            val cfgId = db.rawQuery("SELECT config_id FROM rides WHERE ride_id = ?", arrayOf(rideId)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
            db.execSQL("DELETE FROM ride_waypoints WHERE ride_id = ?", arrayOf<Any?>(rideId))
            db.execSQL("DELETE FROM rides WHERE ride_id = ?", arrayOf<Any?>(rideId))
            cfgId?.let { ConvoyNetworkStore.deleteUnused(it) }
            val dir = GroupTrackStorage.dir("rides", context)
            listOf("$rideId.json", "$rideId.jpg").forEach { n -> java.io.File(dir, n).takeIf { it.exists() }?.delete() }
            Log.i(TAG, "RIDEDELETE: $rideId removed ($reason)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "RIDEDELETE: $rideId failed: ${e.message}"); false
        }
    }

    /** yyyy-M-d -> yyyy-MM-dd (RIDEHEAL); anything else is returned unchanged. */
    private fun padDate(d: String): String {
        val m = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").find(d.trim()) ?: return d
        val (y, mo, da) = m.destructured
        return "%s-%02d-%02d".format(y, mo.toInt(), da.toInt())
    }

    // ---- RIDECFG-2026-09-23: ride state, edits, waypoints -------------------------------------

    /** Ride date + 30 days (the ride file's expiry), or null when the date is not yyyy-MM-dd yet. */
    private fun expiresFor(rideDate: String): String? =
        try { LocalDate.parse(rideDate.trim()).plusDays(30).toString() } catch (e: Exception) { null }

    @Volatile private var schemaChecked = false

    /**
     * CODE RULE 3 -- one-time code, no marker. The ALTER exists only for tablets that already have
     * the rides table (Droid 1); an existing column is fine. REMOVE the ALTER, and add
     * distributed_at to schema_device_additions.sql, when 2.7 is cut.
     */
    private fun ensureSchema() {
        if (schemaChecked) return
        val db = SpatialDbManager.getExtensionDb() ?: return
        try {
            db.execSQL("ALTER TABLE rides ADD COLUMN distributed_at TEXT")
            Log.i(TAG, "RIDECFG-2026-09-23: rides.distributed_at added")
        } catch (e: Exception) {
            Log.d(TAG, "rides.distributed_at already present")
        }
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS ride_waypoints (ride_id TEXT NOT NULL, " +
                    "waypoint_id TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (ride_id, waypoint_id))"
            )
            schemaChecked = true
        } catch (e: Exception) {
            Log.e(TAG, "ensureSchema: ride_waypoints not created: ${e.message}")
        }
    }

    /** In progress = distributed_at empty. DERIVED from the column, never a second flag. */
    fun isDistributed(rideId: String): Boolean {
        ensureSchema()
        val db = SpatialDbManager.getExtensionDb() ?: return true
        return try {
            db.rawQuery("SELECT distributed_at FROM rides WHERE ride_id = ?", arrayOf(rideId)).use { c ->
                c.moveToFirst() && !c.isNull(0) && c.getString(0).isNotBlank()
            }
        } catch (e: Exception) {
            Log.w(TAG, "isDistributed failed: ${e.message}"); true
        }
    }

    /** Changes an IN-PROGRESS ride. Refused once distributed (then only the date may change). */
    fun updateRide(
        rideId: String, rideName: String, rideDate: String, startTime: String,
        description: String, zipCode: String, isPublic: Boolean, routeId: String
    ): Boolean {
        if (isDistributed(rideId)) {
            Log.w(TAG, "updateRide refused: $rideId is distributed -- only the date can change"); return false
        }
        val db = SpatialDbManager.getExtensionDb() ?: return false
        return try {
            db.execSQL(
                "UPDATE rides SET ride_name=?, ride_date=?, start_time=?, description=?, zip_code=?, " +
                    "is_public=?, route_id=?, expires_at=?, updated_at=? " +
                    "WHERE ride_id=? AND distributed_at IS NULL",
                arrayOf<Any?>(
                    rideName.trim(), rideDate.trim(), startTime.trim(), description.trim(),
                    zipCode.trim(), if (isPublic) 1 else 0, routeId, expiresFor(rideDate), nowUtc(), rideId
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateRide failed: ${e.message}"); false
        }
    }

    /** The ONLY change allowed after distribution. Moves the expiry with it. */
    fun updateRideDate(rideId: String, rideDate: String): Boolean {
        val db = SpatialDbManager.getExtensionDb() ?: return false
        return try {
            db.execSQL(
                "UPDATE rides SET ride_date=?, expires_at=?, updated_at=? WHERE ride_id=?",
                arrayOf<Any?>(rideDate.trim(), expiresFor(rideDate), nowUtc(), rideId)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateRideDate failed: ${e.message}"); false
        }
    }

    /** Sending is the lock. Called when the ride is first distributed; later calls change nothing. */
    fun markDistributed(rideId: String): Boolean {
        ensureSchema()
        val db = SpatialDbManager.getExtensionDb() ?: return false
        return try {
            db.execSQL(
                "UPDATE rides SET distributed_at=?, updated_at=? WHERE ride_id=? AND distributed_at IS NULL",
                arrayOf<Any?>(nowUtc(), nowUtc(), rideId)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "markDistributed failed: ${e.message}"); false
        }
    }

    /** Replaces the ride's waypoint list, in order. IN PROGRESS only. */
    fun setRideWaypoints(rideId: String, waypointIds: List<String>): Boolean {
        if (isDistributed(rideId)) {
            Log.w(TAG, "setRideWaypoints refused: $rideId is distributed"); return false
        }
        val db = SpatialDbManager.getExtensionDb() ?: return false
        return try {
            db.execSQL("DELETE FROM ride_waypoints WHERE ride_id=?", arrayOf<Any?>(rideId))
            waypointIds.forEachIndexed { i, wp ->
                db.execSQL(
                    "INSERT OR IGNORE INTO ride_waypoints (ride_id, waypoint_id, seq) VALUES (?,?,?)",
                    arrayOf<Any?>(rideId, wp, i)
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "setRideWaypoints failed: ${e.message}"); false
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
