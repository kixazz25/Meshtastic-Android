package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.GtQueueCertStub
import com.geeksville.mesh.convoy.StateDataQueue
import com.geeksville.mesh.convoy.StateDataQueueCertOld
import com.grouptrack.shared.cert.Cert17e2
import com.grouptrack.shared.state.GtQueueRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.Writer
import java.net.URL

// STEP17G2E2D-2026-11-12 (2.8 step 17, G2e-2 cert, Droid side) -- TEST-ONLY (CODE RULE 3: remove with GtCertRun before 2.8 is released).
// OLD (the generated 2.7e StateDataQueueCertOld) = NEW (the shared GtStateDataQueue, through StateDataQueue's front door) on the same
// scenarios (Cert17e2). Both sides use the app's own prefs file "state_data_queue" (one file; each scenario sets its own value).
// Lines: E17E2 <tab> key <tab> SAME|DIFF <tab> value; footer "# E17E2 done: N SAME, N DIFF in S s".

/** A JSONObject seen through the shared queue's row interface (the same object). */
private class JsonRow(val json: JSONObject) : GtQueueRow {
    override fun optString(key: String): String = json.optString(key)
    override fun optBoolean(key: String): Boolean = json.optBoolean(key)
    override fun put(key: String, value: String) { json.put(key, value) }
    override fun put(key: String, value: Boolean) { json.put(key, value) }
    override fun remove(key: String) { json.remove(key) }
}

object GtCert17e2 {
    private const val PREFS = "state_data_queue"
    private const val KEY_MAX = "max_at_once"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** The OLD side: the generated 2.7e queue. */
    private class OldSide(private val ctx: Context) : Cert17e2.Side {
        private val prefs = GtSettingsAndroid(ctx).store(PREFS)
        override fun row(fields: Map<String, String>): GtQueueRow = JsonRow(JSONObject(fields))
        override suspend fun run(rows: List<GtQueueRow>, fetch: (GtQueueRow) -> Boolean, onChange: () -> Unit): Boolean =
            StateDataQueueCertOld.run(rows.map { (it as JsonRow).json }, { j -> fetch(JsonRow(j)) }, onChange)
        override fun stop(id: String) = StateDataQueueCertOld.stop(id)
        override fun retry(id: String) = StateDataQueueCertOld.retry(id)
        override fun cancelAll() = StateDataQueueCertOld.cancelAll()
        override fun load() = StateDataQueueCertOld.load(ctx)
        override fun setMax(v: Int) = StateDataQueueCertOld.setMax(ctx, v)
        override fun maxAtOnce(): Int = StateDataQueueCertOld.maxAtOnce.value
        override fun waiting(): Boolean = StateDataQueueCertOld.waitingOnRider.value
        override fun active(): Boolean = StateDataQueueCertOld.active.value
        override fun savedMax(): String = if (prefs.contains(KEY_MAX)) "${prefs.getInt(KEY_MAX, -1)}" else "none"
        override fun putSavedMax(v: Int?) = prefs.edit { if (v == null) remove(KEY_MAX) else putInt(KEY_MAX, v) }
        override fun aborts(): List<String> = GtQueueCertStub.aborted.toList()
        override fun serverOf(url: String, sourceId: String): String =
            if (url.isEmpty()) "source:" + sourceId else try { URL(url).host } catch (e: Exception) { url }
    }

    /** Runs both sides and writes the comparison. */
    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        runBlocking { Cert17e2.run(OldSide(ctx), { ms -> Thread.sleep(ms) }) { k, v -> oldMap[k] = v } }
        val newSide = Cert17e2.NewSide(Dispatchers.IO, GtSettingsAndroid(ctx).store(PREFS)) { f -> JsonRow(JSONObject(f)) }
        runBlocking { Cert17e2.run(newSide, { ms -> Thread.sleep(ms) }) { k, v -> newMap[k] = v } }
        var same = 0
        var diff = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            if (o == n) {
                same++
                w.write("E17E2\t$k\tSAME\t${esc(o)}\n")
            } else {
                diff++
                w.write("E17E2\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n")
            }
        }
        w.write("# E17E2 done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
