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

    /**
     * ZOOMSLOT-2026-08-10D: THE DOWNLOAD ZOOM RULE. Satellite to z18, rendered maps to z16.
     *
     * Economics, not capability: each level quadruples the tile count. Above z18
     * satellite imagery in open country is upscaled rather than sharper, and
     * above z16 a topo map has said everything it has to say - contours do not
     * gain detail, labels just get bigger. So the levels above these cost 4x
     * per step and return nothing.
     *
     * KEYED ON THE SLOT, NOT THE SOURCE, because every download path already
     * carries the slot and none of them carry the source. That is also why this
     * publishes as a rule a rider can understand instead of a table of vendor
     * ceilings nobody should have to know.
     *
     * An empty slot means NO CAP - see the note on corridorTiles.
     */
    fun maxZoomForSlot(slotName: String): Int = when (slotName.uppercase()) {
        "" -> ConvoyConfig.DOWNLOAD_ZOOM
        "SAT" -> minOf(18, ConvoyConfig.DOWNLOAD_ZOOM)
        else -> minOf(16, ConvoyConfig.DOWNLOAD_ZOOM)
    }

    /** CORRIDOR-DERIVATION-2026-07-24: the tile set within `bufferDeg` of a line.
     *
     *  NO POLYGON IS BUILT. Walk the points and stamp every tile within the
     *  buffer at each zoom into a SET. Overlaps collapse for free - which is
     *  what makes a self-crossing track (the "figure 7") trivial here, where a
     *  polygon buffer would need self-intersection handling.
     *
     *  ⚠ INTERPOLATION IS NOT OPTIONAL. Track points can be hundreds of metres
     *  apart on a straight fast section; stamping only at the vertices would
     *  leave HOLES in the corridor at high zoom. We step at half a tile width
     *  (at zMax) so no tile between two points can be missed.
     *
     *  ⚠ Each SEGMENT is walked independently - never interpolate across the
     *  gap between two disjoint segments (see getTrackPoints).
     *
     *  @param segments one inner list per segment, points as (LAT, LON)
     *  @param bufferDeg half-width in DEGREES OF LATITUDE (0.00724 ~ half a
     *         mile, the same pad getTrackBbox uses, so PoC numbers are
     *         directly comparable to the bbox baseline)
     *  @return deduped tiles, ready for ConvoyTileDownloader.downloadTiles */
    fun corridorTiles(
        segments: List<List<Pair<Double, Double>>>,
        bufferDeg: Double = 0.00724,
        zMin: Int = ConvoyConfig.DOWNLOAD_ZOOM_MIN,
        zMax: Int = ConvoyConfig.DOWNLOAD_ZOOM,
        // ZOOMSLOT-2026-08-10D: slot for the zoom rule. EMPTY MEANS NO CAP, and that default
        // exists for one caller in particular: ConvoyCorridorDelete.
        //
        // *** A CAPPED DELETE WOULD STRAND TILES FOREVER. *** The delete derives
        // the set it removes from this same function. Cap it, and it computes a
        // smaller set than an older build stored - leaving z17-z18 tiles that
        // nothing can subsequently find or remove, on a store measured in
        // gigabytes. So the delete deliberately does NOT pass a slot and keeps
        // computing the full historical range.
        //
        // Only the two DOWNLOAD paths pass one. That asymmetry is the point.
        slotName: String = "",
        // FUNNEL-2026-08-12B: HOW MANY TILE WIDTHS OF MAP TO KEEP EITHER SIDE OF THE TRACK.
        // 0 = flat bufferDeg, exactly today's behaviour.
        //
        // The buffer stops being one distance for every zoom and becomes a
        // function of the zoom, so the screen FILLS at every level instead of
        // only where a tile happens to be narrower than the band.
        //
        // WHY: group tracking. The app auto-zooms to keep the whole group in
        // frame and first cart to last may be three miles apart. Measured, a
        // three-mile spread lands on z14 with EIGHTY PERCENT OF THE SCREEN
        // WHITE; two miles lands on z15 at 61%; one mile on z16 at 22% -
        // which is exactly why z16 is the last good level today.
        //
        // H = ceil(screen half-diagonal in tiles). The half-diagonal is the
        // right measure because the furthest screen point from a line through
        // centre is a corner, and it does not change when the device rotates.
        // Worst case is a large tablet at 3.33 tiles, so four.
        //
        // *** EXACTLY THE SAME ASYMMETRY AS slotName ABOVE, FOR THE SAME
        // REASON. *** ConvoyCorridorDelete derives what it REMOVES from this
        // function. At z18 the funnel buffer is NARROWER than the flat one, so
        // a funnel-based delete would compute a smaller set than older builds
        // stored - stranding the outer tiles where nothing can ever find or
        // remove them. The delete does not pass this. Only downloads do.
        funnelH: Int = 0
    ): List<TileKey> {
        val keys = LinkedHashSet<TileKey>()
        val zTop = minOf(zMax, maxZoomForSlot(slotName))
        if (zTop < zMax) {
            android.util.Log.i("Corridor",
                "ZOOMSLOT-2026-08-10D: $slotName capped z$zMax -> z$zTop")
        }
        // Half a tile width at zTop, in degrees of longitude.
        val stepDeg = (360.0 / Math.pow(2.0, zTop.toDouble())) / 2.0
        for (seg in segments) {
            if (seg.isEmpty()) continue
            for (i in seg.indices) {
                stampPoint(keys, seg[i].first, seg[i].second, bufferDeg, zMin, zTop, funnelH)
                if (i == seg.lastIndex) continue
                val (lat1, lon1) = seg[i]
                val (lat2, lon2) = seg[i + 1]
                val dLat = lat2 - lat1
                val dLon = lon2 - lon1
                val dist = Math.max(Math.abs(dLat), Math.abs(dLon))
                val steps = Math.ceil(dist / stepDeg).toInt()
                if (steps <= 1) continue
                for (s in 1 until steps) {
                    val f = s.toDouble() / steps
                    stampPoint(keys, lat1 + dLat * f, lon1 + dLon * f, bufferDeg, zMin, zTop, funnelH)
                }
            }
        }
        android.util.Log.i("Corridor",
            "corridorTiles: ${segments.size} seg, ${segments.sumOf { it.size }} pts, " +
            "FUNNEL-2026-08-12B bufferDeg=$bufferDeg funnelH=$funnelH z$zMin-$zTop -> ${keys.size} tiles")
        return keys.toList()
    }

    /** CORRIDOR-DERIVATION-2026-07-24: every tile within `bufferDeg` of ONE point, at every
     *  zoom. Longitude is scaled by cos(lat) so the buffer stays circular on
     *  the ground rather than stretching east-west away from the equator -
     *  the same correction getTrackBbox applies to its pad. */
    private fun stampPoint(
        into: MutableSet<TileKey>,
        lat: Double, lon: Double,
        bufferDeg: Double, zMin: Int, zMax: Int, funnelH: Int = 0
    ) {
        val cosLat = Math.max(0.01, Math.cos(Math.toRadians(lat)))
        for (z in zMin..zMax) {
            // FUNNEL-2026-08-12B: the buffer is per-zoom when funnelH > 0.
            //
            // One tile at zoom z spans 360 / 2^z degrees of longitude, so H
            // tile widths IS H * 360 / 2^z degrees - an exact conversion, not
            // an approximation. The latitude half-width is the same GROUND
            // distance, which is the longitude one scaled by cos(lat); that is
            // the existing lonBuf = bufferDeg / cos(lat) relationship inverted.
            //
            // No floor is needed at H=4: at z18 it already works out wider
            // than the quarter-mile minimum a loosely-recorded track wants, so
            // the rule stays one expression with no special case.
            val latBuf: Double
            val lonBuf: Double
            if (funnelH > 0) {
                lonBuf = funnelH * 360.0 / Math.pow(2.0, z.toDouble())
                latBuf = lonBuf * cosLat
            } else {
                latBuf = bufferDeg
                lonBuf = bufferDeg / cosLat
            }
            // FUNNELCLAMP-2026-08-12G: CLAMP TO THE WORLD.
            //
            // At z4 the world is sixteen tiles wide and the funnel buffer is
            // ninety degrees of longitude, so lon - buffer runs off the west
            // edge and lon2tile returns -1. The server answers HTTP 400 and the
            // job records a failure for a tile that cannot exist.
            //
            // That is what produced an identical failed=20 on four unrelated
            // tracks: at these zooms the buffer dominates the track completely,
            // so every track in the same region generates the same handful of
            // out-of-range URLs.
            //
            // MBTilesStore.tilesInBounds already coerces exactly this way. This
            // was the inconsistency - the old flat buffer never came near a
            // world edge, so it never showed.
            //
            // ⚠ CLAMP, DO NOT WRAP. A buffer wider than the world would, if
            // wrapped, request the entire tile row, which is not what a corridor
            // means. Stopping at the edge is correct: what lies beyond is ocean
            // or the far hemisphere.
            //
            // ⚠ x and y clamp for different reasons - x because the buffer can
            // exceed the world's width, y because latitudes past the Mercator
            // limit have no tile at all.
            val world = (1 shl z) - 1
            val xMin = lon2tile(lon - lonBuf, z).coerceIn(0, world)
            val xMax = lon2tile(lon + lonBuf, z).coerceIn(0, world)
            val yMin = lat2tile(lat + latBuf, z).coerceIn(0, world)   // north = smaller y
            val yMax = lat2tile(lat - latBuf, z).coerceIn(0, world)
            for (x in xMin..xMax) for (y in yMin..yMax) into.add(TileKey(z, x, y))
        }
    }

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
