package com.geeksville.mesh.convoy

// ----------------------------------------------------------------
// RouteManager -- V2.5 Scaffold (Pass 1)
// Save, SET TH, ride enrollment guard
// Source: ScreenReference Decision Log section 11
// ----------------------------------------------------------------

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.PI

object RouteManager {

    // ---- Snap result --------------------------------------------------
    // lat/lon  : the snapped point (the point that lands ON the line)
    // lineId   : trail_id or track_id of the line snapped to
    // lineType : "trail" or "track"
    // segmentIndex + t : position ALONG the polyline. The snapped point
    //   lies on segment [segmentIndex -> segmentIndex+1], a fraction t in
    //   [0,1] from the start vertex. A later slice step uses these to cut
    //   the sub-path between two snapped points on the same line.
    data class SnapResult(
        val lat: Double,
        val lon: Double,
        val lineId: String,
        val lineType: String,
        val segmentIndex: Int,
        val t: Double
    )

    private const val EARTH_M = 6371000.0

    // ---- WKT parse (LON-FIRST; mirrors SpatialDbManager.wktToGeoJsonCoords) ----
    // Returns flattened vertex list as [lon, lat] pairs. MULTILINESTRING
    // parts are concatenated (snap only needs the vertex set + adjacency;
    // for slicing we treat a MULTI as one flattened path -- acceptable for
    // Method 1 where the user drops points on a single visible line).
    fun parseWktLine(wkt: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val inner: String
        if (wkt.startsWith("MULTILINESTRING(")) {
            inner = wkt.removePrefix("MULTILINESTRING(").removeSuffix(")")
            val parts = inner.split("),(").map { it.trim('(', ')') }
            for (p in parts) appendCoords(p, out)
        } else if (wkt.startsWith("LINESTRING(")) {
            inner = wkt.removePrefix("LINESTRING(").removeSuffix(")")
            appendCoords(inner, out)
        }
        return out
    }

    private fun appendCoords(seg: String, out: ArrayList<DoubleArray>) {
        for (pair in seg.split(",")) {
            val xy = pair.trim().split(" ")
            if (xy.size >= 2) {
                val lon = xy[0].toDoubleOrNull() ?: continue
                val lat = xy[1].toDoubleOrNull() ?: continue
                out.add(doubleArrayOf(lon, lat))
            }
        }
    }

