package com.geeksville.mesh.convoy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * TRAILCHECK-2026-10-10 (Fred): "create a json with that record and email it, including whether the trail is being
 * retrieved and, if so, why it is eliminated from the display."
 *
 * Settings -> Storage -> Check a trail. The rider types the trail's Agency Id (or its Id, the start of its geom_hash,
 * or part of its name). This reads the trail's rows in BOTH databases and walks it through the SAME steps the map uses,
 * against the rider's own Map Keys and the last saved view of each map:
 *   1 zoom gate      SpatialDisplayManager: no trails below z9
 *   2 viewport       queryTrailsByViewport: stored bbox overlaps the screen
 *   3 Map Keys       TrailFilterState.whereOrEmpty() -- run as the REAL SQL against this one row
 *   4 cap            ORDER BY bbox diagonal DESC LIMIT (2,000 below z10, 5,000 from z10): its rank in that view
 *   5 ALL / SELECT   in SELECT only trail ids in the saved list draw (ids are re-issued by every import)
 * plus step 8's inputs (the ownership file: present, size, date) and the last imports' classify record
 * (a skipped classify still says completed; processed 0 is how it shows),
 * and returns one JSON the rider sends by email. READ ONLY -- nothing is written.
 *
 * ⚠ The numbers mirror SpatialDisplayManager; if those change, change these.
 */
object TrailCheck {

    private const val TRAILS_MIN_ZOOM = 9
    private fun capFor(zoom: Int) = if (zoom < 10) 2_000 else 5_000
    private const val DIAG =
        "((max_lat-min_lat)*(max_lat-min_lat)+(max_lon-min_lon)*(max_lon-min_lon))"
    private val STATE_NAMES = mapOf(0 to "OFF", 1 to "ALL", 2 to "SELECT")

    /** Runs the whole check. Call OFF the main thread: it reads every trail id once. */
    fun run(context: Context, input: String): JSONObject {
        val q = input.trim()
        val out = JSONObject()
        out.put("kind", "grouptrack_trail_check")
        out.put("generated_at", java.time.Instant.now().toString())
        out.put("app", try { com.geeksville.mesh.BuildConfig.VERSION_NAME } catch (e: Exception) { "?" })
        out.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
            " / Android " + android.os.Build.VERSION.RELEASE)
        out.put("input", q)

        SpatialDbManager.init(context)
        TrailFilterState.load()
        val where = TrailFilterState.whereOrEmpty()
        out.put("map_keys", JSONObject()
            .put("land", TrailFilterState.land)
            .put("use", TrailFilterState.use)
            .put("off", JSONArray(TrailFilterState.off.toList().sorted()))
            .put("where", where))

        val db = SpatialDbManager.getSpatialDb()
        val ext = SpatialDbManager.getExtensionDb()
        if (db == null) return out.put("verdict", "The trail database is not open.")

        // ── totals and alignment: every spatial id once, then the extension against it
        val spatialIds = HashSet<String>(200_000)
        db.rawQuery("SELECT trail_id FROM trails", null).use { while (it.moveToNext()) spatialIds.add(it.getString(0)) }
        var props = 0; var orphanProps = 0
        val withProps = HashSet<String>(spatialIds.size)
        ext?.let { e ->
            try {
                e.rawQuery("SELECT trail_id FROM trail_properties", null).use {
                    while (it.moveToNext()) {
                        props++
                        val id = it.getString(0)
                        if (id == null || id !in spatialIds) orphanProps++ else withProps.add(id)
                    }
                }
            } catch (ex: Exception) { out.put("properties_error", ex.message) }
        }
        out.put("totals", JSONObject()
            .put("trails", spatialIds.size)
            .put("trail_properties", props)
            .put("trails_without_properties", spatialIds.size - withProps.size)
            .put("properties_without_trail", orphanProps))

        // ── step 8's inputs and record: the ownership file, and what the last imports said about "_classify".
        // A classify that SKIPPED is still marked completed; processed 0 is how it shows.
        val own = OwnershipReclass.ownershipFile()
        out.put("ownership_file", JSONObject()
            .put("path", own.absolutePath)
            .put("exists", own.exists())
            .put("bytes", if (own.exists()) own.length() else 0L)
            .put("modified", if (own.exists()) java.time.Instant.ofEpochMilli(own.lastModified()).toString() else JSONObject.NULL)
            .put("usable_by_step8", own.exists() && own.length() >= 1024L))
        val imports = JSONArray()
        try {
            val dir = GroupTrackStorage.dir("imports", context)
            val files = (dir.listFiles()?.toList() ?: emptyList()) +
                (java.io.File(dir, "history").listFiles()?.toList() ?: emptyList())
            for (f in files.filter { it.isFile && it.name.endsWith("_state.json") }
                    .sortedByDescending { it.lastModified() }.take(4)) {
                val o = JSONObject().put("file", f.name)
                    .put("modified", java.time.Instant.ofEpochMilli(f.lastModified()).toString())
                try {
                    val m = JSONObject(f.readText())
                    val srcs = m.optJSONArray("sources")
                    if (srcs != null) for (i in 0 until srcs.length()) {
                        val s = srcs.optJSONObject(i) ?: continue
                        if (s.optString("id") == HomeStateImportController.CLASSIFY_STAGE_ID) {
                            o.put("classify_status", s.optString("status"))
                            o.put("classify_processed", s.opt("processed") ?: JSONObject.NULL)
                        }
                    }
                } catch (e: Exception) { o.put("read_error", e.message) }
                imports.put(o)
            }
        } catch (e: Exception) { imports.put(JSONObject().put("error", e.message)) }
        out.put("recent_imports", imports)

