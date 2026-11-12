package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtSettingsAndroid
import com.grouptrack.shared.settings.GtSession

/*
 * SWITCH17G2B1-2026-11-12 (2.8 step 17, G2b-1 switch): every function is a front door to the shared GtSession (certified 11-12 11:16,
 * 0c178d566: Droid 44 SAME / 0 DIFF, iPad = Droid). The same names, signatures and store ("grouptrack_user", SharedPreferences through
 * GtSettingsAndroid) -- no caller changes. The 2.7e code is kept, generated, as SessionCertOld (the cert's OLD side, TEST-ONLY).
 */
const val TERMS_VERSION   = GtSession.TERMS_VERSION
const val PRIVACY_VERSION = GtSession.PRIVACY_VERSION

object ConvoySessionManager {

    private fun s(ctx: Context) = GtSession(GtSettingsAndroid(ctx), GtSession.STORE, System::currentTimeMillis)

    fun getUserId(ctx: Context): String? = s(ctx).getUserId()
    fun isSignedIn(ctx: Context): Boolean = s(ctx).isSignedIn()
    fun isSubscribed(ctx: Context): Boolean = s(ctx).isSubscribed()
    fun isSubscriptionExpired(ctx: Context): Boolean = s(ctx).isSubscriptionExpired()
    fun termsAccepted(ctx: Context): Boolean = s(ctx).termsAccepted()
    fun privacyAccepted(ctx: Context): Boolean = s(ctx).privacyAccepted()
    fun isOrganizer(ctx: Context): Boolean = s(ctx).isOrganizer()
    fun getFirstName(ctx: Context): String = s(ctx).getFirstName()
    fun getEmail(ctx: Context): String = s(ctx).getEmail()
    fun getZipCode(ctx: Context): String = s(ctx).getZipCode()
    fun getCity(ctx: Context): String = s(ctx).getCity()
    fun setCity(ctx: Context, city: String) = s(ctx).setCity(city)
    fun getState(ctx: Context): String = s(ctx).getState()
    fun setState(ctx: Context, state: String) = s(ctx).setState(state)
    // Phase C: server-side ZIP lookup trigger -- on PUT /users/{id}, if zip_code provided and city/state blank, the server calls
    // api.zippopotam.us/us/{zip} and populates city/state automatically.
    fun getSearchRadius(ctx: Context): Int = s(ctx).getSearchRadius()
    fun setZipCode(ctx: Context, zip: String) { s(ctx).setZipCode(zip) }
    fun setSearchRadius(ctx: Context, miles: Int) { s(ctx).setSearchRadius(miles) }
    fun saveUser(ctx: Context, userId: String, googleId: String, email: String, firstName: String, lastName: String) =
        s(ctx).saveUser(userId, googleId, email, firstName, lastName)
    fun acceptTerms(ctx: Context) { s(ctx).acceptTerms() }
    fun acceptPrivacy(ctx: Context) { s(ctx).acceptPrivacy() }
    fun setOrganizer(ctx: Context, value: Boolean) { s(ctx).setOrganizer(value) }
    fun setSubscriptionExpiry(ctx: Context, expiresAtMs: Long) { s(ctx).setSubscriptionExpiry(expiresAtMs) }
    fun clearSession(ctx: Context) = s(ctx).clearSession()

    enum class LaunchRoute {
        CONVOY_MAP, SIGN_IN, TERMS, PRIVACY, SUBSCRIPTION, DASHBOARD
    }

    /** GtSession's route mapped by name onto this enum (the same six names, the same order) -- callers keep using this one. */
    fun resolveLaunchRoute(ctx: Context, hasInternet: Boolean): LaunchRoute =
        LaunchRoute.valueOf(s(ctx).resolveLaunchRoute(hasInternet).name)
}
