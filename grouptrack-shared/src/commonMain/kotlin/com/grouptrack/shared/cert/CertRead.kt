package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.gpx.GtGpx
import com.grouptrack.shared.spatial.GtRead

/**
 * GTCERT-2B-2026-11-06: the batch-2b test cases -- SpatialDbManager's READING side over the whole database. The same cases run against
 * [Api] implementations: OLD (2.7e's SpatialDbManager, Android) and NEW (shared GtRead, Android + Mac). Lines as GtCert ("KIND|KEY|VARIANT|
 * COUNT|FINGERPRINT"). Doubles are written as their exact bits, never as text, so formatting cannot fake or hide a difference.
 * ⚠ TEST-ONLY (CODE RULE 3).
 */
object CertRead {
    interface Api {
        fun search(type: String, term: String): List<Map<String, String?>>
        fun detail(type: String, id: String): Map<String, String?>
        fun aliases(type: String, id: String): List<Map<String, String?>>
        fun list(table: String): List<Map<String, String?>>
        fun corridor(): List<Map<String, String?>>
        /** each: waypointId, name, lat, lon, miles */
        fun trailheads(lat: Double, lon: Double): List<List<Any>>
        fun routeUsing(waypointId: String): String?
        fun routeName(id: String): String?
        fun routeHash(id: String): String?
        fun routeByHash(hash: String): String?
        fun nameExists(name: String): Boolean
        fun nextNumber(base: String): Int
        fun trackName(id: String): String?
        fun trackGeom(id: String): String?
        fun waypointAt(lat: Double, lon: Double): String?
        fun geomHash(wkt: String): String
        fun parseGpx(text: String): List<Pair<Double, Double>>
        fun trackIdForGpx(text: String): String?
    }

    /** The shared code as an [Api]. */
    class Shared(private val r: GtRead) : Api {
        override fun search(type: String, term: String) = r.searchByName(type, term)
        override fun detail(type: String, id: String): Map<String, String?> = r.getArtifactDetail(type, id)
        override fun aliases(type: String, id: String) = r.getAliasesFor(type, id)
        override fun list(table: String) = r.queryArtifactList(table, -90.0, -180.0, 90.0, 180.0)
        override fun corridor() = r.queryAllTracksForCorridor()
        override fun trailheads(lat: Double, lon: Double) = r.trailheadsNear(lat, lon).map { listOf<Any>(it.waypointId, it.name, it.lat, it.lon, it.miles) }
        override fun routeUsing(waypointId: String) = r.routeUsingTrailhead(waypointId)
        override fun routeName(id: String) = r.routeNameById(id)
        override fun routeHash(id: String) = r.routeGeomHash(id)
        override fun routeByHash(hash: String) = r.findRouteIdByHash(hash)
        override fun nameExists(name: String) = r.routeNameExists(name)
        override fun nextNumber(base: String) = r.nextRouteNumber(base)
        override fun trackName(id: String) = r.trackName(id)
        override fun trackGeom(id: String) = r.trackGeometry(id)
        override fun waypointAt(lat: Double, lon: Double) = r.waypointNameAt(lat, lon)
        override fun geomHash(wkt: String) = GtRead.computeGeomHash(wkt)
        override fun parseGpx(text: String) = GtRead.parseGpxTrackPoints(text)
        override fun trackIdForGpx(text: String) = r.trackIdForGpxText(text)
    }

    private fun v(x: Any?): String = when (x) { null -> "∅"; is Double -> "d" + x.toRawBits().toString(16); is Float -> "f" + x.toRawBits().toString(16); else -> x.toString() }
    private fun maps(l: List<Map<String, String?>>) = l.joinToString("\u0002") { m -> m.entries.joinToString("\u0001") { it.key + "=" + v(it.value) } }

    /** STEP8-2026-11-07: which trail pages the per-trail GEOMHASH/PARSEGPX check runs -- page k when k % every == start.
     *  Not optional (CODE RULE 1): every caller states its sample; ALL = every page. */
    data class TrailSample(val every: Int, val start: Int) {
        init { require(every >= 1 && start in 0 until every) }
        fun takes(k: Int) = k % every == start
        companion object { val ALL = TrailSample(1, 0) }
    }

