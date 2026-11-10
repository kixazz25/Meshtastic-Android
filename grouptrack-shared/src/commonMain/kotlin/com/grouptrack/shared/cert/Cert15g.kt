package com.grouptrack.shared.cert

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.osm.GtGpkgDecoder
import com.grouptrack.shared.osm.GtOsmClassifier
import com.grouptrack.shared.osm.GtOsmExtract
import com.grouptrack.shared.osm.GtOsmImport
import com.grouptrack.shared.osm.GtOsmLayers
import com.grouptrack.shared.osm.GtOsmLedger
import com.grouptrack.shared.osm.GtOsmProgress
import com.grouptrack.shared.osm.GtOsmStage
import com.grouptrack.shared.osm.GtPbfTags
import com.grouptrack.shared.text.GtJava
import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * STEP15G-2026-11-10 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15g certification's ONE runner
 * (the OSM extract + import, 2.7e's OsmPbfTagReader / OsmExtractWorker / OsmImportWorker / OsmImportStage / OsmImportLedger /
 * OsmLayerCatalog / OsmGpkgDecoder / OsmTrailClassifier / OsmExtractProgress), used by every side so the cases, their order and the dumps
 * cannot drift:
 *  - on the Droid, GtCert15g (app) runs it with OLD (the GENERATED *CertOld copies, make_old_copies_15g.py) and with [NewSide];
 *  - on the iPad simulator, GtCertOsm15gMacTest runs it with [NewSide] on copies of the same input and compares with the Droid's report.
 * NOTHING LIVE IS TOUCHED: every path is under the side's cert root (osm/, config/); the Downloads sweep and the legacy sweeps are RECORDED,
 * never run; the clocks are fixed (epoch ms 0 -> only the forced progress publishes, eta -1; local time 2026-11-01 12:00:00).
 * THE INPUT, the same on every side: Utah's Geofabrik PBF + free GeoPackage zip (the Mac's _data/droid1_2026-11-06/states/utah, pushed
 * to the Droid as GroupTrack/cert15g_input/), 15c's databases (LIVE), a FRESH GtSchema pair; and crafted files this runner writes:
 * mini GeoPackages (their zips STORED, made here) covering every decode branch, a crafted tag table, a truncated and a bad PBF.
 * THE SCRIPT (keys in brackets), in the coordinator's order (HomeStateImportController.runGeofabrikSource):
 *   P   the pure parts: the catalog (asset; good / broken / required-off overrides), displayName, the classifier grid, the decoder on
 *       crafted blobs, the progress JSON round trip and eta text                                                           [P|...]
 *   S0  stage / statesInFlight / verify* on an empty root, then on each input as it lands                                  [S|...]
 *   T   the tag pass on Utah (the rows, every progress call), then the truncated and the bad PBF                           [T|...]
 *   L   ledger: create (no prior), recordAcquire, pending scope / bbox round trips, clear                                    [L|...]
 *   X   the extract on Utah: the result, every progress JSON, every notification text, the sweep calls                      [X|...]
 *   XM  the extract on the crafted minis (certmini, certmini2 = a changed geometry, certbad = the extent abort, certnone = no
 *       trails kept, certnozip = no zip)                                                                                     [XM|...]
 *   I   the imports on FRESH: certmini (whole state), Utah AREA (St George box), Utah STATE (the trail extent), Utah STATE again,
 *       certmini2 (the changed geometry -> a second trail); then on LIVE: Utah STATE                                          [I|...]
 *   C   cleanup: sweepDebris (crafted debris), archive, finalize (history), discard                                          [C|...]
 *   per call: the result, every progress line, every foreground text, every sweep record; at the end of each phase the files (names; the
 *   JSON texts in full) and the DATABASES the phase made (the tag table, each skinny: schema without android_metadata, every table's
 *   row count and fingerprint, the first 40 rows listed) and the import tables (Cert15f.dumpTables + reference_points).
 */
object Cert15g {
    const val SLUG = "certutah"
    /** Utah, as geofabrik_states.json */
    const val S = 37.0; const val W = -114.05; const val N = 42.0; const val E = -109.04
    /** St George (the area import) */
    const val AS = 37.0; const val AW = -113.7; const val AN = 37.2; const val AE = -113.4

    /** One side's view of 2.7e's OSM code (OLD = the generated copies, NEW = the shared code). Texts are made the same way on both. */
    interface Side {
        val name: String
        fun catalogText(): String                                   // OsmLayerCatalog.load(ctx).toString()
        fun displayName(slug: String): String
        fun classify(t: List<String?>): String                      // type|carto|motorized
        fun decodeLine(b: ByteArray?): String                       // lineText(...) or "null" (an exception -> its class name)
        fun decodePoint(b: ByteArray?): String
        fun progressRound(json: String): String                     // toJson(fromJson(json))
        fun etaText(sec: Long): String
        fun stage(slug: String): String
        fun inFlight(): String
        fun verify(kind: String, p: Path): Boolean                  // pbf | tags | zip | skinny
        fun tagBuild(pbf: Path, out: Path, onProgress: (Long, Int) -> Unit): Int
        fun ledgerCreate(slug: String, state: String, note: String, carry: Boolean)
        fun recordAcquire(slug: String, file: String, bytes: Long, from: String)
        fun setPending(slug: String, scope: String, bbox: DoubleArray?)
        fun setPendingBbox(slug: String, s: Double, w: Double, n: Double, e: Double)
        fun pendingScope(slug: String): String?
        fun pendingBbox(slug: String): String?                      // jd joined by ","
        fun clearPending(slug: String)
        fun extract(slug: String, onProgress: (String) -> Unit): String    // "Success" | "Failure|<error>"
        fun import(slug: String, onProgress: (String) -> Unit): String
        fun trailExtent(slug: String): DoubleArray?
        fun countInBbox(slug: String, s: Double, w: Double, n: Double, e: Double): Int
        fun sweepDebris(slug: String): Int
        fun archive(slug: String): String?                          // the archived file's NAME
        fun finalize(slug: String, bytes: Long, deleted: List<String>)
        fun discard(slug: String): Boolean
        /** what the side recorded since the last call: notification texts ("extract|..." / "import|...") and Downloads sweeps */
        fun takeRecords(): List<String>
    }

