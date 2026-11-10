package com.grouptrack.shared.osm

import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okio.Path

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmImportLedger -- the run record for one state's OSM import -- PORTED AS IS. The ledger
 * records WHAT HAPPENED, never WHERE YOU ARE (disk wins); every key is an outcome with counts and a time; written .tmp + rename.
 * WHAT CHANGED, and only this:
 *  - org.json JSONObject / JSONArray -> [Obj] (an insertion-ordered map, as Android's JSONObject's LinkedHashMap: put on an existing key
 *    keeps its place, remove drops it) and kotlinx arrays, read with GtOrgJson's org.json answers and written by GtOrgJson.pretty =
 *    toString(2) (the same text: indent, "key": value, "\/", a whole double as a long). JSONObject(text) on a file that is not an
 *    object -> null + the error log, as 2.7e's catch.
 *  - put(k, double) -> GtOrgJson.num (refuses NaN / Infinity as org.json does); JSONObject.NULL -> JsonNull.
 *  - LocalDateTime.now() "yyyy-MM-dd'T'HH:mm:ss" and SimpleDateFormat "yyyy-MM-dd'T'HHmmss" -> GtTime.local(env.clock.localNow(), ...).
 *  - java.io.File -> okio via env.files.fs (writeText = UTF-8; delete then rename; a failed rename -> false); Log -> gtLogger.
 */
class GtOsmLedger(private val env: GtImportEnv, private val stage: GtOsmStage) {

    private val log = gtLogger(TAG)
    private val fs get() = env.files.fs

    /** org.json's mutable JSONObject: insertion order, put keeps an existing key's place. */
    class Obj(val m: LinkedHashMap<String, JsonElement> = LinkedHashMap()) {
        fun put(k: String, v: JsonElement): Obj { m[k] = v; return this }
        fun put(k: String, v: String): Obj = put(k, GtOrgJson.str(v))
        fun put(k: String, v: Int): Obj = put(k, GtOrgJson.num(v))
        fun put(k: String, v: Long): Obj = put(k, GtOrgJson.num(v))
        fun put(k: String, v: Double): Obj = put(k, GtOrgJson.num(v))
        fun remove(k: String) { m.remove(k) }
        fun has(k: String) = m.containsKey(k)
        fun optArray(k: String): JsonArray? = m[k] as? JsonArray
        fun optObject(k: String): JsonObject? = m[k] as? JsonObject
        fun json(): JsonObject = JsonObject(m)
        fun pretty(): String = GtOrgJson.pretty(json(), 2)
        companion object { fun of(o: JsonObject) = Obj(LinkedHashMap(o)) }
    }

    private fun now(): String = GtTime.local(env.clock.localNow(), "yyyy-MM-dd'T'HH:mm:ss")

    fun file(slug: String): Path = stage.dirFor(slug) / FILENAME

    // -- read ---------------------------------------------------------------

    fun read(slug: String): Obj? {
        val f = file(slug)
        if (!fs.exists(f)) return null
        return try {
            val e = GtOrgJson.parse(fs.read(f) { readUtf8() })
            Obj.of(e as? JsonObject ?: throw IllegalStateException("Value of type ${e::class.simpleName} cannot be converted to JSONObject"))
        } catch (e: Exception) {
            log.e { "read failed for $slug: ${e::class.simpleName} ${e.message}" }
            null
        }
    }

    // -- atomic write -------------------------------------------------------

    private fun write(slug: String, json: Obj): Boolean {
        val target = file(slug)
        val tmp = target.parent!! / "$FILENAME.tmp"
        return try {
            fs.createDirectories(target.parent!!)
            fs.write(tmp) { writeUtf8(json.pretty()) }
            if (fs.exists(target)) try { fs.delete(target) } catch (_: Exception) {}
            val ok = try { fs.atomicMove(tmp, target); true } catch (_: Exception) { false }
            if (!ok) log.e { "rename failed: $tmp -> ${target.name}" }
            ok
        } catch (e: Exception) {
            log.e { "write failed for $slug: ${e::class.simpleName} ${e.message}" }
            try { fs.delete(tmp) } catch (_: Exception) {}
            false
        }
    }

    // -- create -------------------------------------------------------------

    fun create(slug: String, state: String, sourceNote: String, carryForwardImports: JsonArray? = null): Obj {
        val j = Obj()
        j.put("state", state)
        j.put("slug", slug)
        j.put("source", sourceNote)
        j.put("created", now())
        j.put("imports", carryForwardImports ?: JsonArray(emptyList()))
        write(slug, j)
        log.i { "ledger created for $slug (carried ${carryForwardImports?.size ?: 0} prior imports)" }
        return j
    }

