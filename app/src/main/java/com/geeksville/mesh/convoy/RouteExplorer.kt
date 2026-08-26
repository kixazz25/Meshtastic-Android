package com.geeksville.mesh.convoy

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ROUTE EXPLORER — ROUTEEXPLORE-2026-08-23
 *
 * The exploratory route suggester. A port of the research generator that was
 * proven on 2026-08-22 across THREE corridors with no retuning between them:
 *
 *     corridor        best route          retrace   unconfirmed junctions
 *     Toroweap        78 mi /  6 feats      50%     176
 *     Bar 10          74 mi / 10 feats      36%      53
 *     Panguitch       79 mi / 13 feats      25%     104
 *     Broken Ridge    79 mi /  9 feats      16%       1
 *
 * The Python is embedded in the living master under AUTOEXPLORE-2026-08-22 so
 * it is not recreated. THIS IS A PORT, NOT A REDESIGN — the four wrong turns
 * below were paid for once and must not be paid for again.
 *
 * ═══ THE FOUR WRONG TURNS, and why the code looks like this ═══
 *
 * ⛔ 1. DISTANCE MUST NOT BE IN THE SCORE. The first search scored
 *    `poiValue - miles * 0.06`, which is a CHEAPEST-PATH search wearing a POI
 *    bonus. It returned 5 features on 69 miles and NO SPRINGS AT ALL though
 *    springs were 22 of the 39 available. Fred: "point value should be the
 *    winner." ⭐ MILES ARE A BUDGET, NEVER A COST. Score the features; spend
 *    miles until the budget runs out. `scoreSet()` takes no distance argument
 *    and it must stay that way.
 *
 * ⛔ 2. PENALISE, DO NOT FILTER. Generating thousands of candidates, ranking
 *    them, then filtering for distinctness returns ONE route — on a corridor
 *    with a shared spine every candidate shares it. Navigation apps do the
 *    opposite: find the best, PENALISE its edges, re-solve. The second route is
 *    then the best route that AVOIDS the first. See `penalty` below.
 *
 * ⛔ 3. RANK, DO NOT PRE-CONSTRAIN. Searching inside a narrow band strangled it
 *    before it found anything. Explore to the ceiling, score, then apply the
 *    rider's band as a CUTOFF on the results.
 *
 * ⛔ 4. THE GREEDY RUNS TO THE CEILING. With one target distance every restart
 *    produced the same length. ⭐ Each restart draws its OWN target from the
 *    range, so the pool spans it.
 *
 * ⭐ AND A POSITIVE-SCORE FLOOR IS REQUIRED. The iterative penalty eventually
 * makes every scoring corridor too expensive and the search returns the best
 * REMAINING route, which visits nothing — two routes came back score 0.0 on
 * 08-22. TWO GOOD ROUTES BEAT FOUR PADDED ONES.
 *
 * ═══ MEASURED FACTS THAT SHAPE THE OUTPUT ═══
 *
 * COVERAGE IS ~12 MILES PER FEATURE, NEAR-LINEAR, NO KNEE. All 39 features in
 * the Arizona Strip were reachable — at 448.7 miles. So a 60-mile ride reaching
 * 4 features is what the corridor COSTS, not a bug. The panel says so.
 *
 * JUNCTION TOLERANCE IS 25 FT, decided by a sweep at 0/10/25/50/100/150:
 * degree-4 nodes climb to 85% by 50 ft, which is parallel tracks being WELDED
 * TOGETHER rather than crossings, and at 150 ft junctions actually FALL as the
 * grid merges distinct ones. ⚠ Anything above 25 ft is invention.
 *
 * CAUTIONS ARE CARRIED, NOT HIDDEN. Every junction wider than 10 ft gets a
 * caution with its real gap. Fred: "add that factor to the trail rating...
 * highlight the 25' intersection so they can use sat to zoom in." The count is
 * a property of the DATA, not the setting — Broken Ridge produced ONE in 79
 * miles against 176 on the Strip, same code.
 */
object RouteExplorer {

    private const val TAG = "RouteExplorer"

    // ── measured constants. Do not tune without re-running the sweep. ──
    private const val SNAP_FT = 25.0
    private const val CAUTION_FT = 10.0
    private const val POI_BUF_MI = 0.5

    /* ROUTEASSIST-2026-08-25A4 -- how far a pin may sit from the routable
     * network before it is not a pin on the network at all. Both taken
     * from nav_pass6b_sweep_2026-08-24.py, which is where the measured
     * numbers came from; do not tune them here without re-running it.
     *
     * The anchor gets a wider cap because a trailhead is a physical AREA
     * -- trucks and trailers -- and the rider taps the marker, not the
     * tread. Panguitch's own trailhead measured 3,814 ft out.
     */
    private const val SNAP_ANCHOR_MI = 2.0
    private const val SNAP_POINT_MI = 0.5
    private const val PENALTY = 2.2
    private const val TRIES = 2500
    private const val BEAM_TOP = 5

    /** What the caller asks for. */
    data class Request(
        val anchorLat: Double,
        val anchorLon: Double,
        val name: String,
        val milesLow: Double,
        val milesHigh: Double,
        val mphLow: Double,
        val mphHigh: Double,
        val maxRoutes: Int = 4,
        /** Must-visit points. Empty = explore mode. */
        val includePoints: List<Pair<Double, Double>> = emptyList(),
        val seed: Long = 20260823L,
    )

    data class Progress(val step: String, val detail: String = "")

    data class Suggestion(
        val name: String,
        val miles: Double,
        val hoursLow: Double,
        val hoursHigh: Double,
        val featureCount: Int,
        val featureMix: String,
        val draftName: String,
    )

    // ══════════════════════════════════════════════════════════════════
    // THE GRAPH
    // ══════════════════════════════════════════════════════════════════

    private class Edge(
        val u: Long, val v: Long, val miles: Double,
        val name: String?, val trailId: String,
        val pts: List<DoubleArray>,
    )

    /**
     * ⭐ CACHED, keyed on corridor + trail count.
     *
     * Building the graph is the HEAVY step — 27,000 edges was seconds on a
     * laptop and it is the SAME work every time for the same area. Per-request
     * would rebuild it on every tap. Once-at-import is wrong because the
     * corridor depends on where the rider drops the pin, which is not known at
     * import.
     *
     * ⚠ INVALIDATION: a new import changes the trails, so trailCount is part of
     * the key. Nothing else needs tracking.
     */
    private class Graph(
        val key: String,
        val nodes: HashMap<Long, DoubleArray>,
        val edges: ArrayList<Edge>,
        val adj: HashMap<Long, ArrayList<IntArray>>,   // node -> [neighbour, edgeIdx]
        val gapFt: HashMap<Long, Double>,              // junction spread, for cautions
        val jCentre: HashMap<Long, DoubleArray>,       // the junction's true middle
        val mainComponent: HashSet<Long>,
    )

    private var cached: Graph? = null

    private val NONAMES = setOf("", "not named", "unnamed", "none", "null", "n/a", "-")
    private val WATER = setOf("spring")
    private val VIEW = setOf("peak", "cliff", "volcano")
    /* USERPOI-2026-08-25E: the rider's own dropped points.
     *
     * ⚠ A CLASS OF ITS OWN, deliberately. scoreSet decays base value per
     * class -- base/(1+0.22*(n-1)) -- so pins sharing a natural class would
     * dilute each other AND the natural POIs of that class.
     *
     * ⛔ The decay still applies WITHIN this class, because scoreSet is
     * shared and changing it would move every natural POI too. So the base
     * is set high enough that the TENTH pin -- 60/(1+0.22*9) = 20.1 -- still
     * far outweighs a peak at 3.0. The pins percolate to the top on gain
     * per mile, which is what Fred specified.
     */
    private const val USERPIN = "userpin"
    private const val USERPIN_BASE = 60.0

    /**
     * ROUTEREACH-2026-08-23W: NOT EVERY fclass IS A FEATURE. These are administrative
     * or trivial and were being scored as though a rider would ride to them --
     * 13 inside the Panguitch box, 24 in the wider one.
     */
    /* JUNKCLASS-2026-08-26: settlements are not features to ride to.
     *
     * ⛔ locality (3,271), hamlet (1,220) and suburb (47) were NOT in this set,
     * so they scored 1.0 each and pulled routes into towns -- Fred, 08-26:
     * "my route created in towns and developments". A locality sits on roads,
     * so a route built to reach one FOLLOWS ROADS to get there.
     *
     * ⚠ locality is also where trailheads live: "Devils Garden Trailhead" is
     * fclass=locality, so the engine was scoring trailheads as destinations.
     *
     * ⭐ An ALLOW-LIST would be safer than this block-list -- an unrecognised
     * class currently scores 1.0 and gets ridden to by default. Left as a
     * separate decision.
     */
    private val JUNK = setOf("county", "city", "town", "tree", "village",
        "locality", "hamlet", "suburb", "farm")

    private fun realName(n: String?): Boolean =
        !n.isNullOrBlank() && n.trim().lowercase() !in NONAMES

