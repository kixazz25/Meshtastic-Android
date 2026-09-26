package com.geeksville.mesh.convoy

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * RIDESEND-2026-09-23 — sends a ride. SENDING IS THE LOCK (Fred 09-23): after the first send only
 * the date can change. The file sent is the STORED ride file (ConvoyRideJsonWriter.rideFile) — the
 * same file apply reads, so the leader's tablet and every rider's hold identical bytes.
 *
 * Share mechanics copied from ConvoyTransferRideScreen: copy into cacheDir (what the FileProvider
 * paths allow), same MIME type and .convoy extension, so the existing "open with" handling catches it.
 * ⚠ The existing receiver (ConvoyFileReceiver) still expects format 2 — import is the next change.
 */
object ConvoyRideSend {

    private const val TAG = "ConvoyRideSend"
    const val MIME = "application/x-convoy-ride"

    /** Opens the share sheet and locks the ride. Returns null on success, or why it cannot be sent. */
    fun send(context: Context, rideId: String): String? {
        val missing = ConvoyRideJsonWriter.save(context, rideId)
            ?: return "The ride file could not be written."
        if (missing.isNotEmpty()) return "Not ready to send \u2014 missing: " + missing.joinToString(", ")
        val stored = ConvoyRideJsonWriter.rideFile(context, rideId)
        if (!stored.exists()) return "The ride file is missing."
        return try {
            val ride = org.json.JSONObject(stored.readText()).optJSONObject("ride")
            val name = ride?.optString("name", "Ride") ?: "Ride"
            val date = ride?.optString("date", "") ?: ""
            val safe = (name + "_" + date).replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val out = File(context.cacheDir, "grouptrack_ride_$safe.convoy")
            stored.copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", out)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_SUBJECT, "GroupTrack ride: $name \u2014 $date")
                putExtra(Intent.EXTRA_TEXT, run {
                    // RIDEMAIL-2026-09-25 (Fred): the email COACHES the rider -- tap the attachment, choose GroupTrack, "Always"
                    // (after which every ride file opens in GroupTrack directly). Name and date as in the subject.
                    val rideName = "$name"
                    val rideDate = "$date"
                    val day = runCatching {
                        java.time.LocalDate.parse(rideDate).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.US))
                    }.getOrDefault(rideDate)
                    "You're invited: $rideName ($day)\n\n" +
                        "If you're a GroupTrack user, tap the attachment below, choose GroupTrack, then tap \"Always\". " +
                        "This imports the route, and downloads the maps for offline use during this ride.\n\n" +
                        "Using a GroupTrack radio? Remember to apply the ride to your radio before your ride (Work with Rides \u2192 Apply ride to radio).\n\n" + // RIDEMAIL2-2026-09-26
                        "Enjoy the ride!"
                })
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Send ride via...")
            if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            ConvoyRideStore.markDistributed(rideId)   // THE LOCK
            Log.i(TAG, "RIDESEND-2026-09-23: $rideId shared and locked")
            null
        } catch (e: Exception) {
            Log.e(TAG, "send failed: ${e.message}")
            "Send failed: ${e.message}"
        }
    }
}
