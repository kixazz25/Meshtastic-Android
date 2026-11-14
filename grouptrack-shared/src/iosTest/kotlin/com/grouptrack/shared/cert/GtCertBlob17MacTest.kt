package com.grouptrack.shared.cert

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BLOB17-2026-11-14 !! TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * the pass 3 pre-flight on the iPad simulator, against the Droid's (gt17blob_droid.txt).
 *
 * !! A DIFFERENCE HERE IS NOT A DEFECT -- IT IS THE ANSWER. This probe exists to find out whether
 * the two SQLite builds agree. A blob round trip that differs would be a BLOCKER for pass 3; a
 * pragma that differs is a PLATFORM FACT to be recorded as EXPECTED before reclaimableBytes is
 * certified. The two are reported separately, because they mean completely different things.
 */
class GtCertBlob17MacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    @Test
    fun blobProbe() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt17blob_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GT17BLOB mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>()
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("BLOB\t")) continue
                val p = l.split('\t', limit = 3)
                if (p.size == 3) droid[p[1]] = p[2]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no BLOB lines")

        val root = "$outDir/mac17blob_tmp".toPath()
        try { fs.deleteRecursively(root) } catch (e: Exception) { }
        fs.createDirectories(root)
        val mine = LinkedHashMap<String, String>()
        GtBlobProbe.run((root / "probe.db").toString()) { k, v -> mine[k] = esc(v) }

        // every blob round trip must say IDENTICAL on BOTH devices -- this is the blocker test
        var blockers = 0
        for ((k, v) in mine) if (k.endsWith("|roundtrip")) {
            if (v != "IDENTICAL") { blockers++; println("GT17BLOB mac: BLOCKER $k on the iPad = $v") }
            if (droid[k] != "IDENTICAL") { blockers++; println("GT17BLOB mac: BLOCKER $k on the Droid = ${droid[k]}") }
        }

        var same = 0
        val pragmaDiffs = ArrayList<String>()
        val realDiffs = ArrayList<String>()
        for ((k, v) in mine) {
            val d = droid[k]
            if (d == v) { same++; continue }
            // sqlite_version and the physical pragmas CAN differ legitimately -- two SQLite builds
            if (k.startsWith("pragma|") || k == "sqlite_version") pragmaDiffs.add("$k  droid=$d  mac=$v")
            else realDiffs.add("$k  droid=$d  mac=$v")
        }
        for (s in pragmaDiffs) println("GT17BLOB mac: PLATFORM-FACT $s")
        for (s in realDiffs) println("GT17BLOB mac: DIFF $s")
        println("GT17BLOB mac: $same SAME, ${realDiffs.size} DIFF, ${pragmaDiffs.size} PLATFORM-FACT, $blockers BLOCKER, cross-device COMPARED -- done")
        assertEquals(0, blockers, "a blob round trip is not byte-identical -- pass 3 cannot proceed on this driver")
        assertEquals(0, realDiffs.size, "the two SQLite builds disagree on something that is not a physical file property")
    }
}
