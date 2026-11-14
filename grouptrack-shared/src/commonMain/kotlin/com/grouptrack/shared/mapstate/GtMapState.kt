package com.grouptrack.shared.mapstate

import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * GTMAPSTATE-2026-11-14 (2.8 step 17, Phase 2 pass 1): 2.7e MapStateStore, PORTED AS IS.
 * Per-map durable panel / artifact state, JSON on disk. Each map owns its own state so it survives
 * close / crash / restart. NO GEOMETRY is stored -- on reopen the screen re-queries the database and
 * saved-but-missing ids simply do not match and drop.
 *
 * SEAMS, both already built and certified -- no new socket:
 *   GtFiles.fs            okio, for read / write / delete / exists   (G2a)
 *   GtImportPaths.dir()   2.7e GroupTrackStorage.dir("state")        (G2a)
 *
 * SUBSTITUTIONS from 2.7e, and ONLY these:
 *   1. java.io.File              -> okio Path via paths.dir("state")              x4 call sites
 *   2. org.json JSONObject/Array -> GtOrgJson.parse + its opt* family (READ) + buildJsonObject (WRITE).
 *      ⭐ USE GtOrgJson's optString / optInt / optBoolean / optDouble -- they ARE org.json's semantics and
 *      are already certified (G2g). A first draft of this port hand-rolled them and got status wrong:
 *      optString(key, fallback) falls back on a MISSING key only, so an explicit "" must stay "".
 *   3. android.util.Log          -> gtLogger("MapStateStore")   (2.7e logs nothing here; kept for parity of shape)
 *   4. dir().mkdirs()            -> fs.createDirectories(dir)
 *
 * \u26a0 ORDER IS LOAD-BEARING IN TWO PLACES:
 *   [GtBBox] is (south, west, north, east, zoom) -- NOT the north/south/east/west used elsewhere in
 *   this codebase. All four are Double, so a "tidied" reordering would transpose north and south,
 *   compile clean, and corrupt every restored viewport. 2.7e GroupTrackScreen:345 constructs it
 *   positionally in exactly this order. \u26d4 DO NOT REORDER.
 *   [TYPES] order drives both the written artifact order and the read loop.
 *
 * \u26a0 THREE DEFAULTS THAT ARE NOT ZERO OR EMPTY, each verified against 2.7e:
 *   Trails defaults to state 1 (ON) and every other type to 0 -- and 2.7e repeats that rule in THREE
 *   places (defaults(), the missing-type branch, and optInt's fallback). [typeDefault] is the one copy.
 *   [GtBBox.zoom] defaults to 15.0 while its four edges default to 0.0.
 *   [GtRouteState.status] defaults to "draft", not "".
 *
 * \u26a0 A ROW WITH AN EMPTY id IS DROPPED, silently, as in 2.7e. It is a decision, not a bug.
 *
 * \u26d4 STRICT vs LENIENT -- THE KNOWN DIVERGENCE (the G2g GtConfigs precedent).
 *   2.7e reads with org.json, which tolerates /* */ and // comments, single-quoted strings,
 *   unquoted keys and a trailing comma. GtOrgJson is kotlinx and refuses all of them, so such a file
 *   THROWS here and lands in [defaults] -- a branch 2.7e already has and already treats as correct
 *   ("SAFE LOAD: missing or corrupt file -> defaults. Never throws to the caller").
 *   Simulated before this port was written (sim_mapstate_v2_2026-11-14): 19 scenarios, all terminate,
 *   323 compared keys, exactly 3 EXPECTED_DIFF -- comment, single-quote, trailing-comma.
 *   \u2b50 NOTHING GROUPTRACK WRITES IS LENIENT: saveMap emits a serializer's output, so this can only
 *   ever bite a hand-edited file, and the cost is one map's saved panel state reverting to defaults.
 *
 * \u26a0 saveMap IS CERTIFIED BY ROUND-TRIP, NOT BY FILE TEXT. org.json's JSONObject is HashMap-backed,
 *   so its key ORDER is unspecified -- comparing written bytes would compare a sampled observation
 *   rather than a decision. The compared key is "write it, read it back, compare the snapshot".
 */

data class GtRow(val id: String, val name: String, val checked: Boolean)
data class GtTypeState(val state: Int, val rows: List<GtRow>)
data class GtPanelBoxes(
    val tilesChecked: Boolean = false,
    val trailsChecked: Boolean = false,
    val removeTilesChecked: Boolean = false,
)

/** \u26d4 (south, west, north, east) -- 2.7e's order. See the header. */
data class GtBBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val zoom: Double = 15.0,
)
data class GtFitArtifact(val id: String, val name: String, val type: String)

