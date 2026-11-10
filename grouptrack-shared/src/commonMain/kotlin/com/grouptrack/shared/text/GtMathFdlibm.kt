package com.grouptrack.shared.text

import kotlin.math.abs

/**
 * MATHTILE-2026-11-10 (2.8 step 16a, Fred 11-10 17:0x "port them to GtMath"): ANDROID's own tan / atan / sinh (+ the exp / expm1 sinh
 * calls), in plain Kotlin, so the iPad computes exactly the bits the Droid does. Used by GtMath on the iPad only (on Android GtMath IS
 * java.lang.Math -- unchanged).
 * WHY: MATHTILE 11-10 (the save-by-area tile math on Droid 1's real inputs -- 400,000 track / trail latitudes, every tile row's edge at
 * z0-14 + 80,000 at z15-18): Apple's libm differs from the Droid in the last bit on tan 158,960 / 400,000, sinh 29,336 / 112,782,
 * atan 6,943 / 112,782 -- so 15,899 of the stored box edges (O) and 25,040 tile-row values (Y) would differ.
 * WHAT ANDROID RUNS: fdlibm, as JDK 21's java.lang.FdLibm (Tan, RemPio2, Atan, Sinh, Expm1, Exp). Proven 11-10 against the Droid's own
 * results: StrictMath (fdlibm) = the Droid on tan 400,000 / 400,000, atan 112,782 / 112,782, sinh 112,782 / 112,782.
 * This file is that Java, transliterated line for line (each constant as its exact bits -- Kotlin has no hex floats); Java's (int) of a
 * double = Kotlin toInt() (truncate toward zero, saturate); no fused multiply-add anywhere (Java's strict IEEE arithmetic; Kotlin/Native
 * does not contract -- as GtMathPort's cos, proven 11-10). The huge-argument reduction (|x| > 2^20 pi/2) is fdlibm's
 * __kernel_rem_pio2 with prec 2 (jk = 4) -- GtMathPort.kernelRemPio2, the same routine and table.
 * NOT REACHED by GroupTrack's inputs and so not proven on the Droid: tan's medium / large reduction (|x| >= 3pi/4), exp / sinh for
 * |x| >= 22, the overflow / underflow / NaN branches -- ported as written.
 */
object GtMathFdlibm {
    private fun f(u: ULong): Double = Double.fromBits(u.toLong())
    private fun hi(x: Double): Int = (x.toRawBits() ushr 32).toInt()
    private fun lo(x: Double): Int = x.toRawBits().toInt()
    private fun withLo(x: Double, low: Int): Double = Double.fromBits((x.toRawBits() and -0x100000000L) or (low.toLong() and 0xFFFFFFFFL))
    private fun withHi(x: Double, high: Int): Double = Double.fromBits((x.toRawBits() and 0xFFFFFFFFL) or (high.toLong() shl 32))
    private const val EXP_SIGNIF_BITS = 0x7fffffff
    private const val EXP_BITS = 0x7ff00000
    private const val TWO24 = 16777216.0      // 0x1.0p24
    private const val HUGE = 1.0e+300
    private const val TINY = 1.0e-300
    private const val SHUGE = 1.0e307

