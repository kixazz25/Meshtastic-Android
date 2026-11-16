package com.geeksville.mesh.grouptrack

import android.content.Context
import com.grouptrack.shared.cert.CertLog
import com.grouptrack.shared.log.gtLogger
import java.io.File
import java.io.Writer

/**
 * CERTLOG-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the Droid legs of the LOGGING seam certification (GTLOGKERMIT-2026-11-16, 994 sites, 102 files,
 * commit 491b15cac).
 *
 * ⚠ NOTHING IS REIMPLEMENTED HERE. The NEW side, the key form and the value form are CertLog's --
 * the same common code the iPad runs. Only the OLD side (android.util.Log) is Android's, because
 * there is no other platform to run it on.
 *
 * Lines: CERTLOG <tab> key <tab> SAME|EXPECTED|DIFF <tab> value
 * Footer: "# CERTLOG done: N SAME, N DIFF, N EXPECTED in S s"
 * The iPad (GtCertLogMacTest) reads the SAME lines and must reproduce NEW's value for every key.
 *
 * Fred 11-16 08:02: "old message on droid 1 then new method on droid 1 then iPad version -- all
 *   have to be identical."   08:18: "no shortcuts. all three have to execute and be compared."
 *
 * TWO PASSES, certifying different things:
 *  PASS A -- the report above: what android.util.Log is HANDED against what Kermit's writer
 *   RECEIVES. Catches a changed tag, a remapped severity, or any altered character between the
 *   call and the writer.
 *  PASS B -- the fenced logcat blocks (Fred 08:13: "a process that writes the logical messaging to
 *   a logcat file with a specific identifier so we can access the log file"). Every case emitted
 *   FOR REAL, first through android.util.Log then through gtLogger, between
 *       CERTLOGFENCE: BEGIN OLD run=<id> / BEGIN NEW run=<id> / END run=<id>
 *   gt_cert already records the run (gt_cert_v47.sh:309-310), so both blocks land in that
 *   recording and are compared POSITIONALLY. The id is in the fences, never in the messages --
 *   prefixing a message would alter the very text under test, and identical OLD and NEW lines are
 *   indistinguishable by content, which is the point.
 *
 * THE CORPUS IS DATA: GroupTrack/certlog/gt_logcert_corpus_2026-11-16.tsv, all 994 messages in
 * Kotlin source form, read back as inert text -- so the exact character sequence is certified with
 * no interpolation in the way. Why all 994 and not a sample (Fred 08:06): 2548 quotes, 1455
 * dollars, 724 brace pairs, 88 newlines, 24 backslashes, 13 percents, and 37 NON-ASCII characters
 * (34 em dashes U+2014, 3 arrows U+2192).
 *
 * NOTHING LIVE IS READ OR WRITTEN: no database, no my_tracks, no Download but the report the
 * harness collects. The only other effect is log lines, which is what is being certified.
 */
object GtCertLog {
    private const val TAG = "CertRun"
    private val log = gtLogger(TAG)

    private fun esc(s: String) =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun sevOf(level: String) = when (level) {
        "v" -> "Verbose"; "d" -> "Debug"; "i" -> "Info"; "w" -> "Warn"; "e" -> "Error"; else -> "?"
    }

