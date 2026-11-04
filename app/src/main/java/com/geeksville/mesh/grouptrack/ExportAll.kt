package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyTrackOps
import com.geeksville.mesh.convoy.SpatialDbManager

/**
 * EXPORTALL-2026-11-03 (Fred 11-01; 11-03 10:26 "it should bundle all objects in one gpx file for importing. one type at a time"):
 * Map > IMPORT / EXPORT MAP FEATURES > Export all routes / tracks / waypoints. Every item of the type in ONE GPX file, each under its
 * own name, written to Downloads (GroupTrack_ROUTES_<date>.gpx etc.) -- built from the same per-item GPX the item panels export.
 * The type travels inside the file (<rte> / <trk> / <wpt>), so the one GPX import brings each back as what it was.
 */
object GtExportAll {
    private const val TAG = "ExportAll"
    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"GroupTrack\"\n" +
        "     xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
        "     xmlns:grouptrack=\"https://grouptrack.app/gpx/1\">\n"

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** What one item's GPX holds inside <gpx>, without its <metadata>. "" = nothing usable. */
    private fun body(gpx: String): String {
        val open = Regex("<gpx\\b[^>]*>").find(gpx) ?: return ""
        val end = gpx.lastIndexOf("</gpx>")
        if (end < 0 || end <= open.range.last) return ""
        return gpx.substring(open.range.last + 1, end).replace(Regex("(?s)<metadata>.*?</metadata>"), "").trim()
    }

    /** A track file's <trk> named with the track's own name (its file is named by its hash). */
    private fun named(body: String, name: String): String {
        val withName = Regex("(?s)(<trk>\\s*)<name>.*?</name>")
        return if (withName.containsMatchIn(body)) withName.replaceFirst(body, "$1<name>" + Regex.escapeReplacement(xml(name)) + "</name>")
               else body.replaceFirst("<trk>", "<trk>\n    <name>" + xml(name) + "</name>")
    }

    /**
     * TRAILEXPORT-2026-11-03 (Fred 11:05): the trails the rider made -- imported from GPX ("import") and from tracks ("rider"). The state
     * and OSM trails are not included (everyone gets those). Each is a <trk> typed "trail", so the one GPX import can tell it from a track.
     */
    private fun trailsGpx(ctx: Context, db: android.database.sqlite.SQLiteDatabase, toDownloads: Boolean): String {
        val sb = StringBuilder(HEADER)
        var n = 0
        db.rawQuery("SELECT name, geometry FROM trails WHERE carto_code_source IN ('import','rider')", null).use { c ->
            while (c.moveToNext()) {
                val wkt = c.getString(1) ?: continue
                val parts = Regex("\\(([^()]+)\\)").findAll(wkt).map { it.groupValues[1] }.toList()
                if (parts.isEmpty()) continue
                sb.append("  <trk>\n    <name>").append(xml(c.getString(0)?.ifBlank { null } ?: "Trail")).append("</name>\n    <type>grouptrack-trail</type>\n")   // ONEIMPORT-2026-11-04: the mark the import reads
                for (p in parts) {
                    sb.append("    <trkseg>\n")
                    for (pt in p.split(',')) {
                        val xy = pt.trim().split(Regex("\\s+"))
                        if (xy.size >= 2) sb.append("      <trkpt lat=\"").append(xy[1]).append("\" lon=\"").append(xy[0]).append("\"/>\n")
                    }
                    sb.append("    </trkseg>\n")
                }
                sb.append("  </trk>\n"); n++
            }
        }
        if (n == 0) return "No rider trails to export (trails imported from outside GroupTrack or made from your tracks)."   /* RIDERTRAILSROW-2026-11-03 */
        sb.append("</gpx>\n")
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val file = "GroupTrack_RIDER_TRAILS_$day"
        val ok = if (toDownloads) ConvoyTrackOps.exportGpxToDownloads(file, sb.toString()) else true   // BACKUP-2026-11-03
        if (ok) keepForShare(ctx, file, sb.toString())   // SHAREEXPORT-2026-11-03
        Log.i(TAG, "TRAILEXPORT $n trail(s) -> Downloads/$file.gpx ok=$ok")
        if (!ok) return "Export failed: could not write to Downloads."
        return "Exported $n rider trails to Downloads: $file.gpx. Bring them back with Map › Import files — they come back as trails."
    }

    /** Off the main thread; the result as a toast. [type] = "routes" | "tracks" | "waypoints". */
    fun start(ctx: Context, type: String) {
        android.widget.Toast.makeText(ctx, "Exporting all $type…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try { run(ctx, type, true) } catch (e: Exception) { Log.e(TAG, "EXPORTALL $type", e); "Export failed: ${e.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                shareNow(ctx)   // SHAREEXPORT-2026-11-03
            }
        }.start()
    }

