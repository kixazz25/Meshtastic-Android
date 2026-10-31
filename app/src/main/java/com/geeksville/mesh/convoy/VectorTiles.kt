package com.geeksville.mesh.convoy

import android.content.Context
import org.json.JSONObject

/**
 * VECSOURCE-2026-10-30 (2.7e step 1, Fred 10-30): THE FOURTH GROUP -- vector maps, store VEC.mbtiles.
 * Source: map_sources.json "vector_sources" (OpenFreeMap). NOT a map-bar slot: Leaflet cannot draw vector tiles; MapLibre will.
 * Fed from the daily recipe (ConvoyMapRecipe) through the ordinary AREA download queue.
 */
object VectorTiles {
    const val SLOT = "VEC"
    const val Z_MAX = 14   // VECENQUEUE-2026-10-30: OpenFreeMap ends at z14; never list tiles above it
    private const val TAG = "VectorTiles"
    @Volatile private var resolved: String? = null

    private fun spec(context: Context): JSONObject? = try {
        JSONObject(context.assets.open("map_sources.json").bufferedReader().use { it.readText() })
            .optJSONArray("vector_sources")?.optJSONObject(0)
    } catch (e: Exception) { android.util.Log.e(TAG, "map_sources.json: ${e.message}"); null }

    /** The {z}/{x}/{y} template from the TileJSON (OpenFreeMap versions its tile path). Null = not reachable now. */
    fun tileTemplate(context: Context): String? {
        resolved?.let { return it }
        val tj = spec(context)?.optString("tilejson")?.takeIf { it.isNotBlank() } ?: return null
        return try {
            okhttp3.OkHttpClient.Builder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build().newCall(   /* VECONLINE2-2026-10-31 */ okhttp3.Request.Builder().url(tj).build()).execute().use { r ->
                if (!r.isSuccessful) { android.util.Log.e(TAG, "TileJSON HTTP ${r.code}"); return null }
                val t = JSONObject(r.body?.string() ?: "{}").optJSONArray("tiles")?.optString(0)
                if (t.isNullOrBlank()) { android.util.Log.e(TAG, "TileJSON has no tiles"); null }
                else { resolved = t; android.util.Log.i(TAG, "VECSOURCE-2026-10-30 tiles: $t"); t }
            }
        } catch (e: Exception) { android.util.Log.e(TAG, "TileJSON: ${e.message}"); null }
    }

    /** What the worker downloads for a VEC job. Role "overlay": TileCodec stores the bytes as received (no image re-encode). */
    fun downloadSources(context: Context): List<Pair<String, List<MapSourceManager.TileLayer>>> {
        val t = tileTemplate(context) ?: return emptyList()
        return listOf(SLOT to listOf(MapSourceManager.TileLayer("overlay", t, "pbf", SLOT, 0)))
    }

    /** The newest recipe saved on this phone: every box of every slot (n, s, e, w), exact duplicates once, in file order. */
    fun recipeBoxes(): Pair<String, List<DoubleArray>>? {
        val f = GroupTrackStorage.dir("recipes").listFiles()
            ?.filter { it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps") }
            ?.maxByOrNull { it.name } ?: return null
        val slots = JSONObject(f.readText()).optJSONArray("slots") ?: return Pair(f.name, emptyList())
        val seen = HashSet<String>(); val out = ArrayList<DoubleArray>()
        for (i in 0 until slots.length()) {
            val b = slots.optJSONObject(i)?.optJSONArray("boxes") ?: continue
            for (j in 0 until b.length()) {
                val x = b.optJSONObject(j) ?: continue
                val box = doubleArrayOf(x.optDouble("n"), x.optDouble("s"), x.optDouble("e"), x.optDouble("w"))
                if (box.any { it.isNaN() }) continue
                if (seen.add(box.joinToString(","))) out.add(box)
            }
        }
        return Pair(f.name, out)
    }

    /** DEMSOURCE-2026-10-30 (Fred 14:12-14:13): THE BINDING. Every vector box is queued WITH its elevation -- same box, same step.
     *  Separate jobs (own rows, counts, failures), never separate decisions. The one door for a vector download. */
    fun enqueueWithElevation(context: Context, n: Double, s: Double, e: Double, w: Double, replaceExisting: Boolean): Int =   // VECDL-2026-10-31: replace REQUIRED
        DownloadQueueManager.enqueueArea(context, SLOT, n, s, e, w, replaceExisting) + DownloadQueueManager.enqueueArea(context, ElevationTiles.SLOT, n, s, e, w, replaceExisting)

    // ---- VECDL-2026-10-31 (Fred 10-30 15:57: "yes just pull the full area"): a corridor on the vector map = the FULL AREA of the track --
    // every z12 square the line passes through plus RING squares all round, joined into tight boxes (rows of squares, then equal rows
    // stacked -- the RECIPESCAN method), each queued vector + elevation. Never a strip, never one box from the extremes.
    private const val SQ_Z = 12
    private const val RING = 1   // one z12 square all round (~7-8 km in Utah)
    private fun sqX(lon: Double) = Math.floor((lon + 180.0) / 360.0 * (1 shl SQ_Z)).toInt()
    private fun sqY(lat: Double): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return Math.floor((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 shl SQ_Z)).toInt()
    }
    private fun lonOf(x: Int) = x.toDouble() / (1 shl SQ_Z) * 360.0 - 180.0
    private fun latOf(y: Int): Double { val k = Math.PI - 2.0 * Math.PI * y / (1 shl SQ_Z); return Math.toDegrees(Math.atan(Math.sinh(k))) }

