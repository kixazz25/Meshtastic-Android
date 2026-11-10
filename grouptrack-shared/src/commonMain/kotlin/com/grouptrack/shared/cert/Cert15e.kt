package com.grouptrack.shared.cert

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.imports.GtGpxImport
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/**
 * STEP15E-2026-11-09 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15e certification's ONE runner
 * (the GPX import: the screen's per-file loop + importGpxAllArtifacts, INVENTORY 7.2 G1-G12 / K1-K2 / J1-J2 through the screen loop), used
 * by every side so the cases, their order and the table dump cannot drift:
 *  - on the Droid, GtCert15e (app) runs [runScript] with OLD (GpxImportCertOld: the GENERATED copy of the app's import, on
 *    SpatialDbManager's handles pointed at copy "old", its folders pointed at the copy's) and with [NewSide] (shared GtGpxImport on copy
 *    "new" and ITS folders); then [dumpTables] on both copies; the line streams are compared;
 *  - on the iPad simulator, GtCertImport15eMacTest runs [runScript] with [NewSide] on a copy of the same input, then [dumpTables].
 * Every side works on its OWN fresh copy of the databases (15c's input) AND of my_tracks (15d's input). The import's input files are the
 * 15b test set (synthetic + real, gt_cert's cert15b push), each file COPIED into a per-pass staging folder first (the import deletes a
 * source once anything imported).
 *
 * THE FILES: every regular file of the test set whose name does not start with "." and whose size is at most [MAX_BYTES] (the onX
 * exports of 17-29 MB and the 6 MB / 17,147-placemark KMZ would add thousands of track adds, each with a rider scan, to every side --
 * hours; they are the 15b parsers' cases and stay there), in name order.                                              [input|files...]
 * THE SCRIPT, in this order (keys in brackets):
 *   A  "NO, maps on":  every file answered NO, maps for all (mapsSkip empty), two map sources, replace off -- KML / KMZ / GeoJSON are
 *                      converted (lines = tracks), GPX as it is; every track add, waypoint, route (+ notes), the corridor requests
 *                      (RECORDED by the side: enqueueCorridor returns [TILES] and queues nothing)                         [A|...]
 *   B  "YES":          every file answered YES, source "Cert15e trails": every file through the converter (GPX too), every line a TRAIL
 *                      (the mark), points as waypoints; no maps (2.7e sends none for YES)                                  [B|...]
 *   C  "NO again":     A once more with one source and replace on -- every artifact is already there: DUPLICATE / ALIAS / DROP paths,
 *                      the route notes re-attached by hash                                                                 [C|...]
 *   per pass: the totals (the screen's recap), whether "the maps reload" fired, every line the screen would show, every platform call
 *   (enqueueCorridor / saveToDownloads, recorded in order), and after it every file in my_tracks / the pass's staging folder /
 *   cache/import_convert: size, sha256, mtime (known = a time the runner gave a file; now = written during the pass; else the seconds a
 *   GPX <time> gave it)                                                                                                     [<pass>|files|...]
 * THE BATCH DATE is fixed (2026-11-01, every side: the OLD copy's certDate(), the NEW side's env clock), so a trail's agency_id is the
 * same on the Droid and the iPad whatever day each runs.
 *
 * THE TABLES ([dumpTables]): Cert15d.dumpTables (trails, trail_properties, artifact_aliases, tracks, track_properties, waypoints -- its
 * masking and listing, s1..s9) plus routes (t1, keyed by geom_hash) and route_notes (t2, keyed by the owning route's hash, seq, kind),
 * every row and column, run-made ids / times MASKED as 15d does; the t9 lines count them and fingerprint every cell.
 * TEST-ONLY.
 */
object Cert15e {
    /** the largest test file the import takes (bytes) -- see the header */
    const val MAX_BYTES = 400_000L
    /** what a side's recorded enqueueCorridor answers (the MAPS lines print it) */
    const val TILES = 7

