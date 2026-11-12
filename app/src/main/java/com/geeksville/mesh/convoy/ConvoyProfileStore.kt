package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.GtAppDb
import com.grouptrack.shared.net.GtProfileStore
import com.grouptrack.shared.net.GtRiderProfile
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

    // SWITCH17G2C1-2026-11-12 (2.8 step 17 G2c-1): front doors to the shared GtProfileStore (certified OLD = NEW at 803ecd4c0) on SpatialDbManager's
    // existing extension handle -- still never a second connection. Same SQL, same refusals, same log lines (tag ConvoyProfileStore).

    private fun nowUtc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private val store = GtProfileStore({ SpatialDbManager.getExtensionDb()?.let { GtAppDb.wrap(it) } }, { nowUtc() }, { UUID.randomUUID().toString() })

    /** True when this tablet already has its own rider profile. */
    fun exists(): Boolean = store.exists()

    /** The own profile, or null when there is none yet. */
    fun load(): RiderProfile? = store.load()?.let {
        RiderProfile(it.userId, it.firstName, it.lastName, it.callsign, it.email, it.cell, it.defaultRole, it.vehicleType, it.team)
    }

    /** Creates the profile, or updates it when one exists. Callsign and email are required. Returns the user_id, or null. */
    fun save(p: RiderProfile): String? =
        store.save(GtRiderProfile(p.userId, p.firstName, p.lastName, p.callsign, p.email, p.cell, p.defaultRole, p.vehicleType, p.team))
}