    // ---- texts made the same way on every side ---------------------------------------------------------------------------
    fun lineText(wkt: String, hash: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
        "sha=" + Cert15d.sha(wkt) + ";len=" + wkt.length + ";head=" + wkt.take(60) + ";hash=" + hash + ";" +
            listOf(minLat, maxLat, minLon, maxLon).joinToString(",") { GtJava.jd(it) }
    fun pointText(lon: Double, lat: Double) = GtJava.jd(lon) + "," + GtJava.jd(lat)
    fun classText(type: String, carto: String?, motorized: Boolean) = "$type|${carto ?: "null"}|$motorized"

    /** NEW: the shared code, on its own env (the Droid's GtImportAndroid-based cert env, or the iPad's). */
    class NewSide(private val env: com.grouptrack.shared.imports.GtImportEnv, private val plat: CertPlat,
                  private val importDbs: () -> Triple<SQLiteConnection?, SQLiteConnection?, com.grouptrack.shared.spatial.GtWrite>) : Side {
        override val name = "NEW"
        private val stage = GtOsmStage(env, plat)
        private val ledger = GtOsmLedger(env, stage)
        override fun catalogText() = GtOsmLayers(env).load().toString()
        override fun displayName(slug: String) = GtOsmStage.displayName(slug)
        override fun classify(t: List<String?>) = GtOsmClassifier.classify(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11])
            .let { classText(it.type, it.carto, it.motorized) }
        override fun decodeLine(b: ByteArray?) = try { GtGpkgDecoder.decodeLine(b)?.let { lineText(it.wkt, it.geomHash, it.minLat, it.maxLat, it.minLon, it.maxLon) } ?: "null" }
            catch (e: Exception) { "EXCEPTION" }
        override fun decodePoint(b: ByteArray?) = try { GtGpkgDecoder.decodePoint(b)?.let { pointText(it.lon, it.lat) } ?: "null" } catch (e: Exception) { "EXCEPTION" }
        override fun progressRound(json: String) = GtOsmProgress.toJson(GtOsmProgress.fromJson(json))
        override fun etaText(sec: Long) = GtOsmProgress.etaText(sec)
        override fun stage(slug: String) = stage.stageOf(slug).name
        override fun inFlight() = stage.statesInFlight().toString()
        override fun verify(kind: String, p: Path) = when (kind) {
            "pbf" -> stage.verifyPbf(p); "tags" -> stage.verifyTags(p); "zip" -> stage.verifyZip(p); else -> stage.verifySkinny(p) }
        override fun tagBuild(pbf: Path, out: Path, onProgress: (Long, Int) -> Unit) = GtPbfTags.build(env.files.fs, pbf, out, { env.clock.epochMs() }, onProgress)
        override fun ledgerCreate(slug: String, state: String, note: String, carry: Boolean) {
            ledger.create(slug, state, note, if (carry) ledger.priorImports(slug) else null) }
        override fun recordAcquire(slug: String, file: String, bytes: Long, from: String) = ledger.recordAcquire(slug, file, bytes, from)
        override fun setPending(slug: String, scope: String, bbox: DoubleArray?) = ledger.setPendingImport(slug, scope, bbox)
        override fun setPendingBbox(slug: String, s: Double, w: Double, n: Double, e: Double) = ledger.setPendingBbox(slug, s, w, n, e)
        override fun pendingScope(slug: String) = ledger.pendingScope(slug)
        override fun pendingBbox(slug: String) = ledger.pendingBbox(slug)?.joinToString(",") { GtJava.jd(it) }
        override fun clearPending(slug: String) = ledger.clearPendingImport(slug)
        override fun extract(slug: String, onProgress: (String) -> Unit) = when (val r = GtOsmExtract(env, plat, slug, onProgress).doWork()) {
            is GtOsmExtract.Result.Success -> "Success"; is GtOsmExtract.Result.Failure -> "Failure|" + r.error }
        override fun import(slug: String, onProgress: (String) -> Unit): String {
            val (c, d, w) = importDbs()
            return when (val r = GtOsmImport(c, d, w, env, plat, slug, onProgress).doWork()) {
                is GtOsmImport.Result.Success -> "Success"; is GtOsmImport.Result.Failure -> "Failure|" + r.error }
        }
        override fun trailExtent(slug: String) = stage.trailExtent(slug)
        override fun countInBbox(slug: String, s: Double, w: Double, n: Double, e: Double) = stage.countTrailsInBbox(slug, s, w, n, e)
        override fun sweepDebris(slug: String) = stage.sweepDebris(slug)
        override fun archive(slug: String) = ledger.archiveLedger(slug)?.name
        override fun finalize(slug: String, bytes: Long, deleted: List<String>) = ledger.finalizeAndDelete(slug, bytes, deleted)
        override fun discard(slug: String) = stage.discardState(slug)
        override fun takeRecords() = plat.take()
    }

    /** The cert's GtOsmPlatform: records every call, touches nothing outside the cert root. */
    class CertPlat(private val free: (Path) -> Long) : com.grouptrack.shared.osm.GtOsmPlatform {   // free = File.usableSpace on the Droid (as OLD reads it)
        private val rec = ArrayList<String>()
        override fun onOsmRoot(root: Path) {}
        override fun discardLegacy(slug: String) { rec.add("legacy|$slug") }
        override fun usableSpace(dir: Path): Long = free(dir)
        override fun sweepDownloadedZips(slug: String) { rec.add("sweep|$slug") }
        override fun foreground(worker: String, text: String) { rec.add("$worker|$text") }
        fun take(): List<String> { val r = rec.toList(); rec.clear(); return r }
    }

    // =================================================================================================================================
    // crafted inputs (written by the runner, the same bytes on every side)
    // =================================================================================================================================
    private fun le32(b: Buffer, v: Int) { b.writeIntLe(v) }
    private fun be32(b: Buffer, v: Int) { b.writeInt(v) }
    private fun d(b: Buffer, v: Double, little: Boolean) { if (little) b.writeLongLe(v.toRawBits()) else b.writeLong(v.toRawBits()) }

