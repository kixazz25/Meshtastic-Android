package com.geeksville.mesh.convoy

import android.util.Log

/**
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
 * It is the DIRECTION TEST. A rider trail written on the first pass now lies
 * under the track, going the same way, within NEAR_M -- so the second pass
 * reads that ground as on-network and produces nothing. The dedup happens in
 * the scanner, before anything reaches the database.
 *
 * A second layer sits behind it: source_unique_id is the geometry hash, so the
 * INSERT OR IGNORE on (source_id, source_unique_id) drops an exact repeat.
 *
 * -- THREADING ---------------------------------------------------------
 *
 * Caller's job. On track add this must not block the save; from the import it
 * is already off the main thread.
 */
object RiderTrailWriter {

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

    /**
     * The prototype's output, which reached the tracks table through the GPX
     * import -- the wrong door. Fred, 09-07: the app does not produce unnamed
     * tracks, so this can only be that.
     */
    private val UNNAMED = Regex("^\\s*Unnamed\\s+\\d+\\s*$", RegexOption.IGNORE_CASE)

    data class Result(
        val tracksScanned: Int,
        val trailsAdded: Int,
        val miles: Double,
        val tracksRemoved: Int
    )

    // -- geometry text ---------------------------------------------------

    /**
     * Stored geometry -> points. Handles LINESTRING and MULTILINESTRING, the
     * latter flattened, which is what the scan wants: one continuous ride.
     *
     * RouteManager has an equivalent parser. This one is local because the
     * writer must not depend on the route layer, but if that changes they
     * should be reconciled rather than left to drift.
     */
    fun parseGeometry(wkt: String?): List<RiderTrailScanner.Pt> {
        if (wkt.isNullOrBlank()) return emptyList()
        val open = wkt.indexOf('(')
        if (open < 0) return emptyList()
        val body = wkt.substring(open).replace("(", " ").replace(")", " ")
        val out = ArrayList<RiderTrailScanner.Pt>()
        for (part in body.split(',')) {
            val f = part.trim().split(Regex("\\s+"))
            if (f.size < 2) continue
            val lon = f[0].toDoubleOrNull() ?: continue
            val lat = f[1].toDoubleOrNull() ?: continue
            out.add(RiderTrailScanner.Pt(lon, lat))
        }
        return out
    }

    /**
     * Points -> stored geometry, in the app's EXACT format: lon then lat,
     * separated by one space, pairs separated by a comma with NO space.
     *
     * THE FORMAT IS THE CONTRACT. The identity hash is taken over this string,
     * so a stray space produces a different identity for identical ground and
     * duplicate detection stops recognising its own rows -- silently. Matched
     * to ConvoyTrackOps, which is where the app already builds this.
     */
    private fun buildGeometry(pts: List<RiderTrailScanner.Pt>): String =
        "LINESTRING(" + pts.joinToString(",") { "${it.lon} ${it.lat}" } + ")"

    // -- the prototype cleanup -------------------------------------------

