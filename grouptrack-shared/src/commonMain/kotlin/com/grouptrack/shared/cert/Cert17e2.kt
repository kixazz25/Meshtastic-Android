package com.grouptrack.shared.cert
// STEP17G2E2B-2026-11-12: installed by step17g2e2b (state data queue, 2.8 step 17 G2e-2)

import com.grouptrack.shared.settings.GtSettingsStore
import com.grouptrack.shared.state.GtQueueRow
import com.grouptrack.shared.state.GtStateDataQueue
import kotlinx.coroutines.CoroutineDispatcher

/**
 * STEP17G2E2-2026-11-12 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released): the G2e-2 cert script (the state-data
 * queue), ONE copy for every side. Fake downloads with scripted lengths (multiples of [T], far apart, so the order is the same every run)
 * and scripted results; the rider's Stop / Retry / Cancel are pressed from onChange when the queue reaches a given state, exactly as
 * the panel would. What is compared: every DIFFERENT snapshot of the rows the queue shows (status, step, stop flag) in order, the
 * flows, the return value, the abort calls, the saved "at a time" setting and serverOf for a list of URLs.
 */
object Cert17e2 {
    const val T = 150L
    interface Side {
        fun row(fields: Map<String, String>): GtQueueRow
        suspend fun run(rows: List<GtQueueRow>, fetch: (GtQueueRow) -> Boolean, onChange: () -> Unit): Boolean
        fun stop(id: String); fun retry(id: String); fun cancelAll()
        fun load(); fun setMax(v: Int); fun maxAtOnce(): Int; fun waiting(): Boolean; fun active(): Boolean
        fun savedMax(): String          // the prefs value as stored ("none" when unset)
        fun putSavedMax(v: Int?)        // the prefs value set directly (null = removed)
        fun aborts(): List<String>      // ids the queue asked to abort, in order
        fun serverOf(url: String, sourceId: String): String
    }
    private fun fields(id: String, kind: String, url: String, src: String = "", status: String = "pending") =
        mapOf("id" to id, "fetch_kind" to kind, "url" to url, "source_id" to src, "status" to status)
    private const val GEO = "https://download.geofabrik.de/north-america/us/utah-latest.osm.pbf"
    private const val GEO2 = "https://download.geofabrik.de/north-america/us/utah-latest-free.gpkg.zip"
    private const val USGS = "https://services.arcgis.com/v3/PADUS/FeatureServer/0/query?f=json"
    private const val NPS = "https://nps.example.gov/trails?state=UT"

    fun text(r: GtQueueRow) = r.optString("id") + ":" + r.optString("status") + ":" + r.optString("current_step") + (if (r.optBoolean("_stop")) ":STOP" else "")