    private val PIO4 = f(0x3fe921fb54442d18UL)   // 0x1.921fb54442d18p-1
    private val PIO4LO = f(0x3c81a62633145c07UL)   // 0x1.1a62633145c07p-55
    private val INVPIO2 = f(0x3fe45f306dc9c883UL)   // 0x1.45f306dc9c883p-1
    private val PIO2_1 = f(0x3ff921fb54400000UL)   // 0x1.921fb544p0
    private val PIO2_1T = f(0x3dd0b4611a626331UL)   // 0x1.0b4611a626331p-34
    private val PIO2_2 = f(0x3dd0b4611a600000UL)   // 0x1.0b4611a6p-34
    private val PIO2_2T = f(0x3ba3198a2e037073UL)   // 0x1.3198a2e037073p-69
    private val PIO2_3 = f(0x3ba3198a2e000000UL)   // 0x1.3198a2ep-69
    private val PIO2_3T = f(0x397b839a252049c1UL)   // 0x1.b839a252049c1p-104
    private val TWOM1000 = f(0x0170000000000000UL)   // 0x1.0p-1000
    private val E_O_THRESHOLD = f(0x40862e42fefa39efUL)   // 0x1.62e42fefa39efp9
    private val E_U_THRESHOLD = f(0xc0874910d52d3051UL)   // -0x1.74910d52d3051p9
    private val LN2HI = f(0x3fe62e42fee00000UL)   // 0x1.62e42feep-1
    private val LN2LO = f(0x3dea39ef35793c76UL)   // 0x1.a39ef35793c76p-33
    private val INVLN2 = f(0x3ff71547652b82feUL)   // 0x1.71547652b82fep0
    private val P1 = f(0x3fc555555555553eUL)   // 0x1.555555555553ep-3
    private val P2 = f(0xbf66c16c16bebd93UL)   // -0x1.6c16c16bebd93p-9
    private val P3 = f(0x3f11566aaf25de2cUL)   // 0x1.1566aaf25de2cp-14
    private val P4 = f(0xbebbbd41c5d26bf1UL)   // -0x1.bbd41c5d26bf1p-20
    private val P5 = f(0x3e66376972bea4d0UL)   // 0x1.6376972bea4d0p-25
    private val Q1 = f(0xbfa11111111110f4UL)   // -0x1.11111111110f4p-5
    private val Q2 = f(0x3f5a01a019fe5585UL)   // 0x1.a01a019fe5585p-10
    private val Q3 = f(0xbf14ce199eaadbb7UL)   // -0x1.4ce199eaadbb7p-14
    private val Q4 = f(0x3ed0cfca86e65239UL)   // 0x1.0cfca86e65239p-18
    private val Q5 = f(0xbe8afdb76e09c32dUL)   // -0x1.afdb76e09c32dp-23
    private val TAN_T = doubleArrayOf(f(0x3fd5555555555563UL), f(0x3fc111111110fe7aUL), f(0x3faba1ba1bb341feUL), f(0x3f9664f48406d637UL), f(0x3f8226e3e96e8493UL), f(0x3f6d6d22c9560328UL), f(0x3f57dbc8fee08315UL), f(0x3f4344d8f2f26501UL), f(0x3f3026f71a8d1068UL), f(0x3f147e88a03792a6UL), f(0x3f12b80f32f0a7e9UL), f(0xbef375cbdb605373UL), f(0x3efb2a7074bf7ad4UL))
    private val ATANHI = doubleArrayOf(f(0x3fddac670561bb4fUL), f(0x3fe921fb54442d18UL), f(0x3fef730bd281f69bUL), f(0x3ff921fb54442d18UL))
    private val ATANLO = doubleArrayOf(f(0x3c7a2b7f222f65e2UL), f(0x3c81a62633145c07UL), f(0x3c7007887af0cbbdUL), f(0x3c91a62633145c07UL))
    private val AT = doubleArrayOf(f(0x3fd555555555550dUL), f(0xbfc999999998ebc4UL), f(0x3fc24924920083ffUL), f(0xbfbc71c6fe231671UL), f(0x3fb745cdc54c206eUL), f(0xbfb3b0f2af749a6dUL), f(0x3fb10d66a0d03d51UL), f(0xbfadde2d52defd9aUL), f(0x3fa97b4b24760debUL), f(0xbfa2b4442c6a6c2fUL), f(0x3f90ad3ae322da11UL))
    private val NPIO2_HW = intArrayOf(0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C, 0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB)

    // ---- FdLibm.Tan ----
    fun tan(x: Double): Double {
        val y = DoubleArray(2)
        val ix = hi(x) and EXP_SIGNIF_BITS
        return if (ix <= 0x3fe921fb) {
            kernelTan(x, 0.0, 1)
        } else if (ix >= EXP_BITS) {
            x - x
        } else {
            val n = remPio2(x, y)
            kernelTan(y[0], y[1], 1 - ((n and 1) shl 1))
        }
    }