    private fun hav(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 3958.8
        val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
        val dp = p2 - p1
        val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(min(1.0, h)))
    }

    /** WKT LINESTRING -> points. ⚠ WKT is lon lat; we store lat lon. */
    private fun wktPoints(wkt: String?): List<DoubleArray> {
        if (wkt.isNullOrBlank()) return emptyList()
        val out = ArrayList<DoubleArray>()
        val m = Regex("(-?\\d+\\.?\\d*)\\s+(-?\\d+\\.?\\d*)").findAll(wkt)
        for (g in m) {
            val lon = g.groupValues[1].toDoubleOrNull() ?: continue
            val lat = g.groupValues[2].toDoubleOrNull() ?: continue
            out.add(doubleArrayOf(lat, lon))
        }
        return out
    }

    /** Snapped grid key. Packs the two cell indices into one Long. */
    private fun cellKey(lat: Double, lon: Double): Long {
        val d = (SNAP_FT / 5280.0) / 69.0
        val a = Math.round(lat / d)
        val b = Math.round(lon / d)
        return (a shl 32) xor (b and 0xffffffffL)
    }

    /**
     * ⭐ THE CORRIDOR IS DERIVED, NOT SUPPLIED. A loop cannot get further from
     * home than half its length, so half the mileage ceiling is the radius —
     * with a margin, because trails wander. Nobody should be asked for a
     * bounding box.
     */
    private fun corridorBox(req: Request): DoubleArray {
        // ROUTEREACH-2026-08-23W: the old formula took half the ceiling, scaled it
        // down and added a margin -- 35 mi for an 80-mile ride, a 70x70 box,
        // 53,711 edges, and an OutOfMemoryError at java.lang.Long.valueOf while
        // building 112 Dijkstra tables.
        //
        // MEASURED at Panguitch, edges inside the box holding usable POIs:
        //     15 mi ->  6,193      25 mi -> 18,753
        //     20 mi ->  9,499      30 mi -> 26,682
        // An 80-mile round trip reaches ~25-30 mi out, so ceiling/3 is the shape.
        //
        // ⭐ VERIFIED, NOT ASSUMED: the research generator re-run with a derived
        // box at ceiling/3 gave 40,558 edges instead of 129,474 and produced
        // IDENTICAL routes -- Panguitch 1 at 78.6 mi, 13 features, 25% retrace,
        // every trail leg and mile marker matching. Nothing of value was lost.
        //
        // ⚠ The margin is thin. If this still OOMs, the next step is unboxing
        // the distance table, not shrinking the box further -- past here the
        // routes start getting worse.
        val radiusMi = req.milesHigh / 3.0
        val dLat = radiusMi / 69.0
        val dLon = radiusMi / (69.0 * max(0.2, cos(Math.toRadians(req.anchorLat))))
        return doubleArrayOf(
            req.anchorLat - dLat, req.anchorLat + dLat,
            req.anchorLon - dLon, req.anchorLon + dLon
        )
    }

    private fun buildGraph(
        db: SQLiteDatabase, box: DoubleArray, trailCount: Int,
        onProgress: ((Progress) -> Unit)?,
    ): Graph {
        val key = "%.3f_%.3f_%.3f_%.3f_%d".format(box[0], box[1], box[2], box[3], trailCount)
        cached?.let { if (it.key == key) { onProgress?.invoke(Progress("Using cached map")); return it } }

        onProgress?.invoke(Progress("Reading trails"))
        val geoms = ArrayList<Triple<String, String?, List<DoubleArray>>>()
        db.rawQuery(
            "SELECT trail_id,name,geometry FROM trails " +
                "WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
            arrayOf(box[1].toString(), box[0].toString(), box[3].toString(), box[2].toString())
        ).use { c ->
            while (c.moveToNext()) {
                val pts = wktPoints(c.getString(2))
                if (pts.size >= 2) geoms.add(Triple(c.getString(0), c.getString(1), pts))
            }
        }
        onProgress?.invoke(Progress("Building the trail network", "${geoms.size} trails"))

        // pass A: which snapped vertices are shared by 2+ trails -> junctions
        val touch = HashMap<Long, HashSet<String>>()
        val spread = HashMap<Long, ArrayList<DoubleArray>>()
        for ((tid, _, pts) in geoms) {
            for (p in pts) {
                val k = cellKey(p[0], p[1])
                touch.getOrPut(k) { HashSet() }.add(tid)
                spread.getOrPut(k) { ArrayList() }.add(p)
            }
        }
        val shared = HashSet<Long>()
        val gapFt = HashMap<Long, Double>()
        val jCentre = HashMap<Long, DoubleArray>()
        for ((k, s) in touch) {
            if (s.size < 2) continue
            shared.add(k)
            val ps = spread[k]!!
            var g = 0.0
            for (i in ps.indices) {
                var j = i + 1
                while (j < min(ps.size, i + 6)) {
                    g = max(g, hav(ps[i][0], ps[i][1], ps[j][0], ps[j][1]) * 5280)
                    j++
                }
            }
            gapFt[k] = g
            // ⭐ neither trail's own vertex is the junction; the middle of them is
            jCentre[k] = doubleArrayOf(ps.sumOf { it[0] } / ps.size, ps.sumOf { it[1] } / ps.size)
        }

        // pass B: split every trail at its endpoints AND any shared vertex
        val nodes = HashMap<Long, DoubleArray>()
        val edges = ArrayList<Edge>()
        for ((tid, nm, pts) in geoms) {
            val cuts = sortedSetOf(0, pts.size - 1)
            for (i in 1 until pts.size - 1) if (cellKey(pts[i][0], pts[i][1]) in shared) cuts.add(i)
            val cl = cuts.toList()
            for (i in 0 until cl.size - 1) {
                val seg = pts.subList(cl[i], cl[i + 1] + 1)
                if (seg.size < 2) continue
                var L = 0.0
                for (j in 0 until seg.size - 1)
                    L += hav(seg[j][0], seg[j][1], seg[j + 1][0], seg[j + 1][1])
                if (L <= 0.0005) continue
                val ka = cellKey(seg[0][0], seg[0][1])
                val kb = cellKey(seg[seg.size - 1][0], seg[seg.size - 1][1])
                if (ka == kb) continue
                nodes.getOrPut(ka) { seg[0] }
                nodes.getOrPut(kb) { seg[seg.size - 1] }
                edges.add(Edge(ka, kb, L, if (realName(nm)) nm else null, tid, ArrayList(seg)))
            }
        }

        val adj = HashMap<Long, ArrayList<IntArray>>()
        edges.forEachIndexed { i, e ->
            adj.getOrPut(e.u) { ArrayList() }.add(intArrayOf(e.v.toInt(), i))
            adj.getOrPut(e.v) { ArrayList() }.add(intArrayOf(e.u.toInt(), i))
        }
        // the int cast above loses the packed key, so rebuild properly
        adj.clear()
        val adj2 = HashMap<Long, ArrayList<LongArray>>()
        edges.forEachIndexed { i, e ->
            adj2.getOrPut(e.u) { ArrayList() }.add(longArrayOf(e.v, i.toLong()))
            adj2.getOrPut(e.v) { ArrayList() }.add(longArrayOf(e.u, i.toLong()))
        }
        val adjFinal = HashMap<Long, ArrayList<IntArray>>()
        for ((n, lst) in adj2) {
            val a = ArrayList<IntArray>()
            for (x in lst) a.add(intArrayOf(0, x[1].toInt()))
            adjFinal[n] = a
        }

        // largest connected component
        val seen = HashSet<Long>()
        var best = HashSet<Long>()
        for (n in adj2.keys) {
            if (n in seen) continue
            val comp = HashSet<Long>()
            val q = ArrayDeque<Long>().apply { add(n) }
            seen.add(n)
            while (q.isNotEmpty()) {
                val x = q.removeFirst(); comp.add(x)
                for (y in adj2[x] ?: emptyList()) {
                    if (seen.add(y[0])) q.add(y[0])
                }
            }
            if (comp.size > best.size) best = comp
        }
        onProgress?.invoke(
            Progress("Network ready", "${edges.size} sections, ${best.size} junctions")
        )

        val g = Graph(key, nodes, edges, adjFinal, gapFt, jCentre, best)
        // adjacency the search actually uses
        gAdj = adj2
        cached = g
        return g
    }

    /** Neighbour lists keyed properly. Held beside the graph. */
    private var gAdj: HashMap<Long, ArrayList<LongArray>> = HashMap()

    // ══════════════════════════════════════════════════════════════════
    // POIs
    // ══════════════════════════════════════════════════════════════════

    private class Poi(
        val name: String, val fclass: String,
        val lat: Double, val lon: Double, val offMi: Double,
    )

    private fun loadPois(
        db: SQLiteDatabase, box: DoubleArray, g: Graph,
    ): HashMap<Long, ArrayList<Poi>> {
        val out = HashMap<Long, ArrayList<Poi>>()
        db.rawQuery(
            "SELECT name,fclass,lat,lon FROM reference_points " +
                "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf(box[0].toString(), box[1].toString(), box[2].toString(), box[3].toString())
        ).use { c ->
            while (c.moveToNext()) {
                val nm = c.getString(0) ?: continue
                val fc = c.getString(1) ?: "other"
                if (fc in JUNK) continue      // ROUTEREACH-2026-08-23W

                val la = c.getDouble(2); val lo = c.getDouble(3)
                var bestK = 0L; var bestD = Double.MAX_VALUE
                for (k in g.mainComponent) {
                    val p = g.nodes[k] ?: continue
                    val d = hav(la, lo, p[0], p[1])
                    if (d < bestD) { bestD = d; bestK = k }
                }
                if (bestD <= POI_BUF_MI) {
                    out.getOrPut(bestK) { ArrayList() }.add(Poi(nm, fc, la, lo, bestD))
                }
            }
        }
        return out
    }

    /**
     * ⭐ VARIETY-WEIGHTED, AND DISTANCE-FREE. A second class of feature beats
     * another of the same, so four springs do not crowd out a summit. ⚠ THERE
     * IS NO DISTANCE TERM HERE AND THERE MUST NOT BE — see wrong turn 1.
     */
    private fun scoreSet(ks: List<Long>, poiAt: Map<Long, List<Poi>>): Double {
        val seen = HashSet<String>()
        val byClass = HashMap<String, Int>()
        var v = 0.0
        for (k in ks) for (p in poiAt[k] ?: emptyList()) {
            if (!seen.add(p.name)) continue
            val n = (byClass[p.fclass] ?: 0) + 1
            byClass[p.fclass] = n
            // USERPOI-2026-08-25E: the rider's pins outrank every natural class.
            val base = if (p.fclass == USERPIN) USERPIN_BASE
                else if (p.fclass in VIEW) 3.0 else if (p.fclass in WATER) 2.0 else 1.0
            v += base / (1.0 + 0.22 * (n - 1))
        }
        return v
    }

    // ══════════════════════════════════════════════════════════════════
    // SEARCH
    // ══════════════════════════════════════════════════════════════════

    private class Dij(val dist: HashMap<Long, Double>, val prev: HashMap<Long, LongArray>)

    private fun dijkstra(src: Long, g: Graph, pen: Map<Int, Double>): Dij {
        val dist = HashMap<Long, Double>(); dist[src] = 0.0
        val prev = HashMap<Long, LongArray>()
        val pq = java.util.PriorityQueue<Pair<Double, Long>>(compareBy { it.first })
        pq.add(0.0 to src)
        while (pq.isNotEmpty()) {
            val (d, x) = pq.poll()
            if (d > (dist[x] ?: Double.MAX_VALUE)) continue
            for (nb in gAdj[x] ?: emptyList()) {
                val ei = nb[1].toInt()
                val w = g.edges[ei].miles * (pen[ei] ?: 1.0)
                val nd = d + w
                if (nd < (dist[nb[0]] ?: Double.MAX_VALUE)) {
                    dist[nb[0]] = nd
                    prev[nb[0]] = longArrayOf(x, ei.toLong())
                    pq.add(nd to nb[0])
                }
            }
        }
        return Dij(dist, prev)
    }

    private fun pathEdges(d: Dij, src: Long, dst: Long): List<Int>? {
        val out = ArrayList<Int>()
        var x = dst
        while (x != src) {
            val p = d.prev[x] ?: return null
            out.add(p[1].toInt()); x = p[0]
        }
        out.reverse(); return out
    }

    private fun nearestNode(lat: Double, lon: Double, g: Graph): Long {
        var bk = 0L; var bd = Double.MAX_VALUE
        for (k in g.mainComponent) {
            val p = g.nodes[k] ?: continue
            val d = hav(lat, lon, p[0], p[1])
            if (d < bd) { bd = d; bk = k }
        }
        return bk
    }

    // ══════════════════════════════════════════════════════════════════
    // ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════
    // ROUTEASSIST-2026-08-25A -- RIDER-ASSISTED ROUTING, STAGE 1 (feasibility)
    //
    // Measured 08-24 at Panguitch, points all within 13 miles of the trailhead:
    //   3 points -> 93.5 mi   5 -> 97.3   7 -> 110.6   10 -> 139.0
    // Three points already blow an 80-mile ceiling and seven more cost only 45.
    // THE BASE COST DOMINATES; the points are nearly free once you are in the
    // region. A rider cannot predict this from a map -- two points two miles
    // apart cost 32.9 network miles at that anchor -- which is the whole reason
    // this runs on every drop instead of once at the end.
    //
    // ⭐ EXACT, NOT APPROXIMATE. Held-Karp answers "the best possible is 93.5
    // miles", which a rider can act on. "We could not find one under 80" is not
    // something they can act on. k <= 10 so 2^k * k^2 is microseconds.
    //
    // ⚠ MEMORY. This holds one Dij per terminal for the duration of the call --
    // at most 11, roughly 9 MB over a 20k-node corridor. The 08-23 OOM was 112
    // simultaneous distance tables with boxed keys on a 256 MB heap. Releasing
    // each instead would mean re-running every Dijkstra to recover leg geometry
    // later, so they are held deliberately and released together on return.
    // The pin cap is what bounds this; if the cap goes, re-measure.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * @param order      indices into (anchor + points), anchor first, visiting
     *                   order -- NOT the order they were dropped.
     * @param totalMiles the FLOOR. Shortest possible ride that visits them all.
     * @param legMiles   per hop, same order. This is what makes a 32-mile pin
     *                   read differently from a 2-mile one.
     * @param snapFeet   how far each pin moved to reach the network.
     * @param onFragment indices sitting on a disconnected piece of network.
     *                   A DIFFERENT PROBLEM FROM BEING FAR, and it must not be
     *                   reported as distance -- the rider would move the pin a
     *                   little and try again, forever.
     * @param unreachable indices dropped because no path exists in one or both
     *                   directions. Reported, never silently discarded.
     * @param marginalMiles what EACH PIN COST, aligned to (anchor + points) with
     *                   0.0 at index 0. tour(all) - tour(all minus this one).
     *                   -1.0 means the reduced tour could not be solved, so the
     *                   figure is unknown rather than zero -- do not render -1
     *                   as "free".
     *                   ⭐ THIS IS THE ACTIONABLE NUMBER. "You are 13 miles over"
     *                   tells a rider nothing about what to change; "this pin
     *                   added 32" does. 32 miles reads very differently from 2.
     * @param overMiles  how far the FLOOR exceeds req.milesHigh, or 0.0 when it
     *                   fits. Computed here because assess() already holds the
     *                   band -- a caller re-deriving it is a second place for
     *                   the ceiling to live.
     *                   ⚠ MEASURED 08-24: three pins cost 93.5 mi against an 80
     *                   ceiling at Panguitch, with every point inside 13 miles
     *                   of the trailhead. Over-band is the COMMON case. Whatever
     *                   renders this must not look like an error.
     */
    data class PinFeasibility(
        val order: List<Int>,
        val totalMiles: Double,
        val legMiles: List<Double>,
        val snapFeet: List<Double>,
        val onFragment: List<Int>,
        val unreachable: List<Int>,
        val marginalMiles: List<Double>,
        val overMiles: Double,
        /**
         * ROUTEASSIST-2026-08-25A4: how far the floor sits BELOW
         * req.milesLow, or 0.0 when it does not. Not the same state as a
         * fit -- under the floor there are miles that MUST be spent and
         * the scorer has room to work; over the ceiling no search helps.
         * overMiles alone reported both as 0.0.
         */
        val underMiles: Double
    )

    // ══════════════════════════════════════════════════════════════════════
    // ROUTEASSIST-2026-08-25A3 -- THE TWO RIDE SHAPES
    //
    // Fred, 08-25: "is this a loop route or return to a new route end dest.
    // that determines if a route end point must be dropped."
    //
    // ⭐ SEALED, NOT A NULLABLE END POINT. With `endLat: Double? = null` every
    // call site is ambiguous between "the rider chose a loop" and "the rider
    // has not answered yet" -- and those demand different UI. Same reasoning
    // that made ImportScope sealed (OsmImportStage.kt:28) rather than a
    // nullable Bbox. CODE RULE 1: the unanswered state is unrepresentable
    // instead of being spelled the same way as a real answer.
    // ══════════════════════════════════════════════════════════════════════
    sealed interface RouteShape {
        object Loop : RouteShape
        data class PointToPoint(val endLat: Double, val endLon: Double) : RouteShape
    }

    /**
     * Exact shortest OPEN path: starts at index 0, finishes at [endIdx],
     * visiting every other index once. Returns (order, miles).
     *
     * Same subset table as heldKarp, one terminal condition different -- no
     * leg home. Worst case here is start + end + 10 points, so the middle set
     * is 10 and the table is 2^10 * 10^2.
     */
    private fun heldKarpOpen(m: Array<DoubleArray>, endIdx: Int): Pair<List<Int>, Double> {
        val n = m.size
        if (endIdx <= 0 || endIdx >= n) return emptyList<Int>() to Double.MAX_VALUE
        val mid = (0 until n).filter { it != 0 && it != endIdx }
        val k = mid.size

        if (k == 0) {
            val d = m[0][endIdx]
            return if (d == Double.MAX_VALUE) emptyList<Int>() to Double.MAX_VALUE
            else listOf(0, endIdx) to d
        }

        val full = 1 shl k
        val dp = Array(full) { DoubleArray(k) { Double.MAX_VALUE } }
        val par = Array(full) { IntArray(k) { -1 } }

        for (i in 0 until k) {
            val d = m[0][mid[i]]
            if (d != Double.MAX_VALUE) dp[1 shl i][i] = d
        }

        for (mask in 0 until full) {
            for (last in 0 until k) {
                if (mask and (1 shl last) == 0) continue
                val cur = dp[mask][last]
                if (cur == Double.MAX_VALUE) continue
                for (nxt in 0 until k) {
                    if (mask and (1 shl nxt) != 0) continue
                    val step = m[mid[last]][mid[nxt]]
                    if (step == Double.MAX_VALUE) continue
                    val nm = mask or (1 shl nxt)
                    val cand = cur + step
                    if (cand < dp[nm][nxt]) {
                        dp[nm][nxt] = cand
                        par[nm][nxt] = last
                    }
                }
            }
        }

        var bestLast = -1
        var best = Double.MAX_VALUE
        for (last in 0 until k) {
            val t = dp[full - 1][last]
            if (t == Double.MAX_VALUE) continue
            val tail = m[mid[last]][endIdx]
            if (tail == Double.MAX_VALUE) continue
            val tot = t + tail
            if (tot < best) { best = tot; bestLast = last }
        }
        if (bestLast < 0) return emptyList<Int>() to Double.MAX_VALUE

        val rev = ArrayList<Int>()
        var mask = full - 1
        var last = bestLast
        while (last >= 0) {
            rev.add(mid[last])
            val p = par[mask][last]
            mask = mask xor (1 shl last)
            last = p
        }
        rev.reverse()
        return (listOf(0) + rev + listOf(endIdx)) to best
    }

    /**
     * The corridor box, shaped to the ride.
     *
     * ⛔ WHY THIS EXISTS. corridorBox() is a circle of milesHigh / 3 around the
     * TRAILHEAD, and its justification is loop geometry -- a loop cannot get
     * further from home than half its length. That reasoning does not survive a
     * point-to-point ride: an end point 40 miles out is perfectly realistic at
     * an 80-mile band and sits outside a 26.7-mile circle.
     *
     * ⚠⚠ AND THE FAILURE WOULD BE SILENT. nearestMainNode does not reject a
     * point outside the graph -- it returns the nearest node INSIDE it, which is
     * the box edge. The rider would get a confident, UNDER-stated mileage for a
     * route to somewhere they did not pick. No error, no warning, a plausible
     * number. That is worse than refusing.
     *
     * ⭐ THE SHAPE, from Fred 08-25: "an 80 mile straight shot is the only point
     * that can exist; a 60 mile ride with 10 miles of east west swing reduces
     * the maximum length to 79." The slack between the ceiling and the direct
     * distance IS the lateral budget, so the box is the start-end segment
     * buffered by half of it. Ends 78 apart on an 80 band: a thin corridor.
     * Ends 20 apart on a 60 band: a wide one. Usually SMALLER than today's
     * circle, not larger.
     *
     * ⚠ THE 2-MILE FLOOR IS A JUDGEMENT CALL, recorded as one. A literal
     * half-slack buffer on a near-straight-shot ride is thinner than trails
     * actually wander, so the box could exclude the only connecting trail and
     * report "cannot be routed" for a ride that genuinely fits at 79.5 miles.
     * The floor costs a slightly larger graph in the tightest case and buys
     * away a false negative. Remove it if refusing is preferred to guessing.
     */
    private fun corridorBoxFor(
        req: Request,
        shape: RouteShape,
        points: List<Pair<Double, Double>>
    ): DoubleArray {
        if (shape !is RouteShape.PointToPoint) return corridorBox(req)

        val direct = hav(req.anchorLat, req.anchorLon, shape.endLat, shape.endLon)
        val slack = (req.milesHigh - direct).coerceAtLeast(0.0)
        val bufMi = max(slack / 2.0, 2.0)

        var minLat = min(req.anchorLat, shape.endLat)
        var maxLat = max(req.anchorLat, shape.endLat)
        var minLon = min(req.anchorLon, shape.endLon)
        var maxLon = max(req.anchorLon, shape.endLon)

        // Include the rider's own points: they are inside the ride by
        // definition, and one dropped wide of the segment still has to be in
        // the graph or it snaps to the box edge -- the same silent failure the
        // end point would have had.
        for ((lat, lon) in points) {
            minLat = min(minLat, lat); maxLat = max(maxLat, lat)
            minLon = min(minLon, lon); maxLon = max(maxLon, lon)
        }

        val dLat = bufMi / 69.0
        val dLon = bufMi / (69.0 * max(0.2, cos(Math.toRadians(req.anchorLat))))
        val box = doubleArrayOf(minLat - dLat, maxLat + dLat, minLon - dLon, maxLon + dLon)
        Log.i(
            TAG,
            "corridorBoxFor: point-to-point direct=${"%.1f".format(direct)} mi, " +
                "slack=${"%.1f".format(slack)}, buffer=${"%.1f".format(bufMi)} mi"
        )
        return box
    }

    /** Nearest node ON THE MAIN COMPONENT. Returns (nodeKey, miles). */
    private fun nearestMainNode(lat: Double, lon: Double, g: Graph): Pair<Long, Double> {
        var best = 0L
        var bestD = Double.MAX_VALUE
        for (k in g.mainComponent) {
            val p = g.nodes[k] ?: continue
            val d = hav(lat, lon, p[0], p[1])
            if (d < bestD) { bestD = d; best = k }
        }
        return best to bestD
    }

    /**
     * Nearest node over EVERY node, main component or not.
     *
     * Its only job is the comparison in assess(): if a pin is much closer to
     * some node than to anything routable, the rider is standing on a fragment.
     * Half the network is fragments (51% of nodes were in the main component at
     * Panguitch), so this is the common case, not an edge case.
     */
    private fun nearestAnyNode(lat: Double, lon: Double, g: Graph): Pair<Long, Double> {
        var best = 0L
        var bestD = Double.MAX_VALUE
        for ((k, p) in g.nodes) {
            val d = hav(lat, lon, p[0], p[1])
            if (d < bestD) { bestD = d; best = k }
        }
        return best to bestD
    }

    /**
     * Exact shortest closed tour from index 0 through every other index and
     * back. Returns (order, miles), or (emptyList, MAX_VALUE) when the matrix
     * has no complete tour.
     *
     * MAX_VALUE entries mean "no path" and are never added into -- every
     * accumulation is guarded, so an unreachable pair cannot overflow into a
     * plausible-looking small number.
     */
    private fun heldKarp(m: Array<DoubleArray>): Pair<List<Int>, Double> {
        val n = m.size
        if (n <= 1) return listOf(0) to 0.0
        if (n == 2) {
            val out = m[0][1]
            val back = m[1][0]
            if (out == Double.MAX_VALUE || back == Double.MAX_VALUE)
                return emptyList<Int>() to Double.MAX_VALUE
            return listOf(0, 1) to (out + back)
        }

        val k = n - 1
        val full = 1 shl k
        val dp = Array(full) { DoubleArray(k) { Double.MAX_VALUE } }
        val par = Array(full) { IntArray(k) { -1 } }

        for (i in 0 until k) {
            val d = m[0][i + 1]
            if (d != Double.MAX_VALUE) dp[1 shl i][i] = d
        }

        for (mask in 0 until full) {
            for (last in 0 until k) {
                if (mask and (1 shl last) == 0) continue
                val cur = dp[mask][last]
                if (cur == Double.MAX_VALUE) continue
                for (nxt in 0 until k) {
                    if (mask and (1 shl nxt) != 0) continue
                    val step = m[last + 1][nxt + 1]
                    if (step == Double.MAX_VALUE) continue
                    val nm = mask or (1 shl nxt)
                    val cand = cur + step
                    if (cand < dp[nm][nxt]) {
                        dp[nm][nxt] = cand
                        par[nm][nxt] = last
                    }
                }
            }
        }

        var bestEnd = -1
        var best = Double.MAX_VALUE
        for (last in 0 until k) {
            val t = dp[full - 1][last]
            if (t == Double.MAX_VALUE) continue
            val home = m[last + 1][0]
            if (home == Double.MAX_VALUE) continue
            val tot = t + home
            if (tot < best) { best = tot; bestEnd = last }
        }
        if (bestEnd < 0) return emptyList<Int>() to Double.MAX_VALUE

        val rev = ArrayList<Int>()
        var mask = full - 1
        var last = bestEnd
        while (last >= 0) {
            rev.add(last + 1)
            val p = par[mask][last]
            mask = mask xor (1 shl last)
            last = p
        }
        rev.reverse()
        return (listOf(0) + rev) to best
    }

    /**
     * What the rider's pins cost, right now.
     *
     * Called on every drop and every removal. `req` supplies the anchor and the
     * mileage band; only anchorLat/anchorLon/milesHigh are read, because the
     * corridor box is derived from milesHigh (REACH = ceiling / 3, the 08-23
     * OOM fix -- 53.4 x 53.4 miles at an 80-mile band).
     *
     * Returns null only when there is no connected network here at all, which
     * is a different message from "your pins cost too much".
     */
    fun assess(
        spatialDb: SQLiteDatabase,
        req: Request,
        points: List<Pair<Double, Double>>,
        shape: RouteShape
    ): PinFeasibility? {
        var trailCount = 0
        spatialDb.rawQuery("SELECT COUNT(*) FROM trails", null).use {
            if (it.moveToNext()) trailCount = it.getInt(0)
        }

        val g = buildGraph(spatialDb, corridorBoxFor(req, shape, points), trailCount, null)
        if (g.mainComponent.isEmpty()) {
            Log.w(TAG, "assess: no connected network in corridor")
            return null
        }

        // Terminal order is anchor, then the rider's points, then the end
        // point LAST when there is one. Keeping user points at 1..points.size
        // means every index the panel shows means the same thing in both
        // shapes -- marginal[3] is the third pin, loop or not.
        val all = ArrayList<Pair<Double, Double>>(points.size + 2)
        all.add(req.anchorLat to req.anchorLon)
        all.addAll(points)
        val endIdx = if (shape is RouteShape.PointToPoint) {
            all.add(shape.endLat to shape.endLon)
            all.size - 1
        } else -1

        val terms = ArrayList<Long>(all.size)
        val snapFt = ArrayList<Double>(all.size)
        val onFrag = ArrayList<Int>()

        // ROUTEASSIST-2026-08-25A4: past its cap a pin is NOT ON the
        // network. It still gets a terminal so every index keeps meaning
        // the same thing to the panel, but it is excluded from the tour.
        val tooFar = ArrayList<Int>()
        for (i in all.indices) {
            val (lat, lon) = all[i]
            val (nMain, dMain) = nearestMainNode(lat, lon, g)
            val (_, dAny) = nearestAnyNode(lat, lon, g)
            terms.add(nMain)
            snapFt.add(dMain * 5280.0)
            val cap = if (i == 0) SNAP_ANCHOR_MI else SNAP_POINT_MI
            if (dMain > cap) {
                tooFar.add(i)
                // Inside the cap for SOME node but not a routable one: the
                // rider is on a fragment. Moving the pin will not fix it,
                // so it must not be reported as distance.
                if (dAny <= cap) onFrag.add(i)
            }
        }

        // ⚠ NOTHING TO ROUTE FROM. A different answer from "your pins cost
        // too much", and the caller must be able to say so.
        if (tooFar.contains(0)) {
            Log.w(
                TAG,
                "assess: trailhead is " +
                    "%.2f".format(snapFt[0] / 5280.0) +
                    " mi from the routable network (cap " +
                    "%.1f".format(SNAP_ANCHOR_MI) + " mi)"
            )
            return null
        }

        // One Dijkstra per DISTINCT terminal -- single-source is all-targets, so
        // each run fills a whole matrix row. Two pins snapping to the same node
        // cost one run, not two.
        val dij = HashMap<Long, Dij>()
        for (t in terms.distinct()) dij[t] = dijkstra(t, g, emptyMap())

        val n = terms.size
        val full = Array(n) { i ->
            DoubleArray(n) { j ->
                if (i == j) 0.0
                else dij[terms[i]]?.dist?.get(terms[j]) ?: Double.MAX_VALUE
            }
        }

        // Drop anything unreachable in EITHER direction, not just from the
        // anchor. Same-component membership makes a one-way failure unlikely,
        // but unlikely is not checked, and an infinite entry reaching heldKarp
        // returns MAX_VALUE for a reason nobody can see.
        val unreachable = ArrayList<Int>()
        val keep = ArrayList<Int>()
        keep.add(0)
        for (i in 1 until n) {
            // ROUTEASSIST-2026-08-25A4: past its cap it never enters the
            // tour. Checked before the matrix test because a terminal that
            // snapped to the far side of the corridor has a perfectly
            // finite distance -- it is simply the wrong place.
            if (tooFar.contains(i)) {
                if (i == endIdx) {
                    Log.w(TAG, "assess: the finish point is not on the network")
                    return null
                }
                unreachable.add(i)
                continue
            }
            // ⚠ THE END POINT IS NOT OPTIONAL. Dropping it as "unreachable"
            // would silently turn a point-to-point ride into a loop and
            // report a feasible mileage for a route that never reaches the
            // destination. It stays in; an unreachable end makes the whole
            // assessment fail, which is the honest answer.
            if (i == endIdx) { keep.add(i); continue }
            if (full[0][i] == Double.MAX_VALUE || full[i][0] == Double.MAX_VALUE) {
                unreachable.add(i)
            } else {
                keep.add(i)
            }
        }

        val sub = Array(keep.size) { a ->
            DoubleArray(keep.size) { b -> full[keep[a]][keep[b]] }
        }
        val (subOrder, miles) = if (endIdx >= 0) {
            heldKarpOpen(sub, keep.indexOf(endIdx))
        } else {
            heldKarp(sub)
        }

        if (subOrder.isEmpty() || miles == Double.MAX_VALUE) {
            Log.w(TAG, "assess: no complete tour over ${keep.size} terminal(s)")
            return PinFeasibility(
                emptyList(), 0.0, emptyList(), snapFt, onFrag,
                (1 until n).toList(), List(n) { -1.0 }, 0.0, 0.0
            )
        }

        val order = subOrder.map { keep[it] }
        // A loop wraps back to the anchor; an open path stops at the end
        // point. Wrapping an open path would add a phantom leg home and
        // overstate the ride by the whole return distance.
        val legs = ArrayList<Double>(order.size)
        val hops = if (endIdx >= 0) order.size - 1 else order.size
        for (i in 0 until hops) {
            val a = order[i]
            val b = order[(i + 1) % order.size]
            legs.add(full[a][b])
        }

        // WHAT EACH PIN COST. One Held-Karp per pin over the SAME matrix -- no
        // extra Dijkstras, so this is microseconds even at ten pins. The tour
        // without a pin is never longer than the tour with it, so a negative
        // result would mean a solver bug; it is clamped at 0.0 rather than
        // shown, because a negative marginal on screen is worse than a zero.
        val marginal = DoubleArray(n) { -1.0 }
        marginal[0] = 0.0
        if (keep.size > 2) {
            for (drop in 1 until keep.size) {
                val idx = keep.filterIndexed { i, _ -> i != drop }
                val reduced = Array(idx.size) { a ->
                    DoubleArray(idx.size) { b -> full[idx[a]][idx[b]] }
                }
                val (o2, m2) = heldKarp(reduced)
                marginal[keep[drop]] =
                    if (o2.isEmpty() || m2 == Double.MAX_VALUE) -1.0
                    else (miles - m2).coerceAtLeast(0.0)
            }
        } else if (keep.size == 2) {
            // One pin: it costs the whole tour, because without it there is no
            // ride at all. Not -1 -- this figure is known, and it is the number
            // that tells a rider the REGION is expensive, not the pin.
            marginal[keep[1]] = miles
        }

        val over = (miles - req.milesHigh).coerceAtLeast(0.0)
        val under = (req.milesLow - miles).coerceAtLeast(0.0)

        Log.i(
            TAG,
            "assess: ${points.size} point(s), floor=${"%.1f".format(miles)} mi, " +
                "band=${"%.0f".format(req.milesLow)}-${"%.0f".format(req.milesHigh)}, " +
                "over=${"%.1f".format(over)}, under=${"%.1f".format(under)}, " +
                "tooFar=$tooFar, order=$order, " +
                "marginal=${marginal.joinToString(",") { "%.1f".format(it) }}, " +
                "unreachable=$unreachable, onFragment=$onFrag"
        )
        return PinFeasibility(
            order, miles, legs, snapFt, onFrag, unreachable,
            marginal.toList(), over, under
        )
    }


    /* NEWENGINE-2026-08-26 -- helpers for the new pipeline. */

    /** Closest point on a segment, and the distance to it in MILES. */
    private fun projectOnSeg(
        plat: Double, plon: Double, a: DoubleArray, b: DoubleArray,
    ): Pair<DoubleArray, Double> {
        val fy = 69.0
        val fx = 69.0 * cos(Math.toRadians(plat))
        val px = plon * fx; val py = plat * fy
        val ax = a[1] * fx; val ay = a[0] * fy
        val bx = b[1] * fx; val by = b[0] * fy
        val vx = bx - ax; val vy = by - ay
        val l2 = vx * vx + vy * vy
        if (l2 <= 0.0) return doubleArrayOf(a[0], a[1]) to
            Math.hypot(px - ax, py - ay)
        var t = ((px - ax) * vx + (py - ay) * vy) / l2
        if (t < 0.0) t = 0.0
        if (t > 1.0) t = 1.0
        val qx = ax + t * vx; val qy = ay + t * vy
        return doubleArrayOf(qy / fy, qx / fx) to Math.hypot(px - qx, py - qy)
    }

    private var splitSeq = 0

    /**
     * Insert a node at [proj] on edge [ei], splitting it in two.
     *
     * ⭐ THIS IS THE ORIGIN FIX. nearestNode() returns the closest EXISTING
     * node and nodes only exist at junctions, so a tap mid-trail was dragged
     * hundreds of feet. Measured 613 ft with ONE spoke; splitting gives 24 ft
     * with two.
     *
     * ⚠ The synthetic key must not collide with a real cellKey. Negative keys
     * cannot be produced by cellKey at any latitude we serve.
     */
    private fun splitEdgeAt(g: Graph, ei: Int, proj: DoubleArray): Long {
        val e = g.edges[ei]
        val pu = g.nodes[e.u] ?: return e.u
        val pv = g.nodes[e.v] ?: return e.u
        val du = hav(proj[0], proj[1], pu[0], pu[1])
        val dv = hav(proj[0], proj[1], pv[0], pv[1])
        val tot = du + dv
        if (tot <= 0.0) return e.u
        val k = -1000000L - (splitSeq++).toLong()
        g.nodes[k] = proj
        val ia = g.edges.size
        g.edges.add(Edge(e.u, k, e.miles * du / tot, e.name, e.trailId, e.pts))
        val ib = g.edges.size
        g.edges.add(Edge(k, e.v, e.miles * dv / tot, e.name, e.trailId, e.pts))
        gAdj.getOrPut(e.u) { ArrayList() }.add(longArrayOf(k, ia.toLong()))
        gAdj.getOrPut(k) { ArrayList() }.add(longArrayOf(e.u, ia.toLong()))
        gAdj.getOrPut(k) { ArrayList() }.add(longArrayOf(e.v, ib.toLong()))
        gAdj.getOrPut(e.v) { ArrayList() }.add(longArrayOf(k, ib.toLong()))
        if (e.u in g.mainComponent || e.v in g.mainComponent) g.mainComponent.add(k)
        return k
    }

    /**
     * POIs attached via a spatial grid instead of scanning every node.
     *
     * ⭐ Measured 5.8s -> 0.1s. The old scan was 274 POIs x 12,231 nodes.
     * ⚠ 3x3 neighbourhood, not one cell: two points either side of a boundary
     * are inches apart and would never be compared. The answer is IDENTICAL,
     * not approximate -- nothing within POI_BUF_MI can fall outside nine cells.
     */
    private fun loadPoisGrid(
        db: SQLiteDatabase, box: DoubleArray, g: Graph, usable: Set<Long>,
    ): HashMap<Long, ArrayList<Poi>> {
        val out = HashMap<Long, ArrayList<Poi>>()
        val cell = POI_BUF_MI / 69.0
        val grid = HashMap<Long, ArrayList<Long>>()
        for (k in usable) {
            val p = g.nodes[k] ?: continue
            val gi = Math.floor(p[0] / cell).toLong()
            val gj = Math.floor(p[1] / cell).toLong()
            grid.getOrPut((gi shl 32) xor (gj and 0xffffffffL)) { ArrayList() }.add(k)
        }
        db.rawQuery(
            "SELECT name,fclass,lat,lon FROM reference_points " +
                "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf(box[0].toString(), box[1].toString(),
                    box[2].toString(), box[3].toString())
        ).use { c ->
            while (c.moveToNext()) {
                val nm = c.getString(0) ?: continue
                val fc = c.getString(1) ?: "other"
                if (fc in JUNK) continue
                val la = c.getDouble(2); val lo = c.getDouble(3)
                val gi = Math.floor(la / cell).toLong()
                val gj = Math.floor(lo / cell).toLong()
                var bk = 0L; var bd = Double.MAX_VALUE
                for (di in -1..1) for (dj in -1..1) {
                    val key = ((gi + di) shl 32) xor ((gj + dj) and 0xffffffffL)
                    for (k in grid[key] ?: continue) {
                        val p = g.nodes[k] ?: continue
                        val d = hav(la, lo, p[0], p[1])
                        if (d < bd) { bd = d; bk = k }
                    }
                }
                if (bd <= POI_BUF_MI) {
                    out.getOrPut(bk) { ArrayList() }.add(Poi(nm, fc, la, lo, bd))
                }
            }
        }
        return out
    }

    /** Every combination of [size] from [pool], without materialising them. */
    private inline fun forEachCombo(
        pool: List<Long>, size: Int, body: (List<Long>) -> Unit,
    ) {
        if (size <= 0 || size > pool.size) return
        val idx = IntArray(size) { it }
        while (true) {
            body(idx.map { pool[it] })
            var i = size - 1
            while (i >= 0 && idx[i] == i + pool.size - size) i--
            if (i < 0) return
            idx[i]++
            for (j in i + 1 until size) idx[j] = idx[j - 1] + 1
        }
    }

    /**
     * Shortest closed tour through [combo] from [start].
     *
     * ⚠ Exact permutations to 8 features, nearest-neighbour above. Permuting
     * 9+ is not affordable and the answer only has to be good enough to FILTER
     * on -- the mileage band is what decides, not the last tenth of a mile.
     */
    private fun orderTour(
        combo: List<Long>, start: Long, d: (Long, Long) -> Double?,
    ): Pair<List<Long>, Double>? {
        if (combo.isEmpty()) return null
        if (combo.size <= 8) {
            var best: Pair<List<Long>, Double>? = null
            val arr = combo.toMutableList()
            fun perm(k: Int) {
                if (k == arr.size) {
                    var t = 0.0; var cur = start
                    for (p in arr) { val x = d(cur, p) ?: return; t += x; cur = p }
                    val back = d(cur, start) ?: return
                    t += back
                    if (best == null || t < best!!.second) best = arr.toList() to t
                    return
                }
                for (i in k until arr.size) {
                    val tmp = arr[k]; arr[k] = arr[i]; arr[i] = tmp
                    perm(k + 1)
                    val t2 = arr[k]; arr[k] = arr[i]; arr[i] = t2
                }
            }
            perm(0)
            return best
        }
        var cur = start
        val left = combo.toMutableList()
        val order = ArrayList<Long>()
        var t = 0.0
        while (left.isNotEmpty()) {
            var bn: Long? = null; var bd = Double.MAX_VALUE
            for (p in left) { val x = d(cur, p) ?: continue; if (x < bd) { bd = x; bn = p } }
            val n = bn ?: return null
            t += bd; order.add(n); left.remove(n); cur = n
        }
        val back = d(cur, start) ?: return null
        return order to (t + back)
    }

    /**
     * How many candidates survive the overlap rule.
     *
     * ⚠ Best first — more features, then more miles — so the ride that
     * survives a collision is the better one, never the dominated subset.
     */
    private fun survivorsOf(
        cands: List<Triple<Int, Double, List<Int>>>, maxOverlap: Double,
    ): Int {
        val sorted = cands.sortedWith(
            compareByDescending<Triple<Int, Double, List<Int>>> { it.first }
                .thenByDescending { it.second })
        val kept = ArrayList<Set<Int>>()
        for (c in sorted) {
            val es = c.third.toSet()
            var dup = false
            for (k in kept) {
                val u = (es + k).size
                if (u > 0 && (es intersect k).size.toDouble() / u >= maxOverlap) {
                    dup = true; break
                }
            }
            if (!dup) kept.add(es)
        }
        return kept.size
    }

    fun explore(
        spatialDb: SQLiteDatabase,
        req: Request,
        onProgress: ((Progress) -> Unit)? = null,
    ): List<Suggestion> {
        /* NEWENGINE-2026-08-26 -- enumerate, filter, score.
         *
         * Replaces a 2,500-restart randomised greedy. Measured 67.8s -> 3.8s
         * with identical results. Every constant below was measured, not
         * chosen; the reasoning is in GroupTrack_algorithm_annotated_2026-08-26.
         */
        val startYds = 600.0
        val wantRides = 6
        val minApart = 5.0
        // 75% sits in a clean measured gap: 80/77/72 above, then 65,
        // then nothing over 44%.
        val maxOverlap = 0.75

        val box = corridorBox(req)
        var trailCount = 0
        spatialDb.rawQuery("SELECT COUNT(*) FROM trails", null).use {
            if (it.moveToNext()) trailCount = it.getInt(0)
        }
        val g = buildGraph(spatialDb, box, trailCount, onProgress)
        if (g.mainComponent.isEmpty()) {
            Log.w(TAG, "no connected network in corridor")
            return emptyList()
        }

        /* ── components ──────────────────────────────────────────────────
         * ⛔ NOT just mainComponent. Measured 08-26: the largest component
         * held ONE of the rider's ten pins while another held SEVEN.
         * "Largest" is not "the one the rider is standing near".
         */
        val compOf = HashMap<Long, Int>()
        run {
            var ci = 0
            for (s in gAdj.keys) {
                if (s in compOf) continue
                val q = ArrayDeque<Long>().apply { add(s) }
                compOf[s] = ci
                while (q.isNotEmpty()) {
                    val x = q.removeFirst()
                    for (y in gAdj[x] ?: emptyList()) {
                        if (y[0] !in compOf) { compOf[y[0]] = ci; q.add(y[0]) }
                    }
                }
                ci++
            }
        }

        /* ── 2. candidate starts: every trail within startYds ────────────
         * ⭐ THE FIX FOR THE ORIGIN DEFECT. Find the closest point on each
         * nearby trail and SPLIT the edge there, so the ride starts where the
         * rider is rather than at a junction hundreds of feet away.
         * Measured: 613 ft / 1 spoke  ->  24 ft / 2 spokes.
         */
        onProgress?.invoke(Progress("Finding where you can start"))
        val startMi = startYds * 3.0 / 5280.0
        val seenTid = HashSet<String>()
        val originsAll = ArrayList<Triple<Long, Double, String>>()
        run {
            val cands = ArrayList<Triple<Double, Int, DoubleArray>>()
            for (i in g.edges.indices) {
                val e = g.edges[i]
                val pts = e.pts
                if (pts.size < 2) continue
                var bd = Double.MAX_VALUE
                var bp: DoubleArray? = null
                for (j in 0 until pts.size - 1) {
                    val pr = projectOnSeg(req.anchorLat, req.anchorLon, pts[j], pts[j + 1])
                    if (pr.second < bd) { bd = pr.second; bp = pr.first }
                }
                if (bd <= startMi && bp != null) cands.add(Triple(bd, i, bp))
            }
            cands.sortBy { it.first }
            for ((d, ei, proj) in cands) {
                val tid = g.edges[ei].trailId
                if (!seenTid.add(tid)) continue
                val k = splitEdgeAt(g, ei, proj)
                compOf[k] = compOf[g.edges[ei].u] ?: -1
                originsAll.add(Triple(k, d * 5280.0,
                    (g.edges[ei].name ?: "trail") + " #" + (compOf[k] ?: -1)))
            }
        }
        if (originsAll.isEmpty()) {
            Log.w(TAG, "no trail within $startYds yds of the staging point")
            return emptyList()
        }

        /* ── 3. one origin per component ─────────────────────────────────
         * ⭐ Thirteen starts at 37.7235,-112.613 were thirteen car parks onto
         * ONE network, all with the same usable POIs. Solving every
         * combination thirteen times cost 15,093 tour solves at one size.
         * ⚠ The alternatives are KEPT -- a rider deciding where to park needs
         * every start a ride can be reached from.
         */
        val altStarts = HashMap<Int, ArrayList<String>>()
        val byComp = HashMap<Int, Triple<Long, Double, String>>()
        for (o in originsAll) {
            val ci = compOf[o.first] ?: -1
            altStarts.getOrPut(ci) { ArrayList() }.add(o.third)
            val cur = byComp[ci]
            if (cur == null || o.second < cur.second) byComp[ci] = o
        }
        val origins = byComp.values.sortedBy { it.second }
        Log.i(TAG, "origins: ${originsAll.size} trail(s) -> ${origins.size} network(s)")

        /* ── 4/5. POIs, and the rider's pins as high-value POIs ──────────
         * ⭐ ONE MODEL. A pin is a POI with a high value -- no separate path,
         * no membership rule. The enumeration is exhaustive, so a combination
         * containing every pin WILL be generated; the greedy could skip them.
         */
        onProgress?.invoke(Progress("Finding features"))
        val originComps = origins.map { compOf[it.first] ?: -1 }.toHashSet()
        val usable = HashSet<Long>()
        for ((n, ci) in compOf) if (ci in originComps) usable.add(n)
        val poiAt = loadPoisGrid(spatialDb, box, g, usable)

        if (req.includePoints.isNotEmpty()) {
            req.includePoints.forEachIndexed { i, (plat, plon) ->
                /* ⚠ SPLIT, NOT nearestNode. Yesterday's E patch used
                 * nearestNode and moved pins up to 6,001 ft, collapsing ten
                 * pins onto four nodes. Same operation as the origins above.
                 */
                var bd = Double.MAX_VALUE; var bei = -1; var bp: DoubleArray? = null
                for (ei in g.edges.indices) {
                    val pts = g.edges[ei].pts
                    for (j in 0 until pts.size - 1) {
                        val pr = projectOnSeg(plat, plon, pts[j], pts[j + 1])
                        if (pr.second < bd) { bd = pr.second; bei = ei; bp = pr.first }
                    }
                }
                if (bei >= 0 && bp != null) {
                    val node = splitEdgeAt(g, bei, bp)
                    compOf[node] = compOf[g.edges[bei].u] ?: -1
                    poiAt.getOrPut(node) { ArrayList() }
                        .add(Poi("Your place ${i + 1}", USERPIN, plat, plon, 0.0))
                    Log.i(TAG, "PIN[${i + 1}] moved ${"%.0f".format(bd * 5280.0)} ft " +
                        "to ${g.edges[bei].name ?: "the nearest trail"}")
                }
            }
        }
        var poiNodes = poiAt.keys.toList()
        Log.i(TAG, "poiNodes=${poiNodes.size} usableNodes=${usable.size}")

        /* ── 6. distance matrix ──────────────────────────────────────────*/
        onProgress?.invoke(Progress("Measuring distances"))
        val terms = (origins.map { it.first } + poiNodes).distinct()
        val pen = HashMap<Int, Double>()
        val dij = HashMap<Long, Dij>()
        for (t in terms) dij[t] = dijkstra(t, g, pen)
        fun dist(a: Long, b: Long): Double? = dij[a]?.dist?.get(b)

        /* ── 7. out-and-back filter ──────────────────────────────────────
         * ⭐ THE CHEAPEST FILTER THERE IS. Removing ONE feature removes every
         * combination containing it -- dropping 4 of 14 leaves 1,024 instead
         * of 16,384, and none are ever generated.
         */
        val perOrigin = HashMap<Long, List<Long>>()
        for ((ok, _, onm) in origins) {
            val keep = poiNodes.filter { p ->
                val a = dist(ok, p); val b = dist(p, ok)
                a != null && b != null && a + b <= req.milesHigh
            }
            perOrigin[ok] = keep
            Log.i(TAG, "origin $onm: ${keep.size} of ${poiNodes.size} features usable")
        }
        val liveOrigins = origins.filter { (perOrigin[it.first] ?: emptyList()).isNotEmpty() }
        if (liveOrigins.isEmpty()) {
            onProgress?.invoke(Progress("No rides found",
                "Nothing within ${req.milesHigh.toInt()} miles of here and back."))
            return emptyList()
        }

        /* ── 8. pairwise table + clique bound ────────────────────────────
         * ⭐⭐ THE BIG WIN. The minimum ride holding both A and B is
         * origin->A + A->B + B->origin. If that busts the ceiling the pair can
         * never share a ride, so every combination holding both is dead AT
         * EVERY SIZE and is never generated.
         * ⛔ Without it, sizes 13 down to 6 generated and rejected 3,302
         * combinations for ZERO results -- most of the old 67.8s.
         */
        val badPair = HashMap<Long, HashSet<Long>>()
        var ceilingSize = 0
        for ((ok, _, _) in liveOrigins) {
            val pois = perOrigin[ok] ?: emptyList()
            val bad = HashSet<Long>()
            for (i in pois.indices) for (j in i + 1 until pois.size) {
                val a = pois[i]; val b = pois[j]
                val d1 = dist(ok, a); val d2 = dist(a, b); val d3 = dist(b, ok)
                if (d1 == null || d2 == null || d3 == null || d1 + d2 + d3 > req.milesHigh) {
                    bad.add(a xor b)
                }
            }
            badPair[ok] = bad
            // greedy clique: the largest set with no impossible pair
            var best = 0
            for (seed in pois) {
                val grp = ArrayList<Long>(); grp.add(seed)
                for (p in pois) {
                    if (p == seed) continue
                    if (grp.all { (p xor it) !in bad }) grp.add(p)
                }
                if (grp.size > best) best = grp.size
            }
            if (best > ceilingSize) ceilingSize = best
        }
        Log.i(TAG, "descent starts at $ceilingSize features (of ${poiNodes.size})")

        /* ── 9. enumerate descending, longest first, stop at six ─────────
         * ⭐ Descend from the clique bound. Within a size, the longest ride
         * first -- a rider who asked for up to milesHigh wants the fullest.
         * ⚠ Six DISTINCT rides, counted on the feature set.
         */
        onProgress?.invoke(Progress("Building rides"))
        data class Cand(val score: Double, val miles: Double, val combo: List<Long>,
                        val origin: String, val eids: List<Int>)
        val found = ArrayList<Cand>()
        var size = minOf(ceilingSize, poiNodes.size)
        while (size >= 1) {
            /* ⭐ STOP ON RIDES THAT SURVIVE THE OVERLAP RULE, not on raw finds.
             *
             * ⛔ Stopping at six FOUND gave five OFFERED once duplicates were
             * removed. Continuing to size 4 produced NINE survivors, and the
             * sixth -- 79.7 mi, Boy Scout Spring and Olsen -- shares only
             * 18-38% with anything else. It was being thrown away.
             *
             * ⚠ A subset proxy was tried and failed: two rides can have
             * non-subset feature sets and still share 80% of their trails.
             */
            if (survivorsOf(found.map {
                    Triple(it.combo.size, it.miles, it.eids) },
                    maxOverlap) >= wantRides) break
            for ((ok, _, onm) in liveOrigins) {
                val pool = perOrigin[ok] ?: continue
                if (pool.size < size) continue
                val bad = badPair[ok] ?: HashSet()
                forEachCombo(pool, size) { combo ->
                    var ok2 = true
                    outer@ for (i in combo.indices) for (j in i + 1 until combo.size) {
                        if ((combo[i] xor combo[j]) in bad) { ok2 = false; break@outer }
                    }
                    if (ok2) {
                        val ord = orderTour(combo, ok, ::dist)
                        if (ord != null) {
                            val mi = ord.second
                            if (mi >= req.milesLow && mi <= req.milesHigh) {
                                val seq = listOf(ok) + ord.first + listOf(ok)
                                val eids = ArrayList<Int>()
                                var good = true
                                for (i in 0 until seq.size - 1) {
                                    val d = dij[seq[i]]
                                    val pe = if (d == null) null else pathEdges(d, seq[i], seq[i + 1])
                                    if (pe == null) { good = false; break }
                                    eids.addAll(pe)
                                }
                                if (good) found.add(Cand(
                                    scoreSet(combo, poiAt), mi, combo, onm, eids))
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "size $size: ${found.size} candidate(s) so far")
            size--
        }

        if (found.isEmpty()) {
            onProgress?.invoke(Progress("No rides found",
                "There are ${poiNodes.size} features here but none fit that distance. " +
                    "Try a wider range."))
            return emptyList()
        }

        /* ── 10. dedupe ──────────────────────────────────────────────────
         * PASS 1 same origin AND same features, within minApart -> keep the
         *        LONGEST (the rider asked for up to milesHigh).
         * PASS 2 same features, ANY origin -> one ride found from several car
         *        parks.
         * ⛔ PASS 2 MUST NOT collapse rides reaching DIFFERENT features. A
         *    trail heading the other way is a different ride at the same
         *    mileage, and that is the whole point of multi-origin.
         */
        val p1 = HashMap<String, ArrayList<Cand>>()
        for (c in found) p1.getOrPut(c.origin + "|" + c.combo.sorted().joinToString(",")) {
            ArrayList() }.add(c)
        val afterP1 = ArrayList<Cand>()
        for ((_, lst) in p1) {
            lst.sortByDescending { it.miles }
            val keep = ArrayList<Cand>()
            for (c in lst) if (keep.all { Math.abs(c.miles - it.miles) >= minApart }) keep.add(c)
            afterP1.addAll(keep)
        }
        val p2 = HashMap<String, ArrayList<Cand>>()
        for (c in afterP1) p2.getOrPut(c.combo.sorted().joinToString(",")) { ArrayList() }.add(c)
        val afterP2 = ArrayList<Cand>()
        for ((_, lst) in p2) {
            lst.sortByDescending { it.miles }
            val keep = ArrayList<Cand>()
            for (c in lst) if (keep.all { Math.abs(c.miles - it.miles) >= minApart }) keep.add(c)
            afterP2.addAll(keep)
        }

        /* ── PASS 3: 75%+ SHARED TRAILS IS ONE RIDE ──────────────────────
         * ⛔ Measured: ride 6 was ride 4 with one knoll dropped -- the knoll
         * sat close to the line already taken, so 80% of the trails were the
         * same. Same ground, one fewer feature, 9.7 fewer miles. DOMINATED.
         * ⚠ "Better" is unambiguous: more features, then more miles.
         */
        afterP2.sortWith(compareByDescending<Cand> { it.combo.size }
            .thenByDescending { it.miles })
        val finalC = ArrayList<Cand>()
        val kept = ArrayList<Set<Int>>()
        for (c in afterP2) {
            val es = c.eids.toSet()
            var dup = false
            for (k in kept) {
                val u = (es + k).size
                if (u > 0 && (es intersect k).size.toDouble() / u >= maxOverlap) {
                    dup = true; break
                }
            }
            if (!dup) { finalC.add(c); kept.add(es) }
        }
        Log.i(TAG, "dedupe: ${found.size} raw -> ${afterP2.size} -> ${finalC.size} distinct")
        if (finalC.size < wantRides) {
            Log.i(TAG, "only ${finalC.size} genuinely different ride(s) here — " +
                "padding would mean offering rides sharing most of their trails " +
                "with one already shown")
        }

        val picks = ArrayList<Triple<List<Int>, Double, List<Long>>>()
        for (c in finalC.take(wantRides)) {
            val pins = c.combo.count { n -> poiAt[n]?.any { it.fclass == USERPIN } == true }
            Log.i(TAG, "ride: ${"%.1f".format(c.miles)} mi, ${c.combo.size} features" +
                (if (req.includePoints.isNotEmpty()) ", $pins of your places" else "") +
                ", from ${c.origin}")
            picks.add(Triple(c.eids, c.miles, c.combo))
        }
        if (picks.isEmpty()) {
            onProgress?.invoke(Progress("No rides found", "Try a wider range."))
            return emptyList()
        }

        // ── emit drafts ────────────────────────────────────────────────
        val label = req.name.ifBlank { "Ride" }
        val out = ArrayList<Suggestion>()
        picks.forEachIndexed { i, (eids, miles, _) ->
            val draftName = "$label Route ${i + 1}"
            val built = buildDraft(g, poiAt, eids, miles, req, draftName)
            out.add(built)
        }
        onProgress?.invoke(Progress("Done", "${out.size} routes"))
        return out
    }

    // ══════════════════════════════════════════════════════════════════
    // DRAFT + NARRATIVE
    // ══════════════════════════════════════════════════════════════════

    private fun buildDraft(
        g: Graph, poiAt: Map<Long, List<Poi>>, eids: List<Int>,
        miles: Double, req: Request, draftName: String,
    ): Suggestion {
        val verts = JSONArray()
        val legs = ArrayList<Triple<String, Double, Double>>()   // trail, from, to
        val cautions = LinkedHashMap<Long, JSONObject>()
        val seenPoi = LinkedHashSet<String>()
        val poiOrder = ArrayList<Poi>()
        val poiMile = HashMap<String, Double>()

        var cum = 0.0
        var prevName: String? = null
        var legStart = 0.0
        var prevTid: String? = null
        var lastLat = 0.0; var lastLon = 0.0
        var first = true

        for (e in eids) {
            val ed = g.edges[e]
            var seg = ed.pts
            if (!first) {
                val dFirst = hav(lastLat, lastLon, seg[0][0], seg[0][1])
                val dLast = hav(lastLat, lastLon, seg[seg.size - 1][0], seg[seg.size - 1][1])
                if (dFirst > dLast) seg = seg.reversed()
            }

            // ⭐ THE JUNCTION PAIR (Fred, 08-23). Snap CANNOT resolve a junction:
            // at the junction point BOTH trails are equally near, so whichever it
            // picks is arbitrary. And carrying straight through with no vertex
            // there cuts the corner. So at every trail switch emit TWO vertices,
            // literally, always:
            //   1. the junction CENTRE, carrying the INCOMING trail's lineId
            //   2. 10 ft along the OUTGOING trail, carrying the OUTGOING lineId
            // ⚠ No substituting a nearby real vertex -- a junction that is
            // sometimes a pair and sometimes not is harder to reason about.
            if (prevTid != null && ed.trailId != prevTid && !first) {
                val jk = cellKey(seg[0][0], seg[0][1])
                val ctr = g.jCentre[jk] ?: seg[0]
                verts.put(vertex(ctr[0], ctr[1], prevTid!!))
                val out10 = alongPolyline(seg, 10.0 / 5280.0)
                verts.put(vertex(out10[0], out10[1], ed.trailId))
            }

            for ((j, p) in seg.withIndex()) {
                if (!first && j == 0) continue
                verts.put(vertex(p[0], p[1], ed.trailId))
                first = false
            }
            lastLat = seg[seg.size - 1][0]; lastLon = seg[seg.size - 1][1]

            val lbl = ed.name ?: "unnamed track"
            if (lbl != prevName) {
                if (prevName != null) legs.add(Triple(prevName!!, legStart, cum))
                prevName = lbl; legStart = cum
            }
            cum += ed.miles

            for (k in listOf(ed.u, ed.v)) {
                for (p in poiAt[k] ?: emptyList()) {
                    if (seenPoi.add(p.name)) { poiOrder.add(p); poiMile[p.name] = cum }
                }
                val gp = g.gapFt[k] ?: 0.0
                if (gp > CAUTION_FT && k !in cautions) {
                    val n = g.nodes[k] ?: continue
                    cautions[k] = JSONObject()
                        .put("at_mi", round2(cum)).put("gap_ft", round1(gp))
                        .put("lat", n[0]).put("lon", n[1])
                }
            }
            prevTid = ed.trailId
        }
        if (prevName != null) legs.add(Triple(prevName!!, legStart, cum))

        val hoursLow = miles / req.mphHigh
        val hoursHigh = miles / req.mphLow
        val mix = poiOrder.groupingBy { it.fclass }.eachCount()
        val mixStr = mix.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.value} ${plural(it.key, it.value)}" }

        val notes = narrative(poiOrder, poiMile, legs, cautions.values.toList(),
            miles, hoursLow, hoursHigh, mix, req)

        val draft = JSONObject()
            .put("schemaVersion", 2)
            .put("name", draftName)
            .put("createdAt", nowIso())
            .put("updatedAt", nowIso())
            .put("method", "suggest")
            .put("vertices", verts)
            .put("notes", notes)

        RouteDraftStore.writeRawDraft(draftName, draft)

        return Suggestion(
            draftName, miles, hoursLow, hoursHigh, poiOrder.size, mixStr, draftName
        )
    }

    private fun vertex(lat: Double, lon: Double, trailId: String) = JSONObject()
        .put("lat", round6(lat)).put("lon", round6(lon))
        .put("lineId", trailId).put("lineType", "trail")
        .put("segmentIndex", -1).put("t", 0.0)
        // ⭐ snapped=true because these came OFF trail geometry. Marking them
        // otherwise invites the planner to re-snap points that are already right.
        .put("snapped", true)

    /** A point `wantMi` ALONG a polyline -- not on a straight bearing, so a
     *  sharp turn still lands on the trail. */
    private fun alongPolyline(pts: List<DoubleArray>, wantMi: Double): DoubleArray {
        var acc = 0.0
        for (i in 0 until pts.size - 1) {
            val d = hav(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
            if (acc + d >= wantMi) {
                val t = if (d > 0) (wantMi - acc) / d else 0.0
                return doubleArrayOf(
                    pts[i][0] + t * (pts[i + 1][0] - pts[i][0]),
                    pts[i][1] + t * (pts[i + 1][1] - pts[i][1])
                )
            }
            acc += d
        }
        return pts[pts.size - 1]
    }

    /**
     * The narrative a RIDER reads. Fred, 08-22: "nothing human readable here
     * about feature and poi on this route -- needs to point to what's in this
     * ride." Structured data stays underneath for the panel and for route_notes.
     */
    private fun narrative(
        pois: List<Poi>, poiMile: Map<String, Double>,
        legs: List<Triple<String, Double, Double>>,
        cautions: List<JSONObject>,
        miles: Double, hLow: Double, hHigh: Double,
        mix: Map<String, Int>, req: Request,
    ): JSONObject {
        val plainNames = mapOf(
            "peak" to "summit", "cliff" to "cliff", "volcano" to "volcanic cone",
            "spring" to "spring", "hamlet" to "settlement", "locality" to "locality"
        )
        val bits = mix.entries.sortedByDescending { it.value }
            .map { "${it.value} ${plural(plainNames[it.key] ?: it.key, it.value)}" }
        val headline = "%.0f miles, roughly %.0f to %.0f hours. %s."
            .format(miles, hLow, hHigh,
                if (bits.isEmpty()) "No named features on this one"
                else joinList(bits).replaceFirstChar { it.uppercase() } + " along the way")

        val seeArr = JSONArray()
        val stopArr = JSONArray()
        var i = 0
        while (i < pois.size) {
            val p = pois[i]
            val m = poiMile[p.name] ?: 0.0
            seeArr.put("${p.name} \u2014 ${plainNames[p.fclass] ?: p.fclass}")
            // group features that sit at the same spot
            val same = arrayListOf(p)
            while (i + 1 < pois.size && abs((poiMile[pois[i + 1].name] ?: 0.0) - m) < 0.15) {
                i++; same.add(pois[i]); seeArr.put(
                    "${pois[i].name} \u2014 ${plainNames[pois[i].fclass] ?: pois[i].fclass}")
            }
            stopArr.put(
                if (same.size == 1)
                    "mile %.0f  %s \u2014 %s".format(m, p.name, plainNames[p.fclass] ?: p.fclass)
                else
                    "mile %.0f  %s \u2014 together at the same spot"
                        .format(m, joinList(same.map { it.name }))
            )
            i++
        }

        val named = LinkedHashMap<String, Double>()
        var unnamedMi = 0.0
        for ((t, f, to) in legs) {
            if (t == "unnamed track") unnamedMi += (to - f)
            else named[t] = (named[t] ?: 0.0) + (to - f)
        }
        val namedTxt = joinList(named.entries.sortedByDescending { it.value }.take(5)
            .map { "%s (%.0f mi)".format(it.key, it.value) })
        val ground = if (named.isEmpty())
            "All %.0f miles are on tracks with no name in the data.".format(miles)
        else
            "Named trail: %s. The rest \u2014 %.0f of %.0f miles \u2014 is unnamed track."
                .format(namedTxt, unnamedMi, miles)

        val warn = JSONArray()
        if (cautions.isNotEmpty()) {
            val worst = cautions.maxOf { it.optDouble("gap_ft", 0.0) }
            warn.put(
                "%d junctions on this route are not confirmed in the map data. The two trails "
                    .format(cautions.size) +
                    "are drawn between 10 and %.0f feet apart, which usually means they meet "
                        .format(worst) +
                    "\u2014 but check the satellite view before relying on the turn."
            )
        }
        val pctUnnamed = 100.0 * unnamedMi / max(miles, 1.0)
        if (pctUnnamed > 50) warn.put(
            "%.0f%% of the distance is on tracks with no name in the data. They are real tracks; "
                .format(pctUnnamed) + "nobody has recorded what they are called."
        )

        val legsArr = JSONArray()
        for ((t, f, to) in legs) {
            if (to - f < 0.3) continue
            legsArr.put(JSONObject().put("trail", t)
                .put("from_mi", round2(f)).put("to_mi", round2(to))
                .put("miles", round2(to - f)))
        }
        val featArr = JSONArray()
        for (p in pois) featArr.put(JSONObject()
            .put("name", p.name).put("fclass", p.fclass)
            .put("lat", p.lat).put("lon", p.lon)
            .put("at_mi", round2(poiMile[p.name] ?: 0.0))
            .put("off_mi", round2(p.offMi)))
        val cautArr = JSONArray(); cautions.forEach { cautArr.put(it) }

        return JSONObject()
            .put("generated", nowIso())
            .put("summary", JSONObject()
                .put("total_miles", round2(miles))
                .put("est_hours", JSONArray().put(round2(hLow)).put(round2(hHigh)))
                .put("speed_mph", JSONArray().put(req.mphLow).put(req.mphHigh))
                .put("features", pois.size)
                .put("unconfirmed_junctions", cautions.size))
            .put("narrative", JSONObject()
                .put("headline", headline)
                .put("what_you_will_see", seeArr)
                .put("stops_in_order", stopArr)
                .put("ground_covered", ground)
                .put("before_you_go", warn))
            .put("legs", legsArr)
            .put("features", featArr)
            .put("cautions", cautArr)
    }

    // ── small helpers ─────────────────────────────────────────────────
    private fun plural(w: String, n: Int) = if (n > 1 && !w.endsWith("s")) "${w}s" else w
    private fun joinList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6
    private fun nowIso(): String =
        java.time.Instant.now().toString()
}
