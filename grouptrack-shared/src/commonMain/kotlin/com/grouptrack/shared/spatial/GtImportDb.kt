package com.grouptrack.shared.spatial

import androidx.sqlite.SQLiteConnection

/**
 * STEP15C-2026-11-09 (2.8 step 15): THE IMPORTS' SQL that GtWrite / GtRead do not have yet -- each function is one statement from 2.7e,
 * with 2.7e's SQL text unchanged and 2.7e's bind types kept (INVENTORY T11: TEXT where 2.7e bound text -- rawQuery's String args and
 * execSQL's String values -- so SQLite converts and compares exactly as it did). Later sub-steps add theirs here (15d track add,
 * 15f agency import, 15g OSM import). Nothing here catches: the caller keeps 2.7e's try/catch where 2.7e had it.
 * 15c (rider trails, from RiderTrailWriter): [trailCandidates], [trackNameGeometry], [riderStored], [tracksForRider], [setTrailClass],
 * [insertTrailPropsRider].
 * STEP15D-2026-11-09 -- 15d (track add / sync / properties, from SpatialDbManager): [trackIdNameByHash], [trackOfficialName],
 * [trackAliasExists], [trackHashes], [trackHashNames], [upsertTrackProps], [insertWaypoint], [insertWaypointMinimal], [setWaypointBbox].
 * STEP15E-2026-11-09 -- 15e (the GPX import, from ConvoyTrackOps.importGpxAllArtifacts): [insertTrailPropsImport] (the spatial UPDATE
 * before it is 15c's [setTrailClass], the same statement).
 * @param dataConn grouptrack_data.db -- CODE RULE 1: optional exactly as in 2.7e (SpatialDbManager.getExtensionDb() can be null; the
 *        extension writes are then skipped, as 2.7e's `eDb?.execSQL`).
 * Column values handed to callbacks are String? because the columns may hold SQL NULL (2.7e read them with `if (c.isNull(i)) null`).
 */
class GtImportDb(private val conn: SQLiteConnection, private val dataConn: SQLiteConnection?) {

    private fun exec(c: SQLiteConnection, sql: String, args: List<Any?>) {
        val st = c.prepare(sql)
        try {
            args.forEachIndexed { i, a -> when (a) { null -> st.bindNull(i + 1); is Double -> st.bindDouble(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); is Long -> st.bindLong(i + 1, a); else -> st.bindText(i + 1, a.toString()) } }
            st.step()
        } finally { st.close() }
    }

    private fun text(st: androidx.sqlite.SQLiteStatement, i: Int): String? = if (st.isNull(i)) null else st.getText(i)

    // ---- rider trails (RiderTrailWriter, 2.7e) ----------------------------------------------------------------------------

    /**
     * RiderTrailWriter.candidatesFor's query: the geometry of every trail whose box meets the given box, in SQLite's row order (2.7e has
     * no ORDER BY -- the order decides anchor ties in the scan, INVENTORY T9). The four bounds are TEXT, as 2.7e bound them
     * (`(minLat - BBOX_MARGIN_DEG).toString()` through rawQuery): the caller writes them with GtJava.jd.
     * [excludeCategory] true adds 2.7e's literal clause ` AND (carto_code IS NULL OR carto_code <> '<category>')` (not bound -- 2.7e
     * built it into the SQL text).
     */
    fun trailCandidates(maxLatGe: String, minLatLe: String, maxLonGe: String, minLonLe: String,
                        excludeCategory: Boolean, category: String, each: (geometry: String?) -> Unit) {
        val st = conn.prepare(
            "SELECT geometry FROM trails WHERE min_lat IS NOT NULL " +
                "AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?" +
                (if (excludeCategory) " AND (carto_code IS NULL OR carto_code <> '$category')" else ""))
        try {
            st.bindText(1, maxLatGe); st.bindText(2, minLatLe); st.bindText(3, maxLonGe); st.bindText(4, minLonLe)
            while (st.step()) each(text(st, 0))
        } finally { st.close() }
    }

    /** One track's (name, geometry) by its hash -- RiderTrailWriter.scanTrackByHash. Null = no such row (2.7e: moveToFirst() false). */
    fun trackNameGeometry(geomHash: String): Pair<String?, String?>? {
        val st = conn.prepare("SELECT name, geometry FROM tracks WHERE geom_hash=? LIMIT 1")
        try {
            st.bindText(1, geomHash)
            return if (st.step()) Pair(text(st, 0), text(st, 1)) else null
        } finally { st.close() }
    }

