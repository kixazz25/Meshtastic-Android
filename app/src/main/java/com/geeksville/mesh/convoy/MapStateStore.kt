package com.geeksville.mesh.convoy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

//
// MapStateStore - per-map durable panel/artifact state (JSON on disk).
//
// PURPOSE: each map (convoy, planning) owns its own state, persisted so it
// survives app close / crash / restart. Eliminates cross-map contamination
// (the old shared ConvoyConfig.*DisplayState / *Checked fields).
//
// WHAT IS STORED (light, no geometry):
//   per artifact type (Trails/Tracks/Waypoints/Routes):
//     - state: Int  (0=OFF, 1=ON, 2=SELECTED)
//     - rows:  [{id, name, checked}]   (the SELECT/Edit list selection)
//   panel checkboxes (map-level): tiles, trails, removeTiles
//
// WHAT IS NOT STORED: geometry / coordinates. On reopen, the screen always
// runs a fresh DB query (queryArtifactList) to attach live geometry to the
// saved selection. Saved-but-now-missing ids simply do not match and drop.
//
// SAFE LOAD: missing or corrupt file -> defaults (Trails ON, others OFF,
// checkboxes false). Never throws to the caller.
//
object MapStateStore {

    private const val DIR = "/sdcard/Documents/GroupTrack/state"
    private val TYPES = listOf("Trails", "Tracks", "Waypoints", "Routes")

    // Which map ("convoy"/"planning") last ran its view refresh. Gates variable
    // substitution: reseed a map's local state from JSON only when the active map
    // changed since the last refresh. @Volatile - touched from worker + main thread.
    @Volatile var lastMapProcessed: String? = null

    data class Row(val id: String, val name: String, val checked: Boolean)
    data class TypeState(val state: Int, val rows: List<Row>)
    data class PanelBoxes(
        val tilesChecked: Boolean = false,
        val trailsChecked: Boolean = false,
        val removeTilesChecked: Boolean = false
    )
    data class BBox(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double
    )
    data class FitArtifact(
        val id: String,
        val name: String,
        val type: String
    )
    data class MapSnapshot(
        val types: Map<String, TypeState>,
        val panel: PanelBoxes,
        val bbox: BBox? = null,
        val fitArtifact: FitArtifact? = null
    )

    // mapKey is "convoy" or "planning"
    private fun fileFor(mapKey: String): File {
        val dir = File(DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, mapKey + "_panel.json")
    }

    private fun defaults(): MapSnapshot {
        val t = TYPES.associateWith { type ->
            // Trails default ON; others OFF. No saved rows.
            TypeState(if (type == "Trails") 1 else 0, emptyList())
        }
        return MapSnapshot(t, PanelBoxes())
    }

    fun saveMap(mapKey: String, snap: MapSnapshot) {
        try {
            val root = JSONObject()
            val artifacts = JSONObject()
            for (type in TYPES) {
                val ts = snap.types[type] ?: TypeState(0, emptyList())
                val o = JSONObject()
                o.put("state", ts.state)
                val arr = JSONArray()
                for (r in ts.rows) {
                    val ro = JSONObject()
                    ro.put("id", r.id)
                    ro.put("name", r.name)
                    ro.put("checked", r.checked)
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
                val b = org.json.JSONObject()
                b.put("south", it.south); b.put("west", it.west)
                b.put("north", it.north); b.put("east", it.east)
                root.put("bbox", b)
            }
            snap.fitArtifact?.let {
                val fa = org.json.JSONObject()
                fa.put("id", it.id); fa.put("name", it.name); fa.put("type", it.type)
                root.put("fitArtifact", fa)
            }
            fileFor(mapKey).writeText(root.toString())
        } catch (e: Exception) {
            // swallow - persistence must never crash the UI
        }
    }

    fun readMap(mapKey: String): MapSnapshot {
        return try {
            val f = fileFor(mapKey)
            if (!f.exists()) return defaults()
            val root = JSONObject(f.readText())
            val artifacts = root.optJSONObject("artifacts") ?: return defaults()
            val types = HashMap<String, TypeState>()
            for (type in TYPES) {
                val o = artifacts.optJSONObject(type)
                if (o == null) {
                    types[type] = TypeState(if (type == "Trails") 1 else 0, emptyList())
                    continue
                }
                val state = o.optInt("state", if (type == "Trails") 1 else 0)
                val rows = ArrayList<Row>()
                val arr = o.optJSONArray("rows")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val ro = arr.optJSONObject(i) ?: continue
                        val id = ro.optString("id", "")
                        if (id.isEmpty()) continue
                        rows.add(Row(id, ro.optString("name", ""), ro.optBoolean("checked", false)))
                    }
                }
                types[type] = TypeState(state, rows)
            }
            val p = root.optJSONObject("panel")
            val panel = if (p == null) PanelBoxes() else PanelBoxes(
                p.optBoolean("tilesChecked", false),
                p.optBoolean("trailsChecked", false),
                p.optBoolean("removeTilesChecked", false)
            )
            val bboxObj = root.optJSONObject("bbox")
            val bbox = if (bboxObj == null) null else BBox(
                bboxObj.optDouble("south", 0.0), bboxObj.optDouble("west", 0.0),
                bboxObj.optDouble("north", 0.0), bboxObj.optDouble("east", 0.0)
            )
            val faObj = root.optJSONObject("fitArtifact")
            val fitArtifact = if (faObj == null) null else FitArtifact(
                faObj.optString("id", ""), faObj.optString("name", ""), faObj.optString("type", "")
            )
            MapSnapshot(types, panel, bbox, fitArtifact)
        } catch (e: Exception) {
            defaults()
        }
    }

    // Convenience: the checked-id set for a type, for the SELECTED filter.
    fun checkedIdsFor(snap: MapSnapshot, type: String): Set<String>? {
        val ts = snap.types[type] ?: return null
        if (ts.state != 2) return null
        return ts.rows.filter { it.checked }.map { it.id }.toSet()
    }
}
