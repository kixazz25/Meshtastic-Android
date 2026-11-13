package com.grouptrack.shared.cert

import com.grouptrack.shared.settings.GtSettings
import com.grouptrack.shared.settings.GtSettingsEditor
import com.grouptrack.shared.settings.GtSettingsStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G2E2F-2026-11-12 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * the state-data QUEUE on the iPad simulator, VALUE FOR VALUE with the Droid's (gt17e2_droid.txt).
 * The same Cert17e2 script on the shared GtStateDataQueue, over the SAME real manifest rows, with the
 * same scripted fetch lengths -- so the comparison is the queue's own decisions: start order, what ran
 * together, which row was held for its server, when the pbf was released, every status transition,
 * the flows, the return values and the abort calls.
 * Rows on a plain map (Cert17e2.MapRow); the Droid's NEW uses its JSONObject rows.
 */
@OptIn(ExperimentalForeignApi::class)
class GtCertQueue17e2MacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** the prefs file in memory (the same shape the other 17 MacTests use) */
    private class Mem : GtSettings {
        val stores = HashMap<String, LinkedHashMap<String, Any?>>()
        override fun store(name: String): GtSettingsStore {
            val m = stores.getOrPut(name) { LinkedHashMap() }
            return object : GtSettingsStore {
                override fun getString(key: String, def: String?): String? = if (m.containsKey(key)) m[key] as String? else def
                override fun getInt(key: String, def: Int): Int = (m[key] as Int?) ?: def
                override fun getLong(key: String, def: Long): Long = (m[key] as Long?) ?: def
                override fun getFloat(key: String, def: Float): Float = (m[key] as Float?) ?: def
                override fun getBoolean(key: String, def: Boolean): Boolean = (m[key] as Boolean?) ?: def
                @Suppress("UNCHECKED_CAST")
                override fun getStringSet(key: String, def: Set<String>?): Set<String>? = if (m.containsKey(key)) m[key] as Set<String>? else def
                override fun contains(key: String): Boolean = m.containsKey(key)
                override fun all(): Map<String, Any?> = m.toMap()
                override fun edit(block: GtSettingsEditor.() -> Unit) {
                    object : GtSettingsEditor {
                        override fun putString(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
                        override fun putInt(key: String, value: Int) { m[key] = value }
                        override fun putLong(key: String, value: Long) { m[key] = value }
                        override fun putFloat(key: String, value: Float) { m[key] = value }
                        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
                        override fun putStringSet(key: String, value: Set<String>?) { if (value == null) m.remove(key) else m[key] = value.toSet() }
                        override fun remove(key: String) { m.remove(key) }
                        override fun clear() { m.clear() }
                    }.block()
                }
            }
        }
    }

    @Test
    fun queue() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt17e2_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GTE17E2 mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>()
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("E17E2\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size == 4 && p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")
        val prefs = Mem().store("state_data_queue")
        val side = Cert17e2.NewSide(Dispatchers.Default, prefs) { f -> Cert17e2.MapRow(f) }
        val mine = LinkedHashMap<String, String>()
        runBlocking {
            Cert17e2.run(side, { ms -> usleep((ms * 1000L).toUInt()) }) { k, v -> mine[k] = esc(v) }
        }
        var same = 0
        var bad = 0
        for ((k, v) in mine) {
            val d = droid[k]
            if (d == v) same++
            else {
                bad++
                println("GTE17E2 mac: DIFF $k\n  droid=${d?.take(400)}\n  mac  =${v.take(400)}")
            }
        }
        val extra = droid.keys - mine.keys
        if (extra.isNotEmpty()) { bad += extra.size; println("GTE17E2 mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GTE17E2 mac: $same SAME, $bad DIFF, cross-device COMPARED -- done")
        assertEquals(0, bad, "the iPad's queue decisions differ from the Droid's")
    }
}
