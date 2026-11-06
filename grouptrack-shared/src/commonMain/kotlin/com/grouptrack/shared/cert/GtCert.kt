package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.filter.TrailFilterState
import com.grouptrack.shared.gpx.GtGpx
import com.grouptrack.shared.spatial.GtSpatial
import okio.ByteString.Companion.encodeUtf8

/**
 * GTCERT-2026-11-06 (2.8, Fred 13:17-13:18): THE WHOLE-DATABASE CERTIFICATION. Instead of a walk per batch, one run compares the moved
 * code with 2.7e over the ENTIRE database -- every row, every filter element on and off by itself -- and writes one line per check:
 *     KIND|KEY|VARIANT|COUNT|FINGERPRINT
 * The same lines come from: OLD = 2.7e's code (frozen copy, Android), NEW = this shared code on Android, MAC = this shared code on the
 * Mac (iPad simulator test) on a copy of the same database. Certified = the three files match line for line.
 * The latitude/longitude selection (bbox, ORDER BY, LIMIT) is the same SQL text on both sides and is not what moved (Fred 13:18), so the
 * whole database is read as id ranges ("pages") instead of map views.
 * FINGERPRINT = the first 16 hex of the SHA-256 of the exact output text -- byte-identical output, nothing stored.
 * ⚠ TEST-ONLY (CODE RULE 3): remove before 2.8 is released.
 */
object GtCert {
    data class Combo(val id: String, val land: String, val restricted: Boolean, val use: String, val off: Set<String>)

    const val PAGE = 900   // under SQLite's 999-variable limit on Android: the trail_properties IN (...) of a page must work as it does in 2.7e
    private const val ALL = 1_000_000

    fun fp(s: String): String = s.encodeUtf8().sha256().hex().substring(0, 16)

    fun ids(c: SQLiteConnection, sql: String): List<String> {
        val st = c.prepare(sql)
        try { val out = ArrayList<String>(); while (st.step()) if (!st.isNull(0)) out.add(st.getText(0)); return out } finally { st.close() }
    }

    fun categories(c: SQLiteConnection) = ids(c, "SELECT DISTINCT COALESCE(carto_code,'shape only') FROM trails ORDER BY 1")

    /** Everything on; then each element off (or changed) by itself; then the rider's saved settings. */
    fun combos(cats: List<String>, saved: Combo): List<Combo> {
        val base = Combo("all-on", "ALL", true, "ALL", emptySet())
        val out = arrayListOf(base,
            base.copy(id = "land-PUBLIC", land = "PUBLIC"), base.copy(id = "land-PRIVATE", land = "PRIVATE"),
            base.copy(id = "restricted-off", restricted = false),
            base.copy(id = "use-MOTORIZED", use = "MOTORIZED"), base.copy(id = "use-NON-MOTORIZED", use = "NON-MOTORIZED"),
            base.copy(id = "off-unofficial", off = setOf(TrailFilterState.ROW_UNOFFICIAL)))
        for (k in cats) out.add(base.copy(id = "off-" + k.replace(' ', '_').replace('|', '_'), off = setOf(k)))
        out.add(saved.copy(id = "saved"))
        return out
    }

    /** [lo, hi) id ranges of [PAGE] rows, as SQL fragments on [col] (text ids quoted, integer ids bare). */
    fun pages(c: SQLiteConnection, table: String, col: String, quoted: Boolean): List<Pair<String, String>> {
        val all = ids(c, "SELECT $col FROM $table ORDER BY $col")
        fun lit(v: String) = if (quoted) "'" + v.replace("'", "''") + "'" else v
        val out = ArrayList<Pair<String, String>>()
        var i = 0; var k = 0
        while (i < all.size) {
            val lo = all[i]; val hiIdx = i + PAGE
            val frag = if (hiIdx < all.size) " AND $col >= ${lit(lo)} AND $col < ${lit(all[hiIdx])}" else " AND $col >= ${lit(lo)}"
            out.add("p" + (k++).toString().padStart(4, '0') to frag)
            i = hiIdx
        }
        return out
    }

    fun line(kind: String, key: String, variant: String, count: Int, fingerprint: String) = "$kind|$key|$variant|$count|$fingerprint"

