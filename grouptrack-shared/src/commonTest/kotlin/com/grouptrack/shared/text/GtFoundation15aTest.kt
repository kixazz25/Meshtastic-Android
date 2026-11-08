package com.grouptrack.shared.text

import com.grouptrack.shared.json.GtOrgJson
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * STEP15A-2026-11-08: fixed cases for the 15a foundations, with answers that are certain from the Java/Android documentation and the
 * 2.7e behaviour already recorded in the INVENTORY. The whole-range proof is the golden file (GtFoundation15aMacTest); these are the
 * quick check that runs on every build of the shared tests.
 */
class GtFoundation15aTest {

    @Test fun javaDoubleText() {
        val cases = listOf(1.0 to "1.0", 0.001 to "0.001", 1.0E-4 to "1.0E-4", 1.0E7 to "1.0E7", 1234567.0 to "1234567.0",
            9999999.0 to "9999999.0", -111.12345 to "-111.12345", 0.0 to "0.0", -0.0 to "-0.0", 100.0 to "100.0", 1.5E300 to "1.5E300",
            -2.5E-5 to "-2.5E-5", 37.1 to "37.1", Double.NaN to "NaN", Double.POSITIVE_INFINITY to "Infinity", Double.NEGATIVE_INFINITY to "-Infinity")
        for ((x, s) in cases) assertEquals(s, GtJava.jd(x), "jd($x)")
    }

    @Test fun shortestDigitsAsAndroid14() {   // STEP15A-B-2026-11-08: the iPad's 28 differences of the 18:08 run, a sample
        val cases = listOf("435e6a44a6db84ba" to "3.424436956417713E16", "c31fdbd200aae6d5" to "-2.2418548197114132E15",
            "c39ea3313f5af229" to "-5.5191559763004883E17", "c2b04a678a9ce6d0" to "-1.7911750761702812E13", "42d95b89369b9fe8" to "1.1152373910284762E14")
        for ((b, s) in cases) assertEquals(s, GtJava.shortestJavaText(Double.fromBits(b.toULong(16).toLong())), b)
        val plain = listOf(1.0 to "1.0", 0.001 to "0.001", 1.0E-4 to "1.0E-4", 1.0E7 to "1.0E7", 0.1 to "0.1", 0.3 to "0.3", 2.0E23 to "2.0E23",
            1.0E23 to "1.0E23", 4.9E-324 to "4.9E-324", 1.7976931348623157E308 to "1.7976931348623157E308", -111.12345 to "-111.12345",
            0.30000000000000004 to "0.30000000000000004", 2.2250738585072014E-308 to "2.2250738585072014E-308")
        for ((x, s) in plain) assertEquals(s, GtJava.shortestJavaText(x), "shortest($x)")
    }

    @Test fun javaLayoutOfAnyDigits() {   // the iOS path, fed by hand
        assertEquals("1.0E-4", GtJava.layoutOf("1.0E-4", 1.0E-4))
        assertEquals("1.0E-4", GtJava.layoutOf("0.0001", 1.0E-4))
        assertEquals("1.0E7", GtJava.layoutOf("10000000", 1.0E7))
        assertEquals("1.0E7", GtJava.layoutOf("1e+07", 1.0E7))
        assertEquals("123.45", GtJava.layoutOf("1.2345e2", 123.45))
        assertEquals("-0.001", GtJava.layoutOf("-1.0E-3", -0.001))
        assertEquals("100.0", GtJava.layoutOf("100", 100.0))
    }

    @Test fun javaRound() {
        assertEquals(1L, GtJava.round(0.5)); assertEquals(0L, GtJava.round(-0.5)); assertEquals(3L, GtJava.round(2.5))
        assertEquals(-2L, GtJava.round(-2.5)); assertEquals(0L, GtJava.round(Double.NaN)); assertEquals(0L, GtJava.round(0.49999999999999994))
        assertEquals(Long.MAX_VALUE, GtJava.round(1.0E19)); assertEquals(Long.MIN_VALUE, GtJava.round(-1.0E19))
        assertEquals(Long.MAX_VALUE, GtJava.round(Double.POSITIVE_INFINITY)); assertEquals(1L, GtJava.round(1.4999999999999998))
        assertEquals(0, GtJava.roundToIntJava(Double.NaN)); assertEquals(-2147483648, GtJava.roundToIntJava(2147483648.0))
    }

