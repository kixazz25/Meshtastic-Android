package com.geeksville.mesh.convoy

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CONVERSION-2026-09-12: copy GroupTrack out of shared storage, through a SAF
 * tree grant, into app-private storage.
 *
 * \u26d4 WHY IT EXISTS. MANAGE_EXTERNAL_STORAGE is leaving the manifest, so the app
 * loses its own view of `Documents`. The rider grants the folder once and the
 * app copies what it needs out of it. \u2b50 No permission, no declaration, no
 * review.
 *
 * \u2b50 THE STRUCTURE IS UNCHANGED. Only the base moves:
 *     Documents/GroupTrack/  ->  <files>/GroupTrack/
 *     Documents/my_tracks/   ->  <files>/my_tracks/
 * \u26a0 my_tracks is a SIBLING of GroupTrack, not a child, exactly as the two sit
 * in Documents today.
 *
 * \u26d4 THIS ENGINE HAS NO UI AND NO DELETES. The picker, the screen and the
 * cleanup are separate; this is the part that must be right first.
 */
object GroupTrackConversion {

    private const val TAG = "GTConversion"

    /** \u2b50 The record IS the flag. Present and complete means never again. */
    const val RECORD_NAME = "conversion_record.json"

    /** Schema of the record, so a future reader knows what it is looking at. */
    private const val RECORD_VERSION = 1

    /** \u26d4 The two folders. NOTHING ELSE IN THE GRANTED TREE IS READ. */
    private const val DIR_GROUPTRACK = "GroupTrack"
    private const val DIR_MYTRACKS = "my_tracks"

    /**
     * \u26d4 EXCLUDED FROM THE COPY.
     *  \u2022 `maps` -- ~17 GB. SAF has no MOVE, only COPY, so copying needs room
     *    for BOTH. SAT.mbtiles alone is 16 GB in ONE FILE and cannot be split.
     *  \u2022 `*-journal` -- SQLite's working files, zero bytes on a clean close.
     *    Copying one is meddling in the engine's business.
     * \u2b50 An EXCLUSION list, not an inclusion one: anything the app gains later
     * comes across on its own rather than being forgotten.
     */
    private fun isExcluded(name: String): Boolean =
        name == "maps" || name.endsWith("-journal")

    // ── the result ──────────────────────────────────────────────────────

    data class Item(
        val path: String,
        val srcBytes: Long,
        val dstBytes: Long,
        val ok: Boolean,
        val error: String? = null,
    )

    data class StepResult(
        val name: String,
        val found: Boolean,
        val items: List<Item>,
        val ok: Boolean,
        val error: String? = null,
        val startedAt: Long,
        val endedAt: Long,
    ) {
        val copied: Int get() = items.count { it.ok }
        val bytes: Long get() = items.filter { it.ok }.sumOf { it.dstBytes }
    }

    data class Outcome(
        val ok: Boolean,
        val treeUri: String,
        val steps: List<StepResult>,
        val error: String? = null,
    )

    // ── the record ──────────────────────────────────────────────────────

    private fun recordFile(ctx: Context): File? =
        GroupTrackStorage.internalBase()?.let { File(it, RECORD_NAME) }

    /**
     * \u2b50 THE TRIGGER. Present and complete means the conversion never runs again.
     *
     * \u26d4 A FLAG, NOT A DISK CHECK. `Documents` belongs to the rider and anything
     * can end up in it; a migration that decided by looking for a folder NAME
     * would re-fire on an unrelated file months later and offer to import over
     * live data.
     *
     * \u26a0 It lives in internal storage, so it dies with an uninstall -- which is
     * correct: an uninstall wipes internal storage anyway and a reinstall
     * genuinely should offer the conversion again.
     */
    fun isComplete(ctx: Context): Boolean {
        val f = recordFile(ctx) ?: return false
        if (!f.exists()) return false
        return try {
            JSONObject(f.readText()).optBoolean("complete", false)
        } catch (e: Exception) {
            Log.w(TAG, "record unreadable: ${e.message}")
            false
        }
    }

