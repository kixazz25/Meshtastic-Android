package com.grouptrack.shared.cert

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G2D-2026-11-12 ⚠ TEST-ONLY: the config files on the iPad simulator, VALUE FOR VALUE with the Droid's (gt17d_droid.txt): the same
 * Cert17d script on the shared GtMasterConfigStore / GtEventStore, with the Droid's asset text and the rider's own files from its report.
 */
class GtConfigMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) { val c = s[i]
            if (c == '\\' && i + 1 < s.length) { when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); else -> sb.append(s[i + 1]) }; i += 2 }
            else { sb.append(c); i++ } }
        return sb.toString()
    }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    @Test
    fun configs() {
        val fs = FileSystem.SYSTEM; val rp = "$outDir/gt17d_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GTD17C mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>(); var asset: String? = null; var master: String? = null; var nEv = -1
        val events = ArrayList<Pair<String, String>>()
        fs.read(rp) { while (true) { val l = readUtf8Line() ?: break
            if (!l.startsWith("D17C\t")) continue
            val p = l.split('\t', limit = 5); if (p.size < 4) continue
            when {
                p[1] == "input|asset" -> asset = unesc(p[3])
                p[1] == "input|riderMaster" -> master = if (p[3] == "NOFILE") null else unesc(p[3])
                p[1] == "input|riderEvents" -> nEv = p[3].toInt()
                p[1].startsWith("input|riderEvent|") && p.size == 5 -> events.add(unesc(p[3]) to unesc(p[4]))
                p[2] == "SAME" -> droid[p[1]] = l.split('\t', limit = 4)[3]
            } } }
        assertTrue(asset != null && nEv == events.size, "the Droid report's inputs are incomplete")
        val mine = LinkedHashMap<String, String>()
        val work = "$outDir/mac17d_work".toPath()
        Cert17d.run(Cert17d.NewSide(fs, work, { p -> fs.list(p) }), asset!!, master, events) { k, v -> mine[k] = esc(v) }
        var same = 0; var bad = 0
        for ((k, v) in mine) { val d = droid[k]; if (d == v) same++ else { bad++; println("GTD17C mac: DIFF $k\n  droid=${d?.take(300)}\n  mac  =${v.take(300)}") } }
        val extra = droid.keys - mine.keys
        if (extra.isNotEmpty()) { bad += extra.size; println("GTD17C mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GTD17C mac: $same SAME, $bad DIFF, cross-device COMPARED (asset ${asset!!.length} chars, rider master ${if (master == null) "none" else "${master!!.length} chars"}, rider events ${events.size}) -- done")
        assertEquals(0, bad, "the iPad's config files differ from the Droid's")
    }
}
