package com.grouptrack.shared.imports

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtGeom
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtJava
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/**
 * STEP15F-2026-11-10 (2.8 step 15f): the AGENCY TRAIL IMPORT -- 2.7e's TrailImporter.kt (V2.5 trail data import engine), PORTED AS IS
 * (Fred 11-08 18:23 "port as is, certify as is"). The app's TrailImporter becomes the front door at the 15f switch.
 *   Method A importFullSource (one source, no box), Method B importByArea (sources in a box), FETCHFIRST importSavedByArea (the same loop
 *   reading SAVED pages, one JSON page per line) and fetchSourceToFile (query once, save every page); the trailheads asset import; the
 *   trail-area records (data/trail_areas: processed records, the pending area); the ingestion log; the spatial v1 -> v2 migration.
 * WHAT CHANGED, and only this (each a platform rule this project already certified):
 *  - org.json -> kotlinx elements read and written the org.json way (GtOrgJson, 15a): optString / optInt / optLong / optDouble give
 *    Android's answers (a JSON null optString'd is "null"; a number's text is Double.toString's); the trail-area records are written by
 *    GtOrgJson.pretty = JSONObject.toString(2). getJSONObject / getJSONArray / getDouble / getString throw where Android's throw.
 *  - THE HASH TRAP (D13, as 15e): 2.7e builds the WKT with Kotlin templates "${lon} ${lat}" (Java's Double.toString on Android, not on the
 *    iPad) and the geom_hash is taken over that text -> GtGeom.lineLonLat / multiLineLonLat / pointLonLat (GtJava.jd: Java's text).
 *    Every other Double put in a text (the box in the query URLs, the ingestion bounds) -> GtJava.jd for the same reason.
 *  - SpatialDbManager's writes (beginDedupSession / sourceUidSeen / markSourceUid / insertTrail / insertWaypointWithId) -> the SAME shared
 *    GtWrite they already delegate to (step 11); sDb / eDb execSQL / rawQuery -> statements on [conn] / [dataConn]. A page's two
 *    transactions (2.7e: beginTransaction on both, setTransactionSuccessful on both, endTransaction on both in a finally) -> BEGIN on
 *    both, COMMIT on both when the page finished, ROLLBACK on both when it threw.
 *  - fillName's executeUpdateDelete count -> the same WHERE counted first, then the UPDATE (the count of rows the UPDATE matches, without
 *    SQLite's changes(), which an Android connection pool may answer from another connection).
 *  - context.assets -> env.platform.readAsset; GroupTrackStorage.dir("data/trail_areas") -> env.paths.dir (created, as 2.7e);
 *    UUID.randomUUID / Instant.now -> env.ids / env.clock; LocalDateTime.now() "yyyyMMdd_HHmmss" -> env.clock.localNow();
 *    httpGet (HttpURLConnection) -> env.http.get (GtHttpAndroid on Android = 2.7e's httpGet); java.net.URLEncoder.encode(q, "UTF-8")
 *    -> [urlEncode] (the same form encoding); java.io.File -> okio through env.files.fs; Log -> gtLogger (same tags).
 *  - TrailImporter's StateFlow progress -> [progress] (the front door feeds its flow); its @Volatile updateMode -> [updateMode];
 *    SpatialDbManager.init(context) -> the front door's (it opens the handles this object is built on); withContext(IO) -> the caller's
 *    thread (no coroutines in shared code, INVENTORY Q4).
 * KNOWN, kept as 2.7e (port as is): scanTrailAreas returns the files in the platform's listing order (D9 -- harness only, Fred 11-09);
 *   fetchSourceToFile leaves its .part file when a page fails at the smallest size (2.7e returns inside the writer's use without deleting it).
 *
 * @param dataConn grouptrack_data.db -- CODE RULE 1: optional exactly as 2.7e's `SpatialDbManager.getExtensionDb()` (null before the
 *        first import): an import then answers "DB not available", as 2.7e.
 * @param progress every ImportProgress 2.7e emitted, in order (the front door sets its StateFlow; the cert records them).
 */
