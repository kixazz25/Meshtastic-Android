package com.geeksville.mesh.convoy

import com.grouptrack.shared.filter.TrailFilterState
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.route.GtRoute
import com.grouptrack.shared.text.GtFmt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * BATCHLAYER-2026-11-24 (Build B1): the AI batch layer, in commonMain, over GtRoute.env's json
 * primitives -- so the iPad can write and read what Android's RouteDraftStore writes and reads.
 *
 * WHY HERE AND NOT A MOVE: RouteDraftStore's versions are File + org.json, neither of which
 * exists in commonMain, so this is a translation. Android keeps its certified copies untouched;
 * the two collapse after the iPad certifies (consolidation list, with GtRoute / SelTable /
 * PinFeasibility / hasOpenBatch).
 *
 * ATOMICITY is preserved exactly as RouteDraftStore does it: write <path>.tmp, then rename it
 * on. JSONRENAME-2026-11-23 added env.jsonRename for this, and both platforms have a real
 * rename (File.renameTo / okio atomicMove), so neither falls back to copy-and-delete.
 *
 * PRETTY PRINT: RouteDraftStore writes o.toString(2) -- two-space indent. prettyPrintIndent is
 * set to match, so a file written by either platform reads the same to a human.
 */
object GtRouteBatch {

    private val log = gtLogger("GtRouteBatch")

    /** RouteDraftStore BATCH_DIR (:95) + BATCH_FILE (:94), under the route json root. */
    const val BATCH = "batch/open_batch.json"

    /** RouteDraftStore LAST_SEARCH_FILE (:170), in the same batch dir. */
    const val LAST_SEARCH = "batch/last_ai_search.json"

    private val J = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /**
     * RouteDraftStore BATCH_COLOURS (:77-88), carried verbatim including the reasoning.
     * RED WAS EXCLUDED ON PURPOSE -- the Distance HUD and the rider's pins own #FF0000.
     * Fred's call, 08-27: the HUD is corner text and a pin is a small circle, while this is a
     * thin line across the ground. The amber/orange collision it replaced was real -- they read
     * as the same colour on satellite.
     */
    private val BATCH_COLOURS = listOf(
        "#4FA3E3",   // blue
        "#FFC53D",   // amber
        "#4ADE80",   // green
        "#C084FC",   // violet
        "#FF3B30",   // red
        "#22D3EE",   // cyan
    )

    /** The colour for route [i] of a batch. Wraps if a batch ever exceeds six. */
    fun batchColour(i: Int): String = BATCH_COLOURS[i % BATCH_COLOURS.size]

    private fun parse(text: String): JsonObject? =
        runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

    /** RouteDraftStore.readBatch (:139-148). A corrupt batch must not strand the rider:
     *  report it and let the caller fall through to the ordinary WIP list. */
    fun readBatch(): JsonObject? {
        val text = GtRoute.env.jsonRead(BATCH) ?: return null
        val o = parse(text)
        if (o == null) log.w { "batch unreadable, ignoring" }
        return o
    }

    /** The atomic write RouteDraftStore uses everywhere: .tmp then rename on. */
    private fun writeAtomic(path: String, text: String): Boolean {
        val tmp = path + ".tmp"
        if (!GtRoute.env.jsonWrite(tmp, text)) return false
        return GtRoute.env.jsonRename(tmp, path)
    }

