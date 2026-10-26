package com.geeksville.mesh.convoy

/**
 * STATSPANEL-2026-10-20 (Fred 10-20 10:46): RIDE STATISTICS -- the range-test analysis (the Python run on the 10-20 logs) inside the app,
 * shown in Settings -> Group awareness -> Ride statistics. Recorded ONLY while group awareness is on (Fred 10-20), in memory for this
 * app session; RESET starts again (e.g. between a Medium Fast and a Long Fast run). JSON / GPX / email come later (T6c).
 * Per cart heard: reports, minutes heard %, the usual gap, farthest heard, reports by distance band, every loss (nothing for > 60 s)
 * with the distance it was lost at / regained at and how far apart we got, the closest loss, signal (SNR, RSSI), hops, channel and
 * air use. Distances are RADIO TO RADIO: the cart's reported position against my radio's own last position (fresh within 2 min).
 */
object RideStats {
    /** One report heard. CODE RULE 1 (all nullable fields): null = not in that report (no position, my radio's position stale,
     *  or the transport does not carry the value). */
    class Rx(val t: Long, val id: String, val name: String, val lat: Double?, val lon: Double?, val myLat: Double?, val myLon: Double?,
             val snr: Float?, val rssi: Int?, val hops: Int?, val ch: Float?, val air: Float?)
    private class Mine(val t: Long, val lat: Double, val lon: Double)

    private val rx = ArrayList<Rx>()
    private val mine = ArrayList<Mine>()
    private var startMs = 0L
    private const val MAX = 50_000          // a whole day of reports; the oldest go first
    private const val GAP_MS = 60_000L      // a LOSS = nothing heard from that cart for more than this

    @Synchronized fun onHeard(m: com.grouptrack.comm.Member, myId: String?, myLat: Double?, myLon: Double?) {
        if (m.id == myId) return
        val now = System.currentTimeMillis()
        if (startMs == 0L) startMs = now
        // STATSDUP-2026-10-26 (Fred 12:39): a cart sends several packets at once (position, telemetry, node info) -- count it once:
        // the same cart, within 2 s of its last counted report, at the same position = the same report.
        rx.lastOrNull { it.id == m.id }?.let { p -> if (now - p.t < 2_000L && p.lat == m.latitude && p.lon == m.longitude) return }
        if (rx.size >= MAX) rx.removeAt(0)
        rx.add(Rx(now, m.id, m.callsign, m.latitude, m.longitude, myLat, myLon, m.snr, m.rssi, m.hopsAway, m.channelUtilPct, m.airUtilPct))
    }

    @Synchronized fun onMine(t: Long, lat: Double, lon: Double) {
        if (startMs == 0L) startMs = t
        if (mine.isNotEmpty() && t - mine.last().t < 5_000L) return
        if (mine.size >= MAX) mine.removeAt(0)
        mine.add(Mine(t, lat, lon))
    }

    @Synchronized fun reset() { rx.clear(); mine.clear(); estimates.clear(); startMs = 0L }
    // LEADEST-2026-10-20: what each estimate of the silent lead was worth when it was heard again (Fred 11:45)
    private val estimates = ArrayList<LeadEstimate.Outcome>()
    @Synchronized fun onEstimate(o: LeadEstimate.Outcome) { if (startMs == 0L) startMs = o.tMs; if (estimates.size < 5_000) estimates.add(o) }

