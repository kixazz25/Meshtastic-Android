package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.spatial.GtSpatial
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.cos

/**
 * STEP9BC-2026-11-07: harness checks 9(b) and 9(c) -- PROPERTIES of the shared map code (not OLD vs NEW):
 *  (b) every map batch is valid JSON -- trails (restricted parts shown and hidden), tracks, waypoints, routes, land -- for a view
 *      centred on each sampled trail at z12 and z14;
 *  (c) every sampled trail is FOUND in a view centred on it at z12 and z14 -- each trail that would not be drawn there is named, with why.
 * The view is a 1280 x 800 tablet screen. No trail filter: a trail the rider's filter hides is not a fault.
 * Sample: about [N] of GtCert.sampleTrailIds (evenly spaced) + the 200 SMALLEST trails (the ones most likely crowded out by the
 * LIMIT, which keeps the largest). Lines: MAP|... summaries, MISS|... and BADJSON|... findings. TEST-ONLY (CODE RULE 3).
 */
object CertMap {
    const val N = 1_500
    private const val W_PX = 1280.0
    private const val H_PX = 800.0
    private class T(val id: String, val name: String, val lat: Double, val lon: Double)

    fun run(conn: SQLiteConnection, dconn: SQLiteConnection?, emit: (String) -> Unit, progress: (String) -> Unit) {
        val sp = GtSpatial(conn, dconn)   // never sp.close(): the connections are the caller's
        val sample = GtCert.sampleTrailIds(conn)
        val step = maxOf(1, sample.size / N)
        val ids = LinkedHashSet<String>()
        for (i in sample.indices step step) ids.add(sample[i])
        ids.addAll(GtCert.ids(conn, "SELECT trail_id FROM trails WHERE geometry IS NOT NULL ORDER BY " +
            "((max_lat-min_lat)*(max_lat-min_lat)+(max_lon-min_lon)*(max_lon-min_lon)) ASC, trail_id LIMIT 200"))
        val trails = ArrayList<T>()
        for (chunk in ids.toList().chunked(GtCert.PAGE)) {
            val st = conn.prepare("SELECT trail_id, name, min_lat, max_lat, min_lon, max_lon FROM trails WHERE trail_id IN (" +
                chunk.joinToString(",") { "?" } + ")")
            try {
                chunk.forEachIndexed { i, v -> st.bindText(i + 1, v) }
                while (st.step()) {
                    if (st.isNull(0) || st.isNull(2) || st.isNull(3) || st.isNull(4) || st.isNull(5)) continue
                    trails.add(T(st.getText(0), if (st.isNull(1)) "" else st.getText(1),
                        (st.getDouble(2) + st.getDouble(3)) / 2, (st.getDouble(4) + st.getDouble(5)) / 2))
                }
            } finally { st.close() }
        }
        progress("map checks: ${trails.size} trails, views at z12 and z14")
        for (z in listOf(12, 14)) {
            var found = 0; var missed = 0; var batches = 0; var bad = 0; var crowded = 0
            val degPerPx = 360.0 / (256.0 * (1 shl z))
            trails.forEachIndexed { k, t ->
                if (k % 250 == 0) progress("z$z: $k of ${trails.size} (missed $missed, bad JSON $bad)")
                val dLon = W_PX / 2 * degPerPx
                val dLat = H_PX / 2 * degPerPx * cos(t.lat * PI / 180.0)
                val s = t.lat - dLat; val n = t.lat + dLat; val w = t.lon - dLon; val e = t.lon + dLon
                val rows = sp.queryTrails(s, w, n, e, GtSpatial.TRAILS_LIMIT, "")
                if (rows.any { it["trail_id"] == t.id }) found++
                else {
                    missed++
                    val why = if (rows.size >= GtSpatial.TRAILS_LIMIT) { crowded++; "crowded out: the view already holds ${rows.size} larger trails" }
                        else "not returned: no drawable geometry"
                    emit("MISS|z$z|${t.id}|${clean(t.name)}|$why")
                }
                val lim = GtSpatial.otherLimit(z)
                val kinds = listOf(
                    "trails" to GtSpatial.trailGeoJson(rows, true),
                    "trails-open" to GtSpatial.trailGeoJson(rows, false),
                    "tracks" to GtSpatial.trackGeoJson(sp.queryTracks(s, w, n, e, GtSpatial.TRACKS_LIMIT)),
                    "waypoints" to GtSpatial.waypointGeoJson(sp.queryWaypoints(s, w, n, e, lim)),
                    "routes" to GtSpatial.routeGeoJson(sp.queryRoutes(s, w, n, e, lim)),
                    "land" to sp.landGeoJson(s, w, n, e, true, true, GtSpatial.LAND_LIMIT).first)
                for ((kind, json) in kinds) {
                    batches++
                    try { Json.parseToJsonElement(json) }
                    catch (ex: Exception) { bad++; emit("BADJSON|z$z|${t.id}|$kind|${clean(ex.message ?: "?").take(160)}") }
                }
            }
            emit("MAP|FIND|z$z|${trails.size}|found=$found|missed=$missed|crowded=$crowded")
            emit("MAP|JSON|z$z|batches=$batches|bad=$bad")
            progress("z$z done: found $found of ${trails.size}, missed $missed ($crowded crowded out); $batches batches, $bad bad JSON")
        }
    }

    private fun clean(v: String) = v.replace('|', '/').replace('\n', ' ')
}
