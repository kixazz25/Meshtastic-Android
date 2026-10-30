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
            okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(tj).build()).execute().use { r ->
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
    fun enqueueWithElevation(context: Context, n: Double, s: Double, e: Double, w: Double): Int =
        DownloadQueueManager.enqueueArea(context, SLOT, n, s, e, w) + DownloadQueueManager.enqueueArea(context, ElevationTiles.SLOT, n, s, e, w)

    /** Queue the first [count] recipe boxes as VEC AREA downloads. Returns one line for the screen. */
    fun queueFromRecipe(context: Context, count: Int): String {
        val r = try { recipeBoxes() } catch (e: Exception) { return "Could not read the recipe: ${e.message}" }
            ?: return "No saved recipe on this phone yet."
        val take = r.second.take(count)
        if (take.isEmpty()) return "${r.first}: no boxes in it."
        var jobs = 0
        for (b in take) jobs += enqueueWithElevation(context, b[0], b[1], b[2], b[3])   // DEMSOURCE-2026-10-30: the binding
        return "${r.first.removePrefix("grouptrack_maps_").removeSuffix(".gtmaps")}: queued ${take.size} of ${r.second.size} boxes " +
            "as $jobs job(s) -- vector and elevation together (DL VEC, DL DEM). Watch them in Download queues."
    }
}
