package com.grouptrack.shared.text

/**
 * GTMATH-2026-11-10 (Fred 11-10 07:17-07:37): THE PLATFORM RULE -- shared code never calls kotlin.math's sin / cos / asin / hypot; it
 * calls GtMath, so every stored or compared number is the same on Android and the iPad (V3 rule: identical on every platform).
 *  - Android: GtMath IS java.lang.Math -- exactly what 2.7e and every certified step already call. Android does not change by a bit.
 *  - iPad:    GtMathPort -- Android's own algorithms (FreeBSD msun cos/sin; fdlibm asin/hypot) in plain Kotlin, proven equal to the
 *             Droid's java.lang.Math on 6,000,000 values (MATHPROBE 11-10). Apple's libm is not: cos(0.68089...) = ...5005 on the iPad,
 *             ...5006 on Android (and exactly); found by the 15e cert (5 tracks' distance_miles 1 bit apart).
 * sqrt is NOT here: IEEE 754 requires it correctly rounded everywhere (kotlin.math.sqrt is the same on both). Same idea as GtGeom (one
 * geometry text), GtRx.WS (one \s) and GtJava (Java's number rules).
 */
object GtMath {
    fun sin(x: Double): Double = gtMathSin(x)
    fun cos(x: Double): Double = gtMathCos(x)
    fun asin(x: Double): Double = gtMathAsin(x)
    fun hypot(x: Double, y: Double): Double = gtMathHypot(x, y)
    /** GTMATH2-2026-11-10: GtRead's haversine (2.7e) squares with x.pow(2) = java.lang.Math.pow(x, 2.0). Android = java.lang.Math.pow.
     *  iPad: GtMathPow = Arm's optimized-routines pow as bionic compiles it (GTMATH2B-2026-11-10). NOT x * x: Android's pow is not
     *  correctly rounded and differs from x * x on 1,266 of MATHPOW's 1,500,000 values; the port = the Droid on all 1,500,000. */
    fun pow(x: Double, y: Double): Double = gtMathPow(x, y)
    /** MATHTILE-2026-11-10 (2.8 step 16a): the save-by-area tile math (lat2tile, the z12 squares, a tile row's edge latitude).
     *  Android = java.lang.Math; the iPad = GtMathFdlibm (tan / atan / sinh: fdlibm, as the Droid -- MATHTILE 11-10) and GtMathLog
     *  (ln: Arm's optimized-routines log, as the Droid). Apple's differ (tan on 40% of real inputs). */
    fun tan(x: Double): Double = gtMathTan(x)
    fun atan(x: Double): Double = gtMathAtan(x)
    fun sinh(x: Double): Double = gtMathSinh(x)
    fun ln(x: Double): Double = gtMathLn(x)
}

internal expect fun gtMathSin(x: Double): Double
internal expect fun gtMathCos(x: Double): Double
internal expect fun gtMathAsin(x: Double): Double
internal expect fun gtMathHypot(x: Double, y: Double): Double
internal expect fun gtMathPow(x: Double, y: Double): Double
internal expect fun gtMathTan(x: Double): Double   // MATHTILE-2026-11-10
internal expect fun gtMathAtan(x: Double): Double
internal expect fun gtMathSinh(x: Double): Double
internal expect fun gtMathLn(x: Double): Double