    /** One implementation of the 15e import. The runner never calls anything else. */
    interface Side {
        val name: String
        /** the screen's loop over [sel]; [onLine] = each line the screen would add, [onFile] = (index from 1, file name) */
        fun importFiles(sel: List<Path>, trailsAnswer: String, trailsSource: String, mapsSkip: Set<String>, selectedSlots: List<String>,
                        replaceExisting: Boolean, onLine: (String) -> Unit, onFile: (Int, String) -> Unit): GtGpxImport.Totals
        /** the platform calls since [resetCalls], in order: "enqueueCorridor|<hash>|<slot>|<replace>", "saveToDownloads|<name>" */
        fun calls(): List<String>
        fun resetCalls()
        fun resetChanged()
        fun changed(): Boolean
    }

    /** Where the side's folders are: the test files (read only), its my_tracks, its cache/import_convert, its staging folder; [nowMs] its clock. */
    class Ctx(val fs: FileSystem, val inputDir: Path, val tracksDir: Path, val convertDir: Path, val stagingDir: Path, val nowMs: () -> Long)

    /** NEW: the shared code. [calls] / [changeCount] are the env's recordings. */
    class NewSide(private val imp: GtGpxImport, private val recorded: MutableList<String>, private val changeCount: () -> Int) : Side {
        override val name = "NEW"
        private var base = 0
        override fun importFiles(sel: List<Path>, trailsAnswer: String, trailsSource: String, mapsSkip: Set<String>, selectedSlots: List<String>,
                                 replaceExisting: Boolean, onLine: (String) -> Unit, onFile: (Int, String) -> Unit) =
            imp.importFiles(sel, trailsAnswer, trailsSource, mapsSkip, selectedSlots, replaceExisting, onLine, onFile)
        override fun calls(): List<String> = recorded.toList()
        override fun resetCalls() { recorded.clear() }
        override fun resetChanged() { base = changeCount() }
        override fun changed(): Boolean = changeCount() != base
    }

    fun totalsText(t: GtGpxImport.Totals) =
        "imported=[" + t.imported.joinToString("|") + "];skipped=[" + t.skipped.joinToString("|") + "];failed=[" + t.failed.joinToString("|") +
            "];datesCorrected=${t.datesCorrected};waypoints=${t.waypoints};routes=${t.routes};new=${t.new};dupe=${t.dupe};aliased=${t.aliased};skippedCount=${t.skippedCount}"

    /** the test files the import takes (see the header), in name order */
    fun inputFiles(fs: FileSystem, dir: Path): List<Path> = fs.list(dir)
        .filter { !it.name.startsWith(".") && fs.metadataOrNull(it)?.isRegularFile == true && (fs.metadataOrNull(it)?.size ?: 0L) <= MAX_BYTES }
        .sortedBy { it.name }

    /** route_notes ids that were there BEFORE the run (Cert15d.Inputs has the rest) */
    fun noteIds(dataConn: SQLiteConnection): Set<String> {
        val s = HashSet<String>()
        val st = dataConn.prepare("SELECT note_id FROM route_notes WHERE note_id IS NOT NULL")
        try { while (st.step()) s.add(st.getText(0)) } finally { st.close() }
        return s
    }

    private class Pass(val key: String, val answer: String, val source: String, val slots: List<String>, val replace: Boolean)
    private val PASSES = listOf(
        Pass("A", "NO", "", listOf("CERT1", "CERT2"), false),
        Pass("B", "YES", "Cert15e trails", emptyList(), false),
        Pass("C", "NO", "", listOf("CERT1"), true),
    )