    @Test fun simpleDateFormatQuirks() {
        val base = 1704450030000L   // 2024-01-05T10:20:30Z
        assertEquals(base, GtTime.gpxTime("2024-01-05T10:20:30Z"))
        assertEquals(base + 123, GtTime.gpxTime("2024-01-05T10:20:30.123Z"))
        // STEP15A-B-2026-11-08: Android 14 (golden run 18:08) -- the fraction is a FRACTION, cut to the millisecond
        assertEquals(base + 500, GtTime.gpxTime("2024-01-05T10:20:30.5Z"))
        assertEquals(base + 50, GtTime.gpxTime("2024-01-05T10:20:30.05Z"))
        assertEquals(base + 123, GtTime.gpxTime("2024-01-05T10:20:30.123456Z"))
        assertEquals(base + 100, GtTime.gpxTime("2024-01-05T10:20:30.1000Z"))
        assertEquals(base, GtTime.gpxTime("2024-01-05T10:20:30.000000001Z"))
        assertEquals(base + 500, GtTime.sdfParse("2024-01-05T10:20:30.5 Z", GtTime.SDF_MS_X))
        assertEquals(1704446430500L, GtTime.sdfParse("2024-01-05T10:20:30.5+01:00", GtTime.SDF_MS_X))
        assertEquals(base, GtTime.gpxTime("2024-01-05T10:20:30Zjunk"))           // prefix parse
        assertEquals(1704423845000L, GtTime.gpxTime("2024-1-5T3:4:5Z"))           // one-digit fields
        assertNull(GtTime.gpxTime("2024-01-05T10:20:60Z"))                         // non-lenient
        assertNull(GtTime.gpxTime("2024-02-30T00:00:00Z"))
        assertEquals(1709251199000L, GtTime.gpxTime("2024-02-29T23:59:59Z"))
        assertEquals(1704446430000L, GtTime.gpxTime("2024-01-05T10:20:30+01:00"))
        assertEquals(1704469830250L, GtTime.gpxTime("2024-01-05T10:20:30.250-05:30"))
        assertNull(GtTime.gpxTime("2024-01-05T10:20:30+01"))                       // XXX needs HH:MM
        assertNull(GtTime.gpxTime("2024-01-05 10:20:30Z"))
        assertNull(GtTime.gpxTime("2024-01-05T10:20:30"))
    }

    @Test fun simpleDateFormatUtcText() {
        assertEquals("2024-01-05T10:20:30Z", GtTime.formatUtcSeconds(1704450030999L))
        assertEquals("1970-01-01T00:00:00Z", GtTime.formatUtcSeconds(0L))
        assertEquals("1969-12-31T23:59:59Z", GtTime.formatUtcSeconds(-1L))
    }

    @Test fun javaTimeIso() {
        val base = 1704450030000L
        assertEquals(base, GtTime.isoToEpochMs("2024-01-05T10:20:30Z"))
        assertEquals(base + 123, GtTime.isoToEpochMs("2024-01-05T10:20:30.123456789Z"))
        assertEquals(base, GtTime.isoToEpochMs("2024-01-05t10:20:30z"))           // case-insensitive
        assertEquals(1704450000000L, GtTime.isoToEpochMs("2024-01-05T10:20Z"))    // OffsetDateTime: seconds optional
        assertEquals(1704446430000L, GtTime.isoToEpochMs("2024-01-05T10:20:30+01:00"))
        assertNull(GtTime.isoToEpochMs("2024-01-05T10:20:30"))
        assertNull(GtTime.isoToEpochMs("2024-01-05T10:20:30Zjunk"))
        assertNull(GtTime.isoToEpochMs("2024-1-05T10:20:30Z"))
        // STEP15A-B-2026-11-08: Android 14 java.time (JDK 12+)
        assertEquals(1704446430000L, GtTime.isoToEpochMs("2024-01-05T10:20:30+01"))
        assertEquals(1704495600000L, GtTime.isoToEpochMs("2024-01-05T24:00:00+01:00"))
        assertEquals(1704495599000L, GtTime.isoToEpochMs("2024-01-05T23:59:60+01:00"))
    }

