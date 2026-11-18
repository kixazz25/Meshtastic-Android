// SEAM2PATHS-2026-11-16: Context.cacheDir/filesDir -> the shared storage socket
package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.VectorTiles
import java.io.File
import com.grouptrack.shared.log.gtLogger

/**
 * BACKUP-2026-11-03 (Fred 11:32-11:41): everything needed to recreate GroupTrack after a forced reload, a crash or a new phone -- the four
 * export-alls (routes, tracks, waypoints, rider trails) and the newest map recipe -- in ONE email the rider sends to himself.
 *  - every app start with internet: built in the background (cache only), fingerprinted; offered (SEND BACKUP / NOT NOW) only when it
 *    differs from the last one SENT;
 *  - Map > Back up my GroupTrack data: the same on demand (also saves the GPX files to Downloads).
 */
object GtBackup {
    private const val TAG = "Backup"
    private val log = gtLogger(TAG)   // GTLOGKERMIT-2026-11-16: android.util.Log(TAG, ..) -- same tag, so the logs read as 2.7e's
    private const val PREF = "gt_backup"

    private class Built(val files: List<File>, val list: String, val print: String)
    @Volatile private var checked = false           // the start-up check runs once per app start

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /**
     * ARCHIVE-2026-11-17 (Fred): "the full image of map recipes and all artifacts should be sent
     * minimally once per week or when starting app is more then 7 days."
     *
     * ⚠ CORRECTED DRFILE-2026-11-18: this paragraph said "the floor is on the EMAIL, not on the
     * archive -- the archive is written on every start-up check regardless". That stopped being true
     * on 11-17 when ARCHIVEGATE put archive() inside the gated branch, and there is no email at all
     * from 11-18. THE FLOOR IS ON THE WRITE: unchanged and inside 7 days writes nothing. A dated full
     * image once a week is still worth having -- it is a restore point even in a quiet week, and proof
     * the backup still works rather than having silently stopped.
     * ⚠ No pref means nothing was ever sent, which the unchanged branch cannot reach anyway (an empty
     * fingerprint never matches a built one), so 0L is safe.
     */
    private fun weekOld(ctx: Context): Boolean {
        val at = prefs(ctx).getLong("sentAtMs", 0L)
        if (at == 0L) return true
        val days = (System.currentTimeMillis() - at) / 86_400_000L
        if (days > 7) log.i { "BACKUP unchanged but $days days since the last send -- offering the weekly image" }
        return days > 7
    }

    private fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { false }

    /** Start-up: with internet, not NOT-NOW'd today -> build, compare with the last SENT, offer only a change. */
    fun autoCheck(ctx: Context) {
        if (checked) return
        checked = true
        if (!online(ctx)) { log.i { "BACKUP start-up check: no internet" }; return }
        Thread {
            try {
                Thread.sleep(20_000)   // after the start-up work
                GtDbAudit.run(ctx)   // DBAUDIT-2026-11-03: read-only, logged
                // ARCHIVEGATE-2026-11-17: build WITHOUT writing to Downloads. keepForShare already
                // puts every file in cache/exports, so the content exists and can be archived below --
                // after the fingerprint says it is worth archiving.
                // ⛔ THIS WAS build(ctx, true) FOR TWENTY MINUTES AND THAT WAS WRONG. It wrote all four
                // DR exports on EVERY app start -- GroupTrack_TRACKS is 20 MB on Droid 2, so ~21 MB of
                // MediaStore writes per launch -- against Fred's actual rule: "we only need to save
                // changed data. the full image ... minimally once per week." Found by his first test:
                // "4 seperate exports all came in one election."
                val b = build(ctx, false)
                when {
                    b.files.isEmpty() -> log.i { "BACKUP start-up check: nothing to back up" }
                    b.print == prefs(ctx).getString("sent", "") && !weekOld(ctx) ->
                        log.i { "BACKUP unchanged since the last backup sent -- nothing offered, nothing archived" }
                    else -> {
                        // BACKUPDIAG-2026-11-03: which part changed since the backup sent
                        val was = (prefs(ctx).getString("sent", "") ?: "").split(';').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
                        val now = b.print.split(';').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
                        val diff = (was.keys + now.keys).filter { was[it] != now[it] }.joinToString(", ") { k -> "$k ${was[k]?.take(8) ?: "none"}->${now[k]?.take(8) ?: "none"}" }
                        log.i { "BACKUP changed -- offering SEND BACKUP (${b.files.size} files); changed since sent: ${diff.ifEmpty { "(nothing sent yet)" }}" }
                        archive(ctx, b)   // DRFILE-2026-11-18: changed (or week-old) -- write the set to DR
                    }
                }
            } catch (e: Exception) { log.e(e) { "BACKUP start-up check failed" } }
        }.start()
    }

