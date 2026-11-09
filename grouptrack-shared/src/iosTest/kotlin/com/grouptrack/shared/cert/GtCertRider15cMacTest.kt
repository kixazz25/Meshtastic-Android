package com.grouptrack.shared.cert

import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtHttp
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.imports.GtTrailClass
import com.grouptrack.shared.rider.GtRiderTrails
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtLocalTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * STEP15C-2026-11-09 (2.8 step 15c) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): rider trails on the iPad
 * simulator. Runs the SAME runner as the Droid (Cert15c.runScript with NewSide, then Cert15c.dumpTables) on a COPY of the Mac's copy of
 * Droid 1's databases ($dataDir: copied to $outDir/r15c_work/ first -- the original is never opened). Writes
 * $outDir/GroupTrack_R15C_mac_<time>.txt ending "# R15C mac done: …", prints "GTR15C mac: …", and fails on any DIFF.
 *
 * STEP15C-B (Fred 11-09, Q4): THE SAME INPUT AS THE DROID. gt_cert_v19b pushes these same two files to the Droid, and the Droid's
 * run (GtCert15c) copies them for OLD and NEW -- so the comparison is VALUE FOR VALUE:
 *  1. the iPad's own run must hold: no case throws; scanAll2 is a no-op (0 added, 0 removed);
 *  2. its input fingerprints (input|s9|fp:<table>, input|..#cols) must EQUAL the Droid report's ($outDir/gt15c_droid.txt, copied by
 *     gt_cert_v19b) -- each differing one is a DIFF (then the two did not run on the same data);
 *  3. every value the iPad writes (results, listed rows, table fingerprints) must equal the Droid NEW's.
 *  No Droid report -> one DIFF ("no Droid report"), never a pass.
 *  Q3 (Fred 11-09): a differing trail row (tables|s1|… / tables|s3|…) -- e.g. a one-bit libm difference that moved an anchor and so
 *  changed a rider trail's WKT and geom_hash -- is NAMED in the report ("TRAIL ROW DIFFERS ... investigate (Q3)") and counted
 *  separately; it is never tolerated.
 * No data -> "GTR15C mac: ... skipped" and return (as the other Mac tests).
 */
class GtCertRider15cMacTest {
    private val defaultDataDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/live_2026-11-08"
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    /** The env the rider code uses on the iPad in this test: fixed clock, counted ids, TrailClassifier's answer for "rider". */
    private class TestEnv : GtImportEnv {
        var changes = 0
        private var n = 0
        override val clock = object : GtClock {
            override fun nowInstant(): String = "2026-11-09T12:00:00Z"
            override fun localNow(): GtLocalTime = GtLocalTime(2026, 11, 9, 12, 0, 0)
            override fun epochMs(): Long = 1_794_225_600_000L   // 2026-11-09T12:00:00Z (not read by 15c)
        }
        override val ids = object : GtIds {
            override fun newId(): String { n++; return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0') }
        }
        override val files = object : GtFiles {
            override val fs: FileSystem = FileSystem.SYSTEM
            override fun setLastModified(path: Path, epochMs: Long): Boolean = throw UnsupportedOperationException("not used by 15c")
            override fun lastModified(path: Path): Long = throw UnsupportedOperationException("not used by 15c")
            override fun list(dir: Path): List<Path> = throw UnsupportedOperationException("not used by 15c")
        }
        override val paths = object : GtImportPaths {
            override fun groupTrackRoot(): Path = throw UnsupportedOperationException("not used by 15c")
            override fun tracksDir(): Path = throw UnsupportedOperationException("not used by 15c")
            override fun dir(name: String): Path = throw UnsupportedOperationException("not used by 15c")
            override fun cacheDir(): Path = throw UnsupportedOperationException("not used by 15c")
            override fun filesDir(): Path = throw UnsupportedOperationException("not used by 15c")
        }
        override val http = object : GtHttp {
            override fun get(url: String, readTimeoutMs: Int): String? = throw UnsupportedOperationException("not used by 15c")
        }
        /** TrailClassifier.useOf (2.7e, app): USE_OF["rider"] = MOTORIZED (RIDERTRAILS-2026-09-07). Only "rider" is asked by 15c. */
        override val trailClass = object : GtTrailClass {
            override fun useOf(category: String): String =
                if (category == "rider") "MOTORIZED" else throw UnsupportedOperationException("15c asks only for 'rider', not '$category'")
        }
        override val platform = object : GtImportPlatform {
            override fun readAsset(name: String): String = throw UnsupportedOperationException("not used by 15c")
            override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int = throw UnsupportedOperationException("not used by 15c")
            override fun saveToDownloads(file: Path): String = throw UnsupportedOperationException("not used by 15c")
            override fun onTrailsChanged() { changes++ }
        }
    }

