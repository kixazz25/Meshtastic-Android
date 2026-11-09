package com.grouptrack.shared.cert

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.rider.GtRiderScan
import com.grouptrack.shared.rider.GtRiderTrails
import com.grouptrack.shared.spatial.GtGeom
import com.grouptrack.shared.text.GtJava
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * STEP15C-2026-11-09 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15c certification's ONE runner
 * (rider trails), used by every side so the cases, their order and the fingerprints cannot drift between them:
 *  - on the Droid, GtCert15c (app) runs [runScript] with OLD (the 2.7e copies RiderTrailWriterCertOld / RiderTrailScannerCertOld, on
 *    SpatialDbManager's handles pointed at copy "old") and with [NewSide] (shared GtRiderTrails on copy "new"); then [dumpTables] on
 *    both copies, and the two line streams are compared;
 *  - on the iPad simulator, GtCertRider15cMacTest runs [runScript] with [NewSide] on a copy of the Mac's data and [dumpTables].
 * Every side works on its OWN fresh copy of the databases; nothing here opens a file.
 *
 * THE SCRIPT (INVENTORY 7.2 R1), in this order:
 *   parse|<case>          parseGeometry on fixed texts (LINE / MULTI / Z / Java number grammar / bad / unicode spaces)
 *   scanAll1|...          the reconcile pass over every track (the stored rider trails: unchanged / added / removed)
 *   scanAll2|...          again: on an unchanged database it must report 0 added, 0 removed ("invariant" line)
 *   scanTrack|<hash>      scanTrackByHash for EVERY track (sorted by geom_hash), no dedup session -- the add-a-track path
 *   scanTrack|nohash      a hash with no row
 *   synth|<case>          scanTrack on made-up geometry in the Great Salt Lake (no trails there: a piece is always written) -- a
 *                         named line, an unnamed one, a name with "%" (2.7e's log throws: EXPECTED), a MULTILINESTRING whose two parts
 *                         are 5 km apart (the flattening's straight joint, NOTES D2), null / blank / one-point geometry
 *   scanAll3|...          the reconcile again: everything the scanTrack / synth cases added that no track produces is REMOVED
 * Results are text: counts as integers, miles and coordinates with GtJava.jd (Java's Double.toString on both platforms).
 * "changed" = whether the side signalled "the maps reload" (2.7e's trailsChanged stamp) during that case.
 *
 * THE TABLES ([dumpTables]): trails, trail_properties, artifact_aliases (type 'trail') and tracks -- all rows, all columns, in a fixed
 * order (keys below). Ids and times made BY THE RUN differ between sides (UUID, Instant.now) and are MASKED: a trail_id / alias_id not
 * present before the run -> NEWID(uuid) when it is UUID-shaped; that row's created_at / updated_at / ingested_at -> NEWTIME(instant) when
 * it is Instant.toString()-shaped (anything else is written as BAD:<value> and so differs). Every other value is exact: REAL as its bits.
 * Keys, ascending (sN|#cols:<table> first in its section -- '#' sorts before every id / hex character): s1|<trails.geom_hash> |
 * s2|<trail_id> (trail_properties of trails that existed before) | s3|<owner trail's geom_hash> (trail_properties of new trails) |
 * s4|<alias key> | s5|<tracks.geom_hash> | s9|count:<table> and s9|fp:<table> (SHA-256 chain over every row). A row is LISTED (written out in full, and compared by the iPad) when it is a rider row or made by the run; every
 * other row is written as h:<sha of its cells> and covered by the table's fp line.
 * TEST-ONLY.
 */
object Cert15c {

    /** RiderTrailWriter.Result, side-free. */
    class Res(val tracksScanned: Int, val trailsAdded: Int, val miles: Double, val tracksRemoved: Int, val trailsTotal: Int,
              val trailsRemoved: Int, val trailsUnchanged: Int, val milesTotal: Double) {
        fun text() = "tracksScanned=$tracksScanned;trailsAdded=$trailsAdded;miles=${GtJava.jd(miles)};tracksRemoved=$tracksRemoved;" +
            "trailsTotal=$trailsTotal;trailsRemoved=$trailsRemoved;trailsUnchanged=$trailsUnchanged;milesTotal=${GtJava.jd(milesTotal)}"
    }

    /** One implementation of the 15c functions. The runner never calls anything else. */
    interface Side {
        val name: String
        fun parseGeometry(wkt: String?): List<GtRiderScan.Pt>
        fun scanAll(onProgress: (Int, Int) -> Unit): Res
        fun scanTrackByHash(geomHash: String): Pair<Int, Double>
        fun scanTrack(trackName: String?, geometry: String?): Pair<Int, Double>
        /** forget earlier "the maps reload" signals */
        fun resetChanged()
        /** was "the maps reload" signalled since [resetChanged]? */
        fun changed(): Boolean
    }

    /** NEW: the shared code. [changeCount] = how many times the env's onTrailsChanged ran (the harness's env counts them). */
    class NewSide(private val rider: GtRiderTrails, private val changeCount: () -> Int) : Side {
        override val name = "NEW"
        private var base = 0
        override fun parseGeometry(wkt: String?) = GtRiderTrails.parseGeometry(wkt)
        override fun scanAll(onProgress: (Int, Int) -> Unit): Res = rider.scanAll(onProgress).let {
            Res(it.tracksScanned, it.trailsAdded, it.miles, it.tracksRemoved, it.trailsTotal, it.trailsRemoved, it.trailsUnchanged, it.milesTotal)
        }
        override fun scanTrackByHash(geomHash: String) = rider.scanTrackByHash(geomHash)
        override fun scanTrack(trackName: String?, geometry: String?) = rider.scanTrack(trackName, geometry)
        override fun resetChanged() { base = changeCount() }
        override fun changed(): Boolean = changeCount() != base
    }

    /** What the copy held BEFORE the run (read by the harness from its own fresh copy, before the side runs). */
    class Inputs(val trailIds: Set<String>, val aliasIds: Set<String>, val trackHashes: List<String>)

    fun inputs(conn: SQLiteConnection, dataConn: SQLiteConnection): Inputs {
        val t = HashSet<String>(); val a = HashSet<String>(); val h = ArrayList<String>()
        each(conn, "SELECT trail_id FROM trails WHERE trail_id IS NOT NULL") { t.add(it.getText(0)) }
        each(dataConn, "SELECT alias_id FROM artifact_aliases WHERE artifact_type='trail' AND alias_id IS NOT NULL") { a.add(it.getText(0)) }
        each(conn, "SELECT geom_hash FROM tracks WHERE geom_hash IS NOT NULL ORDER BY geom_hash") { h.add(it.getText(0)) }
        return Inputs(t, a, h)
    }

    private fun each(c: SQLiteConnection, sql: String, row: (SQLiteStatement) -> Unit) {
        val st = c.prepare(sql); try { while (st.step()) row(st) } finally { st.close() }
    }

    fun sha(s: String): String = s.encodeUtf8().sha256().hex().take(16)
    /** a value on one line: backslash, tab, CR and LF escaped (as Cert15b.esc). */
    fun esc(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private fun d(x: Double) = GtJava.jd(x)
    private fun pair(p: Pair<Int, Double>) = "${p.first};${d(p.second)}"
    private fun pts(l: List<GtRiderScan.Pt>) = "n=${l.size};" + l.joinToString(",") { d(it.lon) + " " + d(it.lat) }

    /** STEP15C-FIXWS-2026-11-09: each Unicode White_Space character, and 3 look-alikes that are not (zero-width space, Mongolian vowel
     *  separator, BOM), between a lon and its lat. Droid OLD (2.7e's \s on Android) = NEW (GtRx.WS) proves GtRx.WS is Android's \s,
     *  character by character; the iPad then matches the Droid. Declared before PARSE_CASES (an object's vals start in order). */
    private val WS_CASES: List<Pair<String, String?>> =
        (listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680) + (0x2000..0x200A).toList() +
            listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0x200B, 0x180E, 0xFEFF)).map { c ->
            ("ws|U+" + c.toString(16).uppercase().padStart(4, '0')) to ("LINESTRING(-112.5" + Char(c) + "38.25,-112.4 38.3)")
        }

    /** parseGeometry inputs: every shape 2.7e's parser handles, and the texts where Java's grammar and other parsers part ways. */
    val PARSE_CASES: List<Pair<String, String?>> = listOf(
        "null" to null,
        "blank" to "   ",
        "empty" to "LINESTRING EMPTY",
        "point" to "POINT(-112.5 38.25)",
        "line" to "LINESTRING(-112.5 38.25,-112.4 38.3)",
        "line_spaces" to "LINESTRING(-112.5 38.25, -112.4 38.3 , -112.3\t38.35)",
        "multi" to "MULTILINESTRING((-112.5 38.25,-112.4 38.3),(-112.3 38.35,-112.2 38.4))",
        "z" to "LINESTRING Z(-112.5 38.25 1500,-112.4 38.3 1510.5)",
        "javanum" to "LINESTRING(1.5f 2d,0x1p3 -0x1.8p1,+3 -.5,1e-5 2E3,NaN Infinity,-0 -0.0)",
        "bad" to "LINESTRING(a b,1 2,3,4 x, ,5 6 7)",
        "nbsp" to "LINESTRING(-112.5\u00a038.25,-112.4 38.3)",
        "emspace" to "\u2003LINESTRING(-112.5 38.25,\u2003-112.4 38.3\u2003)",
        "digits" to "LINESTRING(-112.12345678901234567890 38.98765432109876543210,-112 38,1e400 -1e-400)",
        "nested" to "GEOMETRYCOLLECTION(LINESTRING(1 2,3 4),POINT(5 6))"
    ) + WS_CASES   // STEP15C-FIXWS-2026-11-09

    /**
     * Made-up tracks in the Great Salt Lake (no trail data there, so the scan always lays a piece): a straight line north from
     * (lon, 41.100) in [n] steps of 0.001 degree latitude (about 111 m). The text is GtGeom's (Java's number text on both platforms).
     */
    private fun lakeLine(lon: Double, n: Int): List<Pair<Double, Double>> = (0..n).map { k -> Pair(lon, (41100 + k) / 1000.0) }
    private fun synthCases(): List<Triple<String, String?, String?>> = listOf(
        Triple("named", "Synthetic lake A", GtGeom.lineLonLat(lakeLine(-112.60, 20)).wkt),
        Triple("unnamed", null, GtGeom.lineLonLat(lakeLine(-112.62, 20)).wkt),
        Triple("percent (EXPECTED 2.7e's log format throws on a % in the name)", "Grade 50% climb", GtGeom.lineLonLat(lakeLine(-112.64, 20)).wkt),
        Triple("multi_gap", "Synthetic lake B", GtGeom.multiLineLonLat(listOf(lakeLine(-112.70, 5), lakeLine(-112.66, 5))).wkt),
        Triple("null_geometry", "x", null),
        Triple("blank_geometry", "x", " "),
        Triple("one_point", "x", "POINT(-112.68 41.1)")
    )

    /**
     * Runs the script on [side]. [emit] gets (key, value); [progress] a line now and then (for the log).
     * A case that throws gives "EXCEPTION <class>" (2.7e's exceptions are its own platform's classes; only the class is compared).
     */
    fun runScript(side: Side, inputs: Inputs, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        fun guard(key: String, f: () -> String) = emit(key, try { f() } catch (e: Throwable) { "EXCEPTION " + (e::class.simpleName ?: "?") })
        for ((label, wkt) in PARSE_CASES) guard("parse|$label") { pts(side.parseGeometry(wkt)) }
        for (pass in 1..2) scanAllCase(side, "scanAll$pass", emit, progress)
        var k = 0
        for (h in inputs.trackHashes) {
            side.resetChanged()
            guard("scanTrack|$h") { pair(side.scanTrackByHash(h)) + ";changed=" + side.changed() }
            if (++k % 20 == 0) progress("${side.name} scanTrack $k of ${inputs.trackHashes.size}")
        }
        side.resetChanged()
        guard("scanTrack|nohash") { pair(side.scanTrackByHash("0".repeat(64))) + ";changed=" + side.changed() }
        for ((label, name, wkt) in synthCases()) {
            side.resetChanged()
            guard("synth|$label") { pair(side.scanTrack(name, wkt)) + ";changed=" + side.changed() }
        }
        scanAllCase(side, "scanAll3", emit, progress)
    }

    private fun scanAllCase(side: Side, key: String, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        side.resetChanged()
        val calls = StringBuilder(); var nCalls = 0
        val r = try {
            side.scanAll { i, n -> if (nCalls > 0) calls.append(','); calls.append(i).append('/').append(n); nCalls++
                if (i % 20 == 0) progress("${side.name} $key track $i of $n") }
        } catch (e: Throwable) { emit("$key|result", "EXCEPTION " + (e::class.simpleName ?: "?")); return }
        emit("$key|result", r.text())
        emit("$key|progress", "calls=$nCalls;sha=${sha(calls.toString())};last=${calls.toString().substringAfterLast(',')}")
        emit("$key|changed", side.changed().toString())
        if (key == "scanAll2") emit("$key|invariant",
            if (r.trailsAdded == 0 && r.trailsRemoved == 0) "0 added, 0 removed" else "NOT A NO-OP: added=${r.trailsAdded} removed=${r.trailsRemoved}")
    }

    // ================================================================================================================================
    // the tables
    // ================================================================================================================================

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3}|\.\d{6}|\.\d{9})?Z""")
    private fun newId(v: String?) = if (v != null && UUID.matches(v)) "NEWID(uuid)" else "NEWID(BAD:$v)"
    private fun newTime(v: String?) = if (v != null && INSTANT.matches(v)) "NEWTIME(instant)" else "NEWTIME(BAD:$v)"

    /** one cell: N | I<long> | R<raw bits, hex> | S<text> (S#<len>:<sha> over 200 chars) | B#<len>:<sha> */
    private fun cell(st: SQLiteStatement, i: Int): String = when (st.getColumnType(i)) {
        SQLITE_DATA_NULL -> "N"
        SQLITE_DATA_INTEGER -> "I" + st.getLong(i)
        SQLITE_DATA_FLOAT -> "R" + st.getDouble(i).toRawBits().toULong().toString(16)
        SQLITE_DATA_BLOB -> st.getBlob(i).let { "B#" + it.size + ":" + it.toByteString().sha256().hex().take(16) }
        else -> st.getText(i).let { if (it.length > 200) "S#" + it.length + ":" + sha(it) else "S" + esc(it) }
    }
    private fun text(st: SQLiteStatement, i: Int): String? = if (st.isNull(i)) null else st.getText(i)

    /** the SHA-256 chain over a table's rows (one fp line per table) */
    private class Chain { var h = ""; var n = 0
        fun add(key: String, cells: String) { h = (h + "\n" + key + "\t" + cells).encodeUtf8().sha256().hex(); n++ } }

    /**
     * Every row of the four tables (see the header). [emit] gets (key, value, listed). The order is fixed: keys ascend within each
     * section (s1 by SQL ORDER BY geom_hash, s2 by ORDER BY trail_id, s3/s4 sorted here), sections in order s1 s2 s3 s4 s5 s9 -- so every key ascends and the Droid can merge two dumps in one pass.
     */
    fun dumpTables(conn: SQLiteConnection, dataConn: SQLiteConnection, inputs: Inputs, emit: (String, String, Boolean) -> Unit) {
        val fps = ArrayList<Pair<String, Chain>>()
        // ---- s1 trails ----
        run {
            val ch = Chain(); fps.add("trails" to ch)
            val st = conn.prepare("SELECT * FROM trails ORDER BY geom_hash")
            try {
                var cols: List<String>? = null; var last = ""; var dup = 0
                while (st.step()) {
                    val names = cols ?: (0 until st.getColumnCount()).map { st.getColumnName(it) }.also { cols = it; emit("s1|#cols:trails", it.joinToString(","), true) }
                    val iId = names.indexOf("trail_id"); val iHash = names.indexOf("geom_hash"); val iCarto = names.indexOf("carto_code")
                    val id = text(st, iId); val isNew = id == null || id !in inputs.trailIds
                    val cells = names.indices.joinToString("|") { i ->
                        when {
                            isNew && i == iId -> newId(id)
                            isNew && (names[i] == "created_at" || names[i] == "updated_at") -> newTime(text(st, i))
                            else -> cell(st, i)
                        }
                    }
                    val h = text(st, iHash) ?: "NULL"
                    dup = if (h == last) dup + 1 else 0; last = h
                    val key = "s1|$h" + (if (dup > 0) "#${dup + 1}" else "")
                    ch.add(key, cells)
                    val listed = isNew || text(st, iCarto) == GtRiderTrails.CATEGORY
                    emit(key, if (listed) cells else "h:" + sha(cells), listed)
                }
            } finally { st.close() }
        }
        // ---- s2 / s3 trail_properties ----
        run {
            val ch = Chain(); fps.add("trail_properties" to ch)
            val fresh = ArrayList<Pair<String, String>>()   // (key, cells) of rows whose trail is new -- sorted, then s3
            val st = dataConn.prepare("SELECT * FROM trail_properties ORDER BY trail_id")
            try {
                var cols: List<String>? = null
                while (st.step()) {
                    val names = cols ?: (0 until st.getColumnCount()).map { st.getColumnName(it) }.also { cols = it; emit("s2|#cols:trail_properties", it.joinToString(","), true) }
                    val iId = names.indexOf("trail_id"); val iSrc = names.indexOf("source_id")
                    val id = text(st, iId); val isNew = id == null || id !in inputs.trailIds
                    val cells = names.indices.joinToString("|") { i ->
                        when {
                            isNew && i == iId -> newId(id)
                            isNew && names[i] == "ingested_at" -> newTime(text(st, i))
                            else -> cell(st, i)
                        }
                    }
                    if (isNew) fresh.add(Pair("s3|" + ownerHash(conn, id), cells))
                    else {
                        val key = "s2|$id"; ch.add(key, cells)
                        val listed = text(st, iSrc) == GtRiderTrails.SOURCE_ID
                        emit(key, if (listed) cells else "h:" + sha(cells), listed)
                    }
                }
            } finally { st.close() }
            numbered(fresh) { k, c -> ch.add(k, c); emit(k, c, true) }
        }
        // ---- s4 artifact_aliases of trails ----
        run {
            val ch = Chain(); fps.add("artifact_aliases_trail" to ch)
            val rows = ArrayList<Pair<String, String>>()
            val st = dataConn.prepare("SELECT * FROM artifact_aliases WHERE artifact_type='trail'")
            try {
                var cols: List<String>? = null
                while (st.step()) {
                    val names = cols ?: (0 until st.getColumnCount()).map { st.getColumnName(it) }.also { cols = it; emit("s4|#cols:artifact_aliases", it.joinToString(","), true) }
                    val iA = names.indexOf("alias_id"); val iT = names.indexOf("artifact_id"); val iAl = names.indexOf("alias")
                    val aid = text(st, iA); val tid = text(st, iT)
                    val aliasNew = aid == null || aid !in inputs.aliasIds
                    val trailNew = tid == null || tid !in inputs.trailIds
                    val owner = if (trailNew) "newtrail:" + ownerHash(conn, tid) else "trail:$tid"
                    val cells = names.indices.joinToString("|") { i ->
                        when {
                            aliasNew && i == iA -> newId(aid)
                            trailNew && i == iT -> newId(tid)
                            aliasNew && names[i] == "created_at" -> newTime(text(st, i))
                            else -> cell(st, i)
                        }
                    }
                    rows.add(Pair("s4|" + (if (aliasNew) "new|$owner|" + (text(st, iAl) ?: "NULL") else "old|$aid"), cells))
                }
            } finally { st.close() }
            numbered(rows) { k, c -> ch.add(k, c); emit(k, c, true) }
        }
        // ---- s5 tracks (the rider code never writes them: covered by the fp line) ----
        run {
            val ch = Chain(); fps.add("tracks" to ch)
            val st = conn.prepare("SELECT * FROM tracks ORDER BY geom_hash")
            try {
                var last = ""; var dup = 0; var named = false
                while (st.step()) {
                    val n = st.getColumnCount(); val iHash = (0 until n).first { st.getColumnName(it) == "geom_hash" }
                    if (!named) { named = true; emit("s5|#cols:tracks", (0 until n).joinToString(",") { st.getColumnName(it) }, true) }
                    val cells = (0 until n).joinToString("|") { cell(st, it) }
                    val h = text(st, iHash) ?: "NULL"; dup = if (h == last) dup + 1 else 0; last = h
                    val key = "s5|$h" + (if (dup > 0) "#${dup + 1}" else "")
                    ch.add(key, cells); emit(key, "h:" + sha(cells), false)
                }
            } finally { st.close() }
        }
        // s9 in ascending key order (the merge on the Droid needs every key ascending)
        val tail = ArrayList<Pair<String, String>>()
        for ((t, ch) in fps) { tail.add(Pair("s9|count:$t", ch.n.toString())); tail.add(Pair("s9|fp:$t", ch.h.take(32))) }
        for ((k, v) in tail.sortedBy { it.first }) emit(k, v, true)
    }

    /** the owning trail's geom_hash for a new id (s3 / s4 keys). CODE RULE 1: [trailId] is the row's own value and may be NULL. */
    private fun ownerHash(conn: SQLiteConnection, trailId: String?): String {
        if (trailId == null) return "NULLID"
        val st = conn.prepare("SELECT geom_hash FROM trails WHERE trail_id=? LIMIT 1")
        try { st.bindText(1, trailId); return if (st.step() && !st.isNull(0)) st.getText(0) else "ORPHAN" } finally { st.close() }
    }

    /** sorts (key, cells) by key then cells, numbering repeated keys "#2", "#3" ... so every key is unique and the order is fixed. */
    private fun numbered(rows: MutableList<Pair<String, String>>, out: (String, String) -> Unit) {
        rows.sortWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        var last = ""; var dup = 0
        for ((k, c) in rows) { dup = if (k == last) dup + 1 else 0; last = k; out(k + (if (dup > 0) "#${dup + 1}" else ""), c) }
    }
}
