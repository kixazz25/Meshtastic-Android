package com.grouptrack.shared.cert

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.rider.GtRiderTrails
import com.grouptrack.shared.spatial.GtGeom
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.track.GtTracks
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.blackholeSink
import okio.buffer

/**
 * STEP15D-2026-11-09 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15d certification's ONE runner
 * (track add / sync / properties, INVENTORY 7.2 S1), used by every side so the cases, their order and the table dump cannot drift:
 *  - on the Droid, GtCert15d (app) runs [runScript] with OLD (TrackSyncCertOld: the GENERATED copy of SpatialDbManager's 2.7e track
 *    functions, on SpatialDbManager's handles pointed at copy "old", its my_tracks / GroupTrack root pointed at the copy's folders) and with
 *    [NewSide] (shared GtTracks on copy "new" and ITS folders); then [dumpTables] on both copies; the line streams are compared;
 *  - on the iPad simulator, GtCertTrack15dMacTest runs [runScript] with [NewSide] on a copy of the same input, then [dumpTables].
 * Every side works on its OWN fresh copy of the databases AND of my_tracks. The made-up GPX files are WRITTEN BY THIS RUNNER (the same
 * bytes on every side by construction).
 *
 * THE SCRIPT, in this order (keys in brackets):
 *   setup         the made-up files into the copy's my_tracks: a new track, a pair with the same geometry and two names (the listing
 *                 order decides which one becomes the track -- INVENTORY T8), a copy of track T0's geometry with T0's name (DUPE) and with
 *                 a new name (ALIAS), no geometry, self-closing points, odd times and elevations (NaN, Infinity), Latin-1 bytes in a name
 *                 (T10), a BOM + CRLF file, no <name>, an upper-case .GPX, a .trashed- and a dot file, a .txt and an "x.gpx (2)"  [setup|...]
 *   allHashes     allTrackGeomHashes before                                                                              [allHashes|before]
 *   sync1         syncTracksFromFiles: the listing it sees, the result, every progress line, track_sync.log, "maps reload"   [sync1|...]
 *   sync2         again: nothing may be ADDED ("invariant"); the ALIAS lines are counted (2.7e D8, NOTES_15d)              [sync2|...]
 *   resolve       resolveTrackAdd called directly, from a staging folder: outer spaces (INSERT untrimmed -- Q6 c), the trimmed name
 *                 (ALIAS), again (DROP_ALIAS), a "%" name (EXPECTED: 2.7e's rider log throws, NOTES_15c D1), a missing file (ERROR),
 *                 no geometry (NO_GEOMETRY, the source stays), a source already named <hash>.gpx in my_tracks, and a new track whose
 *                 <hash>.gpx already exists (the source stays)                                                               [resolve|...]
 *   props         updateTrackPropertiesForHash: no such hash, T0, a track whose file was removed                               [props|...]
 *   backfill      backfillAllTrackProperties                                                                                 [backfill|...]
 *   waypoints     insertWaypoint: a normal one, a blank name ("Not Named"), the same point again (INSERT OR IGNORE)          [wp|...]
 *   delete        deleteTrackFromDb: with a file, without a file, an unknown id                                                [delete|...]
 *   sync3         the last sync, then allHashes again                                                                    [sync3|..., allHashes|after]
 *   files         after each phase: every file in my_tracks / staging / the GroupTrack root: size, sha256, and whether its mtime is one the
 *                 runner saw before (kept by a rename) or a new one                                                       [files|<phase>|...]
 * Results are text: numbers with GtJava.jd. Run-made ids in RESULTS are written as their shape ("uuid").
 *
 * THE TABLES ([dumpTables]): trails, trail_properties, artifact_aliases (every type), tracks, track_properties, waypoints -- every row and
 * column; REAL as its bits. Ids and times made BY THE RUN are MASKED (NEWID(uuid), NEWTIME(instant); anything else BAD:<value>, which
 * differs). Rows written in full ("listed"): new rows, rider trails and their properties, every track, track_properties and alias row.
 * Other rows (the ~230k agency / OSM trails, old waypoints) are written as h:<sha of their cells, long text as its length> and are
 * covered in full -- every byte of every cell -- by the table's fp line (s9|fp:<table>, one streaming SHA-256 per table).
 * STEP15D (coordinator 11-09, 15c lesson): the dump STREAMS (no per-row string chain) and calls [progress] every 10,000 rows.
 * TEST-ONLY.
 */
object Cert15d {

    /** One implementation of the 15d functions. The runner never calls anything else. */
    interface Side {
        val name: String
        /** the names the side's next sync will list in [dir], in ITS order (Android: File.listFiles) */
        fun listing(dir: Path): List<String>
        fun sync(onProgress: (String) -> Unit): String
        fun resolve(name: String, source: Path): String
        fun props(geomHash: String, onProgress: (String) -> Unit): Boolean
        fun backfill(onProgress: (String) -> Unit): String
        fun insertWaypoint(name: String, lat: Double, lon: Double, type: String): String
        fun allTrackGeomHashes(): List<Pair<String, String?>>
        fun deleteTrack(id: String)
        fun resetChanged()
        fun changed(): Boolean
    }

