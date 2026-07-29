package com.geeksville.mesh.convoy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-type extract progress, shared between the worker and whatever renders it.
 *
 * OSM-C2-WORKER-2026-07-28
 *
 * SHAPE SET BY FRED 2026-07-28: "a simple counter per type, what % of that
 * type has processed, check box when type is completed", and for the time
 * figure, "time is preferred just relative to each type being transferred."
 *
 * ⭐ WHY THAT SHAPE IS RIGHT AND NOT JUST SIMPLER. The phases of C2 have work
 * units that are not comparable -- unzip moves BYTES, each pass moves ROWS,
 * and per-row cost differs by orders of magnitude between a 1,581-row places
 * pass and a 134,242-row trails pass. Any single blended bar would sit frozen
 * through the unzip, sprint through places, then crawl; and any ETA derived
 * from a whole-run average would be dominated by whichever phase ran first.
 * Giving each type its own denominator removes the need to know the relative
 * cost of unzip versus passes ON DEVICE -- which nobody does, since the 60.7s
 * desktop measurement says nothing about eMMC.
 *
 * ⚠ AND THE RATE WINDOW NEVER CROSSES A TYPE BOUNDARY. It starts when that
 * type starts. Carrying a rate across a boundary is exactly how the shipped
 * download ETA came out ~50x long: it measured across an interval whose rate
 * was not constant.
 *
 * The rows are generated from the catalog, so [OsmLayerCatalog] drives both
 * what gets extracted AND what appears here -- enabling "pois" in the JSON
 * makes a POIs row appear with no paired edit.
 */
object OsmExtractProgress {

    /** WorkManager Data key carrying the serialized list. */
    const val KEY = "osm_extract_progress"

    /**
     * OSM-C2-PROGRESS-COUNTERS-2026-07-28: how often progress reaches the PANEL.
     *
     * Was 30s, conflated with the notification interval below. That was sized
     * against an estimate of 8-20 minutes per pass; the measured Utah run was
     * 40 SECONDS END TO END (unzip 14s, trails 23s, natural 1.2s), so every
     * phase finished inside a single window and no intermediate frame was ever
     * published. The counters were correct; nothing was drawn.
     */
    const val PUBLISH_MS = 1_000L

    /**
     * OSM-C2-PROGRESS-COUNTERS-2026-07-28: how often the FOREGROUND NOTIFICATION is rebuilt.
     *
     * This is the interval that genuinely needs to be slow, and the real thing
     * the old 30s constant was protecting -- a notification rebuilt once per
     * 5,000-row page is its own performance problem. The panel does not read
     * this, so it costs nothing to leave coarse.
     */
    const val NOTIFY_MS = 10_000L

    const val ID_UNZIP = "unzip"

    /**
     * One row in the panel.
     *
     * @param total 0 means "not yet counted" -- shown as indeterminate rather
     *        than as 0%. Real and distinct from a type with genuinely no rows.
     * @param etaSec -1 means no estimate yet (the type has not moved enough to
     *        have a rate). NOT nullable, because -1 and "no estimate" are the
     *        same state and a second representation of it would only let the
     *        two disagree.
     */
    data class Item(
        val id: String,
        val label: String,
        val done: Long,
        val total: Long,
        val complete: Boolean,
        val etaSec: Long
    ) {
        val percent: Int
            get() = when {
                complete -> 100
                total <= 0L -> 0
                else -> ((done * 100L) / total).coerceIn(0L, 100L).toInt()
            }
    }

    fun toJson(items: List<Item>): String {
        val arr = JSONArray()
        for (i in items) {
            val o = JSONObject()
            o.put("id", i.id)
            o.put("label", i.label)
            o.put("done", i.done)
            o.put("total", i.total)
            o.put("complete", i.complete)
            o.put("eta_sec", i.etaSec)
            arr.put(o)
        }
        return arr.toString()
    }

    fun fromJson(s: String?): List<Item> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        id = o.optString("id"),
                        label = o.optString("label"),
                        done = o.optLong("done", 0L),
                        total = o.optLong("total", 0L),
                        complete = o.optBoolean("complete", false),
                        etaSec = o.optLong("eta_sec", -1L)
                    )
                )
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
