package com.grouptrack.shared.spatial

import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.log.gtLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.PI
import kotlin.math.abs
import com.grouptrack.shared.text.GtMath   // GTMATH2-2026-11-10: Android's math on every platform
import com.grouptrack.shared.text.GtMath.asin
import com.grouptrack.shared.text.GtMath.cos
import com.grouptrack.shared.text.GtMath.sin
import kotlin.math.sqrt

/**
 * GtRead -- SpatialDbManager's READING side in SHARED Kotlin (batch 2b, GTBATCH2B-2026-11-06). Ported from 2.7e, same SQL, same results:
 * search by name (with aliases), the artifact detail card, aliases, the artifact list, the corridor track list, trailheads near a point,
 * route names / numbering / hashes / notes, track name and geometry, the waypoint at a point, the track for a GPX text, the GPX point
 * parser, the geometry hash, routeUsingTrailhead.
 * ⭐ TEXT FROM A NUMBER COLUMN is made the way Android's Cursor.getString makes it (CursorWindow: C "%g", 6 significant digits) -- see
 *   [cellText]. 2.7e read map numbers (min_lat ...) that way; reading them as SQLite text instead gives 15-17 digits and the platforms
 *   differ (the acres lesson, 14:09). So the iPad shows exactly what the Droid shows.
 * ⚠ A connection is not thread-safe: the platform serialises calls (Android: the lock in SpatialDbManager's shared holder).
 * @param conn grouptrack_spatial.db.  @param dataConn grouptrack_data.db -- CODE RULE 1: optional as in 2.7e (aliases / properties / notes
 *   are skipped without it, exactly as 2.7e's `extensionDb ?: return`).
 */
class GtRead(private val conn: SQLiteConnection, private val dataConn: SQLiteConnection?) {
    private val log = gtLogger("SpatialRead")

    // ---- reading helpers ------------------------------------------------------------------------------------------------------
    private fun <T> stmt(c: SQLiteConnection, sql: String, args: List<Any>, body: (SQLiteStatement) -> T): T {
        val st = c.prepare(sql)
        try {
            args.forEachIndexed { i, a -> when (a) { is Double -> st.bindDouble(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); is Long -> st.bindLong(i + 1, a); else -> st.bindText(i + 1, a.toString()) } }
            return body(st)
        } finally { st.close() }
    }
    private fun rows(c: SQLiteConnection, sql: String, args: List<Any>, n: Int): List<List<String?>> = stmt(c, sql, args) { st ->
        val out = ArrayList<List<String?>>(); while (st.step()) out.add((0 until n).map { cellText(st, it) }); out }
    private fun first(c: SQLiteConnection, sql: String, args: List<Any>): String? = stmt(c, sql, args) { st -> if (st.step()) cellText(st, 0) else null }

    // ---- search (searchByName) ------------------------------------------------------------------------------------------------
    fun searchByName(type: String, term: String, limit: Int = 200): List<Map<String, String?>> {
        val (table, idCol) = when (type) {
            "tracks" -> "tracks" to "track_id"; "trails" -> "trails" to "trail_id"
            "waypoints" -> "waypoints" to "waypoint_id"; "routes" -> "routes" to "route_id"
            else -> return emptyList()
        }
        val results = mutableListOf<Map<String, String?>>()
        val nameHitIds = HashSet<String>()
        try {
            for (r in rows(conn, "SELECT $idCol, name, geom_hash, min_lat, max_lat, min_lon, max_lon FROM $table " +
                "WHERE name LIKE ? AND name IS NOT NULL AND TRIM(name) <> '' AND name <> 'Not Named' " +
                "ORDER BY name COLLATE NOCASE, geom_hash LIMIT ?", listOf("%$term%", limit), 7)) {
                val id = r[0]
                if (id != null) nameHitIds.add(id)
                results.add(mapOf("id" to id, "name" to r[1], "geom_hash" to r[2], "type" to type, "matched_via_alias" to "false", "matched_alias" to null,
                    "min_lat" to r[3], "max_lat" to r[4], "min_lon" to r[5], "max_lon" to r[6]))
            }
            val edb = dataConn
            if (edb != null && results.size < limit) {
                val singular = type.removeSuffix("s")
                val aliasMatches = LinkedHashMap<String, String?>()
                for (a in rows(edb, "SELECT artifact_id, alias FROM artifact_aliases WHERE artifact_type = ? AND alias LIKE ? " +
                    "ORDER BY is_preferred DESC, alias COLLATE NOCASE LIMIT ?", listOf(singular, "%$term%", limit), 2)) {
                    val aid = a[0] ?: continue
                    if (aid in nameHitIds) continue
                    if (!aliasMatches.containsKey(aid)) aliasMatches[aid] = a[1]
                }
                for ((aid, matchedAlias) in aliasMatches) {
                    if (results.size >= limit) break
                    val r = rows(conn, "SELECT name, geom_hash, min_lat, max_lat, min_lon, max_lon FROM $table WHERE $idCol = ? LIMIT 1", listOf(aid), 6).firstOrNull() ?: continue
                    results.add(mapOf("id" to aid, "name" to r[0], "geom_hash" to r[1], "type" to type, "matched_via_alias" to "true", "matched_alias" to matchedAlias,
                        "min_lat" to r[2], "max_lat" to r[3], "min_lon" to r[4], "max_lon" to r[5]))
                }
            }
            log.i { "searchByName($type,'$term') -> ${results.size} (alias-join)" }
        } catch (e: Exception) { log.e { "searchByName failed: ${e.message}" } }
        return results
    }

