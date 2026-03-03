package com.geeksville.mesh.convoy

/**
 * ConvoySimulation — 20 nodes along New Harmony Road, Utah
 * NW heading (~320°), ~396 ft spacing, 5-second tick
 * KILO-13 starts LOST. HOTEL-10 is MY CART.
 * IMP-001 Task 3.1
 */
object ConvoySimulation {

    const val MY_CART_ID = "HOTEL-10"
    const val TICK_MS = 5_000L

    // Base positions along New Harmony Road, NW heading ~320°
    // Tail at 37.4765, -113.2994 → Lead at 37.5012, -113.3298
    // 20 nodes, ~396 ft (~0.075 miles) spacing
    private val BASE_POSITIONS = listOf(
        Pair(37.5012, -113.3298), // 1  LEAD-1
        Pair(37.4999, -113.3283), // 2  BRAVO-2
        Pair(37.4986, -113.3268), // 3  CHARLIE-3
        Pair(37.4973, -113.3253), // 4  DELTA-4
        Pair(37.4960, -113.3238), // 5  ECHO-5
        Pair(37.4947, -113.3223), // 6  FOXTROT-6
        Pair(37.4934, -113.3208), // 7  GOLF-7
        Pair(37.4921, -113.3193), // 8  HOTEL-8
        Pair(37.4908, -113.3178), // 9  INDIA-9
        Pair(37.4895, -113.3163), // 10 HOTEL-10 (MY CART)
        Pair(37.4882, -113.3148), // 11 JULIET-11
        Pair(37.4869, -113.3133), // 12 KILO-12
        Pair(37.4856, -113.3118), // 13 KILO-13 (LOST)
        Pair(37.4843, -113.3103), // 14 LIMA-14
        Pair(37.4830, -113.3088), // 15 MIKE-15
        Pair(37.4817, -113.3073), // 16 NOVEMBER-16
        Pair(37.4804, -113.3058), // 17 OSCAR-17
        Pair(37.4791, -113.3043), // 18 PAPA-18
        Pair(37.4778, -113.3028), // 19 QUEBEC-19
        Pair(37.4765, -113.2994)  // 20 SIERRA-20 (TAIL)
    )

    private val CALLSIGNS = listOf(
        "LEAD-1", "BRAVO-2", "CHARLIE-3", "DELTA-4", "ECHO-5",
        "FOXTROT-6", "GOLF-7", "HOTEL-8", "INDIA-9", "HOTEL-10",
        "JULIET-11", "KILO-12", "KILO-13", "LIMA-14", "MIKE-15",
        "NOVEMBER-16", "OSCAR-17", "PAPA-18", "QUEBEC-19", "SIERRA-20"
    )

    private var tickCount = 0L
    private var startTimeMs = 0L

    fun start() {
        startTimeMs = System.currentTimeMillis()
        tickCount = 0
    }

    /**
     * Generate a snapshot of all 20 nodes for the current tick.
     * Call this every TICK_MS (5 seconds).
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): List<ConvoyNode> {
        tickCount++
        return CALLSIGNS.mapIndexed { index, callsign ->
            val (baseLat, baseLon) = BASE_POSITIONS[index]

            // Small position drift — nodes move NW slightly each tick
            val driftLat = tickCount * 0.000005
            val driftLon = tickCount * -0.000006
            val lat = baseLat + driftLat
            val lon = baseLon + driftLon

            // KILO-13 (index 12) is permanently LOST — last seen 10 minutes ago
            val lastSeenMs = when (callsign) {
                "KILO-13" -> nowMs - 600_000L  // 10 minutes ago = LOST
                else -> nowMs - (index * 2000L) // stagger last seen times slightly
            }

            // Speed varies by position — lead faster, tail slower
            val speed = when {
                callsign == "KILO-13" -> 0f
                index < 3 -> 18f + (index * 0.5f)   // lead vehicles faster
                index > 16 -> 12f - (index * 0.2f)  // tail vehicles slower
                else -> 15f + (index % 3) * 1.5f    // middle pack
            }

            // Battery varies — a few nodes are low
            val battery = when (callsign) {
                "DELTA-4"    -> 18  // low battery
                "NOVEMBER-16" -> 15 // low battery
                else -> 75 + (index % 3) * 5
            }

            ConvoyNode(
                nodeId = callsign,
                callsign = callsign,
                isMyCart = callsign == MY_CART_ID,
                latitude = lat,
                longitude = lon,
                altitude_m = 900 + (index % 5) * 10,
                speed_mph = speed,
                heading_deg = 320f + (index % 5 - 2).toFloat(), // slight variation around 320°
                battery_pct = battery,
                snr_db = 8f - (index * 0.3f),
                lastSeenMs = lastSeenMs,
                cotType = "a-f-G-U-C",
                timestampUtc = java.time.Instant.ofEpochMilli(lastSeenMs).toString()
            )
        }
    }

    /**
     * Convenience: run the engine on the current tick and return ConvoyState.
     */
    fun computeState(nowMs: Long = System.currentTimeMillis()): ConvoyEngine.ConvoyState {
        val nodes = tick(nowMs)
        return ConvoyEngine.compute(nodes, myCartId = MY_CART_ID, nowMs = nowMs)
    }

    /** Reset simulation to tick 0 */
    fun reset() {
        tickCount = 0
        startTimeMs = System.currentTimeMillis()
    }
}