    /** The rider trails stored now -- RiderTrailWriter.scanAll's first read: (trail_id, geom_hash, geometry) per row, SQLite's order. */
    fun riderStored(category: String, each: (trailId: String?, geomHash: String?, geometry: String?) -> Unit) {
        val st = conn.prepare("SELECT trail_id, geom_hash, geometry FROM trails WHERE carto_code=?")
        try {
            st.bindText(1, category)
            while (st.step()) each(text(st, 0), text(st, 1), text(st, 2))
        } finally { st.close() }
    }

    /** Every track with a geometry -- RiderTrailWriter.scanAll's second read: (name, geometry) per row, SQLite's order (no ORDER BY in
     *  2.7e: the order decides which of two recordings of one ride lays a piece). */
    fun tracksForRider(each: (name: String?, geometry: String?) -> Unit) {
        val st = conn.prepare("SELECT name, geometry FROM tracks WHERE geometry IS NOT NULL")
        try {
            while (st.step()) each(text(st, 0), text(st, 1))
        } finally { st.close() }
    }

    /** RiderTrailWriter.writeOne's spatial UPDATE (RIDEROUT-2026-10-24), the five values bound as text as 2.7e's execSQL bound Strings. */
    fun setTrailClass(trailId: String, cartoCode: String, cartoCodeSource: String, useType: String, landStatus: String) {
        exec(conn, "UPDATE trails SET carto_code=?, carto_code_source=?, use_type=?, land_status=? WHERE trail_id=?",
            listOf(cartoCode, cartoCodeSource, useType, landStatus, trailId))
    }

    /** RiderTrailWriter.writeOne's trail_properties row (6 columns, INSERT OR IGNORE). Nothing without the data database (2.7e `eDb?.`). */
    fun insertTrailPropsRider(trailId: String, sourceId: String, sourceUniqueId: String, cartoCode: String, motorizedAllowed: String, ingestedAt: String) {
        val d = dataConn ?: return
        exec(d, "INSERT OR IGNORE INTO trail_properties " +
                "(trail_id,source_id,source_unique_id,carto_code,motorized_allowed,ingested_at) " +
                "VALUES (?,?,?,?,?,?)",
            listOf(trailId, sourceId, sourceUniqueId, cartoCode, motorizedAllowed, ingestedAt))
    }

    // ---- tracks (SpatialDbManager, 2.7e) -- STEP15D-2026-11-09 ------------------------------------------------------------

    /** updateTrackPropertiesForHash's lookup: (track_id, name) of the track with this hash, or null when there is no row (2.7e:
     *  moveToFirst() false). Each value is String? -- 2.7e read them with getString, which gives null for SQL NULL. */
    fun trackIdNameByHash(geomHash: String): Pair<String?, String?>? {
        val st = conn.prepare("SELECT track_id, name FROM tracks WHERE geom_hash=? LIMIT 1")
        try {
            st.bindText(1, geomHash)
            return if (st.step()) Pair(text(st, 0), text(st, 1)) else null
        } finally { st.close() }
    }

    /** resolveTrackAdd's official name: the track's name, or null for no row or a NULL name (2.7e: `if (it.isNull(0)) null`). */
    fun trackOfficialName(trackId: String): String? {
        val st = conn.prepare("SELECT name FROM tracks WHERE track_id=? LIMIT 1")
        try {
            st.bindText(1, trackId)
            return if (st.step()) text(st, 0) else null
        } finally { st.close() }
    }

    /** resolveTrackAdd's alias check. False without the data database (2.7e: `extensionDb?.rawQuery(...)`). */
    fun trackAliasExists(trackId: String, alias: String): Boolean {
        val d = dataConn ?: return false
        val st = d.prepare("SELECT 1 FROM artifact_aliases WHERE artifact_type='track' AND artifact_id=? AND alias=? LIMIT 1")
        try {
            st.bindText(1, trackId); st.bindText(2, alias)
            return st.step()
        } finally { st.close() }
    }

    /** backfillAllTrackProperties' read: every track hash, SQLite's order (no ORDER BY in 2.7e). */
    fun trackHashes(each: (geomHash: String?) -> Unit) {
        val st = conn.prepare("SELECT geom_hash FROM tracks WHERE geom_hash IS NOT NULL")
        try { while (st.step()) each(text(st, 0)) } finally { st.close() }
    }

