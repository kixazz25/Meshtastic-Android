package com.grouptrack.shared.rider

import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtMath.asin   // GTMATH-2026-11-10: Android's math on every platform
import com.grouptrack.shared.text.GtMath.cos
import com.grouptrack.shared.text.GtMath.hypot
import kotlin.math.max
import kotlin.math.min
import com.grouptrack.shared.text.GtMath.sin
import kotlin.math.sqrt

/**
 * STEP15C-2026-11-09 (2.8 step 15c): 2.7e's RiderTrailScanner (app/.../convoy/RiderTrailScanner.kt), MOVED AS IT IS into shared code.
 * Same algorithm, same constants, same order of every loop, same arithmetic in the same order. What changed, and only this:
 *  - java.lang.Math.toRadians -> GtJava.toRadians (Android's own constant, certified 15a / step-7 fix);
 *  - Math.sin / cos / asin / hypot -> GtMath (GTMATH-2026-11-10); sqrt / min / max -> kotlin.math (on Android these ARE java.lang.Math -- identical by construction;
 *    on the iPad they are the platform's libm, which may differ from Android's in the last bit -- INVENTORY Q7, the cert shows it);
 *  - NOT PORTED, dead in 2.7e (callers checked 11-09 with grep over app/ and grouptrack-shared/, none): bearingDeg, angleDiff (the
 *    direction test RIDERRULES-2026-10-09 removed), cellKey (private, never called -- [key] is used), RiderTrail.joinedBothEnds
 *    (never read). Their comments that still describe live code are kept below, marked "(from ...)".
 * The front-door switch (RiderTrailScanner -> this) is a later sub-step (NOTES_15c.md section 6). Until then 2.7e's own copy runs.
 *
 * ---- 2.7e's header, unchanged ----
 *
 * RIDERTRAILSCANNER-2026-09-07 -- rider trails from recorded tracks.
 *
 * RIDERLINE-2026-10-09 (Fred): THE POLYLINE, NOT THE POINTS. Proven 10-09 on Fred's onX rides (Broken Ridge 2024 both
 * ways, Caliente): the point-by-point test laid 39 pieces (21 unjoined, 13.6 mi of duplicate trail) where the truth
 * was 2 pieces of new ground; this version gives 2 pieces, 32.6 mi, all joined. Four changes, all here:
 *   1. the track is filled in to [DENSE_M] before matching (onX records 30-700 m apart -- jumps skipped junctions);
 *   2. "on a trail" = within [NEAR_M] of the trail's LINE (was: of a node), and the join snaps onto that line;
 *   3. direction = over [BEAR_WIN_M] of track either side (was [SMOOTH] points -- 200 m to over 1 km on onX);
 *   4. a trail segment is indexed in EVERY grid cell its box touches (was: its midpoint's cell only).
 * Only the recorded points (plus each piece's two ends) are written -- no 10 m filler in the database.
 * ⚠ CORRECTION 10-09 (afternoon): the "proven" figures above were TRACK AGAINST TRACK -- the harness used a second
 *   recording of Broken Ridge as the network, never the real trails. Against the real trails release g still left
 *   16 gaps (up to 135 m) -- see RIDERRULES below.
 *
 * RIDERRULES-2026-10-09 (Fred): THE AGREED PROCESS, NOTHING ELSE. Overlay the track's polyline on the trails; every
 * stretch where it leaves the trails and comes back to a trail is ONE piece, joined at both ends. The old process's
 * heuristics are GONE: no direction test (was BEARING_D / BEAR_WIN_M), no "sustained rejoin" (was REJOIN_M 120 m),
 * no minimum length for a connector (MIN_RUN_M 150 m threw away exactly the short connectors riders were missing).
 *   - ONE tolerance: [NEAR_M] -- the polyline within 20 m of a trail's line is ON that trail (GPS error).
 *   - Fred's rule for the tiny ones: a piece joined at both ends and shorter than [TINY_M] (10 m) is written ONLY if
 *     it is the only connection -- different trails at its two ends that do not already meet there. Same trail at
 *     both ends (the GPS wandered off and back) or two trails that already meet -> skipped.
 *   - [MIN_RUN_M] stays ONLY for a piece NOT joined at both ends (a dead end or open ground), so GPS wander out in
 *     the open is not laid down as trail.
 * MEASURED off-device, 10-09, against the REAL trails around Broken Ridge (Fred's PC copy spatial_0909.db: 251
 * trails; the 105 old rider stubs left out as the clear leaves them; written pieces fed back as network, as scanAll
 * does): release g left 16 gaps of 30 m+ (up to 135 m); this version leaves 0 on all three recordings, 58 pieces,
 * and wrote none under 10 m (all 7 were wobble). Uncovered remainder 0.03-0.08 mi per recording, in scraps < 30 m.
 * This now DIVERGES from ridertrails_2026-09-06_v3.py on purpose; this file is the reference from 10-09.
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
 *   (STEP15C: that serialiser is now GtGeom -- GtRiderTrails.buildGeometry calls GtGeom.lineLonLat.)
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
 *   (STEP15C note: 2 and 3 are history -- RIDERRULES-2026-10-09 above removed the direction test and REJOIN_M; kept as 2.7e has it.)
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
object GtRiderScan {

    // -- THE RULES (RIDERRULES-2026-10-09). One tolerance, Fred's tiny-connector rule, a floor for loose ends.
    /** THE tolerance: the polyline within this of a trail's line is ON that trail (GPS error). */
    const val NEAR_M = 20.0
    /** Fred 10-09: a piece joined at both ends and shorter than this is written only if it is the only connection. */
    const val TINY_M = 10.0
    /** ONLY for a piece NOT joined at both ends: shorter than this is GPS wander, not a trail. */
    const val MIN_RUN_M = 150.0
    /** A longer connector is a guess, not a gap. */
    const val MAX_CONN_M = 60.0
    /** RIDERLINE-2026-10-09: the track is filled in to this spacing before matching (onX records 30-700 m apart). */
    const val DENSE_M = 10.0

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
     * (STEP15C: 2.7e's `val joinedBothEnds get() = joinedHead && joinedTail` is not ported -- never read.)
     */
    data class RiderTrail(
        val points: List<Pt>,
        val riddenM: Double,
        val totalM: Double,
        val joinedHead: Boolean,
        val joinedTail: Boolean
    )

    private class Seg(
        val lo1: Double, val la1: Double,
        val lo2: Double, val la2: Double,
        /** RIDERRULES-2026-10-09: which candidate trail this segment belongs to (the tiny-connector rule). */
        val line: Int
    )

    // -- geometry --------------------------------------------------------

    /** Metres between two lat/lon points. */
    fun haversineM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371000.0
        val p1 = GtJava.toRadians(la1)
        val p2 = GtJava.toRadians(la2)
        val dp = GtJava.toRadians(la2 - la1)
        val dl = GtJava.toRadians(lo2 - lo1)
        val x = sin(dp / 2) * sin(dp / 2) +
                cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * r * asin(sqrt(x))
    }

    // (STEP15C: 2.7e's bearingDeg and angleDiff stood here -- dead since RIDERRULES-2026-10-09, not ported.)

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
        // RIDERLINE-2026-10-09: a segment goes into EVERY cell its box touches. Indexed by its midpoint only, a long
        // segment (sparse trail data: nodes hundreds of metres apart) was invisible to a track point near one end --
        // the whole ride then read as new ground and was laid down as duplicate trail.
        // (from 2.7e's cellKey, dead:) TRUNCATION, NOT FLOOR, and deliberately so: Python's int() truncates toward zero and this must
        // cell identically or the two implementations bucket segments differently at negative longitudes -- which is all of them,
        // out west. (STEP15C: Kotlin's Double.toInt() truncates toward zero, NaN -> 0, saturating, on every platform.)
        val grid = HashMap<Long, MutableList<Seg>>()
        for ((li, line) in network.withIndex()) {
            for (k in 0 until line.size - 1) {
                val a = line[k]
                val b = line[k + 1]
                if (a.lon == b.lon && a.lat == b.lat) continue
                val seg = Seg(a.lon, a.lat, b.lon, b.lat, li)
                val r1 = (min(a.lat, b.lat) / CELL).toInt()
                val r2 = (max(a.lat, b.lat) / CELL).toInt()
                val c1 = (min(a.lon, b.lon) / CELL).toInt()
                val c2 = (max(a.lon, b.lon) / CELL).toInt()
                for (r in r1..r2) for (c in c1..c2) grid.getOrPut(key(r, c)) { ArrayList() }.add(seg)
            }
        }

        // -- RIDERLINE-2026-10-09: the track's own POLYLINE, filled in to DENSE_M ------
        // onX records 30-700 m apart; testing only the recorded points let a jump skip a junction or a turn.
        // [orig] marks the recorded points -- only those (plus each piece's two ends) are written back.
        val dense = ArrayList<Pt>(track.size * 4)
        val orig = ArrayList<Boolean>(track.size * 4)
        dense.add(track[0]); orig.add(true)
        for (k in 0 until track.size - 1) {
            val a = track[k]
            val b = track[k + 1]
            val d = haversineM(a.lat, a.lon, b.lat, b.lon)
            val steps = (d / DENSE_M).toInt()
            for (t in 1..steps) {
                val f = t * DENSE_M / d
                if (f < 1.0) { dense.add(Pt(a.lon + (b.lon - a.lon) * f, a.lat + (b.lat - a.lat) * f)); orig.add(false) }
            }
            dense.add(b); orig.add(true)
        }
        val n = dense.size
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + haversineM(dense[k - 1].lat, dense[k - 1].lon, dense[k].lat, dense[k].lon)

        // -- is each point on the network, and WHERE on the trail's line ------
        // null MEANS OFF-NETWORK. That is the whole signal this scan runs on, not a shortcut.
        val anchor = arrayOfNulls<Pt>(n)
        val anchorLine = IntArray(n) { -1 }
        for (i in 0 until n) anchor[i] = anchorAt(grid, dense[i].lon, dense[i].lat, anchorLine, i)

        val out = ArrayList<RiderTrail>()
        var i = 0
        while (i < n) {
            if (anchor[i] != null) { i++; continue }
            val start = i

            // RIDERRULES-2026-10-09: the piece runs until the polyline is back ON a trail -- the first on-trail point.
            // (Was: until it STAYED on for REJOIN_M 120 m, which swallowed junctions and short connectors.)
            var j = i
            while (j < n && anchor[j] == null) j++
            val end = j

            if (end - start >= 2) {
                val riddenM = cum[end - 1] - cum[start]
                val hj = start > 0 && haversineM(anchor[start - 1]!!.lat, anchor[start - 1]!!.lon, dense[start].lat, dense[start].lon) <= MAX_CONN_M
                val tj = end < n && haversineM(dense[end - 1].lat, dense[end - 1].lon, anchor[end]!!.lat, anchor[end]!!.lon) <= MAX_CONN_M
                // RIDERRULES-2026-10-09: joined at both ends -> written from TINY_M up; under TINY_M only when it is the ONLY
                // connection (different trails at its ends that do not already meet there). Not joined at both ends -> MIN_RUN_M.
                val keep = if (hj && tj) {
                    riddenM >= TINY_M || (anchorLine[start - 1] != anchorLine[end] &&
                        !linesMeetNear(grid, anchorLine[start - 1], anchorLine[end], dense[start].lon, dense[start].lat))
                } else riddenM >= MIN_RUN_M
                if (keep) {
                    val head = if (start > 0) anchor[start - 1] else null
                    val tail = if (end < n) anchor[end] else null

                    // The piece = its first point, the RECORDED points inside it, its last point (no 10 m filler).
                    val run = ArrayList<Pt>()
                    run.add(dense[start])
                    for (q in start + 1 until end - 1) if (orig[q]) run.add(dense[q])
                    run.add(dense[end - 1])

                    val pts = ArrayList<Pt>(run.size + 2)
                    var joinedHead = false
                    if (head != null) {
                        val d = haversineM(head.lat, head.lon, run[0].lat, run[0].lon)
                        if (d <= MAX_CONN_M) {
                            joinedHead = true
                            // Under half a metre the anchor IS the first point; prepending it would duplicate a vertex.
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
                        totalM += haversineM(pts[t].lat, pts[t].lon, pts[t + 1].lat, pts[t + 1].lon)
                    }
                    out.add(RiderTrail(pts, riddenM, totalM, joinedHead, joinedTail))
                }
            }
            i = end + 1
        }
        return out
    }

    /** The grid key for cell (row, column) -- the same formula as [cellKey]. (STEP15C: cellKey itself was dead in 2.7e; not ported.) */
    private fun key(r: Int, c: Int): Long = (r.toLong() shl 32) xor (c.toLong() and 0xFFFFFFFFL)

    /**
     * RIDERLINE-2026-10-09: the nearest point ON a trail's LINE within [NEAR_M] whose segment runs the same way, or
     * null if this point is off the network. Was: the nearest NODE -- a point riding the middle of a long segment
     * read as off-network, and the anchor (the join) landed on a corner, not where the track meets the trail.
     * Local flat-earth metres around the point -- exact enough at 20 m.
     * (STEP15C: in 2.7e this comment sits above linesMeetNear, but it describes [anchorAt] -- kept here, where 2.7e has it.)
     */
    /** RIDERRULES-2026-10-09: do trails [a] and [b] already meet (come within [NEAR_M]) in the cells around this point? */
    private fun linesMeetNear(grid: Map<Long, MutableList<Seg>>, a: Int, b: Int, lon: Double, lat: Double): Boolean {
        val r = (lat / CELL).toInt()
        val c = (lon / CELL).toInt()
        val kx = 111320.0 * cos(GtJava.toRadians(lat))
        val ky = 111320.0
        val sa = ArrayList<Seg>()
        val sb = ArrayList<Seg>()
        for (dr in -1..1) for (dc in -1..1) for (s in grid[key(r + dr, c + dc)] ?: continue) {
            if (s.line == a) sa.add(s) else if (s.line == b) sb.add(s)
        }
        fun d(px: Double, py: Double, s: Seg): Double {
            val ax = (s.lo1 - px) * kx
            val ay = (s.la1 - py) * ky
            val dx = (s.lo2 - px) * kx - ax
            val dy = (s.la2 - py) * ky - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
            return hypot(ax + t * dx, ay + t * dy)
        }
        for (x in sa) for (y in sb) {
            if (d(x.lo1, x.la1, y) <= NEAR_M || d(x.lo2, x.la2, y) <= NEAR_M ||
                d(y.lo1, y.la1, x) <= NEAR_M || d(y.lo2, y.la2, x) <= NEAR_M) return true
        }
        return false
    }

    private fun anchorAt(
        grid: Map<Long, MutableList<Seg>>,
        lon: Double, lat: Double,
        outLine: IntArray, idx: Int
    ): Pt? {
        val r = (lat / CELL).toInt()
        val c = (lon / CELL).toInt()
        val kx = 111320.0 * cos(GtJava.toRadians(lat))
        val ky = 111320.0
        var best: Pt? = null
        var bestD = NEAR_M
        for (dr in -1..1) {
            for (dc in -1..1) {
                val bucket = grid[key(r + dr, c + dc)] ?: continue
                for (s in bucket) {
                    val ax = (s.lo1 - lon) * kx
                    val ay = (s.la1 - lat) * ky
                    val dx = (s.lo2 - lon) * kx - ax
                    val dy = (s.la2 - lat) * ky - ay
                    val l2 = dx * dx + dy * dy
                    val t = if (l2 == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
                    val d = hypot(ax + t * dx, ay + t * dy)
                    if (d < bestD) {
                        bestD = d
                        best = Pt(s.lo1 + (s.lo2 - s.lo1) * t, s.la1 + (s.la2 - s.la1) * t)
                        outLine[idx] = s.line
                    }
                }
            }
        }
        return best
    }
}
