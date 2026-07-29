package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * OsmImportLedger -- the run record for one state's OSM trail import.
 *
 * RULE: the ledger records WHAT HAPPENED. It never records WHERE YOU ARE.
 * Stage is derived from disk by OsmImportStage.stageOf(). When the ledger and
 * the filesystem disagree, DISK WINS -- the ledger is simply out of date.
 *
 * This is not a style preference. On 2026-07-27 isSourceFullyImported() read
 * status == "processed" from a JSON that writeTrailAreaJson() wrote
 * UNCONDITIONALLY, so three FAILED runs marked the source permanently imported
 * and un-selectable. A JSON that stores STATE lies the moment anything goes
 * wrong.
 *
 * Therefore every key here is an OUTCOME with counts and a timestamp. An
 * unfinished stage's key is simply ABSENT -- and absence is unambiguous in a
 * way that a stale "processed" is not. There is no stage field, no status
 * field, and nothing in the app may branch on this file to decide what runs
 * next.
 *
 * Lifecycle: created by C1 (acquire), deleted by C4 (cleanup).
 */
object OsmImportLedger {

    private const val TAG = "OsmLedger"
    const val FILENAME = "ledger.json"
    const val HISTORY_FILENAME = "osm_import_history.json"

    private val TS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun now(): String = LocalDateTime.now().format(TS)

    fun file(ctx: Context, slug: String): File =
        File(OsmImportStage.dirFor(ctx, slug), FILENAME)

    // -- read ---------------------------------------------------------------

