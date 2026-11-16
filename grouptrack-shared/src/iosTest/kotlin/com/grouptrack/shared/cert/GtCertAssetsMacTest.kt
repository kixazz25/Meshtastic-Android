package com.grouptrack.shared.cert

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CERTASSETS-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * seam 2's ASSET service on the iPad simulator -- leg 3 against the Droid's leg 2.
 *
 * ⚠ WHAT THIS DOES AND DOES NOT PROVE, stated rather than implied.
 *   PROVES   GtOrgJson parses all 13 shipped assets on iOS to the IDENTICAL leaf set the Droid got.
 *            That is the real risk: GtHelpLinks records that org.json is lenient and GtOrgJson is
 *            strict, and a rejected asset falls back to an EMPTY table with nothing thrown.
 *   DOES NOT IosImportPlatform.readAsset's NSBundle lookup. This test reads the assets from the
 *            Mac's cert directory (the script copies them there), exactly as GtCertLogMacTest reads
 *            its corpus. NSBundle.mainBundle in a test binary is the TEST bundle, not an app bundle,
 *            so the bundle hop can only be proven by a real iPad app. Recorded as untested, not
 *            quietly skipped.
 *
 * Reading from a file rather than the bundle is not a weakening: the bytes are the same bytes the
 * Droid read -- the script copies them out of app/src/main/assets -- so the PARSER is under test on
 * identical input, which is the comparison that matters.
 */
class GtCertAssetsMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun certassets() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt_certassets_droid.txt".toPath()
        if (!fs.exists(rp)) { println("CERTASSETS mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }

        val droid = LinkedHashMap<String, String>()
        var droidDiff = 0
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("CERTASSETS\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size != 4) continue
                if (p[2] == "DIFF") droidDiff++
                if (p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- org.json and GtOrgJson must agree on the Droid first")
        assertEquals(0, droidDiff,
            "the Droid already reports $droidDiff DIFF (org.json vs GtOrgJson, or the socket's bytes) -- fix that first")

        val dir = "$outDir/certassets"
        var missing = 0
        val mine = LinkedHashMap<String, String>()
        val failures = ArrayList<String>()
        for (name in CertAssets.ASSETS) {
            val f = "$dir/$name".toPath()
            if (!fs.exists(f)) { missing++; println("CERTASSETS mac: MISSING $f"); continue }
            val text = fs.read(f) { readUtf8() }
            val r = CertAssets.tryFlatten(text)
            if (r.second != null) {
                // ⛔ a parse failure HERE, with the Droid having parsed the same bytes, IS the recorded
                // divergence -- an asset Android tolerates and the iPad rejects.
                failures.add("$name: GtOrgJson FAILED on iOS: ${r.second}")
                continue
            }
            CertAssets.run(name, r.first) { k, v -> mine[k] = v }
        }
        assertEquals(0, missing, "$missing asset(s) were not copied to $dir -- the harness step did not run")

        var same = 0
        val real = ArrayList<String>()
        for ((k, v) in mine) {
            val d = droid[k]
            if (d == v) { same++; continue }
            real.add("$k  droid=$d  mac=$v")
        }
        // the Droid emits per-asset socket-bytes and parse keys the iPad has no equivalent for
        // ⚠ |accessor-bytes joins the Droid-only set: GtAssetsAndroid is Android code and the iPad
        // has no equivalent, so comparing it would put 13 keys in `extra` and fail a passing cert --
        // the mistake made twice already today (the refusal keys at 10:47, the paths accessor at 11:50).
        val droidOnly = droid.keys.filter {
            it.endsWith("|socket-bytes") || it.endsWith("|parse") || it.endsWith("|accessor-bytes")
        }.toSet()
        val extra = droid.keys - mine.keys - droidOnly

        for (s in failures) println("CERTASSETS mac: PARSE FAILED $s")
        for (s in real) println("CERTASSETS mac: DIFF $s")
        if (extra.isNotEmpty()) println("CERTASSETS mac: DIFF the Droid has keys the iPad did not run: $extra")

        val summary = "CERTASSETS mac: $same SAME, ${real.size + extra.size} DIFF, ${failures.size} PARSE-FAILED, " +
            "${CertAssets.ASSETS.size} assets, NSBundle hop NOT covered -- done"
        println(summary)
        fs.write("$outDir/gt_certassets_ipad.txt".toPath()) {
            writeUtf8((listOf("# " + summary) + mine.map { "CERTASSETS\t${it.key}\tIPAD\t${it.value}" } + real + failures)
                .joinToString("\n") + "\n")
        }
        assertEquals(0, real.size + extra.size + failures.size,
            "the iPad's parse differs from the Droid's -- see gt_certassets_ipad.txt")
    }
}
