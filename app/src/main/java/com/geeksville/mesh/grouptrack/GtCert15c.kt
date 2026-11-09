package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.RiderTrailWriterCertOld
import com.geeksville.mesh.convoy.SpatialDbManager
import com.grouptrack.shared.cert.Cert15c
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.rider.GtRiderScan
import com.grouptrack.shared.rider.GtRiderTrails
import com.grouptrack.shared.spatial.GtWrite
import java.io.File

/**
 * STEP15C-2026-11-09 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15c certification on the Droid
 * (GtCertRun mode "rider15c") -- 2.7e's rider trails (the TEST-ONLY copies RiderTrailWriterCertOld / RiderTrailScannerCertOld, made by
 * make_old_copies_15c.py) against the shared GtRiderTrails / GtRiderScan, INVENTORY 7.2 R1.
 NOTHING LIVE IS READ OR WRITTEN:
 *  - STEP15C-B (Fred 11-09, Q4): THE INPUT is the Mac's copy live_2026-11-08 -- the same data the iPad test uses -- adb-pushed by
 *    gt_cert_v19b to GroupTrack/cert15c_input/ (grouptrack_spatial.db + grouptrack_data.db). NOT the frozen copy: a re-freeze would
 *    reset the lookups cert's BASELINE_FROZEN, and the frozen copy is not what the Mac has. No input -> the run STOPS (no fallback).
 *  - the input is only COPIED -- never opened -- into app.cacheDir/gtcert15c/old/ and /new/ (both databases, with any -wal /
 *    -journal); each side runs on its OWN copy, so the pushed input stays byte-identical for the next run;
 *  - OLD runs with SpatialDbManager's two handles pointed at copy "old" (certSwapDatabases), put back in a finally; its "the maps
 *    reload" stamp is the copy's own trailsChanged flow, which no map collects;
 *  - NEW runs on copy "new" through the app's own handle type (GtAppDb.wrap of a SQLiteDatabase, exactly what the front door will
 *    pass) with a GtWrite of its own and GtImportAndroid's env, whose onTrailsChanged is COUNTED here, never sent to the maps;
 *  - the launch jobs that could touch the handles stay off (GtCertRun.active, set when the request was taken; GtCertGuard);
 *  - the copies are deleted at the start of the next run (they stay for inspection until then).
 * One runner for both sides (shared Cert15c.runScript -- same cases, same order), then shared Cert15c.dumpTables on each copy; the
 * result lines are compared re-syncing on keys (GtCertRides' compare), the table lines by a streaming merge on their (sorted) keys:
 *     R15C<TAB>key<TAB>SAME|EXPECTED|DIFF<TAB>NEW's value       (a DIFF / EXPECTED also gets "  OLD<TAB>…" and "  NEW<TAB>…")
 * Table rows that are not rider rows and were not made by the run are compared but not written when SAME (230k trails); the table
 * fingerprint lines (tables|s9|fp:…) cover them. Footer: "# R15C done: N SAME, N DIFF, N EXPECTED in S s".
 * The iPad (GtCertRider15cMacTest) runs NEW on the SAME files and must equal NEW's values here, value for value; its input
 * fingerprints (input|s9|fp:…) must equal the Droid's (they prove the two inputs are the same data).
 */
object GtCert15c {
    private const val TAG = "CertRun"
    private const val MAX_DIFF_LINES = 2000

    /** OLD: 2.7e, as the TEST-ONLY copies carry it, on SpatialDbManager's (swapped) handles. */
    private object OldSide : Cert15c.Side {
        override val name = "OLD"
        override fun parseGeometry(wkt: String?) = RiderTrailWriterCertOld.parseGeometry(wkt).map { GtRiderScan.Pt(it.lon, it.lat) }
        override fun scanAll(onProgress: (Int, Int) -> Unit): Cert15c.Res = RiderTrailWriterCertOld.scanAll(onProgress).let {
            Cert15c.Res(it.tracksScanned, it.trailsAdded, it.miles, it.tracksRemoved, it.trailsTotal, it.trailsRemoved, it.trailsUnchanged, it.milesTotal)
        }
        override fun scanTrackByHash(geomHash: String) = RiderTrailWriterCertOld.scanTrackByHash(geomHash)
        override fun scanTrack(trackName: String?, geometry: String?) = RiderTrailWriterCertOld.scanTrack(trackName, geometry)
        override fun resetChanged() { RiderTrailWriterCertOld.trailsChanged.value = 0L }
        override fun changed(): Boolean = RiderTrailWriterCertOld.trailsChanged.value != 0L
    }

    /** GtImportAndroid's env with onTrailsChanged COUNTED instead of sent to the maps (the copies are not what the maps show). */
    private class CountingEnv(base: GtImportEnv) : GtImportEnv by base {
        var count = 0
        override val platform: GtImportPlatform = object : GtImportPlatform by base.platform {
            override fun onTrailsChanged() { count++ }
        }
    }