    private fun kernelTan(x0: Double, y0: Double, iy: Int): Double {
        var x = x0; var y = y0
        var z: Double; var r: Double; var v: Double; var w: Double; var s: Double
        val hx = hi(x)
        val ix = hx and EXP_SIGNIF_BITS
        if (ix < 0x3e300000) {
            if (x.toInt() == 0) {
                if (((ix or lo(x)) or (iy + 1)) == 0) {
                    return 1.0 / abs(x)
                } else {
                    if (iy == 1) {
                        return x
                    } else {
                        w = x + y; z = w
                        z = withLo(z, 0)
                        v = y - (z - x)
                        val a = -1.0 / w; var t = a
                        t = withLo(t, 0)
                        s = 1.0 + t * z
                        return t + a * (s + t * v)
                    }
                }
            }
        }
        if (ix >= 0x3FE59428) {
            if (hx < 0) { x = -x; y = -y }
            z = PIO4 - x
            w = PIO4LO - y
            x = z + w
            y = 0.0
        }
        z = x * x
        w = z * z
        r = TAN_T[1] + w * (TAN_T[3] + w * (TAN_T[5] + w * (TAN_T[7] + w * (TAN_T[9] + w * TAN_T[11]))))
        v = z * (TAN_T[2] + w * (TAN_T[4] + w * (TAN_T[6] + w * (TAN_T[8] + w * (TAN_T[10] + w * TAN_T[12])))))
        s = z * x
        r = y + z * (s * (r + v) + y)
        r += TAN_T[0] * s
        w = x + r
        if (ix >= 0x3FE59428) {
            v = iy.toDouble()
            return (1 - ((hx shr 30) and 2)).toDouble() * (v - 2.0 * (x - (w * w / (w + v) - r)))
        }
        if (iy == 1) {
            return w
        } else {
            z = w
            z = withLo(z, 0)
            v = r - (z - x)
            val a = -1.0 / w; var t = a
            t = withLo(t, 0)
            s = 1.0 + t * z
            return t + a * (s + t * v)
        }
    }

    // ---- FdLibm.RemPio2 (fdlibm's own -- NOT FreeBSD's rewrite in GtMathPort, which differs near pi/2 and on the medium path) ----
    private fun remPio2(x: Double, y: DoubleArray): Int {
        var z = 0.0; var w: Double; var t: Double; var r: Double
        val tx = DoubleArray(3)
        val hx = hi(x)
        val ix = hx and EXP_SIGNIF_BITS
        if (ix <= 0x3fe921fb) { y[0] = x; y[1] = 0.0; return 0 }
        if (ix < 0x4002d97c) {
            if (hx > 0) {
                z = x - PIO2_1
                if (ix != 0x3ff921fb) { y[0] = z - PIO2_1T; y[1] = (z - y[0]) - PIO2_1T }
                else { z -= PIO2_2; y[0] = z - PIO2_2T; y[1] = (z - y[0]) - PIO2_2T }
                return 1
            } else {
                z = x + PIO2_1
                if (ix != 0x3ff921fb) { y[0] = z + PIO2_1T; y[1] = (z - y[0]) + PIO2_1T }
                else { z += PIO2_2; y[0] = z + PIO2_2T; y[1] = (z - y[0]) + PIO2_2T }
                return -1
            }
        }
        if (ix <= 0x413921fb) {
            t = abs(x)
            val n = (t * INVPIO2 + 0.5).toInt()
            val fn = n.toDouble()
            r = t - fn * PIO2_1
            w = fn * PIO2_1T
            if (n < 32 && ix != NPIO2_HW[n - 1]) {
                y[0] = r - w
            } else {
                val j = ix shr 20
                y[0] = r - w
                var i = j - ((hi(y[0]) shr 20) and 0x7ff)
                if (i > 16) {
                    t = r
                    w = fn * PIO2_2
                    r = t - w
                    w = fn * PIO2_2T - ((t - r) - w)
                    y[0] = r - w
                    i = j - ((hi(y[0]) shr 20) and 0x7ff)
                    if (i > 49) {
                        t = r
                        w = fn * PIO2_3
                        r = t - w
                        w = fn * PIO2_3T - ((t - r) - w)
                        y[0] = r - w
                    }
                }
            }
            y[1] = (r - y[0]) - w
            return if (hx < 0) { y[0] = -y[0]; y[1] = -y[1]; -n } else n
        }
        if (ix >= EXP_BITS) { y[0] = x - x; y[1] = y[0]; return 0 }
        z = withLo(z, lo(x))
        val e0 = (ix shr 20) - 1046
        z = withHi(z, ix - (e0 shl 20))
        for (i in 0 until 2) {
            tx[i] = z.toInt().toDouble()
            z = (z - tx[i]) * TWO24
        }
        tx[2] = z
        var nx = 3
        while (tx[nx - 1] == 0.0) nx--
        val n = GtMathPort.kernelRemPio2(tx, y, e0, nx)
        if (hx < 0) { y[0] = -y[0]; y[1] = -y[1]; return -n }
        return n
    }

