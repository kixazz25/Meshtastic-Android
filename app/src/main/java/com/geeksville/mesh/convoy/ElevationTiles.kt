package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtMapSpecAndroid   // G1CDELEGATE-2026-11-14
import com.grouptrack.shared.mapsource.GtMapSpec         // G1CDELEGATE-2026-11-14

/**
 * DEMSOURCE-2026-10-30 (2.7e, Fred 14:07-14:14): THE ELEVATION SOURCE -- store DEM. map_sources.json "elevation_sources" (AWS Terrain Tiles,
 * Terrarium PNG, z0-z12). Not selectable: it is always queued WITH vector (VectorTiles.enqueueWithElevation), as its own jobs.
 */
object ElevationTiles {
    // G1CDELEGATE-2026-11-14: the SHARED constants.
    const val SLOT = GtMapSpec.DEM_SLOT
    const val Z_MAX = GtMapSpec.DEM_Z_MAX

    /** VECONLINE-2026-10-31: the {z}/{x}/{y} URL, for the map's online fill (a tile not stored, shown while online). */
    fun urlTemplate(context: Context): String? =
        GtMapSpec.elevationUrlTemplate(GtMapSpecAndroid.Env(context))   // G1CDELEGATE-2026-11-14

    /** What the worker downloads for a DEM job. ⛔ Role "overlay" = stored AS RECEIVED: the pixel colours are the heights, and
     *  re-encoding (as satellite base images are, to WebP) would change them -- spikes and pits in 3D. */
    fun downloadSources(context: Context): List<Pair<String, List<MapSourceManager.TileLayer>>> =
        GtMapSpecAndroid.layers(GtMapSpec.elevationDownloadSources(GtMapSpecAndroid.Env(context)))
}
