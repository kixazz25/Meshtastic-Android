package com.grouptrack.shared.db

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.log.gtLogger

/**
 * STEP12-2026-11-07: THE DATABASE SCHEMA, shared by Android and the iPad.
 * VERSION 1 = exactly what Droid 1's 2.7e databases hold (inventory 11-07 13:52): the three schema files the app ships
 * (schema_spatial_v3.sql, schema_extension_v3.sql, schema_device_additions.sql) + the 15 tables / columns / indexes the app adds
 * outside them (trail columns, land areas, reference points, route notes, the source index, ride waypoints). Every statement is
 * "if missing", so running it on a database that already has it changes nothing.
 * [ensure] brings both databases to [CURRENT]: version 1 as above, then each step in [UPGRADES] ONCE, in order. The version is kept
 * in each database's own PRAGMA user_version.
 *  - Android runs its FROZEN pre-2.8 history first (the v3 delete gate, the old column additions in SpatialDbManager.init), then this.
 *  - The iPad runs only this.
 * ⚠ FUTURE SCHEMA CHANGES: add a step to [UPGRADES] (and raise [CURRENT]) -- never edit version 1. A step then runs once on every
 *   tablet, both platforms, and must itself be "if missing" (a half-run step must converge, as the Android additions always have).
 */
object GtSchema {
    const val CURRENT = 1
    private val log = gtLogger("Schema")

    /** Steps after version 1: (version it brings the databases to, the step). Empty until the first post-2.8 change. */
    val UPGRADES: List<Pair<Int, (SQLiteConnection, SQLiteConnection) -> Unit>> = emptyList()

    /**
     * @param readSchemaFile reads one of the shipped schema files by name (Android: the app's assets; iPad: the app bundle).
     * @return the version both databases are at afterwards.
     */
    fun ensure(spatial: SQLiteConnection, data: SQLiteConnection, readSchemaFile: (String) -> String): Int {
        val before = minOf(version(spatial), version(data))
        if (before < 1) {
            versionOne(spatial, data, readSchemaFile)
            setVersion(spatial, 1); setVersion(data, 1)
            log.i { "STEP12 schema version 1 ensured (was $before)" }
        }
        for ((to, step) in UPGRADES) {
            if (minOf(version(spatial), version(data)) >= to) continue
            step(spatial, data)
            setVersion(spatial, to); setVersion(data, to)
            log.i { "STEP12 schema upgraded to $to" }
        }
        return minOf(version(spatial), version(data))
    }

