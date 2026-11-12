package com.grouptrack.shared.state
// STEP17G2E2B-2026-11-12: installed by step17g2e2b (state data queue, 2.8 step 17 G2e-2)

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.settings.GtSettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * STEP17G2E2-2026-11-12 (2.8 step 17, G2e-2; Fred 14:04 "1" -- kotlinx-coroutines joins the shared module): THE STATE-DATA QUEUE --
 * convoy/StateDataQueue.kt (DATAQUEUE / QUEUEORDER / STOPNOW / PBFSTART 2026-11-02), ported AS IS. The import's file rows download
 * [maxAtOnce] at a time, never two from one server, the GeoPackage first and the PBF once every other file but the GeoPackage is here;
 * Stop / Retry per row, Cancel for the import; [run] returns true only when EVERY row is completed. Changes, and only these:
 *   the rows (org.json JSONObjects the import record holds) -> [GtQueueRow] (Android wraps the SAME JSONObjects -- the record still
 *     sees every status / step change)
 *   SharedPreferences "state_data_queue" / max_at_once  -> a GtSettingsStore given to [load] / [setMax] (Android: the same file and key)
 *   Thread { HomeStateImportController.abortDownload(row) } -> [abort] (Android runs it on a new Thread, as 2.7e)
 *   Dispatchers.IO -> [io] (Android: Dispatchers.IO)
 *   java.net.URL(u).host -> [GtUrlHost.host] (java.net.URL's parse: the protocols Android knows, authority, user info, port)
 *   android.util.Log -> gtLogger (the same tag and lines)
 */
interface GtQueueRow {
    fun optString(key: String): String
    fun optBoolean(key: String): Boolean
    fun put(key: String, value: String)
    fun put(key: String, value: Boolean)
    fun remove(key: String)
}

/** java.net.URL(spec).host -- null where java.net.URL throws MalformedURLException (2.7e then falls back to the text itself). */
object GtUrlHost {
    private val KNOWN = setOf("http", "https", "ftp", "file", "jar")
    fun host(spec0: String): String? {
        val spec = spec0.trim()
        val colon = spec.indexOf(':')
        if (colon <= 0) return null
        val proto = spec.substring(0, colon)
        if (!proto[0].isLetter() || proto.any { !(it.isLetterOrDigit() || it == '+' || it == '-' || it == '.') }) return null
        if (proto.lowercase() !in KNOWN) return null
        val rest = spec.substring(colon + 1)
        if (proto.lowercase() == "jar") return if (rest.contains("!/")) "" else null
        if (!rest.startsWith("//")) return ""
        var auth = rest.substring(2)
        val end = auth.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) auth = auth.substring(0, end)
        val at = auth.lastIndexOf('@'); if (at >= 0) auth = auth.substring(at + 1)
        val host: String; val port: String?
        if (auth.startsWith("[")) {
            val close = auth.indexOf(']'); if (close < 0) return null
            host = auth.substring(0, close + 1); val after = auth.substring(close + 1)
            port = if (after.startsWith(":")) after.substring(1) else if (after.isEmpty()) null else return null
        } else {
            val c = auth.indexOf(':')
            if (c >= 0) { host = auth.substring(0, c); port = auth.substring(c + 1) } else { host = auth; port = null }
        }
        if (port != null && port.isNotEmpty() && port.any { it !in '0'..'9' }) return null
        return host
    }
}

class GtStateDataQueue(private val io: CoroutineDispatcher, private val abort: (GtQueueRow) -> Unit) {
    companion object {
        const val TAG = "StateDataQueue"
        const val PREFS = "state_data_queue"
        const val KEY_MAX = "max_at_once"
        /** QUEUEORDER-2026-11-02: download order by kind -- the big GeoPackage first, the OSM PBF last. */
        val ORDER = mapOf("gpkg" to 0, "land" to 1, "agency" to 2, "pbf" to 3)
        fun serverOf(r: GtQueueRow): String {
            val u = r.optString("url")
            if (u.isEmpty()) return "source:" + r.optString("source_id")
            return GtUrlHost.host(u) ?: u
        }
    }
    private val log = gtLogger(TAG)

