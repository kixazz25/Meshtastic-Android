package com.grouptrack.shared.cert

import com.grouptrack.shared.db.GtSchema
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
 * STEP15G-2026-11-10 (2.8 step 15g) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the OSM extract + import
 * on the iPad simulator. Runs the SAME runner as the Droid (Cert15g.run / runLive with NewSide, then Cert15f.dumpTables +
 * Cert15g.dumpRefPoints) on COPIES of the SAME input: Utah's PBF + GeoPackage zip ($osmIn -- gt_cert_v23 pushes these to the Droid),
 * 15c's databases ($dataDir) for LIVE, and a FRESH pair made here by GtSchema from the repo's schema files ($assets). Writes
 * $outDir/GroupTrack_T15G_mac_<time>.txt ending "# T15G mac done: …", prints "GTT15G mac: …", and fails on any DIFF.
 * VALUE FOR VALUE with the Droid's NEW (its report $outDir/gt15g_droid.txt, copied by gt_cert_v23): the input fingerprints must EQUAL the
 * Droid's (else "cross-device NOT COMPARED" and DIFF); then every value (results, progress, records, files, database dumps, listed
 * rows, table fingerprints) must equal. No tolerance.
 */
class GtCertOsm15gMacTest {
    private val spike = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data"
    private val dataDir = "$spike/live_2026-11-08"
    private val osmIn = "$spike/droid1_2026-11-06/states/utah"
    private val outDir = "$spike/cert"
    private val assets = "/Users/kixazz25/Projects/Meshtastic-Android/app/src/main/assets"

    private class TestEnv(private val fs: FileSystem, private val assets: String, private val osm: Path, private val config: Path) : GtImportEnv {
        private var n = 0
        override val clock = object : GtClock {
            override fun nowInstant(): String = "2026-11-10T12:00:00Z"
            override fun localNow(): GtLocalTime = GtLocalTime(2026, 11, 1, 12, 0, 0)
            override fun epochMs(): Long = 0L
        }
        override val ids = object : GtIds {
            override fun newId(): String { n++; return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0') }
        }
        override val files = object : GtFiles {
            override val fs: FileSystem = this@TestEnv.fs
            override fun setLastModified(path: Path, epochMs: Long): Boolean = throw UnsupportedOperationException("not used by 15g")
            override fun lastModified(path: Path): Long = fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L
            override fun list(dir: Path): List<Path> = fs.listOrNull(dir) ?: emptyList()
        }
        override val paths = object : GtImportPaths {
            override fun groupTrackRoot(): Path = throw UnsupportedOperationException("not used by 15g")
            override fun tracksDir(): Path = throw UnsupportedOperationException("not used by 15g")
            override fun dir(name: String): Path = when (name) { "osm" -> osm; "config" -> config; else -> throw IllegalStateException("cert: no dir $name") }
            override fun cacheDir(): Path = throw UnsupportedOperationException("not used by 15g")
            override fun filesDir(): Path = throw UnsupportedOperationException("not used by 15g")
        }
        override val http = object : GtHttp { override fun get(url: String, readTimeoutMs: Int): String? = throw UnsupportedOperationException("not used by 15g") }
        override val trailClass = object : GtTrailClass { override fun useOf(category: String): String = throw UnsupportedOperationException("not used by 15g") }
        override val platform = object : GtImportPlatform {
            override fun readAsset(name: String): String = fs.read("$assets/$name".toPath()) { readUtf8() }
            override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int = throw UnsupportedOperationException("not used by 15g")
            override fun saveToDownloads(file: Path): String = throw UnsupportedOperationException("not used by 15g")
            override fun onTrailsChanged() {}
        }
    }

    private fun sha256(fs: FileSystem, p: Path, say: (String) -> Unit): String {
        val hs = HashingSink.sha256(blackholeSink()); val out = hs.buffer(); var done = 0L; var chunks = 0
        fs.read(p) { val buf = okio.Buffer()
            while (true) { val n = read(buf, 1L shl 20); if (n < 0) break; done += n; out.write(buf, n); if (++chunks % 100 == 0) say("hashing ${p.name}: ${done shr 20} MB") } }
        out.flush()
        return hs.hash.hex()
    }

