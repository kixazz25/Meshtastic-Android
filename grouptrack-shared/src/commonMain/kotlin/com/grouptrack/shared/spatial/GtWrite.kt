package com.grouptrack.shared.spatial

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.log.gtLogger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.contentOrNull

/**
 * GtWrite -- SpatialDbManager's WRITING side in SHARED Kotlin (step 10, GTWRITE-2026-11-06). Ported from 2.7e, same SQL, same rows:
 * waypoints (insert with id, rename, retype, delete), routes (insert, rename, delete + its notes and aliases, notes, trailhead anchor),
 * aliases (add, rename, delete, set preferred), tracks (insert with the geometry duplicate check, rename, delete -- the DB part), trails
 * (insert through the import duplicate check, the dedup session, delete, clear all).
 * NOT here (they move with imports / track sync): resolveTrackAdd (moves the GPX file, scans rider trails), updateTrackPropertiesForHash,
 * backfillAllTrackProperties, syncTracksFromFiles.
 * ⚠ deleteTrackFromDb's GPX FILE removal stays with the platform: [deleteTrackFromDb] returns the geom hash so the caller removes the file.
 * ⚠ JSON: route notes are stored as org.json writes them (2.7e) -- [orgJson] writes JSON the same way (escapes "/" as "\/", no spaces).
 * @param newId / now  where ids and timestamps come from (Android: UUID + Instant.now(), as 2.7e) -- replaceable so tests repeat exactly.
 * @param dataConn grouptrack_data.db -- CODE RULE 1: optional as in 2.7e (the extension parts are skipped without it).
 */