/** open = route-add mode active; status "draft" (reopenable) or "final"; draftName links the JSON draft. */
data class GtRouteState(val open: Boolean, val status: String, val draftName: String)
data class GtMapSnapshot(
    val types: Map<String, GtTypeState>,
    val panel: GtPanelBoxes,
    val bbox: GtBBox? = null,
    val fitArtifact: GtFitArtifact? = null,
    val routeState: GtRouteState? = null,
)

/** mapType "" = nothing saved yet (2.7e CODE RULE 1: no null). */
data class GtViewState(val mapType: String, val offline: Boolean)

class GtMapState(private val files: GtFiles, private val paths: GtImportPaths) {

    companion object {
        /** 2.7e private val TYPES -- the order drives the written artifact order AND the read loop. */
        val TYPES = listOf("Trails", "Tracks", "Waypoints", "Routes")

        /** 2.7e repeats this rule in three places; this is the one copy. Trails ON, the rest OFF. */
        fun typeDefault(type: String): Int = if (type == "Trails") 1 else 0

        const val DEFAULT_ZOOM = 15.0
        const val DEFAULT_STATUS = "draft"

        /** 2.7e rideKey(): ONE MAP is the only layout. PERSISTKEY-2026-10-23 / CLASSIC4B-2026-10-25. */
        fun rideKey(): String = "grouptrack"

        /** 2.7e checkedIdsFor: null unless the type exists AND its state is 2 (SELECTED). */
        fun checkedIdsFor(snap: GtMapSnapshot, type: String): Set<String>? {
            val ts = snap.types[type] ?: return null
            if (ts.state != 2) return null
            return ts.rows.filter { it.checked }.map { it.id }.toSet()
        }
    }

    private val log = gtLogger("MapStateStore")

    /**
     * 2.7e @Volatile var lastMapProcessed -- which map last ran its view refresh; gates reseeding a
     * map's local state from JSON. Touched from a worker and the main thread, hence @Volatile.
     */
    @Volatile
    var lastMapProcessed: String? = null

    private fun dir() = paths.dir("state")

    /** 2.7e fileFor: mkdirs then <mapKey>_panel.json. */
    private fun fileFor(mapKey: String) = dir().also { files.fs.createDirectories(it) } / (mapKey + "_panel.json")

    /** 2.7e viewFile: mkdirs then <mapKey>_view.json. Separate file because saveMap rewrites its own whole. */
    private fun viewFile(mapKey: String) = dir().also { files.fs.createDirectories(it) } / (mapKey + "_view.json")

    /** 2.7e defaults(): Trails ON, others OFF, no rows, all panel boxes false. */
    fun defaults(): GtMapSnapshot =
        GtMapSnapshot(TYPES.associateWith { GtTypeState(typeDefault(it), emptyList()) }, GtPanelBoxes())

    fun saveMap(mapKey: String, snap: GtMapSnapshot) {
        try {
            val root = buildJsonObject {
                put("artifacts", buildJsonObject {
                    for (type in TYPES) {
                        val ts = snap.types[type] ?: GtTypeState(0, emptyList())   // 2.7e: TypeState(0, emptyList())
                        put(type, buildJsonObject {
                            put("state", ts.state)
                            put("rows", buildJsonArray {
                                for (r in ts.rows) add(buildJsonObject {
                                    put("id", r.id); put("name", r.name); put("checked", r.checked)
                                })
                            })
                        })
                    }
                })
                put("panel", buildJsonObject {
                    put("tilesChecked", snap.panel.tilesChecked)
                    put("trailsChecked", snap.panel.trailsChecked)
                    put("removeTilesChecked", snap.panel.removeTilesChecked)
                })
                snap.bbox?.let {
                    put("bbox", buildJsonObject {
                        put("south", it.south); put("west", it.west)
                        put("north", it.north); put("east", it.east)
                        put("zoom", it.zoom)
                    })
                }
                snap.fitArtifact?.let {
                    put("fitArtifact", buildJsonObject { put("id", it.id); put("name", it.name); put("type", it.type) })
                }
                snap.routeState?.let {
                    put("routeState", buildJsonObject {
                        put("open", it.open); put("status", it.status); put("draftName", it.draftName)
                    })
                }
            }
            files.fs.write(fileFor(mapKey)) { writeUtf8(root.toString()) }
        } catch (e: Exception) {
            // 2.7e swallows: persistence must never crash the UI
        }
    }