    private class Dirs(val root: File) {
        val spatial = File(root, "grouptrack_spatial.db"); val data = File(root, "grouptrack_data.db")
        val results = File(root, "results.txt"); val tables = File(root, "tables.txt"); val input = File(root, "input.txt")
    }

    /** STEP15C-B: the input folder gt_cert_v19b pushes to (GroupTrack/cert15c_input/). */
    private fun inputDir(app: Context): File = GroupTrackStorage.dir("cert15c_input", app)

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val buf = ByteArray(1 shl 16); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun run(app: Context, w: java.io.Writer) {
        val inDir = inputDir(app)
        val inS = File(inDir, "grouptrack_spatial.db"); val inD = File(inDir, "grouptrack_data.db")
        if (!inS.isFile || !inD.isFile) {
            // STEP15C-B: no fallback to the frozen copy (Fred 11-09)
            w.write("# R15C: STOP -- no input at ${inDir.path} (need grouptrack_spatial.db + grouptrack_data.db; gt_cert_v19b pushes them). Not run.\n")
            Log.e(TAG, "GTCERT rider15c STOP: no input at ${inDir.path} -- push the Mac's live_2026-11-08 there (gt_cert_v19b rider15c)")
            return
        }
        for (f in listOf(inS, inD)) w.write("# R15C: input ${f.name} len=${f.length()} sha256=${sha256(f)}\n")
        runOn(app, inS.path, inD.path, inDir.path, w)
    }

