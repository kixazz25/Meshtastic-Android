package com.grouptrack.shared.cert

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G1A-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * G1a (the tile-set arithmetic) on the iPad simulator, VALUE FOR VALUE with the Droid's
 * (gt17g1a_droid.txt).
 *
 * ⭐ WHY THE CROSS-DEVICE HALF IS THE POINT HERE. The Droid half proves the PORT is faithful; this
 * half proves the SHARED CODE computes the same thing on Apple silicon. G1a is pure floating-point
 * tile math, which is exactly where the two platforms could disagree -- MATHTILE and MATHPOW were
 * both found this way (Math.pow(x, 2.0) != x*x). The port calls the certified GtTileMath, so this
 * should be clean; "should be" is why it gets run.
 */
class GtCertTile17g1aMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    @Test
    fun tileCalc() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt17g1a_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GT17G1A mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>()
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("G1A\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size == 4 && p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")

        val mine = LinkedHashMap<String, String>()
        Cert17g1a.run(Cert17g1a.NewSide) { k, v -> mine[k] = esc(v) }

        var same = 0
        var bad = 0
        for ((k, v) in mine) {
            val d = droid[k]
            if (d == v) same++
            else { bad++; println("GT17G1A mac: DIFF $k\n  droid=${d?.take(400)}\n  mac  =${v.take(400)}") }
        }
        val extra = droid.keys - mine.keys
        if (extra.isNotEmpty()) { bad += extra.size; println("GT17G1A mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GT17G1A mac: $same SAME, $bad DIFF, cross-device COMPARED -- done")
        assertEquals(0, bad, "the iPad's tile arithmetic differs from the Droid's")
    }
}
