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
        val radiusMi = (req.milesHigh / 2.0) * 0.75 + 5.0
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
            val base = if (p.fclass in VIEW) 3.0 else if (p.fclass in WATER) 2.0 else 1.0
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

    fun explore(
        spatialDb: SQLiteDatabase,
        req: Request,
        onProgress: ((Progress) -> Unit)? = null,
    ): List<Suggestion> {
        val rnd = Random(req.seed)
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

        onProgress?.invoke(Progress("Finding features"))
        val poiAt = loadPois(spatialDb, box, g)
        val poiNodes = poiAt.keys.toList()
        Log.i(TAG, "corridor: ${g.edges.size} edges, ${poiNodes.size} POI nodes")

        val anchor = nearestNode(req.anchorLat, req.anchorLon, g)
        val pen = HashMap<Int, Double>()
        val picks = ArrayList<Triple<List<Int>, Double, List<Long>>>()

        for (routeNo in 0 until req.maxRoutes) {
            onProgress?.invoke(Progress("Designing route ${routeNo + 1}"))

            // tables rebuilt each round -- the penalty changed the weights
            val terms = (listOf(anchor) + poiNodes).distinct()
            val dij = HashMap<Long, Dij>()
            for (t in terms) dij[t] = dijkstra(t, g, pen)

            var bestChosen: List<Long> = emptyList()
            var bestScore = 0.0
            var bestEids: List<Int> = emptyList()
            var bestMiles = 0.0

            repeat(TRIES) {
                // ⭐ EACH RESTART DRAWS ITS OWN TARGET -- otherwise the greedy
                // always runs to the ceiling and every route is the same length.
                val target = req.milesLow + rnd.nextDouble() * (req.milesHigh - req.milesLow)
                val chosen = ArrayList<Long>()
                var cur = anchor
                var mi = 0.0
                val left = HashSet(poiNodes)

                while (true) {
                    val cands = ArrayList<Triple<Double, Long, Double>>()
                    val dc = dij[cur] ?: break
                    for (k in left) {
                        val step = dc.dist[k] ?: continue
                        val back = dij[k]?.dist?.get(anchor) ?: continue
                        if (mi + step + back > target) continue
                        val gain = scoreSet(chosen + k, poiAt) - scoreSet(chosen, poiAt)
                        if (gain <= 0) continue
                        // ⚠ the ratio only ORDERS candidates -- distance never
                        // enters the score itself
                        cands.add(Triple(gain / max(step, 0.35), k, step))
                    }
                    if (cands.isEmpty()) break
                    cands.sortByDescending { it.first }
                    val r = rnd.nextDouble()
                    val idx = min((r * r * BEAM_TOP).toInt(), cands.size - 1)
                    val pick = cands[idx]
                    mi += pick.third; cur = pick.second
                    chosen.add(pick.second); left.remove(pick.second)
                }

                val home = dij[cur]?.dist?.get(anchor) ?: return@repeat
                mi += home
                if (mi < req.milesLow || mi > req.milesHigh) return@repeat

                val seq = listOf(anchor) + chosen + listOf(anchor)
                val eids = ArrayList<Int>()
                for (i in 0 until seq.size - 1) {
                    val d = dij[seq[i]] ?: return@repeat
                    val pe = pathEdges(d, seq[i], seq[i + 1]) ?: return@repeat
                    eids.addAll(pe)
                }
                val realMiles = eids.sumOf { g.edges[it].miles }
                if (realMiles < req.milesLow || realMiles > req.milesHigh) return@repeat

                val sc = scoreSet(chosen, poiAt)
                if (sc > bestScore) {
                    bestScore = sc; bestChosen = chosen; bestEids = eids; bestMiles = realMiles
                }
            }

            // ⭐ POSITIVE-SCORE FLOOR. A route with no features is not a
            // suggestion -- two good routes beat four padded ones.
            if (bestScore <= 0.0 || bestEids.isEmpty()) {
                Log.i(TAG, "no further route with a positive score after $routeNo")
                break
            }
            picks.add(Triple(bestEids, bestMiles, bestChosen))

            // ⭐ PENALISE, DO NOT FILTER -- the next route is the best one that
            // avoids this one.
            for (e in bestEids.toSet()) pen[e] = (pen[e] ?: 1.0) * PENALTY
        }

        if (picks.isEmpty()) {
            onProgress?.invoke(Progress("No rides found", "Try a wider distance range"))
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
