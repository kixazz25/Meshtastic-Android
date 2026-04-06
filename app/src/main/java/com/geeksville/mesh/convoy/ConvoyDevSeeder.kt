package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log

// ============================================================
// ConvoyDevSeeder.kt
// DEV ONLY — V3.0 Phase B
// Seeds SharedPreferences with realistic test data so all
// screens can be navigated and polished without a live backend.
// Called from ConvoySignInScreen dev toggle.
// NEVER ship with V3_FEATURES_ENABLED = false.
// ============================================================

private const val TAG = "ConvoyDevSeeder"
private const val PREFS_RIDES = "grouptrack_rides"
private const val PREFS_NOTIF = "grouptrack_notifications"

object ConvoyDevSeeder {

    fun seed(ctx: Context) {
        seedUser(ctx)
        seedRides(ctx)
        seedNotifications(ctx)
        Log.i(TAG, "Dev data seeded")
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).edit().clear().apply()
        ConvoySessionManager.clearSession(ctx)
        Log.i(TAG, "Dev data cleared")
    }

    private fun seedUser(ctx: Context) {
        ConvoySessionManager.saveUser(
            ctx,
            userId    = "dev-user-001",
            googleId  = "google-dev-001",
            email     = "fred@grouptrack.org",
            firstName = "Fred",
            lastName  = "Dev"
        )
        ConvoySessionManager.acceptTerms(ctx)
        ConvoySessionManager.acceptPrivacy(ctx)
        ConvoySessionManager.setSubscriptionExpiry(ctx, System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
        ConvoySessionManager.setOrganizer(ctx, true)
        ConvoySessionManager.setZipCode(ctx, "84770")
        ConvoySessionManager.setSearchRadius(ctx, 25)
    }

    private fun seedRides(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).edit()

        // Ride 1 — Organized upcoming
        prefs.putString("ride_001_id", "ride-001")
        prefs.putString("ride_001_name", "Sunday Desert Run — Gooseberry Mesa")
        prefs.putString("ride_001_date", "April 13, 2026")
        prefs.putString("ride_001_status", "ORGANIZED")
        prefs.putString("ride_001_organizer", "Fred Dev")
        prefs.putInt("ride_001_enrolled", 4)

        // Ride 2 — Enrolled upcoming
        prefs.putString("ride_002_id", "ride-002")
        prefs.putString("ride_002_name", "Wednesday Night Ride — Sand Hollow")
        prefs.putString("ride_002_date", "April 9, 2026")
        prefs.putString("ride_002_status", "ENROLLED")
        prefs.putString("ride_002_organizer", "Dave H")
        prefs.putInt("ride_002_enrolled", 6)

        // Ride 3 — Completed
        prefs.putString("ride_003_id", "ride-003")
        prefs.putString("ride_003_name", "Field Test — Gooseberry Mesa")
        prefs.putString("ride_003_date", "April 4, 2026")
        prefs.putString("ride_003_status", "COMPLETED")
        prefs.putString("ride_003_organizer", "Fred Dev")
        prefs.putInt("ride_003_enrolled", 2)

        prefs.putInt("ride_count", 3)
        prefs.apply()
    }

    private fun seedPublicRides(ctx: Context) {
        val prefs = ctx.getSharedPreferences("grouptrack_public_rides", Context.MODE_PRIVATE).edit()
        prefs.putString("pub_001_name", "Sand Hollow SxS Weekend Run")
        prefs.putString("pub_001_organizer", "Utah Off-Road Club")
        prefs.putString("pub_001_email", "info@utahoffroadclub.com")
        prefs.putString("pub_001_date", "April 12, 2026")
        prefs.putString("pub_001_distance", "8 mi")
        prefs.putString("pub_002_name", "Gooseberry Mesa Morning Ride")
        prefs.putString("pub_002_organizer", "Red Rock Riders")
        prefs.putString("pub_002_email", "rides@redrockriders.com")
        prefs.putString("pub_002_date", "April 19, 2026")
        prefs.putString("pub_002_distance", "12 mi")
        prefs.putInt("pub_count", 2)
        prefs.apply()
    }
    fun getPublicRideCount(ctx: Context): Int =
        ctx.getSharedPreferences("grouptrack_public_rides", Context.MODE_PRIVATE).getInt("pub_count", 0)
    fun getPublicRideField(ctx: Context, index: Int, field: String): String =
        ctx.getSharedPreferences("grouptrack_public_rides", Context.MODE_PRIVATE)
            .getString("pub_00${index+1}_$field", "") ?: ""
    private fun seedNotifications(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).edit()
        prefs.putString("notif_001", "Dave H invited you to Wednesday Night Ride")
        prefs.putString("notif_002", "Sunday Desert Run — 3 riders enrolled")
        prefs.putInt("notif_count", 2)
        prefs.apply()
    }

    fun getRideCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getInt("ride_count", 0)

    fun getRideName(ctx: Context, index: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE)
            .getString("ride_00_name", "") ?: ""

    fun getRideStatus(ctx: Context, index: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE)
            .getString("ride_00_status", "") ?: ""

    fun getRideDate(ctx: Context, index: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE)
            .getString("ride_00_date", "") ?: ""

    fun getRideOrganizer(ctx: Context, index: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE)
            .getString("ride_00_organizer", "") ?: ""

    fun getNotifCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).getInt("notif_count", 0)

    fun getNotif(ctx: Context, index: Int): String =
        ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE)
            .getString("notif_00", "") ?: ""
}
