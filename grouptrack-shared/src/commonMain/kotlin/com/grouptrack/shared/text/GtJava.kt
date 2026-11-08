package com.grouptrack.shared.text

import kotlin.math.floor

/**
 * STEP15A-2026-11-08 (2.8 step 15a): java.lang number behaviour for the shared code, so the iPad writes what the Droid writes.
 *  - [jd]        = java.lang.Double.toString(x) (Android). Every WKT the importers build ("LINESTRING(lon lat,...)") is this text, and the
 *                  geom_hash is taken over the WKT -- a different digit or layout is a different identity for the same ground.
 *                  Android: the platform's own Double.toString (identical by construction). iPad: [shortestJavaText] (see GtJavaText.ios.kt).
 *                  STEP15A-B-2026-11-08 (golden run 18:08): Android 14 is NOT the old FloatingDecimal -- it prints exactly what JDK 19+
 *                  prints (Raffaello Giulietti's shortest-decimal rule; all 28 iPad differences matched a JDK 21 run). Kotlin/Native printed
 *                  17 digits on 28 of 188,137 values (1e13..1e18), so the iPad now computes the JDK 19+ rule itself, exactly.
 *  - [round]     = java.lang.Math.round(double): ties toward +infinity, NaN -> 0, saturating at Long.MIN/MAX (Kotlin's roundToLong
 *                  THROWS on NaN -- and "<ele>NaN</ele>" parses).  [roundToIntJava] = Math.round(d).toInt() as 2.7e writes it
 *                  (the Long's low 32 bits, as Kotlin's Long.toInt()).
 *  - [toRadians] / [toDegrees] = java.lang.Math on Droid 1: deg * 0.017453292519943295, rad * 57.29577951308232 (Fred/coordinator 11-08:
 *                  toRadians proven on Droid 1 with this constant). ⚠ GtRead.toRadians (step 7) is written deg / 180.0 * PI -- the golden
 *                  file records both against Math.toRadians (kinds "rad" and "radread") so any difference shows; GtRead is NOT changed here.
 */
object GtJava {
    const val DEGREES_TO_RADIANS = 0.017453292519943295
    const val RADIANS_TO_DEGREES = 57.29577951308232

    fun jd(x: Double): String = gtJavaDoubleText(x)

    fun round(x: Double): Long {
        if (x.isNaN()) return 0L
        val f = floor(x)
        val r = if (x - f >= 0.5) f + 1.0 else f   // x - floor(x) is exact for every finite double
        return r.toLong()                         // Kotlin's Double.toLong saturates (and +-Infinity -> MAX/MIN), as Java's (long) cast
    }

    fun roundToIntJava(x: Double): Int = round(x).toInt()

    fun toRadians(deg: Double): Double = deg * DEGREES_TO_RADIANS
    fun toDegrees(rad: Double): Double = rad * RADIANS_TO_DEGREES

    /**
     * The digits + exponent of a decimal number text ("123.45", "1.2345E-5", "1.0e10", "5"): (digits with no leading/trailing zeros,
     * p) where value = 0.digits x 10^p. Used by the iOS layout. "0" -> ("0", 1).
     */
    internal fun decimalParts(s: String): Pair<String, Int> {
        var t = s
        var e = 0
        val ei = t.indexOfFirst { it == 'E' || it == 'e' }
        if (ei >= 0) { e = t.substring(ei + 1).removePrefix("+").toInt(); t = t.substring(0, ei) }
        val dot = t.indexOf('.')
        var digits = if (dot < 0) t else t.substring(0, dot) + t.substring(dot + 1)
        var p = (if (dot < 0) t.length else dot) + e
        var lz = 0
        while (lz < digits.length - 1 && digits[lz] == '0') { lz++ }
        digits = digits.substring(lz); p -= lz
        digits = digits.trimEnd('0')
        if (digits.isEmpty()) return "0" to 1
        return digits to p
    }

    /** Java's Double.toString layout for 0.digits x 10^p (positive, digits non-empty, no leading zero). */
    internal fun javaLayout(neg: Boolean, digits: String, p: Int): String {
        val sci = p - 1
        val body = if (sci in -3..6) {
            when {
                p <= 0 -> "0." + "0".repeat(-p) + digits
                p >= digits.length -> digits + "0".repeat(p - digits.length) + ".0"
                else -> digits.substring(0, p) + "." + digits.substring(p)
            }
        } else {
            digits.substring(0, 1) + "." + digits.substring(1).ifEmpty { "0" } + "E" + sci
        }
        return if (neg) "-$body" else body
    }