    suspend fun run(side: Side, sleep: (Long) -> Unit, emit: (String, String) -> Unit) {
        // 0. serverOf
        for ((u, s) in listOf(GEO to "", "" to "blm-gtrn", "https://USER:pw@Host.Example:8443/x?y" to "", "http://[::1]:80/x" to "", "ftp://a.b/c" to "",
                "notaurl" to "", "abc://x/y" to "", "https://h?q=1" to "", "https://h#f" to "", "file:/sdcard/x" to "", "https://h:abc/" to "", "HTTPS://Up.Case/" to "",
                "jar:file:/x.jar!/a" to "", "  https://trim.me/x  " to ""))
            emit("serverOf|$u|$s", side.serverOf(u, s))
        // 1. the saved setting
        side.putSavedMax(null); side.load(); emit("max|unset", "${side.maxAtOnce()}")
        side.putSavedMax(7); side.load(); emit("max|7", "${side.maxAtOnce()}")
        side.putSavedMax(0); side.load(); emit("max|0", "${side.maxAtOnce()}")
        side.setMax(9); emit("max|set9", "${side.maxAtOnce()} saved=${side.savedMax()}")
        side.setMax(-2); emit("max|set-2", "${side.maxAtOnce()} saved=${side.savedMax()}")
        // the scenarios: rows, a fetch script per id (lengths in T, results per attempt), what the rider presses, the max
        data class Script(val len: Map<String, Int>, val results: Map<String, List<Boolean>>, val throws: Set<String> = emptySet(), val untilStop: Set<String> = emptySet())
        suspend fun scenario(name: String, max: Int, rows: List<Map<String, String>>, sc: Script, press: (List<GtQueueRow>, (String) -> Unit) -> Unit) {
            side.setMax(max)
            val rs = rows.map { side.row(it) }
            val attempts = HashMap<String, Int>()
            val snaps = ArrayList<String>()
            fun snap() { val s = rs.joinToString(" ") { text(it) } + " | waiting=${side.waiting()} active=${side.active()}"; if (snaps.lastOrNull() != s) snaps.add(s) }
            val fetch: (GtQueueRow) -> Boolean = { r ->
                val id = r.optString("id"); val n = attempts.getOrElse(id) { 0 }; attempts[id] = n + 1
                if (id in sc.throws && n == 0) { sleep(T); throw IllegalStateException("cert: $id throws") }
                var waited = 0
                val len = sc.len[id] ?: 1
                while (waited < len) { if (r.optBoolean("_stop")) break; sleep(T); waited++ }
                if (id in sc.untilStop) { while (!r.optBoolean("_stop")) sleep(T / 3) }
                if (r.optBoolean("_stop")) false else sc.results[id]?.getOrNull(n) ?: true
            }
            val ok = side.run(rs, fetch) { snap(); press(rs) { emit("$name|press", it) } }
            snap()
            emit("$name|return", "$ok")
            snaps.forEachIndexed { i, s -> emit("$name|snap|${i.toString().padStart(2, '0')}", s) }
            emit("$name|attempts", attempts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
            emit("$name|final", rs.joinToString(" ") { text(it) } + " | waiting=${side.waiting()} active=${side.active()} max=${side.maxAtOnce()}")
        }
        val base = listOf(fields("r1", "pbf", GEO), fields("r2", "gpkg", GEO2), fields("r3", "land", USGS), fields("r4", "agency", "", "blm-gtrn"),
            fields("r5", "agency", "", "blm-gtrn"), fields("r6", "agency", NPS, "nps", "completed"), fields("r7", "mystery", "https://other.example/x"))
        // A: one at a time, all good -- the order by kind
        scenario("A-one", 1, base, Script(emptyMap(), emptyMap())) { _, _ -> }
        // B: two at a time -- one server at a time, the PBF beside the GeoPackage's tail
        scenario("B-two", 2, base, Script(mapOf("r2" to 12, "r3" to 2, "r4" to 3, "r5" to 2, "r7" to 1, "r1" to 2), emptyMap())) { _, _ -> }
        // C: a failure and a throw -> the queue waits on the rider -> Retry each -> done
        val retried = HashSet<String>()
        scenario("C-retry", 2, base, Script(mapOf("r2" to 2), mapOf("r3" to listOf(false, true)), throws = setOf("r4"))) { rs, say ->
            if (side.waiting()) for (r in rs) { val id = r.optString("id")
                if (r.optString("status") in setOf("failed", "stopped") && retried.add(id)) { say("retry $id"); side.retry(id) } }
        }
        // D: Stop a download (the abort is called), Retry it, then it completes; a retry of a completed row does nothing
        var stopped = false; var retriedD = false
        scenario("D-stop", 1, base.take(3), Script(mapOf("r2" to 4), emptyMap(), untilStop = setOf("r2"))) { rs, say ->
            val r2 = rs[1]
            if (!stopped && r2.optString("status") == "in_progress") { stopped = true; say("stop r2"); side.stop("r2"); side.retry("r3") }
            if (stopped && !retriedD && side.waiting() && r2.optString("status") == "stopped") { retriedD = true; say("retry r2 (a second fetch, which completes)"); side.retry("r2") }
        }
        emit("D|aborts", side.aborts().joinToString(","))
        // E: Cancel while two are downloading -> both end stopped, run returns false
        var cancelled = false
        scenario("E-cancel", 2, base, Script(emptyMap(), emptyMap(), untilStop = setOf("r2", "r3"))) { rs, say ->
            if (!cancelled && rs.count { it.optString("status") == "in_progress" } == 2) { cancelled = true; say("cancel"); side.cancelAll() }
        }
        // F: nothing to do -> true at once; G: a stop / retry for an unknown id is ignored
        scenario("F-all-done", 3, listOf(fields("d1", "pbf", GEO, status = "completed"), fields("d2", "gpkg", GEO2, status = "completed")), Script(emptyMap(), emptyMap())) { _, _ -> }
        var g = false
        scenario("G-unknown", 1, listOf(fields("g1", "land", USGS)), Script(emptyMap(), emptyMap())) { _, say -> if (!g) { g = true; say("stop/retry nope"); side.stop("nope"); side.retry("nope") } }
        emit("aborts|all", side.aborts().joinToString(","))
        side.putSavedMax(null)
    }

    /** a row on a plain map (the iPad; the Droid's NEW uses its JSONObject rows) */
    class MapRow(f: Map<String, String>) : GtQueueRow {
        private val m = LinkedHashMap<String, Any>(f)
        override fun optString(key: String): String = m[key]?.toString() ?: ""
        override fun optBoolean(key: String): Boolean = m[key] == true || m[key] == "true"
        override fun put(key: String, value: String) { m[key] = value }
        override fun put(key: String, value: Boolean) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }
    /** NEW on the shared queue; [makeRow] = the platform's row (Droid: the JSONObject adapter; iPad: MapRow) */
    class NewSide(io: CoroutineDispatcher, private val prefs: GtSettingsStore, private val makeRow: (Map<String, String>) -> GtQueueRow) : Side {
        private val aborted = ArrayList<String>()
        private val q = GtStateDataQueue(io) { r -> aborted.add(r.optString("id")) }
        override fun row(fields: Map<String, String>) = makeRow(fields)
        override suspend fun run(rows: List<GtQueueRow>, fetch: (GtQueueRow) -> Boolean, onChange: () -> Unit) = q.run(rows, fetch, onChange)
        override fun stop(id: String) = q.stop(id)
        override fun retry(id: String) = q.retry(id)
        override fun cancelAll() = q.cancelAll()
        override fun load() = q.load(prefs)
        override fun setMax(v: Int) = q.setMax(prefs, v)
        override fun maxAtOnce() = q.maxAtOnce.value
        override fun waiting() = q.waitingOnRider.value
        override fun active() = q.active.value
        override fun savedMax() = if (prefs.contains(GtStateDataQueue.KEY_MAX)) "${prefs.getInt(GtStateDataQueue.KEY_MAX, -1)}" else "none"
        override fun putSavedMax(v: Int?) = prefs.edit { if (v == null) remove(GtStateDataQueue.KEY_MAX) else putInt(GtStateDataQueue.KEY_MAX, v) }
        override fun aborts(): List<String> = aborted.toList()
        override fun serverOf(url: String, sourceId: String) = GtStateDataQueue.serverOf(MapRow(mapOf("url" to url, "source_id" to sourceId)))
    }
}
