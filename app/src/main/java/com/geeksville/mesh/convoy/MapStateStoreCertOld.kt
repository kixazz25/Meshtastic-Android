package com.geeksville.mesh.convoy

import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import org.json.JSONArray
import org.json.JSONObject

/**
 * MAPSTATECERTOLD-2026-11-14: the OLD side of the MapStateStore certification (Cert17mapstate).
 * 2.7e MapStateStore's LOGIC, copied as is. Its DATA CLASSES are reused from the live
 * [MapStateStore], not redeclared -- only behaviour is copied, as ConvoyTileCalculatorCertOld does.
 *
 * \u26d4 org.json IS KEPT ON PURPOSE. It is the LENIENT parser, and the divergence from GtOrgJson
 * (kotlinx, strict) is exactly what the 3 EXPECTED_DIFF fixtures measure. Replacing it here would
 * delete the finding.
 *
 * SUBSTITUTION MANIFEST -- counted, and these are the ONLY changes from 2.7e:
 *   1. object MapStateStore -> class MapStateStoreCertOld(files, paths)                     x1
 *      \u26a0 WHY: the cert must drive BOTH sides through ONE env so they read identical bytes (the
 *      G2f rule). 2.7e's object reaches GroupTrackStorage directly and could not be pointed at a
 *      cert directory.
 *   2. GroupTrackStorage.dir("state") -> paths.dir("state")                                 x1
 *   3. java.io.File + mkdirs/exists/readText/writeText/delete -> okio via files.fs          x9
 *        fileFor mkdirs x1, viewFile mkdirs x1, saveMap write x1, deleteMap delete x1,
 *        readMap exists x1 + read x1, readView exists x1 + read x1, and File(dir, name) -> dir / name
 *   4. lastMapProcessed dropped                                                             x1
 *      \u26a0 WHY: it is @Volatile cross-thread state that no compared key reads. Keeping it would add
 *      a member the cert cannot exercise -- the Q5 test (every member put in has a reachable caller).
 *      It stays in the live object and in the shared port; it is only absent from this copy.
 *   TOTAL: 12 substitutions. NOTHING ELSE CHANGES -- not a default, not a branch, not an order.
 */
class MapStateStoreCertOld(private val files: GtFiles, private val paths: GtImportPaths) {

    private val TYPES = listOf("Trails", "Tracks", "Waypoints", "Routes")

    private fun dir() = paths.dir("state")   // substitution 2

    private fun fileFor(mapKey: String) =
        dir().also { files.fs.createDirectories(it) } / (mapKey + "_panel.json")   // substitution 3

    private fun viewFile(mapKey: String) =
        dir().also { files.fs.createDirectories(it) } / (mapKey + "_view.json")    // substitution 3

    fun rideKey(): String = "grouptrack"

    private fun defaults(): MapStateStore.MapSnapshot {
        val t = TYPES.associateWith { type ->
            MapStateStore.TypeState(if (type == "Trails") 1 else 0, emptyList())
        }
        return MapStateStore.MapSnapshot(t, MapStateStore.PanelBoxes())
    }

    fun saveMap(mapKey: String, snap: MapStateStore.MapSnapshot) {
        try {
            val root = JSONObject()
            val artifacts = JSONObject()
            for (type in TYPES) {
                val ts = snap.types[type] ?: MapStateStore.TypeState(0, emptyList())
                val o = JSONObject()
                o.put("state", ts.state)
                val arr = JSONArray()
                for (r in ts.rows) {
                    val ro = JSONObject()
                    ro.put("id", r.id); ro.put("name", r.name); ro.put("checked", r.checked)
                    arr.put(ro)
                }
                o.put("rows", arr)
                artifacts.put(type, o)
            }
            root.put("artifacts", artifacts)
            val panel = JSONObject()
            panel.put("tilesChecked", snap.panel.tilesChecked)
            panel.put("trailsChecked", snap.panel.trailsChecked)
            panel.put("removeTilesChecked", snap.panel.removeTilesChecked)
            root.put("panel", panel)
            snap.bbox?.let {
                val b = JSONObject()
                b.put("south", it.south); b.put("west", it.west)
                b.put("north", it.north); b.put("east", it.east)
                b.put("zoom", it.zoom)
                root.put("bbox", b)
            }
            snap.fitArtifact?.let {
                val fa = JSONObject()
                fa.put("id", it.id); fa.put("name", it.name); fa.put("type", it.type)
                root.put("fitArtifact", fa)
            }
            snap.routeState?.let {
                val rsO = JSONObject()
                rsO.put("open", it.open); rsO.put("status", it.status); rsO.put("draftName", it.draftName)
                root.put("routeState", rsO)
            }
            files.fs.write(fileFor(mapKey)) { writeUtf8(root.toString()) }   // substitution 3
        } catch (e: Exception) {
            // 2.7e swallows: persistence must never crash the UI
        }
    }