    /** The raw record, for the diagnostics screen and the problem report. */
    fun readRecord(ctx: Context): String? =
        recordFile(ctx)?.takeIf { it.exists() }?.let {
            try { it.readText() } catch (e: Exception) {
                Log.w(TAG, "readRecord: ${e.message}"); null
            }
        }

    /**
     * \u26a0 DETAILED ENOUGH TO TROUBLESHOOT, because once storage is internal
     * `adb shell cat` and `ls` no longer reach the app's files and `run-as` is
     * refused on release builds. \u26d4 THIS RECORD IS THE ONLY WINDOW LEFT: per
     * item, both sizes and whether they matched; the exception text, not just
     * "failed"; the granted URI, so it is clear what the rider actually picked;
     * and a timestamp per step, which distinguishes a hang from an error.
     */
    private fun writeRecord(ctx: Context, outcome: Outcome) {
        val f = recordFile(ctx) ?: run {
            Log.e(TAG, "no internal base -- cannot write the record")
            return
        }
        try {
            val steps = JSONArray()
            for (s in outcome.steps) {
                val items = JSONArray()
                for (i in s.items) {
                    items.put(JSONObject().apply {
                        put("path", i.path)
                        put("srcBytes", i.srcBytes)
                        put("dstBytes", i.dstBytes)
                        put("ok", i.ok)
                        i.error?.let { put("error", it) }
                    })
                }
                steps.put(JSONObject().apply {
                    put("step", s.name)
                    put("found", s.found)
                    put("ok", s.ok)
                    put("copied", s.copied)
                    put("bytes", s.bytes)
                    put("startedAt", s.startedAt)
                    put("endedAt", s.endedAt)
                    put("ms", s.endedAt - s.startedAt)
                    s.error?.let { put("error", it) }
                    put("items", items)
                })
            }
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().apply {
                put("recordVersion", RECORD_VERSION)
                put("complete", outcome.ok)
                put("treeUri", outcome.treeUri)
                put("writtenAt", System.currentTimeMillis())
                outcome.error?.let { put("error", it) }
                put("steps", steps)
            }.toString(2))
            Log.i(TAG, "record written: ${f.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "writeRecord FAILED: ${e.message}")
        }
    }

    // ── the copy ────────────────────────────────────────────────────────

    /**
     * Copy from a granted tree into app-private storage.
     *
     * @param treeUri the tree the rider granted -- `Documents`.
     *
     * \u26d4 THE DATABASES MUST BE CLOSED. This replaces the files
     * SpatialDbManager opens, and an open SQLite file written underneath is
     * corrupt in a way that stays invisible until something reads the damaged
     * page. \u2b50 close() also clears `initialized`, so the next open re-resolves
     * the path rather than handing back a stale handle -- both halves matter,
     * and that is the map-source fallback lock of 09-10 all over again.
     *
     * \u26a0 NOTHING IS DELETED HERE. Not the sources, not `maps`. A failed run
     * costs disk space, not data.
     */
    fun run(ctx: Context, treeUri: Uri, onProgress: ((String) -> Unit)? = null): Outcome {
        Log.i(TAG, "=== CONVERSION START === tree=$treeUri")

        try { SpatialDbManager.close() } catch (e: Exception) {
            Log.w(TAG, "close before copy: ${e.message}")
        }

        val internal = GroupTrackStorage.internalBase()
        if (internal == null) {
            val msg = "no internal base -- cannot copy"
            Log.e(TAG, msg)
            return Outcome(false, treeUri.toString(), emptyList(), msg)
        }
        if (!internal.exists() && !internal.mkdirs()) {
            val msg = "cannot create ${internal.absolutePath}"
            Log.e(TAG, msg)
            return Outcome(false, treeUri.toString(), emptyList(), msg)
        }

        val tree = DocumentFile.fromTreeUri(ctx, treeUri)
        if (tree == null || !tree.isDirectory) {
            val msg = "granted tree is not readable"
            Log.e(TAG, msg)
            return Outcome(false, treeUri.toString(), emptyList(), msg)
        }

        val steps = ArrayList<StepResult>()
        for (name in listOf(DIR_GROUPTRACK, DIR_MYTRACKS)) {
            steps.add(copyFolder(ctx, tree, name, File(internal, name), onProgress))
        }

        // \u2b50 "Found nothing" IS SUCCESS, not failure. A new install grants the
        // tree, the engine looks, there is no GroupTrack -- that resolves the
        // conversion. \u26d4 Treat it as incomplete and a new rider is asked every
        // launch forever.
        val ok = steps.all { it.ok }
        val outcome = Outcome(ok, treeUri.toString(), steps)
        writeRecord(ctx, outcome)
        Log.i(TAG, "=== CONVERSION ${if (ok) "DONE" else "FAILED"} ===")
        return outcome
    }

