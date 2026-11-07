package com.grouptrack.shared.text

/**
 * GTFMT-2026-11-07 (2.8 step 13): String.format for the shared code -- common Kotlin has none. Gives BYTE-IDENTICAL text to the
 * JVM/Android String.format(Locale.US, ...) the 2.7e code used, because the route builder's ride descriptions ("Mile 8, Mountain
 * Spring Peak") and its numbers are certified old-vs-new character for character.
 *  - f(x, n)  = "%.{n}f": Java's own rule -- the SHORTEST decimal digits of the double (Double.toString), then HALF_UP at n places
 *               (so 0.15 -> "0.2" and 2.5 -> "3", where C's printf would give "0.1" and "2"). The sign is kept: -0.04 -> "-0.0".
 *  - d(v)     = "%,d": thousands grouped with ",".
 * Proved 11-07 against Java String.format over 4,300,100 values (0 differences); GtFmtMacTest checks the same on the iPad simulator
 * against Java's answers (gtfmt_golden_2026-11-07.txt), because Kotlin/Native has its own Double.toString.
 */
object GtFmt {
    fun f(x: Double, n: Int): String {
        if (x.isNaN()) return "NaN"
        if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
        val neg = x < 0 || (x == 0.0 && 1.0 / x < 0)
        var s = kotlin.math.abs(x).toString()
        var e = 0
        val ei = s.indexOfFirst { it == 'E' || it == 'e' }
        if (ei >= 0) { e = s.substring(ei + 1).removePrefix("+").toInt(); s = s.substring(0, ei) }
        val dot = s.indexOf('.')
        var digits = if (dot < 0) s else s.substring(0, dot) + s.substring(dot + 1)
        var p = (if (dot < 0) s.length else dot) + e
        var lz = 0
        while (lz < digits.length - 1 && digits[lz] == '0') lz++
        digits = digits.substring(lz); p -= lz
        val shift = p + n
        var whole: String
        val next: Char
        when {
            shift >= digits.length -> { whole = digits + "0".repeat(shift - digits.length); next = '0' }
            shift >= 0 -> { whole = if (shift == 0) "0" else digits.substring(0, shift); next = digits[shift] }
            else -> { whole = "0"; next = '0' }
        }
        if (next >= '5') whole = inc(whole)
        val b = StringBuilder(whole)
        while (b.length < n + 1) b.insert(0, '0')
        var r = if (n == 0) b.toString() else b.substring(0, b.length - n) + "." + b.substring(b.length - n)
        var k = 0
        while (k < r.length - 1 && r[k] == '0' && r[k + 1] != '.') k++
        r = r.substring(k)
        return if (neg) "-$r" else r
    }

    fun d(v: Long): String {
        val s = v.toString().removePrefix("-")
        val b = StringBuilder()
        var c = 0
        for (i in s.indices.reversed()) { b.append(s[i]); if (++c % 3 == 0 && i > 0) b.append(',') }
        return (if (v < 0) "-" else "") + b.reverse().toString()
    }

    fun d(v: Int): String = d(v.toLong())

    private fun inc(d: String): String {
        val c = d.toCharArray()
        var i = c.size - 1
        while (i >= 0) { if (c[i] == '9') { c[i] = '0'; i-- } else { c[i] = c[i] + 1; return c.concatToString() } }
        return "1" + c.concatToString()
    }
}
