package com.geeksville.mesh.convoy

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.track.Line
import com.grouptrack.shared.track.LineSource
import com.grouptrack.shared.track.TrackPoint

/** PANELMOVE3-2026-11-19: ANDROID'S LineSource, split out of TrackRules when the rules moved to commonMain. It stays here because it
 *  ACQUIRES a platform resource (SpatialDbManager's SQLite connection) -- SEAMRULE-2026-11-19. The rules themselves are shared.
 *  TRACKGROUNDS-2026-11-19: Android SETS TrackGrounds.source = DbLineSource at startup; the recorder ASSERTS it at record-start.
 *  The cache is keyed id + NUL + wkt.length and memoizes only the WKT -> Line PARSE: every query still goes to the database, so a
 *  trail written during this session (RiderTrailWriter) is found by the next lookup with no restart (measured 11-19).
 *  TRACKRULES-2026-10-19 unchanged -- this is a move, not a rewrite. */
object DbLineSource : LineSource {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Line>>()
    private fun parse(id: String, wkt: String): List<Line> = cache.getOrPut(id + "\u0000" + wkt.length) {
        if (cache.size > 2000) cache.clear()
        val body = when {
            wkt.startsWith("MULTILINESTRING(") -> wkt.removePrefix("MULTILINESTRING(").removeSuffix(")")
            wkt.startsWith("LINESTRING(") -> "(" + wkt.removePrefix("LINESTRING(").removeSuffix(")") + ")"
            else -> return@getOrPut emptyList()
        }
        body.split("),(").mapIndexedNotNull { k, part ->
            val pts = part.trim('(', ')').split(",").mapNotNull { c ->
                val xy = c.trim().split(" ")
                val lon = xy.getOrNull(0)?.toDoubleOrNull(); val lat = xy.getOrNull(1)?.toDoubleOrNull()
                if (lon == null || lat == null) null else TrackPoint(lat, lon)
            }
            if (pts.size < 2) null else Line(if (k == 0) id else "$id#$k", pts)
        }
    }
    private fun query(sql: String, args: Array<String>): List<Line> = try {
        val db = SpatialDbManager.getSpatialDb()
        if (db == null) emptyList() else db.rawQuery(sql, args).use { c ->
            val out = ArrayList<Line>()
            while (c.moveToNext()) { val id = c.getString(0) ?: continue; val g = c.getString(1) ?: continue; out += parse(id, g) }
            out
        }
    } catch (e: Exception) { gtLogger("TRACKRULES").w { "TRACKRULES-2026-10-19 lookup failed: " + e.message }; emptyList() }
    private fun box(s: Double, w: Double, n: Double, e: Double) = arrayOf(s.toString(), n.toString(), w.toString(), e.toString())
    override fun route(id: String): Line? = query("SELECT route_id, geometry FROM routes WHERE route_id = ?", arrayOf(id)).firstOrNull()
    override fun routesNear(s: Double, w: Double, n: Double, e: Double) =
        query("SELECT route_id, geometry FROM routes WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT 50", box(s, w, n, e))
    override fun tracksNear(s: Double, w: Double, n: Double, e: Double) =   // GAPFILL-2026-10-20
        query("SELECT track_id, geometry FROM tracks WHERE (type='TRACK' OR type IS NULL) AND min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT 50", box(s, w, n, e))
    override fun trailsNear(s: Double, w: Double, n: Double, e: Double) =
        query("SELECT trail_id, geometry FROM trails WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT 200", box(s, w, n, e))
}