    /**
     * RouteDraftStore.writeBatch (:354-469). Same keys, same order, same defaults.
     *   * NAME AND COLOUR TOGETHER -- the grid and the map read the same value, so a legend
     *     entry and a line on the ground cannot disagree, and the colour survives a restart.
     *   * THE RECIPE is every parameter the rider chose and nothing derived, so the parameter
     *     list IS the recipe and the two cannot drift.
     *   * RECIPEFILTER-2026-09-02: the trail selection is a parameter the rider chose, so it
     *     belongs in the recipe. Without it a rebuild is not a rebuild. The PREDICATE STRING,
     *     not the state (Fred 09-02), and "" means no filter was set -- different from the key
     *     being absent, which means a recipe written before this existed.
     *   * RECIPEINDRAFT-2026-08-29: and into every draft this batch names, as the SAME object,
     *     because SAVE SELECTED copies a draft's notes into route_notes and deletes the batch.
     */
    fun writeBatch(
        batchName: String,
        draftNames: List<String>,
        miles: List<Double> = emptyList(),
        hoursLow: List<Double> = emptyList(),
        hoursHigh: List<Double> = emptyList(),
        anchorLat: Double, anchorLon: Double, anchorName: String?,
        milesLow: Double, milesHigh: Double,
        mphLow: Double, mphHigh: Double,
        pins: List<Pair<Double, Double>>,
        finish: Pair<Double, Double>?,
    ) {
        if (draftNames.isEmpty()) {
            // No drafts means nothing to resolve. Writing a batch here would lock route
            // creation behind an empty compare screen.
            log.i { "no drafts, no batch written" }
            return
        }
        val r = buildJsonObject {
            put("anchorLat", anchorLat)
            put("anchorLon", anchorLon)
            if (anchorName == null) put("anchorName", JsonNull) else put("anchorName", anchorName)
            put("milesLow", milesLow); put("milesHigh", milesHigh)
            put("mphLow", mphLow); put("mphHigh", mphHigh)
            put("pins", buildJsonArray {
                for (p in pins) add(buildJsonObject { put("lat", p.first); put("lon", p.second) })
            })
            if (finish == null) put("finish", JsonNull)
            else put("finish", buildJsonObject { put("lat", finish.first); put("lon", finish.second) })
            put("trailFilter", TrailFilterState.whereOrEmpty())
        }
        val o = buildJsonObject {
            put("schemaVersion", 1)
            put("batchName", batchName)
            put("createdAt", GtRoute.env.nowIso())
            put("routes", buildJsonArray {
                draftNames.forEachIndexed { i, n ->
                    add(buildJsonObject {
                        put("name", n)
                        put("colour", batchColour(i))
                        put("miles", miles.getOrElse(i) { 0.0 })
                        put("hoursLow", hoursLow.getOrElse(i) { 0.0 })
                        put("hoursHigh", hoursHigh.getOrElse(i) { 0.0 })
                    })
                }
            })
            put("recipe", r)
        }
        // The recipe into every draft this batch names -- the same object, not a second copy.
        for (nm in draftNames) {
            runCatching {
                val path = nm + ".json"
                val text = GtRoute.env.jsonRead(path) ?: return@runCatching
                val dj = parse(text) ?: return@runCatching
                val notes = (dj["notes"] as? JsonObject) ?: JsonObject(emptyMap())
                val merged = buildJsonObject {
                    for ((k, v) in dj) if (k != "notes") put(k, v)
                    put("notes", buildJsonObject {
                        for ((k, v) in notes) if (k != "recipe") put(k, v)
                        put("recipe", r)
                    })
                }
                if (!writeAtomic(path, J.encodeToString(JsonObject.serializer(), merged)))
                    log.w { "recipe into draft '" + nm + "' failed: write" }
            }.onFailure { log.w { "recipe into draft '" + nm + "' failed: " + it.message } }
        }
        if (writeAtomic(BATCH, J.encodeToString(JsonObject.serializer(), o)))
            log.i { "batch '" + batchName + "' written with " + draftNames.size + " route(s)" }
        else log.e { "batch write failed" }
    }

    /** RouteDraftStore.recordSearchOutcome (:173-180). Written at the search's end so it
     *  survives a rebuilt screen. */
    fun recordSearchOutcome(routes: Int, headerWritten: Boolean, error: String?) {
        runCatching {
            val o = buildJsonObject {
                put("at", GtRoute.env.nowIso())
                put("routes", routes)
                put("headerWritten", headerWritten)
                if (error != null) put("error", error)
            }
            GtRoute.env.jsonWrite(LAST_SEARCH, J.encodeToString(JsonObject.serializer(), o))
            log.i { "ROUTEFILES: last AI search -- routes=" + routes + " header=" + headerWritten +
                " error=" + (error ?: "-") }
        }.onFailure { log.w { "ROUTEFILES: could not record the search outcome: " + it.message } }
    }