    /**
     * Removes tracks left behind by the Python prototype's GPX import.
     *
     * Goes through the app's own delete, which takes the spatial row, the
     * track_properties row, the aliases AND the canonical .gpx file in
     * my_tracks. A hand-written SQL delete would orphan the file and the
     * extension row -- the same defect the trail clear had before 08-24L.
     *
     * @return how many tracks were removed.
     */
    fun removeUnnamedTracks(): Int {
        val db = SpatialDbManager.getSpatialDb() ?: return 0
        val doomed = ArrayList<String>()
        try {
            db.rawQuery("SELECT track_id, name FROM tracks", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = if (c.isNull(1)) "" else c.getString(1)
                    if (UNNAMED.matches(name)) doomed.add(id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "unnamed sweep read failed: ${e.message}")
            return 0
        }
        var n = 0
        for (id in doomed) {
            try { SpatialDbManager.deleteTrackFromDb(id); n++ }
            catch (e: Exception) { Log.w(TAG, "delete $id: ${e.message}") }
        }
        Log.i(TAG, "removed $n unnamed track(s)")
        return n
    }

    // -- the scan --------------------------------------------------------

    /**
     * Candidate trails near this track. THE WHOLE TABLE MUST NEVER BE READ:
     * Utah is 145,942 trails and about 5.6 million segments, which on a 3 GB
     * device is not a slow index, it is an allocation that does not come back.
     * One track covers a small area, so its bounding box is the query.
     */
    private fun candidatesFor(pts: List<RiderTrailScanner.Pt>): List<List<RiderTrailScanner.Pt>> {
        val db = SpatialDbManager.getSpatialDb() ?: return emptyList()
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in pts) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        val out = ArrayList<List<RiderTrailScanner.Pt>>()
        try {
            db.rawQuery(
                "SELECT geometry FROM trails WHERE min_lat IS NOT NULL " +
                    "AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?",
                arrayOf(
                    (minLat - BBOX_MARGIN_DEG).toString(), (maxLat + BBOX_MARGIN_DEG).toString(),
                    (minLon - BBOX_MARGIN_DEG).toString(), (maxLon + BBOX_MARGIN_DEG).toString()
                )
            ).use { c ->
                while (c.moveToNext()) {
                    val g = if (c.isNull(0)) null else c.getString(0)
                    val q = parseGeometry(g)
                    if (q.size >= 2) out.add(q)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "candidate query failed: ${e.message}")
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
     *
     * ⚠ DATABASE WORK, and it runs inline on the caller's thread.
     *
     * @return trails written, and their total miles.
     */
    fun scanTrackByHash(geomHash: String): Pair<Int, Double> {
        val db = SpatialDbManager.getSpatialDb() ?: return Pair(0, 0.0)
        var foundName: String? = null
        var foundGeom: String? = null
        try {
            db.rawQuery(
                "SELECT name, geometry FROM tracks WHERE geom_hash=? LIMIT 1",
                arrayOf(geomHash)
            ).use { c ->
                if (c.moveToFirst()) {
                    foundName = if (c.isNull(0)) null else c.getString(0)
                    foundGeom = if (c.isNull(1)) null else c.getString(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "track read failed for ${geomHash.take(12)}: ${e.message}")
            return Pair(0, 0.0)
        }
        val geom = foundGeom
        if (geom.isNullOrBlank()) {
            // Not an error. A row can exist before its geometry does.
            Log.i(TAG, "no geometry for ${geomHash.take(12)} -- nothing to scan")
            return Pair(0, 0.0)
        }
        SpatialDbManager.beginDedupSession()
        return scanTrack(foundName, geom)
    }

    fun scanTrack(trackName: String?, geometry: String?): Pair<Int, Double> {
        val pts = parseGeometry(geometry)
        if (pts.size < 2) return Pair(0, 0.0)
        val network = candidatesFor(pts)
        val found = RiderTrailScanner.scan(pts, network)
        if (found.isEmpty()) return Pair(0, 0.0)

        var n = 0
        var miles = 0.0
        for (t in found) {
            if (writeOne(t)) { n++; miles += t.totalM / 1609.34 }
        }
        if (n > 0) Log.i(TAG, "${trackName ?: "(unnamed)"}: $n trail(s), %.2f mi".format(miles))
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
     */
    private fun writeOne(t: RiderTrailScanner.RiderTrail): Boolean {
        val sDb = SpatialDbManager.getSpatialDb() ?: return false
        val eDb = SpatialDbManager.getExtensionDb()
        val wkt = buildGeometry(t.points)

        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in t.points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }

        val now = java.time.Instant.now().toString()
        val newId = java.util.UUID.randomUUID().toString()
        val hash = SpatialDbManager.computeGeomHash(wkt)

        // Unnamed on purpose. Fred: "it doesn't have to be perfect, it has to
        // be honest" -- it says you rode it, not that it is a road.
        val res = try {
            SpatialDbManager.insertTrail(newId, null, wkt, minLat, maxLat, minLon, maxLon, now)
        } catch (e: Exception) {
            Log.e(TAG, "insertTrail failed: ${e.message}")
            return false
        }
        val trailId = res.first
        if (res.second != SpatialDbManager.AddDecision.INSERT) return false

        try {
            sDb.execSQL(
                "UPDATE trails SET carto_code=?, carto_code_source=? WHERE trail_id=?",
                arrayOf<Any?>(CATEGORY, CATEGORY, trailId)
            )
        } catch (e: Exception) { Log.w(TAG, "spatial carto write: ${e.message}") }

        // source_unique_id is the geometry hash: stable across re-runs, unique
        // per geometry, so the INSERT OR IGNORE on (source_id, source_unique_id)
        // drops an exact repeat without needing a lookup first.
        try {
            eDb?.execSQL(
                "INSERT OR IGNORE INTO trail_properties " +
                    "(trail_id,source_id,source_unique_id,carto_code,motorized_allowed,ingested_at) " +
                    "VALUES (?,?,?,?,?,?)",
                arrayOf<Any?>(trailId, SOURCE_ID, hash, CATEGORY, "yes", now)
            )
        } catch (e: Exception) { Log.w(TAG, "trail_properties write: ${e.message}") }

        return true
    }

    /**
     * Every track, in one pass. Under a hundred tracks, so no batching.
     *
     * The dedup session is opened ONCE for the whole pass. Without it
     * resolveByGeom reads an empty map and every trail looks new.
     *
     * TRACKS ARE READ ONE AT A TIME AND CANDIDATES QUERIED PER TRACK, against
     * LIVE data. That is what makes the pass self-deduplicating: two riders
     * over the same unmapped ground, and the first track's new trail is already
     * present when the second is scanned, so it reads as covered. Index once up
     * front and the same ground is written twice, with different hashes, and
     * nothing catches it.
     *
     * @param onProgress optional; null where there is no UI to report to, which
     *                   is a real case -- the import stage has a manifest
     *                   instead. OwnershipReclass takes the same shape.
     */
    fun scanAll(onProgress: ((Int, Int) -> Unit)? = null): Result {
        val removed = removeUnnamedTracks()

        val db = SpatialDbManager.getSpatialDb()
            ?: return Result(0, 0, 0.0, removed)

        val ids = ArrayList<Pair<String, String?>>()
        try {
            db.rawQuery("SELECT name, geometry FROM tracks WHERE geometry IS NOT NULL", null)
                .use { c ->
                    while (c.moveToNext()) {
                        val name = if (c.isNull(0)) null else c.getString(0)
                        val geom = if (c.isNull(1)) null else c.getString(1)
                        if (geom != null) ids.add(Pair(geom, name))
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "track read failed: ${e.message}")
            return Result(0, 0, 0.0, removed)
        }

        SpatialDbManager.beginDedupSession()

        var trails = 0
        var miles = 0.0
        for (i in ids.indices) {
            onProgress?.invoke(i, ids.size)
            val (geom, name) = ids[i]
            val r = scanTrack(name, geom)
            trails += r.first
            miles += r.second
        }
        onProgress?.invoke(ids.size, ids.size)

        Log.i(TAG, "scanAll: ${ids.size} track(s), $trails trail(s), %.2f mi, $removed removed"
            .format(miles))
        return Result(ids.size, trails, miles, removed)
    }
}
