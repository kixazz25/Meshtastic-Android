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
        // FORMLAYOUT-2026-09-27: the network SHOWN on the form. REQUIRED, no default; CODE RULE 1: null is a real
        // choice -- the organizer's own network; a value is the ride-only network generated when the rider toggled.
        rideNetworkId: String?,
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
        // RIDENET-2026-09-27 (Fred): the ORGANIZER's network by default -- created with their first ride and reused for
        // every ride after (users.config_id, config_mode 'own') -- or, when asked, a NEW network for this ride only.
        // Every network is NAMED after the organizer. Only the rider's OWN network or a new one: never another
        // organizer's (theirs arrive only to JOIN their rides). Supersedes RIDECFG-2026-09-23's always-unique path.
        val cfg = (if (rideNetworkId != null) ConvoyNetworkStore.load(rideNetworkId)
                   else organizerNetwork(me.userId, leaderName)) ?: run {
            Log.w(TAG, "saveRide refused: the ride's network was not available"); return null
        }
        val mode = if (rideNetworkId != null) "unique" else "inherit_leader"
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
                    mode, cfg.configId, expires, now, now
                )
            )
            Log.i(TAG, "RIDECREATE-2026-09-22: ride saved $id name=$rideName route=$routeId")
            markOrganizer(me.userId)   // RIDENET-2026-09-27: creating a ride makes you an organizer
            Log.i(TAG, "RIDENET: ride $id on ${cfg.configId} ($mode)")
            id
        } catch (e: Exception) {
            Log.e(TAG, "saveRide failed: ${e.message}")
            if (rideNetworkId != null) ConvoyNetworkStore.deleteUnused(cfg.configId)   // RIDECFG: no ghost config (never the organizer's own)
            null
        }
    }

    // ---- CHECKIN-2026-09-27 (Fred): the pre-ride check-in and the end of the ride ------------------------------
    data class RideChoice(val rideId: String, val name: String, val date: String, val startTime: String,
                          val organizerName: String, val organizerId: String, val isPublic: Boolean)

    /** The check-in. CODE RULE 1: rideId null = "No scheduled ride" (a normal recording; no survey, no sharing). */
    data class CheckIn(val rideId: String?, val rideName: String?, val isPublic: Boolean, val callsign: String, val role: String)

    /** Open, recent rides: dated today or earlier and not expired -- today's first, then newest. */
    fun openRecentRides(): List<RideChoice> = try {
        val today = java.time.LocalDate.now().toString()
        SpatialDbManager.getExtensionDb()?.rawQuery(
            "SELECT ride_id, ride_name, ride_date, start_time, organizer_name, organizer_id, is_public FROM rides " +
                "WHERE ride_date <= ? AND (expires_at IS NULL OR expires_at >= ?) ORDER BY ride_date DESC, start_time ASC",
            arrayOf(today, today))?.use { c ->
            val out = ArrayList<RideChoice>()
            while (c.moveToNext()) out += RideChoice(c.getString(0), c.getString(1) ?: "Ride", c.getString(2) ?: "",
                c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getInt(6) == 1)
            out
        } ?: emptyList()
    } catch (e: Exception) { Log.w(TAG, "CHECKIN: openRecentRides failed: ${e.message}"); emptyList() }

    /** Checks in: the enrollment (ride, me, callsign and role FOR THIS RIDE; created_by 'login'), replacing any earlier
     *  check-in of mine to the same ride. No ride -> no enrollment. The profile is never changed. Null only on failure. */
    fun checkIn(ride: RideChoice?, callsign: String, role: String): CheckIn? {
        val me = ConvoyProfileStore.load() ?: return null
        val cs = callsign.trim().ifBlank { me.callsign }
        if (ride == null) { Log.i(TAG, "CHECKIN: no scheduled ride, callsign $cs"); return CheckIn(null, null, false, cs, "rider") }
        return try {
            val db = SpatialDbManager.getExtensionDb() ?: return null
            db.execSQL("DELETE FROM enrollments WHERE ride_id = ? AND user_id = ?", arrayOf<Any?>(ride.rideId, me.userId))
            db.execSQL(
                "INSERT INTO enrollments (enrollment_id, ride_id, user_id, callsign, display_name, role, vehicle_type, team, " +
                    "created_by, enrolled_at) VALUES (?,?,?,?,?,?,?,?,'login',?)",
                arrayOf<Any?>(UUID.randomUUID().toString(), ride.rideId, me.userId, cs,
                    listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { cs },
                    role, me.vehicleType.ifBlank { null }, me.team.ifBlank { null }, nowUtc()))
            Log.i(TAG, "CHECKIN: ${ride.name} (${ride.rideId}) as $role, callsign $cs")
            CheckIn(ride.rideId, ride.name, ride.isPublic, cs, role)
        } catch (e: Exception) { Log.e(TAG, "CHECKIN: not saved: ${e.message}"); null }
    }

    /** What the end-of-ride form collected on a PUBLIC ride. shareTrack is the rider's own required choice. */
    data class RideSurveyInput(val rideId: String, val rating: Int, val difficulty: String, val recommend: Boolean,
                               val notes: String, val shareTrack: Boolean)

    /** v2 (Fred): the survey belongs to the RIDE -- one per rider per ride; a re-save replaces it. trackId is the
     *  rider's own local recording, kept only as the link the table requires (the server keeps ONE track per ride). */
    fun saveSurvey(trackId: String, s: RideSurveyInput): Boolean {
        val me = ConvoyProfileStore.load() ?: return false
        return try {
            SpatialDbManager.getExtensionDb()?.execSQL(
                "DELETE FROM ride_surveys WHERE ride_id = ? AND user_id = ?", arrayOf<Any?>(s.rideId, me.userId))
            // CHECKIN-2026-09-27: the alternate key -- one survey per rider per ride, enforced by the database.
            SpatialDbManager.getExtensionDb()?.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_ride_surveys_ride_user ON ride_surveys(ride_id, user_id) WHERE ride_id IS NOT NULL")
            SpatialDbManager.getExtensionDb()?.execSQL(
                "INSERT OR REPLACE INTO ride_surveys (survey_id, track_id, ride_id, user_id, rating, difficulty, recommend, " +
                    "notes, track_donated, submitted_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(UUID.randomUUID().toString(), trackId, s.rideId, me.userId, s.rating, s.difficulty,
                    if (s.recommend) 1 else 0, s.notes.ifBlank { null }, if (s.shareTrack) 1 else 0, nowUtc()))
            Log.i(TAG, "ENDRIDE: survey saved track=$trackId ride=${s.rideId} rating=${s.rating} share=${s.shareTrack}")
            true
        } catch (e: Exception) { Log.e(TAG, "ENDRIDE: survey not saved: ${e.message}"); false }
    }

    // ---- RIDENET-2026-09-27 (Fred): the organizer's network ------------------------------------------------------
    /** The rider's OWN organizer network id (users.config_id of the is_self row), or null before their first ride. */
    private fun myConfigId(): String? = try {
        SpatialDbManager.getExtensionDb()?.rawQuery(
            "SELECT config_id FROM users WHERE is_self = 1 AND config_id IS NOT NULL LIMIT 1", null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** For the ride form: "Fred K · GroupAB12CD". CODE RULE 1: null is a real state -- no network yet. */
    fun myNetworkName(): String? =
        myConfigId()?.let { ConvoyNetworkStore.load(it) }?.let { "${it.displayName} \u00b7 ${it.configId}" }

    /** RIDENETWORDS2-2026-09-27: the rider's OWN network, created now if missing (named after them), returned by name
     *  for the ride form. CODE RULE 1: null only when there is no rider profile or the network could not be made. */
    fun ensureMyNetworkName(): String? {
        val me = ConvoyProfileStore.load() ?: return null
        val name = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { me.callsign }
        organizerNetwork(me.userId, name) ?: return null
        return myNetworkName()
    }

    /** FORMLAYOUT-2026-09-27: a ride-only network, generated when the rider toggles to it on the form (named after the
     *  organizer). Used only when the ride is added; unused ones are removed by deleteUnused. Null only on failure. */
    fun createRideNetwork(): com.grouptrack.core.NetworkConfig? {
        val me = ConvoyProfileStore.load() ?: return null
        val name = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { me.callsign }
        return ConvoyNetworkStore.create(OwnerType.RIDE, "pending-" + UUID.randomUUID().toString(), name)
    }

    /** True once this rider has created a ride (users.is_organizer). */
    fun isOrganizer(): Boolean = try {
        SpatialDbManager.getExtensionDb()?.rawQuery(
            "SELECT is_organizer FROM users WHERE is_self = 1 LIMIT 1", null)?.use { c ->
            c.moveToFirst() && c.getInt(0) == 1
        } ?: false
    } catch (e: Exception) { false }

    /** The organizer's OWN network: the existing one, or created now (owner LEADER, named after the organizer) and
     *  recorded on the rider (config_mode 'own'). Null only when it could not be created. */
    private fun organizerNetwork(userId: String, name: String): com.grouptrack.core.NetworkConfig? {
        myConfigId()?.let { id -> ConvoyNetworkStore.load(id)?.let { return it } }
        val cfg = ConvoyNetworkStore.create(OwnerType.LEADER, userId, name) ?: return null
        try {
            SpatialDbManager.getExtensionDb()?.execSQL(
                "UPDATE users SET config_mode = 'own', config_id = ?, updated_at = ? WHERE user_id = ?",
                arrayOf<Any?>(cfg.configId, nowUtc(), userId))
            Log.i(TAG, "RIDENET: organizer network ${cfg.configId} created for $name")
        } catch (e: Exception) {
            Log.e(TAG, "RIDENET: organizer network not recorded on the rider: ${e.message}")
        }
        return cfg
    }

    /** Creating a ride makes you an organizer (users.is_organizer = 1). */
    private fun markOrganizer(userId: String) {
        try {
            SpatialDbManager.getExtensionDb()?.execSQL(
                "UPDATE users SET is_organizer = 1, updated_at = ? WHERE user_id = ? AND is_organizer = 0",
                arrayOf<Any?>(nowUtc(), userId))
        } catch (e: Exception) {
            Log.w(TAG, "RIDENET: is_organizer not set: ${e.message}")
        }
    }

    // ---- RIDEHEAL-2026-09-26 (Fred): the ride library heals itself ----------------------------------
    /**
     * Reconciles the ride FILES (rides/<id>.json) with the rides TABLE before any ride list is shown.
     * ADDS OR REPAIRS -- and deletes ONLY on EXPIRY (RIDEEXPIRE: 30 days after the ride date, the ride's own
     * published expiry). Never guesses at ghosts (Fred: a profile can be recreated; any other deletion is the
     * rider's own Delete). Everything is observable: every repair and expiry is logged (RIDEHEAL / RIDEDELETE).
     *  - a file with no row  -> a row created as an INCOMPLETE ride of its CREATOR (the file's originator
     *    userId + name; distributed_at empty); the route matched by name; config_mode 'unique' + the file's
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
                        id, creatorId, creatorName, routeId, s("name"), date, s("startTime"), s("description"), s("zipCode"),
                        if (j.optJSONObject("ride")?.optBoolean("isPublic", false) == true) 1 else 0,   // RIDEPUBLIC-2026-09-27: from the file (older files: private)
                        "unique", j.optJSONObject("network") /* CLOSEFIX-2026-09-26: the CHECK allows inherit_org|inherit_leader|unique */?.optString("id")?.takeIf { it.isNotBlank() && it != "null" },
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