    private fun schemaText(path: String): String {
        val c = gtOpenDatabase(path, true)
        try { val st = c.prepare("SELECT type, name, COALESCE(sql,'') FROM sqlite_master WHERE name <> 'android_metadata' ORDER BY name")
            try { val sb = StringBuilder(); while (st.step()) sb.append(st.getText(0)).append('|').append(st.getText(1)).append('|').append(st.getText(2)).append('\n'); return sb.toString() }
            finally { st.close() } } finally { c.close() }
    }

    @Test
    fun osmImport() {
        val fs = FileSystem.SYSTEM
        val t0 = TimeSource.Monotonic.markNow()
        val srcS = "$dataDir/grouptrack_spatial.db".toPath(); val srcD = "$dataDir/grouptrack_data.db".toPath()
        val pbf = "$osmIn/utah-latest.osm.pbf".toPath(); val zip = "$osmIn/utah-latest-free.gpkg.zip".toPath()
        if (!fs.exists(srcS) || !fs.exists(srcD) || !fs.exists(pbf) || !fs.exists(zip) || !fs.exists("$assets/osm_layers.json".toPath())) {
            println("GTT15G mac: no data ($dataDir, $osmIn, $assets) -- skipped"); return
        }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmm"; val stamp = f.stringFromDate(NSDate())
        val out = ArrayList<String>()
        fun put(s: String) { out.add(s); if (s.startsWith("#") || s.contains("\tDIFF\t")) println("GTT15G mac: $s") }
        fun say(s: String) = println("GTT15G mac: $s")

        val inputFp = LinkedHashMap<String, String>()
        for (p in listOf(srcS, srcD)) inputFp["input|db|${p.name}"] = "len=${fs.metadata(p).size};sha256=${sha256(fs, p, ::say)}"
        for (p in listOf(pbf, zip)) inputFp["input|osm|${p.name}"] = "len=${fs.metadata(p).size};sha256=${sha256(fs, p, ::say)}"

        // ---- the Droid's report: its NEW values ----
        val report = "$outDir/gt15g_droid.txt".toPath()
        val droid = LinkedHashMap<String, String>()
        if (fs.exists(report)) fs.read(report) {
            var pending: String? = null   // CODE RULE 1: null = the previous line was not a DIFF / EXPECTED head
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("T15G\t")) {
                    val p = l.split('\t', limit = 4); if (p.size < 4) continue
                    droid[p[1]] = p[3]; pending = if (p[2] == "SAME") null else p[1]
                } else if (l.startsWith("  NEW\t") && pending != null) { droid[pending!!] = l.removePrefix("  NEW\t"); pending = null }
            }
        }

        // ---- fresh COPIES (the originals are only read) ----
        val work = "$outDir/t15g_work".toPath()
        if (fs.exists(work)) fs.deleteRecursively(work)
        val freshD = work / "fresh"; val liveD = work / "live"; val osm = work / "osm"; val config = work / "config"; val wk = work / "work"
        for (d in listOf(work, freshD, liveD, osm, config, wk)) fs.createDirectories(d)
        for (name in listOf("grouptrack_spatial.db", "grouptrack_data.db")) for (sfx in listOf("", "-wal", "-journal")) {
            val s = "$dataDir/$name$sfx".toPath(); if (fs.exists(s)) fs.copy(s, liveD / "$name$sfx")
        }
        run {
            val c = gtOpenDatabase((freshD / "grouptrack_spatial.db").toString(), false)
            try { val d = gtOpenDatabase((freshD / "grouptrack_data.db").toString(), false)
                try { GtSchema.ensure(c, d) { n -> fs.read("$assets/$n".toPath()) { readUtf8() } } } finally { d.close() } } finally { c.close() }
        }
        for (n in listOf("grouptrack_spatial.db", "grouptrack_data.db")) inputFp["input|fresh|$n"] = "sha=" + Cert15d.sha(schemaText((freshD / n).toString()))
        put("# T15G mac $stamp data=$dataDir osm=$osmIn assets=$assets (copied to $work)")