    fun deleteMap(mapKey: String) {
        try { files.fs.delete(fileFor(mapKey)) } catch (e: Exception) { /* never crash UI */ }
    }

    fun readMap(mapKey: String): MapStateStore.MapSnapshot {
        return try {
            val f = fileFor(mapKey)
            if (!files.fs.exists(f)) return defaults()                       // substitution 3
            val root = JSONObject(files.fs.read(f) { readUtf8() })           // substitution 3 (org.json KEPT)
            val artifacts = root.optJSONObject("artifacts") ?: return defaults()
            val types = HashMap<String, MapStateStore.TypeState>()
            for (type in TYPES) {
                val o = artifacts.optJSONObject(type)
                if (o == null) {
                    types[type] = MapStateStore.TypeState(if (type == "Trails") 1 else 0, emptyList())
                    continue
                }
                val state = o.optInt("state", if (type == "Trails") 1 else 0)
                val rows = ArrayList<MapStateStore.Row>()
                val arr = o.optJSONArray("rows")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val ro = arr.optJSONObject(i) ?: continue
                        val id = ro.optString("id", "")
                        if (id.isEmpty()) continue
                        rows.add(MapStateStore.Row(id, ro.optString("name", ""), ro.optBoolean("checked", false)))
                    }
                }
                types[type] = MapStateStore.TypeState(state, rows)
            }
            val p = root.optJSONObject("panel")
            val panel = if (p == null) MapStateStore.PanelBoxes() else MapStateStore.PanelBoxes(
                p.optBoolean("tilesChecked", false),
                p.optBoolean("trailsChecked", false),
                p.optBoolean("removeTilesChecked", false)
            )
            val bboxObj = root.optJSONObject("bbox")
            val bbox = if (bboxObj == null) null else MapStateStore.BBox(
                bboxObj.optDouble("south", 0.0), bboxObj.optDouble("west", 0.0),
                bboxObj.optDouble("north", 0.0), bboxObj.optDouble("east", 0.0),
                bboxObj.optDouble("zoom", 15.0)
            )
            val faObj = root.optJSONObject("fitArtifact")
            val fitArtifact = if (faObj == null) null else MapStateStore.FitArtifact(
                faObj.optString("id", ""), faObj.optString("name", ""), faObj.optString("type", "")
            )
            val rsObj = root.optJSONObject("routeState")
            val routeState = if (rsObj == null) null else MapStateStore.RouteState(
                rsObj.optBoolean("open", false), rsObj.optString("status", "draft"), rsObj.optString("draftName", "")
            )
            MapStateStore.MapSnapshot(types, panel, bbox, fitArtifact, routeState)
        } catch (e: Exception) {
            defaults()
        }
    }

    fun saveView(mapKey: String, mapType: String, offline: Boolean) {
        try {
            files.fs.write(viewFile(mapKey)) {                              // substitution 3
                writeUtf8(JSONObject().put("mapType", mapType).put("offline", offline).toString())
            }
        } catch (e: Exception) { /* persistence must never crash the UI */ }
    }

    fun readView(mapKey: String): MapStateStore.ViewState = try {
        val f = viewFile(mapKey)
        if (!files.fs.exists(f)) MapStateStore.ViewState("", false)          // substitution 3
        else JSONObject(files.fs.read(f) { readUtf8() }).let {               // substitution 3 (org.json KEPT)
            MapStateStore.ViewState(it.optString("mapType", ""), it.optBoolean("offline", false))
        }
    } catch (e: Exception) { MapStateStore.ViewState("", false) }

    fun checkedIdsFor(snap: MapStateStore.MapSnapshot, type: String): Set<String>? {
        val ts = snap.types[type] ?: return null
        if (ts.state != 2) return null
        return ts.rows.filter { it.checked }.map { it.id }.toSet()
    }
}
