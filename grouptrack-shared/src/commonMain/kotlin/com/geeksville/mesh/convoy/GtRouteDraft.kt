package com.geeksville.mesh.convoy

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.route.GtRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * DRAFTSTORE-2026-11-23: THE ROUTE DRAFT STORE, shared.
 *
 * The format is Android's, field for field (RouteDraftStore.persist:703-722, openDraft:839-857), so
 * a draft written on either platform is readable by the other. The file work is the six json
 * primitives on [GtRoute.env] -- read/write/delete/list and, for the atomic write, rename.
 *
 * ⚠ deleteDraft is NOT here: Android's carries the open-batch rules (BATCHDELETE-2026-10-07), which
 * is a separate front. Nothing this file unblocks needs it.
 */
object GtRouteDraft {

    private val log = gtLogger("GtRouteDraft")
    private const val SCHEMA_VERSION = 1

    /** RouteDraftStore.OpenedDraft:827, same fields. */
    data class OpenedDraft(val name: String, val method: String, val vertices: List<RouteManager.Vertex>)

    /** RouteDraftStore.DraftInfo:745, same fields and the same defaults. */
    data class DraftInfo(val name: String, val updatedAt: String, val pointCount: Int, val createdAt: String = "")

    private fun path(name: String) = name + ".json"
    private fun tmpPath(name: String) = name + ".json.tmp"

    // ---- THE DOCUMENT (pure -- no file work, so both platforms share one definition) -----------

    /** RouteDraftStore.persist:703-722, field for field, over the CURRENT RouteManager vertices. */
    fun toJson(name: String, method: String, createdAt: String, updatedAt: String): String =
        buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("name", name)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("method", method)
            put("vertices", buildJsonArray {
                for (v in RouteManager.routeVertices()) add(buildJsonObject {
                    put("lat", v.lat)
                    put("lon", v.lon)
                    if (v.lineId == null) put("lineId", JsonNull) else put("lineId", v.lineId)
                    if (v.lineType == null) put("lineType", JsonNull) else put("lineType", v.lineType)
                    put("segmentIndex", v.segmentIndex)
                    put("t", v.t)
                    put("snapped", v.snapped)
                })
            })
        }.toString()

    /** RouteDraftStore.openDraft:839-857, same defaults: method "point", segmentIndex -1, t 0.0, snapped false. */
    fun fromJson(text: String, fallbackName: String): OpenedDraft? = try {
        val o = Json.parseToJsonElement(text).jsonObject
        val verts = ArrayList<RouteManager.Vertex>()
        (o["vertices"]?.jsonArray ?: emptyList()).forEach { e ->
            val v = e.jsonObject
            verts.add(RouteManager.Vertex(
                lat = dbl(v, "lat") ?: return@forEach,
                lon = dbl(v, "lon") ?: return@forEach,
                lineId = str(v, "lineId"),
                lineType = str(v, "lineType"),
                segmentIndex = dbl(v, "segmentIndex")?.toInt() ?: -1,
                t = dbl(v, "t") ?: 0.0,
                snapped = (v["snapped"]?.jsonPrimitive?.content ?: "false") == "true",
            ))
        }
        OpenedDraft(str(o, "name") ?: fallbackName, str(o, "method") ?: "point", verts)
    } catch (e: Exception) {
        log.e { "openDraft '" + fallbackName + "' failed: " + e.message }
        null
    }

    private fun str(o: JsonObject, k: String): String? =
        o[k]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
    private fun dbl(o: JsonObject, k: String): Double? =
        o[k]?.let { if (it is JsonNull) null else it.jsonPrimitive.content.toDoubleOrNull() }

    // ---- THE STORE (the six primitives) -------------------------------------------------------

    /** RouteDraftStore.draftExists:823 -- fileFor(name).exists(). */
    fun draftExists(name: String): Boolean = GtRoute.env.jsonRead(path(name)) != null

    /** RouteDraftStore.writeDraft:686 -- persist with createdAt = now. */
    fun writeDraft(name: String, method: String): Boolean =
        persist(name, method, GtRoute.env.nowIso())

    /** RouteDraftStore.overwriteDraft:693 -- keeps the original createdAt when the file is there. */
    fun overwriteDraft(name: String, method: String): Boolean {
        val created = GtRoute.env.jsonRead(path(name))
            ?.let { runCatching { str(Json.parseToJsonElement(it).jsonObject, "createdAt") }.getOrNull() }
            ?: GtRoute.env.nowIso()
        return persist(name, method, created)
    }

    /** RouteDraftStore.persist:724-734 -- temp then RENAME, so a crash mid-write keeps the old draft. */
    private fun persist(name: String, method: String, createdAt: String): Boolean {
        val text = toJson(name, method, createdAt, GtRoute.env.nowIso())
        if (!GtRoute.env.jsonWrite(tmpPath(name), text)) {
            log.e { "persist '" + name + "' failed: tmp write" }
            return false
        }
        val ok = GtRoute.env.jsonRename(tmpPath(name), path(name))
        if (!ok) log.e { "persist '" + name + "' failed: rename" }
        else log.d { "writeDraft '" + name + "' (" + RouteManager.routeVertexCount() + " pts)" }
        return ok
    }

    /** RouteDraftStore.openDraft:835. */
    fun openDraft(name: String): OpenedDraft? =
        GtRoute.env.jsonRead(path(name))?.let { fromJson(it, name) }

    /** RouteDraftStore.loadIntoRouteManager:865-870, same three lines. */
    fun loadIntoRouteManager(name: String): OpenedDraft? {
        val d = openDraft(name) ?: return null
        RouteManager.clearRoute()
        for (v in d.vertices) RouteManager.addVertex(v)
        return d
    }

    /** RouteDraftStore.listDrafts:809-828 -- unreadable drafts skipped, newest first. */
    fun listDrafts(): List<DraftInfo> {
        val out = ArrayList<DraftInfo>()
        for (f in GtRoute.env.jsonList()) {
            val text = GtRoute.env.jsonRead(f) ?: continue
            try {
                val o = Json.parseToJsonElement(text).jsonObject
                out.add(DraftInfo(
                    str(o, "name") ?: f.removeSuffix(".json"),
                    str(o, "updatedAt") ?: "",
                    o["vertices"]?.jsonArray?.size ?: 0,
                    str(o, "createdAt") ?: "",
                ))
            } catch (e: Exception) {
                log.w { "skip unreadable draft " + f + ": " + e.message }
            }
        }
        out.sortByDescending { it.updatedAt }
        return out
    }
}
