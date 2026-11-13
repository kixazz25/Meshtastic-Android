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
    // STEP17G2E2F-2026-11-12: the real URLs the manifests hold. GEO / GEO2 above are already Utah's real
    // pbf and GeoPackage URLs, so only these are new.
    private const val PADUS_UT = "https://services.arcgis.com/v01gqwM5QqNysAAi/arcgis/rest/services/Protection_Mechanism_Category_PADUS/FeatureServer/0/query"
    private const val NH_PBF = "https://download.geofabrik.de/north-america/us/new-hampshire-latest.osm.pbf"
    private const val NH_GPKG = "https://download.geofabrik.de/north-america/us/new-hampshire-latest-free.gpkg.zip"
    private const val PADUS_NH = "https://www.sciencebase.gov/catalog/file/get/6759abcfd34edfeb8710a004?name=PADUS4_1_State_NH_GDB_KMZ.zip"

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
                // STEP17G2E2I-2026-11-13: the FIRST attempt only. start() clears _stop when it starts a row, so
                // holding on every attempt left a RETRIED row waiting for a stop that never comes (the press fires
                // one stop) -- scenario D hung there. Its intent: stop the first fetch, let the retry complete.
                if (id in sc.untilStop && n == 0) { while (!r.optBoolean("_stop")) sleep(T / 3) }
                if (r.optBoolean("_stop")) false else sc.results[id]?.getOrNull(n) ?: true
            }
            val ok = side.run(rs, fetch) { snap(); press(rs) { emit("$name|press", it) } }
            snap()
            emit("$name|return", "$ok")
            // STEP17G2E2K-2026-11-13: per-callback snapshots are compared ONLY at one at a time. With rows running
            // concurrently, WHICH transient states a callback happens to observe is thread scheduling, not a decision
            // the queue makes -- comparing them measured the scheduler (A4-four differed at snap|03 and re-synced at
            // 04; the iPad caught a different set again). The queue's DECISIONS are the keys below -- return, attempts,
            // final, aborts and the flows -- and those are compared in EVERY scenario.
            // STEP17G2E2L-2026-11-13: per-callback snapshots are NOT compared, at any concurrency. snap() samples the
            // rows whenever a callback happens to fire, so it records an OBSERVATION TIMELINE, not a decision the queue
            // makes. Proof it is a race and not a difference: between the 07:27 and 07:40 runs the sides FLIPPED --
            // first OLD caught "in_progress:Stopping...", then NEW did. The transient lives only between stop() setting
            // the flag and the fetch noticing it on its next 50 ms poll. Every DIFF in both runs was one of these, and
            // every deterministic key was SAME in both. What the service promises is compared below: the return value,
            // the attempts per row, every final status/step/stop flag, the presses, the aborts and the flows.
            emit("$name|snapshots", "not compared -- an observation timeline, not a decision")
            emit("$name|attempts", attempts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
            emit("$name|final", rs.joinToString(" ") { text(it) } + " | waiting=${side.waiting()} active=${side.active()} max=${side.maxAtOnce()}")
        }
        // STEP17G2E2F-2026-11-12 ⚠ THE ROWS ARE REAL -- the file rows of real state imports, not invented ones.
        // Utah: manifest utah-2026-11-02T18-56-32 (9 file rows). New Hampshire: new-hampshire-2026-11-02T14-28-07 (6).
        // Both pulled from Droid 1 on 11-06; the fixture is inbox/step17g2e2f_2026-11-12/real_rows_2026-11-12.json.
        // What real rows exercise and invented ones did not:
        //   * the pbf AND the GeoPackage are both on download.geofabrik.de -- the one-file-per-server rule fires,
        //     and at 4 at a time the pbf deliberately starts BESIDE the GeoPackage's tail (2.7e PBFSTART-2026-11-02,
        //     Fred 18:01) -- the one place two files share a server on purpose.
        //   * six agency rows, each its own "source:<id>" host -> real parallelism.
        //   * the saved statuses are MIXED: pending / stopped / completed (Utah) and completed / failed (NH).
        //   * eight non-file rows live in the same array in production (_classify, the per-source rows,
        //     geofabrik_<state>) -- they have no fetch_kind and sort last (ORDER default 5).
        // NOT here: "ownership" (polyline pass, dropped after 11-02 12:47, Fred 11-12 18:40) -- do not add it to ORDER.
        val utahSaved = listOf(
            fields("file:utah:pbf", "pbf", GEO),
            fields("file:utah:gpkg", "gpkg", GEO2, status = "stopped"),
            fields("file:UT:padus", "land", PADUS_UT, status = "completed"),
            fields("file:utah:agency:ugrc_utah_trails", "agency", "", "ugrc_utah_trails", "stopped"),
            fields("file:utah:agency:azsp_trails", "agency", "", "azsp_trails"),
            fields("file:utah:agency:blm_gtlf_all", "agency", "", "blm_gtlf_all"),
            fields("file:utah:agency:usfs_nfs_trails", "agency", "", "usfs_nfs_trails"),
            fields("file:utah:agency:usgs_national_trails", "agency", "", "usgs_national_trails"),
            fields("file:utah:agency:nps_public_trails", "agency", "", "nps_public_trails"))
        val utah = utahSaved.map { it + ("status" to "pending") }
        val nh = listOf(
            fields("file:new-hampshire:pbf", "pbf", NH_PBF, status = "completed"),
            fields("file:new-hampshire:gpkg", "gpkg", NH_GPKG, status = "completed"),
            fields("file:NH:padus", "land", PADUS_NH, status = "failed"),
            fields("file:new-hampshire:agency:usfs_nfs_trails", "agency", "", "usfs_nfs_trails"),
            fields("file:new-hampshire:agency:usgs_national_trails", "agency", "", "usgs_national_trails"),
            fields("file:new-hampshire:agency:nps_public_trails", "agency", "", "nps_public_trails"))
        val UT_GPKG = "file:utah:gpkg"; val UT_LAND = "file:UT:padus"; val UT_BLM = "file:utah:agency:blm_gtlf_all"
        // the lengths the simulation used (sim17g2e2f): the GeoPackage long, the land medium, the rest short
        val len = mapOf(UT_GPKG to 12, UT_LAND to 3, "file:utah:pbf" to 2)
        // A: the order by kind and the server rule, at 1 / 2 / 4 at a time
        scenario("A1-one", 1, utah, Script(emptyMap(), emptyMap())) { _, _ -> }
        scenario("A2-two", 2, utah, Script(len, emptyMap())) { _, _ -> }
        scenario("A4-four", 4, utah, Script(len, emptyMap())) { _, _ -> }
        // B: the manifest's own saved statuses -- a stopped row re-queues, a completed row is never started
        scenario("B-saved", 2, utahSaved, Script(len, emptyMap())) { _, _ -> }
        // C: a throw (an agency row) and a failure (the land row) -> the queue waits on the rider -> Retry each -> done
        val retried = HashSet<String>()
        // STEP17G2E2H-2026-11-13: the press also notices the STALL. 2.7e gates the pbf on every other row being
        // "completed", so a settled row (failed or stopped) holds it in `pending` for ever -- and waitingOnRider
        // needs `pending` empty, so the rider is never offered Retry or Cancel and the run never ends. That is
        // 2.7e behaviour, so BOTH sides do it and parity holds; this only lets the run finish so there is a report.
        // Held for 3 consecutive callbacks so the gap between one row ending and the next starting cannot trip it.
        var stalled = 0
        scenario("C-retry", 2, utah, Script(mapOf(UT_GPKG to 2), mapOf(UT_LAND to listOf(false, true)), throws = setOf(UT_BLM))) { rs, say ->
            if (side.waiting()) { stalled = 0
                for (r in rs) { val id = r.optString("id")
                    if (r.optString("status") in setOf("failed", "stopped") && retried.add(id)) { say("retry $id"); side.retry(id) } }
            } else if (rs.none { it.optString("status") == "in_progress" } && rs.any { it.optString("status") != "completed" }) {
                stalled++
                if (stalled == 3) say("DEADLOCK: nothing running, rows unfinished, waitingOnRider false -- the pbf is gated by a settled row (2.7e PBFSTART-2026-11-02); recorded, not fixed here").also { side.cancelAll() }
            } else stalled = 0
        }
        // D: Stop the GeoPackage mid-download (the abort is called), Retry it, then it completes;
        //    a retry of a completed row does nothing
        var stopped = false; var retriedD = false
        scenario("D-stop", 1, utah.take(3), Script(mapOf(UT_GPKG to 4), emptyMap(), untilStop = setOf(UT_GPKG))) { rs, say ->
            val gp = rs[1]
            if (!stopped && gp.optString("status") == "in_progress") { stopped = true; say("stop the GeoPackage"); side.stop(UT_GPKG); side.retry(UT_LAND) }
            if (stopped && !retriedD && side.waiting() && gp.optString("status") == "stopped") { retriedD = true; say("retry it (a second fetch, which completes)"); side.retry(UT_GPKG) }
        }
        emit("D|aborts", side.aborts().joinToString(","))
        // E: Cancel while two are downloading -> both end stopped, run returns false, the saved data is kept
        var cancelled = false
        scenario("E-cancel", 2, utah, Script(emptyMap(), emptyMap(), untilStop = setOf(UT_GPKG, UT_LAND))) { rs, say ->
            if (!cancelled && rs.count { it.optString("status") == "in_progress" } == 2) { cancelled = true; say("cancel"); side.cancelAll() }
        }
        // F: every row already here -> true at once, nothing started; G: a stop / retry for an unknown id is ignored
        scenario("F-all-done", 3, utahSaved.map { it + ("status" to "completed") }, Script(emptyMap(), emptyMap())) { _, _ -> }
        var g = false
        scenario("G-unknown", 1, listOf(fields("file:UT:padus", "land", PADUS_UT)), Script(emptyMap(), emptyMap())) { _, say -> if (!g) { g = true; say("stop/retry nope"); side.stop("nope"); side.retry("nope") } }
        // H: the second real shape -- New Hampshire: the pbf and GeoPackage already here, the land FAILED, 3 agency rows
        scenario("H-nh", 2, nh, Script(emptyMap(), emptyMap())) { _, _ -> }
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
