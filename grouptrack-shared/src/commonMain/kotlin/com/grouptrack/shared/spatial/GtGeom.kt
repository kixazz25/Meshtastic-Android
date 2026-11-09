package com.grouptrack.shared.spatial

import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtJavaParse
import okio.ByteString.Companion.encodeUtf8

/**
 * GEOMONE-2026-11-09 (Fred 07:49-07:54): THE ONE geometry module. Every place that stores a waypoint, route, track or trail gives it
 * the POINTS and gets back the stored text (WKT) and its identity hash (geom_hash). Nothing else builds geometry text or hashes it.
 *
 * Why one module: geom_hash is SHA-256 of the WKT TEXT, so "the same ground" is only the same hash if every caller writes the numbers
 * the same way. On 11-09 twelve places wrote the text themselves (list: notes_2026-11-09/geom_hash_where_used_2026-11-09.md). If one of
 * them ever changes, dedup and sync silently see two trails where there is one.
 *
 * THE FORMAT (exactly 2.7e's, so no stored hash changes -- checked 11-09 on all 230,009 rows of Droid 1's live copy):
 *   POINT(lon lat)                 LINESTRING(lon lat,lon lat)            MULTILINESTRING((lon lat,lon lat),(lon lat,lon lat))
 *   longitude FIRST; comma, NO space; each number as Java's Double.toString writes it (GtJava.jd: Android = Java itself, iPad = the 15a
 *   port) -- never Kotlin's own toString, which on the iPad writes some numbers differently.
 * The hash: SHA-256 of the text's UTF-8 bytes, lower-case hex (2.7e's MessageDigest).
 *
 * The order is in the NAME (...LonLat) and every Pair is (lon, lat), so the lat/lon swap of the old SpatialDbManager.lineWkt cannot recur.
 */
object GtGeom {

    /** The stored text and its hash, always made together. */
    class Geom(val wkt: String, val hash: String)

    /** THE hash. The only copy. */
    fun hash(wkt: String): String = wkt.encodeUtf8().sha256().hex()

    fun pointLonLat(lon: Double, lat: Double): Geom = of("POINT(" + GtJava.jd(lon) + " " + GtJava.jd(lat) + ")")

    /** [lonLat]: (lon, lat) pairs in order. An empty list gives "LINESTRING()" exactly as 2.7e's joinToString did. */
    fun lineLonLat(lonLat: List<Pair<Double, Double>>): Geom = of("LINESTRING(" + coords(lonLat) + ")")

    /** One piece per inner list; a multi-piece line is never joined (the 11-08 import rules). */
    fun multiLineLonLat(parts: List<List<Pair<Double, Double>>>): Geom {
        val sb = StringBuilder("MULTILINESTRING(")
        for ((i, p) in parts.withIndex()) { if (i > 0) sb.append(','); sb.append('(').append(coords(p)).append(')') }
        return of(sb.append(')').toString())
    }

    private fun of(wkt: String) = Geom(wkt, hash(wkt))

    private fun coords(lonLat: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        for ((i, p) in lonLat.withIndex()) { if (i > 0) sb.append(','); sb.append(GtJava.jd(p.first)).append(' ').append(GtJava.jd(p.second)) }
        return sb.toString()
    }

    /**
     * Stored text -> points, for callers that must rebuild or re-hash a stored row, and for the certification.
     * Accepts any spacing after a comma (3 routes on Droid 1 were stored "lon lat, lon lat" -- see [Parsed.canonical]).
     * Returns null for anything that is not POINT / LINESTRING / MULTILINESTRING with readable numbers.
     */
    class Parsed(val kind: String, val parts: List<List<Pair<Double, Double>>>) {
        /** The same ground written in THE FORMAT. Equal to the stored text for every row 2.7e's builders wrote. */
        fun canonical(): Geom = when (kind) {
            "POINT" -> pointLonLat(parts[0][0].first, parts[0][0].second)
            "LINESTRING" -> lineLonLat(parts[0])
            else -> multiLineLonLat(parts)
        }
    }

    fun parse(wkt: String): Parsed? {
        val open = wkt.indexOf('(')
        if (open <= 0 || !wkt.endsWith(")")) return null
        val kind = wkt.substring(0, open).trim()
        val inner = wkt.substring(open + 1, wkt.length - 1)
        fun pts(s: String): List<Pair<Double, Double>>? {
            if (s.isBlank()) return emptyList()
            val out = ArrayList<Pair<Double, Double>>()
            for (pair in s.split(',')) {
                val xy = pair.trim().split(' ').filter { it.isNotEmpty() }
                if (xy.size != 2) return null
                val x = GtJavaParse.toDoubleOrNull(xy[0]) ?: return null   // Java's reading on both platforms (15b)
                val y = GtJavaParse.toDoubleOrNull(xy[1]) ?: return null
                out.add(Pair(x, y))
            }
            return out
        }
        return when (kind) {
            "POINT" -> pts(inner)?.takeIf { it.size == 1 }?.let { Parsed(kind, listOf(it)) }
            "LINESTRING" -> pts(inner)?.let { Parsed(kind, listOf(it)) }
            "MULTILINESTRING" -> {
                val t = inner.trim()
                if (!t.startsWith("(") || !t.endsWith(")")) return null
                val parts = ArrayList<List<Pair<Double, Double>>>()
                for (piece in t.substring(1, t.length - 1).split(Regex("\\)\\s*,\\s*\\("))) parts.add(pts(piece) ?: return null)
                Parsed(kind, parts)
            }
            else -> null
        }
    }
}