    // ══════════════════════════════════════════════════════════════════
    //  CONVMAPS-2026-09-12 -- the operations that TOUCH THE SOURCE.
    //  \u26a0 The copy above can be re-run all afternoon. These cannot.
    // ══════════════════════════════════════════════════════════════════

    /** Suffix on a source the conversion has finished with. */
    const val EXT_SUFFIX = "-EXT"

    /** `Documents/maps` -- where the tiles wait once GroupTrack is renamed away. */
    private const val DIR_MAPS_PARKED = "maps"

    data class MapsInfo(
        val files: List<Pair<String, Long>>,
        val largest: Long,
        val total: Long,
        val free: Long,
        val reserve: Long,
    ) {
        /**
         * \u26d4 THE LARGEST SINGLE FILE, NOT THE TOTAL. With copy-then-delete the
         * peak requirement is whichever file is biggest -- but SAT.mbtiles is
         * 16 GB in ONE FILE and no loop can split it, so that is the floor.
         * \u26a0 PLUS A RESERVE: a device run down to nearly full slows badly and
         * Android starts refusing writes. Leaving it unusable is not a success.
         */
        val canCopy: Boolean get() = free > largest + reserve
        val shortfall: Long get() = (largest + reserve) - free
    }

    /**
     * What is in `maps`, and is there room. \u26a0 Reads only -- nothing moves.
     *
     * @param parked true to look in `Documents/maps` (already moved out),
     *               false to look in `GroupTrack/maps`.
     */
    fun inspectMaps(ctx: Context, tree: DocumentFile, parked: Boolean): MapsInfo? {
        val root = if (parked) {
            tree.listFiles().firstOrNull { it.isDirectory && it.name == DIR_MAPS_PARKED }
        } else {
            tree.listFiles().firstOrNull { it.isDirectory && it.name == DIR_GROUPTRACK }
                ?.listFiles()?.firstOrNull { it.isDirectory && it.name == "maps" }
        } ?: return null

        val found = ArrayList<Pair<String, Long>>()
        fun walk(d: DocumentFile, prefix: String) {
            for (c in d.listFiles()) {
                val n = c.name ?: continue
                if (c.isDirectory) walk(c, "$prefix/$n")
                // \u26a0 journals are zero bytes on a clean close and are not ours
                else if (!n.endsWith("-journal")) found.add("$prefix/$n" to c.length())
            }
        }
        walk(root, root.name ?: "maps")

        val internal = GroupTrackStorage.internalBase()
        val free = internal?.usableSpace ?: 0L
        // \u26a0 The reserve: 10% of the volume or 5 GB, whichever is larger.
        val reserve = maxOf(5L * 1024 * 1024 * 1024, (internal?.totalSpace ?: 0L) / 10)
        return MapsInfo(
            files = found.sortedByDescending { it.second },
            largest = found.maxOfOrNull { it.second } ?: 0L,
            total = found.sumOf { it.second },
            free = free,
            reserve = reserve,
        )
    }

