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
