package com.geeksville.mesh.convoy

/**
 * LEADTRACKBOARD-2026-10-18 (2.7b, the tick replacement, build 1 -- Fred 10-18 07:36-08:49).
 * The message-to-map process, boxes 4-6 (Truth, the Recorder and the Map stay in ConvoyViewModel / ConvoyScreen):
 *   TrackPath  -- last point -> new point. ROUTE and TRAIL are PLACEHOLDERS ("not applicable", logged once); STRAIGHT works.
 *   CartMotion -- a real report -> the steps the map processes. N = 1 in this build: the full message, due now.
 *   LeadTrack  -- the lead's REAL points only; ONE track whoever leads; a new lead is held until he passes the last lead point
 *                 (within 50 m -- the track continues from his NEXT report, no backwards lines); nothing is added while the lead is
 *                 silent; off-track per cart report; shown per tablet = the part AHEAD of my cart (50 m trim on the real points).
 * Plain Kotlin apart from one log line. Model + tests: process_model_2026-10-18.py.
 */
data class TrackPoint(val lat: Double, val lon: Double)

object TrackPath {
    enum class Rule { ROUTE, TRAIL, STRAIGHT, WAIT }
    data class Path(val points: List<TrackPoint>, val rule: Rule)
    @Volatile private var placeholderLogged = false

    fun path(from: TrackPoint, to: TrackPoint): Path {
        // ROUTE (both points within 50 m of the loaded route -> along the route) and TRAIL (nearest trail; junction -> WAIT) are
        // filled in after the tick (builds 3 and 4). Until then they answer "not applicable".
        if (!placeholderLogged) {
            placeholderLogged = true
            android.util.Log.i("TRACKPATH", "LEADTRACKBOARD-2026-10-18: ROUTE n/a (placeholder), TRAIL n/a (placeholder) -> STRAIGHT")
        }
        return Path(listOf(from, to), Rule.STRAIGHT)
    }

    fun metres(a: TrackPoint, b: TrackPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
    }

    /** Distance in metres from p to the segment a-b (local flat projection -- fine at segment length). */
    fun metresToSegment(p: TrackPoint, a: TrackPoint, b: TrackPoint): Double {
        val k = Math.cos(Math.toRadians(p.lat)) * 111_320.0
        val ax = (a.lon - p.lon) * k; val ay = (a.lat - p.lat) * 110_574.0
        val bx = (b.lon - p.lon) * k; val by = (b.lat - p.lat) * 110_574.0
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return Math.sqrt(cx * cx + cy * cy)
    }
}

object CartMotion {
    data class Step(val cartId: String, val point: TrackPoint, val dueMs: Long, val real: Boolean)

    /** N = 1 (build 1): the full message, due now. Later N = the cart's normal report interval (median of its last 5 gaps), each step
     *  due one second apart; a new real report cancels that cart's pending steps. In-between steps are display-only. */
    fun steps(cartId: String, to: TrackPoint, nowMs: Long): List<Step> = listOf(Step(cartId, to, nowMs, true))
}

class LeadTrack {
    companion object { const val PASS_M = 50.0 }
    private val points = ArrayList<TrackPoint>()
    private val real = ArrayList<Boolean>()          // TrackPath may add points between two real ones (ROUTE / TRAIL, later)
    private var lastLead: String? = null
    private var held: String? = null                 // a new lead whose points wait until he passes the last lead point
    private var heldNear = false

    fun reset() { points.clear(); real.clear(); lastLead = null; held = null; heldNear = false }
    fun isEmpty() = points.isEmpty()

    /** A REAL report from the current lead. Returns true when the track grew. */
    fun onLeadReport(leadId: String, p: TrackPoint): Boolean {
        if (lastLead != null && leadId != lastLead && points.isNotEmpty()) { held = leadId; heldNear = false }
        lastLead = leadId
        if (held == leadId) {
            if (!heldNear) {
                if (TrackPath.metres(p, points.last()) <= PASS_M) heldNear = true
                return false
            }
            held = null                               // the report AFTER he reached the last lead point: ahead -> continue
        }
        if (points.isEmpty()) { points.add(p); real.add(true); return true }
        if (points.last() == p) return false
        val path = TrackPath.path(points.last(), p).points
        for (i in 1 until path.size) { points.add(path[i]); real.add(i == path.size - 1) }
        return true
    }

    /** Off-track: metres from p to the nearest piece of the lead track. CODE RULE 1: null = no track yet (nothing to be off). */
    fun metresFromTrack(p: TrackPoint): Double? {
        if (points.isEmpty()) return null
        if (points.size == 1) return TrackPath.metres(p, points[0])
        var best = Double.MAX_VALUE
        for (i in 1 until points.size) best = minOf(best, TrackPath.metresToSegment(p, points[i - 1], points[i]))
        return best
    }

    fun allSegments(): List<ConvoyEngine.LeadTrackSegment> = segmentsFrom(0)

    /** Shown on THIS tablet: the lead track AHEAD of my cart. The last REAL point my cart has come within 50 m of -- and every point
     *  before it -- is hidden (kept in the data). CODE RULE 1: my null = my cart has no position yet -> the whole track. */
    fun shownSegments(my: TrackPoint?): List<ConvoyEngine.LeadTrackSegment> {
        if (my == null) return segmentsFrom(0)
        var cut = -1
        for (i in points.indices) if (real[i] && TrackPath.metres(points[i], my) <= PASS_M) cut = i
        return segmentsFrom(maxOf(0, cut))
    }

    private fun segmentsFrom(start: Int): List<ConvoyEngine.LeadTrackSegment> {
        val out = ArrayList<ConvoyEngine.LeadTrackSegment>()
        for (i in (start + 1) until points.size) {
            val a = points[i - 1]; val b = points[i]
            out.add(ConvoyEngine.LeadTrackSegment(startLat = a.lat, startLon = a.lon, endLat = b.lat, endLon = b.lon, color = "#000000", nodeId = lastLead ?: ""))
        }
        return out
    }
}

/** MYTRACKMSG-2026-10-18: my track = my cart's own REAL reports (the messages that move my cart icon), joined through TrackPath -- the same for
 *  every tablet, the lead's included. No trim (it is behind me), no hold. Plain Kotlin; used by the receiving queue's one consumer only. */
class MyTrack {
    private val points = ArrayList<TrackPoint>()
    fun reset() { points.clear() }
    /** Returns true when the track grew. */
    fun onReport(p: TrackPoint): Boolean {
        if (points.isEmpty()) { points.add(p); return true }
        if (points.last() == p) return false
        val path = TrackPath.path(points.last(), p).points
        for (i in 1 until path.size) points.add(path[i])
        return true
    }
    fun segments(): List<ConvoyEngine.LeadTrackSegment> {
        val out = ArrayList<ConvoyEngine.LeadTrackSegment>()
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            out.add(ConvoyEngine.LeadTrackSegment(startLat = a.lat, startLon = a.lon, endLat = b.lat, endLon = b.lon, color = "#2E75B6", nodeId = "me"))
        }
        return out
    }
}