    @Test fun instantText() {
        assertEquals("1970-01-01T00:00:00Z", GtTime.instantText(0, 0))
        assertEquals("1970-01-01T00:01:00Z", GtTime.instantText(60, 0))
        assertEquals("2023-11-14T22:13:20.123Z", GtTime.instantText(1700000000, 123_000_000))
        assertEquals("1970-01-01T00:00:00.000001Z", GtTime.instantText(0, 1000))
        assertEquals("1970-01-01T00:00:00.000000001Z", GtTime.instantText(0, 1))
        assertEquals("2023-11-14T22:13:20.123Z", GtTime.instantTextMs(1700000000123L))
    }

    @Test fun localPatterns() {
        val t = GtLocalTime(2026, 11, 8, 7, 5, 9)
        assertEquals("2026-11-08T07:05:09", GtTime.local(t, "yyyy-MM-dd'T'HH:mm:ss"))
        assertEquals("20261108_070509", GtTime.local(t, "yyyyMMdd_HHmmss"))
        assertEquals("2026-11-08T070509", GtTime.local(t, "yyyy-MM-dd'T'HHmmss"))
        assertEquals("2026-11-08", GtTime.local(t, "yyyy-MM-dd"))
    }

    @Test fun orgJsonText() {
        val doc = """{"a":1.50,"b":1e3,"c":"x/y","d":null,"e":[1,2.0,-0.0],"f":true,"g":12345678901}"""
        assertEquals("""{"a":1.5,"b":1000,"c":"x\/y","d":null,"e":[1,2,-0],"f":true,"g":12345678901}""", GtOrgJson.compact(GtOrgJson.parse(doc)))
        val o = GtOrgJson.parse(doc) as JsonObject
        assertEquals("1.5", GtOrgJson.optString(o, "a"))
        assertEquals("1000.0", GtOrgJson.optString(o, "b"))      // String.valueOf(Double)
        assertEquals("""{"a":true}""", GtOrgJson.compact(GtOrgJson.parse("""{"a":TRUE}""")))   // STEP15A-B: as org.json reads it
        assertEquals("null", GtOrgJson.optString(o, "d"))
        assertEquals("x/y", GtOrgJson.optString(o, "c"))
        assertEquals("", GtOrgJson.optString(o, "missing"))
        assertEquals("[1,2,-0]", GtOrgJson.optString(o, "e"))
        assertEquals(1000.0, GtOrgJson.optDouble(o, "b"))
        assertEquals(1, GtOrgJson.optInt(o, "a"))
        assertEquals(12345678901L, GtOrgJson.optLong(o, "g"))
        assertEquals(8, GtOrgJson.orgKind("010").let { (it as GtOrgJson.OrgNum.IntNum).v })   // a leading 0 = octal, as Android's JSONTokener
    }

    @Test fun orgJsonPretty() {
        val doc = """{"a":1,"b":[],"c":{},"d":[1,{"x":2}]}"""
        val expect = "{\n  \"a\": 1,\n  \"b\": [],\n  \"c\": {},\n  \"d\": [\n    1,\n    {\n      \"x\": 2\n    }\n  ]\n}"
        assertEquals(expect, GtOrgJson.pretty(GtOrgJson.parse(doc), 2))
    }

    @Test fun orgJsonPut() {
        val o = JsonObject(linkedMapOf("n" to GtOrgJson.num(40.5), "w" to GtOrgJson.num(100.0), "l" to GtOrgJson.num(7L), "z" to GtOrgJson.NULL))
        assertEquals("""{"n":40.5,"w":100,"l":7,"z":null}""", GtOrgJson.compact(o))
        assertEquals("100.0", GtOrgJson.optString(o, "w"))
        assertFailsWith<IllegalArgumentException> { GtOrgJson.num(Double.NaN) }
    }
}
