package com.geeksville.mesh.convoy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * MIGRATION-2026-09-11: move the GroupTrack data out of shared storage.
 *
 * \u26d4 WHY. Google rejected the All Files Access declaration on 09-10. Whatever
 * the appeal returns, everything that does not NEED shared storage should stop
 * using it -- app-private external storage requires no permission, no
 * declaration and no review.
 *
 * \u26a0 THE MBTILES DO NOT MOVE HERE. ~17 GB against the ~415 MB everything else
 * comes to. They stay at the public root through release 1; release 2 decides.
 * That is why [GroupTrackStorage] has a separate `tileRoot()`.
 *
 * \u2b50 THE TREE BELOW THE ROOT IS IDENTICAL on both sides. Only the base moves,
 * so nothing is remapped and no caller changes.
 */
object GroupTrackMigration {

    private const val TAG = "GTMigration"

    /** Suffix on a source that has been superseded. */
    const val EXT_SUFFIX = "-EXT"

    /**
     * \u26d4 EXCLUDED FROM THE MOVE.
     *  \u2022 `maps` -- the MBTiles store, release 2's problem.
     *  \u2022 `*-journal` -- SQLite's own working files. Both are zero bytes on a
     *    cleanly closed database; copying one is meddling in the engine's
     *    business.
     *  \u2022 anything already suffixed -- a previous run's leftovers.
     *
     * \u2b50 AN EXCLUSION LIST, NOT AN INCLUSION LIST. Anything the app gains later
     * comes across on its own rather than being forgotten because nobody updated
     * a manifest.
     */
    private fun isExcluded(f: File): Boolean =
        f.name == "maps" ||
            f.name.endsWith("-journal") ||
            f.name.endsWith(EXT_SUFFIX)

    /** What one item's move came to. [ok] false stops the whole run. */
    data class ItemResult(
        val name: String,
        val ok: Boolean,
        val before: String,
        val after: String,
    ) {
        val line: String get() =
            (if (ok) "\u2713 " else "\u2717 ") + name + "  " + before +
                (if (before == after) "" else "  ->  $after")
    }

    // ── state, read from disk rather than stored ─────────────────────────

    /**
     * \u2b50 THE DATA IS THE RECORD. No preference file, nothing to lose, and it
     * self-heals: a half-finished run leaves the app reading external, and the
     * next launch simply tries again.
     */
    fun isComplete(ctx: Context): Boolean {
        val internal = internalRoot(ctx) ?: return false
        val db = File(internal, "grouptrack_spatial.db")
        // \u26a0 SIZE, NOT EXISTENCE. An empty schema is ~118,784 bytes and exists
        // perfectly happily; a real spatial DB is hundreds of MB.
        return db.exists() && db.length() > 1_000_000L
    }

    /** Is there anything left in shared storage to move? */
    fun externalHasData(): Boolean {
        val root = GroupTrackStorage.root()
        if (!root.exists()) return false
        return root.listFiles()?.any { !isExcluded(it) } == true
    }

    fun internalRoot(ctx: Context): File? =
        try { ctx.getExternalFilesDir(null) } catch (e: Exception) {
            Log.e(TAG, "no internal root: ${e.message}"); null
        }

    // ── step 0 \u2014 PROTOTYPE ONLY ────────────────────────────────────────

    /**
     * \u26a0\u26a0 SCAFFOLDING. Renames `*-EXT` back so the loop can be run again after a
     * pass that reached the rename.
     *
     * \u26d4 THIS MUST NOT SHIP. Fred's own rule, from removing the canary probe
     * hours earlier: *"the severity of forgetting to remove it is worse than
     * removing it for an instance that does not occur."* Code that reverses a
     * migration, living in a build where migration is one-way, can only ever
     * fire by accident -- on a directory somebody named `-EXT` for an unrelated
     * reason, or on a future change that reuses the suffix.
     */
    fun prototypeRestoreExtNames(): Int {
        val root = GroupTrackStorage.root()
        val found = root.listFiles()?.filter { it.name.endsWith(EXT_SUFFIX) } ?: return 0
        var n = 0
        for (f in found) {
            val back = File(root, f.name.removeSuffix(EXT_SUFFIX))
            if (back.exists()) {
                Log.w(TAG, "restore: ${back.name} already exists, leaving ${f.name}")
                continue
            }
            if (f.renameTo(back)) n++ else Log.e(TAG, "restore failed: ${f.name}")
        }
        if (n > 0) Log.i(TAG, "prototype: restored $n item(s) from $EXT_SUFFIX")
        return n
    }

