package com.grouptrack.shared.track

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.imports.GtGpxParse
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.rider.GtRiderTrails
import com.grouptrack.shared.spatial.GtGeom
import com.grouptrack.shared.spatial.GtImportDb
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtFmt
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtJavaParse
import com.grouptrack.shared.text.GtRx
import com.grouptrack.shared.text.GtTime
import okio.IOException
import okio.Path
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * STEP15D-2026-11-09 (2.8 step 15d): 2.7e's TRACK ADD / SYNC / PROPERTIES (app/.../convoy/SpatialDbManager.kt) MOVED AS IT IS into shared
 * code: resolveTrackAdd, syncTracksFromFiles ("Resync tracks with their files"), updateTrackPropertiesForHash + its haversineMiles,
 * backfillAllTrackProperties, insertWaypoint, allTrackGeomHashes, and the GPX-FILE part of deleteTrackFromDb (its rows are GtWrite's since
 * STEP11). Same SQL (GtImportDb, text unchanged), same order, same arithmetic, same file names, same log lines. What changed, and only this:
 *  - SpatialDbManager.spatialDb / extensionDb -> the connections this object is made with ([conn], [dataConn]). The "no spatial database
 *    -> return the empty result" checks move to the Android front door (as 15c), which builds this object only when SpatialDbManager has
 *    a spatial handle. The extension checks INSIDE the functions stay (dataConn is optional, as 2.7e's extensionDb).
 *  - SpatialDbManager.insertTrackToDb / addAlias / deleteTrackFromDb (rows) -> the same functions on [writer], which must be the app's
 *    ONE shared writer for these handles (SpatialDbManager.writer()), as for the rider code.
 *  - RiderTrailWriter.scanTrackByHash -> the shared GtRiderTrails (15c), made here on the SAME connections and writer.
 *  - the two AtomicBoolean single-flight guards (syncRunning, propertiesBackfillRunning) -> [Flight], which the front door gives (Android:
 *    the same two AtomicBooleans, process-wide). Checked at the SAME points as 2.7e -- so a backfill already running still makes the sync's
 *    own backfill skip with "⚠ properties pass already running — skipped", exactly as in 2.7e.
 *  - java.io.File -> okio through env.files.fs: exists, readText -> read { readUtf8() }, length() -> metadata size (0 when missing, as
 *    File.length), renameTo -> atomicMove wrapped to a Boolean (false where it throws, as renameTo returns false), copyTo(overwrite=false)
 *    -> [copyNoOverwrite] (the same checks, in Kotlin's order), delete -> fs.delete (inside 2.7e's own try), writeText -> fs.write.
 *    File.listFiles() -> env.files.list (Android: listFiles' own UNSORTED order -- INVENTORY T8, it decides which of two same-geometry files
 *    becomes the track; the iPad: sorted, owner decision 11). absolutePath equality -> Path text equality (see [samePath]).
 *  - ConvoyTrackOps.tracksDir() -> env.paths.tracksDir(); GroupTrackStorage.root() -> env.paths.groupTrackRoot().
 *  - the WKT "LINESTRING(" + "${lon} ${lat}" joined + ")" -> GtGeom.lineLonLat (GEOMONE: the one serialiser; Android's text exactly, and
 *    Java's text on the iPad); computeGeomHash -> GtGeom.hash. pointWkt(lat, lng) -> GtGeom.pointLonLat(lng, lat).
 *  - parseGpxTrackPoints -> [parseGpxTrackPoints] here: GtRead's regex, but every match through GtRx.findAll and every number through
 *    GtJavaParse.toDoubleOrNull (owner rule 2). On Android the same matches and the same numbers (RXFIX proof; Kotlin's JVM toDoubleOrNull
 *    IS Java's grammar). GtRead.parseGpxTrackPoints is NOT changed here (it is certified and still used by trackIdForGpxText) -- NOTES Q3.
 *  - parseGpxTrackPointsFull -> GtGpxParse's (already the app's since STEP15B-SWITCH).
 *  - Math.toRadians -> GtJava.toRadians (Android's constant); Math.sin/cos/asin/sqrt/min -> kotlin.math (on Android = java.lang.Math).
 *    Math.round(x).toInt() -> GtJava.roundToIntJava (NaN -> 0; +Infinity -> Long.MAX -> -1 as Long.toInt, as Java).
 *  - SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", UTC).format -> GtTime.formatUtcSeconds (15a, certified).
 *  - UUID.randomUUID() / Instant.now() (insertWaypoint) -> env.ids.newId() / env.clock.nowInstant().
 *  - android.util.Log -> gtLogger(<2.7e tag>): TrackSync, HASHTRACE, TrackAdd, TrackProps, SpatialDb, TrackDelete, SpatialDbMgr.
 *    "%.2f"/"%.1f".format (DEFAULT locale) -> GtFmt.f (Locale.US). ⚠ The props line goes into track_sync.log: on a Droid whose locale writes
 *    a decimal comma the 2.7e text differs (NOTES R4). A Double in a LOG line ("$lat", Pair.toString) -> GtJava.jd text (log only).
 *  - Default arguments (`onProgress = null`, `type = "other"`) stay in the front door; here every parameter is passed (CODE RULE 1).
 * Not ported (dead, NOTES section 4): trackHashExists (no caller).
 * Every function is blocking (no coroutines in shared code, INVENTORY Q4). THREADING is the caller's, as 2.7e.
 *
 * @param dataConn grouptrack_data.db -- CODE RULE 1: optional exactly as in 2.7e (SpatialDbManager.getExtensionDb() may be null:
 *        updateTrackPropertiesForHash then returns false, the alias check finds nothing, as 2.7e's `extensionDb ?:` / `extensionDb?.`).
 */
class GtTracks(private val conn: SQLiteConnection, private val dataConn: SQLiteConnection?,
               private val writer: GtWrite, private val env: GtImportEnv, private val flight: Flight) {

    /** 2.7e's two single-flight guards (SpatialDbManager.syncRunning / propertiesBackfillRunning: AtomicBoolean compareAndSet(false, true)
     *  to begin, set(false) to end). Process-wide on Android -- the front door passes the SAME object to every GtTracks it makes. */
    interface Flight {
        fun beginSync(): Boolean
        fun endSync()
        fun beginBackfill(): Boolean
        fun endBackfill()
    }

    private val logSync = gtLogger("TrackSync")
    private val logTrace = gtLogger("HASHTRACE")
    private val logAdd = gtLogger("TrackAdd")
    private val logProps = gtLogger("TrackProps")
    private val logWp = gtLogger("SpatialDb")
    private val logDel = gtLogger("TrackDelete")
    private val logMgr = gtLogger("SpatialDbMgr")   // SpatialDbManager's TAG (allTrackGeomHashes)
    private val db = GtImportDb(conn, dataConn)
    private val rider = GtRiderTrails(conn, dataConn, writer, env)
    private val fs get() = env.files.fs

    /** Sync tracks from GPX files in my_tracks directory */
    /**
     * Result of a sync pass. Cross-foots: processed == skipped + addedRenamed + renamed.
     * Geometry-less files are not tracks and are excluded from processed (logged only).
     */
    data class TrackSyncResult(
        val processed: Int,
        val skipped: Int,
        val addedRenamed: Int,
        val renamed: Int,
        val addedNames: List<String>,
        val propsWritten: Int = 0,
        val propsTotal: Int = 0,
        val failures: List<String> = emptyList()   // "name · hash12 — reason", one per failed track
    )

    /** Result of backfillAllTrackProperties: counts + identifiable failures. */
    data class BackfillResult(
        val written: Int,
        val total: Int,
        val failures: List<String>
    )

    enum class AddOutcome { INSERT, DROP_NAME, DROP_ALIAS, ALIAS, NO_GEOMETRY, ERROR }

    companion object {
        private val TRKPT = Regex("""<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"""")
        private val NAME = Regex("<name>([^<]*)</name>")   // 2.7e made it per file; one instance here -- same pattern, same match

        /** Parse GPX file for track points, returns list of (lon, lat) pairs -- GtRead.parseGpxTrackPoints' pattern (STEP7) through GtRx and
         *  GtJavaParse (see the header). */
        fun parseGpxTrackPoints(gpxText: String): List<Pair<Double, Double>> {
            val coords = mutableListOf<Pair<Double, Double>>()
            for (m in GtRx.findAll(TRKPT, gpxText)) {
                val lat = GtJavaParse.toDoubleOrNull(m.groupValues[1]) ?: continue
                val lon = GtJavaParse.toDoubleOrNull(m.groupValues[2]) ?: continue
                coords.add(Pair(lon, lat))
            }
            return coords
        }

        /** (STEP15D: 2.7e's private haversineMiles -- R = 3958.7613, Double. NOT GtRead's (3958.8, Float): INVENTORY T4.) */
        fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 3958.7613
            val dLat = GtJava.toRadians(lat2 - lat1)
            val dLon = GtJava.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(GtJava.toRadians(lat1)) * cos(GtJava.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            return 2 * R * asin(min(1.0, sqrt(a)))
        }

        /** "(lon, lat)" as Kotlin's Pair.toString wrote it in 2.7e's HASHTRACE line, the numbers as Java's text (log only). */
        private fun pairText(p: Pair<Double, Double>?): String = if (p == null) "null" else "(" + GtJava.jd(p.first) + ", " + GtJava.jd(p.second) + ")"
    }

    /** STEP15D: java.io.File.absolutePath equality. Both paths here are made by joining a name onto a directory from GtImportPaths (or come
     *  from env.files.list of that directory), so their text is what absolutePath compared. ⚠ A RELATIVE path from a caller would differ:
     *  absolutePath resolved it against the working directory; no 2.7e caller passes one (ConvoyViewModel, ConvoyTrackOps: my_tracks files). */
    private fun samePath(a: Path, b: Path): Boolean = a.toString() == b.toString()

    /** java.io.File.renameTo: true when moved, false otherwise -- never throws (okio's atomicMove throws; 2.7e's renameTo returned false). */
    private fun renameTo(from: Path, to: Path): Boolean = try { fs.atomicMove(from, to); true } catch (e: Exception) { false }

    /** Kotlin's File.copyTo(target, overwrite = false), in its order: the source must exist (NoSuchFileException), the target must not
     *  (FileAlreadyExistsException); a directory source makes the target directory; else the parents are made and the bytes copied. */
    private fun copyNoOverwrite(from: Path, to: Path) {
        if (!fs.exists(from)) throw IOException("$from: The source file doesn't exist.")
        if (fs.exists(to)) throw IOException("$to: The destination file already exists.")
        if (fs.metadataOrNull(from)?.isDirectory == true) { fs.createDirectories(to); return }
        to.parent?.let { fs.createDirectories(it) }
        fs.copy(from, to)
    }

    private fun readText(p: Path): String = fs.read(p) { readUtf8() }
    private fun length(p: Path): Long = fs.metadataOrNull(p)?.size ?: 0L

    // ===================================================================================================================================
    // syncTracksFromFiles
    // ===================================================================================================================================

    /**
     * Reconcile my_tracks files into the spatial DB (hash-keyed spatial model).
     * Per valid track file (one with geometry):
     *   - if the filename is already an existing hash key  -> SKIPPED (already current)
     *   - else present to insertTrackToDb (DB enforces UNIQUE(geom_hash)):
     *        true  (new record)            -> ADDED/RENAMED  (insert + rename to <hash>.gpx)
     *        false (hash already in DB)     -> RENAMED        (normalize file to <hash>.gpx)
     * A file with no geometry is not a track: logged and excluded from processed.
     * (STEP15D: [onProgress] is nullable for 2.7e's reason -- a caller with no UI (the launch auto-resync); no default here, CODE RULE 1.
     *  2.7e's unused Context parameter is dropped: it was never read.)
     */
    fun syncTracksFromFiles(onProgress: ((String) -> Unit)?): TrackSyncResult {
        if (!flight.beginSync()) {
            logSync.w { "sync already running -- skipping concurrent call" }
            onProgress?.invoke("⚠ sync already running — this request was ignored")
            return TrackSyncResult(0, 0, 0, 0, emptyList())
        }
        var processed = 0
        var skipped = 0
        var addedRenamed = 0
        var renamed = 0
        val addedNames = mutableListOf<String>()
        var syncPropsWritten = 0
        var syncPropsTotal = 0
        var syncFailures: List<String> = emptyList()
        try {
        try {
            val tracksDir = env.paths.tracksDir()   // MYTRACKSDB-2026-09-12
            val dirMeta = fs.metadataOrNull(tracksDir)
            if (dirMeta == null || !dirMeta.isDirectory) {
                logSync.i { "my_tracks directory not found" }
                return TrackSyncResult(0, 0, 0, 0, emptyList())
            }
            val allFiles = env.files.list(tracksDir)
            // Exclude Android trash (.trashed-<ts>-<name>.gpx, left IN-PLACE on delete) + dotfiles.
            val trashed = allFiles.count { it.name.startsWith(".trashed-") || it.name.startsWith(".") }
            val gpxFiles = allFiles.filter { f ->
                f.name.lowercase().endsWith(".gpx")
                    && !f.name.startsWith(".trashed-")
                    && !f.name.startsWith(".")
            }
            if (trashed > 0) {
                logSync.i { "ignored $trashed trashed/hidden files" }
                onProgress?.invoke("— ignored $trashed trashed/hidden files —")
            }
            logSync.i { "Found ${gpxFiles.size} GPX files to sync" }

            // Per-file decision log to disk (logcat unreliable here). Overwrite each run.
            // LASTPATHS-2026-09-12
            val logFile = env.paths.groupTrackRoot() / "track_sync.log"
            val log = StringBuilder()
            fun L(msg: String) { log.append(msg).append("\n"); logSync.i { msg }; onProgress?.invoke(msg) }
            L("=== sync start: ${gpxFiles.size} gpx files ===")

            for (file in gpxFiles) {
                try {
                    val text = readText(file)
                    val coords = parseGpxTrackPoints(text)
                    if (coords.isEmpty()) {
                        L("IGNORE no-geometry: ${file.name} (${length(file)} bytes, 0 pts)")
                        continue
                    }
                    var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                    var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                    for ((lon, lat) in coords) {
                        if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                        if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                    }
                    val gh = GtGeom.lineLonLat(coords).hash   // THE KEY (computed from geometry, not the filename)

                    processed++

                    val nameMatch = NAME.find(text)?.groupValues?.get(1)?.trim()
                    val recName = if (!nameMatch.isNullOrBlank()) nameMatch else file.name.substringBeforeLast(".")   // File.nameWithoutExtension
                    logTrace.i { "LOOP file=${file.name} loopPts=${coords.size} loopHash=${gh.take(16)} embName='${nameMatch ?: "?"}' recName='$recName'" }
                    // UNIFIED ADD: resolver rereads `file`, inserts, and resolves
                    // INSERT / DROP_NAME / DROP_ALIAS / ALIAS (owns rename + source delete + metrics + alias).
                    when (resolveTrackAdd(recName, file)) {
                        AddOutcome.INSERT -> { addedRenamed++; addedNames.add(recName)
                            L("ADDED: ${file.name} pts=${coords.size} hash=${gh.take(12)} name='$recName' -> INSERT") }
                        AddOutcome.ALIAS -> { renamed++
                            L("ALIAS: ${file.name} hash=${gh.take(12)} name='$recName' -> alias added, source removed") }
                        AddOutcome.DROP_NAME, AddOutcome.DROP_ALIAS -> { renamed++
                            L("DUPE: ${file.name} hash=${gh.take(12)} -> already known, source removed") }
                        AddOutcome.NO_GEOMETRY -> { L("SKIP: ${file.name} -> no geometry") }
                        AddOutcome.ERROR -> { L("ERROR: ${file.name} -> resolveTrackAdd failed") }
                    }
                } catch (e: Exception) {
                    L("ERROR: ${file.name} : ${e.message}")   // (STEP15D: e.message is the platform's text -- okio's on the iPad)
                }
            }

            // metric feed: one guarded, SEQUENTIAL sweep -- writes track_properties for
            // every track (synced + backfills any existing tracks that never had them).
            val bf = backfillAllTrackProperties { line -> L(line) }
            syncPropsWritten = bf.written; syncPropsTotal = bf.total; syncFailures = bf.failures
            L("=== properties: ${bf.written}/${bf.total} written, ${bf.failures.size} failed ===")
            L("=== sync complete: processed=$processed skipped=$skipped added/renamed=$addedRenamed renamed=$renamed ===")
            try { logFile.parent?.let { fs.createDirectories(it) }; fs.write(logFile) { writeUtf8(log.toString()) } } catch (e: Exception) { logSync.e { "log write failed: ${e.message}" } }
        } catch (e: Exception) {
            logSync.e { "Sync failed: ${e.message}" }
        }
        } finally {
            flight.endSync()
        }
        return TrackSyncResult(processed, skipped, addedRenamed, renamed, addedNames,
            syncPropsWritten, syncPropsTotal, syncFailures)
    }

    // ===================================================================================================================================
    // insertWaypoint (SpatialDbManager's INSERT METHODS for GPX import)
    // ===================================================================================================================================

    /**
     * Insert a waypoint into the spatial DB.
     * Returns the generated waypoint_id.
     * (STEP15D: moved with 15d per INVENTORY 3.3; [type] has no default here -- the front door keeps 2.7e's `= "other"`.)
     */
    fun insertWaypoint(name: String, lat: Double, lon: Double, type: String): String {
        val id = env.ids.newId()
        val ts = env.clock.nowInstant()
        val g = GtGeom.pointLonLat(lon, lat)   // 2.7e pointWkt(lat, lon) = "POINT($lon $lat)"
        val geometry = g.wkt
        val nm = GtRead.notNamed(name)
        val gh = g.hash
        try {
            db.insertWaypoint(id, nm, geometry, type.lowercase(), lat, lon, ts, gh)
            logWp.i { "Inserted waypoint: $nm ($type) at ${GtJava.jd(lat)},${GtJava.jd(lon)}" }
        } catch (e: Exception) {
            logWp.e { "Waypoint insert failed: ${e.message}" }
            // If column mismatch, try minimal insert
            try {
                db.insertWaypointMinimal(id, nm, geometry, type.lowercase(), ts, gh)
                // Update bbox columns separately (may have been added by migration)
                db.setWaypointBbox(id, lat, lon)
            } catch (e2: Exception) {
                logWp.e { "Waypoint fallback insert also failed: ${e2.message}" }
            }
        }
        return id
    }

    // ===================================================================================================================================
    // resolveTrackAdd
    // ===================================================================================================================================

    /** THE unified track ADD service. All three capture processes (sync, create,
     *  import) call this with a name and a file that ALREADY EXISTS on disk.
     *  Rereads the file (single source of truth), parses, inserts, and resolves:
     *    INSERT     new hash      -> materialize <hash>.gpx, write metrics, keep file
     *    DROP_NAME  name==official-> delete source file
     *    DROP_ALIAS name==alias   -> delete source file
     *    ALIAS      new name      -> addAlias(existingId,name) + delete source file
     *  Identical behavior for all callers; no caller-side insert/rename/delete.
     *  (STEP15D ⚠ 2.7e kept on purpose (INVENTORY Q6 c): the official name and the alias are matched on name.trim(), but the INSERT stores
     *   the UNTRIMMED name -- a name with outer spaces inserts as given and a later add of the trimmed name becomes an ALIAS.) */
    fun resolveTrackAdd(name: String, sourceFile: Path): AddOutcome {
        try {
            if (!fs.exists(sourceFile)) {
                logAdd.w { "source missing: ${sourceFile.name}" }
                return AddOutcome.ERROR
            }
            val gpxText = readText(sourceFile)
            val coords = parseGpxTrackPoints(gpxText)   // List<Pair<lon,lat>>
            if (coords.isEmpty()) {
                logAdd.w { "no geometry: ${sourceFile.name}" }
                return AddOutcome.NO_GEOMETRY
            }
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            for (p in coords) {
                val lon = p.first; val lat = p.second
                if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
            }
            val g = GtGeom.lineLonLat(coords)
            val wkt = g.wkt
            val _embName = NAME.find(gpxText)?.groupValues?.get(1)?.trim() ?: "?"
            logTrace.i { "RESOLVE file=${sourceFile.name} passedName='$name' embeddedName='$_embName' pts=${coords.size} first=(${pairText(coords.firstOrNull())}) last=(${pairText(coords.lastOrNull())}) wktLen=${wkt.length} hash=${g.hash.take(16)}" }
            val res = writer.insertTrackToDb(name, wkt, minLat, maxLat, minLon, maxLon)
            // MYTRACKSDB-2026-09-12: ⚠ inner replaced, outer kept -- it is
            // what joins the directory to the filename.
            val hashFile = env.paths.tracksDir() / "${res.geomHash}.gpx"

            if (res.wasNew) {
                // INSERT: make the canonical <hash>.gpx exist, then write metrics.
                try {
                    if (!samePath(sourceFile, hashFile)) {
                        if (!fs.exists(hashFile)) {
                            if (!renameTo(sourceFile, hashFile)) copyNoOverwrite(sourceFile, hashFile)
                        }
                    }
                } catch (e: Exception) { logAdd.w { "materialize: ${e.message}" } }
                updateTrackPropertiesForHash(res.geomHash, null)
                // RIDERTRAILS-2026-09-07 -- THE TRIGGER.
                // Every track add runs the trail scan, from here, because this
                // is where recording, file sync and import all converge. One
                // hook; no caller has to remember it and no path can skip it.
                //
                // Inside wasNew deliberately: a duplicate re-add is the same
                // geometry over ground already scanned.
                //
                // It can never fail the add. A track that saved but derived no
                // trails is a far smaller loss than a track that did not save.
                try {
                    val rt = rider.scanTrackByHash(res.geomHash)
                    if (rt.first > 0) logAdd.i { "rider trails: ${rt.first} from '$name'" }
                } catch (e: Exception) {
                    logAdd.w { "rider trails: ${e.message}" }
                }
                logAdd.i { "INSERT ${res.geomHash.take(12)} '$name'" }
                return AddOutcome.INSERT
            }

            // hash exists -> DROP_NAME / DROP_ALIAS / ALIAS. All delete the source file.
            val officialName = db.trackOfficialName(res.trackId)
            val nameMatchesOfficial = officialName != null && officialName.equals(name.trim(), ignoreCase = false)
            var nameMatchesAlias = false
            if (!nameMatchesOfficial) {
                nameMatchesAlias = db.trackAliasExists(res.trackId, name.trim())
            }

            val deleteSource = {
                try {
                    if (!samePath(sourceFile, hashFile) && fs.exists(sourceFile)) fs.delete(sourceFile)
                } catch (e: Exception) { logAdd.w { "del source: ${e.message}" } }
            }

            return when {
                nameMatchesOfficial -> { deleteSource(); logAdd.i { "DROP_NAME ${res.geomHash.take(12)}" }; AddOutcome.DROP_NAME }
                nameMatchesAlias    -> { deleteSource(); logAdd.i { "DROP_ALIAS ${res.geomHash.take(12)}" }; AddOutcome.DROP_ALIAS }
                else -> {
                    writer.addAlias("track", res.trackId, name.trim(), res.geomHash, null)
                    deleteSource()
                    logAdd.i { "ALIAS ${res.geomHash.take(12)} '$name'" }
                    AddOutcome.ALIAS
                }
            }
        } catch (e: Exception) {
            logAdd.e { "resolveTrackAdd failed: ${e.message}" }
            return AddOutcome.ERROR
        }
    }

    // ===================================================================
    // TRACK PROPERTIES UPDATER  (metric feed -- GPX-derived, 2026-06-29)
    // The SINGLE, REUSABLE home for GPX-read + metric-calculation rules that
    // populate track_properties. Keyed by geom_hash ONLY (no caller state), so
    // save / import / resync all call updateTrackPropertiesForHash(hash).
    // insertTrackToDb returns Boolean (not the id); geom_hash is UNIQUE so it
    // resolves to exactly one spatial row. NO DB trigger, NO schema change.
    //
    // [PHASE C SEAM] survey/user-data feed: when the save-fix introduces local
    //   survey capture, refresh track_surveys for this track_id HERE (same
    //   program, second feed). Absent-source = no-op, never overwrite-with-null.
    // [PHASE 3 SEAM] AWS routing of survey data stays in ConvoyEnrollmentQueue/
    //   submitSurvey -- deferred, do not wire here.
    // ===================================================================

    /** Derive + upsert track_properties for the track with this geom_hash.
     *  Reads <hash>.gpx from my_tracks. Writes only GPX-derived columns; never
     *  touches shared/ride_id/area_id. Returns true on a successful write.
     *  (STEP15D: [onProgress] nullable for 2.7e's reason -- resolveTrackAdd calls it with none; no default here, CODE RULE 1.) */
    fun updateTrackPropertiesForHash(geomHash: String, onProgress: ((String) -> Unit)?): Boolean {
        val h12 = geomHash.take(12)
        if (dataConn == null) return false   // 2.7e: val edb = extensionDb ?: return false

        var trackId: String? = null
        var trackName = "?"
        try {
            db.trackIdNameByHash(geomHash)?.let { (id, nm) -> trackId = id; trackName = nm ?: "?" }
        } catch (e: Exception) {
            logProps.e { "track_id lookup failed for $h12: ${e.message}" }
            onProgress?.invoke("FAIL: ? · $h12 — DB lookup error")
            return false
        }
        val tid = trackId ?: run {
            logProps.w { "no spatial row for hash $h12" }
            onProgress?.invoke("FAIL: ? · $h12 — no DB row")
            return false
        }

        val tracksDir = env.paths.tracksDir()   // MYTRACKSDB-2026-09-12
        val gpxFile = tracksDir / "$geomHash.gpx"
        if (!fs.exists(gpxFile)) {
            logProps.w { "file missing: ${gpxFile.name} (track_id=$tid)" }
            onProgress?.invoke("FAIL: $trackName · $h12 — file missing")
            return false
        }

        val pts = try { GtGpxParse.parseGpxTrackPointsFull(readText(gpxFile)) } catch (e: Exception) {
            logProps.e { "parse failed ${gpxFile.name}: ${e.message}" }
            onProgress?.invoke("FAIL: $trackName · $h12 — parse failed"); return false
        }
        if (pts.isEmpty()) {
            logProps.w { "no points in ${gpxFile.name}" }; return false
        }

        var distMiles = 0.0
        var maxMph = 0.0
        var eleGainFt = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val seg = haversineMiles(a.lat, a.lon, b.lat, b.lon)
            distMiles += seg
            val at = a.timeMs; val bt = b.timeMs
            if (at != null && bt != null) {
                val dtHr = (bt - at) / 3_600_000.0
                if (dtHr > 0) { val mph = seg / dtHr; if (mph > maxMph && mph < 200) maxMph = mph }
            }
            val ae = a.eleM; val be = b.eleM
            if (ae != null && be != null) {
                val d = be - ae; if (d > 0) eleGainFt += d * 3.280839895
            }
        }
        val firstT = pts.firstOrNull { it.timeMs != null }?.timeMs
        val lastT = pts.lastOrNull { it.timeMs != null }?.timeMs
        val durMin: Int? = if (firstT != null && lastT != null && lastT > firstT)
            ((lastT - firstT) / 60_000L).toInt() else null
        val avgMph: Double? = if (durMin != null && durMin > 0) distMiles / (durMin / 60.0) else null
        val recordedAt: String? = firstT?.let { GtTime.formatUtcSeconds(it) }

        return try {
            db.upsertTrackProps(tid, "$geomHash.gpx", "gpx", recordedAt,
                distMiles, durMin, maxMph, avgMph,
                GtJava.roundToIntJava(eleGainFt), pts.size)
            logProps.i { "props track_id=$tid dist=${GtFmt.f(distMiles, 2)}mi dur=${durMin}min avg=${avgMph?.let { GtFmt.f(it, 1) }} pts=${pts.size}" }
            onProgress?.invoke("props: $trackName · $h12 — ${GtFmt.f(distMiles, 1)}mi ${pts.size}pts")
            true
        } catch (e: Exception) {
            logProps.e { "upsert failed track_id=$tid: ${e.message}" }
            false
        }
        // [PHASE C SEAM] refresh track_surveys for $tid here once local survey
        // capture exists (save-fix to follow). [PHASE 3 SEAM] AWS upload deferred.
    }

    /** SEQUENTIAL, single-flight backfill of track_properties for every track.
     *  One GPX read at a time -- deliberately NOT parallel (avoids the sync storm
     *  pattern). Idempotent: metrics are a pure function of the immutable GPX.
     *  (STEP15D: [onProgress] nullable for 2.7e's reason; no default here, CODE RULE 1. ⚠ The track read is NOT caught, as in 2.7e: an
     *   error there leaves through the finally, into the sync's "Sync failed" catch.) */
    fun backfillAllTrackProperties(onProgress: ((String) -> Unit)?): BackfillResult {
        if (!flight.beginBackfill()) {
            logProps.w { "backfill already running -- skipping concurrent call" }
            onProgress?.invoke("⚠ properties pass already running — skipped")
            return BackfillResult(0, 0, emptyList())
        }
        try {
            val hashes = mutableListOf<String>()
            db.trackHashes { h -> h?.let { hashes.add(it) } }
            onProgress?.invoke("— properties pass: ${hashes.size} tracks —")
            var written = 0
            val failures = mutableListOf<String>()
            for (h in hashes) {
                var failLine: String? = null
                val ok = updateTrackPropertiesForHash(h) { line ->
                    onProgress?.invoke(line)
                    if (line.startsWith("FAIL:")) failLine = line.removePrefix("FAIL:").trim()
                }
                if (ok) written++ else failLine?.let { failures.add(it) }
            }
            logProps.i { "backfill complete: $written/${hashes.size} written, ${failures.size} failed" }
            onProgress?.invoke("— properties done: $written/${hashes.size} written, ${failures.size} failed —")
            return BackfillResult(written, hashes.size, failures)
        } finally {
            flight.endBackfill()
        }
    }

    // ===================================================================================================================================
    // allTrackGeomHashes, deleteTrackFromDb (the file part)
    // ===================================================================================================================================

    /**
     * Same row source as the backfill query -- tracks WHERE geom_hash IS NOT
     * NULL -- but returns name alongside so a caller iterating all tracks does
     * not need a second read per row. Name is nullable because the column is.
     *
     * Returned as a list of (geom_hash, name) pairs rather than a map: names
     * are not unique, and a map keyed on name would silently collapse tracks.
     */
    fun allTrackGeomHashes(): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        try {
            db.trackHashNames { gh, name ->
                if (gh != null) out.add(gh to name)   // 2.7e: c.getString(0) ?: continue
            }
        } catch (e: Exception) {
            logMgr.e { "CORRDELETE-STAGE1-2026-08-07B allTrackGeomHashes failed: ${e.message}" }
        }
        return out
    }

    /** STEP11: the rows (track, track_properties, aliases) by the shared code; the GPX file here.
     *  (STEP15D: the file part moved from SpatialDbManager, okio through the env -- INVENTORY 3.3 "deleteTrackFromDb".) */
    fun deleteTrackFromDb(id: String) {
        val geomHash = writer.deleteTrackFromDb(id)
        val h = geomHash
        if (h != null && h.isNotBlank()) {
            try {
                val tracksDir = env.paths.tracksDir()   // MYTRACKSDB-2026-09-12
                val f = tracksDir / "$h.gpx"
                if (fs.exists(f)) {
                    val ok = try { fs.delete(f, mustExist = true); true } catch (e: IOException) { false }   // File.delete(): false, never throws
                    logDel.i { "file $h.gpx deleted=$ok" }
                } else {
                    logDel.i { "file $h.gpx not present (no-op)" }
                }
            } catch (e: Exception) { logDel.w { "file: ${e.message}" } }
        } else {
            logDel.i { "no geom_hash for $id -- no file to remove" }
        }
        logDel.i { "deleteTrackFromDb($id) complete" }
    }
}