    // ---- Ground distance ---------------------------------------------
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                sin(dLon / 2) * sin(dLon / 2)
        return EARTH_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ---- Nearest point on ONE segment --------------------------------
    // Equirectangular projection scaled by the tap latitude: at trail
    // scale (meters), this is accurate and cheap. Returns Triple(lat,
    // lon, t) where t in [0,1] is the position along the segment.
    private fun nearestOnSegment(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Triple<Double, Double, Double> {
        val latRad = pLat * PI / 180.0
        val kx = cos(latRad)            // lon degrees shrink with latitude
        // project to a local planar frame (degrees, lon scaled by kx)
        val ax = aLon * kx; val ay = aLat
        val bx = bLon * kx; val by = bLat
        val px = pLon * kx; val py = pLat
        val dx = bx - ax;   val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else
            (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val snapLat = ay + t * dy
        val snapLonScaled = ax + t * dx
        val snapLon = if (kx == 0.0) aLon else snapLonScaled / kx
        return Triple(snapLat, snapLon, t)
    }

    // ---- Nearest point across a set of lines -------------------------
    // lines: list of maps with idKey ("trail_id"/"track_id") + "geometry".
    // Returns best SnapResult within radiusM, or null if none qualify.
    fun nearestPointOnLines(
        tapLat: Double, tapLon: Double,
        lines: List<Map<String, String?>>,
        lineType: String, idKey: String,
        radiusM: Double
    ): SnapResult? {
        var best: SnapResult? = null
        var bestDist = radiusM
        for (line in lines) {
            val wkt = line["geometry"] ?: continue
            val id = line[idKey] ?: continue
            val verts = parseWktLine(wkt)
            if (verts.size < 2) continue
            for (i in 0 until verts.size - 1) {
                val a = verts[i]; val b = verts[i + 1]
                // a/b are [lon, lat]
                val (sLat, sLon, t) = nearestOnSegment(
                    tapLat, tapLon, a[1], a[0], b[1], b[0]
                )
                val d = haversineMeters(tapLat, tapLon, sLat, sLon)
                if (d <= bestDist) {
                    bestDist = d
                    best = SnapResult(sLat, sLon, id, lineType, i, t)
                }
            }
        }
        return best
    }

    // ---- Top-level snap: TRAIL-FIRST, strict track fallback ----------
    // Returns SnapResult? -- null = no line within radius (caller free-
    // places a straight segment). Trails win absolutely: if ANY trail is
    // within radius, tracks are never consulted, even a closer track.
    fun snap(
        tapLat: Double, tapLon: Double,
        trails: List<Map<String, String?>>,
        tracks: List<Map<String, String?>>,
        radiusM: Double
    ): SnapResult? {
        val onTrail = nearestPointOnLines(
            tapLat, tapLon, trails, "trail", "trail_id", radiusM
        )
        if (onTrail != null) return onTrail
        return nearestPointOnLines(
            tapLat, tapLon, tracks, "track", "track_id", radiusM
        )
    }

    // ---- ROUTE PLANNING snap: TRAILS ONLY (Fred 07-29/07-30, 2.6e) ---
    // "tracks can appear on map but snap2 should not be applied to tracks."
    // "only add points if trail is not available."
    //
    // Snapping a route to a TRACK was wrong twice over:
    //   1. COVERAGE -- track-snapping existed because UGRC's 49,125 trails
    //      left real ground unreferenced. At 89,527 OSM trails that gap is
    //      largely closed.
    //   2. lineId CORRECTNESS -- a track's lineId is PER-RIDER. Ten riders on
    //      one trail produce ten tracks with ten ids, so a route snapped to a
    //      track never denoted shared geometry. Wrong for the lead-cart
    //      contract from the start.
    //
    // Returns null when no trail is within radius; the caller then free-places
    // a plain point, exactly as it already does when snap() finds nothing.
    //
    // \u26d4 DO NOT "simplify" this back into snap() by passing an empty track
    // list. snap() still serves the two FROZEN ConvoyScreen.kt call sites
    // (:580, :795) and must keep its signature. A named function also keeps
    // the policy visible at the definition instead of hiding it in an empty
    // argument that a later reader will helpfully re-fill.
    fun snapTrailsOnly(
        tapLat: Double, tapLon: Double,
        trails: List<Map<String, String?>>,
        radiusM: Double
    ): SnapResult? = nearestPointOnLines(
        tapLat, tapLon, trails, "trail", "trail_id", radiusM
    )

    // ===================================================================
    // SLICE + BUILDER STATE  (patch v25 route slice/builder)
    // ===================================================================

    // A placed route vertex. snapped=false means free-placed (lineId null);
    // its segment to the previous vertex will be a straight line.
    data class Vertex(
        val lat: Double,
        val lon: Double,
        val lineId: String?,
        val lineType: String?,
        val segmentIndex: Int,
        val t: Double,
        val snapped: Boolean
    )

    fun snapToVertex(s: SnapResult): Vertex =
        Vertex(s.lat, s.lon, s.lineId, s.lineType, s.segmentIndex, s.t, true)

    fun freeVertex(lat: Double, lon: Double): Vertex =
        Vertex(lat, lon, null, null, -1, 0.0, false)

    // The in-progress route. Single builder instance (one active route at
    // a time); cleared on save/cancel.
    private val vertices = ArrayList<Vertex>()

    fun routeVertexCount(): Int = vertices.size
    fun routeVertices(): List<Vertex> = vertices.toList()

    fun addVertex(v: Vertex) { vertices.add(v) }
    fun undoVertex() { if (vertices.isNotEmpty()) vertices.removeAt(vertices.size - 1) }
    fun clearRoute() { vertices.clear() }

    // position along a line as a single comparable scalar
    private fun pos(v: Vertex): Double = v.segmentIndex + v.t

    // Sub-path of one line between A and B, direction-correct (A first,
    // B last). geom is the full [lon,lat] vertex list of that line.
    // Includes A, every line vertex strictly between the two positions,
    // then B. Handles A/B in either order along the line.
    private fun sliceLine(geom: List<DoubleArray>, a: Vertex, b: Vertex): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val pa = pos(a); val pb = pos(b)
        out.add(doubleArrayOf(a.lon, a.lat))
        if (pa <= pb) {
            // walk forward: vertices with index in (segIdxA, segIdxB]
            var i = a.segmentIndex + 1
            while (i <= b.segmentIndex && i < geom.size) {
                out.add(geom[i]); i++
            }
        } else {
            // walk backward
            var i = a.segmentIndex
            while (i > b.segmentIndex && i >= 0 && i < geom.size) {
                out.add(geom[i]); i--
            }
        }
        out.add(doubleArrayOf(b.lon, b.lat))
        return out
    }

    // Build the full ordered point list for the route. lineGeom resolves a
    // lineId to its full [lon,lat] vertex list (caller supplies from the
    // viewport line lists). Consecutive same-line pairs slice; everything
    // else straight-connects. Shared join points are dedup'd.
    fun buildSegments(lineGeom: (String) -> List<DoubleArray>?): List<DoubleArray> {
        val pts = ArrayList<DoubleArray>()
        if (vertices.size < 2) {
            if (vertices.size == 1) pts.add(doubleArrayOf(vertices[0].lon, vertices[0].lat))
            return pts
        }
        for (k in 0 until vertices.size - 1) {
            val a = vertices[k]; val b = vertices[k + 1]
            val sameLine = a.snapped && b.snapped &&
                    a.lineId != null && a.lineId == b.lineId
            val seg: List<DoubleArray> =
                if (sameLine) {
                    val geom = lineGeom(a.lineId!!)
                    if (geom != null && geom.size >= 2) sliceLine(geom, a, b)
                    else listOf(doubleArrayOf(a.lon, a.lat), doubleArrayOf(b.lon, b.lat))
                } else {
                    listOf(doubleArrayOf(a.lon, a.lat), doubleArrayOf(b.lon, b.lat))
                }
            for (p in seg) {
                // dedup the shared join point between consecutive segments
                val last = pts.lastOrNull()
                if (last == null || last[0] != p[0] || last[1] != p[1]) pts.add(p)
            }
        }
        return pts
    }

    // -> Pair(wkt, bbox[minLat,maxLat,minLon,maxLon]) or null if <2 verts.
    fun buildWktAndBbox(lineGeom: (String) -> List<DoubleArray>?): Pair<String, DoubleArray>? {
        val pts = buildSegments(lineGeom)
        if (pts.size < 2) return null
        val sb = StringBuilder("LINESTRING(")
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for ((idx, p) in pts.withIndex()) {
            val lon = p[0]; val lat = p[1]
            if (idx > 0) sb.append(",")
            sb.append(lon).append(" ").append(lat)
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }
        sb.append(")")
        return Pair(sb.toString(), doubleArrayOf(minLat, maxLat, minLon, maxLon))
    }

    @Suppress("unused")
    fun stub() { /* retained; replaced by Pass-2 functions above */ }
}
