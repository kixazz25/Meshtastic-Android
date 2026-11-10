package com.geeksville.mesh.convoy

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.geeksville.mesh.grouptrack.GtImportAndroid
import com.geeksville.mesh.grouptrack.GtOsmAndroid
import com.grouptrack.shared.osm.GtOsmStage
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Which stage a state's OSM import is at. DERIVED FROM DISK, never stored.
 */
// PBFSTAGE-2026-08-30: ACQUIRE / ACQUIRE_GPKG / PREPARE_TAGS / REDUCE / IMPORT (2.7e's rungs, unchanged).
enum class OsmStage { ACQUIRE, ACQUIRE_GPKG, PREPARE_TAGS, REDUCE, IMPORT }

/**
 * OsmImportStage -- the rules engine (R1-R7, 2026-07-28).
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR. The disk half -- the paths, stageOf, statesInFlight, the four verifiers, trailExtent,
 * countTrailsInBbox, discardState, sweepDebris, displayName -- runs the certified shared GtOsmStage (2.8 step 15g, 57cd2a8a7: Droid
 * 687,564 / 0 DIFF, iPad 231,365 / 0 DIFF) on the app's environment (GtImportAndroid: GroupTrackStorage.dir("osm")) and GtOsmAndroid
 * (2.7e's legacy rescue, once per process, and the legacy sweep in discardState -- moved there word for word). Every result is the
 * shared one, as File where 2.7e returned File. The Android-only half below (All Files Access, the MediaStore discovery, adoption,
 * deleteOriginal) is 2.7e's, UNCHANGED -- a browser download found through MediaStore has no iPad equivalent. The 2.7e file is in git
 * history (57cd2a8a7) and in the TEST-ONLY OsmImportStageCertOld.
 */
object OsmImportStage {

    private const val TAG = "OsmStage"

    /** OSM-SKINNY-CONSTANT-2026-07-28: ONE name for all fifty states. */
    const val SKINNY_NAME = GtOsmStage.SKINNY_NAME

    /** OSM-DATED-FILENAME-2026-07-28: Geofabrik's free-layer GeoPackage archive (either spelling) and browser duplicates of it. */
    private val ZIP_RE = Regex(
        """^([a-z0-9_-]+)-(?:latest|\d{6,8})-free\.gpkg(?:\s*\(\d+\))?\.zip(?:\s*\(\d+\))?$""",
        RegexOption.IGNORE_CASE
    )

    /** OSM-PUBLIC-STORAGE-2026-07-28. */
    const val GROUPTRACK_DIR = GtOsmStage.GROUPTRACK_DIR
    /** The interim tag table: osm_id -> the access tags Geofabrik strips. */
    const val TAGS_NAME = GtOsmStage.TAGS_NAME

    private fun stage(ctx: Context) = GtOsmStage(GtImportAndroid(ctx), GtOsmAndroid(ctx))
    private fun f(p: okio.Path) = File(p.toString())

    // -- paths (the shared ones) --------------------------------------------
    fun rootDir(ctx: Context): File = f(stage(ctx).rootDir())
    fun dirFor(ctx: Context, slug: String): File = f(stage(ctx).dirFor(slug))
    fun zipFor(ctx: Context, slug: String): File = f(stage(ctx).zipFor(slug))
    fun gpkgFor(ctx: Context, slug: String): File = f(stage(ctx).gpkgFor(slug))
    fun skinnyFor(ctx: Context, slug: String): File = f(stage(ctx).skinnyFor(slug))
    fun pbfFor(ctx: Context, slug: String): File = f(stage(ctx).pbfFor(slug))
    fun tagsFor(ctx: Context, slug: String): File = f(stage(ctx).tagsFor(slug))

