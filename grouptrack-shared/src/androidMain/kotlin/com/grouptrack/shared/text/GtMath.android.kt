package com.grouptrack.shared.text

// GTMATH-2026-11-10: Android = java.lang.Math, unchanged -- what 2.7e and every certified step call (see GtMath).
internal actual fun gtMathSin(x: Double): Double = java.lang.Math.sin(x)
internal actual fun gtMathCos(x: Double): Double = java.lang.Math.cos(x)
internal actual fun gtMathAsin(x: Double): Double = java.lang.Math.asin(x)
internal actual fun gtMathHypot(x: Double, y: Double): Double = java.lang.Math.hypot(x, y)
internal actual fun gtMathPow(x: Double, y: Double): Double = java.lang.Math.pow(x, y)   // GTMATH2-2026-11-10
