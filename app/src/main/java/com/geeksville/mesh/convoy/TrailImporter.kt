package com.geeksville.mesh.convoy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/**
 * TrailImporter -- V2.5 Trail data import engine.
 *
 * Two import methods:
 *   Method A: importFullSource() -- imports all trails from one source
 *   Method B: importByArea() -- imports trails from multiple sources within a bounding box
 *
 * Both use the same pipeline: ArcGIS query -> GeoJSON parse -> dedup -> insert.
 * Pagination handles the 2000 record limit per request.
 * Dedup by source_id + source_unique_id prevents duplicate inserts.
 */
object TrailImporter {

    private const val TAG = "TrailImporter"
    private const val PAGE_SIZE = 2000

    data class ImportResult(
        val sourceId: String, val inserted: Int, val skipped: Int,
        val errors: Int, val message: String
    )

    /** Method A: Import all trails from a single source (no bbox filter) */
    suspend fun importFullSource(context: Context, sourceId: String): ImportResult {
        return importFromSource(context, sourceId, null, null, null, null)
    }

    /** Method B: Import trails from multiple sources within a bounding box */
    suspend fun importByArea(
        context: Context, sourceIds: List<String>,
        south: Double, west: Double, north: Double, east: Double
    ): List<ImportResult> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Area import: ${sourceIds.size} sources, bbox=[$south,$west,$north,$east]")
        sourceIds.map { sid ->
            importFromSource(context, sid, south, west, north, east)
        }
    }

    /** Core import: query one source with optional bbox, paginate, dedup, insert */
    private suspend fun importFromSource(
        context: Context, sourceId: String,
        south: Double?, west: Double?, north: Double?, east: Double?
    ): ImportResult = withContext(Dispatchers.IO) {
        val catalog = loadCatalog(context)
            ?: return@withContext ImportResult(sourceId, 0, 0, 1, "Catalog load failed")
        val source = catalog.firstOrNull { it.id == sourceId }
            ?: return@withContext ImportResult(sourceId, 0, 0, 1, "Source not found: $sourceId")

        SpatialDbManager.init(context)
        applyMigrationIfNeeded(context)
        val sDb = SpatialDbManager.getSpatialDb()
        val eDb = SpatialDbManager.getExtensionDb()
        if (sDb == null || eDb == null)
            return@withContext ImportResult(sourceId, 0, 0, 1, "DB not available")

        var inserted = 0; var skipped = 0; var errors = 0
        var offset = 0; var hasMore = true

        while (hasMore) {
            val url = buildUrl(source, south, west, north, east, offset)
            Log.i(TAG, "Fetch offset=$offset source=${source.id}")
            val json = httpGet(url)
            if (json == null) { errors++; break }

            val root = try { JSONObject(json) } catch (_: Exception) { errors++; break }
            if (root.has("error")) {
                Log.e(TAG, "API: ${root.optJSONObject("error")?.optString("message")}")
                errors++; break
            }

            val features = root.optJSONArray("features") ?: JSONArray()
            if (features.length() == 0) { hasMore = false; continue }

            sDb.beginTransaction(); eDb.beginTransaction()
            try {
                for (i in 0 until features.length()) {
                    when (insertFeature(sDb, eDb, features.getJSONObject(i), source)) {
                        "inserted" -> inserted++
                        "skipped" -> skipped++
                        else -> errors++
                    }
                }
                sDb.setTransactionSuccessful(); eDb.setTransactionSuccessful()
            } finally {
                sDb.endTransaction(); eDb.endTransaction()
            }

            hasMore = features.length() >= PAGE_SIZE
            offset += PAGE_SIZE
        }

        logIngestion(eDb, sourceId, inserted, skipped, south, west, north, east)
        val msg = "$inserted imported, $skipped dupes, $errors errors"
        Log.i(TAG, "Done $sourceId: $msg")
        ImportResult(sourceId, inserted, skipped, errors, msg)
    }

    // ── HTTP + URL ──────────────────────────────────────

    private fun buildUrl(src: Src, s: Double?, w: Double?, n: Double?, e: Double?, offset: Int): String {
        val sb = StringBuilder(src.queryUrl)
        sb.append("?f=geojson&outSR=4326&outFields=*")
        if (s != null && w != null && n != null && e != null) {
            sb.append("&geometry=$w,$s,$e,$n")
            sb.append("&geometryType=esriGeometryEnvelope&spatialRel=esriSpatialRelIntersects&inSR=4326")
        } else {
            sb.append("&where=1%3D1")
        }
        sb.append("&resultOffset=$offset&resultRecordCount=$PAGE_SIZE")
        return sb.toString()
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "GroupTrack/2.5")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (ex: Exception) {
            Log.e(TAG, "HTTP: ${ex.message}"); null
        }
    }

    // ── Insert pipeline ──────────────────────────────────

    private fun insertFeature(sDb: SQLiteDatabase, eDb: SQLiteDatabase, feature: JSONObject, src: Src): String {
        val props = feature.optJSONObject("properties") ?: return "error"
        val geom = feature.optJSONObject("geometry") ?: return "error"
        val uid = props.optString(src.fieldId, "").ifEmpty { return "error" }

        // Dedup check
        val cur = eDb.rawQuery(
            "SELECT 1 FROM trail_properties WHERE source_id=? AND source_unique_id=?",
            arrayOf(src.id, uid)
        )
        val exists = cur.moveToFirst(); cur.close()
        if (exists) return "skipped"

        val name = props.optString(src.fieldName, "").ifEmpty { null }
        val wkt = geojsonGeomToWkt(geom)
        val bbox = computeBbox(geom)
        val tid = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        sDb.execSQL(
            "INSERT OR IGNORE INTO trails (trail_id,name,geometry,min_lat,max_lat,min_lon,max_lon,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(tid, name, wkt, bbox[0], bbox[1], bbox[2], bbox[3], now, now)
        )

        val motorized = props.optString(src.fieldExtras.getOrDefault("motorized", ""), "")
        val surface = props.optString(src.fieldExtras.getOrDefault("surface", ""), "")
        val county = props.optString(src.fieldExtras.getOrDefault("county", ""), "")
        val steward = props.optString(src.fieldExtras.getOrDefault("manager", src.fieldExtras.getOrDefault("steward", "")), "")
        val uses = props.optString(src.fieldUse, "")
        val cartoCode = props.optString(src.fieldType, "")

        eDb.execSQL(
            "INSERT OR IGNORE INTO trail_properties (trail_id,source_id,source_unique_id,designated_uses,motorized_allowed,surface_type,carto_code,owner_steward,county,agency_id,ingested_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(tid, src.id, uid, uses, motorized, surface, cartoCode, steward, county, uid, now)
        )
        return "inserted"
    }

    // ── Geometry conversion ──────────────────────────────

    fun geojsonGeomToWkt(geom: JSONObject): String {
        val type = geom.optString("type")
        val coords = geom.optJSONArray("coordinates") ?: return ""
        return when (type) {
            "LineString" -> "LINESTRING(" + coordRingToWkt(coords) + ")"
            "MultiLineString" -> {
                val lines = (0 until coords.length()).map { i ->
                    "(" + coordRingToWkt(coords.getJSONArray(i)) + ")"
                }
                "MULTILINESTRING(" + lines.joinToString(",") + ")"
            }
            "Point" -> "POINT(${coords.getDouble(0)} ${coords.getDouble(1)})"
            else -> ""
        }
    }

    private fun coordRingToWkt(arr: JSONArray): String {
        return (0 until arr.length()).joinToString(",") { i ->
            val c = arr.getJSONArray(i)
            "${c.getDouble(0)} ${c.getDouble(1)}"
        }
    }

    fun computeBbox(geom: JSONObject): DoubleArray {
        val pts = mutableListOf<Pair<Double, Double>>()
        extractCoords(geom.optJSONArray("coordinates") ?: JSONArray(), pts)
        if (pts.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for ((lon, lat) in pts) {
            if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
        }
        return doubleArrayOf(minLat, maxLat, minLon, maxLon)
    }

    private fun extractCoords(arr: JSONArray, out: MutableList<Pair<Double, Double>>) {
        if (arr.length() == 0) return
        if (arr.opt(0) is Number) {
            if (arr.length() >= 2) out.add(Pair(arr.getDouble(0), arr.getDouble(1)))
        } else {
            for (i in 0 until arr.length()) {
                arr.optJSONArray(i)?.let { extractCoords(it, out) }
            }
        }
    }

    // ── Ingestion log ────────────────────────────────────

    private fun logIngestion(db: SQLiteDatabase, sid: String, count: Int, dupes: Int,
                             s: Double?, w: Double?, n: Double?, e: Double?) {
        val bounds = if (s != null) "{\"s\":$s,\"w\":$w,\"n\":$n,\"e\":$e}" else null
        db.execSQL(
            "INSERT INTO source_ingestions (ingestion_id,source_id,ingested_at,trail_count,dupes_skipped,bounds_json) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(UUID.randomUUID().toString(), sid, Instant.now().toString(), count, dupes, bounds)
        )
    }

    // ── Schema migration ─────────────────────────────────

    private fun applyMigrationIfNeeded(context: Context) {
        val db = SpatialDbManager.getSpatialDb() ?: return
        val cur = db.rawQuery("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1", null)
        val ver = if (cur.moveToFirst()) cur.getInt(0) else 0; cur.close()
        if (ver >= 2) return
        Log.i(TAG, "Applying spatial schema v1 -> v2")
        try {
            val sql = context.assets.open("schema_spatial_v1_to_v2.sql").bufferedReader().use { it.readText() }
            sql.split(";").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("--") }.forEach { stmt ->
                try { db.execSQL("$stmt;") } catch (ex: Exception) { Log.w(TAG, "Skip: ${ex.message}") }
            }
            db.execSQL("DELETE FROM schema_version")
            db.execSQL("INSERT INTO schema_version VALUES (2, datetime('now'))")
        } catch (ex: Exception) { Log.e(TAG, "Migration failed: ${ex.message}") }
    }

    // ── Catalog loader ───────────────────────────────────

    data class Src(
        val id: String, val name: String, val queryUrl: String, val maxRecords: Int,
        val fieldId: String, val fieldName: String, val fieldType: String, val fieldUse: String,
        val fieldExtras: Map<String, String>
    )

    private fun loadCatalog(context: Context): List<Src>? {
        return try {
            val json = context.assets.open("trail_sources.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val sources = root.getJSONArray("sources")
            (0 until sources.length()).mapNotNull { i ->
                val s = sources.getJSONObject(i)
                if (s.optString("status") == "display_only_not_queryable") return@mapNotNull null
                val f = s.getJSONObject("fields")
                val extras = mutableMapOf<String, String>()
                f.keys().forEach { k -> if (k !in listOf("id", "name", "type", "use")) extras[k] = f.getString(k) }
                Src(s.getString("id"), s.getString("name"), s.getString("query_url"),
                    s.optInt("max_records", 2000),
                    f.getString("id"), f.getString("name"), f.optString("type", ""), f.optString("use", ""), extras)
            }
        } catch (ex: Exception) { Log.e(TAG, "Catalog: ${ex.message}"); null }
    }
}