    /**
     * Map > Back up my GroupTrack data -- ON DEMAND, and it stays (Fred 11-18: "it can stay on demand
     * but it builds files in startup also"). Builds the five files and writes them to the DR directory.
     * ⚠ build(ctx, false), not true: the rider's own Map Features copies come from the EXPORT rows.
     * This path is the DR position, so the set belongs in DR and nowhere else -- writing both would put
     * the same GPX in two folders on every backup.
     */
    fun start(ctx: Context) {
        android.widget.Toast.makeText(ctx, "Building your GroupTrack OG backup…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            try { GtDbAudit.run(ctx) } catch (e: Exception) { log.e(e) { "DBAUDIT" } }   // DBAUDIT-2026-11-03
            val b = try { build(ctx, false) } catch (e: Exception) { log.e(e) { "BACKUP build" }; Built(emptyList(), "Backup failed: ${e.message}", "") }
            if (b.files.isNotEmpty()) archive(ctx, b)   // DRFILE-2026-11-18
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx,
                    if (b.files.isEmpty()) b.list.ifEmpty { "Nothing to back up yet." }
                    else "Saved ${b.files.size} file(s) to GroupTrack (your recovery folder).",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun sha(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /**
     * ARCHIVEGATE-2026-11-17: the DR position, into its owning folders. Called only when the
     * fingerprint CHANGED or the weekly floor fired -- the archive follows the same rule as the email,
     * which is what Fred asked for: save changed data, with a full image at least weekly.
     *
     * ⚠ The files are already built, in cache/backup. This copies them; it does not rebuild, so the
     * exports run ONCE per check however many destinations they end up in.
     * ⚠ Family by NAME, because that is what the file is: the four GPX sets are map features, and the
     * recipe ("GroupTrack maps <day>.json", RECIPEJSON-2026-11-03) is a map.
     */
    /**
     * DRFILE-2026-11-18 (Fred 11-18 06:24, 06:27): "remove the email and save the startup contents in
     * the dr directory"; "this generates 5 files used for data recreation".
     *
     * THE WHOLE SET GOES TO ONE PLACE -- Family.ROOT, the DR directory. The 11-17 version scattered it
     * (gpx -> Map Features, recipe -> Maps, everything else dropped) ONLY because there was no DR
     * destination then; its own note said the DR position "now lives in the three folders themselves".
     * There is a destination now, so the set is a set again: five files, one folder, each tappable to
     * restore -- which is the recovery Fred described ("we can launch every one of the recovery jobs
     * by tapping it from the dr directory").
     *
     * ⛔ IT RECORDS THE FINGERPRINT. send() used to do that, and send() is gone. Leave this out and
     * the gate never advances, so the backup re-archives on EVERY launch -- GroupTrack_TRACKS is 20 MB
     * on Droid 2, so ~21 MB of MediaStore writes per start. Simulated: gt_drfilesim_2026-11-18_v1.py
     * scores the correct version at 4 writes in 7 launches and that mistake at 6.
     * ⚠ The pref key stays "sent" rather than becoming "saved": renaming it would make every existing
     * install read an empty fingerprint and write one more full set for no benefit.
     */
    private fun archive(ctx: Context, b: Built) {
        var n = 0
        for (f in b.files) {
            if (com.geeksville.mesh.convoy.GtFileHome.save(
                    ctx, com.geeksville.mesh.convoy.GtFileOpen.Family.ROOT, f) != null) n++
        }
        if (n > 0) prefs(ctx).edit().putString("sent", b.print).putLong("sentAtMs", System.currentTimeMillis()).apply()
        log.i { "DRFILE-2026-11-18: wrote $n of ${b.files.size} file(s) to the DR directory" +
            (if (n > 0) " -- fingerprint recorded" else " -- NOTHING WRITTEN, fingerprint left alone") }
    }

    /** The backup's files (cache/backup), the email's list, and its fingerprint. */
    private fun build(ctx: Context, toDownloads: Boolean): Built {
        val dir = File(GtPathsAndroid.cache(ctx), "backup").also { it.mkdirs(); it.listFiles()?.forEach { x -> x.delete() } }
        val files = ArrayList<File>(); val list = StringBuilder(); val print = StringBuilder()
        for ((type, label) in GtExportAll.TYPES) {   // EXPORTONE-2026-11-18: one list, shared with the export screen
            val msg = GtExportAll.run(ctx, type, toDownloads)
            val f = GtExportAll.takeWritten() ?: continue   // none of this type, or it could not be written
            val copy = File(dir, f.name); f.copyTo(copy, overwrite = true); files.add(copy)
            val n = Regex("Exported (\\d+)").find(msg)?.groupValues?.get(1) ?: "?"
            list.append("  • ").append(n).append(' ').append(label).append('\n')
            print.append(type).append('=').append(sha(f.readText().replace(Regex("GroupTrack_[A-Z_]+_\\d{4}-\\d{2}-\\d{2}"), ""))).append(';')
        }
        val recipe = GroupTrackStorage.dir("recipes", ctx).listFiles()
            ?.filter { it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps") }?.maxByOrNull { it.name }
        if (recipe != null) {
            val day = recipe.name.removePrefix("grouptrack_maps_").removeSuffix(".gtmaps")
            val copy = File(dir, "GroupTrack maps $day.json"); recipe.copyTo(copy, overwrite = true); files.add(copy)   // RECIPEJSON-2026-11-03
            list.append("  • your maps as of ").append(day).append('\n')
            // the recipe is rewritten daily with a new date -- its MAP BOXES are what changes
            print.append("maps=").append(sha(VectorTiles.boxesOf(recipe).joinToString("|") { it.joinToString(",") })).append(';')
        }
        log.i { "BACKUP built ${files.size} file(s) toDownloads=$toDownloads: ${files.joinToString { "${it.name} ${it.length()}" }}" }
        return Built(files, list.toString(), print.toString())
    }

}

