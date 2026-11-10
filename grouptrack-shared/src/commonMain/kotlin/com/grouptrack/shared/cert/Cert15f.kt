package com.grouptrack.shared.cert

import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.imports.GtTrailImport
import com.grouptrack.shared.json.GtOrgJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.ExperimentalSerializationApi
import okio.FileSystem
import okio.Path

/**
 * STEP15F-2026-11-10 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15f certification's ONE runner
 * (the agency trail import, TrailImporter 2.7e), used by every side so the cases, their order and the dumps cannot drift:
 *  - on the Droid, GtCert15f (app) runs it with OLD (TrailImporterCertOld: the GENERATED copy, on SpatialDbManager's handles pointed at
 *    the copies) and with [NewSide] (shared GtTrailImport on its own copies); the line streams are compared;
 *  - on the iPad simulator, GtCertTrail15fMacTest runs it with [NewSide] on copies of the same input and compares with the Droid's report.
 * NOTHING GOES TO THE WEB: every query is answered by [Replay] (the same object on every side, recording every URL asked).
 * THE INPUT: the Droid's saved Utah agency pages (FETCHFIRST files, 6 sources, ~56,000 trails, the Mac's
 * _data/droid1_2026-11-06/states/utah/agency, pushed to the Droid as GroupTrack/cert15f_input/agency) + 15c's live databases (live copy).
 * TWO DATABASES per side: FRESH (an empty GtSchema-made pair -- every trail an INSERT, the dedup across sources, aliases) and LIVE (a copy of
 * 15c's input -- the trails are mostly there: the already-imported / name-fill paths).
 * THE SCRIPT on FRESH (keys in brackets):
 *   FS  importFullSource(blm) through the replay (no box: where=1%3D1 pages; writes source_blm_gtlf_all.json)                     [FS|...]
 *   E1  importSavedByArea, each saved source (name order), the Utah box                                                             [E1|<src>|...]
 *   E2  the same again (everything already imported)                                                                                [E2|<src>|...]
 *   X   crafted pages (blm's fields): errors, a JSON null name, a float id, Point, MultiLineString in / out of the box, an unknown type,
 *       an alias and a drop of an E1 trail, then a page that is not JSON (the import stops there)                                    [X|...]
 *   XE  a crafted page whose server answer is {"error":...}                                                                          [XE|...]
 *   U   UpdateMode.ALL on 6 blm features with changed names / uses (then CARTO_ONLY again)                                          [U|...]
 *   O   the Overpass shape (the TEST-ONLY cert_osm source): tags -> motorized / uses, name else ref, a node, a 1-point way         [O|...]
 *   ON  an Overpass way with a point missing "lon" (org.json refuses NaN: the import throws)                                       [ON|...]
 *   BA  importByArea(usgs) through the replay                                                                                        [BA|...]
 *   F1  fetchSourceToFile(blm): the count, pages at 2000 and 1000 FAIL -> halved to 500 -> 2 pages, saved                          [F1|...]
 *   F2  fetchSourceToFile(nps): every page fails -> halved to 62 -> gives up (the .part stays, as 2.7e)                           [F2|...]
 *   F3  fetchSourceToFile(usgs): the count says 120, 99 come -> "not saved"                                                        [F3|...]
 *   F4  fetchSourceToFile(cert_osm): one Overpass page                                                                             [F4|...]
 *   T1  importTrailheads(utah_trailheads.json, ugrc, no box); T2 the same with a St George box                                    [T1|...]
 *   A   the pending area (write / read / clear / read), scanTrailAreas (sorted -- the listing order is the platform's, D9), and
 *       isSourceFullyImported for every catalog source                                                                               [A|...]
 *   per call: the result (List<ImportResult>.toString(), the same text on every side), every ImportProgress emitted, every replay URL;
 *   at the end: every file in trail_areas and the fetch folder (name, size, sha256 of its text with the instants MASKED).
 * THE SCRIPT on LIVE: L1 importSavedByArea(blm, nps), the Utah box.                                                                 [L1|...]
 * Run-made times: the area files' stamps come from [Stamps] (20261101_120000, _120001, ...) on every side; the instants inside files /
 * rows are MASKED (NEWTIME); run-made ids in rows as Cert15d. A call that throws gives "EXCEPTION" (classes differ by platform).
 * THE TABLES: Cert15d.dumpTables (trails, trail_properties, aliases, tracks, track_properties, waypoints) + t3 source_ingestions.
 */
