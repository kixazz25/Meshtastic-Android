package com.geeksville.mesh.grouptrack

import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtTime
import java.io.Writer

/**
 * STEP15A-2026-11-08 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): THE 15a GOLDEN FILE.
 * Written ON THE DROID because the reference is ANDROID's java.lang / java.text / java.time / org.json -- not a desktop JDK (a JDK 19+
 * Double.toString prints different digits from Android's, and desktop org.json is a different library).
 *
 * One line per case:   kind|hex(input)|hex(android answer)|hex(shared answer on the Droid)|SAME or DIFF
 * (hex = the UTF-8 bytes in lower-case hex, so any text -- newlines, controls -- stays on one line). Header lines start with '#'.
 * The iPad simulator test (cert/GtFoundation15aMacTest) reads the same file and compares ITS shared answer with column 3.
 *
 * Kinds:  jd  (input = the double's 16 hex bits; answer = Double.toString)   round (Math.round -> decimal)
 *         rad / deg (Math.toRadians / toDegrees -> 16 hex bits vs GtJava)    radread (Math.toRadians vs GtRead.toRadians, step 7)
 *         sdf (parseGpxTrackPointsFull's four patterns -> ms or "null")      sdf1..sdf4 (each pattern alone)
 *         sdffmt (input ms -> SimpleDateFormat UTC "yyyy-MM-dd'T'HH:mm:ss'Z'")  iso (Instant.parse, else OffsetDateTime.parse -> ms/"null")
 *         inst (input "sec,nanos" -> Instant.toString)
 *         json (input = a document; answer = JSONObject/JSONArray .toString())   jsonp (.toString(2))
 *         jsonopt (input = a one-element array; answer = optString(0) + "|" + Double.toString(optDouble(0)) + "|" + optInt(0) + "|" + optLong(0))
 * A case the shared parser cannot read (kotlinx is strict -- GtOrgJson's note) answers "THROWS" and counts as a DIFF; NOTES_15a lists
 * the cases kept in the file on purpose to show it.
 *
 * Call: GtCertRun, request mode "golden15a" (STEP15A edit), writes into the run's own file (Download/GroupTrack_CERT_droid_<time>.txt);
 * gt_cert_v12.sh golden15a copies it to $CERT/gt15a_golden_2026-11-08.txt, where GtFoundation15aMacTest reads it.
 */
object GtGolden15a {

    class Summary(val lines: Int, val diffs: Int, val byKind: Map<String, Pair<Int, Int>>)

