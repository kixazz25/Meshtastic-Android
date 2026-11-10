package com.grouptrack.shared.cert

import com.grouptrack.shared.text.GtMath
import kotlin.math.pow
import kotlin.test.Test
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

/**
 * GTMATH2-2026-11-10 (TEST-ONLY, the iPad side of MATHPOW): GtMath.pow(x, 2.0) ON THE iPad against every value the Droid's
 * java.lang.Math.pow(x, 2.0) gave (the newest _data/cert/GroupTrack_MATHPOW_droid_*.txt). Also counts how often Apple's own pow would
 * have differed (the evidence for the rule). 0 DIFF = GtRead's haversine squares exactly as on Android.
 * Writes _data/cert/GroupTrack_MATHPOWPORT_mac_<time>.txt (a new name every run).
 */
class GtMathPowMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun bits(x: Double) = x.toRawBits().toULong().toString(16)

    @Test
    fun powEqualsDroid() {
        val fs = FileSystem.SYSTEM
        val input = fs.list(outDir.toPath()).filter { it.name.startsWith("GroupTrack_MATHPOW_droid_") && it.name.endsWith(".txt") }.maxByOrNull { it.name }
        val sb = StringBuilder("# MATHPOWPORT mac -- GtMath.pow(x, 2.0) on the iPad vs the Droid's java.lang.Math.pow(x, 2.0), value for value\n")
        if (input == null) { sb.append("# STOP: no GroupTrack_MATHPOW_droid_*.txt in $outDir\n") } else {
            sb.append("# input ").append(input.name).append('\n')
            var n = 0L; var d = 0L; var apple = 0L; var ex: String? = null; var exApple: String? = null
            fs.read(input) {
                while (true) {
                    val l = readUtf8Line() ?: break
                    if (l.isEmpty() || l[0] != 'P') { if (l.startsWith("# MATHPOW droid")) sb.append(l).append('\n'); continue }
                    val p = l.split('|'); val x = Double.fromBits(p[1].toULong(16).toLong()); val want = p[2].toULong(16).toLong()
                    val v = GtMath.pow(x, 2.0); val a = x.pow(2.0)
                    n++
                    if (v.toRawBits() != want) { d++; if (ex == null) ex = l + " ipad=" + bits(v) }
                    if (a.toRawBits() != want) { apple++; if (exApple == null) exApple = l + " apple=" + bits(a) }
                }
            }
            sb.append("P: ").append(n).append(" Droid values, iPad GtMath differs=").append(d).append(ex?.let { "  e.g. $it" } ?: "").append('\n')
            sb.append("   (Apple's own pow would differ on ").append(apple).append(exApple?.let { "  e.g. $it" } ?: "").append(")\n")
            sb.append("# MATHPOWPORT mac done: ").append(if (d == 0L && n > 0) "0 DIFF -- the iPad squares exactly as Android" else "$d DIFF").append('\n')
        }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmmss"
        fs.write("$outDir/GroupTrack_MATHPOWPORT_mac_${f.stringFromDate(NSDate())}.txt".toPath()) { writeUtf8(sb.toString()) }
        println(sb)
    }
}
