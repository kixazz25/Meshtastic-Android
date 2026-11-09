package com.grouptrack.shared.rider

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtGeom
import com.grouptrack.shared.spatial.GtImportDb
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtFmt
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtJavaParse
import com.grouptrack.shared.text.GtRx   // STEP15C-FIXWS-2026-11-09

/**
 * STEP15C-2026-11-09 (2.8 step 15c): 2.7e's RiderTrailWriter (app/.../convoy/RiderTrailWriter.kt) MOVED AS IT IS into shared code --
 * the model only. What changed, and only this:
 *  - THE WEBVIEW PART STAYS IN THE APP: `trailsChanged` (the StateFlow the maps collect) and `takeReload(WebView, stamp)`. Here every
 *    2.7e `trailsChanged.value = System.currentTimeMillis()` is `env.platform.onTrailsChanged()` (GtImportAndroid sets the app's flow).
 *  - SpatialDbManager.getSpatialDb() / getExtensionDb() -> the connections this object is made with ([conn], [dataConn]); the
 *    "no spatial database -> return the empty result" checks move to the Android front door, which builds this object only when
 *    SpatialDbManager has a spatial handle (as SpatialDbManager.writer() does). The handles cannot change during one call (CERT MODE
 *    guards the jobs that could, GtCertGuard), so checking once per call is what 2.7e's per-call checks did.
 *  - SpatialDbManager.insertTrail / beginDedupSession / endDedupSession / deleteTrailFromDb -> the same functions on [writer], which must
 *    be the app's ONE shared writer for these handles (SpatialDbManager.writer() -- its dedup session is the running import's memory).
 *  - the SQL -> GtImportDb (same text, same binds). computeGeomHash -> GtGeom.hash (the one hash; SpatialDbManager.computeGeomHash is
 *    GtRead.computeGeomHash = GtGeom.hash since GEOMONE). buildGeometry -> GtGeom.lineLonLat (GEOMONE: the one serialiser -- the same
 *    text 2.7e's `"${it.lon} ${it.lat}"` gives on Android, and Java's text on the iPad too).
 *  - Instant.now().toString() -> env.clock.nowInstant(); UUID.randomUUID() -> env.ids.newId(); TrailClassifier.useOf -> env.trailClass.
 *  - String.toDoubleOrNull (stored text) -> GtJavaParse.toDoubleOrNull (15b: Java's grammar on the iPad too).
 *  - `(x).toString()` of a Double bound as SQL text -> GtJava.jd(x) (Java's Double.toString on both platforms).
 *  - android.util.Log -> gtLogger("RiderTrails") (the 2.7e tag); `"%.2f".format(x)` (log only, default locale) -> GtFmt.f(x, 2).
 *    ⚠ ONE KNOWN DIFFERENCE, found 11-09 (NOTES_15c.md D1): 2.7e's scanTrack log line is
 *      "${trackName ?: "(unnamed)"}: $n trail(s), %.2f mi".format(miles) -- the track NAME is part of the FORMAT, so a name holding
 *    "%" (e.g. "Grade 50% climb") makes String.format THROW after the trails are written (measured with Java 21: FormatFlagsConversion-
 *    MismatchException; "100%" -> UnknownFormatConversionException). Here the name is not a format, so nothing throws. Owner question
 *    Q1; the cert labels its test case EXPECTED.
 *
 * ---- 2.7e's header, unchanged ----
 *
 * RIDERTRAILWRITER-2026-09-07 -- turns recorded tracks into trails.
 *
 * The ONLY part of rider trails that touches the database. RiderTrailScanner
 * decides WHAT ground is new; this decides what becomes of it. Keeping the two
 * apart is what lets the scanner be diffed against the Python prototype.
 *
 * -- WHEN THIS RUNS (Fred, 09-07) --------------------------------------
 *
 * TWO CALLERS, ONE FUNCTION:
 *   1. On track add, from any source -- [scanTrack] for that one track.
 *   2. After trails are cleared and RELOADED -- [scanAll], one pass over every
 *      known track, repopulating what the clear removed.
 *
 * Nothing else runs it. There is NO scanned flag and none is needed: it never
 * needs re-running unless trails were cleared and reloaded, so there is no
 * state to keep. Do not add a column for this.
 *
 * IT MUST RUN BEFORE CLASSIFICATION, NOT AFTER. Step 8 (OwnershipReclass)
 * assigns land_status and use_type by reading the trails table. Rider trails
 * written after it would carry neither.
 *
 * AND IT MUST RUN AFTER THE RELOAD, NEVER AT THE CLEAR. StartupHousekeeping and
 * clearTrailsOnce only CLEAR -- they hand the reload to the import. Scanning
 * against an emptied trails table reads every track as entirely off-network and
 * promotes all of it, several hundred miles of duplicate ground, silently.
 *
 * -- WHAT MAKES IT SAFE TO RUN TWICE ------------------------------------
 *
 * NOT the geometry hash. Two GPS traces over the same ground are never
 * byte-identical -- the same reason OSM and UGRC turned out to be independent
 * surveys with 9 hash matches in 89,554.
 *
 * RIDERRULES-2026-10-09: IT IS THE REBUILD. scanAll clears every rider trail first, so a second run lays the same
 * set again. Within one run, each track's candidates come from the database, so a piece written for an earlier
 * track is network for the later ones -- two recordings of one ride do not both lay it. (Was: the direction test.)
 *
 * A second layer sits behind it: source_unique_id is the geometry hash, so the
 * INSERT OR IGNORE on (source_id, source_unique_id) drops an exact repeat.
 *   (STEP15C, measured 11-09 on the Mac copy live_2026-11-08 and GtSchema: trail_properties has NO unique constraint on
 *   (source_id, source_unique_id) -- only PRIMARY KEY(trail_id) and the plain index idx_tp_source. The OR IGNORE therefore fires only
 *   on a repeated trail_id, which writeOne never sends (a new id per INSERT). Harmless -- trails.geom_hash is UNIQUE and insertTrail
 *   decides first -- but this paragraph is not true. Kept as 2.7e has it; NOTES_15c.md D3.)
 *
 * -- THREADING ---------------------------------------------------------
 *
 * Caller's job. On track add this must not block the save; from the import it
 * is already off the main thread. (STEP15C: every function here is blocking -- no coroutines in shared code, INVENTORY Q4.)
 *
 * @param dataConn grouptrack_data.db -- CODE RULE 1: optional exactly as in 2.7e (SpatialDbManager.getExtensionDb() may be null; the
 *        trail_properties row is then not written, as 2.7e's `eDb?.execSQL`).
 */
