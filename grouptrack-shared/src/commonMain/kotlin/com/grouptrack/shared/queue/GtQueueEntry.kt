package com.grouptrack.shared.queue

import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.json.GtOrgJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * STEP16B-2026-11-10 (2.8 step 16b): 2.7e's download-queue record (convoy/ConvoyDownloadQueue.kt: QueueStatus, DownloadType,
 * DownloadPriority, QueueEntry with toJson / fromJson) -- PORTED AS IS. Same fields, same order, same JSON keys in the same put order,
 * the same defaults. The two platform calls in 2.7e's defaults -- UUID.randomUUID() (id) and System.currentTimeMillis() (createdAt) --
 * have no default here: the caller passes env.ids.newId() / env.clock.epochMs(), at the same points.
 * The file text = 2.7e saveQueue's JSONArray.toString(2), through GtOrgJson (Android's org.json: insertion order, whole doubles as longs).
 */
enum class GtQueueStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED, CANCELLED }

enum class GtDownloadType { AREA, CORRIDOR, MAP_SOURCE_REFRESH, DELETE_AREA_TILES }

object GtDownloadPriority {
    const val DELETE = 0
    const val PROMOTED = 1
    const val CORRIDOR = 2
    const val AREA = 3
    const val REFRESH = 4
    fun forType(t: GtDownloadType): Int = when (t) {
        GtDownloadType.DELETE_AREA_TILES -> DELETE
        GtDownloadType.CORRIDOR -> CORRIDOR
        GtDownloadType.AREA -> AREA
        GtDownloadType.MAP_SOURCE_REFRESH -> REFRESH
    }
}

data class GtQueueEntry(
    val id: String,
    val north: Double = 0.0,
    val south: Double = 0.0,
    val east: Double = 0.0,
    val west: Double = 0.0,
    val status: GtQueueStatus = GtQueueStatus.QUEUED,
    val totalTiles: Int = 0,
    val downloadedTiles: Int = 0,
    val failedTiles: Int = 0,
    val retryCount: Int = 0,
    val createdAt: Long,
    val label: String = "",
    val workRequestId: String? = null,
    val refreshMode: Boolean = false,
    val refreshSlot: String = "",
    val downloadType: GtDownloadType = GtDownloadType.AREA,
    val priority: Int = GtDownloadPriority.AREA,
    val completedAt: Long = 0L,
    val startedAt: Long = 0L,
    val tilesPerSec: Double = 0.0,
    val geomHash: String = ""
)

object GtQueueJson {
    /** QueueEntry.toJson: 2.7e's put order (startedAt and tilesPerSec first -- THROUGHPUT-2026-08-08C put them at the top). */
    fun toJson(q: GtQueueEntry): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["startedAt"] = GtOrgJson.num(q.startedAt)
        m["tilesPerSec"] = GtOrgJson.num(q.tilesPerSec)
        m["id"] = GtOrgJson.str(q.id)
        m["north"] = GtOrgJson.num(q.north)
        m["south"] = GtOrgJson.num(q.south)
        m["east"] = GtOrgJson.num(q.east)
        m["west"] = GtOrgJson.num(q.west)
        m["status"] = GtOrgJson.str(q.status.name)
        m["totalTiles"] = GtOrgJson.num(q.totalTiles)
        m["downloadedTiles"] = GtOrgJson.num(q.downloadedTiles)
        m["failedTiles"] = GtOrgJson.num(q.failedTiles)
        m["retryCount"] = GtOrgJson.num(q.retryCount)
        m["createdAt"] = GtOrgJson.num(q.createdAt)
        m["label"] = GtOrgJson.str(q.label)
        m["workRequestId"] = GtOrgJson.str(q.workRequestId ?: "")
        m["refreshMode"] = GtOrgJson.bool(q.refreshMode)
        m["refreshSlot"] = GtOrgJson.str(q.refreshSlot)
        m["downloadType"] = GtOrgJson.str(q.downloadType.name)
        m["priority"] = GtOrgJson.num(q.priority)
        m["completedAt"] = GtOrgJson.num(q.completedAt)
        m["geomHash"] = GtOrgJson.str(q.geomHash)
        return JsonObject(m)
    }

    /** saveQueue's file text: JSONArray of every entry, toString(2). */
    fun fileText(entries: List<GtQueueEntry>): String = GtOrgJson.pretty(JsonArray(entries.map { toJson(it) }), 2)

    /** QueueEntry.fromJson. 2.7e evaluates BOTH defaults on every call (Kotlin evaluates the argument even when the key is present):
     *  one UUID (id's fallback) and one currentTimeMillis (createdAt's fallback), in that order -- kept, so the id / clock streams match. */
    fun fromJson(j: JsonObject, ids: GtIds, clock: GtClock): GtQueueEntry = GtQueueEntry(
        id = GtOrgJson.optString(j, "id", ids.newId()),
        north = GtOrgJson.optDouble(j, "north", 0.0),
        south = GtOrgJson.optDouble(j, "south", 0.0),
        east = GtOrgJson.optDouble(j, "east", 0.0),
        west = GtOrgJson.optDouble(j, "west", 0.0),
        status = try { GtQueueStatus.valueOf(GtOrgJson.optString(j, "status", "QUEUED")) } catch (_: Exception) { GtQueueStatus.QUEUED },
        totalTiles = GtOrgJson.optInt(j, "totalTiles", 0),
        downloadedTiles = GtOrgJson.optInt(j, "downloadedTiles", 0),
        failedTiles = GtOrgJson.optInt(j, "failedTiles", 0),
        retryCount = GtOrgJson.optInt(j, "retryCount", 0),
        createdAt = GtOrgJson.optLong(j, "createdAt", clock.epochMs()),
        label = GtOrgJson.optString(j, "label", ""),
        workRequestId = GtOrgJson.optString(j, "workRequestId", "").ifEmpty { null },
        refreshMode = GtOrgJson.optBoolean(j, "refreshMode", false),
        refreshSlot = GtOrgJson.optString(j, "refreshSlot", ""),
        downloadType = try { GtDownloadType.valueOf(GtOrgJson.optString(j, "downloadType", "AREA")) } catch (_: Exception) { GtDownloadType.AREA },
        priority = GtOrgJson.optInt(j, "priority", GtDownloadPriority.AREA),
        completedAt = GtOrgJson.optLong(j, "completedAt", 0L),
        startedAt = GtOrgJson.optLong(j, "startedAt", 0L),
        tilesPerSec = GtOrgJson.optDouble(j, "tilesPerSec", 0.0),
        geomHash = GtOrgJson.optString(j, "geomHash", "")
    )

    /** loadQueue's parse: JSONArray(text), getJSONObject(i) for each -- throws on anything else (2.7e then quarantines the file;
     *  the quarantine is the platform's, as the file is). */
    fun parseFile(text: String, ids: GtIds, clock: GtClock): List<GtQueueEntry> {
        val arr = GtOrgJson.parse(text) as? JsonArray ?: throw IllegalArgumentException("A JSONArray text must start with '['")
        val out = ArrayList<GtQueueEntry>()
        for (i in 0 until arr.size) {
            val o = arr[i] as? JsonObject ?: throw IllegalArgumentException("JSONArray[$i] is not a JSONObject.")
            out.add(fromJson(o, ids, clock))
        }
        return out
    }
}
