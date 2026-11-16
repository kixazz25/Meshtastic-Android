package com.geeksville.mesh.convoy

import android.app.Activity
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.grouptrack.shared.log.gtLogger

// ============================================================
// ConvoyInviteHandler.kt
// Version 3 Phase A — NEW FILE
//
// Handles grouptrack.org/invite/{token} deep links.
// AndroidManifest intent-filter added in Version 3 Phase B Task W-01.
//
// Flow:
// 1. Extract token from intent URL
// 2. Call ConvoyApiClient.downloadRide(token)
// 3. Parse JSON → ConvoyEventConfig
// 4. Save via ConvoyEventStore.save() — identical to email import
// 5. Enroll rider via ConvoyApiClient.enrollRider()
// 6. Trigger map tile download if map_bounds present
// 7. Show result screen — success or error
//
// If user_id missing (not signed in):
//   Save ride to convoy_import/ for processing after sign-in.
//   Do not attempt enrollment until user_id available.
// ============================================================

private const val TAG = "ConvoyInviteHandler"
private val log = gtLogger(TAG)   // GTLOGKERMIT-2026-11-16: android.util.Log(TAG, ..) -- same tag, so the logs read as 2.7e's

class ConvoyInviteHandler : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) {
            log.w { "No URI in intent" }
            finish()
            return
        }

        log.i { "Invite deep link received: $uri" }

        // Extract token from path: /invite/{token}
        val token = uri.lastPathSegment
        if (token.isNullOrBlank()) {
            log.e { "No token in invite URL: $uri" }
            finish()
            return
        }

        log.i { "Processing invite token: $token" }

        CoroutineScope(Dispatchers.Main).launch {
            processInvite(token)
        }
    }

    private suspend fun processInvite(token: String) {
        // Step 1 — Download ride JSON from API
        val downloadResult = ConvoyApiClient.downloadRide(token)

        downloadResult.fold(
            onSuccess = { rideJson ->
                try {
                    // Step 2 — Parse JSON to ConvoyEventConfig
                    val json = JSONObject(rideJson)
                    val eventConfig = ConvoyEventConfig.fromJson(json)

                    // Step 3 — Save to convoy_events/ — identical to email import
                    ConvoyEventStore.save(this@ConvoyInviteHandler, eventConfig)
                    log.i { "Ride saved: ${eventConfig.eventName} (${eventConfig.eventId})" }

                    // Step 4 — Enroll rider if signed in
                    val userId = getUserId(this@ConvoyInviteHandler)
                    if (userId != null) {
                        val enrollResult = ConvoyApiClient.enrollRider(
                            userId = userId,
                            rideId = eventConfig.eventId
                        )
                        enrollResult.fold(
                            onSuccess = { log.i { "Enrolled in ride: ${eventConfig.eventId}" } },
                            onFailure = { log.w { "Enrollment failed — ride saved locally" } }
                        )
                    } else {
                        log.i { "No user_id — skipping enrollment. Ride saved locally." }
                    }

                    // Step 5 — Trigger map tile download if map bounds present
                    val mapBoundsNorth = json.optDouble("map_bounds_north", Double.NaN)
                    val mapBoundsSouth = json.optDouble("map_bounds_south", Double.NaN)
                    val mapBoundsEast  = json.optDouble("map_bounds_east",  Double.NaN)
                    val mapBoundsWest  = json.optDouble("map_bounds_west",  Double.NaN)

                    if (!mapBoundsNorth.isNaN() && !mapBoundsSouth.isNaN() &&
                        !mapBoundsEast.isNaN()  && !mapBoundsWest.isNaN()) {
                        log.i { "Map bounds found — queuing tile download" }
                        queueMapDownload(
                            this@ConvoyInviteHandler,
                            mapBoundsNorth, mapBoundsSouth,
                            mapBoundsEast,  mapBoundsWest,
                            json.optString("map_tile_source", "SAT"),
                            json.optInt("map_zoom_max", 18)
                        )
                    }

                    // Step 6 — Done. User will see the ride in RIDE tab on next app open.
                    log.i { "Invite processing complete: ${eventConfig.eventName}" }

                } catch (e: Exception) {
                    log.e { "Failed to parse ride JSON: ${e.message}" }
                }
                finish()
            },
            onFailure = { e ->
                log.e { "Failed to download ride: ${e.message}" }
                finish()
            }
        )
    }

    companion object {

        // ── Queue map tile download ───────────────────────────────────────────
        // Saves pending download params to SharedPreferences.
        // ConvoyScreen picks these up on next launch and starts the download.
        fun queueMapDownload(
            context: Context,
            north: Double, south: Double,
            east: Double,  west: Double,
            tileSource: String,
            zoomMax: Int
        ) {
            context.getSharedPreferences("grouptrack_pending_download", Context.MODE_PRIVATE)
                .edit().apply {
                    putFloat("pending_north",    north.toFloat())
                    putFloat("pending_south",    south.toFloat())
                    putFloat("pending_east",     east.toFloat())
                    putFloat("pending_west",     west.toFloat())
                    putString("pending_source",  tileSource)
                    putInt("pending_zoom_max",   zoomMax)
                    putBoolean("pending_download", true)
                    apply()
                }
            log.i { "Map download queued: $tileSource z$zoomMax [$north,$south,$east,$west]" }
        }

        // ── Check and clear pending download ─────────────────────────────────
        // Called from ConvoyScreen on launch to pick up queued downloads.
        fun getPendingDownload(context: Context): PendingMapDownload? {
            val prefs = context.getSharedPreferences("grouptrack_pending_download", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("pending_download", false)) return null
            return PendingMapDownload(
                north      = prefs.getFloat("pending_north", 0f).toDouble(),
                south      = prefs.getFloat("pending_south", 0f).toDouble(),
                east       = prefs.getFloat("pending_east",  0f).toDouble(),
                west       = prefs.getFloat("pending_west",  0f).toDouble(),
                tileSource = prefs.getString("pending_source", "SAT") ?: "SAT",
                zoomMax    = prefs.getInt("pending_zoom_max", 18)
            )
        }

        fun clearPendingDownload(context: Context) {
            context.getSharedPreferences("grouptrack_pending_download", Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }
}

data class PendingMapDownload(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val tileSource: String,
    val zoomMax: Int
)