    private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
        val dp = p2 - p1; val dl = Math.toRadians(bLon - aLon)
        val h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(h))
    }
    private fun yd(m: Double): String = "%.0f yd (%.0f m)".format(m * 1.0936, m)
    private fun hm(t: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(t))
    private fun pt(lat: Double?, lon: Double?): String = if (lat == null || lon == null) "--" else "%.6f, %.6f".format(lat, lon)
    private fun dist(x: Rx): Double? {
        val a = x.lat; val b = x.lon; val c = x.myLat; val d = x.myLon
        return if (a == null || b == null || c == null || d == null) null else metres(a, b, c, d)
    }
    private fun <T : Comparable<T>> lowMedHigh(l: List<T>): Triple<T, T, T>? {
        if (l.isEmpty()) return null
        val s = l.sorted(); return Triple(s.first(), s[s.size / 2], s.last())
    }

    @Synchronized fun report(nowMs: Long, preset: String?, intervalSecs: Int?, avgChannel: Float): String {
        val sb = StringBuilder()
        sb.append("RIDE STATISTICS   ").append(hm(nowMs)).append('\n')
        if (startMs == 0L) {
            sb.append("\nNothing recorded yet.\nStatistics are kept while group\nawareness is on, from the first\nreport heard.\n")
            return sb.toString()
        }
        sb.append("Since ").append(hm(startMs)).append("  (").append((nowMs - startMs) / 60_000L).append(" min)\n")
        sb.append("My radio: ").append(GpsPresets.label(preset)).append(", every ").append(intervalSecs?.toString() ?: "--").append(" s\n")
        sb.append("Channel use now (average): ").append("%.0f%%".format(avgChannel)).append('\n')
        sb.append("My radio positions recorded: ").append(mine.size).append('\n')
        val ids = rx.map { it.id }.distinct()
        if (ids.isEmpty()) sb.append("\nNo other radio heard yet.\n")
        for (id in ids) {
            val r = rx.filter { it.id == id }
            sb.append("\n== ").append(r.last().name).append("  (").append(id).append(")\n")
            val first = r.first().t; val last = r.last().t
            val mins = ((last - first) / 60_000L + 1).toInt()
            val heardMins = r.map { (it.t - first) / 60_000L }.distinct().size
            sb.append("Reports ").append(r.size).append("   ").append(hm(first)).append(" - ").append(hm(last)).append('\n')
            sb.append("Minutes heard ").append(heardMins).append(" of ").append(mins).append(" (").append(heardMins * 100 / mins).append("%)\n")
            lowMedHigh(r.zipWithNext { a, b -> (b.t - a.t) / 1000L })?.let { sb.append("Usual gap between reports ").append(it.second).append(" s\n") }

            // distance
            val dd = r.mapNotNull { x -> dist(x)?.let { Pair(x, it) } }
            if (dd.isEmpty()) sb.append("Distance: no positions to compare yet\n") else {
                val far = dd.maxByOrNull { it.second }!!
                sb.append("Farthest heard ").append(yd(far.second)).append("  ").append(hm(far.first.t)).append('\n')
                sb.append("  cart ").append(pt(far.first.lat, far.first.lon)).append('\n')
                sb.append("  me   ").append(pt(far.first.myLat, far.first.myLon)).append('\n')
                val bands = listOf(0.0 to 100.0, 100.0 to 250.0, 250.0 to 500.0, 500.0 to 1000.0, 1000.0 to Double.MAX_VALUE)
                sb.append("Reports by distance (yd):\n")
                for ((lo, hi) in bands) {
                    val n = dd.count { val y = it.second * 1.0936; y >= lo && y < hi }
                    val label = if (hi == Double.MAX_VALUE) "over %.0f".format(lo) else "%.0f-%.0f".format(lo, hi)
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
                losses.add(hm(a.t) + "  lost " + (b.t - a.t) / 1000L + " s\n    at " + (dA?.let { yd(it) } ?: "--") +
                    "\n    back at " + (dB?.let { yd(it) } ?: "--") + (away?.let { "\n    apart up to " + yd(it) } ?: ""))
                if (dA != null && dA < closestM) { closestM = dA; closestRx = a }
            }
            if (nowMs - last > GAP_MS) {
                val dL = dist(r.last())
                losses.add(hm(last) + "  NOT HEARD SINCE (" + (nowMs - last) / 1000L + " s)\n    at " + (dL?.let { yd(it) } ?: "--"))
            }
            sb.append("Losses (over 60 s): ").append(losses.size).append('\n')
            for (l in losses) sb.append("  ").append(l).append('\n')
            closestRx?.let { c ->
                sb.append("Closest loss ").append(yd(closestM)).append("  ").append(hm(c.t)).append('\n')
                sb.append("  cart ").append(pt(c.lat, c.lon)).append('\n')
                sb.append("  me   ").append(pt(c.myLat, c.myLon)).append('\n')
            }

            // signal and channel
            lowMedHigh(r.mapNotNull { it.snr })?.let { sb.append("SNR dB   ").append("%.1f / %.1f / %.1f".format(it.first, it.second, it.third)).append("  (low/median/high)\n") }
            lowMedHigh(r.mapNotNull { it.rssi })?.let { sb.append("RSSI dBm ").append(it.first).append(" / ").append(it.second).append(" / ").append(it.third).append('\n') }
            val hops = r.mapNotNull { it.hops }.groupingBy { it }.eachCount().toSortedMap()
            if (hops.isNotEmpty()) sb.append("Hops     ").append(hops.entries.joinToString("  ") { "${it.key}: ${it.value}" }).append('\n')
            val ch = r.mapNotNull { it.ch }; val air = r.mapNotNull { it.air }
            if (ch.isNotEmpty()) sb.append("Channel  ").append("%.1f%% avg, %.1f%% high".format(ch.average(), ch.maxOrNull() ?: 0f)).append('\n')
            if (air.isNotEmpty()) sb.append("Air use  ").append("%.1f%% avg, %.1f%% high".format(air.average(), air.maxOrNull() ?: 0f)).append('\n')
        }
        // LEADEST-2026-10-20: the silent lead's estimates
        if (estimates.isNotEmpty()) {
            sb.append("\n== Lead estimates while silent: ").append(estimates.size).append('\n')
            lowMedHigh(estimates.map { it.errorM })?.let { sb.append("Off when heard again (low/median/high)\n  ").append(yd(it.first)).append(" / ").append(yd(it.second)).append(" / ").append(yd(it.third)).append('\n') }
            for (o in estimates.takeLast(10)) {
                sb.append("  ").append(hm(o.tMs)).append("  silent ").append(o.silentMs / 1000L).append(" s, ")
                    .append(if (o.onRoute && o.moving) "along the route at %.0f mph".format(o.speedMps * 2.237) else if (o.moving) "in place (no route)" else "stopped")
                    .append("\n    off by ").append(yd(o.errorM)).append('\n')
            }
        }
        return sb.toString()
    }
}
