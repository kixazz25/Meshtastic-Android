package com.geeksville.mesh.convoy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ============================================================
// ConvoyApiClient.kt
// Version 3 Phase A — NEW FILE
// OkHttp wrapper for all GroupTrack backend API endpoints.
// Not wired to any ViewModel or screen until Version 3 Phase B.
//
// All functions are suspend functions returning Result<T>.
// Callers handle success/failure — no exceptions propagate.
//
// Base URL: ConvoyConfig.API_BASE_URL = http://34.224.89.217/
// ============================================================

object ConvoyApiClient {

    private const val TAG = "ConvoyApiClient"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── POST /users/register ──────────────────────────────────────────────────
    // Called after Google Sign-In succeeds.
    // Returns user_id UUID on success.
    suspend fun registerUser(
        googleId: String,
        email: String,
        firstName: String,
        lastName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("google_id", googleId)
                put("email", email)
                put("first_name", firstName)
                put("last_name", lastName)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}users/register")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "registerUser: ${response.code} $responseBody")

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val userId = json.optString("user_id", "")
                if (userId.isNotEmpty()) Result.success(userId)
                else Result.failure(Exception("No user_id in response"))
            } else {
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerUser error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── POST /users/accept-terms ──────────────────────────────────────────────
    // Called after rider accepts terms on first launch.
    suspend fun acceptTerms(
        userId: String,
        riderVersion: String,
        privacyVersion: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
                put("rider_version", riderVersion)
                put("privacy_version", privacyVersion)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}users/accept-terms")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "acceptTerms: ${response.code}")
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Log.e(TAG, "acceptTerms error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── POST /users/become-organizer ─────────────────────────────────────────
    // Called after organizer accepts organizer agreement.
    suspend fun becomeOrganizer(
        userId: String,
        termsVersion: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
                put("terms_version", termsVersion)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}users/become-organizer")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "becomeOrganizer: ${response.code}")
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Log.e(TAG, "becomeOrganizer error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── GET /terms/{type} ─────────────────────────────────────────────────────
    // Fetches current terms text and version for display.
    // type: "rider", "privacy", "organizer"
    suspend fun getTerms(type: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}terms/$type")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "getTerms[$type]: ${response.code}")

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val version = json.optString("version", "1.0")
                val text = json.optString("terms_text", "")
                Result.success(Pair(version, text))
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTerms error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── POST /rides ───────────────────────────────────────────────────────────
    // Creates a new ride in RDS. Returns ride_id.
    suspend fun createRide(
        userId: String,
        rideName: String,
        channelName: String,
        channelPsk: String,
        rideDate: String,
        description: String = "",
        zipCode: String = "",
        isPublic: Boolean = false,
        mapBoundsNorth: Double? = null,
        mapBoundsSouth: Double? = null,
        mapBoundsEast: Double? = null,
        mapBoundsWest: Double? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
                put("ride_name", rideName)
                put("channel_name", channelName)
                put("channel_psk", channelPsk)
                put("ride_date", rideDate)
                put("description", description)
                put("zip_code", zipCode)
                put("is_public", isPublic)
                mapBoundsNorth?.let { put("map_bounds_north", it) }
                mapBoundsSouth?.let { put("map_bounds_south", it) }
                mapBoundsEast?.let { put("map_bounds_east", it) }
                mapBoundsWest?.let { put("map_bounds_west", it) }
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}rides")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "createRide: ${response.code} $responseBody")

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val rideId = json.optString("ride_id", "")
                if (rideId.isNotEmpty()) Result.success(rideId)
                else Result.failure(Exception("No ride_id in response"))
            } else {
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createRide error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── POST /rides/{id}/invite ───────────────────────────────────────────────
    // Generates an invite token. Returns full invite URL.
    suspend fun createInvite(
        userId: String,
        rideId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}rides/$rideId/invite")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "createInvite: ${response.code} $responseBody")

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val inviteUrl = json.optString("invite_url", "")
                if (inviteUrl.isNotEmpty()) Result.success(inviteUrl)
                else Result.failure(Exception("No invite_url in response"))
            } else {
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createInvite error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── GET /ride/{token} ─────────────────────────────────────────────────────
    // Rider downloads ride JSON via invite link token.
    // Returns raw JSON string — caller saves to convoy_events/
    suspend fun downloadRide(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}ride/$token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "downloadRide: ${response.code} ${responseBody.take(100)}")

            if (response.isSuccessful) Result.success(responseBody)
            else Result.failure(Exception("HTTP ${response.code}: $responseBody"))
        } catch (e: Exception) {
            Log.e(TAG, "downloadRide error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── POST /enroll ──────────────────────────────────────────────────────────
    // Rider enrolls in ride after downloading.
    suspend fun enrollRider(
        userId: String,
        rideId: String,
        callsign: String = "",
        vehicleType: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
                put("ride_id", rideId)
                put("callsign", callsign)
                put("vehicle_type", vehicleType)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}enroll")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "enrollRider: ${response.code}")
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Log.e(TAG, "enrollRider error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── POST /ride/survey ─────────────────────────────────────────────────────
    // Post-ride survey + optional track donation.
    // trackDonation: null if rider skips upload
    suspend fun submitSurvey(
        userId: String,
        rideId: String,
        rating: Int?,
        notes: String,
        trackDonation: TrackDonation? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
                put("ride_id", rideId)
                rating?.let { put("rating", it) }
                if (notes.isNotEmpty()) put("notes", notes)
                trackDonation?.let { donation ->
                    val kmlObj = JSONObject().apply {
                        put("route_name", donation.routeName)
                        put("description", donation.description)
                        put("zip_code", donation.zipCode)
                        put("state", donation.state)
                        put("file_name", donation.fileName)
                        put("file_size_kb", donation.fileSizeKb)
                        put("file_hash", donation.fileHash)
                        put("file_data", donation.fileDataBase64)
                    }
                    put("track_donation", kmlObj)
                }
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}ride/survey")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "submitSurvey: ${response.code}")
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Log.e(TAG, "submitSurvey error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── POST /follows/{organizer_id} ──────────────────────────────────────────
    suspend fun followOrganizer(
        userId: String,
        organizerId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}follows/$organizerId")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "followOrganizer: ${response.code}")
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Log.e(TAG, "followOrganizer error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── DELETE /follows/{organizer_id} ────────────────────────────────────────
    suspend fun unfollowOrganizer(
        userId: String,
        organizerId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}follows/$organizerId")
                .delete(body)
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "unfollowOrganizer: ${response.code}")
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Log.e(TAG, "unfollowOrganizer error: ${e.message}")
            Result.failure(e)
        }
    }

    // ── POST /broadcast/{ride_id} ─────────────────────────────────────────────
    // Triggers email shot to all followers of organizer.
    // API checks broadcast_sent flag — returns 409 if already sent.
    suspend fun broadcastRide(
        userId: String,
        rideId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("${ConvoyConfig.API_BASE_URL}broadcast/$rideId")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "broadcastRide: ${response.code}")
            if (response.code == 409) Result.failure(Exception("ALREADY_SENT"))
            else Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Log.e(TAG, "broadcastRide error: ${e.message}")
            Result.failure(e)
        }
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class TrackDonation(
    val routeName: String,
    val description: String,
    val zipCode: String,
    val state: String,
    val fileName: String,
    val fileSizeKb: Int,
    val fileHash: String,        // MD5 computed on device before upload
    val fileDataBase64: String   // base64 encoded file content
)