    // ---- the detail card (getArtifactDetail) -----------------------------------------------------------------------------------
    fun getArtifactDetail(type: String, artifactId: String): LinkedHashMap<String, String?> {
        val out = LinkedHashMap<String, String?>()
        val (table, idCol) = spatialTableFor(type) ?: return out
        stmt(conn, "SELECT * FROM $table WHERE $idCol = ? LIMIT 1", listOf(artifactId)) { st ->
            if (st.step()) for (i in 0 until st.getColumnCount()) {
                val col = st.getColumnName(i)
                if (col == "geometry") continue
                var v: String? = cellText(st, i)
                if (col == "name" && (v == null || v.isBlank() || v == "null")) v = "Not Named"
                out[col] = v
            }
        }
        val (propTable, propIdCol) = propertiesTableFor(type) ?: return out
        val edb = dataConn ?: return out
        try {
            stmt(edb, "SELECT * FROM $propTable WHERE $propIdCol = ? LIMIT 1", listOf(artifactId)) { st ->
                if (st.step()) for (i in 0 until st.getColumnCount()) {
                    val col = st.getColumnName(i)
                    if (col == propIdCol) continue
                    if (out.containsKey(col) && !out[col].isNullOrBlank()) continue
                    val v = cellText(st, i) ?: continue
                    if (v.isBlank()) continue
                    out[col] = v
                }
            }
        } catch (_: Exception) { }
        return out
    }

    fun getAliasesFor(type: String, artifactId: String): List<Map<String, String?>> {
        val edb = dataConn ?: return emptyList()
        val t = type.lowercase().removeSuffix("s")
        return rows(edb, "SELECT alias_id, alias, is_preferred, source, creation_date FROM artifact_aliases " +
            "WHERE artifact_type = ? AND artifact_id = ? ORDER BY is_preferred DESC, alias COLLATE NOCASE", listOf(t, artifactId), 5)
            .map { r -> mapOf("alias_id" to r[0], "alias" to r[1], "is_preferred" to r[2], "source" to r[3], "creation_date" to r[4]) }
    }

    // ---- lists ----------------------------------------------------------------------------------------------------------------
    fun queryArtifactList(table: String, south: Double, west: Double, north: Double, east: Double): List<Map<String, String?>> {
        val idCol = when (table) { "trails" -> "trail_id"; "tracks" -> "track_id"; "waypoints" -> "waypoint_id"; "routes" -> "route_id"; else -> return emptyList() }
        return try {
            val typeCol = if (table == "waypoints") ", type" else ""
            val typeFilter = if (table == "tracks") " AND (type='TRACK' OR type IS NULL)" else ""
            rows(conn, "SELECT $idCol, name$typeCol FROM $table WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?$typeFilter ORDER BY name LIMIT 200",
                listOf(south, north, west, east), if (table == "waypoints") 3 else 2)
                .map { r -> mapOf("id" to r[0], "name" to r[1], "type" to (if (table == "waypoints") r[2] else "")) }
        } catch (e: Exception) { log.e { "queryArtifactList($table) failed: ${e.message}" }; emptyList() }
    }

    fun queryAllTracksForCorridor(): List<Map<String, String?>> = try {
        rows(conn, "SELECT track_id, name, geom_hash FROM tracks WHERE (type='TRACK' OR type IS NULL) ORDER BY name COLLATE NOCASE, geom_hash", emptyList(), 3)
            .map { r -> mapOf("id" to r[0], "name" to r[1], "geom_hash" to r[2]) }.also { log.i { "CORRIDORQUERY-2026-08-05: ${it.size} track(s) for picker" } }
    } catch (e: Exception) { log.e { "queryAllTracksForCorridor failed: ${e.message}" }; emptyList() }

