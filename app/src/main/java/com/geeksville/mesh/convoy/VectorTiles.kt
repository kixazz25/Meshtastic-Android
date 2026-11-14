package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtMapSpecAndroid   // G1CDELEGATE-2026-11-14
import com.grouptrack.shared.mapsource.GtMapSpec         // G1CDELEGATE-2026-11-14
import okio.Path.Companion.toOkioPath   // STEP16D-2026-11-11

/**
 * VECSOURCE-2026-10-30 (2.7e step 1, Fred 10-30): THE FOURTH GROUP -- vector maps, store VEC.mbtiles.
 * Source: map_sources.json "vector_sources" (OpenFreeMap). NOT a map-bar slot: Leaflet cannot draw vector tiles; MapLibre will.
 * Fed from the daily recipe (ConvoyMapRecipe) through the ordinary AREA download queue.
 */
object VectorTiles {
    // G1CDELEGATE-2026-11-14: the SHARED constants -- one source of truth, no second copy to drift.
    const val SLOT = GtMapSpec.VEC_SLOT
    const val Z_MAX = GtMapSpec.VEC_Z_MAX   // VECENQUEUE-2026-10-30: OpenFreeMap ends at z14; never list tiles above it
    // TAG and the @Volatile resolved cache are GONE -- GtMapSpec owns the cache (and its resetForCert).

    /** G1CDELEGATE-2026-11-14: THE SHARED CODE (GtMapSpec.vectorTileTemplate, certified 11-13 -- 4e9dc9db1).
     *  The {z}/{x}/{y} template from the TileJSON (OpenFreeMap versions its tile path). Null = not reachable now.
     *  The asset read and the TileJSON fetch are the socket's (GtMapSpecAndroid.Env). */
    fun tileTemplate(context: Context): String? =
        GtMapSpec.vectorTileTemplate(GtMapSpecAndroid.Env(context))

    /** What the worker downloads for a VEC job. Role "overlay": TileCodec stores the bytes as received (no image re-encode). */
    fun downloadSources(context: Context): List<Pair<String, List<MapSourceManager.TileLayer>>> =
        GtMapSpecAndroid.layers(GtMapSpec.vectorDownloadSources(GtMapSpecAndroid.Env(context)))

    /** STEP16D-2026-11-11: the save-by-area writes are the SHARED code (GtVectorQueue, certified 16c: Droid OLD = NEW, iPad = Droid,
     *  6,399 values). These are its Android front doors -- same names, same arguments, same results; the queue platform is
     *  DownloadQueueManager's (vecQueueFor). The newest recipe saved on this phone: every box of every slot, duplicates once. */
    fun recipeBoxes(): Pair<String, List<DoubleArray>>? = DownloadQueueManager.vecQueue().recipeBoxes()

    /** VECTILECOUNT-2026-10-31: the recipe's own tile total over the OLD map slots (all but VEC / DEM) -- "your old maps: N tiles". */
    fun oldTilesOf(f: java.io.File): Long = try {
        // G1CDELEGATE-2026-11-14: THE SHARED CODE. Reading the file is the front door's job (MapSpecCertOld
        // substitution 4). ⚠ THE try/catch STAYS HERE: 2.7e wrapped the READ and the parse together and
        // returned 0L for either, so without it a missing recipe file would now THROW where it returned 0.
        GtMapSpec.oldTilesOf(f.readText())
    } catch (e: Exception) { 0L }

    /** STEP16D-2026-11-11: every box of one recipe file (n, s, e, w), exact duplicates once. */
    fun boxesOf(f: java.io.File): List<DoubleArray> = DownloadQueueManager.vecQueue().boxesOf(f.toOkioPath())

    /** STEP16D-2026-11-11: the z12 squares a box covers. */
    fun squaresInBox(n: Double, s: Double, e: Double, w: Double, into: MutableSet<Long>) =
        DownloadQueueManager.vecQueue().squaresInBox(n, s, e, w, into)

    /** DEMSOURCE-2026-10-30 THE BINDING (STEP16D-2026-11-11: the shared code): every vector box queued WITH its elevation. */
    fun enqueueWithElevation(context: Context, n: Double, s: Double, e: Double, w: Double, replaceExisting: Boolean): Int =
        enqueueWithElevation(context, n, s, e, w, replaceExisting, what = "")

    /** VECTRACKNAMES-2026-10-31: the same, both jobs labelled with [what] (a track name; "" = none). */
    fun enqueueWithElevation(context: Context, n: Double, s: Double, e: Double, w: Double, replaceExisting: Boolean, what: String): Int =
        DownloadQueueManager.vecQueueFor(context).enqueueWithElevation(n, s, e, w, replaceExisting, what)

    /** VECDL-2026-10-31 (STEP16D-2026-11-11: the shared code): the squares under [segments] (lat, lon), with the ring, into [into]. */
    fun squaresFor(segments: List<List<Pair<Double, Double>>>, into: MutableSet<Long>) =
        DownloadQueueManager.vecQueue().squaresFor(segments, into)

    /** Joins [squares] into tight boxes and queues each vector + elevation. Returns (jobs, estimated tiles). */
    fun queueSquares(context: Context, squares: Set<Long>, replaceExisting: Boolean, why: String): Pair<Int, Int> =
        queueSquaresNamed(context, squares, emptyMap(), replaceExisting, why)

    /** VECTRACKNAMES-2026-10-31: [names] = the tracks each square is for; a box is labelled with the tracks in it (empty -> [why]). */
    fun queueSquaresNamed(context: Context, squares: Set<Long>, names: Map<Long, Set<String>>, replaceExisting: Boolean, why: String): Pair<Int, Int> =
        DownloadQueueManager.vecQueueFor(context).queueSquaresNamed(squares, names, replaceExisting, why)

    /** Queue the first [count] recipe boxes as VEC AREA downloads. Returns one line for the screen. */
    fun queueFromRecipe(context: Context, count: Int): String = DownloadQueueManager.vecQueueFor(context).queueFromRecipe(count)
}
