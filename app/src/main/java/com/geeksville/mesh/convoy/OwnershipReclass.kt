package com.geeksville.mesh.convoy

import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * OwnershipReclass — STEP 8 of the state import. OWNERSHIP-2026-08-31.
 *
 * THE PROBLEM IT SOLVES. A town street and a backcountry road are the SAME TAG
 * in OSM: `highway=unclassified`, a minor public road. There is no identifier
 * distinguishing Avenue A from Fisher Wash Road, which is why the Beryl street
 * grid arrives as routable trail data. Eight ways of separating them by tag,
 * name, shape or OSM polygon were tried on 2026-08-30 and every one failed —
 * mostly on COVERAGE, because OSM has roads but almost nothing else in rural
 * western Utah (0 residential landuse polygons at Beryl Junction, 7 buildings
 * in the whole grid box, 11 settlement polygons statewide).
 *
 * ⭐ LAND OWNERSHIP HAS NO COVERAGE HOLE BY CONSTRUCTION. Every acre is owned by
 * someone, so every road sits on a polygon. Measured 08-31 with a
 * point-in-polygon test: the complaint areas came back 97% Private and the
 * backcountry areas 74% Federal. That is the first real separation found.
 *
 * ⭐⭐ ANY PUBLIC SEGMENT WINS (Fred 08-31). A midpoint-only test dropped
 * Tv Tower Road, 24000 North and 12500 West — roads that DO touch public land,
 * just not at their middle; 28 of 75 straddled a boundary. Sampling the whole
 * geometry and keeping anything that touches public rescues that population,
 * while the Beryl grid — 100% private, 1 of 43 straddling — has no public
 * segment near it and stays caught.
 *
 * ⛔ IT IS A CATEGORY, NOT A DELETION. Nothing is removed. The rider turns
 * `R - Residential Roads` off and the town grid goes; turns it on and it is
 * back. A wrong call is visible and recoverable — which is what makes an
 * imperfect rule acceptable.
 *
 * ⚠ KNOWN IMPERFECTION, RECORDED HONESTLY: each OSM way is judged on its own,
 * so a road running private-public-private has its wholly-private segments
 * caught. On the 08-31 laptop run, 9 of 11 `24000 North` segments flipped —
 * a road Fred rides. Fred: *"we are not going to get perfection … we need the
 * map done with the filters and colors applied so we can see the difference and
 * make rational decisions, not check ghosts."* Judging by NAME rather than by
 * segment would fix it and is the obvious next refinement.
 *
 * ⚠ UTAH ONLY, DELIBERATELY (Fred 08-31): *"let's not solve for other states
 * till we have an issue."* Utah has both the worst version of this problem and
 * the best data for it. PAD-US is the national fallback when another state
 * needs one.
 *
 * THE DATA. `Land_Ownership.geojson` from UGRC/SITLA, ~72 MB, 16,034 polygons.
 * Read from shared storage — NOT bundled, and NOT fetched here: both SITLA
 * endpoints returned 503 all morning on 08-31, and an import that depends on
 * someone else's uptime fails exactly when a rider needs it. File present, this
 * runs. File absent, it is skipped and the import completes normally.
 */
object OwnershipReclass {

    private const val TAG = "OwnershipReclass"

    const val RESIDENTIAL = "R - Residential Roads"

    /**
     * ⚠ 'R', NOT A DIGIT. trailColor/trailWeight switch on charAt(0), which
     * constrains the code to ONE CHARACTER — not one DIGIT. All of 0-9 were
     * taken; letters were always free.
     */
    private const val FILE_NAME = "Land_Ownership.geojson"

    /** Grid cell in degrees. 0.05 ≈ 3.5 miles. */
    private const val CELL = 0.05

    /**
     * ⭐ SAMPLE EVERY HALF MILE, minimum both ends. A subdivision street is a
     * few hundred feet and needs ONE test; a ten-mile section road needs twenty,
     * placed where the ground actually changes. A fixed count over-samples short
     * roads and under-samples long ones in the same pass — which is how the
     * 08-31 laptop run missed the public stretches on `24000 North`.
     */
    private const val SAMPLE_MILES = 0.5

    private class Ring(
        val minX: Double, val maxX: Double,
        val minY: Double, val maxY: Double,
        val xs: DoubleArray, val ys: DoubleArray,
        val private: Boolean,
    )

    fun ownershipFile(): File =
        File(File(Environment.getExternalStorageDirectory(), "Documents/GroupTrack"),
            FILE_NAME)

