package com.grouptrack.shared.cert

import com.grouptrack.shared.text.GtMath
import kotlin.test.Test
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

/**
 * GTMATH-2026-11-10 (TEST-ONLY, the iPad side): GtMath ON THE iPad against every value the Droid's java.lang.Math gave in MATHPROBE
 * (the newest _data/cert/GroupTrack_MATHPROBE_droid_*.txt: 6,000,000 cos / sin / asin / hypot from 1.47 million real points + random).
 * 0 differences = the iPad now computes Android's bits. Writes _data/cert/GroupTrack_MATHPORT_mac_<time>.txt (a new name every run).
 */
class GtMathPortMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun bits(x: Double) = x.toRawBits().toULong().toString(16)

    @Test
    fun portEqualsDroid() {
        val fs = FileSystem.SYSTEM
        val input = fs.list(outDir.toPath()).filter { it.name.startsWith("GroupTrack_MATHPROBE_droid_") && it.name.endsWith(".txt") }.maxByOrNull { it.name }
        val sb = StringBuilder("# MATHPORT mac -- GtMath on the iPad vs the Droid's java.lang.Math, value for value\n")
        if (input == null) { sb.append("# STOP: no GroupTrack_MATHPROBE_droid_*.txt in $outDir\n") } else {
            sb.append("# input ").append(input.name).append('\n')
            val n = LongArray(4); val d = LongArray(4); val ex = arrayOfNulls<String>(4); val fn = "CSAH"
            fs.read(input) {
                while (true) {
                    val l = readUtf8Line() ?: break
                    if (l.isEmpty() || l[0] == '#') continue
                    val k = fn.indexOf(l[0]); if (k < 0) continue
                    val p = l.split('|'); val want = p[2].toULong(16).toLong()
                    val v = if (k == 3) { val xy = p[1].split(','); GtMath.hypot(Double.fromBits(xy[0].toULong(16).toLong()), Double.fromBits(xy[1].toULong(16).toLong())) }
                            else { val x = Double.fromBits(p[1].toULong(16).toLong()); when (k) { 0 -> GtMath.cos(x); 1 -> GtMath.sin(x); else -> GtMath.asin(x) } }
                    n[k]++
                    if (v.toRawBits() != want) { d[k]++; if (ex[k] == null) ex[k] = l + " ipad=" + bits(v) }
                }
            }
            for (k in 0 until 4) sb.append(fn[k]).append(": ").append(n[k]).append(" Droid values, iPad differs=").append(d[k]).append(ex[k]?.let { "  e.g. $it" } ?: "").append('\n')
            sb.append("# MATHPORT mac done: ").append(if (d.all { it == 0L } && n.sum() > 0) "0 DIFF -- the iPad computes Android's bits" else "${d.sum()} DIFF").append('\n')
        }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmmss"
        fs.write("$outDir/GroupTrack_MATHPORT_mac_${f.stringFromDate(NSDate())}.txt".toPath()) { writeUtf8(sb.toString()) }
        println(sb)
    }
}
