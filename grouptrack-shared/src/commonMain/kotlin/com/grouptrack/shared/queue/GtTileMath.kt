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