class GtTrailImport(private val conn: SQLiteConnection, private val dataConn: SQLiteConnection?,
                    private val writer: GtWrite, private val env: GtImportEnv, private val progress: (ImportProgress) -> Unit) {

    private val log = gtLogger(TAG)
    private val logDiff = gtLogger("IMPORTDIFF")
    private val fs get() = env.files.fs

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

    enum class UpdateMode { CARTO_ONLY, ALL }
    /** Set by the import/update panel before an import run (2.7e TrailImporter.updateMode). CARTO_ONLY = safe carto refresh;
     *  ALL = refresh all property fields + name on existing records (never geometry). */
    var updateMode: UpdateMode = UpdateMode.CARTO_ONLY

    data class Src(
        val id: String, val name: String, val queryUrl: String, val maxRecords: Int,
        val fieldId: String, val fieldName: String, val fieldType: String, val fieldUse: String,
        val fieldExtras: Map<String, String>,
        val sourceType: String = "arcgis_rest"
    )

    companion object {
        private const val TAG = "TrailImporter"
        private const val PAGE_SIZE = 2000
        private val EXTRA_KEYS = listOf(
            "status", "trail_class", "system_name", "recreation_area",
            "trans_network", "data_source", "comments", "horse_allowed",
            "hike_difficulty", "bike_difficulty", "ref_code", "operator",
            "width_raw", "maxwidth_raw", "incline", "sac_scale", "mtb_scale",
            "trail_visibility", "other_restrictions"
        )

        /** java.net.URLEncoder.encode(s, "UTF-8"): a-z A-Z 0-9 . - * _ kept, space -> '+', every other UTF-8 byte -> %XX (upper case). */
        fun urlEncode(s: String): String {
            val sb = StringBuilder()
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                when {
                    (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                        c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> sb.append(c.toChar())
                    c == ' '.code -> sb.append('+')
                    else -> { sb.append('%'); sb.append("0123456789ABCDEF"[c shr 4]); sb.append("0123456789ABCDEF"[c and 15]) }
                }
            }
            return sb.toString()
        }

        /** A Kotlin string template of a Double? ("$x"): Java's Double.toString, or "null". */
        private fun jdN(d: Double?): String = if (d == null) "null" else GtJava.jd(d)
    }

    private fun emitProgress(p: ImportProgress) { progress(p) }

    // ---- org.json's throwing getters on kotlinx elements (GtOrgJson has the opt* answers) ----
    private class JsonEx(msg: String) : RuntimeException(msg)   // org.json's JSONException stand-in (only the class is caught)
    private fun parseObject(text: String): JsonObject =
        (try { GtOrgJson.parse(text) } catch (e: Exception) { throw JsonEx("parse: ${e.message}") }) as? JsonObject ?: throw JsonEx("not a JSON object")
    private fun optObj(o: JsonObject?, k: String): JsonObject? = o?.get(k) as? JsonObject
    private fun optArr(o: JsonObject?, k: String): JsonArray? = o?.get(k) as? JsonArray
    private fun optObj(a: JsonArray?, i: Int): JsonObject? = a?.getOrNull(i) as? JsonObject
    private fun optArr(a: JsonArray?, i: Int): JsonArray? = a?.getOrNull(i) as? JsonArray
    private fun getObj(o: JsonObject, k: String): JsonObject = o[k] as? JsonObject ?: throw JsonEx("JSONObject[$k] not a JSONObject")
    private fun getArr(o: JsonObject, k: String): JsonArray = o[k] as? JsonArray ?: throw JsonEx("JSONObject[$k] not a JSONArray")
    private fun getObj(a: JsonArray, i: Int): JsonObject = a.getOrNull(i) as? JsonObject ?: throw JsonEx("JSONArray[$i] not a JSONObject")
    private fun getArr(a: JsonArray, i: Int): JsonArray = a.getOrNull(i) as? JsonArray ?: throw JsonEx("JSONArray[$i] not a JSONArray")
    /** JSONArray.getDouble: JSON.toDouble or throw (two fallbacks tell "missing / not a number" from a real value). */
    private fun getDouble(a: JsonArray, i: Int): Double {
        val x = GtOrgJson.optDouble(a, i, 0.0)
        if (x == 0.0 && GtOrgJson.optDouble(a, i, 1.0) == 1.0) throw JsonEx("JSONArray[$i] is not a number")
        return x
    }
    /** JSONObject.getString: present -> JSON.toString(value) (a JSON null -> "null"); missing -> throws. */
    private fun getString(o: JsonObject, k: String): String = if (o.containsKey(k)) GtOrgJson.valueText(o[k]) ?: "null" else throw JsonEx("No value for $k")
    /** JSONArray.opt(i) is Number: an unquoted literal org.json reads as Integer / Long / Double. */
    private fun isNumber(e: JsonElement?): Boolean {
        if (e !is JsonPrimitive || e is JsonNull || e.isString) return false
        val t = e.content
        if (t.equals("true", true) || t.equals("false", true) || t.equals("null", true)) return false
        return GtOrgJson.orgKind(t) !is GtOrgJson.OrgNum.Unquoted
    }

    // ---- statements ----
    private fun exec(c: SQLiteConnection, sql: String, args: List<Any?>) {
        val st = c.prepare(sql)
        try {
            args.forEachIndexed { i, a -> when (a) { null -> st.bindNull(i + 1); is Double -> st.bindDouble(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); is Long -> st.bindLong(i + 1, a); else -> st.bindText(i + 1, a.toString()) } }
            st.step()
        } finally { st.close() }
    }
    private fun queryText(c: SQLiteConnection, sql: String, args: List<String>, cols: Int): Array<String?>? {
        val st = c.prepare(sql)
        try {
            args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
            if (!st.step()) return null
            return Array(cols) { i -> if (st.isNull(i)) null else st.getText(i) }
        } finally { st.close() }
    }

    // =================================================================================================================================
    // Methods A / B / FETCHFIRST
    // =================================================================================================================================

    /** Method A: Import all trails from a single source (no bbox filter) */
    fun importFullSource(sourceId: String): ImportResult =
        importFromSource(sourceId, null, null, null, null, emptyList())   // FETCHFIRST-2026-11-02: from the web

    /** Method B: Import trails from multiple sources within a bounding box */
    fun importByArea(sourceIds: List<String>, south: Double, west: Double, north: Double, east: Double): List<ImportResult> {
        log.i { "Area import: ${sourceIds.size} sources, bbox=[${GtJava.jd(south)},${GtJava.jd(west)},${GtJava.jd(north)},${GtJava.jd(east)}]" }
        return sourceIds.map { sid -> importFromSource(sid, south, west, north, east, emptyList()) }
    }

    /** FETCHFIRST-2026-11-02: import one source from its SAVED pages instead of the web. [files] = the source's saved file per state. */
    fun importSavedByArea(sourceId: String, files: List<Path>, south: Double, west: Double, north: Double, east: Double): List<ImportResult> {
        log.i { "FETCHFIRST saved import: $sourceId from ${files.size} file(s), bbox=[${GtJava.jd(south)},${GtJava.jd(west)},${GtJava.jd(north)},${GtJava.jd(east)}]" }
        return listOf(importFromSource(sourceId, south, west, north, east, files))
    }

    /**
     * FETCHFIRST-2026-11-02: query one agency source for an area ONCE and save every page, one JSON page per line, into [dest]
     * (written as .part, renamed only when whole). [onPage] (pages, features, expected -- -1 = the server would not count).
     * FETCHFIX-2026-11-02: counts first, halves the page on a failed page, advances by what it received, and fails short of the count.
     */
    fun fetchSourceToFile(sourceId: String, south: Double, west: Double, north: Double, east: Double,
                          dest: Path, onPage: (Int, Int, Int) -> Unit): Boolean {
        val source = loadCatalog()?.firstOrNull { it.id == sourceId }
        if (source == null) { log.e { "FETCHFIRST $sourceId: not in the catalog" }; return false }
        val overpass = source.sourceType == "overpass"
        val part = (dest.toString() + ".part").toPath()
        part.parent?.let { fs.createDirectories(it) }; fs.delete(part, mustExist = false)
        var offset = 0; var pages = 0; var feats = 0
        var size = PAGE_SIZE
        val expected = if (overpass) -1 else countIn(source, south, west, north, east)
        log.i { "FETCHFIX $sourceId: server count in area = $expected" }
        try {
            var gaveUp = false
            val w = fs.sink(part).buffer()   // part.bufferedWriter().use { } -- closed in the finally, as use closes it
            try {
                while (true) {
                    val url = buildUrl(source, south, west, north, east, offset, size)
                    val json = env.http.get(url, if (overpass) 180_000 else 60_000)
                    val root = if (json == null) null else try { parseObject(json) } catch (e: Exception) { null }
                    if (root == null || root.containsKey("error")) {
                        if (!overpass && size > 100) {
                            size /= 2
                            log.w { "FETCHFIX $sourceId: page at $offset failed -- retrying at $size per page" }
                            continue
                        }
                        log.e { "FETCHFIRST $sourceId: page at $offset failed at $size per page -- giving up ($feats of $expected)" }
                        gaveUp = true; break   // 2.7e: return false from inside the writer's use -- the .part stays (see the header)
                    }
                    val n = (if (overpass) optArr(root, "elements") else optArr(root, "features"))?.size ?: 0
                    if (n == 0) break
                    w.writeUtf8(json!!.replace('\n', ' ').replace('\r', ' ')); w.writeUtf8("\n")
                    pages++; feats += n; onPage(pages, feats, expected)
                    if (overpass) break
                    if (expected >= 0) { if (feats >= expected) break } else if (n < size) break
                    offset += n
                }
            } finally { w.close() }
            if (gaveUp) return false
            if (expected >= 0 && feats < expected) {
                log.e { "FETCHFIX $sourceId: only $feats of $expected -- not saved" }
                fs.delete(part, mustExist = false); return false
            }
        } catch (e: Exception) {
            log.e { "FETCHFIRST $sourceId: fetch failed -- ${e::class.simpleName} ${e.message}" }
            fs.delete(part, mustExist = false); return false
        }
        fs.delete(dest, mustExist = false)
        val ok = try { fs.atomicMove(part, dest); true } catch (e: Exception) { false }   // File.renameTo: false, never throws
        log.i { "FETCHFIRST $sourceId: $pages page(s), $feats feature(s) saved -> $dest ok=$ok" }
        return ok
    }

    /** FETCHFIX-2026-11-02: the server's count of features in the area (ArcGIS returnCountOnly); -1 when it will not say. */
    private fun countIn(src: Src, s: Double, w: Double, n: Double, e: Double): Int {
        val url = src.queryUrl + "?f=json&where=1%3D1&geometry=" + GtJava.jd(w) + "," + GtJava.jd(s) + "," + GtJava.jd(e) + "," + GtJava.jd(n) +
            "&geometryType=esriGeometryEnvelope&spatialRel=esriSpatialRelIntersects&inSR=4326&returnCountOnly=true"
        val j = env.http.get(url, 60_000) ?: return -1
        return try { GtOrgJson.optInt(parseObject(j), "count", -1) } catch (ex: Exception) { -1 }
    }

    /** Core import: query one source with optional bbox, paginate, dedup, insert. [saved] empty = query the web; else read these pages. */
    private fun importFromSource(sourceId: String, south: Double?, west: Double?, north: Double?, east: Double?, saved: List<Path>): ImportResult {
        val catalog = loadCatalog() ?: return ImportResult(sourceId, 0, 0, 1, "Catalog load failed")
        val source = catalog.firstOrNull { it.id == sourceId } ?: return ImportResult(sourceId, 0, 0, 1, "Source not found: $sourceId")

        // OVERPASS-REQUIRE-BBOX-2026-07-27: an Overpass source with no bbox is a user error, not a query to attempt.
        if (source.sourceType == "overpass" && (south == null || west == null || north == null || east == null)) {
            val msg = "OpenStreetMap import needs an area. Draw one on the map, " +
                      "tick Trails, then select OpenStreetMap from the suggested sources."
            log.w { "Refused unbounded overpass query for ${source.id}: no bbox" }
            return ImportResult(sourceId, 0, 0, 1, msg)
        }

        applyMigrationIfNeeded()
        val eDb = dataConn ?: return ImportResult(sourceId, 0, 0, 1, "DB not available")
        val sDb = conn

        writer.beginDedupSession()
        var inserted = 0; var skipped = 0; var errors = 0; var fetched = 0; var rejected = 0; var dropped = 0; var aliased = 0
        var named = 0   // IMPORTQUIET-2026-11-02
        var offset = 0; var hasMore = true
        val savedPages = sequence { for (f in saved) fs.read(f) { while (true) { val l = readUtf8Line() ?: break; yield(l) } } }.iterator()

        while (hasMore) {
            val url = buildUrl(source, south, west, north, east, offset, PAGE_SIZE)
            log.i { "Fetch offset=$offset source=${source.id}" }
            emitProgress(ImportProgress(
                sourceId = sourceId, sourceName = source.name,
                fetched = fetched, inserted = inserted, skipped = skipped,
                rejected = rejected, errors = errors, offset = offset
            ))
            val json = if (saved.isNotEmpty()) (if (savedPages.hasNext()) savedPages.next() else "{\"features\":[],\"elements\":[]}")
                       else env.http.get(url, if (source.sourceType == "overpass") 180_000 else 60_000)
            if (json == null) { errors++; break }

            val root = try { parseObject(json) } catch (_: Exception) { errors++; break }
            if (root.containsKey("error")) {
                log.e { "API: ${optObj(root, "error")?.let { GtOrgJson.optString(it, "message") }}" }
                errors++; break
            }

            val features = if (source.sourceType == "overpass") overpassToFeatures(root) else optArr(root, "features") ?: JsonArray(emptyList())
            if (features.size == 0) { hasMore = false; continue }

            val pageCount = features.size
            fetched += pageCount
            exec(sDb, "BEGIN", emptyList()); exec(eDb, "BEGIN", emptyList())
            var ok = false
            try {
                for (i in 0 until features.size) {
                    val feat = getObj(features, i)
                    // Client-side bbox rejection
                    if (south != null && west != null && north != null && east != null) {
                        val geom = optObj(feat, "geometry")
                        if (geom != null) {
                            val coords = optArr(geom, "paths") ?: optArr(geom, "coordinates")
                            if (coords != null && coords.size > 0) {
                                val firstRing = optArr(coords, 0)
                                if (firstRing != null && firstRing.size > 0) {
                                    val firstPt = optArr(firstRing, 0)
                                    if (firstPt != null) {
                                        val lon = GtOrgJson.optDouble(firstPt, 0, 0.0)
                                        val lat = GtOrgJson.optDouble(firstPt, 1, 0.0)
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
                        "named_drop" -> { dropped++; named++ }
                        "named_alias" -> { aliased++; named++ }
                        "aliased" -> aliased++
                        "skipped" -> skipped++
                        "unchanged" -> skipped++
                        "updated" -> { skipped++; named++ }
                        else -> errors++
                    }
                }
                ok = true
            } finally {
                if (ok) { exec(sDb, "COMMIT", emptyList()); exec(eDb, "COMMIT", emptyList()) }
                else { try { exec(sDb, "ROLLBACK", emptyList()) } catch (_: Exception) {}; try { exec(eDb, "ROLLBACK", emptyList()) } catch (_: Exception) {} }
            }

            // OSM-IMPORT-2026-07-27: Overpass returns the WHOLE bbox in one response -- it does not page.
            hasMore = if (saved.isNotEmpty()) true
                      else if (source.sourceType == "overpass") false
                      else features.size >= PAGE_SIZE
            offset += PAGE_SIZE
        }

        val processed = inserted + dropped + aliased + skipped + rejected + errors
        logIngestion(eDb, sourceId, inserted, dropped + aliased, south, west, north, east)
        val msg = "$processed processed: $inserted new, $dropped dupes dropped, $aliased aliased, $skipped already-imported ($named named), $rejected out-of-area, $errors errors"
        log.i { "Done $sourceId: $msg" }
        emitProgress(ImportProgress(
            sourceId = sourceId, sourceName = source.name,
            inserted = inserted, skipped = skipped, dropped = dropped, aliased = aliased,
            rejected = rejected, errors = errors,
            offset = offset, isComplete = true, message = msg
        ))
        writeTrailAreaJson(source, inserted, dropped + aliased, south, west, north, east)
        return ImportResult(sourceId, inserted, skipped, errors, msg, dropped, aliased, rejected)
    }

    // ---- Overpass (OSM-IMPORT-2026-07-27) ----

    /** OVERPASS-REQUIRE-BBOX-2026-07-27: NEVER default to the whole planet. */
    private fun buildOverpassUrl(src: Src, s: Double?, w: Double?, n: Double?, e: Double?): String {
        if (s == null || w == null || n == null || e == null) {
            log.e { "buildOverpassUrl called with no bbox - refusing" }
            return ""
        }
        val bbox = "(" + GtJava.jd(s) + "," + GtJava.jd(w) + "," + GtJava.jd(n) + "," + GtJava.jd(e) + ")"
        val q = "[out:json][timeout:180];(" +
                "way[\"highway\"=\"track\"]" + bbox + ";" +
                "way[\"highway\"=\"path\"]" + bbox + ";" +
                ");out geom;"
        return src.queryUrl + "?data=" + urlEncode(q)
    }

    private fun osmIsYes(v: String): Boolean =
        v.trim().lowercase() in listOf("yes", "designated", "permissive", "official", "destination", "true", "1")

    private fun osmIsNo(v: String): Boolean =
        v.trim().lowercase() in listOf("no", "private", "false", "0")

    /** Collapse OSM's several motor-access tags into ONE Yes/No/blank value (highway=track with NO access tag -> blank). */
    private fun osmMotorized(tags: JsonObject): String {
        for (t in listOf("motor_vehicle", "atv", "motorcycle", "4wd_only", "motorcar", "ohv")) {
            val v = GtOrgJson.optString(tags, t, "")
            if (v.isBlank()) continue
            if (osmIsYes(v)) return "Yes"
            if (osmIsNo(v)) return "No"
        }
        return ""
    }

    /** Synthesize a DesignatedUses string from the access tag set. */
    private fun osmUses(tags: JsonObject, motorized: String): String {
        val parts = ArrayList<String>()
        if (motorized == "Yes") parts.add("Motorized")
        val hw = GtOrgJson.optString(tags, "highway", "")
        if (osmIsYes(GtOrgJson.optString(tags, "bicycle", "")) || hw == "path") parts.add("Bike")
        if (osmIsYes(GtOrgJson.optString(tags, "foot", "")) || hw == "path") parts.add("Hike")
        if (osmIsYes(GtOrgJson.optString(tags, "horse", ""))) parts.add("Equestrian")
        if (parts.isEmpty()) return ""
        if (parts.size > 2) return "Multiuse"
        return parts.joinToString("/")
    }

    /** elements[] -> the features array insertFeature already consumes (LineString; name, else ref, and NOTHING further). */
    private fun overpassToFeatures(root: JsonObject): JsonArray {
        val out = ArrayList<JsonElement>()
        val els = optArr(root, "elements") ?: return JsonArray(out)
        for (i in 0 until els.size) {
            val el = optObj(els, i) ?: continue
            if (GtOrgJson.optString(el, "type") != "way") continue
            val geom = optArr(el, "geometry") ?: continue
            if (geom.size < 2) continue

            val coords = ArrayList<JsonElement>()
            for (j in 0 until geom.size) {
                val p = optObj(geom, j) ?: continue
                coords.add(JsonArray(listOf(GtOrgJson.num(GtOrgJson.optDouble(p, "lon")), GtOrgJson.num(GtOrgJson.optDouble(p, "lat")))))
            }
            if (coords.size < 2) continue

            val tags = optObj(el, "tags") ?: JsonObject(emptyMap())
            val motorized = osmMotorized(tags)

            val props = LinkedHashMap<String, JsonElement>()
            props["osm_id"] = GtOrgJson.str(GtOrgJson.optLong(el, "id").toString())
            props["name"] = GtOrgJson.str(GtOrgJson.optString(tags, "name", "").ifBlank { GtOrgJson.optString(tags, "ref", "") })
            props["CartoCode"] = GtOrgJson.str("")
            props["DesignatedUses"] = GtOrgJson.str(osmUses(tags, motorized))
            props["MotorizedAllowed"] = GtOrgJson.str(motorized)
            props["SurfaceType"] = GtOrgJson.str(GtOrgJson.optString(tags, "surface", ""))
            props["OwnerSteward"] = GtOrgJson.str(GtOrgJson.optString(tags, "operator", ""))

            val g = JsonObject(linkedMapOf("type" to GtOrgJson.str("LineString"), "coordinates" to JsonArray(coords)))
            out.add(JsonObject(linkedMapOf("type" to GtOrgJson.str("Feature"), "geometry" to g, "properties" to JsonObject(props))))
        }
        return JsonArray(out)
    }

    // FETCHFIX-2026-11-02: [count] = the page size asked for
    private fun buildUrl(src: Src, s: Double?, w: Double?, n: Double?, e: Double?, offset: Int, count: Int): String {
        if (src.sourceType == "overpass") return buildOverpassUrl(src, s, w, n, e)
        val sb = StringBuilder(src.queryUrl)
        sb.append("?f=geojson&outSR=4326&outFields=*")
        if (s != null && w != null && n != null && e != null) {
            sb.append("&geometry=" + GtJava.jd(w) + "," + GtJava.jd(s) + "," + GtJava.jd(e) + "," + GtJava.jd(n))
            sb.append("&geometryType=esriGeometryEnvelope&spatialRel=esriSpatialRelIntersects&inSR=4326")
        } else {
            sb.append("&where=1%3D1")
        }
        sb.append("&resultOffset=$offset&resultRecordCount=$count")
        return sb.toString()
    }

    // =================================================================================================================================
    // Insert pipeline
    // =================================================================================================================================

    private fun insertFeature(sDb: SQLiteConnection, eDb: SQLiteConnection, feature: JsonObject, src: Src): String {
        val props = optObj(feature, "properties") ?: return "error"
        val geom = optObj(feature, "geometry") ?: return "error"
        val uid = GtOrgJson.optString(props, src.fieldId, "").ifEmpty { return "error" }

        if (writer.sourceUidSeen(src.id, uid)) {
            return updateExistingFeature(sDb, eDb, props, src, uid)
        }

        val name = GtOrgJson.optString(props, src.fieldName, "").ifEmpty { null }
        val wkt = geojsonGeomToWkt(geom)
        val bbox = computeBbox(geom)
        val tid = env.ids.newId()
        val now = env.clock.nowInstant()

        val (anchorId, decision) = writer.insertTrail(tid, name, wkt, bbox[0], bbox[1], bbox[2], bbox[3], now)
        writer.markSourceUid(src.id, uid)

        val motorized = GtOrgJson.optString(props, src.fieldExtras["motorized"] ?: "", "")
        val surface = GtOrgJson.optString(props, src.fieldExtras["surface"] ?: "", "")
        val county = GtOrgJson.optString(props, src.fieldExtras["county"] ?: "", "")
        val steward = GtOrgJson.optString(props, src.fieldExtras["manager"] ?: (src.fieldExtras["steward"] ?: ""), "")
        val uses = GtOrgJson.optString(props, src.fieldUse, "")
        val cartoCode = GtOrgJson.optString(props, src.fieldType, "")
        val extra = captureExtras(src, props)

        exec(eDb,
            "INSERT OR IGNORE INTO trail_properties (trail_id,source_id,source_unique_id,designated_uses,motorized_allowed,surface_type,carto_code,owner_steward,county,agency_id,ingested_at," +
                "status,trail_class,system_name,recreation_area,trans_network,data_source,comments,horse_allowed,hike_difficulty,bike_difficulty," +
                "ref_code,operator,width_raw,maxwidth_raw,incline,sac_scale,mtb_scale,trail_visibility,other_restrictions) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(anchorId, src.id, uid, uses, motorized, surface, cartoCode, steward, county, uid, now,
                extra["status"], extra["trail_class"], extra["system_name"],
                extra["recreation_area"], extra["trans_network"], extra["data_source"],
                extra["comments"], extra["horse_allowed"], extra["hike_difficulty"],
                extra["bike_difficulty"], extra["ref_code"], extra["operator"],
                extra["width_raw"], extra["maxwidth_raw"], extra["incline"],
                extra["sac_scale"], extra["mtb_scale"], extra["trail_visibility"],
                extra["other_restrictions"])
        )
        // IMPORTQUIET-2026-11-02 (Fred 13:02): a trail we already have is skipped -- its code is NOT written over. Only an unnamed trail
        //  gets this source's name.
        val namedNow = decision != GtWrite.Decision.INSERT && name != null && fillName(sDb, anchorId, name, src.id, uid)
        if (cartoCode.isNotEmpty() && decision == GtWrite.Decision.INSERT) {
            try { exec(sDb, "UPDATE trails SET carto_code=? WHERE trail_id=?", listOf(cartoCode, anchorId)) }
            catch (ex: Exception) { log.w { "spatial carto write (create) failed: ${ex.message}" } }
        }
        return when (decision) {
            GtWrite.Decision.INSERT -> "inserted"
            GtWrite.Decision.DROP -> if (namedNow) "named_drop" else "dropped"
            GtWrite.Decision.ALIAS -> if (namedNow) "named_alias" else "aliased"
        }
    }

    /** IMPORTQUIET-2026-11-02: give an UNNAMED trail ("Not Named" / empty) the source's real name. True when a name was written. */
    private fun fillName(sDb: SQLiteConnection, trailId: String, rawName: String, sourceId: String, uid: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty() || name.equals("Not Named", ignoreCase = true)) return false
        return try {
            val where = " WHERE trail_id=? AND (name IS NULL OR TRIM(name)='' OR name='Not Named')"
            val n = queryText(sDb, "SELECT COUNT(*) FROM trails$where", listOf(trailId), 1)?.get(0)?.toLongOrNull() ?: 0L
            exec(sDb, "UPDATE trails SET name=?$where", listOf(name, trailId))
            if (n > 0) logDiff.i { "$sourceId $uid [$trailId]: named '$name'" }
            n > 0
        } catch (ex: Exception) { logDiff.w { "name fill failed: ${ex.message}" }; false }
    }

    /**
     * IMPORTQUIET-2026-11-02 (Fred 13:02): an already-imported trail (same source + same source id) is SKIPPED -- except an unnamed one,
     * which gets the source's real name. UpdateMode.ALL refreshes name + property fields, writing ONLY what differs.
     * Returns "unchanged" | "updated" | "skipped" (not found) | "error".
     */
    private fun updateExistingFeature(sDb: SQLiteConnection, eDb: SQLiteConnection, props: JsonObject, src: Src, uid: String): String {
        val name = GtOrgJson.optString(props, src.fieldName, "").trim()
        if (updateMode != UpdateMode.ALL && (name.isEmpty() || name.equals("Not Named", ignoreCase = true))) return "unchanged"
        val trailId = try {
            queryText(eDb, "SELECT trail_id FROM trail_properties WHERE source_id=? AND source_unique_id=? LIMIT 1", listOf(src.id, uid), 1)?.get(0)
        } catch (ex: Exception) { log.w { "resolve trail_id failed: ${ex.message}" }; null } ?: return "skipped"
        if (updateMode != UpdateMode.ALL) return if (fillName(sDb, trailId, name, src.id, uid)) "updated" else "unchanged"

        val motorized = GtOrgJson.optString(props, src.fieldExtras["motorized"] ?: "", "")
        val surface = GtOrgJson.optString(props, src.fieldExtras["surface"] ?: "", "")
        val county = GtOrgJson.optString(props, src.fieldExtras["county"] ?: "", "")
        val steward = GtOrgJson.optString(props, src.fieldExtras["manager"] ?: (src.fieldExtras["steward"] ?: ""), "")
        val uses = GtOrgJson.optString(props, src.fieldUse, "")
        return try {
            var oldName = ""
            queryText(sDb, "SELECT COALESCE(name,'') FROM trails WHERE trail_id=?", listOf(trailId), 1)?.let { oldName = it[0] ?: "" }
            val old = arrayOf("", "", "", "", "")
            queryText(eDb, "SELECT COALESCE(designated_uses,''),COALESCE(motorized_allowed,''),COALESCE(surface_type,''),COALESCE(owner_steward,''),COALESCE(county,'') " +
                "FROM trail_properties WHERE trail_id=?", listOf(trailId), 5)?.let { r -> for (k in 0..4) old[k] = r[k] ?: "" }
            val changes = ArrayList<String>()
            if (name.isNotEmpty() && name != oldName) {
                exec(sDb, "UPDATE trails SET name=? WHERE trail_id=?", listOf(name, trailId)); changes.add("name '$oldName' -> '$name'")
            }
            val now = arrayOf(uses, motorized, surface, steward, county)
            if (!now.contentEquals(old)) {
                exec(eDb, "UPDATE trail_properties SET designated_uses=?,motorized_allowed=?,surface_type=?,owner_steward=?,county=? WHERE trail_id=?",
                    listOf(uses, motorized, surface, steward, county, trailId))
                changes.add("properties")
            }
            if (changes.isEmpty()) "unchanged"
            else { logDiff.i { "${src.id} $uid [$trailId]: ${changes.joinToString("; ")}" }; "updated" }
        } catch (ex: Exception) { logDiff.w { "update failed: ${ex.message}" }; "error" }
    }

    // ---- Geometry conversion (the hash trap: GtGeom writes Java's number text) ----

    fun geojsonGeomToWkt(geom: JsonObject): String {
        val type = GtOrgJson.optString(geom, "type")
        val coords = optArr(geom, "coordinates") ?: return ""
        return when (type) {
            "LineString" -> GtGeom.lineLonLat(ring(coords)).wkt
            "MultiLineString" -> GtGeom.multiLineLonLat((0 until coords.size).map { i -> ring(getArr(coords, i)) }).wkt
            "Point" -> GtGeom.pointLonLat(getDouble(coords, 0), getDouble(coords, 1)).wkt
            else -> ""
        }
    }

    /** coordRingToWkt's points: each getJSONArray(i), getDouble(0), getDouble(1) -- (lon, lat). */
    private fun ring(arr: JsonArray): List<Pair<Double, Double>> =
        (0 until arr.size).map { i -> val c = getArr(arr, i); Pair(getDouble(c, 0), getDouble(c, 1)) }

    /** CAPTURE-2026-09-01: every optional attribute a source supplies, through fieldExtras; an unmapped key gets "" (RAW, ALWAYS). */
    private fun captureExtras(src: Src, props: JsonObject): Map<String, String> {
        val out = HashMap<String, String>(EXTRA_KEYS.size)
        for (k in EXTRA_KEYS) {
            val field = src.fieldExtras[k]
            out[k] = if (field.isNullOrEmpty()) "" else GtOrgJson.optString(props, field, "")
        }
        return out
    }

    fun computeBbox(geom: JsonObject): DoubleArray {
        val pts = mutableListOf<Pair<Double, Double>>()
        extractCoords(optArr(geom, "coordinates") ?: JsonArray(emptyList()), pts)
        if (pts.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for ((lon, lat) in pts) {
            if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
        }
        return doubleArrayOf(minLat, maxLat, minLon, maxLon)
    }

    private fun extractCoords(arr: JsonArray, out: MutableList<Pair<Double, Double>>) {
        if (arr.size == 0) return
        if (isNumber(arr[0])) {
            if (arr.size >= 2) out.add(Pair(getDouble(arr, 0), getDouble(arr, 1)))
        } else {
            for (i in 0 until arr.size) {
                optArr(arr, i)?.let { extractCoords(it, out) }
            }
        }
    }

    // =================================================================================================================================
    // Trail-area records (data/trail_areas) and the ingestion log
    // =================================================================================================================================

    /** TRAILAREAS-2026-09-12: the ONE place trail_areas is resolved (created here, as 2.7e). */
    private fun trailAreasDir(): Path {
        val dir = env.paths.dir("data/trail_areas")
        if (!fs.exists(dir)) fs.createDirectories(dir)
        return dir
    }

    private fun writeTrailAreaJson(source: Src, inserted: Int, skipped: Int, south: Double?, west: Double?, north: Double?, east: Double?) {
        try {
            val dir = trailAreasDir()
            val isFullSource = (south == null && west == null && north == null && east == null)
            val json = LinkedHashMap<String, JsonElement>()
            json["type"] = GtOrgJson.str(if (isFullSource) "full_source" else "area")
            json["source_id"] = GtOrgJson.str(source.id)
            json["source_name"] = GtOrgJson.str(source.name)
            json["status"] = GtOrgJson.str("processed")
            json["trail_count"] = GtOrgJson.num(inserted)
            json["dupes_skipped"] = GtOrgJson.num(skipped)
            json["processed_at"] = GtOrgJson.str(env.clock.nowInstant())
            if (isFullSource) {
                val catJson = env.platform.readAsset("trail_sources.json")
                val sources = getArr(parseObject(catJson), "sources")
                for (j in 0 until sources.size) {
                    val s = getObj(sources, j)
                    if (GtOrgJson.optString(s, "id") == source.id) {
                        val b = optObj(s, "boundary")
                        if (b != null) {
                            json["north"] = GtOrgJson.num(GtOrgJson.optDouble(b, "n", 0.0))
                            json["south"] = GtOrgJson.num(GtOrgJson.optDouble(b, "s", 0.0))
                            json["east"] = GtOrgJson.num(GtOrgJson.optDouble(b, "e", 0.0))
                            json["west"] = GtOrgJson.num(GtOrgJson.optDouble(b, "w", 0.0))
                        }
                        break
                    }
                }
            } else {
                putBox(json, north, south, east, west)
            }
            val filename = if (isFullSource) "source_${source.id}.json" else "area_${stamp()}.json"
            fs.write(dir / filename) { writeUtf8(GtOrgJson.pretty(JsonObject(json))) }
            log.i { "Trail area record: $filename" }
        } catch (e: Exception) {
            log.w { "Failed to write trail area JSON: ${e.message}" }
        }
    }

    /** JSONObject.put(name, Double?): a null removes the name (org.json), a number is stored (NaN / Infinity refused). */
    private fun putBox(json: LinkedHashMap<String, JsonElement>, north: Double?, south: Double?, east: Double?, west: Double?) {
        for ((k, v) in listOf("north" to north, "south" to south, "east" to east, "west" to west)) {
            if (v == null) json.remove(k) else json[k] = GtOrgJson.num(v)
        }
    }

    /** LocalDateTime.now().format("yyyyMMdd_HHmmss") */
    private fun stamp(): String {
        val t = env.clock.localNow()
        fun p(v: Int, n: Int) = v.toString().padStart(n, '0')
        return p(t.year, 4) + p(t.month, 2) + p(t.day, 2) + "_" + p(t.hour, 2) + p(t.minute, 2) + p(t.second, 2)
    }

    /** Write pending area JSON (unprocessed) for Method B signal */
    fun writePendingArea(north: Double, south: Double, east: Double, west: Double) {
        try {
            val dir = trailAreasDir()
            val json = LinkedHashMap<String, JsonElement>()
            json["type"] = GtOrgJson.str("area")
            json["status"] = GtOrgJson.str("unprocessed")
            putBox(json, north, south, east, west)
            json["created_at"] = GtOrgJson.str(env.clock.nowInstant())
            fs.write(dir / "pending_area.json") { writeUtf8(GtOrgJson.pretty(JsonObject(json))) }
            log.i { "Pending area written" }
        } catch (e: Exception) {
            log.w { "Failed to write pending area: ${e.message}" }
        }
    }

    /** Read pending area JSON, return it or null */
    fun readPendingArea(): JsonObject? {
        return try {
            val f = trailAreasDir() / "pending_area.json"
            if (fs.exists(f)) parseObject(fs.read(f) { readUtf8() }) else null
        } catch (_: Exception) { null }
    }

    /** Delete pending area JSON after processing */
    fun clearPendingArea() {
        try {
            val f = trailAreasDir() / "pending_area.json"
            if (fs.exists(f)) fs.delete(f)
        } catch (_: Exception) {}
    }

    /** Scan all processed trail area JSONs for map overlay (in the platform's listing order -- see the header) */
    fun scanTrailAreas(): List<JsonObject> {
        return try {
            val dir = trailAreasDir()
            if (!fs.exists(dir)) return emptyList()
            env.files.list(dir)
                .filter { it.name.substringAfterLast('.', "") == "json" && it.name != "pending_area.json" }
                .mapNotNull { try { parseObject(fs.read(it) { readUtf8() }) } catch (_: Exception) { null } }
                .filter { GtOrgJson.optString(it, "status") == "processed" }
        } catch (_: Exception) { emptyList() }
    }

    /** Check if a source has been fully imported */
    fun isSourceFullyImported(sourceId: String): Boolean {
        return try {
            val f = trailAreasDir() / "source_${sourceId}.json"
            if (fs.exists(f)) {
                val j = parseObject(fs.read(f) { readUtf8() })
                GtOrgJson.optString(j, "status") == "processed" &&
                    GtOrgJson.optString(j, "type") == "full_source" &&
                    GtOrgJson.optInt(j, "trail_count", 0) > 0
            } else false
        } catch (_: Exception) { false }
    }

    private fun logIngestion(db: SQLiteConnection, sid: String, count: Int, dupes: Int, s: Double?, w: Double?, n: Double?, e: Double?) {
        val bounds = if (s != null) "{\"s\":" + jdN(s) + ",\"w\":" + jdN(w) + ",\"n\":" + jdN(n) + ",\"e\":" + jdN(e) + "}" else null
        exec(db,
            "INSERT INTO source_ingestions (ingestion_id,source_id,ingested_at,trail_count,dupes_skipped,bounds_json) VALUES (?,?,?,?,?,?)",
            listOf(env.ids.newId(), sid, env.clock.nowInstant(), count, dupes, bounds)
        )
    }

    private fun applyMigrationIfNeeded() {
        val db = conn
        var ver: Int
        try {
            ver = queryText(db, "SELECT version FROM schema_version ORDER BY version DESC LIMIT 1", emptyList(), 1)?.get(0)?.toIntOrNull() ?: 0
        } catch (ex: Exception) {
            log.w { "schema_version query failed: ${ex.message}" }
            ver = 0
        }
        if (ver >= 2) return
        log.i { "Applying spatial schema v1 -> v2" }
        try {
            val sql = env.platform.readAsset("schema_spatial_v1_to_v2.sql")
            sql.split(";").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("--") }.forEach { stmt ->
                try { exec(db, "$stmt;", emptyList()) } catch (ex: Exception) { log.w { "Skip: ${ex.message}" } }
            }
            exec(db, "DELETE FROM schema_version", emptyList())
            exec(db, "INSERT INTO schema_version VALUES (2, datetime('now'))", emptyList())
        } catch (ex: Exception) { log.e { "Migration failed: ${ex.message}" } }
    }

    // =================================================================================================================================
    // Trailheads (an asset GeoJSON of points -> waypoints, id <source>_th_<id>)
    // =================================================================================================================================

    /**
     * @param sourceId 2.7e's default "ugrc" is the caller's (CODE RULE 1: required here).
     * @param south..east the drawn box -- CODE RULE 1: null = no box (2.7e's reason: the whole-source button passes none), checked as
     *        2.7e checks it: only [south] is tested, the other three are then required (2.7e's `!!`).
     */
    fun importTrailheads(assetFile: String, sourceId: String, south: Double?, west: Double?, north: Double?, east: Double?): Int {
        return try {
            val root = parseObject(env.platform.readAsset(assetFile))
            val features = getArr(root, "features")
            val total = features.size
            var inserted = 0
            var skipped = 0
            var outsideArea = 0
            for (i in 0 until total) {
                val feature = getObj(features, i)
                val props = getObj(feature, "properties")
                val geom = getObj(feature, "geometry")
                val coords = getArr(geom, "coordinates")
                val lon = getDouble(coords, 0)
                val lat = getDouble(coords, 1)
                if (south != null && (lat < south || lat > north!! || lon < west!! || lon > east!!)) {
                    outsideArea++
                    continue
                }
                val name = GtOrgJson.optString(props, "name", GtOrgJson.optString(props, "PrimaryName", "Unknown Trailhead"))
                val trailheadId = GtOrgJson.optString(props, "id", GtOrgJson.optString(props, "TrailheadID", i.toString()))
                val waypointId = sourceId + "_th_" + trailheadId
                if (writer.insertWaypointWithId(waypointId, name, lat, lon, "trailhead")) inserted++ else skipped++
                if (i % 50 == 0) {
                    emitProgress(ImportProgress(
                        sourceId = sourceId, sourceName = "Trailheads: $assetFile",
                        fetched = i + 1, inserted = inserted, skipped = skipped, rejected = outsideArea, offset = i
                    ))
                }
            }
            emitProgress(ImportProgress(
                sourceId = sourceId, sourceName = "Trailheads: $assetFile",
                fetched = total, inserted = inserted, skipped = skipped, rejected = outsideArea,
                offset = total, isComplete = true,
                message = "$inserted trailheads imported, $skipped existing, $outsideArea outside area"
            ))
            log.i { "Trailheads from $assetFile: $inserted imported, $skipped existing, $outsideArea outside area" }
            inserted
        } catch (e: Exception) {
            log.e { "Trailhead import failed: " + e.message }
            0
        }
    }

    // =================================================================================================================================
    // The catalog (assets/trail_sources.json)
    // =================================================================================================================================

    fun loadCatalog(): List<Src>? {
        return try {
            val root = parseObject(env.platform.readAsset("trail_sources.json"))
            val sources = getArr(root, "sources")
            (0 until sources.size).mapNotNull { i ->
                val s = getObj(sources, i)
                if (GtOrgJson.optString(s, "status") == "display_only_not_queryable") return@mapNotNull null
                val f = getObj(s, "fields")
                val extras = LinkedHashMap<String, String>()
                for (k in f.keys) if (k !in listOf("id", "name", "type", "use")) extras[k] = getString(f, k)
                Src(getString(s, "id"), getString(s, "name"), getString(s, "query_url"),
                    GtOrgJson.optInt(s, "max_records", 2000),
                    getString(f, "id"), getString(f, "name"), GtOrgJson.optString(f, "type", ""), GtOrgJson.optString(f, "use", ""), extras,
                    GtOrgJson.optString(s, "source_type", "arcgis_rest"))
            }
        } catch (ex: Exception) { log.e { "Catalog: ${ex.message}" }; null }
    }
}