class GtRiderTrails(private val conn: SQLiteConnection, private val dataConn: SQLiteConnection?,
                    private val writer: GtWrite, private val env: GtImportEnv) {

    private val log = gtLogger(TAG)
    private val db = GtImportDb(conn, dataConn)

    /** RIDERIDS-2026-10-09: a run RECONCILES -- [trailsAdded]/[miles] are the GENUINELY NEW pieces (true adds for the
     *  day); [trailsUnchanged] kept their IDs; [trailsRemoved] are no longer produced; [trailsTotal]/[milesTotal] = what
     *  exists after the run. */
    data class Result(
        val tracksScanned: Int,
        val trailsAdded: Int,
        val miles: Double,
        val tracksRemoved: Int,
        val trailsTotal: Int,
        val trailsRemoved: Int,
        val trailsUnchanged: Int,
        val milesTotal: Double
    )

    companion object {
        private const val TAG = "RiderTrails"

        /** Our own category. Recognised by TrailClassifier so step 8 preserves it. */
        const val CATEGORY = "rider"

        /** trail_properties.source_id. Distinguishes rider ground from every
         *  published source, and is what a provenance filter would key on. */
        const val SOURCE_ID = "rides"

        /**
         * Candidate trails are selected by the track's own bounding box, grown by
         * this much (~110 m) so a trail just outside it can still anchor a point
         * near the edge.
         */
        private const val BBOX_MARGIN_DEG = 0.001

        // -- geometry text ---------------------------------------------------

        /**
         * Stored geometry -> points. Handles LINESTRING and MULTILINESTRING, the
         * latter flattened, which is what the scan wants: one continuous ride.
         *
         * RouteManager has an equivalent parser. This one is local because the
         * writer must not depend on the route layer, but if that changes they
         * should be reconciled rather than left to drift.
         * (STEP15C: [wkt] is String? because it comes straight from a geometry column that may be NULL -- 2.7e's signature.
         *  ⚠ The flattening joins the LAST point of one part to the FIRST of the next with a segment that is not on the ground --
         *  measured 11-09, NOTES_15c.md D2. Kept as 2.7e has it.)
         */
        fun parseGeometry(wkt: String?): List<GtRiderScan.Pt> {
            if (wkt.isNullOrBlank()) return emptyList()
            val open = wkt.indexOf('(')
            if (open < 0) return emptyList()
            val body = wkt.substring(open).replace("(", " ").replace(")", " ")
            val out = ArrayList<GtRiderScan.Pt>()
            for (part in body.split(',')) {
                val f = part.trim().split(WS)
                if (f.size < 2) continue
                val lon = GtJavaParse.toDoubleOrNull(f[0]) ?: continue
                val lat = GtJavaParse.toDoubleOrNull(f[1]) ?: continue
                out.add(GtRiderScan.Pt(lon, lat))
            }
            return out
        }
        private val WS = Regex(GtRx.WS + "+")   // STEP15C-FIXWS-2026-11-09: 2.7e's Regex("\\s+") as ANDROID reads it (GtRx.WS); one instance

        /**
         * Points -> stored geometry, in the app's EXACT format: lon then lat,
         * separated by one space, pairs separated by a comma with NO space.
         *
         * THE FORMAT IS THE CONTRACT. The identity hash is taken over this string,
         * so a stray space produces a different identity for identical ground and
         * duplicate detection stops recognising its own rows -- silently. Matched
         * to ConvoyTrackOps, which is where the app already builds this.
         * (STEP15C/GEOMONE: the format now lives in ONE place, GtGeom; the text and its hash come back together.)
         */
        private fun buildGeometry(pts: List<GtRiderScan.Pt>): GtGeom.Geom =
            GtGeom.lineLonLat(pts.map { Pair(it.lon, it.lat) })
    }

    // -- the scan --------------------------------------------------------

    /**
     * Candidate trails near this track. THE WHOLE TABLE MUST NEVER BE READ:
     * Utah is 145,942 trails and about 5.6 million segments, which on a 3 GB
     * device is not a slow index, it is an allocation that does not come back.
     * One track covers a small area, so its bounding box is the query.
     */
    // RIDERIDS-2026-10-09: excludeRider = true for the reconcile pass -- the ride is matched against the REAL trails only
    // (plus this run's own pieces, added by the caller); false for a single track added on its own (additive, as before).
    private fun candidatesFor(pts: List<GtRiderScan.Pt>, excludeRider: Boolean): List<List<GtRiderScan.Pt>> {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in pts) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        val out = ArrayList<List<GtRiderScan.Pt>>()
        try {
            db.trailCandidates(
                GtJava.jd(minLat - BBOX_MARGIN_DEG), GtJava.jd(maxLat + BBOX_MARGIN_DEG),
                GtJava.jd(minLon - BBOX_MARGIN_DEG), GtJava.jd(maxLon + BBOX_MARGIN_DEG),
                excludeRider, CATEGORY
            ) { g ->
                val q = parseGeometry(g)
                if (q.size >= 2) out.add(q)
            }
        } catch (e: Exception) {
            log.e { "candidate query failed: ${e.message}" }
        }
        return out
    }

    /**
     * Scan one track and write whatever new ground it found.
     *
     * The caller opens the dedup session. [scanAll] does it once for the whole
     * pass rather than per track, which is why it is not done here.
     *
     * @return trails written, and their total miles.
     */
    /**
     * Scan ONE track, by its geometry hash -- the track key.
     *
     * For the add-a-track trigger, which has the hash in hand and no geometry
     * string. Looks the row up and hands off to [scanTrack] below: one scan
     * implementation, two ways in.
     *
     * ⛔ THIS ONE OPENS THE DEDUP SESSION, and that is the difference between
     * the two. scanAll opens ONE session for its whole pass, so the delegate
     * below must not open one -- doing that per track inside the loop would
     * reload every hash on every track. But a single track added on its own has
     * no session around it, and without one resolveByGeom reads an empty map
     * and every derived trail looks new.
     *   (STEP15C note: superseded by DUPECHECK-2026-10-20 below -- no session is opened here; resolveByGeom then does one indexed
     *   lookup per trail (GtWrite.nameByHash). Kept as 2.7e has it.)
     *
     * ⚠ DATABASE WORK, and it runs inline on the caller's thread.
     *
     * @return trails written, and their total miles.
     */
    fun scanTrackByHash(geomHash: String): Pair<Int, Double> {
        var foundName: String? = null
        var foundGeom: String? = null
        try {
            db.trackNameGeometry(geomHash)?.let { (n, g) ->
                foundName = n
                foundGeom = g
            }
        } catch (e: Exception) {
            log.e { "track read failed for ${geomHash.take(12)}: ${e.message}" }
            return Pair(0, 0.0)
        }
        val geom = foundGeom
        if (geom.isNullOrBlank()) {
            // Not an error. A row can exist before its geometry does.
            log.i { "no geometry for ${geomHash.take(12)} -- nothing to scan" }
            return Pair(0, 0.0)
        }
        // DUPECHECK-2026-10-20 (Fred 10-20): NO bulk session for one track -- resolveByGeom does one indexed lookup per derived trail
        // (was beginDedupSession(): every hash in the database loaded for a handful of trails -- 3.8 s per save).
        val r = scanTrack(foundName, geom)
        if (r.first > 0) env.platform.onTrailsChanged()   // RIDERIDS-2026-10-09: the maps reload
        return r
    }

    /** (STEP15C: [trackName] is String? because tracks.name may be NULL -- logged as "(unnamed)"; [geometry] as [parseGeometry].) */
    fun scanTrack(trackName: String?, geometry: String?): Pair<Int, Double> {
        val pts = parseGeometry(geometry)
        if (pts.size < 2) return Pair(0, 0.0)
        val network = candidatesFor(pts, excludeRider = false)
        val found = GtRiderScan.scan(pts, network)
        if (found.isEmpty()) return Pair(0, 0.0)

        var n = 0
        var miles = 0.0
        for (t in found) {
            if (writeOne(t)) { n++; miles += t.totalM / 1609.34 }
        }
        // STEP15C: 2.7e = "${trackName ?: "(unnamed)"}: $n trail(s), %.2f mi".format(miles) -- see the header (D1): no throw here
        if (n > 0) log.i { "${trackName ?: "(unnamed)"}: $n trail(s), " + GtFmt.f(miles, 2) + " mi" }
        return Pair(n, miles)
    }

    /**
     * One rider trail into both stores.
     *
     * BOTH STORES, DELIBERATELY. SpatialDbManager arbitrates between them
     * ("spatial wins only if it has a real value"), so a trail written to one
     * and not the other disagrees with itself. TrailImporter writes carto to
     * the spatial row on create for the same reason.
     *
     * carto_code_source is set as well as carto_code. Step 8 reads the SOURCE
     * value in preference, so leaving it empty would send 'rider' through
     * categoryOf as a bare carto value; setting both means the row says the
     * same thing whichever field is read.
     *
     * land_status and use_type are deliberately NOT set. Step 8 derives them --
     * ownership from the polygon test, use from the category map. Hand-setting
     * them here would be a second path that drifts from the classifier.
     *   (STEP15C note: superseded by RIDEROUT-2026-10-24 below, which DOES set both. Kept as 2.7e has it.)
     */
    private fun writeOne(t: GtRiderScan.RiderTrail): Boolean {
        val g = buildGeometry(t.points)
        val wkt = g.wkt

        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in t.points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }

        val now = env.clock.nowInstant()
        val newId = env.ids.newId()
        val hash = g.hash

        // Unnamed on purpose. Fred: "it doesn't have to be perfect, it has to
        // be honest" -- it says you rode it, not that it is a road.
        val res = try {
            writer.insertTrail(newId, null, wkt, minLat, maxLat, minLon, maxLon, now)
        } catch (e: Exception) {
            log.e { "insertTrail failed: ${e.message}" }
            return false
        }
        val trailId = res.first
        if (res.second != GtWrite.Decision.INSERT) return false

        try {
            // RIDEROUT-2026-10-24 (Fred 15:19): written COMPLETE -- motorized (a UTV drove it), and with no land classification
            // a trail from tracks is PUBLIC. Nothing waits for step 8 or the launch catch-up job.
            db.setTrailClass(trailId, CATEGORY, CATEGORY, env.trailClass.useOf(CATEGORY), "PUBLIC")
        } catch (e: Exception) { log.w { "spatial carto write: ${e.message}" } }

        // source_unique_id is the geometry hash: stable across re-runs, unique
        // per geometry, so the INSERT OR IGNORE on (source_id, source_unique_id)
        // drops an exact repeat without needing a lookup first.   (STEP15C: not so -- see the header, D3.)
        try {
            db.insertTrailPropsRider(trailId, SOURCE_ID, hash, CATEGORY, "yes", now)
        } catch (e: Exception) { log.w { "trail_properties write: ${e.message}" } }

        return true
    }

    /**
     * Every track, in one pass -- RIDERIDS-2026-10-09 (Fred): RECONCILE, NEVER CLEAR. The release g/h clear-and-rebuild gave
     * every rider trail a NEW random ID on every run, so anything holding a rider trail's ID (a saved route snapped to it,
     * a selection) broke on every run, and "added" meant "re-laid". Now:
     *   1. each track is matched against the REAL trails only (rider trails excluded from the candidates), plus the pieces
     *      THIS RUN has already produced -- so two recordings of one ride do not both lay it (what the DB did before);
     *   2. each piece is compared with the rider trails already stored, BY GEOMETRY HASH:
     *        same piece already stored -> UNCHANGED, untouched, same ID, same created_at;
     *        new piece                 -> ADDED (today's date -- "added" is a true add for the day);
     *   3. stored rider trails this run no longer produces -> REMOVED.
     * On an unchanged database a second run reports 0 added, 0 removed. The dedup session is opened ONCE for the pass.
     *
     * @param onProgress optional; null where there is no UI to report to (the import stage has a manifest instead).
     *   (CODE RULE 1: nullable for 2.7e's reason just given -- a caller with no UI. No default: the front door passes its own.)
     */
    fun scanAll(onProgress: ((Int, Int) -> Unit)?): Result {
        val tracksRemoved = 0   // RIDEROUT-2026-10-24: the 09-07 "Unnamed n" prototype cleanup is gone (job done; it could delete a real GPX track)

        // the rider trails stored now: geometry hash -> trail_id (hash from the stored geometry when the column is empty)
        val stored = HashMap<String, String>()
        try {
            db.riderStored(CATEGORY) { id, gh, geom ->
                if (id == null) return@riderStored
                val h = if (gh != null) gh
                        else if (geom != null) GtGeom.hash(geom) else return@riderStored
                stored[h] = id
            }
        } catch (e: Exception) {
            log.e { "RIDERIDS: rider trail read failed: ${e.message}" }
            return Result(0, 0, 0.0, tracksRemoved, 0, 0, 0, 0.0)
        }

        val ids = ArrayList<Pair<String, String?>>()
        try {
            db.tracksForRider { name, geom ->
                if (geom != null) ids.add(Pair(geom, name))
            }
        } catch (e: Exception) {
            log.e { "track read failed: ${e.message}" }
            return Result(0, 0, 0.0, tracksRemoved, 0, 0, 0, 0.0)
        }

        writer.beginDedupSession()
        try {   // STEP15A-2026-11-08 (owner decision 6): the session is ended on every way out -- 2.7e never ended it, so
                //   clearAllTrailData refused ("an import is running") for the rest of the session after a scan
        val produced = HashSet<String>()
        val runPieces = ArrayList<List<GtRiderScan.Pt>>()   // this run's pieces -- network for the tracks after
        var added = 0; var unchanged = 0
        var addedMi = 0.0; var totalMi = 0.0
        for (i in ids.indices) {
            onProgress?.invoke(i, ids.size)
            val (geom, name) = ids[i]
            val pts = parseGeometry(geom)
            if (pts.size < 2) continue
            val network = candidatesFor(pts, excludeRider = true) + piecesNear(runPieces, pts)
            var n = 0
            for (t in GtRiderScan.scan(pts, network)) {
                val hash = buildGeometry(t.points).hash
                if (!produced.add(hash)) continue          // the same piece twice in one run: once is enough
                runPieces.add(t.points)
                val mi = t.totalM / 1609.34
                if (stored.containsKey(hash)) { unchanged++; totalMi += mi; n++ }
                else if (writeOne(t)) { added++; addedMi += mi; totalMi += mi; n++ }
            }
            if (n > 0) log.i { "${name ?: "(unnamed)"}: $n rider trail(s)" }
        }

        var removed = 0
        // STEP15C: HashMap order -- it differs between Android and the iPad; it decides only the ORDER of the deletes (no row differs).
        for ((hash, id) in stored) {
            if (hash in produced) continue
            try { writer.deleteTrailFromDb(id); removed++ } catch (e: Exception) { log.w { "delete $id: ${e.message}" } }
        }
        onProgress?.invoke(ids.size, ids.size)
        if (added + removed > 0) env.platform.onTrailsChanged()   // the maps reload -- only on a change

        log.i { "RIDERIDS scanAll: ${ids.size} track(s) -> ${added + unchanged} rider trail(s): $added added (" + GtFmt.f(addedMi, 2) + " mi), " +
            "$unchanged unchanged, $removed removed; total " + GtFmt.f(totalMi, 2) + " mi; $tracksRemoved unnamed track(s) removed" }
        return Result(ids.size, added, addedMi, tracksRemoved, added + unchanged, removed, unchanged, totalMi)
        } finally {
            writer.endDedupSession()   // STEP15A-2026-11-08
        }
    }

    /** RIDERIDS-2026-10-09: this run's pieces whose box touches the track's box (+ the candidate margin). */
    private fun piecesNear(pieces: List<List<GtRiderScan.Pt>>, pts: List<GtRiderScan.Pt>): List<List<GtRiderScan.Pt>> {
        if (pieces.isEmpty()) return emptyList()
        val la1 = pts.minOf { it.lat } - BBOX_MARGIN_DEG; val la2 = pts.maxOf { it.lat } + BBOX_MARGIN_DEG
        val lo1 = pts.minOf { it.lon } - BBOX_MARGIN_DEG; val lo2 = pts.maxOf { it.lon } + BBOX_MARGIN_DEG
        return pieces.filter { p ->
            p.maxOf { it.lat } >= la1 && p.minOf { it.lat } <= la2 && p.maxOf { it.lon } >= lo1 && p.minOf { it.lon } <= lo2
        }
    }
}
