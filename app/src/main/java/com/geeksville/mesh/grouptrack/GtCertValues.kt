package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.TrailClassifier
import com.grouptrack.shared.cert.CertValues
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.net.GtRandom
import com.grouptrack.shared.state.GtQueueRow
import com.grouptrack.shared.trail.GtTrailUse
import java.io.Writer

/**
 * CERTVALUES-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the Droid legs of seam 2 BATCH 1 -- the pure-value services. Environment values are last and get
 * their own harness (Fred 11-16 09:53).
 *
 * ⚠ NOTHING IS REIMPLEMENTED HERE except where Android gives no choice, and each exception is named:
 *
 *  TRAILUSE  BOTH tables are read as they stand. OLD = TrailClassifier.USE_OF (app, unchanged),
 *   NEW = GtTrailUse.USE_OF (shared, new). Android is NOT being patched, so OLD and NEW both exist
 *   right now -- which is the whole reason batch 1 carries no regression risk, and why this leg can
 *   be certified BEFORE any conversion rather than after.
 *
 *  QUEUEROW  ⛔ StateDataQueue.JsonRow is `private class`, so it cannot be called from here. JsonRowCert
 *   below is its five overrides copied CHARACTER FOR CHARACTER (StateDataQueue.kt:16-21). That is a
 *   copy and must be read as one: if JsonRow is ever edited without this copy, the harness certifies
 *   the wrong thing. The alternative was widening JsonRow's visibility, i.e. changing running app
 *   code to test it, which is the worse trade.
 *
 *  RANDOM  Android's java.security.SecureRandom, as GtNetworks' header describes it. ⚠ Only the
 *   PROPERTIES are emitted, never a drawn value: SecureRandom and arc4random must not agree on
 *   values, and a harness that compared them would fail on a correct generator. Each RANDOM-FACT
 *   line is a boolean that has to read true on every platform, so the facts DO compare strictly
 *   even though the numbers behind them never can.
 *
 * Lines: CERTVALUES <tab> key <tab> SAME|EXPECTED|DIFF <tab> value
 * The iPad (GtCertValuesMacTest) reads the SAME lines and must reproduce each value.
 *
 * NOTHING LIVE IS READ OR WRITTEN: no database, no files, no Download. Three in-memory services.
 */
object GtCertValues {
    private const val TAG = "CertRun"
    private val log = gtLogger(TAG)

    private fun esc(s: String) =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** ⛔ StateDataQueue.kt:16-21 VERBATIM. JsonRow is private; see the header. */
    private class JsonRowCert(val json: org.json.JSONObject) : GtQueueRow {
        override fun optString(key: String): String = json.optString(key)
        override fun optBoolean(key: String): Boolean = json.optBoolean(key)
        override fun put(key: String, value: String) { json.put(key, value) }
        override fun put(key: String, value: Boolean) { json.put(key, value) }
        override fun remove(key: String) { json.remove(key) }
    }

    /** Android's generator, as GtNetworks describes it: the same SecureRandom. */
    private object AndroidRandom : GtRandom {
        private val rng = java.security.SecureRandom()
        override fun nextInt(bound: Int): Int = rng.nextInt(bound)
        override fun nextBytes(b: ByteArray) = rng.nextBytes(b)
    }

    fun run(ctx: Context, w: Writer): Unit {
        val t0 = System.currentTimeMillis()
        var same = 0
        var diff = 0
        var expected = 0

        w.write("# CERTVALUES batch 1 (pure values) -- trailuse 3 legs, queuerow 2 legs, random properties\n")

        // ---------------------------------------------------------------- TRAILUSE: OLD vs NEW
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try {
            CertValues.runTrail(CertValues.TrailSide { TrailClassifier.useOf(it) }) { k, v -> oldMap[k] = v }
        } catch (e: Exception) {
            w.write("CERTVALUES\tTRAILUSE-OLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return
        }
        try {
            CertValues.runTrail(CertValues.TrailSide { GtTrailUse.useOf(it) }) { k, v -> newMap[k] = v }
        } catch (e: Exception) {
            w.write("CERTVALUES\tTRAILUSE-NEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return
        }
        w.write("# CERTVALUES android table=${TrailClassifier.CATEGORIES.size} shared table=${GtTrailUse.USE_OF.size}\n")
        // ⚠ the table SIZES are reported, not asserted equal here: a size difference with every
        // shared key agreeing would mean Android has a key the shared table lacks, and the keys
        // below are what names it.
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k]
            val n = newMap[k]
            val why = CertValues.EXPECTED_DIFF[k]
            if (o != null && o == n) {
                same++; w.write("CERTVALUES\t$k\tSAME\t${esc(o)}\n")
                if (why != null) w.write("# CERTVALUES-NOTE\tEXPECTED_DIFF[$k] is STALE: OLD and NEW agree ($why)\n")
            } else if (why != null) {
                expected++
                w.write("CERTVALUES\t$k\tEXPECTED\t${esc(o.toString())} | ${esc(n.toString())}  [$why]\n")
                w.write("CERTVALUES\t$k|newonly\tSAME\t${esc(n.toString())}\n")
            } else {
                diff++; w.write("CERTVALUES\t$k\tDIFF\t${esc(o.toString())} | ${esc(n.toString())}\n")
            }
        }

        // ------------------------------------------------- QUEUEROW: the Droid's JSONObject row
        // ⚠ ONE leg by design. Android keeps org.json rows ON PURPOSE (JsonRow wraps "the same
        // object, not a copy" so the import record sees the queue's writes), so there is no NEW
        // Droid side to compare against. The second leg is the iPad's map row, in the Mac test.
        try {
            CertValues.runRow(JsonRowCert(org.json.JSONObject())) { k, v ->
                same++; w.write("CERTVALUES\t$k\tSAME\t${esc(v)}\n")
            }
        } catch (e: Exception) {
            w.write("CERTVALUES\tQUEUEROW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return
        }

        // ----------------------------------------------------------- RANDOM: properties, not values
        try {
            CertValues.runRandom(AndroidRandom) { k, v ->
                if (v == "true") { same++; w.write("CERTVALUES\t$k\tSAME\t$v\n") }
                else { diff++; w.write("CERTVALUES\t$k\tDIFF\t$v (a property that must hold on every platform)\n") }
            }
        } catch (e: Exception) {
            w.write("CERTVALUES\tRANDOM-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return
        }

        // Fred 11-16 08:38: the cases the Droid ACTUALLY used, printed, so a later difference can
        // never be blamed on the two sides having read different input.
        w.write("# CERTVALUES keys-used begin\n")
        for (k in CertValues.TRAIL_KEYS) w.write("# KEY\tTRAILUSE\t${CertValues.esc(k)}\n")
        w.write("# CERTVALUES keys-used end\n")
        w.write("# CERTVALUES done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
        log.i { "GTCERT certvalues done: $same SAME, $diff DIFF, $expected EXPECTED" }
    }
}
