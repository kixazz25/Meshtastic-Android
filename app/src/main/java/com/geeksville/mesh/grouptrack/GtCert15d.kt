package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.RiderTrailWriterCertOld
import com.geeksville.mesh.convoy.SpatialDbManager
import com.geeksville.mesh.convoy.TrackSyncCertOld
import com.grouptrack.shared.cert.Cert15d
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.track.GtTracks
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * STEP15D-2026-11-09 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15d certification on the Droid
 * (GtCertRun mode "track15d") -- the app's (2.7e) track add / sync / properties (the GENERATED copy TrackSyncCertOld, made by
 * make_old_copies_15d.py) against the shared GtTracks, INVENTORY 7.2 S1.
 * NOTHING LIVE IS READ OR WRITTEN:
 *  - THE INPUT (pushed by gt_cert_v20, the same files the iPad test reads):
 *      GroupTrack/cert15c_input/grouptrack_spatial.db + grouptrack_data.db   (the Mac's live_2026-11-08 -- the 15c push, reused)
 *      GroupTrack/cert15d_input/my_tracks/ (every file)                       (the Mac's _data/cert15d_input/my_tracks: Droid 1's my_tracks)
 *    No input -> the run STOPS (no fallback). Each input file is HASHED ONCE (size + sha256 into the report -- the iPad compares them).
 *  - the input is only COPIED -- never opened -- into app.cacheDir/gtcert15d/old/ and /new/: both databases (+ any -wal / -journal),
 *    my_tracks (file by file, in name order, so both copies are built the same way), an empty GroupTrack root (track_sync.log goes
 *    there) and an empty staging folder;
 *  - OLD runs with SpatialDbManager's two handles pointed at copy "old" (certSwapDatabases) and TrackSyncCertOld's folders pointed at
 *    old/my_tracks + old/GroupTrack, all put back in a finally; its "the maps reload" stamp is RiderTrailWriterCertOld's own flow, which
 *    no map collects;
 *  - NEW runs on copy "new" through GtAppDb.wrap (what the front door will pass), a GtWrite of its own (UUID + Instant.now, as
 *    SpatialDbManager.writer()) and GtImportAndroid's env with ITS paths pointed at new/my_tracks + new/GroupTrack and onTrailsChanged
 *    COUNTED (never sent to the maps); its single-flight guards are this run's own;
 *  - the launch jobs that could touch the handles or the folders stay off (GtCertRun.active; GtCertGuard);
 *  - the copies are deleted at the start of the next run (they stay for inspection until then).
 * Pre-run ids (Cert15d.Inputs) are read ONCE, from copy "old" before either side runs (the two copies are byte copies of one input).
 * Compare: result lines re-synced on keys (GtCertRides' compare), table lines by a streaming merge on their sorted keys:
 *     T15D<TAB>key<TAB>SAME|EXPECTED|DIFF<TAB>NEW's value       (a DIFF / EXPECTED also gets "  OLD<TAB>…" and "  NEW<TAB>…")
 * Footer: "# T15D done: N SAME, N DIFF, N EXPECTED in S s".
 * PROGRESS (coordinator 11-09, the 15c lesson -- 952 s of "inputs read" with no log line tripped v19b's stall alarm): every phase that
 * can run for more than a few seconds logs "GTCERT track15d ..." at least every 15 s while it works (hashing / copying by bytes, the
 * dumps every 10,000 rows, the scripts by sync line), so gt_cert_v20's wait loop always moves on a healthy run.
 */
object GtCert15d {
    private const val TAG = "CertRun"
    private const val MAX_DIFF_LINES = 2000

    private var lastLog = 0L
    /** "GTCERT track15d <msg>" -- every phase line, and progress lines at most once per 15 s (the wait loop shows the last one). */
    private fun progress(msg: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || msg.contains(" phase ") || now - lastLog >= 15_000) { lastLog = now; Log.i(TAG, "GTCERT track15d $msg") }
    }

    /** OLD: the app's code, as the TEST-ONLY copy carries it, on SpatialDbManager's (swapped) handles and the copy's folders. */
    private class OldSide(private val app: Context) : Cert15d.Side {
        override val name = "OLD"
        override fun listing(dir: Path) = File(dir.toString()).listFiles()?.map { it.name } ?: emptyList()
        override fun sync(onProgress: (String) -> Unit) = TrackSyncCertOld.syncTracksFromFiles(app, onProgress).let {
            Cert15d.syncText(it.processed, it.skipped, it.addedRenamed, it.renamed, it.addedNames, it.propsWritten, it.propsTotal, it.failures) }
        override fun resolve(name: String, source: Path) = TrackSyncCertOld.resolveTrackAdd(name, File(source.toString())).name
        override fun props(geomHash: String, onProgress: (String) -> Unit) = TrackSyncCertOld.updateTrackPropertiesForHash(geomHash, onProgress)
        override fun backfill(onProgress: (String) -> Unit) = TrackSyncCertOld.backfillAllTrackProperties(onProgress).let { Cert15d.backfillText(it.written, it.total, it.failures) }
        override fun insertWaypoint(name: String, lat: Double, lon: Double, type: String) = TrackSyncCertOld.insertWaypoint(name, lat, lon, type)
        override fun allTrackGeomHashes() = TrackSyncCertOld.allTrackGeomHashes()
        override fun deleteTrack(id: String) = TrackSyncCertOld.deleteTrackFromDb(id)
        override fun resetChanged() { RiderTrailWriterCertOld.trailsChanged.value = 0L }
        override fun changed(): Boolean = RiderTrailWriterCertOld.trailsChanged.value != 0L
    }

    /** GtImportAndroid's env with the paths on the copy's folders and onTrailsChanged COUNTED. */
    private class CertEnv(base: GtImportEnv, private val tracks: Path, private val root: Path) : GtImportEnv by base {
        var count = 0
        override val paths: GtImportPaths = object : GtImportPaths by base.paths {
            override fun groupTrackRoot(): Path = root
            override fun tracksDir(): Path { FileSystem.SYSTEM.createDirectories(tracks); return tracks }   // created, as ConvoyTrackOps.tracksDir()
        }
        override val platform: GtImportPlatform = object : GtImportPlatform by base.platform {
            override fun onTrailsChanged() { count++ }
        }
    }

    /** This run's own single-flight guards (the app's are SpatialDbManager's -- not touched). */
    private class CertFlight : GtTracks.Flight {
        private val s = java.util.concurrent.atomic.AtomicBoolean(false); private val b = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun beginSync() = s.compareAndSet(false, true)
        override fun endSync() { s.set(false) }
        override fun beginBackfill() = b.compareAndSet(false, true)
        override fun endBackfill() { b.set(false) }
    }

    private class Dirs(val root: File) {
        val spatial = File(root, "grouptrack_spatial.db"); val data = File(root, "grouptrack_data.db")
        val tracks = File(root, "my_tracks"); val gtRoot = File(root, "GroupTrack"); val staging = File(root, "staging")
        val results = File(root, "results.txt"); val tables = File(root, "tables.txt")
    }

    /** sha256 of a file, streamed, with a progress line every 15 s. */
    private fun sha256(f: File, label: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256"); var done = 0L; val total = f.length()
        f.inputStream().use { inp -> val buf = ByteArray(1 shl 20); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n); done += n
            progress("reading input $label: ${done shr 20} MB of ${total shr 20} MB") } }
        return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** a byte copy, with a progress line every 15 s. */
    private fun copy(src: File, dst: File, label: String) {
        var done = 0L; val total = src.length()
        src.inputStream().use { i -> dst.outputStream().use { o -> val buf = ByteArray(1 shl 20)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress("copying $label: ${done shr 20} MB of ${total shr 20} MB") } } }
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        val dbIn = GroupTrackStorage.dir("cert15c_input", app); val trIn = File(GroupTrackStorage.dir("cert15d_input", app), "my_tracks")
        val inS = File(dbIn, "grouptrack_spatial.db"); val inD = File(dbIn, "grouptrack_data.db")
        if (!inS.isFile || !inD.isFile || !trIn.isDirectory) {
            w.write("# T15D: STOP -- no input (need ${inS.path}, ${inD.path} and the folder ${trIn.path}; gt_cert_v20 pushes them). Not run.\n")
            Log.e(TAG, "GTCERT track15d STOP: no input at ${dbIn.path} / ${trIn.path} -- run gt_cert_v20 track15d")
            return
        }
        var same = 0; var diff = 0; var expected = 0; var diffLines = 0
        fun put(s: String) { w.write(s) }
        fun check(key: String, a: String, b: String, write: Boolean) {
            when {
                a == b -> { same++; if (write) put("T15D\t$key\tSAME\t$b\n") }
                key.contains("(EXPECTED") -> { expected++; put("T15D\t$key\tEXPECTED\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                else -> {
                    diff++
                    if (diffLines < MAX_DIFF_LINES) { diffLines++; put("T15D\t$key\tDIFF\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                    Log.w(TAG, "GTCERT track15d DIFF $key\n OLD ${a.take(300)}\n NEW ${b.take(300)}")
                }
            }
        }
        progress("phase input (hash once)", true)
        // ---- the input, hashed ONCE: the iPad compares these lines with its own ----
        for (f in listOf(inS, inD)) { val h = sha256(f, f.name); check("input|db|${f.name}", "len=${f.length()};sha256=$h", "len=${f.length()};sha256=$h", true) }
        for (sfx in listOf("-wal", "-journal", "-shm")) for (f in listOf(File(inS.path + sfx), File(inD.path + sfx)))
            if (f.exists()) put("# T15D: input ${f.name}: len=${f.length()} (copied with its database)\n")
        val trFiles = (trIn.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }
        val trLines = trFiles.map { f -> "${f.name}\tsize=${f.length()};sha256=${sha256(f, "my_tracks")}" }
        check("input|tracks", "n=${trFiles.size};sha=" + Cert15d.sha(trLines.joinToString("\n")), "n=${trFiles.size};sha=" + Cert15d.sha(trLines.joinToString("\n")), true)
        for (l in trLines) { val k = "input|tracks|" + l.substringBefore('\t'); val v = l.substringAfter('\t'); check(k, v, v, true) }
        put("# T15D: input hashed in ${(System.currentTimeMillis() - t0) / 1000} s\n")

        val base = File(app.cacheDir, "gtcert15d")
        progress("phase copies", true)
        base.deleteRecursively(); base.mkdirs()
        val oldD = Dirs(File(base, "old")); val newD = Dirs(File(base, "new"))
        var aborted: String? = null   // CODE RULE 1: null = the run was not aborted
        try {
            for (d in listOf(oldD, newD)) copyIn(d, inS, inD, trFiles)
            put("# T15D: copies made in ${(System.currentTimeMillis() - t0) / 1000} s\n")
            progress("phase input ids", true)
            val inp = run {
                val c = gtOpenDatabase(oldD.spatial.path, true)
                try { val e = gtOpenDatabase(oldD.data.path, true); try { Cert15d.inputs(c, e) { p -> progress(p) } } finally { e.close() } } finally { c.close() }
            }
            put("# T15D: before the run: ${inp.trailIds.size} trails, ${inp.trackIds.size} tracks, ${inp.aliasIds.size} aliases, ${inp.waypointIds.size} waypoints\n")

            // ---- OLD: the app's code on copy "old" ----
            val tO = System.currentTimeMillis()
            oldD.results.bufferedWriter().use { o ->
                val sp = SQLiteDatabase.openDatabase(oldD.spatial.path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    val dt = SQLiteDatabase.openDatabase(oldD.data.path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        val prev = SpatialDbManager.certSwapDatabases(sp, dt)
                        TrackSyncCertOld.certTracks = oldD.tracks; TrackSyncCertOld.certRootDir = oldD.gtRoot
                        try {
                            val ctx = Cert15d.Ctx(FileSystem.SYSTEM, oldD.tracks.toOkioPath(), oldD.gtRoot.toOkioPath(), oldD.staging.toOkioPath(), GtAppDb.wrap(sp))
                            Cert15d.runScript(OldSide(app), ctx, inp, { k, v -> o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\n") }, { p -> progress(p) })
                        } finally {
                            TrackSyncCertOld.certTracks = null; TrackSyncCertOld.certRootDir = null
                            SpatialDbManager.certSwapDatabases(prev.first, prev.second)
                            Log.i(TAG, "GTCERT track15d live handles restored")
                        }
                    } finally { dt.close() }
                } finally { sp.close() }
            }
            put("# T15D: OLD script done in ${(System.currentTimeMillis() - tO) / 1000} s\n")

            // ---- NEW: shared code on copy "new" ----
            val tN = System.currentTimeMillis()
            newD.results.bufferedWriter().use { o ->
                val sp = SQLiteDatabase.openDatabase(newD.spatial.path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    val dt = SQLiteDatabase.openDatabase(newD.data.path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        val conn = GtAppDb.wrap(sp); val dconn = GtAppDb.wrap(dt)
                        val writer = GtWrite(conn, dconn, { java.util.UUID.randomUUID().toString() }, { java.time.Instant.now().toString() })
                        val env = CertEnv(GtImportAndroid(app), newD.tracks.toOkioPath(), newD.gtRoot.toOkioPath())
                        val side = Cert15d.NewSide(GtTracks(conn, dconn, writer, env, CertFlight()), env.files) { env.count }
                        val ctx = Cert15d.Ctx(FileSystem.SYSTEM, newD.tracks.toOkioPath(), newD.gtRoot.toOkioPath(), newD.staging.toOkioPath(), conn)
                        Cert15d.runScript(side, ctx, inp, { k, v -> o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\n") }, { p -> progress(p) })
                    } finally { dt.close() }
                } finally { sp.close() }
            }
            put("# T15D: NEW script done in ${(System.currentTimeMillis() - tN) / 1000} s\n")

            // ---- the tables, from each copy ----
            val tT = System.currentTimeMillis()
            progress("phase dump OLD", true); dump(oldD, inp, "OLD")
            progress("phase dump NEW", true); dump(newD, inp, "NEW")
            put("# T15D: tables dumped in ${(System.currentTimeMillis() - tT) / 1000} s\n")
        } catch (e: Throwable) {
            aborted = e.toString(); put("# T15D: ABORTED $e\n"); Log.e(TAG, "GTCERT track15d aborted", e)
        }

        // ---- compare ----
        progress("phase compare", true)
        if (oldD.results.exists() && newD.results.exists()) compare(oldD.results, newD.results) { k, a, b -> check(k, a, b, true) }
        var unlisted = 0
        if (oldD.tables.exists() && newD.tables.exists()) merge(oldD.tables, newD.tables) { k, a, b ->
            val listed = a.second || b.second
            if (!listed && a.first == b.first) unlisted++
            check("tables|$k", a.first, b.first, listed)
        }
        put("# T15D tables: $unlisted unchanged unlisted rows SAME (counted, not written; covered by the tables|s9|fp lines)\n")
        if (diff > diffLines) put("# T15D: ${diff - diffLines} more DIFF lines not written (see logcat CertRun)\n")
        put("# T15D done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s" +
            (if (aborted != null) " (ABORTED: $aborted)" else "") + "\n")
        Log.i(TAG, "GTCERT track15d: $same SAME, $diff DIFF, $expected EXPECTED")
    }

    /** a side's folder: the input databases COPIED in (with -wal / -journal when the input has them), my_tracks file by file in name
     *  order, and the empty GroupTrack root and staging folders. */
    private fun copyIn(d: Dirs, inS: File, inD: File, trFiles: List<File>) {
        d.root.mkdirs(); d.tracks.mkdirs(); d.gtRoot.mkdirs(); d.staging.mkdirs()
        for ((src, dst) in listOf(Pair(inS, d.spatial), Pair(inD, d.data))) {
            copy(src, dst, "${d.root.name}/${dst.name}")
            for (sfx in listOf("-wal", "-journal")) { val s = File(src.path + sfx); if (s.isFile) s.copyTo(File(dst.path + sfx), overwrite = true) }
        }
        for (f in trFiles) f.copyTo(File(d.tracks, f.name), overwrite = true)
    }

    private fun dump(d: Dirs, inp: Cert15d.Inputs, label: String) {
        val c = gtOpenDatabase(d.spatial.path, true)
        try {
            val e = gtOpenDatabase(d.data.path, true)
            try { d.tables.bufferedWriter().use { o -> Cert15d.dumpTables(c, e, inp, { k, v, listed -> line(o, k, v, listed) }, { p -> progress("$label $p") }) } }
            finally { e.close() }
        } finally { c.close() }
    }

    private fun line(o: java.io.Writer, k: String, v: String, listed: Boolean) {
        o.write(k); o.write("\t"); o.write(Cert15d.esc(v)); o.write("\t"); o.write(if (listed) "L" else "U"); o.write("\n")
    }

    /** key \t value \t L|U */
    private fun splitTable(l: String): Triple<String, String, Boolean> {
        val a = l.indexOf('\t'); val b = l.lastIndexOf('\t')
        return if (a < 0 || b <= a) Triple(l, "", true) else Triple(l.substring(0, a), l.substring(a + 1, b), l.substring(b + 1) == "L")
    }

    /** GtCert15c's streaming merge (the two table files are in the same ascending key order, Cert15d.dumpTables). */
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

    /** GtCertRides' compare (STEP14C-B), unchanged: re-syncs on keys, an extra line on one side is one DIFF ("only OLD" / "only NEW"). */
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
