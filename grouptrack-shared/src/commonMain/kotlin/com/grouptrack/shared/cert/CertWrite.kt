package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import okio.buffer
import com.grouptrack.shared.spatial.GtWrite

/**
 * GTCERTW-2026-11-06: step 10's test cases -- SpatialDbManager's WRITES, on COPIES of Droid 1's databases (never the live ones).
 * The same scripted sequence runs on each copy through an [Api] (OLD = 2.7e's SpatialDbManager pointed at the copy; NEW / MAC = the
 * shared GtWrite on the copy); then EVERY table of both databases is fingerprinted in chunks. New ids (random in 2.7e) are replaced by
 * "NEW#n" in order of first appearance, created_at / updated_at by "T" -- so the sides compare row for row.
 * Lines as GtCert: WRESULT|op|i|0|value  and  WTABLE|db.table|chunk|rows|fingerprint.   ⚠ TEST-ONLY (CODE RULE 3).
 * ⚠ deleteTrack is only ever called on tracks this script created (no GPX file exists for them) -- 2.7e's delete also removes the file.
 */
object CertWrite {
    interface Api {
        fun insertWaypointWithId(id: String, name: String, lat: Double, lon: Double, type: String): Boolean
        fun renameWaypoint(id: String, n: String); fun deleteWaypoint(id: String); fun changeWaypointType(id: String, t: String)
        fun insertRoute(name: String, wkt: String, a: Double, b: Double, c: Double, d: Double): String
        fun renameRoute(id: String, n: String); fun deleteRoute(id: String)
        fun writeRouteNotes(routeId: String, notesJson: String): Int
        fun deleteRouteNotes(routeId: String): Int
        fun setRouteAnchor(routeId: String, lat: Double, lon: Double, name: String): Boolean
        fun addAlias(type: String, id: String, name: String, hash: String); fun renameAlias(aliasId: String, t: String)
        fun deleteAlias(aliasId: String); fun setPreferredAlias(type: String, id: String, aliasId: String)
        fun insertTrack(name: String, wkt: String, a: Double, b: Double, c: Double, d: Double): String   // "wasNew,trackId,hash,name"
        fun renameTrack(id: String, n: String); fun deleteTrack(id: String)
        fun beginDedup(); fun endDedup(); fun sourceUidSeen(s: String, u: String): Boolean; fun markSourceUid(s: String, u: String)
        fun insertTrail(id: String, name: String?, wkt: String, a: Double, b: Double, c: Double, d: Double, created: String): String   // "id,DECISION"
        fun deleteTrail(id: String)
        fun clearAllTrails(): String
    }

    /** The shared code as an [Api]. */
    class Shared(private val w: GtWrite) : Api {
        override fun insertWaypointWithId(id: String, name: String, lat: Double, lon: Double, type: String) = w.insertWaypointWithId(id, name, lat, lon, type)
        override fun renameWaypoint(id: String, n: String) = w.renameWaypoint(id, n)
        override fun deleteWaypoint(id: String) = w.deleteWaypoint(id)
        override fun changeWaypointType(id: String, t: String) = w.changeWaypointType(id, t)
        override fun insertRoute(name: String, wkt: String, a: Double, b: Double, c: Double, d: Double) = w.insertRoute(name, wkt, a, b, c, d)
        override fun renameRoute(id: String, n: String) = w.renameRoute(id, n)
        override fun deleteRoute(id: String) = w.deleteRoute(id)
        override fun writeRouteNotes(routeId: String, notesJson: String) = w.writeRouteNotes(routeId, notesJson)
        override fun deleteRouteNotes(routeId: String) = w.deleteRouteNotes(routeId)
        override fun setRouteAnchor(routeId: String, lat: Double, lon: Double, name: String) = w.setRouteAnchor(routeId, lat, lon, name)
        override fun addAlias(type: String, id: String, name: String, hash: String) = w.addAlias(type, id, name, hash, null)
        override fun renameAlias(aliasId: String, t: String) = w.renameAlias(aliasId, t)
        override fun deleteAlias(aliasId: String) = w.deleteAlias(aliasId)
        override fun setPreferredAlias(type: String, id: String, aliasId: String) = w.setPreferredAlias(type, id, aliasId)
        override fun insertTrack(name: String, wkt: String, a: Double, b: Double, c: Double, d: Double) = w.insertTrackToDb(name, wkt, a, b, c, d).let { "${it.wasNew},${it.trackId},${it.geomHash},${it.name}" }
        override fun renameTrack(id: String, n: String) = w.renameTrackInDb(id, n)
        override fun deleteTrack(id: String) { w.deleteTrackFromDb(id) }
        override fun beginDedup() = w.beginDedupSession()
        override fun endDedup() = w.endDedupSession()
        override fun sourceUidSeen(s: String, u: String) = w.sourceUidSeen(s, u)
        override fun markSourceUid(s: String, u: String) = w.markSourceUid(s, u)
        override fun insertTrail(id: String, name: String?, wkt: String, a: Double, b: Double, c: Double, d: Double, created: String) = w.insertTrail(id, name, wkt, a, b, c, d, created).let { "${it.first},${it.second}" }
        override fun deleteTrail(id: String) = w.deleteTrailFromDb(id)
        override fun clearAllTrails() = w.clearAllTrailData().let { "${it.trails},${it.properties},${it.aliases},${it.error}" }
    }