    /** Where the side's copies are, and a connection to read its spatial copy (the SAME handle the side writes through). */
    class Ctx(val fs: FileSystem, val tracksDir: Path, val rootDir: Path, val stagingDir: Path, val read: SQLiteConnection)

    fun syncText(processed: Int, skipped: Int, addedRenamed: Int, renamed: Int, addedNames: List<String>, propsWritten: Int, propsTotal: Int,
                 failures: List<String>) =
        "processed=$processed;skipped=$skipped;addedRenamed=$addedRenamed;renamed=$renamed;addedNames=[" + addedNames.joinToString("|") +
            "];propsWritten=$propsWritten;propsTotal=$propsTotal;failures=[" + failures.joinToString("|") + "]"

    fun backfillText(written: Int, total: Int, failures: List<String>) = "written=$written;total=$total;failures=[" + failures.joinToString("|") + "]"

    /** NEW: the shared code. [changeCount] = how many times the env's onTrailsChanged ran. */
    class NewSide(private val t: GtTracks, private val files: GtFiles, private val changeCount: () -> Int) : Side {
        override val name = "NEW"
        private var base = 0
        override fun listing(dir: Path) = files.list(dir).map { it.name }
        override fun sync(onProgress: (String) -> Unit) = t.syncTracksFromFiles(onProgress).let {
            syncText(it.processed, it.skipped, it.addedRenamed, it.renamed, it.addedNames, it.propsWritten, it.propsTotal, it.failures) }
        override fun resolve(name: String, source: Path) = t.resolveTrackAdd(name, source).name
        override fun props(geomHash: String, onProgress: (String) -> Unit) = t.updateTrackPropertiesForHash(geomHash, onProgress)
        override fun backfill(onProgress: (String) -> Unit) = t.backfillAllTrackProperties(onProgress).let { backfillText(it.written, it.total, it.failures) }
        override fun insertWaypoint(name: String, lat: Double, lon: Double, type: String) = t.insertWaypoint(name, lat, lon, type)
        override fun allTrackGeomHashes() = t.allTrackGeomHashes()
        override fun deleteTrack(id: String) = t.deleteTrackFromDb(id)
        override fun resetChanged() { base = changeCount() }
        override fun changed(): Boolean = changeCount() != base
    }

    /** What the copy held BEFORE the run (read once by the harness from a fresh copy -- the copies are byte copies of one input). */
    class Inputs(val trailIds: Set<String>, val aliasIds: Set<String>, val trackIds: Set<String>, val waypointIds: Set<String>,
                 val routeIds: Set<String>, val trackHashes: List<String>)

    fun inputs(conn: SQLiteConnection, dataConn: SQLiteConnection, progress: (String) -> Unit): Inputs {
        val t = HashSet<String>(); val a = HashSet<String>(); val tr = HashSet<String>(); val w = HashSet<String>(); val r = HashSet<String>()
        val h = ArrayList<String>()
        each(conn, "SELECT trail_id FROM trails WHERE trail_id IS NOT NULL") { t.add(it.getText(0)); if (t.size % 50_000 == 0) progress("reading input ids: ${t.size} trails") }
        each(dataConn, "SELECT alias_id FROM artifact_aliases WHERE alias_id IS NOT NULL") { a.add(it.getText(0)) }
        each(conn, "SELECT track_id FROM tracks WHERE track_id IS NOT NULL") { tr.add(it.getText(0)) }
        each(conn, "SELECT waypoint_id FROM waypoints WHERE waypoint_id IS NOT NULL") { w.add(it.getText(0)) }
        each(conn, "SELECT route_id FROM routes WHERE route_id IS NOT NULL") { r.add(it.getText(0)) }
        each(conn, "SELECT geom_hash FROM tracks WHERE geom_hash IS NOT NULL ORDER BY geom_hash") { h.add(it.getText(0)) }
        return Inputs(t, a, tr, w, r, h)
    }

    private fun each(c: SQLiteConnection, sql: String, row: (SQLiteStatement) -> Unit) {
        val st = c.prepare(sql); try { while (st.step()) row(st) } finally { st.close() }
    }
    private fun first(c: SQLiteConnection, sql: String, arg: String): String? {
        val st = c.prepare(sql); try { st.bindText(1, arg); return if (st.step() && !st.isNull(0)) st.getText(0) else null } finally { st.close() }
    }

