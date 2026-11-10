package com.grouptrack.shared.imports

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtGeom
import com.grouptrack.shared.spatial.GtImportDb
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtRx
import com.grouptrack.shared.text.GtTime
import com.grouptrack.shared.track.GtTracks
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path

/**
 * STEP15E-2026-11-09 (2.8 step 15e): THE IMPORT PREPROCESSOR (limited cases, 2.7e rules) + THE IMPORT LOOP -- the post-conversion
 * two-job design (claude/GroupTrack_post_conversion_import_rules_2026-11-08.md: "Import trails" and "Import tracks, routes and waypoints",
 * every file split into single-type objects) EXPANDS this module; it is not rewritten elsewhere. Today's rules, as 2.7e has them:
 *  - [importFiles] = the per-file body of ConvoyTrackImportScreen.doImport's loop (2.7e, ONEIMPORT-2026-11-04): the rider's answer to
 *    "Are these files trails?" decides; YES = every file through the converter (GPX too, every line marked as a trail); NO = KML / KMZ /
 *    GeoJSON converted (lines = tracks), GPX as it is; a converted file is also saved to Download; the trail batch name; the totals.
 *  - [importGpxAllArtifacts] = ConvoyTrackOps.importGpxAllArtifacts (2.7e) MOVED AS IT IS: every <trk> (a marked one, or all with
 *    asTrails, becomes a TRAIL; else a track through the ONE track add, GtTracks.resolveTrackAdd), every <wpt> (a waypoint), every <rte>
 *    (a route, with its notes), the opt-in corridor maps, the source file deleted once anything imported.
 * Same SQL, same order, same arithmetic, same file names, same progress / log lines. What changed, and only this:
 *  - SpatialDbManager.* on the app's handles -> the connections and the ONE writer this object is made with (the front door passes
 *    SpatialDbManager.writer(), as for GtTracks): insertTrail / insertRoute / writeRouteNotes -> [writer]; findRouteIdByHash -> GtRead;
 *    resolveTrackAdd / insertWaypoint -> GtTracks (15d, made here on the SAME connections, writer, env and flight);
 *    SpatialDbManager.parseGpxTrackPoints -> GtTracks.parseGpxTrackPoints (GtRx + GtJavaParse; on Android the same points, 15d).
 *    SpatialDbManager.init(context) is the front door's (the object exists only on an initialised database).
 *  - getSpatialDb()?.execSQL("UPDATE trails SET carto_code ...") -> GtImportDb.setTrailClass (15c: the SAME statement, the same text binds);
 *    getExtensionDb()?.execSQL("INSERT OR IGNORE INTO trail_properties (... agency_id ...)") -> GtImportDb.insertTrailPropsImport (new, the
 *    same text and binds; nothing without the data database, as 2.7e's `?.`).
 *  - ⚠ THE HASH TRAP: 2.7e writes the geometry as "LINESTRING(" + "${lon} ${lat}" joined + ")" -- Kotlin's own Double text, which is Java's
 *    on Android but NOT on the iPad, and the geom_hash is taken over that text. Here -> GtGeom.lineLonLat (GEOMONE: the one serialiser;
 *    Android's bytes exactly, Java's text on the iPad); computeGeomHash(wkt) -> the same Geom's hash.
 *  - org.json: JSONObject(route.description) / JSONObject().put("narrative", JSONObject().put("headline", ...)) / .toString() ->
 *    GtOrgJson.parse (an object, or the wrap) / GtOrgJson.compact (org.json's text: no spaces, "/" escaped) -- NOTES_15e R2 for the
 *    lenient / duplicate-key inputs org.json reads differently.
 *  - java.time.Instant.now().toString() -> env.clock.nowInstant(); UUID.randomUUID() -> env.ids.newId();
 *    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) (the batch date, the device's zone) -> GtTime.local(env.clock.localNow(), ...).
 *  - TrailClassifier.useOf -> env.trailClass.useOf; DownloadQueueManager.enqueueCorridor(context, ...) -> env.platform.enqueueCorridor;
 *    ImportConverter.saveToDownloads(context, file) -> env.platform.saveToDownloads; RiderTrailWriter.trailsChanged.value = now ->
 *    env.platform.onTrailsChanged(); ImportConverter.toGpx -> GtImportConverter.toGpx (15b, the app's since STEP15B-SWITCH).
 *  - ConvoyTrackOps.tracksDir() -> env.paths.tracksDir(); context.cacheDir -> env.paths.cacheDir().
 *  - java.io.File -> okio through env.files.fs (exists, length = 0 when missing, readText -> readUtf8, writeText -> write, delete inside
 *    2.7e's own try); setLastModified / lastModified -> env.files (okio cannot set a file time); File.extension / nameWithoutExtension ->
 *    the name's text after / before the last '.' (Kotlin's definitions).
 *  - Regex.findAll on the file text -> GtRx.findAll (RXFIX: linear on Android; the same matches). No "\s" outside "[\s\S]" (GtRx.WS rule:
 *    none here).
 *  - android.util.Log -> gtLogger(<2.7e tag>): ImportDiag, Import, ConvoyTrackOps.
 *  - suspend + withContext(Dispatchers.IO) -> blocking (no coroutines in shared code, INVENTORY Q4); the front door keeps the IO dispatch.
 *  - Default arguments (downloadMapSlots = emptyList(), replaceExisting = false, onProgress = null) stay in the front door (CODE RULE 1).
 * Not ported (dead, NOTES_15e section 4): ConvoyTrackOps.importTrackFile (no caller since the 2026-06-29 import base fix).
 *
 * @param dataConn grouptrack_data.db -- CODE RULE 1: optional exactly as in 2.7e (getExtensionDb() may be null: the trail_properties row
 *        is skipped and the route notes are not written, as 2.7e's `?.` and writeRouteNotes' `extensionDb == null -> -1`).
 */