    /**
     * Rename `GroupTrack/maps` out to `Documents/maps`.
     *
     * \u26d4 WHY IT MOVES AT ALL. When the copy finishes, GroupTrack is renamed away
     * -- and maps lives INSIDE it. Fred, 09-12: *"moving maps under Documents is
     * the best solution; we know exactly where it is and the migration progressed
     * to that point."*
     *
     * \u26a0 THE ALTERNATIVE WAS REJECTED FOR A REASON. Leaving GroupTrack in place
     * to hold maps *"masks the location of where we are retrieving other
     * components -- we could run fine until we finish copying maps."* A stale
     * path would keep reading the old folder and nobody would notice until it
     * finally went.
     *
     * \u2b50 A RENAME WITHIN THE TREE, NOT A COPY. Instant, whatever the size.
     * \u26a0 DocumentsContract.moveDocument is not supported by every provider --
     * if this fails the whole maps-outstanding design needs rethinking, so it is
     * worth knowing early.
     */
    fun moveOutMaps(ctx: Context, treeUri: Uri): Boolean {
        return try {
            val tree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return false
            val gt = tree.listFiles().firstOrNull {
                it.isDirectory && it.name == DIR_GROUPTRACK
            } ?: run {
                Log.i(TAG, "moveOutMaps: no GroupTrack -- nothing to do")
                return true
            }
            val maps = gt.listFiles().firstOrNull {
                it.isDirectory && it.name == "maps"
            } ?: run {
                Log.i(TAG, "moveOutMaps: no maps -- nothing to do")
                return true
            }
            val moved = android.provider.DocumentsContract.moveDocument(
                ctx.contentResolver, maps.uri, gt.uri, tree.uri)
            Log.i(TAG, "moveOutMaps: " + (if (moved != null) "moved" else "FAILED"))
            moved != null
        } catch (e: Exception) {
            Log.e(TAG, "moveOutMaps FAILED: ${e.message}")
            false
        }
    }

    /**
     * Copy the parked maps in, one file at a time, deleting each source as it
     * lands.
     *
     * \u2b50 PER FILE FOR RESUMABILITY, NOT SPACE. SAT.mbtiles is 16 GB in ONE FILE
     * and no loop can split it -- the space requirement is the same either way.
     * What this buys is that a failure on the third leaves the first two DONE,
     * and the next attempt skips them because the source is gone.
     *
     * \u26d4 DELETE ONLY AFTER THE SIZE MATCHES. A source removed on a short write
     * is the one loss this whole design exists to avoid.
     *
     * \u26a0 LARGEST FIRST. If space runs out it does so on the file that was never
     * going to fit, rather than after an hour spent on the small ones.
     */
    fun copyMaps(
        ctx: Context,
        treeUri: Uri,
        onProgress: ((String) -> Unit)? = null,
    ): StepResult {
        val started = System.currentTimeMillis()
        val items = ArrayList<Item>()
        return try {
            val tree = DocumentFile.fromTreeUri(ctx, treeUri)
                ?: return StepResult("maps", false, items, false,
                    "tree unreadable", started, System.currentTimeMillis())
            val parked = tree.listFiles().firstOrNull {
                it.isDirectory && it.name == DIR_MAPS_PARKED
            } ?: return StepResult("maps", false, items, true, null,
                started, System.currentTimeMillis())

            val internal = GroupTrackStorage.internalBase()
                ?: return StepResult("maps", true, items, false,
                    "no internal base", started, System.currentTimeMillis())
            val dest = File(File(internal, DIR_GROUPTRACK), "maps")

            copyMapsInto(ctx, parked, dest, "maps", items, onProgress)
            val bad = items.count { !it.ok }
            StepResult("maps", true, items, bad == 0,
                if (bad > 0) "$bad file(s) failed" else null,
                started, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "copyMaps FAILED: ${e.message}")
            StepResult("maps", true, items, false, e.message,
                started, System.currentTimeMillis())
        }
    }

