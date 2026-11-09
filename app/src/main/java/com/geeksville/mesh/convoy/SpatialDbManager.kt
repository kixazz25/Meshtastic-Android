package com.geeksville.mesh.convoy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * SpatialDbManager -- singleton managing two SQLite databases for V2.5.
 *
 * Database 1: grouptrack_spatial.db  (spatial tables: trails, tracks, waypoints, routes)
 *   - Geometry stored as WKT TEXT (portable to iOS)
 *   - Pure identity + geometry + timestamps
 *
 * Database 2: grouptrack_data.db  (extension tables: properties, aliases, queues, log)
 *   - References spatial tables by ID
 *   - Maintained by Kotlin triggers (no SQL FKs across databases)
 *
 * RULES:
 *   - No FKs on spatial tables
 *   - Every spatial insert must create matching extension row
 *   - Every spatial delete must cascade to extensions
 *   - is_preferred on aliases is LOCAL ONLY
 *   - All timestamps ISO 8601
 *
 * Storage: Documents/GroupTrack/ (survives reinstall)
 * Schema version tracked per database.
 */
object SpatialDbManager {

    private const val TAG = "SpatialDbMgr"
    private const val SPATIAL_DB = "grouptrack_spatial.db"
    private const val MARKER_FILE = ".db_schema_version"
    private const val EXTENSION_DB = "grouptrack_data.db"
    private const val SPATIAL_SCHEMA_VERSION = 3
    private const val EXTENSION_SCHEMA_VERSION = 3

    private var spatialDb: SQLiteDatabase? = null
    private var extensionDb: SQLiteDatabase? = null
    private val syncRunning = java.util.concurrent.atomic.AtomicBoolean(false)   // single-flight guard for syncTracksFromFiles
    private var initialized = false

    /** Public GroupTrack dir (Documents/GroupTrack) -- same path the DBs use;
     *  survives reinstall. Exposed for the launch-time auto-resync receipt. */
    // STORAGEROOT-2026-09-11: was a second, independent copy of the same
    // path expression dbDir() used below. ⚠ Two functions, one truth, nothing
    // keeping them in step.
    internal fun groupTrackDir(): File = GroupTrackStorage.root()
    // GATEJOB-2026-08-21F: internal so the startup job can test for the DB FILE
    // without opening it. init() calls openOrCreateDatabase, which creates an
    // empty schema where real data should be -- the 08-01 mechanism.
    // STORAGEROOT-2026-09-11: the mkdirs and the .nomedia move with it --
    // rootReady() does exactly what this did.
    internal fun dbDir(): File = GroupTrackStorage.rootReady()

