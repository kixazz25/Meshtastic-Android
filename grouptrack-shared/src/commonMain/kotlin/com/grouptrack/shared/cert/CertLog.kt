package com.grouptrack.shared.cert

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit

/**
 * CERTLOG-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the certification of the LOGGING seam conversion (GTLOGKERMIT-2026-11-16, 994 sites in 102
 * files, commit 491b15cac).
 *
 * WHAT IT CERTIFIES, and why the source comparison was not enough. gt_logkermit's old/new report
 * proves the TRANSFORMATION is right -- the text we wrote is the text we meant to write. It says
 * nothing about what the app EMITS. Those are different claims, and the second is the one that
 * matters: if a tag, a severity or a single character changes, the log stops being 2.7e's log and
 * every log-reading instrument we own (gt_execscope, the cert harness, deploy recordings) silently
 * changes meaning.
 *
 * Fred 11-16 08:06: "the hour test of everything has been the practice... one special character
 *   somewhere could wreak havoc. we must emulate today's messaging exactly or identify and repair
 *   the failing message content."
 * He was right to insist on the whole corpus rather than a sample. The character census over the
 * 994 real messages found: 2548 quotes, 1455 dollars, 724 brace pairs, 95 apostrophes, 88
 * newlines, 24 backslashes, 13 percents -- and 37 NON-ASCII characters (34 em dashes U+2014, 3
 * arrows U+2192). A 12-case sample would have missed every one of those, and they are exactly
 * where Kermit's Android writer and its iOS writer could diverge.
 *
 * THE THREE LEGS (Fred 11-16 08:02: "old message on droid 1 then new method on droid 1 then iPad
 * version -- all have to be identical"):
 *   1. OLD on Droid 1  -- what android.util.Log was handed. Supplied by the app side (Side).
 *   2. NEW on Droid 1  -- what Kermit's writer receives, through a Logger built exactly as
 *                         gtLogger builds one. THIS FUNCTION, in shared code.
 *   3. NEW on the iPad -- the same shared function, so a difference is a platform difference.
 * Legs 1 and 2 are compared on the device and written to the report. Leg 3 re-runs NEW on the
 * simulator and must reproduce NEW's value for every key, exactly as GtCert15bMacTest does.
 *
 * THE CORPUS IS DATA, NOT CODE. The real call sites interpolate local variables that cannot exist
 * in a harness, so each message's literal skeleton is carried verbatim in a TSV and read back at
 * runtime. "${entryId}" is therefore inert text here, which makes this the STRICTEST form of the
 * test: it compares the exact character sequence, with no interpolation to muddy it.
 *
 * The capturing writer's shape is copied from NordicBleInterfaceTest.kt:61, which compiles today --
 * not from Kermit's documentation. Writing Foundation calls from documentation is what broke the
 * iOS build on 11-15.
 */
object CertLog {

    /** Exactly what a logger was handed. */
    data class Rec(val severity: String, val tag: String, val message: String)

    /** One side of the comparison. OLD is Android's android.util.Log; NEW is Kermit. */
    interface Side {
        /** Return what this side's logger was handed / received for one case. */
        fun emit(level: String, tag: String, text: String): Rec
    }

    /**
     * Keys known to differ for a reason that is NOT a port defect, with the reason.
     * Empty: nothing is expected to differ. A message that reaches one logger and not the other
     * is a defect, not an expectation -- that is the whole point of the conversion.
     */
    val EXPECTED_DIFF: Map<String, String> = emptyMap()

    /** Captures what Kermit passes its writer. Signature copied from NordicBleInterfaceTest:61. */
    private class Capture : LogWriter() {
        var last: Rec? = null
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            last = Rec(severity.name, tag, message)
        }
    }

    /**
     * NEW: one case through a Kermit Logger built as gtLogger builds one, reporting what the
     * writer received. Platform-free, so the Droid and the iPad run identical code and a
     * difference between them is a PLATFORM difference.
     */
    fun newSide(): Side = object : Side {
        override fun emit(level: String, tag: String, text: String): Rec {
            val cap = Capture()
            val log = Logger(loggerConfigInit(cap, minSeverity = Severity.Verbose), tag)
            when (level) {
                "v" -> log.v { text }
                "d" -> log.d { text }
                "i" -> log.i { text }
                "w" -> log.w { text }
                "e" -> log.e { text }
                else -> return Rec("BADLEVEL:" + level, tag, text)
            }
            return cap.last ?: Rec("NOTHING_EMITTED", tag, text)
        }
    }

    /** The value form: readable, so a difference can be seen rather than decoded. */
    fun value(r: Rec): String =
        "sev=" + r.severity + ";tag=" + r.tag + ";len=" + r.message.length + ";txt=" + esc(r.message)

    /** One corpus row. */
    data class Case(val level: String, val tag: String, val text: String, val source: String)

    /** The key for a case, stable across all three legs. */
    fun keyOf(index: Int, c: Case): String = "" + (index + 1) + "|" + c.source

    /**
     * Run every case through ONE side and emit key -> value, exactly as Cert17mbtiles.run does.
     * The comparison is the DRIVER's job: the app runs both sides and writes SAME/DIFF; the Mac
     * runs NEW and compares against the Droid's SAME lines.
     */
    fun run(cases: List<Case>, side: Side, sink: (String, String) -> Unit) {
        for ((i, c) in cases.withIndex()) sink(keyOf(i, c), value(side.emit(c.level, c.tag, c.text)))
    }

    fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> b.append("\\\\")
            '\t' -> b.append("\\t")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            else -> if (c.code > 126 || c.code < 32) b.append("\\u").append(hex4(c.code)) else b.append(c)
        }
        return b.toString()
    }

    private fun hex4(n: Int): String {
        val d = "0123456789ABCDEF"
        return "" + d[(n shr 12) and 15] + d[(n shr 8) and 15] + d[(n shr 4) and 15] + d[n and 15]
    }

    fun unesc(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { b.append(c); i++; continue }
            when (s[i + 1]) {
                '\\' -> { b.append('\\'); i += 2 }
                't' -> { b.append('\t'); i += 2 }
                'n' -> { b.append('\n'); i += 2 }
                'r' -> { b.append('\r'); i += 2 }
                'u' -> {
                    if (i + 6 <= s.length) {
                        val v = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (v != null) { b.append(v.toChar()); i += 6 } else { b.append(c); i++ }
                    } else { b.append(c); i++ }
                }
                else -> { b.append(c); i++ }
            }
        }
        return b.toString()
    }

    /** Parse the corpus TSV. Bad rows are returned, never dropped silently. */
    fun parseCorpus(tsv: String): Pair<List<Case>, List<String>> {
        val cases = ArrayList<Case>()
        val bad = ArrayList<String>()
        var first = true
        for (raw in tsv.split("\n")) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            if (first) { first = false; if (line.startsWith("level\t")) continue }
            val p = line.split("\t")
            if (p.size < 3) { bad.add("short row: " + line.take(60)); continue }
            cases.add(Case(p[0].trim(), p[1].trim(), unesc(p[2]), if (p.size > 3) p[3].trim() else "?"))
        }
        return Pair(cases, bad)
    }
}