    /** imports[] from an existing ledger, for carrying across a re-acquire. */
    fun priorImports(slug: String): JsonArray? = read(slug)?.optArray("imports")

    // -- outcomes -----------------------------------------------------------

    fun recordAcquire(slug: String, sourceFile: String, bytes: Long, movedFrom: String) {
        val j = read(slug) ?: return
        val o = Obj()
        o.put("source_file", sourceFile)
        o.put("bytes", bytes)
        o.put("moved_from", movedFrom)
        o.put("completed", now())
        j.put("acquire", o.json())
        write(slug, j)
        log.i { "acquire recorded: $slug $bytes bytes" }
    }

    fun recordExtract(slug: String, gpkgBytes: Long, classes: List<String>, candidateRows: Int, kept: Int, badGeometry: Int, skinnyRows: Long) {
        val j = read(slug) ?: return
        val o = Obj()
        o.put("gpkg_bytes", gpkgBytes)
        o.put("classes", JsonArray(classes.map { GtOrgJson.str(it) }))
        o.put("candidate_rows", candidateRows)
        o.put("kept", kept)
        o.put("bad_geometry", badGeometry)
        o.put("skinny_rows", skinnyRows)   // STEP15G-FIXA-2026-11-10 D16 (Fred 11-10): total rows, not the file size
        o.put("completed", now())
        j.put("extract", o.json())
        write(slug, j)
        log.i { "extract recorded: $slug kept=$kept of $candidateRows" }
    }

    fun appendImport(
        slug: String, scope: String, bboxOrNull: String?,
        found: Int, inserted: Int, aliased: Int, dropped: Int,
        geometryChanged: Int = 0, errors: Int = 0,
        pointsFound: Int = 0, pointsInserted: Int = 0, pointsDropped: Int = 0, pointsMoved: Int = 0
    ) {
        val j = read(slug) ?: return
        val arr = (j.optArray("imports") ?: JsonArray(emptyList())).toMutableList()
        val o = Obj()
        o.put("scope", scope)
        o.put("bbox", if (bboxOrNull != null) GtOrgJson.str(bboxOrNull) else JsonNull)
        o.put("found", found)
        o.put("inserted", inserted)
        o.put("aliased", aliased)
        o.put("dropped", dropped)
        o.put("geometry_changed", geometryChanged)
        o.put("errors", errors)
        o.put("points_found", pointsFound)
        o.put("points_inserted", pointsInserted)
        o.put("points_dropped", pointsDropped)
        o.put("points_moved", pointsMoved)
        o.put("at", now())
        arr.add(o.json())
        j.put("imports", JsonArray(arr))
        j.remove("pending_import")
        write(slug, j)
        log.i { "import recorded: $slug scope=$scope inserted=$inserted aliased=$aliased dropped=$dropped " +
            "geomChanged=$geometryChanged errors=$errors points=+$pointsInserted/~$pointsMoved/=$pointsDropped" }
    }

    // -- pending import (the screen-to-screen handoff) ----------------------

    fun setPendingImport(slug: String, scope: String, bbox: DoubleArray?) {
        val j = read(slug) ?: return
        val o = Obj()
        o.put("scope", scope)
        o.put("chosen_at", now())
        if (bbox != null && bbox.size == 4) {
            val b = Obj()
            b.put("s", bbox[0]); b.put("w", bbox[1]); b.put("n", bbox[2]); b.put("e", bbox[3])
            o.put("bbox", b.json())
        } else {
            o.put("bbox", JsonNull)
        }
        j.put("pending_import", o.json())
        write(slug, j)
        log.i { "pending import: $slug scope=$scope bbox=${bbox?.joinToString(",") { GtJava.jd(it) } ?: "(awaiting draw)"}" }
    }

    /** The chosen scope, or null if row 3 has not been answered. */
    fun pendingScope(slug: String): String? {
        val p = read(slug)?.optObject("pending_import") ?: return null
        return GtOrgJson.optString(p, "scope", "").ifEmpty { null }
    }

    /** [s, w, n, e], or null when still awaiting a draw. */
    fun pendingBbox(slug: String): DoubleArray? {
        val p = read(slug)?.optObject("pending_import") ?: return null
        val b = p["bbox"] as? JsonObject ?: return null
        return doubleArrayOf(
            GtOrgJson.optDouble(b, "s", Double.NaN), GtOrgJson.optDouble(b, "w", Double.NaN),
            GtOrgJson.optDouble(b, "n", Double.NaN), GtOrgJson.optDouble(b, "e", Double.NaN)
        ).takeIf { it.none { v -> v.isNaN() } }
    }

