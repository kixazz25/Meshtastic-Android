import base64, os

content = '''package com.geeksville.mesh.convoy

/**
 * ConvoyConfig — IMP-001 central tuning values
 *
 * All user-adjustable parameters in one place.
 * Future: wire these to a Settings screen with sliders.
 */
object ConvoyConfig {

    // ── Map zoom levels ───────────────────────────────────────────────────
    /** Default zoom when opening convoy map — higher = more detail */
    const val MAP_DEFAULT_ZOOM = 16.0

    /** Zoom used in GROUP mode to fit full convoy span */
    const val MAP_GROUP_ZOOM_PADDING = 1.4f

    /** Zoom used in MY CART and NODE modes */
    const val MAP_CART_ZOOM = 17.0

    // ── Blink rates (milliseconds) ────────────────────────────────────────
    /** LOST node blink interval — slow pulse */
    const val BLINK_LOST_MS = 3000L

    /** SIGNAL_DROP node blink interval — fast pulse */
    const val BLINK_DROP_MS = 150L

    // ── Marker sizes (dp) ─────────────────────────────────────────────────
    const val MARKER_SIZE_LARGE_DP = 32
    const val MARKER_SIZE_MEDIUM_DP = 20

    // ── Tick rate ─────────────────────────────────────────────────────────
    /** How often the convoy engine ticks (ms) */
    const val TICK_MS = 5000L
}
'''

TARGET = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyConfig.kt"
with open(TARGET, "w", encoding="utf-8") as f:
    f.write(content)
print(f"Created {TARGET}")
