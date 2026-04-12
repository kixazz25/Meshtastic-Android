package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log

// ============================================================
// ConvoyDevSeeder.kt
// DEV ONLY — V3.0 Phase B
// Complete test data set covering all dashboard sections.
// ============================================================

private const val TAG          = "ConvoyDevSeeder"
private const val PREFS_RIDES  = "grouptrack_rides"
private const val PREFS_NOTIF  = "grouptrack_notifications"
private const val PREFS_INVITE = "grouptrack_invites"
private const val PREFS_PUBLIC = "grouptrack_public_rides"

object ConvoyDevSeeder {

    fun seed(ctx: Context) {
        seedUser(ctx)
        seedNotifications(ctx)
        seedInvites(ctx)
        seedRides(ctx)
        seedPublicRides(ctx)
        Log.i(TAG, "Seeded — notif:${getNotifCount(ctx)} invites:${getInviteCount(ctx)} rides:${getRideCount(ctx)} pub:${getPublicRideCount(ctx)}")
    }

    fun clear(ctx: Context) {
        listOf(PREFS_RIDES, PREFS_NOTIF, PREFS_INVITE, PREFS_PUBLIC).forEach {
            ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply()
        }
        Log.i(TAG, "Dev data cleared")
    }

    fun getRideCount(ctx: Context)       = ctx.getSharedPreferences(PREFS_RIDES,  Context.MODE_PRIVATE).getInt("ride_count", 0)
    fun getNotifCount(ctx: Context)      = ctx.getSharedPreferences(PREFS_NOTIF,  Context.MODE_PRIVATE).getInt("notif_count", 0)
    fun getInviteCount(ctx: Context)     = ctx.getSharedPreferences(PREFS_INVITE, Context.MODE_PRIVATE).getInt("invite_count", 0)
    fun getPublicRideCount(ctx: Context) = ctx.getSharedPreferences(PREFS_PUBLIC, Context.MODE_PRIVATE).getInt("pub_count", 0)