    /** OLD: what android.util.Log is handed. It cannot be read back in-process, so the arguments
     *  are the report, and pass B proves what logcat actually received. */
    private object OldSide : CertLog.Side {
        override fun emit(level: String, tag: String, text: String): CertLog.Rec =
            CertLog.Rec(sevOf(level), tag, text)
    }

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val app = ctx.applicationContext
        // CERTLOGASSET-2026-11-16: the corpus is an APP ASSET, not a pushed file. Prior art:
        // Cert17g1c / Cert17e1 / CertSchema / GtMapSpec all take their fixture through readAsset.
        // Why it matters here: the asset ships inside the APK, so the Droid and the Mac cannot
        // read different inputs, there is no push step to mis-scope (v48's push block landed
        // inside `if [ "$MODE" = osm15g ]` and never ran), and the 1455 '$' characters never pass
        // through Kotlin source escaping, which would have risked corrupting the very characters
        // under test.
        val name = "gt_logcert_corpus_2026-11-16.tsv"
        val text = try {
            app.assets.open(name).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            log.e { "GTCERT certlog failed: asset $name not readable -- ${e.message}" }
            w.write("CERTLOG\tNO-CORPUS\tDIFF\t${esc(name + ": " + e.toString())}\n"); w.flush(); return
        }
        // The corpus fingerprint goes in the report so the Mac can PROVE it read the same input.
        var h = 0L
        for (c in text) h = h * 31 + c.code
        w.write("# CERTLOG corpus asset=$name len=${text.length} lines=${text.count { it == '\n' }} hash=$h\n")
        val parsed = CertLog.parseCorpus(text)
        val cases = parsed.first
        for (b in parsed.second) w.write("# CERTLOG BADROW ${esc(b)}\n")
        if (cases.isEmpty()) {
            log.e { "GTCERT certlog failed: corpus parsed to 0 cases" }
            w.write("CERTLOG\tNO-CASES\tDIFF\t0\n"); w.flush(); return
        }
        val runId = t0.toString()
        val nonAscii = cases.count { c -> c.text.any { it.code > 126 } }
        w.write("# CERTLOG cases=${cases.size} nonAscii=$nonAscii runId=$runId\n")
        log.i { "GTCERT certlog: ${cases.size} cases, $nonAscii with non-ASCII, runId=$runId" }

        // ---- PASS B first, so the recorded logcat holds the blocks even if pass A throws -----
        android.util.Log.i("CERTLOGFENCE", "BEGIN OLD run=$runId cases=${cases.size}")
        for (c in cases) when (c.level) {
            "v" -> android.util.Log.v(c.tag, c.text)
            "d" -> android.util.Log.d(c.tag, c.text)
            "i" -> android.util.Log.i(c.tag, c.text)
            "w" -> android.util.Log.w(c.tag, c.text)
            "e" -> android.util.Log.e(c.tag, c.text)
        }
        android.util.Log.i("CERTLOGFENCE", "BEGIN NEW run=$runId cases=${cases.size}")
        for (c in cases) {
            val lg = gtLogger(c.tag)
            when (c.level) {
                "v" -> lg.v { c.text }
                "d" -> lg.d { c.text }
                "i" -> lg.i { c.text }
                "w" -> lg.w { c.text }
                "e" -> lg.e { c.text }
            }
        }
        android.util.Log.i("CERTLOGFENCE", "END run=$runId cases=${cases.size}")
        log.i { "GTCERT certlog: fenced logcat blocks emitted, runId=$runId" }

        // ---- PASS A: both sides through the shared runner, then compare ----------------------
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try { CertLog.run(cases, OldSide) { k, v -> oldMap[k] = v } }
        catch (e: Exception) { w.write("CERTLOG\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try { CertLog.run(cases, CertLog.newSide()) { k, v -> newMap[k] = v } }
        catch (e: Exception) { w.write("CERTLOG\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0; var diff = 0; var expected = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k]; val n = newMap[k]
            val whyExp = CertLog.EXPECTED_DIFF[k]
            if (o != null && o == n) {
                same++; w.write("CERTLOG\t$k\tSAME\t$o\n")
                if (whyExp != null) w.write("# CERTLOG-NOTE\tEXPECTED_DIFF[$k] is STALE: OLD and NEW now agree ($whyExp)\n")
            } else if (whyExp != null) {
                expected++
                w.write("CERTLOG\t$k\tEXPECTED\t$o | $n  [$whyExp]\n")
                w.write("CERTLOG\t$k|newonly\tSAME\t$n\n")
            } else {
                diff++; w.write("CERTLOG\t$k\tDIFF\t$o | $n\n")
            }
        }
        // Fred 11-16 08:38: "write your test cases to a print file and test from there." The cases
        // the Droid ACTUALLY used are printed into the report, so a later difference can never be
        // blamed on the two sides having read different input.
        w.write("# CERTLOG cases-used begin\n")
        for ((i, c) in cases.withIndex())
            w.write("# CASE\t${CertLog.keyOf(i, c)}\t${c.level}\t${c.tag}\t${CertLog.esc(c.text)}\n")
        w.write("# CERTLOG cases-used end\n")
        w.write("# CERTLOG fences runId=$runId -- slice the recorded logcat on BEGIN OLD / BEGIN NEW / END\n")
        w.write("# CERTLOG done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
        log.i { "GTCERT certlog done: $same SAME, $diff DIFF, $expected EXPECTED" }
    }
}
