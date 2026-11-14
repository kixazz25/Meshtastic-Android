package com.grouptrack.shared.queue

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtMath
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor

/**
 * STEP16B-2026-11-10 (2.8 step 16b): the tile arithmetic of the save-by-area writes, PORTED AS IS --
 *   ConvoyTileCalculator.lon2tile / lat2tile / quickEstimate (+ DownloadEstimate), ConvoyTileDownloader.segmentCells.
 * java.lang.Math -> GtMath (tan / ln / cos: Android's own algorithms on the iPad -- MATHTILE 11-10, 0 DIFF on 1,938,346 values; pow:
 * GTMATH2), Math.toRadians -> GtJava.toRadians. floor / ceil / max are exact everywhere. The two 2.7e defaults that read app state
 * (quickEstimate's zMin / zMax = ConvoyConfig.DOWNLOAD_ZOOM_MIN / DOWNLOAD_ZOOM, segmentCells' zMax = DOWNLOAD_ZOOM -- a var) are
 * REQUIRED here: the save-by-area path always passes them.
 */
data class GtDownloadEstimate(
    val tileCount: Int,
    val estimatedMB: Float,
    val withinCeiling: Boolean,
    val zoomMin: Int,
    val zoomMax: Int
)

/**
 * ESTCALC-2026-11-14: what one vector-map area download actually fetches, per slot and in total.
 * The breakdown is kept, not just the total, so a screen can show "VEC n + DEM n" without a second call.
 */
data class GtAreaTiles(
    val vecTiles: Int,
    val demTiles: Int,
) {
    val total: Int get() = vecTiles + demTiles
}

object GtTileMath {
    private const val AVG_TILE_KB = 15f
    const val CEILING_MB = 5000f
    private val seg = gtLogger("SEGMENT")