    fun getRideField(ctx: Context, i: Int, field: String): String =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getString("ride_${pad(i)}_$field", "") ?: ""
    fun getRideInt(ctx: Context, i: Int, field: String): Int =
        ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).getInt("ride_${pad(i)}_$field", 0)

    // Legacy compat
    fun getRideName(ctx: Context, i: Int)      = getRideField(ctx, i, "name")
    fun getRideStatus(ctx: Context, i: Int)    = getRideField(ctx, i, "my_status")
    fun getRideDate(ctx: Context, i: Int)      = getRideField(ctx, i, "date")
    fun getRideOrganizer(ctx: Context, i: Int) = getRideField(ctx, i, "organizer")
    fun getRideEmail(ctx: Context, i: Int)     = getRideField(ctx, i, "email")
    fun getRideEnrolled(ctx: Context, i: Int)  = getRideInt(ctx, i, "enrolled")
    fun getRideTime(ctx: Context, i: Int)      = getRideField(ctx, i, "time")

    fun getNotif(ctx: Context, i: Int, field: String): String =
        ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).getString("notif_${pad(i)}_$field", "") ?: ""
    fun getNotifInt(ctx: Context, i: Int, field: String): Int =
        ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).getInt("notif_${pad(i)}_$field", 0)

    fun getInviteField(ctx: Context, i: Int, field: String): String =
        ctx.getSharedPreferences(PREFS_INVITE, Context.MODE_PRIVATE).getString("invite_${pad(i)}_$field", "") ?: ""

    fun getPublicRideField(ctx: Context, i: Int, field: String): String =
        ctx.getSharedPreferences(PREFS_PUBLIC, Context.MODE_PRIVATE).getString("pub_${pad(i)}_$field", "") ?: ""
    fun getPublicRideInviteRequired(ctx: Context, i: Int): Boolean =
        ctx.getSharedPreferences(PREFS_PUBLIC, Context.MODE_PRIVATE).getBoolean("pub_${pad(i)}_invite_required", false)

    private fun pad(i: Int) = String.format("%03d", i + 1)

    // ── Seed User ─────────────────────────────────────────────────────────────
    private fun seedUser(ctx: Context) {
        ConvoySessionManager.saveUser(ctx,
            userId = "dev-user-001", googleId = "google-dev-001",
            email = "fred@grouptrack.org", firstName = "Fred", lastName = "Dev")
        ConvoySessionManager.acceptTerms(ctx)
        ConvoySessionManager.acceptPrivacy(ctx)
        ConvoySessionManager.setSubscriptionExpiry(ctx, System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
        ConvoySessionManager.setOrganizer(ctx, true)
        ConvoySessionManager.setZipCode(ctx, "84770")
        ConvoySessionManager.setSearchRadius(ctx, 100)
    }

    // ── Seed Notifications (organizer enrollment tracking) ────────────────────
    private fun seedNotifications(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE).edit()

        // Notification 1 — active organized ride, open
        p.putString("notif_001_ride_id", "ride-001")
        p.putString("notif_001_ride", "Sunday Desert Run — Gooseberry Mesa")
        p.putString("notif_001_organizer_id", "dev-user-001")
        p.putString("notif_001_visibility", "PRIVATE")
        p.putString("notif_001_ride_status", "OPEN")
        p.putString("notif_001_date", "April 13, 2026")
        p.putString("notif_001_trailhead", "Gooseberry Trailhead")
        p.putString("notif_001_location", "Springdale, UT")
        p.putString("notif_001_arrive_time", "7:30 AM")
        p.putString("notif_001_depart_time", "8:00 AM")
        p.putString("notif_001_description", "Moderate terrain, UTV recommended. Bring water and snacks. Meet at staging area.")
        p.putInt("notif_001_invited", 8)
        p.putInt("notif_001_accepted", 5)
        p.putInt("notif_001_maybe", 2)
        p.putInt("notif_001_declined", 1)
        // Accepted riders
        p.putString("notif_001_accepted_001_name", "Dave H"); p.putString("notif_001_accepted_001_callsign", "DELTA-4"); p.putString("notif_001_accepted_001_vehicle", "RZR Pro XP")
        p.putString("notif_001_accepted_002_name", "Mike T"); p.putString("notif_001_accepted_002_callsign", "MIKE-7"); p.putString("notif_001_accepted_002_vehicle", "Can-Am Maverick")
        p.putString("notif_001_accepted_003_name", "Sarah K"); p.putString("notif_001_accepted_003_callsign", "SIERRA-3"); p.putString("notif_001_accepted_003_vehicle", "Polaris General")
        p.putString("notif_001_accepted_004_name", "Tom R"); p.putString("notif_001_accepted_004_callsign", "TANGO-2"); p.putString("notif_001_accepted_004_vehicle", "RZR Turbo R")
        p.putString("notif_001_accepted_005_name", "Lisa M"); p.putString("notif_001_accepted_005_callsign", "LIMA-1"); p.putString("notif_001_accepted_005_vehicle", "Can-Am Defender")
        // Maybe riders
        p.putString("notif_001_maybe_001_name", "Chris B"); p.putString("notif_001_maybe_001_callsign", "CHARLIE-5"); p.putString("notif_001_maybe_001_vehicle", "RZR 1000")
        p.putString("notif_001_maybe_002_name", "Ann W"); p.putString("notif_001_maybe_002_callsign", "ALPHA-8"); p.putString("notif_001_maybe_002_vehicle", "Polaris RZR")
        // Declined riders
        p.putString("notif_001_declined_001_name", "Bob L"); p.putString("notif_001_declined_001_callsign", "BRAVO-6"); p.putString("notif_001_declined_001_vehicle", "Can-Am X3")

        // Notification 2 — second organized ride, closed (full)
        p.putString("notif_002_ride_id", "ride-005")
        p.putString("notif_002_ride", "Hurricane Cliffs Bash")
        p.putString("notif_002_organizer_id", "dev-user-001")
        p.putString("notif_002_visibility", "PUBLIC")
        p.putString("notif_002_ride_status", "CLOSED")
        p.putString("notif_002_date", "April 22, 2026")
        p.putString("notif_002_trailhead", "Hurricane Trailhead")
        p.putString("notif_002_location", "Hurricane, UT")
        p.putString("notif_002_arrive_time", "6:30 AM")
        p.putString("notif_002_depart_time", "7:00 AM")
        p.putString("notif_002_description", "Full day ride. Hard terrain. Experienced riders only. Ride is now full.")
        p.putInt("notif_002_invited", 20)
        p.putInt("notif_002_accepted", 20)
        p.putInt("notif_002_maybe", 0)
        p.putInt("notif_002_declined", 0)

        p.putInt("notif_count", 2)
        p.apply()
    }

    // ── Seed Invites ──────────────────────────────────────────────────────────
    private fun seedInvites(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_INVITE, Context.MODE_PRIVATE).edit()

        p.putString("invite_001_ride_id", "ride-ext-001")
        p.putString("invite_001_ride", "Wednesday Night Ride — Sand Hollow")
        p.putString("invite_001_organizer", "Dave H")
        p.putString("invite_001_organizer_id", "org-dave-001")
        p.putString("invite_001_email", "dave.h@grouptrack.org")
        p.putString("invite_001_date", "April 16, 2026")
        p.putString("invite_001_time", "6:00 PM")
        p.putString("invite_001_visibility", "PRIVATE")
        p.putString("invite_001_ride_status", "OPEN")
        p.putString("invite_001_trailhead", "Sand Hollow Trailhead")
        p.putString("invite_001_location", "Hurricane, UT")
        p.putString("invite_001_my_status", "INVITED")

        p.putString("invite_002_ride_id", "ride-ext-002")
        p.putString("invite_002_ride", "Zion Narrows Approach")
        p.putString("invite_002_organizer", "Red Rock Riders")
        p.putString("invite_002_organizer_id", "org-rrr-001")
        p.putString("invite_002_email", "rides@redrockriders.com")
        p.putString("invite_002_date", "April 20, 2026")
        p.putString("invite_002_time", "7:00 AM")
        p.putString("invite_002_visibility", "PUBLIC")
        p.putString("invite_002_ride_status", "OPEN")
        p.putString("invite_002_trailhead", "Zion Canyon Trailhead")
        p.putString("invite_002_location", "Springdale, UT")
        p.putString("invite_002_my_status", "MAYBE")

        p.putInt("invite_count", 2)
        p.apply()
    }

    // ── Seed My Rides ─────────────────────────────────────────────────────────
    private fun seedRides(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_RIDES, Context.MODE_PRIVATE).edit()

        // Ride 1 — Organized, private, open
        p.putString("ride_001_id", "ride-001")
        p.putString("ride_001_name", "Sunday Desert Run — Gooseberry Mesa")
        p.putString("ride_001_date", "April 13, 2026")
        p.putString("ride_001_time", "8:00 AM")
        p.putString("ride_001_my_status", "ORGANIZED")
        p.putString("ride_001_ride_status", "OPEN")
        p.putString("ride_001_visibility", "PRIVATE")
        p.putString("ride_001_organizer", "Fred Dev")
        p.putString("ride_001_organizer_id", "dev-user-001")
        p.putString("ride_001_email", "fred@grouptrack.org")
        p.putString("ride_001_trailhead", "Gooseberry Trailhead")
        p.putString("ride_001_location", "Springdale, UT")
        p.putInt("ride_001_enrolled", 5)

        // Ride 2 — Enrolled/Accepted, public, open
        p.putString("ride_002_id", "ride-002")
        p.putString("ride_002_name", "Wednesday Night Ride — Sand Hollow")
        p.putString("ride_002_date", "April 16, 2026")
        p.putString("ride_002_time", "6:00 PM")
        p.putString("ride_002_my_status", "ACCEPTED")
        p.putString("ride_002_ride_status", "OPEN")
        p.putString("ride_002_visibility", "PUBLIC")
        p.putString("ride_002_organizer", "Dave H")
        p.putString("ride_002_organizer_id", "org-dave-001")
        p.putString("ride_002_email", "dave.h@grouptrack.org")
        p.putString("ride_002_trailhead", "Sand Hollow Trailhead")
        p.putString("ride_002_location", "Hurricane, UT")
        p.putInt("ride_002_enrolled", 6)

        // Ride 3 — Maybe, private, open
        p.putString("ride_003_id", "ride-003")
        p.putString("ride_003_name", "Zion Narrows Approach")
        p.putString("ride_003_date", "April 20, 2026")
        p.putString("ride_003_time", "7:00 AM")
        p.putString("ride_003_my_status", "MAYBE")
        p.putString("ride_003_ride_status", "OPEN")
        p.putString("ride_003_visibility", "PUBLIC")
        p.putString("ride_003_organizer", "Red Rock Riders")
        p.putString("ride_003_organizer_id", "org-rrr-001")
        p.putString("ride_003_email", "rides@redrockriders.com")
        p.putString("ride_003_trailhead", "Zion Canyon Trailhead")
        p.putString("ride_003_location", "Springdale, UT")
        p.putInt("ride_003_enrolled", 12)

        // Ride 4 — Organized, public, pending
        p.putString("ride_004_id", "ride-004")
        p.putString("ride_004_name", "Escalante Canyons — May Run")
        p.putString("ride_004_date", "May 3, 2026")
        p.putString("ride_004_time", "TBD")
        p.putString("ride_004_my_status", "ORGANIZED")
        p.putString("ride_004_ride_status", "PENDING")
        p.putString("ride_004_visibility", "PUBLIC")
        p.putString("ride_004_organizer", "Fred Dev")
        p.putString("ride_004_organizer_id", "dev-user-001")
        p.putString("ride_004_email", "fred@grouptrack.org")
        p.putString("ride_004_trailhead", "")
        p.putString("ride_004_location", "Escalante, UT")
        p.putInt("ride_004_enrolled", 0)

        // Ride 5 — Organized, public, closed (full)
        p.putString("ride_005_id", "ride-005")
        p.putString("ride_005_name", "Hurricane Cliffs Bash")
        p.putString("ride_005_date", "April 22, 2026")
        p.putString("ride_005_time", "7:00 AM")
        p.putString("ride_005_my_status", "ORGANIZED")
        p.putString("ride_005_ride_status", "CLOSED")
        p.putString("ride_005_visibility", "PUBLIC")
        p.putString("ride_005_organizer", "Fred Dev")
        p.putString("ride_005_organizer_id", "dev-user-001")
        p.putString("ride_005_email", "fred@grouptrack.org")
        p.putString("ride_005_trailhead", "Hurricane Trailhead")
        p.putString("ride_005_location", "Hurricane, UT")
        p.putInt("ride_005_enrolled", 20)

        p.putInt("ride_count", 5)
        p.apply()
    }

    // ── Seed Public Rides Near Me ──────────────────────────────────────────────
    private fun seedPublicRides(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS_PUBLIC, Context.MODE_PRIVATE).edit()

        fun pub(i: Int, name: String, org: String, orgId: String, email: String,
                date: String, time: String, dist: String, location: String,
                trailhead: String, status: String) {
            val k = "pub_${String.format("%03d", i)}_"
            p.putString("${k}name", name); p.putString("${k}organizer", org)
            p.putString("${k}organizer_id", orgId); p.putString("${k}email", email)
            p.putString("${k}date", date); p.putString("${k}time", time)
            p.putString("${k}distance_miles", dist); p.putString("${k}location", location)
            p.putString("${k}trailhead", trailhead); p.putString("${k}ride_status", status)
            p.putString("${k}visibility", "PUBLIC")
            p.putBoolean("${k}invite_required", false)
        }

        // Within 25 miles of 84770 (St. George, UT)
        pub(1, "Sand Hollow OHV Blast", "Utah Off-Road Club", "org-uorc-001",
            "info@utahoffroadclub.com", "April 14, 2026", "8:00 AM",
            "8", "Hurricane, UT", "Sand Hollow Trailhead", "OPEN")

        pub(2, "Gooseberry Mesa Morning Run", "Red Rock Riders", "org-rrr-001",
            "rides@redrockriders.com", "April 19, 2026", "7:30 AM",
            "12", "Springdale, UT", "Gooseberry Trailhead", "OPEN")

        pub(3, "Hurricane Cliffs Trail Run", "Desert Crawlers", "org-dc-001",
            "info@desertcrawlers.com", "April 26, 2026", "9:00 AM",
            "18", "Hurricane, UT", "Hurricane Cliffs TH", "OPEN")

        // Within 50 miles
        pub(4, "Zion Narrows Approach", "Zion Adventure Co", "org-zac-001",
            "rides@zionadventure.com", "April 21, 2026", "6:30 AM",
            "35", "Springdale, UT", "Zion Canyon Trailhead", "OPEN")

        pub(5, "Cedar Breaks Trail Run", "Cedar City Riders", "org-ccr-001",
            "info@cedarcityriders.com", "April 28, 2026", "8:00 AM",
            "42", "Cedar City, UT", "Cedar Breaks TH", "OPEN")

        // Within 100 miles
        pub(6, "Bryce Canyon Rim Ride", "Southern Utah OHV", "org-suohv-001",
            "rides@southernutahohv.com", "May 2, 2026", "7:00 AM",
            "78", "Bryce Canyon, UT", "Bryce Canyon TH", "OPEN")

        pub(7, "Escalante Canyons Loop", "Canyon Country Club", "org-ccc-001",
            "info@canyoncountryclub.com", "May 9, 2026", "8:00 AM",
            "88", "Escalante, UT", "Escalante TH", "OPEN")

        pub(8, "Capitol Reef Loop", "Wayne County Riders", "org-wcr-001",
            "info@waynecountyriders.com", "May 16, 2026", "7:30 AM",
            "95", "Torrey, UT", "Capitol Reef TH", "CLOSED")

        // Within 125 miles
        pub(9, "Moab Slickrock Classic", "Moab Trail Alliance", "org-mta-001",
            "rides@moabtrailalliance.com", "May 3, 2026", "6:00 AM",
            "112", "Moab, UT", "Slickrock Trailhead", "OPEN")

        pub(10, "Canyonlands Maze Run", "Four Corners OHV", "org-fcohv-001",
            "info@fourcornersohv.com", "May 10, 2026", "7:00 AM",
            "118", "Moab, UT", "Canyonlands TH", "OPEN")

        pub(11, "Arches Rock Crawl", "Moab Rock Crawlers", "org-mrc-001",
            "info@moabrockcrawlers.com", "May 17, 2026", "8:00 AM",
            "122", "Moab, UT", "Arches TH", "OPEN")

        p.putInt("pub_count", 11)
        p.apply()
    }
}
