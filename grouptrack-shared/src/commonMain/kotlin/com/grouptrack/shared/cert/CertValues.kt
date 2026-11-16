package com.grouptrack.shared.cert

import com.grouptrack.shared.imports.GtTrailClass
import com.grouptrack.shared.net.GtRandom
import com.grouptrack.shared.state.GtQueueRow

/**
 * CERTVALUES-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the harness for seam 2 BATCH 1, the pure-value services. Environment values are deliberately NOT
 * here; they are last and get a harness of their own (Fred 11-16 09:53).
 *
 * Fred 11-16 10:03: "wow this complexity is why we wrote test harnesses."
 * He is right, and today proved it twice: a zero-byte file copy and a private directory turned
 * user-visible both compiled clean. Only running the thing and comparing OUTPUT finds those.
 *
 * ⛔ THE THREE SERVICES DO NOT HAVE THE SAME NUMBER OF LEGS, and pretending they do would be the
 * dishonest part of this harness:
 *
 *   TRAILUSE   THREE legs, strict. OLD = Android's TrailClassifier.USE_OF, NEW = the shared
 *              GtTrailUse table on the Droid, IPAD = the same shared table through IosTrailClass.
 *              Android's table is NOT being changed, so OLD and NEW both exist right now and can be
 *              read side by side -- which is why batch 1 carries no regression risk.
 *
 *   QUEUEROW   TWO legs, strict. There is no Droid conversion: Android keeps org.json JSONObject
 *              rows ON PURPOSE, because JsonRow wraps "the same object, not a copy" so the import
 *              record sees the queue's writes. So the comparison is Droid's JSONObject-backed row
 *              against the iPad's map-backed row -- two implementations of one interface that must
 *              agree. Marked QUEUEROW, and a missing NEW leg is correct, not a gap.
 *
 *   RANDOM     NO comparison at all. PROPERTY checks only, reported as RANDOM-FACT the way the
 *              mbtiles cert reported ENGINE-FACT. Android is SecureRandom, the iPad is arc4random;
 *              identical values would mean the generator is broken. The existing certs never tested
 *              the real one either -- they inject java.util.Random(seed) on both sides.
 *
 * The shape is copied from CertLog, which certified 994 cases byte for byte this morning: one Side
 * per leg, emit (key, value), the app driver compares OLD against NEW and writes the report, the
 * Mac test re-runs the shared side and must reproduce it.
 */
object CertValues {

    /**
     * Keys known to differ for a reason that is NOT a port defect, with the reason.
     * Empty on purpose: every difference shows as a DIFF on the first run and is classified then,
     * rather than being pre-authorised by me now.
     */
    val EXPECTED_DIFF: Map<String, String> = emptyMap()

    // ------------------------------------------------------------------ TRAILUSE

    /**
     * Every key in the table, plus the ones that must fall through to the default.
     * ⚠ TrailClassifier.useOf is an EXACT map lookup -- no ignoreCase -- so "ohv" and "OHV " are
     * genuinely different questions and both must answer MOTORIZED by the default, not by a match.
     * If a future edit makes the lookup case-insensitive, these three cases are what catches it.
     */
    val TRAIL_KEYS: List<String> = listOf(
        "OHV", "track", "forestry/access road", "shape only",
        "hiking and biking", "hiking", "biking", "equestrian", "steps/bridge",
        "unknown", "rider",
        "", " ", "ohv", "OHV ", "Track", "nonsense", "rider ", "NON-MOTORIZED",
    )

    /** One side of the trail-use comparison: whatever that leg's table says. */
    fun interface TrailSide {
        fun useOf(category: String): String
    }

    fun trailSideOf(t: GtTrailClass): TrailSide = TrailSide { t.useOf(it) }

    fun runTrail(side: TrailSide, sink: (String, String) -> Unit) {
        for ((i, k) in TRAIL_KEYS.withIndex()) {
            sink("TRAILUSE|" + (i + 1) + "|" + esc(k), side.useOf(k))
        }
    }

    // ------------------------------------------------------------------ QUEUEROW

