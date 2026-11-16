package com.grouptrack.shared.cert

import com.grouptrack.shared.platform.IosQueueRow
import com.grouptrack.shared.platform.IosRandom
import com.grouptrack.shared.platform.IosTrailClass
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CERTVALUES-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * seam 2 BATCH 1 on the iPad simulator, VALUE FOR VALUE with the Droid's (gt_certvalues_droid.txt).
 *
 * ⚠ NOTHING IS REIMPLEMENTED HERE. The cases, the key form and the runners are CertValues' -- the
 * same common code the Droid ran. What differs is only which implementation is handed in:
 *   TRAILUSE   IosTrailClass -> GtTrailUse, the shared table. The Droid's SAME lines are where its
 *              OWN table and the shared table already agreed, so if Android and shared disagree
 *              this test fails before comparing anything -- the GtCertMBTiles17MacTest discipline.
 *   QUEUEROW   IosQueueRow, the map-backed row, against the Droid's org.json JSONObject row. TWO
 *              legs by design: Android keeps org.json ON PURPOSE (JsonRow wraps "the same object,
 *              not a copy"), so there is no third row to run and a missing NEW leg is correct.
 *   RANDOM     IosRandom (arc4random) against the Droid's SecureRandom -- PROPERTIES only. Every
 *              RANDOM-FACT line is a boolean that must read true on both platforms. ⛔ The drawn
 *              VALUES are never emitted by either side and must never be compared: two correct
 *              CSPRNGs agreeing on a value would mean one of them is broken.
 *
 * There is NO corpus file for this cert. CertLog needed one because 994 message skeletons cannot
 * live in source without their 1455 '$' characters being re-escaped; batch 1's 43 cases are a key
 * list and a scripted sequence, so they are code and both platforms run the identical code.
 */
class GtCertValuesMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun certvalues() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt_certvalues_droid.txt".toPath()
        if (!fs.exists(rp)) { println("CERTVALUES mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }

        val droid = LinkedHashMap<String, String>()
        var droidDiff = 0
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("CERTVALUES\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size != 4) continue
                if (p[2] == "DIFF") droidDiff++
                if (p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(),
            "the Droid report has no SAME lines -- Android's table and the shared table must agree on the Droid first")
        assertEquals(0, droidDiff,
            "the Droid already reports $droidDiff DIFF (its own table vs the shared table, or a failed random property) -- fix that first")

        // ---- the iPad legs, through the SAME shared runners -----------------------------------
        val mine = LinkedHashMap<String, String>()
        CertValues.runTrail(CertValues.trailSideOf(IosTrailClass())) { k, v -> mine[k] = v }
        CertValues.runRow(IosQueueRow()) { k, v -> mine[k] = v }
        CertValues.runRandom(IosRandom()) { k, v -> mine[k] = v }

        fun lookup(k: String) = if (CertValues.EXPECTED_DIFF.containsKey(k)) "$k|newonly" else k

        var same = 0
        val real = ArrayList<String>()
        val propFail = ArrayList<String>()
        for ((k, v) in mine) {
            // ⚠ a RANDOM-FACT must be true on its OWN merits first; a shared "false" on both
            // platforms would otherwise read as agreement and pass.
            if (k.startsWith("RANDOM-FACT") && v != "true") propFail.add("$k  mac=$v")
            val d = droid[lookup(k)]
            if (d == v) { same++; continue }
            real.add("$k  droid=$d  mac=$v")
        }
        val extra = droid.keys - mine.keys.map { lookup(it) }.toSet()
        for (s in propFail) println("CERTVALUES mac: PROPERTY FAILED $s")
        for (s in real) println("CERTVALUES mac: DIFF $s")
        if (extra.isNotEmpty()) println("CERTVALUES mac: DIFF the Droid has keys the iPad did not run: $extra")

        val trail = mine.keys.count { it.startsWith("TRAILUSE") }
        val row = mine.keys.count { it.startsWith("QUEUEROW") }
        val rnd = mine.keys.count { it.startsWith("RANDOM-FACT") }
        val summary = "CERTVALUES mac: $same SAME, ${real.size + extra.size} DIFF, ${propFail.size} PROPERTY-FAILED, " +
            "$trail trailuse + $row queuerow + $rnd random-fact = ${mine.size} cases, cross-device COMPARED -- done"
        println(summary)
        fs.write("$outDir/gt_certvalues_ipad.txt".toPath()) {
            writeUtf8((listOf("# " + summary) + mine.map { "CERTVALUES\t${it.key}\tIPAD\t${it.value}" } + real + propFail)
                .joinToString("\n") + "\n")
        }
        assertEquals(0, real.size + extra.size + propFail.size,
            "the iPad's values differ from the Droid's -- see gt_certvalues_ipad.txt")
    }
}