    // ---- trailheads (TRAILHEAD-2026-09-23) ------------------------------------------------------------------------------------
    data class Trailhead(val waypointId: String, val name: String, val lat: Double, val lon: Double, val miles: Double)

    fun trailheadsNear(lat: Double, lon: Double, radiusMiles: Double = 0.5): List<Trailhead> {
        val dLat = radiusMiles / 69.0
        val dLon = radiusMiles / (69.0 * cos(toRadians(lat))).coerceAtLeast(0.01)
        val out = mutableListOf<Trailhead>()
        try {
            stmt(conn, "SELECT waypoint_id, name, geometry FROM waypoints WHERE type='trailhead' AND " +
                "(min_lat IS NULL OR (max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?))",
                listOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString())) { c ->
                while (c.step()) {
                    val g = cellText(c, 2) ?: continue
                    val p = g.removePrefix("POINT(").removeSuffix(")").trim().split(" ")
                    if (p.size < 2) continue
                    val wLon = p[0].toDoubleOrNull() ?: continue
                    val wLat = p[1].toDoubleOrNull() ?: continue
                    val mi = haversineMiles(lat, lon, wLat, wLon).toDouble()
                    val id = cellText(c, 0) ?: continue
                    if (mi <= radiusMiles) out += Trailhead(id, cellText(c, 1) ?: "Trailhead", wLat, wLon, mi)
                }
            }
        } catch (e: Exception) { log.e { "trailheadsNear error: " + e.message } }
        return out.sortedBy { it.miles }
    }

    /** The route whose recipe anchor is this trailhead (within ~10 m), or null. */
    fun routeUsingTrailhead(waypointId: String): String? {
        try {
            val w = rows(conn, "SELECT type, geometry FROM waypoints WHERE waypoint_id=?", listOf(waypointId), 2).firstOrNull() ?: return null
            if ((w[0] ?: "") != "trailhead") return null
            val p = (w[1] ?: return null).removePrefix("POINT(").removeSuffix(")").trim().split(" ")
            if (p.size < 2) return null
            val wLon = p[0].toDoubleOrNull() ?: return null
            val wLat = p[1].toDoubleOrNull() ?: return null
            for (r in rows(conn, "SELECT route_id, name FROM routes", emptyList(), 2)) {
                val rid = r[0] ?: continue
                val recipe = (notesObject(rid)?.get("recipe") as? JsonObject) ?: continue
                val aLat = (recipe["anchorLat"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() ?: Double.NaN } ?: continue
                val aLon = (recipe["anchorLon"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() ?: Double.NaN } ?: continue
                if (haversineMiles(wLat, wLon, aLat, aLon).toDouble() <= 0.006) return r[1] ?: "a route"
            }
        } catch (e: Exception) { log.e { "routeUsingTrailhead error: " + e.message } }
        return null
    }

    // ---- routes ---------------------------------------------------------------------------------------------------------------
    /** The route's notes payload (route_notes, kind 'narrative') exactly as stored; the platform makes its own JSON object from it. */
    fun readRouteNotesPayload(routeId: String): String? {
        val edb = dataConn ?: return null
        return try { first(edb, "SELECT payload FROM route_notes WHERE route_id=? AND kind='narrative' LIMIT 1", listOf(routeId)) }
        catch (e: Exception) { log.w { "readRouteNotes: ${e.message}" }; null }
    }
    private fun notesObject(routeId: String): JsonObject? = readRouteNotesPayload(routeId)?.let { try { Json.parseToJsonElement(it).jsonObject } catch (e: Exception) { null } }

    fun nextRouteNumber(base: String): Int {
        val b = base.trim().lowercase()
        if (b.isEmpty()) return 1
        var max = 0
        fun take(name: String?) {
            val n = name?.trim()?.lowercase() ?: return
            if (!n.startsWith("$b ")) return
            val rest = n.substring(b.length + 1)
            if (rest.isNotEmpty() && rest.all { it.isDigit() } && rest.length < 7) max = maxOf(max, rest.toInt())
        }
        try { rows(conn, "SELECT name FROM routes WHERE name IS NOT NULL", emptyList(), 1).forEach { take(it[0]) } } catch (e: Exception) { log.w { "nextRouteNumber routes: ${e.message}" } }
        try { dataConn?.let { d -> rows(d, "SELECT alias FROM artifact_aliases WHERE artifact_type='route'", emptyList(), 1).forEach { take(it[0]) } } } catch (e: Exception) { log.w { "nextRouteNumber aliases: ${e.message}" } }
        return max + 1
    }
    fun routeNameById(routeId: String): String? = try { first(conn, "SELECT name FROM routes WHERE route_id=? LIMIT 1", listOf(routeId)) } catch (e: Exception) { null }
    fun findRouteIdByHash(geomHash: String): String? = try { first(conn, "SELECT route_id FROM routes WHERE geom_hash=? LIMIT 1", listOf(geomHash)) } catch (e: Exception) { null }
    fun routeGeomHash(routeId: String): String? = try { first(conn, "SELECT geom_hash FROM routes WHERE route_id=? LIMIT 1", listOf(routeId)) } catch (e: Exception) { null }
    fun routeNameExists(name: String): Boolean {
        val needle = name.trim()
        if (needle.isEmpty()) return false
        return try { stmt(conn, "SELECT 1 FROM routes WHERE name IS NOT NULL AND lower(trim(name)) = lower(trim(?)) LIMIT 1", listOf(needle)) { it.step() } }
        catch (e: Exception) { log.e { "routeNameExists failed: " + e.message }; false }
    }

    // ---- tracks and waypoints -------------------------------------------------------------------------------------------------
    fun trackGeometry(trackId: String): String? = try { first(conn, "SELECT geometry FROM tracks WHERE track_id=? LIMIT 1", listOf(trackId)) }
        catch (e: Exception) { log.w { "TRACKROUTE: trackGeometry failed: ${e.message}" }; null }
    fun trackName(trackId: String): String? = try { first(conn, "SELECT name FROM tracks WHERE track_id=? LIMIT 1", listOf(trackId)) } catch (e: Exception) { null }
    fun waypointNameAt(lat: Double, lon: Double): String? {
        val d = 0.00002   // ~2 metres
        return try {
            first(conn, "SELECT name FROM waypoints WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT 1",
                listOf((lat - d).toString(), (lat + d).toString(), (lon - d).toString(), (lon + d).toString()))?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { log.w { "waypointNameAt failed: ${e.message}" }; null }
    }
    fun trackIdForGpxText(text: String): String? = try {
        val coords = parseGpxTrackPoints(text)
        if (coords.isEmpty()) null else first(conn, "SELECT track_id FROM tracks WHERE geom_hash=? LIMIT 1",
            listOf(GtGeom.lineLonLat(coords).hash))   // GEOMONE-2026-11-09: the one module (coords are (lon, lat)); Java's number text on the iPad too
    } catch (e: Exception) { log.w { "ENDRIDE: trackIdForGpxText failed: ${e.message}" }; null }

    companion object {
        fun spatialTableFor(type: String): Pair<String, String>? = when (type.lowercase()) {
            "trail", "trails" -> "trails" to "trail_id"; "track", "tracks" -> "tracks" to "track_id"
            "waypoint", "waypoints" -> "waypoints" to "waypoint_id"; "route", "routes" -> "routes" to "route_id"; else -> null
        }
        fun propertiesTableFor(type: String): Pair<String, String>? = when (type.lowercase()) {
            "trail", "trails" -> "trail_properties" to "trail_id"; "track", "tracks" -> "track_properties" to "track_id"
            "waypoint", "waypoints" -> "waypoint_properties" to "waypoint_id"; "route", "routes" -> "route_properties" to "route_id"; else -> null
        }

        /** <trkpt lat lon> -> (lon, lat) pairs, as 2.7e. */
        fun parseGpxTrackPoints(gpxText: String): List<Pair<Double, Double>> {
            val coords = mutableListOf<Pair<Double, Double>>()
            for (m in Regex("""<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"""").findAll(gpxText)) {
                val lat = m.groupValues[1].toDoubleOrNull() ?: continue
                val lon = m.groupValues[2].toDoubleOrNull() ?: continue
                coords.add(Pair(lon, lat))
            }
            return coords
        }

        /** SHA-256 of the WKT, lower-case hex (as 2.7e's MessageDigest). */
        fun computeGeomHash(wkt: String): String = GtGeom.hash(wkt)   // GEOMONE-2026-11-09: the one hash (callers move to GtGeom in part B)

        fun notNamed(name: String?): String = if (name.isNullOrBlank()) "Not Named" else name

        /** STEP7FIX-2026-11-08: Android's Math.toRadians is angdeg * 0.017453292519943295 (proven on Droid 1: probe 100,000/100,000 on
         *  11-07, golden 2026-11-08_1834). The earlier (angdeg / 180.0 * PI) differed in the last bit on 26.6% of inputs. Same as GtRoute
         *  and GtJava.toRadians -- so distances match the Droid to the last bit. */
        fun toRadians(deg: Double) = deg * 0.017453292519943295

        /** ConvoyEngine.haversineMiles (2.7e): miles, as a Float. */
        fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val r = 3958.8
            val dLat = toRadians(lat2 - lat1)
            val dLon = toRadians(lon2 - lon1)
            val a = GtMath.pow(sin(dLat / 2), 2.0) + cos(toRadians(lat1)) * cos(toRadians(lat2)) * GtMath.pow(sin(dLon / 2), 2.0)   // GTMATH2-2026-11-10: was x.pow(2) = Math.pow(x, 2.0)
            return (2 * r * asin(sqrt(a))).toFloat()
        }

        /** A column as Android's Cursor.getString gives it: NULL -> null, a REAL -> C "%g" (6 significant digits), else SQLite's text. */
        fun cellText(st: SQLiteStatement, i: Int): String? = when (st.getColumnType(i)) {
            SQLITE_DATA_NULL -> null
            SQLITE_DATA_FLOAT -> cG(st.getDouble(i))
            else -> st.getText(i)
        }

        /** C printf "%g" (precision 6), as Android's CursorWindow uses for a REAL read as a String.
         *  GTCG2-2026-11-06: rounds the EXACT stored value, half-even, as C does (36.50225 is held as 36.502249999... -> "36.5022").
         *  The first version rounded the short printed form and differed on exact-halfway digits (113 of 200,000 coordinates; found by
         *  the certification). Proved identical to C/Python "%g" on 1,135,284 values (every trail edge and land acreage on Droid 1 +
         *  200,000 random + the halfway cases). */
        fun cG(v: Double): String {
            if (v.isNaN()) return "nan"
            if (v.isInfinite()) return if (v > 0) "inf" else "-inf"
            if (v == 0.0) return if (1.0 / v < 0) "-0" else "0"
            val neg = v < 0
            val (all, pp0) = exactDigits(abs(v))
            val lead = all.indexOfFirst { it != '0' }
            val digits = all.substring(lead)
            var x = pp0 - lead - 1
            var k = digits.take(6).padEnd(6, '0').toLong()
            val rest = if (digits.length > 6) digits.substring(6) else ""
            if (rest.isNotEmpty()) {
                val tail = rest.substring(1).any { it != '0' }
                if (rest[0] > '5' || (rest[0] == '5' && tail) || (rest[0] == '5' && !tail && k % 2L == 1L)) k += 1
            }
            if (k >= 1_000_000L) { k /= 10; x += 1 }
            val d = k.toString()
            val sb = StringBuilder()
            if (neg) sb.append('-')
            if (x < -4 || x >= 6) {
                val m = (d.substring(0, 1) + "." + d.substring(1)).trimEnd('0').trimEnd('.')
                sb.append(m).append('e').append(if (x < 0) '-' else '+').append(abs(x).toString().padStart(2, '0'))
            } else if (x >= 0) {
                val ip = d.substring(0, x + 1); val fp = d.substring(x + 1).trimEnd('0')
                sb.append(ip); if (fp.isNotEmpty()) sb.append('.').append(fp)
            } else {
                sb.append("0.").append("0".repeat(-x - 1)).append(d.trimEnd('0'))
            }
            return sb.toString()
        }

        /** The exact decimal digits of a positive finite double, and how many of them come before the decimal point (can be <= 0). */
        private fun exactDigits(a: Double): Pair<String, Int> {
            val bits = a.toRawBits()
            val eb = ((bits ushr 52) and 0x7ffL).toInt()
            var m = bits and 0xfffffffffffffL
            val e2: Int
            if (eb == 0) e2 = -1074 else { m = m or (1L shl 52); e2 = eb - 1075 }
            val base = 1_000_000_000L
            val limbs = ArrayList<Long>()
            var t = m
            while (t > 0) { limbs.add(t % base); t /= base }
            fun mul(f: Long) {
                var carry = 0L
                for (i in limbs.indices) { val p = limbs[i] * f + carry; limbs[i] = p % base; carry = p / base }
                while (carry > 0) { limbs.add(carry % base); carry /= base }
            }
            var n = if (e2 >= 0) e2 else -e2
            if (e2 >= 0) { while (n >= 30) { mul(1L shl 30); n -= 30 }; if (n > 0) mul(1L shl n) }
            else { while (n >= 13) { mul(1_220_703_125L); n -= 13 }; var f = 1L; repeat(n) { f *= 5 }; if (n > 0) mul(f) }
            val sb = StringBuilder(limbs.last().toString())
            for (i in limbs.size - 2 downTo 0) sb.append(limbs[i].toString().padStart(9, '0'))
            val s = sb.toString()
            return s to (if (e2 >= 0) s.length else s.length - (-e2))
        }
    }
}