    /**
     * Reclassify every row with no usable carto_code. Returns the number of
     * rows changed, or -1 if the pass could not run.
     *
     * @param onProgress (done, total) — this walks tens of thousands of rows
     *        and MUST be visible. A job nobody can watch is one a rider doubts
     *        and force-quits.
     */
    fun run(
        sDb: SQLiteDatabase,
        eDb: SQLiteDatabase,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Int {
        val f = ownershipFile()
        if (!f.exists() || f.length() < 1024L) {
            Log.i(TAG, "no ownership file at ${f.absolutePath} -- skipping step 8")
            return -1
        }

        val started = System.currentTimeMillis()
        val rings = try {
            loadRings(f)
        } catch (e: Exception) {
            Log.e(TAG, "ownership parse failed: ${e.javaClass.simpleName} ${e.message}")
            return -1
        }
        if (rings.isEmpty()) {
            Log.e(TAG, "ownership file carried no usable rings")
            return -1
        }

        val grid = HashMap<Long, MutableList<Int>>()
        rings.forEachIndexed { i, r ->
            val x0 = Math.floor(r.minX / CELL).toInt()
            val x1 = Math.floor(r.maxX / CELL).toInt()
            val y0 = Math.floor(r.minY / CELL).toInt()
            val y1 = Math.floor(r.maxY / CELL).toInt()
            // ⚠ a huge ring would carpet the grid; skip indexing it rather than
            // spend the memory. It is still tested by the bbox scan fallback.
            if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > 20000L) return@forEachIndexed
            for (gx in x0..x1) for (gy in y0..y1) {
                grid.getOrPut(key(gx, gy)) { ArrayList() }.add(i)
            }
        }
        Log.i(TAG, "ownership: ${rings.size} rings, ${grid.size} cells, " +
            "${(System.currentTimeMillis() - started) / 1000}s")

        // ── the rows: no usable carto, whatever source wrote them ──────────
        // ⭐ BY VALUE, NOT BY SOURCE (Fred 08-31): an unspecified UGRC road
        // through a subdivision is the same clutter as an unspecified OSM one.
        // CLASSIFY4-2026-09-02: ⛔ EVERY ROW, NOT JUST THE UNCLASSIFIED ONES.
        // This query used to read only rows with no usable carto_code -- about
        // 28,000 of 146,000 -- because step 8's job was to FLIP those to a
        // residential category. The four-field design replaced that: ownership
        // is its own field, so EVERY trail needs an answer.
        // ⚠ The 08-31 measurement of the old behaviour: it missed 17,283
        // private motorized and 20,013 private non-motorized rows.
        // ⚠ AND IT COSTS TIME. ~146,000 point-in-polygon tests instead of
        // ~28,000. It is already inside a progress-reporting step, which is
        // why this is tolerable here and would not be anywhere else.
        val ids = ArrayList<String>()
        val wkts = ArrayList<String>()
        val srcs = ArrayList<String>()
        val uses = ArrayList<String>()
        // ⚠ carto_code_source may be null on a database that predates it; fall
        // back to carto_code, which IS the source value except where the old
        // step 8 overwrote it. Same fallback classify4 used.
        sDb.rawQuery(
            "SELECT trail_id, COALESCE(NULLIF(TRIM(carto_code_source),''), carto_code), " +
                "geometry FROM trails", null
        ).use { c ->
            while (c.moveToNext()) {
                val g = c.getString(2)
                if (!g.isNullOrEmpty()) {
                    ids.add(c.getString(0))
                    srcs.add(c.getString(1) ?: "")
                    wkts.add(g)
                }
            }
        }
        // designated_uses lives in the OTHER database, so it cannot be joined
        // in the query above -- read it into a map and look it up per row.
        val useOf = HashMap<String, String>(ids.size)
        try {
            eDb.rawQuery(
                "SELECT trail_id, designated_uses FROM trail_properties " +
                    "WHERE designated_uses IS NOT NULL AND TRIM(designated_uses) <> ''",
                null
            ).use { c ->
                while (c.moveToNext()) useOf[c.getString(0)] = c.getString(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "designated_uses read failed: ${e.message}")
        }
        for (id in ids) uses.add(useOf[id] ?: "")

        Log.i(TAG, "step 8: classifying ${ids.size} rows (${useOf.size} with uses)")
        if (ids.isEmpty()) return 0

        // ⭐ THE FOUR FIELDS. carto_code_source keeps what the source said;
        // carto_code is OUR category and carries nothing about ownership or
        // motorisation; land_status and use_type each answer one question.
        val cats = ArrayList<String>(ids.size)
        val lands = ArrayList<String>(ids.size)
        val usets = ArrayList<String>(ids.size)
        for (i in ids.indices) {
            if (i % 2000 == 0) onProgress?.invoke(i, ids.size)
            val cat = TrailClassifier.categoryOf(srcs[i], uses[i])
            cats.add(cat)
            usets.add(TrailClassifier.useOf(cat))
            // ⭐ ANY PUBLIC SEGMENT WINS (Fred, 08-31): a feature with any
            // sampled point on public land is PUBLIC. allPrivate() already
            // samples every half mile plus both ends.
            lands.add(if (allPrivate(wkts[i], rings, grid)) "PRIVATE" else "PUBLIC")
        }
        onProgress?.invoke(ids.size, ids.size)

        // ⚠ BOTH STORES. SpatialDbManager:1689 arbitrates between them
        // ("spatial wins only if it has a real value"), so leaving one behind
        // means the same trail disagrees with itself.
        var n = 0
        var priv = 0
        sDb.beginTransaction()
        eDb.beginTransaction()
        try {
            // ⛔ NO MORE `R - Residential Roads`. Fred, 08-31: "ownership is a
            // SEPARATE FIELD, not folded into the category" -- folding it in is
            // what made the old step 8 destroy identity, and 201 rows still
            // cannot say what they used to be. A private road now KEEPS its
            // category and answers PRIVATE on land_status.
            val s1 = sDb.compileStatement(
                "UPDATE trails SET carto_code_source=?, carto_code=?, " +
                    "land_status=?, use_type=? WHERE trail_id=?")
            // ⚠ BOTH STORES. SpatialDbManager:1689 arbitrates between them
            // ("spatial wins only if it has a real value"), so leaving one
            // behind means the same trail disagrees with itself.
            val s2 = eDb.compileStatement(
                "UPDATE trail_properties SET carto_code=? WHERE trail_id=?")
            for (i in ids.indices) {
                s1.clearBindings()
                s1.bindString(1, srcs[i]); s1.bindString(2, cats[i])
                s1.bindString(3, lands[i]); s1.bindString(4, usets[i])
                s1.bindString(5, ids[i])
                s1.executeUpdateDelete()
                s2.clearBindings()
                s2.bindString(1, cats[i]); s2.bindString(2, ids[i])
                s2.executeUpdateDelete()
                if (lands[i] == "PRIVATE") priv++
                n++
            }
            sDb.setTransactionSuccessful()
            eDb.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "step 8 write failed: ${e.message}")
            return -1
        } finally {
            eDb.endTransaction()
            sDb.endTransaction()
        }

        Log.i(TAG, "step 8 complete: $n classified, $priv private, " +
            "in ${(System.currentTimeMillis() - started) / 1000}s")
        return n
    }