    // ── step 2 \u2014 clear internal ────────────────────────────────────────

    /**
     * \u2b50 SAFE ONLY BEFORE THE RENAME. External is still intact at this point, so
     * a clear costs nothing and every run starts from the same place. \u26d4 After
     * the rename this would be destructive -- which is exactly why the rename is
     * the commitment point.
     *
     * \u26a0 Does NOT touch `maps` if one somehow exists internally.
     */
    fun clearInternal(ctx: Context): Boolean {
        val internal = internalRoot(ctx) ?: return false
        val kids = internal.listFiles() ?: return true
        for (f in kids) {
            if (f.name == "maps") continue
            if (!f.deleteRecursively()) {
                Log.e(TAG, "clearInternal: could not delete ${f.name}")
                return false
            }
        }
        Log.i(TAG, "clearInternal: internal root emptied")
        return true
    }

    // ── certification ────────────────────────────────────────────────────

    /**
     * \u26d4 A COMPARISON, NOT AN EXISTENCE CHECK. A zero-byte file exists.
     *
     * \u2b50 Databases are certified by ROW COUNT -- the strongest check available,
     * and it costs seconds even on 303 MB. \u26a0 If the copy is truncated or
     * corrupt the open itself throws, which is also a failure and is caught.
     */
    private fun describe(f: File): String = when {
        !f.exists() -> "missing"
        f.isDirectory -> {
            var n = 0; var b = 0L
            f.walkTopDown().forEach { if (it.isFile) { n++; b += it.length() } }
            "$n file(s), ${b} bytes"
        }
        f.name.endsWith(".db") -> {
            val rows = tableRowCount(f)
            "${f.length()} bytes" + (if (rows != null) ", $rows row(s)" else ", UNREADABLE")
        }
        else -> "${f.length()} bytes"
    }