@OptIn(ExperimentalSerializationApi::class)
object Cert15f {
    /** Utah, as geofabrik_states.json (bbox [w, s, e, n] = [-114.05, 37.0, -109.04, 42.0]) */
    const val S = 37.0; const val W = -114.05; const val N = 42.0; const val E = -109.04
    const val OSM_ID = "cert_osm"
    private const val OSM_URL = "https://overpass.cert.invalid/api/interpreter"

    interface Side {
        val name: String
        fun full(sourceId: String): String
        fun byArea(ids: List<String>, s: Double, w: Double, n: Double, e: Double): String
        fun saved(sourceId: String, files: List<Path>, s: Double, w: Double, n: Double, e: Double): String
        fun fetch(sourceId: String, s: Double, w: Double, n: Double, e: Double, dest: Path, onPage: (Int, Int, Int) -> Unit): Boolean
        fun trailheads(asset: String, sourceId: String, s: Double?, w: Double?, n: Double?, e: Double?): Int
        fun setAll(all: Boolean)
        fun writePending(n: Double, s: Double, e: Double, w: Double)
        fun readPending(): String?          // compact text (org.json's toString())
        fun clearPending()
        fun scanAreas(): List<String>       // compact texts
        fun fully(sourceId: String): Boolean
        fun progress(): List<String>
        fun resetProgress()
    }

    /** NEW: the shared code. [progressLog] is what the GtTrailImport's progress callback appends to. */
    class NewSide(private val t: GtTrailImport, private val progressLog: MutableList<String>) : Side {
        override val name = "NEW"
        override fun full(sourceId: String) = listOf(t.importFullSource(sourceId)).toString()
        override fun byArea(ids: List<String>, s: Double, w: Double, n: Double, e: Double) = t.importByArea(ids, s, w, n, e).toString()
        override fun saved(sourceId: String, files: List<Path>, s: Double, w: Double, n: Double, e: Double) = t.importSavedByArea(sourceId, files, s, w, n, e).toString()
        override fun fetch(sourceId: String, s: Double, w: Double, n: Double, e: Double, dest: Path, onPage: (Int, Int, Int) -> Unit) =
            t.fetchSourceToFile(sourceId, s, w, n, e, dest, onPage)
        override fun trailheads(asset: String, sourceId: String, s: Double?, w: Double?, n: Double?, e: Double?) = t.importTrailheads(asset, sourceId, s, w, n, e)
        override fun setAll(all: Boolean) { t.updateMode = if (all) GtTrailImport.UpdateMode.ALL else GtTrailImport.UpdateMode.CARTO_ONLY }
        override fun writePending(n: Double, s: Double, e: Double, w: Double) = t.writePendingArea(n, s, e, w)
        override fun readPending() = t.readPendingArea()?.let { GtOrgJson.compact(it) }
        override fun clearPending() = t.clearPendingArea()
        override fun scanAreas() = t.scanTrailAreas().map { GtOrgJson.compact(it) }
        override fun fully(sourceId: String) = t.isSourceFullyImported(sourceId)
        override fun progress(): List<String> = progressLog.toList()
        override fun resetProgress() { progressLog.clear() }
    }

    /** The area-file clock: 20261101_120000, 20261101_120001, ... (one per call; the same count on every side) */
    class Stamps { private var k = 0; fun next(): Int = k++ ; fun text(i: Int): String = "20261101_12" + (i / 60).toString().padStart(2, '0') + (i % 60).toString().padStart(2, '0') }

