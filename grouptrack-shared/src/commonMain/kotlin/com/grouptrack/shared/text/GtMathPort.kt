package com.grouptrack.shared.text

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * GTMATH-2026-11-10 (Fred 11-10 07:17 "find it", 07:37 "solve the issue"): ANDROID's own sin / cos / asin / hypot, in plain Kotlin, so
 * the iPad computes exactly the bits the Droid does. Used by GtMath on the iPad only (on Android GtMath IS java.lang.Math -- unchanged).
 *
 * WHY: 15e cert 2026-11-09_1919: 5 tracks' distance_miles differed in the last bit, Droid vs iPad. MATHPROBE 11-10 (iPad simulator):
 * the first differing step was cos(0.6808...) -- Apple ...5005, Android ...5006, the exact value ...5006. Apple's libm is not correctly
 * rounded there; Android's is.
 * WHAT ANDROID RUNS (MATHPROBE 11-10 on Droid 1: 6,000,000 values from 1.47 million real track/trail points + seeded random, run here
 * against the Droid's own results, bit for bit):
 *   cos, sin = FreeBSD msun (bionic's upstream): FreeBSD's __kernel_cos (bde's rewrite -- NOT fdlibm's, which differed on 56,695 of
 *              1.5 million) and __kernel_sin, plain IEEE arithmetic (no fused multiply-add: the fused variant differed on 5,556);
 *              1,500,000 / 1,500,000 each, incl. the angle reduction for latitudes over 45 degrees.
 *   asin, hypot = fdlibm (as JDK 21's FdLibm.Asin / FdLibm.Hypot): 1,500,000 / 1,500,000 each.
 * NOT REACHED by GroupTrack's inputs and so not proven on the Droid: the argument reduction above 3pi/4 (FreeBSD's "medium" and
 * "large" paths, ported as written; the large path uses fdlibm's __kernel_rem_pio2 with jk = 4, as FreeBSD's prec 1).
 * Every Java (int) cast is Kotlin's toInt() (both truncate toward zero and saturate); floor / abs / sqrt are exact on every platform.
 */
object GtMathPort {
    // ---- bit helpers (fdlibm's __HI / __LO) ----
    private fun hi(x: Double): Int = (x.toRawBits() ushr 32).toInt()
    private fun lo(x: Double): Int = x.toRawBits().toInt()
    private fun withLo(x: Double, low: Int): Double = Double.fromBits((x.toRawBits() and -0x100000000L) or (low.toLong() and 0xFFFFFFFFL))
    private fun withHi(x: Double, high: Int): Double = Double.fromBits((x.toRawBits() and 0xFFFFFFFFL) or (high.toLong() shl 32))
    private fun hiLo(high: Int, low: Int): Double = Double.fromBits((high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL))
    /** 2^k exactly, k in [-1022, 1023] (Math.scalb(1.0, k) / Math.powerOfTwoD). */
    private fun pow2(k: Int): Double = Double.fromBits((k + 1023).toLong() shl 52)
    /** Math.scalb(z, k) for the small k __kernel_rem_pio2 uses (|k| <= 1022): exact. */
    private fun scalb(z: Double, k: Int): Double = z * pow2(k)

    private const val TWO24 = 16777216.0                 // 0x1.0p24
    private const val TWON24 = 5.9604644775390625E-8     // 0x1.0p-24

    // ---- FreeBSD k_cos.c ----
    private const val C1 = 4.16666666666666019037e-02
    private const val C2 = -1.38888888888741095749e-03
    private const val C3 = 2.48015872894767294178e-05
    private const val C4 = -2.75573143513906633035e-07
    private const val C5 = 2.08757232129817482790e-09
    private const val C6 = -1.13596475577881948265e-11
    private fun kernelCos(x: Double, y: Double): Double {
        val z = x * x
        var w = z * z
        val r = z * (C1 + z * (C2 + z * C3)) + w * w * (C4 + z * (C5 + z * C6))
        val hz = 0.5 * z
        w = 1.0 - hz
        return w + (((1.0 - w) - hz) + (z * r - x * y))
    }

    // ---- FreeBSD k_sin.c ----
    private const val S1 = -1.66666666666666324348e-01
    private const val S2 = 8.33333333332248946124e-03
    private const val S3 = -1.98412698298579493134e-04
    private const val S4 = 2.75573137070700676789e-06
    private const val S5 = -2.50507602534068634195e-08
    private const val S6 = 1.58969099521155010221e-10
    private fun kernelSin(x: Double, y: Double, iy: Int): Double {
        val z = x * x
        val w = z * z
        val r = S2 + z * (S3 + z * S4) + z * w * (S5 + z * S6)
        val v = z * x
        return if (iy == 0) x + v * (S1 + z * r) else x - ((z * (0.5 * y - v * r) - y) - v * S1)
    }

    // ---- FreeBSD e_rem_pio2.c ----
    private const val INVPIO2 = 6.36619772367581382433e-01
    private const val PIO2_1 = 1.57079632673412561417e+00
    private const val PIO2_1T = 6.07710050650619224932e-11
    private const val PIO2_2 = 6.07710050630396597660e-11
    private const val PIO2_2T = 2.02226624879595063154e-21
    private const val PIO2_3 = 2.02226624871116645580e-21
    private const val PIO2_3T = 8.47842766036889956997e-32
    private const val RNINT_C = 6755399441055744.0       // 0x1.8p52: (x + C) - C rounds to the nearest integer, ties to even

    /** Returns n; y[0] + y[1] = x - n*pi/2. */
    private fun remPio2(x: Double, y: DoubleArray): Int {
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        var medium = false
        if (ix <= 0x400f6a7a) {                                  // |x| ~<= 5pi/4
            if ((ix and 0xfffff) == 0x921fb) medium = true        // |x| ~= pi/2 or 2pi/2: cancellation
            else if (ix <= 0x4002d97c) {                          // |x| ~<= 3pi/4
                if (hx > 0) { val z = x - PIO2_1; y[0] = z - PIO2_1T; y[1] = (z - y[0]) - PIO2_1T; return 1 }
                val z = x + PIO2_1; y[0] = z + PIO2_1T; y[1] = (z - y[0]) + PIO2_1T; return -1
            } else {
                if (hx > 0) { val z = x - 2 * PIO2_1; y[0] = z - 2 * PIO2_1T; y[1] = (z - y[0]) - 2 * PIO2_1T; return 2 }
                val z = x + 2 * PIO2_1; y[0] = z + 2 * PIO2_1T; y[1] = (z - y[0]) + 2 * PIO2_1T; return -2
            }
        }
        if (!medium && ix <= 0x401c463b) {                       // |x| ~<= 9pi/4
            if (ix <= 0x4015fdbc) {                               // |x| ~<= 7pi/4
                if (ix == 0x4012d97c) medium = true               // |x| ~= 3pi/2
                else {
                    if (hx > 0) { val z = x - 3 * PIO2_1; y[0] = z - 3 * PIO2_1T; y[1] = (z - y[0]) - 3 * PIO2_1T; return 3 }
                    val z = x + 3 * PIO2_1; y[0] = z + 3 * PIO2_1T; y[1] = (z - y[0]) + 3 * PIO2_1T; return -3
                }
            } else {
                if (ix == 0x401921fb) medium = true               // |x| ~= 4pi/2
                else {
                    if (hx > 0) { val z = x - 4 * PIO2_1; y[0] = z - 4 * PIO2_1T; y[1] = (z - y[0]) - 4 * PIO2_1T; return 4 }
                    val z = x + 4 * PIO2_1; y[0] = z + 4 * PIO2_1T; y[1] = (z - y[0]) + 4 * PIO2_1T; return -4
                }
            }
        }
        if (medium || ix < 0x413921fb) {                         // |x| ~< 2^20*(pi/2), medium size
            val fn = (x * INVPIO2 + RNINT_C) - RNINT_C
            val n = fn.toInt()
            var r = x - fn * PIO2_1
            var w = fn * PIO2_1T                                  // 1st round, good to 85 bits
            val j = ix shr 20
            y[0] = r - w
            var i = j - ((hi(y[0]) shr 20) and 0x7ff)
            if (i > 16) {                                         // 2nd iteration, good to 118
                var t = r
                w = fn * PIO2_2
                r = t - w
                w = fn * PIO2_2T - ((t - r) - w)
                y[0] = r - w
                i = j - ((hi(y[0]) shr 20) and 0x7ff)
                if (i > 49) {                                     // 3rd iteration, 151 bits
                    t = r
                    w = fn * PIO2_3
                    r = t - w
                    w = fn * PIO2_3T - ((t - r) - w)
                    y[0] = r - w
                }
            }
            y[1] = (r - y[0]) - w
            return n
        }
        if (ix >= 0x7ff00000) { y[0] = x - x; y[1] = y[0]; return 0 }   // inf or NaN
        // large: z = scalbn(|x|, ilogb(x) - 23)
        val e0 = (ix shr 20) - 1046
        var z = hiLo(ix - (e0 shl 20), lo(x))
        val tx = DoubleArray(3)
        for (k in 0 until 2) { tx[k] = z.toInt().toDouble(); z = (z - tx[k]) * TWO24 }
        tx[2] = z
        var nx = 3
        while (tx[nx - 1] == 0.0) nx--
        val ty = DoubleArray(2)
        val n = kernelRemPio2(tx, ty, e0, nx)
        if (hx < 0) { y[0] = -ty[0]; y[1] = -ty[1]; return -n }
        y[0] = ty[0]; y[1] = ty[1]
        return n
    }

    // ---- fdlibm __kernel_rem_pio2 (JDK 21 FdLibm.KernelRemPio2), two-term result; jk = 4 (FreeBSD prec 1 = JDK prec 2) ----
    private val IPIO2 = intArrayOf(
        0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, 0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A,
        0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129, 0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41,
        0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8, 0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF,
        0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, 0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08,
        0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3, 0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880,
        0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B)
    private val PIO2 = doubleArrayOf(
        Double.fromBits(0x3ff921fb40000000L), Double.fromBits(0x3e74442d00000000L), Double.fromBits(0x3cf8469880000000L),
        Double.fromBits(0x3b78cc5160000000L), Double.fromBits(0x39f01b8380000000L), Double.fromBits(0x387a252040000000L),
        Double.fromBits(0x36e3822280000000L), Double.fromBits(0x3569f31d00000000L))

    private fun kernelRemPio2(x: DoubleArray, y: DoubleArray, e0: Int, nx: Int): Int {
        val iq = IntArray(20); val f = DoubleArray(20); val fq = DoubleArray(20); val q = DoubleArray(20)
        val jk = 4; val jp = jk
        val jx = nx - 1
        var jv = (e0 - 3) / 24
        if (jv < 0) jv = 0
        var q0 = e0 - 24 * (jv + 1)
        var j = jv - jx
        val m = jx + jk
        for (i in 0..m) { f[i] = if (j < 0) 0.0 else IPIO2[j].toDouble(); j++ }
        for (i in 0..jk) { var fw = 0.0; for (jj in 0..jx) fw += x[jj] * f[jx + i - jj]; q[i] = fw }
        var jz = jk
        var z: Double; var n: Int; var ih: Int
        while (true) {
            z = q[jz]
            run { var i = 0; var jj = jz; while (jj > 0) { val fw = (TWON24 * z).toInt().toDouble(); iq[i] = (z - TWO24 * fw).toInt(); z = q[jj - 1] + fw; i++; jj-- } }
            z = scalb(z, q0)
            z -= 8.0 * floor(z * 0.125)
            n = z.toInt()
            z -= n.toDouble()
            ih = 0
            if (q0 > 0) {
                val i = iq[jz - 1] shr (24 - q0)
                n += i
                iq[jz - 1] -= i shl (24 - q0)
                ih = iq[jz - 1] shr (23 - q0)
            } else if (q0 == 0) {
                ih = iq[jz - 1] shr 23
            } else if (z >= 0.5) {
                ih = 2
            }
            if (ih > 0) {
                n += 1
                var carry = 0
                for (i in 0 until jz) {
                    val jj = iq[i]
                    if (carry == 0) { if (jj != 0) { carry = 1; iq[i] = 0x1000000 - jj } } else iq[i] = 0xffffff - jj
                }
                if (q0 > 0) {
                    when (q0) { 1 -> iq[jz - 1] = iq[jz - 1] and 0x7fffff; 2 -> iq[jz - 1] = iq[jz - 1] and 0x3fffff }
                }
                if (ih == 2) {
                    z = 1.0 - z
                    if (carry != 0) z -= scalb(1.0, q0)
                }
            }
            if (z == 0.0) {
                var jj = 0
                for (i in jz - 1 downTo jk) jj = jj or iq[i]
                if (jj == 0) {
                    var k = 1
                    while (iq[jk - k] == 0) k++
                    for (i in jz + 1..jz + k) {
                        f[jx + i] = IPIO2[jv + i].toDouble()
                        var fw = 0.0; for (j2 in 0..jx) fw += x[j2] * f[jx + i - j2]; q[i] = fw
                    }
                    jz += k
                    continue
                }
            }
            break
        }
        if (z == 0.0) {
            jz -= 1; q0 -= 24
            while (iq[jz] == 0) { jz--; q0 -= 24 }
        } else {
            z = scalb(z, -q0)
            if (z >= TWO24) {
                val fw = (TWON24 * z).toInt().toDouble()
                iq[jz] = (z - TWO24 * fw).toInt()
                jz += 1; q0 += 24
                iq[jz] = fw.toInt()
            } else iq[jz] = z.toInt()
        }
        var fw = scalb(1.0, q0)
        for (i in jz downTo 0) { q[i] = fw * iq[i].toDouble(); fw *= TWON24 }
        for (i in jz downTo 0) {
            fw = 0.0; var k = 0
            while (k <= jp && k <= jz - i) { fw += PIO2[k] * q[i + k]; k++ }
            fq[jz - i] = fw
        }
        fw = 0.0
        for (i in jz downTo 0) fw += fq[i]
        y[0] = if (ih == 0) fw else -fw
        fw = fq[0] - fw
        for (i in 1..jz) fw += fq[i]
        y[1] = if (ih == 0) fw else -fw
        return n and 7
    }

    // ---- FreeBSD s_cos.c / s_sin.c ----
    fun cos(x: Double): Double {
        val ix = hi(x) and 0x7fffffff
        if (ix <= 0x3fe921fb) {                                   // |x| ~< pi/4
            if (ix < 0x3e46a09e) { if (x.toInt() == 0) return 1.0 } // |x| < 2**-27 * sqrt(2)
            return kernelCos(x, 0.0)
        }
        if (ix >= 0x7ff00000) return x - x                        // inf or NaN
        val y = DoubleArray(2)
        val n = remPio2(x, y)
        return when (n and 3) {
            0 -> kernelCos(y[0], y[1])
            1 -> -kernelSin(y[0], y[1], 1)
            2 -> -kernelCos(y[0], y[1])
            else -> kernelSin(y[0], y[1], 1)
        }
    }

    fun sin(x: Double): Double {
        val ix = hi(x) and 0x7fffffff
        if (ix <= 0x3fe921fb) {                                   // |x| ~< pi/4
            if (ix < 0x3e500000) { if (x.toInt() == 0) return x }  // |x| < 2**-26
            return kernelSin(x, 0.0, 0)
        }
        if (ix >= 0x7ff00000) return x - x
        val y = DoubleArray(2)
        val n = remPio2(x, y)
        return when (n and 3) {
            0 -> kernelSin(y[0], y[1], 1)
            1 -> kernelCos(y[0], y[1])
            2 -> -kernelSin(y[0], y[1], 1)
            else -> -kernelCos(y[0], y[1])
        }
    }

    // ---- fdlibm e_asin.c (JDK 21 FdLibm.Asin) ----
    private fun b(u: ULong) = Double.fromBits(u.toLong())
    private val PIO2_HI = b(0x3ff921fb54442d18UL)
    private val PIO2_LO = b(0x3c91a62633145c07UL)
    private val PIO4_HI = b(0x3fe921fb54442d18UL)
    private val PS0 = b(0x3fc5555555555555UL)
    private val PS1 = b(0xbfd4d61203eb6f7dUL)
    private val PS2 = b(0x3fc9c1550e884455UL)
    private val PS3 = b(0xbfa48228b5688f3bUL)
    private val PS4 = b(0x3f49efe07501b288UL)
    private val PS5 = b(0x3f023de10dfdf709UL)
    private val QS1 = b(0xc0033a271c8a2d4bUL)
    private val QS2 = b(0x40002ae59c598ac8UL)
    private val QS3 = b(0xbfe6066c1b8d0159UL)
    private val QS4 = b(0x3fb3b8c5b12e9282UL)
    private const val HUGE = 1.0e+300

    fun asin(x: Double): Double {
        var t = 0.0
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x3ff00000) {                                   // |x| >= 1
            if (((ix - 0x3ff00000) or lo(x)) == 0) return x * PIO2_HI + x * PIO2_LO   // asin(+-1) = +-pi/2
            return (x - x) / (x - x)                              // asin(|x| > 1) is NaN
        } else if (ix < 0x3fe00000) {                             // |x| < 0.5
            if (ix < 0x3e400000) {                                // |x| < 2**-27
                if (HUGE + x > 1.0) return x
            } else {
                t = x * x
            }
            val p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))))
            val q = 1.0 + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)))
            val w = p / q
            return x + x * w
        }
        var w = 1.0 - abs(x)                                       // 1 > |x| >= 0.5
        t = w * 0.5
        var p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))))
        var q = 1.0 + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)))
        val s = sqrt(t)
        if (ix >= 0x3FEF3333) {                                    // |x| > 0.975
            w = p / q
            t = PIO2_HI - (2.0 * (s + s * w) - PIO2_LO)
        } else {
            w = s
            w = withLo(w, 0)
            val c = (t - w * w) / (s + w)
            val r = p / q
            p = 2.0 * s * r - (PIO2_LO - 2.0 * c)
            q = PIO4_HI - 2.0 * w
            t = PIO4_HI - (p - q)
        }
        return if (hx > 0) t else -t
    }

    // ---- fdlibm e_hypot.c (JDK 21 FdLibm.Hypot) ----
    private val TWO_MINUS_600 = pow2(-600)
    private val TWO_PLUS_600 = pow2(600)
    private val HYP_BIG = b(0x5f300000ffffffffUL)                // 0x1.00000_ffff_ffffp500
    private val TWO_M500 = pow2(-500)
    private val MIN_NORMAL = Double.fromBits(0x0010000000000000L)

    fun hypot(x: Double, y: Double): Double {
        var a = abs(x)
        var bb = abs(y)
        if (!a.isFinite() || !bb.isFinite()) {
            return if (a == Double.POSITIVE_INFINITY || bb == Double.POSITIVE_INFINITY) Double.POSITIVE_INFINITY else a + bb
        }
        if (bb > a) { val tmp = a; a = bb; bb = tmp }
        var ha = hi(a)
        var hb = hi(bb)
        if ((ha - hb) > 0x3c00000) return a + bb                  // x / y > 2**60
        var k = 0
        if (a > HYP_BIG) {                                         // a > ~2**500
            ha -= 0x25800000; hb -= 0x25800000
            a *= TWO_MINUS_600; bb *= TWO_MINUS_600
            k += 600
        }
        var t1: Double
        var t2: Double
        if (bb < TWO_M500) {                                       // b < 2**-500
            if (bb < MIN_NORMAL) {
                if (bb == 0.0) return a
                t1 = pow2(1022)
                bb *= t1; a *= t1
                k -= 1022
            } else {
                ha += 0x25800000; hb += 0x25800000
                a *= TWO_PLUS_600; bb *= TWO_PLUS_600
                k -= 600
            }
        }
        var w = a - bb
        if (w > bb) {
            t1 = withHi(0.0, ha)
            t2 = a - t1
            w = sqrt(t1 * t1 - (bb * (-bb) - t2 * (a + t1)))
        } else {
            a = a + a
            val y1 = withHi(0.0, hb)
            val y2 = bb - y1
            t1 = withHi(0.0, ha + 0x00100000)
            t2 = a - t1
            w = sqrt(t1 * y1 - (w * (-w) - (t1 * y2 + t2 * bb)))
        }
        return if (k != 0) pow2(k) * w else w
    }
}
