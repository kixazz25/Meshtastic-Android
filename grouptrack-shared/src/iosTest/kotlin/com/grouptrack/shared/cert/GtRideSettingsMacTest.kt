package com.grouptrack.shared.cert

import com.grouptrack.shared.settings.GtRideSettings
import com.grouptrack.shared.settings.GtSettings
import com.grouptrack.shared.settings.GtSettingsEditor
import com.grouptrack.shared.settings.GtSettingsStore
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G2B2-2026-11-12 ⚠ TEST-ONLY: the ride settings on the iPad simulator, VALUE FOR VALUE with the Droid's NEW: the Droid's input
 * (its DataStore values and day number, from gt17c_droid.txt) through the same one-time copy and the same changes.
 */
class GtRideSettingsMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
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
    private fun enc(v: Any?): String = when (v) {
        null -> "null"; is String -> "s:$v"; is Int -> "i:$v"; is Long -> "l:$v"; is Float -> "f:$v"; is Boolean -> "b:$v"
        is Set<*> -> "set:" + v.map { it.toString() }.sorted().joinToString("\u001f"); else -> "?:$v"
    }
    private fun dec(s: String): Any? {
        if (s == "null") return null
        val i = s.indexOf(':'); val t = s.substring(0, i); val v = s.substring(i + 1)
        return when (t) { "s" -> v; "i" -> v.toInt(); "l" -> v.toLong(); "f" -> v.toFloat(); "b" -> v.toBoolean()
            "set" -> if (v.isEmpty()) emptySet() else v.split('\u001f').toSet(); else -> v }
    }

    @Test
    fun ride() {
        val fs = FileSystem.SYSTEM; val rp = "$outDir/gt17c_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GTS17C mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>(); val raw = LinkedHashMap<String, Any?>(); var day = -1L
        fs.read(rp) { while (true) { val l = readUtf8Line() ?: break
            if (!l.startsWith("S17C\t")) continue
            val p = l.split('\t', limit = 4); if (p.size < 4) continue
            when { p[1] == "input-day" -> day = p[3].toLong(); p[2] == "INPUT" -> raw[p[1].removePrefix("input|")] = dec(p[3]); p[2] == "SAME" -> droid[p[1]] = p[3] } } }
        assertTrue(day > 0, "no day in the Droid report")
        val st = Mem(); val ns = st.store("gtcert17b2_new"); val n = GtRideSettings(st, "gtcert17b2_new") { day }
        val mine = LinkedHashMap<String, String>()
        fun reads(tag: String) {
            mine["$tag|signalDropMinutes"] = "${n.signalDropMinutes()}"; mine["$tag|signalLostMinutes"] = "${n.signalLostMinutes()}"
            mine["$tag|offTrackMiles"] = "${n.offTrackMiles()}"; mine["$tag|offTrackMilesSaved"] = "${n.offTrackMilesSaved()}"
            mine["$tag|admissionWindowHours"] = "${n.admissionWindowHours()}"; mine["$tag|removedCartsForToday"] = "${n.removedCartsForToday().entries.sortedBy { it.key }.associate { it.key to it.value }}"
            mine["$tag|removed_carts(raw)"] = enc(ns.getStringSet("removed_carts", null))
        }
        ns.edit { clear() }
        mine["copy|count"] = "${n.fillMissing(raw)}"
        reads("seeded")
        n.setSignalDropMinutes(3.5f); n.setSignalLostMinutes(7f); n.setOffTrackMiles(0.4f); n.setAdmissionWindowHours(2)
        n.removeCart("!a1b2c3d4", "Fred"); n.removeCart("!e5f6a7b8", "Lead/Car"); n.reinstateCart("!a1b2c3d4"); n.purgeStaleRemovedCarts()
        reads("ops")
        n.clearRemovedCarts(); reads("cleared")
        ns.edit { clear() }; reads("empty")
        n.setSignalDropMinutes(5f)
        val c2 = n.fillMissing(mapOf("signal_drop_minutes" to 9f, "admission_window_hours" to 3))
        mine["copy|no-overwrite"] = "${n.signalDropMinutes()} / $c2 copied / ${n.admissionWindowHours()}"
        var same = 0; var bad = 0
        for ((k, v) in mine) { val d = droid[k]; if (d == v) same++ else { bad++; println("GTS17C mac: DIFF $k droid=$d mac=$v") } }
        println("GTS17C mac: $same SAME, $bad DIFF, cross-device COMPARED (${raw.size} input keys, day $day) -- done")
        assertEquals(0, bad, "the iPad's ride settings differ from the Droid's")
    }
}
