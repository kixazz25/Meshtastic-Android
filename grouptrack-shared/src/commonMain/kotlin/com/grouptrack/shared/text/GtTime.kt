package com.grouptrack.shared.text

/**
 * STEP15A-2026-11-08 (2.8 step 15a): the 2.7e date/time text, reproduced for the shared code -- QUIRKS INCLUDED, because the values
 * land in database rows (track_properties.recorded_at / duration_minutes / max+avg speed) and in file times.
 *
 *  1. [sdfParse] with [SDF_MS_Z] / [SDF_Z] / [SDF_X] / [SDF_MS_X] = java.text.SimpleDateFormat(pattern, Locale.US), UTC, isLenient=false,
 *     .parse(s) -- the four patterns of SpatialDbManager.parseGpxTrackPointsFull (1500-1505), tried in that order by [gpxTime]:
 *       "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX".
 *     STEP15A-B-2026-11-08: the rules below are ANDROID 14's (Droid 1, API 34, golden run 18:08), derived from every DIFF line of that run
 *     and re-checked off-device on all 6,074 time texts x 6 parsers (sdf, sdf1..4, iso): 0 differences (step15a/code_b/evidence). The
 *     first version modelled the desktop JDK's SimpleDateFormat; Android differs in the FRACTION (and so did 2,437 lines).
 *       - PREFIX parse: text after the pattern is ignored ("...30Zjunk" parses).
 *       - a number field takes as many digits as are there ("2024-1-5T3:4:5Z" parses), after skipping spaces/tabs, and accepts a
 *         leading '-' (the value is then out of range -> invalid, except "-0" = 0) -- UNLESS the next pattern element is another field
 *         (ss before XXX): then it reads at most its letter count of characters ("obeyCount").
 *       - SSS is a FRACTION OF A SECOND, read greedily (never obeyCount): ".5Z" = 500 ms, ".05Z" = 50 ms, ".1000Z" = 100 ms,
 *         ".123456Z" / ".123456789Z" = 123 ms (accepted, cut to the millisecond), ".000000001Z" = 0 ms. So "SSSXXX" also reads
 *         ".5Z", ".5 Z" and ".589724+05:45".
 *       - Number.intValue(): a Long's low 32 bits (DecimalFormat returns a Long), a value past Long saturates.
 *       - non-lenient GregorianCalendar: YEAR 1..292278994, month 1..12, day valid for the month, hour 0..23, minute/second 0..59,
 *         ms 0..999, zone offset -13h..+14h; dates before 1582-10-15 are JULIAN, 1582-10-05..14 do not exist.
 *       - 'Z' (quoted) is the letter Z only; XXX = "Z" or +/-HH:MM (two digits each, hours <= 23, minutes <= 59).
 *  2. [formatUtcSeconds] = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US), UTC, .format(Date(ms)) (recorded_at, SDM 1601).
 *  3. [isoToEpochMs] = ConvoyTrackOps.extractEarliestTime's parse: java.time.Instant.parse(s).toEpochMilli(), else
 *     OffsetDateTime.parse(s).toInstant().toEpochMilli(). STEP15A-B-2026-11-08: Android 14's java.time is JDK 12+ (golden run 18:08):
 *     both case-insensitive, whole text, and the offset is Z or +/-HH[:MM[:ss]] (minutes optional -- "+01" parses):
 *       - Instant.parse: uuuu-MM-dd'T'HH:mm:ss[.0-9 digits] then the offset -- seconds REQUIRED; "24:00:00" = next midnight (also with an
 *         offset: "T24:00:00+01:00"); "23:59:60" = 23:59:59.
 *       - OffsetDateTime.parse: uuuu-MM-dd'T'HH:mm[:ss[.0-9 digits]] then the offset; STRICT (no 24:00, no :60), offset <= 18h.
 *       - year: 4 to 10 digits; more than 4 needs '+'; '-' allowed, but not "-0000". Every other field exactly 2 digits.
 *  4. [instantText] = java.time.Instant.toString() (ingested_at, created_at): ...Z with no fraction when nanos == 0, else 3, 6 or 9
 *     digits; seconds always written.
 *  5. [local] = the local-time patterns 2.7e formats with SimpleDateFormat / DateTimeFormatter (ledger, manifests, area files).
 */