    /**
     * Total rows across every user table. \u26a0 Read-only, and the handle is closed
     * in a finally -- leaving one open on a file about to be renamed is how a
     * migration wedges itself.
     */
    private fun tableRowCount(db: File): Long? {
        var h: SQLiteDatabase? = null
        return try {
            h = SQLiteDatabase.openDatabase(db.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY)
            var total = 0L
            h.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%'", null
            ).use { c ->
                while (c.moveToNext()) {
                    val t = c.getString(0)
                    try {
                        h.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { r ->
                            if (r.moveToFirst()) total += r.getLong(0)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "count failed on $t: ${e.message}")
                    }
                }
            }
            total
        } catch (e: Exception) {
            Log.e(TAG, "cannot open ${db.name}: ${e.message}")
            null
        } finally {
            try { h?.close() } catch (_: Exception) {}
        }
    }

    // ── step 3 \u2014 the move ───────────────────────────────────────────────

    /**
     * Copy every non-excluded item and certify each one.
     *
     * \u26d4 STOPS ON THE FIRST FAILURE. Fred, 09-11: *"we cannot proceed to test
     * unless every step ahead is processed and certified."* Nothing is renamed
     * and nothing deleted, so a failed run costs disk space rather than data.
     *
     * \u26a0 CLOSES THE DATABASES FIRST. They are already open by this point -- the
     * gate ran StartupHousekeeping.run(), which calls SpatialDbManager.init().
     * Copying an open SQLite file produces corruption that is invisible until
     * something reads the damaged page. close() also clears `initialized`, so
     * the next open re-resolves the path rather than handing back a stale handle
     * pointing at the old location.
     */
    fun migrate(ctx: Context, onItem: (ItemResult) -> Unit): Boolean {
        val internal = internalRoot(ctx) ?: run {
            onItem(ItemResult("internal storage", false, "unavailable", ""))
            return false
        }
        if (!internal.exists() && !internal.mkdirs()) {
            onItem(ItemResult("internal storage", false, "cannot create", ""))
            return false
        }

        try { SpatialDbManager.close() } catch (e: Exception) {
            Log.w(TAG, "close before copy: ${e.message}")
        }

        val root = GroupTrackStorage.root()
        val items = (root.listFiles() ?: emptyArray()).filter { !isExcluded(it) }
            .sortedBy { it.name.lowercase() }

        if (items.isEmpty()) {
            onItem(ItemResult("nothing to move", true, "external is empty", ""))
            return true
        }

        // \u26a0 FREE SPACE FIRST. Filling the device halfway through is a worse
        // failure than refusing to start.
        var need = 0L
        items.forEach { f -> f.walkTopDown().forEach { if (it.isFile) need += it.length() } }
        val free = internal.usableSpace
        if (free < need + (32L * 1024 * 1024)) {
            onItem(ItemResult("free space", false,
                "need ${need} bytes, have ${free}", ""))
            return false
        }
        Log.i(TAG, "migrate: ${items.size} item(s), $need bytes, $free free")

        for (src in items) {
            val dst = File(internal, src.name)
            val before = describe(src)
            val ok = try {
                if (dst.exists()) dst.deleteRecursively()
                src.copyRecursively(dst, overwrite = true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "copy failed ${src.name}: ${e.message}")
                false
            }
            val after = if (ok) describe(dst) else "copy failed"
            val certified = ok && before == after && after != "missing"
            onItem(ItemResult(src.name, certified, before, after))
            if (!certified) {
                Log.e(TAG, "CERTIFICATION FAILED ${src.name}: '$before' vs '$after'")
                return false
            }
            Log.i(TAG, "certified ${src.name}: $before")
        }

        // \u26a0 .nomedia must exist in BOTH roots afterwards -- maps/ stays behind at
        // the public root and still must not be indexed by the media scanner.
        try {
            val nm = File(internal, ".nomedia")
            if (!nm.exists()) nm.createNewFile()
        } catch (_: Exception) {}

        return true
    }

    // ── step 5 \u2014 the commitment point ──────────────────────────────────

    /**
     * Rename every moved source to `*-EXT`.
     *
     * \u2b50 A RENAME, NOT A DELETE, IN THIS BUILD. A stale path now hits a name that
     * does not exist and fails LOUDLY, instead of quietly reading old data and
     * looking fine until release 3 takes the permission away. \u26a0 And it is
     * reversible by hand if something turns out to still need it.
     *
     * \u26d4 CALL ONLY AFTER A FULLY CERTIFIED [migrate]. Renaming sources that were
     * not successfully copied loses them.
     *
     * \u26a0 `maps` is never renamed -- it has not moved.
     */
    fun renameSources(ctx: Context, onItem: (ItemResult) -> Unit): Boolean {
        if (!isComplete(ctx)) {
            onItem(ItemResult("rename", false,
                "internal data not certified -- refusing", ""))
            return false
        }
        val root = GroupTrackStorage.root()
        val items = (root.listFiles() ?: emptyArray()).filter { !isExcluded(it) }
            .sortedBy { it.name.lowercase() }
        var allOk = true
        for (src in items) {
            val dst = File(root, src.name + EXT_SUFFIX)
            val ok = try {
                if (dst.exists()) dst.deleteRecursively()
                src.renameTo(dst)
            } catch (e: Exception) {
                Log.e(TAG, "rename failed ${src.name}: ${e.message}"); false
            }
            onItem(ItemResult(src.name, ok,
                if (ok) "renamed to ${dst.name}" else "rename FAILED", ""))
            if (!ok) allOk = false
        }
        Log.i(TAG, "renameSources: ${items.size} item(s), allOk=$allOk")
        return allOk
    }
}
