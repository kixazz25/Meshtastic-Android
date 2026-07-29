package com.geeksville.mesh.convoy

import android.content.ContentUris
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Which stage a state's OSM import is at. DERIVED FROM DISK, never stored.
 */
enum class OsmStage { ACQUIRE, REDUCE, IMPORT }

/**
 * What a C3 import covers.
 *
 * A sealed type rather than a nullable Bbox, deliberately: with `Bbox? = null`
 * every call site is ambiguous between "the user chose whole state" and "the
 * caller forgot to set it". Here whole-state is a value, the `when` is
 * exhaustive, and CODE RULE 1 does not apply because there is no null to
 * justify.
 */
sealed interface ImportScope {
    object WholeState : ImportScope
    data class Area(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double
    ) : ImportScope {
        /** "west,south,east,north" -- the form the ledger records. */
        fun asLedgerString(): String = "$west,$south,$east,$north"
    }
}

/**
 * OsmImportStage -- the rules engine.
 *
 * THE RULES (2026-07-28):
 *   R1  Stage is DERIVED FROM DISK, never stored. No stage counter, no status.
 *   R2  Only TWO paths are ever consulted: the zip and the skinny DB. The
 *       .gpkg is never a recovery point -- unzip + skinny is one atomic op.
 *   R3  EXISTENCE MUST MEAN COMPLETE. In-flight work uses .part / .tmp and is
 *       renamed atomically on success. Derivation only looks at final names.
 *   R4  The ledger never gates. Disk wins on disagreement.
 *   R5  Derivation runs at MOMENTS -- panel open, job completion, return from
 *       background. No file watcher; nothing outside the app writes here.
 *   R6  Derivation is PER-SLUG. States are independent.
 *   R7  On ACQUIRE entry, sweep debris only (.part / .tmp / .gpkg). NEVER
 *       blind-delete the directory -- step 1 stays enabled at IMPORT stage,
 *       and a wholesale clear there would destroy an import-ready skinny DB
 *       and the record of what was already imported.
 *
 * Verification exists because existence proves a RENAME happened, not that the
 * bytes are good. A failed verification deletes that one artifact, which drops
 * the stage by exactly one -- automatic, no dialog, and it costs one stage
 * rather than the whole run.
 */
object OsmImportStage {

    private const val TAG = "OsmStage"

    /**
     * OSM-SKINNY-CONSTANT-2026-07-28: ONE name for all fifty states.
     *
     * Fred: "we want the import to consistently run over a common name for the
     * skinnied file ready to be imported, not 50 state names of that file."
     * Only one state is ever in flight, so the slug in the filename made C3
     * and C4 carry it purely to open a file -- and the old name said
     * "osm_trails" while the file also holds places and natural.
     *
     * State identity lives in the DIRECTORY. statesInFlight() derives the slug
     * from dir.name and never from this file, so nothing is lost.
     *
     * A CONSTANT rather than a literal because there were two sites written
     * independently, which is how two become three and then disagree.
     */
    const val SKINNY_NAME = "ready_for_import.db"

    /**
     * Matches Geofabrik's free-layer GeoPackage archive, and browser duplicates
     * of it.
     *
     * OSM-DATED-FILENAME-2026-07-28: Geofabrik publishes the SAME product under two
     * spellings and the download page offers both --
     *
     *     utah-latest-free.gpkg.zip
     *     arizona-260728-free.gpkg.zip     <- seen in the field 2026-07-28
     *
     * Demanding "latest" made the probe report "extract not found" for a file
     * sitting right there in Downloads. Accept either.
     *
     * Group 1 is the slug, and it is everything before the separator -- NOT
     * "text before the first hyphen", which would reduce north-carolina-260728
     * to "north". Greedy matching backtracks to the LAST valid separator,
     * which is correct here because the date is a suffix.
     */
    private val ZIP_RE = Regex(
        """^([a-z0-9_-]+)-(?:latest|\d{6,8})-free\.gpkg(?:\s*\(\d+\))?\.zip(?:\s*\(\d+\))?$""",
        RegexOption.IGNORE_CASE
    )

    // -- paths --------------------------------------------------------------

    /**
     * OSM-PUBLIC-STORAGE-2026-07-28: the shared GroupTrack directory.
     *
     * A CONSTANT because the planning state file already uses this base path.
     * Spelling it twice is how two copies of the same path drift apart -- the
     * same reason SKINNY_NAME became a constant earlier today.
     */
    const val GROUPTRACK_DIR = "Documents/GroupTrack"

