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
import com.grouptrack.shared.imports.GtTrailImport
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
 * STEP15F-2026-11-10 (2.8 step 15f) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the agency trail import on
 * the iPad simulator. Runs the SAME runner as the Droid (Cert15f.runFresh / runLive with NewSide, then Cert15f.dumpTables) on COPIES of
 * the SAME input: the saved pages ($agencyIn, *.jsonl -- gt_cert_v22 pushes these to the Droid), 15c's databases ($dataDir) for LIVE, and
 * a FRESH pair made here by GtSchema from the repo's schema files ($assets -- the files the APK ships). Writes
 * $outDir/GroupTrack_T15F_mac_<time>.txt ending "# T15F mac done: …", prints "GTT15F mac: …", and fails on any DIFF.
 * VALUE FOR VALUE with the Droid's NEW (its report $outDir/gt15f_droid.txt, copied by gt_cert_v22): the input fingerprints (the
 * databases, the fresh pair's schema text, every saved page) must EQUAL the Droid's (else "cross-device NOT COMPARED" and DIFF); then every
 * value (results, progress, replay URLs, file snapshots, listed rows, table fingerprints) must equal. No tolerance.
 */
class GtCertTrail15fMacTest {
    private val spike = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data"
    private val dataDir = "$spike/live_2026-11-08"
    private val agencyIn = "$spike/droid1_2026-11-06/states/utah/agency"
    private val outDir = "$spike/cert"
    private val assets = "/Users/kixazz25/Projects/Meshtastic-Android/app/src/main/assets"

