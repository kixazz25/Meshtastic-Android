package com.grouptrack.shared.cert

import com.grouptrack.shared.settings.GtSettingsEditor
import com.grouptrack.shared.settings.GtSettingsStore
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G2C2-2026-11-12 ⚠ TEST-ONLY: the V2 local user on the iPad simulator, VALUE FOR VALUE with the Droid's (gt17u_droid.txt): the
 * same Cert17c2 script on the shared GtUserStore, with the rider's own file from the Droid's report as its last case.
 */
class GtUserMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private class Mem : GtSettingsStore {
        val m = LinkedHashMap<String, Any?>()
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
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) { val c = s[i]
            if (c == '\\' && i + 1 < s.length) { when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); else -> sb.append(s[i + 1]) }; i += 2 }
            else { sb.append(c); i++ } }
        return sb.toString()
    }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    @Test
    fun users() {
        val fs = FileSystem.SYSTEM; val rp = "$outDir/gt17u_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GTU17C mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>(); var own: String? = null; var sawOwn = false
        fs.read(rp) { while (true) { val l = readUtf8Line() ?: break
            if (!l.startsWith("U17C\t")) continue
            val p = l.split('\t', limit = 4); if (p.size < 4) continue
            when { p[1] == "input|riderOwn" -> { sawOwn = true; own = if (p[3] == "NOFILE") null else unesc(p[3]) }; p[2] == "SAME" -> droid[p[1]] = p[3] } } }
        assertTrue(sawOwn, "no rider-own input line in the Droid report")
        val mine = LinkedHashMap<String, String>()
        val work = "$outDir/mac17u_work".toPath()
        Cert17c2.run(Cert17c2.NewSide(fs, work, Mem()), own) { k, v -> mine[k] = esc(v) }
        var same = 0; var bad = 0
        for ((k, v) in mine) { val d = droid[k]; if (d == v) same++ else { bad++; println("GTU17C mac: DIFF $k\n  droid=$d\n  mac  =$v") } }
        val extra = droid.keys - mine.keys
        if (extra.isNotEmpty()) { bad += extra.size; println("GTU17C mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GTU17C mac: $same SAME, $bad DIFF, cross-device COMPARED (${Cert17c2.CASES.size} cases + rider's own: ${if (own == null) "no file" else "${own!!.length} chars"}) -- done")
        assertEquals(0, bad, "the iPad's users differ from the Droid's")
    }
}