    fun setPendingBbox(slug: String, s: Double, w: Double, n: Double, e: Double) {
        val scope = pendingScope(slug) ?: "area"
        setPendingImport(slug, scope, doubleArrayOf(s, w, n, e))
    }

    fun clearPendingImport(slug: String) {
        val j = read(slug) ?: return
        if (!j.has("pending_import")) return
        j.remove("pending_import")
        write(slug, j)
        log.i { "pending import cleared: $slug" }
    }

    // -- cleanup ------------------------------------------------------------

    /** OSM-C4-ARCHIVE-2026-07-29: RENAME the run record into osm/history/<slug>_<time>.json (a free name). */
    fun archiveLedger(slug: String): Path? {
        val src = stage.dirFor(slug) / FILENAME
        if (!fs.exists(src)) {
            log.w { "archiveLedger: no ledger for $slug" }
            return null
        }
        val histDir = stage.rootDir() / "history"
        if (!fs.exists(histDir)) {
            val made = try { fs.createDirectories(histDir); true } catch (_: Exception) { false }
            if (!made) {
                log.e { "archiveLedger: could not create history dir" }
                return null
            }
        }
        val ts = GtTime.local(env.clock.localNow(), "yyyy-MM-dd'T'HHmmss")
        var dst = histDir / "${slug}_$ts.json"
        var n = 1
        while (fs.exists(dst)) {
            dst = histDir / "${slug}_${ts}_$n.json"
            n++
        }
        val ok = try { fs.atomicMove(src, dst); true } catch (_: Exception) { false }
        return if (ok) {
            log.i { "ledger archived: ${dst.name}" }
            dst
        } else {
            log.e { "archiveLedger: rename FAILED for $slug -- nothing removed" }
            null
        }
    }

    fun finalizeAndDelete(slug: String, bytesReclaimed: Long, deleted: List<String>) {
        val j = read(slug)
        if (j != null) appendHistory(j, bytesReclaimed, deleted)
        val f = file(slug)
        if (fs.exists(f)) {
            val ok = try { fs.delete(f); true } catch (_: Exception) { false }
            if (!ok) log.e { "ledger delete failed for $slug" }
        }
        log.i { "ledger finalized and removed: $slug" }
    }

    private fun appendHistory(ledger: Obj, bytesReclaimed: Long, deleted: List<String>) {
        val hist = stage.rootDir() / HISTORY_FILENAME
        val arr: MutableList<JsonElement> = try {
            if (fs.exists(hist)) (GtOrgJson.parse(fs.read(hist) { readUtf8() }) as? JsonArray
                ?: throw IllegalStateException("not a JSONArray")).toMutableList()
            else mutableListOf()
        } catch (e: Exception) {
            log.e { "history unreadable, starting fresh: ${e::class.simpleName}" }
            mutableListOf()
        }

        val imports = ledger.optArray("imports") ?: JsonArray(emptyList())
        var trailsAdded = 0
        for (i in 0 until imports.size) {
            trailsAdded += (imports[i] as? JsonObject)?.let { GtOrgJson.optInt(it, "inserted", 0) } ?: 0
        }

        val line = Obj()
        line.put("state", GtOrgJson.optString(ledger.json(), "slug"))
        line.put("extracted", ledger.optObject("extract")?.let { GtOrgJson.optInt(it, "kept", 0) } ?: 0)
        line.put("imports", imports.size)
        line.put("trails_added", trailsAdded)
        line.put("bytes_reclaimed", bytesReclaimed)
        line.put("deleted", JsonArray(deleted.map { GtOrgJson.str(it) }))
        line.put("completed", now())
        arr.add(line.json())

        val tmp = hist.parent!! / "$HISTORY_FILENAME.tmp"
        try {
            fs.createDirectories(hist.parent!!)
            fs.write(tmp) { writeUtf8(GtOrgJson.pretty(JsonArray(arr), 2)) }
            if (fs.exists(hist)) try { fs.delete(hist) } catch (_: Exception) {}
            try { fs.atomicMove(tmp, hist) } catch (_: Exception) {}
            log.i { "history appended: ${GtOrgJson.optString(ledger.json(), "slug")} trails_added=$trailsAdded" }
        } catch (e: Exception) {
            log.e { "history append failed: ${e::class.simpleName} ${e.message}" }
            try { fs.delete(tmp) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "OsmLedger"
        const val FILENAME = "ledger.json"
        const val HISTORY_FILENAME = "osm_import_history.json"
    }
}
