package com.geeksville.mesh.convoy

/**
 * LEADEST-2026-10-20 (Fred 10-20 11:44-12:11): THE LEAD'S ESTIMATED POSITION WHILE IT IS SILENT -- no track, an estimate of where the
 * lead cart is NOW, corrected when it is heard again.
 *   SILENT   = 2 missed broadcasts (Fred 12:13): nothing for 2.5 x its usual report gap (median of its last 5), at least 12 s.
 *   BLINK    = the lead cart blinks while silent (the screen); a grey "last heard" dot stays where it was last heard.
 *   ROUTE    = with the ride's route DISPLAYED (TrackGrounds.displayedRouteId -- the yardstick, Fred 11:57), the estimate moves ALONG it
 *              at the lead's AVERAGE SPEED OVER THE KNOWN MOVING STRETCHES OF THE RIDE (Fred 12:12: consecutive reports <= 60 s apart and
 *              faster than 3 mph), along the route from where its last reports place it: the reading of its last 5 reports that moves
 *              steadily FORWARD (an out-and-back road gives each report two places), else steadily backward, else in place.
 *              Without a route: in place.
 *   STOPS    = the lead was slower than 3 mph over its last 90 s when it went silent (a stop / breakdown is never shown driving away); the route's end;
 *              10 minutes of silence (it holds there, still blinking).
 *   HEARD    = the estimate ends; its ERROR (estimate vs the real report, metres) goes to the ride statistics (Fred 11:45).
 * Pure Kotlin (tested with the 10-20 Long Fast replay). Thread-safe: the receiving queue and the 1-second tick both call it.
 */
class LeadEstimate {
    /** CODE RULE 1 (none nullable): an Est exists only while the lead is silent. */
    data class Est(val leadId: String, val lat: Double, val lon: Double, val lastLat: Double, val lastLon: Double,
                   val lastHeardMs: Long, val silentMs: Long, val moving: Boolean, val speedMps: Double, val onRoute: Boolean)
    /** What one estimate was worth, when the lead was heard again. */
    data class Outcome(val tMs: Long, val silentMs: Long, val errorM: Double, val speedMps: Double, val onRoute: Boolean, val moving: Boolean)

    companion object {
        const val MIN_SILENT_MS = 12_000L   // Fred 12:13: 2 missed broadcasts; 2 x the 6 s moving interval at least
        const val MAX_MS = 600_000L          // 10 minutes: the estimate holds there
        const val STOP_MPS = 1.34            // 3 mph
        const val SPEED_WINDOW_MS = 90_000L
        const val SNAP_M = 50.0
    }
    private class R(val t: Long, val p: TrackPoint, val routeId: String?, val places: List<Double>)   // places = its places along the route (m)
    private val recent = ArrayList<R>()
    private var leadId = ""
    private var current: Est? = null   // CODE RULE 1: null = the lead is not silent (no estimate showing)

    @Synchronized fun reset() { recent.clear(); rideLog.clear(); current = null; leadId = "" }

    /** Fred 12:13: SILENT after 2 missed broadcasts -- 2.5 x its usual report gap (median of its last 5; the half is slack), at least
     *  MIN_SILENT_MS. Before two reports: 2 x MIN_SILENT_MS. */
    @Synchronized fun silentAfterMs(): Long {
        if (recent.size < 2) return 2 * MIN_SILENT_MS
        val gaps = recent.zipWithNext { a, b -> b.t - a.t }.takeLast(5).sorted()
        return maxOf(MIN_SILENT_MS, (2.5 * gaps[gaps.size / 2]).toLong())
    }

    /** A real report from the lead. Returns the outcome of the estimate it ends (CODE RULE 1: null = no estimate was showing). */
    @Synchronized fun onReport(id: String, p: TrackPoint, tMs: Long, route: Line?): Outcome? {
        if (id != leadId) { recent.clear(); current = null; leadId = id }   // a new lead: the ride's speed log is kept (same ride)
        val ended = current?.let { est ->
            val at = estimateAt(tMs, route)   // where the estimate had the lead at the moment it was heard
            Outcome(tMs, tMs - est.lastHeardMs, if (at != null) TrackGeo.metres(TrackPoint(at.lat, at.lon), p) else 0.0, est.speedMps, est.onRoute, est.moving)
        }
        current = null
        if (recent.isNotEmpty() && TrackGeo.metres(recent.last().p, p) < 0.05 && tMs - recent.last().t < 2_000L) return ended
        val pl = route?.places(p)?.filter { it.off <= SNAP_M }?.map { it.s } ?: emptyList()
        recent.add(R(tMs, p, route?.id, pl)); while (recent.size > 30) recent.removeAt(0)
        rideLog.add(R(tMs, p, null, emptyList())); if (rideLog.size > 20_000) rideLog.removeAt(0)
        return ended
    }

