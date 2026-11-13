package com.grouptrack.shared.cert

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G1C-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * G1c (the map source spec) on the iPad simulator, VALUE FOR VALUE with the Droid's
 * (gt17g1c_droid.txt).
 *
 * ⛔ THE ASSET COMES FROM THE DROID REPORT, not from a bundle. The iPad has no APK, so the Droid
 * wrote the real map_sources.json into the report on a "# G1C-ASSET" line. Reading it back is what
 * guarantees both devices parsed byte-identical input -- without it this test would certify the
 * port against whatever happened to be lying around on the Mac.
 */
class GtCertMapSpec17g1cMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** The inverse of esc(), left to right so "\\n" is a backslash + n, not a newline. */
    private fun unesc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    @Test
    fun mapSpec() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt17g1c_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GT17G1C mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>()
        var asset: String? = null
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("# G1C-ASSET\t")) { asset = unesc(l.substringAfter('\t')); continue }
                if (!l.startsWith("G1C\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size == 4 && p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")
        val a = asset
        assertTrue(a != null && a.isNotBlank(), "the Droid report carries no # G1C-ASSET line -- the iPad cannot run the real asset")

        val mine = LinkedHashMap<String, String>()
        Cert17g1c.run(Cert17g1c.NewSide, a!!) { k, v -> mine[k] = esc(v) }

        // ⛔ For a key in EXPECTED_DIFF the Droid's SAME line is "<key>|newonly" (OLD and NEW are known
        // to differ there, so the base key is recorded as EXPECTED). The iPad still compares NEW to
        // NEW -- an expected OLD/NEW divergence is no reason to stop checking the two devices agree.
        fun lookup(k: String) = if (Cert17g1c.EXPECTED_DIFF.containsKey(k)) "$k|newonly" else k

        var same = 0
        var bad = 0
        for ((k, v) in mine) {
            val d = droid[lookup(k)]
            if (d == v) same++
            else { bad++; println("GT17G1C mac: DIFF ${lookup(k)}\n  droid=${d?.take(400)}\n  mac  =${v.take(400)}") }
        }
        val extra = droid.keys - mine.keys.map { lookup(it) }.toSet()
        if (extra.isNotEmpty()) { bad += extra.size; println("GT17G1C mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GT17G1C mac: $same SAME, $bad DIFF, cross-device COMPARED -- done")
        assertEquals(0, bad, "the iPad's map-source decisions differ from the Droid's")
    }
}
