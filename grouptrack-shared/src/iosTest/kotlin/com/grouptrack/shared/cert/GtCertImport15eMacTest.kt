package com.grouptrack.shared.cert

import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtGpxImport
import com.grouptrack.shared.imports.GtHttp
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.imports.GtTrailClass
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtLocalTime
import com.grouptrack.shared.track.GtTracks
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * STEP15E-2026-11-09 (2.8 step 15e) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the GPX import on the iPad
 * simulator. Runs the SAME runner as the Droid (Cert15e.runScript with NewSide, then Cert15e.dumpTables) on a COPY of the SAME input:
 *     $dataDir/grouptrack_spatial.db + grouptrack_data.db      (_data/live_2026-11-08 -- gt_cert_v21 pushes these to the Droid)
 *     $tracksIn/<every file>                                    (_data/cert15d_input/my_tracks -- the 15d input)
 *     $gpxIn/<the test files>                                   ($CERT/cert15b -- the 15b test set, copied there by gt_cert_v21)
 * copied to $outDir/t15e_work/ first (the originals are only read). Writes $outDir/GroupTrack_T15E_mac_<time>.txt ending
 * "# T15E mac done: …", prints "GTT15E mac: …", and fails on any DIFF. No data -> "GTT15E mac: ... skipped" and return.
 * VALUE FOR VALUE with the Droid's NEW (its report $outDir/gt15e_droid.txt, copied by gt_cert_v21):
 *  1. no pass throws on the iPad;
 *  2. its input fingerprints (input|db|…, input|tracks…, input|files / input|file|…) must EQUAL the Droid report's -- otherwise
 *     "cross-device NOT COMPARED" and DIFF;
 *  3. every value the iPad writes (results, lines, calls, file snapshots, listed rows, table fingerprints) must equal the Droid NEW's.
 *     ONE tolerance, and only for exception TEXT: a screen line "ERROR: <file>: <message>" or a failed entry "<file>: <message>" whose
 *     message differs is EXPECTED (the platform's exception wording -- okio / zip / Kotlin/Native) when everything up to the message is
 *     equal. Anything else that differs is a DIFF; a differing trail / track / route row is NAMED (Fred's 15c Q3 rule).
 * The env: the clock and ids are fixed (the dump masks run-made ids and times by their shape); setLastModified / lastModified are
 * NSFileManager's (2.7e keeps a track's earliest <time> as its file time -- compared by the snapshots); TrailClassifier.useOf as 2.7e's
 * table answers the two categories 15e asks ("OHV", "rider": MOTORIZED); the platform calls are recorded exactly as the Droid's cert env.
 */
@OptIn(ExperimentalForeignApi::class)
class GtCertImport15eMacTest {
    private val spike = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data"
    private val dataDir = "$spike/live_2026-11-08"
    private val tracksIn = "$spike/cert15d_input/my_tracks"
    private val outDir = "$spike/cert"
    private val gpxIn = "$outDir/cert15b"

    private class TestEnv(private val fs: FileSystem, private val tracks: Path, private val root: Path, private val cache: Path) : GtImportEnv {
        var changes = 0
        val recorded = ArrayList<String>()
        private var n = 0
        override val clock = object : GtClock {
            override fun nowInstant(): String = "2026-11-09T12:00:00Z"
            override fun localNow(): GtLocalTime = GtLocalTime(2026, 11, 1, 12, 0, 0)   // = the Droid's cert env and GpxImportCertOld.certDate()
            override fun epochMs(): Long = 1_794_225_600_000L
        }
        override val ids = object : GtIds {
            override fun newId(): String { n++; return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0') }
        }
        override val files = object : GtFiles {
            override val fs: FileSystem = this@TestEnv.fs
            override fun setLastModified(path: Path, epochMs: Long): Boolean =
                NSFileManager.defaultManager.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)),
                    ofItemAtPath = path.toString(), error = null)
            override fun lastModified(path: Path): Long = fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L   // java.io.File: 0 when missing
            override fun list(dir: Path): List<Path> = fs.list(dir)
        }
        override val paths = object : GtImportPaths {
            override fun groupTrackRoot(): Path = root
            override fun tracksDir(): Path { fs.createDirectories(tracks); return tracks }
            override fun dir(name: String): Path = throw UnsupportedOperationException("not used by 15e")
            override fun cacheDir(): Path = cache
            override fun filesDir(): Path = throw UnsupportedOperationException("not used by 15e")
        }
        override val http = object : GtHttp {
            override fun get(url: String, readTimeoutMs: Int): String? = throw UnsupportedOperationException("not used by 15e")
        }
        /** TrailClassifier.useOf (2.7e, app): USE_OF["OHV"] = USE_OF["rider"] = MOTORIZED; 15e asks only these two. */
        override val trailClass = object : GtTrailClass {
            override fun useOf(category: String): String =
                if (category == "OHV" || category == "rider") "MOTORIZED" else throw UnsupportedOperationException("15e asks only for 'OHV' / 'rider', not '$category'")
        }
        override val platform = object : GtImportPlatform {
            override fun readAsset(name: String): String = throw UnsupportedOperationException("not used by 15e")
            override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int {
                recorded.add("enqueueCorridor|$geomHash|$slot|$replaceExisting"); return Cert15e.TILES
            }
            override fun saveToDownloads(file: Path): String { recorded.add("saveToDownloads|" + file.name); return "cert-" + file.name }
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

    /** the one tolerance (see the header): the exception message after "ERROR: <file>: " / inside each failed=[<file>: <message>] entry */
    private fun withoutMessages(v: String): String {
        val err = Regex("""^(ERROR: [^:]*): .*$""")
        if (err.matches(v)) return v.replace(err, "$1: *")
        val i = v.indexOf("failed=["); if (i < 0) return v
        val j = v.indexOf("];", i); if (j < 0) return v
        val items = v.substring(i + 8, j).split('|').joinToString("|") { e -> val k = e.indexOf(": "); if (k < 0) e else e.substring(0, k) + ": *" }
        return v.substring(0, i + 8) + items + v.substring(j)
    }

    @Test
    fun gpxImport() {
        val fs = FileSystem.SYSTEM
        val t0 = TimeSource.Monotonic.markNow()
        val srcS = "$dataDir/grouptrack_spatial.db".toPath(); val srcD = "$dataDir/grouptrack_data.db".toPath(); val srcT = tracksIn.toPath(); val srcG = gpxIn.toPath()
        if (!fs.exists(srcS) || !fs.exists(srcD) || !fs.exists(srcT) || !fs.exists(srcG)) { println("GTT15E mac: no data ($dataDir, $tracksIn, $gpxIn) -- skipped"); return }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmm"; val stamp = f.stringFromDate(NSDate())
        val out = ArrayList<String>()
        fun put(s: String) { out.add(s); if (s.startsWith("#") || s.contains("\tDIFF\t")) println("GTT15E mac: $s") }
        fun say(s: String) = println("GTT15E mac: $s")

        // ---- the databases and my_tracks, hashed ONCE (the test files are fingerprinted by the script itself) ----
        val inputFp = LinkedHashMap<String, String>()
        for (p in listOf(srcS, srcD)) inputFp["input|db|${p.name}"] = "len=${fs.metadata(p).size};sha256=${sha256(fs, p, ::say)}"
        val trFiles = fs.list(srcT).filter { fs.metadataOrNull(it)?.isRegularFile == true }.sortedBy { it.name }
        val trLines = trFiles.map { p -> "${p.name}\tsize=${fs.metadata(p).size};sha256=${sha256(fs, p, ::say)}" }
        inputFp["input|tracks"] = "n=${trFiles.size};sha=" + Cert15d.sha(trLines.joinToString("\n"))
        for (l in trLines) inputFp["input|tracks|" + l.substringBefore('\t')] = Cert15d.esc(l.substringAfter('\t'))

        // ---- the Droid's report: its NEW values ----
        val report = "$outDir/gt15e_droid.txt".toPath()
        val droid = LinkedHashMap<String, String>()
        if (fs.exists(report)) fs.read(report) {
            var pending: String? = null   // CODE RULE 1: null = the previous line was not a DIFF / EXPECTED head
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("T15E\t")) {
                    val p = l.split('\t', limit = 4); if (p.size < 4) continue
                    droid[p[1]] = p[3]; pending = if (p[2] == "SAME") null else p[1]
                } else if (l.startsWith("  NEW\t") && pending != null) { droid[pending!!] = l.removePrefix("  NEW\t"); pending = null }
            }
        }

        // ---- fresh COPIES (the originals are only read) ----
        val work = "$outDir/t15e_work".toPath()
        if (fs.exists(work)) fs.deleteRecursively(work)
        val tracks = work / "my_tracks"; val root = work / "GroupTrack"; val cache = work / "cache"; val staging = work / "staging"
        for (d in listOf(work, tracks, root, cache, staging)) fs.createDirectories(d)
        for (name in listOf("grouptrack_spatial.db", "grouptrack_data.db")) for (sfx in listOf("", "-wal", "-journal")) {
            val s = "$dataDir/$name$sfx".toPath(); if (fs.exists(s)) fs.copy(s, work / "$name$sfx")
        }
        for (p in trFiles) fs.copy(p, tracks / p.name)
        put("# T15E mac $stamp data=$dataDir tracks=$tracksIn files=$gpxIn (copied to $work)")

        val conn = gtOpenDatabase((work / "grouptrack_spatial.db").toString(), false)
        val dconn = gtOpenDatabase((work / "grouptrack_data.db").toString(), false)
        val mine = LinkedHashMap<String, String>()
        var selfBad = 0
        val env = TestEnv(fs, tracks, root, cache)
        try {
            val inp = Cert15d.inputs(conn, dconn, ::say); val notes = Cert15e.noteIds(dconn)
            put("# T15E mac: before the run: ${inp.trailIds.size} trails, ${inp.trackIds.size} tracks, ${inp.routeIds.size} routes, ${notes.size} route notes, ${inp.aliasIds.size} aliases, ${inp.waypointIds.size} waypoints")
            val writer = GtWrite(conn, dconn, { env.ids.newId() }, { env.clock.nowInstant() })
            val side = Cert15e.NewSide(GtGpxImport(conn, dconn, writer, env, Flight()), env.recorded) { env.changes }
            val clock = TimeSource.Monotonic.markNow(); val wall = (NSDate().timeIntervalSince1970 * 1000).toLong()
            Cert15e.runScript(side, Cert15e.Ctx(fs, srcG, tracks, cache / "import_convert", staging) { wall + clock.elapsedNow().inWholeMilliseconds }, { k, v ->
                mine[k] = Cert15d.esc(v)
                if (k.endsWith("|result") && v.startsWith("EXCEPTION")) { selfBad++; put("SELF\t$k\tDIFF\tNEW threw on the iPad: $v") }
            }, ::say)
            Cert15e.dumpTables(conn, dconn, inp, notes, { k, v, listed -> if (listed) mine["tables|$k"] = Cert15d.esc(v) }, ::say)
        } finally { conn.close(); dconn.close() }
        for ((k, v) in mine) if (k.endsWith("|result") || k.endsWith("|changed") || k.startsWith("tables|s9|") || k.startsWith("tables|t9|")) put("MAC\t$k\t$v")
        for ((k, v) in mine) if (k.startsWith("input|")) inputFp[k] = v   // the test files' fingerprints are the script's own lines

        var same = 0; var bad = selfBad; var expected = 0; var rows = 0
        fun named(k: String) = if (k.startsWith("tables|s1|") || k.startsWith("tables|s3|") || k.startsWith("tables|s5|") || k.startsWith("tables|s6|") ||
            k.startsWith("tables|t1|") || k.startsWith("tables|t2|")) {
            rows++; "\tROW DIFFERS (trail / track / track_properties / route / route_notes) -- investigate (libm?), not tolerated" } else ""
        val inputsDiffer = ArrayList<String>()
        for ((k, v) in inputFp) if (droid[k] != v) inputsDiffer.add(k)
        for (k in droid.keys) if (k.startsWith("input|") && k !in inputFp) inputsDiffer.add(k)
        val cross = droid.isNotEmpty() && inputsDiffer.isEmpty()
        if (droid.isEmpty()) { bad++; put("T15E\tdroid-report\tDIFF\tno Droid report at $report -- run gt_cert_v21 import15e") }
        for (k in inputsDiffer) { bad++; put("T15E\t$k\tDIFF\tthe iPad's input differs from the Droid's: droid=${droid[k]?.take(80)} mac=${inputFp[k]?.take(80)}") }
        if (droid.isNotEmpty()) {
            for ((k, v) in mine) {
                if (k.startsWith("input|")) continue
                val d = droid[k]
                when {
                    d == v -> same++
                    d != null && withoutMessages(d) == withoutMessages(v) -> { expected++; put("T15E\t$k\tEXPECTED\tthe platform's exception text differs\tdroid=${d.take(300)}\tmac=${v.take(300)}") }
                    d == null && k.startsWith("tables|") && !k.startsWith("tables|s9|") && !k.startsWith("tables|t9|") && !k.contains("|#cols:") -> { bad++; put("T15E\t$k\tDIFF\tonly on the iPad: $v" + named(k)) }
                    else -> { bad++; put("T15E\t$k\tDIFF\tdroid=${d?.take(300)}\tmac=${v.take(300)}" + named(k)) }
                }
            }
            for (k in droid.keys) if (!k.startsWith("input|") && !k.contains(" (only OLD)") && k !in mine &&
                (k.startsWith("tables|s9|") || k.startsWith("tables|t9|") || !k.startsWith("tables|") || k.startsWith("tables|s1|") || k.startsWith("tables|s3|") ||
                    k.startsWith("tables|s4|") || k.startsWith("tables|s5|") || k.startsWith("tables|s6|") || k.startsWith("tables|t1|") || k.startsWith("tables|t2|"))) {
                bad++; put("T15E\t$k\tDIFF\tonly on the Droid" + named(k))
            }
        }
        if (rows > 0) put("# T15E mac: $rows ROW(S) DIFFER between the iPad and the Droid -- each one is to be investigated")
        val secs = t0.elapsedNow().inWholeSeconds
        put("# T15E mac done: $same SAME, $bad DIFF, $expected EXPECTED, cross-device ${if (cross) "COMPARED" else "NOT COMPARED"}, rows differing $rows in $secs s")
        val file = "$outDir/GroupTrack_T15E_mac_$stamp.txt".toPath()
        fs.write(file) { writeUtf8(out.joinToString("\n") + "\n") }
        println("GTT15E mac: report $file")
        assertEquals(0, bad, "T15E: the iPad's NEW differs (see $file)")
    }
}