    /**
     * A scripted sequence against one row, chosen to pin org.json's coercion rather than a happy path.
     * ⛔ The last four cases are the REAL queue's _stop flow (GtStateDataQueue:103, :124, :128, :129):
     * written as a Boolean, read with optBoolean, removed, read again. A row backed by plain Strings
     * passes the first three cases by luck and fails "string true".
     */
    fun runRow(row: GtQueueRow, sink: (String, String) -> Unit) {
        fun put(k: String, v: String) = sink(k, v)

        put("QUEUEROW|01|missing optString", "[" + row.optString("nope") + "]")
        put("QUEUEROW|02|missing optBoolean", row.optBoolean("nope").toString())

        row.put("s", "hello")
        put("QUEUEROW|03|string roundtrip", "[" + row.optString("s") + "]")
        put("QUEUEROW|04|string as boolean", row.optBoolean("s").toString())

        row.put("bt", true)
        put("QUEUEROW|05|bool true optBoolean", row.optBoolean("bt").toString())
        put("QUEUEROW|06|bool true optString", "[" + row.optString("bt") + "]")

        row.put("bf", false)
        put("QUEUEROW|07|bool false optBoolean", row.optBoolean("bf").toString())
        put("QUEUEROW|08|bool false optString", "[" + row.optString("bf") + "]")

        row.put("st", "true")
        put("QUEUEROW|09|STRING true optBoolean", row.optBoolean("st").toString())
        row.put("sf", "false")
        put("QUEUEROW|10|STRING false optBoolean", row.optBoolean("sf").toString())

        row.put("gone", "x")
        row.remove("gone")
        put("QUEUEROW|11|removed optString", "[" + row.optString("gone") + "]")
        put("QUEUEROW|12|removed optBoolean", row.optBoolean("gone").toString())

        row.put("s", "overwritten")
        put("QUEUEROW|13|overwrite", "[" + row.optString("s") + "]")

        row.put("_stop", true)
        put("QUEUEROW|14|_stop set", row.optBoolean("_stop").toString())
        put("QUEUEROW|15|_stop as string", "[" + row.optString("_stop") + "]")
        row.remove("_stop")
        put("QUEUEROW|16|_stop cleared", row.optBoolean("_stop").toString())
        put("QUEUEROW|17|status default", "[" + row.optString("status") + "]")
    }

    // ------------------------------------------------------------------ RANDOM

    /**
     * PROPERTIES, not values. Each line is a RANDOM-FACT: true on a correct generator on any
     * platform, and never compared between legs.
     * ⚠ draws is fixed so the coverage case is deterministic in its PASS/FAIL, not in its values.
     */
    fun runRandom(r: GtRandom, draws: Int = 4000, sink: (String, String) -> Unit) {
        var allZeroBound = true
        for (i in 0 until draws) if (r.nextInt(1) != 0) allZeroBound = false
        sink("RANDOM-FACT|01|nextInt(1) always 0", allZeroBound.toString())

        var inRange = true
        val seen = HashSet<Int>()
        for (i in 0 until draws) {
            val v = r.nextInt(10)
            if (v < 0 || v > 9) inRange = false
            seen.add(v)
        }
        sink("RANDOM-FACT|02|nextInt(10) in 0..9", inRange.toString())
        sink("RANDOM-FACT|03|nextInt(10) saw all ten", (seen.size == 10).toString())

        val a = ByteArray(32)
        val b = ByteArray(32)
        r.nextBytes(a)
        r.nextBytes(b)
        sink("RANDOM-FACT|04|nextBytes fills 32", (a.size == 32).toString())
        sink("RANDOM-FACT|05|nextBytes not all zero", a.any { it != 0.toByte() }.toString())
        sink("RANDOM-FACT|06|two draws differ", (!a.contentEquals(b)).toString())

        val odd = ByteArray(7)
        r.nextBytes(odd)
        sink("RANDOM-FACT|07|non-multiple-of-4 filled", odd.any { it != 0.toByte() }.toString())
    }

    // ------------------------------------------------------------------ shared helpers

    /** Non-ASCII and control characters as \uXXXX, so a report line is always one printable line. */
    fun esc(s: String): String = buildString {
        for (c in s) {
            val n = c.code
            if (n in 32..126 && c != '\t') append(c)
            else append("\\u").append(n.toString(16).padStart(4, '0'))
        }
    }
}
