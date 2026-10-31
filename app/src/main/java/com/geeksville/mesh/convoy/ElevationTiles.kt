package com.geeksville.mesh.convoy

import android.content.Context
import org.json.JSONObject

/**
 * DEMSOURCE-2026-10-30 (2.7e, Fred 14:07-14:14): THE ELEVATION SOURCE -- store DEM. map_sources.json "elevation_sources" (AWS Terrain Tiles,
 * Terrarium PNG, z0-z12). Not selectable: it is always queued WITH vector (VectorTiles.enqueueWithElevation), as its own jobs.
 */
object ElevationTiles {
    const val SLOT = "DEM"
    const val Z_MAX = 12
    private const val TAG = "ElevationTiles"

    private fun spec(context: Context): JSONObject? = try {
        JSONObject(context.assets.open("map_sources.json").bufferedReader().use { it.readText() })
            .optJSONArray("elevation_sources")?.optJSONObject(0)
    } catch (e: Exception) { android.util.Log.e(TAG, "map_sources.json: ${e.message}"); null }

    /** VECONLINE-2026-10-31: the {z}/{x}/{y} URL, for the map's online fill (a tile not stored, shown while online). */
    fun urlTemplate(context: Context): String? = spec(context)?.optString("url_template")?.takeIf { it.isNotBlank() }

    /** What the worker downloads for a DEM job. ⛔ Role "overlay" = stored AS RECEIVED: the pixel colours are the heights, and
     *  re-encoding (as satellite base images are, to WebP) would change them -- spikes and pits in 3D. */
    fun downloadSources(context: Context): List<Pair<String, List<MapSourceManager.TileLayer>>> {
        val t = spec(context)?.optString("url_template")?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(SLOT to listOf(MapSourceManager.TileLayer("overlay", t, "png", SLOT, 0)))
    }
}
