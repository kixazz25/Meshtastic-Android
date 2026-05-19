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
        return dir
    }

    /** Initialize both databases. Call from app startup. */
    fun init(context: Context) {
        if (initialized) return
        try {
            val dir = dbDir()

            // Open/create spatial database
            val spatialFile = File(dir, SPATIAL_DB)
            val isNewSpatial = !spatialFile.exists()
            spatialDb = SQLiteDatabase.openOrCreateDatabase(spatialFile, null)
            if (isNewSpatial) {
                runSchemaFromAsset(context, spatialDb!!, "schema_spatial_v1.sql")
                android.util.Log.i(TAG, "Created spatial database: ${spatialFile.absolutePath}")
            } else {
                android.util.Log.i(TAG, "Opened spatial database: ${spatialFile.absolutePath}")
            }

            // Open/create extension database
            val extFile = File(dir, EXTENSION_DB)
            val isNewExt = !extFile.exists()
            extensionDb = SQLiteDatabase.openOrCreateDatabase(extFile, null)
            if (isNewExt) {
                runSchemaFromAsset(context, extensionDb!!, "schema_extension_v1.sql")
                android.util.Log.i(TAG, "Created extension database: ${extFile.absolutePath}")
            } else {
                android.util.Log.i(TAG, "Opened extension database: ${extFile.absolutePath}")
            }

            // Attach extension db to spatial for cross-db views (optional, for future use)
            // spatialDb?.execSQL("ATTACH DATABASE '${extFile.absolutePath}' AS ext")

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

    /** Run SQL schema file from assets */
    private fun runSchemaFromAsset(context: Context, db: SQLiteDatabase, assetName: String) {
        val sql = context.assets.open(assetName).bufferedReader().use { it.readText() }
        // Split on semicolons, execute each statement
        db.beginTransaction()
        try {
            sql.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("--") }
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

    /** Count rows in a table */
    private fun countRows(db: SQLiteDatabase, table: String): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $table", null)
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count
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