class GtWrite(private val conn: SQLiteConnection, private val dataConn: SQLiteConnection?,
              private val newId: () -> String, private val now: () -> String) {
    private val log = gtLogger("SpatialWrite")
    private val read = GtRead(conn, dataConn)

    private fun exec(c: SQLiteConnection, sql: String, args: List<Any?>) {
        val st = c.prepare(sql)
        try {
            args.forEachIndexed { i, a -> when (a) { null -> st.bindNull(i + 1); is Double -> st.bindDouble(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); is Long -> st.bindLong(i + 1, a); else -> st.bindText(i + 1, a.toString()) } }
            st.step()
        } finally { st.close() }
    }
    private fun firstText(c: SQLiteConnection, sql: String, args: List<Any>): String? {
        val st = c.prepare(sql)
        try { args.forEachIndexed { i, a -> st.bindText(i + 1, a.toString()) }; return if (st.step() && !st.isNull(0)) st.getText(0) else null } finally { st.close() }
    }
    private fun changes(c: SQLiteConnection): Long { val st = c.prepare("SELECT changes()"); try { st.step(); return st.getLong(0) } finally { st.close() } }

    // ---- waypoints --------------------------------------------------------------------------------------------------------
    fun insertWaypointWithId(waypointId: String, name: String, lat: Double, lon: Double, type: String): Boolean {
        val ts = now(); val g = GtGeom.pointLonLat(lon, lat); val geometry = g.wkt; val nm = GtRead.notNamed(name); val gh = g.hash   // GEOMONE-2026-11-09
        return try {
            exec(conn, "INSERT OR IGNORE INTO waypoints (waypoint_id, name, geometry, type, min_lat, max_lat, min_lon, max_lon, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                listOf(waypointId, nm, geometry, type.lowercase(), lat, lat, lon, lon, ts, ts, gh)); true
        } catch (e: Exception) { log.e { "insertWaypointWithId failed: " + e.message }; false }
    }
    fun renameWaypoint(id: String, newName: String) = exec(conn, "UPDATE waypoints SET name=?, updated_at=? WHERE waypoint_id=?", listOf(newName, now(), id))
    fun deleteWaypoint(id: String) = exec(conn, "DELETE FROM waypoints WHERE waypoint_id=?", listOf(id))
    fun changeWaypointType(id: String, newType: String) = exec(conn, "UPDATE waypoints SET type=?, updated_at=? WHERE waypoint_id=?", listOf(newType.lowercase(), now(), id))

    // ---- routes -----------------------------------------------------------------------------------------------------------
    fun insertRoute(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): String {
        val id = newId(); val ts = now(); val nm = GtRead.notNamed(name); val gh = GtRead.computeGeomHash(geometryWkt)
        try {
            exec(conn, "INSERT OR IGNORE INTO routes (route_id, name, geometry, min_lat, max_lat, min_lon, max_lon, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?)",
                listOf(id, nm, geometryWkt, minLat, maxLat, minLon, maxLon, ts, ts, gh))
            log.i { "Inserted route: $nm" }
        } catch (e: Exception) { log.e { "Route insert failed: ${e.message}" } }
        return id
    }
    fun renameRoute(id: String, newName: String) = exec(conn, "UPDATE routes SET name=?, updated_at=? WHERE route_id=?", listOf(newName, now(), id))
    fun deleteRoute(id: String) { exec(conn, "DELETE FROM routes WHERE route_id=?", listOf(id)); deleteRouteNotes(id); deleteRouteAliases(id) }

    /** writeRouteNotes: the notes JSON (as the platform writes it) -> one narrative row + a row per feature and caution. Rows written, -1 = failed. */
    fun writeRouteNotes(routeId: String, notesJson: String): Int {
        val db = dataConn ?: return -1
        val gh = read.routeGeomHash(routeId) ?: run { log.w { "writeRouteNotes: no route $routeId" }; return -1 }
        val ts = now()
        var n = 0
        try {
            val notes = Json.parseToJsonElement(notesJson) as JsonObject
            exec(db, "DELETE FROM route_notes WHERE route_id=?", listOf(routeId))
            val nar = notes["narrative"] as? JsonObject
            exec(db, "INSERT INTO route_notes (note_id,route_id,geom_hash,seq,kind,title,body,payload,author,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                listOf(newId(), routeId, gh, 0, "narrative", null, nar?.let { optString(it, "headline") }, notesJson, "generated", ts))
            n++
            (notes["features"] as? JsonArray)?.forEachIndexed { i, el ->
                val f = el as? JsonObject ?: return@forEachIndexed
                exec(db, "INSERT INTO route_notes (note_id,route_id,geom_hash,seq,kind,at_mile,lat,lon,title,body,payload,author,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    listOf(newId(), routeId, gh, i + 1, "feature", optDouble(f, "at_mi"), optDouble(f, "lat"), optDouble(f, "lon"),
                        optString(f, "name"), optString(f, "fclass"), orgJson(f), "generated", ts))
                n++
            }
            (notes["cautions"] as? JsonArray)?.forEachIndexed { i, el ->
                val c = el as? JsonObject ?: return@forEachIndexed
                exec(db, "INSERT INTO route_notes (note_id,route_id,geom_hash,seq,kind,at_mile,lat,lon,body,payload,author,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    listOf(newId(), routeId, gh, 1000 + i, "caution", optDouble(c, "at_mi"), optDouble(c, "lat"), optDouble(c, "lon"),
                        "Trails mapped ${optDouble(c, "gap_ft").toInt()} ft apart", orgJson(c), "generated", ts))
                n++
            }
            log.i { "route_notes: wrote $n rows for $routeId" }
        } catch (e: Exception) { log.e { "writeRouteNotes failed: ${e.message}" }; return -1 }
        return n
    }
    fun deleteRouteNotes(routeId: String): Int {
        val db = dataConn ?: return 0
        return try { exec(db, "DELETE FROM route_notes WHERE route_id=?", listOf(routeId)); 1 } catch (e: Exception) { log.w { "deleteRouteNotes: ${e.message}" }; 0 }
    }
    fun deleteRouteAliases(routeId: String) {
        try { dataConn?.let { exec(it, "DELETE FROM artifact_aliases WHERE artifact_type='route' AND artifact_id=?", listOf(routeId)) } }
        catch (e: Exception) { log.w { "deleteRouteAliases: ${e.message}" } }
    }
    /** setRouteAnchor: record the trailhead in the route's recipe -- once (an existing anchor wins). */
    @OptIn(ExperimentalSerializationApi::class)
    fun setRouteAnchor(routeId: String, lat: Double, lon: Double, name: String): Boolean {
        val notes = LinkedHashMap<String, JsonElement>(read.readRouteNotesPayload(routeId)?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap()))
        val recipe = LinkedHashMap<String, JsonElement>((notes["recipe"] as? JsonObject) ?: JsonObject(emptyMap()))
        if (recipe.containsKey("anchorLat") && recipe.containsKey("anchorLon")) return true
        recipe["anchorLat"] = JsonUnquotedLiteral(numberToString(lat)); recipe["anchorLon"] = JsonUnquotedLiteral(numberToString(lon)); recipe["anchorName"] = JsonPrimitive(name)
        notes["recipe"] = JsonObject(recipe)
        return writeRouteNotes(routeId, orgJson(JsonObject(notes))) >= 0
    }

    // ---- aliases ----------------------------------------------------------------------------------------------------------
    fun addAlias(type: String, artifactId: String, name: String, geomHash: String, creationDate: String?) {
        val e = dataConn ?: return
        try { exec(e, "INSERT OR IGNORE INTO artifact_aliases (alias_id, artifact_type, artifact_id, alias, is_preferred, source, created_at, creation_date, geom_hash) VALUES (?,?,?,?,?,?,?,?,?)",
            listOf(newId(), type, artifactId, name, 0, "add", now(), creationDate, geomHash)) } catch (ex: Exception) { log.w { "addAlias: ${ex.message}" } }
    }
    fun renameAlias(aliasId: String, newText: String) { val e = dataConn ?: return; try { exec(e, "UPDATE artifact_aliases SET alias = ? WHERE alias_id = ?", listOf(newText, aliasId)) } catch (ex: Exception) { log.w { "${ex.message}" } } }
    fun deleteAlias(aliasId: String) { val e = dataConn ?: return; exec(e, "DELETE FROM artifact_aliases WHERE alias_id = ?", listOf(aliasId)) }
    /** The official name and an alias swap (trails are not user-maintained -- skipped, as 2.7e). */
    fun setPreferredAlias(type: String, artifactId: String, aliasId: String) {
        val tl = type.lowercase()
        if (tl == "trail" || tl == "trails") { log.i { "skip: trails are not user-maintained" }; return }
        val edb = dataConn ?: return
        val (table, idCol) = GtRead.spatialTableFor(type) ?: return
        val aliasText = try { firstText(edb, "SELECT alias FROM artifact_aliases WHERE alias_id = ? LIMIT 1", listOf(aliasId)) } catch (e: Exception) { null } ?: run { log.w { "alias $aliasId not found" }; return }
        val oldName = try { firstText(conn, "SELECT name FROM $table WHERE $idCol = ? LIMIT 1", listOf(artifactId)) } catch (e: Exception) { null }
        try { exec(conn, "UPDATE $table SET name = ? WHERE $idCol = ?", listOf(aliasText, artifactId)) } catch (e: Exception) { log.w { "set name: ${e.message}" } }
        try {
            if (oldName != null && oldName.isNotBlank() && oldName != "Not Named") exec(edb, "UPDATE artifact_aliases SET alias = ? WHERE alias_id = ?", listOf(oldName, aliasId))
            else exec(edb, "DELETE FROM artifact_aliases WHERE alias_id = ?", listOf(aliasId))
        } catch (e: Exception) { log.w { "demote: ${e.message}" } }
    }

    // ---- tracks (the database part) ---------------------------------------------------------------------------------------
    data class TrackInsert(val wasNew: Boolean, val trackId: String, val geomHash: String, val name: String)
    fun insertTrackToDb(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): TrackInsert {
        val id = newId(); val ts = now(); val bbox = "$minLat,$minLon,$maxLat,$maxLon"; val nm = GtRead.notNamed(name); val gh = GtRead.computeGeomHash(geometryWkt)
        var inserted = false; var resolvedId = id
        try {
            exec(conn, "INSERT OR IGNORE INTO tracks (track_id, name, geometry, min_lat, max_lat, min_lon, max_lon, bbox, type, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(id, nm, geometryWkt, minLat, maxLat, minLon, maxLon, bbox, "TRACK", ts, ts, gh))
            inserted = changes(conn) > 0
            if (!inserted) firstText(conn, "SELECT track_id FROM tracks WHERE geom_hash=? LIMIT 1", listOf(gh))?.let { resolvedId = it }
        } catch (e: Exception) { log.e { "Track DB insert failed: ${e.message}" } }
        return TrackInsert(inserted, resolvedId, gh, nm)
    }
    fun renameTrackInDb(id: String, newName: String) = exec(conn, "UPDATE tracks SET name=?, updated_at=? WHERE track_id=?", listOf(newName, now(), id))
    /** The track's rows in both databases; returns its geom hash (the caller removes the GPX file -- a platform matter), or null. */
    fun deleteTrackFromDb(id: String): String? {
        val h = try { firstText(conn, "SELECT geom_hash FROM tracks WHERE track_id=? LIMIT 1", listOf(id)) } catch (e: Exception) { null }
        try { exec(conn, "DELETE FROM tracks WHERE track_id=?", listOf(id)) } catch (e: Exception) { log.w { "spatial row: ${e.message}" } }
        try { dataConn?.let { exec(it, "DELETE FROM track_properties WHERE track_id=?", listOf(id)) } } catch (e: Exception) { log.w { "track_properties: ${e.message}" } }
        try { dataConn?.let { exec(it, "DELETE FROM artifact_aliases WHERE artifact_type=? AND artifact_id=?", listOf("track", id)) } } catch (e: Exception) { log.w { "aliases: ${e.message}" } }
        return h
    }

    // ---- trails and the import duplicate check ----------------------------------------------------------------------------
    enum class Decision { INSERT, DROP, ALIAS }
    private val dedupHashToName = HashMap<String, HashMap<String, String>>()
    private val dedupSourceUids = HashSet<String>()
    private var dedupSessionActive = false
    fun beginDedupSession() {
        dedupHashToName.clear(); dedupSourceUids.clear()
        for ((t, table) in listOf("trail" to "trails", "track" to "tracks", "waypoint" to "waypoints", "route" to "routes")) {
            val m = HashMap<String, String>(); dedupHashToName[t] = m
            try { val st = conn.prepare("SELECT geom_hash, name FROM $table WHERE geom_hash IS NOT NULL")
                try { while (st.step()) m[st.getText(0)] = if (st.isNull(1)) "Not Named" else st.getText(1) } finally { st.close() } }
            catch (e: Exception) { log.w { "loadHashes($table): ${e.message}" } }
        }
        dataConn?.let { e -> try { val st = e.prepare("SELECT source_id, source_unique_id FROM trail_properties WHERE source_id IS NOT NULL")
            try { while (st.step()) dedupSourceUids.add(st.getText(0) + "\u0000" + (if (st.isNull(1)) "null" else st.getText(1))) } finally { st.close() } }
            catch (ex: Exception) { log.w { "loadSourceUids: ${ex.message}" } } }
        dedupSessionActive = true
    }
    fun endDedupSession() { dedupHashToName.clear(); dedupSourceUids.clear(); dedupSessionActive = false }
    fun sourceUidSeen(sourceId: String, uid: String) = dedupSourceUids.contains(sourceId + "\u0000" + uid)
    fun markSourceUid(sourceId: String, uid: String) { dedupSourceUids.add(sourceId + "\u0000" + uid) }
    fun resolveByGeom(type: String, name: String, geomHash: String): Decision {
        val m = if (dedupSessionActive) dedupHashToName[type] else null
        val existing = (if (m != null) m[geomHash] else nameByHash(type, geomHash)) ?: return Decision.INSERT
        return if (existing == name) Decision.DROP else Decision.ALIAS
    }
    private fun nameByHash(type: String, geomHash: String): String? {
        val table = GtRead.spatialTableFor(type)?.first ?: return null
        return try { val st = conn.prepare("SELECT name FROM $table WHERE geom_hash=? LIMIT 1")
            try { st.bindText(1, geomHash); if (st.step()) (if (st.isNull(0)) "Not Named" else st.getText(0)) else null } finally { st.close() } }
        catch (e: Exception) { log.w { "nameByHash($table): ${e.message}" }; null }
    }
    fun noteInserted(type: String, geomHash: String, name: String) { dedupHashToName[type]?.put(geomHash, name) }
    fun insertTrail(trailId: String, rawName: String?, wkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, createdAt: String): Pair<String, Decision> {
        val name = GtRead.notNamed(rawName); val geomHash = GtRead.computeGeomHash(wkt)
        return when (val d = resolveByGeom("trail", name, geomHash)) {
            Decision.DROP -> (findTrailIdByHash(geomHash) ?: trailId) to d
            Decision.ALIAS -> { val anchor = findTrailIdByHash(geomHash) ?: trailId; addAlias("trail", anchor, name, geomHash, null); anchor to d }
            Decision.INSERT -> {
                exec(conn, "INSERT OR IGNORE INTO trails (trail_id,name,geometry,min_lat,max_lat,min_lon,max_lon,created_at,updated_at,geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    listOf(trailId, name, wkt, minLat, maxLat, minLon, maxLon, createdAt, createdAt, geomHash))
                noteInserted("trail", geomHash, name); trailId to d
            }
        }
    }
    private fun findTrailIdByHash(geomHash: String): String? = try { firstText(conn, "SELECT trail_id FROM trails WHERE geom_hash=? LIMIT 1", listOf(geomHash)) } catch (e: Exception) { null }
    fun deleteTrailFromDb(id: String) {
        try { exec(conn, "DELETE FROM trails WHERE trail_id=?", listOf(id)) } catch (e: Exception) { log.w { "spatial row: ${e.message}" } }
        try { dataConn?.let { exec(it, "DELETE FROM trail_properties WHERE trail_id=?", listOf(id)) } } catch (e: Exception) { log.w { "trail_properties: ${e.message}" } }
        try { dataConn?.let { exec(it, "DELETE FROM artifact_aliases WHERE artifact_type=? AND artifact_id=?", listOf("trail", id)) } } catch (e: Exception) { log.w { "aliases: ${e.message}" } }
    }
    data class TrailClear(val trails: Int, val properties: Int, val aliases: Int, val error: String?)
    fun clearAllTrailData(): TrailClear {
        if (dedupSessionActive) return TrailClear(0, 0, 0, "an import is running -- try again when it has finished")
        fun count(c: SQLiteConnection, sql: String): Int { val st = c.prepare(sql); try { return if (st.step()) st.getLong(0).toInt() else 0 } finally { st.close() } }
        var trails = 0; var props = 0; var aliases = 0; val errors = ArrayList<String>()
        try {
            trails = count(conn, "SELECT COUNT(*) FROM trails")
            exec(conn, "BEGIN", emptyList())
            try { exec(conn, "DELETE FROM trails", emptyList()); exec(conn, "COMMIT", emptyList()) } catch (e: Exception) { exec(conn, "ROLLBACK", emptyList()); throw e }
        } catch (e: Exception) { return TrailClear(0, 0, 0, "trails could not be removed: ${e.message}") }
        val ext = dataConn
        if (ext == null) errors.add("the details database is not open -- trail details were NOT removed")
        else {
            try { props = count(ext, "SELECT COUNT(*) FROM trail_properties"); exec(ext, "DELETE FROM trail_properties", emptyList()) } catch (e: Exception) { errors.add("trail details: ${e.message}") }
            try { aliases = count(ext, "SELECT COUNT(*) FROM artifact_aliases WHERE artifact_type='trail'"); exec(ext, "DELETE FROM artifact_aliases WHERE artifact_type='trail'", emptyList()) } catch (e: Exception) { errors.add("trail aliases: ${e.message}") }
        }
        return TrailClear(trails, props, aliases, if (errors.isEmpty()) null else errors.joinToString("\n"))
    }

    companion object {
        // org.json's optString / optDouble as 2.7e uses them
        fun optString(o: JsonObject, k: String): String = when (val v = o[k]) { null -> ""; is JsonPrimitive -> v.content; else -> orgJson(v) }
        fun optDouble(o: JsonObject, k: String): Double = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull() ?: 0.0

        /** org.json's JSONObject.numberToString for a Double: Double.toString, trailing zeros (and the point) dropped when there is no exponent. */
        fun numberToString(d: Double): String {
            var s = d.toString()
            if (s.indexOf('.') > 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) { s = s.trimEnd('0'); if (s.endsWith('.')) s = s.dropLast(1) }
            return s
        }

        /** JSON text as Android's org.json (JSONStringer) writes it: no spaces; quote, backslash and "/" escaped; tab, backspace, newline,
         *  return, form feed named; other control characters as \u00xx; numbers exactly as parsed. */
        fun orgJson(e: JsonElement): String = StringBuilder().also { w(it, e) }.toString()
        private fun w(sb: StringBuilder, e: JsonElement) {
            when (e) {
                is JsonObject -> { sb.append('{'); var first = true; for ((k, v) in e) { if (!first) sb.append(','); first = false; str(sb, k); sb.append(':'); w(sb, v) }; sb.append('}') }
                is JsonArray -> { sb.append('['); e.forEachIndexed { i, v -> if (i > 0) sb.append(','); w(sb, v) }; sb.append(']') }
                is JsonNull -> sb.append("null")
                is JsonPrimitive -> if (e.isString) str(sb, e.content) else sb.append(e.content)
            }
        }
        private fun str(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '/' -> sb.append("\\/")
                '\t' -> sb.append("\\t"); '\b' -> sb.append("\\b"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\u000c' -> sb.append("\\f")
                else -> if (c.code <= 0x1f) sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
            sb.append('"')
        }
    }
}