    // ---- STEP15A-B-2026-11-08: java.lang.Double.toString as JDK 19+ / Android 14 defines it, computed exactly ------------------
    /**
     * The decimal Android 14 prints for [x], by the definition in JDK 19+'s Double.toString javadoc (Giulietti): R = every decimal that
     * rounds to x (round half even -- the interval's ends belong to R when x's significand is even); m = the shortest length in R;
     * T = the decimals of R of length m (of length 1 or 2 when m = 1); the one in T closest to x, or of two the one with the even last
     * digit; then Java's layout. Computed with exact decimal expansions of the interval ends (no floating point), so it does not depend
     * on the platform's own Double.toString or parser.
     * PROVED 11-08 off-device against JDK 21's Double.toString (= Android 14 on the 28 golden cases): 3,105,295 values -- every power of
     * two and its neighbours from 2^-1074 to 2^1023, 1..99,999, 2,000,000 random bit patterns, 1,000,000 coordinate texts -- 0 differences
     * (the Java transcription of this function, step15a/code_b/evidence/Jd.java). The Kotlin is that transcription line for line.
     */
    fun shortestJavaText(x: Double): String {
        special(x)?.let { return it }
        val neg = x < 0
        val bits = (if (neg) -x else x).toRawBits()
        val eb = ((bits ushr 52) and 0x7ffL).toInt()
        val mant = bits and 0xfffffffffffffL
        val c: Long; val q: Int; val irregular: Boolean
        if (eb == 0) { c = mant; q = -1074; irregular = false } else { c = mant or (1L shl 52); q = eb - 1075; irregular = mant == 0L && eb > 1 }
        val even = (c and 1L) == 0L
        val s = q - 2
        val lo = exactDecimal(4 * c - (if (irregular) 1 else 2), s)
        val hi = exactDecimal(4 * c + 2, s)
        val v = exactDecimal(4 * c, s)
        val p = v.second
        var m = -1; var dlo = 0L; var dhi = 0L; var t = 0
        for (n in 1..17) {
            t = p - n
            val fl = floorScaled(lo, t); dlo = if (fl.second && even) fl.first else fl.first + 1
            val fh = floorScaled(hi, t); dhi = if (fh.second && !even) fh.first - 1 else fh.first
            if (dlo <= dhi) { m = n; break }
        }
        if (m == 1) {
            t = p - 2
            val fl = floorScaled(lo, t); dlo = if (fl.second && even) fl.first else fl.first + 1
            val fh = floorScaled(hi, t); dhi = if (fh.second && !even) fh.first - 1 else fh.first
        }
        val fv = floorScaled(v, t)
        val h = fracVsHalf(v, t)
        var d = fv.first
        if (h > 0 || (h == 0 && (fv.first and 1L) == 1L)) d = fv.first + 1
        if (d < dlo) d = dlo
        if (d > dhi) d = dhi
        val ds = d.toString()
        val stripped = ds.trimEnd('0').ifEmpty { "0" }
        return javaLayout(neg, stripped, ds.length + t)
    }

    /** The exact decimal digits of [m] x 2^[e2] (m > 0): (digits without leading zeros, how many come before the point). */
    internal fun exactDecimal(m: Long, e2: Int): Pair<String, Int> {
        val base = 1_000_000_000L
        val limbs = ArrayList<Long>()
        var t = m
        while (t > 0) { limbs.add(t % base); t /= base }
        fun mul(f: Long) {
            var carry = 0L
            for (i in limbs.indices) { val pr = limbs[i] * f + carry; limbs[i] = pr % base; carry = pr / base }
            while (carry > 0) { limbs.add(carry % base); carry /= base }
        }
        var n = if (e2 >= 0) e2 else -e2
        if (e2 >= 0) { while (n >= 30) { mul(1L shl 30); n -= 30 }; if (n > 0) mul(1L shl n) }
        else { while (n >= 13) { mul(1_220_703_125L); n -= 13 }; var f = 1L; repeat(n) { f *= 5 }; if (n > 0) mul(f) }
        val sb = StringBuilder(limbs.last().toString())
        for (i in limbs.size - 2 downTo 0) sb.append(limbs[i].toString().padStart(9, '0'))
        val str = sb.toString()
        val pp = if (e2 >= 0) str.length else str.length - (-e2)
        var lz = 0
        while (lz < str.length - 1 && str[lz] == '0') lz++
        return str.substring(lz) to (pp - lz)
    }

    /** floor(X / 10^t) and whether the division is exact. */
    private fun floorScaled(x: Pair<String, Int>, t: Int): Pair<Long, Boolean> {
        val d = x.first
        val pp = x.second - t
        var v = 0L
        var exact = true
        if (pp <= 0) { exact = d.all { it == '0' } }
        else {
            for (i in 0 until pp) v = v * 10 + (if (i < d.length) d[i] - '0' else 0)
            for (i in pp until d.length) if (d[i] != '0') { exact = false; break }
        }
        return v to exact
    }

    /** The fractional part of X / 10^t against one half: -1 below, 0 exactly, 1 above. */
    private fun fracVsHalf(x: Pair<String, Int>, t: Int): Int {
        val d = x.first
        val pp = x.second - t
        if (pp < 0) return -1
        val first = if (pp < d.length) d[pp] - '0' else 0
        if (first > 5) return 1
        if (first < 5) return -1
        for (i in pp + 1 until d.length) if (d[i] != '0') return 1
        return 0
    }

    /** Shared by both actuals' special cases. */
    internal fun special(x: Double): String? = when {
        x.isNaN() -> "NaN"
        x == Double.POSITIVE_INFINITY -> "Infinity"
        x == Double.NEGATIVE_INFINITY -> "-Infinity"
        x == 0.0 -> if (1.0 / x < 0) "-0.0" else "0.0"
        else -> null
    }

    internal fun layoutOf(platformText: String, x: Double): String {
        special(x)?.let { return it }
        val (digits, p) = decimalParts(platformText.removePrefix("-"))
        return javaLayout(x < 0, digits, p)
    }
}

/** STEP15A-2026-11-08: java.lang.Double.toString(x). Android = x.toString(); iOS = GtJava.shortestJavaText(x) (STEP15A-B). */
expect fun gtJavaDoubleText(x: Double): String
