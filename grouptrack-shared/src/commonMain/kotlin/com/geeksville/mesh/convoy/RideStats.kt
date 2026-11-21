package com.geeksville.mesh.convoy

import com.grouptrack.shared.concurrent.GtLock
import com.grouptrack.shared.text.GtFmt
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtMath

/**
 * STATSPANEL-2026-10-20 (Fred 10-20 10:46): RIDE STATISTICS -- the range-test analysis inside the app,
 * shown in Settings -> Group awareness -> Ride statistics. Recorded ONLY while group awareness is on
 * (Fred 10-20), in memory for this app session; RESET starts again (e.g. between a Medium Fast and a
 * Long Fast run).
 * Per cart heard: reports, minutes heard %, the usual gap, farthest heard, reports by distance band,
 * every loss (nothing for > 60 s) with the distance it was lost at / regained at and how far apart we
 * got, the closest loss, signal (SNR, RSSI), hops, channel and air use. Distances are RADIO TO RADIO:
 * the cart's reported position against my radio's own last position (fresh within 2 min).
 *
 * ⭐ MOVED TO SHARED CODE AT RIDESTATS-2026-11-21, unchanged in behaviour. The iPad's panel displays the
 * string report() builds, so the engine had to cross the seam before the panel could exist (patch B).
 *
 * ⛔ json() DID NOT MOVE. It is the "Email last ride statistics" payload (GtRideStatsSend), which
 * LaunchModules.kt:138 puts last in RIDES -- a different launch point, already certified. GtOrgJson has
 * no num(Float) and snr/ch/air are Float, so re-serialising here would change the bytes. Android's
 * json() now reads [startedMs], [heard], [minePositions] and [estimates] instead, so THE STATE IS NOT
 * DUPLICATED. See C28-26367; settle it when Rides is walked.
 *
 * ⛔ THE DISTANCES ARE TRUE HAVERSINE and must stay so: TrackGeo.metres is equirectangular
 * (cos(lat)*111_320 by 110_574) and swapping it in would move every distance in this report.
 */
object RideStats {
    /** One report heard. CODE RULE 1 (all nullable fields): null = not in that report (no position, my
     *  radio's position stale, or the transport does not carry the value). */
    class Rx(val t: Long, val id: String, val name: String, val lat: Double?, val lon: Double?, val myLat: Double?, val myLon: Double?,
             val snr: Float?, val rssi: Int?, val hops: Int?, val ch: Float?, val air: Float?)
    /** My radio's own position at a moment. Public since RIDESTATS-2026-11-21 so Android's json() can read it. */
    class Mine(val t: Long, val lat: Double, val lon: Double)
    /** RIDESTATS-2026-11-21: LeadEstimate.Outcome is six primitives, so the 204-line estimate engine does
     *  NOT cross the seam -- Android keeps producing outcomes and hands over this record. */
    data class RideLeadOutcome(val tMs: Long, val silentMs: Long, val errorM: Double, val speedMps: Double, val onRoute: Boolean, val moving: Boolean)

    private val lock = GtLock()
    private val rx = ArrayList<Rx>()
    private val mine = ArrayList<Mine>()
    private val estimates = ArrayList<RideLeadOutcome>()
    private var startMs = 0L
    private const val MAX = 50_000          // a whole day of reports; the oldest go first
    private const val GAP_MS = 60_000L      // a LOSS = nothing heard from that cart for more than this

    /** RIDESTATS-2026-11-21: [nowMs] is the caller's -- shared code has no ambient clock. */
    fun onHeard(m: com.grouptrack.comm.Member, myId: String?, myLat: Double?, myLon: Double?, nowMs: Long) = lock.withLock {
        if (m.id == myId) return@withLock
        if (startMs == 0L) startMs = nowMs
        // STATSDUP-2026-10-26 (Fred 12:39): a cart sends several packets at once (position, telemetry, node info) --
        // count it once: the same cart, within 2 s of its last counted report, at the same position = the same report.
        val prev = rx.lastOrNull { it.id == m.id }
        if (prev != null && nowMs - prev.t < 2_000L && prev.lat == m.latitude && prev.lon == m.longitude) return@withLock
        if (rx.size >= MAX) rx.removeAt(0)
        rx.add(Rx(nowMs, m.id, m.callsign, m.latitude, m.longitude, myLat, myLon, m.snr, m.rssi, m.hopsAway, m.channelUtilPct, m.airUtilPct))
    }

