package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log

// ============================================================
// ConvoyDevSeeder.kt
// DEV ONLY — V3.0 Phase B
// Seeds SharedPreferences with realistic test data.
// Long-press GroupTrack header → Dev Menu → CLEAR + SEED
// ============================================================

private const val TAG = "ConvoyDevSeeder"
private const val PREFS_RIDES   = "grouptrack_rides"
private const val PREFS_NOTIF   = "grouptrack_notifications"
private const val PREFS_INVITES = "grouptrack_invites"
private const val PREFS_PUBLIC  = "grouptrack_public_rides"

object ConvoyDevSeeder {

    fun seed(ctx: Context) {
        seedUser(ctx)
        seedRides(ctx)
        seedInvites(ctx)
        seedNotifications(ctx)
        seedPublicRides(ctx)
        Log.i(TAG, "Dev data seeded — rides:${getRideCount(ctx)} invites:${getInviteCount(ctx)} notif:${getNotifCount(ctx)} pub:${getPublicRideCount(ctx)}")
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS_RIDES,   Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSharedPreferences(PREFS_NOTIF,   Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSharedPreferences(PREFS_INVITES, Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSharedPreferences(PREFS_PUBLIC,  Context.MODE_PRIVATE).edit().clear().apply()
        ConvoySessionManager.clearSession(ctx)
        Log.i(TAG, "Dev data cleared")
    }

    private fun seedUser(ctx: Context) {
        ConvoySessionManager.saveUser(ctx,
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
        ConvoySessionManager.setSearchRadius(ctx, 100)
    }

    private fun seedRides(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).edit()
        p.putString("ride_001_id", "ride-001")
        p.putString("ride_001_name", "Sunday Desert Run — Gooseberry Mesa")
        p.putString("ride_001_date", "April 13, 2026")
        p.putString("ride_001_status", "ORGANIZED")
        p.putString("ride_001_organizer", "Fred Dev")
        p.putString("ride_001_email", "fred@grouptrack.org")
        p.putInt("ride_001_enrolled", 4)

        p.putString("ride_002_id", "ride-002")
        p.putString("ride_002_name", "Wednesday Night Ride — Sand Hollow")
        p.putString("ride_002_date", "April 9, 2026")
        p.putString("ride_002_status", "ENROLLED")
        p.putString("ride_002_organizer", "Dave H")
        p.putString("ride_002_email", "dave.h@grouptrack.org")
        p.putInt("ride_002_enrolled", 6)

        p.putString("ride_003_id", "ride-003")
        p.putString("ride_003_name", "Field Test — Gooseberry Mesa")
        p.putString("ride_003_date", "April 4, 2026")
        p.putString("ride_003_status", "COMPLETED")
        p.putString("ride_003_organizer", "Fred Dev")
        p.putString("ride_003_email", "fred@grouptrack.org")
        p.putInt("ride_003_enrolled", 2)

        p.putInt("ride_count", 3)
        p.apply()
    }

    private fun seedInvites(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_INVITES, Context.MODE_PRIVATE).edit()
        p.putString("invite_001_ride", "Wednesday Night Ride — Sand Hollow")
        p.putString("invite_001_organizer", "Dave H")
        p.putString("invite_001_email", "dave.h@grouptrack.org")
        p.putString("invite_001_date", "April 9, 2026")
        p.putString("invite_001_token", "dev-token-001")
        p.putInt("invite_count", 1)
        p.apply()
    }

    private fun seedNotifications(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).edit()
        p.putString("notif_001_ride_id", "ride-001")
        p.putString("notif_001_ride", "Sunday Desert Run")
        p.putString("notif_001_msg", "4 of 5 riders enrolled")
        p.putString("notif_001_time", "2h ago")

        p.putString("notif_002_ride_id", "ride-001")
        p.putString("notif_002_ride", "Sunday Desert Run")
        p.putString("notif_002_msg", "Broadcast sent to 12 followers")
        p.putString("notif_002_time", "1d ago")

        p.putInt("notif_count", 2)
        p.apply()
    }

    private fun seedPublicRides(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_PUBLIC, Context.MODE_PRIVATE).edit()
        p.putString("pub_001_name", "Sand Hollow SxS Weekend Run")
        p.putString("pub_001_organizer", "Utah Off-Road Club")
        p.putString("pub_001_email", "info@utahoffroadclub.com")
        p.putString("pub_001_date", "April 12, 2026")
        p.putString("pub_001_distance", "8 mi")
        p.putBoolean("pub_001_invite_required", false)

        p.putString("pub_002_name", "Gooseberry Mesa Morning Ride")
        p.putString("pub_002_organizer", "Red Rock Riders")
        p.putString("pub_002_email", "rides@redrockriders.com")
        p.putString("pub_002_date", "April 19, 2026")
        p.putString("pub_002_distance", "12 mi")
        p.putBoolean("pub_002_invite_required", true)

        p.putInt("pub_count", 2)
        p.apply()
    }

    // ── Rides ──────────────────────────────────────────────
    fun getRideCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getInt("ride_count", 0)
    fun getRideName(ctx: Context, i: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getString("ride_00${i+1}_name", "") ?: ""
    fun getRideStatus(ctx: Context, i: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getString("ride_00${i+1}_status", "") ?: ""
    fun getRideDate(ctx: Context, i: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getString("ride_00${i+1}_date", "") ?: ""
    fun getRideOrganizer(ctx: Context, i: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getString("ride_00${i+1}_organizer", "") ?: ""
    fun getRideTime(ctx: Context, i: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getString("ride_00${i+1}_time", "") ?: ""
    fun getRideEmail(ctx: Context, i: Int): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getString("ride_00${i+1}_email", "") ?: ""
    fun getRideEnrolled(ctx: Context, i: Int): Int =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getInt("ride_00${i+1}_enrolled", 0)

    // ── Invites ────────────────────────────────────────────
    fun getInviteCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_INVITES, Context.MODE_PRIVATE).getInt("invite_count", 0)
    fun getInviteField(ctx: Context, i: Int, field: String): String =
        ctx.getSharedPreferences(PREFS_INVITES, Context.MODE_PRIVATE).getString("invite_00${i+1}_$field", "") ?: ""

    // ── Notifications ──────────────────────────────────────
    fun getNotifCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).getInt("notif_count", 0)
    fun getNotif(ctx: Context, i: Int, field: String): String =
        ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).getString("notif_00${i+1}_$field", "") ?: ""

    // ── Public Rides ───────────────────────────────────────
    fun getPublicRideCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_PUBLIC, Context.MODE_PRIVATE).getInt("pub_count", 0)
    fun getPublicRideField(ctx: Context, i: Int, field: String): String =
        ctx.getSharedPreferences(PREFS_PUBLIC, Context.MODE_PRIVATE).getString("pub_00${i+1}_$field", "") ?: ""
    fun getPublicRideInviteRequired(ctx: Context, i: Int): Boolean =
        ctx.getSharedPreferences(PREFS_PUBLIC, Context.MODE_PRIVATE).getBoolean("pub_00${i+1}_invite_required", false)
}
