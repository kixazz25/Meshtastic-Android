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
            // INVITE2-2026-10-03 (Fred): the attachment is named after the ride -- "panguitch 1 - Wed 7 Oct.convoy".
            // File-safe like the 10-01 GPX fix; the .convoy ending is what opens it in GroupTrack.
            val dayShort = runCatching {
                java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.US))
            }.getOrDefault(date)
            val label = (name.trim() + (if (dayShort.isNotBlank()) " - $dayShort" else ""))
                .replace(Regex("[^A-Za-z0-9 ._-]"), "_").replace(Regex("\\s+"), " ").trim().take(60)
                .ifBlank { "GroupTrack ride" }
            context.cacheDir.listFiles { f -> f.name.endsWith(".convoy") }?.forEach { it.delete() }
            val out = File(context.cacheDir, "$label.convoy")
            stored.copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", out)
            // INVITE2-2026-10-03 (Fred): the same invite twice -- plain text (unchanged, URL visible) and HTML in which
            // "GroupTrack Off-Road Navigation" is a link to Google Play. Apps that ignore HTML show the plain text.
            // RIDEMAIL-2026-09-25 (Fred): the email COACHES the rider -- tap the attachment, choose GroupTrack, "Always".
            val play = "https://play.google.com/store/apps/details?id=com.grouptrack.android&hl=en_US"
            val day = runCatching {
                java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.US))
            }.getOrDefault(date)
            // RIDEPUBLIC-2026-09-27 (Fred): public rides may be forwarded; private rides may not.
            val forward = if (ride?.optBoolean("isPublic", false) == true)
                "This is an open ride. Feel free to forward this email to friends who would like to come."
            else
                "This is a private ride for the riders invited. Please don't forward this email."
            val inviteText = "You're invited: $name ($day)\n\n" + forward + "\n\n" +
                "Don't be left behind! Click the link below to download GroupTrack Off-Road Navigation on your Android, " +
                "and reap the benefits beginning with this ride!\n" + play + "\n\n" +
                "If you're a GroupTrack user, tap the attachment below, choose GroupTrack, then tap \"Always\". " +
                "This imports the route, and downloads the maps for offline use during this ride.\n\n" +
                "Riding with a mesh radio? Check in at the trailhead and GroupTrack sets your radio up for this ride.\n\n" +
                "Enjoy the ride!"
            fun esc(s: String): String = android.text.TextUtils.htmlEncode(s)
            val inviteHtml = "<p>You're invited: <b>" + esc(name) + "</b> (" + esc(day) + ")</p>" +
                "<p>" + esc(forward) + "</p>" +
                "<p>Don't be left behind! Click the link below to download " +
                "<a href=\"" + play + "\">GroupTrack Off-Road Navigation</a> on your Android, " +
                "and reap the benefits beginning with this ride!<br>" +
                "<a href=\"" + play + "\">" + esc(play) + "</a></p>" +
                "<p>If you're a GroupTrack user, tap the attachment below, choose GroupTrack, then tap &quot;Always&quot;. " +
                "This imports the route, and downloads the maps for offline use during this ride.</p>" +
                "<p>Riding with a mesh radio? Check in at the trailhead and GroupTrack sets your radio up for this ride.</p>" +
                "<p>Enjoy the ride!</p>"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_SUBJECT, "GroupTrack ride: $name \u2014 $date")
                putExtra(Intent.EXTRA_TEXT, inviteText)
                putExtra(Intent.EXTRA_HTML_TEXT, inviteHtml)
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
