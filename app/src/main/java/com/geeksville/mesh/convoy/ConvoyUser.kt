package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtSettingsAndroid
import com.geeksville.mesh.grouptrack.GtStorageAndroid
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.user.GtUser
import com.grouptrack.shared.user.GtUserStore
import com.grouptrack.shared.user.GtUserType
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * ConvoyUser — V2 local user model
 *
 * Two user types: ORGANIZER and RIDER
 * All fields required except knownDevices (auto-populated)
 * Expiration: one year from creation date
 * Pay model: deferred to V3
 *
 * SWITCH17G2C2-2026-11-12 (2.8 step 17, G2c-2): front doors to the shared GtUser / GtUserStore (certified at 2dc90a905: Droid 226 SAME,
 * 0 DIFF on 13 cases + the rider's own file; iPad = Droid). Every public name and type kept -- the screens are unchanged. The same file
 * (filesDir/convoy_users.json, the same text), the same prefs file and key (convoy_prefs / active_user_id). Today = LocalDate.now().
 */

enum class ConvoyUserType { ORGANIZER, RIDER }

data class ConvoyUser(
    val userId: String,
    val userType: ConvoyUserType,
    val firstName: String,
    val lastName: String,
    val email: String,
    val cellPhone: String,
    val vehicleType: String,
    val createdDate: String,
    val expirationDate: String,
    val knownDevices: MutableList<String> = mutableListOf()
) {
    val fullName: String get() = "$firstName $lastName"

    val isExpired: Boolean get() = toShared().isExpiredOn(LocalDate.now().toString())

    fun toJson(): JSONObject = JSONObject(GtOrgJson.compact(toShared().toJson()))

    /** the shared user -- knownDevices is the SAME list (2.7e callers change it in place) */
    fun toShared(): GtUser = GtUser(userId, GtUserType.valueOf(userType.name), firstName, lastName, email, cellPhone, vehicleType,
        createdDate, expirationDate, knownDevices)

    companion object {
        fun fromShared(g: GtUser): ConvoyUser = ConvoyUser(g.userId, ConvoyUserType.valueOf(g.userType.name), g.firstName, g.lastName, g.email,
            g.cellPhone, g.vehicleType, g.createdDate, g.expirationDate, g.knownDevices)

        fun fromJson(obj: JSONObject): ConvoyUser = fromShared(GtUser.fromJson(GtOrgJson.parse(obj.toString()) as JsonObject))

        fun create(
            userType: ConvoyUserType,
            firstName: String,
            lastName: String,
            email: String,
            cellPhone: String,
            vehicleType: String
        ): ConvoyUser = fromShared(GtUser.create(GtUserType.valueOf(userType.name), firstName, lastName, email, cellPhone, vehicleType,
            LocalDate.now().toString(), UUID.randomUUID().toString()))
    }
}

/**
 * ConvoyUserStore — local JSON file storage for user profiles
 * File: convoy_users.json in app files directory
 *
 * Pairing rules:
 *   - Hardware ID found + current user matches     → recognized, no action
 *   - Hardware ID found + current user no match    → create new pairing for current user
 *   - Hardware ID not found                        → create device record and pairing
 *   - User not found at import/export              → create user record silently
 */
object ConvoyUserStore {

    private fun store(context: Context) = GtUserStore(FileSystem.SYSTEM, GtStorageAndroid.of(context).filesDir() / GtUserStore.FILE_NAME,
        GtSettingsAndroid(context).store(GtUserStore.PREFS_NAME))

    fun loadAll(context: Context): List<ConvoyUser> = store(context).loadAll().map { ConvoyUser.fromShared(it) }

    fun saveAll(context: Context, users: List<ConvoyUser>) = store(context).saveAll(users.map { it.toShared() })

    fun save(context: Context, user: ConvoyUser) = store(context).save(user.toShared())

    fun getActiveUser(context: Context): ConvoyUser? = store(context).getActiveUser()?.let { ConvoyUser.fromShared(it) }

    fun setActiveUser(context: Context, userId: String) = store(context).setActiveUser(userId)

    fun isEnrolled(context: Context): Boolean = store(context).isEnrolled()

    fun addDeviceToActiveUser(context: Context, hardwareId: String) = store(context).addDeviceToActiveUser(hardwareId)

    // Returns the verified active user after pairing resolution
    fun resolvePairing(context: Context, hardwareId: String): ConvoyUser? = store(context).resolvePairing(hardwareId)?.let { ConvoyUser.fromShared(it) }
}
