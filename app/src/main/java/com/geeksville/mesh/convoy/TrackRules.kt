package com.geeksville.mesh.convoy

/**
 * TRACKRULES-2026-10-19 (2.7b -- Fred 10-19 08:12-08:57). THE TRACK RULES, WRITTEN ONCE. Replaces TrackPipeline.kt (LEADTRACKBOARD-2026-10-18).
 * Model + proof: track_model_full_2026-10-19.py (yesterday's points; every case below is a case there).
 *
 *   point in -> TrackWriter (gap test, fill, junction wait: the point is WRITTEN = settled)
 *            -> ScreenWriter (written -> screen steps; N = 1 now, defined so it can be split)
 *            -> the map draws the steps only (the screen never shows anything that was not written)
 *
 * R1 every real point is posted -- no location test (the 10-18 hold and the 50 m trim are gone).
 * R2 a GAP = more than 4 s AND more than 5 m since the last written point -> filled; otherwise a straight join (the chip's 3 s points).
 * R3 fill order: the ONE displayed route -> if NO route is displayed, a saved route both points lie on -> the known trails underneath,
 *    displayed or not (no direction; nearest the previous point) -> a straight line.
 *    ROUTEBOTH-2026-10-19 (Fred 10-19 15:48): a route is followed in EITHER direction -- along its arrows first when that fits, else the
 *    shortest way (was: forward only -- a route driven against its arrows was refused).
 *    (the straight line is replaced later by the lead's polyline -- stage 2).
 * R4 snap 50 m; the line is drawn ON the route / trail. A fill longer than 3 x straight + 60 m is not believed (never round a whole loop).
 * R5 WAIT: the track goes through an intersection only when the next point, on the far side, is known. A held point is written
 *    when that point arrives, or at STOP / reset on its nearest line.
 * MYCARTRAW-2026-10-19 (Fred 10-19 12:06): the route / trail rules apply to the LEAD CART'S TRACK ONLY for now -- my cart is written raw
 *    (TrackWriter fill = false: every point as delivered, straight lines, no gap fill, no snapping, no WAIT).
 * R6 two routines, the same rules: LeadCartProcess (radio reports; correction = the lead's polyline) and MyCartProcess
 *    (the chip, or the radio when there is no chip; the written points are the GPX).
 */
data class TrackPoint(val lat: Double, val lon: Double)

/** One written point. real = a point that arrived (false = a road point added between two). */
data class WPoint(val lat: Double, val lon: Double, val tMs: Long, val alt: Double, val real: Boolean, val rule: String)

/** A route or trail as the rules see it: points in order, with a local flat frame and the distance along it. */
class Line(val id: String, val pts: List<TrackPoint>) {
    private val lat0 = pts.firstOrNull()?.lat ?: 0.0
    private val lon0 = pts.firstOrNull()?.lon ?: 0.0
    private val kx = Math.cos(Math.toRadians(lat0)) * 111_320.0
    private val ky = 110_574.0
    val xs = DoubleArray(pts.size) { (pts[it].lon - lon0) * kx }
    val ys = DoubleArray(pts.size) { (pts[it].lat - lat0) * ky }
    val cum = DoubleArray(pts.size).also { c -> for (i in 1 until pts.size) c[i] = c[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) }
    fun x(p: TrackPoint) = (p.lon - lon0) * kx
    fun y(p: TrackPoint) = (p.lat - lat0) * ky

    /** A place on the line: off = metres from the point, i = segment, t = 0..1 along it, s = metres along the line. */
    data class Place(val off: Double, val i: Int, val t: Double, val s: Double)

