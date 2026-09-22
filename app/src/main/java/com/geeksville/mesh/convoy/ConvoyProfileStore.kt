package com.geeksville.mesh.convoy

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * PROFILE-2026-09-22 — the rider's own profile (users.is_self = 1).
 *
 * Created at first launch, before the map opens, because the callsign has to be in
 * every TAK packet from the first broadcast. Nothing here leaves the tablet: the
 * callsign and position go out over the radio to the riders in range, and a ride file
 * goes where the rider sends it. Nothing is sent to GroupTrack.
 *
 * ONE WRITE PATH: this object is the only place the own-profile row is written.
 * It uses SpatialDbManager's existing extension handle -- never a second connection.
 */
data class RiderProfile(
    val userId: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val callsign: String = "",
    val email: String = "",
    val cell: String = "",
    val defaultRole: String = "rider",
    val vehicleType: String = "",
    val team: String = ""
)

object ConvoyProfileStore {

    private const val TAG = "ConvoyProfileStore"

    private fun nowUtc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /** True when this tablet already has its own rider profile. */
    fun exists(): Boolean {
        val db = SpatialDbManager.getExtensionDb() ?: return false
        return try {
            db.rawQuery("SELECT 1 FROM users WHERE is_self = 1 LIMIT 1", null).use { c ->
                c.moveToFirst()
            }
        } catch (e: Exception) {
            Log.w(TAG, "exists() failed: ${e.message}")
            false
        }
    }

    /** The own profile, or null when there is none yet. */
    fun load(): RiderProfile? {
        val db = SpatialDbManager.getExtensionDb() ?: return null
        return try {
            db.rawQuery(
                "SELECT user_id, first_name, last_name, callsign, email, cell, " +
                    "default_role, vehicle_type, team FROM users WHERE is_self = 1 LIMIT 1",
                null
            ).use { c ->
                if (!c.moveToFirst()) return null
                RiderProfile(
                    userId = c.getString(0) ?: "",
                    firstName = c.getString(1) ?: "",
                    lastName = c.getString(2) ?: "",
                    callsign = c.getString(3) ?: "",
                    email = c.getString(4) ?: "",
                    cell = c.getString(5) ?: "",
                    defaultRole = c.getString(6) ?: "rider",
                    vehicleType = c.getString(7) ?: "",
                    team = c.getString(8) ?: ""
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load() failed: ${e.message}")
            null
        }
    }

    /**
     * Creates the profile, or updates it when one exists. Callsign and email are
     * required -- the table's own CHECK enforces the email on the own row.
     * Returns the user_id, or null when the write failed.
     */
    fun save(p: RiderProfile): String? {
        val db = SpatialDbManager.getExtensionDb() ?: return null
        if (p.callsign.isBlank() || p.email.isBlank()) {
            Log.w(TAG, "save() refused: callsign and email are required")
            return null
        }
        val now = nowUtc()
        val existing = load()
        val id = existing?.userId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        return try {
            if (existing == null) {
                db.execSQL(
                    "INSERT INTO users (user_id, first_name, last_name, email, cell, callsign, " +
                        "default_role, vehicle_type, team, is_self, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,1,?,?)",
                    arrayOf(
                        id, p.firstName, p.lastName, p.email, p.cell, p.callsign,
                        p.defaultRole, p.vehicleType, p.team, now, now
                    )
                )
                Log.i(TAG, "PROFILE-2026-09-22: own profile created ($id)")
            } else {
                db.execSQL(
                    "UPDATE users SET first_name=?, last_name=?, email=?, cell=?, callsign=?, " +
                        "default_role=?, vehicle_type=?, team=?, updated_at=? WHERE user_id=?",
                    arrayOf(
                        p.firstName, p.lastName, p.email, p.cell, p.callsign,
                        p.defaultRole, p.vehicleType, p.team, now, id
                    )
                )
                Log.i(TAG, "PROFILE-2026-09-22: own profile updated ($id)")
            }
            id
        } catch (e: Exception) {
            Log.e(TAG, "save() failed: ${e.message}")
            null
        }
    }
}
