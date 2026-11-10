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
}

internal expect fun gtMathSin(x: Double): Double
internal expect fun gtMathCos(x: Double): Double
internal expect fun gtMathAsin(x: Double): Double
internal expect fun gtMathHypot(x: Double, y: Double): Double
