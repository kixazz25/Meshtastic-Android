package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.OsmCertHooks15g
import com.geeksville.mesh.convoy.OsmExtractProgressCertOld
import com.geeksville.mesh.convoy.OsmExtractWorkerCertOld
import com.geeksville.mesh.convoy.OsmGpkgDecoderCertOld
import com.geeksville.mesh.convoy.OsmImportLedgerCertOld
import com.geeksville.mesh.convoy.OsmImportStageCertOld
import com.geeksville.mesh.convoy.OsmImportWorkerCertOld
import com.geeksville.mesh.convoy.OsmLayerCatalogCertOld
import com.geeksville.mesh.convoy.OsmPbfTagReaderCertOld
import com.geeksville.mesh.convoy.OsmTrailClassifierCertOld
import com.geeksville.mesh.convoy.SpatialDbManager
import com.grouptrack.shared.cert.Cert15d
import com.grouptrack.shared.cert.Cert15f
import com.grouptrack.shared.cert.Cert15g
import com.grouptrack.shared.db.GtSchema
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtLocalTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * STEP15G-2026-11-10 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15g certification on the Droid
 * (GtCertRun mode "osm15g") -- the app's (2.7e) OSM extract + import (the GENERATED *CertOld copies, make_old_copies_15g.py) against the
 * shared com.grouptrack.shared.osm, through ONE script (Cert15g, see its header for the cases).
 * NOTHING LIVE IS READ OR WRITTEN:
 *  - THE INPUT (pushed by gt_cert_v23, the same files the iPad test reads): GroupTrack/cert15g_input/utah-latest.osm.pbf +
 *    utah-latest-free.gpkg.zip, and GroupTrack/cert15c_input/ (15c's databases, for LIVE). No input -> STOP (no fallback).
 *  - copies only, in app.cacheDir/gtcert15g/: fresh0/ (ONE GtSchema pair, byte-copied to both sides), old|new / fresh, live, osm, config,
 *    work. OLD runs with OsmCertHooks15g set (root = its osm/, config = its config/, fixed clocks, the sweeps RECORDED) and SpatialDbManager's
 *    handles on its copy (certSwapDatabases), all cleared / put back in a finally. NEW runs on GtImportAndroid's env with its paths / clocks
 *    replaced, GtAppDb.wrap on its copy and a GtWrite of its own.
 * Compare as GtCert15f: results re-synced on keys; tables merged on sorted keys (FRESH tablesF|, LIVE tablesL|):
 *     T15G<TAB>key<TAB>SAME|EXPECTED|DIFF<TAB>NEW's value       (a DIFF / EXPECTED also gets "  OLD<TAB>…" and "  NEW<TAB>…")
 * Footer: "# T15G done: N SAME, N DIFF, N EXPECTED in S s". Progress: "GTCERT osm15g ..." at least every 15 s.
 */
object GtCert15g {
    private const val TAG = "CertRun"
    private const val MAX_DIFF_LINES = 2000

    private var lastLog = 0L
    private fun progress(msg: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || msg.startsWith("OLD ") || msg.startsWith("NEW ") || now - lastLog >= 15_000) { lastLog = now; val rt = Runtime.getRuntime()
            Log.i(TAG, "GTCERT osm15g $msg [heap ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB of ${rt.maxMemory() / 1048576}]") }
    }

    /** OLD: the app's code, as the TEST-ONLY copies carry it. */
    private class OldSide(private val app: Context) : Cert15g.Side {
        override val name = "OLD"
        private fun f(p: Path) = File(p.toString())
        override fun catalogText() = OsmLayerCatalogCertOld.load(app).toString()
        override fun displayName(slug: String) = OsmImportStageCertOld.displayName(slug)
        override fun classify(t: List<String?>) = OsmTrailClassifierCertOld.classify(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11])
            .let { Cert15g.classText(it.type, it.carto, it.motorized) }
        override fun decodeLine(b: ByteArray?) = try { OsmGpkgDecoderCertOld.decodeLine(b)?.let { Cert15g.lineText(it.wkt, it.geomHash, it.minLat, it.maxLat, it.minLon, it.maxLon) } ?: "null" }
            catch (e: Exception) { "EXCEPTION" }
        override fun decodePoint(b: ByteArray?) = try { OsmGpkgDecoderCertOld.decodePoint(b)?.let { Cert15g.pointText(it.lon, it.lat) } ?: "null" } catch (e: Exception) { "EXCEPTION" }
        override fun progressRound(json: String) = OsmExtractProgressCertOld.toJson(OsmExtractProgressCertOld.fromJson(json))
        override fun etaText(sec: Long) = OsmExtractProgressCertOld.etaText(sec)
        override fun stage(slug: String) = OsmImportStageCertOld.stageOf(app, slug).name
        override fun inFlight() = OsmImportStageCertOld.statesInFlight(app).toString()
        override fun verify(kind: String, p: Path) = when (kind) {
            "pbf" -> OsmImportStageCertOld.verifyPbf(f(p)); "tags" -> OsmImportStageCertOld.verifyTags(f(p))
            "zip" -> OsmImportStageCertOld.verifyZip(f(p)); else -> OsmImportStageCertOld.verifySkinny(f(p)) }
        override fun tagBuild(pbf: Path, out: Path, onProgress: (Long, Int) -> Unit) = OsmPbfTagReaderCertOld.build(f(pbf), f(out), onProgress)
        override fun ledgerCreate(slug: String, state: String, note: String, carry: Boolean) {
            OsmImportLedgerCertOld.create(app, slug, state, note, if (carry) OsmImportLedgerCertOld.priorImports(app, slug) else null) }
        override fun recordAcquire(slug: String, file: String, bytes: Long, from: String) = OsmImportLedgerCertOld.recordAcquire(app, slug, file, bytes, from)
        override fun setPending(slug: String, scope: String, bbox: DoubleArray?) = OsmImportLedgerCertOld.setPendingImport(app, slug, scope, bbox)
        override fun setPendingBbox(slug: String, s: Double, w: Double, n: Double, e: Double) = OsmImportLedgerCertOld.setPendingBbox(app, slug, s, w, n, e)
        override fun pendingScope(slug: String) = OsmImportLedgerCertOld.pendingScope(app, slug)
        override fun pendingBbox(slug: String) = OsmImportLedgerCertOld.pendingBbox(app, slug)?.joinToString(",") { it.toString() }
        override fun clearPending(slug: String) = OsmImportLedgerCertOld.clearPendingImport(app, slug)
        override fun extract(slug: String, onProgress: (String) -> Unit): String =
            when (val r = kotlinx.coroutines.runBlocking { OsmExtractWorkerCertOld(app, slug, onProgress).doWork() }) {
                is OsmExtractWorkerCertOld.Result.Success -> "Success"
                is OsmExtractWorkerCertOld.Result.Failure -> "Failure|" + r.data.getString(OsmExtractWorkerCertOld.KEY_ERROR)
            }
        override fun import(slug: String, onProgress: (String) -> Unit): String =
            when (val r = kotlinx.coroutines.runBlocking { OsmImportWorkerCertOld(app, slug, onProgress).doWork() }) {
                is OsmImportWorkerCertOld.Result.Success -> "Success"
                is OsmImportWorkerCertOld.Result.Failure -> "Failure|" + r.data.getString(OsmImportWorkerCertOld.KEY_ERROR)
            }
        override fun trailExtent(slug: String) = OsmImportStageCertOld.trailExtent(app, slug)
        override fun countInBbox(slug: String, s: Double, w: Double, n: Double, e: Double) = OsmImportStageCertOld.countTrailsInBbox(app, slug, s, w, n, e)
        override fun sweepDebris(slug: String) = OsmImportStageCertOld.sweepDebris(app, slug)
        override fun archive(slug: String) = OsmImportLedgerCertOld.archiveLedger(app, slug)?.name
        override fun finalize(slug: String, bytes: Long, deleted: List<String>) = OsmImportLedgerCertOld.finalizeAndDelete(app, slug, bytes, deleted)
        override fun discard(slug: String) = OsmImportStageCertOld.discardState(app, slug)
        override fun takeRecords(): List<String> { val l = OsmCertHooks15g.rec ?: return emptyList(); synchronized(l) { val r = l.toList(); l.clear(); return r } }
    }

    /** GtImportAndroid's env with the cert's osm / config folders and the fixed clocks (the same as OsmCertHooks15g's). */
    private class CertEnv(base: GtImportEnv, private val osm: Path, private val config: Path) : GtImportEnv by base {
        override val clock: GtClock = object : GtClock by base.clock {
            override fun localNow(): GtLocalTime = GtLocalTime(2026, 11, 1, 12, 0, 0)
            override fun epochMs(): Long = 0L
        }
        override val paths: GtImportPaths = object : GtImportPaths by base.paths {
            override fun dir(name: String): Path = when (name) { "osm" -> osm; "config" -> config; else -> throw IllegalStateException("cert: no dir $name") }
        }
    }

    private class Dirs(val root: File) {
        val fresh = File(root, "fresh"); val live = File(root, "live"); val osm = File(root, "osm"); val config = File(root, "config"); val work = File(root, "work")
        val results = File(root, "results.txt"); val tablesF = File(root, "tablesF.txt"); val tablesL = File(root, "tablesL.txt")
    }

    private fun sha256(f: File, label: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256"); var done = 0L; val total = f.length()
        f.inputStream().use { inp -> val buf = ByteArray(1 shl 20); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n); done += n
            progress("reading input $label: ${done shr 20} MB of ${total shr 20} MB") } }
        return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun copy(src: File, dst: File, label: String) {
        var done = 0L; val total = src.length()
        src.inputStream().use { i -> dst.outputStream().use { o -> val buf = ByteArray(1 shl 20)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress("copying $label: ${done shr 20} MB of ${total shr 20} MB") } } }
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        val dbIn = GroupTrackStorage.dir("cert15c_input", app); val osmIn = GroupTrackStorage.dir("cert15g_input", app)
        val inS = File(dbIn, "grouptrack_spatial.db"); val inD = File(dbIn, "grouptrack_data.db")
        val inP = File(osmIn, "utah-latest.osm.pbf"); val inZ = File(osmIn, "utah-latest-free.gpkg.zip")
        if (!inS.isFile || !inD.isFile || !inP.isFile || !inZ.isFile) {
            w.write("# T15G: STOP -- no input (need ${inS.path}, ${inD.path}, ${inP.path}, ${inZ.path}; gt_cert_v23 pushes them). Not run.\n")
            Log.e(TAG, "GTCERT osm15g STOP: no input at ${dbIn.path} / ${osmIn.path} -- run gt_cert_v23 osm15g")
            return
        }
        var same = 0; var diff = 0; var expected = 0; var diffLines = 0
        fun put(s: String) { w.write(s) }
        fun check(key: String, a: String, b: String, write: Boolean) {
            when {
                a == b -> { same++; if (write) put("T15G\t$key\tSAME\t$b\n") }
                key.contains("(EXPECTED") -> { expected++; put("T15G\t$key\tEXPECTED\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                else -> {
                    diff++
                    if (diffLines < MAX_DIFF_LINES) { diffLines++; put("T15G\t$key\tDIFF\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                    Log.w(TAG, "GTCERT osm15g DIFF $key\n OLD ${a.take(300)}\n NEW ${b.take(300)}")
                }
            }
        }
        progress("phase input (hash once)", true)
        for (f in listOf(inS, inD, inP, inZ)) { val h = sha256(f, f.name); val k = if (f.name.endsWith(".db")) "input|db|${f.name}" else "input|osm|${f.name}"
            check(k, "len=${f.length()};sha256=$h", "len=${f.length()};sha256=$h", true) }
        for (sfx in listOf("-wal", "-journal", "-shm")) for (f in listOf(File(inS.path + sfx), File(inD.path + sfx)))
            if (f.exists()) put("# T15G: input ${f.name}: len=${f.length()} (copied with its database)\n")
        val base = File(app.cacheDir, "gtcert15g")
        progress("phase copies", true)
        base.deleteRecursively(); base.mkdirs()
        val oldD = Dirs(File(base, "old")); val newD = Dirs(File(base, "new")); val fresh0 = File(base, "fresh0")
        var aborted: String? = null   // CODE RULE 1: null = the run was not aborted
        try {
            fresh0.mkdirs()
            run {
                val c = gtOpenDatabase(File(fresh0, "grouptrack_spatial.db").path, false)
                try { val d = gtOpenDatabase(File(fresh0, "grouptrack_data.db").path, false)
                    try { GtSchema.ensure(c, d) { n -> app.assets.open(n).bufferedReader().use { it.readText() } } } finally { d.close() } } finally { c.close() }
            }
            for (n in listOf("grouptrack_spatial.db", "grouptrack_data.db")) {
                val st = GtCert15f.schemaText(File(fresh0, n).path); check("input|fresh|$n", "sha=" + Cert15d.sha(st), "sha=" + Cert15d.sha(st), true)
            }
            for (d in listOf(oldD, newD)) {
                for (x in listOf(d.fresh, d.live, d.osm, d.config, d.work)) x.mkdirs()
                for (n in listOf("grouptrack_spatial.db", "grouptrack_data.db")) for (sfx in listOf("", "-wal", "-journal")) {
                    val s = File(fresh0, n + sfx); if (s.isFile) s.copyTo(File(d.fresh, n + sfx), overwrite = true)
                }
                for ((src, dst) in listOf(Pair(inS, File(d.live, inS.name)), Pair(inD, File(d.live, inD.name)))) {
                    copy(src, dst, "${d.root.name}/live/${dst.name}")
                    for (sfx in listOf("-wal", "-journal")) { val s = File(src.path + sfx); if (s.isFile) s.copyTo(File(dst.path + sfx), overwrite = true) }
                }
            }
            put("# T15G: copies made in ${(System.currentTimeMillis() - t0) / 1000} s\n")
            fun inputsOf(dir: File): Cert15d.Inputs {
                val c = gtOpenDatabase(File(dir, "grouptrack_spatial.db").path, true)
                try { val e = gtOpenDatabase(File(dir, "grouptrack_data.db").path, true); try { return Cert15d.inputs(c, e) { p -> progress(p) } } finally { e.close() } } finally { c.close() }
            }
            val inpF = inputsOf(oldD.fresh); val inpL = inputsOf(oldD.live)
            put("# T15G: before the run: fresh ${inpF.trailIds.size} trails; live ${inpL.trailIds.size} trails / ${inpL.waypointIds.size} waypoints\n")
            val inPath = osmIn.toOkioPath()

            // ---- OLD: the app's code ----
            val tO = System.currentTimeMillis()
            oldD.results.bufferedWriter().use { o ->
                val emit: (String, String) -> Unit = { k, v -> o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\n") }
                for (phase in listOf("fresh", "live")) {
                    val dir = if (phase == "fresh") oldD.fresh else oldD.live
                    val sp = SQLiteDatabase.openDatabase(File(dir, "grouptrack_spatial.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        val dt = SQLiteDatabase.openDatabase(File(dir, "grouptrack_data.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                        try {
                            val prev = SpatialDbManager.certSwapDatabases(sp, dt)
                            OsmCertHooks15g.rec = java.util.Collections.synchronizedList(ArrayList())
                            OsmCertHooks15g.config = oldD.config; OsmCertHooks15g.root = oldD.osm
                            try {
                                val ctx = Cert15g.Ctx(FileSystem.SYSTEM, inPath, oldD.osm.toOkioPath(), oldD.config.toOkioPath(), oldD.work.toOkioPath()) { }
                                if (phase == "fresh") Cert15g.run(OldSide(app), ctx, emit) { p -> progress(p) }
                                else Cert15g.runLive(OldSide(app), ctx, emit) { p -> progress(p) }
                            } finally {
                                OsmCertHooks15g.root = null; OsmCertHooks15g.config = null; OsmCertHooks15g.rec = null
                                SpatialDbManager.certSwapDatabases(prev.first, prev.second)
                                Log.i(TAG, "GTCERT osm15g live handles restored")
                            }
                        } finally { dt.close() }
                    } finally { sp.close() }
                }
            }
            put("# T15G: OLD script done in ${(System.currentTimeMillis() - tO) / 1000} s\n")

            // ---- NEW: shared code ----
            val tN = System.currentTimeMillis()
            newD.results.bufferedWriter().use { o ->
                val emit: (String, String) -> Unit = { k, v -> o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\n") }
                for (phase in listOf("fresh", "live")) {
                    val dir = if (phase == "fresh") newD.fresh else newD.live
                    val sp = SQLiteDatabase.openDatabase(File(dir, "grouptrack_spatial.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        val dt = SQLiteDatabase.openDatabase(File(dir, "grouptrack_data.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                        try {
                            val conn = GtAppDb.wrap(sp); val dconn = GtAppDb.wrap(dt)
                            val writer = GtWrite(conn, dconn, { java.util.UUID.randomUUID().toString() }, { java.time.Instant.now().toString() })
                            val env = CertEnv(GtImportAndroid(app), newD.osm.toOkioPath(), newD.config.toOkioPath())
                            val plat = Cert15g.CertPlat { p -> File(p.toString()).usableSpace }
                            val side = Cert15g.NewSide(env, plat) { Triple(conn, dconn, writer) }
                            val ctx = Cert15g.Ctx(FileSystem.SYSTEM, inPath, newD.osm.toOkioPath(), newD.config.toOkioPath(), newD.work.toOkioPath()) { }
                            if (phase == "fresh") Cert15g.run(side, ctx, emit) { p -> progress(p) }
                            else Cert15g.runLive(side, ctx, emit) { p -> progress(p) }
                        } finally { dt.close() }
                    } finally { sp.close() }
                }
            }
            put("# T15G: NEW script done in ${(System.currentTimeMillis() - tN) / 1000} s\n")

            // ---- the tables, from each copy ----
            val tT = System.currentTimeMillis()
            progress("phase dump", true)
            for ((d, label) in listOf(oldD to "OLD", newD to "NEW")) {
                dump(d.fresh, d.tablesF, inpF, "$label fresh"); dump(d.live, d.tablesL, inpL, "$label live")
            }
            put("# T15G: tables dumped in ${(System.currentTimeMillis() - tT) / 1000} s\n")
        } catch (e: Throwable) {
            aborted = e.toString(); put("# T15G: ABORTED $e\n"); Log.e(TAG, "GTCERT osm15g aborted", e)
        } finally {
            OsmCertHooks15g.root = null; OsmCertHooks15g.config = null; OsmCertHooks15g.rec = null
        }

        // ---- compare ----
        progress("phase compare", true)
        if (oldD.results.exists() && newD.results.exists()) compare(oldD.results, newD.results) { k, a, b -> check(k, a, b, true) }
        var unlisted = 0
        for ((tag, a, b) in listOf(Triple("tablesF", oldD.tablesF, newD.tablesF), Triple("tablesL", oldD.tablesL, newD.tablesL)))
            if (a.exists() && b.exists()) merge(a, b) { k, va, vb ->
                val listed = va.second || vb.second
                if (!listed && va.first == vb.first) unlisted++
                check("$tag|$k", va.first, vb.first, listed)
            }
        put("# T15G tables: $unlisted unchanged unlisted rows SAME (counted, not written; covered by the fp lines)\n")
        if (diff > diffLines) put("# T15G: ${diff - diffLines} more DIFF lines not written (see logcat CertRun)\n")
        put("# T15G done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s" +
            (if (aborted != null) " (ABORTED: $aborted)" else "") + "\n")
        Log.i(TAG, "GTCERT osm15g: $same SAME, $diff DIFF, $expected EXPECTED")
        // the copies stay until the next run (inspection); the big ones go now
        for (d in listOf(oldD, newD)) d.osm.deleteRecursively()
    }

    private fun dump(dir: File, out: File, inp: Cert15d.Inputs, label: String) {
        val c = gtOpenDatabase(File(dir, "grouptrack_spatial.db").path, true)
        try {
            val e = gtOpenDatabase(File(dir, "grouptrack_data.db").path, true)
            try { out.bufferedWriter().use { o ->
                val emit: (String, String, Boolean) -> Unit = { k, v, listed -> line(o, k, v, listed) }
                Cert15f.dumpTables(c, e, inp, emit) { p -> progress("$label $p") }
                Cert15g.dumpRefPoints(c, emit)
            } } finally { e.close() }
        } finally { c.close() }
    }

    private fun line(o: java.io.Writer, k: String, v: String, listed: Boolean) {
        o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\t"); o.write(if (listed) "L" else "U"); o.write("\n")
    }

    private fun splitTable(l: String): Triple<String, String, Boolean> {
        val a = l.indexOf('\t'); val b = l.lastIndexOf('\t')
        return if (a < 0 || b <= a) Triple(l, "", true) else Triple(l.substring(0, a), l.substring(a + 1, b), l.substring(b + 1) == "L")
    }

    /** GtCert15f's streaming merge (both table files in the same ascending key order). t4 (reference_points) sorts after t3. */
    private fun merge(a: File, b: File, emit: (key: String, va: Pair<String, Boolean>, vb: Pair<String, Boolean>) -> Unit) {
        a.bufferedReader().use { ra -> b.bufferedReader().use { rb ->
            var la = ra.readLine()?.let { splitTable(it) }; var lb = rb.readLine()?.let { splitTable(it) }
            while (la != null || lb != null) {
                if (lb == null || (la != null && la.first < lb.first)) { emit("${la!!.first} (only OLD)", Pair(la.second, true), Pair("MISSING", true)); la = ra.readLine()?.let { splitTable(it) } }
                else if (la == null || lb.first < la.first) { emit("${lb.first} (only NEW)", Pair("MISSING", true), Pair(lb.second, true)); lb = rb.readLine()?.let { splitTable(it) } }
                else {
                    emit(la.first, Pair(la.second, la.third), Pair(lb.second, lb.third))
                    la = ra.readLine()?.let { splitTable(it) }; lb = rb.readLine()?.let { splitTable(it) }
                }
            }
        } }
    }

    private fun splitLine(l: String?): Pair<String, String> {
        if (l == null) return Pair("<end of file>", "MISSING")
        val t = l.indexOf('\t')
        return if (t < 0) Pair(l, "") else Pair(l.substring(0, t), l.substring(t + 1))
    }

    /** GtCert15f's compare, unchanged: re-syncs on keys, an extra line on one side is one DIFF ("only OLD" / "only NEW"). */
    private fun compare(a: File, b: File, emit: (key: String, va: String, vb: String) -> Unit) {
        val la = a.readLines().map { splitLine(it) }; val lb = b.readLines().map { splitLine(it) }
        var i = 0; var j = 0; val win = 2000
        fun find(list: List<Pair<String, String>>, from: Int, key: String): Int {
            var k = from; val end = minOf(list.size, from + win)
            while (k < end) { if (list[k].first == key) return k - from; k++ }
            return -1
        }
        while (i < la.size || j < lb.size) {
            if (i >= la.size) { emit("${lb[j].first} (only NEW)", "MISSING", lb[j].second); j++; continue }
            if (j >= lb.size) { emit("${la[i].first} (only OLD)", la[i].second, "MISSING"); i++; continue }
            val (ka, va) = la[i]; val (kb, vb) = lb[j]
            if (ka == kb) { emit(ka, va, vb); i++; j++; continue }
            val inB = find(lb, j, ka)
            val inA = find(la, i, kb)
            if (inB >= 0 && (inA < 0 || inB <= inA)) { repeat(inB) { emit("${lb[j].first} (only NEW)", "MISSING", lb[j].second); j++ } }
            else if (inA >= 0) { repeat(inA) { emit("${la[i].first} (only OLD)", la[i].second, "MISSING"); i++ } }
            else { emit("$ka (only OLD)", va, "MISSING"); emit("$kb (only NEW)", "MISSING", vb); i++; j++ }
        }
    }
}