        // ── the saved view of each map
        val maps = JSONObject()
        val snaps = listOf("planning", "convoy").associateWith { MapStateStore.readMap(it) }
        for ((k, s) in snaps) {
            val ts = s.types["Trails"]
            val sel = MapStateStore.checkedIdsFor(s, "Trails")
            maps.put(k, JSONObject()
                .put("trails_display", STATE_NAMES[ts?.state ?: 0] ?: "?")
                .put("select_list_size", sel?.size ?: 0)
                .put("select_list_dead_ids", sel?.count { it !in spatialIds } ?: 0)
                .put("view", s.bbox?.let { b -> JSONObject().put("south", b.south).put("west", b.west)
                    .put("north", b.north).put("east", b.east).put("zoom", b.zoom) } ?: JSONObject.NULL))
        }
        out.put("maps", maps)

        // ── find the trail
        val found = LinkedHashSet<String>()
        val notes = JSONArray()
        when {
            Regex("^[0-9a-fA-F-]{36}$").matches(q) -> found.add(q)
            Regex("^[0-9]+$").matches(q) -> ext?.let { e ->
                try {
                    e.rawQuery("SELECT trail_id FROM trail_properties WHERE source_unique_id = ?", arrayOf(q)).use {
                        while (it.moveToNext()) {
                            val id = it.getString(0)
                            if (id != null && id in spatialIds) found.add(id)
                            else notes.put("trail_properties row for source id $q points at trail_id $id, " +
                                "which is NOT in the trails table (an orphan: it also blocks this trail's re-import)")
                        }
                    }
                } catch (ex: Exception) { notes.put("source id lookup failed: ${ex.message}") }
            }
            Regex("^[0-9a-f]{8,64}$").matches(q) ->
                db.rawQuery("SELECT trail_id FROM trails WHERE geom_hash LIKE ? LIMIT 10", arrayOf("$q%")).use {
                    while (it.moveToNext()) found.add(it.getString(0))
                }
            else ->
                db.rawQuery("SELECT trail_id FROM trails WHERE name LIKE ? LIMIT 10", arrayOf("%$q%")).use {
                    while (it.moveToNext()) found.add(it.getString(0))
                }
        }
        out.put("notes", notes)
        out.put("found", found.size)
        if (found.isEmpty()) {
            return out.put("verdict", "NOT RETRIEVED: no trail on this tablet matches '$q'" +
                (if (notes.length() > 0) " (see notes)" else "") + ".")
        }