    private fun key(gx: Int, gy: Int): Long = (gx.toLong() shl 32) xor (gy.toLong() and 0xffffffffL)

    // ── the rule ────────────────────────────────────────────────────────

    /** True only if EVERY sampled point is on private land. Any public wins. */
    private fun allPrivate(
        wkt: String, rings: List<Ring>, grid: Map<Long, MutableList<Int>>,
    ): Boolean {
        var sawPrivate = false
        for ((x, y) in samplePoints(wkt)) {
            val p = ownerAt(x, y, rings, grid) ?: continue
            if (!p) return false        // ⭐ public -- stop immediately
            sawPrivate = true
        }
        // ⚠ no polygon under any sampled point: NOT private, left as it is.
        // Absence of evidence is not evidence.
        return sawPrivate
    }

    /** null = no polygon here, true = private, false = public. */
    private fun ownerAt(
        x: Double, y: Double, rings: List<Ring>, grid: Map<Long, MutableList<Int>>,
    ): Boolean? {
        val cand = grid[key(Math.floor(x / CELL).toInt(), Math.floor(y / CELL).toInt())]
            ?: return null
        for (i in cand) {
            val r = rings[i]
            if (x < r.minX || x > r.maxX || y < r.minY || y > r.maxY) continue
            if (inside(r, x, y)) return r.private
        }
        return null
    }

