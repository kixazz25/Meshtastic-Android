package com.geeksville.mesh.convoy

/**
 * RIDESEAM-2026-09-24 — the ride list for the standalone T1000-E write ("Apply Ride"), read from the
 * STORED ride JSONs (rides/<rideId>.json -- rides created on this tablet, and later imported ones, which are
 * stored in the same folder). The field names match what ConvoyApplyRadioScreen already reads from
 * the old event store (eventId, eventName, eventDate, channelName, channelPsk), so the screen changes
 * only where the list is loaded. The LoRa values come from the ride's network.mesh, so a ride on a
 * different preset writes that preset. "Apply Master Config" is untouched: it still applies the asset.
 */
data class RideForApply(
    val eventId: String,
    val eventName: String,
    val eventDate: String,
    val channelName: String,
    val channelPsk: String,          // base64, as the old store held it and the builder decodes it
    val loraRegion: String,
    val loraModemPreset: String,
    val loraHopLimit: Int,
    val loraTxPower: Int,
    val loraChannelNum: Int
)

object RideApplySource {

    /**
     * DATEFILTER-2026-09-24 (Fred): which rides the Send and Apply lists offer. Today or later by
     * default; with includeRecent, the last 30 days too (the ride file's own lifetime). A date that
     * cannot be read is kept -- Send and Apply judge the ride themselves.
     */
    fun isCurrent(date: String, includeRecent: Boolean): Boolean {
        val d = parseDate(date) ?: return true
        val cutoff = java.time.LocalDate.now().minusDays(if (includeRecent) 30L else 0L)
        return !d.isBefore(cutoff)
    }

    /** Accepts 2026-09-30 and also older typed forms such as 2026-9-3. */
    private fun parseDate(s: String): java.time.LocalDate? = try {
        val p = s.trim().split("-").map { it.toInt() }
        if (p.size == 3) java.time.LocalDate.of(p[0], p[1], p[2]) else null
    } catch (e: Exception) { null }

    /** Every stored ride with a complete network block, earliest date first. Incomplete files are skipped. */
    fun loadAll(context: android.content.Context, includeRecent: Boolean = false): List<RideForApply> {
        val dir = GroupTrackStorage.dir("rides", context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val j = org.json.JSONObject(f.readText())
                if (j.optString("kind") != "grouptrack.ride") return@mapNotNull null
                val ride = j.optJSONObject("ride") ?: return@mapNotNull null
                val mesh = j.optJSONObject("network")?.optJSONObject("mesh") ?: return@mapNotNull null
                val name = mesh.optString("channelName", "")
                val key = mesh.optString("key", "")
                if (name.isBlank() || key.isBlank()) return@mapNotNull null
                RideForApply(
                    eventId = ride.optString("rideId", f.nameWithoutExtension),
                    eventName = ride.optString("name", "Ride"),
                    eventDate = ride.optString("date", ""),
                    channelName = name,
                    channelPsk = key,
                    loraRegion = mesh.optString("region", "US"),
                    loraModemPreset = mesh.optString("preset", "LONG_FAST"),
                    loraHopLimit = mesh.optInt("hopLimit", 3),
                    loraTxPower = mesh.optInt("txPower", 27),
                    loraChannelNum = mesh.optInt("frequencySlot", 0)
                )
            } catch (e: Exception) {
                android.util.Log.w("RideApplySource", "skipped ${f.name}: ${e.message}")
                null
            }
        }.filter { isCurrent(it.eventDate, includeRecent) }.sortedBy { it.eventDate }   // DATEFILTER-2026-09-24
    }
}