    private fun copyMapsInto(
        ctx: Context,
        src: DocumentFile,
        dest: File,
        prefix: String,
        items: MutableList<Item>,
        onProgress: ((String) -> Unit)?,
    ) {
        if (!dest.exists()) dest.mkdirs()
        // \u26a0 Largest first -- see copyMaps.
        val children = src.listFiles().sortedByDescending { it.length() }
        for (child in children) {
            val cname = child.name ?: continue
            if (cname.endsWith("-journal")) continue
            val target = File(dest, cname)
            if (child.isDirectory) {
                copyMapsInto(ctx, child, target, "$prefix/$cname", items, onProgress)
                continue
            }
            val srcBytes = child.length()
            onProgress?.invoke("$prefix/$cname (${srcBytes / 1024 / 1024} MB)")
            try {
                ctx.contentResolver.openInputStream(child.uri).use { input ->
                    if (input == null) throw IllegalStateException("no input stream")
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val dstBytes = target.length()
                val ok = dstBytes == srcBytes
                if (ok) {
                    // \u26d4 ONLY NOW. A source deleted on a short write is the one
                    // loss this design exists to avoid.
                    if (!child.delete()) Log.w(TAG, "$prefix/$cname copied but not deleted")
                } else {
                    Log.e(TAG, "$prefix/$cname SIZE MISMATCH $srcBytes != $dstBytes")
                }
                items.add(Item("$prefix/$cname", srcBytes, dstBytes, ok,
                    if (ok) null else "size mismatch"))
            } catch (e: Exception) {
                Log.e(TAG, "$prefix/$cname FAILED: ${e.message}")
                items.add(Item("$prefix/$cname", srcBytes, 0L, false, e.message))
            }
        }
    }

    /**
     * Rename the two source folders to `*-EXT`.
     *
     * \u26d4 THIS IS WHERE THE DELETE GOES IN THE FIELD BUILD. One operation swaps;
     * nothing structural changes.
     *
     * \u2b50 A RENAME MAKES A STALE PATH FAIL LOUDLY. Anything still resolving to
     * Documents/GroupTrack now hits a name that does not exist, instead of
     * quietly working right up until the deletes land.
     *
     * \u26a0 CALL ONLY AFTER A VERIFIED COPY. Renaming a source that did not copy
     * successfully is how the data goes missing.
     */
    fun renameSources(ctx: Context, treeUri: Uri): List<String> {
        val done = ArrayList<String>()
        try {
            val tree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return done
            for (name in listOf(DIR_GROUPTRACK, DIR_MYTRACKS)) {
                val d = tree.listFiles().firstOrNull {
                    it.isDirectory && it.name == name
                } ?: continue
                val to = name + EXT_SUFFIX
                val ok = try {
                    android.provider.DocumentsContract.renameDocument(
                        ctx.contentResolver, d.uri, to) != null
                } catch (e: Exception) {
                    Log.e(TAG, "rename $name: ${e.message}"); false
                }
                Log.i(TAG, "renameSources: $name -> $to " + (if (ok) "OK" else "FAILED"))
                if (ok) done.add(to)
            }
        } catch (e: Exception) {
            Log.e(TAG, "renameSources FAILED: ${e.message}")
        }
        return done
    }

    /**
     * \u26a0\u26a0 SCAFFOLDING. Undo the renames and put maps back, so the whole thing
     * can be run again.
     *
     * \u26d4 REMOVE FOR THE FIELD BUILD. Code that reverses a migration, in a build
     * where migration is one-way, can only ever fire by accident -- on a folder
     * somebody named -EXT for an unrelated reason, or a future change that reuses
     * the suffix. Fred's own rule, from the canary probe: *"the severity of
     * forgetting to remove it is worse than removing it for an instance that does
     * not occur."*
     *
     * \u2b50 AND IT IS NOT THE ONLY WAY BACK. Documents is shared storage, so a file
     * manager can rename GroupTrack-EXT by hand if this ever fails.
     */
    fun restore(ctx: Context, treeUri: Uri): String {
        val log = StringBuilder()
        try {
            val tree = DocumentFile.fromTreeUri(ctx, treeUri)
                ?: return "tree unreadable"

            for (name in listOf(DIR_GROUPTRACK, DIR_MYTRACKS)) {
                val from = name + EXT_SUFFIX
                val d = tree.listFiles().firstOrNull {
                    it.isDirectory && it.name == from
                } ?: continue
                if (tree.listFiles().any { it.name == name }) {
                    log.append("$name already exists -- left $from alone\n")
                    continue
                }
                val ok = try {
                    android.provider.DocumentsContract.renameDocument(
                        ctx.contentResolver, d.uri, name) != null
                } catch (e: Exception) { false }
                log.append("$from -> $name ").append(if (ok) "OK" else "FAILED").append("\n")
            }

            // maps back inside GroupTrack
            val parked = tree.listFiles().firstOrNull {
                it.isDirectory && it.name == DIR_MAPS_PARKED
            }
            val gt = tree.listFiles().firstOrNull {
                it.isDirectory && it.name == DIR_GROUPTRACK
            }
            if (parked != null && gt != null) {
                val ok = try {
                    android.provider.DocumentsContract.moveDocument(
                        ctx.contentResolver, parked.uri, tree.uri, gt.uri) != null
                } catch (e: Exception) { false }
                log.append("maps -> GroupTrack/maps ")
                    .append(if (ok) "OK" else "FAILED").append("\n")
            }

            // \u26a0 and the record, or the conversion will not offer itself again
            GroupTrackStorage.internalBase()?.let { base ->
                val f = File(base, RECORD_NAME)
                if (f.exists()) {
                    log.append("record deleted ").append(f.delete()).append("\n")
                }
            }
        } catch (e: Exception) {
            log.append("restore FAILED: ").append(e.message)
        }
        Log.i(TAG, "restore:\n$log")
        return log.toString()
    }

    /**
     * \u26a0 ONE OF THE TWO FOLDERS. Nothing else in the tree is looked at -- the
     * discipline the grant cannot enforce.
     */
    private fun copyFolder(
        ctx: Context,
        tree: DocumentFile,
        name: String,
        dest: File,
        onProgress: ((String) -> Unit)?,
    ): StepResult {
        val started = System.currentTimeMillis()
        val src = tree.listFiles().firstOrNull { it.isDirectory && it.name == name }
        if (src == null) {
            Log.i(TAG, "$name: not present in the granted tree")
            onProgress?.invoke("$name: nothing to copy")
            return StepResult(name, false, emptyList(), true, null,
                started, System.currentTimeMillis())
        }
        val items = ArrayList<Item>()
        return try {
            copyInto(ctx, src, dest, name, items, onProgress)
            val bad = items.count { !it.ok }
            StepResult(name, true, items, bad == 0,
                if (bad > 0) "$bad item(s) failed" else null,
                started, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "$name FAILED: ${e.message}")
            StepResult(name, true, items, false, e.message,
                started, System.currentTimeMillis())
        }
    }

    /**
     * Recursive copy. \u2b50 Everything at any depth, minus the exclusions.
     *
     * \u26a0 VERIFY BY COMPARISON, NOT EXISTENCE -- a zero-byte file exists.
     * \u26d4 Byte length is all that is available: reading the source through SAF
     * gives SQLite no path to open, so a row count on BOTH sides is impossible.
     * That is weaker than the earlier design had and it is the price of losing
     * the permission.
     */
    private fun copyInto(
        ctx: Context,
        src: DocumentFile,
        dest: File,
        prefix: String,
        items: MutableList<Item>,
        onProgress: ((String) -> Unit)?,
    ) {
        if (!dest.exists()) dest.mkdirs()
        for (child in src.listFiles()) {
            val cname = child.name ?: continue
            if (isExcluded(cname)) {
                Log.i(TAG, "skip $prefix/$cname")
                continue
            }
            val target = File(dest, cname)
            if (child.isDirectory) {
                copyInto(ctx, child, target, "$prefix/$cname", items, onProgress)
                continue
            }
            val srcBytes = child.length()
            onProgress?.invoke("$prefix/$cname")
            try {
                ctx.contentResolver.openInputStream(child.uri).use { input ->
                    if (input == null) throw IllegalStateException("no input stream")
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val dstBytes = target.length()
                val ok = dstBytes == srcBytes
                if (!ok) Log.e(TAG, "$prefix/$cname SIZE MISMATCH $srcBytes != $dstBytes")
                items.add(Item("$prefix/$cname", srcBytes, dstBytes, ok,
                    if (ok) null else "size mismatch"))
            } catch (e: Exception) {
                Log.e(TAG, "$prefix/$cname FAILED: ${e.message}")
                items.add(Item("$prefix/$cname", srcBytes, 0L, false, e.message))
            }
        }
    }
}
