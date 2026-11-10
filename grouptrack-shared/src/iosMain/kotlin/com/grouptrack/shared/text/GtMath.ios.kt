package com.grouptrack.shared.text

// GTMATH-2026-11-10: the iPad = Android's algorithms (GtMathPort), never Apple's libm (see GtMath).
internal actual fun gtMathSin(x: Double): Double = GtMathPort.sin(x)
internal actual fun gtMathCos(x: Double): Double = GtMathPort.cos(x)
internal actual fun gtMathAsin(x: Double): Double = GtMathPort.asin(x)
internal actual fun gtMathHypot(x: Double, y: Double): Double = GtMathPort.hypot(x, y)
// GTMATH2B-2026-11-10: Android's own pow (Arm's, as bionic compiles it -- GtMathPow), never Apple's pow and never x * x.
internal actual fun gtMathPow(x: Double, y: Double): Double = GtMathPow.pow(x, y)
// MATHTILE-2026-11-10: Android's own tan / atan / sinh (fdlibm -- GtMathFdlibm) and ln (Arm's -- GtMathLog), never Apple's.
internal actual fun gtMathTan(x: Double): Double = GtMathFdlibm.tan(x)
internal actual fun gtMathAtan(x: Double): Double = GtMathFdlibm.atan(x)
internal actual fun gtMathSinh(x: Double): Double = GtMathFdlibm.sinh(x)
internal actual fun gtMathLn(x: Double): Double = GtMathLog.log(x)
