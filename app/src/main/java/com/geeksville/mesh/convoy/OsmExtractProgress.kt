package com.geeksville.mesh.convoy

import com.grouptrack.shared.osm.GtOsmProgress

/**
 * Per-type extract progress, shared between the workers and whatever renders it (OSM-C2-WORKER-2026-07-28).
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR of the certified shared GtOsmProgress (2.8 step 15g, 57cd2a8a7) -- the same JSON text
 * (org.json's), the same reading. The public API is 2.7e's, unchanged; the 2.7e file (Fred's 07-28 shape, the rate-window rule) is in
 * git history (57cd2a8a7) and in the TEST-ONLY OsmExtractProgressCertOld.
 */
object OsmExtractProgress {
    const val KEY = GtOsmProgress.KEY
    const val PUBLISH_MS = GtOsmProgress.PUBLISH_MS
    const val NOTIFY_MS = GtOsmProgress.NOTIFY_MS
    const val ID_UNZIP = GtOsmProgress.ID_UNZIP

    data class Item(val id: String, val label: String, val done: Long, val total: Long, val complete: Boolean, val etaSec: Long) {
        val percent: Int get() = GtOsmProgress.Item(id, label, done, total, complete, etaSec).percent
    }

    fun toJson(items: List<Item>): String = GtOsmProgress.toJson(items.map { GtOsmProgress.Item(it.id, it.label, it.done, it.total, it.complete, it.etaSec) })
    fun fromJson(s: String?): List<Item> = GtOsmProgress.fromJson(s).map { Item(it.id, it.label, it.done, it.total, it.complete, it.etaSec) }
    fun etaText(etaSec: Long): String = GtOsmProgress.etaText(etaSec)
}