    /**
     * Runs the script on [side]. [emit] gets (key, value); [progress] a line now and then (the harness logs it as "GTCERT import15e ...").
     * A pass that throws gives "EXCEPTION <class>" (only the class is compared).
     */
    fun runScript(side: Side, ctx: Ctx, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        val fs = ctx.fs
        val files = inputFiles(fs, ctx.inputDir)
        emit("input|files", "n=${files.size};max=$MAX_BYTES;names=" + Cert15d.sha(files.joinToString("\n") { it.name }))
        for (p in files) { val b = fs.read(p) { readByteString() }; emit("input|file|" + p.name, "size=${b.size};sha256=" + b.sha256().hex()) }
        val known = HashSet<Long>()
        for (pass in PASSES) {
            progress("${side.name} phase ${pass.key}")
            val stage = ctx.stagingDir / pass.key
            fs.createDirectories(stage)
            val staged = files.map { p -> val q = stage / p.name; fs.copy(p, q); fs.metadataOrNull(q)?.lastModifiedAtMillis?.let { known.add(it) }; q }
            side.resetChanged(); side.resetCalls()
            val lines = ArrayList<String>()
            val t0 = ctx.nowMs()
            val r = try {
                totalsText(side.importFiles(staged, pass.answer, pass.source, emptySet(), pass.slots, pass.replace, { l ->
                    lines.add(l); if (lines.size % 200 == 0) progress("${side.name} ${pass.key}: ${lines.size} lines")
                }) { i, n -> progress("${side.name} ${pass.key}: file $i of ${staged.size} $n") })
            } catch (e: Throwable) { "EXCEPTION " + (e::class.simpleName ?: "?") }
            val t1 = ctx.nowMs()
            emit("${pass.key}|result", r)
            emit("${pass.key}|changed", side.changed().toString())
            lines.forEachIndexed { i, l -> emit("${pass.key}|l|" + (i + 1).toString().padStart(5, '0'), l) }
            side.calls().forEachIndexed { i, c -> emit("${pass.key}|call|" + (i + 1).toString().padStart(4, '0'), c) }
            snapshot(fs, pass.key, listOf("tracks" to ctx.tracksDir, "staging" to stage, "convert" to ctx.convertDir), known, t0, t1, emit)
        }
    }

    /** every file in the listed folders (sorted by name): size, sha256, mtime (see the header) */
    private fun snapshot(fs: FileSystem, phase: String, dirs: List<Pair<String, Path>>, known: Set<Long>, t0: Long, t1: Long, emit: (String, String) -> Unit) {
        for ((label, dir) in dirs) {
            if (!fs.exists(dir)) { emit("$phase|files|$label", "MISSING"); continue }
            val names = fs.list(dir).map { it.name }.sorted()
            emit("$phase|files|$label", "n=${names.size}")
            for (n in names) {
                val p = dir / n; val md = fs.metadataOrNull(p)
                val v = if (md == null) "GONE" else if (md.isDirectory) "dir" else {
                    val bs = fs.read(p) { readByteString() }
                    val mt = md.lastModifiedAtMillis
                    val m = when {
                        mt == null -> "none"
                        mt in known -> "known"
                        mt >= t0 - 2000 && mt <= t1 + 2000 -> "now"
                        else -> "s" + (mt / 1000)   // a GPX <time> (ms precision differs by filesystem: seconds)
                    }
                    "size=${bs.size};sha=" + bs.sha256().hex().take(16) + ";mtime=" + m
                }
                emit("$phase|files|$label|$n", v)
            }
        }
    }