    /** One place per pass of the line near p (the nearest point of each pass). */
    fun places(p: TrackPoint): List<Place> {
        if (pts.size < 2) return emptyList()
        val px = x(p); val py = y(p)
        val all = ArrayList<Place>(pts.size - 1)
        for (i in 0 until pts.size - 1) {
            val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / l2).coerceIn(0.0, 1.0)
            all.add(Place(Math.hypot(px - xs[i] - t * dx, py - ys[i] - t * dy), i, t, cum[i] + t * Math.sqrt(l2)))
        }
        val out = ArrayList<Place>()
        for (k in all.indices) {
            val prev = if (k > 0) all[k - 1].off else Double.MAX_VALUE
            val next = if (k + 1 < all.size) all[k + 1].off else Double.MAX_VALUE
            val c = all[k].off
            if ((c <= prev && c < next) || (c < prev && c <= next)) out.add(all[k])
        }
        return if (out.isEmpty()) listOf(all.minByOrNull { it.off }!!) else out
    }

    fun pointAt(pl: Place): TrackPoint {
        val a = pts[pl.i]; val b = pts[pl.i + 1]
        return TrackPoint(a.lat + pl.t * (b.lat - a.lat), a.lon + pl.t * (b.lon - a.lon))
    }

    /** The line's own points from place a to place b (forward, or back when b is behind a), both ends included. */
    fun along(a: Place, b: Place): List<TrackPoint> {
        val out = ArrayList<TrackPoint>(); out.add(pointAt(a))
        if (b.s >= a.s) for (k in a.i + 1..b.i) out.add(pts[k]) else for (k in a.i downTo b.i + 1) out.add(pts[k])
        out.add(pointAt(b))
        val clean = ArrayList<TrackPoint>(); clean.add(out[0])
        for (q in out.drop(1)) if (TrackGeo.metres(q, clean.last()) > 0.05) clean.add(q)
        return clean
    }
}

object TrackGeo {
    fun metres(a: TrackPoint, b: TrackPoint): Double {
        val k = Math.cos(Math.toRadians((a.lat + b.lat) / 2)) * 111_320.0
        return Math.hypot((b.lon - a.lon) * k, (b.lat - a.lat) * 110_574.0)
    }
    fun length(pts: List<TrackPoint>): Double { var s = 0.0; for (i in 1 until pts.size) s += metres(pts[i - 1], pts[i]); return s }
    /** Distance in metres from p to the segment a-b (local flat projection -- fine at segment length). */
    fun metresToSegment(p: TrackPoint, a: TrackPoint, b: TrackPoint): Double {
        val k = Math.cos(Math.toRadians(p.lat)) * 111_320.0
        val ax = (a.lon - p.lon) * k; val ay = (a.lat - p.lat) * 110_574.0
        val bx = (b.lon - p.lon) * k; val by = (b.lat - p.lat) * 110_574.0
        val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return Math.hypot(ax + t * dx, ay + t * dy)
    }
}

/** Where the routes and trails come from. The app reads the trail database (TrackGrounds.db); a test passes its own. */
interface LineSource {
    /** CODE RULE 1: null = no such route in the database (deleted since it was displayed). */
    fun route(id: String): Line?
    fun routesNear(s: Double, w: Double, n: Double, e: Double): List<Line>
    /** The known trails, displayed or not -- NOT the Map Keys filter (Fred 10-19 08:39). */
    fun trailsNear(s: Double, w: Double, n: Double, e: Double): List<Line>
}

/** What the tracks are laid on -- shared by both routines (the lead's in the ViewModel, mine in the recorder service). */
object TrackGrounds {
    /** The ONE route on the screen. CODE RULE 1: null = there is not exactly one displayed route (routes off, all on, or several
     *  selected) -> R3 uses any saved route that both points lie on. Set by ConvoyScreen from the Routes display state. */
    @Volatile var displayedRouteId: String? = null
    @Volatile var source: LineSource = DbLineSource