    fun onMine(t: Long, lat: Double, lon: Double) = lock.withLock {
        if (startMs == 0L) startMs = t
        if (mine.isNotEmpty() && t - mine.last().t < 5_000L) return@withLock
        if (mine.size >= MAX) mine.removeAt(0)
        mine.add(Mine(t, lat, lon))
    }

    // LEADEST-2026-10-20: what each estimate of the silent lead was worth when it was heard again (Fred 11:45)
    fun onEstimate(o: RideLeadOutcome) = lock.withLock {
        if (startMs == 0L) startMs = o.tMs
        if (estimates.size < 5_000) estimates.add(o)
    }

    fun reset() = lock.withLock { rx.clear(); mine.clear(); estimates.clear(); startMs = 0L }

    // ---- RIDESTATS-2026-11-21: read access for Android's json(), which did not move. Copies, so a
    // ---- caller cannot mutate the record, and the lock is held while copying.
    fun startedMs(): Long = lock.withLock { startMs }
    fun heard(): List<Rx> = lock.withLock { ArrayList(rx) }
    fun minePositions(): List<Mine> = lock.withLock { ArrayList(mine) }
    fun estimates(): List<RideLeadOutcome> = lock.withLock { ArrayList(estimates) }

    /** ⛔ TRUE HAVERSINE -- see the class note. GtMath.asin for parity (asin is not exactly rounded);
     *  kotlin.math.sqrt needs no wrapper because IEEE-754 requires sqrt to be exactly rounded. */
    private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6_371_000.0
        val p1 = GtJava.toRadians(aLat); val p2 = GtJava.toRadians(bLat)
        val dp = p2 - p1; val dl = GtJava.toRadians(bLon - aLon)
        val h = GtMath.sin(dp / 2) * GtMath.sin(dp / 2) + GtMath.cos(p1) * GtMath.cos(p2) * GtMath.sin(dl / 2) * GtMath.sin(dl / 2)
        return 2 * r * GtMath.asin(kotlin.math.sqrt(h))
    }
    private fun yd(m: Double): String = GtFmt.f(m * 1.0936, 0) + " yd (" + GtFmt.f(m, 0) + " m)"
    private fun pad2(v: Long): String = if (v < 10) "0" + v else v.toString()
    /** SimpleDateFormat("HH:mm:ss") in the device's zone. ⚠ ONE offset for the whole report -- see the
     *  patch header: a ride spanning a DST change shows pre-change times an hour out. */
    private fun hm(t: Long, offMin: Int): String {
        var sod = (t / 1000L + offMin * 60L) % 86_400L
        if (sod < 0) sod += 86_400L
        return pad2(sod / 3600L) + ":" + pad2(sod % 3600L / 60L) + ":" + pad2(sod % 60L)
    }
    private fun pt(lat: Double?, lon: Double?): String = if (lat == null || lon == null) "--" else GtFmt.f(lat, 6) + ", " + GtFmt.f(lon, 6)
    private fun dist(x: Rx): Double? {
        val a = x.lat; val b = x.lon; val c = x.myLat; val d = x.myLon
        return if (a == null || b == null || c == null || d == null) null else metres(a, b, c, d)
    }
    private fun <T : Comparable<T>> lowMedHigh(l: List<T>): Triple<T, T, T>? {
        if (l.isEmpty()) return null
        val s = l.sorted(); return Triple(s.first(), s[s.size / 2], s.last())
    }

    /** RIDESTATS-2026-11-21: [tzOffsetMinutes] is the host's -- shared code has no time zone. */
    /** RIDESTATSPANEL-2026-11-21: [avgChannel] is NULLABLE. CODE RULE 1 -- null = this platform's
     *  transport does not report channel utilisation (the iPad has no radio figure; that is RADIO
     *  SETUP's data), exactly as Member.channelUtilPct is nullable in the comm contract. It prints
     *  "--", as intervalSecs does. ⛔ NOT 0f: C28-26356 LASTSTALE -- never substitute a default for
     *  data we do not have, because 0% reads as a measurement. */
    fun report(nowMs: Long, tzOffsetMinutes: Int, preset: String?, intervalSecs: Int?, avgChannel: Float?): String = lock.withLock {
        val sb = StringBuilder()
        sb.append("RIDE STATISTICS   ").append(hm(nowMs, tzOffsetMinutes)).append('\n')
        if (startMs == 0L) {
            sb.append("\nNothing recorded yet.\nStatistics are kept while group\nawareness is on, from the first\nreport heard.\n")
            return@withLock sb.toString()
        }
        sb.append("Since ").append(hm(startMs, tzOffsetMinutes)).append("  (").append((nowMs - startMs) / 60_000L).append(" min)\n")
        sb.append("My radio: ").append(GpsPresets.label(preset)).append(", every ").append(intervalSecs?.toString() ?: "--").append(" s\n")
        sb.append("Channel use now (average): ")
            .append(if (avgChannel == null) "--" else GtFmt.f(avgChannel.toDouble(), 0) + "%").append('\n')
        sb.append("My radio positions recorded: ").append(mine.size).append('\n')
        val ids = rx.map { it.id }.distinct()
        if (ids.isEmpty()) sb.append("\nNo other radio heard yet.\n")
        for (id in ids) {
            val r = rx.filter { it.id == id }
            sb.append("\n== ").append(r.last().name).append("  (").append(id).append(")\n")
            val first = r.first().t; val last = r.last().t
            val mins = ((last - first) / 60_000L + 1).toInt()
            val heardMins = r.map { (it.t - first) / 60_000L }.distinct().size
            sb.append("Reports ").append(r.size).append("   ").append(hm(first, tzOffsetMinutes)).append(" - ").append(hm(last, tzOffsetMinutes)).append('\n')
            sb.append("Minutes heard ").append(heardMins).append(" of ").append(mins).append(" (").append(heardMins * 100 / mins).append("%)\n")
            lowMedHigh(r.zipWithNext { a, b -> (b.t - a.t) / 1000L })?.let { sb.append("Usual gap between reports ").append(it.second).append(" s\n") }

            // distance
            val dd = r.mapNotNull { x -> dist(x)?.let { Pair(x, it) } }
            if (dd.isEmpty()) sb.append("Distance: no positions to compare yet\n") else {
                val far = dd.maxByOrNull { it.second }!!
                sb.append("Farthest heard ").append(yd(far.second)).append("  ").append(hm(far.first.t, tzOffsetMinutes)).append('\n')
                sb.append("  cart ").append(pt(far.first.lat, far.first.lon)).append('\n')
                sb.append("  me   ").append(pt(far.first.myLat, far.first.myLon)).append('\n')
                val bands = listOf(0.0 to 100.0, 100.0 to 250.0, 250.0 to 500.0, 500.0 to 1000.0, 1000.0 to Double.MAX_VALUE)
                sb.append("Reports by distance (yd):\n")
                for ((lo, hi) in bands) {
                    val n = dd.count { val y = it.second * 1.0936; y >= lo && y < hi }
                    val label = if (hi == Double.MAX_VALUE) "over " + GtFmt.f(lo, 0) else GtFmt.f(lo, 0) + "-" + GtFmt.f(hi, 0)
                    sb.append("  ").append(label.padEnd(10)).append(n).append('\n')
                }
            }

            // losses
            val losses = ArrayList<String>()
            var closestM = Double.MAX_VALUE; var closestRx: Rx? = null   // CODE RULE 1: null = no loss with a known distance
            for (i in 1 until r.size) {
                val a = r[i - 1]; val b = r[i]
                if (b.t - a.t <= GAP_MS) continue
                val dA = dist(a); val dB = dist(b)
                val aLat = a.lat; val aLon = a.lon
                val away = if (aLat != null && aLon != null)
                    mine.filter { it.t > a.t && it.t < b.t }.maxOfOrNull { metres(aLat, aLon, it.lat, it.lon) } else null
                losses.add(hm(a.t, tzOffsetMinutes) + "  lost " + (b.t - a.t) / 1000L + " s\n    at " + (dA?.let { yd(it) } ?: "--") +
                    "\n    back at " + (dB?.let { yd(it) } ?: "--") + (away?.let { "\n    apart up to " + yd(it) } ?: ""))
                if (dA != null && dA < closestM) { closestM = dA; closestRx = a }
            }
            if (nowMs - last > GAP_MS) {
                val dL = dist(r.last())
                losses.add(hm(last, tzOffsetMinutes) + "  NOT HEARD SINCE (" + (nowMs - last) / 1000L + " s)\n    at " + (dL?.let { yd(it) } ?: "--"))
            }
            sb.append("Losses (over 60 s): ").append(losses.size).append('\n')
            for (l in losses) sb.append("  ").append(l).append('\n')
            closestRx?.let { c ->
                sb.append("Closest loss ").append(yd(closestM)).append("  ").append(hm(c.t, tzOffsetMinutes)).append('\n')
                sb.append("  cart ").append(pt(c.lat, c.lon)).append('\n')
                sb.append("  me   ").append(pt(c.myLat, c.myLon)).append('\n')
            }

            // signal and channel
            lowMedHigh(r.mapNotNull { it.snr })?.let {
                sb.append("SNR dB   ").append(GtFmt.f(it.first.toDouble(), 1)).append(" / ").append(GtFmt.f(it.second.toDouble(), 1))
                    .append(" / ").append(GtFmt.f(it.third.toDouble(), 1)).append("  (low/median/high)\n")
            }
            lowMedHigh(r.mapNotNull { it.rssi })?.let { sb.append("RSSI dBm ").append(it.first).append(" / ").append(it.second).append(" / ").append(it.third).append('\n') }
            val hops = r.mapNotNull { it.hops }.groupingBy { it }.eachCount().entries.sortedBy { it.key }
            if (hops.isNotEmpty()) sb.append("Hops     ").append(hops.joinToString("  ") { it.key.toString() + ": " + it.value }).append('\n')
            val ch = r.mapNotNull { it.ch }; val air = r.mapNotNull { it.air }
            if (ch.isNotEmpty()) sb.append("Channel  ").append(GtFmt.f(ch.average(), 1)).append("% avg, ")
                .append(GtFmt.f((ch.maxOrNull() ?: 0f).toDouble(), 1)).append("% high").append('\n')
            if (air.isNotEmpty()) sb.append("Air use  ").append(GtFmt.f(air.average(), 1)).append("% avg, ")
                .append(GtFmt.f((air.maxOrNull() ?: 0f).toDouble(), 1)).append("% high").append('\n')
        }
        // LEADEST-2026-10-20: the silent lead's estimates
        if (estimates.isNotEmpty()) {
            sb.append("\n== Lead estimates while silent: ").append(estimates.size).append('\n')
            lowMedHigh(estimates.map { it.errorM })?.let {
                sb.append("Off when heard again (low/median/high)\n  ").append(yd(it.first)).append(" / ").append(yd(it.second))
                    .append(" / ").append(yd(it.third)).append('\n')
            }
            for (o in estimates.takeLast(10)) {
                sb.append("  ").append(hm(o.tMs, tzOffsetMinutes)).append("  silent ").append(o.silentMs / 1000L).append(" s, ")
                    .append(if (o.onRoute && o.moving) "along the route at " + GtFmt.f(o.speedMps * 2.237, 0) + " mph"
                            else if (o.moving) "in place (no route)" else "stopped")
                    .append("\n    off by ").append(yd(o.errorM)).append('\n')
            }
        }
        return@withLock sb.toString()
    }
}
