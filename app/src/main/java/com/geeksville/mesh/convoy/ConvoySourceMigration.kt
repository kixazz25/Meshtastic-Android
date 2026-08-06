package com.geeksville.mesh.convoy

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SOURCEMIGRATION-2026-08-06
 *
 * The durable record of a map-source change.
 *
 * A source change on a column is destructive in two ways at once: it deletes
 * the column's .mbtiles stores (GB scale, no backup) and it clears the
 * download queue history that would otherwise say what had been downloaded.
 * Afterwards nothing in the app can answer "what did I have, and where did it
 * go?" - so this object writes that answer down BEFORE the deletion starts.
 *
 * It doubles as the in-progress sentinel. The outgoing source's cache dirs are
 * only knowable while that source is still assigned to the slot; once
 * MapSourceManager.updateSlotSource has run they are gone from every live
 * structure. begin() must therefore be called BEFORE updateSlotSource, or the
 * label stores (SAT_LABELS_PLACES, SAT_LABELS_TRANSPORT - 461 MB on a measured
 * device) become orphans that nothing knows to remove.
 *
 * A file with no completed_at is an interrupted migration. Resume is a
 * deliberate prompt, never automatic: finishing means deleting GB of tiles,
 * and the launch that discovers it may be at a trailhead.
 *
 * Location is maps/source_changes/ - a SIBLING of maps/tiles/, chosen so that
 * clearing a column can never destroy the record of that clear. Nothing that
 * deletes tiles should ever walk this directory.
 */
object ConvoySourceMigration {

    private const val TAG = "SourceMigration"
    private const val SCHEMA = 1

    /** maps/source_changes/ - sibling of maps/tiles/, never inside a cache dir. */
    private fun migrationDir(): File {
        val maps = ConvoyConfig.TILE_DIR.parentFile
        val dir = if (maps != null) File(maps, "source_changes")
                  else File(ConvoyConfig.TILE_DIR, "../source_changes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun stamp(d: Date): String =
        SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(d)

    private fun safe(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._+-]"), "-")

    /** Byte size of one cache dir's store, including its sqlite sidecars. */
    private fun bytesForCacheDir(cacheDir: String): Long {
        var total = 0L
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            val f = File(ConvoyConfig.TILE_DIR, "$cacheDir.mbtiles$suffix")
            if (f.exists()) total += f.length()
        }
        return total
    }

    /**
     * Measured size of a set of cache dirs, for the panel and for the record.
     * The SAT column owns THREE stores; showing only SAT.mbtiles understates
     * what a clear actually reclaims by ~461 MB.
     */
    fun measureBytes(cacheDirs: List<String>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (d in cacheDirs) out[d] = bytesForCacheDir(d)
        return out
    }

    fun totalBytes(cacheDirs: List<String>): Long =
        measureBytes(cacheDirs).values.sum()

    /**
     * Open a migration record. MUST be called BEFORE
     * MapSourceManager.updateSlotSource - see the class comment.
     *
     * Returns the record file, or null if it could not be written. A null
     * return should ABORT the migration rather than proceed unrecorded: an
     * unrecorded GB-scale delete is the failure this object exists to prevent.
     */
    fun begin(
        slot: String,
        fromSourceId: String,
        fromCacheDirs: List<String>,
        toSourceId: String,
        toCacheDirs: List<String>
    ): File? {
        return try {
            val now = Date()
            val name = "${stamp(now)}_${safe(slot)}_${safe(fromSourceId)}_to_${safe(toSourceId)}.json"
            val dest = File(migrationDir(), name)

            val sizes = measureBytes(fromCacheDirs)
            val sizeObj = JSONObject()
            for ((k, v) in sizes) sizeObj.put(k, v)

            val root = JSONObject()
            root.put("schema", SCHEMA)
            root.put("slot", slot)
            root.put("from_source_id", fromSourceId)
            root.put("from_cache_dirs", JSONArray(fromCacheDirs))
            root.put("from_bytes", sizeObj)
            root.put("from_bytes_total", sizes.values.sum())
            root.put("to_source_id", toSourceId)
            root.put("to_cache_dirs", JSONArray(toCacheDirs))
            root.put("started_at", now.time)
            root.put("phase", "opened")
            root.put("deleted_cache_dirs", JSONArray())
            root.put("queue_cleared", false)
            root.put("reload_choice", JSONObject.NULL)
            root.put("completed_at", JSONObject.NULL)
            root.put("error", JSONObject.NULL)

            write(dest, root)
            Log.i(TAG, "BEGIN $slot $fromSourceId -> $toSourceId, " +
                "${sizes.values.sum()} bytes across ${fromCacheDirs.size} store(s): ${dest.name}")
            dest
        } catch (e: Exception) {
            Log.e(TAG, "begin failed: ${e.message}")
            null
        }
    }

    /** Atomic-ish write, mirroring MapSourceManager's tmp + rename. */
    private fun write(dest: File, root: JSONObject) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeText(root.toString(2), Charsets.UTF_8)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            dest.writeText(root.toString(2), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun read(record: File): JSONObject? = try {
        if (!record.exists()) null else JSONObject(record.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        Log.e(TAG, "read failed ${record.name}: ${e.message}")
        null
    }

    private fun update(record: File, block: (JSONObject) -> Unit) {
        val root = read(record) ?: return
        try {
            block(root)
            write(record, root)
        } catch (e: Exception) {
            Log.e(TAG, "update failed ${record.name}: ${e.message}")
        }
    }

    fun setPhase(record: File, phase: String) {
        update(record) { it.put("phase", phase) }
        Log.i(TAG, "PHASE ${record.name} -> $phase")
    }

    fun noteQueueCleared(record: File) {
        update(record) { it.put("queue_cleared", true) }
    }

    /**
     * Record one cache dir as deleted, as it happens. deleteSource works per
     * store, so a three-store column can be interrupted with one gone and two
     * remaining - recording each makes a resume exact instead of approximate.
     */
    fun noteDeleted(record: File, cacheDir: String) {
        update(record) {
            val arr = it.optJSONArray("deleted_cache_dirs") ?: JSONArray()
            arr.put(cacheDir)
            it.put("deleted_cache_dirs", arr)
        }
        Log.i(TAG, "DELETED $cacheDir (${record.name})")
    }

    /** "all_tracks" | "selected" | "none" - what the user chose to reload. */
    fun noteReloadChoice(record: File, choice: String) {
        update(record) { it.put("reload_choice", choice) }
    }

    fun complete(record: File) {
        update(record) {
            it.put("phase", "complete")
            it.put("completed_at", System.currentTimeMillis())
        }
        Log.i(TAG, "COMPLETE ${record.name}")
    }

    /**
     * Mark the migration failed and leave the record in place. Used when
     * workers will not stop: deleting a store out from under a live worker
     * risks a half-written file or a wedged handle, so failing loudly and
     * keeping the record is the safer outcome.
     */
    fun fail(record: File, reason: String) {
        update(record) {
            it.put("phase", "failed")
            it.put("error", reason)
        }
        Log.e(TAG, "FAILED ${record.name}: $reason")
    }

    /** Records with no completed_at, newest first. Empty = nothing in flight. */
    fun inProgress(): List<File> {
        val dir = migrationDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.filter { f ->
            val root = read(f)
            root != null && root.isNull("completed_at")
        }.sortedByDescending { it.lastModified() }
    }

    /** All records, newest first - the history behind "where did my tiles go?". */
    fun history(): List<File> {
        val dir = migrationDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }
}
