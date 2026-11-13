package com.grouptrack.shared.cert

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G2F1-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * G2f PIECE 1 (everyLaunch) on the iPad simulator, VALUE FOR VALUE with the Droid's (gt17g2f1_droid.txt).
 *
 * The same Cert17g2f script on the same shared GtStartupHousekeeping, through the same TripwireEnv --
 * so what is compared is the port's own behaviour on the two platforms, with no platform collaborator
 * reachable at all. ⭐ A green run here says everyLaunch is inert on the iPad exactly as it is on the
 * Droid, and that the whole pipe -- script, report, replay, cross-device compare -- is sound before
 * pieces 2 to 4 push real volume through it.
 */
class GtCertHk17g2f1MacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    @Test
    fun housekeepEveryLaunch() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt17g2f1_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GT17G2F1 mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>()
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("G2F1\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size == 4 && p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")

        val mine = LinkedHashMap<String, String>()
        Cert17g2f.run(Cert17g2f.NewSide()) { k, v -> mine[k] = esc(v) }

        var same = 0
        var bad = 0
        for ((k, v) in mine) {
            val d = droid[k]
            if (d == v) same++
            else { bad++; println("GT17G2F1 mac: DIFF $k\n  droid=${d?.take(400)}\n  mac  =${v.take(400)}") }
        }
        val extra = droid.keys - mine.keys
        if (extra.isNotEmpty()) { bad += extra.size; println("GT17G2F1 mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GT17G2F1 mac: $same SAME, $bad DIFF, cross-device COMPARED -- done")
        assertEquals(0, bad, "the iPad's housekeeping decisions differ from the Droid's")
    }
}
