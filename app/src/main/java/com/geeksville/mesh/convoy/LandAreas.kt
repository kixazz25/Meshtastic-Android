package com.geeksville.mesh.convoy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * PADUSLAND-2026-11-02 -- public, restricted and non-federal LAND AREAS from the state's saved PAD-US zip (USGS PAD-US 4.1).
 *
 * Fred 11-02: "public areas will display with a light green color and restricted areas a faint red stripe" / "we need the polyline
 * stored anyway ... public land accessible and public land restricted from motorized access. same database drives display actions and
 * route scoring limitations" / "if a trail is motorized and exists in a now restricted area access has now been taken away".
 *
 * ONE TABLE, land_areas (spatial database), drives step 8's public / private / restricted AND (later) the map's green / red areas, the
 * area panel and route scoring. Built on the tablet from the zip the import saved in states/<state>/ -- once per zip: land_sources
 * records which zip (and its date) each state's rows came from; a new zip rebuilds that state.
 *
 * FROM THE ZIP (two KMZ files inside it; each Placemark carries its fields in an HTML table in <description>):
 *   Fee         -- who OWNS the land.  Own_Type FED / STAT / LOC / DIST -> kind PUBLIC; anything else (PVT, NGO, TRIB, JNT, UNK...)
 *                  -> kind NONFED, kept only so a restricted area can exclude the non-federal land inside it.
 *   Designation -- Des_Tp WA (wilderness), WSA (wilderness study area), SW (state wilderness) -> kind RESTRICTED: closed to motor
 *                  vehicles, open on foot / bike / horse. Other designations are not stored (yet).
 * Outlines are simplified to ~30 m (Douglas-Peucker, [TOL] degrees) -- the Python model (11-02): Utah ~4 MB.
 * RINGS: polygons joined by '|', a polygon's rings by ';' (first = outer, then holes), a ring "x y,x y,..." (lon lat, 5 decimals).
 *
 * COVERAGE: land_sources also holds each state's box (the union of its geofabrik_states.json rows). Step 8 reads "no public area
 * under this trail" as PRIVATE ONLY inside a covered state -- a state with no PAD-US data stays all PUBLIC (Fred's default).
 */
object LandAreas {
    private const val TAG = "LandAreas"
    private const val TOL = 0.0003            // ~30 m
    private const val CELL = 0.05             // grid cell, degrees (~3.5 mi) -- as step 8's old index
    val PUBLIC_OWN = setOf("FED", "STAT", "LOC", "DIST")
    val RESTRICTED_DES = setOf("WA", "WSA", "SW")
    private val ATTR = Regex("<td>([A-Za-z_]+)</td>\\s*<td>([^<]*)</td>")