    @Test
    fun riderTrails() {
        val fs = FileSystem.SYSTEM
        val t0 = TimeSource.Monotonic.markNow()
        val dataDir = defaultDataDir   // STEP15C-B: the Droid's input is these same files (gt_cert_v19b pushes them)
        val srcS = "$dataDir/grouptrack_spatial.db".toPath(); val srcD = "$dataDir/grouptrack_data.db".toPath()
        if (!fs.exists(srcS) || !fs.exists(srcD)) { println("GTR15C mac: no data at $dataDir -- skipped"); return }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmm"; val stamp = f.stringFromDate(NSDate())
        val out = ArrayList<String>()
        fun put(s: String) { out.add(s); if (s.startsWith("#") || s.contains("\tDIFF\t")) println("GTR15C mac: $s") }

        // ---- a fresh COPY (the original is only read, by the copy) ----
        val work = "$outDir/r15c_work".toPath()
        if (fs.exists(work)) fs.deleteRecursively(work)
        fs.createDirectories(work)
        for (name in listOf("grouptrack_spatial.db", "grouptrack_data.db")) for (sfx in listOf("", "-wal", "-journal")) {
            val s = "$dataDir/$name$sfx".toPath(); if (fs.exists(s)) fs.copy(s, work / "$name$sfx")
        }
        put("# R15C mac $stamp data=$dataDir (copied to $work)")

        val conn = gtOpenDatabase((work / "grouptrack_spatial.db").toString(), false)
        val dconn = gtOpenDatabase((work / "grouptrack_data.db").toString(), false)
        val mine = LinkedHashMap<String, String>()     // key -> esc(value), the keys the Droid's report also carries
        val inputFp = LinkedHashMap<String, String>()
        var selfBad = 0
        try {
            val inp = Cert15c.inputs(conn, dconn)
            put("# R15C mac: before the run: ${inp.trailIds.size} trails, ${inp.aliasIds.size} trail aliases, ${inp.trackHashes.size} tracks")
            Cert15c.dumpTables(conn, dconn, inp) { k, v, listed -> if (listed && (k.contains("|#cols:") || k.startsWith("s9|"))) inputFp["input|$k"] = Cert15c.esc(v) }
            val env = TestEnv()
            val writer = GtWrite(conn, dconn, { env.ids.newId() }, { env.clock.nowInstant() })
            val side = Cert15c.NewSide(GtRiderTrails(conn, dconn, writer, env)) { env.changes }
            Cert15c.runScript(side, inp, { k, v ->
                mine[k] = Cert15c.esc(v)
                if (v.startsWith("EXCEPTION") && !k.contains("(EXPECTED")) { selfBad++; put("SELF\t$k\tDIFF\tNEW threw on the iPad: $v") }
                if (k == "scanAll2|invariant" && v != "0 added, 0 removed") { selfBad++; put("SELF\t$k\tDIFF\t$v") }
            }, { p -> println("GTR15C mac: $p") })
            Cert15c.dumpTables(conn, dconn, inp) { k, v, listed -> if (listed) mine["tables|$k"] = Cert15c.esc(v) }
        } finally { conn.close(); dconn.close() }
        for ((k, v) in mine) if (k.endsWith("|result") || k.endsWith("|invariant") || k.startsWith("tables|s9|")) put("MAC\t$k\t$v")

        // ---- the Droid's NEW values, when the inputs are the same ----
        val report = "$outDir/gt15c_droid.txt".toPath()
        val droid = LinkedHashMap<String, String>()
        if (fs.exists(report)) fs.read(report) {
            var pending: String? = null   // CODE RULE 1: null = the previous line was not a DIFF / EXPECTED head
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("R15C\t")) {
                    val p = l.split('\t', limit = 4); if (p.size < 4) continue
                    droid[p[1]] = p[3]; pending = if (p[2] == "SAME") null else p[1]
                } else if (l.startsWith("  NEW\t") && pending != null) { droid[pending!!] = l.removePrefix("  NEW\t"); pending = null }
            }
        }
        var same = 0; var bad = selfBad; var expected = 0; var trailRows = 0
        // STEP15C-B (Q3): a differing trail row is named, counted, never tolerated
        fun q3(k: String) = if (k.startsWith("tables|s1|") || k.startsWith("tables|s3|")) { trailRows++; "\tTRAIL ROW DIFFERS (WKT / geom_hash / columns) -- investigate (Q3), not tolerated" } else ""
        val inputsDiffer = ArrayList<String>()
        for ((k, v) in inputFp) if (droid[k] != v) inputsDiffer.add(k.removePrefix("input|"))
        val cross = droid.isNotEmpty() && inputsDiffer.isEmpty()
        if (droid.isEmpty()) { bad++; put("R15C\tdroid-report\tDIFF\tno Droid report at $report -- run gt_cert_v19b rider15c") }
        for (k in inputsDiffer) { bad++; put("R15C\tinput|$k\tDIFF\tthe iPad's input differs from the Droid's: droid=${droid["input|$k"]?.take(80)} mac=${inputFp["input|$k"]?.take(80)}") }
        if (droid.isNotEmpty()) {
            for ((k, v) in mine) {
                val d = droid[k]
                when {
                    d == v -> same++
                    d == null && k.startsWith("tables|") && !k.startsWith("tables|s9|") && !k.contains("|#cols:") -> { bad++; put("R15C\t$k\tDIFF\tonly on the iPad: $v" + q3(k)) }
                    else -> { bad++; put("R15C\t$k\tDIFF\tdroid=${d?.take(300)}\tmac=${v.take(300)}" + q3(k)) }
                }
            }
            for (k in droid.keys) if (!k.startsWith("input|") && !k.contains(" (only OLD)") && k !in mine && (k.startsWith("tables|s9|") || !k.startsWith("tables|") || k.startsWith("tables|s1|") || k.startsWith("tables|s3|"))) {
                bad++; put("R15C\t$k\tDIFF\tonly on the Droid" + q3(k))
            }
        }
        if (trailRows > 0) put("# R15C mac: $trailRows TRAIL ROW(S) DIFFER between the iPad and the Droid -- each one is to be investigated (Q3)")
        val secs = t0.elapsedNow().inWholeSeconds
        put("# R15C mac done: $same SAME, $bad DIFF, $expected EXPECTED, cross-device ${if (cross) "COMPARED" else "NOT COMPARED"}, trail rows differing $trailRows in $secs s")
        val file = "$outDir/GroupTrack_R15C_mac_$stamp.txt".toPath()
        fs.write(file) { writeUtf8(out.joinToString("\n") + "\n") }
        println("GTR15C mac: report $file")
        assertEquals(0, bad, "R15C: the iPad's NEW differs (see $file)")
    }
}
