package com.geeksville.mesh.convoy

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import com.grouptrack.shared.log.gtLogger

/**
 * FILEHOME-2026-11-17: the four app-owned directories under Downloads, and the one way to write into
 * them. Names are [GtFileOpen.Family]'s, which are Fred's.
 *
 *   Download/GroupTrack/                 convoy documents, backups, config
 *   Download/GroupTrack Maps/            map recipes, route recipes
 *   Download/GroupTrack Map Features/    routes, tracks, waypoints, rider trails
 *   Download/GroupTrack Rides/           ride packages, ride stats
 *
 * ⭐ NO PERMISSION IS NEEDED TO WRITE THESE, which is why this can exist before the install-flow
 * grant. MediaStore creates the subdirectory from RELATIVE_PATH and the app can read back the entries
 * it created, with no permission at all on API 29+. The tree grant is for a LATER job -- the internal
 * picker listing files the app did NOT create (a GPX the rider saved here from email) -- and nothing
 * in this file waits on it. The write path is ImportConverter.saveToDownloads's, which is proven.
 *
 * ⛔ THE MIME TYPE MUST BE octet-stream. CONVERTFIX-2026-10-30: with application/gpx+xml "Android does
 * not know it and renamed the file .bin". Every write here goes out as octet-stream so the name the
 * rider sees is the name given. Do not "correct" it to a real GPX type.
 *
 * ⛔ AND AN EXISTING NAME IS UPDATED, NOT RE-INSERTED. MediaStore does not overwrite: a second insert
 * of the same DISPLAY_NAME silently becomes "GroupTrack_ROUTES_2026-11-17 (1).gpx". Left to do that,
 * these folders would fill with numbered near-duplicates and quietly break the promise the backup's
 * own recovery steps make -- that importing something twice is safe because it is not added twice.
 * So [save] queries first and rewrites the existing row when there is one.
 * ⚠ OVERWRITE IS THE LOCAL POLICY ON PURPOSE (Fred 11-17): the local directories are the CURRENT
 * recovery position and stay small and browsable. History lives in the Drive mirror, which is
 * add-only and never deletes. These are two different jobs and this is the "current" one.
 */
object GtFileHome {

    private val log = gtLogger("GtFileHome")

    /**
     * ⛔ FOURTHDIR-2026-11-17: MATCH THE DIRECTORY EXACTLY. This was
     *     RELATIVE_PATH LIKE 'Download/GroupTrack%'
     * and "GroupTrack" is a PREFIX of "GroupTrack Maps", "GroupTrack Map Features" and
     * "GroupTrack Rides". Two consequences, both observed:
     *   1. ensure() runs in enum order and reached ROOT last, where has(ROOT, "README.txt") matched the
     *      README already written in GroupTrack Map Features and skipped creating GroupTrack at all.
     *      Fred: "I also asked for a fourth dr directory and did [not] see that created."
     *   2. WORSE, and not yet observed only because of (1): save() used the same match to find a row to
     *      replace, so a file destined for GroupTrack could have found a sibling folder's row and
     *      overwritten THAT file -- a write into the wrong directory, silently.
     * ROWSIN-2026-11-17: the exact-equality selection that replaced the LIKE then matched NOTHING, with
     * files visibly present -- Fred: "grouptrack was created. nothing cleared form existing directories."
     * Both selections are gone. [rowsIn] matches the path in Kotlin instead, where the trailing slash, a
     * lowercase "download/" and a doubled slash are all handled and a wrong answer can be logged.
     * ⛔ DO NOT REINTRODUCE A RELATIVE_PATH SELECTION HERE. Two tries, two silent wrong answers.
     */

    /** ARCHIVE-2026-11-17: is [name] already in [family]'s directory? Used by [ensure] so a launch does
     *  no work when the structure is already in place. */
    fun has(ctx: Context, family: GtFileOpen.Family, name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return File(legacyDir(family), name).isFile
        // ROWSIN-2026-11-17: through the one primitive, so has() cannot disagree with save()
        return rowsIn(ctx, family).any { it.name.equals(name, ignoreCase = true) }
    }

    /** ROWSIN-2026-11-17: one row of one of our directories. */
    class Row(val id: Long, val name: String, val uri: android.net.Uri)