    /** RouteDraftStore.recordSearchDetail (:183-191): the recipe and what the search worked
     *  on, kept with its outcome -- success or not. */
    fun recordSearchDetail(text: String, recipe: JsonObject) {
        runCatching {
            val existing = GtRoute.env.jsonRead(LAST_SEARCH)?.let { parse(it) } ?: JsonObject(emptyMap())
            val o = buildJsonObject {
                for ((k, v) in existing) if (k != "searched" && k != "recipe") put(k, v)
                put("searched", text)
                put("recipe", recipe)
            }
            GtRoute.env.jsonWrite(LAST_SEARCH, J.encodeToString(JsonObject.serializer(), o))
        }.onFailure { log.w { "ROUTEFILES: could not record the search detail: " + it.message } }
    }

    /** RouteDraftStore's BatchRow (ConvoyBatchGridPanel.kt:59), same fields and defaults.
     *  GtBatchRow because Android's BatchRow is in this same package in the app module. */
    data class GtBatchRow(
        val name: String,
        val colour: String,
        val miles: Double,
        val hoursLow: Double,
        val hoursHigh: Double,
        val features: Map<String, Double> = emptyMap(),
        val trails: Map<String, Double> = emptyMap(),
    )

    /** RouteDraftStore.demojibake (:565-569): UTF-8 read as latin-1, as it reaches the notes. */
    private fun demojibake(s: String): String =
        s.replace("\u00e2\u20ac\u201d", "-")
         .replace("\u00e2\u20ac\u2122", "'")
         .replace("\u00e2\u20ac\u0153", "\"")
         .replace("\u00e2\u20ac\u009d", "\"")

    /** RouteDraftStore.readNotes (:772-781): the draft's notes object, or null. */
    private fun notesOf(name: String): JsonObject? {
        val text = GtRoute.env.jsonRead(name + ".json") ?: return null
        val o = parse(text) ?: return null
        return o["notes"] as? JsonObject
    }

