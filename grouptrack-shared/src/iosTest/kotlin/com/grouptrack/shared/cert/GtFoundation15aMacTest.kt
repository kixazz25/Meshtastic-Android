package com.grouptrack.shared.cert

import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * STEP15A-2026-11-08 (2.8 step 15a): the 15a foundations on the iPad simulator against the GOLDEN FILE THE DROID WROTE (GtGolden15a,
 * gt_cert_v12.sh mode golden15a), found as GtFmtMacTest finds its golden: $dir/gt15a_golden_2026-11-08.txt (gt_cert_v12 copies the
 * Droid's run there). Rule per line: the iPad's shared answer == the Droid's shared answer (column 4), always; and == Android's own
 * answer (column 3) wherever the Droid's line is SAME (an EXPECTED line -- STEP15A-B, the lenient JSON -- is held only to the first rule). Prints "GT15A mac checked N ..." and writes the summary + first differences to
 * GroupTrack_GT15A_mac_<time>.txt, then fails if anything differs. No golden file -> skipped (as GtFmtMacTest). TEST-ONLY.
 */
class GtFoundation15aMacTest {
    private val dir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    private fun unhex(h: String): String = ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }.decodeToString()
    private fun dbl(bits: String): Double = Double.fromBits(bits.toULong(16).toLong())
    private fun bits(d: Double): String = d.toRawBits().toULong().toString(16).padStart(16, '0')

    private fun mac(kind: String, input: String): String = try {
        when (kind) {
            "jd" -> GtJava.jd(dbl(input))
            "round" -> GtJava.round(dbl(input)).toString()
            "roundi" -> GtJava.roundToIntJava(dbl(input)).toString()
            "rad" -> bits(GtJava.toRadians(dbl(input)))
            "radread" -> bits(GtRead.toRadians(dbl(input)))
            "deg" -> bits(GtJava.toDegrees(dbl(input)))
            "sdf" -> GtTime.gpxTime(input)?.toString() ?: "null"
            "sdf1", "sdf2", "sdf3", "sdf4" -> GtTime.sdfParse(input, GtTime.GPX_PATTERNS[kind.last() - '1'])?.toString() ?: "null"
            "iso" -> GtTime.isoToEpochMs(input.trim())?.toString() ?: "null"
            "sdffmt" -> GtTime.formatUtcSeconds(input.toLong())
            "inst" -> { val (s, n) = input.split(","); GtTime.instantText(s.toLong(), n.toInt()) }
            "json" -> GtOrgJson.compact(GtOrgJson.parse(input.trim()))
            "jsonp" -> GtOrgJson.pretty(GtOrgJson.parse(input.trim()), 2)
            "jsonopt" -> {
                val a = GtOrgJson.parse(input) as JsonArray
                val o = JsonObject(mapOf("k" to a[0]))
                GtOrgJson.optString(a, 0) + "|" + GtJava.jd(GtOrgJson.optDouble(a, 0)) + "|" + GtOrgJson.optInt(o, "k") + "|" + GtOrgJson.optLong(o, "k")
            }
            else -> "UNKNOWN-KIND"
        }
    } catch (e: Throwable) { "THROWS" }

    @Test
    fun foundations() {
        val fs = FileSystem.SYSTEM
        val gold = "$dir/gt15a_golden_2026-11-08.txt".toPath()
        if (!fs.exists(gold)) { println("GT15A mac: no golden file at $gold -- skipped"); return }
        var lines = 0; var vsDroid = 0; var vsAndroid = 0; var droidDiff = 0; var accepted = 0
        val byKind = LinkedHashMap<String, IntArray>()
        val diffs = ArrayList<String>()
        val heads = ArrayList<String>()
        fs.read(gold) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("#")) { heads.add(l); continue }
                val f = l.split("|")
                if (f.size < 5) continue
                val kind = f[0]; val input = unhex(f[1]); val android = unhex(f[2]); val droidShared = unhex(f[3]); val droidSame = f[4] == "SAME"
                val m = mac(kind, input)
                val c = byKind.getOrPut(kind) { IntArray(3) }
                c[0]++; lines++
                if (f[4] == "EXPECTED") accepted++ else if (!droidSame) droidDiff++   // STEP15A-B: EXPECTED = accepted in advance
                if (m != droidShared) { vsDroid++; c[1]++; if (diffs.size < 60) diffs.add("DIFF|$kind|in=${input.take(100)}|droidShared=${droidShared.take(100)}|mac=${m.take(100)}") }
                if (droidSame && m != android) { vsAndroid++; c[2]++ }
            }
        }
        val summary = "GT15A mac checked $lines; vs droid-shared bad $vsDroid; vs android (droid SAME lines) bad $vsAndroid; droid's own DIFF lines $droidDiff, EXPECTED $accepted"
        val kinds = byKind.map { (k, v) -> "  $k: ${v[0]} lines, vs droid-shared ${v[1]}, vs android ${v[2]}" }
        println(summary); kinds.forEach { println(it) }
        val stamp = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd_HHmm" }.stringFromDate(NSDate())
        fs.write("$dir/GroupTrack_GT15A_mac_$stamp.txt".toPath()) { writeUtf8((heads + listOf(summary) + kinds + diffs).joinToString("\n") + "\n") }
        assertEquals(0, vsDroid, "GT15A: the iPad's shared answers differ from the Droid's")
        assertEquals(0, vsAndroid, "GT15A: the iPad's shared answers differ from Android's")
    }
}