    private fun hex(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0xF]) }
        return sb.toString()
    }
    private fun bits(d: Double) = java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(d)).padStart(16, '0')

    fun write(out: Writer): Summary {
        val counts = LinkedHashMap<String, IntArray>()
        var lines = 0; var diffs = 0
        var expected = 0
        /** STEP15A-B-2026-11-08: [accepted] = a difference known and accepted in advance (the lenient JSON org.json reads and kotlinx
         *  refuses -- NOTES_15a_b.md, as rides' PORT_NOTES a3): written EXPECTED, not DIFF, and not counted as a failure. */
        fun line(kind: String, input: String, android: String, shared: String, accepted: Boolean = false) {
            val same = android == shared
            out.write(kind); out.write("|"); out.write(hex(input)); out.write("|"); out.write(hex(android)); out.write("|")
            out.write(hex(shared)); out.write(if (same) "|SAME\n" else if (accepted) "|EXPECTED\n" else "|DIFF\n")
            val c = counts.getOrPut(kind) { IntArray(2) }; c[0]++
            if (!same) { if (accepted) expected++ else { c[1]++; diffs++ } }
            lines++
        }
        fun guarded(f: () -> String): String = try { f() } catch (e: Throwable) { "THROWS" }
        out.write("# STEP15A-2026-11-08 golden: android=" + android.os.Build.VERSION.SDK_INT + " " + android.os.Build.MODEL + "\n")

        // ---- jd ---------------------------------------------------------------------------------------------------------
        val rnd = java.util.Random(1511L)
        val dbl = ArrayList<Double>()
        dbl.addAll(listOf(0.0, -0.0, 1.0, -1.0, 0.001, 0.0009999999999999998, 1.0E-4, 9999999.0, 9999999.999999998, 1.0E7, 1.0E-3,
            100.0, 1234567.0, 0.1, 0.2, 0.3, 1.0 / 3, 2.0 / 3, 2.0E-3, 2.0E23, 1.0E23, 8.41E21, 5.0E-324, Double.MIN_VALUE, Double.MAX_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 4.35, 0.15, 2.5, 1.0E22, 9.007199254740993E15, 1.7976931348623157E308,
            -111.12345, 37.1, -113.5, 40.123456789, -111.987654321))
        var p = 1.0E-12
        while (p < 1.0E26) { dbl.add(p); dbl.add(Math.nextUp(p)); dbl.add(Math.nextDown(p)); p *= 10.0 }
        repeat(60_000) {   // coordinates as GPX / GeoJSON text carries them, parsed -- what every WKT is built from
            val places = 1 + rnd.nextInt(9)
            val lon = (rnd.nextDouble() * 360.0 - 180.0); val lat = (rnd.nextDouble() * 180.0 - 90.0)
            dbl.add(String.format(java.util.Locale.US, "%." + places + "f", lon).toDouble())
            dbl.add(String.format(java.util.Locale.US, "%." + places + "f", lat).toDouble())
            if (it % 10 == 0) { dbl.add(lon - 0.001); dbl.add(lat + 0.001); dbl.add(lon * 3.280839895) }
        }
        repeat(50_000) { val d = java.lang.Double.longBitsToDouble(rnd.nextLong()); if (!d.isNaN()) dbl.add(d) }
        for (d in dbl) line("jd", bits(d), d.toString(), GtJava.jd(d))

        // ---- round, rad, deg --------------------------------------------------------------------------------------------
        val rd = ArrayList<Double>(listOf(0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.49999999999999994, -0.49999999999999994, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0E19, -1.0E19, 9.223372036854775807E18, -9.223372036854775808E18,
            4.503599627370497E15, -4.503599627370497E15, 0.0, -0.0, 1.0E-300, -1.0E-300, 1.4999999999999998, 2147483648.5, -2147483649.5))
        repeat(20_000) { rd.add((rnd.nextInt(200_001) - 100_000) + 0.5); rd.add((rnd.nextDouble() - 0.5) * Math.pow(10.0, rnd.nextInt(25).toDouble())) }
        for (d in rd) {
            line("round", bits(d), Math.round(d).toString(), GtJava.round(d).toString())
            line("roundi", bits(d), Math.round(d).toInt().toString(), GtJava.roundToIntJava(d).toString())
        }
        val ang = ArrayList<Double>(listOf(0.0, 90.0, 180.0, 360.0, -180.0, 1.0, 45.0, 37.1, -113.5, 1.0E-10, 1.0E300))
        repeat(30_000) { ang.add(rnd.nextDouble() * 720.0 - 360.0); ang.add((rnd.nextDouble() * 360.0 - 180.0).let { a -> String.format(java.util.Locale.US, "%.6f", a).toDouble() }) }
        for (a in ang) {
            line("rad", bits(a), bits(Math.toRadians(a)), bits(GtJava.toRadians(a)))
            line("radread", bits(a), bits(Math.toRadians(a)), bits(GtRead.toRadians(a)))
            val r = a / 57.0
            line("deg", bits(r), bits(Math.toDegrees(r)), bits(GtJava.toDegrees(r)))
        }

        // ---- SimpleDateFormat (parseGpxTrackPointsFull) ------------------------------------------------------------------
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val pats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        fun sdfJava(pattern: String, s: String): String = try {
            java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = utc; isLenient = false }.parse(s)?.time?.toString() ?: "null"
        } catch (e: Exception) { "null" }
        val times = timeCases(rnd)
        for (s in times) {
            var any = "null"
            for (pt in pats) { val r = sdfJava(pt, s.trim()); if (r != "null") { any = r; break } }
            line("sdf", s, any, guarded { GtTime.gpxTime(s)?.toString() ?: "null" })
            pats.forEachIndexed { i, pt -> line("sdf" + (i + 1), s, sdfJava(pt, s), guarded { GtTime.sdfParse(s, GtTime.GPX_PATTERNS[i])?.toString() ?: "null" }) }
            val isoJava = try { java.time.Instant.parse(s.trim()).toEpochMilli().toString() } catch (e: Exception) {
                try { java.time.OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli().toString() } catch (e2: Exception) { "null" } }
            line("iso", s, isoJava, guarded { GtTime.isoToEpochMs(s.trim())?.toString() ?: "null" })
        }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = utc }
        val msList = ArrayList<Long>(listOf(0L, -1L, 1L, 999L, -999L, 1704450030000L, 253402300799000L, 253402300800000L,
            -12219292800000L, -12219292800001L, -62135596800000L, -62135596800001L, -100000000000000L, 951782400000L, 4102444800000L))
        repeat(20_000) { msList.add(rnd.nextLong() % 400_000_000_000_000L) }
        for (ms in msList) line("sdffmt", ms.toString(), fmt.format(java.util.Date(ms)), guarded { GtTime.formatUtcSeconds(ms) })

        // ---- Instant.toString ------------------------------------------------------------------------------------------
        val inst = ArrayList<Pair<Long, Int>>(listOf(0L to 0, 0L to 1, 0L to 1000, 0L to 1_000_000, 0L to 123_000_000, 60L to 0,
            1700000000L to 123_000_000, 1700000000L to 123_400_000, 1700000000L to 123_456_789, 253402300799L to 999_999_999, -1L to 0, -86400L to 500_000_000))
        repeat(20_000) { inst.add((rnd.nextLong() % 250_000_000_000L).let { s -> if (s < 0) -s else s } to (if (rnd.nextBoolean()) rnd.nextInt(1000) * 1_000_000 else rnd.nextInt(1_000_000_000))) }
        for ((s, n) in inst) line("inst", "$s,$n", java.time.Instant.ofEpochSecond(s, n.toLong()).toString(), guarded { GtTime.instantText(s, n) })

        // ---- org.json ----------------------------------------------------------------------------------------------------
        for (doc in jsonDocs()) {
            val t = doc.trim()
            val javaCompact = try { if (t.startsWith("[")) org.json.JSONArray(t).toString() else org.json.JSONObject(t).toString() } catch (e: Exception) { "THROWS" }
            val javaPretty = try { if (t.startsWith("[")) org.json.JSONArray(t).toString(2) else org.json.JSONObject(t).toString(2) } catch (e: Exception) { "THROWS" }
            val lenient = doc in EXPECTED_LENIENT   // STEP15A-B: org.json reads these, kotlinx refuses -- accepted in advance
            line("json", doc, javaCompact, guarded { GtOrgJson.compact(GtOrgJson.parse(t)) }, lenient)
            line("jsonp", doc, javaPretty, guarded { GtOrgJson.pretty(GtOrgJson.parse(t), 2) }, lenient)
        }
        for (lit in jsonLiterals()) {
            val doc = "[$lit]"
            val javaOpt = try { val a = org.json.JSONArray(doc); a.optString(0) + "|" + a.optDouble(0).toString() + "|" + a.optInt(0) + "|" + a.optLong(0) } catch (e: Exception) { "THROWS" }
            line("jsonopt", doc, javaOpt, guarded {
                val a = GtOrgJson.parse(doc) as kotlinx.serialization.json.JsonArray
                val o = kotlinx.serialization.json.JsonObject(mapOf("k" to a[0]))
                GtOrgJson.optString(a, 0) + "|" + GtJava.jd(GtOrgJson.optDouble(a, 0)) + "|" + GtOrgJson.optInt(o, "k") + "|" + GtOrgJson.optLong(o, "k")
            })
        }
        val byKind = counts.mapValues { (_, v) -> v[0] to v[1] }
        out.write("# STEP15A golden done: $lines lines, $diffs DIFF on the Droid ($expected EXPECTED); " + byKind.entries.joinToString(" ") { "${it.key}=${it.value.first}/${it.value.second}" } + "\n")
        out.flush()
        return Summary(lines, diffs, byKind)
    }

    /** The time texts: the quirks, then a random batch built from valid and broken parts. */
    private fun timeCases(rnd: java.util.Random): List<String> {
        val l = ArrayList<String>(listOf(
            "2024-01-05T10:20:30Z", "2024-01-05T10:20:30.123Z", "2024-01-05T10:20:30.5Z", "2024-01-05T10:20:30.05Z", "2024-01-05T10:20:30.123456Z",
            "2024-01-05T10:20:30.123456789Z", "2024-01-05T10:20:30.Z", "2024-01-05T10:20:30Zjunk", "2024-01-05T10:20:30", "2024-1-5T3:4:5Z",
            "2024-01-05T10:20:60Z", "2024-01-05T23:59:60Z", "2024-01-05T24:00:00Z", "2024-02-29T23:59:59Z", "2023-02-29T00:00:00Z",
            "2024-02-30T00:00:00Z", "2024-13-01T00:00:00Z", "2024-00-10T00:00:00Z", "0000-01-01T00:00:00Z", "0001-01-01T00:00:00Z",
            "1582-10-04T00:00:00Z", "1582-10-05T00:00:00Z", "1582-10-14T00:00:00Z", "1582-10-15T00:00:00Z", "1500-02-29T00:00:00Z",
            "2024-01-05T10:20:30+01:00", "2024-01-05T10:20:30-05:30", "2024-01-05T10:20:30.250-05:30", "2024-01-05T10:20:30+01",
            "2024-01-05T10:20:30+0100", "2024-01-05T10:20:30+14:00", "2024-01-05T10:20:30+15:00", "2024-01-05T10:20:30-13:00",
            "2024-01-05T10:20:30-14:00", "2024-01-05T10:20:30+18:00", "2024-01-05T10:20:30+19:00", "2024-01-05T10:20:30+01:00:30",
            "2024-01-05T10:20:30z", "2024-01-05t10:20:30z", "2024-01-05T10:20Z", "2024-01-05T10:20+01:00", "2024-01-05T24:00:00+01:00",
            "2024-01-05T23:59:60+01:00", "2024-01-05T10:20:5+01:00", "2024-01-05T10:20:123+01:00", "2024-01-05T10:20:30.5+01:00",
            "2024-01-05T10:20:30.1234+01:00", " 2024-01-05T10:20:30Z", "2024-01-05T 10:20:30Z", "2024-01-05T10:20:30 Z",
            "2024-01-05T10:20:30 +01:00", "2024--1-05T10:20:30Z", "2024-01-05T-0:20:30Z", "+2024-01-05T10:20:30Z", "+12024-01-05T10:20:30Z",
            "12024-01-05T10:20:30Z", "-0001-01-01T00:00:00Z", "4294969320-01-05T10:20:30Z", "99999999999999999999-01-05T10:20:30Z",
            "2024-01-05 10:20:30Z", "", "Z", "garbage", "2024-01-05T10:20:30.999Z", "2024-01-05T10:20:30.1000Z", "1970-01-01T00:00:00Z",
            "1969-12-31T23:59:59.999Z", "2038-01-19T03:14:08Z", "9999-12-31T23:59:59Z", "2024-01-05T10:20:30.000000001Z",
            "2024-01-05T10:20:30.0000000001Z", "2024-01-05T10:20:30,5Z", "2024-01-05T10:20:30.5z", "2024-01-05T10:20:30.5 Z"))
        val parts = listOf("2024", "1999", "24", "2024-", "-01", "-1", "-12", "-31", "-00", "T", "t", " ", "10", ":20", ":5", ":60", ":00",
            ".5", ".123", ".123456", ".", "Z", "z", "+01:00", "-05:30", "+1:00", "+01", "x", "")
        repeat(3_000) { val sb = StringBuilder(); repeat(3 + rnd.nextInt(8)) { sb.append(parts[rnd.nextInt(parts.size)]) }; l.add(sb.toString()) }
        repeat(3_000) {   // valid times with random fields and endings
            val y = 1900 + rnd.nextInt(250); val mo = 1 + rnd.nextInt(12); val d = 1 + rnd.nextInt(31); val h = rnd.nextInt(25)
            val mi = rnd.nextInt(61); val s = rnd.nextInt(61)
            val frac = when (rnd.nextInt(4)) { 0 -> ""; 1 -> "." + rnd.nextInt(1000).toString().padStart(3, '0'); 2 -> "." + rnd.nextInt(10); else -> "." + rnd.nextInt(1_000_000) }
            val end = listOf("Z", "+01:00", "-07:00", "", "+05:45", "+14:00")[rnd.nextInt(6)]
            l.add(String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d", y, mo, d, h, mi, s) + frac + end)
        }
        return l
    }

    /** STEP15A-B-2026-11-08: the lenient inputs of [jsonDocs] that Android's org.json reads and kotlinx refuses (18:08 run). */
    private val EXPECTED_LENIENT = setOf("""{a:1}""", """{'a':'b'}""", """{"a":1}trailing""")

    private fun jsonDocs(): List<String> = listOf(
        """{"a":1.50,"b":1e3,"c":"x/y","d":null,"e":[1,2.0,-0.0,-0],"f":true,"g":12345678901,"h":false}""",
        """{"a":1,"b":[],"c":{},"d":[1,{"x":2}],"e":[[]],"f":[{}]}""",
        """{"s":"quote \" backslash \\ slash / tab \t nl \n cr \r ff \f bs \b u0001 \u0001 u001f \u001f del \u007f e é emoji 😀 ls  "}""",
        """{"n1":1.0E20,"n2":9.0E18,"n3":9.223372036854775807E18,"n4":1e-7,"n5":0.001,"n6":1234567.0,"n7":12345678.0,"n8":-1.5e300,"n9":5e-324}""",
        """{"i1":2147483647,"i2":2147483648,"i3":-2147483648,"i4":-2147483649,"i5":9223372036854775807,"i6":9223372036854775808,"i7":-9223372036854775808}""",
        """{"z1":0,"z2":-0,"z3":0.0,"z4":-0.0,"z5":0e0,"z6":1E+2,"z7":1e-0,"z8":100,"z9":100.0}""",
        """{"dup":1,"x":2,"dup":3}""",
        """[1,"two",3.5,null,true,{"k":[1,2]}]""",
        """[]""", """{}""", """[[[]]]""",
        """{"recipe":{"anchorLat":40.123456789,"anchorLon":-111.5,"anchorName":"Trailhead \"A\"/B"},"narrative":{"headline":"Mile 8, Spring Peak"},"features":[{"at_mi":8.0,"name":"x","lat":40.1,"lon":-111.2}],"cautions":[{"gap_ft":12.5}]}""",
        """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-111.123456,40.1],[-111,40]]},"properties":{"OBJECTID":12,"NAME":"A & B","Len":1.50,"Nul":null}}]}""",
        // kept on purpose: Android's lenient tokenizer reads these, kotlinx refuses -> expected DIFF (NOTES_15a)
        """{"a":010}""", """{"a":0x1F}""", """{a:1}""", """{'a':'b'}""", """{"a":1}trailing""", """{"a":TRUE}""", """{"a":1,}"""
    )

    private fun jsonLiterals(): List<String> = listOf("1", "-1", "0", "-0", "1.50", "1e3", "1E3", "1.0", "-0.0", "2147483648", "9223372036854775807",
        "9223372036854775808", "1.0E20", "0.1", "1e-7", "12345678.9", "\"12.5\"", "\"abc\"", "\"\"", "null", "true", "false", "\"true\"",
        "\" 12 \"", "\"1e3\"", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"12.5d\"", "\"0x10\"", "[1]", "{\"a\":1}", "\"2147483648.7\"",
        "3.99", "-3.99", "1e19", "-1e19", "\"010\"", "010")
}
