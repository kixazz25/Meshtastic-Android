package com.grouptrack.shared.cert

import com.grouptrack.shared.platform.IosImportPaths
import com.grouptrack.shared.platform.IosImportPlatform
import com.grouptrack.shared.platform.iosStorage
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CERTPATHS-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * seam 2's ENVIRONMENT half on the iPad simulator -- leg 3 against the Droid's leg 2.
 *
 * THE ASSERTION (Fred 11-16 09:35): the iPad uses "the default android behavior paths to this app",
 * i.e. Android's own layout with iOS's container directories substituted. So every tail the APP
 * addresses must match the Droid's, and only the OS-owned prefix may differ.
 *
 * ⚠ THIS IS NOT GtStorageMacTest, AND IT DOES NOT REPLACE IT. That test (STEP17G2A) hardcodes a
 * DROID-SHAPED base and proves the shared JOINING is identical -- its own comment says "The iPad's
 * own base (Documents) is step 18". This is step 18: the real iPad base, through IosStorageBase.
 * Both tests stay; breaking a green cert to tidy an overlap is not a trade worth making.
 *
 * ⛔ FACT keys are NOT compared. The container absolutes, and cacheDir/filesDir, cannot match --
 * Android names them "cache" and "files" inside the app's data directory, iOS names them
 * "Library/Caches" and "Library". Comparing those would fail on a CORRECT implementation. They are
 * printed instead, so a human can read what each platform actually resolved.
 */
class GtCertPathsMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun certpaths() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt_certpaths_droid.txt".toPath()
        if (!fs.exists(rp)) { println("CERTPATHS mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }

        val droid = LinkedHashMap<String, String>()
        val droidFacts = LinkedHashMap<String, String>()
        var droidDiff = 0
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("CERTPATHS\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size != 4) continue
                when (p[2]) {
                    "DIFF" -> droidDiff++
                    "SAME" -> droid[p[1]] = p[3]
                    "FACT" -> droidFacts[p[1]] = p[3]
                }
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")
        assertEquals(0, droidDiff, "the Droid's own OLD vs NEW already differs in $droidDiff case(s) -- fix that first")

        // ---- leg 3: the SAME shared code, on the iPad's real base ---------------------------
        val mine = LinkedHashMap<String, String>()
        val st = iosStorage()
        CertPaths.run(CertPaths.Side(st, fs)) { k, v -> mine[k] = v }

        // ---- the EXPORT leg on the iPad: Documents/Download, through the shared socket --------
        // ⛔ PRE-CLEAN, or the name diverges on the SECOND run. IosImportPlatform mirrors Android's
        // pre-29 collision rule ("name_2.gpx"), so a leftover probe from an earlier run would make
        // the iPad return a different name than the Droid and the comparison would fail for a reason
        // that is not a defect. The Droid side deletes its MediaStore copy for the same reason.
        val paths = IosImportPaths(st)
        val dlDir = paths.groupTrackRoot().parent?.let { it / "Download" }
        if (dlDir != null) runCatching { fs.delete(dlDir / CertPaths.EXPORT_NAME) }

        CertPaths.runExport(IosImportPlatform(paths, fs), fs, st.cacheDir() / "certpaths_export") { k, v ->
            mine[k] = v
        }

        // did it LAND, and are the bytes the payload's? On iOS this is a plain file read -- on the
        // Droid the same question needs a MediaStore query, which is why the check lives per driver.
        if (dlDir != null) {
            val f = dlDir / CertPaths.EXPORT_NAME
            val exists = fs.exists(f)
            mine["EXPORT-D|download|file-exists"] = exists.toString()
            if (exists) {
                val back = fs.read(f) { readUtf8() }
                var h = 0L
                for (c in back) h = h * 31 + c.code
                mine["EXPORT-D|download|bytes-match"] = (h == CertPaths.payloadHash()).toString()
                mine["PATHS-FACT|download|absolute"] = f.toString()
                runCatching { fs.delete(f) }
            } else {
                mine["EXPORT-D|download|bytes-match"] = "false (no file)"
                mine["PATHS-FACT|download|absolute"] = f.toString() + " (absent)"
            }
        } else {
            mine["EXPORT-D|download|file-exists"] = "false (no parent for the GroupTrack root)"
            mine["EXPORT-D|download|bytes-match"] = "false (no parent)"
        }

        // ⛔ THE DROID RECORDS A REFUSAL AS TWO LINES, and this must mirror that or a PASSING run
        // fails. GtCertPaths writes, for each of dir("./x") / dir("../x") / dir("a/../b"):
        //     PATHS|refused|<n>           EXPECTED   OLD ALLOWED:<path> | NEW REFUSED
        //     PATHS|refused|<n>|newonly   SAME       REFUSED
        // because OLD (java.io.File) keeps "."/".." parts while NEW (okio) refuses them -- a rule
        // added deliberately, since the two would otherwise name different places. EXPECTED_DIFF is
        // empty, so keying only off it looked up the BARE key, found nothing, and reported 3 DIFFs
        // plus 3 "keys the iPad did not run". Caught on the first certpaths run, 11-16 10:47.
        fun lookup(k: String) = when {
            CertPaths.EXPECTED_DIFF.containsKey(k) -> "$k|newonly"
            k.startsWith("PATHS|refused|") -> "$k|newonly"
            else -> k
        }

        var same = 0
        val real = ArrayList<String>()
        val trips = ArrayList<String>()
        for ((k, v) in mine) {
            if (CertPaths.isFact(k)) continue                      // reported below, never compared
            // ⚠ a round trip must succeed on its OWN merits first: "identical=false" on BOTH
            // platforms would otherwise compare as agreement and pass -- the TileCodec failure mode
            // from 11-14, where two implementations agreed because both skipped the same step.
            if (k.startsWith("PATHS|roundtrip|") && !v.contains("identical=true")) trips.add("$k  mac=$v")
            // ⚠ EXPORT-D is the iPad's OWN verification (did the file land in Documents/Download).
            // The Droid's equivalent is EXPORT-A/B, which asks MediaStore -- a different question with
            // a different key. So EXPORT-D is asserted on its own merits here and never compared.
            if (k.startsWith("EXPORT-D|")) {
                if (!v.startsWith("true")) trips.add("$k  mac=$v")
                continue
            }
            val d = droid[lookup(k)]
            if (d == v) { same++; continue }
            real.add("$k  droid=$d  mac=$v")
        }
        val extra = droid.keys - mine.keys.map { lookup(it) }.toSet()

        println("CERTPATHS mac: --- the two containers, reported not compared ---")
        for ((k, v) in droidFacts) println("CERTPATHS mac: FACT droid  $k = $v")
        for ((k, v) in mine) if (CertPaths.isFact(k)) println("CERTPATHS mac: FACT ipad   $k = $v")
        for (s in trips) println("CERTPATHS mac: ROUND TRIP FAILED $s")
        for (s in real) println("CERTPATHS mac: DIFF $s")
        if (extra.isNotEmpty()) println("CERTPATHS mac: DIFF the Droid has keys the iPad did not run: $extra")

        val summary = "CERTPATHS mac: $same SAME, ${real.size + extra.size} DIFF, ${trips.size} ROUND-TRIP-FAILED, " +
            "${mine.size} keys (${mine.keys.count { CertPaths.isFact(it) }} FACT), cross-device COMPARED -- done"
        println(summary)
        fs.write("$outDir/gt_certpaths_ipad.txt".toPath()) {
            writeUtf8((listOf("# " + summary) + mine.map { "CERTPATHS\t${it.key}\t${if (CertPaths.isFact(it.key)) "FACT" else "IPAD"}\t${it.value}" } + real + trips)
                .joinToString("\n") + "\n")
        }
        assertEquals(0, real.size + extra.size + trips.size,
            "the iPad's paths differ from the Droid's -- see gt_certpaths_ipad.txt")
    }
}
