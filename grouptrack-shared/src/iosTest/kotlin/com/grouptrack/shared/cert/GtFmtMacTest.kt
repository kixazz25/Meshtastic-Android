package com.grouptrack.shared.cert

import com.grouptrack.shared.text.GtFmt
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test

/**
 * GTFMT-2026-11-07 (2.8 step 13a): GtFmt on the iPad simulator against Java's String.format answers -- 400,120 "%.{0-3}f" values
 * (given as the double's exact bits) and 50,000 "%,d" values, in $GT_SPIKE/_data/cert/gtfmt_golden_2026-11-07.txt.
 * Prints "GTFMT mac checked N bad M" and writes the first differences to GroupTrack_GTFMT_mac_<time>.txt. TEST-ONLY.
 */
class GtFmtMacTest {
    private val dir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun formats() {
        val fs = FileSystem.SYSTEM
        val gold = "$dir/gtfmt_golden_2026-11-07.txt".toPath()
        if (!fs.exists(gold)) { println("GTFMT mac: no golden file at $gold -- skipped"); return }
        var n = 0; var bad = 0; val diffs = ArrayList<String>()
        fs.read(gold) {
            while (true) {
                val line = readUtf8Line() ?: break
                val p = line.split('|', limit = 4)
                n++
                val ours = if (p[0] == "F") GtFmt.f(Double.fromBits(p[1].toULong(16).toLong()), p[2].toInt()) else GtFmt.d(p[1].toLong())
                if (ours != p[3]) { bad++; if (diffs.size < 40) diffs.add("DIFF|$line|ours=$ours") }
            }
        }
        val summary = "GTFMT mac checked $n bad $bad"
        println(summary)
        val stamp = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd_HHmm" }.stringFromDate(NSDate())
        fs.write("$dir/GroupTrack_GTFMT_mac_$stamp.txt".toPath()) { writeUtf8((listOf(summary) + diffs).joinToString("\n") + "\n") }
    }
}
