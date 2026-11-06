package com.grouptrack.shared.spatial

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import kotlin.math.roundToLong

/**
 * GTSHARED-2026-11-06 (2.8): moved from the spike (SPATIALKMP-2026-11-06, step 3a -- all counts matched 2.7e on Droid 1's data).
 * The FIRST real GroupTrack logic in SHARED Kotlin (runs on iPad and Android alike). NOT yet used by the Android app.
 * Ported from 2.7e, same SQL and same GeoJSON, so the map page draws exactly what the Droid draws:
 *   SpatialDbManager.query{Trails,Tracks,Waypoints,Routes}ByViewport + build*GeoJson, jsonEsc, wktToGeoJsonCoords
 *   SpatialDisplayManager.processArtifact / processLand (limits, zoom gates, update-then-show)
 *   LandAreas.viewportGeoJson
 * SQLite through androidx.sqlite (the Kotlin Multiplatform library) -- the database file is the Droid's grouptrack_spatial.db, read-only.
 * NOT YET (the filter step): TrailFilterState's WHERE (only its unconditional CLOSED rule is here), per-type ON / SELECTED / OFF,
 * and the trail_properties extras from grouptrack_data.db (detail-card fields; they do not change what draws).
 */
class GtSpatial(path: String) {
    private val conn: SQLiteConnection = BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY)

    // the filter step replaces these with the real TrailFilterState switches; today = the 2.7e defaults (all true)
    var showTrails = true; var showTracks = true; var showWaypoints = true; var showRoutes = true
    var restricted = true; var areaPublic = true; var areaRestricted = true

    /** What to run on the page for one viewport, and what each type returned (for the screen). */
    class Batch(val js: List<String>, val counts: Map<String, Int>)

    fun viewport(north: Double, south: Double, east: Double, west: Double, zoom: Int): Batch {
        val js = ArrayList<String>(); val counts = LinkedHashMap<String, Int>()
        fun send(type: String, on: Boolean, minZoom: Int, rows: () -> List<Map<String, String?>>, build: (List<Map<String, String?>>) -> String) {
            if (!on || zoom < minZoom) { js.add("hide$type()"); counts[type] = 0; return }   // processArtifact: OFF / below the gate -> hide
            val r = rows(); counts[type] = r.size
            js.add("(function(){try{update$type(" + build(r) + ");show$type();return 'ok';}catch(e){return 'ERR $type '+e;}})()")
        }
        send("Trails", showTrails, 9, { trails(south, west, north, east, 1_000) }, ::trailGeoJson)
        send("Tracks", showTracks, 0, { simple("SELECT track_id, name, geometry FROM tracks WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?", listOf("track_id", "name", "geometry"), south, north, west, east, 500) }, ::trackGeoJson)
        val lim = if (zoom < 14) 500 else 2000
        send("Waypoints", showWaypoints, 0, { simple("SELECT waypoint_id, name, geometry, type FROM waypoints WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?", listOf("waypoint_id", "name", "geometry", "type"), south, north, west, east, lim) }, ::waypointGeoJson)
        send("Routes", showRoutes, 0, { simple("SELECT route_id, name, geometry FROM routes WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?", listOf("route_id", "name", "geometry"), south, north, west, east, lim) }, ::routeGeoJson)
        // processLand: from app zoom 9, with Trails on
        if (zoom < 9 || !showTrails || (!areaPublic && !areaRestricted)) { js.add("window.updateLandAreas&&updateLandAreas(null,false,false)"); counts["Land"] = 0 }
        else { val (json, n) = land(south, west, north, east, 600); counts["Land"] = n; js.add("window.updateLandAreas&&updateLandAreas($json,$areaPublic,$areaRestricted)") }
        return Batch(js, counts)
    }

    // ---- reading -------------------------------------------------------------------------------------------------------------
    private fun query(sql: String, cols: List<String>, args: List<Any>): List<Map<String, String?>> {
        val st = conn.prepare(sql)
        try {
            args.forEachIndexed { i, a -> when (a) { is Double -> st.bindDouble(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); else -> st.bindText(i + 1, a.toString()) } }
            val out = ArrayList<Map<String, String?>>()
            while (st.step()) out.add(cols.indices.associate { i -> cols[i] to (if (st.isNull(i)) null else st.getText(i)) })
            return out
        } finally { st.close() }
    }

    private fun simple(sql: String, cols: List<String>, s: Double, n: Double, w: Double, e: Double, limit: Int) =
        query(sql, cols, listOf(s, n, w, e, limit)).filter { it["geometry"] != null }

    /** queryTrailsByViewport: largest first; closed trails never draw (TrailFilterState's unconditional rule). */
    private fun trails(s: Double, w: Double, n: Double, e: Double, limit: Int) = query(
        "SELECT trail_id, name, geometry, carto_code, status, land_status, restricted_geom, open_geom FROM trails " +
            "WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? AND (status IS NULL OR status <> 'CLOSED') " +
            "ORDER BY ((max_lat-min_lat)*(max_lat-min_lat)+(max_lon-min_lon)*(max_lon-min_lon)) DESC LIMIT ?",
        listOf("trail_id", "name", "geometry", "CartoCode", "Status", "Land", "RstGeom", "OpenGeom"), listOf(s, n, w, e, limit)
    ).filter { !it["geometry"].isNullOrEmpty() }

    private fun land(s: Double, w: Double, n: Double, e: Double, limit: Int): Pair<String, Int> {
        val kinds = ArrayList<String>(); if (areaPublic) kinds.add("'PUBLIC'"); if (areaRestricted) kinds.add("'RESTRICTED'")
        val rows = query("SELECT area_id, kind, unit_name, own_name, mang_name, des_name, pub_access, acres, rings FROM land_areas " +
            "WHERE kind IN (" + kinds.joinToString(",") + ") AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? ORDER BY acres DESC LIMIT ?",
            listOf("area_id", "kind", "unit", "owner", "manager", "designation", "access", "acres", "rings"), listOf(s, n, w, e, limit))
        val sb = StringBuilder(64 * 1024).append("{\"type\":\"FeatureCollection\",\"features\":[")
        var count = 0
        for (r in rows) {
            val rings = r["rings"] ?: continue
            if (count > 0) sb.append(',')
            count++
            fun q(k: String) = jsonEsc(r[k] ?: "")
            sb.append("{\"type\":\"Feature\",\"properties\":{\"area_id\":").append(r["area_id"] ?: "0")
                .append(",\"kind\":\"").append(q("kind")).append("\",\"unit\":\"").append(q("unit"))
                .append("\",\"owner\":\"").append(q("owner")).append("\",\"manager\":\"").append(q("manager"))
                .append("\",\"designation\":\"").append(q("designation")).append("\",\"access\":\"").append(q("access"))
                .append("\",\"acres\":").append((r["acres"]?.toDoubleOrNull() ?: 0.0).roundToLong())
                .append("},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[")
            rings.split('|').forEachIndexed { pi, poly ->
                if (pi > 0) sb.append(','); sb.append('[')
                poly.split(';').forEachIndexed { ri, ring ->
                    if (ri > 0) sb.append(','); sb.append('[')
                    ring.split(',').forEachIndexed { k, pt ->
                        if (k > 0) sb.append(',')
                        val sp = pt.indexOf(' ')
                        sb.append('[').append(pt, 0, sp).append(',').append(pt, sp + 1, pt.length).append(']')
                    }
                    sb.append(']')
                }
                sb.append(']')
            }
            sb.append("]}}")
        }
        sb.append("]}")
        return sb.toString() to count
    }

    // ---- GeoJSON (as SpatialDbManager) ----------------------------------------------------------------------------------------
    private fun trailGeoJson(trails: List<Map<String, String?>>): String {
        val sb = StringBuilder().append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (trail in trails) {
            val wkt = (if (!trail["RstGeom"].isNullOrEmpty()) (trail["OpenGeom"] ?: trail["geometry"]) else trail["geometry"]) ?: ""
            fun s(k: String): String = jsonEsc(trail[k] ?: "")
            if (wkt.isEmpty()) {
                val rg0 = trail["RstGeom"] ?: ""
                if (rg0.isNotEmpty() && restricted) {
                    val rc0 = wktToGeoJsonCoords(rg0)
                    if (rc0 != "[]") { if (!first) sb.append(","); first = false
                        sb.append("{\"type\":\"Feature\",\"properties\":{\"trail_id\":\"" + s("trail_id") + "\",\"PrimaryName\":\"" + s("name") +
                            "\",\"_rst\":1},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":" + rc0 + "}}") }
                }
                continue
            }
            val coords = wktToGeoJsonCoords(wkt)
            if (coords == "[]") continue
            if (!first) sb.append(",")
            first = false
            val geoType = if (wkt.startsWith("MULTI")) "MultiLineString" else "LineString"
            sb.append("{\"type\":\"Feature\",\"properties\":{")
            sb.append("\"trail_id\":\"" + s("trail_id") + "\"").append(",\"PrimaryName\":\"" + s("name") + "\"")
            sb.append(",\"CartoCode\":\"" + s("CartoCode") + "\"")
            for (k in listOf("SurfaceType", "DesignatedUses", "MotorizedAllowed", "HorseAllowed", "HikeDifficulty", "BikeDifficulty", "OwnerSteward", "County"))
                sb.append(",\"$k\":\"" + s(k) + "\"")
            sb.append(",\"Land\":\"" + s("Land") + "\"")
            sb.append("},\"geometry\":{\"type\":\"" + geoType + "\",\"coordinates\":" + coords + "}}")
            val rg = trail["RstGeom"] ?: ""
            if (rg.isNotEmpty() && restricted) {
                val rc = wktToGeoJsonCoords(rg)
                if (rc != "[]") sb.append(",{\"type\":\"Feature\",\"properties\":{\"trail_id\":\"" + s("trail_id") +
                    "\",\"PrimaryName\":\"" + s("name") + "\",\"_rst\":1},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":" + rc + "}}")
            }
        }
        return sb.append("]}").toString()
    }

    private fun lineCoords(geom: String) = geom.removePrefix("LINESTRING(").removeSuffix(")").split(",").joinToString(",") { pair ->
        val p = pair.trim().split(" "); if (p.size >= 2) "[${p[0]},${p[1]}]" else "[0,0]" }

    private fun trackGeoJson(tracks: List<Map<String, String?>>) = tracks.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") { t ->
        "{\"type\":\"Feature\",\"properties\":{\"name\":\"${jsonEsc(t["name"] ?: "Unnamed Track")}\",\"cartoCode\":\"\",\"track_id\":\"${jsonEsc(t["track_id"] ?: "")}\"}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[${lineCoords(t["geometry"] ?: "")}]}}" }

    private fun routeGeoJson(routes: List<Map<String, String?>>) = routes.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") { r ->
        "{\"type\":\"Feature\",\"properties\":{\"name\":\"${jsonEsc(r["name"] ?: "Unnamed Route")}\",\"route_id\":\"${jsonEsc(r["route_id"] ?: "")}\"}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[${lineCoords(r["geometry"] ?: "")}]}}" }

    private val pointRe = Regex("POINT\\(([\\d.\\-]+) ([\\d.\\-]+)\\)")
    private fun waypointGeoJson(wpts: List<Map<String, String?>>) = wpts.mapNotNull { w ->
        val m = pointRe.find(w["geometry"] ?: "") ?: return@mapNotNull null
        "{\"type\":\"Feature\",\"properties\":{\"waypoint_id\":\"${jsonEsc(w["waypoint_id"] ?: "")}\",\"name\":\"${jsonEsc(w["name"] ?: "Unnamed")}\"," +
            "\"wpt_type\":\"${jsonEsc(w["type"] ?: "other")}\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[${m.groupValues[1]},${m.groupValues[2]}]}}"
    }.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}")

    companion object {
        /** RIDERESC-2026-10-09: the one escape for a value inside a JSON string (backslash, quote, control characters). */
        fun jsonEsc(v: String): String {
            val sb = StringBuilder(v.length + 8)
            for (ch in v) when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch.code < 0x20 || ch == ' ' || ch == ' ' -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
            return sb.toString()
        }

        fun wktToGeoJsonCoords(wkt: String): String {
            val multi = wkt.startsWith("MULTILINESTRING(")
            val inner = when {
                multi -> wkt.removePrefix("MULTILINESTRING(").removeSuffix(")")
                wkt.startsWith("LINESTRING(") -> wkt.removePrefix("LINESTRING(").removeSuffix(")")
                else -> return "[]"
            }
            fun line(l: String) = "[" + l.split(",").map { it.trim().split(" ") }.joinToString(",") { "[${it[0]},${it[1]}]" } + "]"
            return if (!multi) line(inner) else "[" + inner.split("),(").map { it.trim('(', ')') }.joinToString(",") { line(it) } + "]"
        }
    }
}
