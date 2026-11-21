package com.geeksville.mesh.convoy

/**
 * RIDESTATS-2026-11-21: what did NOT move to shared code -- the ride-statistics JSON.
 *
 * The engine (the recorded state, the three producers, reset and report) is now
 * grouptrack-shared/.../com/geeksville/mesh/convoy/RideStats.kt, in this same package, because the
 * iPad's panel displays the string report() builds.
 *
 * ⛔ WHY json() STAYED HERE. It is the payload of "Email last ride statistics to GroupTrack OG"
 * (GtRideStatsSend), which LaunchModules.kt:138 places last in RIDES -- a different launch point whose
 * output is already certified. The shared JSON writer GtOrgJson reproduces org.json exactly
 * (STEP15A-2026-11-08, golden run) EXCEPT that it offers num(Double / Long / Int) and no num(Float),
 * while snr, ch and air are Float. Android's JSONObject.numberToString falls through to
 * Float.toString, so 0.1f writes "0.1"; widening to Double writes "0.10000000149011612". Re-serialising
 * in shared code would therefore change the bytes of an email we have already certified -- for the
 * benefit of a launch point we have not walked yet.
 * ⇒ So this file keeps using real org.json, and reads the record through RideStats' accessors.
 * THE STATE IS NOT DUPLICATED: there is one copy, in shared code.
 * ⇒ C28-26367 settles whether this collapses into GtOrgJson, AFTER the loop is tested and certified,
 *   by counting each service's consumers and weighing the merge against the volume of changes.
 */
object RideStatsJson {
    /** RIDESTATSSEND-2026-10-26: everything recorded, for the Rides email -- every report heard, my
     *  radio's positions, the lead estimates. Byte-for-byte as before the move. */
    fun json(nowMs: Long): String {
        fun org.json.JSONObject.p(k: String, v: Any?): org.json.JSONObject {
            if (v != null && !(v is Double && (v.isNaN() || v.isInfinite())) && !(v is Float && (v.isNaN() || v.isInfinite()))) put(k, v)
            return this
        }
        val o = org.json.JSONObject().p("format", "grouptrack-ride-stats-1").p("madeMs", nowMs).p("startMs", RideStats.startedMs())
        o.put("heard", org.json.JSONArray().also { a -> RideStats.heard().forEach { x -> a.put(org.json.JSONObject().p("t", x.t).p("id", x.id).p("name", x.name)
            .p("lat", x.lat).p("lon", x.lon).p("myLat", x.myLat).p("myLon", x.myLon).p("snr", x.snr).p("rssi", x.rssi).p("hops", x.hops)
            .p("ch", x.ch).p("air", x.air)) } })
        o.put("mine", org.json.JSONArray().also { a -> RideStats.minePositions().forEach { m -> a.put(org.json.JSONObject().p("t", m.t).p("lat", m.lat).p("lon", m.lon)) } })
        o.put("leadEstimates", org.json.JSONArray().also { a -> RideStats.estimates().forEach { e -> a.put(org.json.JSONObject().p("t", e.tMs).p("silentMs", e.silentMs)
            .p("errorM", e.errorM).p("speedMps", e.speedMps).p("onRoute", e.onRoute).p("moving", e.moving)) } })
        return o.toString()
    }
}
