package com.grouptrack.shared.text

/** STEP15A-2026-11-08: on Android the shared code runs on ART, so Kotlin's Double.toString IS java.lang.Double.toString. */
actual fun gtJavaDoubleText(x: Double): String = x.toString()