    object DbLineSource : LineSource {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Line>>()
        private fun parse(id: String, wkt: String): List<Line> = cache.getOrPut(id + "\u0000" + wkt.length) {
            if (cache.size > 2000) cache.clear()
            val body = when {
                wkt.startsWith("MULTILINESTRING(") -> wkt.removePrefix("MULTILINESTRING(").removeSuffix(")")
                wkt.startsWith("LINESTRING(") -> "(" + wkt.removePrefix("LINESTRING(").removeSuffix(")") + ")"
                else -> return@getOrPut emptyList()
            }
            body.split("),(").mapIndexedNotNull { k, part ->
                val pts = part.trim('(', ')').split(",").mapNotNull { c ->
                    val xy = c.trim().split(" ")
                    val lon = xy.getOrNull(0)?.toDoubleOrNull(); val lat = xy.getOrNull(1)?.toDoubleOrNull()
                    if (lon == null || lat == null) null else TrackPoint(lat, lon)
                }
                if (pts.size < 2) null else Line(if (k == 0) id else "$id#$k", pts)
            }
        }
        private fun query(sql: String, args: Array<String>): List<Line> = try {
            val db = SpatialDbManager.getSpatialDb()
            if (db == null) emptyList() else db.rawQuery(sql, args).use { c ->
                val out = ArrayList<Line>()
                while (c.moveToNext()) { val id = c.getString(0) ?: continue; val g = c.getString(1) ?: continue; out += parse(id, g) }
                out
            }
        } catch (e: Exception) { android.util.Log.w("TRACKRULES", "TRACKRULES-2026-10-19 lookup failed: " + e.message); emptyList() }
        private fun box(s: Double, w: Double, n: Double, e: Double) = arrayOf(s.toString(), n.toString(), w.toString(), e.toString())
        override fun route(id: String): Line? = query("SELECT route_id, geometry FROM routes WHERE route_id = ?", arrayOf(id)).firstOrNull()
        override fun routesNear(s: Double, w: Double, n: Double, e: Double) =
            query("SELECT route_id, geometry FROM routes WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT 50", box(s, w, n, e))
        override fun trailsNear(s: Double, w: Double, n: Double, e: Double) =
            query("SELECT trail_id, geometry FROM trails WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT 200", box(s, w, n, e))
    }
}

/** R3 + R4: one gap -> the points between. One per routine (it remembers how far along a route he is, and which trail he is on). */
class TrackPath {
    companion object {
        const val SNAP_M = 50.0; const val NEAR_M = 15.0; const val AMBIG_M = 12.0; const val DETOUR = 3.0; const val DETOUR_M = 60.0
    }
    /** A place b could be: on which line, where. */
    data class Cand(val line: Line, val place: Line.Place)
    /** points = from a's place to b's place (a STRAIGHT fill is just a, b). alts = b's other places on a different line (R5). */
    data class Fill(val points: List<TrackPoint>, val rule: String, val alts: List<Cand>)

    private val routeS = HashMap<String, Double>()      // where along each route he was last (ROUTEBOTH-2026-10-19: either direction)
    private var lastRoute: String? = null                // CODE RULE 1: null = not on a saved route yet
    private var trailPrev: Pair<String, Double>? = null  // CODE RULE 1: null = not on a trail yet (the first trail place is the nearest)

    fun reset() { routeS.clear(); lastRoute = null; trailPrev = null }

    private fun bbox(a: TrackPoint, b: TrackPoint): DoubleArray {
        val pad = 0.0006   // ~60 m
        return doubleArrayOf(minOf(a.lat, b.lat) - pad, minOf(a.lon, b.lon) - pad * 1.4, maxOf(a.lat, b.lat) + pad, maxOf(a.lon, b.lon) + pad * 1.4)
    }

    private fun route(a: TrackPoint, b: TrackPoint): Triple<Line, Line.Place, Line.Place>? {
        val src = TrackGrounds.source; val shown = TrackGrounds.displayedRouteId
        val order: List<Line> = if (shown != null) listOfNotNull(src.route(shown)) else {
            val bb = bbox(a, b); val near = src.routesNear(bb[0], bb[1], bb[2], bb[3])
            near.sortedBy { it.id != lastRoute }                  // a saved route he is already on goes first
        }
        val straight = TrackGeo.metres(a, b)
        for (r in order) {
            // ROUTEBOTH-2026-10-19: EITHER direction. Every near-equal place of a paired with every near-equal place of b (a route that uses the
            // same road twice -- out and back -- has two places for one point); pairs past the detour limit dropped; then ALONG the
            // arrows first when one fits, then the shortest way, then a's place nearest the previous one.
            val prev = routeS[r.id]   // CODE RULE 1: null = not on this route yet (no previous place to prefer)
            val ca = r.places(a).filter { it.off <= SNAP_M }
            if (ca.isEmpty()) continue
            val bestA = ca.minOf { it.off }
            val nearA = ca.filter { it.off <= bestA + NEAR_M }
            val cb = r.places(b).filter { it.off <= SNAP_M }
            if (cb.isEmpty()) continue
            val bestB = cb.minOf { it.off }
            val nearB = cb.filter { it.off <= bestB + NEAR_M }
            val lim = DETOUR * straight + DETOUR_M
            val pairs = nearA.flatMap { qa -> nearB.map { qb -> Pair(qa, qb) } }.filter { Math.abs(it.second.s - it.first.s) <= lim }
            val best = pairs.minWithOrNull(compareBy<Pair<Line.Place, Line.Place>>(
                { it.second.s < it.first.s },                                  // against the arrows only when nothing along them fits
                { Math.abs(it.second.s - it.first.s) },                        // then the shortest way
                { if (prev != null) Math.abs(it.first.s - prev) else 0.0 }))   // then a's place nearest the previous one
                ?: continue
            return Triple(r, best.first, best.second)
        }
        return null
    }