    /** Files downloaded at the same time, 1-4. */
    val maxAtOnce = MutableStateFlow(1)
    /** True while the queue has settled with a failed or stopped file -- the panel asks: Retry, or Cancel the import. */
    val waitingOnRider = MutableStateFlow(false)
    /** True while [run] is running. */
    val active = MutableStateFlow(false)

    private val actions = Channel<Pair<String, String>>(Channel.UNLIMITED)
    @kotlin.concurrent.Volatile private var rows: List<GtQueueRow> = emptyList()

    fun load(prefs: GtSettingsStore) { maxAtOnce.value = prefs.getInt(KEY_MAX, 1).coerceIn(1, 4) }

    fun setMax(prefs: GtSettingsStore, v: Int) {
        val n = v.coerceIn(1, 4)
        maxAtOnce.value = n
        prefs.edit { putInt(KEY_MAX, n) }
        actions.trySend("" to "max")
        log.i { "DATAQUEUE: $n at a time" }
    }

    fun stop(id: String) {
        val row = rows.firstOrNull { it.optString("id") == id } ?: return
        row.put("_stop", true)
        row.put("current_step", "Stopping…")
        log.i { "DATAQUEUE $id: stop" }
        abort(row)
        actions.trySend(id to "stop")
    }

    fun retry(id: String) { actions.trySend(id to "retry") }

    fun cancelAll() { actions.trySend("" to "cancel") }

    suspend fun run(list: List<GtQueueRow>, fetch: (GtQueueRow) -> Boolean, onChange: () -> Unit): Boolean = coroutineScope {
        while (actions.tryReceive().isSuccess) { }
        rows = list
        active.value = true
        waitingOnRider.value = false
        val byId = list.associateBy { it.optString("id") }
        val pending = ArrayDeque(list.filter { it.optString("status") != "completed" }.sortedBy { ORDER[it.optString("fetch_kind")] ?: 5 })
        val running = HashMap<String, Job>()
        fun start(row: GtQueueRow) {
            val id = row.optString("id")
            row.remove("_stop")
            row.put("status", "in_progress")
            running[id] = launch(io) {
                val ok = try { fetch(row) } catch (e: Exception) { log.w { "DATAQUEUE $id: ${e::class.simpleName} ${e.message}" }; false }
                row.put("status", if (ok) "completed" else if (row.optBoolean("_stop")) "stopped" else "failed")
                if (!ok && row.optBoolean("_stop")) row.put("current_step", "Stopped")
                actions.trySend(id to "done")
            }
        }
        log.i { "DATAQUEUE: ${list.size} file(s), ${pending.size} to get, ${maxAtOnce.value} at a time" }
        try {
            while (true) {
                while (running.size < maxAtOnce.value) {
                    val busy = running.keys.mapNotNull { byId[it] }.map { serverOf(it) }.toSet()
                    val restHere = list.all { it.optString("fetch_kind") in setOf("gpkg", "pbf") || it.optString("status") == "completed" }
                    val next = pending.firstOrNull { if (it.optString("fetch_kind") == "pbf") restHere else serverOf(it) !in busy } ?: break
                    pending.remove(next); start(next)
                }
                waitingOnRider.value = running.isEmpty() && pending.isEmpty() && list.any { it.optString("status") != "completed" }
                onChange()
                if (running.isEmpty() && pending.isEmpty() && list.all { it.optString("status") == "completed" }) {
                    log.i { "DATAQUEUE: all ${list.size} file(s) here" }
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
                            log.i { "DATAQUEUE $id: retry" }
                        }
                    }
                    "cancel" -> {
                        log.i { "DATAQUEUE: cancelled by the rider" }
                        for (r in list) if (r.optString("status") == "in_progress") r.put("_stop", true)
                        running.values.forEach { it.join() }
                        onChange()
                        return@coroutineScope false
                    }
                }
            }
            false
        } finally {
            for (r in list) if (r.optString("status") == "in_progress") r.put("_stop", true)
            active.value = false
            waitingOnRider.value = false
        }
    }
}
