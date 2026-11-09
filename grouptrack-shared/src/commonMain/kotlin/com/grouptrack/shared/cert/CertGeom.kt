package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.spatial.GtGeom
import com.grouptrack.shared.spatial.GtRead

/**
 * GEOMONE-2026-11-09 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): certifies THE ONE geometry module
 * (GtGeom) against the data 2.7e already stored. Same code on the Droid (GtCertRun mode "geom", on the FROZEN copy) and on the iPad
 * simulator (GtCertGeomMacTest, on the Mac's copy of Droid 1's databases). The reference is the stored data itself, so each side checks
 * itself against what Android wrote; no file passes between them.
 *
 * For EVERY row of waypoints, routes, tracks and trails:
 *   1. GtGeom.hash(stored text) == stored geom_hash                      (the one hash = what 2.7e stored)
 *   2. GtGeom.parse(stored text).canonical().wkt == stored text           (the one builder rebuilds 2.7e's text from the points)
 *      A row whose text differs ONLY in the spacing after a comma is NONCANONICAL (named, not a failure: 2.7e stored it that way;
 *      fixing it is a data change for Fred to decide). Any other difference is a DIFF -- a number written differently = a platform gap.
 *   3. tracks only: GtRead.trackIdForGpxText(a GPX made from the track's own number text) == the track   (the end-ride lookup, step 7)
 * Lines (tab-separated): GEOM <table> <id> NONCANONICAL|DIFF <detail>   (SAME rows are counted, not written: 230k trails)
 * Footer: "# GEOM done: R rows, S SAME, N NONCANONICAL, D DIFF; end-ride lookup L/T"
 */
object CertGeom {

    class Summary(val rows: Int, val same: Int, val noncanonical: Int, val diff: Int, val lookupOk: Int, val tracks: Int)

    private val TABLES = listOf("waypoints" to "waypoint_id", "routes" to "route_id", "tracks" to "track_id", "trails" to "trail_id")

    fun run(conn: SQLiteConnection, out: (String) -> Unit, progress: (String) -> Unit): Summary {
        var rows = 0; var same = 0; var nonc = 0; var diff = 0; var lookOk = 0; var tracks = 0
        val read = GtRead(conn, null)   // CODE RULE 1: the data db is not needed for these reads (geometry and hashes live in spatial)
        for ((table, idCol) in TABLES) {
            var tRows = 0; var tSame = 0; var tNonc = 0; var tDiff = 0
            val st = conn.prepare("SELECT $idCol, geometry, geom_hash FROM $table")
            try {
                while (st.step()) {
                    rows++; tRows++
                    val id = if (st.isNull(0)) "(null id)" else st.getText(0)
                    val g = if (st.isNull(1)) null else st.getText(1)
                    val h = if (st.isNull(2)) null else st.getText(2)
                    fun bad(why: String) { diff++; tDiff++; out("GEOM\t$table\t$id\tDIFF\t$why") }
                    if (g == null) { bad("no geometry"); continue }
                    if (GtGeom.hash(g) != h) { bad("stored hash is not the hash of the stored text: stored=$h"); continue }
                    val p = GtGeom.parse(g)
                    if (p == null) { bad("unreadable geometry: " + g.take(80)); continue }
                    val c = p.canonical()
                    when {
                        c.wkt == g -> { same++; tSame++ }
                        c.wkt == g.replace(Regex(",\\s+"), ",") -> { nonc++; tNonc++
                            out("GEOM\t$table\t$id\tNONCANONICAL\tstored with a space after the comma; the same ground in THE FORMAT hashes ${c.hash}") }
                        else -> { val k = firstDiff(c.wkt, g)
                            bad("rebuilt text differs at char $k: stored '" + g.substring(maxOf(0, k - 20), minOf(g.length, k + 30)) +
                                "' rebuilt '" + c.wkt.substring(maxOf(0, k - 20), minOf(c.wkt.length, k + 30)) + "'") }
                    }
                    if (table == "tracks" && p.kind == "LINESTRING") {
                        tracks++
                        val gpx = StringBuilder("<gpx><trk><trkseg>")
                        val inner = g.substring(g.indexOf('(') + 1, g.length - 1)
                        if (inner.isNotBlank()) for (pair in inner.split(',')) {
                            val xy = pair.trim().split(' ').filter { it.isNotEmpty() }
                            gpx.append("<trkpt lat=\"").append(xy[1]).append("\" lon=\"").append(xy[0]).append("\"></trkpt>")
                        }
                        gpx.append("</trkseg></trk></gpx>")
                        val found = read.trackIdForGpxText(gpx.toString())
                        if (found == id) lookOk++ else bad("end-ride lookup found ${found ?: "nothing"}")
                    }
                }
            } finally { st.close() }
            out("# geom $table: $tRows rows, $tSame SAME, $tNonc NONCANONICAL, $tDiff DIFF")
            progress("$table: $tRows rows, $tDiff DIFF")
        }
        out("# GEOM done: $rows rows, $same SAME, $nonc NONCANONICAL, $diff DIFF; end-ride lookup $lookOk/$tracks")
        return Summary(rows, same, nonc, diff, lookOk, tracks)
    }

    private fun firstDiff(a: String, b: String): Int { var i = 0; while (i < a.length && i < b.length && a[i] == b[i]) i++; return i }
}