    // -- derivation / verification / extent / discard (the shared ones) -----
    fun stageOf(ctx: Context, slug: String): OsmStage = OsmStage.valueOf(stage(ctx).stageOf(slug).name)
    fun statesInFlight(ctx: Context): List<String> = stage(ctx).statesInFlight()
    /** "north-carolina" -> "North Carolina". Display only. */
    fun displayName(slug: String): String = GtOsmStage.displayName(slug)
    fun verifyPbf(f: File): Boolean = shared().verifyPbf(f.toOkioPath())
    fun verifyTags(f: File): Boolean = shared().verifyTags(f.toOkioPath())
    fun verifyZip(f: File): Boolean = shared().verifyZip(f.toOkioPath())
    fun verifySkinny(f: File): Boolean = shared().verifySkinny(f.toOkioPath())
    /** [s, w, n, e] or null -- the order the ledger's pending record uses. */
    fun trailExtent(ctx: Context, slug: String): DoubleArray? = stage(ctx).trailExtent(slug)
    fun countTrailsInBbox(ctx: Context, slug: String, s: Double, w: Double, n: Double, e: Double): Int = stage(ctx).countTrailsInBbox(slug, s, w, n, e)
    fun discardState(ctx: Context, slug: String): Boolean = stage(ctx).discardState(slug)
    fun sweepDebris(ctx: Context, slug: String): Int = stage(ctx).sweepDebris(slug)

    /**
     * The verifiers take a File and no context in 2.7e; the shared ones read only the file system (okio's SYSTEM) -- so they run on an
     * environment that gives exactly that and refuses everything else (a call to anything else is a bug, loudly).
     */
    private object VerifyEnv : com.grouptrack.shared.imports.GtImportEnv {
        private fun no(): Nothing = throw IllegalStateException("OsmImportStage verifiers use the file system only")
        override val clock: com.grouptrack.shared.imports.GtClock get() = no()
        override val ids: com.grouptrack.shared.imports.GtIds get() = no()
        override val files = object : com.grouptrack.shared.imports.GtFiles {
            override val fs: okio.FileSystem = okio.FileSystem.SYSTEM
            override fun setLastModified(path: okio.Path, epochMs: Long): Boolean = no()
            override fun lastModified(path: okio.Path): Long = no()
            override fun list(dir: okio.Path): List<okio.Path> = no()
        }
        override val paths: com.grouptrack.shared.imports.GtImportPaths get() = no()
        override val http: com.grouptrack.shared.imports.GtHttp get() = no()
        override val trailClass: com.grouptrack.shared.imports.GtTrailClass get() = no()
        override val platform: com.grouptrack.shared.imports.GtImportPlatform get() = no()
    }
    private object VerifyPlat : com.grouptrack.shared.osm.GtOsmPlatform {
        private fun no(): Nothing = throw IllegalStateException("OsmImportStage verifiers use the file system only")
        override fun onOsmRoot(root: okio.Path) = no()
        override fun discardLegacy(slug: String) = no()
        override fun usableSpace(dir: okio.Path): Long = no()
        override fun sweepDownloadedZips(slug: String) = no()
        override fun foreground(worker: String, text: String) = no()
    }
    private fun shared() = GtOsmStage(VerifyEnv, VerifyPlat)

    // -- C1 support: find what the user downloaded --------------------------

    /**
     * Reading the public Downloads folder needs All Files Access.
     *
     * PRECONDITION, NOT A PROBE RESULT. Check this when the panel LAUNCHES and
     * again on RETURN FROM BACKGROUND (R5 moments) -- not when stage 2 is
     * tapped. A revoked grant makes every stage impossible, so it blocks the
     * whole panel rather than presenting itself as one stage's failure.
     *
     * NOTE: Settings -> Clear storage SILENTLY REVOKES this grant, and it has
     * already caused one import bug on this project. If it reported as "no file
     * found" the tester would wait forever for something that can never happen.
     */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    // -- discovery via MediaStore ------------------------------------------
    //
    // We cannot control or predict where a browser writes. The destination is
    // user-configurable, differs per browser, and on some devices is not
    // internal storage at all. So we do NOT scan a path -- we ask MediaStore,
    // which every browser registers its downloads with regardless of location.

    data class Candidate(
        val uri: Uri,
        val displayName: String,
        val slug: String,
        val bytes: Long
    )

    /**
     * What stage 2's tap found.
     *
     * NOTE THE ABSENCE: there is no StillDownloading case, because we cannot
     * detect one. Android browsers vary in how in-progress downloads are named
     * and whether they are visible at all, so "not downloaded yet" and
     * "downloading right now" are INDISTINGUISHABLE from here. They collapse
     * into NotFound, and the message must not claim to know which it is.
     *
     * NoPermission is also absent -- that is a launch-time precondition, not a
     * probe outcome. See hasAllFilesAccess().
     */
    sealed interface AcquireProbe {
        /** Nothing matching. May not be downloaded yet, may still be running. */
        object NotFound : AcquireProbe