    private fun inside(r: Ring, x: Double, y: Double): Boolean {
        var hit = false
        val n = r.xs.size
        var j = n - 1
        for (i in 0 until n) {
            val yi = r.ys[i]; val yj = r.ys[j]
            if ((yi > y) != (yj > y)) {
                if (x < (r.xs[j] - r.xs[i]) * (y - yi) / (yj - yi) + r.xs[i]) hit = !hit
            }
            j = i
        }
        return hit
    }

    /** Points every SAMPLE_MILES along the line, plus both ends. */
    private fun samplePoints(wkt: String): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>(64)
        var i = 0
        val n = wkt.length
        val sb = StringBuilder(24)
        var x: Double? = null
        while (i < n) {
            val ch = wkt[i]
            if (ch == '-' || ch == '.' || (ch in '0'..'9') || ch == 'E' || ch == 'e') {
                sb.append(ch)
            } else if (sb.isNotEmpty()) {
                val v = sb.toString().toDoubleOrNull(); sb.setLength(0)
                if (v != null) { if (x == null) x = v else { pts.add(x!! to v); x = null } }
            }
            i++
        }
        if (sb.isNotEmpty() && x != null) {
            sb.toString().toDoubleOrNull()?.let { pts.add(x!! to it) }
        }
        if (pts.size <= 2) return pts

        val out = ArrayList<Pair<Double, Double>>(16)
        out.add(pts[0])
        var acc = 0.0
        for (k in 1 until pts.size) {
            val (x1, y1) = pts[k - 1]
            val (x2, y2) = pts[k]
            val dy = (y2 - y1) * 69.0
            val dx = (x2 - x1) * 69.0 * Math.cos(Math.toRadians((y1 + y2) / 2.0))
            acc += Math.sqrt(dx * dx + dy * dy)
            if (acc >= SAMPLE_MILES) { out.add(pts[k]); acc = 0.0 }
        }
        val last = pts[pts.size - 1]
        if (out[out.size - 1] !== last) out.add(last)
        return out
    }

    // ── the file ────────────────────────────────────────────────────────

    /**
     * Streaming-ish GeoJSON read. ⚠ 72 MB is too much to hold as a parsed
     * object graph on a phone, so this pulls the fields it needs with a scan
     * rather than building a JSONObject for the whole document: the `owner`
     * value and the coordinate runs, feature by feature.
     */
    private fun loadRings(f: File): List<Ring> {
        val out = ArrayList<Ring>(20000)
        val text = f.readText()           // ~72 MB as a String
        var pos = 0
        while (true) {
            val fi = text.indexOf("\"owner\"", pos)
            if (fi < 0) break
            val q1 = text.indexOf('"', text.indexOf(':', fi) + 1)
            if (q1 < 0) break
            val q2 = text.indexOf('"', q1 + 1)
            if (q2 < 0) break
            val owner = text.substring(q1 + 1, q2)
            val gi = text.indexOf("\"coordinates\"", q2)
            if (gi < 0) break
            var end = text.indexOf("\"owner\"", gi)
            if (end < 0) end = text.length
            parseRings(text, gi, end, owner == "Private", out)
            pos = end
        }
        return out
    }

    /** Every bracket-run of coordinate pairs between [from, to). */
    private fun parseRings(
        text: String, from: Int, to: Int, isPrivate: Boolean, out: MutableList<Ring>,
    ) {
        var i = from
        val xs = ArrayList<Double>(512)
        val ys = ArrayList<Double>(512)
        val sb = StringBuilder(24)
        var x: Double? = null
        var depth = 0
        while (i < to) {
            val c = text[i]
            when {
                c == '[' -> depth++
                c == ']' -> {
                    if (sb.isNotEmpty()) {
                        val v = sb.toString().toDoubleOrNull(); sb.setLength(0)
                        if (v != null && x != null) { xs.add(x!!); ys.add(v); x = null }
                    }
                    depth--
                    if (depth <= 1 && xs.size >= 4) {
                        out.add(Ring(xs.min(), xs.max(), ys.min(), ys.max(),
                            xs.toDoubleArray(), ys.toDoubleArray(), isPrivate))
                        xs.clear(); ys.clear()
                    }
                }
                c == '-' || c == '.' || c in '0'..'9' || c == 'e' || c == 'E' -> sb.append(c)
                else -> if (sb.isNotEmpty()) {
                    val v = sb.toString().toDoubleOrNull(); sb.setLength(0)
                    if (v != null) { if (x == null) x = v else { xs.add(x!!); ys.add(v); x = null } }
                }
            }
            i++
        }
    }
}
