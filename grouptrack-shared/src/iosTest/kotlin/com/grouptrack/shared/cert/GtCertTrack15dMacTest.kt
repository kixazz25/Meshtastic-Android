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
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtLocalTime
import com.grouptrack.shared.track.GtTracks
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * STEP15D-2026-11-09 (2.8 step 15d) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): track add / sync /
 * properties on the iPad simulator. Runs the SAME runner as the Droid (Cert15d.runScript with NewSide, then Cert15d.dumpTables) on a COPY
 * of the SAME input the Droid used:
 *     $dataDir/grouptrack_spatial.db + grouptrack_data.db      (_data/live_2026-11-08 -- gt_cert_v20 pushes these to the Droid)
 *     $tracksIn/<every file>                                    (_data/cert15d_input/my_tracks -- pulled from Droid 1, pushed back by v20)
 * copied to $outDir/t15d_work/ first (the originals are only read). Writes $outDir/GroupTrack_T15D_mac_<time>.txt ending
 * "# T15D mac done: …", prints "GTT15D mac: …", and fails on any DIFF. No data -> "GTT15D mac: ... skipped" and return.
 * VALUE FOR VALUE with the Droid's NEW (its report $outDir/gt15d_droid.txt, copied by gt_cert_v20):
 *  1. the iPad's own run must hold: no case throws (the EXPECTED one aside); sync2 adds nothing (its aliases are 2.7e's D8, compared);
 *  2. its input fingerprints (input|db|…, input|tracks…: size + sha256 of every input file, hashed ONCE, streamed) must EQUAL the Droid
 *     report's -- otherwise "cross-device NOT COMPARED" and DIFF;
 *  3. every value the iPad writes (results, listed rows, table fingerprints) must equal the Droid NEW's.
 * ⚠ THE LISTING ORDER IS THE DROID'S. The sync's order decides which of two same-geometry files becomes the track (INVENTORY T8). The
 *   iPad app's own order will be SORTED (owner decision 11); this test instead hands the shared code the order the DROID's NEW saw
 *   (the report's sync<N>|listing lines, matched by the set of names) -- it certifies that the CODE behaves the same on the same order,
 *   not the iPad's listing policy. A directory whose names match no Droid listing gets the sorted order and is counted ("replay misses").
 * A differing trail, track or track_properties row is NAMED ("ROW DIFFERS ... investigate (libm?), not tolerated" -- Fred's 15c Q3 rule).
 */
class GtCertTrack15dMacTest {
    private val spike = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data"
    private val dataDir = "$spike/live_2026-11-08"
    private val tracksIn = "$spike/cert15d_input/my_tracks"
    private val outDir = "$spike/cert"

    private class TestEnv(private val fs: FileSystem, private val tracks: Path, private val root: Path, private val droidListings: List<List<String>>) : GtImportEnv {
        var changes = 0
        var replayMisses = 0
        private var n = 0
        override val clock = object : GtClock {
            override fun nowInstant(): String = "2026-11-09T12:00:00Z"
            override fun localNow(): GtLocalTime = GtLocalTime(2026, 11, 9, 12, 0, 0)
            override fun epochMs(): Long = 1_794_225_600_000L   // 2026-11-09T12:00:00Z (not read by 15d)
        }
        override val ids = object : GtIds {
            override fun newId(): String { n++; return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0') }
        }
        override val files = object : GtFiles {
            override val fs: FileSystem = this@TestEnv.fs
            override fun setLastModified(path: Path, epochMs: Long): Boolean = throw UnsupportedOperationException("not used by 15d")
            override fun lastModified(path: Path): Long = throw UnsupportedOperationException("not used by 15d")
            /** the Droid's order for this exact set of names (see the header); sorted when none matches */
            override fun list(dir: Path): List<Path> {
                val real = fs.list(dir)
                val names = real.map { it.name }.sorted()
                val order = droidListings.firstOrNull { it.sorted() == names }
                if (order == null) { replayMisses++; return real }
                return order.map { dir / it }
            }
        }
        override val paths = object : GtImportPaths {
            override fun groupTrackRoot(): Path = root
            override fun tracksDir(): Path { fs.createDirectories(tracks); return tracks }
            override fun dir(name: String): Path = throw UnsupportedOperationException("not used by 15d")
            override fun cacheDir(): Path = throw UnsupportedOperationException("not used by 15d")
            override fun filesDir(): Path = throw UnsupportedOperationException("not used by 15d")
        }
        override val http = object : GtHttp {
            override fun get(url: String, readTimeoutMs: Int): String? = throw UnsupportedOperationException("not used by 15d")
        }
        /** TrailClassifier.useOf (2.7e, app): USE_OF["rider"] = MOTORIZED. Only "rider" is asked (the rider scan on a track add). */
        override val trailClass = object : GtTrailClass {
            override fun useOf(category: String): String =
                if (category == "rider") "MOTORIZED" else throw UnsupportedOperationException("15d asks only for 'rider', not '$category'")
        }
        override val platform = object : GtImportPlatform {
            override fun readAsset(name: String): String = throw UnsupportedOperationException("not used by 15d")
            override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int = throw UnsupportedOperationException("not used by 15d")
            override fun saveToDownloads(file: Path): String = throw UnsupportedOperationException("not used by 15d")
            override fun onTrailsChanged() { changes++ }
        }
    }

    private class Flight : GtTracks.Flight {
        var s = false; var b = false
        override fun beginSync(): Boolean { if (s) return false; s = true; return true }
        override fun endSync() { s = false }
        override fun beginBackfill(): Boolean { if (b) return false; b = true; return true }
        override fun endBackfill() { b = false }
    }

    private fun sha256(fs: FileSystem, p: Path, say: (String) -> Unit): String {
        val hs = HashingSink.sha256(blackholeSink()); val out = hs.buffer(); var done = 0L; var chunks = 0
        fs.read(p) { val buf = okio.Buffer()
            while (true) { val n = read(buf, 1L shl 20); if (n < 0) break; done += n; out.write(buf, n); if (++chunks % 100 == 0) say("hashing ${p.name}: ${done shr 20} MB") } }
        out.flush()
        return hs.hash.hex()
    }

    @Test
    fun trackSync() {
        val fs = FileSystem.SYSTEM
        val t0 = TimeSource.Monotonic.markNow()
        val srcS = "$dataDir/grouptrack_spatial.db".toPath(); val srcD = "$dataDir/grouptrack_data.db".toPath(); val srcT = tracksIn.toPath()
        if (!fs.exists(srcS) || !fs.exists(srcD) || !fs.exists(srcT)) { println("GTT15D mac: no data ($dataDir, $tracksIn) -- skipped"); return }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmm"; val stamp = f.stringFromDate(NSDate())
        val out = ArrayList<String>()
        fun put(s: String) { out.add(s); if (s.startsWith("#") || s.contains("\tDIFF\t")) println("GTT15D mac: $s") }
        fun say(s: String) = println("GTT15D mac: $s")

        // ---- the input, hashed ONCE ----
        val inputFp = LinkedHashMap<String, String>()
        for (p in listOf(srcS, srcD)) inputFp["input|db|${p.name}"] = "len=${fs.metadata(p).size};sha256=${sha256(fs, p, ::say)}"
        val trFiles = fs.list(srcT).filter { fs.metadataOrNull(it)?.isRegularFile == true }.sortedBy { it.name }
        val trLines = trFiles.map { p -> "${p.name}\tsize=${fs.metadata(p).size};sha256=${sha256(fs, p, ::say)}" }
        inputFp["input|tracks"] = "n=${trFiles.size};sha=" + Cert15d.sha(trLines.joinToString("\n"))
        for (l in trLines) inputFp["input|tracks|" + l.substringBefore('\t')] = Cert15d.esc(l.substringAfter('\t'))

        // ---- the Droid's report: its NEW values (and its listings, for the replay) ----
        val report = "$outDir/gt15d_droid.txt".toPath()
        val droid = LinkedHashMap<String, String>()
        if (fs.exists(report)) fs.read(report) {
            var pending: String? = null   // CODE RULE 1: null = the previous line was not a DIFF / EXPECTED head
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("T15D\t")) {
                    val p = l.split('\t', limit = 4); if (p.size < 4) continue
                    droid[p[1]] = p[3]; pending = if (p[2] == "SAME") null else p[1]
                } else if (l.startsWith("  NEW\t") && pending != null) { droid[pending!!] = l.removePrefix("  NEW\t"); pending = null }
            }
        }
        val listings = droid.filterKeys { Regex("""sync\d+\|listing""").matches(it) }.values.map { v -> if (v.isEmpty()) emptyList() else v.split('/') }

        // ---- fresh COPIES (the originals are only read) ----
        val work = "$outDir/t15d_work".toPath()
        if (fs.exists(work)) fs.deleteRecursively(work)
        val tracks = work / "my_tracks"; val root = work / "GroupTrack"; val staging = work / "staging"
        for (d in listOf(work, tracks, root, staging)) fs.createDirectories(d)
        for (name in listOf("grouptrack_spatial.db", "grouptrack_data.db")) for (sfx in listOf("", "-wal", "-journal")) {
            val s = "$dataDir/$name$sfx".toPath(); if (fs.exists(s)) fs.copy(s, work / "$name$sfx")
        }
        for (p in trFiles) fs.copy(p, tracks / p.name)
        put("# T15D mac $stamp data=$dataDir tracks=$tracksIn (copied to $work); Droid listings for the replay: ${listings.size}")

        val conn = gtOpenDatabase((work / "grouptrack_spatial.db").toString(), false)
        val dconn = gtOpenDatabase((work / "grouptrack_data.db").toString(), false)
        val mine = LinkedHashMap<String, String>()
        var selfBad = 0
        val env = TestEnv(fs, tracks, root, listings)
        try {
            val inp = Cert15d.inputs(conn, dconn, ::say)
            put("# T15D mac: before the run: ${inp.trailIds.size} trails, ${inp.trackIds.size} tracks, ${inp.aliasIds.size} aliases, ${inp.waypointIds.size} waypoints")
            val writer = GtWrite(conn, dconn, { env.ids.newId() }, { env.clock.nowInstant() })
            val side = Cert15d.NewSide(GtTracks(conn, dconn, writer, env, Flight()), env.files) { env.changes }
            Cert15d.runScript(side, Cert15d.Ctx(fs, tracks, root, staging, conn), inp, { k, v ->
                mine[k] = Cert15d.esc(v)
                if (v.startsWith("EXCEPTION") && !k.contains("(EXPECTED")) { selfBad++; put("SELF\t$k\tDIFF\tNEW threw on the iPad: $v") }
                if (k == "sync2|invariant" && v != "0 added") { selfBad++; put("SELF\t$k\tDIFF\t$v") }
            }, ::say)
            Cert15d.dumpTables(conn, dconn, inp, { k, v, listed -> if (listed) mine["tables|$k"] = Cert15d.esc(v) }, ::say)
        } finally { conn.close(); dconn.close() }
        for ((k, v) in mine) if (k.endsWith("|result") || k.endsWith("|invariant") || k.startsWith("tables|s9|")) put("MAC\t$k\t$v")
        put("# T15D mac: listing replay misses ${env.replayMisses}")

        var same = 0; var bad = selfBad; val expected = 0; var rows = 0
        fun named(k: String) = if (k.startsWith("tables|s1|") || k.startsWith("tables|s3|") || k.startsWith("tables|s5|") || k.startsWith("tables|s6|")) {
            rows++; "\tROW DIFFERS (trail / track / track_properties) -- investigate (libm?), not tolerated" } else ""
        val inputsDiffer = ArrayList<String>()
        for ((k, v) in inputFp) if (droid[k] != v) inputsDiffer.add(k)
        for (k in droid.keys) if (k.startsWith("input|") && k !in inputFp) inputsDiffer.add(k)
        val cross = droid.isNotEmpty() && inputsDiffer.isEmpty()
        if (droid.isEmpty()) { bad++; put("T15D\tdroid-report\tDIFF\tno Droid report at $report -- run gt_cert_v20 track15d") }
        for (k in inputsDiffer) { bad++; put("T15D\t$k\tDIFF\tthe iPad's input differs from the Droid's: droid=${droid[k]?.take(80)} mac=${inputFp[k]?.take(80)}") }
        if (droid.isNotEmpty()) {
            for ((k, v) in mine) {
                val d = droid[k]
                when {
                    d == v -> same++
                    d == null && k.startsWith("tables|") && !k.startsWith("tables|s9|") && !k.contains("|#cols:") -> { bad++; put("T15D\t$k\tDIFF\tonly on the iPad: $v" + named(k)) }
                    else -> { bad++; put("T15D\t$k\tDIFF\tdroid=${d?.take(300)}\tmac=${v.take(300)}" + named(k)) }
                }
            }
            for (k in droid.keys) if (!k.startsWith("input|") && !k.contains(" (only OLD)") && k !in mine &&
                (k.startsWith("tables|s9|") || !k.startsWith("tables|") || k.startsWith("tables|s1|") || k.startsWith("tables|s3|") ||
                    k.startsWith("tables|s4|") || k.startsWith("tables|s5|") || k.startsWith("tables|s6|"))) {
                bad++; put("T15D\t$k\tDIFF\tonly on the Droid" + named(k))
            }
        }
        if (rows > 0) put("# T15D mac: $rows ROW(S) DIFFER between the iPad and the Droid -- each one is to be investigated")
        val secs = t0.elapsedNow().inWholeSeconds
        put("# T15D mac done: $same SAME, $bad DIFF, $expected EXPECTED, cross-device ${if (cross) "COMPARED" else "NOT COMPARED"}, rows differing $rows, replay misses ${env.replayMisses} in $secs s")
        val file = "$outDir/GroupTrack_T15D_mac_$stamp.txt".toPath()
        fs.write(file) { writeUtf8(out.joinToString("\n") + "\n") }
        println("GTT15D mac: report $file")
        assertEquals(0, bad, "T15D: the iPad's NEW differs (see $file)")
    }
}
