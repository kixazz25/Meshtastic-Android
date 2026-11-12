package com.grouptrack.shared.cert

import com.grouptrack.shared.settings.GtConfig
import com.grouptrack.shared.settings.GtSession
import com.grouptrack.shared.settings.GtSettings
import com.grouptrack.shared.settings.GtSettingsEditor
import com.grouptrack.shared.settings.GtSettingsStore
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G2B1-2026-11-12 ⚠ TEST-ONLY: the session on the iPad simulator, VALUE FOR VALUE with the Droid's NEW. Reads the Droid's report
 * (gt_cert_v26 copies it to $outDir/gt17b_droid.txt), seeds an in-memory store with the Droid's input lines (the rider's store), runs the
 * same reads, the same writes on a copy, clearSession, and the constants -- every value must equal the Droid's. "cross-device COMPARED"
 * only when the Droid's input was found and used.
 */
class GtSessionMacTest {
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
        is Set<*> -> "set:" + v.map { it.toString() }.sorted().joinToString("\u001f")
        else -> "?:$v"
    }
    private fun dec(s: String): Any? {
        if (s == "null") return null
        val i = s.indexOf(':'); val t = s.substring(0, i); val v = s.substring(i + 1)
        return when (t) { "s" -> v; "i" -> v.toInt(); "l" -> v.toLong(); "f" -> v.toFloat(); "b" -> v.toBoolean()
            "set" -> if (v.isEmpty()) emptySet() else v.split('\u001f').toSet(); else -> v }
    }

    @Test
    fun session() {
        val fs = FileSystem.SYSTEM; val rp = "$outDir/gt17b_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GTS17B mac: no Droid report ($rp) -- run gt_cert_v26 session17b first"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>(); val input = LinkedHashMap<String, String>()
        fs.read(rp) { while (true) { val l = readUtf8Line() ?: break
            if (!l.startsWith("S17B\t")) continue
            val p = l.split('\t', limit = 4); if (p.size < 4) continue
            if (p[2] == "INPUT") input[p[1].removePrefix("input|")] = p[3] else if (p[2] == "SAME") droid[p[1]] = p[3] } }
        val st = Mem()
        st.store(GtSession.STORE).edit { for ((k, v) in input) when (val x = dec(v)) {
            is String -> putString(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x)
            is Boolean -> putBoolean(k, x); is Set<*> -> putStringSet(k, x.map { it.toString() }.toSet()) } }
        val mine = LinkedHashMap<String, String>()
        val n = GtSession(st, GtSession.STORE) { 1L }   // the clock only decides a subscription expiry: the time-dependent answers are compared on the Droid only
        mine["read|getUserId"] = "${n.getUserId()}"; mine["read|isSignedIn"] = "${n.isSignedIn()}"
        mine["read|termsAccepted"] = "${n.termsAccepted()}"; mine["read|privacyAccepted"] = "${n.privacyAccepted()}"
        mine["read|isOrganizer"] = "${n.isOrganizer()}"; mine["read|getFirstName"] = n.getFirstName(); mine["read|getEmail"] = n.getEmail()
        mine["read|getZipCode"] = n.getZipCode(); mine["read|getCity"] = n.getCity(); mine["read|getState"] = n.getState()
        mine["read|getSearchRadius"] = "${n.getSearchRadius()}"
        // copy + writes
        val cp = st.store("gtcert17b_new"); cp.edit { clear() }
        cp.edit { for ((k, v) in input) when (val x = dec(v)) { is String -> putString(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x)
            is Float -> putFloat(k, x); is Boolean -> putBoolean(k, x); is Set<*> -> putStringSet(k, x.map { it.toString() }.toSet()) } }
        val nn = GtSession(st, "gtcert17b_new") { 1L }
        nn.setCity("Kanab"); nn.setState("UT"); nn.setZipCode("84741"); nn.setSearchRadius(40)
        nn.saveUser("cert-user-1", "cert-google-1", "cert@example.com", "Cert", "Rider"); nn.acceptTerms(); nn.acceptPrivacy()
        nn.setOrganizer(true); nn.setSubscriptionExpiry(1893456000000L)
        for ((k, v) in cp.all()) mine["write|$k"] = enc(v)
        mine["write|route(true)"] = nn.resolveLaunchRoute(true).name
        nn.clearSession(); mine["write|cleared"] = "${cp.all().size}"
        mine["config|MAP_DEFAULT_ZOOM"] = "${GtConfig.MAP_DEFAULT_ZOOM}"; mine["config|LOCAL_TILE_BASE"] = GtConfig.LOCAL_TILE_BASE
        mine["config|MAP_GROUP_ZOOM_PADDING"] = "${GtConfig.MAP_GROUP_ZOOM_PADDING}"; mine["config|MAP_CART_ZOOM"] = "${GtConfig.MAP_CART_ZOOM}"
        mine["config|MAP_MIN_ZOOM"] = "${GtConfig.MAP_MIN_ZOOM}"; mine["config|BLINK_LOST_MS"] = "${GtConfig.BLINK_LOST_MS}"
        mine["config|BLINK_DROP_MS"] = "${GtConfig.BLINK_DROP_MS}"; mine["config|MARKER_SIZE_LARGE_DP"] = "${GtConfig.MARKER_SIZE_LARGE_DP}"
        mine["config|MARKER_SIZE_MEDIUM_DP"] = "${GtConfig.MARKER_SIZE_MEDIUM_DP}"; mine["config|API_BASE_URL"] = GtConfig.API_BASE_URL
        mine["config|V3_FEATURES_ENABLED"] = "${GtConfig.V3_FEATURES_ENABLED}"; mine["config|PAYWALL_ENABLED"] = "${GtConfig.PAYWALL_ENABLED}"
        mine["config|IS_STANDALONE_BUILD"] = "${GtConfig.IS_STANDALONE_BUILD}"; mine["config|DOWNLOAD_ZOOM_MIN"] = "${GtConfig.DOWNLOAD_ZOOM_MIN}"
        var same = 0; var bad = 0
        for ((k, v) in mine) { val d = droid[k]; if (d == v) same++ else { bad++; println("GTS17B mac: DIFF $k droid=$d mac=$v") } }
        val writeKeysDroid = droid.keys.filter { it.startsWith("write|") && it != "write|route(true)" && it != "write|cleared" }.toSet()
        val writeKeysMine = mine.keys.filter { it.startsWith("write|") && it != "write|route(true)" && it != "write|cleared" }.toSet()
        if (writeKeysDroid != writeKeysMine) { bad++; println("GTS17B mac: DIFF write keys droid=$writeKeysDroid mac=$writeKeysMine") }
        val cross = input.isNotEmpty() || droid.containsKey("read|getUserId")
        println("GTS17B mac: $same SAME, $bad DIFF, cross-device ${if (cross) "COMPARED" else "NOT COMPARED"} (${input.size} input keys) -- done")
        assertEquals(0, bad, "the iPad's session differs from the Droid's")
    }
}