    /** Where the lead is along the route and which way it is going, from its last (up to) 5 reports: the reading that moves steadily
     *  FORWARD along the route (each step drivable in the time between -- an out-and-back road gives each report two places), else
     *  steadily BACKWARD. Returns (metres along, +1 / -1). CODE RULE 1: null = no steady reading -> the estimate blinks in place. */
    private fun routeFix(route: Line): Pair<Double, Double>? {
        val rs = recent.takeLast(5).filter { it.routeId == route.id && it.places.isNotEmpty() }
        if (rs.isEmpty() || rs.last() !== recent.last()) return null           // the last report is off the route
        for (dir in listOf(1.0, -1.0)) {
            var ends: List<Double> = rs[0].places
            var ok = true
            for (k in 1 until rs.size) {
                val reach = 22.4 * ((rs[k].t - rs[k - 1].t) / 1000.0) + 60.0   // 50 mph
                val next = rs[k].places.filter { s -> ends.any { e -> val step = (s - e) * dir; step >= -5.0 && step <= reach } }
                if (next.isEmpty()) { ok = false; break }
                ends = next
            }
            if (ok) return Pair(if (dir > 0) ends.minOrNull()!! else ends.maxOrNull()!!, dir)
        }
        return null
    }

    /** Fred 12:12: the lead's average speed over the KNOWN MOVING STRETCHES of the ride -- consecutive reports at most 60 s apart and
     *  faster than 3 mph (m/s). The ride, not the last minute: gaps and stops do not drag it down. */
    private fun rideSpeed(): Double {
        var d = 0.0; var t = 0.0
        for (i in 1 until rideLog.size) {
            val dt = (rideLog[i].t - rideLog[i - 1].t) / 1000.0
            if (dt <= 0.0 || dt > 60.0) continue
            val m = TrackGeo.metres(rideLog[i - 1].p, rideLog[i].p)
            if (m / dt < STOP_MPS) continue
            d += m; t += dt
        }
        return if (t > 0.0) d / t else 0.0
    }
    private val rideLog = ArrayList<R>()   // every report of this ride (the moving-speed average)

    /** The lead's average speed over its last 90 s of reports (m/s) -- decides whether it was STOPPED when it went silent. */
    private fun speed(): Double {
        if (recent.size < 2) return 0.0
        val end = recent.last().t
        val win = recent.filter { it.t >= end - SPEED_WINDOW_MS }.let { if (it.size >= 2) it else recent.takeLast(2) }
        val span = (win.last().t - win.first().t) / 1000.0
        if (span < 1.0) return 0.0
        var d = 0.0; for (i in 1 until win.size) d += TrackGeo.metres(win[i - 1].p, win[i].p)
        return d / span
    }

    private fun pointAtS(l: Line, s0: Double): TrackPoint {
        val s = s0.coerceIn(0.0, l.cum.last())
        var i = 1; while (i < l.cum.size - 1 && l.cum[i] < s) i++
        val seg = l.cum[i] - l.cum[i - 1]; val f = if (seg <= 0.0) 0.0 else (s - l.cum[i - 1]) / seg
        val a = l.pts[i - 1]; val b = l.pts[i]
        return TrackPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /** CODE RULE 1: null = no report yet (nothing to estimate from). */
    private fun estimateAt(nowMs: Long, route: Line?): Est? {
        val last = recent.lastOrNull() ?: return null
        val silent = nowMs - last.t
        val moving = speed() >= STOP_MPS          // was it moving when it went silent (its last 90 s)
        val v = if (moving) rideSpeed() else 0.0   // how fast to move the estimate: the ride's moving average (Fred 12:12)
        val fix = if (route != null) routeFix(route) else null   // CODE RULE 1: null = not steadily on the displayed route
        val onRoute = fix != null
        val p = if (moving && fix != null && route != null)
            pointAtS(route, fix.first + fix.second * v * (minOf(silent, MAX_MS) / 1000.0)) else last.p
        return Est(leadId, p.lat, p.lon, last.p.lat, last.p.lon, last.t, silent, moving, v, onRoute)
    }

    /** Every second: the estimate to show, or none. CODE RULE 1: null = the lead is not silent (or there is no lead). */
    @Synchronized fun tick(nowMs: Long, route: Line?): Est? {
        val last = recent.lastOrNull() ?: return null.also { current = null }
        if (nowMs - last.t < silentAfterMs()) { current = null; return null }
        current = estimateAt(nowMs, route); return current
    }
}