    private val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z""")
    fun mask(s: String) = INSTANT.replace(s, "NEWTIME")

    // =================================================================================================================================
    // the catalog the cert uses: the shipped one + the TEST-ONLY Overpass source
    // =================================================================================================================================
    fun catalog(shipped: String): String {
        val root = GtOrgJson.parse(shipped) as JsonObject
        val sources = (root["sources"] as JsonArray).toMutableList()
        sources.add(GtOrgJson.parse("""{"id":"$OSM_ID","name":"Cert OSM (TEST-ONLY)","query_url":"$OSM_URL","max_records":2000,"source_type":"overpass",""" +
            """"fields":{"id":"osm_id","name":"name","type":"CartoCode","use":"DesignatedUses","motorized":"MotorizedAllowed","surface":"SurfaceType","manager":"OwnerSteward"}}"""))
        return GtOrgJson.compact(JsonObject(LinkedHashMap(root).also { it["sources"] = JsonArray(sources) }))
    }

    // =================================================================================================================================
    // THE REPLAY SERVER -- every side asks it, in the same order, and it answers the same text
    // =================================================================================================================================
    class Replay(private val feeds: Map<String, List<JsonElement>>, private val queryUrls: Map<String, String>) {
        val calls = ArrayList<String>()
        /** per phase: the page sizes (resultRecordCount) that answer {"error"}; [count] overrides what returnCountOnly says (-1 = the feed's) */
        var failSizes: Set<Int> = emptySet(); var count: Int = -1; var overpassPage: String = """{"elements":[]}"""
        fun get(url: String, readTimeoutMs: Int): String? {
            calls.add("$readTimeoutMs|$url")
            if (url.startsWith(OSM_URL)) return overpassPage
            val src = queryUrls.entries.firstOrNull { url.startsWith(it.value + "?") }?.key ?: return null
            val feed = feeds[src] ?: emptyList()
            if (url.contains("returnCountOnly=true")) return """{"count":${if (count >= 0) count else feed.size}}"""
            val off = Regex("resultOffset=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
            val n = Regex("resultRecordCount=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 2000
            if (n in failSizes) return """{"error":{"code":500,"message":"cert: page refused"}}"""
            val page = if (off >= feed.size) emptyList() else feed.subList(off, minOf(feed.size, off + n))
            return """{"type":"FeatureCollection","features":""" + JsonArray(page).toString() + "}"
        }
    }

    /** the features of a saved source file (every page, in order) */
    fun featuresOf(fs: FileSystem, file: Path): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        fs.read(file) { while (true) { val l = readUtf8Line() ?: break; ((GtOrgJson.parse(l) as? JsonObject)?.get("features") as? JsonArray)?.let { out.addAll(it) } } }
        return out
    }

    // ---- crafted pages ----
    private fun num(d: Double): JsonElement = JsonUnquotedLiteral(com.grouptrack.shared.text.GtJava.jd(d))
    private fun pt(lon: Double, lat: Double) = JsonArray(listOf(num(lon), num(lat)))
    private fun line(vararg p: Pair<Double, Double>) = JsonArray(p.map { pt(it.first, it.second) })
    private fun feat(props: JsonObject?, geom: JsonObject?): JsonElement {
        val m = LinkedHashMap<String, JsonElement>(); m["type"] = JsonPrimitive("Feature")
        if (geom != null) m["geometry"] = geom; if (props != null) m["properties"] = props
        return JsonObject(m)
    }
    private fun geom(type: String, coords: JsonElement) = JsonObject(linkedMapOf("type" to JsonPrimitive(type), "coordinates" to coords))
    private fun blmProps(id: JsonElement?, name: JsonElement?, cls: String = "Transportation System - Trail", mode: String = "Motorized"): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (id != null) m["OBJECTID"] = id
        if (name != null) m["ROUTE_PRMRY_NM"] = name
        m["PLAN_ASSET_CLASS"] = JsonPrimitive(cls); m["PLAN_MODE_TRNSPRT"] = JsonPrimitive(mode); m["PLAN_OHV_ROUTE_DSGNTN"] = JsonPrimitive("Open")
        return JsonObject(m)
    }
    private fun page(features: List<JsonElement>) = """{"type":"FeatureCollection","features":""" + JsonArray(features).toString() + "}"

    private fun craftedX(blm: List<JsonElement>): String {
        val first = blm[0] as JsonObject; val second = blm[1] as JsonObject
        val g1 = first["geometry"] as JsonObject; val g2 = second["geometry"] as JsonObject
        val p2 = second["properties"] as JsonObject
        val f = ArrayList<JsonElement>()
        f.add(feat(null, geom("LineString", line(-112.0 to 38.0, -112.001 to 38.001))))                                   // no properties
        f.add(feat(blmProps(JsonUnquotedLiteral("990001"), JsonPrimitive("No geometry")), null))                         // no geometry
        f.add(feat(blmProps(null, JsonPrimitive("No id")), geom("LineString", line(-112.0 to 38.0, -112.002 to 38.002)))) // no OBJECTID
        f.add(feat(blmProps(JsonUnquotedLiteral("9.9E7"), JsonNull),                                                    // float id, JSON null name (STEP15F-FIXA-2026-11-10)
            geom("MultiLineString", JsonArray(listOf(line(-112.1 to 38.1, -112.11 to 38.11), line(-112.12 to 38.12, -112.13 to 38.13))))))
        f.add(feat(blmProps(JsonUnquotedLiteral("990002"), JsonPrimitive("Not Named")), geom("Point", pt(-112.2, 38.2))))      // Point
        f.add(feat(blmProps(JsonUnquotedLiteral("990003"), JsonPrimitive("Outside multi")),                              // out of the box -> rejected
            geom("MultiLineString", JsonArray(listOf(line(-120.0 to 45.0, -120.01 to 45.01))))))
        f.add(feat(blmProps(JsonUnquotedLiteral("990004"), JsonPrimitive("Outside line")),                               // LineString: no box check
            geom("LineString", line(-120.0 to 45.0, -120.02 to 45.02))))
        f.add(feat(blmProps(JsonUnquotedLiteral("990005"), JsonPrimitive("Polygon")),                                    // unknown type -> ""
            geom("Polygon", JsonArray(listOf(line(-112.3 to 38.3, -112.31 to 38.3, -112.3 to 38.31, -112.3 to 38.3))))))
        f.add(feat(blmProps(JsonUnquotedLiteral("990006"), JsonPrimitive("Cert alias of blm 1")), g1))                   // E1's geometry, new name
        f.add(feat(blmProps(JsonUnquotedLiteral("990007"), p2["ROUTE_PRMRY_NM"]), g2))                                  // E1's geometry, same name
        f.add(feat(blmProps(JsonUnquotedLiteral("990008"), JsonPrimitive("Integer coords")), geom("LineString", JsonArray(listOf(
            JsonArray(listOf(JsonUnquotedLiteral("-112"), JsonUnquotedLiteral("38"))), JsonArray(listOf(JsonUnquotedLiteral("-112.5"), JsonUnquotedLiteral("38.0000000000000001"))))))))
        return page(f) + "\n" + "not json at all" + "\n" + page(listOf(feat(blmProps(JsonUnquotedLiteral("990009"), JsonPrimitive("Never reached")),
            geom("LineString", line(-112.4 to 38.4, -112.41 to 38.41)))))
    }

    private fun craftedU(blm: List<JsonElement>): String {
        val f = ArrayList<JsonElement>()
        for (i in 0 until 6) {
            val o = blm[i] as JsonObject; val p = LinkedHashMap(o["properties"] as JsonObject)
            if (i < 3) p["ROUTE_PRMRY_NM"] = JsonPrimitive("Cert renamed $i")
            if (i in 2..4) p["PLAN_MODE_TRNSPRT"] = JsonPrimitive("Cert uses $i")
            f.add(JsonObject(LinkedHashMap(o).also { it["properties"] = JsonObject(p) }))
        }
        return page(f)
    }

    private fun overpass(withNaN: Boolean): String {
        fun g(vararg p: Pair<Double, Double>) = JsonArray(p.map { JsonObject(linkedMapOf("lat" to num(it.second), "lon" to num(it.first))) })
        fun way(id: Long, tags: Map<String, String>?, geom: JsonArray): JsonElement {
            val m = LinkedHashMap<String, JsonElement>(); m["type"] = JsonPrimitive("way"); m["id"] = JsonUnquotedLiteral(id.toString())
            if (tags != null) m["tags"] = JsonObject(tags.mapValues { JsonPrimitive(it.value) }); m["geometry"] = geom
            return JsonObject(m)
        }
        val els = ArrayList<JsonElement>()
        els.add(way(1001, mapOf("highway" to "track", "name" to "Cert OSM track", "motor_vehicle" to "yes", "surface" to "gravel", "operator" to "BLM"),
            g(-111.5 to 39.5, -111.51 to 39.51, -111.52 to 39.515)))
        els.add(way(1002, mapOf("highway" to "path", "ref" to "28G", "foot" to "designated", "atv" to "no"), g(-111.6 to 39.6, -111.61 to 39.61)))
        els.add(way(1003, mapOf("highway" to "track", "horse" to "yes", "bicycle" to "yes", "foot" to "yes", "motorcycle" to "permissive"), g(-111.7 to 39.7, -111.71 to 39.7)))
        els.add(way(1004, null, g(-111.8 to 39.8, -111.81 to 39.81)))
        els.add(JsonObject(linkedMapOf("type" to JsonPrimitive("node"), "id" to JsonUnquotedLiteral("2001"), "lat" to num(39.9), "lon" to num(-111.9))))
        els.add(way(1005, mapOf("highway" to "track", "name" to "One point"), g(-111.95 to 39.95)))
        if (withNaN) els.add(way(1006, mapOf("highway" to "track"), JsonArray(listOf(JsonObject(linkedMapOf("lat" to num(39.0))),
            JsonObject(linkedMapOf("lat" to num(39.01), "lon" to num(-111.01)))))))
        return """{"elements":""" + JsonArray(els).toString() + "}"
    }

    // =================================================================================================================================
    // the scripts
    // =================================================================================================================================
    class Ctx(val fs: FileSystem, val agencyDir: Path, val workDir: Path, val areasDir: Path)

    private fun call(key: String, side: Side, replay: Replay, emit: (String, String) -> Unit, f: () -> String) {
        side.resetProgress(); replay.calls.clear()
        val r = try { f() } catch (e: Throwable) { "EXCEPTION" }
        emit("$key|result", r)
        side.progress().forEachIndexed { i, p -> emit("$key|p|" + (i + 1).toString().padStart(5, '0'), p) }
        replay.calls.forEachIndexed { i, c -> emit("$key|http|" + (i + 1).toString().padStart(4, '0'), c) }
    }

    /** the saved sources, in name order (file name without ".jsonl" = the source id) */
    fun savedFiles(fs: FileSystem, dir: Path): List<Path> = fs.list(dir).filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }

    /** [replay] is the side's own (built by [replayFor]); [sourceIds] = the catalog's queryable ids (for A's isSourceFullyImported) */
    fun runFresh(side: Side, ctx: Ctx, replay: Replay, sourceIds: List<String>, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        val fs = ctx.fs
        val files = savedFiles(fs, ctx.agencyDir)
        emit("input|agency", "n=${files.size}")
        for (p in files) { val b = fs.read(p) { readByteString() }; emit("input|agency|" + p.name, "size=${b.size};sha256=" + b.sha256().hex()) }
        val blm = featuresOf(fs, ctx.agencyDir / "blm_gtlf_all.jsonl")
        fs.createDirectories(ctx.workDir)

        progress("${side.name} FS"); call("FS", side, replay, emit) { side.full("blm_gtlf_all") }
        for (pass in listOf("E1", "E2")) for (p in files) {
            val sid = p.name.removeSuffix(".jsonl")
            progress("${side.name} $pass $sid"); call("$pass|$sid", side, replay, emit) { side.saved(sid, listOf(p), S, W, N, E) }
        }
        val x = ctx.workDir / "crafted_x.jsonl"; fs.write(x) { writeUtf8(craftedX(blm)) }
        progress("${side.name} X"); call("X", side, replay, emit) { side.saved("blm_gtlf_all", listOf(x), S, W, N, E) }
        val xe = ctx.workDir / "crafted_xe.jsonl"; fs.write(xe) { writeUtf8("""{"error":{"code":400,"message":"cert: bad query"}}""" + "\n") }
        call("XE", side, replay, emit) { side.saved("blm_gtlf_all", listOf(xe), S, W, N, E) }
        val u = ctx.workDir / "crafted_u.jsonl"; fs.write(u) { writeUtf8(craftedU(blm)) }
        side.setAll(true); progress("${side.name} U")
        try { call("U", side, replay, emit) { side.saved("blm_gtlf_all", listOf(u), S, W, N, E) } } finally { side.setAll(false) }
        call("U2", side, replay, emit) { side.saved("blm_gtlf_all", listOf(u), S, W, N, E) }
        val o = ctx.workDir / "crafted_o.jsonl"; fs.write(o) { writeUtf8(overpass(false)) }
        progress("${side.name} O"); call("O", side, replay, emit) { side.saved(OSM_ID, listOf(o), S, W, N, E) }
        val on = ctx.workDir / "crafted_on.jsonl"; fs.write(on) { writeUtf8(overpass(true)) }
        call("ON", side, replay, emit) { side.saved(OSM_ID, listOf(on), S, W, N, E) }
        progress("${side.name} BA"); call("BA", side, replay, emit) { side.byArea(listOf("usgs_national_trails", "no_such_source"), S, W, N, E) }
        // ---- the fetches ----
        val fd = ctx.workDir / "fetch"; fs.createDirectories(fd)
        fun fetch(key: String, sid: String, fail: Set<Int>, count: Int, op: String = """{"elements":[]}""") {
            replay.failSizes = fail; replay.count = count; replay.overpassPage = op
            val pages = ArrayList<String>()
            call(key, side, replay, emit) { side.fetch(sid, S, W, N, E, fd / "$sid.jsonl") { a, b, c -> pages.add("$a,$b,$c") }.toString() }
            emit("$key|pages", pages.joinToString(";"))
            replay.failSizes = emptySet(); replay.count = -1; replay.overpassPage = """{"elements":[]}"""
        }
        progress("${side.name} F")
        fetch("F1", "blm_gtlf_all", setOf(2000, 1000), -1)
        fetch("F2", "nps_public_trails", (1..2000).toSet(), -1)
        fetch("F3", "usgs_national_trails", emptySet(), 120)
        fetch("F4", OSM_ID, emptySet(), -1, overpass(false))
        // ---- trailheads ----
        progress("${side.name} T")
        call("T1", side, replay, emit) { side.trailheads("utah_trailheads.json", "ugrc", null, null, null, null).toString() }
        call("T2", side, replay, emit) { side.trailheads("utah_trailheads.json", "ugrc", 37.0, -113.7, 37.2, -113.4).toString() }
        // ---- the pending area, the records ----
        call("A|write", side, replay, emit) { side.writePending(38.5, 38.0, -111.5, -112.0); "ok" }
        call("A|read1", side, replay, emit) { mask(side.readPending() ?: "null") }
        call("A|clear", side, replay, emit) { side.clearPending(); "ok" }
        call("A|read2", side, replay, emit) { side.readPending() ?: "null" }
        call("A|scan", side, replay, emit) { side.scanAreas().map { mask(it) }.sorted().joinToString("\n") }
        for (sid in sourceIds + OSM_ID + "no_such_source") call("A|fully|$sid", side, replay, emit) { side.fully(sid).toString() }
        snapshot(fs, "files|areas", ctx.areasDir, emit)
        snapshot(fs, "files|fetch", fd, emit)
    }

    fun runLive(side: Side, ctx: Ctx, replay: Replay, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        for (sid in listOf("blm_gtlf_all", "nps_public_trails")) {
            progress("${side.name} L1 $sid")
            call("L1|$sid", side, replay, emit) { side.saved(sid, listOf(ctx.agencyDir / "$sid.jsonl"), S, W, N, E) }
        }
        snapshot(ctx.fs, "files|areasL", ctx.areasDir, emit)
    }

    /** the replay server for a side (built from the saved pages: blm, nps, usgs feeds; the catalog's query URLs) */
    fun replayFor(fs: FileSystem, agencyDir: Path, catalogText: String): Replay {
        val urls = LinkedHashMap<String, String>()
        for (s in ((GtOrgJson.parse(catalogText) as JsonObject)["sources"] as JsonArray)) {
            val o = s as JsonObject; urls[GtOrgJson.optString(o, "id")] = GtOrgJson.optString(o, "query_url")
        }
        val feeds = HashMap<String, List<JsonElement>>()
        for (sid in listOf("blm_gtlf_all", "nps_public_trails", "usgs_national_trails")) feeds[sid] = featuresOf(fs, agencyDir / "$sid.jsonl")
        return Replay(feeds, urls)
    }

    /** every file in [dir] by name: size and the sha256 of its text with the instants masked */
    private fun snapshot(fs: FileSystem, label: String, dir: Path, emit: (String, String) -> Unit) {
        if (!fs.exists(dir)) { emit(label, "MISSING"); return }
        val names = fs.list(dir).map { it.name }.sorted()
        emit(label, "n=${names.size}")
        for (n in names) {
            val t = fs.read(dir / n) { readUtf8() }
            val m = mask(t)   // STEP15F-FIXB-2026-11-10: the size of the masked text
            emit("$label|$n", "size=${m.length};sha=" + Cert15d.sha(m) + (if (m.length <= 600) ";text=" + m else ""))
        }
    }

    // =================================================================================================================================
    // the tables: Cert15d's six + t3 source_ingestions (insertion order; id and time masked)
    // =================================================================================================================================
    fun dumpTables(conn: SQLiteConnection, dataConn: SQLiteConnection, inputs: Cert15d.Inputs, emit: (String, String, Boolean) -> Unit, progress: (String) -> Unit) {
        Cert15d.dumpTables(conn, dataConn, inputs, emit, progress)
        val st = dataConn.prepare("SELECT ingestion_id, source_id, ingested_at, trail_count, dupes_skipped, bounds_json FROM source_ingestions ORDER BY rowid")
        try {
            var k = 0; val all = StringBuilder()
            while (st.step()) {
                k++
                val cells = (0 until 6).joinToString("|") { i ->
                    if (st.getColumnType(i) == SQLITE_DATA_NULL) "N" else when (i) {
                        0 -> "ID"; 2 -> mask(st.getText(i)); else -> "S" + Cert15d.esc(st.getText(i)) }
                }
                all.append(cells).append('\n')
                emit("t3|" + k.toString().padStart(6, '0'), cells, true)
            }
            emit("t3|~count", k.toString(), true); emit("t3|~fp", Cert15d.sha(all.toString()), true)
        } finally { st.close() }
    }
}
