package com.geeksville.mesh.convoy

/**
 * RIDERTRAILSCANNER-2026-09-07 -- rider trails from recorded tracks.
 *
 * A PORT of `ridertrails_2026-09-06_v3.py`, which was proven on Broken Ridge and
 * Steamboat (about 80 miles of new connected ground). The Python is the
 * reference; this is a transliteration of it, not a re-derivation. If the two
 * ever disagree the Python is right until proven otherwise.
 *
 * PURE ON PURPOSE. No database, no Android imports, no UI, no geometry
 * serialisation. Geometry in, trails out. That is what makes it diffable
 * against the Python run on the same data before a single row is written -- and
 * it is why the caller, not this object, owns the query, the insert and the
 * manifest stage.
 *
 * IT DOES NOT SERIALISE GEOMETRY, and must not learn how. The trail identity
 * hash is taken over the serialised geometry STRING, so a second serialiser
 * with different spacing or number formatting yields a different identity for
 * identical ground, and duplicate detection silently stops recognising its own
 * rows. Use the serialiser that already exists in the database layer.
 *
 * -- THE ALGORITHM, AND THE THREE WRONG TURNS BEHIND IT -----------------
 *
 * 1. DISTANCE ALONE GIVES NONSENSE. "Is a trail within 20 m?" produced 271
 *    fragments from ONE ride -- GPS drift stepping in and out of a threshold.
 *
 * 2. DIRECTION IS THE TEST. A trail nearby GOING THE SAME WAY is the trail you
 *    are on, however far the GPS wandered. Undirected: ridden the other way is
 *    the same trail. 271 became 69.
 *
 * 3. A RUN MUST NOT END THE FIRST TIME A TRAIL APPEARS. Passing near something,
 *    or crossing a road, was cutting one stretch of new ground into several. A
 *    run closes only after the track STAYS on the network for [REJOIN_M]. An
 *    out-and-back is ONE trail, because you rode all of it and it is
 *    continuous.
 *
 * -- THE 09-07 CHANGE: CONNECTORS ARE PART OF THE TRAIL ----------------
 *
 * v3 wrote connectors as separate short lines. Fred, 09-07: the connector is
 * "the start of the trail and connects to rest of trail points ... we are
 * adding one new trail including derived connector."
 *
 * So a rider trail is: anchor node -> the ground you rode -> anchor node, as
 * ONE polyline. This removes a real failure mode -- a run written whose
 * connector write failed was an island the router could not reach, which is
 * decorative. One row, joined at both ends, or nothing.
 *
 * The anchor points are the ONLY geometry here that was not ridden. Up to
 * [MAX_CONN_M]; beyond that the end is left unjoined, because a longer
 * connector is a guess, not a gap.
 */
object RiderTrailScanner {

    // -- THE TUNING. Tuned on Broken Ridge and Steamboat; expect to revisit.
    /** A trail this close, going your way, means you are on it. */
    const val NEAR_M = 20.0
    /** ...and within this many degrees is THE SAME trail. */
    const val BEARING_D = 45.0
    /** Shorter than this is a wobble, not a trail. */
    const val MIN_RUN_M = 150.0
    /** How far the track must STAY on the network before a run closes. */
    const val REJOIN_M = 120.0
    /** A longer connector is a guess, not a gap. */
    const val MAX_CONN_M = 60.0
    /** Points either side used to smooth a point's bearing. */
    const val SMOOTH = 7

    /**
     * A ~200 m grid over the candidate segments. Comparing every track point
     * against an unindexed network is the difference between seconds and hours.
     *
     * AND THE CALLER MUST PASS CANDIDATES FOR ONE TRACK, NOT THE WHOLE
     * DATABASE. The Python indexed every trail because it ran on a laptop. Utah
     * is 145,942 trails and about 5.6 million segments -- held as boxed doubles
     * that is hundreds of megabytes on a 3 GB tester device. Select by the
     * track's own bounding box and this stays small.
     */
    private const val CELL = 0.002

