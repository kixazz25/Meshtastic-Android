package com.geeksville.mesh.convoy

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * ConvoyTileCalculator — V2.4 Offline Map Tile Calculator
 *
 * Calculates the complete set of Web Mercator slippy map tile coordinates
 * for a given geographic bounding box across a range of zoom levels.
 *
 * Used by:
 *   - ConvoyTileDownloader — to know which tiles to download
 *   - ConvoyScreen.kt — to estimate download size before starting
 *
 * All functions are pure — no side effects, fully unit testable.
 *
 * Zoom strategy:
 *   zMin = DOWNLOAD_ZOOM_MIN (10) — always included, gives regional overview
 *   zMax = ConvoyConfig.DOWNLOAD_ZOOM (user-set via slider, default 18)
 */
object ConvoyTileCalculator {

    /** Average tile size in KB — used for size estimation */
    private const val AVG_TILE_KB = 15f

    /** Maximum allowed download in MB */
    const val CEILING_MB = 5000f

    // ── Tile coordinate math ──────────────────────────────────────────────────

    /**
     * Convert longitude to tile X coordinate at zoom level z.
     * Standard Web Mercator slippy map formula.
     */
    fun lon2tile(lon: Double, z: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    /**
     * Convert latitude to tile Y coordinate at zoom level z.
     * Standard Web Mercator slippy map formula.
     */
    fun lat2tile(lat: Double, z: Int): Int {
        val rad = Math.toRadians(lat)
        return floor(
            (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl z)
        ).toInt()
    }

    // ── Main calculation ──────────────────────────────────────────────────────

    /**
     * Calculate all tile keys for a bounding box across zoom levels.
     *
     * @param north  North boundary latitude
     * @param south  South boundary latitude
     * @param east   East boundary longitude
     * @param west   West boundary longitude
     * @param zMin   Minimum zoom level (default: ConvoyConfig.DOWNLOAD_ZOOM_MIN = 10)
     * @param zMax   Maximum zoom level (default: ConvoyConfig.DOWNLOAD_ZOOM = 18)
     * @return       Complete list of TileKey objects covering the bounding box
     */
    // [V2.6b-NO-ZOOM-CAP] Download requests the FULL zoom range for every source.
    // The old per-source cap (TOPO+/TRAIL -> 17) was a stale download restriction;
    // removing it means at worst we fetch empty/404 tiles at high zoom (already the
    // current behavior for sources that top out). The per-source max_zoom in
    // map_sources.json is retained as a DISPLAY clamp to snap back to — download and
    // display ceilings are intentionally decoupled.
    fun maxZoomForSource(sourceName: String): Int = ConvoyConfig.DOWNLOAD_ZOOM

    fun calculateTiles(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        zMin: Int = ConvoyConfig.DOWNLOAD_ZOOM_MIN,
        zMax: Int = ConvoyConfig.DOWNLOAD_ZOOM,
        sourceName: String = ""
    ): List<TileKey> {
        val effectiveZMax = if (sourceName.isNotEmpty()) minOf(zMax, maxZoomForSource(sourceName)) else zMax
        val tiles = mutableListOf<TileKey>()
        for (z in zMin..effectiveZMax) {
            val xMin = lon2tile(west, z)
            val xMax = lon2tile(east, z)
            val yMin = lat2tile(north, z)  // north = smaller y value
            val yMax = lat2tile(south, z)  // south = larger y value
            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    tiles.add(TileKey(z, x, y))
                }
            }
        }
        return tiles
    }

    // ── Size estimation ───────────────────────────────────────────────────────

    /**
     * Estimate download size in MB for a list of tiles.
     * Uses average tile size of 15KB.
     */
    fun estimateSizeMB(tiles: List<TileKey>): Float =
        tiles.size * AVG_TILE_KB / 1024f

    /**
     * Check if a tile list is within the download ceiling.
     */
    fun isWithinCeiling(tiles: List<TileKey>): Boolean =
        estimateSizeMB(tiles) <= CEILING_MB

    /**
     * Quick estimate without building the full tile list.
     * Useful for live preview while user is drawing the bounding box.
     */
    fun quickEstimate(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        zMin: Int = ConvoyConfig.DOWNLOAD_ZOOM_MIN,
        zMax: Int = ConvoyConfig.DOWNLOAD_ZOOM
    ): DownloadEstimate {
        var totalTiles = 0
        for (z in zMin..zMax) {
            val xCount = lon2tile(east, z) - lon2tile(west, z) + 1
            val yCount = lat2tile(south, z) - lat2tile(north, z) + 1
            totalTiles += xCount * yCount
        }
        val sizeMB = totalTiles * AVG_TILE_KB / 1024f
        return DownloadEstimate(
            tileCount = totalTiles,
            estimatedMB = sizeMB,
            withinCeiling = sizeMB <= CEILING_MB,
            zoomMin = zMin,
            zoomMax = zMax
        )
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

/**
 * A single map tile identified by zoom level and x/y coordinates.
 * Key format: "{z}/{x}/{y}" — matches standard slippy map convention.
 */
data class TileKey(val z: Int, val x: Int, val y: Int) {
    /** String key for registry lookup and file path construction */
    override fun toString(): String = "$z/$x/$y"

    companion object {
        fun fromString(s: String): TileKey {
            val parts = s.split("/")
            return TileKey(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }
}

/**
 * Result of a quick size estimate before downloading.
 */
data class DownloadEstimate(
    val tileCount: Int,
    val estimatedMB: Float,
    val withinCeiling: Boolean,
    val zoomMin: Int,
    val zoomMax: Int
) {
    val displaySize: String get() = "%.1f MB".format(estimatedMB)
    val displayCount: String get() = "%,d tiles".format(tileCount)
    val summary: String get() = "$displayCount — $displaySize (z$zoomMin–z$zoomMax)"
}