    /**
     * OSM working files live in SHARED storage, not app-private storage.
     *
     * Fred 2026-07-28: "we have hit roadblocks all day with data in the app
     * area. there is a reason we moved data to public area."
     *
     * ⭐ THE REASON, restated so it is not re-learned a third time: run-as is
     * BLOCKED on release builds, and Android 11+ blocks adb from another app's
     * Android/data. Together those leave app-private storage with NO
     * inspection route at all -- not adb pull, not a file manager, nothing.
     * Verifying what an extract produced would have required writing an export
     * feature to work around a directory choice.
     *
     * ⭐ IT IS ALSO A USER FIX. Nothing can browse Android/data, so a user who
     * downloaded the wrong state had no way to delete a 610 MB file from
     * inside the app or outside it.
     *
     * ⚠ Files here SURVIVE UNINSTALL and are NOT removed by Clear Storage.
     * For a large extract that is arguably correct -- a reinstall should not
     * cost another download -- but it makes CANCEL the only cleanup path.
     *
     * ⚠ ctx is unused now and kept deliberately: removing it would touch every
     * call site across four files for no behavioural gain, and it is what a
     * user-chosen directory would be derived from later.
     */
    fun rootDir(ctx: Context): File {
        val d = File(Environment.getExternalStorageDirectory(), "$GROUPTRACK_DIR/osm")
        if (!d.exists()) d.mkdirs()
        if (!legacyChecked) {
            legacyChecked = true
            try {
                migrateLegacy(ctx, d)
            } catch (e: Exception) {
                Log.e(TAG, "legacy migration failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return d
    }

    /** OSM-LEGACY-MIGRATE-2026-07-28: once per process, not once per install. */
    @Volatile
    private var legacyChecked = false

    /** Where OSM files lived before they moved to shared storage. */
    private fun legacyRootDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "osm")

    /**
     * OSM-LEGACY-MIGRATE-2026-07-28: rescue whatever is stranded in the old app-private
     * directory.
     *
     * WHY THIS IS CODE AND NOT A COMMAND. Nothing outside the app can reach
     * getExternalFilesDir() on a release build -- adb is blocked by Android 11+,
     * run-as is blocked by the release signing, and no file manager can browse
     * it. That is precisely why the directory moved. It also means the files
     * left behind can ONLY be moved by the app itself.
     *
     * MIGRATE RATHER THAN DELETE: the zip is ~630 MB and re-downloading it is
     * pure waste. Anything already at the new path WINS -- so re-extracting
     * before migrating is safe, and the stale copy is dropped rather than
     * overwriting fresher output.
     *
     * ⚠ RENAME ONLY. rootDir() is called from the main thread on every panel
     * refresh, and a 630 MB copy there is a guaranteed ANR. Both paths are on
     * /storage/emulated/0, so renameTo is a metadata operation. A rename that
     * fails anyway leaves the file ALONE and logs it -- silently destroying a
     * download the user waited on is worse than leaving an orphan behind.
     *
     * Every step is per-file and idempotent, so an interrupted run simply
     * resumes on the next launch.
     */
    private fun migrateLegacy(ctx: Context, newRoot: File) {
        val old = legacyRootDir(ctx)
        if (!old.exists() || !old.isDirectory) return
        Log.i(TAG, "legacy OSM directory found at ${old.absolutePath} -- migrating")

        var moved = 0
        var dropped = 0
        var failed = 0

        old.listFiles()?.forEach { entry ->
            if (!entry.isDirectory) {
                // Nothing but per-state directories belongs at this level.
                if (entry.delete()) dropped++
                return@forEach
            }
            val dest = File(newRoot, entry.name)
            if (!dest.exists()) dest.mkdirs()
            entry.listFiles()?.forEach { f ->
                val target = File(dest, f.name)
                when {
                    target.exists() -> {
                        // Newer output already there -- the old copy is stale.
                        if (f.delete()) dropped++
                    }
                    f.renameTo(target) -> moved++
                    else -> {
                        failed++
                        Log.w(TAG, "could not move ${f.name} (${f.length()} bytes) " +
                            "-- left in place rather than deleted")
                    }
                }
            }
            if (entry.listFiles()?.isEmpty() == true) entry.delete()
        }

        if (old.listFiles()?.isEmpty() == true) old.delete()
        Log.i(TAG, "legacy migration: moved=$moved droppedStale=$dropped failed=$failed")
    }

    fun dirFor(ctx: Context, slug: String): File {
        val d = File(rootDir(ctx), slug)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun zipFor(ctx: Context, slug: String): File =
        File(dirFor(ctx, slug), "$slug-latest-free.gpkg.zip")

    fun gpkgFor(ctx: Context, slug: String): File =
        File(dirFor(ctx, slug), "$slug.gpkg")

    fun skinnyFor(ctx: Context, slug: String): File =
        File(dirFor(ctx, slug), SKINNY_NAME)

    // -- derivation ---------------------------------------------------------

    /**
     * THE STATE MACHINE. Three file checks, and that is the whole of it.
     *
     * Verification is part of derivation on purpose: an artifact that exists
     * but does not verify is treated as absent AND removed, so the next call
     * derives the stage below it without any separate repair step.
     */
    fun stageOf(ctx: Context, slug: String): OsmStage {
        val skinny = skinnyFor(ctx, slug)
        if (skinny.exists()) {
            if (verifySkinny(skinny)) return OsmStage.IMPORT
            Log.w(TAG, "skinny DB failed verification, removing: ${skinny.name}")
            skinny.delete()
        }
        val zip = zipFor(ctx, slug)
        if (zip.exists()) {
            if (verifyZip(zip)) return OsmStage.REDUCE
            Log.w(TAG, "zip failed verification, removing: ${zip.name}")
            zip.delete()
        }
        return OsmStage.ACQUIRE
    }

    /**
     * Every state currently in flight.
     *
     * OSM-GENERIC-2026-07-28: a directory is NOT enough. dirFor() creates one as a
     * side effect of resolving a path, so merely deriving a stage used to leave
     * an empty directory that then reported as a state forever -- including
     * after cleanup had removed everything real. A state counts only if it
     * holds one of the two recovery points.
     */
    fun statesInFlight(ctx: Context): List<String> =
        rootDir(ctx).listFiles()
            ?.filter { dir ->
                dir.isDirectory && (
                    File(dir, "${dir.name}-latest-free.gpkg.zip").exists() ||
                        File(dir, SKINNY_NAME).exists()
                    )
            }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** "north-carolina" -> "North Carolina". Display only. */
    fun displayName(slug: String): String =
        slug.split("-", "_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { p ->
                p.replaceFirstChar { it.uppercase() }
            }

    // -- verification -------------------------------------------------------

    /**
     * We no longer issue the download ourselves, so there is no Content-Length
     * to compare against. Opening the central directory is therefore the
     * PRIMARY check rather than a secondary one -- and it must confirm a .gpkg
     * is actually inside, so picking the wrong file fails here at the gate
     * instead of part-way through C2.
     */
    fun verifyZip(f: File): Boolean {
        if (!f.exists() || f.length() < 1024L) return false
        return try {
            ZipFile(f).use { zf ->
                val hasGpkg = zf.entries().asSequence().any {
                    it.name.endsWith(".gpkg", ignoreCase = true)
                }
                if (!hasGpkg) Log.w(TAG, "zip has no .gpkg entry: ${f.name}")
                hasGpkg
            }
        } catch (e: Exception) {
            Log.w(TAG, "zip unreadable ${f.name}: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /**
     * The skinny DB verifies against its own recorded expectation, so a pass
     * that died part-way (and somehow got renamed) cannot present itself as
     * complete.
     */
    fun verifySkinny(f: File): Boolean {
        if (!f.exists() || f.length() < 4096L) return false
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                f.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            var rows = -1
            db.rawQuery("SELECT COUNT(*) FROM osm_trails", null).use { c ->
                if (c.moveToFirst()) rows = c.getInt(0)
            }
            if (rows <= 0) {
                Log.w(TAG, "skinny DB has no rows: ${f.name}")
                return false
            }
            var expected = -1
            try {
                db.rawQuery(
                    "SELECT value FROM subset_meta WHERE key = ?",
                    arrayOf("feature_count")
                ).use { c ->
                    if (c.moveToFirst()) expected = c.getString(0)?.toIntOrNull() ?: -1
                }
            } catch (_: Exception) {
                // subset_meta absent -- tolerated; the row count already passed.
            }
            if (expected >= 0 && expected != rows) {
                Log.w(TAG, "skinny DB count mismatch: rows=$rows expected=$expected")
                return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "skinny DB unreadable ${f.name}: ${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            try { db?.close() } catch (_: Exception) { }
        }
    }

    // -- debris -------------------------------------------------------------

    /**
     * R7. Safe by definition of the stage: reaching ACQUIRE means neither
     * recovery point exists, so anything else here is from a killed run.
     */
    /**
     * OSM-CANCEL-BUTTON-2026-07-28: throw a state away entirely.
     *
     * WHY THIS HAS TO EXIST. Originally: the working directory was under
     * getExternalFilesDir(), which nothing can browse on Android 11+, so a
     * wrong download was unremovable. That has since been fixed properly by
     * moving the directory to shared storage (see rootDir).
     *
     * It matters MORE now, not less. Files in shared storage SURVIVE UNINSTALL
     * and are NOT cleared by Clear Storage, so this is the only thing that
     * removes a 610 MB extract the user no longer wants.
     *
     * NOT the same as CLEANUP (C4). Cleanup is the normal exit after a
     * successful import and finalizes the ledger into history first. This is
     * ABANDON: nothing was imported, so there is no history to write, and both
     * recovery points go with everything else.
     *
     * Deliberately NOT recursive. The working directory is flat by design, so
     * a directory inside it means something is wrong -- and a recursive delete
     * rooted at a derived path is exactly the shape of mistake that removes
     * more than it was asked to.
     */
    fun discardState(ctx: Context, slug: String): Boolean {
        val dir = dirFor(ctx, slug)
        var ok = true
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                Log.w(TAG, "unexpected directory in working dir, left alone: ${f.name}")
                ok = false
            } else if (f.delete()) {
                n++
            } else {
                Log.w(TAG, "could not delete ${f.name}")
                ok = false
            }
        }
        if (ok && !dir.delete()) {
            // Not fatal: statesInFlight() requires a recovery point INSIDE a
            // directory, so an empty one left behind does not register as a
            // state. It just should not happen.
            Log.w(TAG, "removed $n file(s) but could not remove the directory itself")
        }
        // OSM-CANCEL-SWEEPS-LEGACY-2026-07-28: sweep the legacy location too.
        //
        // Normally there is nothing here -- migrateLegacy() runs at the first
        // path resolution of every process and clears it. What survives is the
        // one case migration refuses to handle: a FAILED rename, where the
        // file is deliberately left alone rather than deleted because throwing
        // away a 630 MB download the user waited on is worse than an orphan.
        //
        // CANCEL is where that calculation changes: the user has just asked
        // for this to be deleted.
        //
        // ⚠ SAME SLUG ONLY. If the legacy tree also holds another state, it is
        // left alone -- cancelling one state has never meant discarding
        // another, and a control that removes more than it names is one people
        // stop trusting.
        try {
            val legacyDir = File(legacyRootDir(ctx), slug)
            if (legacyDir.exists()) {
                var ln = 0
                legacyDir.listFiles()?.forEach { f ->
                    if (!f.isDirectory && f.delete()) ln++
                }
                legacyDir.delete()
                if (ln > 0) Log.i(TAG, "legacy sweep: removed $ln stranded file(s) for $slug")
            }
            val legacyRoot = legacyRootDir(ctx)
            if (legacyRoot.exists() && legacyRoot.listFiles()?.isEmpty() == true) {
                legacyRoot.delete()
            }
        } catch (e: Exception) {
            // Never fail a cancel over the legacy path -- the new location is
            // what the user can actually see, and it is already gone.
            Log.w(TAG, "legacy sweep skipped: ${e.javaClass.simpleName} ${e.message}")
        }

        Log.i(TAG, "discarded $slug ($n file(s) removed, ok=$ok)")
        return ok
    }

    fun sweepDebris(ctx: Context, slug: String): Int {
        val dir = dirFor(ctx, slug)
        var n = 0
        dir.listFiles()?.forEach { f ->
            val name = f.name.lowercase()
            val debris = name.endsWith(".part") ||
                name.endsWith(".tmp") ||
                name.endsWith(".gpkg")
            if (debris && f.delete()) {
                n++
                Log.i(TAG, "swept debris: ${f.name}")
            }
        }
        return n
    }

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
