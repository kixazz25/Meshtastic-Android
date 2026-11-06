package com.grouptrack.shared.spatial

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.filter.TrailFilterState
import com.grouptrack.shared.log.gtLogger
import kotlin.math.roundToLong

/**
 * GtSpatial -- the map display's database queries, in SHARED Kotlin (Android + iPad).
 * GTSHARED-2026-11-06: moved from the spike (SPATIALKMP-2026-11-06, step 3a -- all counts matched 2.7e on Droid 1's data).
 * GTBATCH1B-2026-11-06: the ONE MAP's queries on Android come from here now (SpatialDisplayManager). Ported from 2.7e, same SQL and same
 * GeoJSON: SpatialDbManager.query{Trails,Tracks,Waypoints,Routes}ByViewport + build*GeoJson (incl. the trail_properties extras),
 * LandAreas.viewportGeoJson, and the filter through the shared TrailFilterState (where(), restricted, the area overlays).
 * ⚠ Not yet moved: SpatialDbManager's own copies, still used by the route builder, the artifact list and the ride map -- they move with
 *   SpatialDbManager (its own batch, walked with route building).
 * ⚠ A connection is NOT thread-safe: the platform serialises calls (Android: SpatialDisplayManager's lock; iPad: one queue).
 *
 * @param conn grouptrack_spatial.db (read only by this class).
 * @param dataConn grouptrack_data.db for the trail detail fields (trail_properties). CODE RULE 1 -- nullable on purpose: 2.7e reads it only
 *   when SpatialDbManager has it open (a fresh install before the first import has none); without it the trails draw the same and only
 *   the detail fields stay empty -- exactly the 2.7e behaviour.
 */
class GtSpatial(private val conn: SQLiteConnection, private val dataConn: SQLiteConnection?) {
    private val log = gtLogger("SpatialShared")

    fun close() { try { conn.close() } catch (_: Exception) {}; try { dataConn?.close() } catch (_: Exception) {} }

    // ---- the four types (SpatialDbManager.query*ByViewport) -----------------------------------------------------------------------
    /** queryTrailsByViewport: the filter's WHERE, largest first, LIMIT; rows without geometry dropped; then the detail extras. */
    fun queryTrails(s: Double, w: Double, n: Double, e: Double, limit: Int): List<Map<String, String?>> {
        val rows = query(
            "SELECT trail_id, name, geometry, carto_code, status, land_status, restricted_geom, open_geom FROM trails " +
                "WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? " + TrailFilterState.whereOrEmpty() +
                " ORDER BY ((max_lat-min_lat)*(max_lat-min_lat)+(max_lon-min_lon)*(max_lon-min_lon)) DESC LIMIT ?",
            listOf("trail_id", "name", "geometry", "CartoCode", "Status", "Land", "RstGeom", "OpenGeom"), listOf(s, n, w, e, limit)
        ).filter { !it["geometry"].isNullOrEmpty() }.map { it.toMutableMap() }
        val dc = dataConn
        if (dc != null && rows.isNotEmpty()) {
            try {
                val ids = rows.mapNotNull { it["trail_id"] }
                if (ids.isNotEmpty()) {
                    val props = queryOn(dc, "SELECT trail_id, surface_type, designated_uses, motorized_allowed, horse_allowed, hike_difficulty, " +
                        "bike_difficulty, owner_steward, county FROM trail_properties WHERE trail_id IN (" + ids.joinToString(",") { "?" } + ")",
                        listOf("trail_id", "SurfaceType", "DesignatedUses", "MotorizedAllowed", "HorseAllowed", "HikeDifficulty", "BikeDifficulty",
                            "OwnerSteward", "County"), ids)
                    val byId = HashMap<String, Map<String, String?>>()
                    for (p in props) { val tid = p["trail_id"] ?: continue; byId[tid] = p }
                    for (row in rows) {
                        val p = byId[row["trail_id"]] ?: continue
                        for ((k, v) in p) { if (k == "trail_id") continue; if (!row[k].isNullOrBlank()) continue; if (v.isNullOrBlank()) continue; row[k] = v }
                    }
                }
            } catch (ex: Exception) { log.w { "trail_properties: ${ex.message}" } }   // 2.7e: the extras are optional
        }
        return rows
    }

    fun queryTracks(s: Double, w: Double, n: Double, e: Double, limit: Int) =
        simple("SELECT track_id, name, geometry FROM tracks WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?",
            listOf("track_id", "name", "geometry"), s, n, w, e, limit)

    fun queryWaypoints(s: Double, w: Double, n: Double, e: Double, limit: Int) =
        simple("SELECT waypoint_id, name, geometry, type FROM waypoints WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?",
            listOf("waypoint_id", "name", "geometry", "type"), s, n, w, e, limit).map { r -> if (r["type"] == null) r + ("type" to "other") else r }

    fun queryRoutes(s: Double, w: Double, n: Double, e: Double, limit: Int) =
        simple("SELECT route_id, name, geometry FROM routes WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?",
            listOf("route_id", "name", "geometry"), s, n, w, e, limit)

