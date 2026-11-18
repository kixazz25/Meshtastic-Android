// SEAM2PATHS-2026-11-16: Context.cacheDir/filesDir -> the shared storage socket
package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.ConvoyTrackOps
import com.geeksville.mesh.convoy.SpatialDbManager
import com.grouptrack.shared.log.gtLogger

/**
 * EXPORTALL-2026-11-03 (Fred 11-01; 11-03 10:26 "it should bundle all objects in one gpx file for importing. one type at a time"):
 * Map > IMPORT / EXPORT MAP FEATURES > Export all routes / tracks / waypoints. Every item of the type in ONE GPX file, each under its
 * own name, written to Downloads (GroupTrack_ROUTES_<date>.gpx etc.) -- built from the same per-item GPX the item panels export.
 * The type travels inside the file (<rte> / <trk> / <wpt>), so the one GPX import brings each back as what it was.
 */
object GtExportAll {
    private const val TAG = "ExportAll"
    private val log = gtLogger(TAG)   // GTLOGKERMIT-2026-11-16: android.util.Log(TAG, ..) -- same tag, so the logs read as 2.7e's
    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"GroupTrack\"\n" +
        "     xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
        "     xmlns:grouptrack=\"https://grouptrack.app/gpx/1\">\n"

    /**
     * EXPORTONE-2026-11-18: THE four map-feature types, in one place, in export order.
     * ⛔ This list was private to GtBackup, which meant the DR set and the Map menu each carried
     * their own idea of "all map features" and could drift. GtExportAll owns exporting, so it owns the
     * list; GtBackup now reads it. One list, two consumers (the de-duplication rule).
     */
    val TYPES = listOf("routes" to "routes", "tracks" to "tracks",
                       "waypoints" to "waypoints", "trails" to "rider trails")

    /**
     * EXPORTONE-2026-11-18: write every ticked [types] to GroupTrack Map Features and return the files
     * written, in TYPES order. A type with nothing on the tablet is skipped silently and simply does
     * not appear in the result -- that is what lets the screen say "3 file(s)" honestly.
     *
     * ⚠ WHY IT COPIES. keepForShare keeps ONE file in cache/exports and DELETES the rest on every
     * call, so after a four-type run only the last file still exists and the earlier File handles point
     * at nothing. Each file is copied into its own run folder as it is produced. GtBackup.build() does
     * exactly this for the same reason; this is not a new mechanism.
     */
    fun exportMany(ctx: Context, types: Set<String>): List<java.io.File> {
        val dir = java.io.File(GtPathsAndroid.cache(ctx), "exportrun")
            .also { it.mkdirs(); it.listFiles()?.forEach { x -> x.delete() } }
        val out = ArrayList<java.io.File>()
        for ((type, _) in TYPES) {
            if (type !in types) continue
            val msg = try { run(ctx, type, true) } catch (e: Exception) { log.e(e) { "EXPORTONE-2026-11-18 $type" }; continue }
            val f = takeWritten()
            if (f == null) { log.i { "EXPORTONE-2026-11-18: $type -- nothing written ($msg)" }; continue }
            val copy = java.io.File(dir, f.name)
            try { f.copyTo(copy, overwrite = true); out.add(copy) }
            catch (e: Exception) { log.e(e) { "EXPORTONE-2026-11-18: could not stage ${f.name}" } }
        }
        log.i { "EXPORTONE-2026-11-18: wrote ${out.size} of ${types.size} ticked type(s): " + out.joinToString { it.name } }
        return out
    }