    private fun versionOne(s: SQLiteConnection, d: SQLiteConnection, read: (String) -> String) {
        // ---- spatial (grouptrack_spatial.db) ----
        if (!hasTable(s, "schema_version")) runFile(s, read("schema_spatial_v3.sql"))
        for (c in listOf("carto_code", "status", "land_status", "use_type", "carto_code_source", "restricted_geom", "open_geom"))
            addColumn(s, "trails", c, "TEXT")
        exec(s, "CREATE TABLE IF NOT EXISTS land_areas (area_id INTEGER PRIMARY KEY, state_code TEXT NOT NULL, kind TEXT NOT NULL, " +
            "own_type TEXT, own_name TEXT, mang_name TEXT, des_type TEXT, des_name TEXT, unit_name TEXT, pub_access TEXT, gap_sts TEXT, " +
            "acres REAL, min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL, rings TEXT NOT NULL)")
        exec(s, "CREATE INDEX IF NOT EXISTS idx_land_state_bbox ON land_areas(state_code, min_lat, max_lat, min_lon, max_lon)")
        exec(s, "CREATE TABLE IF NOT EXISTS land_meta (k TEXT PRIMARY KEY, v TEXT)")
        exec(s, "CREATE TABLE IF NOT EXISTS land_sources (state_code TEXT PRIMARY KEY, zip_path TEXT, zip_mtime INTEGER, built_at TEXT, " +
            "public_n INTEGER, restricted_n INTEGER, nonfed_n INTEGER, s REAL, w REAL, n REAL, e REAL)")
        exec(s, "CREATE TABLE IF NOT EXISTS reference_points (point_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, source_uid TEXT NOT NULL, " +
            "name TEXT NOT NULL, fclass TEXT, population INTEGER, lat REAL NOT NULL, lon REAL NOT NULL, geom_hash TEXT NOT NULL, ingested_at TEXT)")
        exec(s, "CREATE INDEX IF NOT EXISTS ix_refpt_bbox ON reference_points(lat, lon)")
        exec(s, "CREATE INDEX IF NOT EXISTS ix_refpt_fclass ON reference_points(fclass)")
        exec(s, "CREATE UNIQUE INDEX IF NOT EXISTS ix_refpt_identity ON reference_points(geom_hash)")
        // ---- data (grouptrack_data.db) ----
        if (!hasTable(d, "schema_version")) runFile(d, read("schema_extension_v3.sql"))
        for (c in listOf("ref_code", "operator", "width_raw", "maxwidth_raw", "incline", "sac_scale", "mtb_scale", "trail_visibility", "other_restrictions"))
            addColumn(d, "trail_properties", c, "TEXT")
        exec(d, "CREATE INDEX IF NOT EXISTS idx_tp_source ON trail_properties(source_id, source_unique_id)")
        exec(d, "CREATE TABLE IF NOT EXISTS area_downloads (download_id TEXT PRIMARY KEY, artifact_type TEXT NOT NULL, source_id TEXT, " +
            "direction TEXT NOT NULL DEFAULT 'download', bounds_json TEXT NOT NULL, item_count INTEGER DEFAULT 0, ride_id TEXT, " +
            "created_at TEXT NOT NULL, completed_at TEXT)")
        exec(d, "CREATE INDEX IF NOT EXISTS idx_area_dl_type ON area_downloads(artifact_type, direction)")
        exec(d, "CREATE INDEX IF NOT EXISTS idx_area_dl_ride ON area_downloads(ride_id)")
        exec(d, "CREATE TABLE IF NOT EXISTS route_notes (note_id TEXT PRIMARY KEY, route_id TEXT NOT NULL, geom_hash TEXT, seq INTEGER DEFAULT 0, " +
            "kind TEXT, at_mile REAL, lat REAL, lon REAL, title TEXT, body TEXT, payload TEXT, author TEXT DEFAULT 'generated', created_at TEXT NOT NULL)")
        exec(d, "CREATE INDEX IF NOT EXISTS idx_route_notes_route ON route_notes(route_id, seq)")
        exec(d, "CREATE INDEX IF NOT EXISTS idx_route_notes_hash ON route_notes(geom_hash)")
        runFile(d, read("schema_device_additions.sql"))   // idempotent, as Android runs it every launch
        exec(d, "CREATE TABLE IF NOT EXISTS ride_waypoints (ride_id TEXT NOT NULL, waypoint_id TEXT NOT NULL, seq INTEGER NOT NULL, " +
            "PRIMARY KEY (ride_id, waypoint_id))")
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------------
    fun version(c: SQLiteConnection): Int { val st = c.prepare("PRAGMA user_version"); try { return if (st.step()) st.getLong(0).toInt() else 0 } finally { st.close() } }
    private fun setVersion(c: SQLiteConnection, v: Int) = exec(c, "PRAGMA user_version = $v")
    private fun exec(c: SQLiteConnection, sql: String) { val st = c.prepare(sql); try { st.step() } finally { st.close() } }
    fun hasTable(c: SQLiteConnection, name: String): Boolean {
        val st = c.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")
        try { st.bindText(1, name); return st.step() } finally { st.close() }
    }
    private fun hasColumn(c: SQLiteConnection, table: String, col: String): Boolean {
        val st = c.prepare("PRAGMA table_info($table)")
        try { while (st.step()) if (st.getText(1) == col) return true; return false } finally { st.close() }
    }
    private fun addColumn(c: SQLiteConnection, table: String, col: String, type: String) {
        if (!hasTable(c, table) || hasColumn(c, table, col)) return
        try { exec(c, "ALTER TABLE $table ADD COLUMN $col $type") } catch (e: Exception) { log.w { "STEP12 $table.$col: ${e.message}" } }
    }
    /** As SpatialDbManager.runSchemaFromAsset (2.7e): comment lines out, split on ';', each statement run, a failing one skipped. */
    private fun runFile(c: SQLiteConnection, text: String) {
        val sql = text.lines().filter { !it.trim().startsWith("--") }.joinToString("\n")
        exec(c, "BEGIN")
        try {
            for (raw in sql.split(";")) {
                val st = raw.trim(); if (st.isEmpty()) continue
                try { exec(c, "$st;") } catch (e: Exception) { log.w { "STEP12 skip: ${e.message} | ${st.take(60)}" } }
            }
            exec(c, "COMMIT")
        } catch (e: Exception) { try { exec(c, "ROLLBACK") } catch (_: Exception) {}; throw e }
    }
}
