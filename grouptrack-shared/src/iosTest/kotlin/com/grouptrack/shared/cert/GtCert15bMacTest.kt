package com.grouptrack.shared.cert

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * STEP15B-2026-11-09 (2.8 step 15b) ⚠ TEST-ONLY: the 15b parsers and converter on the iPad simulator. Runs the SAME runner as the Droid
 * (Cert15b.runFile with NewSide) on the SAME test files ($dir/cert15b/, copied there by gt_cert_v16 parse15b) and compares every value
 * with NEW's value in the Droid's report ($dir/gt15b_droid.txt -- the P15B lines; a DIFF / EXPECTED line's "  NEW" line). A key on one
 * side only is a difference too. Writes GroupTrack_GT15B_mac_<time>.txt (summary + the first differences), then fails on any
 * difference. No report or no test files -> skipped (as GtFmtMacTest).
 */
class GtCert15bMacTest {
    private val dir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun parse15b() {
        val fs = FileSystem.SYSTEM
        val report = "$dir/gt15b_droid.txt".toPath()
        val files = "$dir/cert15b".toPath()
        if (!fs.exists(report) || !fs.exists(files)) { println("GT15B mac: no report at $report or no files at $files -- skipped"); return }
        // the Droid's NEW value per key
        val droid = LinkedHashMap<String, String>()
        var pending: String? = null   // CODE RULE 1: null = the previous line was not a DIFF / EXPECTED head
        fs.read(report) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("P15B\t")) {
                    val p = l.split('\t', limit = 4)
                    if (p.size < 4) continue
                    droid[p[1]] = p[3]
                    pending = if (p[2] == "SAME") null else p[1]
                } else if (l.startsWith("  NEW\t") && pending != null) {
                    droid[pending!!] = l.removePrefix("  NEW\t"); pending = null
                }
            }
        }
        val work = "$dir/gt15b_work".toPath()
        if (fs.exists(work)) fs.deleteRecursively(work)
        fs.createDirectories(work)
        val mac = LinkedHashMap<String, String>()
        for (f in fs.list(files).sortedBy { it.name }) {
            if (fs.metadata(f).isDirectory) continue
            Cert15b.runFile(Cert15b.NewSide, fs, f, work) { k, v -> mac[k] = Cert15b.esc(v) }
        }
        var same = 0; var bad = 0; var expected = 0
        val diffs = ArrayList<String>()
        // STEP15B-FIXC-2026-11-09: a ZIP's "read" line is EXPECTED -- its bytes are not text, and the platforms decode invalid UTF-8 differently
        val zips = fs.list(files).filter { !fs.metadata(it).isDirectory && fs.read(it) { if (exhausted()) "" else readUtf8CodePoint().toChar().toString() + (if (exhausted()) "" else readUtf8CodePoint().toChar().toString()) } == "PK" }.map { it.name }.toSet()
        // the Droid's keys carry " (only OLD)" / " (only NEW)" where its own two sides disagreed in shape; NEW's own keys are compared
        val droidNew = droid.filterKeys { !it.endsWith(" (only OLD)") }.mapKeys { it.key.removeSuffix(" (only NEW)") }
        for ((k, v) in mac) {
            val d = droidNew[k]
            if (d == v) same++
            else if (d != null && k.endsWith("|read") && k.removeSuffix("|read") in zips) { expected++; diffs.add("EXPECTED|$k|binary zip read as text|droid=${d.take(200)}|mac=${v.take(200)}") }
            else { bad++; if (diffs.size < 60) diffs.add("DIFF|$k|droid=${d?.take(200)}|mac=${v.take(200)}") }
        }
        for (k in droidNew.keys) if (k !in mac) { bad++; if (diffs.size < 60) diffs.add("DIFF|$k|only on the Droid") }
        val summary = "GT15B mac checked ${mac.size} keys: $same same, $bad differ, $expected expected (Droid report: ${droid.size} keys)"
        println(summary); diffs.forEach { println(it) }
        val stamp = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd_HHmm" }.stringFromDate(NSDate())
        fs.write("$dir/GroupTrack_GT15B_mac_$stamp.txt".toPath()) { writeUtf8((listOf(summary) + diffs).joinToString("\n") + "\n") }
        assertEquals(0, bad, "GT15B: the iPad's NEW differs from the Droid's NEW")
    }
}
