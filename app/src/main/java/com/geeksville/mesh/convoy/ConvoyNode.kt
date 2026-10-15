package com.geeksville.mesh.convoy

enum class ConvoyStatus {
    ACTIVE,
    SIGNAL_DROP,
    LOST
}

data class ConvoyNode(
    val nodeId: String = "",
    val callsign: String = "",
    val role: String = "Convoy",
    val status: ConvoyStatus = ConvoyStatus.ACTIVE,
    val isLead: Boolean = false,
    val prevSeenMs: Long = 0L,   // PANELGAP-2026-10-15: the previous report's time (CODE RULE 1 n/a -- 0 = no previous report yet)
    val isTail: Boolean = false,
    val isMyCart: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude_m: Int = 0,
    val speed_mph: Float = 0f,
    val heading_deg: Float = 0f,
    val battery_pct: Int = 100,
    val snr_db: Float = 0f,
    val lastSeenMs: Long = 0L,
    val convoyPosition: Int = 0,
    val feetToNodeAhead: Float = 0f,
    val feetToNodeBehind: Float = 0f,
    val milesToLead: Float = 0f,
    val milesToTail: Float = 0f,
    val cotType: String = "a-f-G-U-C",
    val timestampUtc: String = "",
    // TICKDATA-2026-09-28 (Fred): the GroupTrack RIDE ROLE (leader / middle / tail_gunner / rider) carried through the tick
    // for DISPLAY only -- "" = none (a bare radio). Not the engine's `role` label above, which the engine rewrites every tick.
    val rideRole: String = "",
    // HEARDSWITCH-2026-10-13 (P5.1b, Fred 10-12): MY cart records BOTH fixes; which one is SHOWN is decided at the tick replacement.
    // CODE RULE 1 (all six): null = not available -- and ALWAYS null on other carts (extended for my cart only, Fred 10-12).
    val radioLat: Double? = null, val radioLon: Double? = null, val radioFixMs: Long? = null,
    val tabletLat: Double? = null, val tabletLon: Double? = null, val tabletFixMs: Long? = null,
) {
    val markerColor: String get() {
        // Error states use blink only - keep assigned color
        if (isLead) return "#1CF0A0"
        if (isTail) return "#FF8C42"
        if (isMyCart) return "#2E75B6"
        val palette = listOf(
            "#E91E63", "#9C27B0", "#3F51B5", "#00BCD4",
            "#4CAF50", "#CDDC39", "#FF9800", "#FF5722",
            "#795548", "#607D8B", "#F06292", "#CE93D8",
            "#90CAF9", "#80DEEA", "#A5D6A7", "#FFF176"
        )
        return palette[(convoyPosition % palette.size).coerceAtLeast(0)]
    }

    val markerSymbol: String get() = when {
        isLead -> "triangle"
        isTail -> "triangle-stroked"
        isMyCart -> "star"
        else -> "circle"
    }

    val markerSize: String get() =
        if (isLead || isTail || isMyCart) "large" else "medium"

    /** PANELGAP-2026-10-15 (Fred 14:36): the time BETWEEN this report and the previous one -- fixed at each report, no timer. */
    val reportGap: String get() {
        if (prevSeenMs <= 0L || lastSeenMs <= prevSeenMs) return "--"
        val secs = ((lastSeenMs - prevSeenMs) / 1000).toInt()
        return if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }

    val lastSeenAgo: String get() {
        val nowMs = System.currentTimeMillis()
        val diffMs = nowMs - lastSeenMs
        val secs = (diffMs / 1000).toInt()
        return when {
            secs < 60 -> "${secs}s ago"
            else -> "${secs / 60}m ${secs % 60}s ago"
        }
    }
}