    private class Ids(val original: Set<String>) {
        val map = HashMap<String, String>()
        fun n(v: String?): String = when { v == null -> "∅"; v in original -> v; else -> map.getOrPut(v) { "NEW#" + map.size } }
    }

    private fun rows(c: SQLiteConnection, sql: String, n: Int): List<List<String?>> {
        // GTCERTW-FIX4-2026-11-06 / GTCERTW-FIX5-2026-11-06: REAL inputs read as exact doubles (the platforms print doubles differently)
        val st = c.prepare(sql); try { val out = ArrayList<List<String?>>(); while (st.step()) out.add((0 until n).map { when (st.getColumnType(it)) { androidx.sqlite.SQLITE_DATA_NULL -> null; androidx.sqlite.SQLITE_DATA_FLOAT -> st.getDouble(it).toString(); else -> st.getText(it) } }); return out } finally { st.close() }
    }

    /**
     * Run the script on one side. [spatial] / [data] are connections on THAT side's copy (used to enumerate and to dump -- never to write).
     * [fileExists] tells whether my_tracks holds a GPX file for a hash (the deleteTrack guard).
     */
    fun run(api: Api, spatial: SQLiteConnection, data: SQLiteConnection, fileExists: (String) -> Boolean, emit: (String) -> Unit, progress: (String) -> Unit) {
        val idCols = listOf("trails" to "trail_id", "tracks" to "track_id", "waypoints" to "waypoint_id", "routes" to "route_id")
        val original = HashSet<String>()
        for ((t, c) in idCols) original.addAll(GtCert.ids(spatial, "SELECT $c FROM $t"))
        original.addAll(GtCert.ids(data, "SELECT alias_id FROM artifact_aliases")); original.addAll(GtCert.ids(data, "SELECT note_id FROM route_notes"))
        val ids = Ids(original)
        fun res(op: String, i: Int, v: String) = emit(GtCert.line("WRESULT", op, i.toString(), 0, v.replace("|", "/").replace("\n", " ")))   // the value itself, readable
        val wps = rows(spatial, "SELECT waypoint_id, name, geometry FROM waypoints ORDER BY waypoint_id", 3)
        val routes = rows(spatial, "SELECT route_id, name, geom_hash FROM routes ORDER BY route_id", 3)
        val tracks = rows(spatial, "SELECT track_id, name, geometry, min_lat, max_lat, min_lon, max_lon, geom_hash FROM tracks ORDER BY track_id", 8)
        val trailIds = GtCert.sampleTrailIds(spatial).take(60)
        val trails = trailIds.mapNotNull { id -> rows(spatial, "SELECT trail_id, name, geometry, min_lat, max_lat, min_lon, max_lon FROM trails WHERE trail_id='" + id.replace("'", "''") + "'", 7).firstOrNull() }
        val aliases = rows(data, "SELECT alias_id, artifact_type, artifact_id, alias FROM artifact_aliases ORDER BY alias_id", 4)
        val notePayload = rows(data, "SELECT payload FROM route_notes WHERE kind='narrative' ORDER BY route_id LIMIT 1", 1).firstOrNull()?.get(0)
        fun pt(g: String?): Pair<Double, Double>? { val p = (g ?: return null).removePrefix("POINT(").removeSuffix(")").trim().split(" "); val lo = p.getOrNull(0)?.toDoubleOrNull(); val la = p.getOrNull(1)?.toDoubleOrNull(); return if (la != null && lo != null) la to lo else null }
        fun d(s: String?) = s?.toDoubleOrNull() ?: 0.0
        val types = listOf("trailhead", "scenic", "viewpoint", "other", "Gas")

        // 1. waypoints
        for (i in 0 until minOf(50, wps.size)) { val p = pt(wps[i][2]) ?: continue
            res("insertWaypoint", i, api.insertWaypointWithId("certwp-" + i.toString().padStart(3, '0'), "CERT wp $i", p.first + 0.001, p.second + 0.001, types[i % types.size]).toString()) }
        res("insertWaypointAgain", 0, api.insertWaypointWithId("certwp-000", "CERT wp again", 1.0, 2.0, "other").toString())
        for (i in 0 until minOf(20, wps.size)) api.renameWaypoint(wps[i][0]!!, (wps[i][1] ?: "") + " r")
        for (i in 20 until minOf(40, wps.size)) api.changeWaypointType(wps[i][0]!!, types[i % types.size])
        for (i in 40 until minOf(50, wps.size)) api.deleteWaypoint(wps[i][0]!!)
        progress("writes: waypoints")
        // 2. routes
        val newRoutes = ArrayList<String>()
        for (i in 0 until minOf(10, tracks.size)) { val t = tracks[i]
            val id = api.insertRoute("CERT route $i", t[2] ?: continue, d(t[3]), d(t[4]), d(t[5]), d(t[6])); newRoutes.add(id); res("insertRoute", i, ids.n(id)) }
        for (i in 0 until minOf(5, routes.size)) api.renameRoute(routes[i][0]!!, (routes[i][1] ?: "") + " r")
        if (notePayload != null) for (i in 0 until minOf(5, newRoutes.size)) res("writeRouteNotes", i, api.writeRouteNotes(newRoutes[i], notePayload).toString())
        for (i in 0 until newRoutes.size) { val p = pt(wps.getOrNull(i)?.get(2)) ?: (37.0 to -113.0); res("setRouteAnchor", i, api.setRouteAnchor(newRoutes[i], p.first, p.second, "CERT trailhead $i").toString()) }
        if (newRoutes.size > 4) res("deleteRouteNotes", 4, api.deleteRouteNotes(newRoutes[4]).toString())
        if (newRoutes.size > 9) api.deleteRoute(newRoutes[9])
        if (routes.isNotEmpty()) api.deleteRoute(routes.last()[0]!!)
        progress("writes: routes")
        // 3. aliases
        for (i in 0 until 20) {
            val (type, id, h) = when (i % 3) { 0 -> Triple("route", routes.getOrNull(i % maxOf(1, routes.size))?.get(0), routes.getOrNull(i % maxOf(1, routes.size))?.get(2))
                1 -> Triple("track", tracks.getOrNull(i)?.get(0), tracks.getOrNull(i)?.get(7)); else -> Triple("waypoint", wps.getOrNull(60 + i)?.get(0), "") }
            if (id != null) api.addAlias(type, id, "CERT alias $i", h ?: "")
        }
        for (i in 0 until minOf(10, aliases.size)) api.renameAlias(aliases[i][0]!!, (aliases[i][3] ?: "") + " r")
        aliases.filter { it[1] == "route" || it[1] == "track" }.take(5).forEach { api.setPreferredAlias(it[1]!!, it[2]!!, it[0]!!) }
        aliases.firstOrNull { it[1] == "trail" }?.let { api.setPreferredAlias("trail", it[2]!!, it[0]!!) }
        for (i in 10 until minOf(15, aliases.size)) api.deleteAlias(aliases[i][0]!!)
        progress("writes: aliases")
        // 4. tracks (database part)
        val newTracks = ArrayList<String>()
        for (i in 0 until minOf(10, trails.size)) { val t = trails[i]
            val r = api.insertTrack("CERT track $i", t[2]!!, d(t[3]), d(t[4]), d(t[5]), d(t[6])); val p = r.split(",")
            newTracks.add(p[1]); res("insertTrack", i, p[0] + "," + ids.n(p[1]) + "," + p.drop(2).joinToString(",")) }
        for (i in 0 until minOf(5, tracks.size)) { val t = tracks[i]
            val r = api.insertTrack("dup $i", t[2]!!, d(t[3]), d(t[4]), d(t[5]), d(t[6])); val p = r.split(","); res("insertTrackDup", i, p[0] + "," + ids.n(p[1]) + "," + p.drop(2).joinToString(",")) }
        for (i in 0 until minOf(10, tracks.size)) api.renameTrack(tracks[i][0]!!, (tracks[i][1] ?: "") + " r")
        for (i in 0 until minOf(3, newTracks.size)) {
            val h = rows(spatial, "SELECT geom_hash FROM tracks WHERE track_id='" + newTracks[i].replace("'", "''") + "'", 1).firstOrNull()?.get(0)
            if (h != null && fileExists(h)) { res("deleteTrack", i, "SKIPPED file exists"); continue }   // never remove a real GPX file
            api.deleteTrack(newTracks[i]); res("deleteTrack", i, "done")
        }
        progress("writes: tracks")
        // 5. trails through the import duplicate check (in a session, then without one)
        api.beginDedup()
        for (i in 0 until minOf(30, trails.size)) { val t = trails[i]
            val wkt = if (i < 10) t[2]!!.removeSuffix(")") + ",-1.0 1.0)" else t[2]!!
            val name = when { i < 10 -> "CERT trail $i"; i < 20 -> t[1]; else -> "CERT other name $i" }
            val r = api.insertTrail("cert-trail-" + i.toString().padStart(3, '0'), name, wkt, d(t[3]), d(t[4]), d(t[5]), d(t[6]), "2026-11-06T00:00:00Z")
            res("insertTrail", i, r) }
        res("sourceUidSeen", 0, api.sourceUidSeen("cert", "u1").toString()); api.markSourceUid("cert", "u1"); res("sourceUidSeen", 1, api.sourceUidSeen("cert", "u1").toString())
        res("clearWhileImporting", 0, api.clearAllTrails())
        api.endDedup()
        for (i in 30 until minOf(35, trails.size)) { val t = trails[i]; res("insertTrailNoSession", i, api.insertTrail("cert-trail-" + i.toString().padStart(3, '0'), t[1], t[2]!!, d(t[3]), d(t[4]), d(t[5]), d(t[6]), "2026-11-06T00:00:00Z")) }
        for (i in 40 until minOf(50, trails.size)) api.deleteTrail(trails[i][0]!!)
        progress("writes: trails")
        // 6. every table, then clear-all and the trail tables again
        dump(spatial, data, ids, emit, "a")
        res("clearAllTrails", 0, api.clearAllTrails())
        dump(spatial, data, ids, emit, "b", onlyTrails = true)
        progress("writes: done")
    }