    /** A GPKG geometry blob: "GP", version 0, flags (envelope code << 1 | byte order bit), srs 4326, the envelope, then the WKB. */
    fun gpkgBlob(envCode: Int, little: Boolean, wkbType: Int, parts: List<List<Pair<Double, Double>>>, magic: String = "GP",
                 truncatePoints: Int = -1): ByteArray {
        val b = Buffer()
        b.writeUtf8(magic); b.writeByte(0); b.writeByte((envCode shl 1) or (if (little) 1 else 0)); le32(b, 4326)
        val envN = intArrayOf(0, 4, 6, 6, 8, 0, 0, 0)[envCode]
        repeat(envN) { d(b, it.toDouble(), true) }
        b.writeByte(if (little) 1 else 0)
        fun i32(v: Int) = if (little) le32(b, v) else be32(b, v)
        i32(wkbType)
        fun ring(pts: List<Pair<Double, Double>>) { i32(if (truncatePoints >= 0) truncatePoints else pts.size); for ((x, y) in pts) { d(b, x, little); d(b, y, little) } }
        when (wkbType and 0xFF) {
            1 -> { d(b, parts[0][0].first, little); d(b, parts[0][0].second, little) }
            2 -> ring(parts[0])
            5 -> { i32(parts.size); for (p in parts) { b.writeByte(if (little) 1 else 0); i32(2); ring(p) } }
        }
        return b.readByteArray()
    }

    private fun line(vararg p: Pair<Double, Double>) = listOf(p.toList())

    /** The decoder cases: (label, blob) -- every branch of wkbOffset / decodeLine / decodePoint. */
    fun decoderCases(): List<Pair<String, ByteArray?>> = listOf(
        "null" to null,
        "short" to byteArrayOf(0x47, 0x50, 0, 0),
        "magic" to gpkgBlob(1, true, 2, line(-112.0 to 38.0, -112.1 to 38.1), magic = "XP"),
        "env0le" to gpkgBlob(0, true, 2, line(-112.0 to 38.0, -112.1 to 38.1)),
        "env1le" to gpkgBlob(1, true, 2, line(-112.123456789012 to 38.000000000001, -112.5 to 38.25, -112.75 to 38.125)),
        "env2be" to gpkgBlob(2, false, 2, line(-113.0 to 39.0, -113.000001 to 39.000001)),
        "env3le" to gpkgBlob(3, true, 2, line(-111.0 to 40.0, -111.1 to 40.1)),
        "env4be" to gpkgBlob(4, false, 2, line(-110.0 to 41.0, -110.1 to 41.1)),
        "env5" to gpkgBlob(5, true, 2, line(-110.0 to 41.0, -110.1 to 41.1)),
        "multi" to gpkgBlob(1, true, 5, listOf(listOf(-112.0 to 38.0, -112.01 to 38.01), listOf(-112.02 to 38.02, -112.03 to 38.03, -112.04 to 38.04))),
        "multi1pt" to gpkgBlob(1, true, 5, listOf(listOf(-112.0 to 38.0, -112.01 to 38.01), listOf(-112.02 to 38.02), listOf(-112.05 to 38.05, -112.06 to 38.06))),
        "multi1ptfirst" to gpkgBlob(1, true, 5, listOf(listOf(-112.02 to 38.02), listOf(-112.05 to 38.05, -112.06 to 38.06))),
        "line1pt" to gpkgBlob(1, true, 2, line(-112.0 to 38.0)),
        "lineZ" to gpkgBlob(1, true, 1002, line(-112.0 to 38.0, -112.1 to 38.1)),
        "trunc" to gpkgBlob(1, true, 2, line(-112.0 to 38.0, -112.1 to 38.1), truncatePoints = 5),
        "neg" to gpkgBlob(1, true, 2, line(-112.0 to 38.0, -112.1 to 38.1), truncatePoints = -2).let { it },   // n < 0 written as 0xFFFFFFFE
        "tiny" to gpkgBlob(1, true, 2, line(1.0E-7 to -0.0, 1.0E7 to 123456789.0)),
        "pointLE" to gpkgBlob(0, true, 1, line(-111.891 to 40.7608)),
        "pointBE" to gpkgBlob(0, false, 1, line(-109.5498 to 38.5733)),
        "pointEnv1" to gpkgBlob(1, true, 1, line(-113.5684 to 37.0965)),
        "pointAsLine" to gpkgBlob(0, true, 1, line(-111.0 to 40.0)),
        "byteorder" to byteArrayOf(0x47, 0x50, 0, 0, 0, 0, 0, 0, 7, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    )

    /** The classifier grid: every exclusion, confirmation, surface and shape branch, and their mixes (12 tags each). */
    fun classifierCases(): List<List<String?>> {
        val hw = listOf(null, "track", "path", "cycleway", "bridleway", "tertiary", "residential", "service", "unclassified", "road", "byway")
        val surf = listOf(null, "gravel", "asphalt", "mud")
        val out = ArrayList<List<String?>>()
        for (h in hw) for (s in surf) for (tt in listOf(null, "grade3", "")) out.add(listOf(h, s, tt, null, null, null, null, null, null, null, null, null))
        val extra = listOf(
            listOf("access" to "private"), listOf("access" to "no"), listOf("motor_vehicle" to "no"), listOf("vehicle" to "no"),
            listOf("motor_vehicle" to "no", "bicycle" to "yes", "foot" to "designated"), listOf("vehicle" to "no", "bicycle" to "designated"),
            listOf("motor_vehicle" to "no", "foot" to "yes"), listOf("ohv" to "yes"), listOf("atv" to "designated"), listOf("ohv" to "no @ (>48\" wide);designated"),
            listOf("four_wd_only" to "yes"), listOf("four_wd_only" to "designated"), listOf("motor_vehicle" to "yes"), listOf("motorcar" to "designated"),
            listOf("motor_vehicle" to "yes", "tracktype" to "grade1"), listOf("bicycle" to "yes", "foot" to "yes"), listOf("bicycle" to "yes"),
            listOf("foot" to "yes"), listOf("access" to "yes", "motor_vehicle" to "destination"))
        val names = listOf("highway", "surface", "tracktype", "access", "motor_vehicle", "vehicle", "four_wd_only", "ohv", "atv", "motorcar", "foot", "bicycle")
        for (h in listOf("track", "path", "cycleway", "bridleway", "residential", "unclassified")) for (x in extra) {
            val m = HashMap<String, String?>(); m["highway"] = h; for ((k, v) in x) m[k] = v
            out.add(names.map { m[it] })
        }
        return out
    }

    /** a STORED zip of one entry "<dir>/<name>" (as Geofabrik nests it) plus a README, CRC-32 made here */
    fun storedZip(entry: String, data: ByteArray): ByteArray {
        fun crc32(b: ByteArray): Int {
            var c = -1
            for (x in b) { c = c xor (x.toInt() and 0xFF); repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 } }
            return c.inv()
        }
        val files = listOf("README" to "cert README\n".encodeToByteArray(), entry to data)
        val out = Buffer(); val cd = Buffer(); val offsets = ArrayList<Int>()
        for ((n, b) in files) {
            offsets.add(out.size.toInt()); val nb = n.encodeToByteArray(); val crc = crc32(b)
            out.writeIntLe(0x04034b50); out.writeShortLe(20); out.writeShortLe(0); out.writeShortLe(0); out.writeShortLe(0); out.writeShortLe(0x5A21)
            out.writeIntLe(crc); out.writeIntLe(b.size); out.writeIntLe(b.size); out.writeShortLe(nb.size); out.writeShortLe(0); out.write(nb); out.write(b)
            cd.writeIntLe(0x02014b50); cd.writeShortLe(20); cd.writeShortLe(20); cd.writeShortLe(0); cd.writeShortLe(0); cd.writeShortLe(0); cd.writeShortLe(0x5A21)
            cd.writeIntLe(crc); cd.writeIntLe(b.size); cd.writeIntLe(b.size); cd.writeShortLe(nb.size); cd.writeShortLe(0); cd.writeShortLe(0)
            cd.writeShortLe(0); cd.writeShortLe(0); cd.writeIntLe(0); cd.writeIntLe(offsets.last()); cd.write(nb)
        }
        val cdOff = out.size.toInt(); val cdLen = cd.size.toInt(); out.writeAll(cd)
        out.writeIntLe(0x06054b50); out.writeShortLe(0); out.writeShortLe(0); out.writeShortLe(files.size); out.writeShortLe(files.size)
        out.writeIntLe(cdLen); out.writeIntLe(cdOff); out.writeShortLe(0)
        return out.readByteArray()
    }