    fun lon2tile(lon: Double, z: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    fun lat2tile(lat: Double, z: Int): Int {
        val rad = GtJava.toRadians(lat)
        return floor(
            (1.0 - GtMath.ln(GtMath.tan(rad) + 1.0 / GtMath.cos(rad)) / PI) / 2.0 * (1 shl z)
        ).toInt()
    }

    fun quickEstimate(north: Double, south: Double, east: Double, west: Double, zMin: Int, zMax: Int): GtDownloadEstimate {
        var totalTiles = 0
        for (z in zMin..zMax) {
            val xCount = lon2tile(east, z) - lon2tile(west, z) + 1
            val yCount = lat2tile(south, z) - lat2tile(north, z) + 1
            totalTiles += xCount * yCount
        }
        val sizeMB = totalTiles * AVG_TILE_KB / 1024f
        return GtDownloadEstimate(
            tileCount = totalTiles,
            estimatedMB = sizeMB,
            withinCeiling = sizeMB <= CEILING_MB,
            zoomMin = zMin,
            zoomMax = zMax
        )
    }

    /**
     * ESTCALC-2026-11-14 (Fred 11-14 08:35: "make it a callable function so we can easily reuse").
     * THE ONE AREA ESTIMATE. Every caller that wants "how big is this download?" asks THIS, so the
     * preview and the queue can never disagree again.
     *
     * WHY IT EXISTS: the 2.7e UI callers asked [quickEstimate] with NO zoom arguments and inherited the
     * RASTER defaults (DOWNLOAD_ZOOM_MIN=4 .. DOWNLOAD_ZOOM=18), while the queue correctly asked
     * zMin=0, zMax=<that slot's ceiling>. Tile count quadruples per zoom level, so the preview over-counted
     * by ~150-230x: a ~60 km area showed 271,735 tiles / 3,980 MB against a real job of 1,287, and a
     * ~150 km trip was REFUSED by the "Area Too Large" gate while the real job was under 10,000 tiles.
     * (Fred 11-14 08:27 / 08:32.)
     *
     * WHAT IT IS: exactly what GtVectorQueue.enqueueArea:195-196 does, for both slots a vector-map area
     * download actually fetches -- VEC and DEM, zMin=0 to each slot's OWN ceiling, ONE layer each.
     * The raster path multiplied by satLayers (3, for SAT's three layers over the same geography); a
     * vector slot is one layer, so there is no layer multiplier here.
     *
     * CERTIFICATION: this adds NO new seam. Both calls are already certified -- Cert16c compares
     * quickEstimate at z0-14 (key "qe14") and z0-12 (key "qe12") over every box in its set, Droid
     * OLD = NEW and iPad = Droid. Composing two certified results with "+" is a DECISION, not a seam,
     * so it needs no new golden run. See the living master, THE THREE LEVELS.
     *
     * ⛔ THE CEILINGS ARE PARAMETERS ON PURPOSE. [vecZMax] / [demZMax] are passed in, never read from a
     * constant here, because those two numbers ALREADY exist in FOUR places -- VectorTiles.Z_MAX,
     * ElevationTiles.Z_MAX, GtVectorQueue.Z_MAX/DEM_Z_MAX, GtMapSpec.VEC_Z_MAX/DEM_Z_MAX. This must not
     * become a fifth. (Same reason GtTileMath's header makes quickEstimate's zMin/zMax REQUIRED: a
     * default that reads app state is how the raster range leaked into the UI in the first place.)
     * Android callers pass GtVectorQueue.Z_MAX and GtVectorQueue.DEM_Z_MAX.
     *
     * ⚠ TILES ONLY, NO MB. estimatedMB anywhere in this file is computed from AVG_TILE_KB = 15f, a
     * SATELLITE-RASTER figure, and the app now downloads only pbf vector and terrarium png. Until the
     * real per-slot byte sizes are measured off VEC.mbtiles / DEM.mbtiles, a caller that needs MB owns
     * that conversion in platform code: shared carries DATA, the platform carries DISPLAY.
     */
    fun areaTiles(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        vecZMax: Int,
        demZMax: Int,
    ): GtAreaTiles = GtAreaTiles(
        vecTiles = quickEstimate(north, south, east, west, zMin = 0, zMax = vecZMax).tileCount,
        demTiles = quickEstimate(north, south, east, west, zMin = 0, zMax = demZMax).tileCount,
    )

    fun segmentCells(
        north: Double, south: Double, east: Double, west: Double,
        sizeTiles: Int,
        zMax: Int,
        segCeilingTiles: Int = 50_000,
        overlapTiles: Int = 3
    ): List<DoubleArray> {
        val n = maxOf(1, ceil(sizeTiles.toDouble() / segCeilingTiles).toInt())
        val tileDeg = 360.0 / GtMath.pow(2.0, zMax.toDouble())
        val pad = if (n > 1) overlapTiles * tileDeg else 0.0
        val dLat = north - south
        val dLon = east - west
        val cutLat = dLat >= dLon
        val cells = mutableListOf<DoubleArray>()
        for (i in 0 until n) {
            val f0 = i.toDouble() / n
            val f1 = (i + 1).toDouble() / n
            val baseN: Double; val baseS: Double; val baseE: Double; val baseW: Double
            if (cutLat) {
                baseN = north - f0 * dLat; baseS = north - f1 * dLat; baseE = east; baseW = west
            } else {
                baseN = north; baseS = south; baseW = west + f0 * dLon; baseE = west + f1 * dLon
            }
            val cN = baseN + pad
            val cS = baseS - pad
            val cE = baseE + pad
            val cW = baseW - pad
            cells.add(doubleArrayOf(cN, cS, cE, cW))
            seg.i { "cell ${i + 1}/$n N=${GtJava.jd(cN)} S=${GtJava.jd(cS)} E=${GtJava.jd(cE)} W=${GtJava.jd(cW)}" }
        }
        seg.i {
            "SPLIT sizeTiles=$sizeTiles ceiling=$segCeilingTiles segments=$n " +
                "cutAxis=${if (cutLat) "lat" else "lon"} overlapTiles=${if (n > 1) overlapTiles else 0} padDeg=${GtJava.jd(pad)}"
        }
        return cells
    }
}