object GtTime {

    // ---- calendar arithmetic ------------------------------------------------------------------------------------------------
    /** Proleptic Gregorian (y, m 1..12, d) -> days since 1970-01-01. */
    fun gregorianEpochDay(y: Long, m: Int, d: Int): Long {
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        val jdn = d + (153 * mm + 2) / 5 + 365 * yy + fd(yy, 4) - fd(yy, 100) + fd(yy, 400) - 32045
        return jdn - 2440588
    }
    /** Julian calendar (y, m, d) -> days since 1970-01-01. */
    fun julianEpochDay(y: Long, m: Int, d: Int): Long {
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        val jdn = d + (153 * mm + 2) / 5 + 365 * yy + fd(yy, 4) - 32083
        return jdn - 2440588
    }
    private fun fd(a: Long, b: Long): Long = Math_floorDiv(a, b)
    private fun Math_floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }
    private fun floorMod(a: Long, b: Long): Long = a - Math_floorDiv(a, b) * b

    private fun gLeap(y: Long) = (y % 4 == 0L && y % 100 != 0L) || y % 400 == 0L
    private fun jLeap(y: Long) = floorMod(y, 4) == 0L
    private fun dim(m: Int, leap: Boolean) = when (m) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }

    /** The days for a java.util.GregorianCalendar date (Julian before 1582-10-15); null = no such date. */
    private fun hybridEpochDay(y: Long, m: Int, d: Int): Long? {
        val key = y * 10000 + m * 100 + d
        return when {
            key >= 15821015L -> if (d <= dim(m, gLeap(y))) gregorianEpochDay(y, m, d) else null
            key <= 15821004L -> if (d <= dim(m, jLeap(y))) julianEpochDay(y, m, d) else null
            else -> null
        }
    }

    /** days since 1970-01-01 -> (y, m, d), proleptic Gregorian (Howard Hinnant's civil_from_days). */
    fun gregorianCivil(epochDay: Long): Triple<Long, Int, Int> {
        val z = epochDay + 719468
        val era = Math_floorDiv(z, 146097)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        if (m <= 2) y += 1
        return Triple(y, m, d)
    }
    /** days since 1970-01-01 -> (y, m, d) in the Julian calendar. */
    fun julianCivil(epochDay: Long): Triple<Long, Int, Int> {
        val c = epochDay + 2440588 + 32082
        val dd = Math_floorDiv(4 * c + 3, 1461)
        val e = c - Math_floorDiv(1461 * dd, 4)
        val mm = (5 * e + 2) / 153
        val day = (e - (153 * mm + 2) / 5 + 1).toInt()
        val month = (mm + 3 - 12 * (mm / 10)).toInt()
        val year = dd - 4800 + mm / 10
        return Triple(year, month, day)
    }
    private const val GREGORIAN_CUTOVER_DAY = -141427L   // 1582-10-15

    private fun pad(v: Long, n: Int): String { val s = (if (v < 0) -v else v).toString().padStart(n, '0'); return if (v < 0) "-$s" else s }

    // ---- 1. SimpleDateFormat parse ------------------------------------------------------------------------------------------
    private enum class F(val count: Int) { Y(4), MO(2), D(2), H(2), MI(2), S(2), MS(3) }
    private sealed class Tok {
        class Num(val f: F) : Tok()
        class Lit(val c: Char) : Tok()
        object Zone : Tok()
    }
    class SdfPattern internal constructor(internal val toks: List<Any>, val text: String)

    private val BASE: List<Tok> = listOf(Tok.Num(F.Y), Tok.Lit('-'), Tok.Num(F.MO), Tok.Lit('-'), Tok.Num(F.D), Tok.Lit('T'),
        Tok.Num(F.H), Tok.Lit(':'), Tok.Num(F.MI), Tok.Lit(':'), Tok.Num(F.S))
    val SDF_MS_Z = SdfPattern(BASE + listOf(Tok.Lit('.'), Tok.Num(F.MS), Tok.Lit('Z')), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    val SDF_Z = SdfPattern(BASE + listOf(Tok.Lit('Z')), "yyyy-MM-dd'T'HH:mm:ss'Z'")
    val SDF_X = SdfPattern(BASE + listOf(Tok.Zone), "yyyy-MM-dd'T'HH:mm:ssXXX")
    val SDF_MS_X = SdfPattern(BASE + listOf(Tok.Lit('.'), Tok.Num(F.MS), Tok.Zone), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    /** In parseGpxTrackPointsFull's order. */
    val GPX_PATTERNS = listOf(SDF_MS_Z, SDF_Z, SDF_X, SDF_MS_X)

    /** SpatialDbManager.parseGpxTrackPointsFull's parseTime: trim, then the four patterns in order; epoch ms or null. */
    fun gpxTime(s: String): Long? {
        val t = s.trim()
        for (p in GPX_PATTERNS) sdfParse(t, p)?.let { return it }
        return null
    }

    /** One SimpleDateFormat(pattern).parse(text) with UTC, non-lenient. Epoch ms, or null where Java throws ParseException. */
    fun sdfParse(text: String, pattern: SdfPattern): Long? {
        @Suppress("UNCHECKED_CAST") val toks = pattern.toks as List<Tok>
        val len = text.length
        var pos = 0
        val v = IntArray(F.values().size)
        var offsetMs = 0L
        var zoneSet = false
        var i = 0
        while (i < toks.size) {
            when (val t = toks[i]) {
                is Tok.Lit -> { if (pos >= len || text[pos] != t.c) return null; pos++ }
                is Tok.Num -> {
                    while (true) { if (pos >= len) return null; val c = text[pos]; if (c != ' ' && c != '\t') break; pos++ }
                    val obey = i + 1 < toks.size && toks[i + 1] !is Tok.Lit && t.f != F.MS   // STEP15A-B: Android reads the fraction greedily
                    val limit = if (obey) { if (pos + t.f.count > len) return null; pos + t.f.count } else len
                    var p = pos
                    var neg = false
                    if (p < limit && text[p] == '-') { neg = true; p++ }
                    val start = p
                    var value = 0L
                    var overflow = false
                    while (p < limit && text[p] in '0'..'9') {
                        val dg = text[p] - '0'
                        if (!overflow) { if (value > (Long.MAX_VALUE - dg) / 10) overflow = true else value = value * 10 + dg }
                        p++
                    }
                    if (p == start) return null
                    pos = p
                    if (t.f == F.MS) {   // STEP15A-B-2026-11-08: Android 14 -- the digits are a fraction of a second, cut to milliseconds
                        val ms = (text.substring(start, p) + "000").substring(0, 3).toInt()
                        v[t.f.ordinal] = if (neg && ms != 0) -ms else ms   // "-5" -> out of range -> invalid; "-0" -> 0
                        i++; continue
                    }
                    v[t.f.ordinal] = when {
                        overflow -> if (neg) Int.MIN_VALUE else Int.MAX_VALUE   // DecimalFormat -> Double -> intValue saturates
                        neg -> (-value).toInt()                                 // "-0" -> 0
                        else -> value.toInt()                                   // Long.intValue: low 32 bits
                    }
                }
                is Tok.Zone -> {
                    while (true) { if (pos >= len) return null; val c = text[pos]; if (c != ' ' && c != '\t') break; pos++ }
                    val c = text[pos]
                    if (c == 'Z') { offsetMs = 0; zoneSet = true; pos++ }
                    else {
                        val sign = when (c) { '+' -> 1; '-' -> -1; else -> return null }
                        var k = pos + 1
                        fun digitAt(j: Int): Int? = if (j < len && text[j] in '0'..'9') text[j] - '0' else null
                        val h1 = digitAt(k) ?: return null; val h2 = digitAt(k + 1) ?: return null
                        val hours = h1 * 10 + h2
                        if (hours > 23) return null
                        k += 2
                        if (k >= len || text[k] != ':') return null
                        k++
                        val m1 = digitAt(k) ?: return null; val m2 = digitAt(k + 1) ?: return null
                        val minutes = m1 * 10 + m2
                        if (minutes > 59) return null
                        k += 2
                        offsetMs = sign * (hours * 60L + minutes) * 60_000L
                        zoneSet = true
                        pos = k
                    }
                }
            }
            i++
        }
        // non-lenient GregorianCalendar
        val y = v[F.Y.ordinal]; val mo = v[F.MO.ordinal]; val d = v[F.D.ordinal]
        val h = v[F.H.ordinal]; val mi = v[F.MI.ordinal]; val s = v[F.S.ordinal]; val ms = v[F.MS.ordinal]
        if (y < 1 || y > 292278994) return null
        if (mo < 1 || mo > 12) return null
        if (d < 1 || d > 31) return null
        if (h !in 0..23 || mi !in 0..59 || s !in 0..59 || ms !in 0..999) return null
        if (zoneSet && (offsetMs < -13 * 3_600_000L || offsetMs > 14 * 3_600_000L)) return null
        val day = hybridEpochDay(y.toLong(), mo, d) ?: return null
        return ((day * 86400 + h * 3600L + mi * 60L + s) * 1000L + ms) - offsetMs
    }

    // ---- 2. SimpleDateFormat format, UTC ------------------------------------------------------------------------------------
    /** "yyyy-MM-dd'T'HH:mm:ss'Z'" of [ms] in UTC, as java.text.SimpleDateFormat (Julian before 1582-10-15; BC as year of era). */
    fun formatUtcSeconds(ms: Long): String {
        val sec = Math_floorDiv(ms, 1000)
        val day = Math_floorDiv(sec, 86400)
        val sod = floorMod(sec, 86400)
        val (y0, m, d) = if (day >= GREGORIAN_CUTOVER_DAY) gregorianCivil(day) else julianCivil(day)
        val y = if (y0 <= 0) 1 - y0 else y0
        return pad(y, 4) + "-" + pad(m.toLong(), 2) + "-" + pad(d.toLong(), 2) + "T" +
            pad(sod / 3600, 2) + ":" + pad(sod % 3600 / 60, 2) + ":" + pad(sod % 60, 2) + "Z"
    }

    // ---- 3. java.time ISO parse (Android 14 = JDK 12+) -------------------------------------------------------------------------------------
    /** ConvoyTrackOps.extractEarliestTime's two parses: Instant.parse, else OffsetDateTime.parse. Epoch ms or null (Android 14 rules). */
    fun isoToEpochMs(s: String): Long? = instantParse(s) ?: offsetDateTimeParse(s)

    private class Cur(val t: String) {
        var p = 0
        fun lit(c: Char): Boolean { if (p < t.length && t[p].equals(c, ignoreCase = true)) { p++; return true }; return false }
        fun two(): Int? { if (p + 2 > t.length) return null; val a = t[p]; val b = t[p + 1]; if (a !in '0'..'9' || b !in '0'..'9') return null; p += 2; return (a - '0') * 10 + (b - '0') }
        /** appendValue(YEAR, 4, 10, EXCEEDS_PAD), strict. */
        fun year(): Long? {
            val start = p
            var neg = false; var pos = false
            if (p < t.length && t[p] == '+') { pos = true; p++ } else if (p < t.length && t[p] == '-') { neg = true; p++ }
            val ds = p
            while (p < t.length && p - ds < 10 && t[p] in '0'..'9') p++
            val n = p - ds
            if (n < 4) { p = start; return null }
            val v = t.substring(ds, p).toLong()
            if (neg && v == 0L) { p = start; return null }
            if (!neg && pos && n <= 4) { p = start; return null }
            if (!neg && !pos && n > 4) { p = start; return null }
            return if (neg) -v else v
        }
        /** appendFraction(NANO_OF_SECOND, 0, 9, true): optional '.' then 0..9 digits -> nanos. */
        fun fraction(): Int {
            if (p >= t.length || t[p] != '.') return 0
            p++
            val ds = p
            while (p < t.length && p - ds < 9 && t[p] in '0'..'9') p++
            val dig = t.substring(ds, p)
            return if (dig.isEmpty()) 0 else dig.padEnd(9, '0').toInt()
        }
        fun end() = p == t.length
    }

    private fun validDate(y: Long, m: Int, d: Int) = m in 1..12 && d >= 1 && d <= dim(m, gLeap(y))

    /**
     * appendOffsetId as Android 14 parses it: "Z" (any case) or +/-HH, then optionally :MM, then optionally :ss (each two digits,
     * <= 59; a ':' not followed by two such digits is left unread -> the text does not end there -> no time). Offset <= 18 h.
     * Seconds of offset, or null.
     */
    private fun offset(c: Cur, s: String): Int? {
        if (c.p >= s.length) return null
        if (s[c.p].equals('Z', ignoreCase = true)) { c.p++; return 0 }
        val sign = when (s[c.p]) { '+' -> 1; '-' -> -1; else -> return null }
        c.p++
        val oh = c.two() ?: return null
        if (oh > 59) return null
        var om = 0; var os = 0
        val save = c.p
        if (c.lit(':')) {
            val x = c.two()
            if (x == null || x > 59) c.p = save
            else {
                om = x
                val save2 = c.p
                if (c.lit(':')) { val y = c.two(); if (y == null || y > 59) c.p = save2 else os = y }
            }
        }
        val off = sign * (oh * 3600 + om * 60 + os)
        if (off < -64800 || off > 64800) return null
        return off
    }

    /** Android 14 (JDK 12+) Instant.parse (ISO_INSTANT): seconds required, Z or an offset, 24:00:00 and 23:59:60 accepted. */
    fun instantParse(s: String): Long? {
        val c = Cur(s)
        val yearParsed = c.year() ?: return null
        if (!c.lit('-')) return null; val mo = c.two() ?: return null
        if (!c.lit('-')) return null; val d = c.two() ?: return null
        if (!c.lit('T')) return null; var h = c.two() ?: return null
        if (!c.lit(':')) return null; val mi = c.two() ?: return null
        if (!c.lit(':')) return null; var sec = c.two() ?: return null
        val nano = c.fraction()
        val off = offset(c, s) ?: return null   // STEP15A-B: JDK 12+ -- Z or an offset (was: 'Z' only, JDK 8)
        if (!c.end()) return null
        var days = 0
        if (h == 24 && mi == 0 && sec == 0 && nano == 0) { h = 0; days = 1 }
        else if (h == 23 && mi == 59 && sec == 60) sec = 59
        val year = yearParsed % 10_000
        if (!validDate(year, mo, d) || h !in 0..23 || mi !in 0..59 || sec !in 0..59) return null
        val epochSec = (gregorianEpochDay(year, mo, d) + days) * 86400 + h * 3600L + mi * 60L + sec +
            (yearParsed / 10_000L) * 315569520000L - off
        return epochSec * 1000 + nano / 1_000_000
    }

    /** Android 14 OffsetDateTime.parse (ISO_OFFSET_DATE_TIME, STRICT). */
    fun offsetDateTimeParse(s: String): Long? {
        val c = Cur(s)
        val year = c.year() ?: return null
        if (!c.lit('-')) return null; val mo = c.two() ?: return null
        if (!c.lit('-')) return null; val d = c.two() ?: return null
        if (!c.lit('T')) return null; val h = c.two() ?: return null
        if (!c.lit(':')) return null; val mi = c.two() ?: return null
        var sec = 0; var nano = 0
        run {   // optionalStart ':' ss [fraction]
            val save = c.p
            if (c.lit(':')) { val ss = c.two(); if (ss == null) c.p = save else { sec = ss; nano = c.fraction() } }
        }
        val off = offset(c, s) ?: return null
        if (!c.end()) return null
        if (!validDate(year, mo, d) || h !in 0..23 || mi !in 0..59 || sec !in 0..59) return null
        if (year < -999_999_999L || year > 999_999_999L) return null
        val epochSec = gregorianEpochDay(year, mo, d) * 86400 + h * 3600L + mi * 60L + sec - off
        return epochSec * 1000 + nano / 1_000_000
    }

    // ---- 4. Instant.toString ------------------------------------------------------------------------------------------------
    /** java.time.Instant.ofEpochSecond(epochSec, nanos).toString(). nanos 0..999,999,999. */
    fun instantText(epochSec: Long, nanos: Int): String {
        val day = Math_floorDiv(epochSec, 86400)
        val sod = floorMod(epochSec, 86400)
        val (y, m, d) = gregorianCivil(day)
        val sb = StringBuilder()
        if (y > 9999) sb.append('+').append(y) else sb.append(pad(y, 4))
        sb.append('-').append(pad(m.toLong(), 2)).append('-').append(pad(d.toLong(), 2)).append('T')
            .append(pad(sod / 3600, 2)).append(':').append(pad(sod % 3600 / 60, 2)).append(':').append(pad(sod % 60, 2))
        if (nanos != 0) {
            sb.append('.')
            when {
                nanos % 1_000_000 == 0 -> sb.append((nanos / 1_000_000).toString().padStart(3, '0'))
                nanos % 1_000 == 0 -> sb.append((nanos / 1_000).toString().padStart(6, '0'))
                else -> sb.append(nanos.toString().padStart(9, '0'))
            }
        }
        sb.append('Z')
        return sb.toString()
    }
    /** Instant.ofEpochMilli(ms).toString() -- what Instant.now().toString() gives on Android (millisecond clock). */
    fun instantTextMs(ms: Long): String = instantText(Math_floorDiv(ms, 1000), (floorMod(ms, 1000) * 1_000_000).toInt())

    // ---- 5. local-time patterns ---------------------------------------------------------------------------------------------
    /**
     * [t] formatted with a SimpleDateFormat-style pattern of y M d H m s, quoted literals ('T') and any other character as itself:
     * "yyyy-MM-dd'T'HH:mm:ss" (ledger, manifests), "yyyyMMdd_HHmmss" (trail area files), "yyyy-MM-dd'T'HHmmss" (ledger archive),
     * "yyyy-MM-dd" (manifest names, the trail batch). Each letter run pads to its length.
     */
    fun local(t: GtLocalTime, pattern: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                val j = pattern.indexOf('\'', i + 1)
                if (j == i + 1) sb.append('\'') else sb.appendRange(pattern, i + 1, if (j < 0) pattern.length else j)
                i = if (j < 0) pattern.length else j + 1
                continue
            }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            val v: Long? = when (c) { 'y' -> t.year.toLong(); 'M' -> t.month.toLong(); 'd' -> t.day.toLong()
                'H' -> t.hour.toLong(); 'm' -> t.minute.toLong(); 's' -> t.second.toLong(); else -> null }
            if (v == null) repeat(n) { sb.append(c) } else sb.append(pad(v, n))
            i += n
        }
        return sb.toString()
    }
}

/** STEP15A-2026-11-08: a device-local wall-clock time (the platform reads its own zone). */
data class GtLocalTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)