    private fun exec(c: SQLiteConnection, sql: String, args: List<Any?> = emptyList()) {
        val st = c.prepare(sql)
        try {
            args.forEachIndexed { i, a -> when (a) { null -> st.bindNull(i + 1); is ByteArray -> st.bindBlob(i + 1, a); is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong()); is Double -> st.bindDouble(i + 1, a); else -> st.bindText(i + 1, a.toString()) } }
            st.step()
        } finally { st.close() }
    }

    /**
     * A mini GeoPackage, the Geofabrik layout (the four free tables + gpkg_contents with the declared extents). [variant]:
     * "mini" (every decode branch), "mini2" (900001's shape changed), "bad" (a place outside the declared extent), "none" (no trail kept).
     */
    fun writeMiniGpkg(fs: FileSystem, path: Path, variant: String) {
        if (fs.exists(path)) fs.delete(path)
        val c = gtOpenDatabase(path.toString(), false)
        try {
            exec(c, "CREATE TABLE gpkg_contents (table_name TEXT PRIMARY KEY, data_type TEXT, min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE)")
            exec(c, "CREATE TABLE gis_osm_roads_free (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom LINESTRING, osm_id TEXT(12), code MEDIUMINT, fclass TEXT(28), name TEXT(100), ref TEXT(20), oneway TEXT(1), maxspeed MEDIUMINT)")
            exec(c, "CREATE TABLE gis_osm_places_free (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom POINT, osm_id TEXT(12), code MEDIUMINT, fclass TEXT(28), population MEDIUMINT, name TEXT(100))")
            exec(c, "CREATE TABLE gis_osm_natural_free (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom POINT, osm_id TEXT(12), code MEDIUMINT, fclass TEXT(28), name TEXT(100))")
            exec(c, "CREATE TABLE gis_osm_pois_free (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom POINT, osm_id TEXT(12), code MEDIUMINT, fclass TEXT(28), name TEXT(100))")
            for (t in listOf("gis_osm_roads_free", "gis_osm_places_free", "gis_osm_natural_free", "gis_osm_pois_free"))
                exec(c, "INSERT INTO gpkg_contents VALUES (?,?,?,?,?,?)", listOf(t, "features", -114.06, 36.99, -109.03, 42.01))
            fun road(geom: ByteArray?, id: String?, fclass: String, name: String?, ref: String?, speed: Long?) =
                exec(c, "INSERT INTO gis_osm_roads_free (geom,osm_id,code,fclass,name,ref,oneway,maxspeed) VALUES (?,?,?,?,?,?,?,?)",
                    listOf(geom, id, 5141L, fclass, name, ref, "B", speed))
            val cases = decoderCases().toMap()
            if (variant == "none") {
                road(cases["env1le"], "900101", "motorway", "Cert Motorway", null, 0L)
                road(cases["env1le"], "900102", "path", "Cert closed path", null, 0L)   // the tag table says motor_vehicle=no
            } else {
                road(if (variant == "mini2") gpkgBlob(1, true, 2, line(-112.0 to 38.0, -112.2 to 38.2, -112.3 to 38.3)) else cases["env1le"], "900001", "track_grade2", "Cert Track", "T1", 0L)
                road(cases["env2be"], "900002", "path", "Cert Path", null, 0L)
                road(cases["multi1pt"], "900003", "unclassified", null, "U3", 40L)
                road(cases["magic"], "900004", "track", "Bad magic", null, 0L)
                road(null, "900005", "track", "Null geom", null, 0L)
                road(cases["env1le"], "900006", "motorway", "Not in the filter", null, 110L)
                road(cases["lineZ"], "900007", "track", "Z type", null, 0L)
                road(cases["env5"], "900008", "track", "Reserved envelope", null, 0L)
                road(cases["line1pt"], "900009", "track", "One point", null, 0L)
                road(cases["env3le"], "abc", "residential", "Odd osm_id", null, 0L)
                road(cases["trunc"], "900011", "track", "Truncated", null, 0L)
                road(cases["env4be"], "900012", "service", "Envelope 4", null, 0L)
                road(cases["env0le"], "900013", "road", "Cert & \"quoted\" / name", null, 0L)
                road(cases["tiny"], "900014", "byway", "", null, 0L)
                road(cases["multi"], "900015", "tertiary", "Tertiary gravel", null, 50L)
            }
            fun place(table: String, geom: ByteArray?, id: String, fclass: String, name: String?, pop: Long?) =
                if (table == "gis_osm_places_free") exec(c, "INSERT INTO gis_osm_places_free (geom,osm_id,code,fclass,population,name) VALUES (?,?,?,?,?,?)",
                    listOf(geom, id, 1001L, fclass, pop, name))
                else exec(c, "INSERT INTO $table (geom,osm_id,code,fclass,name) VALUES (?,?,?,?,?)", listOf(geom, id, 4101L, fclass, name))
            place("gis_osm_places_free", cases["pointLE"], "800001", "city", "Cert City", 200133L)
            place("gis_osm_places_free", cases["pointBE"], "800002", "town", "Cert Town", null)
            place("gis_osm_places_free", cases["pointEnv1"], "800003", "town", "", 5L)
            place("gis_osm_places_free", cases["magic"], "800004", "village", "Bad place", 1L)
            place("gis_osm_natural_free", cases["pointLE"], "800001", "peak", "Cert City", null)   // the same node as the city: the same hash
            place("gis_osm_natural_free", gpkgBlob(0, true, 1, line(-111.6383 to 40.4264)), "800010", "peak", "Cert Peak", null)
            place("gis_osm_natural_free", gpkgBlob(0, true, 1, line(-111.7 to 40.5)), "800011", "spring", null, null)
            if (variant == "bad") place("gis_osm_places_free", gpkgBlob(0, true, 1, line(-120.0 to 45.0)), "800099", "city", "Outside", 9L)
            exec(c, "DROP TABLE IF EXISTS android_metadata"); exec(c, "VACUUM")   // STEP15G-FIXA-2026-11-10: the same file size on every platform
        } finally { c.close() }
    }

    /** The crafted tag table for the minis (osm_way_tags, the reader's own columns). */
    fun writeMiniTags(fs: FileSystem, path: Path) {
        if (fs.exists(path)) fs.delete(path)
        val c = gtOpenDatabase(path.toString(), false)
        try {
            exec(c, "CREATE TABLE osm_way_tags (osm_id INTEGER PRIMARY KEY, highway TEXT,surface TEXT,tracktype TEXT,smoothness TEXT,access TEXT," +
                "motor_vehicle TEXT,four_wd_only TEXT,ohv TEXT,atv TEXT,motorcar TEXT,vehicle TEXT,foot TEXT,bicycle TEXT,horse TEXT)")
            fun t(id: Long, vararg kv: Pair<String, String>) {
                val m = kv.toMap(); val cols = listOf("highway", "surface", "tracktype", "smoothness", "access", "motor_vehicle", "four_wd_only", "ohv", "atv", "motorcar", "vehicle", "foot", "bicycle", "horse")
                exec(c, "INSERT INTO osm_way_tags VALUES (" + (0..cols.size).joinToString(",") { "?" } + ")", listOf<Any?>(id) + cols.map { m[it] })
            }
            t(900001, "highway" to "track", "tracktype" to "grade2")
            t(900002, "highway" to "path", "motor_vehicle" to "no", "foot" to "yes")
            t(900003, "highway" to "unclassified", "surface" to "gravel")
            t(900012, "highway" to "service", "surface" to "asphalt")
            t(900013, "highway" to "road", "ohv" to "designated")
            t(900015, "highway" to "tertiary", "surface" to "gravel")
            t(900102, "highway" to "path", "motor_vehicle" to "no")
            // padding so the file passes verifyTags' 4096-byte floor
            for (k in 0 until 40) t(700000L + k, "highway" to "track", "surface" to "dirt", "smoothness" to "bad", "horse" to "yes")
        } finally { c.close() }
    }

    // =================================================================================================================================
    // the script
    // =================================================================================================================================
    /** [osmRoot] = the side's env.paths.dir("osm"); [configDir] = its env.paths.dir("config"); [work] = scratch for crafted files. */
    class Ctx(val fs: FileSystem, val inputDir: Path, val osmRoot: Path, val configDir: Path, val work: Path,
              val dumpImport: (label: String) -> Unit)

    private fun call(key: String, side: Side, emit: (String, String) -> Unit, f: ((String) -> Unit) -> String) {
        side.takeRecords()
        val prog = ArrayList<String>()
        val r = try { f { prog.add(it) } } catch (e: Throwable) { "EXCEPTION" }
        emit("$key|result", r)
        prog.forEachIndexed { i, p -> emit("$key|p|" + (i + 1).toString().padStart(5, '0'), p) }
        side.takeRecords().forEachIndexed { i, p -> emit("$key|rec|" + (i + 1).toString().padStart(4, '0'), p) }
    }

    private fun copyIn(fs: FileSystem, from: Path, to: Path) { fs.createDirectories(to.parent!!); if (fs.exists(to)) fs.delete(to); fs.copy(from, to) }

    fun run(side: Side, ctx: Ctx, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        val fs = ctx.fs
        fs.createDirectories(ctx.work); fs.createDirectories(ctx.configDir)
        val pbfIn = ctx.inputDir / "utah-latest.osm.pbf"; val zipIn = ctx.inputDir / "utah-latest-free.gpkg.zip"
        for (p in listOf(pbfIn, zipIn)) emit("input|" + p.name, "size=" + (fs.metadataOrNull(p)?.size ?: -1))

        // ---- P: the pure parts ----
        progress("${side.name} P")
        val ov = ctx.configDir / "osm_layers.json"
        call("P|catalog|asset", side, emit) { side.catalogText() }
        val good = """{"layers":[{"id":"natural","label":"Peaks","enabled":true,"source_table":"gis_osm_natural_free","geometry":"point","target_table":"osm_natural","columns":["osm_id","fclass","name"]},""" +
            """{"id":"trails","enabled":"TRUE","required":true,"source_table":"gis_osm_roads_free","geometry":"line","target_table":"osm_trails","columns":["osm_id","fclass","name","ref","maxspeed"],"filter_column":"fclass","filter_values":["track","path",1]}]}"""
        for ((k, text) in listOf("good" to good, "broken" to "{\"layers\":[{\"id\":", "notobj" to "[1,2]", "nolayers" to "{\"x\":1}",
                "reqoff" to good.replace("\"enabled\":\"TRUE\"", "\"enabled\":false"), "dupe" to good.replace("\"id\":\"natural\"", "\"id\":\"trails\""),
                "badgeom" to good.replace("\"geometry\":\"point\"", "\"geometry\":\"polygon\""), "emptyfilter" to good.replace("[\"track\",\"path\",1]", "[]"),
                "nocols" to good.replace("[\"osm_id\",\"fclass\",\"name\"]", "[]"))) {
            fs.write(ov) { writeUtf8(text) }
            call("P|catalog|$k", side, emit) { side.catalogText() }
        }
        fs.delete(ov)
        for (s in listOf("utah", "north-carolina", "new_mexico", "district-of-columbia", "x", "-a--b_", "")) call("P|name|$s", side, emit) { side.displayName(s) }
        classifierCases().forEachIndexed { i, t -> call("P|class|" + i.toString().padStart(3, '0'), side, emit) { t.joinToString(",") + " -> " + side.classify(t) } }
        for ((label, b) in decoderCases()) {
            call("P|line|$label", side, emit) { side.decodeLine(b) }
            call("P|point|$label", side, emit) { side.decodePoint(b) }
        }
        for ((k, j) in listOf("ok" to """[{"id":"unzip","label":"Unzip","done":5,"total":10,"complete":false,"eta_sec":-1},{"id":"trails","label":"Trails / \"x\"","done":"7","total":2.9,"complete":"true","eta_sec":12}]""",
                "bad" to "[{\"id\":1},", "empty" to "", "notarr" to "{\"id\":1}", "nested" to "[[1]]")) call("P|prog|$k", side, emit) { side.progressRound(j) }
        for (s in listOf(-5L, -1L, 0L, 1L, 89L, 90L, 119L, 149L, 150L, 3600L)) call("P|eta|$s", side, emit) { side.etaText(s) }

        // ---- S0 + the inputs land ----
        val slug = SLUG
        val dir = ctx.osmRoot / slug
        call("S|empty|stage", side, emit) { side.stage(slug) }
        call("S|empty|inflight", side, emit) { side.inFlight() }
        val pbf = dir / "$slug-latest.osm.pbf"; val zip = dir / "$slug-latest-free.gpkg.zip"; val tags = dir / "osm_way_tags.db"
        progress("${side.name} copy inputs"); copyIn(fs, pbfIn, pbf)
        call("S|pbf|stage", side, emit) { side.stage(slug) }
        call("S|pbf|verify", side, emit) { side.verify("pbf", pbf).toString() }
        copyIn(fs, zipIn, zip)
        call("S|zip|stage", side, emit) { side.stage(slug) }
        call("S|zip|verify", side, emit) { side.verify("zip", zip).toString() }
        call("S|zip|inflight", side, emit) { side.inFlight() }
        for ((k, p) in listOf("pbfAsZip" to pbf, "zipAsPbf" to zip)) call("S|cross|$k", side, emit) { side.verify(if (k == "pbfAsZip") "zip" else "pbf", p).toString() }

        // ---- T: the tag pass ----
        progress("${side.name} T utah (the tag pass)")
        call("T|utah", side, emit) { p -> side.tagBuild(pbf, tags) { w, r -> p("$w,$r") }.toString() }
        call("T|utah|verify", side, emit) { side.verify("tags", tags).toString() }
        call("T|utah|stage", side, emit) { side.stage(slug) }
        dumpDb(fs, "T|db", tags, emit, listRows = 40)
        val trunc = ctx.work / "trunc.osm.pbf"; val tOut = ctx.work / "trunc_tags.db"
        fs.write(trunc) { fs.read(pbfIn) { write(readByteString(3_000_000)) } }
        call("T|trunc|verify", side, emit) { side.verify("pbf", trunc).toString() }
        call("T|trunc", side, emit) { p -> side.tagBuild(trunc, tOut) { w, r -> p("$w,$r") }.toString() }
        call("T|trunc|out", side, emit) { fs.exists(tOut).toString() }
        val bad = ctx.work / "bad.osm.pbf"; val bOut = ctx.work / "bad_tags.db"
        fs.write(bad) { writeInt(0x7FFF0000); write(ByteArray(2048) { (it * 7).toByte() }) }
        call("T|bad|verify", side, emit) { side.verify("pbf", bad).toString() }
        call("T|bad", side, emit) { p -> side.tagBuild(bad, bOut) { w, r -> p("$w,$r") }.toString() }
        call("T|bad|out", side, emit) { fs.exists(bOut).toString() }
        call("T|missing", side, emit) { p -> side.tagBuild(ctx.work / "none.pbf", ctx.work / "none.db") { w, r -> p("$w,$r") }.toString() }

        // ---- L: the ledger (the coordinator's create, then the pending round trips) ----
        call("L|create", side, emit) { side.ledgerCreate(slug, side.displayName(slug), "Geofabrik Utah (auto download)", true); "ok" }
        call("L|acquire", side, emit) { side.recordAcquire(slug, zip.name, 338566732L, "Download/utah-latest-free.gpkg.zip"); "ok" }
        call("L|scope0", side, emit) { side.pendingScope(slug) ?: "null" }
        call("L|pend|null", side, emit) { side.setPending(slug, "area", null); (side.pendingScope(slug) ?: "null") + "|" + (side.pendingBbox(slug) ?: "null") }
        call("L|pend|fill", side, emit) { side.setPendingBbox(slug, 37.25, -113.5, 37.75, -112.0); (side.pendingScope(slug) ?: "null") + "|" + (side.pendingBbox(slug) ?: "null") }
        call("L|pend|whole", side, emit) { side.setPending(slug, "state", doubleArrayOf(S, W, N, E)); side.pendingBbox(slug) ?: "null" }
        call("L|pend|short", side, emit) { side.setPending(slug, "state", doubleArrayOf(1.0, 2.0)); side.pendingBbox(slug) ?: "null" }
        call("L|clear", side, emit) { side.clearPending(slug); side.clearPending(slug); (side.pendingScope(slug) ?: "null") }
        snapshot(fs, "L|files", dir, emit)

        // ---- X: the extract on Utah ----
        progress("${side.name} X utah (the extract)")
        call("X|utah", side, emit) { p -> side.extract(slug, p) }
        call("X|utah|stage", side, emit) { side.stage(slug) }
        call("X|utah|verify", side, emit) { side.verify("skinny", dir / GtOsmStage.SKINNY_NAME).toString() }
        call("X|utah|extent", side, emit) { side.trailExtent(slug)?.joinToString(",") { GtJava.jd(it) } ?: "null" }
        call("X|utah|count|state", side, emit) { side.countInBbox(slug, S, W, N, E).toString() }
        call("X|utah|count|area", side, emit) { side.countInBbox(slug, AS, AW, AN, AE).toString() }
        snapshot(fs, "X|files", dir, emit)
        dumpDb(fs, "X|db", dir / GtOsmStage.SKINNY_NAME, emit, listRows = 40)

        // ---- XM: the crafted minis ----
        progress("${side.name} XM minis")
        for (v in listOf("mini", "bad", "none")) {
            val ms = "cert$v"; val md = ctx.osmRoot / ms; fs.createDirectories(md)
            val gp = ctx.work / "$v.gpkg"; writeMiniGpkg(fs, gp, v)
            fs.write(md / "$ms-latest-free.gpkg.zip") { write(storedZip("$ms-latest-free.gpkg/$ms.gpkg", fs.read(gp) { readByteArray() })) }
            emit("input|mini|$v", "size=" + (fs.metadataOrNull(md / "$ms-latest-free.gpkg.zip")?.size ?: -1))   // STEP15G-FIXA-2026-11-10
            writeMiniTags(fs, md / "osm_way_tags.db")
            call("XM|$ms|ledger", side, emit) { side.ledgerCreate(ms, side.displayName(ms), "cert", false); "ok" }
            call("XM|$ms", side, emit) { p -> side.extract(ms, p) }
            call("XM|$ms|stage", side, emit) { side.stage(ms) }
            snapshot(fs, "XM|$ms|files", md, emit)
            if (fs.exists(md / GtOsmStage.SKINNY_NAME)) dumpDb(fs, "XM|$ms|db", md / GtOsmStage.SKINNY_NAME, emit, listRows = 100)
        }
        call("XM|certnozip", side, emit) { p -> side.extract("certnozip", p) }

        // ---- I: the imports (FRESH, then LIVE) ----
        val mini = "certmini"
        call("I|nopending", side, emit) { p -> side.import(mini, p) }
        call("I|mini|set", side, emit) { side.setPending(mini, "state", side.trailExtent(mini)); "ok" }
        progress("${side.name} I mini"); call("I|mini", side, emit) { p -> side.import(mini, p) }
        call("I|area|set", side, emit) { side.setPending(slug, "area", doubleArrayOf(AS, AW, AN, AE)); "ok" }
        progress("${side.name} I area"); call("I|area", side, emit) { p -> side.import(slug, p) }
        call("I|state|set", side, emit) { side.setPending(slug, "state", side.trailExtent(slug)); "ok" }
        progress("${side.name} I state"); call("I|state", side, emit) { p -> side.import(slug, p) }
        call("I|state2|set", side, emit) { side.setPending(slug, "state", side.trailExtent(slug)); "ok" }
        progress("${side.name} I state again"); call("I|state2", side, emit) { p -> side.import(slug, p) }
        // the changed geometry: certmini re-extracted from the mini2 GeoPackage (900001 moved), imported again
        run {
            val md = ctx.osmRoot / mini; val gp = ctx.work / "mini2.gpkg"; writeMiniGpkg(fs, gp, "mini2")
            fs.write(md / "$mini-latest-free.gpkg.zip") { write(storedZip("$mini-latest-free.gpkg/$mini.gpkg", fs.read(gp) { readByteArray() })) }
            emit("input|mini|mini2", "size=" + (fs.metadataOrNull(md / "$mini-latest-free.gpkg.zip")?.size ?: -1))   // STEP15G-FIXA-2026-11-10
            call("I|mini2|extract", side, emit) { p -> side.extract(mini, p) }
            call("I|mini2|set", side, emit) { side.setPending(mini, "state", side.trailExtent(mini)); "ok" }
            call("I|mini2", side, emit) { p -> side.import(mini, p) }
        }
        call("I|empty|set", side, emit) { side.setPending(mini, "area", doubleArrayOf(10.0, 10.0, 11.0, 11.0)); "ok" }
        call("I|empty", side, emit) { p -> side.import(mini, p) }
        snapshot(fs, "I|files", dir, emit)
        snapshot(fs, "I|filesMini", ctx.osmRoot / mini, emit)
        ctx.dumpImport("fresh")

        // ---- C: cleanup ----
        for (n in listOf("x.part", "y.TMP", "z.gpkg", "keep.txt", "ready_for_import.db.part")) fs.write(dir / n) { writeUtf8(n) }
        call("C|debris", side, emit) { side.sweepDebris(slug).toString() }
        snapshot(fs, "C|afterDebris", dir, emit)
        call("C|archive", side, emit) { side.archive(slug) ?: "null" }
        call("C|archive2", side, emit) { side.archive(slug) ?: "null" }
        call("C|ledgerMini", side, emit) { side.ledgerCreate(mini, "Cert Mini", "cert", true); "ok" }
        call("C|finalize", side, emit) { side.finalize(mini, 12345L, listOf("a.zip", "b \"q\".pbf")); "ok" }
        call("C|finalize2", side, emit) { side.finalize("certbad", 0L, emptyList()); "ok" }
        snapshot(fs, "C|history", ctx.osmRoot / "history", emit)
        snapshot(fs, "C|root", ctx.osmRoot, emit)
        call("C|inflight", side, emit) { side.inFlight() }
        for (s in listOf(mini, "certbad", "certnone", slug, "certnozip")) call("C|discard|$s", side, emit) { side.discard(s).toString() }
        call("C|inflight2", side, emit) { side.inFlight() }
        snapshot(fs, "C|rootAfter", ctx.osmRoot, emit)
    }

    /** the LIVE import (15c's databases): Utah whole state on a fresh extract of the same input */
    fun runLive(side: Side, ctx: Ctx, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        val fs = ctx.fs; val slug = SLUG; val dir = ctx.osmRoot / slug
        copyIn(fs, ctx.inputDir / "utah-latest.osm.pbf", dir / "$slug-latest.osm.pbf")
        copyIn(fs, ctx.inputDir / "utah-latest-free.gpkg.zip", dir / "$slug-latest-free.gpkg.zip")
        progress("${side.name} LIVE tag pass")
        call("V|tags", side, emit) { p -> side.tagBuild(dir / "$slug-latest.osm.pbf", dir / "osm_way_tags.db") { w, r -> p("$w,$r") }.toString() }
        call("V|ledger", side, emit) { side.ledgerCreate(slug, side.displayName(slug), "Geofabrik Utah (auto download)", true); "ok" }
        progress("${side.name} LIVE extract"); call("V|extract", side, emit) { p -> side.extract(slug, p) }
        call("V|set", side, emit) { side.setPending(slug, "state", doubleArrayOf(S, W, N, E)); "ok" }
        progress("${side.name} LIVE import"); call("V|import", side, emit) { p -> side.import(slug, p) }
        snapshot(fs, "V|files", dir, emit)
        call("V|discard", side, emit) { side.discard(slug).toString() }
        ctx.dumpImport("live")
    }

    // =================================================================================================================================
    // snapshots and database dumps (made by the SAME code on every side, from the files the side's run left)
    // =================================================================================================================================
    /** every file in [dir] by name: the JSON texts in full; a database by name only (its bytes carry android_metadata on Android) */
    fun snapshot(fs: FileSystem, label: String, dir: Path, emit: (String, String) -> Unit) {
        if (!fs.exists(dir)) { emit(label, "MISSING"); return }
        val names = fs.list(dir).map { it.name }.filter { !(it.endsWith("-journal") || it.endsWith("-wal") || it.endsWith("-shm")) }.sorted()   // STEP15G-FIXA-2026-11-10: SQLite's side files are the platform's
        emit(label, "n=${names.size};" + names.joinToString(","))
        for (n in names) {
            val p = dir / n
            if (fs.metadataOrNull(p)?.isDirectory == true) { emit("$label|$n", "DIR"); continue }
            if (n.endsWith(".json") || n.endsWith(".txt") || n.endsWith(".part") || n.endsWith(".tmp") || n.endsWith(".TMP")) {
                val t = fs.read(p) { readUtf8() }
                emit("$label|$n", "size=${t.length};sha=" + Cert15d.sha(t) + ";text=" + t)
            } else emit("$label|$n", "file")
        }
    }

    private fun cell(st: androidx.sqlite.SQLiteStatement, i: Int): String = when (st.getColumnType(i)) {
        SQLITE_DATA_NULL -> "N"
        SQLITE_DATA_INTEGER -> "I" + st.getLong(i)
        SQLITE_DATA_FLOAT -> "R" + GtJava.jd(st.getDouble(i))
        SQLITE_DATA_BLOB -> "B" + st.getBlob(i).let { okio.ByteString.of(*it).sha256().hex().take(16) }
        else -> "S" + Cert15d.esc(st.getText(i))
    }

    /** A database the run made: the schema (android_metadata left out), then every table: count, fingerprint, the first [listRows] rows. */
    fun dumpDb(fs: FileSystem, label: String, path: Path, emit: (String, String) -> Unit, listRows: Int) {
        if (!fs.exists(path)) { emit(label, "MISSING"); return }
        val c = gtOpenDatabase(path.toString(), true)
        try {
            val tables = ArrayList<String>()
            val st = c.prepare("SELECT type, name, COALESCE(sql,'') FROM sqlite_master WHERE name NOT LIKE 'android_metadata' AND name NOT LIKE 'sqlite_%' ORDER BY type, name")
            try { while (st.step()) { val t = st.getText(0); val n = st.getText(1); emit("$label|schema|$t|$n", st.getText(2)); if (t == "table") tables.add(n) } } finally { st.close() }
            for (t in tables) {
                val q = c.prepare("SELECT * FROM \"$t\" ORDER BY rowid")
                try {
                    var k = 0; val h = okio.Buffer(); val hs = okio.HashingSink.sha256(okio.blackholeSink())
                    while (q.step()) {
                        k++
                        val row = (0 until q.getColumnCount()).joinToString("|") { cell(q, it) }
                        if (k <= listRows) emit("$label|$t|" + k.toString().padStart(6, '0'), row)
                        h.writeUtf8(row).writeByte('\n'.code)
                        if (h.size > 1_000_000) hs.write(h, h.size)
                    }
                    hs.write(h, h.size); hs.flush()
                    emit("$label|$t|~count", k.toString()); emit("$label|$t|~fp", hs.hash.hex().take(16))
                } finally { q.close() }
            }
        } finally { c.close() }
    }

    /** reference_points (the import's point table): insertion order, the run's ids and times masked. */
    fun dumpRefPoints(conn: SQLiteConnection, emit: (String, String, Boolean) -> Unit) {
        val ex = conn.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='reference_points'")
        val has = try { ex.step() } finally { ex.close() }
        if (!has) { emit("t4|~absent", "true", true); return }
        val st = conn.prepare("SELECT point_id, source_id, source_uid, name, fclass, population, lat, lon, geom_hash, ingested_at FROM reference_points ORDER BY geom_hash")
        try {
            var k = 0; val all = StringBuilder()
            while (st.step()) {
                k++
                val cells = (0 until 10).joinToString("|") { i -> if (st.getColumnType(i) == SQLITE_DATA_NULL) "N" else when (i) {
                    0 -> "ID"; 9 -> Cert15f.mask(st.getText(i)); else -> cell(st, i) } }
                all.append(cells).append('\n')
                emit("t4|" + st.getText(8), cells, true)
            }
            emit("t4|~count", k.toString(), true); emit("t4|~fp", Cert15d.sha(all.toString()), true)
        } finally { st.close() }
    }

}
