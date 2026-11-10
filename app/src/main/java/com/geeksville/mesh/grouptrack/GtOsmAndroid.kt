package com.geeksville.mesh.grouptrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.ForegroundInfo
import com.grouptrack.shared.osm.GtOsmPlatform
import okio.Path
import java.io.File

/**
 * STEP15G-SWITCH-2026-11-10: Android's GtOsmPlatform -- what the shared OSM extract + import (com.grouptrack.shared.osm, certified
 * 57cd2a8a7) asks of the platform. Every body here is 2.7e's own code, MOVED from the files that are now front doors (each block
 * names its 2.7e origin); nothing in them is new.
 */
class GtOsmAndroid(context: Context) : GtOsmPlatform {
    private val ctx: Context = context.applicationContext

    /** OsmImportStage.rootDir's once-per-process legacy rescue (OSM-LEGACY-MIGRATE-2026-07-28), with 2.7e's own flag and catch. */
    override fun onOsmRoot(root: Path) {
        if (!legacyChecked) {
            legacyChecked = true
            try {
                migrateLegacy(File(root.toString()))
            } catch (e: Exception) {
                Log.e(STAGE_TAG, "legacy migration failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /** Where OSM files lived before they moved to shared storage (2.7e OsmImportStage.legacyRootDir). */
    private fun legacyRootDir(): File = File(ctx.getExternalFilesDir(null), "osm")

    /** 2.7e OsmImportStage.migrateLegacy, unchanged: RENAME ONLY, anything already at the new path wins, a failed rename is left alone. */
    private fun migrateLegacy(newRoot: File) {
        val old = legacyRootDir()
        if (!old.exists() || !old.isDirectory) return
        Log.i(STAGE_TAG, "legacy OSM directory found at ${old.absolutePath} -- migrating")
        var moved = 0
        var dropped = 0
        var failed = 0
        old.listFiles()?.forEach { entry ->
            if (!entry.isDirectory) {
                if (entry.delete()) dropped++
                return@forEach
            }
            val dest = File(newRoot, entry.name)
            if (!dest.exists()) dest.mkdirs()
            entry.listFiles()?.forEach { f ->
                val target = File(dest, f.name)
                when {
                    target.exists() -> { if (f.delete()) dropped++ }
                    f.renameTo(target) -> moved++
                    else -> {
                        failed++
                        Log.w(STAGE_TAG, "could not move ${f.name} (${f.length()} bytes) -- left in place rather than deleted")
                    }
                }
            }
            if (entry.listFiles()?.isEmpty() == true) entry.delete()
        }
        if (old.listFiles()?.isEmpty() == true) old.delete()
        Log.i(STAGE_TAG, "legacy migration: moved=$moved droppedStale=$dropped failed=$failed")
    }

    /** 2.7e OsmImportStage.discardState's legacy sweep (OSM-CANCEL-SWEEPS-LEGACY-2026-07-28) -- SAME SLUG ONLY. The caller guards it. */
    override fun discardLegacy(slug: String) {
        val legacyDir = File(legacyRootDir(), slug)
        if (legacyDir.exists()) {
            var ln = 0
            legacyDir.listFiles()?.forEach { f -> if (!f.isDirectory && f.delete()) ln++ }
            legacyDir.delete()
            if (ln > 0) Log.i(STAGE_TAG, "legacy sweep: removed $ln stranded file(s) for $slug")
        }
        val legacyRoot = legacyRootDir()
        if (legacyRoot.exists() && legacyRoot.listFiles()?.isEmpty() == true) legacyRoot.delete()
    }

    /** OsmExtractWorker's free-space gate: File.usableSpace. */
    override fun usableSpace(dir: Path): Long = File(dir.toString()).usableSpace

    /**
     * 2.7e OsmExtractWorker's download sweep (OSM-ZIPDEL-2026-07-30), unchanged: the browser's copies of this state's zip in the public
     * Downloads folder, matched by SLUG (incl. a browser's "(1)"), deleted with File.delete (NOT MediaStore -- permanent). The caller
     * guards it (a failure never fails the extract).
     */
    override fun sweepDownloadedZips(slug: String) {
        val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val zipPat = Regex(
            "^" + Regex.escape(slug) +
                "-(?:latest|\\d{6,8})-free\\.gpkg(?:\\s*\\(\\d+\\))?" +
                "\\.zip(?:\\s*\\(\\d+\\))?$",
            RegexOption.IGNORE_CASE
        )
        var removed = 0
        var freedMb = 0L
        dl?.listFiles()?.forEach { f ->
            if (!f.isDirectory && zipPat.matches(f.name)) {
                val mb = f.length() / 1048576L
                if (f.delete()) {
                    removed++; freedMb += mb
                    Log.i(EXTRACT_TAG, "deleted download ${f.name} ($mb MB)")
                } else {
                    Log.w(EXTRACT_TAG, "could not delete download ${f.name}")
                }
            }
        }
        Log.i(EXTRACT_TAG, "downloads swept for $slug: $removed file(s), $freedMb MB")
    }

    /**
     * The workers' setForegroundSafely(text): 2.7e BUILDS the notification (creating its channel) and hands it to a shim that does
     * nothing (INPROCESS-2026-10-02) -- kept exactly so. 2.7e buildForegroundInfo of each worker (channel, name, title).
     */
    override fun foreground(worker: String, text: String) {
        val imp = worker == "import"
        val channelId = if (imp) "osm_import" else "osm_extract"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, if (imp) "OSM import" else "OSM extract", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
            .setContentTitle(if (imp) "Importing OSM trails" else "Extracting OSM trail data")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        @Suppress("UNUSED_VARIABLE") val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(0x0, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(0x0, n)
        }
        // INPROCESS-2026-10-02: setForegroundAsync is a no-op shim -- the import screen shows progress.
    }

    companion object {
        private const val STAGE_TAG = "OsmStage"
        private const val EXTRACT_TAG = "OsmExtract"
        /** OSM-LEGACY-MIGRATE-2026-07-28: once per process, not once per install (2.7e's flag). */
        @Volatile private var legacyChecked = false
    }
}
