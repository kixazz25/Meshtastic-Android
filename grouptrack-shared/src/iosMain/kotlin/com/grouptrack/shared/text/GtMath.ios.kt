package com.grouptrack.shared.text

// GTMATH-2026-11-10: the iPad = Android's algorithms (GtMathPort), never Apple's libm (see GtMath).
internal actual fun gtMathSin(x: Double): Double = GtMathPort.sin(x)
internal actual fun gtMathCos(x: Double): Double = GtMathPort.cos(x)
internal actual fun gtMathAsin(x: Double): Double = GtMathPort.asin(x)
internal actual fun gtMathHypot(x: Double, y: Double): Double = GtMathPort.hypot(x, y)
// GTMATH2B-2026-11-10: Android's own pow (Arm's, as bionic compiles it -- GtMathPow), never Apple's pow and never x * x.
internal actual fun gtMathPow(x: Double, y: Double): Double = GtMathPow.pow(x, y)