    // ================================================================================================================================
    // the tables: Cert15d's six, then routes (t1) and route_notes (t2)
    // ================================================================================================================================

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3}|\.\d{6}|\.\d{9})?Z""")
    private fun newId(v: String?) = if (v != null && UUID.matches(v)) "NEWID(uuid)" else "NEWID(BAD:$v)"
    private fun newTime(v: String?) = if (v != null && INSTANT.matches(v)) "NEWTIME(instant)" else "NEWTIME(BAD:$v)"
    private fun text(st: SQLiteStatement, i: Int): String? = if (st.isNull(i)) null else st.getText(i)
    private fun cell(st: SQLiteStatement, i: Int): String = when (st.getColumnType(i)) {
        SQLITE_DATA_NULL -> "N"
        SQLITE_DATA_INTEGER -> "I" + st.getLong(i)
        SQLITE_DATA_FLOAT -> "R" + st.getDouble(i).toRawBits().toULong().toString(16)
        SQLITE_DATA_BLOB -> st.getBlob(i).let { "B#" + it.size + ":" + it.toByteString().sha256().hex().take(16) }
        else -> st.getText(i).let { if (it.length > 200) "S#" + it.length + ":" + Cert15d.sha(it) else "S" + Cert15d.esc(it) }
    }
    private fun names(st: SQLiteStatement) = (0 until st.getColumnCount()).map { st.getColumnName(it) }

    /** Cert15d's six tables, then t1 routes and t2 route_notes; [emit] gets (key, value, listed). Keys ascend in emit order. */
    fun dumpTables(conn: SQLiteConnection, dataConn: SQLiteConnection, inputs: Cert15d.Inputs, notes: Set<String>,
                   emit: (String, String, Boolean) -> Unit, progress: (String) -> Unit) {
        Cert15d.dumpTables(conn, dataConn, inputs, emit, progress)
        val tail = ArrayList<Pair<String, String>>()
        // ---- t1 routes ----
        val routeHash = HashMap<String, String>()   // route_id -> geom_hash, for t2
        run {
            val all = StringBuilder(); var n = 0
            val st = conn.prepare("SELECT * FROM routes ORDER BY geom_hash")
            try {
                var cols: List<String>? = null; var last = ""; var dup = 0
                while (st.step()) {
                    val nm = cols ?: names(st).also { cols = it; emit("t1|#cols:routes", it.joinToString(","), true) }
                    val iId = nm.indexOf("route_id"); val iHash = nm.indexOf("geom_hash")
                    val id = text(st, iId); val isNew = id == null || id !in inputs.routeIds
                    val h = text(st, iHash) ?: "NULL"; dup = if (h == last) dup + 1 else 0; last = h
                    if (id != null) routeHash[id] = h
                    val cells = nm.indices.joinToString("|") { i ->
                        when {
                            isNew && i == iId -> newId(id)
                            isNew && (nm[i] == "created_at" || nm[i] == "updated_at") -> newTime(text(st, i))
                            else -> cell(st, i)
                        }
                    }
                    val key = "t1|$h" + (if (dup > 0) "#${dup + 1}" else "")
                    all.append(key).append('\t').append(cells).append('\n'); n++
                    emit(key, if (isNew) cells else "h:" + Cert15d.sha(cells), isNew)
                }
            } finally { st.close() }
            tail.add(Pair("t9|count:routes", n.toString())); tail.add(Pair("t9|fp:routes", Cert15d.sha(all.toString())))
        }
        progress("dumped routes")
        // ---- t2 route_notes (keyed by the owning route's hash) ----
        run {
            val rows = ArrayList<Pair<String, String>>()
            val st = dataConn.prepare("SELECT * FROM route_notes")
            try {
                var cols: List<String>? = null
                while (st.step()) {
                    val nm = cols ?: names(st).also { cols = it; emit("t2|#cols:route_notes", it.joinToString(","), true) }
                    val iN = nm.indexOf("note_id"); val iR = nm.indexOf("route_id"); val iSeq = nm.indexOf("seq"); val iK = nm.indexOf("kind")
                    val nid = text(st, iN); val rid = text(st, iR)
                    val noteNew = nid == null || nid !in notes
                    val routeOld = rid != null && rid in inputs.routeIds
                    val owner = if (routeOld) "id:$rid" else "new:" + ((if (rid == null) null else routeHash[rid]) ?: "ORPHAN")
                    val cells = nm.indices.joinToString("|") { i ->
                        when {
                            noteNew && i == iN -> newId(nid)
                            !routeOld && i == iR -> newId(rid)
                            noteNew && nm[i] == "created_at" -> newTime(text(st, i))
                            else -> cell(st, i)
                        }
                    }
                    rows.add(Pair("t2|" + (if (noteNew) "new|" else "old|") + owner + "|" + (text(st, iSeq) ?: "NULL") + "|" + (text(st, iK) ?: "NULL"), cells))
                }
            } finally { st.close() }
            rows.sortWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            var last = ""; var dup = 0; val all = StringBuilder()
            for ((k, c) in rows) {
                dup = if (k == last) dup + 1 else 0; last = k
                val key = k + (if (dup > 0) "#${dup + 1}" else "")
                all.append(key).append('\t').append(c).append('\n')
                emit(key, c, true)
            }
            tail.add(Pair("t9|count:route_notes", rows.size.toString())); tail.add(Pair("t9|fp:route_notes", Cert15d.sha(all.toString())))
        }
        for ((k, v) in tail.sortedBy { it.first }) emit(k, v, true)
    }
}