    private fun trailPlaces(trails: List<Line>, p: TrackPoint): List<Pair<Double, Cand>> =
        trails.flatMap { t -> t.places(p).filter { it.off <= SNAP_M }.map { Pair(it.off, Cand(t, it)) } }.sortedBy { it.first }

    /** Where two trails meet (a vertex of l1 within 10 m of l2). CODE RULE 1: null = they do not meet. */
    private fun junction(l1: Line, l2: Line): Pair<Line.Place, Line.Place>? {
        var best: Triple<Double, Line.Place, Line.Place>? = null
        for (i in l1.pts.indices) {
            for (c in l2.places(l1.pts[i])) {
                if (c.off <= 10 && (best == null || c.off < best.first)) {
                    val seg = minOf(i, l1.pts.size - 2); val t = if (i < l1.pts.size - 1) 0.0 else 1.0
                    best = Triple(c.off, Line.Place(0.0, seg, t, l1.cum[i]), c)
                }
            }
        }
        return best?.let { Pair(it.second, it.third) }
    }

    private fun trail(a: TrackPoint, b: TrackPoint, bChoice: Cand?): Fill? {
        val bb = bbox(a, b); val trails = TrackGrounds.source.trailsNear(bb[0], bb[1], bb[2], bb[3])
        if (trails.isEmpty()) return null
        val paAll = trailPlaces(trails, a); val pbAll = trailPlaces(trails, b)
        if (paAll.isEmpty() || pbAll.isEmpty()) return null
        var nearA = paAll.filter { it.first <= paAll[0].first + NEAR_M }
        trailPrev?.let { (tid, s) ->
            nearA = nearA.sortedWith(compareBy<Pair<Double, Cand>>({ it.second.line.id != tid },
                { if (it.second.line.id == tid) Math.abs(it.second.place.s - s) else it.first }))
        }
        val ca = nearA[0].second
        val nearB: List<Pair<Double, Cand>> = if (bChoice != null) listOf(Pair(bChoice.place.off, bChoice))
            else pbAll.filter { it.first <= pbAll[0].first + NEAR_M }
        val straight = TrackGeo.metres(a, b)
        var best: Triple<Double, Cand, List<TrackPoint>>? = null
        for ((_, cb) in nearB) {
            val path: List<TrackPoint> = if (cb.line === ca.line) ca.line.along(ca.place, cb.place) else {
                val j = junction(ca.line, cb.line) ?: continue
                ca.line.along(ca.place, j.first) + cb.line.along(j.second, cb.place).drop(1)
            }
            val len = TrackGeo.length(path)
            if (len > DETOUR * straight + DETOUR_M) continue
            if (best == null || len < best.first) best = Triple(len, cb, path)
        }
        if (best == null) return null
        val chosen = best.second; val at = chosen.line.pointAt(chosen.place)
        // R5: b's other near-equal places on a DIFFERENT line, apart on the ground = the intersection is not resolved yet
        val alts = nearB.map { it.second }.filter { it.line !== chosen.line && TrackGeo.metres(it.line.pointAt(it.place), at) > AMBIG_M }
        return Fill(best.third, "TRAIL:" + chosen.line.id, alts).also { lastTrailChoice = chosen }
    }
    private var lastTrailChoice: Cand? = null