    /** lon, lat -- the order the stored geometry uses, kept so the port cannot
     *  transpose them silently. */
    data class Pt(val lon: Double, val lat: Double)

    /**
     * One rider trail. [points] already includes its anchors, so it is ready to
     * become a single geometry.
     *
     * [riddenM] is the ground actually ridden -- the run without its anchors --
     * and is what [MIN_RUN_M] was tested against. [totalM] includes the
     * connectors. Reporting the first is honest; the second is what the trail
     * measures on the map.
     */
    data class RiderTrail(
        val points: List<Pt>,
        val riddenM: Double,
        val totalM: Double,
        val joinedHead: Boolean,
        val joinedTail: Boolean
    ) {
        val joinedBothEnds: Boolean get() = joinedHead && joinedTail
    }

    private class Seg(
        val lo1: Double, val la1: Double,
        val lo2: Double, val la2: Double,
        val bearing: Double
    )

    // -- geometry --------------------------------------------------------

    /** Metres between two lat/lon points. */
    fun haversineM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(la1)
        val p2 = Math.toRadians(la2)
        val dp = Math.toRadians(la2 - la1)
        val dl = Math.toRadians(lo2 - lo1)
        val x = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2.0 * r * Math.asin(Math.sqrt(x))
    }

    /** Initial bearing, degrees, 0..360. */
    fun bearingDeg(lo1: Double, la1: Double, lo2: Double, la2: Double): Double {
        val p1 = Math.toRadians(la1)
        val p2 = Math.toRadians(la2)
        val dl = Math.toRadians(lo2 - lo1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * UNDIRECTED angular difference. A trail ridden the other way is the same
     * trail, so anything past 90 degrees is folded back.
     */
    fun angleDiff(a: Double, b: Double): Double {
        var d = Math.abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return if (d > 90.0) Math.min(d, 180.0 - d) else d
    }

    // -- the scan --------------------------------------------------------

    /**
     * @param track   the recorded track's points, in order.
     * @param network candidate trails near this track, each a list of points.
     *                Pass only what the track's bounding box selects -- see
     *                [CELL].
     */
    fun scan(track: List<Pt>, network: List<List<Pt>>): List<RiderTrail> {
        if (track.size < 2) return emptyList()

        // -- index the candidates -------------------------------------
        val grid = HashMap<Long, MutableList<Seg>>()
        for (line in network) {
            for (k in 0 until line.size - 1) {
                val a = line[k]
                val b = line[k + 1]
                if (a.lon == b.lon && a.lat == b.lat) continue
                val key = cellKey((a.lat + b.lat) / 2.0, (a.lon + b.lon) / 2.0)
                grid.getOrPut(key) { ArrayList() }
                    .add(Seg(a.lon, a.lat, b.lon, b.lat, bearingDeg(a.lon, a.lat, b.lon, b.lat)))
            }
        }

        // -- a smoothed bearing per track point ------------------------
        // null where the window collapses (a one-point track, or a zero-length
        // window). Justified nullable: "no bearing available" is a real state
        // and anchorAt treats it as "match on distance alone", which is what
        // the Python does.
        val brs = arrayOfNulls<Double>(track.size)
        for (i in track.indices) {
            val a = Math.max(0, i - SMOOTH)
            val b = Math.min(track.size - 1, i + SMOOTH)
            brs[i] = if (a != b)
                bearingDeg(track[a].lon, track[a].lat, track[b].lon, track[b].lat)
            else null
        }

        // -- is each point on the network, and against which node ------
        // null MEANS OFF-NETWORK. That is the whole signal this scan runs on,
        // not a shortcut.
        val anchor = arrayOfNulls<Pt>(track.size)
        for (i in track.indices) {
            anchor[i] = anchorAt(grid, track[i].lon, track[i].lat, brs[i])
        }

        val out = ArrayList<RiderTrail>()
        var i = 0
        while (i < track.size) {
            if (anchor[i] != null) { i++; continue }
            val start = i

            // RUN UNTIL A SUSTAINED REJOIN. Passing near a trail is not
            // rejoining it.
            var j = i
            while (j < track.size) {
                if (anchor[j] == null) { j++; continue }
                var k = j
                var runM = 0.0
                while (k + 1 < track.size && anchor[k + 1] != null) {
                    runM += haversineM(track[k].lat, track[k].lon,
                                       track[k + 1].lat, track[k + 1].lon)
                    if (runM >= REJOIN_M) break
                    k++
                }
                if (runM >= REJOIN_M || k + 1 >= track.size) break  // genuinely back
                j = k + 1                                           // a brush past
            }
            val end = j

            if (end - start >= 2) {
                val run = track.subList(start, end)
                var riddenM = 0.0
                for (t in 0 until run.size - 1) {
                    riddenM += haversineM(run[t].lat, run[t].lon,
                                          run[t + 1].lat, run[t + 1].lon)
                }
                if (riddenM >= MIN_RUN_M) {
                    val head = if (start > 0) anchor[start - 1] else null
                    val tail = if (end < track.size) anchor[end] else null

                    val pts = ArrayList<Pt>(run.size + 2)
                    var joinedHead = false
                    if (head != null) {
                        val d = haversineM(head.lat, head.lon, run[0].lat, run[0].lon)
                        if (d <= MAX_CONN_M) {
                            joinedHead = true
                            // Under half a metre the anchor IS the first point;
                            // prepending it would duplicate a vertex.
                            if (d > 0.5) pts.add(head)
                        }
                    }
                    pts.addAll(run)
                    var joinedTail = false
                    if (tail != null) {
                        val last = run[run.size - 1]
                        val d = haversineM(last.lat, last.lon, tail.lat, tail.lon)
                        if (d <= MAX_CONN_M) {
                            joinedTail = true
                            if (d > 0.5) pts.add(tail)
                        }
                    }

                    var totalM = 0.0
                    for (t in 0 until pts.size - 1) {
                        totalM += haversineM(pts[t].lat, pts[t].lon,
                                             pts[t + 1].lat, pts[t + 1].lon)
                    }
                    out.add(RiderTrail(pts, riddenM, totalM, joinedHead, joinedTail))
                }
            }
            i = end + 1
        }
        return out
    }

    /**
     * TRUNCATION, NOT FLOOR, and deliberately so: Python's int() truncates
     * toward zero and this must cell identically or the two implementations
     * bucket segments differently at negative longitudes -- which is all of
     * them, out west.
     */
    private fun cellKey(lat: Double, lon: Double): Long {
        val r = (lat / CELL).toInt()
        val c = (lon / CELL).toInt()
        return (r.toLong() shl 32) xor (c.toLong() and 0xFFFFFFFFL)
    }

    /**
     * The nearest network node within [NEAR_M] whose segment runs the same way,
     * or null if this point is off the network.
     */
    private fun anchorAt(
        grid: Map<Long, MutableList<Seg>>,
        lon: Double, lat: Double, br: Double?
    ): Pt? {
        val r = (lat / CELL).toInt()
        val c = (lon / CELL).toInt()
        var best: Pt? = null
        var bestD = NEAR_M
        for (dr in -1..1) {
            for (dc in -1..1) {
                val bucket = grid[((r + dr).toLong() shl 32) xor
                                  ((c + dc).toLong() and 0xFFFFFFFFL)] ?: continue
                for (s in bucket) {
                    if (br != null && angleDiff(br, s.bearing) > BEARING_D) continue
                    val d1 = haversineM(lat, lon, s.la1, s.lo1)
                    val d2 = haversineM(lat, lon, s.la2, s.lo2)
                    val d = Math.min(d1, d2)
                    if (d < bestD) {
                        bestD = d
                        best = if (d1 <= d2) Pt(s.lo1, s.la1) else Pt(s.lo2, s.la2)
                    }
                }
            }
        }
        return best
    }
}
