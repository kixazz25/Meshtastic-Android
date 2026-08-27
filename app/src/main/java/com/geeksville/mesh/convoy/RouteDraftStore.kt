package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * RouteDraftStore — interim (in-progress) route persistence.
 *
 * Design (DecisionLog 2026-06-05):
 *  - A route is EITHER an in-progress draft (this JSON store) OR a completed
 *    spatial-DB row (SpatialDbManager.insertRoute), never both. Graduation
 *    writes the DB row and deletes the draft.
 *  - Identity in the in-progress world = the typed NAME (R2). Filename derives
 *    from a sanitized name. Names are demanded unique across BOTH this store AND
 *    the routes DB at the prompt, so a draft can always graduate without a
 *    route-table name collision.
 *  - The draft stores the FULL vertex chain (R7), NOT wkt. wkt/bbox are derived
 *    at graduation by replaying RouteManager.buildWktAndBbox over the restored
 *    vertices — so a resumed draft graduates through the identical completed-save
 *    path.
 *  - RouteManager stays pure (no Context/file I/O); all file work lives here.
 *  - Atomic write: write <name>.json.tmp then rename over <name>.json — a draft
 *    is never half-written.
 *
 * Storage: /sdcard/Documents/GroupTrack/route_drafts/<sanitized-name>.json
 *
 * JSON shape:
 * {
 *   "schemaVersion": 1,
 *   "name": "Cedar Loop",
 *   "createdAt": "2026-06-06T...Z",
 *   "updatedAt": "2026-06-06T...Z",
 *   "method": "point",
 *   "vertices": [
 *     { "lat":.., "lon":.., "lineId":null, "lineType":null,
 *       "segmentIndex":-1, "t":0.0, "snapped":false }, ...
 *   ]
 * }
 */
object RouteDraftStore {

    private const val TAG = "RouteDraftStore"
    private const val SCHEMA_VERSION = 1
    private const val DIR_NAME = "route_drafts"

    // ----- BATCHJSON-2026-08-27: the open batch ----------------------------
    //
    // ⚠ This does NOT own the drafts -- RouteDraftStore already does. It records
    // WHICH drafts belong to one AI search, and the parameters that produced
    // them, so that:
    //
    //   * Route+ can fork: batch present -> COMPARE, absent -> the WIP list
    //   * COMPARE can be built and tested against a hand-written file
    //   * a saved route can carry the RECIPE that rebuilds its alternatives
    //
    // ⭐ NAMES, NOT A COUNT. Fred, 08-27: "we will not always have 6 routes."
    // Yesterday's ground gave five after the overlap dedupe.
    private const val BATCH_FILE = "open_batch.json"
    private const val BATCH_DIR = "batch"

    /* BATCHDIR-2026-08-27: its own subdirectory, not beside the drafts.
     *
     * The WIP list scans route_drafts/ for *.json, so open_batch.json appeared
     * there as an entry. Invisible once the Route+ fork lands -- but a batch
     * that failed to write cleanly would leave a bogus row with nothing to say
     * what it was, and a .tmp could be caught mid-rename.
     *
     * A subdirectory rather than a filename skip: it can never collide with a
     * draft a rider happens to name "open batch", and the drafts directory
     * stays purely drafts.
     */
    private fun batchDir(): File {
        val dir = File(draftDir(), BATCH_DIR)
        if (!dir.exists()) dir.mkdirs()
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
        return dir
    }

    private fun batchFile(): File {
        val f = File(batchDir(), BATCH_FILE)
        /* ⚠ MIGRATE FROM THE OLD LOCATION, ONCE.
         *
         * A batch written by the previous build sits in route_drafts/. Without
         * this a rider mid-batch when they update would silently lose it -- and
         * the lock could never clear, because nothing would be able to find the
         * file to delete.
         */
        if (!f.exists()) {
            val legacy = File(draftDir(), BATCH_FILE)
            if (legacy.exists()) {
                runCatching {
                    legacy.copyTo(f, overwrite = true)
                    legacy.delete()
                    Log.i(TAG, "migrated open batch out of the drafts directory")
                }.onFailure { Log.w(TAG, "batch migration failed: ${it.message}") }
            }
        }
        return f
    }