    fun sha(s: String): String = s.encodeUtf8().sha256().hex().take(16)
    fun esc(s: String): String = Cert15c.esc(s)
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3}|\.\d{6}|\.\d{9})?Z""")
    private fun idShape(v: String) = if (UUID.matches(v)) "uuid" else "BAD:$v"
    private fun newId(v: String?) = if (v != null && UUID.matches(v)) "NEWID(uuid)" else "NEWID(BAD:$v)"
    private fun newTime(v: String?) = if (v != null && INSTANT.matches(v)) "NEWTIME(instant)" else "NEWTIME(BAD:$v)"
    private fun d(x: Double) = GtJava.jd(x)

    // ================================================================================================================================
    // the made-up files (the same bytes on every side)
    // ================================================================================================================================

    /** One trackpoint. [ele] / [time] are the TEXT written (null = no element), so odd values can be written as they are. */
    class P(val lat: Double, val lon: Double, val ele: String?, val time: String?)

    /** A straight line north in the Great Salt Lake (no trails within 0.02 deg on live_2026-11-08 -- measured 11-09 for 15c), [n]+1
     *  points 0.001 deg apart, elevation 1280 m rising 1.5 m a point, times 10 s apart from 2026-11-01T10:00:00Z. */
    private fun lake(lon: Double, n: Int, withEle: Boolean = true, withTime: Boolean = true): List<P> = (0..n).map { k ->
        val s = 10 * k
        P((41100 + k) / 1000.0, lon, if (withEle) d(1280 + 1.5 * k) else null,
            if (withTime) "2026-11-01T10:" + (s / 60).toString().padStart(2, '0') + ":" + (s % 60).toString().padStart(2, '0') + "Z" else null)
    }

    private fun gpx(name: String?, pts: List<P>, selfClosing: Boolean = false, nl: String = "\n"): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(nl)
        sb.append("<gpx version=\"1.1\" creator=\"GroupTrack cert15d\" xmlns=\"http://www.topografix.com/GPX/1/1\">").append(nl)
        sb.append("<trk>")
        if (name != null) sb.append("<name>").append(name).append("</name>")
        sb.append(nl).append("<trkseg>").append(nl)
        for (p in pts) {
            sb.append("<trkpt lat=\"").append(d(p.lat)).append("\" lon=\"").append(d(p.lon)).append("\"")
            if (selfClosing) { sb.append("/>").append(nl); continue }
            sb.append(">")
            if (p.ele != null) sb.append("<ele>").append(p.ele).append("</ele>")
            if (p.time != null) sb.append("<time>").append(p.time).append("</time>")
            sb.append("</trkpt>").append(nl)
        }
        sb.append("</trkseg>").append(nl).append("</trk>").append(nl).append("</gpx>").append(nl)
        return sb.toString()
    }

    private fun hashOf(pts: List<P>) = GtGeom.lineLonLat(pts.map { Pair(it.lon, it.lat) }).hash

    /** T0's own points (its stored WKT, parsed), so a file can be made with EXACTLY its geometry (Java's number text round-trips). */
    private fun pointsOf(wkt: String): List<P>? = GtGeom.parse(wkt)?.takeIf { it.kind == "LINESTRING" }?.parts?.get(0)?.map { P(it.second, it.first, null, null) }

    private fun write(fs: FileSystem, p: Path, bytes: okio.ByteString) { fs.write(p) { write(bytes) } }

    // ================================================================================================================================
    // the script
    // ================================================================================================================================

    /**
     * Runs the script on [side]. [emit] gets (key, value); [progress] a line now and then (the harness logs it as "GTCERT track15d ...").
     * A case that throws gives "EXCEPTION <class>" (only the class is compared: 2.7e's exceptions are its own platform's classes).
     */
    fun runScript(side: Side, ctx: Ctx, inputs: Inputs, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        val fs = ctx.fs
        fun guard(key: String, f: () -> String) = emit(key, try { f() } catch (e: Throwable) { "EXCEPTION " + (e::class.simpleName ?: "?") })
        val known = HashSet<Long>()   // every mtime the runner has seen on a file (a file that keeps one was kept, not rewritten)
        fun remember(dir: Path) { if (fs.exists(dir)) for (p in fs.list(dir)) fs.metadataOrNull(p)?.lastModifiedAtMillis?.let { known.add(it) } }
        fs.createDirectories(ctx.stagingDir); fs.createDirectories(ctx.rootDir)
        progress("${side.name} phase setup")

        // ---- T0: the first track by hash (its geometry and official name) ----
        val t0Hash = inputs.trackHashes.firstOrNull()
        val t0Wkt = t0Hash?.let { first(ctx.read, "SELECT geometry FROM tracks WHERE geom_hash=? LIMIT 1", it) }
        val t0Name = t0Hash?.let { first(ctx.read, "SELECT name FROM tracks WHERE geom_hash=? LIMIT 1", it) }
        val t0Pts = t0Wkt?.let { pointsOf(it) }
        emit("t0|info", "n=${inputs.trackHashes.size};T0=" + (t0Hash ?: "none") + ";T0pts=" + (t0Pts?.size ?: -1) +
            ";T0rebuilt=" + (t0Pts?.let { hashOf(it) == t0Hash } ?: false))

        // ---- setup: the made-up files ----
        val newA = lake(-112.40, 20)
        val pair = lake(-112.42, 15)
        val selfC = lake(-112.44, 12, withEle = false, withTime = false)
        val times = lake(-112.46, 10).mapIndexed { k, p ->
            // fractional Z, numeric offset, ".5Z" (= 5 ms, the SSS quirk), garbage, microseconds, no zone, a step back, a jump (> 200 mph)
            val t = when (k) {
                1 -> "2026-11-01T10:00:10.250Z"; 2 -> "2026-11-01T11:00:20+01:00"; 3 -> "2026-11-01T10:00:30.5Z"; 4 -> "garbage"
                5 -> "2026-11-01T10:00:50.123456Z"; 6 -> "2026-11-01T10:01:00"; 7 -> "2026-11-01T10:00:55Z"; 8 -> "2026-11-01T10:01:00.001Z"
                else -> p.time
            }
            val e = when (k) { 2 -> "NaN"; 3 -> " 1285.25 "; 5 -> "1e3"; 6 -> "1290.0f"; else -> p.ele }
            P(p.lat, p.lon, e, t)
        }
        val eleInf = lake(-112.48, 6).mapIndexed { k, p -> P(p.lat, p.lon, if (k == 3) "Infinity" else p.ele, p.time) }
        val latin = lake(-112.50, 8)
        val bom = lake(-112.52, 8)
        val noName = lake(-112.54, 8)
        val upper = lake(-112.56, 8)
        val files = ArrayList<Pair<String, okio.ByteString>>()
        fun add(n: String, text: String) = files.add(Pair(n, text.encodeUtf8()))
        add("Cert15d new A.gpx", gpx("Cert15d new A", newA))
        add("Cert15d pair 1.gpx", gpx("Cert15d pair one", pair))
        add("Cert15d pair 2.gpx", gpx("Cert15d pair two", pair))
        if (t0Pts != null && t0Name != null) {
            add("cert15d_dupname.gpx", gpx(t0Name, t0Pts))   // raw: the sync reads <name> without unescaping
            add("cert15d_alias.gpx", gpx("Cert15d other name for T0", t0Pts))
        }
        add("cert15d_nogeom.gpx", gpx("Cert15d no geometry", emptyList()))
        add("cert15d_selfclosing.gpx", gpx("Cert15d self-closing", selfC, selfClosing = true))
        add("cert15d_times.gpx", gpx("Cert15d odd times", times))
        add("cert15d_eleinf.gpx", gpx("Cert15d infinite climb", eleInf))
        run {   // T10: a Latin-1 byte (0xE9) inside the name -- not UTF-8
            val text = gpx("Caf#E9 loop", latin); val at = text.indexOf("#E9")
            files.add(Pair("cert15d_latin1.gpx", Buffer().writeUtf8(text.substring(0, at)).writeByte(0xE9).writeUtf8(text.substring(at + 3)).readByteString()))
        }
        files.add(Pair("cert15d_bom_crlf.gpx", Buffer().writeByte(0xEF).writeByte(0xBB).writeByte(0xBF).writeUtf8(gpx("Cert15d BOM CRLF", bom, nl = "\r\n")).readByteString()))
        add("cert15d_noname.gpx", gpx(null, noName))
        add("CERT15D_UPPER.GPX", gpx("Cert15d upper case", upper))
        add(".trashed-1700000000-cert15d.gpx", gpx("trashed", lake(-112.58, 4)))
        add(".cert15d_hidden.gpx", gpx("hidden", lake(-112.60, 4)))
        add("cert15d_notes.txt", "not a track\n")
        add("cert15d_copy.gpx (2)", gpx("Cert15d copy suffix", lake(-112.62, 4)))
        for ((n, b) in files) { write(fs, ctx.tracksDir / n, b); emit("setup|$n", "len=${b.size};sha=" + b.sha256().hex().take(16)) }
        remember(ctx.tracksDir)

        guard("allHashes|before") { hashesText(side.allTrackGeomHashes()) }
        snapshot(fs, ctx, "setup", known, emit)

        // ---- sync1, sync2 ----
        syncCase(side, ctx, "sync1", emit, progress)
        snapshot(fs, ctx, "sync1", known, emit)
        val s2 = syncCase(side, ctx, "sync2", emit, progress)
        // nothing may be ADDED the second time. ALIASES are reported, not judged: 2.7e aliases a track whose file has no <name> under its
        // own hash on the second Resync (the file is <hash>.gpx by then and its name falls back to the file name -- NOTES_15d D8)
        emit("sync2|invariant", if (s2.first == 0) "0 added" else "NOT A NO-OP: added=${s2.first}")
        emit("sync2|aliased", s2.second.toString())
        snapshot(fs, ctx, "sync2", known, emit)

        // ---- resolveTrackAdd directly ----
        progress("${side.name} phase resolve")
        val spaced = lake(-112.64, 10); val pct = lake(-112.66, 10); val inPlace = lake(-112.68, 10); val exists = lake(-112.70, 10)
        fun stage(file: String, text: String): Path { val p = ctx.stagingDir / file; write(fs, p, text.encodeUtf8()); remember(ctx.stagingDir); return p }
        fun resolveCase(label: String, name: String, source: Path) {
            side.resetChanged()
            guard("resolve|$label") {
                val r = side.resolve(name, source)
                r + ";changed=" + side.changed() + ";staging=[" + fs.list(ctx.stagingDir).map { it.name }.sorted().joinToString("/") + "]"
            }
        }
        resolveCase("d1 outer spaces", "  Cert15d spaced  ", stage("d1.gpx", gpx("  Cert15d spaced  ", spaced)))
        resolveCase("d2 trimmed name", "Cert15d spaced", stage("d2.gpx", gpx("Cert15d spaced", spaced)))
        resolveCase("d3 trimmed again", "Cert15d spaced", stage("d3.gpx", gpx("Cert15d spaced", spaced)))
        resolveCase("d4 spaces again", "  Cert15d spaced  ", stage("d4.gpx", gpx("  Cert15d spaced  ", spaced)))
        resolveCase("d5 percent (EXPECTED 2.7e's rider log format throws on a % in the name: no maps-reload signal)", "Grade 50% climb",
            stage("d5.gpx", gpx("Grade 50% climb", pct)))
        resolveCase("d6 missing file", "Cert15d missing", ctx.stagingDir / "d6_not_there.gpx")
        resolveCase("d7 no geometry", "Cert15d nothing", stage("d7.gpx", gpx("Cert15d nothing", emptyList())))
        run {
            val p = ctx.tracksDir / (hashOf(inPlace) + ".gpx"); write(fs, p, gpx("Cert15d in place", inPlace).encodeUtf8()); remember(ctx.tracksDir)
            resolveCase("d8 source is <hash>.gpx", "Cert15d in place", p)
        }
        run {
            write(fs, ctx.tracksDir / (hashOf(exists) + ".gpx"), gpx("Cert15d target first", exists).encodeUtf8()); remember(ctx.tracksDir)
            resolveCase("d9 target exists", "Cert15d target exists", stage("d9.gpx", gpx("Cert15d target exists", exists)))
        }
        snapshot(fs, ctx, "resolve", known, emit)

        // ---- properties ----
        progress("${side.name} phase props")
        fun propsCase(label: String, h: String) {
            val lines = ArrayList<String>()
            guard("props|$label") { side.props(h) { lines.add(it) }.toString() + ";lines=" + lines.size }
            lines.forEachIndexed { i, l -> emit("props|$label|" + (i + 1).toString().padStart(3, '0'), l) }
        }
        propsCase("no such hash", "0".repeat(64))
        if (t0Hash != null) propsCase("T0", t0Hash)
        val aHash = hashOf(newA)
        guard("props|remove A's file") { val f = ctx.tracksDir / "$aHash.gpx"; val had = fs.exists(f); if (had) fs.delete(f); "had=$had" }
        propsCase("A without its file", aHash)
        run {
            val lines = ArrayList<String>()
            guard("backfill|result") { side.backfill { lines.add(it) } }
            lines.forEachIndexed { i, l -> emit("backfill|" + (i + 1).toString().padStart(4, '0'), l) }
            if (lines.size > 0) progress("${side.name} backfill: ${lines.size} lines")
        }

        // ---- waypoints ----
        guard("wp|normal") { idShape(side.insertWaypoint("Cert15d waypoint", 41.123456789, -112.71, "Trail Head")) }
        guard("wp|blank name") { idShape(side.insertWaypoint("  ", 41.2, -112.72, "other")) }
        guard("wp|same point again") { idShape(side.insertWaypoint("Cert15d waypoint again", 41.123456789, -112.71, "Parking")) }

        // ---- delete ----
        progress("${side.name} phase delete")
        fun idOf(h: String) = first(ctx.read, "SELECT track_id FROM tracks WHERE geom_hash=? LIMIT 1", h)
        guard("delete|with its file") { val id = idOf(hashOf(spaced)); if (id == null) "no track" else { side.deleteTrack(id); "done;row=" + (idOf(hashOf(spaced)) != null) } }
        guard("delete|without its file") { val id = idOf(aHash); if (id == null) "no track" else { side.deleteTrack(id); "done;row=" + (idOf(aHash) != null) } }
        guard("delete|unknown id") { side.deleteTrack("cert15d-no-such-track"); "done" }
        snapshot(fs, ctx, "delete", known, emit)

        // ---- sync3 ----
        syncCase(side, ctx, "sync3", emit, progress)
        snapshot(fs, ctx, "sync3", known, emit)
        guard("allHashes|after") { hashesText(side.allTrackGeomHashes()) }
    }

    private fun hashesText(l: List<Pair<String, String?>>): String =
        "n=${l.size};order=" + sha(l.joinToString("\n") { it.first + "\t" + (it.second ?: "NULL") }) +
            ";sorted=" + sha(l.map { it.first + "\t" + (it.second ?: "NULL") }.sorted().joinToString("\n"))

    /** one sync: (added, aliased) for the invariant */
    private fun syncCase(side: Side, ctx: Ctx, key: String, emit: (String, String) -> Unit, progress: (String) -> Unit): Pair<Int, Int> {
        progress("${side.name} phase $key")
        val fs = ctx.fs
        emit("$key|listing", try { side.listing(ctx.tracksDir).joinToString("/") } catch (e: Throwable) { "EXCEPTION " + (e::class.simpleName ?: "?") })
        val log = ctx.rootDir / "track_sync.log"
        if (fs.exists(log)) fs.delete(log)   // each sync writes it anew (2.7e overwrites); deleted so a sync that writes none shows MISSING
        side.resetChanged()
        val lines = ArrayList<String>()
        var added = 0; var aliased = 0
        val r = try { side.sync { l -> lines.add(l); if (l.startsWith("ADDED:")) added++; if (l.startsWith("ALIAS:")) aliased++
            if (lines.size % 25 == 0) progress("${side.name} $key: ${lines.size} lines") } } catch (e: Throwable) { "EXCEPTION " + (e::class.simpleName ?: "?") }
        emit("$key|result", r)
        emit("$key|changed", side.changed().toString())
        lines.forEachIndexed { i, l -> emit("$key|p|" + (i + 1).toString().padStart(4, '0'), l) }
        if (fs.exists(log)) {
            val text = fs.read(log) { readUtf8() }
            emit("$key|log", "len=${text.length};sha=${sha(text)}")
            text.split('\n').forEachIndexed { i, l -> emit("$key|log|" + (i + 1).toString().padStart(4, '0'), l) }
        } else emit("$key|log", "MISSING")
        return Pair(added, aliased)
    }

    /** every file in the copy's my_tracks, staging folder and GroupTrack root (sorted by name) */
    private fun snapshot(fs: FileSystem, ctx: Ctx, phase: String, known: Set<Long>, emit: (String, String) -> Unit) {
        for ((label, dir) in listOf("tracks" to ctx.tracksDir, "staging" to ctx.stagingDir, "root" to ctx.rootDir)) {
            if (!fs.exists(dir)) { emit("files|$phase|$label", "MISSING"); continue }
            val names = fs.list(dir).map { it.name }.sorted()
            emit("files|$phase|$label", "n=${names.size}")
            for (n in names) {
                val p = dir / n; val md = fs.metadataOrNull(p)
                val v = if (md == null) "GONE" else if (md.isDirectory) "dir" else {
                    val bs = fs.read(p) { readByteString() }
                    val mt = md.lastModifiedAtMillis
                    "size=${bs.size};sha=" + bs.sha256().hex().take(16) + ";mtime=" + (if (mt != null && mt in known) "known" else "new")
                }
                emit("files|$phase|$label|$n", v)
            }
        }
    }

    // ================================================================================================================================
    // the tables
    // ================================================================================================================================

    /** one cell, in full: N | I<long> | R<raw bits, hex> | S<text> (S#<len>:<sha> over 200 chars) | B#<len>:<sha> */
    private fun cell(st: SQLiteStatement, i: Int): String = when (st.getColumnType(i)) {
        SQLITE_DATA_NULL -> "N"
        SQLITE_DATA_INTEGER -> "I" + st.getLong(i)
        SQLITE_DATA_FLOAT -> "R" + st.getDouble(i).toRawBits().toULong().toString(16)
        SQLITE_DATA_BLOB -> st.getBlob(i).let { "B#" + it.size + ":" + it.toByteString().sha256().hex().take(16) }
        else -> st.getText(i).let { if (it.length > 200) "S#" + it.length + ":" + sha(it) else "S" + esc(it) }
    }
    private fun text(st: SQLiteStatement, i: Int): String? = if (st.isNull(i)) null else st.getText(i)

    /** A table's fingerprint: ONE streaming SHA-256 over every row's key and EVERY byte of every cell (no per-row re-hash). */
    private class Fp {
        private val hs = HashingSink.sha256(blackholeSink()); private val out = hs.buffer(); var n = 0
        fun row(key: String, st: SQLiteStatement, masked: Map<Int, String>) {
            out.writeUtf8(key).writeByte(9)
            for (i in 0 until st.getColumnCount()) {
                val m = masked[i]
                if (m != null) out.writeUtf8(m)
                else when (st.getColumnType(i)) {
                    SQLITE_DATA_NULL -> out.writeUtf8("N")
                    SQLITE_DATA_INTEGER -> out.writeUtf8("I").writeDecimalLong(st.getLong(i))
                    SQLITE_DATA_FLOAT -> out.writeUtf8("R").writeLong(st.getDouble(i).toRawBits())
                    SQLITE_DATA_BLOB -> { val b = st.getBlob(i); out.writeUtf8("B").writeDecimalLong(b.size.toLong()).writeByte(58).write(b) }
                    else -> { val t = st.getText(i); out.writeUtf8("S").writeDecimalLong(t.length.toLong()).writeByte(58).writeUtf8(t) }
                }
                out.writeByte(124)
            }
            out.writeByte(10); n++
        }
        fun hex(): String { out.flush(); return hs.hash.hex().take(32) }
    }

    /** light cells for an unlisted row's own h: line -- long text as its length only (its bytes are in the table's fp) */
    private fun lightCells(st: SQLiteStatement): String = (0 until st.getColumnCount()).joinToString("|") { i ->
        if (st.getColumnType(i) == SQLITE_DATA_NULL || st.getColumnType(i) == SQLITE_DATA_INTEGER || st.getColumnType(i) == SQLITE_DATA_FLOAT || st.getColumnType(i) == SQLITE_DATA_BLOB) cell(st, i)
        else st.getText(i).let { if (it.length > 200) "S#" + it.length else "S" + esc(it) }
    }

    private fun names(st: SQLiteStatement) = (0 until st.getColumnCount()).map { st.getColumnName(it) }

    /**
     * Every row of the six tables (see the header). [emit] gets (key, value, listed). Keys ascend in emit order (SQL ORDER BY on ASCII
     * keys, or sorted here), sections s1 s2 s3 s4 s5 s6 s7 s9 -- so the Droid merges two dumps in one pass.
     */
    fun dumpTables(conn: SQLiteConnection, dataConn: SQLiteConnection, inputs: Inputs, emit: (String, String, Boolean) -> Unit, progress: (String) -> Unit) {
        val fps = ArrayList<Pair<String, Fp>>()
        val tail = ArrayList<Pair<String, String>>()   // the s9 lines, written last in key order
        // ---- s1 trails ----
        run {
            val fp = Fp(); fps.add("trails" to fp)
            val st = conn.prepare("SELECT * FROM trails ORDER BY geom_hash")
            try {
                var cols: List<String>? = null; var last = ""; var dup = 0; var k = 0
                while (st.step()) {
                    val nm = cols ?: names(st).also { cols = it; emit("s1|#cols:trails", it.joinToString(","), true) }
                    val iId = nm.indexOf("trail_id"); val iHash = nm.indexOf("geom_hash"); val iCarto = nm.indexOf("carto_code")
                    val id = text(st, iId); val isNew = id == null || id !in inputs.trailIds
                    val masked = HashMap<Int, String>()
                    if (isNew) { masked[iId] = newId(id); nm.forEachIndexed { i, c -> if (c == "created_at" || c == "updated_at") masked[i] = newTime(text(st, i)) } }
                    val h = text(st, iHash) ?: "NULL"; dup = if (h == last) dup + 1 else 0; last = h
                    val key = "s1|$h" + (if (dup > 0) "#${dup + 1}" else "")
                    fp.row(key, st, masked)
                    val listed = isNew || text(st, iCarto) == GtRiderTrails.CATEGORY
                    emit(key, if (listed) nm.indices.joinToString("|") { masked[it] ?: cell(st, it) } else "h:" + sha(lightCells(st)), listed)
                    if (++k % 10_000 == 0) progress("dumping trails $k")
                }
            } finally { st.close() }
        }
        // ---- s2 / s3 trail_properties ----
        run {
            val fp = Fp(); fps.add("trail_properties" to fp)
            val fresh = ArrayList<Pair<String, String>>()
            val st = dataConn.prepare("SELECT * FROM trail_properties ORDER BY trail_id")
            try {
                var cols: List<String>? = null; var k = 0
                while (st.step()) {
                    val nm = cols ?: names(st).also { cols = it; emit("s2|#cols:trail_properties", it.joinToString(","), true) }
                    val iId = nm.indexOf("trail_id"); val iSrc = nm.indexOf("source_id")
                    val id = text(st, iId); val isNew = id == null || id !in inputs.trailIds
                    val masked = HashMap<Int, String>()
                    if (isNew) { masked[iId] = newId(id); nm.forEachIndexed { i, c -> if (c == "ingested_at") masked[i] = newTime(text(st, i)) } }
                    val cells = nm.indices.joinToString("|") { masked[it] ?: cell(st, it) }
                    if (isNew) fresh.add(Pair("s3|" + ownerHash(conn, "trails", "trail_id", id), cells))
                    else {
                        val key = "s2|$id"; fp.row(key, st, masked)
                        val listed = text(st, iSrc) == GtRiderTrails.SOURCE_ID
                        emit(key, if (listed) cells else "h:" + sha(cells), listed)
                    }
                    if (++k % 10_000 == 0) progress("dumping trail_properties $k")
                }
            } finally { st.close() }
            numbered(fresh) { key, c -> emit(key, c, true) }   // listed in full: covered by their own lines
            tail.add(Pair("s9|count:trail_properties_new", fresh.size.toString()))
        }
        // ---- s4 artifact_aliases (every type) ----
        run {
            val rows = ArrayList<Pair<String, String>>()
            val st = dataConn.prepare("SELECT * FROM artifact_aliases")
            try {
                var cols: List<String>? = null
                while (st.step()) {
                    val nm = cols ?: names(st).also { cols = it; emit("s4|#cols:artifact_aliases", it.joinToString(","), true) }
                    val iA = nm.indexOf("alias_id"); val iT = nm.indexOf("artifact_id"); val iTy = nm.indexOf("artifact_type"); val iAl = nm.indexOf("alias")
                    val aid = text(st, iA); val tid = text(st, iT); val ty = text(st, iTy) ?: "NULL"
                    val aliasNew = aid == null || aid !in inputs.aliasIds
                    val ownerOld = tid != null && (tid in inputs.trailIds || tid in inputs.trackIds || tid in inputs.waypointIds || tid in inputs.routeIds)
                    val owner = if (ownerOld) "id:$tid" else "new:" + when (ty) {
                        "trail" -> ownerHash(conn, "trails", "trail_id", tid); "track" -> ownerHash(conn, "tracks", "track_id", tid)
                        "waypoint" -> ownerHash(conn, "waypoints", "waypoint_id", tid); "route" -> ownerHash(conn, "routes", "route_id", tid)
                        else -> "TYPE?"
                    }
                    val cells = nm.indices.joinToString("|") { i ->
                        when {
                            aliasNew && i == iA -> newId(aid)
                            !ownerOld && i == iT -> newId(tid)
                            aliasNew && nm[i] == "created_at" -> newTime(text(st, i))
                            else -> cell(st, i)
                        }
                    }
                    rows.add(Pair("s4|" + (if (aliasNew) "new|$ty|$owner|" + esc(text(st, iAl) ?: "NULL") else "old|$aid"), cells))
                }
            } finally { st.close() }
            numbered(rows) { k, c -> emit(k, c, true) }
            tail.add(Pair("s9|count:artifact_aliases", rows.size.toString()))
        }
        // ---- s5 tracks ----
        val newTrackHash = HashMap<String, String>()   // track_id -> geom_hash, for s6
        run {
            val fp = Fp(); fps.add("tracks" to fp)
            val st = conn.prepare("SELECT * FROM tracks ORDER BY geom_hash")
            try {
                var cols: List<String>? = null; var last = ""; var dup = 0
                while (st.step()) {
                    val nm = cols ?: names(st).also { cols = it; emit("s5|#cols:tracks", it.joinToString(","), true) }
                    val iId = nm.indexOf("track_id"); val iHash = nm.indexOf("geom_hash")
                    val id = text(st, iId); val isNew = id == null || id !in inputs.trackIds
                    val masked = HashMap<Int, String>()
                    if (isNew) { masked[iId] = newId(id); nm.forEachIndexed { i, c -> if (c == "created_at" || c == "updated_at") masked[i] = newTime(text(st, i)) } }
                    val h = text(st, iHash) ?: "NULL"; dup = if (h == last) dup + 1 else 0; last = h
                    if (id != null) newTrackHash[id] = h
                    val key = "s5|$h" + (if (dup > 0) "#${dup + 1}" else "")
                    fp.row(key, st, masked)
                    emit(key, nm.indices.joinToString("|") { masked[it] ?: cell(st, it) }, true)
                }
            } finally { st.close() }
        }
        // ---- s6 track_properties (keyed by the owning track's hash) ----
        run {
            val rows = ArrayList<Pair<String, String>>()
            val st = dataConn.prepare("SELECT * FROM track_properties")
            try {
                var cols: List<String>? = null
                while (st.step()) {
                    val nm = cols ?: names(st).also { cols = it; emit("s6|#cols:track_properties", it.joinToString(","), true) }
                    val iId = nm.indexOf("track_id"); val id = text(st, iId)
                    val isNew = id == null || id !in inputs.trackIds
                    val owner = (if (id == null) null else newTrackHash[id]) ?: ("ORPHAN:" + (if (isNew) "new" else id))
                    rows.add(Pair("s6|$owner", nm.indices.joinToString("|") { i -> if (isNew && i == iId) newId(id) else cell(st, i) }))
                }
            } finally { st.close() }
            numbered(rows) { k, c -> emit(k, c, true) }
            tail.add(Pair("s9|count:track_properties", rows.size.toString()))
        }
        // ---- s7 waypoints ----
        run {
            val fp = Fp(); fps.add("waypoints" to fp)
            val st = conn.prepare("SELECT * FROM waypoints ORDER BY geom_hash")
            try {
                var cols: List<String>? = null; var last = ""; var dup = 0
                while (st.step()) {
                    val nm = cols ?: names(st).also { cols = it; emit("s7|#cols:waypoints", it.joinToString(","), true) }
                    val iId = nm.indexOf("waypoint_id"); val iHash = nm.indexOf("geom_hash")
                    val id = text(st, iId); val isNew = id == null || id !in inputs.waypointIds
                    val masked = HashMap<Int, String>()
                    if (isNew) { masked[iId] = newId(id); nm.forEachIndexed { i, c -> if (c == "created_at" || c == "updated_at") masked[i] = newTime(text(st, i)) } }
                    val h = text(st, iHash) ?: "NULL"; dup = if (h == last) dup + 1 else 0; last = h
                    val key = "s7|$h" + (if (dup > 0) "#${dup + 1}" else "")
                    fp.row(key, st, masked)
                    emit(key, if (isNew) nm.indices.joinToString("|") { masked[it] ?: cell(st, it) } else "h:" + sha(lightCells(st)), isNew)
                }
            } finally { st.close() }
        }
        for ((t, fp) in fps) { tail.add(Pair("s9|fp:$t", fp.hex())); tail.add(Pair("s9|count:$t", fp.n.toString())) }
        for ((k, v) in tail.sortedBy { it.first }) emit(k, v, true)
    }

    /** the owning row's geom_hash for a new id. CODE RULE 1: [id] is the row's own value and may be NULL. */
    private fun ownerHash(conn: SQLiteConnection, table: String, idCol: String, id: String?): String {
        if (id == null) return "NULLID"
        val st = conn.prepare("SELECT geom_hash FROM $table WHERE $idCol=? LIMIT 1")
        try { st.bindText(1, id); return if (st.step() && !st.isNull(0)) st.getText(0) else "ORPHAN" } finally { st.close() }
    }

    /** sorts (key, cells) by key then cells, numbering repeated keys "#2", "#3" ... so every key is unique and the order is fixed. */
    private fun numbered(rows: MutableList<Pair<String, String>>, out: (String, String) -> Unit) {
        rows.sortWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        var last = ""; var dup = 0
        for ((k, c) in rows) { dup = if (k == last) dup + 1 else 0; last = k; out(k + (if (dup > 0) "#${dup + 1}" else ""), c) }
    }
}