class GtGpxImport(private val conn: SQLiteConnection, private val dataConn: SQLiteConnection?,
                  private val writer: GtWrite, private val env: GtImportEnv, flight: GtTracks.Flight) {

    private val logDiag = gtLogger("ImportDiag")
    private val logImport = gtLogger("Import")
    private val logOps = gtLogger("ConvoyTrackOps")
    private val db = GtImportDb(conn, dataConn)
    private val read = GtRead(conn, dataConn)
    private val tracks = GtTracks(conn, dataConn, writer, env, flight)
    private val fs get() = env.files.fs

    /** GTMATH-2026-11-10 (D14): java.io.File.setLastModified's own rule, on every platform -- a time before 1970 (negative) throws
     *  IllegalArgumentException("Negative time"), as on Android (2.7e: such a track fails to import and its file stays behind). Apple's
     *  file call accepted 1582 (cert 15e: "Time zoo" became a track on the iPad only). Behaviour on Android is unchanged. */
    private fun javaSetLastModified(path: Path, epochMs: Long): Boolean {
        if (epochMs < 0) throw IllegalArgumentException("Negative time")
        return env.files.setLastModified(path, epochMs)
    }

    /** ConvoyTrackOps.ImportArtifactsSummary, field for field (the front door maps it back). */
    data class Summary(
        val sourceName: String,
        val trackCount: Int,
        val waypointCount: Int,
        val routeCount: Int,
        val trackFiles: List<String>,
        val errors: List<String>,
        val inserted: Int = 0,   // tracks newly inserted (INSERT)
        val dropped: Int = 0,    // tracks that were exact duplicates (DROP_NAME/DROP_ALIAS)
        val aliased: Int = 0,    // ADDED 2026-06-30: same geometry, new name -> alias recorded
        val skipped: Int = 0     // ADDED 2026-06-30: no geometry / error
    )

    /** What the screen's loop adds up over the files it imported (its recap). */
    data class Totals(
        val imported: List<String>,
        val skipped: List<String>,
        val failed: List<String>,
        val datesCorrected: Int,
        val waypoints: Int,
        val routes: Int,
        val new: Int,
        val dupe: Int,
        val aliased: Int,
        val skippedCount: Int
    )

    private fun exists(p: Path) = fs.exists(p)
    private fun length(p: Path): Long = fs.metadataOrNull(p)?.size ?: 0L
    private fun extension(p: Path) = p.name.substringAfterLast('.', "")       // java.io.File.extension
    private fun nameWithoutExtension(p: Path) = p.name.substringBeforeLast(".")   // java.io.File.nameWithoutExtension

    companion object {
        private val CONVERT_EXT = setOf("kml", "kmz", "geojson", "json")
    }

    // ===================================================================================================================================
    // the screen's per-file loop (ConvoyTrackImportScreen.doImport, 2.7e)
    // ===================================================================================================================================

    /**
     * The screen's loop over the rider's chosen files, as 2.7e runs it inside doImport's coroutine. [onLine] = `importLines = importLines + x`
     * (the progress list the screen shows); [onFile] = `progressCurrent = i + 1; progressName = f.name`.
     * @param trailsAnswer "YES" / "NO" -- the answer to "Are these files trails?" (2.7e: trailsAnswer == "YES")
     * @param trailsSource the batch name the rider typed (used for YES only; blank = the file's name)
     * @param mapsSkip the file names whose maps the rider unticked; [selectedSlots] the map sources chosen (NO only)
     */
    fun importFiles(sel: List<Path>, trailsAnswer: String, trailsSource: String, mapsSkip: Set<String>, selectedSlots: List<String>,
                    replaceExisting: Boolean, onLine: (String) -> Unit, onFile: (Int, String) -> Unit): Totals {
        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var datesCorrected = 0
        var wptTotal = 0
        var rteTotal = 0
        var newTotal = 0
        var dupeTotal = 0
        var aliasedTotal = 0
        var skippedTotal = 0
        for ((i, f) in sel.withIndex()) {
            onFile(i + 1, f.name)
            onLine("— ${f.name} —")
            try {
                // MAPSDEFAULT-2026-09-16: maps unless this file was explicitly excluded.
                // ONEIMPORT-2026-11-04: YES = every file through the converter (GPX too) -> <name>-Trails.gpx with the trail mark;
                // NO = KML / KMZ / GeoJSON converted (lines = tracks), GPX as it is. A converted file is also saved to Download.
                val yes = trailsAnswer == "YES"
                val ext = GtGpxParse.importExt(f.name)
                val conv = if (yes || ext in CONVERT_EXT) run {
                    val od = env.paths.cacheDir() / "import_convert"
                    fs.createDirectories(od)
                    val r = GtImportConverter.toGpx(fs, f, od, yes)
                    val kept = try { "Download/" + env.platform.saveToDownloads(r.file) } catch (e: Exception) { "not saved: ${e.message}" }
                    Pair(r, kept)
                } else null
                conv?.let { (r, kept) -> onLine("CONVERTED ${f.name}: ${r.lines} ${if (yes) "trails" else "tracks"}, ${r.points} waypoints -> $kept") }
                val src = conv?.first?.file ?: f
                // TRAILCLEAN-2026-11-04: the batch every trail of this file is recorded under (YES: the name given; NO: the file name --
                // a GroupTrack trail export answered NO still brings trails)
                val batch = (if (yes) trailsSource.trim().ifEmpty { nameWithoutExtension(f) } else nameWithoutExtension(f)) + " · " +
                    GtTime.local(env.clock.localNow(), "yyyy-MM-dd")
                val summary = importGpxAllArtifacts(src, if (!yes && !mapsSkip.contains(f.name)) selectedSlots else emptyList(), replaceExisting, false, batch) { line ->
                    onLine(line)
                }
                imported.addAll(summary.trackFiles)
                wptTotal += summary.waypointCount
                rteTotal += summary.routeCount
                datesCorrected += summary.trackFiles.size
                newTotal += summary.inserted
                dupeTotal += summary.dropped
                aliasedTotal += summary.aliased
                skippedTotal += summary.skipped
                if (summary.errors.isNotEmpty()) {
                    failed.addAll(summary.errors.map { f.name + ": " + it })
                }
            } catch (e: Exception) {
                failed.add(f.name + ": " + (e.message ?: "unknown error"))
                onLine("ERROR: ${f.name}: ${e.message ?: "unknown"}")
            }
        }
        logImport.i { "RECAP TRIGGER: new=$newTotal dupe=$dupeTotal files=${imported.size}" }
        return Totals(imported, skipped, failed, datesCorrected, wptTotal, rteTotal, newTotal, dupeTotal, aliasedTotal, skippedTotal)
    }

    // ===================================================================================================================================
    // importGpxAllArtifacts (ConvoyTrackOps, 2.7e)
    // ===================================================================================================================================

    /**
     * Import all artifact types from a GPX file:
     *   - Tracks: create GPX files in my_tracks/ + insert into spatial DB
     *   - Waypoints: insert into spatial DB waypoints table
     *   - Routes: insert into spatial DB tracks table (type='ROUTE')
     *
     * Source file is deleted from Downloads after successful import.
     *
     * @param sourceFile GPX file to import (typically from Downloads)
     * @return Summary with counts per type
     * (STEP15E: [onProgress] nullable for 2.7e's reason -- a caller may want no lines; no default here, CODE RULE 1. 2.7e's Context
     *  parameter is gone: it served SpatialDbManager.init (the front door's) and enqueueCorridor (env.platform's).)
     */
    fun importGpxAllArtifacts(
        sourceFile: Path,
        downloadMapSlots: List<String>,
        replaceExisting: Boolean,
        asTrails: Boolean,   // IMPORTTRAILS-2026-10-30: true = every <trk> line goes into trails, not tracks (CODE RULE 1: required)
        trailBatch: String,   // TRAILCLEAN-2026-11-04: "<source name> · <date>" written to every trail's agency_id (CODE RULE 1: required)
        onProgress: ((String) -> Unit)?
    ): Summary {
        val sourceName = sourceFile.name
        val errors = mutableListOf<String>()
        val trackFiles = mutableListOf<String>()
        var waypointCount = 0
        var routeCount = 0
        fun diag(msg: String) { logDiag.i { msg }; onProgress?.invoke(msg) }
        diag("ENTRY name='$sourceName' exists=${exists(sourceFile)} len=${length(sourceFile)} ext='${extension(sourceFile)}'")

        try {
            if (!exists(sourceFile)) {
                return Summary(sourceName, 0, 0, 0, emptyList(), listOf("File not found"))
            }

            // Validate extension before reading (streaming — we never load the whole file)
            if (GtGpxParse.importExt(sourceFile.name) != "gpx") {   // GPXDUPNAME-2026-11-01: "x.gpx (2)" is a GPX
                return Summary(sourceName, 0, 0, 0, emptyList(), listOf("Not a GPX file"))
            }

            // Initialize spatial DB  (STEP15E: SpatialDbManager.init(context) -- the front door's)
            val dir = env.paths.tracksDir()   // created, as ConvoyTrackOps.tracksDir()

            // ── 1. Process Tracks (<trk> elements) — STREAMED 2026-06-02 ──
            // Tier-2 streaming: read the file with a BufferedReader and accumulate ONE
            // <trk>..</trk> block at a time, process it, release it. The whole file is
            // never held in memory (was: readText + whole-file regex + findAll().toList(),
            // which OOM'd on large onX exports). Per-track parse/insert logic is reused.
            val namePattern = Regex("""<name>([^<]*)</name>""")
            val extPattern = Regex("""<extensions>[\s\S]*?</extensions>""")
            var trackIndex = 0
            var insertedCount = 0
            var aliasedCount = 0
            var duplicateCount = 0
            var skippedCount = 0

            // PROVEN regex track-detection (mirrors importTrackFile): match <trk>..</trk>
            // across newlines with findAll. Replaces the line-streamer that found 0 tracks.
            // GPXIMPORT-2026-08-22N3: hoisted OUT of the run block. It was scoped inside,
            // so the waypoint and route parsers below could not see it. Reading the
            // file a SECOND time down there would compile, but the comment above
            // records that the whole-file approach OOM'd on large onX exports --
            // two reads doubles that. One read, both consumers.
            val fullText = fs.read(sourceFile) { readUtf8() }
            var trailsAdded = 0   // ONEIMPORT-2026-11-04: maps reload when a marked trail went in
            run {
                val trkPattern = Regex("""<trk>([\s\S]*?)</trk>""")
                val matchCount = GtRx.findAll(trkPattern, fullText).count()
                diag("READ len=${fullText.length} contains<trk>=${fullText.contains("<trk>")} regexMatches=$matchCount")
                for (m in GtRx.findAll(trkPattern, fullText)) {
                        val rawTrk = m.groupValues[1]
                        val trkContent = extPattern.replace(rawTrk, "")
                        trackIndex++
                        diag("TRACK #$trackIndex trkContentLen=${trkContent.length}")
                        try {
                            val rawName = namePattern.find(trkContent)?.groupValues?.get(1)?.trim()
                                ?: "track_$trackIndex"
                            val baseName = rawName
                                .replace("/", "_").replace("\\", "_")
                                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                                .trim().ifEmpty { "track_$trackIndex" }
                            // IMPORT base fix (2026-06-29): DB-first, hash-named, like save/sync.
                            // Parse geometry FIRST so we can compute the hash that names the file.
                            val coords = GtTracks.parseGpxTrackPoints("<trk>${trkContent}</trk>")
                            if (coords.isEmpty()) {
                                logImport.w { "skip no-geometry trk #$trackIndex ('$baseName')" }
                                continue
                            }
                            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                            for (pair in coords) {
                                val lon = pair.first; val lat = pair.second
                                if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                                if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                            }
                            val geom = GtGeom.lineLonLat(coords)   // STEP15E: 2.7e's "LINESTRING(" + "${lon} ${lat}" joined + ")" (the hash trap)
                            val wkt = geom.wkt
                            // IMPORTTRAILS-2026-10-30 (D31-min, Fred 10-30): the rider ticked "Import tracks as trails" -- this
                            // batch is a trail map. Each line becomes a TRAIL; nothing below (track file, track row, corridor) runs.
                            if (asTrails || GtGpxParse.trailMarked(trkContent, fullText)) {   // ONEIMPORT-2026-11-04: the mark in the file
                                val trailName = namePattern.find(trkContent)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
                                val now = env.clock.nowInstant()
                                val res = writer.insertTrail(env.ids.newId(), trailName, wkt,
                                    minLat, maxLat, minLon, maxLon, now)
                                when (res.second) {
                                    GtWrite.Decision.INSERT -> {
                                        insertedCount++; trailsAdded++   // ONEIMPORT-2026-11-04
                                        try {
                                            db.setTrailClass(res.first, "OHV", "import", env.trailClass.useOf("OHV"), "PUBLIC")
                                        } catch (e: Exception) { logImport.w { "trail carto write: ${e.message}" } }
                                        try {
                                            db.insertTrailPropsImport(res.first, "import", geom.hash, "OHV", "yes", trailBatch, now)   // TRAILCLEAN-2026-11-04: + the batch
                                        } catch (e: Exception) { logImport.w { "trail_properties write: ${e.message}" } }
                                        onProgress?.invoke("TRAIL: ${trailName ?: "(no name)"}")
                                    }
                                    GtWrite.Decision.ALIAS -> {
                                        aliasedCount++; onProgress?.invoke("TRAIL ALIAS: ${trailName ?: "(no name)"}")
                                    }
                                    GtWrite.Decision.DROP -> {
                                        duplicateCount++; onProgress?.invoke("TRAIL DUPLICATE: ${trailName ?: "(no name)"}")
                                    }
                                }
                                continue
                            }
                            // FULLY UNIFORM: write the split track under its HUMAN name (the
                            // same value used for the spatial name). NO hash is computed here --
                            // the resolver owns computeGeomHash, the rename to <hash>.gpx, dedup
                            // (INSERT/DROP/ALIAS), source-file delete, aliasing, and the metric feed.
                            val srcName = "$baseName.gpx"
                            val dest = dir / srcName
                            run {
                                val singleGpx = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                    "<gpx version=\"1.1\" creator=\"GroupTrack\">\n" +
                                    "<trk>${trkContent}</trk>\n</gpx>"
                                fs.write(dest) { writeUtf8(singleGpx) }
                                GtGpxParse.extractEarliestTime(singleGpx)?.let { javaSetLastModified(dest, it) }   // GTMATH-2026-11-10 (D14)
                                    ?: javaSetLastModified(dest, env.files.lastModified(sourceFile))
                            }
                            when (tracks.resolveTrackAdd(baseName, dest)) {
                                GtTracks.AddOutcome.INSERT -> {
                                    insertedCount++; trackFiles.add(srcName)
                                    onProgress?.invoke("INSERT: $baseName")
                                    // [2026-07-01 maps-follow-tracks] queue this new track's map tiles
                                    // (padded bbox -> download queue). INSERT only; already on IO thread.
                                    // OPT-IN: only when the import-maps checkbox was checked.
                                    if (downloadMapSlots.isNotEmpty()) {
                                        try {
                                            val gh = geom.hash
                                            // CORRIDOR-CUTOVER-2026-07-24: CORRIDOR, not bbox. The old
                                            // call was ONE submitDownload covering ALL slots;
                                            // corridor is ONE ENTRY PER SOURCE, so this LOOPS.
                                            // getTrackBbox is gone from here - the corridor
                                            // derives from the geometry, and the bounding box
                                            // was only ever the thing being replaced.
                                            // ⚠ 10 tracks x 3 sources = 30 rows, not 10.
                                            var queued = 0
                                            for (slot in downloadMapSlots) {
                                                queued += env.platform.enqueueCorridor(
                                                    gh, slot, replaceExisting
                                                )
                                            }
                                            if (queued > 0) {
                                                onProgress?.invoke(
                                                    "MAPS: $baseName corridor queued " +
                                                    "(${downloadMapSlots.size} sources, $queued tiles)")
                                            } else {
                                                onProgress?.invoke("MAPS: $baseName no corridor")
                                            }
                                        } catch (e: Exception) {
                                            onProgress?.invoke("MAPS ERR: $baseName ${e.message}")
                                        }
                                    }
                                }
                                GtTracks.AddOutcome.ALIAS -> {
                                    aliasedCount++
                                    onProgress?.invoke("ALIAS: $baseName")
                                }
                                GtTracks.AddOutcome.DROP_NAME,
                                GtTracks.AddOutcome.DROP_ALIAS -> {
                                    duplicateCount++
                                    onProgress?.invoke("DUPLICATE: $baseName")
                                }
                                GtTracks.AddOutcome.NO_GEOMETRY -> {
                                    skippedCount++
                                    onProgress?.invoke("SKIP: $baseName (no geometry)")
                                }
                                GtTracks.AddOutcome.ERROR -> {
                                    skippedCount++; errors.add("Track $trackIndex: resolveTrackAdd error")
                                    onProgress?.invoke("ERROR: $baseName")
                                }
                            }
                        } catch (e: Exception) {
                            errors.add("Track $trackIndex: ${e.message}")
                        }
                }
            }

            // ── 2. Process Waypoints (<wpt> elements) ──
            // GPXIMPORT-2026-08-22N: BYPASS LIFTED. The 2026-06-02 hold said "re-enable when
            // import logic is built + tested" -- the logic below was already complete
            // the whole time; only this call was stubbed. Testers could not import
            // routes because of it.
            // ⚠ The source file is deleted once anything imports (see step 4). Before
            // this, a waypoints-only GPX imported nothing and survived.
            val waypoints = GtGpxParse.parseGpxWaypoints(fullText)
            for (wpt in waypoints) {
                try {
                    tracks.insertWaypoint(wpt.name, wpt.lat, wpt.lon, wpt.type)
                    waypointCount++
                } catch (e: Exception) {
                    errors.add("Waypoint ${wpt.name}: ${e.message}")
                }
            }

            // ── 3. Process Routes (<rte> elements) ──
            // GPXIMPORT-2026-08-22N: BYPASS LIFTED. Coordinate order verified end to end
            // before enabling: the parser stores Pair(lon, lat), the data class
            // documents it, the loop below destructures (lon, lat), and the WKT emits
            // lon lat. No transposition.
            // ⛔ insertRoute writes a PERMANENT spatial-DB row -- an imported route
            // skips draft-then-graduate and is permanent immediately. Permanent routes
            // are the only unrecoverable class on this project.
            val routes = GtGpxParse.parseGpxRoutes(fullText)
            for (route in routes) {
                try {
                    val pts = route.points
                    var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                    var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                    for ((lon, lat) in pts) {
                        if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                        if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                    }
                    val geom = GtGeom.lineLonLat(pts)   // STEP15E: the hash trap (see the header)
                    val wkt = geom.wkt
                    writer.insertRoute(route.name, wkt, minLat, maxLat, minLon, maxLon)
                    /* ROUTENOTESIMPORT-2026-09-08: AND ITS NARRATIVE.
                     *
                     * ⛔ parseGpxRoutes has read <desc> into route.notes since
                     * 08-29 and NOTHING STORED IT, so every imported route
                     * arrived with an empty narrative -- proven on a real
                     * device-to-device transfer today.
                     *
                     * ⭐ writeRouteNotes is the SAME function draft promotion
                     * uses. One writer; narrative and recipe cannot drift apart.
                     *
                     * ⚠ THE ID IS RESOLVED BY HASH, not taken from insertRoute.
                     * That call is INSERT OR IGNORE and hands back the id it
                     * minted, which on a duplicate refers to no row at all.
                     * Resolving by hash also does the right thing on a
                     * re-import: the notes attach to the route that exists.
                     */
                    if (route.description.isNotBlank()) {
                        try {
                            val rgh = geom.hash
                            val rid = read.findRouteIdByHash(rgh)
                            if (rid != null) {
                                /* ROUTENOTESSHAPE-2026-09-08: WHICH IS IT?
                                 *
                                 * ⛔ parseGpxRoutes returns EITHER our
                                 * <grouptrack:notes> payload OR another app's
                                 * plain <desc>, in the same field -- it prefers
                                 * ours where present. Wrapping unconditionally
                                 * put the entire JSON document into the
                                 * headline, which is the gibberish Fred saw the
                                 * moment the export side started working.
                                 *
                                 * ⭐ A real parse, not a startsWith("{"): prose
                                 * that happens to begin with a brace would be
                                 * accepted and stored as a notes object with no
                                 * narrative in it -- an empty panel rather than
                                 * an error, which is harder to notice.
                                 */
                                val payload: JsonObject = try {
                                    GtOrgJson.parse(route.description) as? JsonObject
                                        ?: throw IllegalArgumentException("not a JSON object")   // org.json: JSONObject(text) throws
                                } catch (_: Exception) {
                                    JsonObject(mapOf("narrative" to JsonObject(mapOf("headline" to JsonPrimitive(route.description)))))
                                }
                                // SpatialDbManager.writeRouteNotes(rid, payload): extensionDb == null -> -1; else the writer, with org.json's text
                                if (dataConn != null) writer.writeRouteNotes(rid, GtOrgJson.compact(payload))
                                logOps.i { "route '${route.name}': narrative stored" }
                            } else {
                                logOps.w { "route '${route.name}': no row for hash, narrative dropped" }
                            }
                        } catch (e: Exception) {
                            // ⚠ A narrative that fails to store must not fail the
                            // import. The route itself is already in.
                            logOps.w { "route '${route.name}': narrative: ${e.message}" }
                        }
                    }
                    // ROUTEMAPS-2026-09-24 (Fred): routes get their corridor maps too -- the SAME call the track
                    // path above and the route detail panel use. The importer only knew tracks because route
                    // corridors came later (ROUTECORR-2026-08-10C). Opt-in, exactly as for tracks.
                    if (downloadMapSlots.isNotEmpty()) {
                        try {
                            val gh = geom.hash
                            var queued = 0
                            for (slot in downloadMapSlots) {
                                queued += env.platform.enqueueCorridor(gh, slot, replaceExisting)
                            }
                            onProgress?.invoke(
                                if (queued > 0) "MAPS: route ${route.name} corridor queued " +
                                    "(${downloadMapSlots.size} sources, $queued tiles)"
                                else "MAPS: route ${route.name} no corridor")
                            logOps.i { "ROUTEMAPS-2026-09-24: route '${route.name}' queued $queued tiles" }
                        } catch (e: Exception) {
                            // A map failure must not fail the import -- the route is already in.
                            logOps.w { "route '${route.name}': maps: ${e.message}" }
                        }
                    }
                    routeCount++
                } catch (e: Exception) {
                    errors.add("Route ${route.name}: ${e.message}")
                }
            }

            // ── 4. Delete source file after successful import ──
            val totalImported = trackFiles.size + waypointCount + routeCount
            if (totalImported > 0) {
                try {
                    fs.delete(sourceFile)
                    logImport.i { "Deleted source file: $sourceName" }
                } catch (e: Exception) {
                    logImport.w { "Could not delete source: ${e.message}" }
                }
            }

            logImport.i { "Import complete: ${trackFiles.size} tracks, $waypointCount waypoints, $routeCount routes from $sourceName" }
            if (trailsAdded > 0) env.platform.onTrailsChanged()   // IMPORTTRAILS-2026-10-30: maps reload
            return Summary(sourceName, trackFiles.size, waypointCount, routeCount, trackFiles, errors, insertedCount, duplicateCount, aliasedCount, skippedCount)

        } catch (e: Exception) {
            logImport.e { "Import failed for $sourceName: ${e.message}" }
            return Summary(sourceName, 0, 0, 0, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }
}
