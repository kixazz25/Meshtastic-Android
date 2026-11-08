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

    /** STEP14E-2026-11-08 (Fred 12:48): the invite's line for riders without GroupTrack (only when GPX files are attached). */
    // STEP14E-B-2026-11-08 (Fred 13:03): other navigation users download the attachments to their Download folder, then import the
    // GPX files with their own app's import function.
    private const val NO_GT_TEXT = "Using another navigation app? Download the two GPX attachments to your Download folder, then " +
        "import them with your app's import function: one is the route, the other is the trailhead and the ride's waypoints."

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
                .ifBlank { "GroupTrack OG ride" }
            context.cacheDir.listFiles { f -> f.name.endsWith(".convoy") }?.forEach { it.delete() }
            val out = File(context.cacheDir, "$label.convoy")
            stored.copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", out)
            // STEP14E-2026-11-08 (Fred 12:48): riders WITHOUT GroupTrack get the route and the trailhead + waypoints as two GPX files,
            // cut from the package's own gpx (shared GtRideJson.splitGpx) so they always match it. Own folder, emptied per send.
            val sendDir = File(context.cacheDir, "ride_send").also { d -> d.listFiles()?.forEach { it.delete() }; d.mkdirs() }
            val rideData = org.json.JSONObject(stored.readText()).optJSONObject("rideData")
            val gpx = if (rideData == null || rideData.isNull("gpx")) "" else rideData.optString("gpx", "")
            val gpxUris = ArrayList<android.net.Uri>()
            if (gpx.isNotBlank()) {
                val parts = com.grouptrack.shared.ride.GtRideJson.splitGpx(gpx)
                val routeFile = File(sendDir, "$label - route.gpx").also { it.writeText(parts.route) }
                gpxUris += FileProvider.getUriForFile(context, "${context.packageName}.provider", routeFile)
                parts.waypoints?.let { w ->
                    val wptFile = File(sendDir, "$label - trailhead and waypoints.gpx").also { it.writeText(w) }
                    gpxUris += FileProvider.getUriForFile(context, "${context.packageName}.provider", wptFile)
                }
            }
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
                "Don't be left behind! Click the link below to download GroupTrack Off Grid on your Android, " +
                "and reap the benefits beginning with this ride!\n" + play + "\n\n" +
                "If you're a GroupTrack OG user, tap the " + (if (gpxUris.isEmpty()) "" else ".convoy ") +   // STEP14E-B-2026-11-08
                "attachment below, choose GroupTrack OG, then tap \"Always\". " +
                "This imports the route, and downloads the maps for offline use during this ride.\n\n" +
                "Riding with a mesh radio? Check in at the trailhead and GroupTrack OG sets your radio up for this ride.\n\n" +
                (if (gpxUris.isEmpty()) "" else NO_GT_TEXT + "\n\n") +   // STEP14E-2026-11-08
                "Enjoy the ride!"
            fun esc(s: String): String = android.text.TextUtils.htmlEncode(s)
            val inviteHtml = "<p>You're invited: <b>" + esc(name) + "</b> (" + esc(day) + ")</p>" +
                "<p>" + esc(forward) + "</p>" +
                "<p>Don't be left behind! Click the link below to download " +
                "<a href=\"" + play + "\">GroupTrack Off Grid</a> on your Android, " +
                "and reap the benefits beginning with this ride!<br>" +
                "<a href=\"" + play + "\">" + esc(play) + "</a></p>" +
                "<p>If you're a GroupTrack OG user, tap the " + (if (gpxUris.isEmpty()) "" else ".convoy ") +   // STEP14E-B-2026-11-08
                "attachment below, choose GroupTrack OG, then tap &quot;Always&quot;. " +
                "This imports the route, and downloads the maps for offline use during this ride.</p>" +
                "<p>Riding with a mesh radio? Check in at the trailhead and GroupTrack OG sets your radio up for this ride.</p>" +
                (if (gpxUris.isEmpty()) "" else "<p>" + esc(NO_GT_TEXT) + "</p>") +   // STEP14E-2026-11-08
                "<p>Enjoy the ride!</p>"
            // STEP14E-2026-11-08: with GPX files the share carries several attachments (SEND_MULTIPLE + ClipData so the chosen app may
            // read every one); a ride with no GPX goes exactly as before (SEND, the ride's own MIME type).
            val intent = if (gpxUris.isEmpty()) Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_SUBJECT, "GroupTrack OG ride: $name \u2014 $date")
                putExtra(Intent.EXTRA_TEXT, inviteText)
                putExtra(Intent.EXTRA_HTML_TEXT, inviteHtml)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                val all = ArrayList<android.net.Uri>().apply { add(uri); addAll(gpxUris) }
                type = "*/*"
                putExtra(Intent.EXTRA_SUBJECT, "GroupTrack OG ride: $name \u2014 $date")
                putExtra(Intent.EXTRA_TEXT, inviteText)
                putExtra(Intent.EXTRA_HTML_TEXT, inviteHtml)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, all)
                clipData = android.content.ClipData.newRawUri("", all[0]).also { c -> all.drop(1).forEach { c.addItem(android.content.ClipData.Item(it)) } }
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