    /** commit = this fill is being written (the place on the trail is kept for the next gap). */
    fun fill(a: TrackPoint, b: TrackPoint, bChoice: Cand? = null, commit: Boolean = false): Fill {
        route(a, b)?.let { (r, pa, pb) ->
            routeS[r.id] = pb.s; lastRoute = r.id
            return Fill(r.along(pa, pb), "ROUTE:" + r.id, emptyList())
        }
        trail(a, b, bChoice)?.let { f ->
            if (f.alts.isEmpty() || bChoice != null || commit) lastTrailChoice?.let { trailPrev = Pair(it.line.id, it.place.s) }
            return f
        }
        return Fill(listOf(a, b), "STRAIGHT", emptyList())
    }

    /** Try a fill without keeping anything it learned (used to compare the branches of a held point). */
    fun <T> trial(block: () -> T): T {
        val s = HashMap(routeS); val lr = lastRoute; val tp = trailPrev; val lc = lastTrailChoice
        try { return block() } finally { routeS.clear(); routeS.putAll(s); lastRoute = lr; trailPrev = tp; lastTrailChoice = lc }
    }
}

/** R1 + R2 + R5: points in, WRITTEN points out. One per routine. Not thread-safe: each routine calls it from one thread. */
class TrackWriter(private val name: String, private val fill: Boolean) {   // MYCARTRAW-2026-10-19: fill false = raw (no route / trail rules)
    companion object { const val GAP_MS = 4_000L; const val GAP_M = 5.0 }
    sealed class Update {
        data class Append(val points: List<WPoint>) : Update()
        data class Replace(val t0: Long, val t1: Long, val points: List<WPoint>) : Update()
    }

    private val path = TrackPath()
    val pts = ArrayList<WPoint>()
    private data class Held(val p: TrackPoint, val tMs: Long, val alt: Double, val alts: List<TrackPath.Cand>)
    private var held: Held? = null   // CODE RULE 1: null = no point waiting at an intersection

    fun reset() { pts.clear(); held = null; path.reset() }

    private fun write(seg: List<TrackPoint>, t1: Long, alt1: Double, rule: String): List<WPoint> {
        val last = pts.last(); val a = TrackPoint(last.lat, last.lon)
        val total = TrackGeo.length(listOf(a) + seg); var run = 0.0; var prev = a
        val out = ArrayList<WPoint>()
        for ((k, q) in seg.withIndex()) {
            run += TrackGeo.metres(prev, q); prev = q
            val isLast = k == seg.size - 1
            val f = if (isLast || total < 0.05) 1.0 else run / total
            val w = WPoint(q.lat, q.lon, last.tMs + ((t1 - last.tMs) * f).toLong(), last.alt + (alt1 - last.alt) * f, isLast, rule)
            pts.add(w); out.add(w)
        }
        return out
    }
    private fun placed(f: TrackPath.Fill, p: TrackPoint): List<TrackPoint> =
        if (f.rule == "STRAIGHT") listOf(p) else f.points.drop(1).ifEmpty { listOf(f.points.last()) }

    fun onPoint(p: TrackPoint, tMs: Long, alt: Double): List<Update> {
        val ups = ArrayList<Update>()
        if (pts.isEmpty()) { val w = WPoint(p.lat, p.lon, tMs, alt, true, "FIRST"); pts.add(w); ups.add(Update.Append(listOf(w))); return ups }
        held?.let { h -> held = null; ups.addAll(settleHeld(h, p)) }
        val last = pts.last(); val a = TrackPoint(last.lat, last.lon)
        val d = TrackGeo.metres(a, p)
        if (!fill || tMs - last.tMs <= GAP_MS || d <= GAP_M) {             // R2: no gap -> straight join (MYCARTRAW-2026-10-19: always, when fill is off)
            if (d < 0.05) return ups
            ups.add(Update.Append(write(listOf(p), tMs, alt, "DIRECT"))); return ups
        }
        val f = path.fill(a, p)
        if (f.alts.isNotEmpty()) {                                          // R5: at an intersection -> hold, write nothing yet
            held = Held(p, tMs, alt, f.alts)
            android.util.Log.d("TRACKRULES", "TRACKRULES-2026-10-19 $name WAIT at an intersection (" + f.rule + ")")
            return ups
        }
        val seg = placed(f, p)
        if (TrackGeo.length(listOf(a) + seg) < 0.05) return ups             // stationary on the line: nothing moved, nothing to draw
        ups.add(Update.Append(write(seg, tMs, alt, f.rule)))
        return ups
    }

