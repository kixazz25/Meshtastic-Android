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
    private const val EXTENSION_DB = "grouptrack_data.db"
    private const val SPATIAL_SCHEMA_VERSION = 1
    private const val EXTENSION_SCHEMA_VERSION = 1

    private var spatialDb: SQLiteDatabase? = null
    private var extensionDb: SQLiteDatabase? = null
    private var initialized = false

    private fun dbDir(): File {
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
            // v3 migration: ensure routes columns on tracks table (routes share tracks table per decision log)
            // Routes are tracks with type='ROUTE'

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
                runSchemaFromAsset(context, spatialDb!!, "schema_spatial_v1.sql")
                android.util.Log.i(TAG, "Applied spatial schema: \${spatialFile.absolutePath}")
            } else {
                android.util.Log.i(TAG, "Opened spatial database: \${spatialFile.absolutePath}")
            }

            // Open/create extension database
            val extFile = File(dir, EXTENSION_DB)
            extensionDb = SQLiteDatabase.openOrCreateDatabase(extFile, null)
            if (!hasTable(extensionDb!!, "schema_version")) {
                runSchemaFromAsset(context, extensionDb!!, "schema_extension_v1.sql")
                android.util.Log.i(TAG, "Applied extension schema: \${extFile.absolutePath}")
            } else {
                android.util.Log.i(TAG, "Opened extension database: \${extFile.absolutePath}")
            }

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
        val results = mutableListOf<Map<String, String?>>()
        val cursor = db.rawQuery(
            "SELECT trail_id, name, geometry, carto_code FROM trails WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? LIMIT ?",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
        )
        while (cursor.moveToNext()) {
            val wkt = cursor.getString(2)
            if (!wkt.isNullOrEmpty()) {
                results.add(mapOf(
                    "trail_id" to cursor.getString(0),
                    "name" to cursor.getString(1),
                    "geometry" to wkt
                ))
            }
        }
        cursor.close()
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
            sb.append("\"PrimaryName\":\"" + s("name") + "\"")
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
    fun queryTracksByViewport(north: Double, south: Double, east: Double, west: Double, limit: Int = 500): List<Map<String, String?>> {
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
            sb.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"$name\",\"cartoCode\":\"$cc\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Sync tracks from GPX files in my_tracks directory */
    fun syncTracksFromFiles(context: android.content.Context) {
        val db = spatialDb ?: return
        try {
            val tracksDir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                "my_tracks"
            )
            if (!tracksDir.exists() || !tracksDir.isDirectory) {
                android.util.Log.i("TrackSync", "my_tracks directory not found")
                return
            }
            val gpxFiles = tracksDir.listFiles { f -> f.name.lowercase().endsWith(".gpx") } ?: return
            android.util.Log.i("TrackSync", "Found ${gpxFiles.size} GPX files to sync")

            val existing = mutableSetOf<String>()
            val cursor = db.rawQuery("SELECT name FROM tracks", null)
            cursor.use { while (it.moveToNext()) { existing.add(it.getString(0) ?: "") } }

            var inserted = 0
            for (file in gpxFiles) {
                val name = file.nameWithoutExtension
                if (existing.contains(name)) continue
                try {
                    val text = file.readText()
                    val coords = parseGpxTrackPoints(text)
                    if (coords.isEmpty()) continue

                    var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                    var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                    for ((lon, lat) in coords) {
                        if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                        if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                    }

                    val wkt = "LINESTRING(" + coords.joinToString(",") { "${it.first} ${it.second}" } + ")"
                    val trackId = java.util.UUID.randomUUID().toString()
                    val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())

                    db.execSQL(
                        "INSERT OR IGNORE INTO tracks (track_id, name, geometry, min_lat, max_lat, min_lon, max_lon, bbox, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        arrayOf(trackId, name, wkt, minLat, maxLat, minLon, maxLon, "$minLat,$minLon,$maxLat,$maxLon", now, now)
                    )
                    inserted++
                } catch (e: Exception) {
                    android.util.Log.e("TrackSync", "Failed to sync ${file.name}: ${e.message}")
                }
            }
            android.util.Log.i("TrackSync", "Sync complete: $inserted new tracks inserted")
        } catch (e: Exception) {
            android.util.Log.e("TrackSync", "Sync failed: ${e.message}")
        }
    }

    /** Parse GPX file for track points, returns list of (lon, lat) pairs */
    /** Parse GPX track points — used by ConvoyTrackOps for import */
    fun parseGpxTrackPoints(gpxText: String): List<Pair<Double, Double>> {
        val coords = mutableListOf<Pair<Double, Double>>()
        val regex = Regex("""lat="([^"]+)"\s+lon="([^"]+)"""")
        for (match in regex.findAll(gpxText)) {
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
            sb.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"$name\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}")
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
    fun insertWaypoint(name: String, lat: Double, lon: Double, type: String = "other"): String {
        val db = spatialDb ?: throw IllegalStateException("SpatialDbManager not initialized")
        val id = newId()
        val ts = now()
        val geometry = pointWkt(lat, lon)
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO waypoints (waypoint_id, name, geometry, type, min_lat, max_lat, min_lon, max_lon, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(id, name, geometry, type.lowercase(), lat, lat, lon, lon, ts, ts)
            )
            android.util.Log.i("SpatialDb", "Inserted waypoint: $name ($type) at $lat,$lon")
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "Waypoint insert failed: ${e.message}")
            // If column mismatch, try minimal insert
            try {
                db.execSQL(
                    "INSERT OR IGNORE INTO waypoints (waypoint_id, name, geometry, type, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                    arrayOf(id, name, geometry, type.lowercase(), ts, ts)
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
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO routes (route_id, name, geometry, min_lat, max_lat, min_lon, max_lon, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(id, name, geometryWkt, minLat, maxLat, minLon, maxLon, ts, ts)
            )
            android.util.Log.i("SpatialDb", "Inserted route: $name")
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "Route insert failed: ${e.message}")
        }
        return id
    }

    /**
     * Insert a track into the spatial DB (type='TRACK').
     * Used by import to write directly to DB alongside the GPX file.
     * Returns the generated track_id.
     */
    fun insertTrackToDb(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): String {
        val db = spatialDb ?: throw IllegalStateException("SpatialDbManager not initialized")
        val id = newId()
        val ts = now()
        val bbox = "$minLat,$minLon,$maxLat,$maxLon"
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO tracks (track_id, name, geometry, min_lat, max_lat, min_lon, max_lon, bbox, type, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(id, name, geometryWkt, minLat, maxLat, minLon, maxLon, bbox, "TRACK", ts, ts)
            )
            android.util.Log.i("SpatialDb", "Inserted track to DB: $name")
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "Track DB insert failed: ${e.message}")
        }
        return id
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
}