    /** Initialize both databases. Call from app startup. */
    fun init(context: Context) {
        if (initialized) return
        try {
            val dir = dbDir()
            // === V2.5 DB REVISION v3: one-time delete-gate (regenerate-not-migrate) ===
            // DBs live in PUBLIC storage and survive uninstall/clear-data, so this in-app
            // delete is the SOLE clearing mechanism. Gated by a marker FILE stored beside the
            // DBs (see DBMARKER-SHARED-2026-08-16 below) so the gate and the data it guards share
            // a lifetime; an app-private marker did not survive uninstall and fired on reinstall.
            // After delete, the open/create below
            // finds the files missing and rebuilds fresh from the shipped v3 schema assets.
            try {
                // === DBMARKER-SHARED-2026-08-16 ===
                // The marker now lives in a FILE beside the databases so it shares their
                // lifetime. SharedPreferences is app-private and does not survive an
                // uninstall, while the DBs in public storage do -- that mismatch made this
                // gate fire on every reinstall and delete live data.
                val markerFile = File(dir, MARKER_FILE)

                // Read order: marker file -> legacy prefs value -> 0.
                // The prefs fallback carries existing installs across the changeover; without
                // it, the first launch of this build would see no file and delete everything.
                val prefs = context.getSharedPreferences("grouptrack_db", Context.MODE_PRIVATE)
                var dbMarker = 0
                try {
                    if (markerFile.exists()) {
                        dbMarker = markerFile.readText().trim().toIntOrNull() ?: 0
                    }
                } catch (ex: Exception) {
                    android.util.Log.w(TAG, "DB marker: file read failed: " + ex.message)
                }
                if (dbMarker <= 0) {
                    val legacy = prefs.getInt("db_schema_marker", 0)
                    if (legacy > 0) {
                        dbMarker = legacy
                        android.util.Log.i(TAG, "DB marker: adopted legacy prefs value " + legacy)
                    }
                }

                // === DBMARKER-STAMP-2026-08-16 ===
                // Lay the marker file down whenever it is missing but the resolved marker is
                // already current. Without this, an install whose marker came from the legacy
                // prefs value reaches neither of the branches that write the file, and stays
                // protected only by app-private state -- the very lifetime mismatch this
                // marker was moved to public storage to eliminate. Writes only when absent,
                // so it is a no-op on every subsequent launch.
                if (dbMarker >= 3 && !markerFile.exists()) {
                    try {
                        markerFile.writeText(dbMarker.toString())
                        android.util.Log.i(TAG, "DB marker: stamped missing marker file at " + dbMarker)
                    } catch (ex: Exception) {
                        android.util.Log.e(TAG, "DB marker: stamp-if-missing failed: " + ex.message)
                    }
                }

                // Row-count guard. Populated databases mean this install has already been
                // through the migration whatever the marker says, so the delete is skipped
                // and the marker is stamped instead. A count that cannot be taken (the
                // SELinux ioctl denial on the FUSE-mounted file is routine) returns -1 and
                // is treated as populated: refuse to delete on uncertainty.
                var existingRows = -1
                try {
                    val probe = File(dir, SPATIAL_DB)
                    if (!probe.exists()) {
                        existingRows = 0
                    } else {
                        val rdb = SQLiteDatabase.openDatabase(
                            probe.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                        )
                        rdb.use { db ->
                            var total = 0
                            for (t in listOf("trails", "tracks", "waypoints", "routes")) {
                                try {
                                    db.rawQuery("SELECT COUNT(*) FROM " + t, null).use { c ->
                                        if (c.moveToFirst()) total += c.getInt(0)
                                    }
                                } catch (_: Exception) {
                                    // Table absent on a genuinely fresh DB; contributes nothing.
                                }
                            }
                            existingRows = total
                        }
                    }
                } catch (ex: Exception) {
                    android.util.Log.w(TAG, "DB marker: row probe failed, assuming populated: " + ex.message)
                    existingRows = -1
                }

                if (dbMarker < 3 && existingRows != 0) {
                    // Data present (or unreadable) but the marker is behind: this is a
                    // reinstall over live databases, not a fresh install. Stamp and skip.
                    android.util.Log.w(
                        TAG,
                        "DB v3 migration: SKIPPED -- marker " + dbMarker + " but rows=" + existingRows +
                            " (reinstall over existing data); stamping marker, no delete"
                    )
                    try {
                        markerFile.writeText("3")
                    } catch (ex: Exception) {
                        android.util.Log.e(TAG, "DB marker: stamp failed: " + ex.message)
                    }
                    prefs.edit().putInt("db_schema_marker", 3).apply()
                    dbMarker = 3
                }

                if (dbMarker < 3) {
                    // Delete each DB plus its SQLite sidecars (-journal/-wal/-shm). A stale
                    // journal/wal left by a mid-write kill could otherwise REPLAY into the
                    // fresh blank v3 DB and resurrect/ corrupt data. Absent sidecars are skipped.
                    val sidecarSuffixes = listOf("", "-journal", "-wal", "-shm")
                    var sd = true
                    var ed = true
                    for (suffix in sidecarSuffixes) {
                        val f = File(dir, SPATIAL_DB + suffix)
                        if (f.exists()) {
                            val ok = f.delete()
                            android.util.Log.i(TAG, "DB v3 migration: delete " + SPATIAL_DB + suffix + " = " + ok)
                            if (!ok) sd = false
                        }
                    }
                    for (suffix in sidecarSuffixes) {
                        val f = File(dir, EXTENSION_DB + suffix)
                        if (f.exists()) {
                            val ok = f.delete()
                            android.util.Log.i(TAG, "DB v3 migration: delete " + EXTENSION_DB + suffix + " = " + ok)
                            if (!ok) ed = false
                        }
                    }
                    android.util.Log.i(TAG, "DB v3 migration: spatial-clean=" + sd + " extension-clean=" + ed + " (marker was " + dbMarker + ")")
                    if (sd && ed) {
                        // DBMARKER-SHARED-2026-08-16: write the file first -- it is the one that
                        // survives an uninstall. The prefs value is kept in step so a
                        // rollback to the previous build still reads a sane marker.
                        try {
                            File(dir, MARKER_FILE).writeText("3")
                        } catch (ex: Exception) {
                            android.util.Log.e(TAG, "DB marker: write failed: " + ex.message)
                        }
                        prefs.edit().putInt("db_schema_marker", 3).apply()
                        android.util.Log.i(TAG, "DB v3 migration: marker set to 3; DBs will rebuild from v3 assets")
                    } else {
                        android.util.Log.w(TAG, "DB v3 migration: a delete failed (scoped-storage?); marker NOT advanced, will retry next launch")
                    }
                }
            } catch (ex: Exception) {
                android.util.Log.e(TAG, "DB v3 migration gate error: " + ex.message)
            }
            // === end delete-gate ===

            // Open/create spatial database
            val spatialFile = File(dir, SPATIAL_DB)
            spatialDb = SQLiteDatabase.openOrCreateDatabase(spatialFile, null)
            // Apply v2 migration if needed (add geometry/bbox to tracks)
            try {
                spatialDb!!.rawQuery("SELECT geometry FROM tracks LIMIT 1", null).use { it.moveToFirst() }
            } catch (e: Exception) {
                android.util.Log.i("SpatialDb", "Applying v2 migration to tracks table")
                try {
                    spatialDb!!.execSQL("ALTER TABLE tracks ADD COLUMN geometry TEXT")
                    spatialDb!!.execSQL("ALTER TABLE tracks ADD COLUMN min_lat REAL")
                    spatialDb!!.execSQL("ALTER TABLE tracks ADD COLUMN max_lat REAL")
                    spatialDb!!.execSQL("ALTER TABLE tracks ADD COLUMN min_lon REAL")
                    spatialDb!!.execSQL("ALTER TABLE tracks ADD COLUMN max_lon REAL")
                    spatialDb!!.execSQL("CREATE INDEX IF NOT EXISTS idx_tracks_bbox ON tracks(min_lat, max_lat, min_lon, max_lon)")
                } catch (e2: Exception) { android.util.Log.w("SpatialDb", "Migration partial: " + e2.message) }
            }
            // v3 migration: add type column to tracks (TRACK/ROUTE distinction)
            try {
                spatialDb!!.rawQuery("SELECT type FROM tracks LIMIT 1", null).use { it.moveToFirst() }
            } catch (e: Exception) {
                android.util.Log.i("SpatialDb", "Applying v3 migration: type column on tracks")
                try {
                    spatialDb!!.execSQL("ALTER TABLE tracks ADD COLUMN type TEXT NOT NULL DEFAULT 'TRACK'")
                    spatialDb!!.execSQL("CREATE INDEX IF NOT EXISTS idx_tracks_type ON tracks(type)")
                } catch (e3: Exception) { android.util.Log.w("SpatialDb", "v3 migration partial: " + e3.message) }
            }
            // v3 migration: ensure waypoints table has bbox columns for viewport queries
            try {
                spatialDb!!.rawQuery("SELECT min_lat FROM waypoints LIMIT 1", null).use { it.moveToFirst() }
            } catch (e: Exception) {
                android.util.Log.i("SpatialDb", "Applying v3 migration: bbox columns on waypoints")
                try {
                    spatialDb!!.execSQL("ALTER TABLE waypoints ADD COLUMN min_lat REAL")
                    spatialDb!!.execSQL("ALTER TABLE waypoints ADD COLUMN max_lat REAL")
                    spatialDb!!.execSQL("ALTER TABLE waypoints ADD COLUMN min_lon REAL")
                    spatialDb!!.execSQL("ALTER TABLE waypoints ADD COLUMN max_lon REAL")
                    spatialDb!!.execSQL("CREATE INDEX IF NOT EXISTS idx_waypoints_bbox ON waypoints(min_lat, max_lat, min_lon, max_lon)")
                } catch (e3: Exception) { android.util.Log.w("SpatialDb", "waypoint bbox migration: " + e3.message) }
            }
            // STATUS-2026-09-01: `status` on TRAILS.
            // ⛔ THIS IS WHAT CRASHED THE 09-01 BUILD. queryTrailsByViewport
            // selects `status AS Status` and the filter excludes CLOSED, but
            // status lived ONLY on trail_properties -- so every viewport query
            // threw and the map died on first draw.
            // ⭐ Carto is already kept in BOTH stores for exactly this reason
            // (see :1689, "spatial wins only if it has a real value"): the
            // viewport query reads `trails` alone and cannot afford a join.
            // Status is the same kind of value and gets the same treatment.
            try {
                spatialDb!!.rawQuery("SELECT status FROM trails LIMIT 1", null)
                    .use { it.moveToFirst() }
            } catch (_: Exception) {
                android.util.Log.i("SpatialDb", "Applying migration: status on trails")
                try {
                    spatialDb!!.execSQL("ALTER TABLE trails ADD COLUMN status TEXT")
                } catch (e: Exception) {
                    android.util.Log.w("SpatialDb", "status column: ${e.message}")
                }
            }

            // CLASSIFYCOLS-2026-09-02: ⛔ THE FOUR-FIELD CLASSIFICATION COLUMNS
            // EXISTED ON EXACTLY ONE DEVICE. A Python script added land_status,
            // use_type and carto_code_source to Droid 2 on 08-31; they were
            // never put in a schema or a migration, and nothing in the app
            // writes them. Droid 1 crashed on the first viewport query --
            // "no such column: land_status" -- because the filter references
            // them.
            // ⚠ Each is probed SEPARATELY. A device that got one and not the
            // others (there is at least one) must converge, and a single probe
            // on the first column would skip the rest.
            for (col in listOf("land_status", "use_type", "carto_code_source", "restricted_geom", "open_geom")) {   // RSTSEG-2026-11-02: + restricted_geom; RSTOPEN-2026-11-02: + open_geom
                try {
                    spatialDb!!.rawQuery("SELECT $col FROM trails LIMIT 1", null)
                        .use { it.moveToFirst() }
                } catch (_: Exception) {
                    android.util.Log.i("SpatialDb", "Applying migration: $col on trails")
                    try {
                        spatialDb!!.execSQL("ALTER TABLE trails ADD COLUMN $col TEXT")
                    } catch (e: Exception) {
                        android.util.Log.w("SpatialDb", "$col column: ${e.message}")
                    }
                }
            }

            // v3 migration: ensure routes columns on tracks table (routes share tracks table per decision log)
            // Routes are tracks with type='ROUTE'

            // STATUS-2026-09-01: the v5 block MOVED from here to after the
            // extension database is opened at :308. ⛔ It ran against a null
            // extensionDb and all nine ALTERs threw -- visible in the 09-01
            // logcat as nine "null object reference" warnings. Harmless on this
            // device because the Python had already added the columns, but a
            // FRESH INSTALL would have got none of them.

            // v4 migration: add carto_code to trails for color display
            try {
                spatialDb!!.rawQuery("SELECT carto_code FROM trails LIMIT 1", null).use { it.moveToFirst() }
            } catch (e: Exception) {
                android.util.Log.i("SpatialDb", "Applying v4 migration: carto_code on trails")
                try {
                    spatialDb!!.execSQL("ALTER TABLE trails ADD COLUMN carto_code TEXT")
                } catch (e4: Exception) { android.util.Log.w("SpatialDb", "v4 migration: " + e4.message) }
            }
            if (!hasTable(spatialDb!!, "schema_version")) {
                runSchemaFromAsset(context, spatialDb!!, "schema_spatial_v3.sql")
                // CLEANUP-2026-08-04: the $ was backslash-escaped, so this logged the
                // LITERAL text and the DB path never appeared. That is the line that
                // would have made the 08-01 loss obvious immediately.
                android.util.Log.i(TAG, "Applied spatial schema: ${spatialFile.absolutePath}")
            } else {
                android.util.Log.i(TAG, "Opened spatial database: ${spatialFile.absolutePath}")
            }

            // Open/create extension database
            val extFile = File(dir, EXTENSION_DB)
            extensionDb = SQLiteDatabase.openOrCreateDatabase(extFile, null)
            if (!hasTable(extensionDb!!, "schema_version")) {
                runSchemaFromAsset(context, extensionDb!!, "schema_extension_v3.sql")
                android.util.Log.i(TAG, "Applied extension schema: \${extFile.absolutePath}")
            } else {
                android.util.Log.i(TAG, "Opened extension database: \${extFile.absolutePath}")
            }

            // ── STATUS-2026-09-01: both databases are open from here ──────
            // ⚠ Everything below needs extensionDb. Anything placed above the
            // open at :308 gets a null -- which is exactly what happened to the
            // v5 block on 09-01.

            // v5: the attribute columns the sources supply and we used to drop.
            try {
                extensionDb!!.rawQuery(
                    "SELECT ref_code FROM trail_properties LIMIT 1", null
                ).use { it.moveToFirst() }
            } catch (_: Exception) {
                android.util.Log.i("SpatialDb",
                    "Applying v5 migration: attribute columns on trail_properties")
                for (c in listOf("ref_code", "operator", "width_raw",
                                 "maxwidth_raw", "incline", "sac_scale",
                                 "mtb_scale", "trail_visibility",
                                 "other_restrictions")) {
                    try {
                        extensionDb!!.execSQL(
                            "ALTER TABLE trail_properties ADD COLUMN $c TEXT")
                    } catch (e: Exception) {
                        // One column already present is not a reason to abandon
                        // the rest -- a half-applied earlier run must converge.
                        android.util.Log.w("SpatialDb", "v5 $c: ${e.message}")
                    }
                }
            }

            // NOBACKFILL-2026-09-01: a status backfill lived here and has been
            // REMOVED. It walked ~47,000 rows one at a time on the
            // database-open path and ANR'd the first launch.
            // ⛔ It was also unnecessary: this release CLEARS the trails table,
            // so every updating rider reimports from empty and there is nothing
            // to backfill. ⚠ Worse, it would have sat here waiting to fire on
            // some future edge case -- a guarded heavy job on the startup path
            // is a trap, not a safeguard.
            // ⭐ The real fix is TrailImporter writing status to BOTH stores at
            // import, the way carto_code already is.

            // Attach extension db to spatial for cross-db views (optional, for future use)
            // spatialDb?.execSQL("ATTACH DATABASE '${extFile.absolutePath}' AS ext")

            // Ensure .nomedia in tile directories
            val tileDir = File(dir, "maps/tiles")
            if (tileDir.exists()) {
                val tileMapsNoMedia = File(dir, "maps/.nomedia")
                if (!tileMapsNoMedia.exists()) {
                    try { tileMapsNoMedia.createNewFile() } catch (_: Exception) {}
                }
                val tileNoMedia = File(File(dir, "maps/tiles"), ".nomedia")
                if (!tileNoMedia.exists()) {
                    try { tileNoMedia.createNewFile() } catch (_: Exception) {}
                }
            }
            // IDXSOURCE-2026-11-02: trail_properties had no index on (source_id, source_unique_id), so the import's "do we already have
            //  this source's trail" lookup read the table from the top for every trail -- each page slower than the last (Utah 11-02).
            //  Created once; a no-op every launch after.
            try {
                val t0 = System.currentTimeMillis()
                extensionDb!!.execSQL("CREATE INDEX IF NOT EXISTS idx_tp_source ON trail_properties(source_id, source_unique_id)")
                android.util.Log.i(TAG, "IDXSOURCE: trail_properties(source_id, source_unique_id) index ready in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "IDXSOURCE: ${e.message}")
            }
            // Migrate: add area_downloads if missing (existing DBs)
            if (!hasTable(extensionDb!!, "area_downloads")) {
                try {
                    extensionDb!!.execSQL("""CREATE TABLE IF NOT EXISTS area_downloads (
                        download_id TEXT PRIMARY KEY, artifact_type TEXT NOT NULL,
                        source_id TEXT, direction TEXT NOT NULL DEFAULT 'download',
                        bounds_json TEXT NOT NULL, item_count INTEGER DEFAULT 0,
                        ride_id TEXT, created_at TEXT NOT NULL, completed_at TEXT)""")
                    extensionDb!!.execSQL("CREATE INDEX IF NOT EXISTS idx_area_dl_type ON area_downloads(artifact_type, direction)")
                    extensionDb!!.execSQL("CREATE INDEX IF NOT EXISTS idx_area_dl_ride ON area_downloads(ride_id)")
                    android.util.Log.i(TAG, "Migrated: added area_downloads table")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "area_downloads migration: ${e.message}")
                }
            }
            // ROUTENOTES-2026-08-23X: route_notes. Same shape as the area_downloads
            // migration above -- hasTable guard, CREATE IF NOT EXISTS, indexes.
            // ⭐ Existing devices get an empty table on next launch: no version
            // bump, no data migration. And existing routes correctly get NO
            // notes, because a route saved last week never had a narrative.
            //
            // ⚠ IT LIVES IN THE EXTENSION DB, not the spatial one. The split is
            // geometry in spatial, everything descriptive in data -- trails
            // already follow it with trail_properties. Putting notes in spatial
            // because it is convenient would blur the line that makes the rest
            // of the schema predictable.
            if (!hasTable(extensionDb!!, "route_notes")) {
                try {
                    extensionDb!!.execSQL("""CREATE TABLE IF NOT EXISTS route_notes (
                        note_id TEXT PRIMARY KEY, route_id TEXT NOT NULL,
                        geom_hash TEXT, seq INTEGER DEFAULT 0,
                        kind TEXT, at_mile REAL, lat REAL, lon REAL,
                        title TEXT, body TEXT, payload TEXT,
                        author TEXT DEFAULT 'generated', created_at TEXT NOT NULL)""")
                    extensionDb!!.execSQL("CREATE INDEX IF NOT EXISTS idx_route_notes_route ON route_notes(route_id, seq)")
                    extensionDb!!.execSQL("CREATE INDEX IF NOT EXISTS idx_route_notes_hash ON route_notes(geom_hash)")
                    android.util.Log.i(TAG, "Migrated: added route_notes table")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "route_notes migration: ${e.message}")
                }
            }
            // DEVSCHEMA-2026-09-22: V3-shaped device tables (convergence doc v24).
            // Idempotent CREATE IF NOT EXISTS from assets -- runs every launch, never touches
            // the version marker or any existing table. Dormant in 2.6h except ride_surveys.
            // runSchemaFromAsset logs and SKIPS failing statements, so the table count is
            // logged before and after: first launch 18 -> 28, every launch after 28 -> 28.
            try {
                val tablesBefore = extensionDb!!.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
                runSchemaFromAsset(context, extensionDb!!, "schema_device_additions.sql")
                val tablesAfter = extensionDb!!.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
                android.util.Log.i(TAG, "DEVSCHEMA-2026-09-22: extension tables $tablesBefore -> $tablesAfter")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "DEVSCHEMA-2026-09-22: ${e.message}")
            }
            // STEP12B-2026-11-07: the shared schema (GtSchema) -- the ONE place the schema is brought current, on both platforms.
            // The pre-2.8 steps above are Android's frozen history and run first; the iPad runs only GtSchema. Every schema change
            // from 2.8 on goes in GtSchema.UPGRADES (grouptrack-shared), never in this function.
            try {
                val sv = com.grouptrack.shared.db.GtSchema.ensure(
                    com.geeksville.mesh.grouptrack.GtAppDb.wrap(spatialDb!!),
                    com.geeksville.mesh.grouptrack.GtAppDb.wrap(extensionDb!!)
                ) { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
                android.util.Log.i(TAG, "GTSCHEMA-2026-11-07: schema version $sv")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "GTSCHEMA-2026-11-07: ${e.message}")
            }
            initialized = true
            val trailCount = countRows(spatialDb!!, "trails")
            val trackCount = countRows(spatialDb!!, "tracks")
            val wptCount = countRows(spatialDb!!, "waypoints")
            val routeCount = countRows(spatialDb!!, "routes")
            android.util.Log.i(TAG, "Spatial DB: $trailCount trails, $trackCount tracks, $wptCount waypoints, $routeCount routes")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Database init failed: ${e.message}")
        }
    }

    /** Check if a table exists in the database */
    private fun hasTable(db: SQLiteDatabase, tableName: String): Boolean {
        return try {
            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            exists
        } catch (e: Exception) {
            false
        }
    }

    /** Run SQL schema file from assets */
    private fun runSchemaFromAsset(context: Context, db: SQLiteDatabase, assetName: String) {
        val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
        // Strip comment lines first, then split on semicolons
        val sql = raw.lines()
            .filter { !it.trim().startsWith("--") }
            .joinToString("\n")
        db.beginTransaction()
        try {
            sql.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { statement ->
                    try {
                        db.execSQL("$statement;")
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "SQL skip: ${e.message} | ${statement.take(60)}")
                    }
                }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Get spatial database reference */
    fun getSpatialDb(): SQLiteDatabase? = spatialDb

    /** Get extension database reference */
    fun getExtensionDb(): SQLiteDatabase? = extensionDb

    // ── STEP7-2026-11-07 (2.8 step 7): THE LOOKUPS ON SHARED CODE ─────────────────────────────────────────────────────────────────
    // searchByName, getArtifactDetail, getAliasesFor, queryArtifactList, queryAllTracksForCorridor, trailheadsNear, routeUsingTrailhead,
    // nextRouteNumber, routeNameById, findRouteIdByHash, routeGeomHash, routeNameExists, trackGeometry, trackName, waypointNameAt,
    // trackIdForGpxText, computeGeomHash, parseGpxTrackPoints now call :grouptrack-shared GtRead -- certified identical to their 2.7e
    // bodies 447/447 OLD = NEW = MAC (run 2026-11-06_1606). The 2.7e bodies are gone; commit 40e6efa21 still holds them.
    // Their own read-only connection pair (not the map display's: a search never waits behind a 20,000-trail map batch), reopened
    // whenever this manager's handles change (init, close, the certification swap). An androidx SQLiteConnection is single-threaded
    // (2.7e's SQLiteDatabase pooled), so every lookup goes through [lookupLock]. Nothing here runs inside a write transaction (checked
    // 11-07), so a separate connection sees exactly what 2.7e's handle saw.
    private val lookupLock = Any()
    private var lookupFor: Pair<SQLiteDatabase?, SQLiteDatabase?>? = null
    private var lookupConns: Pair<androidx.sqlite.SQLiteConnection, androidx.sqlite.SQLiteConnection?>? = null
    private var lookups: com.grouptrack.shared.spatial.GtRead? = null

    private fun closeLookups() = synchronized(lookupLock) {
        lookupConns?.let { (c, d) -> try { c.close() } catch (_: Exception) {}; try { d?.close() } catch (_: Exception) {} }
        lookupConns = null; lookupFor = null; lookups = null
    }

    /** STEP7-2026-11-07: runs [f] on the shared lookups; [none] when no spatial database is open (what each 2.7e body returned then). */
    private fun <T> withLookups(none: T, f: (com.grouptrack.shared.spatial.GtRead) -> T): T = synchronized(lookupLock) {
        val s = spatialDb ?: return@synchronized none
        val e = extensionDb
        try {
            if (lookups == null || lookupFor?.first !== s || lookupFor?.second !== e) {
                lookupConns?.let { (c, d) -> try { c.close() } catch (_: Exception) {}; try { d?.close() } catch (_: Exception) {} }
                // CODE RULE 1: the data db is optional exactly as in 2.7e (no extras before the first import) -- see GtRead's doc
                val c = com.grouptrack.shared.db.gtOpenDatabase(s.path, true)
                val d = e?.let { com.grouptrack.shared.db.gtOpenDatabase(it.path, true) }
                lookupConns = c to d; lookupFor = s to e; lookups = com.grouptrack.shared.spatial.GtRead(c, d)
                android.util.Log.i("SpatialDb", "STEP7 shared lookups opened on " + s.path + (if (e != null) " + " + e.path else " (no data db)"))
            }
            f(lookups!!)
        } catch (ex: Exception) {
            android.util.Log.w("SpatialDb", "STEP7 lookup failed: " + ex.message); none
        }
    }

    /**
     * GTCERTW-2026-11-06 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): points this manager's two handles
     * at other databases and returns the previous pair, so the certification run can drive 2.7e's own write functions against COPIES
     * of the databases and then put the live handles back (GtCertRun.runWrites, in a finally).
     * CODE RULE 1: both nullable because the pair being put BACK may hold null -- 2.7e runs with no data database open (see init) and
     * the swap must restore exactly what was there.
     */
    @Synchronized
    fun certSwapDatabases(spatial: SQLiteDatabase?, extension: SQLiteDatabase?): Pair<SQLiteDatabase?, SQLiteDatabase?> {
        val prev = spatialDb to extensionDb
        spatialDb = spatial; extensionDb = extension
        android.util.Log.i("CertRun", "GTCERTW handles now ${spatial?.path} / ${extension?.path}")
        return prev
    }

    /**
     * Query trails by viewport bounding box.
     * Returns list of [trail_id, name, geometry_wkt]
     */
    fun queryTrailsByViewport(south: Double, west: Double, north: Double, east: Double, limit: Int = 500): List<Map<String, String?>> {
        val db = spatialDb ?: return emptyList()
        val results = mutableListOf<MutableMap<String, String?>>()
        val cursor = db.rawQuery(
            // TRAILS-GATE-ORDER-2026-07-31: ORDER BY added. LIMIT without it returned
            // whichever rows SQLite reached first — rowid, i.e. IMPORT ORDER.
            // That produced longitude-banded results, and made Arizona trails
            // invisible behind Utah's lower rowids until the viewport shrank.
            // Named first (OSM carries no carto_code), then largest extent —
            // squared bbox diagonal, no sqrt needed for ordering, and both
            // columns already exist and are indexed.
            // MAPFILTER-2026-09-01: ⭐ ONE PLACE, BOTH MAPS. Everything reaches
            // the map through SpatialDisplayManager.processViewport, which
            // calls this and only this -- so the rider's Map Keys selection is
            // applied here and cannot be applied inconsistently anywhere else.
            //
            // ⛔ ALWAYS APPLIED, NEVER CONDITIONAL (Fred, 09-01): "that filter
            // should always be there with current values." An empty selection
            // yields an empty clause and the query is exactly what it was.
            //
            // ⚠ whereOrEmpty(), not where(): this can run before the map
            // composable's load() on a cold path, and a viewport query is the
            // wrong place to throw. TrailFilterState.load() is called at the
            // FRONT of both map screens precisely so that does not happen.
            //
            // ⭐ status joins the SELECT so CLOSED draws red and PLANNED
            // ghosted -- trailStyleOf() in the assets already handles it and
            // has been waiting for the property to arrive.
            "SELECT trail_id, name, geometry, carto_code AS CartoCode, status AS Status, land_status AS Land, restricted_geom AS RstGeom, open_geom AS OpenGeom FROM trails " +   // RSTSEG-2026-11-02: + RstGeom   // LANDDISPLAY-2026-11-02: + Land
                "WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? " +
                TrailFilterState.whereOrEmpty() +
                // ORDERBY-2026-09-01: ⛔ THE NAME CLAUSE IS GONE. It sorted
                // named trails first -- but "Not Named" IS A NAME (Fred,
                // 09-01), and ~69% of rows carry that literal string, so it
                // only ever sorted the truly NULL ones last. Tens of thousands
                // of placeholders competed as "named", and a 200-foot named
                // connector outranked a ten-mile track.
                // ⭐ Length alone. Fred: "real unnamed trails can be long. I
                // would rather load by trail length so meaningful artifacts are
                // loaded first."
                // ⚠ This is the BBOX DIAGONAL, not true length -- close for a
                // straight road, an understatement for a switchbacked trail.
                // Real length would need a column computed at extract; Fred
                // called that too much work for the gain, and this is still
                // strictly better than name-first.
                " ORDER BY " +
                "((max_lat-min_lat)*(max_lat-min_lat)+(max_lon-min_lon)*(max_lon-min_lon)) DESC " +
                "LIMIT ?",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
        )
        while (cursor.moveToNext()) {
            val wkt = cursor.getString(2)
            if (!wkt.isNullOrEmpty()) {
                results.add(mutableMapOf(
                    // MAPFILTER-2026-09-01: Status rides along so the WebView
                    // can colour closed and planned.
                    "Status" to (if (cursor.columnCount > 4) cursor.getString(4) else null),
                    "trail_id" to cursor.getString(0),
                    "name" to cursor.getString(1),
                    "geometry" to wkt,
                    "CartoCode" to cursor.getString(3),
                    "Land" to (if (cursor.columnCount > 5) cursor.getString(5) else null),   // LANDDISPLAY-2026-11-02: RESTRICTED draws red
                    "RstGeom" to (if (cursor.columnCount > 6) cursor.getString(6) else null),   // RSTSEG-2026-11-02: the red segments
                    "OpenGeom" to (if (cursor.columnCount > 7) cursor.getString(7) else null)   // RSTOPEN-2026-11-02: the trail without them
                ))
            }
        }
        cursor.close()
        // ── Enrich draw rows with trail_properties (data DB) so the map popup
        //    shows the same fields as the detail panel. ONE batched query for all
        //    visible trail_ids (hot draw path: avoid N per-trail SELECTs).
        //    snake_case columns -> PascalCase keys buildTrailGeoJson reads.
        val edb = extensionDb
        if (edb != null && results.isNotEmpty()) {
            try {
                val ids = results.mapNotNull { it["trail_id"] }
                if (ids.isNotEmpty()) {
                    val placeholders = ids.joinToString(",") { "?" }
                    val pc = edb.rawQuery(
                        "SELECT trail_id, surface_type, designated_uses, motorized_allowed, " +
                        "horse_allowed, hike_difficulty, bike_difficulty, owner_steward, county " +
                        "FROM trail_properties WHERE trail_id IN (" + placeholders + ")",
                        ids.toTypedArray()
                    )
                    val byId = HashMap<String, Map<String, String?>>()
                    pc.use {
                        while (it.moveToNext()) {
                            val tid = it.getString(0) ?: continue
                            byId[tid] = mapOf(
                                "SurfaceType" to it.getString(1),
                                "DesignatedUses" to it.getString(2),
                                "MotorizedAllowed" to it.getString(3),
                                "HorseAllowed" to it.getString(4),
                                "HikeDifficulty" to it.getString(5),
                                "BikeDifficulty" to it.getString(6),
                                "OwnerSteward" to it.getString(7),
                                "County" to it.getString(8)
                            )
                        }
                    }
                    for (row in results) {
                        val props = byId[row["trail_id"]] ?: continue
                        for ((k, v) in props) {
                            // spatial CartoCode wins only if it already has a real value
                            val existing = row[k]
                            if (!existing.isNullOrBlank()) continue
                            if (v.isNullOrBlank()) continue
                            row[k] = v
                        }
                    }
                }
            } catch (e: Exception) {
                // trail_properties missing/empty -> leave spatial-only rows
            }
        }
        return results
    }

    /** Convert WKT to GeoJSON coordinates string */
    fun wktToGeoJsonCoords(wkt: String): String {
        val inner: String
        val isMulti: Boolean
        if (wkt.startsWith("MULTILINESTRING(")) {
            inner = wkt.removePrefix("MULTILINESTRING(").removeSuffix(")")
            isMulti = true
        } else if (wkt.startsWith("LINESTRING(")) {
            inner = wkt.removePrefix("LINESTRING(").removeSuffix(")")
            isMulti = false
        } else return "[]"
        if (!isMulti) {
            val coords = inner.split(",").map { it.trim().split(" ") }
            return "[" + coords.joinToString(",") { "[${it[0]},${it[1]}]" } + "]"
        } else {
            val lines = inner.split("),(").map { it.trim('(', ')') }
            val jsonLines = lines.map { line ->
                val coords = line.split(",").map { it.trim().split(" ") }
                "[" + coords.joinToString(",") { "[${it[0]},${it[1]}]" } + "]"
            }
            return "[" + jsonLines.joinToString(",") + "]"
        }
    }

    /**
     * RIDERESC-2026-10-09: THE ONE ESCAPE for a value placed inside a JSON string in the map batches -- backslash, quote,
     * and every control character (a GPX <name> can carry a line break). The trail builder turned a quote into \\" (an
     * escaped backslash then a quote that ENDS the string), so ONE trail with a quote in its name -- Droid 1 has three:
     * Rush "Fly By Night", Old quigley "Wood Road", SCAR "LOOP" SPUR A -- made the whole updateTrails(...) call invalid
     * JavaScript and blanked EVERY trail on that screen. Tracks, waypoints and routes escaped the quote but not \ or
     * line breaks. All four builders use this now.
     */
    fun jsonEsc(v: String): String {
        val sb = StringBuilder(v.length + 8)
        for (ch in v) when {
            ch == '\\' -> sb.append("\\\\")
            ch == '"' -> sb.append("\\\"")
            ch.code < 0x20 || ch == '\u2028' || ch == '\u2029' -> sb.append(String.format("\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    /** Build GeoJSON FeatureCollection from viewport query results */
    fun buildTrailGeoJson(trails: List<Map<String, String?>>): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (trail in trails) {
            // RSTOPEN-2026-11-02: a trail with red parts is drawn from its OPEN parts -- nothing of it lies under the red
            val wkt = (if (!trail["RstGeom"].isNullOrEmpty()) (trail["OpenGeom"] ?: trail["geometry"]) else trail["geometry"]) ?: ""   // NULL open = not worked out yet -> whole line
            if (wkt.isEmpty()) {
                // wholly red: no open part -- its red parts are still sent below (with Restricted ticked)
                val rg0 = trail["RstGeom"] ?: ""
                if (rg0.isNotEmpty() && TrailFilterState.restricted) {
                    val rc0 = wktToGeoJsonCoords(rg0)
                    if (rc0 != "[]") { if (!first) sb.append(","); first = false
                        sb.append("{\"type\":\"Feature\",\"properties\":{\"trail_id\":\"" + jsonEsc(trail["trail_id"] ?: "") +
                            "\",\"PrimaryName\":\"" + jsonEsc(trail["name"] ?: "") + "\",\"_rst\":1},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":" + rc0 + "}}") }
                }
                continue
            }
            val coords = wktToGeoJsonCoords(wkt)
            if (coords == "[]") continue
            if (!first) sb.append(",")
            first = false
            val geoType = if (wkt.startsWith("MULTI")) "MultiLineString" else "LineString"
            fun s(k: String): String = jsonEsc(trail[k] ?: "")   // RIDERESC-2026-10-09 (was: a quote became \\" -> broken batch)
            sb.append("{\"type\":\"Feature\",\"properties\":{")
            // TRAILTAP-2026-09-02: ⛔ THE ID WAS NEVER IN THE PROPERTIES. Every
            // other artifact hands its id to Kotlin on tap; a trail had nothing
            // to hand over, which is why trails kept a Leaflet popup while
            // tracks and routes got the detail panel.
            sb.append("\"trail_id\":\"" + s("trail_id") + "\"")
            sb.append(",\"PrimaryName\":\"" + s("name") + "\"")
            sb.append(",\"CartoCode\":\"" + s("CartoCode") + "\"")
            sb.append(",\"SurfaceType\":\"" + s("SurfaceType") + "\"")
            sb.append(",\"DesignatedUses\":\"" + s("DesignatedUses") + "\"")
            sb.append(",\"MotorizedAllowed\":\"" + s("MotorizedAllowed") + "\"")
            sb.append(",\"HorseAllowed\":\"" + s("HorseAllowed") + "\"")
            sb.append(",\"HikeDifficulty\":\"" + s("HikeDifficulty") + "\"")
            sb.append(",\"BikeDifficulty\":\"" + s("BikeDifficulty") + "\"")
            sb.append(",\"OwnerSteward\":\"" + s("OwnerSteward") + "\"")
            sb.append(",\"County\":\"" + s("County") + "\"")
            sb.append(",\"Land\":\"" + s("Land") + "\"")   // LANDDISPLAY-2026-11-02
            sb.append("},\"geometry\":{\"type\":\"" + geoType + "\",\"coordinates\":" + coords + "}}")
            // RSTSEG-2026-11-02: the trail's restricted segments, drawn red OVER it (after it) -- with Filter > LAND > Restricted ticked
            val rg = trail["RstGeom"] ?: ""
            if (rg.isNotEmpty() && TrailFilterState.restricted) {
                val rc = wktToGeoJsonCoords(rg)
                if (rc != "[]") sb.append(",{\"type\":\"Feature\",\"properties\":{\"trail_id\":\"" + s("trail_id") +
                    "\",\"PrimaryName\":\"" + s("name") + "\",\"_rst\":1},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":" + rc + "}}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }


    /** Query tracks by viewport bounding box */
    /**
     * NON-SPATIAL name search across the WHOLE artifact table (NOT bbox-bounded).
     * Names are not unique -- a result is a name-occurrence keyed by geom_hash.
     * Rows ordered by (name COLLATE NOCASE, geom_hash) so the caller can assign a
     * stable per-name sequence number (1 = first geom_hash for that name, ...).
     * Excludes unnamed rows (null / blank / 'Not Named' sentinel).
     * type in: "tracks" | "trails" | "waypoints" | "routes" (lowercase plural).
     * Each row: {id, name, geom_hash, type}.
     */
    fun searchByName(type: String, term: String, limit: Int = 200): List<Map<String, String?>> = withLookups(emptyList()) { it.searchByName(type, term, limit) }   // STEP7-2026-11-07: shared GtRead

    fun queryTracksByViewport(south: Double, west: Double, north: Double, east: Double, limit: Int = 500): List<Map<String, String?>> {
        val db = spatialDb ?: return emptyList()
        val results = mutableListOf<Map<String, String?>>()
        try {
            val cursor = db.rawQuery(
                "SELECT track_id, name, geometry FROM tracks WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?",
                arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val wkt = it.getString(2) ?: continue
                    results.add(mapOf(
                        "track_id" to it.getString(0),
                        "name" to it.getString(1),
                        "geometry" to wkt
                    ))
                }
            }
            android.util.Log.i("TrackLazy", "Viewport query returned ${results.size} tracks")
        } catch (e: Exception) {
            android.util.Log.e("TrackLazy", "Viewport query failed: ${e.message}")
        }
        return results
    }

    /** Build GeoJSON FeatureCollection from track query results */
    fun buildTrackGeoJson(tracks: List<Map<String, String?>>): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        tracks.forEachIndexed { idx, track ->
            if (idx > 0) sb.append(",")
            val geom = track["geometry"] ?: return@forEachIndexed
            val name = jsonEsc(track["name"] ?: "Unnamed Track")
            val coordStr = geom.removePrefix("LINESTRING(").removeSuffix(")")
            val coords = coordStr.split(",").joinToString(",") { pair ->
                val parts = pair.trim().split(" ")
                if (parts.size >= 2) "[${parts[0]},${parts[1]}]" else "[0,0]"
            }
            val cc = jsonEsc(track["carto_code"] ?: "")
            val tid = jsonEsc(track["track_id"] ?: "")
            sb.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"$name\",\"cartoCode\":\"$cc\",\"track_id\":\"$tid\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

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

    /** Read by hash key: does a track with this geom_hash already exist? */
    fun trackHashExists(geomHash: String): Boolean {
        val db = spatialDb ?: return false
        return try {
            db.rawQuery("SELECT 1 FROM tracks WHERE geom_hash=? LIMIT 1", arrayOf(geomHash)).use { c ->
                c.moveToFirst()
            }
        } catch (e: Exception) { false }
    }

    /**
     * Reconcile my_tracks files into the spatial DB (hash-keyed spatial model).
     * Per valid track file (one with geometry):
     *   - if the filename is already an existing hash key  -> SKIPPED (already current)
     *   - else present to insertTrackToDb (DB enforces UNIQUE(geom_hash)):
     *        true  (new record)            -> ADDED/RENAMED  (insert + rename to <hash>.gpx)
     *        false (hash already in DB)     -> RENAMED        (normalize file to <hash>.gpx)
     * A file with no geometry is not a track: logged and excluded from processed.
     */
    fun syncTracksFromFiles(context: android.content.Context, onProgress: ((String) -> Unit)? = null): TrackSyncResult {
        val db = spatialDb ?: return TrackSyncResult(0, 0, 0, 0, emptyList())
        if (!syncRunning.compareAndSet(false, true)) {
            android.util.Log.w("TrackSync", "sync already running -- skipping concurrent call")
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
            val tracksDir = ConvoyTrackOps.tracksDir()   // MYTRACKSDB-2026-09-12
            if (!tracksDir.exists() || !tracksDir.isDirectory) {
                android.util.Log.i("TrackSync", "my_tracks directory not found")
                return TrackSyncResult(0, 0, 0, 0, emptyList())
            }
            val allFiles = tracksDir.listFiles() ?: emptyArray()
            // Exclude Android trash (.trashed-<ts>-<name>.gpx, left IN-PLACE on delete) + dotfiles.
            val trashed = allFiles.count { it.name.startsWith(".trashed-") || it.name.startsWith(".") }
            val gpxFiles = allFiles.filter { f ->
                f.name.lowercase().endsWith(".gpx")
                    && !f.name.startsWith(".trashed-")
                    && !f.name.startsWith(".")
            }.toTypedArray()
            if (trashed > 0) {
                android.util.Log.i("TrackSync", "ignored $trashed trashed/hidden files")
                onProgress?.invoke("— ignored $trashed trashed/hidden files —")
            }
            android.util.Log.i("TrackSync", "Found ${gpxFiles.size} GPX files to sync")

            // Per-file decision log to disk (logcat unreliable here). Overwrite each run.
            // LASTPATHS-2026-09-12
            val logFile = java.io.File(GroupTrackStorage.root(), "track_sync.log")
            val log = StringBuilder()
            fun L(msg: String) { log.append(msg).append("\n"); android.util.Log.i("TrackSync", msg); onProgress?.invoke(msg) }
            L("=== sync start: ${gpxFiles.size} gpx files ===")

            for (file in gpxFiles) {
                try {
                    val text = file.readText()
                    val coords = parseGpxTrackPoints(text)
                    if (coords.isEmpty()) {
                        L("IGNORE no-geometry: ${file.name} (${file.length()} bytes, 0 pts)")
                        continue
                    }
                    var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                    var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                    for ((lon, lat) in coords) {
                        if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                        if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                    }
                    val wkt = "LINESTRING(" + coords.joinToString(",") { "${it.first} ${it.second}" } + ")"
                    val gh = computeGeomHash(wkt)   // THE KEY (computed from geometry, not the filename)

                    processed++

                    val nameMatch = Regex("<name>([^<]*)</name>").find(text)?.groupValues?.get(1)?.trim()
                    val recName = if (!nameMatch.isNullOrBlank()) nameMatch else file.nameWithoutExtension
                    android.util.Log.i("HASHTRACE", "LOOP file=${file.name} loopPts=${coords.size} loopHash=${gh.take(16)} embName='${nameMatch ?: "?"}' recName='$recName'")
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
                    L("ERROR: ${file.name} : ${e.message}")
                }
            }

            // metric feed: one guarded, SEQUENTIAL sweep -- writes track_properties for
            // every track (synced + backfills any existing tracks that never had them).
            val bf = backfillAllTrackProperties { line -> L(line) }
            syncPropsWritten = bf.written; syncPropsTotal = bf.total; syncFailures = bf.failures
            L("=== properties: ${bf.written}/${bf.total} written, ${bf.failures.size} failed ===")
            L("=== sync complete: processed=$processed skipped=$skipped added/renamed=$addedRenamed renamed=$renamed ===")
            try { logFile.parentFile?.mkdirs(); logFile.writeText(log.toString()) } catch (e: Exception) { android.util.Log.e("TrackSync","log write failed: ${e.message}") }
        } catch (e: Exception) {
            android.util.Log.e("TrackSync", "Sync failed: ${e.message}")
        }
        } finally {
            syncRunning.set(false)
        }
        return TrackSyncResult(processed, skipped, addedRenamed, renamed, addedNames,
            syncPropsWritten, syncPropsTotal, syncFailures)
    }

    /** Parse GPX file for track points, returns list of (lon, lat) pairs */
    /** Parse GPX track points — used by ConvoyTrackOps for import */
    fun parseGpxTrackPoints(gpxText: String): List<Pair<Double, Double>> = com.grouptrack.shared.spatial.GtRead.parseGpxTrackPoints(gpxText)   // STEP7-2026-11-07: shared code

    /** Count rows in a table */
    private fun countRows(db: SQLiteDatabase, table: String): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $table", null)
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count
    }



    /** Query waypoints by viewport bounding box */
    fun queryWaypointsByViewport(south: Double, west: Double, north: Double, east: Double, limit: Int = 500): List<Map<String, String?>> {
        val db = spatialDb ?: return emptyList()
        val results = mutableListOf<Map<String, String?>>()
        try {
            // For POINT geometry, min_lat==max_lat, min_lon==max_lon
            // But we query the same bbox pattern for consistency
            val cursor = db.rawQuery(
                "SELECT waypoint_id, name, geometry, type FROM waypoints WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?",
                arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val wkt = it.getString(2) ?: continue
                    results.add(mapOf(
                        "waypoint_id" to it.getString(0),
                        "name" to it.getString(1),
                        "geometry" to wkt,
                        "type" to (it.getString(3) ?: "other")
                    ))
                }
            }
            android.util.Log.i("WaypointLazy", "Viewport query returned ${results.size} waypoints")
        } catch (e: Exception) {
            android.util.Log.e("WaypointLazy", "Viewport query failed: ${e.message}")
        }
        return results
    }

    /** Build GeoJSON FeatureCollection from waypoint query results (Point geometry) */
    fun buildWaypointGeoJson(waypoints: List<Map<String, String?>>): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        waypoints.forEachIndexed { idx, wpt ->
            if (idx > 0) sb.append(",")
            val geom = wpt["geometry"] ?: return@forEachIndexed
            val name = jsonEsc(wpt["name"] ?: "Unnamed")
            val wptType = jsonEsc(wpt["type"] ?: "other")
            // Parse POINT(lon lat)
            val match = Regex("POINT\\(([\\d.\\-]+) ([\\d.\\-]+)\\)").find(geom)
            if (match != null) {
                val lon = match.groupValues[1]
                val lat = match.groupValues[2]
                // WPTTAP-2026-09-30 (Fred): the id a tap sends to the detail panel (the query selects it; it was dropped here).
                val wid = jsonEsc(wpt["waypoint_id"] ?: "")
                sb.append("{\"type\":\"Feature\",\"properties\":{\"waypoint_id\":\"$wid\",\"name\":\"$name\",\"wpt_type\":\"$wptType\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[$lon,$lat]}}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }


    /** Query routes by viewport bounding box (dedicated routes table) */
    fun queryRoutesByViewport(south: Double, west: Double, north: Double, east: Double, limit: Int = 500): List<Map<String, String?>> {
        val db = spatialDb ?: return emptyList()
        val results = mutableListOf<Map<String, String?>>()
        try {
            val cursor = db.rawQuery(
                "SELECT route_id, name, geometry FROM routes WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?",
                arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val wkt = it.getString(2) ?: continue
                    results.add(mapOf(
                        "route_id" to it.getString(0),
                        "name" to it.getString(1),
                        "geometry" to wkt
                    ))
                }
            }
            android.util.Log.i("RouteLazy", "Viewport query returned ${results.size} routes")
        } catch (e: Exception) {
            android.util.Log.e("RouteLazy", "Viewport query failed: ${e.message}")
        }
        return results
    }

    /** Build GeoJSON FeatureCollection from route query results */
    fun buildRouteGeoJson(routes: List<Map<String, String?>>): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        routes.forEachIndexed { idx, route ->
            if (idx > 0) sb.append(",")
            val geom = route["geometry"] ?: return@forEachIndexed
            val name = jsonEsc(route["name"] ?: "Unnamed Route")
            val coordStr = geom.removePrefix("LINESTRING(").removeSuffix(")")
            val coords = coordStr.split(",").joinToString(",") { pair ->
                val parts = pair.trim().split(" ")
                if (parts.size >= 2) "[${parts[0]},${parts[1]}]" else "[0,0]"
            }
            // ROUTETAP-2026-08-23Z: emit route_id. It was in the row map all along
            // (queryRoutesByViewport returns it) but never reached properties, so
            // the JS had nothing to hand back on a tap. Tracks already carry theirs.
            val rid = jsonEsc(route["route_id"] ?: "")
            sb.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"$name\",\"route_id\":\"$rid\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Update queryTracksByViewport to exclude routes (only type='TRACK' or no type) */
    fun queryTracksOnlyByViewport(south: Double, west: Double, north: Double, east: Double, limit: Int = 500): List<Map<String, String?>> {
        val db = spatialDb ?: return emptyList()
        val results = mutableListOf<Map<String, String?>>()
        try {
            val cursor = db.rawQuery(
                "SELECT track_id, name, geometry FROM tracks WHERE (type='TRACK' OR type IS NULL) AND min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?",
                arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val wkt = it.getString(2) ?: continue
                    results.add(mapOf(
                        "track_id" to it.getString(0),
                        "name" to it.getString(1),
                        "geometry" to wkt
                    ))
                }
            }
            android.util.Log.i("TrackLazy", "Tracks-only viewport query returned ${results.size} tracks")
        } catch (e: Exception) {
            android.util.Log.e("TrackLazy", "Tracks-only viewport query failed: ${e.message}")
        }
        return results
    }

    
    // ── INSERT METHODS (for GPX import) ────────────────────────────

    /**
     * Insert a waypoint into the spatial DB.
     * Returns the generated waypoint_id.
     */
    /** Insert waypoint with a specific ID (for deterministic dedup on reimport). */
    // STEP11-2026-11-07: the WRITES run on the shared GtWrite -- on the app's OWN handles (GtAppDb), so a write inside an import's
    // transaction stays in that transaction (no second connection, no lock waits). One writer per pair of handles (a new pair -- the
    // cert run's copies, a reinit -- makes a new one); its dedup session is the running import's memory of what it has added.
    private val writeLock = Any()
    private var writerObj: com.grouptrack.shared.spatial.GtWrite? = null
    private var writerFor: Pair<android.database.sqlite.SQLiteDatabase?, android.database.sqlite.SQLiteDatabase?>? = null
    private fun writer(): com.grouptrack.shared.spatial.GtWrite? = synchronized(writeLock) {
        val s = spatialDb ?: return@synchronized null
        val e = extensionDb
        if (writerObj == null || writerFor?.first !== s || writerFor?.second !== e) {
            writerObj = com.grouptrack.shared.spatial.GtWrite(com.geeksville.mesh.grouptrack.GtAppDb.wrap(s), e?.let { com.geeksville.mesh.grouptrack.GtAppDb.wrap(it) }, { newId() }, { now() })
            writerFor = s to e
            android.util.Log.i("SpatialDb", "STEP11 shared writer on the app's handles")
        }
        writerObj
    }

    fun insertWaypointWithId(waypointId: String, name: String, lat: Double, lon: Double, type: String): Boolean =
        writer()?.insertWaypointWithId(waypointId, name, lat, lon, type) ?: false   // STEP11

        fun insertWaypoint(name: String, lat: Double, lon: Double, type: String = "other"): String {
        val db = spatialDb ?: throw IllegalStateException("SpatialDbManager not initialized")
        val id = newId()
        val ts = now()
        val geometry = pointWkt(lat, lon)
        val nm = notNamed(name)
        val gh = computeGeomHash(geometry)
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO waypoints (waypoint_id, name, geometry, type, min_lat, max_lat, min_lon, max_lon, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(id, nm, geometry, type.lowercase(), lat, lat, lon, lon, ts, ts, gh)
            )
            android.util.Log.i("SpatialDb", "Inserted waypoint: $nm ($type) at $lat,$lon")
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "Waypoint insert failed: ${e.message}")
            // If column mismatch, try minimal insert
            try {
                db.execSQL(
                    "INSERT OR IGNORE INTO waypoints (waypoint_id, name, geometry, type, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?)",
                    arrayOf(id, nm, geometry, type.lowercase(), ts, ts, gh)
                )
                // Update bbox columns separately (may have been added by migration)
                db.execSQL("UPDATE waypoints SET min_lat=?, max_lat=?, min_lon=?, max_lon=? WHERE waypoint_id=?",
                    arrayOf<Any>(lat, lat, lon, lon, id))
            } catch (e2: Exception) {
                android.util.Log.e("SpatialDb", "Waypoint fallback insert also failed: ${e2.message}")
            }
        }
        return id
    }

    /**
     * Insert a route into the spatial DB (dedicated routes table).
     * Returns the generated route_id.
     */
    /** TRACKROUTE-2026-09-26: a track's recorded line (WKT) by id; null when the track is absent. */
    fun trackGeometry(trackId: String): String? = withLookups(null) { it.trackGeometry(trackId) }   // STEP7-2026-11-07: shared GtRead

    /** TRACKDESC-2026-09-26: a track's name by id; null when the track is absent. */
    fun trackName(trackId: String): String? = withLookups(null) { it.trackName(trackId) }   // STEP7-2026-11-07: shared GtRead

    fun insertRoute(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): String =
        (writer() ?: throw IllegalStateException("SpatialDbManager not initialized")).insertRoute(name, geometryWkt, minLat, maxLat, minLon, maxLon)   // STEP11

    /**
     * ROUTENOTES-2026-08-23X: move a draft's `notes` block into route_notes.
     *
     * The narrative is stored as ONE row of kind='narrative' carrying the whole
     * JSON in `payload`, plus a row per feature and per caution so they can be
     * queried by position later. ⭐ The payload is kept VERBATIM: the panel's
     * builder already knows how to render it, and a shape change in the
     * generator must not need a schema change here.
     *
     * ⚠ author='generated'. The same table carries RIDER observations later --
     * the 3.0 work -- and "history is information, not current fact" means those
     * are dated rows, never a replacement for these.
     *
     * Returns rows written, or -1 if the route does not exist.
     */
    fun writeRouteNotes(routeId: String, notes: org.json.JSONObject): Int {   // STEP11
        if (extensionDb == null) return -1
        return writer()?.writeRouteNotes(routeId, notes.toString()) ?: -1
    }

    /* RECIPEBTN-2026-08-29: does this route carry a recipe?
     *
     * ⭐ The button is SHOWN OR NOT — Fred: "just check for the existence in
     * the route db before showing button." A route drawn by hand or imported
     * has no recipe and simply offers nothing.
     */
    fun routeRecipe(routeId: String): org.json.JSONObject? =
        readRouteNotes(routeId)?.optJSONObject("recipe")

    /* RECIPEBTN-2026-08-29: the waypoint at these coordinates, by name.
     *
     * ⭐ EXACT, NOT NEAREST. The recipe's anchor is the waypoint's own
     * coordinates, stored when the rider tapped it — so this resolves the name
     * for every recipe already written, including the ones whose anchorName
     * was never captured.
     *
     * ⚠ A tiny box rather than equality: the stored value is a double and the
     * geometry is text, so a hair of tolerance costs nothing and equality
     * would be brittle.
     */
    fun waypointNameAt(lat: Double, lon: Double): String? = withLookups(null) { it.waypointNameAt(lat, lon) }   // STEP7-2026-11-07: shared GtRead

    /** ROUTENOTES-2026-08-23X: the narrative payload for a saved route, or null. */
    fun readRouteNotes(routeId: String): org.json.JSONObject? {
        val db = extensionDb ?: return null
        return try {
            db.rawQuery("SELECT payload FROM route_notes WHERE route_id=? AND kind='narrative' LIMIT 1",
                arrayOf(routeId)).use { c ->
                if (c.moveToNext()) org.json.JSONObject(c.getString(0)) else null
            }
        } catch (e: Exception) {
            android.util.Log.w("SpatialDb", "readRouteNotes: ${e.message}"); null
        }
    }

    /**
     * ROUTENOTES-2026-08-23X: notes die with the route.
     * ⚠ MUST BE CALLED FROM APP CODE. Cross-database triggers do not work on
     * this project -- the spatial DB cannot cascade into the extension DB.
     */
    fun deleteRouteNotes(routeId: String): Int {   // STEP11
        if (extensionDb == null) return 0
        return writer()?.deleteRouteNotes(routeId) ?: 0
    }

    /** ROUTENOTES-2026-08-23X: geom_hash for a route, or null if it does not exist. */
    /**
     * ROUTENOTESIMPORT-2026-09-08: the route id for a geometry hash, or null.
     *
     * ⛔ WHY IT IS NEEDED. insertRoute is INSERT OR IGNORE and returns the id it
     * MINTED -- on a duplicate geometry the row is dropped and that id refers
     * to nothing. Anything that must attach to the row actually in the table
     * has to resolve it by hash instead, exactly as findTrailIdByHash does for
     * trails.
     */
    fun findRouteIdByHash(geomHash: String): String? = withLookups(null) { it.findRouteIdByHash(geomHash) }   // STEP7-2026-11-07: shared GtRead

    fun routeGeomHash(routeId: String): String? = withLookups(null) { it.routeGeomHash(routeId) }   // STEP7-2026-11-07: shared GtRead

    /**
     * True if a COMPLETED route with this name already exists in the routes table.
     * Case-insensitive, trimmed. Used by RouteDraftStore.isNameTaken to enforce the
     * demand-unique-name rule across drafts + routes DB.
     */
    /**
     * ROUTESEQ-2026-09-04: how many saved routes already start with [base]?
     *
     * ⭐ Testers type ONE name and save SEVERAL routes from a batch, so the
     * saved name gets a sequence number. Counting what is ALREADY SAVED rather
     * than numbering within the batch means three today are 1..3 and two more
     * next week are 4 and 5 -- no collision with anything on the device.
     *
     * ⚠ LIKE, not equality: the stored names carry the number, so
     * "Broken Ridge Route 2" must count against a base of "Broken Ridge Route".
     * ⚠ The base is escaped for LIKE -- a name containing % or _ would
     * otherwise match far more than it should, and rider-typed names can
     * contain anything.
     */
    fun routeNameCount(base: String): Int {
        val db = spatialDb ?: return 0
        val needle = base.trim()
        if (needle.isEmpty()) return 0
        return try {
            val esc = needle.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_")
            var n = 0
            db.rawQuery(
                "SELECT COUNT(*) FROM routes WHERE name IS NOT NULL " +
                    "AND lower(trim(name)) LIKE lower(?) ESCAPE '\\'",
                arrayOf("$esc%")
            ).use { c -> if (c.moveToFirst()) n = c.getInt(0) }
            n
        } catch (e: Exception) {
            android.util.Log.w("SpatialDb", "routeNameCount: ${e.message}")
            0
        }
    }

    fun routeNameExists(name: String): Boolean = withLookups(false) { it.routeNameExists(name) }   // STEP7-2026-11-07: shared GtRead

    /**
     * Insert a track into the spatial DB (type='TRACK').
     * Used by import to write directly to DB alongside the GPX file.
     * Returns the generated track_id.
     */
    // CHANGED 2026-06-02: returns Boolean (true=row inserted, false=dropped as dupe via
    // INSERT OR IGNORE on UNIQUE(geom_hash)). Detected with changes(). Enables real import recap.
    /** Result of a track insert attempt. Returned on the caller's stack
     *  (per-thread, race-free) so the add resolver can branch without a
     *  second lookup. On a dupe, trackId is the EXISTING row's id. */
    data class TrackInsertResult(
        val wasNew: Boolean,
        val trackId: String,
        val geomHash: String,
        val name: String
    )

    fun insertTrackToDb(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): TrackInsertResult {   // STEP11
        val r = (writer() ?: throw IllegalStateException("SpatialDbManager not initialized")).insertTrackToDb(name, geometryWkt, minLat, maxLat, minLon, maxLon)
        return TrackInsertResult(r.wasNew, r.trackId, r.geomHash, r.name)
    }

    /** CHECKIN-2026-09-27: the id of the track a GPX text resolves to -- the SAME parse, WKT and computeGeomHash as
     *  resolveTrackAdd, then a lookup by geom_hash. Works for INSERT and for DROP/ALIAS (the existing track).
     *  CODE RULE 1: null when there is no geometry or no such track. */
    fun trackIdForGpxText(text: String): String? = withLookups(null) { it.trackIdForGpxText(text) }   // STEP7-2026-11-07: shared GtRead

    enum class AddOutcome { INSERT, DROP_NAME, DROP_ALIAS, ALIAS, NO_GEOMETRY, ERROR }

    /** THE unified track ADD service. All three capture processes (sync, create,
     *  import) call this with a name and a file that ALREADY EXISTS on disk.
     *  Rereads the file (single source of truth), parses, inserts, and resolves:
     *    INSERT     new hash      -> materialize <hash>.gpx, write metrics, keep file
     *    DROP_NAME  name==official-> delete source file
     *    DROP_ALIAS name==alias   -> delete source file
     *    ALIAS      new name      -> addAlias(existingId,name) + delete source file
     *  Identical behavior for all callers; no caller-side insert/rename/delete. */
    fun resolveTrackAdd(name: String, sourceFile: java.io.File): AddOutcome {
        try {
            if (!sourceFile.exists()) {
                android.util.Log.w("TrackAdd", "source missing: ${sourceFile.name}")
                return AddOutcome.ERROR
            }
            val gpxText = sourceFile.readText()
            val coords = parseGpxTrackPoints(gpxText)   // List<Pair<lon,lat>>
            if (coords.isEmpty()) {
                android.util.Log.w("TrackAdd", "no geometry: ${sourceFile.name}")
                return AddOutcome.NO_GEOMETRY
            }
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            for (p in coords) {
                val lon = p.first; val lat = p.second
                if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
            }
            val wkt = "LINESTRING(" + coords.joinToString(",") { "${it.first} ${it.second}" } + ")"
            val _embName = Regex("<name>([^<]*)</name>").find(gpxText)?.groupValues?.get(1)?.trim() ?: "?"
            android.util.Log.i("HASHTRACE", "RESOLVE file=${sourceFile.name} passedName='$name' embeddedName='$_embName' pts=${coords.size} first=(${coords.firstOrNull()}) last=(${coords.lastOrNull()}) wktLen=${wkt.length} hash=${computeGeomHash(wkt).take(16)}")
            val res = insertTrackToDb(name, wkt, minLat, maxLat, minLon, maxLon)
            // MYTRACKSDB-2026-09-12: ⚠ inner replaced, outer kept -- it is
            // what joins the directory to the filename.
            val hashFile = java.io.File(
                ConvoyTrackOps.tracksDir(),
                "${res.geomHash}.gpx"
            )

            if (res.wasNew) {
                // INSERT: make the canonical <hash>.gpx exist, then write metrics.
                try {
                    if (sourceFile.absolutePath != hashFile.absolutePath) {
                        if (!hashFile.exists()) {
                            if (!sourceFile.renameTo(hashFile)) sourceFile.copyTo(hashFile, overwrite = false)
                        }
                    }
                } catch (e: Exception) { android.util.Log.w("TrackAdd", "materialize: ${e.message}") }
                updateTrackPropertiesForHash(res.geomHash)
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
                    val rt = RiderTrailWriter.scanTrackByHash(res.geomHash)
                    if (rt.first > 0) android.util.Log.i("TrackAdd",
                        "rider trails: ${rt.first} from '$name'")
                } catch (e: Exception) {
                    android.util.Log.w("TrackAdd", "rider trails: ${e.message}")
                }
                android.util.Log.i("TrackAdd", "INSERT ${res.geomHash.take(12)} '$name'")
                return AddOutcome.INSERT
            }

            // hash exists -> DROP_NAME / DROP_ALIAS / ALIAS. All delete the source file.
            val officialName = run {
                var n: String? = null
                spatialDb?.rawQuery("SELECT name FROM tracks WHERE track_id=? LIMIT 1", arrayOf(res.trackId))?.use {
                    if (it.moveToFirst()) n = if (it.isNull(0)) null else it.getString(0)
                }
                n
            }
            val nameMatchesOfficial = officialName != null && officialName.equals(name.trim(), ignoreCase = false)
            var nameMatchesAlias = false
            if (!nameMatchesOfficial) {
                extensionDb?.rawQuery(
                    "SELECT 1 FROM artifact_aliases WHERE artifact_type='track' AND artifact_id=? AND alias=? LIMIT 1",
                    arrayOf(res.trackId, name.trim())
                )?.use { if (it.moveToFirst()) nameMatchesAlias = true }
            }

            val deleteSource = {
                try {
                    if (sourceFile.absolutePath != hashFile.absolutePath && sourceFile.exists()) sourceFile.delete()
                } catch (e: Exception) { android.util.Log.w("TrackAdd", "del source: ${e.message}") }
            }

            return when {
                nameMatchesOfficial -> { deleteSource(); android.util.Log.i("TrackAdd", "DROP_NAME ${res.geomHash.take(12)}"); AddOutcome.DROP_NAME }
                nameMatchesAlias    -> { deleteSource(); android.util.Log.i("TrackAdd", "DROP_ALIAS ${res.geomHash.take(12)}"); AddOutcome.DROP_ALIAS }
                else -> {
                    addAlias("track", res.trackId, name.trim(), res.geomHash, null)
                    deleteSource()
                    android.util.Log.i("TrackAdd", "ALIAS ${res.geomHash.take(12)} '$name'")
                    AddOutcome.ALIAS
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TrackAdd", "resolveTrackAdd failed: ${e.message}")
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

    private val propertiesBackfillRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One trackpoint with optional elevation (meters) + time (epoch millis). */
    data class TrackPtFull(val lat: Double, val lon: Double, val eleM: Double?, val timeMs: Long?)

    /** Richer GPX parse: lat, lon, <ele> (m), <time> (ISO 8601 'yyyy-MM-dd'T'HH:mm:ss'Z'').
     *  Existing parseGpxTrackPoints (lon,lat only, used for WKT/hash) is untouched. */
    fun parseGpxTrackPointsFull(gpxText: String): List<TrackPtFull> =   // STEP15B-SWITCH-2026-11-09: the certified shared parser (15b, RXFIX)
        com.grouptrack.shared.imports.GtGpxParse.parseGpxTrackPointsFull(gpxText).map { TrackPtFull(it.lat, it.lon, it.eleM, it.timeMs) }

    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 3958.7613
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    /** Derive + upsert track_properties for the track with this geom_hash.
     *  Reads <hash>.gpx from my_tracks. Writes only GPX-derived columns; never
     *  touches shared/ride_id/area_id. Returns true on a successful write. */
    fun updateTrackPropertiesForHash(geomHash: String, onProgress: ((String) -> Unit)? = null): Boolean {
        val h12 = geomHash.take(12)
        val sdb = spatialDb ?: return false
        val edb = extensionDb ?: return false

        var trackId: String? = null
        var trackName = "?"
        try {
            sdb.rawQuery("SELECT track_id, name FROM tracks WHERE geom_hash=? LIMIT 1", arrayOf(geomHash)).use { c ->
                if (c.moveToFirst()) { trackId = c.getString(0); trackName = c.getString(1) ?: "?" }
            }
        } catch (e: Exception) {
            android.util.Log.e("TrackProps", "track_id lookup failed for $h12: ${e.message}")
            onProgress?.invoke("FAIL: ? · $h12 — DB lookup error")
            return false
        }
        val tid = trackId ?: run {
            android.util.Log.w("TrackProps", "no spatial row for hash $h12")
            onProgress?.invoke("FAIL: ? · $h12 — no DB row")
            return false
        }

        val tracksDir = ConvoyTrackOps.tracksDir()   // MYTRACKSDB-2026-09-12
        val gpxFile = java.io.File(tracksDir, "$geomHash.gpx")
        if (!gpxFile.exists()) {
            android.util.Log.w("TrackProps", "file missing: ${gpxFile.name} (track_id=$tid)")
            onProgress?.invoke("FAIL: $trackName · $h12 — file missing")
            return false
        }

        val pts = try { parseGpxTrackPointsFull(gpxFile.readText()) } catch (e: Exception) {
            android.util.Log.e("TrackProps", "parse failed ${gpxFile.name}: ${e.message}")
            onProgress?.invoke("FAIL: $trackName · $h12 — parse failed"); return false
        }
        if (pts.isEmpty()) {
            android.util.Log.w("TrackProps", "no points in ${gpxFile.name}"); return false
        }

        var distMiles = 0.0
        var maxMph = 0.0
        var eleGainFt = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val seg = haversineMiles(a.lat, a.lon, b.lat, b.lon)
            distMiles += seg
            if (a.timeMs != null && b.timeMs != null) {
                val dtHr = (b.timeMs - a.timeMs) / 3_600_000.0
                if (dtHr > 0) { val mph = seg / dtHr; if (mph > maxMph && mph < 200) maxMph = mph }
            }
            if (a.eleM != null && b.eleM != null) {
                val d = b.eleM - a.eleM; if (d > 0) eleGainFt += d * 3.280839895
            }
        }
        val firstT = pts.firstOrNull { it.timeMs != null }?.timeMs
        val lastT = pts.lastOrNull { it.timeMs != null }?.timeMs
        val durMin: Int? = if (firstT != null && lastT != null && lastT > firstT)
            ((lastT - firstT) / 60_000L).toInt() else null
        val avgMph: Double? = if (durMin != null && durMin > 0) distMiles / (durMin / 60.0) else null
        val recordedAt: String? = firstT?.let {
            val s = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            s.timeZone = java.util.TimeZone.getTimeZone("UTC"); s.format(java.util.Date(it))
        }

        return try {
            edb.execSQL("INSERT OR IGNORE INTO track_properties (track_id) VALUES (?)", arrayOf<Any>(tid))
            edb.execSQL(
                "UPDATE track_properties SET filename=?, source_format=?, recorded_at=?, " +
                "distance_miles=?, duration_minutes=?, max_speed_mph=?, avg_speed_mph=?, " +
                "elevation_gain_ft=?, point_count=? WHERE track_id=?",
                arrayOf<Any?>(
                    "$geomHash.gpx", "gpx", recordedAt,
                    distMiles, durMin, maxMph, avgMph,
                    Math.round(eleGainFt).toInt(), pts.size, tid
                )
            )
            android.util.Log.i("TrackProps",
                "props track_id=$tid dist=${"%.2f".format(distMiles)}mi dur=${durMin}min avg=${avgMph?.let { "%.1f".format(it) }} pts=${pts.size}")
            onProgress?.invoke("props: $trackName · $h12 — ${"%.1f".format(distMiles)}mi ${pts.size}pts")
            true
        } catch (e: Exception) {
            android.util.Log.e("TrackProps", "upsert failed track_id=$tid: ${e.message}")
            false
        }
        // [PHASE C SEAM] refresh track_surveys for $tid here once local survey
        // capture exists (save-fix to follow). [PHASE 3 SEAM] AWS upload deferred.
    }

    /** SEQUENTIAL, single-flight backfill of track_properties for every track.
     *  One GPX read at a time -- deliberately NOT parallel (avoids the sync storm
     *  pattern). Idempotent: metrics are a pure function of the immutable GPX. */
    fun backfillAllTrackProperties(onProgress: ((String) -> Unit)? = null): BackfillResult {
        if (!propertiesBackfillRunning.compareAndSet(false, true)) {
            android.util.Log.w("TrackProps", "backfill already running -- skipping concurrent call")
            onProgress?.invoke("⚠ properties pass already running — skipped")
            return BackfillResult(0, 0, emptyList())
        }
        try {
            val sdb = spatialDb ?: return BackfillResult(0, 0, emptyList())
            val hashes = mutableListOf<String>()
            sdb.rawQuery("SELECT geom_hash FROM tracks WHERE geom_hash IS NOT NULL", null).use { c ->
                while (c.moveToNext()) c.getString(0)?.let { hashes.add(it) }
            }
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
            android.util.Log.i("TrackProps", "backfill complete: $written/${hashes.size} written, ${failures.size} failed")
            onProgress?.invoke("— properties done: $written/${hashes.size} written, ${failures.size} failed —")
            return BackfillResult(written, hashes.size, failures)
        } finally {
            propertiesBackfillRunning.set(false)
        }
    }

    // ===================================================================
    // SHARED ARTIFACT ADD CORE  (ADD-RULES CONTRACT -- see schema_spatial_v3.sql)
    // All four artifact types funnel through this core. It computes geom_hash,
    // applies the 'Not Named' fallback, and makes the dupe/alias/insert decision.
    // A null geom_hash reaching the DB means a caller bypassed this core
    // (geom_hash is NOT NULL in-schema -> such an insert FAILS loudly).
    // ===================================================================

    /** SHA-256 of the full WKT geometry string. Raw (no normalization): the
     *  duplicate rows in real data are byte-identical WKT, so a raw hash catches
     *  them. Identity = (artifact_type, geom_hash); per-type tables make
     *  UNIQUE(geom_hash) express that. */
    /** ROUTENUM-2026-10-28 (D20): the next number for "<base> N" -- the highest N among routes AND route aliases named exactly
     *  "<base> <digits>" (case-insensitive), plus 1. Was a count of names STARTING WITH base ("broken ridge" counted
     *  "Broken Ridge 2024 Jamboree"), and a count repeats numbers after a delete. */
    fun nextRouteNumber(base: String): Int = withLookups(1) { it.nextRouteNumber(base) }   // STEP7-2026-11-07: shared GtRead

    /** ROUTENAME-2026-10-28 (D11): a route's name by id. CODE RULE 1: null = no such route (deleted). */
    fun routeNameById(routeId: String): String? = withLookups(null) { it.routeNameById(routeId) }   // STEP7-2026-11-07: shared GtRead

    /** ROUTEDEL-2026-10-28 (D21): a deleted route's other names go with it. */
    fun deleteRouteAliases(routeId: String) { writer()?.deleteRouteAliases(routeId) }   // STEP11

    fun computeGeomHash(wkt: String): String = com.grouptrack.shared.spatial.GtRead.computeGeomHash(wkt)   // STEP7-2026-11-07: shared code

    /** 'Not Named' fallback, applied for ALL four artifacts. */
    fun notNamed(name: String?): String = if (name.isNullOrBlank()) "Not Named" else name

    // ---- session-scoped in-memory dedup (loaded once per import) ----
    // One geom_hash->name lookup per artifact type. Plus a source-uid set for trails
    // (replaces the per-row SELECT 1 FROM trail_properties query).
    private val dedupHashToName = HashMap<String, HashMap<String, String>>()
    private val dedupSourceUids = HashSet<String>()
    private var dedupSessionActive = false

    /** Load the lookups once. Call at the start of an import; release with endDedupSession(). */
    fun beginDedupSession() { writer()?.beginDedupSession() }   // STEP11: the import's memory lives in the shared writer

    fun endDedupSession() { writer()?.endDedupSession() }   // STEP11

    private fun loadHashes(db: android.database.sqlite.SQLiteDatabase, table: String, idCol: String, type: String) {
        try {
            val c = db.rawQuery("SELECT geom_hash, name FROM $table WHERE geom_hash IS NOT NULL", null)
            val m = dedupHashToName[type]!!
            c.use { while (it.moveToNext()) m[it.getString(0)] = it.getString(1) ?: "Not Named" }
        } catch (ex: Exception) { android.util.Log.w("SpatialDb", "loadHashes($table): ${ex.message}") }
    }

    /** Has this (source_id, source_unique_id) already been imported? In-memory. */
    fun sourceUidSeen(sourceId: String, uid: String): Boolean = writer()?.sourceUidSeen(sourceId, uid) ?: false   // STEP11

    fun markSourceUid(sourceId: String, uid: String) { writer()?.markSourceUid(sourceId, uid) }   // STEP11

    enum class AddDecision { INSERT, DROP, ALIAS }

    /** The dupe/alias decision for trail/route/waypoint (geometry-name rule).
     *  Tracks use their own three-layer rule (see insertTrackToDb). */
    fun resolveByGeom(type: String, name: String, geomHash: String): AddDecision =   // STEP11
        AddDecision.valueOf((writer()?.resolveByGeom(type, name, geomHash) ?: com.grouptrack.shared.spatial.GtWrite.Decision.INSERT).name)

    /** DUPECHECK-2026-10-20: the name stored for this (type, geom_hash) -- one indexed lookup (UNIQUE(geom_hash) per type table).
     *  CODE RULE 1: null = no such geometry of this type (new), or the database is not open / the type is unknown. */
    private fun nameByHash(type: String, geomHash: String): String? {
        val db = spatialDb ?: return null
        val table = spatialTableFor(type)?.first ?: return null
        return try {
            db.rawQuery("SELECT name FROM $table WHERE geom_hash=? LIMIT 1", arrayOf(geomHash)).use { c ->
                if (c.moveToFirst()) (if (c.isNull(0)) "Not Named" else c.getString(0)) else null
            }
        } catch (ex: Exception) { android.util.Log.w("SpatialDb", "DUPECHECK-2026-10-20 nameByHash($table): ${ex.message}"); null }
    }

    /** Record a freshly-inserted artifact in the in-memory lookup. */
    fun noteInserted(type: String, geomHash: String, name: String) { writer()?.noteInserted(type, geomHash, name) }   // STEP11

    /** Pointer-model alias write. Hash lives on the artifact; alias carries the name.
     *  INSERT OR IGNORE -> alias-table UNIQUE constraints dedup silently. */
    // ── [2h] DETAIL-CARD READERS (spatial = full-data source; data-DB = aliases only) ──

    // type is singular: "trail"/"track"/"waypoint"/"route"
    private fun spatialTableFor(type: String): Pair<String, String>? = when (type.lowercase()) {
        "trail", "trails"       -> "trails" to "trail_id"
        "track", "tracks"       -> "tracks" to "track_id"
        "waypoint", "waypoints" -> "waypoints" to "waypoint_id"
        "route", "routes"       -> "routes" to "route_id"
        else -> null
    }

    /** Full-data card: the artifact's SPATIAL row (all displayable fields except the geometry blob).
     *  name=="null"/blank is coerced to "Not Named". Ordered map preserves column order for display. */
    fun getArtifactDetail(type: String, artifactId: String): LinkedHashMap<String, String?> = withLookups(LinkedHashMap()) { it.getArtifactDetail(type, artifactId) }   // STEP7-2026-11-07: shared GtRead

    private fun propertiesTableFor(type: String): Pair<String, String>? = when (type.lowercase()) {
        "trail", "trails"       -> "trail_properties" to "trail_id"
        "track", "tracks"       -> "track_properties" to "track_id"
        "waypoint", "waypoints" -> "waypoint_properties" to "waypoint_id"
        "route", "routes"       -> "route_properties" to "route_id"
        else -> null
    }

    /** Alias accordion: rows from data-DB artifact_aliases, preferred-first. artifact_type is singular. */
    fun getAliasesFor(type: String, artifactId: String): List<Map<String, String?>> = withLookups(emptyList()) { it.getAliasesFor(type, artifactId) }   // STEP7-2026-11-07: shared GtRead

    /** Star an alias: set is_preferred=1 on aliasId and clear all siblings for the same artifact,
     *  in one transaction (no db-level one-preferred constraint — enforced here). */
    /** SWAP: promote an alias to the OFFICIAL local name and demote the old
     *  official name into that alias row. "Make this my preferred name."
     *  LOCAL-ONLY -- the AWS canonical (first-identified) name is never touched.
     *  GUARDED: trails are externally sourced / not user-maintained -> no swap.
     *  Generic across user types via spatialTableFor(). Two separate DB files,
     *  so ops are ordered + logged (no shared transaction). */
    fun setPreferredAlias(type: String, artifactId: String, aliasId: String) { writer()?.setPreferredAlias(type, artifactId, aliasId) }   // STEP11

    /** Edit one alias row's text. One-at-a-time alias maintenance. */
    fun renameAlias(aliasId: String, newText: String) { writer()?.renameAlias(aliasId, newText) }   // STEP11

    /** Delete one alias row. UI enforces the min-one guard before calling. */
    fun deleteAlias(aliasId: String) { writer()?.deleteAlias(aliasId) }   // STEP11

    fun addAlias(type: String, artifactId: String, name: String, geomHash: String, creationDate: String?) {
        writer()?.addAlias(type, artifactId, name, geomHash, creationDate)   // STEP11
    }

    /** Trail insert routed through the core (TrailImporter calls this).
     *  Returns the trail_id used (existing id if aliased/dropped, new id if inserted),
     *  or null on drop with no existing id available. */
    fun insertTrail(trailId: String, rawName: String?, wkt: String, minLat: Double, maxLat: Double,
                    minLon: Double, maxLon: Double, createdAt: String): Pair<String, AddDecision> {   // STEP11
        val (id, d) = (writer() ?: throw IllegalStateException("SpatialDbManager not initialized")).insertTrail(trailId, rawName, wkt, minLat, maxLat, minLon, maxLon, createdAt)
        return id to AddDecision.valueOf(d.name)
    }

    private fun findTrailIdByHash(geomHash: String): String? {
        val db = spatialDb ?: return null
        return try {
            val c = db.rawQuery("SELECT trail_id FROM trails WHERE geom_hash=? LIMIT 1", arrayOf(geomHash))
            c.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (ex: Exception) { null }
    }

    /** Get bounding box of all items in a table. Returns [south, west, north, east] or null if empty. */
    fun getArtifactBounds(table: String): DoubleArray? {
        val db = spatialDb ?: return null
        val latCol = if (table == "trails") "min_lat" else "min_lat"
        try {
            val cursor = db.rawQuery(
                "SELECT MIN(min_lat), MIN(min_lon), MAX(max_lat), MAX(max_lon) FROM $table WHERE min_lat IS NOT NULL", null
            )
            cursor.use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    return doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "getArtifactBounds($table) failed: ${e.message}")
        }
        return null
    }
    

    // ── CRUD OPERATIONS (for ArtifactListPanel) ──────────────────

    /** Query artifact list for display in ArtifactListPanel */
    /** PROBESOUT-2026-09-03: kept only because ArtifactListPanel referenced it.
     *  ⚠ Unused now; safe to delete with the next tidy.
     *  (was) LIFECYCLE-2026-09-01: the trail followed from retrieve to display.
     *  ⚠ TEMPORARY -- remove with the probes once the cause is found. */
    const val LIFECYCLE_NAME = "Spanish George"

    fun queryArtifactList(table: String, south: Double, west: Double, north: Double, east: Double): List<Map<String, String?>> = withLookups(emptyList()) { it.queryArtifactList(table, south, west, north, east) }   // STEP7-2026-11-07: shared GtRead

    /**
     * CORRIDORQUERY-2026-08-05: EVERY track, for the corridor picker.
     *
     * Distinct from queryArtifactList above, which is VIEWPORT-BOUNDED and does not
     * return geom_hash, and from searchByName, which needs a term and drops unnamed
     * rows. enqueueCorridor keys on geom_hash, so the picker must carry it per row.
     *
     * NO bbox. NO LIMIT -- "ALL" in the picker has to mean all.
     * Unnamed tracks ARE included (they have geometry and deserve coverage); the
     * picker renders a blank name as "(unnamed)".
     * ORDER BY name COLLATE NOCASE, geom_hash -- names are NOT unique, so a row is a
     * name-occurrence and the picker numbers repeats 1..n off this stable ordering.
     * Type filter matches queryArtifactList: routes share the tracks table.
     *
     * Each row: {id, name, geom_hash}. name may be null or blank.
     */
    fun queryAllTracksForCorridor(): List<Map<String, String?>> = withLookups(emptyList()) { it.queryAllTracksForCorridor() }   // STEP7-2026-11-07: shared GtRead

    /** Rename a waypoint */
    fun renameWaypoint(id: String, newName: String) { writer()?.renameWaypoint(id, newName) }   // STEP11

    /** Delete a waypoint */
    fun deleteWaypoint(id: String) { writer()?.deleteWaypoint(id) }   // STEP11

    /** Change waypoint type */
    fun changeWaypointType(id: String, newType: String) { writer()?.changeWaypointType(id, newType) }   // STEP11

    /** Rename a route */
    fun renameRoute(id: String, newName: String) { writer()?.renameRoute(id, newName) }   // STEP11

    /** Delete a route */
    /** TRACKSFIX-2026-11-03: a route takes its notes and names with it (the shared delete does all three). */
    fun deleteRoute(id: String) { writer()?.deleteRoute(id) }   // STEP11

    /** Rename a track in spatial DB (file rename handled by ConvoyTrackOps) */
    fun renameTrackInDb(id: String, newName: String) { writer()?.renameTrackInDb(id, newName) }   // STEP11

    /** COMPLETE track delete service (detail-panel "delete this track").
     *  Removes ALL of: spatial tracks row, track_properties row, every
     *  artifact_aliases row for the track, and the canonical <hash>.gpx file.
     *  Ordered + logged; each step best-effort (a missing piece is a no-op).
     *  SEPARATE from the add-time dupe/alias source-file cleanup (that lives in
     *  the add resolver and deletes the INCOMING source file, not <hash>.gpx). */
    /** REMOVEALLTRAILS-2026-10-10: what clearAllTrailData removed; error = null when every step worked. */
    data class TrailClearResult(val trails: Int, val properties: Int, val aliases: Int, val error: String?)

    /**
     * REMOVEALLTRAILS-2026-10-10 (Fred): Settings -> "Remove all trail data". The bulk form of
     * deleteTrailFromDb below -- the same three stores, every trail:
     *   spatial   trails
     *   extension trail_properties
     *   extension artifact_aliases (artifact_type = 'trail' only)
     *
     * ⚠ BOTH DATABASES, ALWAYS. The import skips a trail whose (source_id, source_unique_id) is already in
     *   trail_properties (dedupSourceUids), and the map draws a trail from its properties. A properties row
     *   left behind therefore blocks that trail's re-import -- the clean start this exists for would not be clean.
     * ⚠ The two older bulk clears (clearTrailsOnce, jobSchemaConverge) leave the trail aliases behind; this does not.
     * ⚠ Refuses while an import holds its dedup session: its in-memory hashes would describe rows that are gone.
     * Tracks, routes, waypoints, route_notes and source_ingestions are NOT touched.
     */
    @Synchronized
    fun clearAllTrailData(): TrailClearResult {   // STEP11
        val w = writer() ?: return TrailClearResult(0, 0, 0, "the trail database is not open")
        val r = w.clearAllTrailData()
        return TrailClearResult(r.trails, r.properties, r.aliases, r.error)
    }

    /**
     * RIDERTRAILDELETE-2026-09-08: remove one trail, both stores.
     *
     * ⚠ BOTH STORES, and the extension row is not optional. TRAILCLEAR-2026-08-24L
     * is the precedent: trail_properties is keyed on trail_id, so once the
     * spatial row is gone an orphan there joins to nothing and can never be
     * reached again -- and worse, TrailImporter inserts with INSERT OR IGNORE
     * on (source_id, source_unique_id), so a stale row KEEPS THE SLOT and a
     * re-imported trail silently gets no properties.
     *
     * ⚠ THIS IS NOT PERMANENT FOR A RIDER TRAIL. They are derived from tracks
     * and the scan rederives them on the next clear-and-reload. Deleting the
     * track as well is what makes it stick.
     */
    fun deleteTrailFromDb(id: String) { writer()?.deleteTrailFromDb(id) }   // STEP11

    fun deleteTrackFromDb(id: String) {   // STEP11: the rows (track, track_properties, aliases) by the shared code; the GPX file here
        val geomHash = writer()?.deleteTrackFromDb(id)
        val h = geomHash
        if (h != null && h.isNotBlank()) {
            try {
                val tracksDir = ConvoyTrackOps.tracksDir()   // MYTRACKSDB-2026-09-12
                val f = java.io.File(tracksDir, "$h.gpx")
                if (f.exists()) {
                    val ok = f.delete()
                    android.util.Log.i("TrackDelete", "file $h.gpx deleted=$ok")
                } else {
                    android.util.Log.i("TrackDelete", "file $h.gpx not present (no-op)")
                }
            } catch (e: Exception) { android.util.Log.w("TrackDelete", "file: ${e.message}") }
        } else {
            android.util.Log.i("TrackDelete", "no geom_hash for $id -- no file to remove")
        }
        android.util.Log.i("TrackDelete", "deleteTrackFromDb($id) complete")
    }

    
    // ── GPX BUILDERS (for SHARE and EXPORT) ─────────────────

    // ROUTEEXTENSIONS-2026-09-08: the grouptrack namespace is DECLARED now.
    // \u26a0 buildRouteGpxById emits <grouptrack:notes> inside <extensions>, and an
    // undeclared prefix makes the file invalid to a strict XML parser. Our own
    // regex reader would not notice; someone else's parser will.
    // \u26a0 Harmless on every other artifact -- an unused namespace declaration is
    // legal and ignored.
    private val GPX_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"GroupTrack\"\n" +
        "     xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
        "     xmlns:grouptrack=\"https://grouptrack.app/gpx/1\">\n"
    private const val GPX_FOOTER = "</gpx>"

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Build GPX for a single waypoint by ID. Returns Pair(name, gpxContent) or null. */
    // ---- TRAILHEAD-2026-09-23 ----------------------------------------------------------------
    /** One trailhead candidate near a point. */
    data class TrailheadCandidate(
        val waypointId: String, val name: String, val lat: Double, val lon: Double, val miles: Double
    )

    /**
     * Trailhead waypoints within [radiusMiles] of (lat, lon), nearest first. Rule (Fred 09-23): a
     * route's trailhead is within 1/2 mile of its start. One = use it, several = selector, none = the
     * rider adds one on the map. Box query on the stored bounds, then an exact haversine check.
     */
    fun trailheadsNear(lat: Double, lon: Double, radiusMiles: Double = 0.5): List<TrailheadCandidate> = withLookups(emptyList()) { it.trailheadsNear(lat, lon, radiusMiles).map { h -> TrailheadCandidate(h.waypointId, h.name, h.lat, h.lon, h.miles) } }   // STEP7-2026-11-07: shared GtRead

    /**
     * Writes the route's trailhead into its RECIPE as the anchor, so the route carries it from then
     * on. A recipe that already has an anchor (AI routes) is left alone. A route with no notes gets
     * notes holding just the recipe. Returns true when the recipe has an anchor afterwards.
     */
    fun setRouteAnchor(routeId: String, lat: Double, lon: Double, name: String): Boolean =
        writer()?.setRouteAnchor(routeId, lat, lon, name) ?: false   // STEP11
    /**
     * WPTTAP-2026-09-30 (Fred): a waypoint that is a route's trailhead cannot be retyped or deleted while the
     * route exists -- the route must be removed first. A route stores its trailhead as recipe.anchorLat/anchorLon
     * (setRouteAnchor), copied from the waypoint's own position, so the match is by position (within ~10 m).
     * Returns the name of the first route using it, or null (not a trailhead, or no route uses it).
     */
    fun routeUsingTrailhead(waypointId: String): String? = withLookups(null) { it.routeUsingTrailhead(waypointId) }   // STEP7-2026-11-07: shared GtRead

    // ---- end TRAILHEAD-2026-09-23 ------------------------------------------------------------

    fun buildWaypointGpxById(waypointId: String): Pair<String, String>? {
        val db = spatialDb ?: return null
        try {
            val cursor = db.rawQuery(
                "SELECT name, type, geometry FROM waypoints WHERE waypoint_id=?",
                arrayOf(waypointId))
            cursor.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(0) ?: "Unnamed"
                val type = c.getString(1) ?: ""
                val geom = c.getString(2) ?: return null
                val desc = if (c.columnCount > 3) (c.getString(3) ?: "") else ""
                // GTBATCH2A-2026-11-06: the GPX text is built by the shared GtGpx (2.7e byte for byte)
                val gpx = com.grouptrack.shared.gpx.GtGpx.waypoint(name, type, geom, desc) ?: return null
                return Pair(name, gpx)
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "buildWaypointGpx error: " + e.message)
            return null
        }
    }

    /** Build GPX for a single route by ID. Returns Pair(name, gpxContent) or null. */
    fun buildRouteGpxById(routeId: String): Pair<String, String>? {
        val db = spatialDb ?: return null
        try {
            val cursor = db.rawQuery(
                "SELECT name, geometry FROM routes WHERE route_id=?",
                arrayOf(routeId))
            cursor.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(0) ?: "Unnamed"
                val geom = c.getString(1) ?: return null
                /* CLOSEPLANNER-2026-08-29: THE NARRATIVE, AT LAST.
                 *
                 * ⛔ This read `columnCount > 2` against a two-column SELECT, so
                 * it has NEVER fired and no narrative has ever been shared. It
                 * was written for a description column that does not exist.
                 *
                 * ⭐ It lives in route_notes, and readRouteNotes() returns it.
                 *
                 * ⚠ <desc> GETS THE HEADLINE, NOT THE JSON. Gaia and onX put
                 * human prose here, and a rider opening this route there should
                 * see a sentence rather than a payload. The structured block
                 * goes in <extensions>, which other apps ignore.
                 */
                val notes = readRouteNotes(routeId)
                val desc = notes?.optJSONObject("narrative")?.optString("headline") ?: ""
                // GTBATCH2A-2026-11-06: the GPX text is built by the shared GtGpx (2.7e byte for byte, incl. the notes block twice --
                // a known defect kept so the move certifies identical). The notes are handed over as org.json writes them.
                return Pair(name, com.grouptrack.shared.gpx.GtGpx.route(name, geom, desc, notes?.toString()))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "buildRouteGpx error: " + e.message)
            return null
        }
    }
    /**
     * TRACK EXPORT: read the GPX file from my_tracks by track_id (= geom_hash),
     * return Pair(displayName, gpxContent). The file is already valid GPX.
     */
    fun buildTrackGpxById(trackId: String): Pair<String, String>? {
        val db = spatialDb ?: return null
        // Get display name from the tracks table
        // track_id is a UUID; the file on disk is named by geom_hash
        var name: String = trackId
        var geomHash: String = trackId
        try {
            db.rawQuery("SELECT name, geom_hash FROM tracks WHERE track_id=? LIMIT 1", arrayOf(trackId)).use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: trackId
                    geomHash = c.getString(1) ?: trackId
                }
            }
        } catch (_: Exception) { }

        // Read the GPX file from <tracks root>/{geom_hash}.gpx
        val tracksDir = ConvoyTrackOps.tracksDir()   // MYTRACKSDB-2026-09-12
        val gpxFile = java.io.File(tracksDir, "$geomHash.gpx")
        if (!gpxFile.exists()) {
            android.util.Log.e("SpatialDb", "Track file not found: ${gpxFile.absolutePath}")
            return null
        }

        return try {
            val content = gpxFile.readText()
            android.util.Log.i("SpatialDb", "buildTrackGpxById: $name (${content.length} chars)")
            name to content
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "Failed to read track file", e)
            null
        }
    }


    /**
     * Fetch geometry (WKT) for a set of artifact IDs, keyed by id. Used to rederive
     * a route's snapped shape from its vertices' lineIds WITHOUT a viewport query —
     * so reload / undo / zoom-pan rebuild correctly regardless of where the map looks.
     * type: "trail"/"trails" -> trails/trail_id ; "track"/"tracks"/"route"/"routes" -> tracks/track_id.
     */
    fun queryGeomByIds(ids: Collection<String>, type: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (ids.isEmpty()) return out
        val db = spatialDb ?: return out
        val (table, idCol) = when (type.lowercase()) {
            "trail", "trails" -> "trails" to "trail_id"
            else              -> "tracks" to "track_id"   // tracks + routes share the tracks table
        }
        val distinct = ids.toSet().toList()
        val placeholders = distinct.joinToString(",") { "?" }
        try {
            db.rawQuery(
                "SELECT $idCol, geometry FROM $table WHERE $idCol IN ($placeholders)",
                distinct.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val g = c.getString(1) ?: continue
                    out[id] = g
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "queryGeomByIds($type) failed: " + e.message)
        }
        return out
    }

    /** Build GPX for a single trail by ID. Returns Pair(name, gpxContent) or null. */
    fun buildTrailGpxById(trailId: String): Pair<String, String>? {
        val db = spatialDb ?: return null
        try {
            val cursor = db.rawQuery(
                "SELECT name, geometry FROM trails WHERE trail_id=?",
                arrayOf(trailId))
            cursor.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(0) ?: "Unnamed"
                val geom = c.getString(1) ?: return null
                // GTBATCH2A-2026-11-06: the GPX text is built by the shared GtGpx (2.7e byte for byte)
                return Pair(name, com.grouptrack.shared.gpx.GtGpx.trail(name, geom))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "buildTrailGpx error: " + e.message)
            return null
        }
    }

        /** Close both databases. Call on app teardown. */
    fun close() {
        closeLookups()   // STEP7-2026-11-07
        spatialDb?.close()
        extensionDb?.close()
        spatialDb = null
        extensionDb = null
        initialized = false
        android.util.Log.i(TAG, "Databases closed")
    }

    // ── Accessors ─────────────────────────────────────────────

    fun spatial(): SQLiteDatabase {
        check(initialized) { "SpatialDbManager not initialized. Call init() first." }
        return spatialDb!!
    }

    fun extension(): SQLiteDatabase {
        check(initialized) { "SpatialDbManager not initialized. Call init() first." }
        return extensionDb!!
    }

    fun isReady(): Boolean = initialized

    // ── Utility: generate UUID ────────────────────────────────
    fun newId(): String = UUID.randomUUID().toString()

    // ── Utility: ISO 8601 timestamp ───────────────────────────
    fun now(): String = Instant.now().toString()

    // ── Utility: WKT builders ─────────────────────────────────

    /** Build POINT WKT from lat/lng */
    fun pointWkt(lat: Double, lng: Double): String =
        "POINT($lng $lat)"

    /** Build LINESTRING WKT from list of lat/lng pairs */
    fun lineWkt(points: List<Pair<Double, Double>>): String {
        val coords = points.joinToString(", ") { "${it.second} ${it.first}" }
        return "LINESTRING($coords)"
    }

    /** Build POLYGON WKT from bounding box (for track bbox) */
    fun bboxWkt(north: Double, south: Double, east: Double, west: Double): String =
        "POLYGON(($west $south, $east $south, $east $north, $west $north, $west $south))"

    /** [2026-07-01] "Maps follow the tracks" ENGINE.
     *  Reads a track's bbox by geom_hash, pads 1/2 mile, and submits it to the
     *  download queue as an area download (mimics the area-download UI call).
     *  @param hashFileName "<hash>.gpx" or a bare hash.
     *  @return number of grid cells enqueued (0 = track not found / no bbox).
     *  MUST be called from a background thread (enqueueArea requirement). */
    /**
     * Derive the padded download bbox for a track's geometry hash.
     * Same lookup + 1/2-mile pad as downloadMapsForTrackHash, but returns
     * the bbox instead of enqueuing — so the caller can feed box 1 (source
     * selection) and submitDownload, identical to the area path.
     */
    /** CORRIDOR-DERIVATION-2026-07-24: geometry -> POINTS, for corridor downloads.
     *
     *  Returns ONE INNER LIST PER SEGMENT. Disjoint MULTILINESTRING segments
     *  must never be joined: interpolating across the gap between two
     *  segments would stamp a corridor over terrain the rider never crossed -
     *  and that gap is precisely where a bbox wastes its tiles, so joining
     *  them would reintroduce the very bug the corridor exists to fix.
     *
     *  ⚠ WKT is `lon lat`. This returns (LAT, LON) - flipped, matching the
     *  convention at :1763. Backwards puts the corridor in another hemisphere.
     *
     *  @return segments, or null if the track/geometry is missing. */
    // CORRDELETE-STAGE1-2026-08-07B
    /**
     * Every track carrying a geom_hash, with its current name.
     *
     * Takes no Context and opens nothing: the pattern at :1266 is
     * `val sdb = spatialDb ?: return <empty>` -- spatialDb is a property that
     * is either already open or is not, and inventing an open path here would
     * diverge from every other reader in this file.
     *
     * Same row source as the backfill query -- tracks WHERE geom_hash IS NOT
     * NULL -- but returns name alongside so a caller iterating all tracks does
     * not need a second read per row. Name is nullable because the column is.
     *
     * Returned as a list of (geom_hash, name) pairs rather than a map: names
     * are not unique, and a map keyed on name would silently collapse tracks.
     */
    fun allTrackGeomHashes(): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        val sdb = spatialDb ?: return out
        try {
            sdb.rawQuery(
                "SELECT geom_hash, name FROM tracks WHERE geom_hash IS NOT NULL",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val gh = c.getString(0) ?: continue
                    out.add(gh to (if (c.isNull(1)) null else c.getString(1)))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "CORRDELETE-STAGE1-2026-08-07B allTrackGeomHashes failed: ${e.message}")
        }
        return out
    }

    fun getTrackPoints(context: Context, hashFileName: String): List<List<Pair<Double, Double>>>? {
        val hash = hashFileName.substringBeforeLast(".gpx").trim()
        val sdb = spatialDb ?: return null
        var wkt: String? = null
        try {
            sdb.rawQuery(
                "SELECT geometry FROM tracks WHERE geom_hash=? LIMIT 1",
                arrayOf(hash)
            ).use { c -> if (c.moveToFirst()) wkt = c.getString(0) }
        } catch (e: Exception) {
            android.util.Log.e("Corridor", "getTrackPoints lookup failed for $hash: ${e.message}")
            return null
        }
        val g = wkt
        if (g.isNullOrBlank()) {
            android.util.Log.w("Corridor", "getTrackPoints: no geometry for hash $hash")
            return null
        }
        // ROUTECORR-2026-08-10A: the parse moved to parseWktSegments below so tracks and
        // routes share ONE copy and cannot drift apart later. Behaviour here is
        // unchanged - same input, same output, same failure cases.
        val out = parseWktSegments(g, hash, "getTrackPoints") ?: return null
        android.util.Log.i("Corridor",
            "getTrackPoints $hash: ${out.size} segment(s), ${out.sumOf { it.size }} points")
        return out
    }

    /**
     * ROUTECORR-2026-08-10A: WKT LINESTRING / MULTILINESTRING to lat/lon segments.
     *
     * Extracted verbatim out of getTrackPoints. Same parse shape as
     * wktToGeoJsonCoords (:342): strip the wrapper, and for MULTI split the
     * segments before trimming stray parens.
     *
     * Returns null for blank or unsupported geometry - the caller decides what
     * that means. Never throws.
     *
     * @param tag caller name, for the log line only
     */
    private fun parseWktSegments(
        wkt: String,
        hash: String,
        tag: String
    ): List<List<Pair<Double, Double>>>? {
        val g = wkt.trim()
        if (g.isBlank()) return null
        val segments: List<String> = when {
            g.startsWith("MULTILINESTRING(") ->
                g.removePrefix("MULTILINESTRING(").removeSuffix(")")
                    .split("),(").map { it.trim('(', ')') }
            g.startsWith("LINESTRING(") ->
                listOf(g.removePrefix("LINESTRING(").removeSuffix(")"))
            else -> {
                android.util.Log.w("Corridor", "$tag: unsupported WKT for $hash")
                return null
            }
        }
        val out = segments.mapNotNull { seg ->
            val pts = seg.split(",").mapNotNull { p ->
                val parts = p.trim().split(" ")
                if (parts.size < 2) return@mapNotNull null
                val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                Pair(lat, lon)          // FLIPPED - WKT is lon lat
            }
            if (pts.isEmpty()) null else pts
        }
        return if (out.isEmpty()) null else out
    }

    /**
     * ROUTECORR-2026-08-10A: route geometry by geom_hash. The routes-table twin of
     * getTrackPoints.
     *
     * Safe to key on the hash: the device schema declares UNIQUE(geom_hash) on
     * routes, so at most one row can answer.
     */
    fun getRoutePoints(context: Context, hashFileName: String): List<List<Pair<Double, Double>>>? {
        val hash = hashFileName.substringBeforeLast(".gpx").trim()
        val sdb = spatialDb ?: return null
        var wkt: String? = null
        try {
            sdb.rawQuery(
                "SELECT geometry FROM routes WHERE geom_hash=? LIMIT 1",
                arrayOf(hash)
            ).use { c -> if (c.moveToFirst()) wkt = c.getString(0) }
        } catch (e: Exception) {
            android.util.Log.e("Corridor", "getRoutePoints lookup failed for $hash: ${e.message}")
            return null
        }
        val g = wkt
        if (g.isNullOrBlank()) return null
        val out = parseWktSegments(g, hash, "getRoutePoints") ?: return null
        android.util.Log.i("Corridor",
            "getRoutePoints $hash: ${out.size} segment(s), ${out.sumOf { it.size }} points")
        return out
    }

    /**
     * ROUTECORR-2026-08-10A: THE corridor geometry lookup. Tracks first, then routes.
     *
     * *** WHY THERE IS NO ARTIFACT-TYPE PARAMETER. *** geom_hash is
     * CONTENT-ADDRESSED - computed from the WKT itself - and both tables declare
     * it unique. The same hash therefore means the same geometry wherever it is
     * stored, so a corridor derived from either table is identical.
     *
     * That is what lets route corridors reuse the entire existing pipeline
     * unchanged: no new enqueueCorridor parameter, no new QueueEntry field, no
     * inputData change, no worker change, and no serialisation risk to a queue
     * file already persisted on devices.
     *
     * A route drawn along an existing track resolves from tracks first. Not a
     * conflict - the geometry is identical, so the tiles are too.
     */
    fun getCorridorGeometry(context: Context, hashFileName: String): List<List<Pair<Double, Double>>>? =
        getTrackPoints(context, hashFileName) ?: getRoutePoints(context, hashFileName)

    /**
     * ROUTECORR-2026-08-10B: display name for a corridor job, by geom_hash. Tracks, then routes.
     *
     * Replaces a full scan. The previous label lookup pulled EVERY track hash and
     * name into a list and searched it, on every corridor enqueue - so a 272-job
     * submission did that 272 times. Both tables index geom_hash, so one indexed
     * row each is enough.
     *
     * Returns null when neither table has the hash, or the row has no usable
     * name. The caller decides what to show instead.
     */
    fun nameForGeomHash(hash: String): String? {
        val h = hash.substringBeforeLast(".gpx").trim()
        val sdb = spatialDb ?: return null
        for (table in listOf("tracks", "routes")) {
            try {
                sdb.rawQuery(
                    "SELECT name FROM $table WHERE geom_hash=? LIMIT 1",
                    arrayOf(h)
                ).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) {
                        val nm = c.getString(0)
                        if (!nm.isNullOrBlank()) return nm
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Corridor", "ROUTECORR-2026-08-10B: name lookup failed in $table: ${e.message}")
            }
        }
        return null
    }

    fun getTrackBbox(context: Context, hashFileName: String): DownloadBbox? {
        val hash = hashFileName.substringBeforeLast(".gpx").trim()
        val sdb = spatialDb ?: return null
        var minLat = 0.0; var maxLat = 0.0; var minLon = 0.0; var maxLon = 0.0
        var found = false
        try {
            sdb.rawQuery(
                "SELECT name, min_lat, max_lat, min_lon, max_lon FROM tracks WHERE geom_hash=? LIMIT 1",
                arrayOf(hash)
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(1)) {
                    minLat = c.getDouble(1); maxLat = c.getDouble(2)
                    minLon = c.getDouble(3); maxLon = c.getDouble(4)
                    found = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TrackMaps", "getTrackBbox lookup failed for $hash: ${e.message}")
            return null
        }
        if (!found) { android.util.Log.w("TrackMaps", "getTrackBbox: no bbox row for hash $hash"); return null }
        val padLat = 0.00724
        val midLat = (minLat + maxLat) / 2.0
        val padLon = 0.00724 / Math.max(0.01, Math.cos(Math.toRadians(midLat)))
        return DownloadBbox(
            north = maxLat + padLat, south = minLat - padLat,
            east = maxLon + padLon, west = minLon - padLon
        )
    }

    /**
     * ROUTECORR-2026-08-10C: route bbox by geom_hash. The routes-table twin of getTrackBbox.
     *
     * Same padding as the track version - half a mile, adjusted for longitude
     * convergence - so a route and a track over identical ground produce an
     * identical box.
     */
    fun getRouteBbox(context: Context, hashFileName: String): DownloadBbox? {
        val hash = hashFileName.substringBeforeLast(".gpx").trim()
        val sdb = spatialDb ?: return null
        var minLat = 0.0; var maxLat = 0.0; var minLon = 0.0; var maxLon = 0.0
        var found = false
        try {
            sdb.rawQuery(
                "SELECT min_lat, max_lat, min_lon, max_lon FROM routes WHERE geom_hash=? LIMIT 1",
                arrayOf(hash)
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    minLat = c.getDouble(0); maxLat = c.getDouble(1)
                    minLon = c.getDouble(2); maxLon = c.getDouble(3)
                    found = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TrackMaps", "getRouteBbox lookup failed for $hash: ${e.message}")
            return null
        }
        if (!found) return null
        val padLat = 0.00724
        val midLat = (minLat + maxLat) / 2.0
        val padLon = 0.00724 / Math.max(0.01, Math.cos(Math.toRadians(midLat)))
        return DownloadBbox(
            north = maxLat + padLat, south = minLat - padLat,
            east = maxLon + padLon, west = minLon - padLon
        )
    }

    /**
     * ROUTECORR-2026-08-10C: bbox for a corridor download. Tracks first, then routes.
     *
     * The SAVE MAPS action needs this BEFORE the download pipeline is reached,
     * so without it a route fails at the very first step and the user is told
     * the artifact has no geometry when it has perfectly good geometry.
     *
     * Same reasoning as getCorridorGeometry: geom_hash is content-addressed and
     * unique in both tables, so whichever answers gives the same box.
     */
    fun getCorridorBbox(context: Context, hashFileName: String): DownloadBbox? =
        getTrackBbox(context, hashFileName) ?: getRouteBbox(context, hashFileName)

    fun downloadMapsForTrackHash(context: Context, hashFileName: String): Int {
        val hash = hashFileName.substringBeforeLast(".gpx").trim()
        val sdb = spatialDb ?: return 0
        var minLat = 0.0; var maxLat = 0.0; var minLon = 0.0; var maxLon = 0.0
        var name = hash; var found = false
        try {
            sdb.rawQuery(
                "SELECT name, min_lat, max_lat, min_lon, max_lon FROM tracks WHERE geom_hash=? LIMIT 1",
                arrayOf(hash)
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(1)) {
                    name = c.getString(0) ?: hash
                    minLat = c.getDouble(1); maxLat = c.getDouble(2)
                    minLon = c.getDouble(3); maxLon = c.getDouble(4)
                    found = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TrackMaps", "bbox lookup failed for $hash: ${e.message}")
            return 0
        }
        if (!found) { android.util.Log.w("TrackMaps", "no bbox row for hash $hash"); return 0 }
        // 1/2-mile pad: lat constant; lon scaled by cos(midLat), guarded near the poles.
        val padLat = 0.00724
        val midLat = (minLat + maxLat) / 2.0
        val padLon = 0.00724 / Math.max(0.01, Math.cos(Math.toRadians(midLat)))
        // Use enqueue() (computes every tile via calculateTiles) NOT enqueueArea()
        // (grid-cell split -> empty for a sub-cell-sized per-track box -> totalTiles=0).
        val entry = DownloadQueueManager.enqueue(
            context,
            maxLat + padLat, minLat - padLat,
            maxLon + padLon, minLon - padLon,
            "DL $name"
        )
        val count = entry.totalTiles
        android.util.Log.i("TrackMaps", "queued $count tiles for '$name' ($hash)")
        return count
    }

    /** Parse POINT WKT to lat/lng pair */
    fun parsePoint(wkt: String): Pair<Double, Double>? {
        val match = Regex("POINT\\(([\\d.\\-]+) ([\\d.\\-]+)\\)").find(wkt) ?: return null
        val lng = match.groupValues[1].toDoubleOrNull() ?: return null
        val lat = match.groupValues[2].toDoubleOrNull() ?: return null
        return Pair(lat, lng)
    }

    /** Parse LINESTRING WKT to list of lat/lng pairs */
    fun parseLine(wkt: String): List<Pair<Double, Double>> {
        val match = Regex("LINESTRING\\((.+)\\)").find(wkt) ?: return emptyList()
        return match.groupValues[1].split(",").mapNotNull { coord ->
            val parts = coord.trim().split(" ")
            if (parts.size >= 2) {
                val lng = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lat != null && lng != null) Pair(lat, lng) else null
            } else null
        }
    }

    /** Parse POLYGON WKT to bounding box [north, south, east, west] */
    fun parseBbox(wkt: String): DoubleArray? {
        val match = Regex("POLYGON\\(\\((.+)\\)\\)").find(wkt) ?: return null
        val points = match.groupValues[1].split(",").mapNotNull { coord ->
            val parts = coord.trim().split(" ")
            if (parts.size >= 2) Pair(parts[0].toDouble(), parts[1].toDouble()) else null
        }
        if (points.size < 4) return null
        val lngs = points.map { it.first }
        val lats = points.map { it.second }
        return doubleArrayOf(lats.max(), lats.min(), lngs.max(), lngs.min())
    }

    // ── Schema version check ──────────────────────────────────
    fun spatialSchemaVersion(): Int {
        if (!initialized) return -1
        return try {
            val cursor = spatialDb!!.rawQuery("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1", null)
            cursor.moveToFirst()
            val v = cursor.getInt(0)
            cursor.close()
            v
        } catch (_: Exception) { 0 }
    }

    fun extensionSchemaVersion(): Int {
        if (!initialized) return -1
        return try {
            val cursor = extensionDb!!.rawQuery("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1", null)
            cursor.moveToFirst()
            val v = cursor.getInt(0)
            cursor.close()
            v
        } catch (_: Exception) { 0 }
    }

    /** FIT support: bounding box [south, west, north, east] for ONE artifact, or null. */
    fun bboxForArtifact(type: String, artifactId: String): DoubleArray? {
        val (table, idCol) = spatialTableFor(type) ?: return null
        val db = spatialDb ?: return null
        db.rawQuery("SELECT min_lat, min_lon, max_lat, max_lon FROM $table WHERE $idCol = ? LIMIT 1", arrayOf(artifactId)).use {
            if (!it.moveToFirst()) return null
            if (it.isNull(0) || it.isNull(1) || it.isNull(2) || it.isNull(3)) return null
            val south = it.getDouble(0); val west = it.getDouble(1)
            val north = it.getDouble(2); val east = it.getDouble(3)
            return doubleArrayOf(south, west, north, east)
        }
    }
}
