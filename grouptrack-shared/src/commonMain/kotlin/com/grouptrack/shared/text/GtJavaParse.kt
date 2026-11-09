package com.grouptrack.shared.text

/**
 * STEP15B-2026-11-09 (2.8 step 15b): Kotlin-on-the-JVM's String.toDoubleOrNull() -- what every 2.7e GPX / KML / GeoJSON parser calls on
 * a coordinate, an elevation or a number in a file -- for the shared code. On the JVM (Android) Kotlin screens the text with the grammar
 * of java.lang.Double.valueOf (its javadoc's regular expression) and then calls Double.parseDouble, so the accepted texts are exactly
 * Java's:
 *   - whitespace (chars <= ' ') on either side;
 *   - an optional sign; "NaN"; "Infinity";
 *   - decimal: digits with an optional point and optional exponent ("1.", ".5", "1e5"), then an optional f / F / d / D;
 *   - hexadecimal: 0x / 0X, hex digits with an optional point, a REQUIRED binary exponent p / P, then an optional f / F / d / D.
 * Anything else -> null. Kotlin/Native's own toDoubleOrNull is a different parser (owner rule 4, 11-09: Java-specific behaviour goes
 * through GtJava / GtTime / GtOrgJson / this), so the iPad reads "37.1f", " 12 " and "0x1.8p1" as the Droid does.
 * The decimal value itself comes from the platform's parser (Android: Java's own; correctly rounded on both platforms); the hexadecimal
 * value is computed here, exactly, round half to even (Java's rule).
 * ⚠ GtOrgJson.javaParseDouble (15a) has the same decimal rule but no hexadecimal; not changed here (15a is certified and committed).
 * ⚠ GtRead.parseGpxTrackPoints (step 7) still calls Kotlin's own toDoubleOrNull -- identical on Android, an iPad item for the owner.
 */
object GtJavaParse {
    private val DECIMAL = Regex("""[+-]?(NaN|Infinity|((\d+\.?\d*|\.\d+)([eE][+-]?\d+)?[fFdD]?))""")
    private val HEX = Regex("""([+-]?)0[xX]([0-9a-fA-F]*)(\.([0-9a-fA-F]*))?[pP]([+-]?\d+)[fFdD]?""")

    fun toDoubleOrNull(s0: String): Double? {
        val s = s0.trim { it <= ' ' }
        if (s.isEmpty()) return null
        if (DECIMAL.matches(s)) {
            val unsigned = s.removePrefix("+").removePrefix("-")
            if (unsigned == "NaN") return Double.NaN
            if (unsigned == "Infinity") return if (s.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            var body = if (s.last() in "fFdD") s.dropLast(1) else s
            // the platform parser gets the plainest form of the same number ("1." -> "1.0", ".5" -> "0.5", "-.5e3" -> "-0.5e3"), so its
            // own extras or gaps never decide what is accepted -- the grammar above already did. Same value on every parser.
            val sign = if (body.startsWith("+") || body.startsWith("-")) body.substring(0, 1) else ""
            var num = body.substring(sign.length)
            if (num.startsWith(".")) num = "0$num"
            val ei = num.indexOfFirst { it == 'e' || it == 'E' }
            val mant = if (ei < 0) num else num.substring(0, ei)
            if (mant.endsWith(".")) num = mant + "0" + (if (ei < 0) "" else num.substring(ei))
            body = sign + num
            return body.toDouble()
        }
        val m = HEX.matchEntire(s) ?: return null
        val neg = m.groupValues[1] == "-"
        val whole = m.groupValues[2]
        val frac = m.groupValues[4]
        if (whole.isEmpty() && frac.isEmpty()) return null   // "0x.p1" is not a number
        val expText = m.groupValues[5]
        val v = hexValue(whole + frac, frac.length, expText)
        return if (neg) -v else v
    }

    /** The exact value of the hex digits [digits] x 16^-[fracDigits] x 2^[expText], rounded half even to a double. */
    private fun hexValue(digits: String, fracDigits: Int, expText: String): Double {
        // the binary exponent can be huge ("p99999999999"): clamp -- far beyond the double range either way
        val e2 = (expText.removePrefix("+").let { t -> if (t.startsWith("-")) -clampInt(t.drop(1)) else clampInt(t) })
        var mant = 0L
        var dropped = 0          // hex digits not kept (each 4 bits)
        var sticky = false
        var seen = false
        for (ch in digits) {
            val d = ch.digitToInt(16)
            if (!seen && d == 0) continue          // leading zeros
            seen = true
            if (mant < (1L shl 56)) mant = mant * 16 + d else { dropped++; if (d != 0) sticky = true }
        }
        if (mant == 0L) return 0.0
        val binExp = e2.toLong() - 4L * fracDigits + 4L * dropped     // value = mant x 2^binExp (+ sticky below the last kept bit)
        val hb = 63 - mant.countLeadingZeroBits()                     // highest set bit of mant
        val e = hb + binExp                                           // the value's unbiased exponent
        if (e > 1023) return Double.POSITIVE_INFINITY
        val avail: Long = if (e >= -1022) 53 else 53 - (-1022 - e)    // significand bits the result can hold
        if (avail < 0) return 0.0
        val shift = (hb + 1) - avail                                  // bits of mant to drop
        var kept: Long
        if (shift > 0) {
            if (shift > 62) {
                // everything is dropped: only "above half" can round up to the smallest step
                return if (avail == 0L && (mant.countOneBits() > 1 || sticky)) scale(1L, e - avail + 1) else 0.0
            }
            val s = shift.toInt()
            val drop = mant and ((1L shl s) - 1)
            kept = mant ushr s
            val half = 1L shl (s - 1)
            if (drop > half || (drop == half && (sticky || (kept and 1L) == 1L))) kept++
        } else {
            kept = mant shl (-shift).toInt()
        }
        return scale(kept, e - avail + 1)
    }

    /** [k] x 2^[p], exact: k <= 2^53 and p is -1074 (a subnormal result) or the normal exponent minus 52, so 2^p is a double and the
     *  product is representable (or overflows to infinity) -- one IEEE multiply, no rounding. */
    private fun scale(k: Long, p: Long): Double = k.toDouble() * pow2(p)

    private fun pow2(p: Long): Double = when {
        p >= -1022 && p <= 1023 -> Double.fromBits((p + 1023) shl 52)
        p < -1022 && p >= -1074 -> Double.fromBits(1L shl (p + 1074).toInt())
        p > 1023 -> Double.POSITIVE_INFINITY
        else -> 0.0
    }

    private fun clampInt(t: String): Int = t.trimStart('0').let { if (it.length > 9) 999_999_999 else if (it.isEmpty()) 0 else it.toInt() }
}
