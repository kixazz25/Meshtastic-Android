package com.geeksville.mesh.grouptrack

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.content.FileProvider
import java.io.File

/**
 * RIDESTATSSEND-2026-10-26 (Fred 12:45-13:27): Rides > "Email last ride statistics to GroupTrack" -- what testers' real rides tell us,
 * to improve GroupTrack's tracking. Nothing is sent without the rider: the email app opens with everything attached; the rider taps Send.
 * Attached: the map as it is (screenshot, taken just after the menu closes), the statistics (summary .txt + full .json), the lead track
 * AS THIS TABLET DREW IT with the rule behind every point (.csv), and my cart's last recorded track (.gpx). To support@grouptrack.org.
 */
object GtRideStatsSend {
    fun send(ctx: Context, report: String, statsJson: String, lead: List<com.geeksville.mesh.convoy.WPoint>, lastGpx: File?) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())
        val dir = File(ctx.cacheDir, "ride_stats").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val files = ArrayList<File>()
        runCatching { File(dir, "ride_stats_$stamp.txt").also { it.writeText(report); files.add(it) } }
        runCatching { File(dir, "ride_stats_$stamp.json").also { it.writeText(statsJson); files.add(it) } }
        if (lead.isNotEmpty()) runCatching {
            File(dir, "lead_track_as_drawn_$stamp.csv").also { f ->
                f.bufferedWriter().use { w ->
                    w.write("time_ms,lat,lon,alt,real,rule\n")
                    lead.forEach { p -> w.write("${p.tMs},${p.lat},${p.lon},${p.alt},${p.real},${p.rule.replace(',', ';')}\n") }
                }
                files.add(f)
            }
        }
        if (lastGpx != null && lastGpx.exists()) runCatching { files.add(lastGpx.copyTo(File(dir, "my_cart_" + lastGpx.name), overwrite = true)) }
        val act = ctx.findActivity()
        if (act == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { email(ctx, files, report, stamp); return }
        // the Rides menu closes first; the map is captured half a second later
        Handler(Looper.getMainLooper()).postDelayed({
            val w = act.window; val v = w.decorView
            if (v.width <= 0 || v.height <= 0) { email(ctx, files, report, stamp); return@postDelayed }
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(w, bmp, { r ->
                if (r == PixelCopy.SUCCESS) runCatching {
                    val f = File(dir, "map_$stamp.jpg")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    files.add(0, f)
                }
                email(ctx, files, report, stamp)
            }, Handler(Looper.getMainLooper()))
        }, 500L)
    }

    private fun email(ctx: Context, files: List<File>, report: String, stamp: String) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", it) })
        val ver = runCatching { com.geeksville.mesh.BuildConfig.VERSION_NAME }.getOrDefault("?")
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(GtIssue.TO))
            putExtra(Intent.EXTRA_SUBJECT, "GroupTrack ride statistics · $stamp · $ver")
            putExtra(Intent.EXTRA_TEXT, "Ride statistics from GroupTrack, to help improve group tracking. Attached: the map, the statistics, " +
                "the lead track as this tablet drew it, and my cart's last track.\n\n" + report)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, "Email the ride statistics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.widget.Toast.makeText(ctx, "No email app on this tablet.", android.widget.Toast.LENGTH_LONG).show() }
    }
}