    private class TestEnv(private val fs: FileSystem, private val assets: String, private val areas: Path, private val catalog: String,
                          private val replay: Cert15f.Replay, private val stamps: Cert15f.Stamps) : GtImportEnv {
        private var n = 0
        override val clock = object : GtClock {
            override fun nowInstant(): String = "2026-11-10T12:00:00Z"
            override fun localNow(): GtLocalTime { val i = stamps.next(); return GtLocalTime(2026, 11, 1, 12, i / 60, i % 60) }
            override fun epochMs(): Long = 1_794_312_000_000L
        }
        override val ids = object : GtIds {
            override fun newId(): String { n++; return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0') }
        }
        override val files = object : GtFiles {
            override val fs: FileSystem = this@TestEnv.fs
            override fun setLastModified(path: Path, epochMs: Long): Boolean = throw UnsupportedOperationException("not used by 15f")
            override fun lastModified(path: Path): Long = fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L
            override fun list(dir: Path): List<Path> = fs.list(dir)
        }
        override val paths = object : GtImportPaths {
            override fun groupTrackRoot(): Path = throw UnsupportedOperationException("not used by 15f")
            override fun tracksDir(): Path = throw UnsupportedOperationException("not used by 15f")
            override fun dir(name: String): Path = if (name == "data/trail_areas") areas else throw IllegalStateException("cert: no dir $name")
            override fun cacheDir(): Path = throw UnsupportedOperationException("not used by 15f")
            override fun filesDir(): Path = throw UnsupportedOperationException("not used by 15f")
        }
        override val http = object : GtHttp { override fun get(url: String, readTimeoutMs: Int): String? = replay.get(url, readTimeoutMs) }
        override val trailClass = object : GtTrailClass { override fun useOf(category: String): String = throw UnsupportedOperationException("not used by 15f") }
        override val platform = object : GtImportPlatform {
            override fun readAsset(name: String): String = if (name == "trail_sources.json") catalog else fs.read("$assets/$name".toPath()) { readUtf8() }
            override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int = throw UnsupportedOperationException("not used by 15f")
            override fun saveToDownloads(file: Path): String = throw UnsupportedOperationException("not used by 15f")
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
        try { val st = c.prepare("SELECT type, name, COALESCE(sql,'') FROM sqlite_master WHERE name <> 'android_metadata' ORDER BY name"   /* STEP15F-FIXB-2026-11-10 */)
            try { val sb = StringBuilder(); while (st.step()) sb.append(st.getText(0)).append('|').append(st.getText(1)).append('|').append(st.getText(2)).append('\n'); return sb.toString() }
            finally { st.close() } } finally { c.close() }
    }

    @Test
    fun trailImport() {
        val fs = FileSystem.SYSTEM
        val t0 = TimeSource.Monotonic.markNow()
        val srcS = "$dataDir/grouptrack_spatial.db".toPath(); val srcD = "$dataDir/grouptrack_data.db".toPath(); val ag = agencyIn.toPath()
        if (!fs.exists(srcS) || !fs.exists(srcD) || !fs.exists(ag) || !fs.exists("$assets/trail_sources.json".toPath())) {
            println("GTT15F mac: no data ($dataDir, $agencyIn, $assets) -- skipped"); return
        }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmm"; val stamp = f.stringFromDate(NSDate())
        val out = ArrayList<String>()
        fun put(s: String) { out.add(s); if (s.startsWith("#") || s.contains("\tDIFF\t")) println("GTT15F mac: $s") }
        fun say(s: String) = println("GTT15F mac: $s")

        val inputFp = LinkedHashMap<String, String>()
        for (p in listOf(srcS, srcD)) inputFp["input|db|${p.name}"] = "len=${fs.metadata(p).size};sha256=${sha256(fs, p, ::say)}"

        // ---- the Droid's report: its NEW values ----
        val report = "$outDir/gt15f_droid.txt".toPath()
        val droid = LinkedHashMap<String, String>()
        if (fs.exists(report)) fs.read(report) {
            var pending: String? = null   // CODE RULE 1: null = the previous line was not a DIFF / EXPECTED head
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("T15F\t")) {
                    val p = l.split('\t', limit = 4); if (p.size < 4) continue
                    droid[p[1]] = p[3]; pending = if (p[2] == "SAME") null else p[1]
                } else if (l.startsWith("  NEW\t") && pending != null) { droid[pending!!] = l.removePrefix("  NEW\t"); pending = null }
            }
        }

        // ---- fresh COPIES (the originals are only read) ----
        val work = "$outDir/t15f_work".toPath()
        if (fs.exists(work)) fs.deleteRecursively(work)
        val freshD = work / "fresh"; val liveD = work / "live"; val areas = work / "areas"; val areasL = work / "areasL"; val wk = work / "work"
        for (d in listOf(work, freshD, liveD, areas, areasL, wk)) fs.createDirectories(d)
        for (name in listOf("grouptrack_spatial.db", "grouptrack_data.db")) for (sfx in listOf("", "-wal", "-journal")) {
            val s = "$dataDir/$name$sfx".toPath(); if (fs.exists(s)) fs.copy(s, liveD / "$name$sfx")
        }
        run {
            val c = gtOpenDatabase((freshD / "grouptrack_spatial.db").toString(), false)
            try { val d = gtOpenDatabase((freshD / "grouptrack_data.db").toString(), false)
                try { GtSchema.ensure(c, d) { n -> fs.read("$assets/$n".toPath()) { readUtf8() } } } finally { d.close() } } finally { c.close() }
        }
        for (n in listOf("grouptrack_spatial.db", "grouptrack_data.db")) inputFp["input|fresh|$n"] = "sha=" + Cert15d.sha(schemaText((freshD / n).toString()))
        put("# T15F mac $stamp data=$dataDir agency=$agencyIn assets=$assets (copied to $work)")

        val catalog = Cert15f.catalog(fs.read("$assets/trail_sources.json".toPath()) { readUtf8() })
        val sourceIds = run {
            val root = (com.grouptrack.shared.json.GtOrgJson.parse(fs.read("$assets/trail_sources.json".toPath()) { readUtf8() }) as kotlinx.serialization.json.JsonObject)["sources"] as kotlinx.serialization.json.JsonArray
            root.map { it as kotlinx.serialization.json.JsonObject }.filter { com.grouptrack.shared.json.GtOrgJson.optString(it, "status") != "display_only_not_queryable" }
                .map { com.grouptrack.shared.json.GtOrgJson.optString(it, "id") }
        }
        val replay = Cert15f.replayFor(fs, ag, catalog); val stamps = Cert15f.Stamps()
        val plog = ArrayList<String>()
        val mine = LinkedHashMap<String, String>()
        var selfBad = 0
        val emit: (String, String) -> Unit = { k, v -> mine[k] = Cert15d.esc(v)
            if (k.endsWith("|result") && v.startsWith("EXCEPTION") && !k.startsWith("ON|")) { selfBad++; put("SELF\t$k\tDIFF\tNEW threw on the iPad: $v") } }
        for ((phase, dbDir, ar, tag) in listOf(Quad("fresh", freshD, areas, "tablesF"), Quad("live", liveD, areasL, "tablesL"))) {
            val conn = gtOpenDatabase((dbDir / "grouptrack_spatial.db").toString(), false)
            val dconn = gtOpenDatabase((dbDir / "grouptrack_data.db").toString(), false)
            try {
                val inp = Cert15d.inputs(conn, dconn, ::say)
                put("# T15F mac: $phase before the run: ${inp.trailIds.size} trails, ${inp.waypointIds.size} waypoints")
                val env = TestEnv(fs, assets, ar, catalog, replay, stamps)
                val writer = GtWrite(conn, dconn, { env.ids.newId() }, { env.clock.nowInstant() })
                val side = Cert15f.NewSide(GtTrailImport(conn, dconn, writer, env) { p -> plog.add(p.toString()) }, plog)
                val ctx = Cert15f.Ctx(fs, ag, wk, ar)
                if (phase == "fresh") Cert15f.runFresh(side, ctx, replay, sourceIds, emit, ::say) else Cert15f.runLive(side, ctx, replay, emit, ::say)
                Cert15f.dumpTables(conn, dconn, inp, { k, v, listed -> if (listed) mine["$tag|$k"] = Cert15d.esc(v) }, ::say)
            } finally { conn.close(); dconn.close() }
        }
        for ((k, v) in mine) if (k.startsWith("input|")) inputFp[k] = v   // the saved pages' fingerprints are the script's own lines

        var same = 0; var bad = selfBad; var rows = 0
        fun named(k: String) = if (k.startsWith("tablesF|s1|") || k.startsWith("tablesF|s3|") || k.startsWith("tablesL|s1|") || k.startsWith("tablesL|s3|") ||
            k.startsWith("tablesF|s6|") || k.startsWith("tablesL|s6|")) { rows++; "\tROW DIFFERS (trail / alias / waypoint) -- investigate, not tolerated" } else ""
        val inputsDiffer = ArrayList<String>()
        for ((k, v) in inputFp) if (droid[k] != v) inputsDiffer.add(k)
        for (k in droid.keys) if (k.startsWith("input|") && k !in inputFp) inputsDiffer.add(k)
        val cross = droid.isNotEmpty() && inputsDiffer.isEmpty()
        if (droid.isEmpty()) { bad++; put("T15F\tdroid-report\tDIFF\tno Droid report at $report -- run gt_cert_v22 trail15f") }
        for (k in inputsDiffer) { bad++; put("T15F\t$k\tDIFF\tthe iPad's input differs from the Droid's: droid=${droid[k]?.take(80)} mac=${inputFp[k]?.take(80)}") }
        if (droid.isNotEmpty()) {
            for ((k, v) in mine) {
                if (k.startsWith("input|")) continue
                val d = droid[k]
                when {
                    d == v -> same++
                    d == null && k.startsWith("tables") && !k.contains("|s9|") && !k.contains("|t3|~") && !k.contains("|#cols:") -> { bad++; put("T15F\t$k\tDIFF\tonly on the iPad: $v" + named(k)) }
                    else -> { bad++; put("T15F\t$k\tDIFF\tdroid=${d?.take(300)}\tmac=${v.take(300)}" + named(k)) }
                }
            }
            for (k in droid.keys) if (!k.startsWith("input|") && !k.contains(" (only OLD)") && k !in mine &&
                (!k.startsWith("tables") || k.contains("|s9|") || k.contains("|t3|") || k.contains("|s1|") || k.contains("|s2|") || k.contains("|s3|") || k.contains("|s6|"))) {
                bad++; put("T15F\t$k\tDIFF\tonly on the Droid" + named(k))
            }
        }
        if (rows > 0) put("# T15F mac: $rows ROW(S) DIFFER between the iPad and the Droid -- each one is to be investigated")
        val secs = t0.elapsedNow().inWholeSeconds
        put("# T15F mac done: $same SAME, $bad DIFF, 0 EXPECTED, cross-device ${if (cross) "COMPARED" else "NOT COMPARED"}, rows differing $rows in $secs s")
        val file = "$outDir/GroupTrack_T15F_mac_$stamp.txt".toPath()
        fs.write(file) { writeUtf8(out.joinToString("\n") + "\n") }
        println("GTT15F mac: report $file")
        assertEquals(0, bad, "T15F: the iPad's NEW differs (see $file)")
    }

    private data class Quad(val a: String, val b: Path, val c: Path, val d: String)
}
