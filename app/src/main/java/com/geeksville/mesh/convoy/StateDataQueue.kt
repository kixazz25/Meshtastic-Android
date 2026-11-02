package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * DATAQUEUE-2026-11-02 -- THE STATE-DATA QUEUE (Fred 11-02, cut 3). Its own queue, NOT the map tile queue (Fred 12:13 "I vote
 * separate because the queue monitor can be the upper half of the process panel").
 *
 * The import's FILE ROWS (FETCHFIRST: state . source . file) are downloaded here, [maxAtOnce] at a time (Fred 12:28 "a throttle we
 * can control on simultaneous downloads", like the map queue; default 1, saved). Each row: Stop (while downloading) / Retry (failed
 * or stopped); Cancel stops the import. PROCESSING WAITS until EVERY row is done (Fred 11:49 "jobs cannot proceed until all data is
 * here"): [run] returns true only then, false when the rider cancels.
 *
 * The rows are the import record's own JSON objects -- their status / step / MB are what the record saves and the panel shows, so a
 * killed import's record says exactly which files were done (D4 recovery builds on that).
 * A row being stopped carries "_stop": the downloader checks it on every buffer and the agency fetch on every page.
 */
object StateDataQueue {
    private const val TAG = "StateDataQueue"
    private const val PREFS = "state_data_queue"
    private const val KEY_MAX = "max_at_once"

    /** QUEUEORDER-2026-11-02: download order by kind -- the big GeoPackage first, the OSM PBF last. */
    private val ORDER = mapOf("gpkg" to 0, "land" to 1, "agency" to 2, "pbf" to 3)

    /** QUEUEORDER-2026-11-02: the server a file comes from (an agency source: its own service). */
    private fun serverOf(r: JSONObject): String {
        val u = r.optString("url")
        if (u.isEmpty()) return "source:" + r.optString("source_id")
        return try { java.net.URL(u).host } catch (e: Exception) { u }
    }

    /** Files downloaded at the same time, 1-4. */
    val maxAtOnce = MutableStateFlow(1)
    /** True while the queue has settled with a failed or stopped file -- the panel asks: Retry, or Cancel the import. */
    val waitingOnRider = MutableStateFlow(false)
    /** True while [run] is running. */
    val active = MutableStateFlow(false)

    private val actions = Channel<Pair<String, String>>(Channel.UNLIMITED)
    @Volatile private var rows: List<JSONObject> = emptyList()

    fun load(ctx: Context) {
        maxAtOnce.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAX, 1).coerceIn(1, 4)
    }

    fun setMax(ctx: Context, v: Int) {
        val n = v.coerceIn(1, 4)
        maxAtOnce.value = n
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MAX, n).apply()
        actions.trySend("" to "max")
        Log.i(TAG, "DATAQUEUE: $n at a time")
    }

    /** Stop a downloading file (it ends as "stopped"; Retry starts it again). */
    fun stop(id: String) {
        val row = rows.firstOrNull { it.optString("id") == id } ?: return
        row.put("_stop", true)
        row.put("current_step", "Stopping\u2026")   // STOPNOW-2026-11-02: says so at once
        Log.i(TAG, "DATAQUEUE $id: stop")
        // STOPNOW-2026-11-02: close the connection too (off the main thread) -- a read waiting on a slow network ends at once
        Thread { HomeStateImportController.abortDownload(row) }.start()
        actions.trySend(id to "stop")
    }

    /** Start a failed or stopped file again. */
    fun retry(id: String) { actions.trySend(id to "retry") }

    /** Stop every file and end the import before processing. */
    fun cancelAll() { actions.trySend("" to "cancel") }

    /**
     * Download every row not already completed, [maxAtOnce] at a time. [fetch] does one row (blocking; true = the file is here).
     * [onChange] is called about once a second and on every change -- the caller saves the record and publishes progress.
     * Returns true when every row is completed; false when the rider cancels.
     */
    suspend fun run(list: List<JSONObject>, fetch: (JSONObject) -> Boolean, onChange: () -> Unit): Boolean = coroutineScope {
        while (actions.tryReceive().isSuccess) { }   // nothing left over from an earlier run
        rows = list
        active.value = true
        waitingOnRider.value = false
        val byId = list.associateBy { it.optString("id") }
        // QUEUEORDER-2026-11-02 (Fred 16:25): GeoPackage first, then PAD-US, the agency sources, and the OSM PBF last
        val pending = ArrayDeque(list.filter { it.optString("status") != "completed" }.sortedBy { ORDER[it.optString("fetch_kind")] ?: 5 })
        val running = HashMap<String, Job>()
        fun start(row: JSONObject) {
            val id = row.optString("id")
            row.remove("_stop")
            row.put("status", "in_progress")
            running[id] = launch(Dispatchers.IO) {
                val ok = try { fetch(row) } catch (e: Exception) { Log.w(TAG, "DATAQUEUE $id: ${e.javaClass.simpleName} ${e.message}"); false }
                row.put("status", if (ok) "completed" else if (row.optBoolean("_stop")) "stopped" else "failed")
                if (!ok && row.optBoolean("_stop")) row.put("current_step", "Stopped")
                actions.trySend(id to "done")
            }
        }
        Log.i(TAG, "DATAQUEUE: ${list.size} file(s), ${pending.size} to get, ${maxAtOnce.value} at a time")
        try {
            while (true) {
                // QUEUEORDER-2026-11-02: the next file whose SERVER is free -- never two files from one server at once
                while (running.size < maxAtOnce.value) {
                    val busy = running.keys.mapNotNull { byId[it] }.map { serverOf(it) }.toSet()
                    // PBFSTART-2026-11-02 (Fred 18:01): the PBF once every file but the GeoPackage is here -- beside the GeoPackage's tail
                    val restHere = list.all { it.optString("fetch_kind") in setOf("gpkg", "pbf") || it.optString("status") == "completed" }
                    val next = pending.firstOrNull { if (it.optString("fetch_kind") == "pbf") restHere else serverOf(it) !in busy } ?: break
                    pending.remove(next); start(next)
                }
                waitingOnRider.value = running.isEmpty() && pending.isEmpty() && list.any { it.optString("status") != "completed" }
                onChange()
                if (running.isEmpty() && pending.isEmpty() && list.all { it.optString("status") == "completed" }) {
                    Log.i(TAG, "DATAQUEUE: all ${list.size} file(s) here")
                    return@coroutineScope true
                }
                val a = withTimeoutOrNull(1000L) { actions.receive() } ?: continue
                val (id, what) = a
                when (what) {
                    "done" -> running.remove(id)
                    "retry" -> {
                        val r = byId[id]
                        if (r != null && id !in running && r.optString("status") in setOf("failed", "stopped")) {
                            r.put("status", "pending"); r.put("current_step", "Waiting"); pending.addLast(r)
                            Log.i(TAG, "DATAQUEUE $id: retry")
                        }
                    }
                    "cancel" -> {
                        Log.i(TAG, "DATAQUEUE: cancelled by the rider")
                        for (r in list) if (r.optString("status") == "in_progress") r.put("_stop", true)
                        running.values.forEach { it.join() }
                        onChange()
                        return@coroutineScope false
                    }
                }
            }
            false
        } finally {
            // the import itself was cancelled: tell every download to stop at its next buffer
            for (r in list) if (r.optString("status") == "in_progress") r.put("_stop", true)
            active.value = false
            waitingOnRider.value = false
        }
    }
}