        /** Present but truncated or not a Geofabrik extract. Waiting won't fix it. */
        data class BadFile(val candidate: Candidate) : AcquireProbe

        /** Exactly one. Ready to adopt. */
        data class Found(val candidate: Candidate) : AcquireProbe

        /** Several matched -- ask, never guess. This is the (n)-duplicate case. */
        data class Several(val all: List<Candidate>) : AcquireProbe
    }

    /**
     * Stage 2's tap IS the poll (R5) -- no file watcher, no background scan.
     * Tapping before the file exists is harmless and reports NotFound.
     */
    fun probeDownloads(ctx: Context): AcquireProbe {
        val found = findCandidates(ctx)
        return when {
            found.isEmpty() -> AcquireProbe.NotFound
            found.size > 1 -> AcquireProbe.Several(found)
            else -> AcquireProbe.Found(found.first())
        }
    }

    /** Newest first. Name-matched only -- contents are verified after adoption. */
    fun findCandidates(ctx: Context): List<Candidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val out = mutableListOf<Candidate>()
        val cols = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cols,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("%-free.gpkg%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val m = ZIP_RE.find(name) ?: continue
                    out.add(
                        Candidate(
                            uri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(iId)
                            ),
                            displayName = name,
                            slug = m.groupValues[1].lowercase(),
                            bytes = c.getLong(iSize)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query failed: ${e.javaClass.simpleName} ${e.message}")
        }
        Log.i(TAG, "candidates found: ${out.size}")
        return out
    }

    // -- adoption -----------------------------------------------------------

    /**
     * Copy the download into the state's working directory under the canonical
     * name, then verify.
     *
     * A copy rather than a move, deliberately: MediaStore hands back a
     * content:// URI, which cannot be renameTo()'d and may live on a different
     * volume. Copying costs one pass over ~314 MB and buys back full control of
     * recovery point 1 -- it lands in a directory only GroupTrack writes to,
     * so nothing outside the app can move or delete it between stages.
     *
     * Writes to .part and renames on success (R3), so a killed copy never
     * presents itself as a complete artifact.
     */
    fun adoptCandidate(
        ctx: Context,
        c: Candidate,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean {
        val target = zipFor(ctx, c.slug)
        val part = File(target.parentFile, "${target.name}.part")
        try {
            if (part.exists()) part.delete()
            ctx.contentResolver.openInputStream(c.uri)?.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        copied += n
                        onProgress(copied, c.bytes)
                    }
                    output.flush()
                }
            } ?: run {
                Log.e(TAG, "openInputStream returned null for ${c.displayName}")
                return false
            }

            if (c.bytes > 0 && part.length() != c.bytes) {
                Log.e(TAG, "size mismatch: copied=${part.length()} expected=${c.bytes}")
                part.delete()
                return false
            }
            if (!verifyZip(part)) {
                Log.e(TAG, "copied file failed zip verification: ${c.displayName}")
                part.delete()
                return false
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                Log.e(TAG, "rename failed: ${part.name} -> ${target.name}")
                part.delete()
                return false
            }
            Log.i(TAG, "adopted ${c.displayName} -> ${target.absolutePath} (${target.length()} bytes)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "adopt failed: ${e.javaClass.simpleName} ${e.message}")
            part.delete()
            return false
        }
    }

    /**
     * Remove the browser's copy once adoption has verified. Optional and
     * gated -- it reclaims ~314 MB the tester would otherwise never know to
     * clean up, but it deletes a file the app did not create.
     */
    fun deleteOriginal(ctx: Context, c: Candidate): Boolean = try {
        val n = ctx.contentResolver.delete(c.uri, null, null)
        Log.i(TAG, "deleted original ${c.displayName}: rows=$n")
        n > 0
    } catch (e: Exception) {
        Log.w(TAG, "could not delete original: ${e.javaClass.simpleName} ${e.message}")
        false
    }
}