    // SHAREEXPORT-2026-11-03 (Fred 11:29): every export-all also opens the share sheet (email, Drive, Files ...). The copy lives in
    // cache/exports (what the FileProvider shares); only the newest is kept. pending: the file written by this run, null = none written
    // (the export failed or found nothing) -- a field set by the export thread and taken once on the main thread.
    @Volatile private var pending: java.io.File? = null

    private fun keepForShare(ctx: Context, name: String, gpx: String) {
        val dir = java.io.File(ctx.cacheDir, "exports").also { it.mkdirs(); it.listFiles()?.forEach { x -> x.delete() } }
        pending = java.io.File(dir, "$name.gpx").also { it.writeText(gpx) }
    }

    /** BACKUP-2026-11-03: the file this run wrote, taken (the backup collects one per type instead of sharing each). null = none. */
    fun takeWritten(): java.io.File? { val f = pending; pending = null; return f }

    private fun shareNow(ctx: Context) {
        val f = pending ?: return
        pending = null
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", f)
            val what = f.nameWithoutExtension.replace('_', ' ')
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, what)
                putExtra(android.content.Intent.EXTRA_TEXT, "$what, sent from GroupTrack.\n\n" +
                    "On your Android phone or tablet: tap the attachment and choose GroupTrack — it opens in GroupTrack's GPX import.\n\n" +
                    "Don't have GroupTrack yet? Get it on Google Play:\nhttps://play.google.com/store/apps/details?id=com.grouptrack.android&hl=en_US\n")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(send, "Send or save $what")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "SHAREEXPORT share sheet for ${f.name} (${f.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "SHAREEXPORT share failed", e)
            android.widget.Toast.makeText(ctx, "Saved to Downloads; could not open sharing: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** One GPX, every item of [type], to Downloads. The message for the rider. */
    /** BACKUP-2026-11-03: [toDownloads] false = the background backup (the file is kept for it only, never in Downloads). */
    fun run(ctx: Context, type: String, toDownloads: Boolean): String {
        SpatialDbManager.init(ctx)
        val db = SpatialDbManager.getSpatialDb() ?: return "Export failed: the map features database is not open."
        if (type == "trails") return trailsGpx(ctx, db, toDownloads)   // TRAILEXPORT-2026-11-03
        val sql = when (type) { "routes" -> "SELECT route_id FROM routes"; "tracks" -> "SELECT track_id FROM tracks WHERE (type='TRACK' OR type IS NULL)";   /* DBAUDIT-2026-11-03: not the old ROUTE rows */ else -> "SELECT waypoint_id FROM waypoints" }
        val ids = ArrayList<String>()
        db.rawQuery(sql, null).use { c -> while (c.moveToNext()) c.getString(0)?.let { ids.add(it) } }
        if (ids.isEmpty()) return "No $type on this tablet to export."
        val sb = StringBuilder(HEADER)
        var n = 0; var skipped = 0; val why = ArrayList<String>()   // BACKUPDIAG-2026-11-03
        for (id in ids) {
            val g = try {
                when (type) {
                    "routes" -> SpatialDbManager.buildRouteGpxById(id)
                    "tracks" -> SpatialDbManager.buildTrackGpxById(id)
                    else -> SpatialDbManager.buildWaypointGpxById(id)
                }
            } catch (e: Exception) { null }
            if (g == null) { skipped++; why.add("$id: no GPX"); continue }
            var b = body(g.second)
            if (b.isEmpty()) { skipped++; why.add("${g.first}: no <gpx> body"); continue }
            if (type == "tracks") b = named(b, g.first)
            sb.append(b).append('\n'); n++
        }
        sb.append("</gpx>\n")
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val file = "GroupTrack_${type.uppercase(java.util.Locale.US)}_$day"
        val ok = if (toDownloads) ConvoyTrackOps.exportGpxToDownloads(file, sb.toString()) else true   // BACKUP-2026-11-03
        if (ok) keepForShare(ctx, file, sb.toString())   // SHAREEXPORT-2026-11-03
        Log.i(TAG, "EXPORTALL $type: $n of ${ids.size} written ($skipped skipped) -> Downloads/$file.gpx ok=$ok" + (if (why.isEmpty()) "" else " SKIPPED: " + why.take(20).joinToString("; ")))
        if (!ok) return "Export failed: could not write to Downloads."
        return "Exported $n $type to Downloads: $file.gpx" + (if (skipped > 0) " ($skipped could not be read)" else "") + "."
    }
}