    fun read(ctx: Context, slug: String): JSONObject? {
        val f = file(ctx, slug)
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "read failed for $slug: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    // -- atomic write -------------------------------------------------------

    /**
     * Write via .tmp + rename. A crash mid-write can therefore never leave a
     * half-parsed ledger behind -- same mechanism saveQueue() already uses.
     */
    private fun write(ctx: Context, slug: String, json: JSONObject): Boolean {
        val target = file(ctx, slug)
        val tmp = File(target.parentFile, "$FILENAME.tmp")
        return try {
            target.parentFile?.mkdirs()
            tmp.writeText(json.toString(2))
            if (target.exists()) target.delete()
            val ok = tmp.renameTo(target)
            if (!ok) Log.e(TAG, "rename failed: ${tmp.absolutePath} -> ${target.name}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "write failed for $slug: ${e.javaClass.simpleName} ${e.message}")
            tmp.delete()
            false
        }
    }

    // -- create -------------------------------------------------------------

    /**
     * Start a fresh ledger for a run.
     *
     * carryForwardImports: on a re-acquire the trails already moved into the
     * live spatial DB are still there, so the record of them staying true is
     * honest rather than stale. Pass the previous ledger's imports[] to keep it.
     */
    fun create(
        ctx: Context,
        slug: String,
        state: String,
        sourceNote: String,
        carryForwardImports: JSONArray? = null
    ): JSONObject {
        val j = JSONObject()
        j.put("state", state)
        j.put("slug", slug)
        j.put("source", sourceNote)
        j.put("created", now())
        j.put("imports", carryForwardImports ?: JSONArray())
        write(ctx, slug, j)
        Log.i(TAG, "ledger created for $slug (carried ${carryForwardImports?.length() ?: 0} prior imports)")
        return j
    }

    /** imports[] from an existing ledger, for carrying across a re-acquire. */
    fun priorImports(ctx: Context, slug: String): JSONArray? =
        read(ctx, slug)?.optJSONArray("imports")

    // -- outcomes -----------------------------------------------------------

    /** C1 completed: the zip is in place and verified. */
    fun recordAcquire(
        ctx: Context,
        slug: String,
        sourceFile: String,
        bytes: Long,
        movedFrom: String
    ) {
        val j = read(ctx, slug) ?: return
        val o = JSONObject()
        o.put("source_file", sourceFile)
        o.put("bytes", bytes)
        o.put("moved_from", movedFrom)
        o.put("completed", now())
        j.put("acquire", o)
        write(ctx, slug, j)
        Log.i(TAG, "acquire recorded: $slug $bytes bytes")
    }

    /** C2 completed: unzip + skinny pass, as one atomic operation. */
    fun recordExtract(
        ctx: Context,
        slug: String,
        gpkgBytes: Long,
        classes: List<String>,
        candidateRows: Int,
        kept: Int,
        badGeometry: Int,
        skinnyBytes: Long
    ) {
        val j = read(ctx, slug) ?: return
        val o = JSONObject()
        o.put("gpkg_bytes", gpkgBytes)
        o.put("classes", JSONArray(classes))
        o.put("candidate_rows", candidateRows)
        o.put("kept", kept)
        o.put("bad_geometry", badGeometry)
        o.put("skinny_bytes", skinnyBytes)
        o.put("completed", now())
        j.put("extract", o)
        write(ctx, slug, j)
        Log.i(TAG, "extract recorded: $slug kept=$kept of $candidateRows")
    }

    /**
     * C3 completed one import. Appends -- C3 repeats as many times as the user
     * chooses, so this accumulates rather than replacing.
     *
     * bboxOrNull is null for a whole-state import; that null IS the record of
     * scope, which is why "scope" is stored alongside it explicitly.
     */
    fun appendImport(
        ctx: Context,
        slug: String,
        scope: String,
        bboxOrNull: String?,
        found: Int,
        inserted: Int,
        aliased: Int,
        dropped: Int
    ) {
        val j = read(ctx, slug) ?: return
        val arr = j.optJSONArray("imports") ?: JSONArray()
        val o = JSONObject()
        o.put("scope", scope)
        o.put("bbox", bboxOrNull ?: JSONObject.NULL)
        o.put("found", found)
        o.put("inserted", inserted)
        o.put("aliased", aliased)
        o.put("dropped", dropped)
        o.put("at", now())
        arr.put(o)
        j.put("imports", arr)
        write(ctx, slug, j)
        Log.i(TAG, "import recorded: $slug scope=$scope inserted=$inserted aliased=$aliased")
    }

    // -- cleanup ------------------------------------------------------------

    /**
     * C4: append one durable line to the permanent history, then remove the
     * transient ledger.
     *
     * The per-state ledger is transient by design. The history line is what
     * still tells you six weeks from now which states a tester actually loaded,
     * and it costs kilobytes.
     */
    fun finalizeAndDelete(
        ctx: Context,
        slug: String,
        bytesReclaimed: Long,
        deleted: List<String>
    ) {
        val j = read(ctx, slug)
        if (j != null) {
            appendHistory(ctx, j, bytesReclaimed, deleted)
        }
        val f = file(ctx, slug)
        if (f.exists() && !f.delete()) {
            Log.e(TAG, "ledger delete failed for $slug")
        }
        Log.i(TAG, "ledger finalized and removed: $slug")
    }

    private fun appendHistory(
        ctx: Context,
        ledger: JSONObject,
        bytesReclaimed: Long,
        deleted: List<String>
    ) {
        val hist = File(OsmImportStage.rootDir(ctx), HISTORY_FILENAME)
        val arr = try {
            if (hist.exists()) JSONArray(hist.readText()) else JSONArray()
        } catch (e: Exception) {
            Log.e(TAG, "history unreadable, starting fresh: ${e.javaClass.simpleName}")
            JSONArray()
        }

        val imports = ledger.optJSONArray("imports") ?: JSONArray()
        var trailsAdded = 0
        for (i in 0 until imports.length()) {
            trailsAdded += imports.optJSONObject(i)?.optInt("inserted", 0) ?: 0
        }

        val line = JSONObject()
        line.put("state", ledger.optString("slug"))
        line.put("extracted", ledger.optJSONObject("extract")?.optInt("kept", 0) ?: 0)
        line.put("imports", imports.length())
        line.put("trails_added", trailsAdded)
        line.put("bytes_reclaimed", bytesReclaimed)
        line.put("deleted", JSONArray(deleted))
        line.put("completed", now())
        arr.put(line)

        val tmp = File(hist.parentFile, "$HISTORY_FILENAME.tmp")
        try {
            hist.parentFile?.mkdirs()
            tmp.writeText(arr.toString(2))
            if (hist.exists()) hist.delete()
            tmp.renameTo(hist)
            Log.i(TAG, "history appended: ${ledger.optString("slug")} trails_added=$trailsAdded")
        } catch (e: Exception) {
            Log.e(TAG, "history append failed: ${e.javaClass.simpleName} ${e.message}")
            tmp.delete()
        }
    }
}