    private fun dump(spatial: SQLiteConnection, data: SQLiteConnection, ids: Ids, emit: (String) -> Unit, tag: String, onlyTrails: Boolean = false) {
        val idNames = setOf("trail_id", "track_id", "waypoint_id", "route_id", "alias_id", "note_id", "artifact_id", "source_track_id", "trailhead_id", "trailhead_waypoint_id")
        for ((db, c) in listOf("spatial" to spatial, "data" to data)) {
            val tables = GtCert.ids(c, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name")
                .filter { !onlyTrails || it == "trails" || it == "trail_properties" || it == "artifact_aliases" }
            for (t in tables) {
                val st = c.prepare("SELECT * FROM \"$t\" ORDER BY rowid")
                try {
                    val n = st.getColumnCount(); val names = (0 until n).map { st.getColumnName(it) }
                    // GTCERTW-FIX2-2026-11-06: cells go straight into the hash (no chunk text in memory -- Droid 1 ran out at 256 MB)
                    var chunk = 0; var rowsIn = 0
                    var hs = okio.HashingSink.sha256(okio.blackholeSink()); var sb = hs.buffer()
                    fun flush() { sb.flush(); emit(GtCert.line("WTABLE", "$db.$t", "$tag${chunk.toString().padStart(4, '0')}", rowsIn, hs.hash.hex().substring(0, 16)))
                        chunk++; rowsIn = 0; sb.close(); hs = okio.HashingSink.sha256(okio.blackholeSink()); sb = hs.buffer() }
                    while (st.step()) {
                        for (i in 0 until n) {
                            // GTCERTW-FIX3-2026-11-06: a REAL as its exact bits -- the two platforms' SQLite print doubles differently
                            val v = when (st.getColumnType(i)) { androidx.sqlite.SQLITE_DATA_NULL -> null; androidx.sqlite.SQLITE_DATA_FLOAT -> "R" + st.getDouble(i).toRawBits().toString(16); else -> st.getText(i) }
                            // GTCERTW-FIX1-2026-11-06: ids -> NEW#n, clock times (*_at, creation_date) -> T: they differ by nature
                            sb.writeUtf8(when { names[i] in idNames -> ids.n(v); names[i].endsWith("_at") || names[i] == "creation_date" || names[i] == "recorded_at" -> if (v == null) "∅" else "T"; else -> v ?: "∅" }).writeUtf8("\u0001")
                        }
                        sb.writeUtf8("\u0002"); rowsIn++
                        if (rowsIn == 5_000) flush()   // 5,000 rows a chunk: trail geometry is large (Android memory)
                    }
                    if (rowsIn > 0 || chunk == 0) flush()
                    sb.close()
                } finally { st.close() }
            }
        }
    }
}