    fun run(api: Api, conn: SQLiteConnection, sample: TrailSample, emit: (String) -> Unit, progress: (String) -> Unit) {
        val types = listOf("trails", "tracks", "waypoints", "routes")
        val ids = types.associateWith { t -> if (t == "trails") GtCert.sampleTrailIds(conn) else { val col = t.removeSuffix("s") + "_id"; GtCert.ids(conn, "SELECT $col FROM $t ORDER BY $col") } }   // GTCERTV3: trails = the sample
        fun pageEach(kind: String, t: String, one: (String) -> String) {
            ids.getValue(t).chunked(GtCert.PAGE).forEachIndexed { k, chunk ->
                if (k % 10 == 0) progress("$kind $t page $k")   // GTCERTV3
                val sb = StringBuilder(); for (id in chunk) sb.append(one(id)).append('\u0003')
                emit(GtCert.line(kind, t + "/p" + k.toString().padStart(4, '0'), "-", chunk.size, GtCert.fp(sb.toString())))
            }
        }
        // search: every letter and digit, and the empty term, in each type
        for (t in types) for (term in listOf("") + ('a'..'z').map { "$it" } + ('0'..'9').map { "$it" }) {
            val r = api.search(t, term); emit(GtCert.line("SEARCH", t, if (term.isEmpty()) "-" else term, r.size, GtCert.fp(maps(r))))
        }
        progress("search done")
        // the detail card of every artifact
        for (t in types) pageEach("DETAIL", t) { id -> maps(listOf(api.detail(t, id))) }
        progress("detail done")
        // aliases of every artifact that has one, and the lists
        for (t in types) pageEach("ALIASES", t) { id -> maps(api.aliases(t, id)) }
        for (t in types) api.list(t).let { emit(GtCert.line("LIST", t, "-", it.size, GtCert.fp(maps(it)))) }
        api.corridor().let { emit(GtCert.line("CORRIDOR", "tracks", "-", it.size, GtCert.fp(maps(it)))) }
        progress("aliases/lists done")
        // trailheads near every waypoint, the route anchored at it, the waypoint at its point
        val pts = HashMap<String, Pair<Double, Double>>()
        val st = conn.prepare("SELECT waypoint_id, geometry FROM waypoints")
        try { while (st.step()) { if (st.isNull(0) || st.isNull(1)) continue
            val p = st.getText(1).removePrefix("POINT(").removeSuffix(")").trim().split(" ")
            val lon = p.getOrNull(0)?.toDoubleOrNull(); val lat = p.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lon != null) pts[st.getText(0)] = lat to lon } } finally { st.close() }
        pageEach("THEAD", "waypoints") { id -> pts[id]?.let { (la, lo) -> api.trailheads(la, lo).joinToString("\u0001") { r -> r.joinToString(",") { v(it) } } } ?: "-" }
        pageEach("RUSING", "waypoints") { id -> v(api.routeUsing(id)) }
        pageEach("WPTAT", "waypoints") { id -> pts[id]?.let { (la, lo) -> v(api.waypointAt(la, lo)) } ?: "-" }
        progress("trailheads done")
        // routes: name, hash, back by hash, name exists, next number for its base
        pageEach("ROUTE", "routes") { id ->
            val n = api.routeName(id); val h = api.routeHash(id)
            val base = (n ?: "").trim().replace(Regex("\\s+\\d+$"), "")
            listOf(v(n), v(h), v(h?.let { api.routeByHash(it) }), v(n?.let { api.nameExists(it) }), v(api.nextNumber(base))).joinToString(",")
        }
        // tracks: name, geometry, the track found again from its own GPX text
        pageEach("TRACK", "tracks") { id ->
            val g = api.trackGeom(id)
            val gpx = g?.let { GtGpx.trail(api.trackName(id) ?: "", it) }
            listOf(v(api.trackName(id)), v(g?.let { GtCert.fp(it) }), v(gpx?.let { api.trackIdForGpx(it) })).joinToString(",")
        }
        // every trail: the geometry hash, and its GPX points parsed back
        val geoms = HashMap<String, String>()
        ids.getValue("trails").chunked(GtCert.PAGE).forEachIndexed { k, chunk ->
            if (!sample.takes(k)) return@forEachIndexed   // STEP8-2026-11-07
            geoms.clear()
            if (k % 10 == 0) progress("GEOMHASH/PARSEGPX page $k")   // GTCERTV3
            val s2 = conn.prepare("SELECT trail_id, name, geometry FROM trails WHERE trail_id IN (" + chunk.joinToString(",") { "'" + it.replace("'", "''") + "'" } + ")")
            try { while (s2.step()) if (!s2.isNull(0) && !s2.isNull(2)) geoms[s2.getText(0)] = s2.getText(2) } finally { s2.close() }
            val sb = StringBuilder(); val sp = StringBuilder()
            for (id in chunk) { val g = geoms[id]; sb.append(if (g == null) "-" else api.geomHash(g)).append(','); sp.append(if (g == null) "-" else api.parseGpx(GtGpx.trail("", g)).joinToString(";") { v(it.first) + " " + v(it.second) }).append('\u0003') }
            val key = "trails/p" + k.toString().padStart(4, '0')
            emit(GtCert.line("GEOMHASH", key, "-", chunk.size, GtCert.fp(sb.toString())))
            emit(GtCert.line("PARSEGPX", key, "-", chunk.size, GtCert.fp(sp.toString())))
        }
        progress("2b done")
    }
}
