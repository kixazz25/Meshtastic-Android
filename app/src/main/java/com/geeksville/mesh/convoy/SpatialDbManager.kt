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
    internal fun groupTrackDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "GroupTrack"
    )
    // GATEJOB-2026-08-21F: internal so the startup job can test for the DB FILE
    // without opening it. init() calls openOrCreateDatabase, which creates an
    // empty schema where real data should be -- the 08-01 mechanism.
    internal fun dbDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "GroupTrack"
        )
        if (!dir.exists()) dir.mkdirs()
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try { noMedia.createNewFile() } catch (_: Exception) {}
        }
        return dir
    }

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
            for (col in listOf("land_status", "use_type", "carto_code_source")) {
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
            "SELECT trail_id, name, geometry, carto_code AS CartoCode, status AS Status FROM trails " +
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
                    "CartoCode" to cursor.getString(3)
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

    /** Build GeoJSON FeatureCollection from viewport query results */
    fun buildTrailGeoJson(trails: List<Map<String, String?>>): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (trail in trails) {
            val wkt = trail["geometry"] ?: continue
            val coords = wktToGeoJsonCoords(wkt)
            if (coords == "[]") continue
            if (!first) sb.append(",")
            first = false
            val geoType = if (wkt.startsWith("MULTI")) "MultiLineString" else "LineString"
            fun s(k: String): String = (trail[k] ?: "").replace("\\", "\\\\").replace("\"", "\\\\\"")
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
            sb.append("},\"geometry\":{\"type\":\"" + geoType + "\",\"coordinates\":" + coords + "}}")
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
    fun searchByName(type: String, term: String, limit: Int = 200): List<Map<String, String?>> {
        val db = spatialDb ?: return emptyList()
        val (table, idCol) = when (type) {
            "tracks"    -> "tracks"    to "track_id"
            "trails"    -> "trails"    to "trail_id"
            "waypoints" -> "waypoints" to "waypoint_id"
            "routes"    -> "routes"    to "route_id"
            else -> return emptyList()
        }
        val results = mutableListOf<Map<String, String?>>()
        // ids already matched on the OFFICIAL spatial name -- a name hit always
        // wins over an alias hit for the same artifact (it is NOT "via alias").
        val nameHitIds = HashSet<String>()
        try {
            val cursor = db.rawQuery(
                // SEARCHFIT-2026-09-02: bbox columns added so a result can be
                // framed on selection. ⚠ They are already indexed -- the
                // viewport queries use them -- so this costs nothing.
                "SELECT $idCol, name, geom_hash, min_lat, max_lat, min_lon, max_lon FROM $table " +
                "WHERE name LIKE ? AND name IS NOT NULL AND TRIM(name) <> '' AND name <> 'Not Named' " +
                "ORDER BY name COLLATE NOCASE, geom_hash LIMIT ?",
                arrayOf("%" + term + "%", limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    nameHitIds.add(id)
                    results.add(mapOf(
                        "id" to id,
                        "name" to it.getString(1),
                        "geom_hash" to it.getString(2),
                        "type" to type,
                        "matched_via_alias" to "false",
                        "matched_alias" to null,
                        // SEARCHFIT-2026-09-02: bounds ride along so a result
                        // can be framed on selection. ⚠ The ALIAS pass below
                        // does NOT set these -- an alias hit gets nulls and the
                        // fit is skipped, which is correct: it still opens the
                        // card, it just does not move the map.
                        "min_lat" to it.getString(3),
                        "max_lat" to it.getString(4),
                        "min_lon" to it.getString(5),
                        "max_lon" to it.getString(6)
                    ))
                }
            }
            // ---- ALIAS PASS: match the term against artifact_aliases (extension DB) ----
            // artifact_aliases stores artifact_type SINGULAR (see getAliasesFor()).
            val edb = extensionDb
            if (edb != null && results.size < limit) {
                val singular = type.removeSuffix("s")
                // Collect distinct artifact ids whose alias matches, with the matched text.
                val aliasMatches = LinkedHashMap<String, String>()  // id -> matched alias text (first hit kept)
                val ac = edb.rawQuery(
                    "SELECT artifact_id, alias FROM artifact_aliases " +
                    "WHERE artifact_type = ? AND alias LIKE ? " +
                    "ORDER BY is_preferred DESC, alias COLLATE NOCASE LIMIT ?",
                    arrayOf(singular, "%" + term + "%", limit.toString())
                )
                ac.use {
                    while (it.moveToNext()) {
                        val aid = it.getString(0) ?: continue
                        if (aid in nameHitIds) continue          // name hit already covers it
                        if (!aliasMatches.containsKey(aid)) aliasMatches[aid] = it.getString(1)
                    }
                }
                // For each alias-only match, look up the OFFICIAL name+hash from the spatial table.
                for ((aid, matchedAlias) in aliasMatches) {
                    if (results.size >= limit) break
                    val rc = db.rawQuery(
                        "SELECT name, geom_hash FROM $table WHERE $idCol = ? LIMIT 1",
                        arrayOf(aid)
                    )
                    rc.use {
                        if (it.moveToFirst()) {
                            val offName = it.getString(0)
                            results.add(mapOf(
                                "id" to aid,
                                "name" to offName,                 // ALWAYS the official name
                                "geom_hash" to it.getString(1),
                                "type" to type,
                                "matched_via_alias" to "true",
                                "matched_alias" to matchedAlias
                            ))
                        }
                    }
                }
            }
            android.util.Log.i("ArtifactSearch", "searchByName($type,'$term') -> ${results.size} (alias-join)")
        } catch (e: Exception) {
            android.util.Log.e("ArtifactSearch", "searchByName failed: ${e.message}")
        }
        return results
    }

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
            val name = (track["name"] ?: "Unnamed Track").replace("\"", "\\\"")
            val coordStr = geom.removePrefix("LINESTRING(").removeSuffix(")")
            val coords = coordStr.split(",").joinToString(",") { pair ->
                val parts = pair.trim().split(" ")
                if (parts.size >= 2) "[${parts[0]},${parts[1]}]" else "[0,0]"
            }
            val cc = (track["carto_code"] ?: "").replace("\"", "\\\"")
            val tid = (track["track_id"] ?: "").replace("\"", "\\\"")
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
            val tracksDir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                "my_tracks"
            )
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
            val logFile = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                "GroupTrack/track_sync.log"
            )
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
    fun parseGpxTrackPoints(gpxText: String): List<Pair<Double, Double>> {
        val coords = mutableListOf<Pair<Double, Double>>()
        // 2026-07-11: use the SAME robust trkpt-anchored regex as parseGpxTrackPointsFull
        // so external files (onX/Gaia) with different attribute order/spacing parse into
        // the SAME point set as our own recorder's files. The fragile `lat=..\s+lon=..`
        // regex (here since 2026-05-22) matched external files differently -> different
        // geometry -> different geom_hash -> duplicate tracks. Returns Pair(lon, lat) as before.
        val ptRegex = Regex("""<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"""")
        for (match in ptRegex.findAll(gpxText)) {
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lon = match.groupValues[2].toDoubleOrNull() ?: continue
            coords.add(Pair(lon, lat))
        }
        return coords
    }

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
            val name = (wpt["name"] ?: "Unnamed").replace("\"", "\\\"")
            val wptType = (wpt["type"] ?: "other").replace("\"", "\\\"")
            // Parse POINT(lon lat)
            val match = Regex("POINT\\(([\\d.\\-]+) ([\\d.\\-]+)\\)").find(geom)
            if (match != null) {
                val lon = match.groupValues[1]
                val lat = match.groupValues[2]
                sb.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"$name\",\"wpt_type\":\"$wptType\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[$lon,$lat]}}")
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
            val name = (route["name"] ?: "Unnamed Route").replace("\"", "\\\"")
            val coordStr = geom.removePrefix("LINESTRING(").removeSuffix(")")
            val coords = coordStr.split(",").joinToString(",") { pair ->
                val parts = pair.trim().split(" ")
                if (parts.size >= 2) "[${parts[0]},${parts[1]}]" else "[0,0]"
            }
            // ROUTETAP-2026-08-23Z: emit route_id. It was in the row map all along
            // (queryRoutesByViewport returns it) but never reached properties, so
            // the JS had nothing to hand back on a tap. Tracks already carry theirs.
            val rid = (route["route_id"] ?: "").replace("\"", "\\\"")
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
    fun insertWaypointWithId(waypointId: String, name: String, lat: Double, lon: Double, type: String): Boolean {
        val db = spatialDb ?: return false
        val ts = now()
        val geometry = pointWkt(lat, lon)
        val nm = notNamed(name)
        val gh = computeGeomHash(geometry)
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO waypoints (waypoint_id, name, geometry, type, min_lat, max_lat, min_lon, max_lon, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(waypointId, nm, geometry, type.lowercase(), lat, lat, lon, lon, ts, ts, gh))
            return true
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "insertWaypointWithId failed: " + e.message)
            return false
        }
    }

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
    fun insertRoute(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): String {
        val db = spatialDb ?: throw IllegalStateException("SpatialDbManager not initialized")
        val id = newId()
        val ts = now()
        val nm = notNamed(name)
        val gh = computeGeomHash(geometryWkt)
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO routes (route_id, name, geometry, min_lat, max_lat, min_lon, max_lon, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(id, nm, geometryWkt, minLat, maxLat, minLon, maxLon, ts, ts, gh)
            )
            android.util.Log.i("SpatialDb", "Inserted route: $nm")
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "Route insert failed: ${e.message}")
        }
        return id
    }

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
    fun writeRouteNotes(routeId: String, notes: org.json.JSONObject): Int {
        val db = extensionDb ?: return -1
        // ⛔ insertRoute uses INSERT OR IGNORE -- if the row was ignored the id
        // points at nothing and these notes would belong to a phantom route.
        val gh = routeGeomHash(routeId) ?: run {
            android.util.Log.w("SpatialDb", "writeRouteNotes: no route $routeId")
            return -1
        }
        val ts = now()
        var n = 0
        try {
            db.execSQL("DELETE FROM route_notes WHERE route_id=?", arrayOf<Any>(routeId))
            val nar = notes.optJSONObject("narrative")
            db.execSQL(
                "INSERT INTO route_notes (note_id,route_id,geom_hash,seq,kind,title,body,payload,author,created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(newId(), routeId, gh, 0, "narrative",
                    null, nar?.optString("headline"), notes.toString(), "generated", ts))
            n++
            val feats = notes.optJSONArray("features")
            if (feats != null) for (i in 0 until feats.length()) {
                val f = feats.optJSONObject(i) ?: continue
                db.execSQL(
                    "INSERT INTO route_notes (note_id,route_id,geom_hash,seq,kind,at_mile,lat,lon,title,body,payload,author,created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(newId(), routeId, gh, i + 1, "feature",
                        f.optDouble("at_mi", 0.0), f.optDouble("lat", 0.0),
                        f.optDouble("lon", 0.0), f.optString("name"),
                        f.optString("fclass"), f.toString(), "generated", ts))
                n++
            }
            val cauts = notes.optJSONArray("cautions")
            if (cauts != null) for (i in 0 until cauts.length()) {
                val c = cauts.optJSONObject(i) ?: continue
                db.execSQL(
                    "INSERT INTO route_notes (note_id,route_id,geom_hash,seq,kind,at_mile,lat,lon,body,payload,author,created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(newId(), routeId, gh, 1000 + i, "caution",
                        c.optDouble("at_mi", 0.0), c.optDouble("lat", 0.0),
                        c.optDouble("lon", 0.0),
                        "Trails mapped ${c.optDouble("gap_ft", 0.0).toInt()} ft apart",
                        c.toString(), "generated", ts))
                n++
            }
            android.util.Log.i("SpatialDb", "route_notes: wrote $n rows for $routeId")
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "writeRouteNotes failed: ${e.message}")
            return -1
        }
        return n
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
    fun waypointNameAt(lat: Double, lon: Double): String? {
        val db = spatialDb ?: return null
        val d = 0.00002   // ~2 metres
        return try {
            db.rawQuery(
                "SELECT name FROM waypoints WHERE min_lat IS NOT NULL AND " +
                    "max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT 1",
                arrayOf((lat - d).toString(), (lat + d).toString(),
                        (lon - d).toString(), (lon + d).toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }
                         else null }
        } catch (e: Exception) {
            android.util.Log.w("SpatialDb", "waypointNameAt failed: ${e.message}")
            null
        }
    }

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
    fun deleteRouteNotes(routeId: String): Int {
        val db = extensionDb ?: return 0
        return try {
            db.execSQL("DELETE FROM route_notes WHERE route_id=?", arrayOf<Any>(routeId)); 1
        } catch (e: Exception) {
            android.util.Log.w("SpatialDb", "deleteRouteNotes: ${e.message}"); 0
        }
    }

    /** ROUTENOTES-2026-08-23X: geom_hash for a route, or null if it does not exist. */
    fun routeGeomHash(routeId: String): String? {
        val db = spatialDb ?: return null
        return try {
            db.rawQuery("SELECT geom_hash FROM routes WHERE route_id=? LIMIT 1",
                arrayOf(routeId)).use { c -> if (c.moveToNext()) c.getString(0) else null }
        } catch (e: Exception) { null }
    }

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

    fun routeNameExists(name: String): Boolean {
        val db = spatialDb ?: return false
        val needle = name.trim()
        if (needle.isEmpty()) return false
        return try {
            val c = db.rawQuery(
                "SELECT 1 FROM routes WHERE name IS NOT NULL " +
                    "AND lower(trim(name)) = lower(trim(?)) LIMIT 1",
                arrayOf(needle)
            )
            val exists = c.moveToFirst()
            c.close()
            exists
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "routeNameExists failed: " + e.message)
            false
        }
    }

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

    fun insertTrackToDb(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): TrackInsertResult {
        val db = spatialDb ?: throw IllegalStateException("SpatialDbManager not initialized")
        val id = newId()
        val ts = now()
        val bbox = "$minLat,$minLon,$maxLat,$maxLon"
        val nm = notNamed(name)
        val gh = computeGeomHash(geometryWkt)
        var inserted = false
        var resolvedId = id
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO tracks (track_id, name, geometry, min_lat, max_lat, min_lon, max_lon, bbox, type, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(id, nm, geometryWkt, minLat, maxLat, minLon, maxLon, bbox, "TRACK", ts, ts, gh)
            )
            db.rawQuery("SELECT changes()", null).use { c ->
                if (c.moveToFirst()) inserted = c.getInt(0) > 0
            }
            if (inserted) {
                android.util.Log.i("SpatialDb", "Inserted track: $nm")
            } else {
                // dupe: resolve the EXISTING row's id by the unique geom_hash
                db.rawQuery("SELECT track_id FROM tracks WHERE geom_hash=? LIMIT 1", arrayOf(gh)).use { c ->
                    if (c.moveToFirst()) resolvedId = c.getString(0)
                }
                android.util.Log.i("SpatialDb", "Skipped dupe track: $nm")
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "Track DB insert failed: ${e.message}")
        }
        return TrackInsertResult(inserted, resolvedId, gh, nm)
    }

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
            val hashFile = java.io.File(
                java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                    "my_tracks"
                ),
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
    fun parseGpxTrackPointsFull(gpxText: String): List<TrackPtFull> {
        val out = mutableListOf<TrackPtFull>()
        val ptRegex = Regex("""<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"[^>]*?>(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
        val latLonOnly = Regex("""<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"[^>]*/>""")
        val eleRegex = Regex("""<ele>([^<]+)</ele>""")
        val timeRegex = Regex("""<time>([^<]+)</time>""")
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val timeFormats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",   // fractional seconds + Z (Gaia/onX exports)
            "yyyy-MM-dd'T'HH:mm:ss'Z'",       // whole seconds + Z (recorder)
            "yyyy-MM-dd'T'HH:mm:ssXXX",       // whole seconds + numeric offset (+00:00)
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"    // fractional + numeric offset
        ).map { java.text.SimpleDateFormat(it, java.util.Locale.US).apply { timeZone = utc; isLenient = false } }
        fun parseTime(s: String): Long? {
            val t = s.trim()
            for (f in timeFormats) {
                try { return f.parse(t)?.time } catch (e: Exception) { /* try next pattern */ }
            }
            return null
        }

        for (m in ptRegex.findAll(gpxText)) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            val inner = m.groupValues[3]
            val ele = eleRegex.find(inner)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()
            val tMs = timeRegex.find(inner)?.groupValues?.get(1)?.let { parseTime(it) }
            out.add(TrackPtFull(lat, lon, ele, tMs))
        }
        for (m in latLonOnly.findAll(gpxText)) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            out.add(TrackPtFull(lat, lon, null, null))
        }
        return out
    }

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

        val tracksDir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            "my_tracks"
        )
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
    fun computeGeomHash(wkt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(wkt.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xFF; sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0x0F]) }
        return sb.toString()
    }

    /** 'Not Named' fallback, applied for ALL four artifacts. */
    fun notNamed(name: String?): String = if (name.isNullOrBlank()) "Not Named" else name

    // ---- session-scoped in-memory dedup (loaded once per import) ----
    // One geom_hash->name lookup per artifact type. Plus a source-uid set for trails
    // (replaces the per-row SELECT 1 FROM trail_properties query).
    private val dedupHashToName = HashMap<String, HashMap<String, String>>()
    private val dedupSourceUids = HashSet<String>()
    private var dedupSessionActive = false

    /** Load the lookups once. Call at the start of an import; release with endDedupSession(). */
    fun beginDedupSession() {
        val db = spatialDb ?: return
        dedupHashToName.clear(); dedupSourceUids.clear()
        for (t in listOf("trail", "track", "waypoint", "route")) dedupHashToName[t] = HashMap()
        loadHashes(db, "trails", "trail_id", "trail")
        loadHashes(db, "tracks", "track_id", "track")
        loadHashes(db, "waypoints", "waypoint_id", "waypoint")
        loadHashes(db, "routes", "route_id", "route")
        // source-uid set from extension db trail_properties
        extensionDb?.let { e ->
            try {
                val c = e.rawQuery("SELECT source_id, source_unique_id FROM trail_properties WHERE source_id IS NOT NULL", null)
                c.use { while (it.moveToNext()) dedupSourceUids.add(it.getString(0) + "\u0000" + it.getString(1)) }
            } catch (ex: Exception) { android.util.Log.w("SpatialDb", "loadSourceUids: ${ex.message}") }
        }
        dedupSessionActive = true
        android.util.Log.i("SpatialDb", "Dedup session: trails=${dedupHashToName["trail"]?.size} tracks=${dedupHashToName["track"]?.size} wpts=${dedupHashToName["waypoint"]?.size} routes=${dedupHashToName["route"]?.size} srcUids=${dedupSourceUids.size}")
    }

    fun endDedupSession() {
        dedupHashToName.clear(); dedupSourceUids.clear(); dedupSessionActive = false
    }

    private fun loadHashes(db: android.database.sqlite.SQLiteDatabase, table: String, idCol: String, type: String) {
        try {
            val c = db.rawQuery("SELECT geom_hash, name FROM $table WHERE geom_hash IS NOT NULL", null)
            val m = dedupHashToName[type]!!
            c.use { while (it.moveToNext()) m[it.getString(0)] = it.getString(1) ?: "Not Named" }
        } catch (ex: Exception) { android.util.Log.w("SpatialDb", "loadHashes($table): ${ex.message}") }
    }

    /** Has this (source_id, source_unique_id) already been imported? In-memory. */
    fun sourceUidSeen(sourceId: String, uid: String): Boolean =
        dedupSourceUids.contains(sourceId + "\u0000" + uid)

    fun markSourceUid(sourceId: String, uid: String) { dedupSourceUids.add(sourceId + "\u0000" + uid) }

    enum class AddDecision { INSERT, DROP, ALIAS }

    /** The dupe/alias decision for trail/route/waypoint (geometry-name rule).
     *  Tracks use their own three-layer rule (see insertTrackToDb). */
    fun resolveByGeom(type: String, name: String, geomHash: String): AddDecision {
        val m = dedupHashToName[type] ?: return AddDecision.INSERT
        val existingName = m[geomHash] ?: return AddDecision.INSERT  // new geometry
        return if (existingName == name) AddDecision.DROP else AddDecision.ALIAS
    }

    /** Record a freshly-inserted artifact in the in-memory lookup. */
    fun noteInserted(type: String, geomHash: String, name: String) {
        dedupHashToName[type]?.put(geomHash, name)
    }

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
    fun getArtifactDetail(type: String, artifactId: String): LinkedHashMap<String, String?> {
        val out = LinkedHashMap<String, String?>()
        val (table, idCol) = spatialTableFor(type) ?: return out
        val db = spatialDb ?: return out
        val c = db.rawQuery("SELECT * FROM $table WHERE $idCol = ? LIMIT 1", arrayOf(artifactId))
        c.use {
            if (it.moveToFirst()) {
                for (i in 0 until it.columnCount) {
                    val col = it.getColumnName(i)
                    if (col == "geometry") continue            // never show the WKT blob
                    var v: String? = if (it.isNull(i)) null else it.getString(i)
                    if (col == "name" && (v == null || v.isBlank() || v == "null")) v = "Not Named"
                    out[col] = v
                }
            }
        }
        // ── merge per-type *_properties (data DB) into the same map ──
        val (propTable, propIdCol) = propertiesTableFor(type) ?: return out
        val edb = extensionDb ?: return out
        try {
            val pc = edb.rawQuery("SELECT * FROM $propTable WHERE $propIdCol = ? LIMIT 1", arrayOf(artifactId))
            pc.use {
                if (it.moveToFirst()) {
                    for (i in 0 until it.columnCount) {
                        val col = it.getColumnName(i)
                        if (col == propIdCol) continue          // skip the duplicate id column
                        if (out.containsKey(col) && !out[col].isNullOrBlank()) continue  // spatial wins only if it has a real value (null carto_code must not shadow trail_properties)
                        if (it.isNull(i)) continue
                        val v = it.getString(i)
                        if (v == null || v.isBlank()) continue  // skip blanks
                        out[col] = v
                    }
                }
            }
        } catch (e: Exception) {
            // properties table may not exist for a type / empty — leave spatial-only
        }
        return out
    }

    private fun propertiesTableFor(type: String): Pair<String, String>? = when (type.lowercase()) {
        "trail", "trails"       -> "trail_properties" to "trail_id"
        "track", "tracks"       -> "track_properties" to "track_id"
        "waypoint", "waypoints" -> "waypoint_properties" to "waypoint_id"
        "route", "routes"       -> "route_properties" to "route_id"
        else -> null
    }

    /** Alias accordion: rows from data-DB artifact_aliases, preferred-first. artifact_type is singular. */
    fun getAliasesFor(type: String, artifactId: String): List<Map<String, String?>> {
        val res = ArrayList<Map<String, String?>>()
        val db = extensionDb ?: return res
        val t = type.lowercase().removeSuffix("s")     // table stores singular
        val c = db.rawQuery(
            "SELECT alias_id, alias, is_preferred, source, creation_date FROM artifact_aliases " +
            "WHERE artifact_type = ? AND artifact_id = ? ORDER BY is_preferred DESC, alias COLLATE NOCASE",
            arrayOf(t, artifactId)
        )
        c.use {
            while (it.moveToNext()) {
                res.add(mapOf(
                    "alias_id" to it.getString(0),
                    "alias" to it.getString(1),
                    "is_preferred" to it.getString(2),
                    "source" to it.getString(3),
                    "creation_date" to (if (it.isNull(4)) null else it.getString(4))
                ))
            }
        }
        return res
    }

    /** Star an alias: set is_preferred=1 on aliasId and clear all siblings for the same artifact,
     *  in one transaction (no db-level one-preferred constraint — enforced here). */
    /** SWAP: promote an alias to the OFFICIAL local name and demote the old
     *  official name into that alias row. "Make this my preferred name."
     *  LOCAL-ONLY -- the AWS canonical (first-identified) name is never touched.
     *  GUARDED: trails are externally sourced / not user-maintained -> no swap.
     *  Generic across user types via spatialTableFor(). Two separate DB files,
     *  so ops are ordered + logged (no shared transaction). */
    fun setPreferredAlias(type: String, artifactId: String, aliasId: String) {
        val tl = type.lowercase()
        if (tl == "trail" || tl == "trails") {
            android.util.Log.i("AliasSwap", "skip: trails are not user-maintained")
            return
        }
        val edb = extensionDb ?: return
        val (table, idCol) = spatialTableFor(type) ?: return
        val sdb = spatialDb ?: return

        // read the alias text to promote
        var aliasText: String? = null
        try {
            edb.rawQuery("SELECT alias FROM artifact_aliases WHERE alias_id = ? LIMIT 1", arrayOf(aliasId)).use {
                if (it.moveToFirst()) aliasText = if (it.isNull(0)) null else it.getString(0)
            }
        } catch (e: Exception) { android.util.Log.w("AliasSwap", "read alias: ${e.message}") }
        if (aliasText == null) { android.util.Log.w("AliasSwap", "alias $aliasId not found"); return }

        // read the current official spatial name to demote
        var oldName: String? = null
        try {
            sdb.rawQuery("SELECT name FROM $table WHERE $idCol = ? LIMIT 1", arrayOf(artifactId)).use {
                if (it.moveToFirst()) oldName = if (it.isNull(0)) null else it.getString(0)
            }
        } catch (e: Exception) { android.util.Log.w("AliasSwap", "read name: ${e.message}") }

        // promote: spatial name <- alias text
        try {
            sdb.execSQL("UPDATE $table SET name = ? WHERE $idCol = ?", arrayOf<Any>(aliasText!!, artifactId))
        } catch (e: Exception) { android.util.Log.w("AliasSwap", "set name: ${e.message}") }

        // demote: alias row text <- old official name (skip if old name was blank)
        try {
            if (oldName != null && oldName!!.isNotBlank() && oldName != "Not Named") {
                edb.execSQL("UPDATE artifact_aliases SET alias = ? WHERE alias_id = ?", arrayOf<Any>(oldName!!, aliasId))
            } else {
                // nothing meaningful to demote -> remove the now-redundant alias row
                edb.execSQL("DELETE FROM artifact_aliases WHERE alias_id = ?", arrayOf<Any>(aliasId))
            }
        } catch (e: Exception) { android.util.Log.w("AliasSwap", "demote: ${e.message}") }

        android.util.Log.i("AliasSwap", "swapped $type $artifactId: name<->alias($aliasId)")
    }

    /** Edit one alias row's text. One-at-a-time alias maintenance. */
    fun renameAlias(aliasId: String, newText: String) {
        val edb = extensionDb ?: return
        try {
            edb.execSQL("UPDATE artifact_aliases SET alias = ? WHERE alias_id = ?", arrayOf<Any>(newText, aliasId))
            android.util.Log.i("AliasRename", "alias $aliasId -> '$newText'")
        } catch (e: Exception) { android.util.Log.w("AliasRename", "${e.message}") }
    }

    /** Delete one alias row. UI enforces the min-one guard before calling. */
    fun deleteAlias(aliasId: String) {
        val db = extensionDb ?: return
        db.execSQL("DELETE FROM artifact_aliases WHERE alias_id = ?", arrayOf(aliasId))
    }

    fun addAlias(type: String, artifactId: String, name: String, geomHash: String, creationDate: String?) {
        val e = extensionDb ?: return
        val ts = now()
        try {
            e.execSQL(
                "INSERT OR IGNORE INTO artifact_aliases (alias_id, artifact_type, artifact_id, alias, is_preferred, source, created_at, creation_date, geom_hash) VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(newId(), type, artifactId, name, 0, "add", ts, creationDate, geomHash)
            )
        } catch (ex: Exception) { android.util.Log.w("SpatialDb", "addAlias: ${ex.message}") }
    }

    /** Trail insert routed through the core (TrailImporter calls this).
     *  Returns the trail_id used (existing id if aliased/dropped, new id if inserted),
     *  or null on drop with no existing id available. */
    fun insertTrail(trailId: String, rawName: String?, wkt: String, minLat: Double, maxLat: Double,
                    minLon: Double, maxLon: Double, createdAt: String): Pair<String, AddDecision> {
        val db = spatialDb ?: throw IllegalStateException("SpatialDbManager not initialized")
        val name = notNamed(rawName)
        val geomHash = computeGeomHash(wkt)
        val decision = resolveByGeom("trail", name, geomHash)
        when (decision) {
            AddDecision.DROP -> { return Pair(findTrailIdByHash(geomHash) ?: trailId, decision) }
            AddDecision.ALIAS -> {
                val anchorId = findTrailIdByHash(geomHash) ?: trailId
                addAlias("trail", anchorId, name, geomHash, null)
                return Pair(anchorId, decision)
            }
            AddDecision.INSERT -> {
                db.execSQL(
                    "INSERT OR IGNORE INTO trails (trail_id,name,geometry,min_lat,max_lat,min_lon,max_lon,created_at,updated_at,geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(trailId, name, wkt, minLat, maxLat, minLon, maxLon, createdAt, createdAt, geomHash)
                )
                noteInserted("trail", geomHash, name)
                return Pair(trailId, decision)
            }
        }
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

    fun queryArtifactList(table: String, south: Double, west: Double, north: Double, east: Double): List<Map<String, String?>> {
        val db = spatialDb ?: return emptyList()
        val results = mutableListOf<Map<String, String?>>()
        try {
            val idCol = when (table) {
                "trails" -> "trail_id"
                "tracks" -> "track_id"
                "waypoints" -> "waypoint_id"
                "routes" -> "route_id"
                else -> return emptyList()
            }
            val typeCol = if (table == "waypoints") ", type" else ""
            val typeFilter = if (table == "tracks") " AND (type='TRACK' OR type IS NULL)" else ""
            // PROBESOUT-2026-09-03: the LIFECYCLE probes are gone. They were
            // hunting a trail that turned out to be TWO ROADS SHARING A NAME --
            // the one in the database drew correctly, ten miles from the one
            // Fred was looking at, which is not in any source. There was never
            // a display bug.
            // ⚠ WHAT THE PROBE'S COMMENT RECORDED IS STILL TRUE and stays on the
            // list: this query has `ORDER BY name LIMIT 200`, and with ~69% of
            // rows called "Not Named" and numbered roads sorting before letters,
            // it will bite at 200+ trails in view.
            val sql = "SELECT $idCol, name$typeCol FROM $table WHERE min_lat IS NOT NULL AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?$typeFilter ORDER BY name LIMIT 200"
            val cursor = db.rawQuery(sql, arrayOf(south.toString(), north.toString(), west.toString(), east.toString()))
            cursor.use {
                while (it.moveToNext()) {
                    val map = mutableMapOf<String, String?>()
                    map["id"] = it.getString(0)
                    map["name"] = it.getString(1)
                    if (table == "waypoints" && it.columnCount > 2) {
                        map["type"] = it.getString(2)
                    } else {
                        map["type"] = ""
                    }
                    results.add(map)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "queryArtifactList($table) failed: ${e.message}")
        }
        return results
    }

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
    fun queryAllTracksForCorridor(): List<Map<String, String?>> {
        val db = spatialDb ?: return emptyList()
        val results = mutableListOf<Map<String, String?>>()
        try {
            val sql = "SELECT track_id, name, geom_hash FROM tracks " +
                "WHERE (type='TRACK' OR type IS NULL) " +
                "ORDER BY name COLLATE NOCASE, geom_hash"
            db.rawQuery(sql, null).use {
                while (it.moveToNext()) {
                    results.add(mapOf(
                        "id" to it.getString(0),
                        "name" to it.getString(1),
                        "geom_hash" to it.getString(2)
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "queryAllTracksForCorridor failed: ${e.message}")
        }
        android.util.Log.i("SpatialDb", "CORRIDORQUERY-2026-08-05: ${results.size} track(s) for picker")
        return results
    }

    /** Rename a waypoint */
    fun renameWaypoint(id: String, newName: String) {
        spatialDb?.execSQL("UPDATE waypoints SET name=?, updated_at=? WHERE waypoint_id=?",
            arrayOf<Any>(newName, now(), id))
    }

    /** Delete a waypoint */
    fun deleteWaypoint(id: String) {
        spatialDb?.execSQL("DELETE FROM waypoints WHERE waypoint_id=?", arrayOf<Any>(id))
    }

    /** Change waypoint type */
    fun changeWaypointType(id: String, newType: String) {
        spatialDb?.execSQL("UPDATE waypoints SET type=?, updated_at=? WHERE waypoint_id=?",
            arrayOf<Any>(newType.lowercase(), now(), id))
    }

    /** Rename a route */
    fun renameRoute(id: String, newName: String) {
        spatialDb?.execSQL("UPDATE routes SET name=?, updated_at=? WHERE route_id=?",
            arrayOf<Any>(newName, now(), id))
    }

    /** Delete a route */
    fun deleteRoute(id: String) {
        spatialDb?.execSQL("DELETE FROM routes WHERE route_id=?", arrayOf<Any>(id))
    }

    /** Rename a track in spatial DB (file rename handled by ConvoyTrackOps) */
    fun renameTrackInDb(id: String, newName: String) {
        spatialDb?.execSQL("UPDATE tracks SET name=?, updated_at=? WHERE track_id=?",
            arrayOf<Any>(newName, now(), id))
    }

    /** COMPLETE track delete service (detail-panel "delete this track").
     *  Removes ALL of: spatial tracks row, track_properties row, every
     *  artifact_aliases row for the track, and the canonical <hash>.gpx file.
     *  Ordered + logged; each step best-effort (a missing piece is a no-op).
     *  SEPARATE from the add-time dupe/alias source-file cleanup (that lives in
     *  the add resolver and deletes the INCOMING source file, not <hash>.gpx). */
    fun deleteTrackFromDb(id: String) {
        // 1. read geom_hash first (needed to locate the file before the row is gone)
        var geomHash: String? = null
        try {
            spatialDb?.rawQuery("SELECT geom_hash FROM tracks WHERE track_id=? LIMIT 1", arrayOf(id))?.use {
                if (it.moveToFirst()) geomHash = if (it.isNull(0)) null else it.getString(0)
            }
        } catch (e: Exception) { android.util.Log.w("TrackDelete", "read hash: ${e.message}") }

        // 2. spatial row
        try {
            spatialDb?.execSQL("DELETE FROM tracks WHERE track_id=?", arrayOf<Any>(id))
        } catch (e: Exception) { android.util.Log.w("TrackDelete", "spatial row: ${e.message}") }

        // 3. extension: track_properties
        try {
            extensionDb?.execSQL("DELETE FROM track_properties WHERE track_id=?", arrayOf<Any>(id))
        } catch (e: Exception) { android.util.Log.w("TrackDelete", "track_properties: ${e.message}") }

        // 4. extension: all aliases for this track (artifact_type stored SINGULAR)
        try {
            extensionDb?.execSQL(
                "DELETE FROM artifact_aliases WHERE artifact_type=? AND artifact_id=?",
                arrayOf<Any>("track", id)
            )
        } catch (e: Exception) { android.util.Log.w("TrackDelete", "aliases: ${e.message}") }

        // 5. canonical <hash>.gpx file in my_tracks
        val h = geomHash
        if (h != null && h.isNotBlank()) {
            try {
                val tracksDir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                    "my_tracks"
                )
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

    private val GPX_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"GroupTrack\"\n" +
        "     xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
    private const val GPX_FOOTER = "</gpx>"

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Build GPX for a single waypoint by ID. Returns Pair(name, gpxContent) or null. */
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
                val coords = geom.removePrefix("POINT(").removeSuffix(")").trim().split(" ")
                if (coords.size < 2) return null
                val lon = coords[0]
                val lat = coords[1]
                val sb = StringBuilder()
                sb.append(GPX_HEADER)
                sb.append("  <wpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\">\n")
                sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
                if (type.isNotEmpty()) sb.append("    <type>").append(xmlEscape(type)).append("</type>\n")
                if (desc.isNotEmpty()) sb.append("    <desc>").append(xmlEscape(desc)).append("</desc>\n")
                sb.append("  </wpt>\n")
                sb.append(GPX_FOOTER)
                return Pair(name, sb.toString())
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
                val coordStr = geom.removePrefix("LINESTRING(").removeSuffix(")")
                val points = coordStr.split(",").mapNotNull { pt ->
                    val parts = pt.trim().split(" ")
                    if (parts.size >= 2) Pair(parts[1], parts[0]) else null
                }
                val sb = StringBuilder()
                sb.append(GPX_HEADER)
                sb.append("  <rte>\n")
                sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
                if (desc.isNotEmpty()) sb.append("    <desc>").append(xmlEscape(desc)).append("</desc>\n")
                var idx = 1
                for ((lat, lon) in points) {
                    sb.append("    <rtept lat=\"").append(lat).append("\" lon=\"").append(lon).append("\">\n")
                    sb.append("      <name>Pt ").append(idx).append("</name>\n")
                    sb.append("    </rtept>\n")
                    idx++
                }
                /* THE WHOLE PAYLOAD, so the recipe travels with the route.
                 * A recipient presses one button and gets six rides from the
                 * sender's parameters -- the entire point of keeping a recipe.
                 * Other apps ignore extensions they do not recognise. */
                if (notes != null) {
                    sb.append("    <extensions>\n")
                    sb.append("      <grouptrack:notes>")
                    sb.append(xmlEscape(notes.toString()))
                    sb.append("</grouptrack:notes>\n")
                    sb.append("    </extensions>\n")
                }
                sb.append("  </rte>\n")
                sb.append(GPX_FOOTER)
                return Pair(name, sb.toString())
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

        // Read the GPX file from Documents/my_tracks/{geom_hash}.gpx
        val tracksDir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS),
            "my_tracks"
        )
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
                val coordStr = geom.removePrefix("LINESTRING(").removeSuffix(")")
                val points = coordStr.split(",").mapNotNull { pt ->
                    val parts = pt.trim().split(" ")
                    if (parts.size >= 2) Pair(parts[1], parts[0]) else null
                }
                val sb = StringBuilder()
                sb.append(GPX_HEADER)
                sb.append("  <trk>\n")
                sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
                sb.append("    <trkseg>\n")
                for ((lat, lon) in points) {
                    sb.append("      <trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"/>\n")
                }
                sb.append("    </trkseg>\n")
                sb.append("  </trk>\n")
                sb.append(GPX_FOOTER)
                return Pair(name, sb.toString())
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "buildTrailGpx error: " + e.message)
            return null
        }
    }

        /** Close both databases. Call on app teardown. */
    fun close() {
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
