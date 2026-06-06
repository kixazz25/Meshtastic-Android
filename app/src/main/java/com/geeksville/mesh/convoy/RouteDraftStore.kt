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

    data class DraftInfo(val name: String, val updatedAt: String, val pointCount: Int)

    /** List drafts for the In-Progress picker: name + date + point count. */
    fun listDrafts(): List<DraftInfo> {
        val dir = draftDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<DraftInfo>()
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                val name = o.optString("name", f.nameWithoutExtension)
                val updated = o.optString("updatedAt", "")
                val pts = o.optJSONArray("vertices")?.length() ?: 0
                out.add(DraftInfo(name, updated, pts))
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