    /** The NEW side: everything through the shared code. [notes] = (headline, notes JSON as the platform writes it) for a route. */
    fun runNew(conn: SQLiteConnection, dataConn: SQLiteConnection?, saved: Combo, notes: (String) -> Pair<String, String?>,
               emit: (String) -> Unit, progress: (String) -> Unit) {
        val sp = GtSpatial(conn, dataConn)
        val cats = categories(conn)
        // 1. the filter: every combination's WHERE, and the full set of trail ids it lets through
        for (cb in combos(cats, saved)) {
            val w = TrailFilterState.whereFor(cb.land, cb.restricted, cb.use, cb.off)
            val got = ids(conn, "SELECT trail_id FROM trails WHERE 1=1$w ORDER BY trail_id")
            emit(line("WHERE", cb.id, "-", got.size, fp(got.joinToString("\n")) + "/" + fp(w)))
        }
        progress("filters done")
        // 2. every trail through the query + builder, Restricted on and off
        val tp = pages(conn, "trails", "trail_id", true)
        for (r in listOf(true, false)) {
            val w = TrailFilterState.whereFor("ALL", r, "ALL", emptySet())
            for ((k, frag) in tp) {
                val rows = sp.queryTrails(-90.0, -180.0, 90.0, 180.0, ALL, w + frag)
                emit(line("TRAILS", k, "r$r", rows.size, fp(GtSpatial.trailGeoJson(rows, r))))
            }
            progress("trails r$r done")
        }
        // 3. tracks, waypoints, routes -- the whole table each
        sp.queryTracks(-90.0, -180.0, 90.0, 180.0, ALL).let { emit(line("TRACKS", "all", "-", it.size, fp(GtSpatial.trackGeoJson(it)))) }
        sp.queryWaypoints(-90.0, -180.0, 90.0, 180.0, ALL).let { emit(line("WAYPOINTS", "all", "-", it.size, fp(GtSpatial.waypointGeoJson(it)))) }
        sp.queryRoutes(-90.0, -180.0, 90.0, 180.0, ALL).let { emit(line("ROUTES", "all", "-", it.size, fp(GtSpatial.routeGeoJson(it)))) }
        // 4. every land area, the three overlay settings
        val lp = pages(conn, "land_areas", "area_id", false)
        for ((pub, rst) in listOf(true to true, true to false, false to true)) for ((k, frag) in lp) {
            val (json, n) = sp.landGeoJson(-90.0, -180.0, 90.0, 180.0, pub, rst, ALL, frag)
            emit(line("LAND", k, "p${pub}r$rst", n, fp(json)))
        }
        progress("land done")
        // 5. GPX of every waypoint, route and trail (per page: count of GPX made + fingerprint of them all)
        fun gpxPages(kind: String, table: String, col: String, one: (String) -> String?) {
            for ((k, frag) in pages(conn, table, col, true)) {
                val sb = StringBuilder(); var n = 0
                for (id in ids(conn, "SELECT $col FROM $table WHERE 1=1$frag ORDER BY $col")) { val g = one(id); if (g != null) { sb.append(g).append('\u0000'); n++ } else sb.append("NULL\u0000") }
                emit(line(kind, k, "-", n, fp(sb.toString())))
            }
        }
        fun row(sql: String, id: String, cols: Int): List<String?>? {
            val st = conn.prepare(sql)
            try { st.bindText(1, id); if (!st.step()) return null; return (0 until cols).map { if (st.isNull(it)) null else st.getText(it) } } finally { st.close() }
        }
        gpxPages("GPXW", "waypoints", "waypoint_id") { id ->
            val r = row("SELECT name, type, geometry FROM waypoints WHERE waypoint_id=?", id, 3) ?: return@gpxPages null
            val geom = r[2] ?: return@gpxPages null
            GtGpx.waypoint(r[0] ?: "Unnamed", r[1] ?: "", geom, "")
        }
        gpxPages("GPXR", "routes", "route_id") { id ->
            val r = row("SELECT name, geometry FROM routes WHERE route_id=?", id, 2) ?: return@gpxPages null
            val geom = r[1] ?: return@gpxPages null
            val (headline, nj) = notes(id)
            GtGpx.route(r[0] ?: "Unnamed", geom, headline, nj)
        }
        gpxPages("GPXT", "trails", "trail_id") { id ->
            val r = row("SELECT name, geometry FROM trails WHERE trail_id=?", id, 2) ?: return@gpxPages null
            val geom = r[1] ?: return@gpxPages null
            GtGpx.trail(r[0] ?: "Unnamed", geom)
        }
        progress("gpx done")
    }
}
