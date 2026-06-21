package com.geeksville.mesh.convoy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        val errors: Int, val message: String,
        val dropped: Int = 0, val aliased: Int = 0, val rejected: Int = 0
    )

    data class ImportProgress(
        val sourceId: String = "",
        val sourceName: String = "",
        val totalSources: Int = 1,
        val currentSourceIndex: Int = 0,
        val fetched: Int = 0,
        val inserted: Int = 0,
        val skipped: Int = 0,
        val dropped: Int = 0,
        val aliased: Int = 0,
        val rejected: Int = 0,
        val errors: Int = 0,
        val offset: Int = 0,
        val isComplete: Boolean = false,
        val message: String = ""
    )

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    private fun emitProgress(p: ImportProgress) { _progress.value = p }

    enum class UpdateMode { CARTO_ONLY, ALL }
    /** Set by the import/update panel before an import run. CARTO_ONLY = safe carto refresh;
     *  ALL = refresh all property fields + name on existing records (never geometry). */
    @Volatile var updateMode: UpdateMode = UpdateMode.CARTO_ONLY

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

        SpatialDbManager.beginDedupSession()
        var inserted = 0; var skipped = 0; var errors = 0; var fetched = 0; var rejected = 0; var dropped = 0; var aliased = 0
        var offset = 0; var hasMore = true

        while (hasMore) {
            val url = buildUrl(source, south, west, north, east, offset)
            Log.i(TAG, "Fetch offset=$offset source=${source.id}")
            emitProgress(ImportProgress(
                sourceId = sourceId, sourceName = source.name,
                fetched = fetched, inserted = inserted, skipped = skipped,
                rejected = rejected, errors = errors, offset = offset
            ))
            val json = httpGet(url)
            if (json == null) { errors++; break }

            val root = try { JSONObject(json) } catch (_: Exception) { errors++; break }
            if (root.has("error")) {
                Log.e(TAG, "API: ${root.optJSONObject("error")?.optString("message")}")
                errors++; break
            }

            val features = root.optJSONArray("features") ?: JSONArray()
            if (features.length() == 0) { hasMore = false; continue }

            val pageCount = features.length()
            fetched += pageCount
            sDb.beginTransaction(); eDb.beginTransaction()
            try {
                for (i in 0 until features.length()) {
                    val feat = features.getJSONObject(i)
                    // Client-side bbox rejection
                    if (south != null && west != null && north != null && east != null) {
                        val geom = feat.optJSONObject("geometry")
                        if (geom != null) {
                            val coords = geom.optJSONArray("paths") ?: geom.optJSONArray("coordinates")
                            if (coords != null && coords.length() > 0) {
                                val firstRing = coords.optJSONArray(0)
                                if (firstRing != null && firstRing.length() > 0) {
                                    val firstPt = firstRing.optJSONArray(0)
                                    if (firstPt != null) {
                                        val lon = firstPt.optDouble(0, 0.0)
                                        val lat = firstPt.optDouble(1, 0.0)
                                        if (lat < south || lat > north || lon < west || lon > east) {
                                            rejected++
                                            continue
                                        }
                                    }
                                }
                            }
                        }
                    }
                    when (insertFeature(sDb, eDb, feat, source)) {
                        "inserted" -> inserted++
                        "dropped" -> dropped++
                        "aliased" -> aliased++
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

        val processed = inserted + dropped + aliased + skipped + rejected + errors
        logIngestion(eDb, sourceId, inserted, dropped + aliased, south, west, north, east)
        val msg = "$processed processed: $inserted new, $dropped dupes dropped, $aliased aliased, $skipped already-imported, $rejected out-of-area, $errors errors"
        Log.i(TAG, "Done $sourceId: $msg")
        emitProgress(ImportProgress(
            sourceId = sourceId, sourceName = source.name,
            inserted = inserted, skipped = skipped, dropped = dropped, aliased = aliased,
            rejected = rejected, errors = errors,
            offset = offset, isComplete = true, message = msg
        ))
        // Write trail area JSON record
        writeTrailAreaJson(context, source, inserted, dropped + aliased, south, west, north, east)
        ImportResult(sourceId, inserted, skipped, errors, msg, dropped, aliased, rejected)
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

        // Dedup check (in-memory source-uid set, loaded once at session start)
        if (SpatialDbManager.sourceUidSeen(src.id, uid)) {
            // [2026-06-21] Existing record: update in place per updateMode (NOT skip), so a
            // re-run refreshes carto (and, in ALL mode, the other property fields + name).
            return updateExistingFeature(sDb, eDb, props, src, uid)
        }

        val name = props.optString(src.fieldName, "").ifEmpty { null }
        val wkt = geojsonGeomToWkt(geom)
        val bbox = computeBbox(geom)
        val tid = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        // Route through the shared add core (computes geom_hash, dupe/alias decision).
        val (anchorId, decision) = SpatialDbManager.insertTrail(tid, name, wkt, bbox[0], bbox[1], bbox[2], bbox[3], now)
        SpatialDbManager.markSourceUid(src.id, uid)

        val motorized = props.optString(src.fieldExtras.getOrDefault("motorized", ""), "")
        val surface = props.optString(src.fieldExtras.getOrDefault("surface", ""), "")
        val county = props.optString(src.fieldExtras.getOrDefault("county", ""), "")
        val steward = props.optString(src.fieldExtras.getOrDefault("manager", src.fieldExtras.getOrDefault("steward", "")), "")
        val uses = props.optString(src.fieldUse, "")
        val cartoCode = props.optString(src.fieldType, "")

        eDb.execSQL(
            "INSERT OR IGNORE INTO trail_properties (trail_id,source_id,source_unique_id,designated_uses,motorized_allowed,surface_type,carto_code,owner_steward,county,agency_id,ingested_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(anchorId, src.id, uid, uses, motorized, surface, cartoCode, steward, county, uid, now)
        )
        // [2026-06-21] Write carto to SPATIAL trails too on create (both stores populated).
        if (cartoCode.isNotEmpty()) {
            try { sDb.execSQL("UPDATE trails SET carto_code=? WHERE trail_id=?", arrayOf<Any?>(cartoCode, anchorId)) }
            catch (ex: Exception) { Log.w(TAG, "spatial carto write (create) failed: ${ex.message}") }
        }
        return when (decision) {
            SpatialDbManager.AddDecision.INSERT -> "inserted"
            SpatialDbManager.AddDecision.DROP -> "dropped"
            SpatialDbManager.AddDecision.ALIAS -> "aliased"
        }
    }

    /**
     * [2026-06-21] Update an already-imported trail in place (called from insertFeature's
     * dedup branch). Resolves the existing trail_id via trail_properties(source_id,uid).
     * CARTO_ONLY: carto_code in both stores. ALL: + name(spatial) + the other property
     * fields(extension). NEVER geometry/bbox/geom_hash. Logs before/after (tag IMPORTDIFF).
     */
    private fun updateExistingFeature(sDb: SQLiteDatabase, eDb: SQLiteDatabase, props: JSONObject, src: Src, uid: String): String {
        // resolve existing trail_id
        val trailId = try {
            eDb.rawQuery("SELECT trail_id FROM trail_properties WHERE source_id=? AND source_unique_id=? LIMIT 1", arrayOf(src.id, uid)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (ex: Exception) { Log.w(TAG, "resolve trail_id failed: ${ex.message}"); null } ?: return "skipped"

        // source values
        val cartoCode = props.optString(src.fieldType, "")
        val name = props.optString(src.fieldName, "").ifEmpty { null }
        val motorized = props.optString(src.fieldExtras.getOrDefault("motorized", ""), "")
        val surface = props.optString(src.fieldExtras.getOrDefault("surface", ""), "")
        val county = props.optString(src.fieldExtras.getOrDefault("county", ""), "")
        val steward = props.optString(src.fieldExtras.getOrDefault("manager", src.fieldExtras.getOrDefault("steward", "")), "")
        val uses = props.optString(src.fieldUse, "")

        fun dumpRow(tag: String) {
            try {
                eDb.rawQuery("SELECT designated_uses,motorized_allowed,surface_type,carto_code,owner_steward,county FROM trail_properties WHERE trail_id=?", arrayOf(trailId)).use {
                    if (it.moveToFirst()) Log.i("IMPORTDIFF", "$tag ext[$trailId] uses=${it.getString(0)} motor=${it.getString(1)} surf=${it.getString(2)} carto=${it.getString(3)} steward=${it.getString(4)} county=${it.getString(5)}")
                }
                sDb.rawQuery("SELECT name,carto_code,geom_hash FROM trails WHERE trail_id=?", arrayOf(trailId)).use {
                    if (it.moveToFirst()) Log.i("IMPORTDIFF", "$tag spa[$trailId] name=${it.getString(0)} carto=${it.getString(1)} ghash=${it.getString(2)}")
                }
            } catch (ex: Exception) { Log.w("IMPORTDIFF", "$tag dump failed: ${ex.message}") }
        }

        dumpRow("BEFORE")
        try {
            // carto in both stores (always)
            sDb.execSQL("UPDATE trails SET carto_code=? WHERE trail_id=?", arrayOf<Any?>(cartoCode, trailId))
            eDb.execSQL("UPDATE trail_properties SET carto_code=? WHERE trail_id=?", arrayOf<Any?>(cartoCode, trailId))
            if (updateMode == UpdateMode.ALL) {
                if (name != null) sDb.execSQL("UPDATE trails SET name=? WHERE trail_id=?", arrayOf<Any?>(name, trailId))
                eDb.execSQL(
                    "UPDATE trail_properties SET designated_uses=?,motorized_allowed=?,surface_type=?,owner_steward=?,county=? WHERE trail_id=?",
                    arrayOf<Any?>(uses, motorized, surface, steward, county, trailId)
                )
            }
        } catch (ex: Exception) { Log.w("IMPORTDIFF", "update failed: ${ex.message}"); return "error" }
        dumpRow("AFTER")
        return "updated"
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

    /** Write JSON trail area record to Documents/GroupTrack/data/trail_areas/ */
    private fun writeTrailAreaJson(
        context: Context, source: Src, inserted: Int, skipped: Int,
        south: Double?, west: Double?, north: Double?, east: Double?
    ) {
        try {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS),
                "GroupTrack/data/trail_areas"
            )
            if (!dir.exists()) dir.mkdirs()
            val isFullSource = (south == null && west == null && north == null && east == null)
            val json = JSONObject().apply {
                put("type", if (isFullSource) "full_source" else "area")
                put("source_id", source.id)
                put("source_name", source.name)
                put("status", "processed")
                put("trail_count", inserted)
                put("dupes_skipped", skipped)
                put("processed_at", Instant.now().toString())
                if (isFullSource) {
                    val catJson = context.assets.open("trail_sources.json").bufferedReader().use { it.readText() }
                    val sources = JSONObject(catJson).getJSONArray("sources")
                    for (j in 0 until sources.length()) {
                        val s = sources.getJSONObject(j)
                        if (s.optString("id") == source.id) {
                            val b = s.optJSONObject("boundary")
                            if (b != null) {
                                put("north", b.optDouble("n", 0.0))
                                put("south", b.optDouble("s", 0.0))
                                put("east", b.optDouble("e", 0.0))
                                put("west", b.optDouble("w", 0.0))
                            }
                            break
                        }
                    }
                } else if (!isFullSource) {
                    put("north", north); put("south", south)
                    put("east", east); put("west", west)
                }
            }
            val filename = if (isFullSource)
                "source_${source.id}.json"
            else {
                val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                "area_${ts}.json"
            }
            File(dir, filename).writeText(json.toString(2))
            Log.i(TAG, "Trail area record: $filename")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write trail area JSON: ${e.message}")
        }
    }

    /**
     * Explicit launch mode for the trail-source screen. Each launch point sets
     * this before navigating; the screen derives its starting step from it.
     * A stale pending-area JSON can never hijack SELECT_SOURCE because that mode
     * does not read the JSON. Extensible: add a value for a future import method.
     */
    enum class LaunchMode { SELECT_SOURCE, BY_AREA }

    @Volatile
    var launchMode: LaunchMode = LaunchMode.SELECT_SOURCE

    /** Write pending area JSON (unprocessed) for Method B signal */
    fun writePendingArea(north: Double, south: Double, east: Double, west: Double) {
        try {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS),
                "GroupTrack/data/trail_areas"
            )
            if (!dir.exists()) dir.mkdirs()
            val json = JSONObject().apply {
                put("type", "area")
                put("status", "unprocessed")
                put("north", north); put("south", south)
                put("east", east); put("west", west)
                put("created_at", Instant.now().toString())
            }
            File(dir, "pending_area.json").writeText(json.toString(2))
            Log.i(TAG, "Pending area written")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write pending area: ${e.message}")
        }
    }

    /** Read pending area JSON, return bbox or null */
    fun readPendingArea(): JSONObject? {
        return try {
            val f = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS),
                "GroupTrack/data/trail_areas/pending_area.json"
            )
            if (f.exists()) JSONObject(f.readText()) else null
        } catch (_: Exception) { null }
    }

    /** Delete pending area JSON after processing */
    fun clearPendingArea() {
        try {
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS),
                "GroupTrack/data/trail_areas/pending_area.json"
            ).let { if (it.exists()) it.delete() }
        } catch (_: Exception) {}
    }

    /** Scan all processed trail area JSONs for map overlay */
    fun scanTrailAreas(): List<JSONObject> {
        return try {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS),
                "GroupTrack/data/trail_areas"
            )
            if (!dir.exists()) return emptyList()
            dir.listFiles()
                ?.filter { it.extension == "json" && it.name != "pending_area.json" }
                ?.mapNotNull { try { JSONObject(it.readText()) } catch (_: Exception) { null } }
                ?.filter { it.optString("status") == "processed" }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** Check if a source has been fully imported */
    fun isSourceFullyImported(sourceId: String): Boolean {
        return try {
            val f = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS),
                "GroupTrack/data/trail_areas/source_${sourceId}.json"
            )
            if (f.exists()) {
                val j = JSONObject(f.readText())
                j.optString("status") == "processed" && j.optString("type") == "full_source"
            } else false
        } catch (_: Exception) { false }
    }

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
        var ver = 0
        try {
            val cur = db.rawQuery("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1", null)
            ver = if (cur.moveToFirst()) cur.getInt(0) else 0; cur.close()
        } catch (ex: Exception) {
            Log.w(TAG, "schema_version query failed: ${ex.message}")
            ver = 0
        }
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

    /** Import trailheads from bundled GeoJSON asset into waypoints table. */
    fun importTrailheads(
        context: Context,
        assetFile: String,
        sourceId: String = "ugrc",
        south: Double? = null,
        west: Double? = null,
        north: Double? = null,
        east: Double? = null
    ): Int {
        return try {
            SpatialDbManager.init(context)
            val json = context.assets.open(assetFile).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val features = root.getJSONArray("features")
            val total = features.length()
            var inserted = 0
            var skipped = 0
            var outsideArea = 0
            for (i in 0 until total) {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                val geom = feature.getJSONObject("geometry")
                val coords = geom.getJSONArray("coordinates")
                val lon = coords.getDouble(0)
                val lat = coords.getDouble(1)
                // Bbox filter — skip trailheads outside drawn area
                if (south != null && (lat < south || lat > north!! || lon < west!! || lon > east!!)) {
                    outsideArea++
                    continue
                }
                val name = props.optString("name",
                    props.optString("PrimaryName", "Unknown Trailhead"))
                val trailheadId = props.optString("id",
                    props.optString("TrailheadID", i.toString()))
                val waypointId = sourceId + "_th_" + trailheadId
                if (SpatialDbManager.insertWaypointWithId(waypointId, name, lat, lon, "trailhead")) {
                    inserted++
                } else {
                    skipped++
                }
                // Emit progress every 50 records
                if (i % 50 == 0) {
                    emitProgress(ImportProgress(
                        sourceId = sourceId,
                        sourceName = "Trailheads: $assetFile",
                        fetched = i + 1,
                        inserted = inserted,
                        skipped = skipped,
                        rejected = outsideArea,
                        offset = i
                    ))
                }
            }
            // Final progress
            emitProgress(ImportProgress(
                sourceId = sourceId,
                sourceName = "Trailheads: $assetFile",
                fetched = total,
                inserted = inserted,
                skipped = skipped,
                rejected = outsideArea,
                offset = total,
                isComplete = true,
                message = "$inserted trailheads imported, $skipped existing, $outsideArea outside area"
            ))
            Log.i(TAG, "Trailheads from $assetFile: $inserted imported, $skipped existing, $outsideArea outside area")
            inserted
        } catch (e: Exception) {
            Log.e(TAG, "Trailhead import failed: " + e.message)
            0
        }
    }

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
