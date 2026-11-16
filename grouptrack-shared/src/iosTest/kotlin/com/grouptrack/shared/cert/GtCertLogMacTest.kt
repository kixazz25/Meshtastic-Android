package com.grouptrack.shared.cert

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CERTLOG-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * the LOGGING seam conversion on the iPad simulator, VALUE FOR VALUE with the Droid's
 * (gt_certlog_droid.txt).
 *
 * ⚠ NOTHING IS REIMPLEMENTED HERE. The NEW side, the corpus, the key form and the value form are
 * CertLog's -- the same common code the Droid ran. Only the directory differs.
 *
 * Fred 11-16 08:02: "old message on droid 1 then new method on droid 1 then iPad version -- all
 *   have to be identical."   08:18: "no shortcuts. all three have to execute and be compared."
 *   08:20: "write their summary files, move to the Mac and compare... that is what we did before."
 *
 * THE THREE LEGS: OLD and NEW are compared ON THE DROID, and only its SAME lines are loaded here --
 * so if OLD and NEW disagree on the Droid this test fails before it compares anything, exactly as
 * GtCertMBTiles17MacTest requires the Droid to agree first. IPAD is this run: the same shared
 * newSide() over the same corpus, so a difference from the Droid's NEW is a PLATFORM difference --
 * the class that bit us on GtRx.WS (\s is Unicode on Android, ASCII on iOS), Math.toRadians and
 * NSDate.
 *
 * ⚠ NON-ASCII IS CALLED OUT SEPARATELY. The corpus carries 37 non-ASCII characters (34 em dashes
 * U+2014, 3 arrows U+2192) among 2548 quotes, 1455 dollars, 724 brace pairs, 88 newlines, 24
 * backslashes and 13 percents. If a difference is confined to those cases it is a UTF-8 handling
 * fact about the two platform writers, not a defect in the 994-site conversion -- so it is named
 * rather than buried in a DIFF list.
 */
class GtCertLogMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun certlog() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt_certlog_droid.txt".toPath()
        if (!fs.exists(rp)) { println("CERTLOG mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }

        val droid = LinkedHashMap<String, String>()
        var droidDiff = 0
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("CERTLOG\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size != 4) continue
                if (p[2] == "DIFF") droidDiff++
                if (p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")
        assertEquals(0, droidDiff, "the Droid's own OLD vs NEW already differs in $droidDiff case(s) -- fix that first")

        val cp = "$outDir/gt_logcert_corpus_2026-11-16.tsv".toPath()
        assertTrue(fs.exists(cp), "no corpus at $cp")
        val parsed = CertLog.parseCorpus(fs.read(cp) { readUtf8() })
        val cases = parsed.first
        for (b in parsed.second) println("CERTLOG mac: BADROW $b")
        assertTrue(cases.isNotEmpty(), "the corpus parsed to 0 cases")

        val mine = LinkedHashMap<String, String>()
        CertLog.run(cases, CertLog.newSide()) { k, v -> mine[k] = v }

        fun lookup(k: String) = if (CertLog.EXPECTED_DIFF.containsKey(k)) "$k|newonly" else k
        val nonAsciiKeys = HashSet<String>()
        for ((i, c) in cases.withIndex()) if (c.text.any { it.code > 126 }) nonAsciiKeys.add(CertLog.keyOf(i, c))

        var same = 0
        val utf8 = ArrayList<String>()
        val real = ArrayList<String>()
        for ((k, v) in mine) {
            val d = droid[lookup(k)]
            if (d == v) { same++; continue }
            if (nonAsciiKeys.contains(k)) utf8.add("$k  droid=$d  mac=$v")
            else real.add("$k  droid=$d  mac=$v")
        }
        val extra = droid.keys - mine.keys.map { lookup(it) }.toSet()
        for (s in utf8) println("CERTLOG mac: UTF8-FACT $s")
        for (s in real) println("CERTLOG mac: DIFF $s")
        if (extra.isNotEmpty()) println("CERTLOG mac: DIFF the Droid has keys the iPad did not run: $extra")
        val summary = "CERTLOG mac: $same SAME, ${real.size + extra.size} DIFF, ${utf8.size} UTF8-FACT, " +
            "${cases.size} cases, ${nonAsciiKeys.size} non-ASCII, cross-device COMPARED -- done"
        println(summary)
        fs.write("$outDir/gt_certlog_ipad.txt".toPath()) {
            writeUtf8((listOf("# " + summary) + mine.map { "CERTLOG\t${it.key}\tIPAD\t${it.value}" } + utf8 + real)
                .joinToString("\n") + "\n")
        }
        assertEquals(0, real.size + extra.size + utf8.size,
            "the iPad's Kermit output differs from the Droid's -- see gt_certlog_ipad.txt")
    }
}
