package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyConfig
import com.geeksville.mesh.convoy.ConvoyTileCalculator
import com.geeksville.mesh.convoy.ConvoyTileDownloader
import com.geeksville.mesh.convoy.DownloadQueueCertOld
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.VectorTilesCertOld
import com.grouptrack.shared.cert.Cert15d
import com.grouptrack.shared.cert.Cert16c
import com.grouptrack.shared.db.gtOpenDatabase
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.json.JSONArray
import java.io.File

/**
 * STEP16C-2026-11-11 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 16c certification on the Droid
 * (GtCertRun mode "queue16c") -- the app's (2.7e) save-by-area writes (the GENERATED copies VectorTilesCertOld / DownloadQueueCertOld,
 * made by make_old_copies_16c.py) against the shared GtVectorQueue / GtTileMath / GtQueueJson, through ONE script (Cert16c, see its
 * header for the cases).
 * NOTHING LIVE IS WRITTEN: the OLD copy's enqueueArea adds to DownloadQueueCertOld.collected (no init, no saveQueue, no worker); NEW's
 * platform collects. READ only: GroupTrack/cert15c_input/grouptrack_spatial.db (the tracks -- 15c's push), GroupTrack/cert16c_input/
 * recipes (*.gtmaps files; gt_cert_v24 step 1h pushes the Mac's _data/droid1_2026-11-06/recipes), and the app's own download_queue.json
 * (filesDir/download_queue/ -- its boxes are written into this report as hex bits for the iPad). The crafted recipe folders are made
 * in app.cacheDir/gtcert16c/ (deleted at the start of the next run). No input -> STOP (no fallback).
 * DOWNLOAD_ZOOM: 2.7e's segmentCells default reads ConvoyConfig.DOWNLOAD_ZOOM (a var); the cert's NEW platform answers 18, so the run
 * STOPs unless ConvoyConfig.DOWNLOAD_ZOOM == 18.
 * Report (as GtCert15f):  Q16C<TAB>key<TAB>SAME|EXPECTED|DIFF<TAB>NEW's value   (a DIFF / EXPECTED also gets "  OLD<TAB>…" and "  NEW<TAB>…")
 * Footer: "# Q16C done: N SAME, N DIFF, N EXPECTED in S s". Progress: "GTCERT queue16c ..." at least every 15 s.
 */
object GtCert16c {
    private const val TAG = "CertRun"
    private const val MAX_DIFF_LINES = 2000

