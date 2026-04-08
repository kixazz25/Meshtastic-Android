package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log

// ============================================================
// ConvoyEnrollmentQueue.kt
// V3.0 Phase B — Background async enrollment task queue.
// All functions are STUBS. Phase C wires actual API calls.
// Triggers: app startup, JSON receipt, ride accept.
// NO SCREENS — status surfaces on ride row icons.
// ============================================================

object ConvoyEnrollmentQueue {

    private const val TAG = "ConvoyEnrollmentQueue"

    // Called on every app startup with internet connection
    // Phase C: GET /users/pending_invites → distribute JSON for any undistributed invites
    fun checkPendingDistributions(ctx: Context) {
        Log.d(TAG, "checkPendingDistributions — stub, Phase C")
        // Phase C implementation:
        // val response = ConvoyApiClient.getPendingInvites(userId)
        // response.forEach { invite -> distributeRideJson(ctx, invite.token) }
    }

    // Called after JSON receipt — starts map tile download
    // Phase C: ConvoyTileDownloader.downloadTiles(bounds)
    fun queueMapDownload(ctx: Context, rideId: String, boundsNorth: Double, boundsSouth: Double, boundsEast: Double, boundsWest: Double) {
        Log.d(TAG, "queueMapDownload — rideId=$rideId stub, Phase C")
        setEnrollmentStatus(ctx, rideId, "maps", "PENDING")
        // Phase C implementation:
        // ConvoyTileDownloader.downloadTiles(boundsNorth, boundsSouth, boundsEast, boundsWest)
        //     .onProgress { setEnrollmentStatus(ctx, rideId, "maps", "DOWNLOADING") }
        //     .onComplete { setEnrollmentStatus(ctx, rideId, "maps", "COMPLETE") }
    }

    // Called on app startup if maps are pending and connection available
    fun processMapQueue(ctx: Context) {
        Log.d(TAG, "processMapQueue — stub, Phase C")
        // Phase C: check all enrollment_{rideId}_maps_status = PENDING/DOWNLOADING
        // resume downloads
    }

    // Called on JSON receipt — flags radio config as pending
    fun setRadioConfigPending(ctx: Context, rideId: String) {
        Log.d(TAG, "setRadioConfigPending — rideId=$rideId")
        setEnrollmentStatus(ctx, rideId, "radio", "PENDING")
        // Field Radio screen reads this flag and shows APPLY CONFIG prompt
    }

    // Called when rider applies radio config in Field Radio screen
    fun setRadioConfigApplied(ctx: Context, rideId: String) {
        Log.d(TAG, "setRadioConfigApplied — rideId=$rideId")
        setEnrollmentStatus(ctx, rideId, "radio", "APPLIED")
    }

    // Called on ACCEPT tap — CAL-01 stub
    fun addRideToCalendar(ctx: Context, rideName: String, date: String, time: String, location: String = "") {
        Log.d(TAG, "addRideToCalendar — stub CAL-01, Phase C")
        android.widget.Toast.makeText(ctx, "Calendar entry — Phase C", android.widget.Toast.LENGTH_SHORT).show()
        // Phase C implementation:
        // val intent = Intent(Intent.ACTION_INSERT).apply {
        //     data = CalendarContract.Events.CONTENT_URI
        //     putExtra(CalendarContract.Events.TITLE, rideName)
        //     putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        // }
        // ctx.startActivity(intent)
    }

    // Called on first ride save — sets organizer status
    fun postOrganizerStatus(ctx: Context) {
        Log.d(TAG, "postOrganizerStatus — stub, Phase C")
        ConvoySessionManager.setOrganizer(ctx, true)
        // Phase C implementation:
        // ConvoyApiClient.postOrganizerStatus(userId)
    }

    // Called on app startup — uploads any queued ride surveys
    fun processSurveyQueue(ctx: Context) {
        Log.d(TAG, "processSurveyQueue — stub, Phase C")
        // Phase C: check SharedPrefs for queued surveys
        // POST /ride/survey for each, clear on success
    }

    // Follow/unfollow organizer stubs
    fun followOrganizer(ctx: Context, organizerId: String, organizerName: String) {
        Log.d(TAG, "followOrganizer — organizerId=$organizerId stub FOLLOW-01, Phase C")
        android.widget.Toast.makeText(ctx, "Following $organizerName", android.widget.Toast.LENGTH_SHORT).show()
        // Phase C: ConvoyApiClient.postFollow(userId, organizerId)
    }

    fun unfollowOrganizer(ctx: Context, organizerId: String, organizerName: String) {
        Log.d(TAG, "unfollowOrganizer — organizerId=$organizerId stub FOLLOW-01, Phase C")
        android.widget.Toast.makeText(ctx, "Unfollowed $organizerName", android.widget.Toast.LENGTH_SHORT).show()
        // Phase C: ConvoyApiClient.deleteFollow(userId, organizerId)
    }

    // SharedPrefs task status helpers
    private fun setEnrollmentStatus(ctx: Context, rideId: String, task: String, status: String) {
        ctx.getSharedPreferences("grouptrack_enrollment_queue", Context.MODE_PRIVATE)
            .edit().putString("enrollment_${rideId}_${task}_status", status).apply()
    }

    fun getEnrollmentStatus(ctx: Context, rideId: String, task: String): String {
        return ctx.getSharedPreferences("grouptrack_enrollment_queue", Context.MODE_PRIVATE)
            .getString("enrollment_${rideId}_${task}_status", "PENDING") ?: "PENDING"
    }
}