    /** LandAreas.viewportGeoJson: PUBLIC / RESTRICTED areas in view, largest first. Returns the GeoJSON and how many areas it holds.
     *  ⚠ The table must exist -- the platform creates it (Android: LandAreas.createTables, as 2.7e did before every query). */
    fun landGeoJson(s: Double, w: Double, n: Double, e: Double, pub: Boolean, rst: Boolean, limit: Int): Pair<String, Int> {
        val kinds = ArrayList<String>(); if (pub) kinds.add("'PUBLIC'"); if (rst) kinds.add("'RESTRICTED'")
        if (kinds.isEmpty()) return EMPTY to 0
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

    // ---- the iPad's whole viewport (SpatialDisplayManager.processViewport with every type ON) --------------------------------------
    class Batch(val js: List<String>, val counts: Map<String, Int>)

    fun viewport(north: Double, south: Double, east: Double, west: Double, zoom: Int): Batch {
        val js = ArrayList<String>(); val counts = LinkedHashMap<String, Int>()
        fun send(type: String, minZoom: Int, rows: () -> List<Map<String, String?>>, build: (List<Map<String, String?>>) -> String) {
            if (zoom < minZoom) { js.add("hide$type()"); counts[type] = 0; return }
            val r = rows(); counts[type] = r.size
            js.add("(function(){try{update$type(" + build(r) + ");show$type();return 'ok';}catch(e){return 'ERR $type '+e;}})()")
        }
        send("Trails", TRAILS_MIN_ZOOM, { queryTrails(south, west, north, east, TRAILS_LIMIT) }, ::trailGeoJson)
        send("Tracks", 0, { queryTracks(south, west, north, east, TRACKS_LIMIT) }, ::trackGeoJson)
        val lim = otherLimit(zoom)
        send("Waypoints", 0, { queryWaypoints(south, west, north, east, lim) }, ::waypointGeoJson)
        send("Routes", 0, { queryRoutes(south, west, north, east, lim) }, ::routeGeoJson)
        val pub = TrailFilterState.areaPublic; val rst = TrailFilterState.areaRestricted
        if (zoom < LAND_MIN_ZOOM || (!pub && !rst)) { js.add("window.updateLandAreas&&updateLandAreas(null,false,false)"); counts["Land"] = 0 }
        else { val (json, n) = landGeoJson(south, west, north, east, pub, rst, LAND_LIMIT); counts["Land"] = n; js.add("window.updateLandAreas&&updateLandAreas($json,$pub,$rst)") }
        return Batch(js, counts)
    }

    // ---- reading -------------------------------------------------------------------------------------------------------------------
    private fun query(sql: String, cols: List<String>, args: List<Any>) = queryOn(conn, sql, cols, args)

    private fun queryOn(c: SQLiteConnection, sql: String, cols: List<String>, args: List<Any>): List<Map<String, String?>> {
        val st = c.prepare(sql)
        try {
            args.forEachIndexed { i, a -> when (a) { is Double -> st.bindDouble(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); else -> st.bindText(i + 1, a.toString()) } }
            val out = ArrayList<Map<String, String?>>()
            while (st.step()) out.add(cols.indices.associate { i -> cols[i] to (if (st.isNull(i)) null else st.getText(i)) })
            return out
        } finally { st.close() }
    }

    private fun simple(sql: String, cols: List<String>, s: Double, n: Double, w: Double, e: Double, limit: Int) =
        query(sql, cols, listOf(s, n, w, e, limit)).filter { it["geometry"] != null }

    companion object {
        // SpatialDisplayManager's gates and limits (2.7e), in app (Leaflet) zoom
        const val TRAILS_MIN_ZOOM = 9
        const val TRAILS_LIMIT = 1_000      // VECWELCOME-2026-10-31
        const val TRACKS_LIMIT = 500
        const val LAND_MIN_ZOOM = 9
        const val LAND_LIMIT = 600
        fun otherLimit(zoom: Int) = if (zoom < 14) 500 else 2000
        const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

        // ---- GeoJSON (SpatialDbManager.build*GeoJson) -------------------------------------------------------------------------------
        fun trailGeoJson(trails: List<Map<String, String?>>): String {
            val sb = StringBuilder().append("{\"type\":\"FeatureCollection\",\"features\":[")
            var first = true
            val restricted = TrailFilterState.restricted
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

        fun trackGeoJson(tracks: List<Map<String, String?>>) = tracks.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") { t ->
            "{\"type\":\"Feature\",\"properties\":{\"name\":\"${jsonEsc(t["name"] ?: "Unnamed Track")}\",\"cartoCode\":\"${jsonEsc(t["carto_code"] ?: "")}\",\"track_id\":\"${jsonEsc(t["track_id"] ?: "")}\"}," +
                "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[${lineCoords(t["geometry"] ?: "")}]}}" }

        fun routeGeoJson(routes: List<Map<String, String?>>) = routes.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") { r ->
            "{\"type\":\"Feature\",\"properties\":{\"name\":\"${jsonEsc(r["name"] ?: "Unnamed Route")}\",\"route_id\":\"${jsonEsc(r["route_id"] ?: "")}\"}," +
                "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[${lineCoords(r["geometry"] ?: "")}]}}" }

        private val pointRe = Regex("POINT\\(([\\d.\\-]+) ([\\d.\\-]+)\\)")
        /** As 2.7e, except a waypoint whose geometry is not a POINT is skipped cleanly (2.7e left a stray comma -- an invalid batch). */
        fun waypointGeoJson(wpts: List<Map<String, String?>>) = wpts.mapNotNull { w ->
            val m = pointRe.find(w["geometry"] ?: "") ?: return@mapNotNull null
            "{\"type\":\"Feature\",\"properties\":{\"waypoint_id\":\"${jsonEsc(w["waypoint_id"] ?: "")}\",\"name\":\"${jsonEsc(w["name"] ?: "Unnamed")}\"," +
                "\"wpt_type\":\"${jsonEsc(w["type"] ?: "other")}\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[${m.groupValues[1]},${m.groupValues[2]}]}}"
        }.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}")

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