    private var lastLog = 0L
    private fun progress(msg: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastLog >= 15_000) { lastLog = now; val rt = Runtime.getRuntime()
            Log.i(TAG, "GTCERT queue16c $msg [heap ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB of ${rt.maxMemory() / 1048576}]") }
    }

    /** OLD: the app's code, as the TEST-ONLY copies carry it. */
    private class OldSide(private val app: Context) : Cert16c.Side {
        override val name = "OLD"
        private fun file(p: Path) = File(p.toString())
        override fun setRecipesDir(dir: Path) { DownloadQueueCertOld.recipesDir = file(dir) }
        override fun boxesOf(f: Path): List<DoubleArray> = VectorTilesCertOld.boxesOf(file(f))
        override fun recipeBoxes(): Pair<String, List<DoubleArray>>? = VectorTilesCertOld.recipeBoxes()
        override fun squaresInBox(n: Double, s: Double, e: Double, w: Double, into: MutableSet<Long>) = VectorTilesCertOld.squaresInBox(n, s, e, w, into)
        override fun squaresFor(segments: List<List<Pair<Double, Double>>>, into: MutableSet<Long>) = VectorTilesCertOld.squaresFor(segments, into)
        override fun enqueueWithElevation(n: Double, s: Double, e: Double, w: Double, replace: Boolean, what: String) =
            VectorTilesCertOld.enqueueWithElevation(app, n, s, e, w, replace, what)
        override fun queueSquares(sq: Set<Long>, replace: Boolean, why: String) = VectorTilesCertOld.queueSquares(app, sq, replace, why)
        override fun queueSquaresNamed(sq: Set<Long>, names: Map<Long, Set<String>>, replace: Boolean, why: String) =
            VectorTilesCertOld.queueSquaresNamed(app, sq, names, replace, why)
        override fun queueFromRecipe(count: Int) = VectorTilesCertOld.queueFromRecipe(app, count)
        override fun quickEstimate(n: Double, s: Double, e: Double, w: Double, zMin: Int, zMax: Int): Cert16c.Est =
            ConvoyTileCalculator.quickEstimate(n, s, e, w, zMin, zMax).let { Cert16c.Est(it.tileCount, it.estimatedMB.toRawBits(), it.withinCeiling, it.zoomMin, it.zoomMax) }
        override fun segmentCells(n: Double, s: Double, e: Double, w: Double, sizeTiles: Int, zMax: Int): List<DoubleArray> =
            ConvoyTileDownloader.segmentCells(n, s, e, w, sizeTiles, zMax = zMax)
        /** renumbered in append order (q1, q2, ...; createdAt 0) -- 2.7e's UUID / clock defaults are the only fields not compared as made */
        override fun takeEntries(): Pair<List<String>, String> {
            val es = DownloadQueueCertOld.collected.mapIndexed { i, e -> e.copy(id = "q${i + 1}", createdAt = 0L) }
            DownloadQueueCertOld.collected.clear()
            val arr = JSONArray(); for (e in es) arr.put(e.toJson())   // saveQueue's text: JSONArray + put, toString(2)
            return Pair(es.map { it.toJson().toString() }, arr.toString(2))
        }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256"); var done = 0L; val total = f.length()
        f.inputStream().use { inp -> val buf = ByteArray(1 shl 20); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n); done += n
            progress("reading input ${f.name}: ${done shr 20} MB of ${total shr 20} MB") } }
        return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        val inS = File(GroupTrackStorage.dir("cert15c_input", app), "grouptrack_spatial.db")
        val recIn = File(GroupTrackStorage.dir("cert16c_input", app), "recipes")
        val nRec = recIn.listFiles { x -> x.name.endsWith(".gtmaps") }?.size ?: 0
        if (!inS.isFile || nRec == 0) {
            w.write("# Q16C: STOP -- no input (need ${inS.path} and the recipes in ${recIn.path}; gt_cert_v24 pushes them). Not run.\n")
            Log.e(TAG, "GTCERT queue16c STOP: no input at ${inS.path} / ${recIn.path} -- run gt_cert_v24 queue16c")
            return
        }
        if (ConvoyConfig.DOWNLOAD_ZOOM != 18) {
            w.write("# Q16C: STOP -- ConvoyConfig.DOWNLOAD_ZOOM is ${ConvoyConfig.DOWNLOAD_ZOOM}, the cert's platform answers 18. Not run.\n")
            Log.e(TAG, "GTCERT queue16c STOP: DOWNLOAD_ZOOM ${ConvoyConfig.DOWNLOAD_ZOOM}"); return
        }
        var same = 0; var diff = 0; var expected = 0; var diffLines = 0
        fun put(s: String) { w.write(s) }
        fun check(key: String, a: String, b: String) {
            when {
                a == b -> { same++; put("Q16C\t$key\tSAME\t$b\n") }
                key.contains("(EXPECTED") -> { expected++; put("Q16C\t$key\tEXPECTED\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                else -> {
                    diff++
                    if (diffLines < MAX_DIFF_LINES) { diffLines++; put("Q16C\t$key\tDIFF\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                    Log.w(TAG, "GTCERT queue16c DIFF $key\n OLD ${a.take(300)}\n NEW ${b.take(300)}")
                }
            }
        }
        var aborted: String? = null   // CODE RULE 1: null = the run was not aborted
        try {
            val fs = FileSystem.SYSTEM
            // ---- the input, the same on every side ----
            progress("phase input (hash once)", true)
            val h = sha256(inS); check("input|db|${inS.name}", "len=${inS.length()};sha256=$h", "len=${inS.length()};sha256=$h")
            for (sfx in listOf("-wal", "-journal", "-shm")) { val x = File(inS.path + sfx); if (x.exists()) put("# Q16C: input ${x.name}: len=${x.length()} (beside the database)\n") }
            val tracks = run {   // STEP16C-FIXB-2026-11-11: Android's gtOpenDatabase opens read/write, and the pushed input is not the app's -- read a COPY
                val cd = File(app.cacheDir, "gtcert16c_db"); cd.deleteRecursively(); cd.mkdirs()
                val cp = File(cd, inS.name); progress("copying the input database", true); inS.copyTo(cp, overwrite = true)
                val c = gtOpenDatabase(cp.path, true)
                try { Cert16c.readTracks(c) } finally { c.close(); cd.deleteRecursively() }
            }
            val tt = Cert16c.tracksText(tracks); check("input|tracks", tt, tt)
            val recFiles = Cert16c.recipeFiles(fs, recIn.toOkioPath())
            for (p in recFiles) { val b = fs.read(p) { readByteString() }; val v = "size=${b.size};sha256=" + b.sha256().hex(); check("input|recipe|" + p.name, v, v) }
            check("input|zoom", "DOWNLOAD_ZOOM=${ConvoyConfig.DOWNLOAD_ZOOM}", "DOWNLOAD_ZOOM=18")
            val qf = File(app.filesDir, "download_queue/download_queue.json")
            val qboxes = ArrayList<DoubleArray>()
            if (qf.isFile) {
                val arr = JSONArray(qf.readText())
                for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue
                    val b = doubleArrayOf(o.optDouble("north"), o.optDouble("south"), o.optDouble("east"), o.optDouble("west"))
                    if (b.any { it.isNaN() }) continue
                    qboxes.add(b) }
                put("# Q16C: download_queue.json: ${arr.length()} entries, ${qboxes.size} boxes (read only)\n")
            } else put("# Q16C: no download_queue.json on this Droid (${qf.path}) -- F has no boxes\n")
            qboxes.forEachIndexed { i, b -> val t = Cert16c.boxText(b); check("input|qbox|" + (i + 1).toString().padStart(4, '0'), t, t) }
            val root = File(app.cacheDir, "gtcert16c"); root.deleteRecursively()
            val crafted = Cert16c.craft(fs, File(root, "craft").toOkioPath())
            val inp = Cert16c.Inputs(tracks, recIn.toOkioPath(), recFiles, qboxes, crafted)
            put("# Q16C: input: ${tracks.size} tracks, ${recFiles.size} recipes, ${qboxes.size} queue boxes; made in ${(System.currentTimeMillis() - t0) / 1000} s\n")

            // ---- OLD, then NEW ----
            val tO = System.currentTimeMillis()
            val old = LinkedHashMap<String, String>()
            Cert16c.run(OldSide(app), inp, fs, { k, v -> old[k] = Cert15d.esc(v) }) { p -> progress(p) }
            put("# Q16C: OLD script done in ${(System.currentTimeMillis() - tO) / 1000} s (${old.size} values)\n")
            val tN = System.currentTimeMillis()
            val new = LinkedHashMap<String, String>()
            Cert16c.run(Cert16c.NewSide(fs), inp, fs, { k, v -> new[k] = Cert15d.esc(v) }) { p -> progress(p) }
            put("# Q16C: NEW script done in ${(System.currentTimeMillis() - tN) / 1000} s (${new.size} values)\n")
            progress("phase compare", true)
            for ((k, b) in new) { val a = old[k]; if (a == null) check("$k (only NEW)", "", b) else check(k, a, b) }
            for ((k, a) in old) if (k !in new) check("$k (only OLD)", a, "")
        } catch (e: Throwable) {
            aborted = e.toString(); put("# Q16C: ABORTED $e\n"); Log.e(TAG, "GTCERT queue16c aborted", e)
        } finally {
            DownloadQueueCertOld.collected.clear()
        }
        if (diff > diffLines) put("# Q16C: ${diff - diffLines} more DIFF lines not written (see logcat CertRun)\n")
        put("# Q16C done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s" +
            (if (aborted != null) " -- ABORTED" else "") + "\n")
        Log.i(TAG, "GTCERT queue16c: $same SAME, $diff DIFF, $expected EXPECTED")
    }
}
