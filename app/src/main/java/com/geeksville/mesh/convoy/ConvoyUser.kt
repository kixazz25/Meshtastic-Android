package com.geeksville.mesh.convoy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * ConvoyUser — V2 local user model
 *
 * Two user types: ORGANIZER and RIDER
 * All fields required except knownDevices (auto-populated)
 * Expiration: one year from creation date
 * Pay model: deferred to V3
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

    val isExpired: Boolean
        get() {
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            val expiry = LocalDate.parse(expirationDate, fmt)
            return LocalDate.now().isAfter(expiry)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("userId", userId)
        put("userType", userType.name)
        put("firstName", firstName)
        put("lastName", lastName)
        put("email", email)
        put("cellPhone", cellPhone)
        put("vehicleType", vehicleType)
        put("createdDate", createdDate)
        put("expirationDate", expirationDate)
        put("knownDevices", JSONArray(knownDevices))
    }

    companion object {
        fun fromJson(obj: JSONObject): ConvoyUser {
            val devices = mutableListOf<String>()
            val arr = obj.optJSONArray("knownDevices")
            if (arr != null) {
                for (i in 0 until arr.length()) devices.add(arr.getString(i))
            }
            return ConvoyUser(
                userId        = obj.getString("userId"),
                userType      = ConvoyUserType.valueOf(obj.getString("userType")),
                firstName     = obj.getString("firstName"),
                lastName      = obj.getString("lastName"),
                email         = obj.getString("email"),
                cellPhone     = obj.getString("cellPhone"),
                vehicleType   = obj.getString("vehicleType"),
                createdDate   = obj.getString("createdDate"),
                expirationDate= obj.getString("expirationDate"),
                knownDevices  = devices
            )
        }

        fun create(
            userType: ConvoyUserType,
            firstName: String,
            lastName: String,
            email: String,
            cellPhone: String,
            vehicleType: String
        ): ConvoyUser {
            val today = LocalDate.now()
            val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
            return ConvoyUser(
                userId         = UUID.randomUUID().toString(),
                userType       = userType,
                firstName      = firstName.trim(),
                lastName       = lastName.trim(),
                email          = email.trim(),
                cellPhone      = cellPhone.trim(),
                vehicleType    = vehicleType.trim(),
                createdDate    = today.format(fmt),
                expirationDate = today.plusYears(1).format(fmt)
            )
        }
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

    private const val FILE_NAME = "convoy_users.json"
    private const val ACTIVE_KEY = "active_user_id"
    private const val PREFS_NAME = "convoy_prefs"

    // ── Load all users ────────────────────────────────────────────────────────
    fun loadAll(context: Context): List<ConvoyUser> {
        val file = java.io.File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { ConvoyUser.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Save all users ────────────────────────────────────────────────────────
    fun saveAll(context: Context, users: List<ConvoyUser>) {
        val arr = JSONArray()
        users.forEach { arr.put(it.toJson()) }
        java.io.File(context.filesDir, FILE_NAME).writeText(arr.toString())
    }

    // ── Save single user (add or update) ─────────────────────────────────────
    fun save(context: Context, user: ConvoyUser) {
        val users = loadAll(context).toMutableList()
        val idx = users.indexOfFirst { it.userId == user.userId }
        if (idx >= 0) users[idx] = user else users.add(user)
        saveAll(context, users)
    }

    // ── Get active user ───────────────────────────────────────────────────────
    fun getActiveUser(context: Context): ConvoyUser? {
        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val active = prefs.getString(ACTIVE_KEY, null) ?: return null
        return loadAll(context).firstOrNull { it.userId == active }
    }

    // ── Set active user ───────────────────────────────────────────────────────
    fun setActiveUser(context: Context, userId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_KEY, userId).apply()
    }

    // ── Check enrollment ──────────────────────────────────────────────────────
    fun isEnrolled(context: Context): Boolean = getActiveUser(context) != null

    // ── Add device to active user ─────────────────────────────────────────────
    fun addDeviceToActiveUser(context: Context, hardwareId: String) {
        val user = getActiveUser(context) ?: return
        if (!user.knownDevices.contains(hardwareId)) {
            user.knownDevices.add(hardwareId)
            save(context, user)
        }
    }

    // ── Resolve pairing at import/export ─────────────────────────────────────
    // Returns the verified active user after pairing resolution
    fun resolvePairing(context: Context, hardwareId: String): ConvoyUser? {
        val activeUser = getActiveUser(context) ?: return null
        return if (activeUser.knownDevices.contains(hardwareId)) {
            activeUser // Match found
        } else {
            // Add device to current user — create pairing silently
            activeUser.knownDevices.add(hardwareId)
            save(context, activeUser)
            activeUser
        }
    }
}
