package com.grouptrack.shared.cert

import com.grouptrack.shared.text.GtMath
import kotlin.math.PI
import kotlin.test.Test
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

/**
 * MATHTILE2-2026-11-10 (TEST-ONLY, 2.8 step 16a, the PROOF of the port): GtMath tan / ln / sinh / atan / cos ON THE iPad (GtMathFdlibm, GtMathLog, GtMathPort)
 * against every value the Droid's java.lang.Math gave (the newest _data/cert/GroupTrack_MATHTILE_droid_*.txt), function by function, and
 * the two whole expressions the save-by-area writes keep: Y = lat2tile before * 2^z, O = a tile row's edge latitude (a stored box edge).
 * 0 DIFF on a function = the shared code may call the platform's function for it; any DIFF = that function needs GtMath (as cos / pow).
 * Writes _data/cert/GroupTrack_MATHTILE2_mac_<time>.txt (a new name every run).
 */
class GtMathTile2MacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun bits(x: Double) = x.toRawBits().toULong().toString(16)
    private fun d(hex: String) = Double.fromBits(hex.toULong(16).toLong())
    private val D2R = 0.017453292519943295
    private val R2D = 57.29577951308232

    @Test
    fun tileMathEqualsDroid() {
        val fs = FileSystem.SYSTEM
        val input = fs.list(outDir.toPath()).filter { it.name.startsWith("GroupTrack_MATHTILE_droid_") && it.name.endsWith(".txt") }.maxByOrNull { it.name }
        val sb = StringBuilder("# MATHTILE2 mac -- the save-by-area tile math on the iPad vs the Droid's java.lang.Math, value for value\n")
        if (input == null) { sb.append("# STOP: no GroupTrack_MATHTILE_droid_*.txt in $outDir\n") } else {
            sb.append("# input ").append(input.name).append('\n')
            val n = LinkedHashMap<String, Long>(); val diff = LinkedHashMap<String, Long>(); val ex = LinkedHashMap<String, String>()
            fs.read(input) {
                while (true) {
                    val l = readUtf8Line() ?: break
                    if (l.isEmpty() || l.startsWith("#")) { if (l.startsWith("# MATHTILE droid")) sb.append(l).append('\n'); continue }
                    val p = l.split('|'); val k = p[0]; val want = p[2].toULong(16).toLong()
                    val got = when (k) {
                        "T" -> GtMath.tan(d(p[1]))
                        "C" -> GtMath.cos(d(p[1]))
                        "L" -> GtMath.ln(d(p[1]))
                        "Y" -> { val rad = d(p[1]) * D2R; (1.0 - GtMath.ln(GtMath.tan(rad) + 1.0 / GtMath.cos(rad)) / PI) / 2.0 }
                        "SH" -> GtMath.sinh(d(p[1]))
                        "AT" -> GtMath.atan(d(p[1]))
                        "O" -> { val (z, y) = p[1].split(','); val kk = PI - 2.0 * PI * y.toLong() / (1L shl z.toInt()).toDouble(); GtMath.atan(GtMath.sinh(kk)) * R2D }
                        else -> continue
                    }
                    n[k] = (n[k] ?: 0L) + 1
                    if (got.toRawBits() != want) { diff[k] = (diff[k] ?: 0L) + 1; if (k !in ex) ex[k] = "$l ipad=${bits(got)}" }
                }
            }
            var all = 0L
            for ((k, c) in n) { val dd = diff[k] ?: 0L; all += dd
                sb.append(k.padEnd(3)).append(c).append(" values, iPad differs=").append(dd).append(ex[k]?.let { "  e.g. $it" } ?: "").append('\n') }
            sb.append("# MATHTILE2 mac done: ").append(if (all == 0L && n.isNotEmpty()) "0 DIFF -- GtMath tan / ln / sinh / atan = Android on these inputs" else "$all DIFF").append('\n')
        }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmmss"
        fs.write("$outDir/GroupTrack_MATHTILE2_mac_${f.stringFromDate(NSDate())}.txt".toPath()) { writeUtf8(sb.toString()) }
        println(sb)
    }
}
