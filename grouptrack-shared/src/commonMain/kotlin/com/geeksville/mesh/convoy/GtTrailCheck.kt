package com.geeksville.mesh.convoy

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.filter.TrailFilterState
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.mapstate.GtMapSnapshot
import com.grouptrack.shared.mapstate.GtMapState
import com.grouptrack.shared.platform.GtBuildInfo
import com.grouptrack.shared.text.GtTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * TRAILCHECK-2026-11-21: the shared port of app/.../convoy/TrailCheck.kt (291 lines), unchanged in
 * behaviour. A.MENU > settings > CHECK A TRAIL.
 *
 * WHAT IT ANSWERS: "I imported this trail and it is not on my map -- why." It walks ONE trail through
 * the map's five REAL gates and returns one JSON the rider emails:
 *   1. the z9 zoom gate            2. the viewport bbox overlap
 *   3. the rider's Map Keys, run as ACTUAL SQL against that one row, plus each part on its own
 *   4. the 2,000 / 5,000 cap at this zoom, and this row's RANK inside that view
 *   5. ALL vs SELECT, and whether a SELECT list still contains the id
 * plus the ownership file (present, size, date) and what the last four imports said about "_classify".
 *
 * ⛔ WHY THE RANK MATTERS: it is the one number no stub can fake. A gate that says "passed" proves
 * nothing; "ranks 1,344 of 2,000" can only come from running the query.
 *
 * ⛔ THE CAPS AND THE MIN ZOOM ARE MIRRORED FROM SpatialDisplayManager and must change together --
 * that was true in 2.7e and it is still true here.
 *
 * Every argument is the HOST's (SEAMRULE-2026-11-19): this object opens nothing, finds nothing and
 * owns no lifetime. Call it OFF the main thread -- it reads every trail id once.
 */
object GtTrailCheck {

    private const val TRAILS_MIN_ZOOM = 9
    private fun capFor(zoom: Int) = if (zoom < 10) 2_000 else 5_000
    private const val DIAG =
        "((max_lat-min_lat)*(max_lat-min_lat)+(max_lon-min_lon)*(max_lon-min_lon))"
    private val STATE_NAMES = mapOf(0 to "OFF", 1 to "ALL", 2 to "SELECT")
    /** HomeStateImportController.CLASSIFY_STAGE_ID -- Android-only there, one constant here. */
    private const val CLASSIFY_STAGE_ID = "_classify"

    // ---- tiny JSON helpers. GtOrgJson gives the primitives; these give the two containers, so the
    // ---- transcription below reads like the original's .put() chains.
    private fun v(x: Any?): JsonElement = when (x) {
        null -> JsonNull
        is String -> GtOrgJson.str(x)
        is Int -> GtOrgJson.num(x)
        is Long -> GtOrgJson.num(x)
        is Double -> GtOrgJson.num(x)
        is Boolean -> GtOrgJson.bool(x)
        is JsonElement -> x
        else -> GtOrgJson.str(x.toString())
    }
    private fun obj(m: Map<String, Any?>): JsonObject = JsonObject(m.mapValues { v(it.value) })
    private fun arr(l: List<Any?>): JsonArray = JsonArray(l.map { v(it) })

    // ---- SQLite, the shared way: prepare / bind / step, as GtNetworks.kt:100 does.
    private inline fun <T> query(c: SQLiteConnection, sql: String, args: List<String>, f: (SQLiteStatement) -> T): T {
        val st = c.prepare(sql)
        try {
            args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
            return f(st)
        } finally { st.close() }
    }
    private fun count(c: SQLiteConnection, sql: String, args: List<String>): Int =
        query(c, sql, args) { if (it.step()) it.getInt(0) else 0 }
    private fun utc(ms: Long): String = GtTime.formatUtcSeconds(ms)