    /** The point on the far side is known: the held point goes on the branch that makes last -> held -> next shortest. */
    private fun settleHeld(h: Held, next: TrackPoint): List<Update> {
        val last = pts.last(); val a = TrackPoint(last.lat, last.lon)
        var best: Pair<Double, TrackPath.Cand?>? = null
        for (choice in listOf<TrackPath.Cand?>(null) + h.alts) {
            val len = path.trial {
                val s1 = path.fill(a, h.p, choice); val s2 = path.fill(s1.points.last(), next)
                TrackGeo.length(s1.points) + TrackGeo.length(s2.points)
            }
            if (best == null || len < best.first) best = Pair(len, choice)
        }
        val f = path.fill(a, h.p, best!!.second, commit = true)
        return listOf(Update.Append(write(placed(f, h.p), h.tMs, h.alt, f.rule)))
    }

    /** STOP / WAIT with no next point: the held point is written on its nearest place. */
    fun settle(): List<Update> {
        val h = held ?: return emptyList(); held = null
        val last = pts.last(); val a = TrackPoint(last.lat, last.lon)
        val f = path.fill(a, h.p, null, commit = true)
        return listOf(Update.Append(write(placed(f, h.p), h.tMs, h.alt, f.rule)))
    }

    /** Stage 2 (the lead's polyline): its points replace the written points inside [t0, t1]. */
    fun replaceWindow(t0: Long, t1: Long, polyline: List<WPoint>): List<Update> {
        val keep = pts.filter { it.tMs < t0 } + polyline + pts.filter { it.tMs > t1 }
        pts.clear(); pts.addAll(keep)
        return listOf(Update.Replace(t0, t1, polyline))
    }
}

/** Written updates -> what the screen shows. N = 1: one step per written update, due now -- each segment can be tested by itself.
 *  Later N = the measured gap between reports, a segment split by distance ALONG it, one step a second (the model has it: I N=6).
 *  The screen line is always the written track (R6). */
/**
 * THE UPDATE FREQUENCY (Fred 10-19 08:51 / 09:10): screen updates per written segment. Shown in each process; DEFAULT 1 = the WHOLE
 * written segment is ONE screen update, due now (each segment can be tested by itself). Only 1 is implemented.
 * WHAT A FREQUENCY ABOVE 1 REQUIRES (not built -- noted so we know the implementation):
 *   1. steps(): split the segment into N pieces by distance ALONG the written path (not the straight line) -- the model does it (I, N=6).
 *   2. N = the measured gap between this cart's reports in seconds (median of its last 5), not the configured broadcast interval.
 *   3. a timer that delivers each step when due (one a second) -- the map draws a step only when it is due.
 *   4. a new update FINISHES the pending steps at once, then starts its own (no step is ever dropped; the line always ends on the cart).
 *   5. a late report: the cart waits at the last real point (no step ever goes past what was written).
 *   6. in-between ends are display-only: the screen line is the written track again when the segment completes (model check I).
 *   7. a switch in the GRP Awareness settings: "Smooth cart movement" YES / NO (Fred 10-19 09:11). NO = 1 (as now); YES = item 2.
 */
class ScreenWriter(val updateFrequency: Int = 1) {
    data class Step(val dueMs: Long, val cart: WPoint?, val points: List<WPoint>)
    val line = ArrayList<WPoint>()
    fun reset() { line.clear() }

