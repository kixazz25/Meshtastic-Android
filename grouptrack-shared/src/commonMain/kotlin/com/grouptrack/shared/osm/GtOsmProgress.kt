package com.grouptrack.shared.osm

import com.grouptrack.shared.json.GtOrgJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmExtractProgress (OSM-C2-WORKER-2026-07-28), PORTED AS IS -- per-type progress shared
 * between the workers and the import screen: a counter per type, its own denominator, an ETA measured from THAT type's start only.
 * WHAT CHANGED, and only this: org.json JSONArray / JSONObject -> GtOrgJson (toJson writes JSONArray.toString()'s exact text; fromJson
 * reads with optString / optLong / optBoolean's answers and gives an empty list on any failure, as 2.7e).
 */
object GtOsmProgress {

    /** The key 2.7e's WorkManager Data carried the list under (kept: the screen reads it by this name). */
    const val KEY = "osm_extract_progress"
    /** How often progress reaches the PANEL (OSM-C2-PROGRESS-COUNTERS-2026-07-28). */
    const val PUBLISH_MS = 1_000L
    /** How often the foreground notification is rebuilt. */
    const val NOTIFY_MS = 10_000L
    const val ID_UNZIP = "unzip"

    data class Item(val id: String, val label: String, val done: Long, val total: Long, val complete: Boolean, val etaSec: Long) {
        val percent: Int
            get() = when {
                complete -> 100
                total <= 0L -> 0
                else -> ((done * 100L) / total).coerceIn(0L, 100L).toInt()
            }
    }

    fun toJson(items: List<Item>): String = GtOrgJson.compact(JsonArray(items.map { i ->
        JsonObject(linkedMapOf(
            "id" to GtOrgJson.str(i.id),
            "label" to GtOrgJson.str(i.label),
            "done" to GtOrgJson.num(i.done),
            "total" to GtOrgJson.num(i.total),
            "complete" to GtOrgJson.bool(i.complete),
            "eta_sec" to GtOrgJson.num(i.etaSec),
        ))
    }))

    fun fromJson(s: String?): List<Item> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = GtOrgJson.parse(s) as? JsonArray ?: return emptyList()
            val out = ArrayList<Item>(arr.size)
            for (e in arr) {
                val o = e as? JsonObject ?: return emptyList()   // getJSONObject throws -> the catch -> empty, as 2.7e
                out.add(Item(
                    id = GtOrgJson.optString(o, "id"),
                    label = GtOrgJson.optString(o, "label"),
                    done = GtOrgJson.optLong(o, "done", 0L),
                    total = GtOrgJson.optLong(o, "total", 0L),
                    complete = GtOrgJson.optBoolean(o, "complete", false),
                    etaSec = GtOrgJson.optLong(o, "eta_sec", -1L)
                ))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** "about 6 min left" / "about 40 sec left" / "" when there is no estimate. */
    fun etaText(etaSec: Long): String = when {
        etaSec < 0L -> ""
        etaSec < 90L -> "about $etaSec sec left"
        else -> "about ${(etaSec + 30L) / 60L} min left"
    }
}
