package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log

private const val TAG = "ConvoySession"
private const val PREFS_NAME = "grouptrack_user"

const val TERMS_VERSION   = "1.0"
const val PRIVACY_VERSION = "1.0"

object ConvoySessionManager {

    fun getUserId(ctx: Context): String? =
        prefs(ctx).getString("user_id", null)

    fun isSignedIn(ctx: Context): Boolean =
        getUserId(ctx) != null

    fun isSubscribed(ctx: Context): Boolean {
        if (prefs(ctx).getBoolean("dev_subscribed_override", false)) return true
        val exp = prefs(ctx).getLong("subscription_expires_at", 0L)
        return exp > System.currentTimeMillis()
    }

    fun isSubscriptionExpired(ctx: Context): Boolean =
        isSignedIn(ctx) && !isSubscribed(ctx)

    fun termsAccepted(ctx: Context): Boolean =
        prefs(ctx).getString("terms_version", null) == TERMS_VERSION

    fun privacyAccepted(ctx: Context): Boolean =
        prefs(ctx).getString("privacy_version", null) == PRIVACY_VERSION

    fun isOrganizer(ctx: Context): Boolean =
        prefs(ctx).getBoolean("is_organizer", false)

    fun getFirstName(ctx: Context): String =
        prefs(ctx).getString("first_name", "") ?: ""

    fun getEmail(ctx: Context): String =
        prefs(ctx).getString("email", "") ?: ""

    fun getZipCode(ctx: Context): String =
        prefs(ctx).getString("zip_code", "") ?: ""

    fun getSearchRadius(ctx: Context): Int =
        prefs(ctx).getInt("search_radius_miles", 25)

    fun setZipCode(ctx: Context, zip: String) {
        prefs(ctx).edit().putString("zip_code", zip).apply()
    }

    fun setSearchRadius(ctx: Context, miles: Int) {
        prefs(ctx).edit().putInt("search_radius_miles", miles).apply()
    }

    fun saveUser(ctx: Context, userId: String, googleId: String,
                 email: String, firstName: String, lastName: String) {
        prefs(ctx).edit().apply {
            putString("user_id",    userId)
            putString("google_id",  googleId)
            putString("email",      email)
            putString("first_name", firstName)
            putString("last_name",  lastName)
            apply()
        }
        Log.i(TAG, "User saved: $userId")
    }

    fun acceptTerms(ctx: Context) {
        prefs(ctx).edit().putString("terms_version", TERMS_VERSION).apply()
    }

    fun acceptPrivacy(ctx: Context) {
        prefs(ctx).edit().putString("privacy_version", PRIVACY_VERSION).apply()
    }

    fun setOrganizer(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("is_organizer", value).apply()
    }

    fun setSubscriptionExpiry(ctx: Context, expiresAtMs: Long) {
        prefs(ctx).edit().putLong("subscription_expires_at", expiresAtMs).apply()
    }

    fun clearSession(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        Log.i(TAG, "Session cleared")
    }

    enum class LaunchRoute {
        CONVOY_MAP, SIGN_IN, TERMS, PRIVACY, SUBSCRIPTION, DASHBOARD
    }

    fun resolveLaunchRoute(ctx: Context, hasInternet: Boolean): LaunchRoute {
        if (!isSignedIn(ctx))      return LaunchRoute.SIGN_IN
        if (!termsAccepted(ctx))   return LaunchRoute.TERMS
        if (!privacyAccepted(ctx)) return LaunchRoute.PRIVACY
        if (ConvoyConfig.PAYWALL_ENABLED && !isSubscribed(ctx)) return LaunchRoute.SUBSCRIPTION
        return LaunchRoute.DASHBOARD
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
