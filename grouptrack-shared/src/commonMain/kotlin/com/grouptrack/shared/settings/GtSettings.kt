package com.grouptrack.shared.settings

import com.grouptrack.shared.log.gtLogger

/**
 * STEP17G2B1-2026-11-12 (2.8 step 17, G2b-1): the SETTINGS socket -- typed values in NAMED stores, each read with the caller's own
 * default, exactly as Android's SharedPreferences does (Android = SharedPreferences(name): the same files, the same keys; the iPad =
 * UserDefaults, one suite per store name, at step 18). A write is applied at once in memory and saved in the background (apply()).
 */
interface GtSettingsStore {
    fun getString(key: String, def: String?): String?
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getStringSet(key: String, def: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    /** every key and value (SharedPreferences.getAll) -- for the cert and the one-time copies */
    fun all(): Map<String, Any?>
    fun edit(block: GtSettingsEditor.() -> Unit)
}
interface GtSettingsEditor {
    fun putString(key: String, value: String?)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putFloat(key: String, value: Float)
    fun putBoolean(key: String, value: Boolean)
    fun putStringSet(key: String, value: Set<String>?)
    fun remove(key: String)
    fun clear()
}
interface GtSettings {
    fun store(name: String): GtSettingsStore
}

/**
 * STEP17G2B1-2026-11-12: ConvoySessionManager (2.7e, convoy/ConvoySessionManager.kt) ported as is onto the settings socket -- the
 * same store ("grouptrack_user" for the app; the cert passes its own copy), the same keys, defaults and versions, the same launch route.
 * Changes, and only these: Context -> the socket; System.currentTimeMillis -> [nowMs]; android.util.Log -> gtLogger("ConvoySession");
 * ConvoyConfig.PAYWALL_ENABLED -> GtConfig.PAYWALL_ENABLED.
 */
class GtSession(private val settings: GtSettings, private val storeName: String, private val nowMs: () -> Long) {
    companion object {
        const val STORE = "grouptrack_user"
        const val TERMS_VERSION = "1.0"
        const val PRIVACY_VERSION = "1.0"
    }
    private val log = gtLogger("ConvoySession")
    private fun prefs() = settings.store(storeName)

    fun getUserId(): String? = prefs().getString("user_id", null)
    fun isSignedIn(): Boolean = getUserId() != null
    fun isSubscribed(): Boolean {
        if (prefs().getBoolean("dev_subscribed_override", false)) return true
        val exp = prefs().getLong("subscription_expires_at", 0L)
        return exp > nowMs()
    }
    fun isSubscriptionExpired(): Boolean = isSignedIn() && !isSubscribed()
    fun termsAccepted(): Boolean = prefs().getString("terms_version", null) == TERMS_VERSION
    fun privacyAccepted(): Boolean = prefs().getString("privacy_version", null) == PRIVACY_VERSION
    fun isOrganizer(): Boolean = prefs().getBoolean("is_organizer", false)
    fun getFirstName(): String = prefs().getString("first_name", "") ?: ""
    fun getEmail(): String = prefs().getString("email", "") ?: ""
    fun getZipCode(): String = prefs().getString("zip_code", "") ?: ""
    fun getCity(): String = prefs().getString("city", "") ?: ""
    fun setCity(city: String) = prefs().edit { putString("city", city) }
    fun getState(): String = prefs().getString("state", "") ?: ""
    fun setState(state: String) = prefs().edit { putString("state", state) }
    fun getSearchRadius(): Int = prefs().getInt("search_radius_miles", 25)
    fun setZipCode(zip: String) = prefs().edit { putString("zip_code", zip) }
    fun setSearchRadius(miles: Int) = prefs().edit { putInt("search_radius_miles", miles) }
    fun saveUser(userId: String, googleId: String, email: String, firstName: String, lastName: String) {
        prefs().edit {
            putString("user_id", userId); putString("google_id", googleId); putString("email", email)
            putString("first_name", firstName); putString("last_name", lastName)
        }
        log.i { "User saved: $userId" }
    }
    fun acceptTerms() = prefs().edit { putString("terms_version", TERMS_VERSION) }
    fun acceptPrivacy() = prefs().edit { putString("privacy_version", PRIVACY_VERSION) }
    fun setOrganizer(value: Boolean) = prefs().edit { putBoolean("is_organizer", value) }
    fun setSubscriptionExpiry(expiresAtMs: Long) = prefs().edit { putLong("subscription_expires_at", expiresAtMs) }
    fun clearSession() { prefs().edit { clear() }; log.i { "Session cleared" } }

    enum class LaunchRoute { CONVOY_MAP, SIGN_IN, TERMS, PRIVACY, SUBSCRIPTION, DASHBOARD }
    /** [hasInternet] is unused, as in 2.7e (kept so the callers do not change) */
    fun resolveLaunchRoute(@Suppress("UNUSED_PARAMETER") hasInternet: Boolean): LaunchRoute {
        if (!isSignedIn()) return LaunchRoute.SIGN_IN
        if (!termsAccepted()) return LaunchRoute.TERMS
        if (!privacyAccepted()) return LaunchRoute.PRIVACY
        if (GtConfig.PAYWALL_ENABLED && !isSubscribed()) return LaunchRoute.SUBSCRIPTION
        return LaunchRoute.DASHBOARD
    }
}

/**
 * STEP17G2B1-2026-11-12: ConvoyConfig's TRUE constants (2.7e, convoy/ConvoyConfig.kt), the same names and values, now shared.
 * ConvoyConfig keeps every name (the switch points them here). Its runtime values (display states, thresholds, DOWNLOAD_ZOOM, export
 * format, the tile sources and folder) stay in the app for the map / ride groups.
 */
object GtConfig {
    const val MAP_DEFAULT_ZOOM = 18.0
    const val LOCAL_TILE_BASE = "convoy://tiles/"
    const val MAP_GROUP_ZOOM_PADDING = 1.4f
    const val MAP_CART_ZOOM = 18.0
    const val MAP_MIN_ZOOM = 16.0
    const val BLINK_LOST_MS = 3000L
    const val BLINK_DROP_MS = 150L
    const val MARKER_SIZE_LARGE_DP = 24
    const val MARKER_SIZE_MEDIUM_DP = 16
    const val API_BASE_URL = "https://grouptrack.org/convoy_api.php/"
    const val V3_FEATURES_ENABLED = false
    const val PAYWALL_ENABLED = false
    const val IS_STANDALONE_BUILD = true
    const val DOWNLOAD_ZOOM_MIN = 4
}