    /**
     * ROWSIN-2026-11-17: the rows in [family]'s directory -- the ONE way this object looks anything up.
     *
     * ⛔ WHY THE PATH IS MATCHED IN KOTLIN AND NOT IN SQL. Two selections were tried and both were
     * wrong: `RELATIVE_PATH LIKE 'Download/GroupTrack%'` matched the SIBLING folders, because
     * "GroupTrack" is a prefix of "GroupTrack Maps"; the exact-equality version then matched NOTHING,
     * with files visibly present (Fred 11-17: "nothing cleared form existing directories"). Rather than
     * guess at a third selection, this queries the collection and compares the path here, where the
     * comparison can be made tolerant of the one thing that actually varies -- the trailing slash --
     * and where a wrong answer is debuggable instead of silent.
     * ⚠ It reads every Downloads row the app owns. That is a few hundred at most, and it runs on an
     * archive write or a folder listing, never in a loop.
     */
    fun rowsIn(ctx: Context, family: GtFileOpen.Family): List<Row> {
        val want = relativePath(family).trimEnd('/')
        val out = ArrayList<Row>()
        try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = (c.getString(2) ?: "").trimEnd('/')
                    if (!path.equals(want, ignoreCase = true)) continue
                    val name = c.getString(1) ?: continue
                    val id = c.getLong(0)
                    out.add(Row(id, name,
                        android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)))
                }
            }
        } catch (e: Exception) {
            log.e { "ROWSIN-2026-11-17: ${family.dirName} query failed: ${e.message}" }
        }
        log.i { "ROWSIN-2026-11-17: ${family.dirName} -- ${out.size} row(s)" }
        return out
    }

    /**
     * ROWSIN-2026-11-17: the files in [family]'s directory whose name ends with one of [exts].
     * Fred 11-17: "gpx, kms, and geo-json files are allowed" -- so the caller names what it handles.
     *
     * \u26d4 EMPTYEXTS-2026-11-18: AN EMPTY [exts] MEANS EVERY FILE, and it did the opposite.
     * GtFilePicker.exts() documents it plainly -- "ROOT is the DR directory and holds every artifact the
     * recovery job writes, so it filters nothing: an empty list means 'offer everything in the folder'"
     * -- but `exts.any { .. }` on an empty list is FALSE for every row, so this returned NOTHING. The
     * kdoc and the code were written the same day and said opposite things.
     * \u26a0 It only became reachable on 11-18: DRFILE made Family.ROOT the directory the backup writes
     * its five files into, so the DR listing is the first caller to pass an empty list. It would have
     * presented as an EMPTY RECOVERY FOLDER -- which reads as "the backup did not write", not as a
     * filter bug, and that is the expensive kind of wrong.
     */
    fun listFiles(ctx: Context, family: GtFileOpen.Family, exts: List<String>): List<Row> =
        rowsIn(ctx, family)
            .filter { r -> exts.isEmpty() || exts.any { r.name.endsWith(it, ignoreCase = true) } }
            .sortedBy { it.name.lowercase() }

    /** "Download/GroupTrack Maps" -- MediaStore creates the directory from this on the first write. */
    private fun relativePath(family: GtFileOpen.Family): String =
        Environment.DIRECTORY_DOWNLOADS + File.separator + family.dirName

    /** Pre-29 only: the real directory. minSdk is 26, so this path still has to work. */
    private fun legacyDir(family: GtFileOpen.Family): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), family.dirName)

    /**
     * Writes [content] as [name] into [family]'s directory, replacing a file of that name.
     * Returns the name written, or null with the reason logged -- an archive write must never take
     * down the job it is archiving for.
     */
    fun save(ctx: Context, family: GtFileOpen.Family, name: String, content: String): String? =
        save(ctx, family, name) { it.write(content.toByteArray()) }

    /** The same, for a file already on disk (an export, a PDF, a zip). */
    fun save(ctx: Context, family: GtFileOpen.Family, f: File): String? =
        save(ctx, family, f.name) { out -> f.inputStream().use { it.copyTo(out) } }

    private fun save(ctx: Context, family: GtFileOpen.Family, name: String, body: (java.io.OutputStream) -> Unit): String? {
        try {
            if (Build.VERSION.SDK_INT < 29) {
                // ⚠ No WRITE permission is held below 29 (ImportConverter carries the same note), so
                // this can fail -- and then it says so rather than reporting a save that did not happen.
                val dir = legacyDir(family).also { it.mkdirs() }
                val t = File(dir, name)
                t.outputStream().use(body)
                log.i { "FILEHOME-2026-11-17: ${family.dirName}/$name (legacy)" }
                return name
            }
            val cr = ctx.contentResolver
            val rel = relativePath(family)
            // ⛔ Replace, do not re-insert -- see the class note on "(1)".
            // ROWSIN-2026-11-17: the same primitive has() uses, so replace-vs-insert can never
            // disagree with "is it already there".
            val existing = rowsIn(ctx, family).firstOrNull { it.name.equals(name, ignoreCase = true) }?.id

            val uri = if (existing != null) {
                android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing)
            } else {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    // ⛔ CONVERTFIX-2026-10-30: octet-stream, or Android renames the file .bin
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    ?: throw IllegalStateException("could not create ${family.dirName}/$name")
            }
            // "wt" truncates: without it a shorter rewrite leaves the tail of the old file behind.
            cr.openOutputStream(uri, if (existing != null) "wt" else "w").use { o ->
                if (o == null) throw IllegalStateException("could not write ${family.dirName}/$name")
                body(o)
            }
            if (existing == null) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(uri, v, null, null)
            }
            log.i { "FILEHOME-2026-11-17: ${family.dirName}/$name (${if (existing != null) "replaced" else "new"})" }
            return name
        } catch (e: Exception) {
            log.e { "FILEHOME-2026-11-17: ${family.dirName}/$name FAILED: ${e.message}" }
            return null
        }
    }

    /**
     * DIRRESET-2026-11-17 (Fred): "lets delete and recreate directories." Removes every file the app
     * put in the four directories, then rebuilds the structure. Returns (deleted, created).
     *
     * ⛔ THROUGH MediaStore, NOT adb or File.delete. contentResolver.delete removes the ROW AND THE
     * FILE together. `adb shell rm` removes the file and leaves the row, after which has() reports a
     * README that is not there, ensure() skips the folder, and the directory is never recreated -- the
     * same symptom as the prefix bug, by a different route.
     *
     * ⚠ IT DELETES ARCHIVED DATA. That is the point of a reset, and it is safe only because these
     * directories are COPIES: routes, tracks, waypoints, trails and rides all still live in the app's
     * own databases, and the next changed-backup check writes the archive again.
     *
     * ⚠ ONE-TIME, AND GATED -- a deliberate deviation from CODE RULE 3's "ungated, no marker". That
     * rule is for CONVERGENCE jobs, which are idempotent; this one DESTROYS, so running it ungated on
     * every launch would wipe the archive every launch. The pref below is the gate, and it goes out
     * with the cert code before 2.8 ships.
     */
    /**
     * PATHDUMP-2026-11-17 ⚠ TEST-ONLY (CODE RULE 3 -- out with the cert code before 2.8 ships).
     *
     * ⛔ WHY THIS EXISTS INSTEAD OF A THIRD GUESS. The fourth directory is created now, so the
     * exact-path selection returns FALSE correctly -- but resetAll deleted nothing, which means the
     * same selection matched ZERO rows for folders that visibly contain files. Two different causes
     * produce that one symptom (the stored RELATIVE_PATH is not the string being compared, or the rows
     * are not visible to this query at all), and they need opposite fixes. The consequence is not
     * cosmetic: save() uses the same lookup to find a row to REPLACE, so a lookup that matches nothing
     * makes every save an INSERT, and MediaStore answers a duplicate name with "name (1)".
     *
     * So: ask MediaStore what it holds, with the WIDEST possible net, and print it.
     */
    fun dumpPaths(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) { log.i { "PATHDUMP-2026-11-17: API < 29, legacy files" }; return }
        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE,
        )
        // the widest net: every Downloads row whose path mentions GroupTrack at all
        for (uri in listOf(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                           MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))) {
            var n = 0
            try {
                ctx.contentResolver.query(uri, cols,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf("%GroupTrack%"), null)?.use { c ->
                    while (c.moveToNext()) {
                        n++
                        log.i {
                            "PATHDUMP-2026-11-17: " + uri.lastPathSegment +
                                " id=" + c.getLong(0) +
                                " name='" + (c.getString(1) ?: "") + "'" +
                                " path='" + (c.getString(2) ?: "") + "'" +
                                " owner=" + (c.getString(3) ?: "(none)") +
                                " pending=" + c.getInt(4) +
                                " size=" + c.getLong(5)
                        }
                    }
                }
                log.i { "PATHDUMP-2026-11-17: " + uri.lastPathSegment + " -- $n row(s) matching %GroupTrack%" }
            } catch (e: Exception) {
                log.e { "PATHDUMP-2026-11-17: " + uri.lastPathSegment + " query failed: " + e.message }
            }
        }
        // and what the app BELIEVES the path is, for each family
        for (f in GtFileOpen.Family.entries) log.i { "PATHDUMP-2026-11-17: expects path='" + relativePath(f) + "'" }
    }

    fun resetAll(ctx: Context): Pair<Int, Int> {
        dumpPaths(ctx)   // PATHDUMP-2026-11-17: before anything is touched
        var deleted = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            for (f in GtFileOpen.Family.entries) {
                try {
                    val ids = rowsIn(ctx, f).map { it.id }   // ROWSIN-2026-11-17
                    for (id in ids) {
                        try {
                            deleted += cr.delete(
                                android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                                null, null)
                        } catch (e: Exception) {
                            // not ours to delete (a different install wrote it): leave it, say so
                            log.w { "DIRRESET-2026-11-17: ${f.dirName} id=$id not deletable: ${e.message}" }
                        }
                    }
                    log.i { "DIRRESET-2026-11-17: ${f.dirName} -- ${ids.size} entry(ies) found" }
                } catch (e: Exception) {
                    log.e { "DIRRESET-2026-11-17: ${f.dirName} query failed: ${e.message}" }
                }
            }
        } else {
            for (f in GtFileOpen.Family.entries) {
                legacyDir(f).listFiles()?.forEach { if (it.isFile && it.delete()) deleted++ }
            }
        }
        val created = ensure(ctx)
        log.i { "DIRRESET-2026-11-17: deleted $deleted file(s), $created of ${GtFileOpen.Family.entries.size} directories ready" }
        return Pair(deleted, created)
    }

    /**
     * FILEHOME-2026-11-17: creates all four directories, so the rider sees the structure before
     * anything has been archived into it. Rule 6's exists-check-and-create.
     *
     * ⚠ MediaStore HAS NO "create an empty directory" CALL. A directory exists only because a file is
     * in it. So this writes a tiny README in each, which is also the honest thing to put there: a
     * rider who opens an unfamiliar folder in Downloads should be told what it is and that deleting it
     * is safe. The alternative -- an empty .nomedia -- creates the folder and explains nothing.
     */
    /**
     * DIRRESET-2026-11-17: the one-time reset, then the ordinary ensure() on every later launch.
     * ⛔ THE PREF KEY IS THE WHOLE GATE. Change the date in it to reset again; delete this function and
     * call ensure() directly when the cert code comes out before 2.8 ships.
     */
    fun resetOnce(ctx: Context): Int {
        val p = ctx.getSharedPreferences("gt_filehome", Context.MODE_PRIVATE)
        val key = "reset_2026-11-17b"   // PATHDUMP-2026-11-17: re-armed
        if (p.getBoolean(key, false)) return ensure(ctx)
        val (deleted, created) = resetAll(ctx)
        p.edit().putBoolean(key, true).apply()
        log.i { "DIRRESET-2026-11-17: one-time reset done ($deleted deleted, $created created)" }
        return created
    }

    fun ensure(ctx: Context): Int {
        var made = 0
        for (f in GtFileOpen.Family.entries) {
            // ARCHIVE-2026-11-17: skip what is already there. ensure() runs every launch, and save()
            // rewrites unconditionally, so without this it would touch MediaStore four times per start
            // for files that have not changed since the day they were written.
            if (has(ctx, f, "README.txt")) continue
            val text = "This folder holds your GroupTrack OG " + when (f) {
                GtFileOpen.Family.MAPS -> "map recipes. Tap one in GroupTrack OG to download those maps again."
                GtFileOpen.Family.MAP_FEATURES -> "routes, tracks, waypoints and rider trails. Tap one to import it."
                GtFileOpen.Family.RIDES -> "rides. Tap one to import it."
                GtFileOpen.Family.ROOT -> "convoy documents and anything recoverable that is not a map, a feature or a ride."
            } + "\n\nIt is written by the app so your data can be recovered on a new tablet.\n" +
                "Deleting these files does not affect the app -- they are copies.\n"
            if (save(ctx, f, "README.txt", text) != null) made++
        }
        log.i { "FILEHOME-2026-11-17: ensure() -- $made of ${GtFileOpen.Family.entries.size} directories ready" }
        return made
    }
}