    /** A written update -> the screen steps it becomes. updateFrequency 1: one step, the whole segment, due now.
     *  (Above 1 is not built -- see the note above; until it is, every value gives the one step.) */
    fun steps(nowMs: Long, u: TrackWriter.Update): List<Step> = when (u) {
        is TrackWriter.Update.Append -> listOf(Step(nowMs, u.points.lastOrNull(), u.points))
        is TrackWriter.Update.Replace -> listOf(Step(nowMs, null, u.points))
    }

    fun onUpdate(nowMs: Long, u: TrackWriter.Update): List<Step> {
        val st = steps(nowMs, u)
        when (u) {
            is TrackWriter.Update.Append -> for (w in u.points) if (line.isEmpty() || line.last().lat != w.lat || line.last().lon != w.lon) line.add(w)
            is TrackWriter.Update.Replace -> {
                val keep = line.filter { it.tMs < u.t0 } + u.points + line.filter { it.tMs > u.t1 }
                line.clear(); line.addAll(keep)
            }
        }
        return st
    }
}

/** THE LEAD CART: his radio reports (any lead -- a lead change continues the ONE track). Correction: his polyline (stage 2). */
class LeadCartProcess(val updateFrequency: Int = 1) {
    private val track = TrackWriter("lead", fill = true)    // MYCARTRAW-2026-10-19: the lead track keeps the route / trail rules
    private val screen = ScreenWriter(updateFrequency)
    private var lead = ""
    fun reset() { track.reset(); screen.reset(); lead = "" }
    fun onLeadReport(leadId: String, p: TrackPoint, rxMs: Long): Boolean {
        lead = leadId
        val ups = track.onPoint(p, rxMs, 0.0)
        for (u in ups) screen.onUpdate(rxMs, u)
        return ups.isNotEmpty()
    }
    fun onPolyline(nowMs: Long, t0: Long, t1: Long, poly: List<WPoint>) { for (u in track.replaceWindow(t0, t1, poly)) screen.onUpdate(nowMs, u) }
    /** Off-track: metres from p to the lead track. CODE RULE 1: null = no track yet (nothing to be off). */
    fun metresFromTrack(p: TrackPoint): Double? {
        val l = screen.line
        if (l.isEmpty()) return null
        if (l.size == 1) return TrackGeo.metres(p, TrackPoint(l[0].lat, l[0].lon))
        var best = Double.MAX_VALUE
        for (i in 1 until l.size) best = minOf(best, TrackGeo.metresToSegment(p, TrackPoint(l[i - 1].lat, l[i - 1].lon), TrackPoint(l[i].lat, l[i].lon)))
        return best
    }
    /** The whole lead track (no trim -- R1). */
    fun segments(): List<ConvoyEngine.LeadTrackSegment> {
        val l = screen.line; val out = ArrayList<ConvoyEngine.LeadTrackSegment>()
        for (i in 1 until l.size) out.add(ConvoyEngine.LeadTrackSegment(startLat = l[i - 1].lat, startLon = l[i - 1].lon, endLat = l[i].lat, endLon = l[i].lon, color = "#000000", nodeId = lead))
        return out
    }
}

/** MY CART: the chip every 3 s, or my radio when the tablet has no chip. The written points are the GPX AND the screen (R6). */
class MyCartProcess(val updateFrequency: Int = 1) {
    private val track = TrackWriter("my", fill = false)     // MYCARTRAW-2026-10-19: my cart raw for now (Fred 10-19 12:06)
    private val screen = ScreenWriter(updateFrequency)
    fun reset() { track.reset(); screen.reset() }
    /** Returns the points WRITTEN by this position (empty while it waits at an intersection): the recorder writes exactly these. */
    fun onPosition(p: TrackPoint, tMs: Long, alt: Double): List<WPoint> = written(tMs, track.onPoint(p, tMs, alt))
    /** STOP: a held point is written on its nearest place. */
    fun stop(nowMs: Long): List<WPoint> = written(nowMs, track.settle())
    private fun written(nowMs: Long, ups: List<TrackWriter.Update>): List<WPoint> {
        val out = ArrayList<WPoint>()
        for (u in ups) { screen.onUpdate(nowMs, u); if (u is TrackWriter.Update.Append) out.addAll(u.points) }
        return out
    }
}