    // ---- FdLibm.Atan ----
    fun atan(x0: Double): Double {
        var x = x0
        val w: Double; val s1: Double; val s2: Double; var z: Double
        val id: Int
        val hx = hi(x)
        val ix = hx and EXP_SIGNIF_BITS
        if (ix >= 0x44100000) {
            if (ix > EXP_BITS || (ix == EXP_BITS && (lo(x) != 0))) return x + x
            return if (hx > 0) ATANHI[3] + ATANLO[3] else -ATANHI[3] - ATANLO[3]
        }
        if (ix < 0x3fdc0000) {
            if (ix < 0x3e200000) {
                if (HUGE + x > 1.0) return x
            }
            id = -1
        } else {
            x = abs(x)
            if (ix < 0x3ff30000) {
                if (ix < 0x3fe60000) { id = 0; x = (2.0 * x - 1.0) / (2.0 + x) }
                else { id = 1; x = (x - 1.0) / (x + 1.0) }
            } else {
                if (ix < 0x40038000) { id = 2; x = (x - 1.5) / (1.0 + 1.5 * x) }
                else { id = 3; x = -1.0 / x }
            }
        }
        z = x * x
        w = z * z
        s1 = z * (AT[0] + w * (AT[2] + w * (AT[4] + w * (AT[6] + w * (AT[8] + w * AT[10])))))
        s2 = w * (AT[1] + w * (AT[3] + w * (AT[5] + w * (AT[7] + w * AT[9]))))
        return if (id < 0) {
            x - x * (s1 + s2)
        } else {
            z = ATANHI[id] - ((x * (s1 + s2) - ATANLO[id]) - x)
            if (hx < 0) -z else z
        }
    }

