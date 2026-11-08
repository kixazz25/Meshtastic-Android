package com.grouptrack.shared.text

/**
 * STEP15A-2026-11-08 / STEP15A-B-2026-11-08: java.lang.Double.toString on the iPad = what Android 14 prints.
 * The 18:08 golden run showed Kotlin/Native's own Double.toString giving 17 digits where Android gives the shortest (28 of 188,137
 * values, all 1e13..1e18), so the iPad no longer uses it: [GtJava.shortestJavaText] computes the JDK 19+ shortest-decimal rule exactly.
 * ⚠ Cost: three exact decimal expansions per call (about 50 digits for a coordinate) -- fine for import WKT; measure on the iPad with the
 *   huge GPX (INVENTORY G12) before any per-frame use.
 */
actual fun gtJavaDoubleText(x: Double): String = GtJava.shortestJavaText(x)