    // bits returned by LandIndex.kindsAt
    const val B_PUBLIC = 1
    const val B_NONFEDERAL = 2   // public non-FED (state, local, district) or NONFED -- excluded from a restricted area
    const val B_RESTRICTED = 4

    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS land_areas (area_id INTEGER PRIMARY KEY, state_code TEXT NOT NULL, kind TEXT NOT NULL, " +
            "own_type TEXT, own_name TEXT, mang_name TEXT, des_type TEXT, des_name TEXT, unit_name TEXT, pub_access TEXT, gap_sts TEXT, " +
            "acres REAL, min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL, rings TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_land_state_bbox ON land_areas(state_code, min_lat, max_lat, min_lon, max_lon)")
        db.execSQL("CREATE TABLE IF NOT EXISTS land_meta (k TEXT PRIMARY KEY, v TEXT)")   // RSTSEG-2026-11-02: the land-rules version
        db.execSQL("CREATE TABLE IF NOT EXISTS land_sources (state_code TEXT PRIMARY KEY, zip_path TEXT, zip_mtime INTEGER, built_at TEXT, " +
            "public_n INTEGER, restricted_n INTEGER, nonfed_n INTEGER, s REAL, w REAL, n REAL, e REAL)")
    }

    /**
     * Build land_areas for every state of the run whose PAD-US zip is saved and not yet built from (or built from an older zip).
     * Returns true when any state was (re)built -- the caller then classifies EVERY trail, not only the new ones.
     */
    fun ensure(ctx: Context, db: SQLiteDatabase, states: List<GeofabrikState>, onStep: (String) -> Unit): Boolean {
        createTables(db)
        var rebuilt = false
        // INHOLDING-2026-11-02: a changed land RULE also rebuilds every state's areas from its saved zip (the rules decide what is stored)
        createTables(db)
        val rulesNow = db.rawQuery("SELECT v FROM land_meta WHERE k='rules'", null).use { if (it.moveToFirst()) it.getString(0) else "" }
        val rulesChanged = rulesNow != RULES.toString()
        for ((code, rows) in states.filter { it.landCode.isNotEmpty() }.groupBy { it.landCode }) {
            val gs = rows.first()
            val folder = gs.parentState?.lowercase()?.replace(' ', '-') ?: gs.slug
            val zip = StateData.landFile(folder, code)
            if (!zip.exists() || zip.length() < 1_000_000L) { Log.w(TAG, "PADUSLAND $code: no PAD-US zip at ${zip.path} -- this state stays all PUBLIC"); continue }
            val have = db.rawQuery("SELECT zip_mtime FROM land_sources WHERE state_code=?", arrayOf(code)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (have == zip.lastModified() && !rulesChanged) { Log.i(TAG, "PADUSLAND $code: land areas already built from this zip"); continue }   // INHOLDING-2026-11-02
            // the state's box: every geofabrik row of this code (California's two halves)
            val all = GeofabrikCatalog.load(ctx).filter { it.landCode == code }
            val box = doubleArrayOf(all.minOf { it.bboxSouth }, all.minOf { it.bboxWest }, all.maxOf { it.bboxNorth }, all.maxOf { it.bboxEast })
            build(db, code, zip, box, onStep)
            rebuilt = true
        }
        // RSTSEG-2026-11-02: a changed land RULE needs every trail classified again, exactly as new land data does
        val have = db.rawQuery("SELECT v FROM land_meta WHERE k='rules'", null).use { if (it.moveToFirst()) it.getString(0) else "" }
        if (have != RULES.toString()) {
            db.execSQL("INSERT OR REPLACE INTO land_meta (k, v) VALUES ('rules', ?)", arrayOf<Any?>(RULES.toString()))
            Log.i(TAG, "RSTSEG: land rules $have -> $RULES -- every trail is classified again")
            rebuilt = true
        }
        return rebuilt
    }

    /** RSTSEG-2026-11-02: the land-rules version. Bump when step 8's land rules change. 2 = restricted by segment, 15 m margin. */
    const val RULES = 4   // INHOLDING-2026-11-02: 3 = inholdings not restricted; RSTOPEN-2026-11-02: 4 = open_geom stored
    /** RSTSEG-2026-11-02: how far inside a restricted area a point must be (metres) -- the boundary / cherry-stem margin. */
    const val DEEP_M = 15.0

    /** Parse the zip's Fee + Designation KMZs into land_areas for [code] (its old rows replaced, in one transaction). */
    fun build(db: SQLiteDatabase, code: String, zip: File, box: DoubleArray, onStep: (String) -> Unit) {
        val t0 = System.currentTimeMillis()
        var pub = 0; var nonfed = 0; var restr = 0; var skipped = 0
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM land_areas WHERE state_code=?", arrayOf(code))
            val ins = db.compileStatement("INSERT INTO land_areas (state_code,kind,own_type,own_name,mang_name,des_type,des_name,unit_name," +
                "pub_access,gap_sts,acres,min_lat,max_lat,min_lon,max_lon,rings) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            ZipFile(zip).use { zf ->
                for ((layer, label) in listOf("Fee_State" to "ownership", "Designation_State" to "wilderness areas")) {
                    val e = zf.entries().asSequence().firstOrNull { val n = it.name.substringAfterLast('/'); n.contains(layer) && n.endsWith(".kmz") }
                    if (e == null) { Log.e(TAG, "PADUSLAND $code: no $layer KMZ in ${zip.name}"); throw IllegalStateException("no $layer KMZ in the PAD-US zip") }
                    var seen = 0
                    onStep("Public lands · reading $label")
                    ZipInputStream(zf.getInputStream(e)).use { zin ->
                        var k = zin.nextEntry
                        while (k != null && !k.name.endsWith(".kml")) k = zin.nextEntry
                        if (k == null) throw IllegalStateException("no KML inside ${e.name}")
                        parseKml(zin) { a, polys ->
                            seen++
                            if (seen % 500 == 0) onStep("Public lands · $label · $seen")
                            val kind = if (layer == "Fee_State") { if ((a["Own_Type"] ?: "") in PUBLIC_OWN) "PUBLIC" else "NONFED" }
                                       else if ((a["Des_Tp"] ?: "") in RESTRICTED_DES &&
                                                !(a["Unit_Nm"] ?: "").contains("Inholding", ignoreCase = true)) "RESTRICTED" else ""   // INHOLDING-2026-11-02: inholdings are NOT the WSA
                            if (kind.isEmpty()) { skipped++; return@parseKml }
                            val enc = encode(polys) ?: run { skipped++; return@parseKml }
                            ins.clearBindings()
                            ins.bindString(1, code); ins.bindString(2, kind)
                            ins.bindString(3, a["Own_Type"] ?: ""); ins.bindString(4, a["d_Own_Name"] ?: a["Own_Name"] ?: "")
                            ins.bindString(5, a["d_Mang_Nam"]?.ifEmpty { null } ?: a["Mang_Name"] ?: "")
                            ins.bindString(6, a["Des_Tp"] ?: ""); ins.bindString(7, a["d_Des_Tp"] ?: "")
                            ins.bindString(8, a["Unit_Nm"] ?: ""); ins.bindString(9, a["Pub_Access"] ?: ""); ins.bindString(10, a["GAP_Sts"] ?: "")
                            ins.bindDouble(11, (a["GIS_Acres"] ?: "").toDoubleOrNull() ?: 0.0)
                            ins.bindDouble(12, enc.second[1]); ins.bindDouble(13, enc.second[3]); ins.bindDouble(14, enc.second[0]); ins.bindDouble(15, enc.second[2])
                            ins.bindString(16, enc.first)
                            ins.executeInsert()
                            when (kind) { "PUBLIC" -> pub++; "NONFED" -> nonfed++; else -> restr++ }
                        }
                    }
                }
            }
            db.execSQL("INSERT OR REPLACE INTO land_sources (state_code,zip_path,zip_mtime,built_at,public_n,restricted_n,nonfed_n,s,w,n,e) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(code, zip.path, zip.lastModified(), java.time.Instant.now().toString(),
                pub, restr, nonfed, box[0], box[1], box[2], box[3]))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "PADUSLAND $code: $pub public, $restr restricted, $nonfed non-federal areas ($skipped skipped) " +
            "in ${(System.currentTimeMillis() - t0) / 1000}s")
    }

    /** Stream a KML: for each Placemark, its <description> fields and its polygons (each a list of rings, flat x,y arrays). */
    private fun parseKml(input: InputStream, onPlacemark: (Map<String, String>, List<List<DoubleArray>>) -> Unit) {
        val p = android.util.Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")
        var inPm = false
        var attrs = HashMap<String, String>()
        var polys = ArrayList<List<DoubleArray>>()
        var cur = ArrayList<DoubleArray>()
        var inPoly = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "Placemark" -> { inPm = true; attrs = HashMap(); polys = ArrayList() }
                    "description" -> if (inPm) { for (m in ATTR.findAll(p.nextText())) attrs[m.groupValues[1]] = m.groupValues[2].trim() }
                    "Polygon" -> if (inPm) { inPoly = true; cur = ArrayList() }
                    "coordinates" -> if (inPm && inPoly) { val r = coords(p.nextText()); if (r.size >= 8) cur.add(r) }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "Polygon" -> { if (inPoly && cur.isNotEmpty()) polys.add(cur); inPoly = false }
                    "Placemark" -> { if (inPm) onPlacemark(attrs, polys); inPm = false }
                }
            }
            ev = p.next()
        }
    }

    /** "lon,lat,0 lon,lat,0 ..." -> [x0,y0,x1,y1,...] */
    private fun coords(t: String): DoubleArray {
        val out = ArrayList<Double>(256)
        for (tok in t.trim().split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            val c = tok.split(',')
            if (c.size < 2) continue
            val x = c[0].toDoubleOrNull() ?: continue
            val y = c[1].toDoubleOrNull() ?: continue
            out.add(x); out.add(y)
        }
        return out.toDoubleArray()
    }

    /** Simplify and encode; null when nothing survives. Second = [minX, minY, maxX, maxY]. */
    private fun encode(polys: List<List<DoubleArray>>): Pair<String, DoubleArray>? {
        val sb = StringBuilder()
        var minX = 999.0; var minY = 999.0; var maxX = -999.0; var maxY = -999.0
        var nPoly = 0
        for (poly in polys) {
            if (poly.isEmpty()) continue
            val outer = simplify(poly[0])
            if (outer.size < 8) continue                     // the outer ring must survive; holes that do not are dropped
            val rings = listOf(outer) + poly.drop(1).map { simplify(it) }.filter { it.size >= 8 }
            if (nPoly > 0) sb.append('|')
            rings.forEachIndexed { ri, r ->
                if (ri > 0) sb.append(';')
                var i = 0
                while (i < r.size) {
                    if (i > 0) sb.append(',')
                    val x = Math.round(r[i] * 1e5) / 1e5; val y = Math.round(r[i + 1] * 1e5) / 1e5
                    sb.append(x).append(' ').append(y)
                    if (ri == 0) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }
                    i += 2
                }
            }
            nPoly++
        }
        return if (nPoly == 0) null else Pair(sb.toString(), doubleArrayOf(minX, minY, maxX, maxY))
    }

    /** Douglas-Peucker on a flat [x,y,...] ring, tolerance [TOL] degrees. */
    private fun simplify(r: DoubleArray): DoubleArray {
        val n = r.size / 2
        if (n <= 4) return r
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>(); stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            if (b <= a + 1) continue
            val ax = r[2 * a]; val ay = r[2 * a + 1]; val bx = r[2 * b]; val by = r[2 * b + 1]
            val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
            var best = -1.0; var bi = -1
            for (i in a + 1 until b) {
                val px = r[2 * i]; val py = r[2 * i + 1]
                val d = if (len2 == 0.0) Math.hypot(px - ax, py - ay)
                        else Math.abs(dy * px - dx * py + bx * ay - by * ax) / Math.sqrt(len2)
                if (d > best) { best = d; bi = i }
            }
            if (best > TOL) { keep[bi] = true; stack.addLast(intArrayOf(a, bi)); stack.addLast(intArrayOf(bi, b)) }
        }
        val out = ArrayList<Double>(n)
        for (i in 0 until n) if (keep[i]) { out.add(r[2 * i]); out.add(r[2 * i + 1]) }
        return out.toDoubleArray()
    }

    /**
     * LANDDISPLAY-2026-11-02: the areas in view as GeoJSON for the map -- PUBLIC and / or RESTRICTED, largest first, up to [limit].
     * Each feature: kind, area_id, unit, owner, manager, designation, access, acres; geometry MultiPolygon from the stored rings.
     */
    fun viewportGeoJson(db: SQLiteDatabase, s: Double, w: Double, n: Double, e: Double, pub: Boolean, rst: Boolean, limit: Int): String {
        createTables(db)
        val kinds = ArrayList<String>()
        if (pub) kinds.add("'PUBLIC'")
        if (rst) kinds.add("'RESTRICTED'")
        if (kinds.isEmpty()) return "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val sb = StringBuilder(64 * 1024)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        db.rawQuery("SELECT area_id, kind, unit_name, own_name, mang_name, des_name, pub_access, acres, rings FROM land_areas " +
            "WHERE kind IN (" + kinds.joinToString(",") + ") AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? " +
            "ORDER BY acres DESC LIMIT ?", arrayOf(s.toString(), n.toString(), w.toString(), e.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) {
                fun q(i: Int) = SpatialDbManager.jsonEsc(c.getString(i) ?: "")
                if (!first) sb.append(',')
                first = false
                sb.append("{\"type\":\"Feature\",\"properties\":{\"area_id\":").append(c.getLong(0))
                    .append(",\"kind\":\"").append(q(1)).append("\",\"unit\":\"").append(q(2))
                    .append("\",\"owner\":\"").append(q(3)).append("\",\"manager\":\"").append(q(4))
                    .append("\",\"designation\":\"").append(q(5)).append("\",\"access\":\"").append(q(6))
                    .append("\",\"acres\":").append(Math.round(c.getDouble(7)))
                    .append("},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[")
                c.getString(8).split('|').forEachIndexed { pi, poly ->
                    if (pi > 0) sb.append(',')
                    sb.append('[')
                    poly.split(';').forEachIndexed { ri, ring ->
                        if (ri > 0) sb.append(',')
                        sb.append('[')
                        ring.split(',').forEachIndexed { k, pt ->
                            if (k > 0) sb.append(',')
                            val sp = pt.indexOf(' ')
                            sb.append('[').append(pt, 0, sp).append(',').append(pt, sp + 1, pt.length).append(']')
                        }
                        sb.append(']')
                    }
                    sb.append(']')
                }
                sb.append("]}}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    // ── the index step 8 reads ──────────────────────────────────────────

    class Poly(val bits: Int, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double, val rings: List<DoubleArray>)

    class LandIndex(private val polys: List<Poly>, private val grid: Map<Long, IntArray>, private val big: IntArray,
                    private val coverage: List<DoubleArray>) {
        val size: Int get() = polys.size
        val states: Int get() = coverage.size
        // RSTQUICK-2026-11-02: the polygons that can change the 15 m test -- restricted and non-federal. The big federal public ones
        //  (one BLM polygon is ~14,000 points) are left out, which is where the restricted pass spent its time.
        private val rGrid: Map<Long, IntArray>
        private val rBig: IntArray
        init {
            val g = HashMap<Long, ArrayList<Int>>(); val bigL = ArrayList<Int>()
            polys.forEachIndexed { i, p ->
                if ((p.bits and (B_RESTRICTED or B_NONFEDERAL)) == 0) return@forEachIndexed
                val x0 = Math.floor(p.minX / CELL).toInt(); val x1 = Math.floor(p.maxX / CELL).toInt()
                val y0 = Math.floor(p.minY / CELL).toInt(); val y1 = Math.floor(p.maxY / CELL).toInt()
                if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > 20000L) { bigL.add(i); return@forEachIndexed }
                for (gx in x0..x1) for (gy in y0..y1) g.getOrPut(key(gx, gy)) { ArrayList() }.add(i)
            }
            rGrid = g.mapValues { it.value.toIntArray() }; rBig = bigL.toIntArray()
        }
        /** RSTQUICK-2026-11-02: B_RESTRICTED / B_NONFEDERAL at this point, from the small index only. */
        private fun rKindsAt(x: Double, y: Double): Int {
            var bits = 0
            val cand = rGrid[key(Math.floor(x / CELL).toInt(), Math.floor(y / CELL).toInt())]
            if (cand != null) for (i in cand) bits = bits or hit(polys[i], x, y)
            for (i in rBig) bits = bits or hit(polys[i], x, y)
            return bits
        }
        /** True when this point is inside a state whose PAD-US data is loaded ([s,w,n,e] boxes). */
        fun covered(x: Double, y: Double): Boolean = coverage.any { y >= it[0] && y <= it[2] && x >= it[1] && x <= it[3] }
        /** B_PUBLIC | B_NONFEDERAL | B_RESTRICTED for the areas this point is in. */
        /** RSTSEG-2026-11-02: true when any restricted area's box meets this box (a cheap pre-test). */
        // RSTAREA-2026-11-02: the restricted polygons alone (~300 of ~10,000)
        private val rstPolys: List<Poly> = polys.filter { (it.bits and B_RESTRICTED) != 0 }
        /** RSTAREA-2026-11-02: each restricted polygon's box [minX, minY, maxX, maxY] -- step 8's restricted pass starts from these. */
        fun restrictedBoxes(): List<DoubleArray> = rstPolys.map { doubleArrayOf(it.minX, it.minY, it.maxX, it.maxY) }
        fun anyRestricted(minX: Double, minY: Double, maxX: Double, maxY: Double): Boolean =
            rstPolys.any { it.maxX >= minX && it.minX <= maxX && it.maxY >= minY && it.minY <= maxY }
        /**
         * RSTSEG-2026-11-02: this point is MORE THAN [DEEP_M] inside restricted federal land -- it and the 8 points [DEEP_M] around it
         * are all restricted and none non-federal. A road along the edge, or in a cherry-stem gap, fails.
         */
        fun deepRestricted(x: Double, y: Double): Boolean {
            val dy = DEEP_M / 110_540.0
            val dx = DEEP_M / (111_320.0 * Math.cos(Math.toRadians(y)))
            // RSTAREA-2026-11-02: the centre first -- most points fail here and the other 8 are never tested
            val c0 = rKindsAt(x, y)   // RSTQUICK-2026-11-02: the small index
            if ((c0 and B_RESTRICTED) == 0 || (c0 and B_NONFEDERAL) != 0) return false
            for (k in -1..1) for (j in -1..1) {
                if (k == 0 && j == 0) continue
                val b = rKindsAt(x + k * dx, y + j * dy)   // RSTQUICK-2026-11-02
                if ((b and B_RESTRICTED) == 0 || (b and B_NONFEDERAL) != 0) return false
            }
            return true
        }
        fun kindsAt(x: Double, y: Double): Int {
            var bits = 0
            val cand = grid[key(Math.floor(x / CELL).toInt(), Math.floor(y / CELL).toInt())]
            if (cand != null) for (i in cand) bits = bits or hit(polys[i], x, y)
            for (i in big) bits = bits or hit(polys[i], x, y)
            return bits
        }
        private fun hit(p: Poly, x: Double, y: Double): Int {
            if (x < p.minX || x > p.maxX || y < p.minY || y > p.maxY) return 0
            if (!inside(p.rings[0], x, y)) return 0
            for (k in 1 until p.rings.size) if (inside(p.rings[k], x, y)) return 0
            return p.bits
        }
    }

    private fun key(gx: Int, gy: Int): Long = (gx.toLong() shl 32) xor (gy.toLong() and 0xffffffffL)

    private fun inside(r: DoubleArray, x: Double, y: Double): Boolean {
        var hit = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) hit = !hit
            j = i
        }
        return hit
    }

    /** Load every built state's areas into an index. No land_sources rows -> an empty index that covers nothing (all PUBLIC). */
    fun loadIndex(db: SQLiteDatabase): LandIndex {
        createTables(db)
        val cov = ArrayList<DoubleArray>()
        db.rawQuery("SELECT s,w,n,e FROM land_sources", null).use { c ->
            while (c.moveToNext()) cov.add(doubleArrayOf(c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)))
        }
        val polys = ArrayList<Poly>()
        db.rawQuery("SELECT kind, own_type, rings FROM land_areas", null).use { c ->
            while (c.moveToNext()) {
                val kind = c.getString(0); val own = c.getString(1) ?: ""
                val bits = when (kind) {
                    "PUBLIC" -> if (own == "FED") B_PUBLIC else (B_PUBLIC or B_NONFEDERAL)
                    "NONFED" -> B_NONFEDERAL
                    "RESTRICTED" -> B_RESTRICTED
                    else -> 0
                }
                if (bits == 0) continue
                for (poly in c.getString(2).split('|')) {
                    val rings = poly.split(';').map { ring ->
                        val pts = ring.split(',')
                        val a = DoubleArray(pts.size * 2)
                        pts.forEachIndexed { i, pt -> val sp = pt.indexOf(' '); a[2 * i] = pt.substring(0, sp).toDouble(); a[2 * i + 1] = pt.substring(sp + 1).toDouble() }
                        a
                    }
                    val o = rings[0]
                    var mnx = 999.0; var mny = 999.0; var mxx = -999.0; var mxy = -999.0
                    var i = 0
                    while (i < o.size) { val x = o[i]; val y = o[i + 1]; if (x < mnx) mnx = x; if (x > mxx) mxx = x; if (y < mny) mny = y; if (y > mxy) mxy = y; i += 2 }
                    polys.add(Poly(bits, mnx, mny, mxx, mxy, rings))
                }
            }
        }
        val grid = HashMap<Long, ArrayList<Int>>()
        val big = ArrayList<Int>()
        polys.forEachIndexed { i, p ->
            val x0 = Math.floor(p.minX / CELL).toInt(); val x1 = Math.floor(p.maxX / CELL).toInt()
            val y0 = Math.floor(p.minY / CELL).toInt(); val y1 = Math.floor(p.maxY / CELL).toInt()
            if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > 20000L) { big.add(i); return@forEachIndexed }   // tested by box every time
            for (gx in x0..x1) for (gy in y0..y1) grid.getOrPut(key(gx, gy)) { ArrayList() }.add(i)
        }
        Log.i(TAG, "PADUSLAND index: ${polys.size} polygons, ${cov.size} state(s) covered, ${grid.size} cells, ${big.size} large")
        return LandIndex(polys, grid.mapValues { it.value.toIntArray() }, big.toIntArray(), cov)
    }
}