    /**
     * @param spatial the spatial db (trails). @param ext the extension db, or null.
     * @param mapState reads each map's last saved view. @param ownershipPath GtHkEnv.ownershipFilePath().
     * @param importsDir GroupTrackStorage.dir("imports") -- its "history" child is read too.
     * @param fs the caller's okio FileSystem. @param build GtBuildInfo. @param nowMs the caller's clock.
     */
    fun run(
        spatial: SQLiteConnection,
        ext: SQLiteConnection?,
        mapState: GtMapState,
        ownershipPath: String,
        importsDir: Path,
        fs: FileSystem,
        build: GtBuildInfo,
        nowMs: Long,
        input: String,
    ): JsonObject {
        val q = input.trim()
        val out = LinkedHashMap<String, Any?>()
        out["kind"] = "grouptrack_trail_check"
        out["generated_at"] = utc(nowMs)
        out["app"] = try { build.appVersion() } catch (e: Exception) { "?" }
        out["device"] = try { build.device() } catch (e: Exception) { "?" }
        out["input"] = q

        TrailFilterState.load()
        val where = TrailFilterState.whereOrEmpty()
        out["map_keys"] = obj(linkedMapOf(
            "land" to TrailFilterState.land,
            "use" to TrailFilterState.use,
            "off" to arr(TrailFilterState.off.toList().sorted()),
            "where" to where,
        ))

        // ── totals and alignment: every spatial id once, then the extension against it
        val spatialIds = HashSet<String>(200_000)
        query(spatial, "SELECT trail_id FROM trails", emptyList()) { st -> while (st.step()) spatialIds.add(st.getText(0)) }
        var props = 0; var orphanProps = 0
        val withProps = HashSet<String>(spatialIds.size)
        if (ext != null) {
            try {
                query(ext, "SELECT trail_id FROM trail_properties", emptyList()) { st ->
                    while (st.step()) {
                        props++
                        val id = if (st.isNull(0)) null else st.getText(0)
                        if (id == null || id !in spatialIds) orphanProps++ else withProps.add(id)
                    }
                }
            } catch (ex: Exception) { out["properties_error"] = ex.message }
        }
        out["totals"] = obj(linkedMapOf(
            "trails" to spatialIds.size,
            "trail_properties" to props,
            "trails_without_properties" to (spatialIds.size - withProps.size),
            "properties_without_trail" to orphanProps,
        ))

        // ── step 8's inputs and record: the ownership file, and what the last imports said about
        // "_classify". A classify that SKIPPED is still marked completed; processed 0 is how it shows.
        val ownPath = ownershipPath.toPathOrNull()
        val ownMeta = if (ownPath == null) null else fs.metadataOrNull(ownPath)
        val ownBytes = ownMeta?.size ?: 0L
        out["ownership_file"] = obj(linkedMapOf(
            "path" to ownershipPath,
            "exists" to (ownMeta != null),
            "bytes" to ownBytes,
            "modified" to (ownMeta?.lastModifiedAtMillis?.let { utc(it) }),
            "usable_by_step8" to (ownMeta != null && ownBytes >= 1024L),
        ))
        val imports = ArrayList<Any?>()
        try {
            val files = ArrayList<Path>()
            for (d in listOf(importsDir, importsDir / "history"))
                if (fs.metadataOrNull(d)?.isDirectory == true) files.addAll(fs.list(d))
            val stateFiles = files
                .filter { fs.metadataOrNull(it)?.isDirectory != true && it.name.endsWith("_state.json") }
                .sortedByDescending { fs.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }
                .take(4)
            for (f in stateFiles) {
                val o = LinkedHashMap<String, Any?>()
                o["file"] = f.name
                o["modified"] = fs.metadataOrNull(f)?.lastModifiedAtMillis?.let { utc(it) }
                try {
                    val m = GtOrgJson.parse(fs.read(f) { readUtf8() }) as? JsonObject
                    val srcs = m?.get("sources") as? JsonArray
                    if (srcs != null) for (s0 in srcs) {
                        val s = s0 as? JsonObject ?: continue
                        if (GtOrgJson.optString(s, "id") == CLASSIFY_STAGE_ID) {
                            o["classify_status"] = GtOrgJson.optString(s, "status")
                            o["classify_processed"] = s["processed"]?.let { GtOrgJson.valueText(it) }
                        }
                    }
                } catch (e: Exception) { o["read_error"] = e.message }
                imports.add(obj(o))
            }
        } catch (e: Exception) { imports.add(obj(linkedMapOf("error" to e.message))) }
        out["recent_imports"] = arr(imports)

        // ── the saved view of each map
        val maps = LinkedHashMap<String, Any?>()
        val snaps = listOf("planning", "convoy", "grouptrack")   /* PERSISTKEY-2026-10-23 */
            .associateWith { mapState.readMap(it) }
        for ((k, s) in snaps) {
            val ts = s.types["Trails"]
            val sel = GtMapState.checkedIdsFor(s, "Trails")
            val b = s.bbox
            maps[k] = obj(linkedMapOf(
                "trails_display" to (STATE_NAMES[ts?.state ?: 0] ?: "?"),
                "select_list_size" to (sel?.size ?: 0),
                "select_list_dead_ids" to (sel?.count { it !in spatialIds } ?: 0),
                "view" to (if (b == null) null else obj(linkedMapOf(
                    "south" to b.south, "west" to b.west, "north" to b.north, "east" to b.east,
                    "zoom" to b.zoom,
                ))),
            ))
        }
        out["maps"] = obj(maps)

        // ── find the trail
        val found = LinkedHashSet<String>()
        val notes = ArrayList<Any?>()
        when {
            Regex("^[0-9a-fA-F-]{36}$").matches(q) -> found.add(q)
            Regex("^[0-9]+$").matches(q) -> if (ext != null) {
                try {
                    query(ext, "SELECT trail_id FROM trail_properties WHERE source_unique_id = ?", listOf(q)) { st ->
                        while (st.step()) {
                            val id = if (st.isNull(0)) null else st.getText(0)
                            if (id != null && id in spatialIds) found.add(id)
                            else notes.add("trail_properties row for source id " + q + " points at trail_id " + id +
                                ", which is NOT in the trails table (an orphan: it also blocks this trail's re-import)")
                        }
                    }
                } catch (ex: Exception) { notes.add("source id lookup failed: " + ex.message) }
            }
            Regex("^[0-9a-f]{8,64}$").matches(q) ->
                query(spatial, "SELECT trail_id FROM trails WHERE geom_hash LIKE ? LIMIT 10", listOf(q + "%")) { st ->
                    while (st.step()) found.add(st.getText(0))
                }
            else ->
                query(spatial, "SELECT trail_id FROM trails WHERE name LIKE ? LIMIT 10", listOf("%" + q + "%")) { st ->
                    while (st.step()) found.add(st.getText(0))
                }
        }
        out["notes"] = arr(notes)
        out["found"] = found.size
        if (found.isEmpty()) {
            out["verdict"] = "NOT RETRIEVED: no trail on this tablet matches '" + q + "'" +
                (if (notes.isNotEmpty()) " (see notes)" else "") + "."
            return obj(out)
        }

        val trails = ArrayList<Any?>()
        val verdicts = ArrayList<String>()
        for (id in found.take(10)) {
            val t = checkOne(spatial, ext, id, where, snaps, spatialIds)
            trails.add(obj(t))
            verdicts.add(t["verdict"] as? String ?: "")
        }
        out["trails"] = arr(trails)
        out["verdict"] = verdicts.joinToString("\n")
        return obj(out)
    }