    private fun runOn(app: Context, spatialPath: String, dataPath: String, stamp: String, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0; var expected = 0; var diffLines = 0
        fun put(s: String) { w.write(s) }
        fun check(key: String, a: String, b: String, write: Boolean) {
            when {
                a == b -> { same++; if (write) put("R15C\t$key\tSAME\t$b\n") }
                key.contains("(EXPECTED") -> { expected++; put("R15C\t$key\tEXPECTED\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                else -> {
                    diff++
                    if (diffLines < MAX_DIFF_LINES) { diffLines++; put("R15C\t$key\tDIFF\t$b\n  OLD\t$a\n  NEW\t$b\n")
                        // STEP15C-B (Fred 11-09, Q3): a trail row (s1) or a new trail's properties (s3) that differs is named -- never tolerated
                        if (key.startsWith("tables|s1|") || key.startsWith("tables|s3|")) put("  NOTE\tTRAIL ROW DIFFERS (WKT / geom_hash / columns) -- investigate (Q3), not tolerated\n") }
                    Log.w(TAG, "GTCERT rider15c DIFF $key\n OLD ${a.take(300)}\n NEW ${b.take(300)}")
                }
            }
        }
        put("# R15C: input folder $stamp\n")
        val base = File(app.cacheDir, "gtcert15c")
        base.deleteRecursively(); base.mkdirs()
        val oldD = Dirs(File(base, "old")); val newD = Dirs(File(base, "new"))
        for (path in listOf(spatialPath, dataPath)) for (sfx in listOf("", "-wal", "-journal", "-shm")) {
            val f = File(path + sfx); put("# R15C: input ${f.name}: " + (if (f.exists()) "len=${f.length()} mtime=${f.lastModified()}" else "none") + "\n")
        }
        var aborted: String? = null   // CODE RULE 1: null = the run was not aborted
        try {
            for (d in listOf(oldD, newD)) copyIn(d, spatialPath, dataPath)
            put("# R15C: copies made in ${(System.currentTimeMillis() - t0) / 1000} s\n")
            // what each copy holds before its side runs (read from the copy itself, never from the input files)
            val inOld = inputs(oldD); val inNew = inputs(newD)
            put("# R15C: before the run: ${inOld.trailIds.size} trails, ${inOld.aliasIds.size} trail aliases, ${inOld.trackHashes.size} tracks\n")
            Log.i(TAG, "GTCERT rider15c inputs read (${(System.currentTimeMillis() - t0) / 1000} s)")

            // ---- OLD: 2.7e on copy "old" ----
            val tO = System.currentTimeMillis()
            oldD.results.bufferedWriter().use { o ->
                val sp = SQLiteDatabase.openDatabase(oldD.spatial.path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    val dt = SQLiteDatabase.openDatabase(oldD.data.path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        val prev = SpatialDbManager.certSwapDatabases(sp, dt)
                        try {
                            Cert15c.runScript(OldSide, inOld, { k, v -> o.write(k); o.write("\t"); o.write(Cert15c.esc(v)); o.write("\n") },
                                { p -> Log.i(TAG, "GTCERT rider15c $p") })
                        } finally {
                            SpatialDbManager.certSwapDatabases(prev.first, prev.second)
                            Log.i(TAG, "GTCERT rider15c live handles restored")
                        }
                    } finally { dt.close() }
                } finally { sp.close() }
            }
            put("# R15C: OLD script done in ${(System.currentTimeMillis() - tO) / 1000} s\n")

            // ---- NEW: shared code on copy "new" ----
            val tN = System.currentTimeMillis()
            newD.results.bufferedWriter().use { o ->
                val sp = SQLiteDatabase.openDatabase(newD.spatial.path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    val dt = SQLiteDatabase.openDatabase(newD.data.path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        val conn = GtAppDb.wrap(sp); val dconn = GtAppDb.wrap(dt)
                        // the writer exactly as SpatialDbManager.writer() makes it (UUID + Instant.now)
                        val writer = GtWrite(conn, dconn, { java.util.UUID.randomUUID().toString() }, { java.time.Instant.now().toString() })
                        val env = CountingEnv(GtImportAndroid(app))
                        val side = Cert15c.NewSide(GtRiderTrails(conn, dconn, writer, env)) { env.count }
                        Cert15c.runScript(side, inNew, { k, v -> o.write(k); o.write("\t"); o.write(Cert15c.esc(v)); o.write("\n") },
                            { p -> Log.i(TAG, "GTCERT rider15c $p") })
                    } finally { dt.close() }
                } finally { sp.close() }
            }
            put("# R15C: NEW script done in ${(System.currentTimeMillis() - tN) / 1000} s\n")

            // ---- the tables, from each copy ----
            val tT = System.currentTimeMillis()
            dump(oldD, inOld, oldD.tables); dump(newD, inNew, newD.tables)
            put("# R15C: tables dumped in ${(System.currentTimeMillis() - tT) / 1000} s\n")
        } catch (e: Throwable) {
            aborted = e.toString(); put("# R15C: ABORTED $e\n"); Log.e(TAG, "GTCERT rider15c aborted", e)
        }

        // ---- compare ----
        if (oldD.input.exists() && newD.input.exists()) merge(oldD.input, newD.input) { k, a, b -> check("input|$k", a.first, b.first, true) }
        if (oldD.results.exists() && newD.results.exists()) compare(oldD.results, newD.results) { k, a, b -> check(k, a, b, true) }
        var unlisted = 0
        if (oldD.tables.exists() && newD.tables.exists()) merge(oldD.tables, newD.tables) { k, a, b ->
            val listed = a.second || b.second
            if (!listed && a.first == b.first) unlisted++
            check("tables|$k", a.first, b.first, listed)
        }
        put("# R15C tables: $unlisted unchanged non-rider rows SAME (counted, not written; covered by the tables|s9|fp lines)\n")
        if (diff > diffLines) put("# R15C: ${diff - diffLines} more DIFF lines not written (see logcat CertRun)\n")
        put("# R15C done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s" +
            (if (aborted != null) " (ABORTED: $aborted)" else "") + "\n")
        Log.i(TAG, "GTCERT rider15c: $same SAME, $diff DIFF, $expected EXPECTED")
    }

    /** a side's folder: the input databases COPIED in (with -wal / -journal when the input has them). */
    private fun copyIn(d: Dirs, spatialPath: String, dataPath: String) {
        d.root.mkdirs()
        for ((src, dst) in listOf(Pair(File(spatialPath), d.spatial), Pair(File(dataPath), d.data))) {
            src.copyTo(dst, overwrite = true)
            for (sfx in listOf("-wal", "-journal")) { val s = File(src.path + sfx); if (s.isFile) s.copyTo(File(dst.path + sfx), overwrite = true) }
        }
    }

    /** the copy's contents before its side runs ([Cert15c.Inputs]), and its table fingerprints into [Dirs.input] (s0 / s9 lines only). */
    private fun inputs(d: Dirs): Cert15c.Inputs {
        val c = gtOpenDatabase(d.spatial.path, true)
        try {
            val e = gtOpenDatabase(d.data.path, true)
            try {
                val inp = Cert15c.inputs(c, e)
                d.input.bufferedWriter().use { o ->
                    Cert15c.dumpTables(c, e, inp) { k, v, listed -> if (listed && (k.contains("|#cols:") || k.startsWith("s9|"))) line(o, k, v, listed) }
                }
                return inp
            } finally { e.close() }
        } finally { c.close() }
    }

    private fun dump(d: Dirs, inp: Cert15c.Inputs, to: File) {
        val c = gtOpenDatabase(d.spatial.path, true)
        try {
            val e = gtOpenDatabase(d.data.path, true)
            try { to.bufferedWriter().use { o -> Cert15c.dumpTables(c, e, inp) { k, v, listed -> line(o, k, v, listed) } } }
            finally { e.close() }
        } finally { c.close() }
    }

    private fun line(o: java.io.Writer, k: String, v: String, listed: Boolean) {
        o.write(k); o.write("\t"); o.write(Cert15c.esc(v)); o.write("\t"); o.write(if (listed) "L" else "U"); o.write("\n")
    }

    /** key \t value \t L|U */
    private fun splitTable(l: String): Triple<String, String, Boolean> {
        val a = l.indexOf('\t'); val b = l.lastIndexOf('\t')
        return if (a < 0 || b <= a) Triple(l, "", true) else Triple(l.substring(0, a), l.substring(a + 1, b), l.substring(b + 1) == "L")
    }

    /** the two table files are in the same ascending key order (Cert15c.dumpTables): a streaming merge, one row in memory per side. */
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