    // ---- FdLibm.Exp ----
    fun exp(x0: Double): Double {
        var x = x0
        var y: Double
        var hi = 0.0; var lo = 0.0
        val c: Double; var t: Double
        var k = 0
        var hx = hi(x)
        val xsb = (hx shr 31) and 1
        hx = hx and EXP_SIGNIF_BITS
        if (hx >= 0x40862E42) {
            if (hx >= 0x7ff00000) {
                return if (((hx and 0xfffff) or lo(x)) != 0) x + x else if (xsb == 0) x else 0.0
            }
            if (x > E_O_THRESHOLD) return HUGE * HUGE
            if (x < E_U_THRESHOLD) return TWOM1000 * TWOM1000
        }
        if (hx > 0x3fd62e42) {
            if (hx < 0x3FF0A2B2) {
                hi = x - LN2HI * (1 - 2 * xsb)
                lo = if (xsb == 0) LN2LO else -LN2LO
                k = 1 - xsb - xsb
            } else {
                k = (INVLN2 * x + 0.5 * (1 - 2 * xsb)).toInt()
                t = k.toDouble()
                hi = x - t * LN2HI
                lo = t * LN2LO
            }
            x = hi - lo
        } else if (hx < 0x3e300000) {
            if (HUGE + x > 1.0) return 1.0 + x
        } else {
            k = 0
        }
        t = x * x
        c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))))
        if (k == 0) return 1.0 - ((x * c) / (c - 2.0) - x)
        else y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi)
        return if (k >= -1021) {
            withHi(y, hi(y) + (k shl 20))
        } else {
            y = withHi(y, hi(y) + ((k + 1000) shl 20))
            y * TWOM1000
        }
    }

    // ---- FdLibm.Expm1 ----
    fun expm1(x0: Double): Double {
        var x = x0
        var y: Double; val hi: Double; val lo: Double; var c = 0.0; var t: Double; var e: Double
        val k: Int
        var hx = hi(x)
        val xsb = hx and Int.MIN_VALUE            // SIGN_BIT (0x80000000)
        hx = hx and EXP_SIGNIF_BITS
        if (hx >= 0x4043687A) {
            if (hx >= 0x40862E42) {
                if (hx >= 0x7ff00000) {
                    return if (((hx and 0xfffff) or lo(x)) != 0) x + x else if (xsb == 0) x else -1.0
                }
                if (x > E_O_THRESHOLD) return HUGE * HUGE
            }
            if (xsb != 0) {
                if (x + TINY < 0.0) return TINY - 1.0
            }
        }
        if (hx > 0x3fd62e42) {
            if (hx < 0x3FF0A2B2) {
                if (xsb == 0) { hi = x - LN2HI; lo = LN2LO; k = 1 }
                else { hi = x + LN2HI; lo = -LN2LO; k = -1 }
            } else {
                k = (INVLN2 * x + (if (xsb == 0) 0.5 else -0.5)).toInt()
                t = k.toDouble()
                hi = x - t * LN2HI
                lo = t * LN2LO
            }
            x = hi - lo
            c = (hi - x) - lo
        } else if (hx < 0x3c900000) {
            t = HUGE + x
            return x - (t - (HUGE + x))
        } else {
            k = 0
        }
        val hfx = 0.5 * x
        val hxs = x * hfx
        val r1 = 1.0 + hxs * (Q1 + hxs * (Q2 + hxs * (Q3 + hxs * (Q4 + hxs * Q5))))
        t = 3.0 - r1 * hfx
        e = hxs * ((r1 - t) / (6.0 - x * t))
        if (k == 0) {
            return x - (x * e - hxs)
        } else {
            e = (x * (e - c) - c)
            e -= hxs
            if (k == -1) return 0.5 * (x - e) - 0.5
            if (k == 1) {
                return if (x < -0.25) -2.0 * (e - (x + 0.5)) else 1.0 + 2.0 * (x - e)
            }
            if (k <= -2 || k > 56) {
                y = 1.0 - (e - x)
                y = withHi(y, hi(y) + (k shl 20))
                return y - 1.0
            }
            t = 1.0
            if (k < 20) {
                t = withHi(t, 0x3ff00000 - (0x200000 shr k))
                y = t - (e - x)
                y = withHi(y, hi(y) + (k shl 20))
            } else {
                t = withHi(t, ((0x3ff - k) shl 20))
                y = x - (e + t)
                y += 1.0
                y = withHi(y, hi(y) + (k shl 20))
            }
        }
        return y
    }

    // ---- FdLibm.Sinh ----
    fun sinh(x: Double): Double {
        val t: Double; val w: Double
        val jx = hi(x)
        val ix = jx and EXP_SIGNIF_BITS
        if (ix >= EXP_BITS) return x + x
        var h = 0.5
        if (jx < 0) h = -h
        if (ix < 0x40360000) {
            if (ix < 0x3e300000)
                if (SHUGE + x > 1.0) return x
            t = expm1(abs(x))
            if (ix < 0x3ff00000) return h * (2.0 * t - t * t / (t + 1.0))
            return h * (t + t / (t + 1.0))
        }
        if (ix < 0x40862E42) return h * exp(abs(x))
        val lx = lo(x)
        if (ix < 0x408633CE || ((ix == 0x408633ce) && ((lx.toLong() and 0xFFFFFFFFL) <= 0x8fb9f87dL))) {
            w = exp(0.5 * abs(x))
            val tt = h * w
            return tt * w
        }
        return x * SHUGE
    }
}