    /** allTrackGeomHashes' read: (geom_hash, name) per track, SQLite's order (no ORDER BY in 2.7e). */
    fun trackHashNames(each: (geomHash: String?, name: String?) -> Unit) {
        val st = conn.prepare("SELECT geom_hash, name FROM tracks WHERE geom_hash IS NOT NULL")
        try { while (st.step()) each(text(st, 0), text(st, 1)) } finally { st.close() }
    }

    /**
     * updateTrackPropertiesForHash's write: 2.7e's two statements, in order, with 2.7e's binds (execSQL's Any args: String as text, Double
     * as a REAL, Int as an INTEGER, null as NULL). The caller has already returned when there is no data database (2.7e `extensionDb ?:
     * return false`), so [dataConn] is present here; the guard only keeps the type honest.
     * @param recordedAt / durationMinutes / avgSpeedMph null = SQL NULL, as 2.7e bound them (no times in the GPX -> no duration).
     */
    fun upsertTrackProps(trackId: String, filename: String, sourceFormat: String, recordedAt: String?,
                         distanceMiles: Double, durationMinutes: Int?, maxSpeedMph: Double, avgSpeedMph: Double?,
                         elevationGainFt: Int, pointCount: Int) {
        val d = dataConn ?: return
        exec(d, "INSERT OR IGNORE INTO track_properties (track_id) VALUES (?)", listOf(trackId))
        exec(d,
            "UPDATE track_properties SET filename=?, source_format=?, recorded_at=?, " +
            "distance_miles=?, duration_minutes=?, max_speed_mph=?, avg_speed_mph=?, " +
            "elevation_gain_ft=?, point_count=? WHERE track_id=?",
            listOf(filename, sourceFormat, recordedAt,
                distanceMiles, durationMinutes, maxSpeedMph, avgSpeedMph,
                elevationGainFt, pointCount, trackId))
    }

    // ---- waypoints (SpatialDbManager.insertWaypoint, 2.7e) -- STEP15D-2026-11-09 -------------------------------------------

    /** insertWaypoint's first statement (11 columns, INSERT OR IGNORE): lat/lon bound as REAL, as 2.7e's arrayOf<Any>. */
    fun insertWaypoint(waypointId: String, name: String, geometry: String, type: String, lat: Double, lon: Double, ts: String, geomHash: String) {
        exec(conn, "INSERT OR IGNORE INTO waypoints (waypoint_id, name, geometry, type, min_lat, max_lat, min_lon, max_lon, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            listOf(waypointId, name, geometry, type, lat, lat, lon, lon, ts, ts, geomHash))
    }

    /** insertWaypoint's fallback ("If column mismatch, try minimal insert"): 7 columns, all text. */
    fun insertWaypointMinimal(waypointId: String, name: String, geometry: String, type: String, ts: String, geomHash: String) {
        exec(conn, "INSERT OR IGNORE INTO waypoints (waypoint_id, name, geometry, type, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?)",
            listOf(waypointId, name, geometry, type, ts, ts, geomHash))
    }

    /** insertWaypoint's fallback, second statement: the bbox columns, REAL. */
    fun setWaypointBbox(waypointId: String, lat: Double, lon: Double) {
        exec(conn, "UPDATE waypoints SET min_lat=?, max_lat=?, min_lon=?, max_lon=? WHERE waypoint_id=?",
            listOf(lat, lat, lon, lon, waypointId))
    }

    // ---- the GPX import (ConvoyTrackOps.importGpxAllArtifacts, 2.7e) -- STEP15E-2026-11-09 ---------------------------------

    /** importGpxAllArtifacts' trail_properties row for a trail imported from a GPX (7 columns, INSERT OR IGNORE; TRAILCLEAN-2026-11-04:
     *  + agency_id = the batch). All seven bound as text, as 2.7e's execSQL bound Strings. Nothing without the data database
     *  (2.7e `getExtensionDb()?.`). The spatial UPDATE before it is [setTrailClass] (the same statement). */
    fun insertTrailPropsImport(trailId: String, sourceId: String, sourceUniqueId: String, cartoCode: String, motorizedAllowed: String,
                               agencyId: String, ingestedAt: String) {
        val d = dataConn ?: return
        exec(d, "INSERT OR IGNORE INTO trail_properties " +
                "(trail_id,source_id,source_unique_id,carto_code,motorized_allowed,agency_id,ingested_at) VALUES (?,?,?,?,?,?,?)",
            listOf(trailId, sourceId, sourceUniqueId, cartoCode, motorizedAllowed, agencyId, ingestedAt))
    }
}
