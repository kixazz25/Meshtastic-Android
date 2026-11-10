package com.grouptrack.shared.text

// GTMATH-2026-11-10: the iPad = Android's algorithms (GtMathPort), never Apple's libm (see GtMath).
internal actual fun gtMathSin(x: Double): Double = GtMathPort.sin(x)
internal actual fun gtMathCos(x: Double): Double = GtMathPort.cos(x)
internal actual fun gtMathAsin(x: Double): Double = GtMathPort.asin(x)
internal actual fun gtMathHypot(x: Double, y: Double): Double = GtMathPort.hypot(x, y)