    /** Adds the squares under [segments] (lat, lon), with the ring, to [into]. Long straight legs are walked in ~2 km steps. */
    fun squaresFor(segments: List<List<Pair<Double, Double>>>, into: MutableSet<Long>) {
        val n = 1 shl SQ_Z
        fun add(lat: Double, lon: Double) {
            val x = sqX(lon); val y = sqY(lat)
            for (dx in -RING..RING) for (dy in -RING..RING) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until n && yy in 0 until n) into.add(xx.toLong() * n + yy)
            }
        }
        for (seg in segments) {
            var prev: Pair<Double, Double>? = null
            for (p in seg) {
                val q = prev
                if (q != null) {
                    val steps = Math.max(1, Math.ceil(Math.max(Math.abs(p.first - q.first), Math.abs(p.second - q.second)) / 0.02).toInt())
                    for (i in 1 until steps) { val f = i.toDouble() / steps; add(q.first + (p.first - q.first) * f, q.second + (p.second - q.second) * f) }
                }
                add(p.first, p.second); prev = p
            }
        }
    }

    /** Joins [squares] into tight boxes and queues each vector + elevation. Returns (jobs, estimated tiles). */
    fun queueSquares(context: Context, squares: Set<Long>, replaceExisting: Boolean, why: String): Pair<Int, Int> {
        if (squares.isEmpty()) return Pair(0, 0)
        val n = (1 shl SQ_Z).toLong()
        class Box(val x0: Int, val x1: Int, val y0: Int, var y1: Int)
        val byRow = squares.groupBy { (it % n).toInt() }
        var open = HashMap<Long, Box>(); val done = ArrayList<Box>()
        for (y in byRow.keys.sorted()) {
            val xs = byRow.getValue(y).map { (it / n).toInt() }.sorted()
            val runs = ArrayList<Pair<Int, Int>>(); var a = xs[0]; var b = xs[0]
            for (x in xs.drop(1)) { if (x == b + 1) b = x else { runs.add(a to b); a = x; b = x } }
            runs.add(a to b)
            val next = HashMap<Long, Box>()
            for ((x0, x1) in runs) {
                val k = x0.toLong() * n + x1; val o = open[k]
                if (o != null && o.y1 == y - 1) { o.y1 = y; next[k] = o } else next[k] = Box(x0, x1, y, y)
            }
            for ((k, o) in open) if (next[k] !== o) done.add(o)
            open = next
        }
        done.addAll(open.values)
        var jobs = 0; var tiles = 0
        for (bx in done) {
            val nn = latOf(bx.y0); val ss = latOf(bx.y1 + 1); val ww = lonOf(bx.x0); val ee = lonOf(bx.x1 + 1)
            jobs += enqueueWithElevation(context, nn, ss, ee, ww, replaceExisting)
            tiles += ConvoyTileCalculator.quickEstimate(nn, ss, ee, ww, zMin = 0, zMax = Z_MAX).tileCount +
                ConvoyTileCalculator.quickEstimate(nn, ss, ee, ww, zMin = 0, zMax = ElevationTiles.Z_MAX).tileCount
        }
        android.util.Log.i(TAG, "VECDL-2026-10-31 $why: ${squares.size} z12 square(s) -> ${done.size} box(es), $jobs job(s), ~$tiles tiles, replace=$replaceExisting")
        return Pair(jobs, tiles)
    }

    /** Queue the first [count] recipe boxes as VEC AREA downloads. Returns one line for the screen. */
    fun queueFromRecipe(context: Context, count: Int): String {
        val r = try { recipeBoxes() } catch (e: Exception) { return "Could not read the recipe: ${e.message}" }
            ?: return "No saved recipe on this phone yet."
        val take = r.second.take(count)
        if (take.isEmpty()) return "${r.first}: no boxes in it."
        var jobs = 0
        for (b in take) jobs += enqueueWithElevation(context, b[0], b[1], b[2], b[3], false)   // DEMSOURCE-2026-10-30: the binding
        return "${r.first.removePrefix("grouptrack_maps_").removeSuffix(".gtmaps")}: queued ${take.size} of ${r.second.size} boxes " +
            "as $jobs job(s) -- vector and elevation together (DL VEC, DL DEM). Watch them in Download queues."
    }
}
