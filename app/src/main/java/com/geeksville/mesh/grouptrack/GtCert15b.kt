package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.ImportConverterCertOld
import com.geeksville.mesh.convoy.ImportParseCertOld
import com.grouptrack.shared.cert.Cert15b
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * STEP15B-2026-11-09 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15b certification on the Droid --
 * 2.7e's import parsers and converter (the TEST-ONLY copies ImportParseCertOld / ImportConverterCertOld, made by make_old_copies_15b.py)
 * against the shared GtGpxParse / GtImportConverter, on every file in GroupTrack/cert15b/ (adb-pushed by gt_cert_v16 parse15b).
 * NOTHING LIVE IS READ OR WRITTEN: no database, no my_tracks, no Download; the converter writes only into app.cacheDir/gtcert15b/<side>/.
 * Both sides go through ONE runner (shared Cert15b.runFile -- same cases, same order, same fingerprints); each side's lines go to its
 * own file, then the two files are compared re-syncing on keys (as GtCertRides):
 *     P15B<TAB>key<TAB>SAME|EXPECTED|DIFF<TAB>NEW's value          (a DIFF also gets "  OLD<TAB>value" and "  NEW<TAB>value" lines)
 * A key carrying "(EXPECTED" is known different (an "expected_" test file); its difference is written EXPECTED and not counted.
 * The iPad simulator (GtCert15bMacTest) reads this report and the same test files and must give NEW's value for every key.
 */
object GtCert15b {
    private const val TAG = "CertRun"

    /** OLD: 2.7e, as the TEST-ONLY copies carry it. Files are java.io.File as 2.7e used them. */
    private object OldSide : Cert15b.Side {
        override val name = "OLD"
        override fun readText(fs: FileSystem, path: Path): String = File(path.toString()).readText()
        override fun importExt(fileName: String) = ImportParseCertOld.importExt(File(fileName))
        override fun trailMarked(trk: String, file: String) = ImportParseCertOld.trailMarked(trk, file)
        override fun extractEarliestTime(content: String) = ImportParseCertOld.extractEarliestTime(content)
        override fun sanitizeFilename(name: String) = ImportParseCertOld.sanitizeFilename(name)
        override fun waypoints(text: String) = ImportParseCertOld.parseGpxWaypoints(text).map { Cert15b.Wpt(it.name, it.lat, it.lon, it.type, it.description) }
        override fun routes(text: String) = ImportParseCertOld.parseGpxRoutes(text).map { Cert15b.Rte(it.name, it.points, it.description) }
        override fun pointsFull(text: String) = ImportParseCertOld.parseGpxTrackPointsFull(text).map { Cert15b.Pt(it.lat, it.lon, it.eleM, it.timeMs) }
        override fun toGpx(fs: FileSystem, src: Path, outDir: Path, asTrails: Boolean): Cert15b.Conv =
            ImportConverterCertOld.toGpx(File(src.toString()), File(outDir.toString()), asTrails).let { Cert15b.Conv(it.file.name, it.lines, it.tracks, it.points) }
    }

    /** RXFIX-2026-11-09: the files 2.7e (the OLD side) cannot finish on Android in hours -- Kotlin's findAll copies the whole text per
     *  match there (GtRx). OLD is not run on them on the Droid; the JVM proof (11-09, 0 DIFF) covers OLD = NEW for them; NEW runs them and
     *  writes NEWONLY lines, which the iPad check still compares. */
    private val OLD_TOO_SLOW = setOf("TrailsAndPathways_7580073023867142241.kmz", "onx-markups-04222026.gpx", "onx-markups-05122026.gpx",
        "onx-markups-2026-04-21.gpx")

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        val inDir = GroupTrackStorage.dir("cert15b", app)
        val files = (inDir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }
        w.write("# P15B test files in ${inDir.path}: ${files.size}\n")
        for (f in files) w.write("# P15B file ${f.name} len=${f.length()}\n")
        if (files.isEmpty()) { w.write("# P15B: no test files -- push the test set first (gt_cert_v16 parse15b)\n"); return }
        val base = File(app.cacheDir, "gtcert15b")
        base.deleteRecursively(); base.mkdirs()
        val fs = FileSystem.SYSTEM
        val sideFiles = HashMap<String, File>()
        val newOnly = File(base, "new_only.txt")   // RXFIX-2026-11-09: NEW's lines for the OLD_TOO_SLOW files
        val noW = newOnly.bufferedWriter()
        try {
        for (side in listOf<Cert15b.Side>(OldSide, Cert15b.NewSide)) {
            val out = File(base, "${side.name.lowercase()}.txt")
            val work = File(base, side.name.lowercase()).toOkioPath()
            fs.createDirectories(work)
            out.bufferedWriter().use { o ->
                for (f in files) {
                    if (side.name == "OLD" && f.name in OLD_TOO_SLOW) {
                        w.write("# P15B OLD not run on the Droid: ${f.name} (RXFIX-2026-11-09: 2.7e takes hours on Android; the JVM proof covers it)\n")
                        Log.i(TAG, "GTCERT parse15b OLD ${f.name} not run (too slow on Android -- RXFIX)"); continue
                    }
                    val dest: java.io.Writer = if (side.name == "NEW" && f.name in OLD_TOO_SLOW) noW else o
                    val ts = System.currentTimeMillis()
                    Cert15b.runFile(side, fs, f.toOkioPath(), work) { k, v -> dest.write(k); dest.write("\t"); dest.write(Cert15b.esc(v)); dest.write("\n") }
                    Log.i(TAG, "GTCERT parse15b ${side.name} ${f.name} ${System.currentTimeMillis() - ts} ms")
                }
            }
            sideFiles[side.name] = out
        }
        } finally { noW.close() }
        var same = 0; var diff = 0; var expected = 0
        compare(sideFiles.getValue("OLD"), sideFiles.getValue("NEW")) { key, a, b ->
            when {
                a == b -> { same++; w.write("P15B\t$key\tSAME\t$b\n") }
                key.contains("(EXPECTED") -> { expected++; w.write("P15B\t$key\tEXPECTED\t$b\n  OLD\t$a\n  NEW\t$b\n") }
                else -> { diff++; w.write("P15B\t$key\tDIFF\t$b\n  OLD\t$a\n  NEW\t$b\n"); Log.w(TAG, "GTCERT parse15b DIFF $key\n OLD ${a.take(300)}\n NEW ${b.take(300)}") }
            }
        }
        var newonly = 0   // RXFIX-2026-11-09
        newOnly.forEachLine { l -> val t = l.indexOf('\t'); if (t > 0) { newonly++; w.write("P15B\t${l.substring(0, t)}\tNEWONLY\t${l.substring(t + 1)}\n") } }
        w.write("# P15B done: $same SAME, $diff DIFF, $expected EXPECTED, $newonly NEWONLY in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT parse15b: $same SAME, $diff DIFF, $expected EXPECTED")
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