    /** TRAILCHECKFIX-2026-11-21: okio's toPath is an EXTENSION on String, imported above -- not a
     *  companion call. A malformed path returns null rather than throwing, so a bad ownership path
     *  reports "exists: false" instead of failing the whole check. */
    private fun String.toPathOrNull(): Path? = try { this.toPath() } catch (e: Throwable) { null }

    private fun checkOne(
        spatial: SQLiteConnection, ext: SQLiteConnection?, id: String, where: String,
        snaps: Map<String, GtMapSnapshot>, spatialIds: Set<String>,
    ): LinkedHashMap<String, Any?> {
        val t = LinkedHashMap<String, Any?>()
        t["trail_id"] = id

        // the spatial row, every column but the geometry body.
        // ⛔ Android built this as a JSONObject and read it back for the Map Keys parts; kotlinx elements
        // are immutable, so it is a LinkedHashMap here -- same values, same order, same checks.
        val row = LinkedHashMap<String, Any?>()
        var diag = 0.0
        query(spatial, "SELECT *, " + DIAG + " AS _diag FROM trails WHERE trail_id = ?", listOf(id)) { c ->
            if (c.step()) {
                for (i in 0 until c.getColumnCount()) {
                    val n = c.getColumnName(i)
                    if (n == "geometry") {
                        val g = if (c.isNull(i)) "" else c.getText(i)
                        row["geometry_chars"] = g.length
                        row["geometry_points"] = if (g.isEmpty()) 0 else g.count { it == ',' } + 1
                        row["geometry_head"] = g.take(80)
                    } else if (n == "_diag") {
                        diag = if (c.isNull(i)) 0.0 else c.getDouble(i)
                    } else row[n] = if (c.isNull(i)) null else c.getText(i)
                }
            }
        }
        t["spatial"] = obj(row)

        // its rows in the extension DB
        val pr = ArrayList<Any?>()
        val al = ArrayList<Any?>()
        if (ext != null) {
            try {
                query(ext, "SELECT * FROM trail_properties WHERE trail_id = ?", listOf(id)) { c ->
                    while (c.step()) {
                        val o = LinkedHashMap<String, Any?>()
                        for (i in 0 until c.getColumnCount())
                            o[c.getColumnName(i)] = if (c.isNull(i)) null else c.getText(i)
                        pr.add(obj(o))
                    }
                }
                query(ext, "SELECT alias FROM artifact_aliases WHERE artifact_type = 'trail' AND artifact_id = ?",
                    listOf(id)) { c -> while (c.step()) al.add(c.getText(0)) }
            } catch (ex: Exception) { t["extension_error"] = ex.message }
        }
        t["properties"] = arr(pr)
        t["aliases"] = arr(al)

        // step 3: the rider's Map Keys, the REAL SQL, plus each part on its own
        val keysPass = count(spatial, "SELECT COUNT(*) FROM trails WHERE trail_id = ? " + where, listOf(id)) == 1
        val carto = row["carto_code"] as? String ?: ""
        val cartoNull = row["carto_code"] == null
        val off = TrailFilterState.off
        val status = row["status"] as? String
        val parts = LinkedHashMap<String, Boolean>()
        parts["status"] = status == null || status != "CLOSED"
        parts["land"] = TrailFilterState.land == "ALL" || row["land_status"] == null ||
            row["land_status"] as? String == TrailFilterState.land
        parts["use"] = TrailFilterState.use == "ALL" || row["use_type"] == null ||
            row["use_type"] as? String == TrailFilterState.use
        parts["category"] = !(off.any { it != TrailFilterState.ROW_UNOFFICIAL } && cartoNull) && carto !in off
        parts["unofficial"] = !(off.contains(TrailFilterState.ROW_UNOFFICIAL) &&
            (status == "UNOFFICIAL" || status == "UNCERTAIN"))
        t["map_keys_pass"] = keysPass
        t["map_keys_parts"] = obj(parts.mapValues { it.value as Any? })

        // steps 1, 2, 4, 5 per map, against its last saved view
        val per = LinkedHashMap<String, Any?>()
        val reasons = ArrayList<String>()
        for ((k, s) in snaps) {
            val m = LinkedHashMap<String, Any?>()
            val state = s.types["Trails"]?.state ?: 0
            val sel = GtMapState.checkedIdsFor(s, "Trails")
            val b = s.bbox
            var why: String? = null
            if (state == 0) why = "Trails display is OFF"
            else if (state == 2 && (sel == null || id !in sel))
                why = "Trails display is SELECT and this trail is not in the selection (" +
                    (sel?.size ?: 0) + " ids, " + (sel?.count { it !in spatialIds } ?: 0) + " of them no longer exist)"
            if (b == null) {
                m["view"] = "none saved"
            } else {
                val z = b.zoom.toInt()
                m["zoom"] = z
                val args = listOf(b.south.toString(), b.north.toString(), b.west.toString(), b.east.toString())
                val bbox = "max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?"
                val inView = count(spatial, "SELECT COUNT(*) FROM trails WHERE trail_id = '" +
                    id.replace("'", "") + "' AND " + bbox, args) == 1
                m["in_saved_view"] = inView
                val total = count(spatial, "SELECT COUNT(*) FROM trails WHERE " + bbox + " " + where, args)
                val ahead = count(spatial, "SELECT COUNT(*) FROM trails WHERE " + bbox + " " + where +
                    " AND " + DIAG + " > " + diag, args)
                m["trails_in_view_after_keys"] = total
                m["rank_in_view"] = ahead + 1
                m["cap_at_this_zoom"] = capFor(z)
                if (why == null) why = when {
                    z < TRAILS_MIN_ZOOM -> "the saved view is zoom " + z + "; trails draw from zoom " + TRAILS_MIN_ZOOM
                    !inView -> "the trail is not inside this map's last saved view (pan to it and run the check again)"
                    !keysPass -> "Map Keys exclude it: " + parts.filter { !it.value }.keys.joinToString(", ")
                    ahead + 1 > capFor(z) -> "it ranks " + (ahead + 1) + " in this view and only " + capFor(z) +
                        " draw at zoom " + z
                    else -> null
                }
            }
            if (why == null && b != null && !keysPass) why = "Map Keys exclude it"
            m["trails_display"] = STATE_NAMES[state] ?: "?"
            m["drawn"] = why == null && b != null
            m["reason"] = why ?: if (b == null) "no saved view to test" else
                "passes every step -- it is sent to the map page (if it still does not show, the page draw is next)"
            per[k] = obj(m)
            reasons.add(k + " map: " + (why ?: if (b == null) "no saved view" else "SHOULD DRAW"))
        }
        t["maps"] = obj(per)
        val nm = row["name"] as? String ?: ""
        val name = if (nm.isBlank()) "Not Named" else nm
        t["verdict"] = "RETRIEVED: " + name + " (" + id + ") -- " + reasons.joinToString("; ")
        return t
    }

    /** The email body Android sends: JSONObject.toString(2). GtOrgJson.pretty IS toString(2). */
    fun pretty(result: JsonObject): String = GtOrgJson.pretty(result, 2)
}