    /** 2.7e deleteMap: session-only maps, cleared on cold launch. Never crashes. */
    fun deleteMap(mapKey: String) {
        try { files.fs.delete(fileFor(mapKey)) } catch (e: Exception) { /* never crash UI */ }
    }

    fun readMap(mapKey: String): GtMapSnapshot {
        return try {
            val f = fileFor(mapKey)
            if (!files.fs.exists(f)) return defaults()
            val root = GtOrgJson.parse(files.fs.read(f) { readUtf8() }) as? JsonObject ?: return defaults()
            val artifacts = root["artifacts"] as? JsonObject ?: return defaults()
            val types = HashMap<String, GtTypeState>()
            for (type in TYPES) {
                val o = artifacts[type] as? JsonObject
                if (o == null) { types[type] = GtTypeState(typeDefault(type), emptyList()); continue }
                // 2.7e optInt(state, typeDefault): a non-int yields the default
                val state = GtOrgJson.optInt(o, "state", typeDefault(type))
                val rows = ArrayList<GtRow>()
                val arr = o["rows"] as? JsonArray
                if (arr != null) for (el in arr) {
                    val ro = el as? JsonObject ?: continue                 // 2.7e optJSONObject(i) ?: continue
                    val id = GtOrgJson.optString(ro, "id")
                    if (id.isEmpty()) continue                             // 2.7e: an empty id is DROPPED
                    rows.add(GtRow(id, GtOrgJson.optString(ro, "name"), GtOrgJson.optBoolean(ro, "checked", false)))
                }
                types[type] = GtTypeState(state, rows)
            }
            val p = root["panel"] as? JsonObject
            val panel = if (p == null) GtPanelBoxes() else GtPanelBoxes(
                GtOrgJson.optBoolean(p, "tilesChecked", false),
                GtOrgJson.optBoolean(p, "trailsChecked", false),
                GtOrgJson.optBoolean(p, "removeTilesChecked", false))
            val b = root["bbox"] as? JsonObject
            val bbox = if (b == null) null else GtBBox(
                GtOrgJson.optDouble(b, "south", 0.0), GtOrgJson.optDouble(b, "west", 0.0),
                GtOrgJson.optDouble(b, "north", 0.0), GtOrgJson.optDouble(b, "east", 0.0),
                GtOrgJson.optDouble(b, "zoom", DEFAULT_ZOOM))
            val fa = root["fitArtifact"] as? JsonObject
            val fitArtifact = if (fa == null) null else GtFitArtifact(
                GtOrgJson.optString(fa, "id"), GtOrgJson.optString(fa, "name"), GtOrgJson.optString(fa, "type"))
            val rs = root["routeState"] as? JsonObject
            val routeState = if (rs == null) null else GtRouteState(
                GtOrgJson.optBoolean(rs, "open", false),
                GtOrgJson.optString(rs, "status", DEFAULT_STATUS),   // ⚠ fallback on MISSING only -- an explicit "" stays ""
                GtOrgJson.optString(rs, "draftName"))
            GtMapSnapshot(types, panel, bbox, fitArtifact, routeState)
        } catch (e: Exception) {
            defaults()   // 2.7e SAFE LOAD
        }
    }

    fun saveView(mapKey: String, mapType: String, offline: Boolean) {
        try {
            val o = buildJsonObject { put("mapType", mapType); put("offline", offline) }
            files.fs.write(viewFile(mapKey)) { writeUtf8(o.toString()) }
        } catch (e: Exception) { /* persistence must never crash the UI */ }
    }

    fun readView(mapKey: String): GtViewState = try {
        val f = viewFile(mapKey)
        if (!files.fs.exists(f)) GtViewState("", false)
        else (GtOrgJson.parse(files.fs.read(f) { readUtf8() }) as? JsonObject)?.let {
            GtViewState(GtOrgJson.optString(it, "mapType"), GtOrgJson.optBoolean(it, "offline", false))
        } ?: GtViewState("", false)
    } catch (e: Exception) { GtViewState("", false) }
}