    /** RouteDraftStore.featuresOf (:572-586): "mile 29  Upper Point - summit" -> "Upper Point" to 29.0 */
    private fun featuresOf(notes: JsonObject?): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        val arr = (notes?.get("narrative") as? JsonObject)?.get("stops_in_order") as? JsonArray
            ?: return out
        val re = Regex("""^mile\s+([\d.]+)\s+(.+?)(?:\s+-\s+.*)?$""")
        for (e in arr) {
            val raw = demojibake((e as? JsonPrimitive)?.content.orEmpty()).trim()
            val m = re.find(raw)
            if (m != null) out[m.groupValues[2].trim()] = m.groupValues[1].toDoubleOrNull() ?: 0.0
            else if (raw.isNotBlank()) out[raw] = 0.0
        }
        return out
    }

    /** RouteDraftStore.trailsOf (:591-601): legs[] summed per trail. */
    private fun trailsOf(notes: JsonObject?): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        val arr = notes?.get("legs") as? JsonArray ?: return out
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val t = demojibake(str(o, "trail")).trim()
            if (t.isBlank()) continue
            out[t] = (out[t] ?: 0.0) + num(o, "miles")
        }
        return out
    }

    /**
     * BATCHDRAW-2026-11-24: RouteDraftStore.drawBatch (:505-556), whole. ONE pass over the batch
     * json DRAWS each route in its colour AND builds its row -- Fred 16:27: "one process builds
     * both display." The js calls are Android's own, verbatim, through GtRouteLaunch.js.
     *
     * ⚠ VISIBLE: a draft the batch names but that is gone from disk is logged and skipped --
     *   otherwise the rider compares five lines against a legend showing six.
     * ⚠ ESCAPED: draft names carry spaces and could carry an apostrophe, which would end the JS
     *   string literal silently.
     * ⚠ GRIDFIX-2026-08-27 (Fred): ZOOM, NOT FIT. fitBatchRoutes posts to main like every other
     *   map call, so it can run BEFORE the draws it is meant to frame. setZoom has no such
     *   dependency. Once, not locked -- the rider pans and zooms from there.
     */
    fun drawBatch(): List<GtBatchRow> {
        val b = readBatch() ?: return emptyList()
        val arr = b["routes"] as? JsonArray ?: return emptyList()
        val js = com.geeksville.mesh.grouptrack.GtRouteLaunch.js
        js("clearBatchRoutes()")
        val rows = ArrayList<GtBatchRow>()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val nm = str(o, "name")
            if (nm.isEmpty()) continue
            val col = str(o, "colour", "#FF00FF")
            val dr = GtRouteDraft.openDraft(nm)
            if (dr == null) {
                log.w { "batch names '" + nm + "' but the draft is gone" }
                continue
            }
            val pts = dr.vertices.joinToString(",", "[", "]") {
                "[" + GtFmt.f(it.lat, 6) + "," + GtFmt.f(it.lon, 6) + "]"
            }
            val safe = nm.replace("\\", "\\\\").replace("'", "\\'")
            js("drawBatchRoute('" + safe + "', '" + pts + "', '" + col + "')")
            val notes = notesOf(nm)
            rows.add(GtBatchRow(nm, col,
                num(o, "miles"), num(o, "hoursLow"), num(o, "hoursHigh"),
                featuresOf(notes), trailsOf(notes)))
        }
        js("map.setZoom(11)")
        log.i { "drew " + rows.size + " batch route(s)" }
        return rows
    }

    /**
     * COMPAREMAP-2026-11-24: the showCompareContext call, built from the batch's OWN recipe so
     * the pins and the perimeter match the rides shown (AICMPCTX-2026-11-11, Fred 10:07-10:08).
     * RouteModule.kt:164-179 verbatim, with its arithmetic: the perimeter is drawn only for a
     * LOOP ride (no finish point, a real anchor, a ceiling), and is milesHigh/3 either side.
     * Math is GtMath / GtJava, the TrackRules.kt:52 idiom. null = nothing to draw.
     */
    fun compareContextJs(): String? {
        val bt = readBatch() ?: return null
        // AICMPCTXFIX-2026-11-11: the pins, trailhead and ceiling live under recipe.
        val b = (bt["recipe"] as? JsonObject) ?: bt
        val pins = b["pins"] as? JsonArray
        val pj = StringBuilder("[")
        if (pins != null) for (e in pins) {
            val o = e as? JsonObject ?: continue
            if (pj.length > 1) pj.append(",")
            pj.append("[").append(GtFmt.f(num(o, "lat"), 6)).append(",")
                .append(GtFmt.f(num(o, "lon"), 6)).append("]")
        }
        pj.append("]")
        val la = num(b, "anchorLat"); val lo = num(b, "anchorLon"); val hi = num(b, "milesHigh")
        val noFinish = b["finish"] == null || b["finish"] is kotlinx.serialization.json.JsonNull
        val loopRide = noFinish && !(la == 0.0 && lo == 0.0) && hi > 0.0
        val box = if (!loopRide) "null,null,null,null" else {
            val dLat = hi / 3.0 / 69.0
            val dLon = hi / 3.0 / (69.0 * maxOf(0.2,
                com.grouptrack.shared.text.GtMath.cos(com.grouptrack.shared.text.GtJava.toRadians(la))))
            GtFmt.f(la + dLat, 6) + "," + GtFmt.f(la - dLat, 6) + "," +
                GtFmt.f(lo + dLon, 6) + "," + GtFmt.f(lo - dLon, 6)
        }
        return "if (window.showCompareContext) showCompareContext('" + pj.toString() + "'," + box + ")"
    }

    /**
     * BATCHDELETE-2026-11-24: delete the whole compare set -- the batch header and every draft
     * it names. Returns how many drafts went.
     *
     * \u26d4 THE HEADER FIRST. RouteDraftStore.deleteDraft:908-914 (BATCHDELETE-2026-10-07,
     * Fred) refuses a draft that still belongs to the open batch: "the batch is resolved in the
     * compare table, which clears the batch header first (then these are ordinary drafts)".
     * Clearing it first is therefore not tidiness -- the other order deletes nothing.
     */
    fun deleteWholeBatch(): Int {
        val names = (readBatch()?.get("routes") as? JsonArray)
            ?.mapNotNull { e -> (e as? JsonObject)?.let { str(it, "name") }?.takeIf { it.isNotEmpty() } }
            ?: emptyList()
        GtRoute.env.jsonDelete(BATCH)   // the header FIRST -- see above
        var gone = 0
        for (nm in names) if (GtRoute.env.jsonDelete(nm + ".json")) gone++
        log.i { "deleted the compare set: " + gone + " of " + names.size + " draft(s)" }
        return gone
    }

    /**
     * SURFACES-2026-11-24: trail name -> surface label, for the compare table's trail rows.
     * AiCompareTable.kt:128-168's two queries, shared so one copy serves both platforms.
     *
     * \u26a0 Keyed on NAME because that is what GtBatchRow.trails carries; duplicate names
     *   collapse to whichever row comes first, which is fine for a label and would NOT be for
     *   anything that routes (the original's own warning, kept).
     * [spatial] holds trails, [data] holds trail_properties -- Android's getSpatialDb() and
     * getExtensionDb(), which is "grouptrack_data.db" (SpatialDbManager.kt:41).
     * Empty map on any failure: the label falls back to the bare trail name, as it always did.
     * A null [data] is logged, not swallowed -- trail_properties is written by the trail import
     * (GtTrailImport.kt:493 carries surface_type), so a missing handle is a real fault to see.
     */
    fun surfacesFor(
        spatial: androidx.sqlite.SQLiteConnection,
        data: androidx.sqlite.SQLiteConnection?,
        names: List<String>,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (names.isEmpty()) return out
        if (data == null) { log.w { "surface lookup: no extension db handle" }; return out }
        try {
            // 1. names -> ids, from the spatial database
            val idToName = LinkedHashMap<String, String>()
            val marks = names.joinToString(",") { "?" }
            val st = spatial.prepare("SELECT trail_id, name FROM trails WHERE name IN (" + marks + ")")
            try {
                names.forEachIndexed { i, n -> st.bindText(i + 1, n) }
                while (st.step()) {
                    val id = com.grouptrack.shared.spatial.GtRead.cellText(st, 0) ?: continue
                    idToName[id] = com.grouptrack.shared.spatial.GtRead.cellText(st, 1) ?: continue
                }
            } finally { st.close() }
            // 2. ids -> surface, from the extension database
            if (idToName.isNotEmpty()) {
                val ids = idToName.keys.toList()
                val m2 = ids.joinToString(",") { "?" }
                val st2 = data.prepare(
                    "SELECT trail_id, surface_type FROM trail_properties " +
                        "WHERE trail_id IN (" + m2 + ") AND surface_type IS NOT NULL " +
                        "AND TRIM(surface_type) <> ''")
                try {
                    ids.forEachIndexed { i, v -> st2.bindText(i + 1, v) }
                    while (st2.step()) {
                        val n = idToName[com.grouptrack.shared.spatial.GtRead.cellText(st2, 0)] ?: continue
                        if (!out.containsKey(n)) {
                            out[n] = com.grouptrack.shared.spatial.GtRead.cellText(st2, 1) ?: continue
                        }
                    }
                } finally { st2.close() }
            }
        } catch (e: Exception) { log.w { "surface lookup: " + e.message } }
        return out
    }

    // ---- the text helpers (RouteModule.kt:542-554, :570-586, :589-600, :700-709) ----

    private fun num(o: JsonObject, k: String, d: Double = 0.0): Double =
        (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: d
    private fun str(o: JsonObject, k: String, d: String = ""): String =
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: d
    private fun bool(o: JsonObject, k: String, d: Boolean): Boolean =
        (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: d

    /** RouteModule.currentRecipe (:542-554). */
    fun currentRecipe(
        aLat: Double, aLon: Double, aName: String, mLo: Int, mHi: Int, sLo: Int, sHi: Int,
        pins: List<Pair<Double, Double>>, finish: Pair<Double, Double>?,
    ): JsonObject {
        val f = TrailFilterState
        return buildJsonObject {
            put("anchorLat", aLat); put("anchorLon", aLon); put("anchorName", aName)
            put("milesLow", mLo.toDouble()); put("milesHigh", mHi.toDouble())
            put("mphLow", sLo.toDouble()); put("mphHigh", sHi.toDouble())
            put("pins", buildJsonArray {
                for (p in pins) add(buildJsonObject { put("lat", p.first); put("lon", p.second) })
            })
            if (finish == null) put("finish", JsonNull)
            else put("finish", buildJsonObject { put("lat", finish.first); put("lon", finish.second) })
            put("trailFilter", f.whereOrEmpty())
            put("filterPanel", buildJsonObject {
                put("land", f.land); put("restricted", f.restricted); put("use", f.use)
                put("off", buildJsonArray { for (s in f.off.toList().sorted()) add(s) })
            })
        }
    }

    /** RouteModule.selectionText (:589-600). */
    fun selectionText(fp: JsonObject): String {
        val land = str(fp, "land", "PUBLIC")
        val shown = listOfNotNull(
            if (land != "PRIVATE") "Public" else null,
            if (land != "PUBLIC") "Private" else null,
            if (bool(fp, "restricted", true)) "Restricted" else null)
        val offA = fp["off"] as? JsonArray
        val hidden = (offA?.map { (it as? JsonPrimitive)?.content.orEmpty() } ?: emptyList())
            .map { if (it == TrailFilterState.ROW_UNOFFICIAL) "unofficial / uncertain" else it }
        val use = str(fp, "use", "ALL")
        return "land shown " + shown.joinToString(", ").ifEmpty { "none" } +
            "; trail types hidden " + hidden.joinToString(", ").ifEmpty { "none" } +
            (if (use != "ALL") "; " + use.lowercase() + " only" else "")
    }

    /** RouteModule.recipeText (:570-586). [nameAt] is the waypoint-name lookup the host owns
     *  (Android SpatialDbManager.waypointNameAt, iPad GtRead.waypointNameAt) -- a PARAMETER so
     *  this file needs no database handle at all. */
    fun recipeText(r: JsonObject, nameAt: (Double, Double) -> String?): String {
        val la = num(r, "anchorLat"); val lo = num(r, "anchorLon")
        fun ll(a: Double, b: Double) = GtFmt.f(a, 5) + ", " + GtFmt.f(b, 5)
        val nm = str(r, "anchorName").takeIf { it.isNotEmpty() && it != "null" }
            ?: (nameAt(la, lo) ?: "not recorded")
        val fin = r["finish"] as? JsonObject
        val ps = r["pins"] as? JsonArray
        val pinTxt = if (ps == null || ps.isEmpty()) "none" else ps.joinToString("; ") { e ->
            (e as? JsonObject)?.let { ll(num(it, "lat"), num(it, "lon")) } ?: "?"
        }
        val fp = r["filterPanel"] as? JsonObject
        val sel = if (fp == null) "filter " + str(r, "trailFilter", "not recorded") else selectionText(fp)
        return "Start: " + nm + " (" + ll(la, lo) + ")\n" +
            "Distance: " + num(r, "milesLow").toInt() + "-" + num(r, "milesHigh").toInt() + " miles\n" +
            "Speed: " + num(r, "mphLow").toInt() + "-" + num(r, "mphHigh").toInt() + " mph\n" +
            "Finish: " + (if (fin == null) "back at the start (loop)"
                          else "at " + ll(num(fin, "lat"), num(fin, "lon"))) + "\n" +
            "Places to include: " + pinTxt + "\n" +
            "Trail selection: " + sel
    }

    /** RouteModule.statsText (:700-709), over the shared GtRoute.lastStats. */
    fun statsText(): String {
        val st = GtRoute.lastStats
        fun n(v: Int) = if (v < 0) "not reached" else GtFmt.d(v)
        return "Search area: " + st.areaMi.ifEmpty { "not reached" } + "\n" +
            "Trails read: " + n(st.trails) + "\n" +
            "Trail network from the start: " + n(st.junctions) + " junctions in " +
                n(st.networks) + " network(s)\n" +
            "Features in reach: " + n(st.features) +
            (if (st.omitted.isNotEmpty())
                "\nLeft out by FILTER (trails in the search area; a trail can count twice): " + st.omitted
             else "") +
            (if (st.stop.isNotEmpty()) "\nStopped: " + st.stop else "")
    }
}