        val arr = JSONArray()
        val verdicts = ArrayList<String>()
        for (id in found.take(10)) {
            val t = checkOne(db, ext, id, where, snaps, spatialIds)
            arr.put(t)
            verdicts.add(t.optString("verdict"))
        }
        out.put("trails", arr)
        out.put("verdict", verdicts.joinToString("\n"))
        return out
    }

    private fun checkOne(
        db: SQLiteDatabase, ext: SQLiteDatabase?, id: String, where: String,
        snaps: Map<String, MapStateStore.MapSnapshot>, spatialIds: Set<String>,
    ): JSONObject {
        val t = JSONObject().put("trail_id", id)

        // the spatial row, every column but the geometry body
        val row = JSONObject()
        var diag = 0.0
        db.rawQuery("SELECT *, $DIAG AS _diag FROM trails WHERE trail_id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                for (i in 0 until c.columnCount) {
                    val n = c.getColumnName(i)
                    if (n == "geometry") {
                        val g = c.getString(i) ?: ""
                        row.put("geometry_chars", g.length)
                        row.put("geometry_points", if (g.isEmpty()) 0 else g.count { it == ',' } + 1)
                        row.put("geometry_head", g.take(80))
                    } else if (n == "_diag") {
                        diag = if (c.isNull(i)) 0.0 else c.getDouble(i)
                    } else row.put(n, if (c.isNull(i)) JSONObject.NULL else c.getString(i))
                }
            }
        }
        t.put("spatial", row)

        // its rows in the extension DB
        val pr = JSONArray()
        val al = JSONArray()
        ext?.let { e ->
            try {
                e.rawQuery("SELECT * FROM trail_properties WHERE trail_id = ?", arrayOf(id)).use { c ->
                    while (c.moveToNext()) {
                        val o = JSONObject()
                        for (i in 0 until c.columnCount)
                            o.put(c.getColumnName(i), if (c.isNull(i)) JSONObject.NULL else c.getString(i))
                        pr.put(o)
                    }
                }
                e.rawQuery("SELECT alias FROM artifact_aliases WHERE artifact_type = 'trail' AND artifact_id = ?",
                    arrayOf(id)).use { c -> while (c.moveToNext()) al.put(c.getString(0)) }
            } catch (ex: Exception) { t.put("extension_error", ex.message) }
        }
        t.put("properties", pr)
        t.put("aliases", al)

        // step 3: the rider's Map Keys, the REAL SQL, plus each part on its own
        val keysPass = count(db, "SELECT COUNT(*) FROM trails WHERE trail_id = ? $where", arrayOf(id)) == 1
        val carto = row.optString("carto_code", "")
        val cartoNull = row.isNull("carto_code")
        val off = TrailFilterState.off
        val parts = JSONObject()
            .put("status", row.isNull("status") || row.optString("status") != "CLOSED")
            .put("land", TrailFilterState.land == "ALL" || row.isNull("land_status") ||
                row.optString("land_status") == TrailFilterState.land)
            .put("use", TrailFilterState.use == "ALL" || row.isNull("use_type") ||
                row.optString("use_type") == TrailFilterState.use)
            .put("category", !(off.any { it != TrailFilterState.ROW_UNOFFICIAL } && cartoNull) && carto !in off)
            .put("unofficial", !(off.contains(TrailFilterState.ROW_UNOFFICIAL) &&
                row.optString("status") in setOf("UNOFFICIAL", "UNCERTAIN")))
        t.put("map_keys_pass", keysPass)
        t.put("map_keys_parts", parts)

        // steps 1, 2, 4, 5 per map, against its last saved view
        val per = JSONObject()
        val reasons = ArrayList<String>()
        for ((k, s) in snaps) {
            val m = JSONObject()
            val state = s.types["Trails"]?.state ?: 0
            val sel = MapStateStore.checkedIdsFor(s, "Trails")
            val b = s.bbox
            var why: String? = null
            if (state == 0) why = "Trails display is OFF"
            else if (state == 2 && (sel == null || id !in sel))
                why = "Trails display is SELECT and this trail is not in the selection (" +
                    "${sel?.size ?: 0} ids, ${sel?.count { it !in spatialIds } ?: 0} of them no longer exist)"
            if (b == null) {
                m.put("view", "none saved")
            } else {
                val z = b.zoom.toInt()
                m.put("zoom", z)
                val args = arrayOf(b.south.toString(), b.north.toString(), b.west.toString(), b.east.toString())
                val bbox = "max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?"
                val inView = count(db, "SELECT COUNT(*) FROM trails WHERE trail_id = '" +
                    id.replace("'", "") + "' AND $bbox", args) == 1
                m.put("in_saved_view", inView)
                val total = count(db, "SELECT COUNT(*) FROM trails WHERE $bbox $where", args)
                val ahead = count(db, "SELECT COUNT(*) FROM trails WHERE $bbox $where AND $DIAG > $diag", args)
                m.put("trails_in_view_after_keys", total)
                m.put("rank_in_view", ahead + 1)
                m.put("cap_at_this_zoom", capFor(z))
                if (why == null) why = when {
                    z < TRAILS_MIN_ZOOM -> "the saved view is zoom $z; trails draw from zoom $TRAILS_MIN_ZOOM"
                    !inView -> "the trail is not inside this map's last saved view (pan to it and run the check again)"
                    !keysPass -> "Map Keys exclude it: " + parts.keys().asSequence()
                        .filter { !parts.optBoolean(it, true) }.joinToString(", ")
                    ahead + 1 > capFor(z) -> "it ranks ${ahead + 1} in this view and only ${capFor(z)} draw at zoom $z"
                    else -> null
                }
            }
            if (why == null && b != null && !keysPass)
                why = "Map Keys exclude it"
            m.put("trails_display", STATE_NAMES[state] ?: "?")
            m.put("drawn", why == null && b != null)
            m.put("reason", why ?: if (b == null) "no saved view to test" else
                "passes every step -- it is sent to the map page (if it still does not show, the page draw is next)")
            per.put(k, m)
            reasons.add("$k map: " + (why ?: if (b == null) "no saved view" else "SHOULD DRAW"))
        }
        t.put("maps", per)
        val name = row.optString("name", "").ifBlank { "Not Named" }
        t.put("verdict", "RETRIEVED: $name ($id) -- " + reasons.joinToString("; "))
        return t
    }

    private fun count(db: SQLiteDatabase, sql: String, args: Array<String>): Int =
        db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