    /** The open batch, or null. Safe to call at any time. */
    fun readBatch(): JSONObject? {
        val f = batchFile()
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText()) }.getOrElse {
            // ⚠ A corrupt batch must not strand the rider. Report it and let the
            // caller fall through to the ordinary WIP list.
            Log.w(TAG, "batch unreadable, ignoring: ${it.message}")
            null
        }
    }

    fun hasOpenBatch(): Boolean = readBatch() != null

    fun clearBatch() {
        runCatching { batchFile().delete() }
    }

    /**
     * Record the drafts one AI search produced, with the recipe that built them.
     *
     * ⚠ PINS ARE STORED AS TAPPED, NOT AS SNAPPED. A rebuild that used the
     * snapped point would creep a little further along the trail every time.
     */
    fun writeBatch(
        batchName: String,
        draftNames: List<String>,
        anchorLat: Double, anchorLon: Double, anchorName: String?,
        milesLow: Double, milesHigh: Double,
        mphLow: Double, mphHigh: Double,
        pins: List<Pair<Double, Double>>,
        finish: Pair<Double, Double>?,
    ) {
        if (draftNames.isEmpty()) {
            // ⚠ No drafts means nothing to resolve. Writing a batch here would
            // lock route creation behind an empty compare screen.
            Log.i(TAG, "no drafts, no batch written")
            return
        }
        val o = JSONObject()
        o.put("schemaVersion", 1)
        o.put("batchName", batchName)
        o.put("createdAt", Instant.now().toString())
        o.put("routes", JSONArray().also { a -> draftNames.forEach { a.put(it) } })

        // ⭐ THE RECIPE. Every parameter the rider chose, and nothing derived --
        // the parameter list IS the recipe, so the two cannot drift apart.
        val r = JSONObject()
        r.put("anchorLat", anchorLat)
        r.put("anchorLon", anchorLon)
        r.put("anchorName", anchorName ?: JSONObject.NULL)
        r.put("milesLow", milesLow); r.put("milesHigh", milesHigh)
        r.put("mphLow", mphLow); r.put("mphHigh", mphHigh)
        r.put("pins", JSONArray().also { a ->
            pins.forEach { p ->
                a.put(JSONObject().put("lat", p.first).put("lon", p.second))
            }
        })
        r.put("finish", if (finish == null) JSONObject.NULL
            else JSONObject().put("lat", finish.first).put("lon", finish.second))
        o.put("recipe", r)

        // ⚠ ATOMIC, matching this store's own convention. A half-written batch
        // on a crash would send Route+ into compare with a corrupt list.
        val f = batchFile()
        val tmp = File(f.parentFile, "$BATCH_FILE.tmp")
        runCatching {
            tmp.writeText(o.toString(2))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            Log.i(TAG, "batch '$batchName' written with ${draftNames.size} route(s)")
        }.onFailure { Log.e(TAG, "batch write failed: ${it.message}") }
    }

    // ----- locations -------------------------------------------------------

    private fun baseDir(): File =
        File(android.os.Environment.getExternalStorageDirectory(), "Documents/GroupTrack")

    private fun draftDir(): File {
        val dir = File(baseDir(), DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        // keep drafts out of the media scanner, consistent with the rest of GroupTrack
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
        return dir
    }

    /**
     * Filename is derived from the name (identity = name, R2). Sanitize only the
     * characters illegal in a filename; the stored "name" field keeps the exact
     * user text for display. Uniqueness is enforced at the prompt (isNameTaken),
     * so distinct accepted names cannot collide here.
     */
    private fun fileFor(name: String): File {
        val safe = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "Not Named" }
        return File(draftDir(), "$safe.json")
    }

    private fun now(): String = Instant.now().toString()

    // ----- write / overwrite ----------------------------------------------

    /**
     * Create a new draft from the current RouteManager vertex chain.
     * Caller guarantees the name is unique (prompt-enforced). Returns true on success.
     */
    fun writeDraft(name: String, method: String): Boolean =
        persist(name, method, createdAt = now())

    /**
     * Overwrite an existing draft (same name) with the current vertex chain.
     * Preserves the original createdAt if the file exists.
     */
    fun overwriteDraft(name: String, method: String): Boolean {
        val existingCreated = runCatching {
            val f = fileFor(name)
            if (f.exists()) JSONObject(f.readText()).optString("createdAt", now()) else now()
        }.getOrDefault(now())
        return persist(name, method, createdAt = existingCreated)
    }

    private fun persist(name: String, method: String, createdAt: String): Boolean {
        return try {
            val root = JSONObject()
            root.put("schemaVersion", SCHEMA_VERSION)
            root.put("name", name)
            root.put("createdAt", createdAt)
            root.put("updatedAt", now())
            root.put("method", method)

            val arr = JSONArray()
            for (v in RouteManager.routeVertices()) {
                val o = JSONObject()
                o.put("lat", v.lat)
                o.put("lon", v.lon)
                o.put("lineId", v.lineId ?: JSONObject.NULL)
                o.put("lineType", v.lineType ?: JSONObject.NULL)
                o.put("segmentIndex", v.segmentIndex)
                o.put("t", v.t)
                o.put("snapped", v.snapped)
                arr.put(o)
            }
            root.put("vertices", arr)

            // atomic write: temp then rename
            val target = fileFor(name)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(root.toString())
            if (target.exists()) target.delete()
            val ok = tmp.renameTo(target)
            if (!ok) {
                // fallback: copy then drop tmp (rename can fail across odd FS states)
                target.writeText(root.toString())
                tmp.delete()
            }
            Log.d(TAG, "writeDraft '$name' -> ${target.name} (${arr.length()} pts)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "persist '$name' failed", e)
            false
        }
    }

    // ----- list ------------------------------------------------------------

    data class DraftInfo(val name: String, val updatedAt: String, val pointCount: Int, val createdAt: String = "")

    /** [draft-resolver 2026-08-01] The unnamed-draft literal. A draft under this name is a
     *  crash remnant: Route+ arms with it, and a clean exit always renames or deletes it. */
    const val UNNAMED = "Auto Saved In Progress"

    /** List drafts for the In-Progress picker: name + date + point count. */
    /**
     * WIPNOTES-2026-08-23S: the narrative for ONE draft, or null if it has none.
     *
     * The notes live INSIDE the draft file as a `notes` block beside `vertices`
     * -- one file, no sidecar. listDrafts() deliberately does not carry them:
     * it is a light summary for the picker and every entry would be bloated by
     * a narrative the picker never shows.
     *
     * Returns the raw JSON so the caller decides how much of it to render. A
     * hand-drawn route has no notes and returns null, which is what hides the
     * DETAILS button.
     */
    fun readNotes(name: String): JSONObject? {
        return try {
            val f = fileFor(name)
            if (!f.exists()) null
            else JSONObject(f.readText()).optJSONObject("notes")
        } catch (e: Exception) {
            Log.w(TAG, "readNotes failed for $name: ${e.message}")
            null
        }
    }

    /** WIPNOTES-2026-08-23S: cheap test for the DETAILS button's visibility. */
    fun hasNotes(name: String): Boolean = readNotes(name) != null

    /**
     * ROUTEEXPLORE-2026-08-23T: write a draft from a SUPPLIED JSONObject.
     *
     * writeDraft() above serialises RouteManager's LIVE vertex chain -- what the
     * rider has drawn. The explorer has no live chain: it has a finished object
     * carrying vertices AND a notes block. This writes that.
     *
     * ⚠ Reuses fileFor()/draftDir() deliberately, so the .nomedia guard and the
     * naming rules stay in ONE place. A second path writing drafts its own way
     * would drift from the first.
     */
    fun writeRawDraft(name: String, doc: JSONObject): Boolean {
        return try {
            draftDir()
            fileFor(name).writeText(doc.toString())
            Log.i(TAG, "wrote generated draft: $name (${doc.optJSONArray("vertices")?.length() ?: 0} pts)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeRawDraft failed for $name: ${e.message}")
            false
        }
    }

    fun listDrafts(): List<DraftInfo> {
        val dir = draftDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<DraftInfo>()
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                val name = o.optString("name", f.nameWithoutExtension)
                val updated = o.optString("updatedAt", "")
                val created = o.optString("createdAt", "")
                val pts = o.optJSONArray("vertices")?.length() ?: 0
                out.add(DraftInfo(name, updated, pts, created))
            } catch (e: Exception) {
                Log.w(TAG, "skip unreadable draft ${f.name}: ${e.message}")
            }
        }
        // newest first
        out.sortByDescending { it.updatedAt }
        return out
    }

    fun draftExists(name: String): Boolean = fileFor(name).exists()

    // ----- open (used by RESUME and ROLL-BACK) -----------------------------

    data class OpenedDraft(val name: String, val method: String, val vertices: List<RouteManager.Vertex>)

    /**
     * Read a draft back into a vertex list. Used by both RESUME (continue editing)
     * and ROLL-BACK (reload the unchanged file, dropping in-memory edits).
     * Does NOT mutate RouteManager — the caller decides how to load (typically
     * clearRoute() then addVertex() per returned vertex).
     */
    fun openDraft(name: String): OpenedDraft? {
        return try {
            val f = fileFor(name)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            val method = o.optString("method", "point")
            val arr = o.optJSONArray("vertices") ?: JSONArray()
            val verts = ArrayList<RouteManager.Vertex>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                verts.add(
                    RouteManager.Vertex(
                        lat = v.getDouble("lat"),
                        lon = v.getDouble("lon"),
                        lineId = if (v.isNull("lineId")) null else v.optString("lineId"),
                        lineType = if (v.isNull("lineType")) null else v.optString("lineType"),
                        segmentIndex = v.optInt("segmentIndex", -1),
                        t = v.optDouble("t", 0.0),
                        snapped = v.optBoolean("snapped", false)
                    )
                )
            }
            OpenedDraft(o.optString("name", name), method, verts)
        } catch (e: Exception) {
            Log.e(TAG, "openDraft '$name' failed", e)
            null
        }
    }

    /** Convenience: load a draft straight into RouteManager (resume path). */
    fun loadIntoRouteManager(name: String): OpenedDraft? {
        val d = openDraft(name) ?: return null
        RouteManager.clearRoute()
        for (v in d.vertices) RouteManager.addVertex(v)
        return d
    }

    // ----- delete ----------------------------------------------------------

    /** Remove a draft (graduate cleanup, or Discard -> Delete in-progress). */
    /**
     * Rename an existing draft file oldName -> newName, preserving its JSON
     * contents (the stored `name` field is updated to newName). No geometry
     * re-serialization. Returns true on success, false if source missing or
     * target name already taken.
     */
    fun renameDraft(oldName: String, newName: String): Boolean {
        if (oldName == newName) return true
        val src = fileFor(oldName)
        if (!src.exists()) return false
        if (isNameTaken(newName)) return false
        return try {
            val root = JSONObject(src.readText())
            root.put("name", newName)
            root.put("updatedAt", now())
            val target = fileFor(newName)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(root.toString())
            if (target.exists()) target.delete()
            val ok = tmp.renameTo(target)
            if (ok) { src.delete(); true } else { tmp.delete(); false }
        } catch (e: Exception) {
            false
        }
    }

    fun deleteDraft(name: String): Boolean {
        return try {
            val f = fileFor(name)
            val ok = if (f.exists()) f.delete() else true
            Log.d(TAG, "deleteDraft '$name' -> $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "deleteDraft '$name' failed", e)
            false
        }
    }

    // ----- uniqueness (prompt-time) ---------------------------------------

    /**
     * True if the name is already taken by a draft (this store) OR a completed
     * route (routes DB). Demanded-unique policy: the New/rename dialog rejects a
     * taken name and re-prompts with the old name pre-filled. Checking the DB here
     * (not just drafts) prevents a name-dupe on the route-table insert at graduation.
     *
     * Case-insensitive, trimmed comparison so "Cedar Pass" and "cedar pass " collide.
     */
    fun isNameTaken(name: String): Boolean {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return false
        // drafts
        if (listDrafts().any { it.name.trim().lowercase() == needle }) return true
        // completed routes in the DB
        return SpatialDbManager.routeNameExists(name)
    }
}