    /**
     * EXPORTONE-2026-11-18: ONE share sheet for the files a run just wrote, reached from the prompt
     * the export screen raises after saving. Fred 07:06: "the se[le]ctor panel [is] asking a prompt
     * after the save[:] do you wan[t] to send these files as an email. the files are know[n] and if yes
     * the sh[ar]e [sheet] is created with up to four files passed."
     *
     * ⭐ THIS PROMPT IS THE ONE SOLICITATION, and it replaces two. Before: the chooser fired by
     * itself on every export (nobody asked), and emailing anything else meant walking to the selector.
     * Now the rider is asked once, at the only moment the answer is obvious, and the files are already
     * known so nothing has to be picked again.
     *
     * ⚠ THE INTENT TYPE IS THE TWO-WILDCARD FORM, not octet-stream: octet-stream made the chooser
     * read as a generic share sheet (EMAILHOUSE-2026-11-17). ⛔ No setPackage -- the manifest's <queries> declares the geo: intent
     * only, so a named-package lookup returns null even with a mail app installed. A chooser is the
     * mechanism on Android; that is why "open an email with the files attached" is not available.
     */
    fun emailWritten(ctx: Context, files: List<java.io.File>) {
        if (files.isEmpty()) return
        try {
            val uris = ArrayList(files.map {
                androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", it) })
            val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "GroupTrack OG map features")
                putExtra(android.content.Intent.EXTRA_TEXT,
                    "Attached: " + files.joinToString(", ") { it.name } + ", exported from GroupTrack OG.\n\n" +
                    "Open an attachment on a tablet running GroupTrack OG to bring it in.")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(send, "Send as email")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            log.i { "EXPORTONE-2026-11-18: share sheet for ${files.size} file(s)" }
        } catch (e: Exception) {
            log.e(e) { "EXPORTONE-2026-11-18: no app took the send" }
            android.widget.Toast.makeText(ctx,
                "Saved to GroupTrack Map Features; no app would take the email.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

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
        if (n == 0) return "No rider trails to export (trails imported from outside GroupTrack OG or made from your tracks)."   /* RIDERTRAILSROW-2026-11-03 */
        sb.append("</gpx>\n")
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val file = "GroupTrack_RIDER_TRAILS_$day"
        // EXPORTFAM-2026-11-17: into GroupTrack Map Features, through MediaStore (mechanism B).
        val ok = if (toDownloads) com.geeksville.mesh.convoy.GtFileHome.save(
            ctx, com.geeksville.mesh.convoy.GtFileOpen.Family.MAP_FEATURES,
            com.geeksville.mesh.convoy.ConvoyTrackOps.safeGpxName(file), sb.toString()) != null else true
        if (ok) keepForShare(ctx, file, sb.toString())   // SHAREEXPORT-2026-11-03
        log.i { "TRAILEXPORT $n trail(s) -> GroupTrack Map Features/$file.gpx ok=$ok" }
        if (!ok) return "Export failed: could not write to GroupTrack Map Features."
        return "Exported $n rider trails to GroupTrack Map Features: $file.gpx. Bring them back by tapping the file — they come back as trails."
    }

    /** Off the main thread; the result as a toast. [type] = "routes" | "tracks" | "waypoints". */
    // EXPORTONE-2026-11-18: start(ctx, type) was here -- the single-type entry behind each of the four Map
    // rows, toast then export. With one screen and one prompt there is no caller left; exportMany is
    // the entry now. GtCertPaths still calls run() directly, which is untouched.

    // SHAREEXPORT-2026-11-03, SUPERSEDED BY NOSHARE-2026-11-18: export-all no longer opens a share sheet.
    // The cache/exports copy and [pending] REMAIN, and are not vestigial -- GtBackup.build() calls
    // run() then takeWritten() to collect one file per type for the DR set (DRFILE-2026-11-18).
    // pending: the file written by this run, null = none written (the export failed or found nothing).
    @Volatile private var pending: java.io.File? = null

    private fun keepForShare(ctx: Context, name: String, gpx: String) {
        val dir = java.io.File(GtPathsAndroid.cache(ctx), "exports").also { it.mkdirs(); it.listFiles()?.forEach { x -> x.delete() } }
        pending = java.io.File(dir, "$name.gpx").also { it.writeText(gpx) }
    }

    /** BACKUP-2026-11-03: the file this run wrote, taken (the backup collects one per type instead of sharing each). null = none. */
    fun takeWritten(): java.io.File? { val f = pending; pending = null; return f }


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
        // EXPORTFAM-2026-11-17: into GroupTrack Map Features, through MediaStore (mechanism B).
        val ok = if (toDownloads) com.geeksville.mesh.convoy.GtFileHome.save(
            ctx, com.geeksville.mesh.convoy.GtFileOpen.Family.MAP_FEATURES,
            com.geeksville.mesh.convoy.ConvoyTrackOps.safeGpxName(file), sb.toString()) != null else true
        if (ok) keepForShare(ctx, file, sb.toString())   // SHAREEXPORT-2026-11-03
        log.i { "EXPORTALL $type: $n of ${ids.size} written ($skipped skipped) -> GroupTrack Map Features/$file.gpx ok=$ok" + (if (why.isEmpty()) "" else " SKIPPED: " + why.take(20).joinToString("; ")) }
        if (!ok) return "Export failed: could not write to GroupTrack Map Features."
        return "Exported $n $type to GroupTrack Map Features: $file.gpx" + (if (skipped > 0) " ($skipped could not be read)" else "") + "."
    }
}
