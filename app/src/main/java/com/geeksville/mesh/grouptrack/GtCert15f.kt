package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.SpatialDbManager
import com.geeksville.mesh.convoy.TrailImporterCertOld
import com.grouptrack.shared.cert.Cert15d
import com.grouptrack.shared.cert.Cert15f
import com.grouptrack.shared.db.GtSchema
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtHttp
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.imports.GtTrailImport
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtLocalTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * STEP15F-2026-11-10 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15f certification on the Droid
 * (GtCertRun mode "trail15f") -- the app's (2.7e) agency trail import (the GENERATED copy TrailImporterCertOld, made by
 * make_old_copies_15f_b.py) against the shared GtTrailImport, through ONE script (Cert15f, see its header for the cases).
 * NOTHING LIVE IS READ OR WRITTEN:
 *  - THE INPUT (pushed by gt_cert_v22, the same files the iPad test reads): GroupTrack/cert15c_input/ (the two databases, 15c's push) and
 *    GroupTrack/cert15f_input/agency/<source>.jsonl (the Mac's _data/droid1_2026-11-06/states/utah/agency). No input -> STOP (no fallback).
 *  - copies only, in app.cacheDir/gtcert15f/: fresh0/ (ONE empty pair made by GtSchema, then byte-copied to old/fresh and new/fresh),
 *    old/live and new/live (15c's input), each side's areas/ (trail_areas), areasL/ (the live run's) and work/ (crafted pages, fetches);
 *  - OLD runs with SpatialDbManager's handles pointed at its copy (certSwapDatabases) and TrailImporterCertOld's hooks set (its trail_areas,
 *    the cert catalog, the replay server, the stamps), all cleared / put back in a finally;
 *  - NEW runs through GtAppDb.wrap on its copy, a GtWrite of its own, GtImportAndroid's env with its paths / catalog / replay / stamps;
 *  - the launch jobs stay off (GtCertRun.active; GtCertGuard). The copies are deleted at the start of the next run.
 * Compare (as GtCert15e): results re-synced on keys; tables by a streaming merge on their sorted keys (FRESH as tablesF|, LIVE as tablesL|):
 *     T15F<TAB>key<TAB>SAME|EXPECTED|DIFF<TAB>NEW's value       (a DIFF / EXPECTED also gets "  OLD<TAB>…" and "  NEW<TAB>…")
 * Footer: "# T15F done: N SAME, N DIFF, N EXPECTED in S s". Progress: "GTCERT trail15f ..." at least every 15 s.
 */
object GtCert15f {
    private const val TAG = "CertRun"
    private const val MAX_DIFF_LINES = 2000

    private var lastLog = 0L
    private fun progress(msg: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || msg.contains(" E1 ") || msg.contains(" E2 ") || now - lastLog >= 15_000) { lastLog = now; val rt = Runtime.getRuntime()
            Log.i(TAG, "GTCERT trail15f $msg [heap ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB of ${rt.maxMemory() / 1048576}]") }
    }

    /** OLD: the app's code, as the TEST-ONLY copy carries it, on SpatialDbManager's (swapped) handles; [progressLog] = its certProgress. */
    private class OldSide(private val app: Context, private val progressLog: MutableList<String>) : Cert15f.Side {
        override val name = "OLD"
        private fun f(p: Path) = File(p.toString())
        override fun full(sourceId: String) = kotlinx.coroutines.runBlocking { listOf(TrailImporterCertOld.importFullSource(app, sourceId)).toString() }
        override fun byArea(ids: List<String>, s: Double, w: Double, n: Double, e: Double) =
            kotlinx.coroutines.runBlocking { TrailImporterCertOld.importByArea(app, ids, s, w, n, e).toString() }
        override fun saved(sourceId: String, files: List<Path>, s: Double, w: Double, n: Double, e: Double) =
            kotlinx.coroutines.runBlocking { TrailImporterCertOld.importSavedByArea(app, sourceId, files.map { f(it) }, s, w, n, e).toString() }
        override fun fetch(sourceId: String, s: Double, w: Double, n: Double, e: Double, dest: Path, onPage: (Int, Int, Int) -> Unit) =
            TrailImporterCertOld.fetchSourceToFile(app, sourceId, s, w, n, e, f(dest), onPage)
        override fun trailheads(asset: String, sourceId: String, s: Double?, w: Double?, n: Double?, e: Double?) =
            TrailImporterCertOld.importTrailheads(app, asset, sourceId, s, w, n, e)
        override fun setAll(all: Boolean) { TrailImporterCertOld.updateMode = if (all) TrailImporterCertOld.UpdateMode.ALL else TrailImporterCertOld.UpdateMode.CARTO_ONLY }
        override fun writePending(n: Double, s: Double, e: Double, w: Double) = TrailImporterCertOld.writePendingArea(n, s, e, w)
        override fun readPending() = TrailImporterCertOld.readPendingArea()?.toString()
        override fun clearPending() = TrailImporterCertOld.clearPendingArea()
        override fun scanAreas() = TrailImporterCertOld.scanTrailAreas().map { it.toString() }
        override fun fully(sourceId: String) = TrailImporterCertOld.isSourceFullyImported(sourceId)
        override fun progress(): List<String> = synchronized(progressLog) { progressLog.toList() }
        override fun resetProgress() { synchronized(progressLog) { progressLog.clear() } }
    }

    /** GtImportAndroid's env with the cert's trail_areas, catalog, replay server and stamps. */
    private class CertEnv(base: GtImportEnv, private val areas: Path, private val catalog: String, private val replay: Cert15f.Replay,
                          private val stamps: Cert15f.Stamps) : GtImportEnv by base {
        override val clock: GtClock = object : GtClock by base.clock {
            override fun localNow(): GtLocalTime { val i = stamps.next(); return GtLocalTime(2026, 11, 1, 12, i / 60, i % 60) }
        }
        override val paths: GtImportPaths = object : GtImportPaths by base.paths {
            override fun dir(name: String): Path = if (name == "data/trail_areas") areas else throw IllegalStateException("cert: no dir $name")
        }
        override val http: GtHttp = object : GtHttp { override fun get(url: String, readTimeoutMs: Int): String? = replay.get(url, readTimeoutMs) }
        override val platform: GtImportPlatform = object : GtImportPlatform by base.platform {
            override fun readAsset(name: String): String = if (name == "trail_sources.json") catalog else base.platform.readAsset(name)
        }
    }

    private class Dirs(val root: File) {
        val fresh = File(root, "fresh"); val live = File(root, "live")
        val areas = File(root, "areas"); val areasL = File(root, "areasL"); val work = File(root, "work")
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

    /** the schema of a database as text (type, name, sql of every object, by name) -- the fresh pair's fingerprint */
    fun schemaText(path: String): String {
        val c = gtOpenDatabase(path, true)
        try { val st = c.prepare("SELECT type, name, COALESCE(sql,'') FROM sqlite_master WHERE name <> 'android_metadata' ORDER BY name"   /* STEP15F-FIXB-2026-11-10 */)
            try { val sb = StringBuilder(); while (st.step()) sb.append(st.getText(0)).append('|').append(st.getText(1)).append('|').append(st.getText(2)).append('\n'); return sb.toString() }
            finally { st.close() } } finally { c.close() }
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        val dbIn = GroupTrackStorage.dir("cert15c_input", app); val agIn = File(GroupTrackStorage.dir("cert15f_input", app), "agency")
        val inS = File(dbIn, "grouptrack_spatial.db"); val inD = File(dbIn, "grouptrack_data.db")
        if (!inS.isFile || !inD.isFile || !agIn.isDirectory || (agIn.listFiles { f -> f.name.endsWith(".jsonl") }?.size ?: 0) == 0) {
            w.write("# T15F: STOP -- no input (need ${inS.path}, ${inD.path} and the saved pages in ${agIn.path}; gt_cert_v22 pushes them). Not run.\n")
            Log.e(TAG, "GTCERT trail15f STOP: no input at ${dbIn.path} / ${agIn.path} -- run gt_cert_v22 trail15f")
            return
        }
        var same = 0; var diff = 0; var expected = 0; var diffLines = 0
        fun put(s: String) { w.write(s) }
        fun check(key: String, a: String, b: String, write: Boolean) {
            when {
                a == b -> { same++; if (write) put("T15F\t$key\tSAME\t$b\n") }
                key.contains("(EXPECTED") -> { expected++; put("T15F\t$key\tEXPECTED\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                else -> {
                    diff++
                    if (diffLines < MAX_DIFF_LINES) { diffLines++; put("T15F\t$key\tDIFF\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                    Log.w(TAG, "GTCERT trail15f DIFF $key\n OLD ${a.take(300)}\n NEW ${b.take(300)}")
                }
            }
        }
        progress("phase input (hash once)", true)
        for (f in listOf(inS, inD)) { val h = sha256(f, f.name); check("input|db|${f.name}", "len=${f.length()};sha256=$h", "len=${f.length()};sha256=$h", true) }
        for (sfx in listOf("-wal", "-journal", "-shm")) for (f in listOf(File(inS.path + sfx), File(inD.path + sfx)))
            if (f.exists()) put("# T15F: input ${f.name}: len=${f.length()} (copied with its database)\n")
        val shipped = app.assets.open("trail_sources.json").bufferedReader().use { it.readText() }
        val catalog = Cert15f.catalog(shipped)
        val sourceIds = run {   // the catalog's queryable ids (2.7e's loadCatalog keeps exactly these), in its order
            val root = (com.grouptrack.shared.json.GtOrgJson.parse(shipped) as kotlinx.serialization.json.JsonObject)["sources"] as kotlinx.serialization.json.JsonArray
            root.map { it as kotlinx.serialization.json.JsonObject }.filter { com.grouptrack.shared.json.GtOrgJson.optString(it, "status") != "display_only_not_queryable" }
                .map { com.grouptrack.shared.json.GtOrgJson.optString(it, "id") }
        }
        val base = File(app.cacheDir, "gtcert15f")
        progress("phase copies", true)
        base.deleteRecursively(); base.mkdirs()
        val oldD = Dirs(File(base, "old")); val newD = Dirs(File(base, "new")); val fresh0 = File(base, "fresh0")
        var aborted: String? = null   // CODE RULE 1: null = the run was not aborted
        try {
            // ---- the fresh pair: made ONCE by GtSchema (the app's schema files), then byte-copied to both sides ----
            fresh0.mkdirs()
            run {
                val c = gtOpenDatabase(File(fresh0, "grouptrack_spatial.db").path, false)
                try { val d = gtOpenDatabase(File(fresh0, "grouptrack_data.db").path, false)
                    try { GtSchema.ensure(c, d) { n -> app.assets.open(n).bufferedReader().use { it.readText() } } } finally { d.close() } } finally { c.close() }
            }
            for (n in listOf("grouptrack_spatial.db", "grouptrack_data.db")) {
                val st = schemaText(File(fresh0, n).path); check("input|fresh|$n", "sha=" + Cert15d.sha(st), "sha=" + Cert15d.sha(st), true)
            }
            for (d in listOf(oldD, newD)) {
                for (x in listOf(d.fresh, d.live, d.areas, d.areasL, d.work)) x.mkdirs()
                for (n in listOf("grouptrack_spatial.db", "grouptrack_data.db")) for (sfx in listOf("", "-wal", "-journal")) {
                    val s = File(fresh0, n + sfx); if (s.isFile) s.copyTo(File(d.fresh, n + sfx), overwrite = true)
                }
                for ((src, dst) in listOf(Pair(inS, File(d.live, inS.name)), Pair(inD, File(d.live, inD.name)))) {
                    copy(src, dst, "${d.root.name}/live/${dst.name}")
                    for (sfx in listOf("-wal", "-journal")) { val s = File(src.path + sfx); if (s.isFile) s.copyTo(File(dst.path + sfx), overwrite = true) }
                }
            }
            put("# T15F: copies made in ${(System.currentTimeMillis() - t0) / 1000} s\n")
            progress("phase input ids", true)
            fun inputsOf(dir: File): Cert15d.Inputs {
                val c = gtOpenDatabase(File(dir, "grouptrack_spatial.db").path, true)
                try { val e = gtOpenDatabase(File(dir, "grouptrack_data.db").path, true); try { return Cert15d.inputs(c, e) { p -> progress(p) } } finally { e.close() } } finally { c.close() }
            }
            val inpF = inputsOf(oldD.fresh); val inpL = inputsOf(oldD.live)
            put("# T15F: before the run: fresh ${inpF.trailIds.size} trails / ${inpF.waypointIds.size} waypoints; live ${inpL.trailIds.size} trails / ${inpL.waypointIds.size} waypoints\n")
            val agPath = agIn.toOkioPath()

            // ---- OLD: the app's code ----
            val tO = System.currentTimeMillis()
            oldD.results.bufferedWriter().use { o ->
                val emit: (String, String) -> Unit = { k, v -> o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\n") }
                val replay = Cert15f.replayFor(FileSystem.SYSTEM, agPath, catalog); val stamps = Cert15f.Stamps()
                val plog = java.util.Collections.synchronizedList(ArrayList<String>())
                for (phase in listOf("fresh", "live")) {
                    val dir = if (phase == "fresh") oldD.fresh else oldD.live
                    val sp = SQLiteDatabase.openDatabase(File(dir, "grouptrack_spatial.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        val dt = SQLiteDatabase.openDatabase(File(dir, "grouptrack_data.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                        try {
                            val prev = SpatialDbManager.certSwapDatabases(sp, dt)
                            TrailImporterCertOld.certAreas = if (phase == "fresh") oldD.areas else oldD.areasL
                            TrailImporterCertOld.certProgress = plog; TrailImporterCertOld.certAssets = mapOf("trail_sources.json" to catalog)
                            TrailImporterCertOld.certHttpFn = { u, t -> replay.get(u, t) }; TrailImporterCertOld.certClock = { stamps.text(stamps.next()) }
                            try {
                                val ctx = Cert15f.Ctx(FileSystem.SYSTEM, agPath, oldD.work.toOkioPath(), (if (phase == "fresh") oldD.areas else oldD.areasL).toOkioPath())
                                if (phase == "fresh") Cert15f.runFresh(OldSide(app, plog), ctx, replay, sourceIds, emit) { p -> progress(p) }
                                else Cert15f.runLive(OldSide(app, plog), ctx, replay, emit) { p -> progress(p) }
                            } finally {
                                TrailImporterCertOld.certAreas = null; TrailImporterCertOld.certProgress = null; TrailImporterCertOld.certAssets = null
                                TrailImporterCertOld.certHttpFn = null; TrailImporterCertOld.certClock = null
                                TrailImporterCertOld.updateMode = TrailImporterCertOld.UpdateMode.CARTO_ONLY
                                SpatialDbManager.certSwapDatabases(prev.first, prev.second)
                                Log.i(TAG, "GTCERT trail15f live handles restored")
                            }
                        } finally { dt.close() }
                    } finally { sp.close() }
                }
            }
            put("# T15F: OLD script done in ${(System.currentTimeMillis() - tO) / 1000} s\n")

            // ---- NEW: shared code ----
            val tN = System.currentTimeMillis()
            newD.results.bufferedWriter().use { o ->
                val emit: (String, String) -> Unit = { k, v -> o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\n") }
                val replay = Cert15f.replayFor(FileSystem.SYSTEM, agPath, catalog); val stamps = Cert15f.Stamps()
                val plog = java.util.Collections.synchronizedList(ArrayList<String>())
                for (phase in listOf("fresh", "live")) {
                    val dir = if (phase == "fresh") newD.fresh else newD.live
                    val areas = if (phase == "fresh") newD.areas else newD.areasL
                    val sp = SQLiteDatabase.openDatabase(File(dir, "grouptrack_spatial.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        val dt = SQLiteDatabase.openDatabase(File(dir, "grouptrack_data.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                        try {
                            val conn = GtAppDb.wrap(sp); val dconn = GtAppDb.wrap(dt)
                            val writer = GtWrite(conn, dconn, { java.util.UUID.randomUUID().toString() }, { java.time.Instant.now().toString() })
                            val env = CertEnv(GtImportAndroid(app), areas.toOkioPath(), catalog, replay, stamps)
                            val side = Cert15f.NewSide(GtTrailImport(conn, dconn, writer, env) { p -> plog.add(p.toString()) }, plog)
                            val ctx = Cert15f.Ctx(FileSystem.SYSTEM, agPath, newD.work.toOkioPath(), areas.toOkioPath())
                            if (phase == "fresh") Cert15f.runFresh(side, ctx, replay, sourceIds, emit) { p -> progress(p) }
                            else Cert15f.runLive(side, ctx, replay, emit) { p -> progress(p) }
                        } finally { dt.close() }
                    } finally { sp.close() }
                }
            }
            put("# T15F: NEW script done in ${(System.currentTimeMillis() - tN) / 1000} s\n")

            // ---- the tables, from each copy ----
            val tT = System.currentTimeMillis()
            progress("phase dump", true)
            for ((d, label) in listOf(oldD to "OLD", newD to "NEW")) {
                dump(d.fresh, d.tablesF, inpF, "$label fresh"); dump(d.live, d.tablesL, inpL, "$label live")
            }
            put("# T15F: tables dumped in ${(System.currentTimeMillis() - tT) / 1000} s\n")
        } catch (e: Throwable) {
            aborted = e.toString(); put("# T15F: ABORTED $e\n"); Log.e(TAG, "GTCERT trail15f aborted", e)
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
        put("# T15F tables: $unlisted unchanged unlisted rows SAME (counted, not written; covered by the s9 fp lines)\n")
        if (diff > diffLines) put("# T15F: ${diff - diffLines} more DIFF lines not written (see logcat CertRun)\n")
        put("# T15F done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s" +
            (if (aborted != null) " (ABORTED: $aborted)" else "") + "\n")
        Log.i(TAG, "GTCERT trail15f: $same SAME, $diff DIFF, $expected EXPECTED")
    }

    private fun dump(dir: File, out: File, inp: Cert15d.Inputs, label: String) {
        val c = gtOpenDatabase(File(dir, "grouptrack_spatial.db").path, true)
        try {
            val e = gtOpenDatabase(File(dir, "grouptrack_data.db").path, true)
            try { out.bufferedWriter().use { o -> Cert15f.dumpTables(c, e, inp, { k, v, listed -> line(o, k, v, listed) }, { p -> progress("$label $p") }) } }
            finally { e.close() }
        } finally { c.close() }
    }

    private fun line(o: java.io.Writer, k: String, v: String, listed: Boolean) {
        o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\t"); o.write(if (listed) "L" else "U"); o.write("\n")
    }

    private fun splitTable(l: String): Triple<String, String, Boolean> {
        val a = l.indexOf('\t'); val b = l.lastIndexOf('\t')
        return if (a < 0 || b <= a) Triple(l, "", true) else Triple(l.substring(0, a), l.substring(a + 1, b), l.substring(b + 1) == "L")
    }

    /** GtCert15e's streaming merge (the two table files are in the same ascending key order, Cert15f.dumpTables). */
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

    /** GtCert15e's compare, unchanged: re-syncs on keys, an extra line on one side is one DIFF ("only OLD" / "only NEW"). */
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