        val mine = LinkedHashMap<String, String>()
        val emit: (String, String) -> Unit = { k, v -> mine[k] = Cert15d.esc(v) }
        for ((phase, dbDir, tag) in listOf(Triple("fresh", freshD, "tablesF"), Triple("live", liveD, "tablesL"))) {
            val conn = gtOpenDatabase((dbDir / "grouptrack_spatial.db").toString(), false)
            val dconn = gtOpenDatabase((dbDir / "grouptrack_data.db").toString(), false)
            try {
                val inp = Cert15d.inputs(conn, dconn, ::say)
                put("# T15G mac: $phase before the run: ${inp.trailIds.size} trails, ${inp.waypointIds.size} waypoints")
                val env = TestEnv(fs, assets, osm, config)
                val writer = GtWrite(conn, dconn, { env.ids.newId() }, { env.clock.nowInstant() })
                val side = Cert15g.NewSide(env, Cert15g.CertPlat { Long.MAX_VALUE }) { Triple(conn, dconn, writer) }
                val ctx = Cert15g.Ctx(fs, osmIn.toPath(), osm, config, wk) { }
                if (phase == "fresh") Cert15g.run(side, ctx, emit, ::say) else Cert15g.runLive(side, ctx, emit, ::say)
                val listedOnly: (String, String, Boolean) -> Unit = { k, v, listed -> if (listed) mine["$tag|$k"] = Cert15d.esc(v) }
                Cert15f.dumpTables(conn, dconn, inp, listedOnly, ::say)
                Cert15g.dumpRefPoints(conn, listedOnly)
            } finally { conn.close(); dconn.close() }
        }
        if (fs.exists(osm)) fs.deleteRecursively(osm)   // the big working files; the rest stays for inspection

        var same = 0; var bad = 0; var rows = 0
        fun named(k: String) = if (Regex("""^tables[FL]\|(s1|s3|s6|t4)\|""").containsMatchIn(k)) { rows++; "\tROW DIFFERS -- investigate, not tolerated" } else ""
        val inputsDiffer = ArrayList<String>()
        for ((k, v) in inputFp) if (droid[k] != v) inputsDiffer.add(k)
        for (k in droid.keys) if ((k.startsWith("input|db|") || k.startsWith("input|osm|") || k.startsWith("input|fresh|")) && k !in inputFp) inputsDiffer.add(k)
        val cross = droid.isNotEmpty() && inputsDiffer.isEmpty()
        if (droid.isEmpty()) { bad++; put("T15G\tdroid-report\tDIFF\tno Droid report at $report -- run gt_cert_v23 osm15g") }
        for (k in inputsDiffer) { bad++; put("T15G\t$k\tDIFF\tthe iPad's input differs from the Droid's: droid=${droid[k]?.take(80)} mac=${inputFp[k]?.take(80)}") }
        if (droid.isNotEmpty()) {
            for ((k, v) in mine) {
                val d = droid[k]
                when {
                    d == v -> same++
                    d == null && k.startsWith("tables") && !k.contains("|s9|") && !k.contains("|t3|~") && !k.contains("|t4|~") && !k.contains("|#cols:") -> { bad++; put("T15G\t$k\tDIFF\tonly on the iPad: $v" + named(k)) }
                    else -> { bad++; put("T15G\t$k\tDIFF\tdroid=${d?.take(300)}\tmac=${v.take(300)}" + named(k)) }
                }
            }
            for (k in droid.keys) if (!k.startsWith("input|db|") && !k.startsWith("input|osm|") && !k.startsWith("input|fresh|") && !k.contains(" (only OLD)") && k !in mine &&
                (!k.startsWith("tables") || Regex("""^tables[FL]\|(s9|t3|t4|s1|s2|s3|s6)\|""").containsMatchIn(k))) {
                bad++; put("T15G\t$k\tDIFF\tonly on the Droid" + named(k))
            }
        }
        if (rows > 0) put("# T15G mac: $rows ROW(S) DIFFER between the iPad and the Droid -- each one is to be investigated")
        val secs = t0.elapsedNow().inWholeSeconds
        put("# T15G mac done: $same SAME, $bad DIFF, 0 EXPECTED, cross-device ${if (cross) "COMPARED" else "NOT COMPARED"}, rows differing $rows in $secs s")
        val file = "$outDir/GroupTrack_T15G_mac_$stamp.txt".toPath()
        fs.write(file) { writeUtf8(out.joinToString("\n") + "\n") }
        println("GTT15G mac: report $file")
        assertEquals(0, bad, "T15G: the iPad's NEW differs (see $file)")
    }
}
