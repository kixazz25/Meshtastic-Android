package com.grouptrack.shared.cert

import com.grouptrack.shared.text.GtJava
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

/**
 * MATHPROBE-2026-11-10 v b (TEST-ONLY, the iPad side): Fred 11-10 07:17 "every activity is off by 1 byte -- it is something systemic, find
 * it". Cert 2026-11-09_1919 import15e: 5 tracks' distance_miles differ in the last bit, Droid vs iPad. The smallest is a 2-POINT track
 * ("Cart !emu000c to Cart !emu000d", -104.9818937 39.0120809 -> -104.9803944 39.0130806) = ONE call of GtTracks.haversineMiles.
 * This runs that call step by step on the iPad and prints every intermediate's raw bits next to the Droid's (= Java's; checked 11-10
 * against Java Math, Java StrictMath/fdlibm, glibc and a 300-bit exact computation -- all agree, every step correctly rounded).
 * The first step that differs is the cause. Writes _data/cert/GroupTrack_MATHPROBE_mac_<ms>.txt (a new name every run).
 */
class GtMathProbeMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    private fun b(x: Double): String = x.toRawBits().toULong().toString(16).padStart(16, '0')

    @Test
    fun haversineSteps() {
        val lon1 = -104.9818937; val lat1 = 39.0120809; val lon2 = -104.9803944; val lat2 = 39.0130806
        val R = 3958.7613
        val dLat = GtJava.toRadians(lat2 - lat1)
        val dLon = GtJava.toRadians(lon2 - lon1)
        val l1 = GtJava.toRadians(lat1); val l2 = GtJava.toRadians(lat2)
        val s1 = sin(dLat / 2); val c1 = cos(l1); val c2 = cos(l2); val s2 = sin(dLon / 2)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(GtJava.toRadians(lat1)) * cos(GtJava.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val aSplit = s1 * s1 + c1 * c2 * s2 * s2          // the same sum from stored values (a fused multiply-add would show here)
        val q = sqrt(a)
        val asn = asin(min(1.0, q))
        val d = 2 * R * asn
        val viaTracks = com.grouptrack.shared.track.GtTracks.haversineMiles(lat1, lon1, lat2, lon2)
        val droid = linkedMapOf(
            "dLat" to "3ef24bad50a0cc42", "dLon" to "3efb7058208d1a77", "lat1r" to "3fe5c9d845dba7dc", "lat2r" to "3fe5c9fcdd36491d",
            "sin1" to "3ee24bad509fcd16", "cos1" to "3fe8dd4ae4b99bee", "cos2" to "3fe8dd33dc0f5006", "sin2" to "3eeb70582089bdb2",
            "a" to "3de8aa3dd5874582", "aSplit" to "3de8aa3dd5874582", "sqrt" to "3eec1818f6dc49e9", "asin" to "3eec1818f6dfe5d5",
            "dist" to "3fbb271f550ef8c5", "GtTracks.haversineMiles" to "3fbb271f550ef8c5")
        val mac = linkedMapOf(
            "dLat" to b(dLat), "dLon" to b(dLon), "lat1r" to b(l1), "lat2r" to b(l2), "sin1" to b(s1), "cos1" to b(c1), "cos2" to b(c2),
            "sin2" to b(s2), "a" to b(a), "aSplit" to b(aSplit), "sqrt" to b(q), "asin" to b(asn), "dist" to b(d), "GtTracks.haversineMiles" to b(viaTracks))
        val sb = StringBuilder("# MATHPROBE mac -- one haversine (the 2-point 15e track), every step's raw bits vs the Droid's (Java)\n")
        var first: String? = null
        for ((k, v) in mac) {
            val same = v == droid[k]
            if (!same && first == null) first = k
            sb.append(k.padEnd(26)).append("mac=").append(v).append("  droid=").append(droid[k]).append(if (same) "  SAME" else "  DIFF").append('\n')
        }
        sb.append("# FIRST STEP THAT DIFFERS: ").append(first ?: "none -- every step matches").append('\n')
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmmss"; val stamp = f.stringFromDate(NSDate())   // v b: as the other iPad tests (11-10 07:24 timeIntervalSince1970 unresolved)
        FileSystem.SYSTEM.write("$outDir/GroupTrack_MATHPROBE_mac_$stamp.txt".toPath()) { writeUtf8(sb.toString()) }
        println(sb)
    }
}
