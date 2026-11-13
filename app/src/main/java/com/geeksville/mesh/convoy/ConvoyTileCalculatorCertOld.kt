package com.geeksville.mesh.convoy

/**
 * ConvoyTileCalculatorCertOld — 2.7e's ConvoyTileCalculator, AS IT IS, for the G1a certification.
 * STEP17G1A-CERTOLD-2026-11-13
 *
 * ⛔ DO NOT EDIT TO MAKE A TEST PASS. This is the OLD side of the comparison. If OLD and NEW differ,
 * the port is wrong, not this file. Correcting a difference here destroys the only evidence the
 * certification produces.
 *
 * ⭐ BASELINE: `git diff 2.7e -- ConvoyTileCalculator.kt` is EMPTY, so the source copied here is
 * byte-identical to the field-lock version. Nothing had to be un-done first (unlike G2f, where two
 * TEST-ONLY guards had to come out).
 *
 * ══════════════════════════════════════════════════════════════════════════════
 *  THE SUBSTITUTION MANIFEST — COUNTED. Reverse these and this file must diff
 *  CLEAN against `git show 2.7e:app/.../ConvoyTileCalculator.kt`.
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  1. object name                                                              ×1
 *       ConvoyTileCalculator            -> ConvoyTileCalculatorCertOld
 *
 *  2. APP STATE BECOMES A PARAMETER (the 16b rule, and the ONLY semantic change) ×6
 *       ConvoyConfig.DOWNLOAD_ZOOM      -> downloadZoom
 *     at 2.7e line 72 (the EXPRESSION BODY of maxZoomForSource) and lines 91, 92, 93, 94, 95.
 *     ⚠ CORRECTED (CERTOLDMANIFEST-2026-11-13): first written as ×8, wrongly including the zMax
 *     parameter defaults at :260 and :269 -- those are item 3, not item 2. An expression body and a
 *     parameter default both read "= ConvoyConfig.DOWNLOAD_ZOOM"; only the latter is a default.
 *     ⛔ WHY: DOWNLOAD_ZOOM is a `var` the rider's slider sets (ConvoyConfig:91, default 18). The
 *     shared port cannot read app state, so NEW takes it as a parameter. OLD must take it the same
 *     way or the two sides are reading different values and the comparison means nothing.
 *     ⚠ This is a SEAM, not a behaviour change: the harness passes ONE scripted value to both sides.
 *
 *  2b. THE NEW PARAMETER IS THREADED TO AN INTERNAL CALL SITE                  ×1
 *       maxZoomForSource(sourceName)  ->  maxZoomForSource(sourceName, downloadZoom)
 *     in calculateTiles (2.7e :272).
 *     ⛔ THIS IS WHY THE FILE HOLDS SEVEN `downloadZoom` READS AND ITEM 2 COUNTS SIX. Six are
 *     substituted ConvoyConfig reads; this one is the seam being passed along. It is NOT a behaviour
 *     change -- 2.7e's maxZoomForSource ignores its argument and returns DOWNLOAD_ZOOM, so both
 *     sides compute the same thing -- but it IS a textual difference from 2.7e and every such
 *     difference has to be named here or the manifest cannot be reversed.
 *     ⭐ TALLY, so the counts can be checked against the file without arithmetic:
 *       `downloadZoom` occurrences = 3 parameter declarations + 6 substituted reads (item 2)
 *                                  + 1 threaded argument (this item) = 10.
 *
 *  3. PARAMETER DEFAULTS BECOME REQUIRED                                       ×4
 *       zMin: Int = ConvoyConfig.DOWNLOAD_ZOOM_MIN  -> zMin: Int
 *       zMax: Int = ConvoyConfig.DOWNLOAD_ZOOM      -> zMax: Int
 *     at 2.7e :260 (zoomRanges, both) and :268-269 (calculateTiles, both).
 *     ⚠ CORRECTED: first written as ×2, counting only the _MIN pair.
 *     ⭐ NOTE: DOWNLOAD_ZOOM_MIN is ALREADY SHARED — ConvoyConfig.DOWNLOAD_ZOOM_MIN is a const
 *     reading GtConfig.DOWNLOAD_ZOOM_MIN (SWITCH17G2B1-2026-11-12). It is made required only so the
 *     two signatures line up; its VALUE is the same object on both sides.
 *
 *  4. the certified math is CALLED, not copied                                 ×8
 *       lon2tile(...)  -> ConvoyTileCalculator.lon2tile(...)   ×4
 *       lat2tile(...)  -> ConvoyTileCalculator.lat2tile(...)   ×4
 *     at 2.7e :261 (zoomRanges — 2 lon + 2 lat) and :272-275 (calculateTiles — 2 lon + 2 lat).
 *     ⚠ CORRECTED: the header said ×6 while its own detail line said 4 + 4. The file was right; the
 *     count was not.
 *     ⛔ WHY NOT COPIED: lon2tile/lat2tile were certified at step 16a as MATHTILE — 0 DIFF across
 *     1,938,346 values — and NEW calls the certified GtTileMath versions. Re-copying them here would
 *     put uncertified duplicates on the OLD side and re-test something already closed. OLD calls the
 *     REAL 2.7e functions, which is what makes this a test of G1a's members and nothing else.
 *
 *  5. buildTileUrl comes from ConvoyTileDownloader, not this file              ×1
 *     2.7e ConvoyTileDownloader:54. It is G1a's piece of that file (the FETCH stays platform), so it
 *     is certified here beside the calculator members. Copied verbatim but for the object name.
 *
 * ⚠ NOT PRESENT, because NEW does not port them (and a dead member certifies nothing — see the
 * living master's Q5, "wrongly kept"): corridorTiles, stampPoint, estimateSizeMB, isWithinCeiling,
 * AVG_TILE_KB. Also absent: quickEstimate and segmentCells (certified at 16b in GtTileMath), and
 * tileRelPath (which never existed in 2.7e).
 *
 * ⭐ TileKey IS NOT SUBSTITUTED. 2.7e declares it in ConvoyTileCalculator.kt:339, same package, so
 * this file uses the real one. The NEW side's GtTileKey is compared to it by VALUE (z/x/y), never by
 * type.
 */
object ConvoyTileCalculatorCertOld {

    /**
     * 2.7e:72. [V2.6b-NO-ZOOM-CAP] — takes [sourceName] AND IGNORES IT. The per-source cap was
     * removed and the parameter was left behind. ⛔ The ignored parameter is the ported behaviour;
     * it is reproduced here deliberately.
     */
    @Suppress("UNUSED_PARAMETER")
    fun maxZoomForSource(sourceName: String, downloadZoom: Int): Int = downloadZoom

    /** 2.7e:90. ZOOMSLOT-2026-08-10D. An empty slot means NO CAP. */
    fun maxZoomForSlot(slotName: String, downloadZoom: Int): Int = when (slotName.uppercase()) {
        "" -> downloadZoom
        "SAT" -> minOf(18, downloadZoom)
        "DEM" -> minOf(12, downloadZoom)
        "VEC" -> minOf(14, downloadZoom)
        else -> minOf(16, downloadZoom)
    }

    /** 2.7e:259. REMOVEAREA-2026-11-03: each zoom's x/y range, WITHOUT listing the tiles. */
    fun zoomRanges(north: Double, south: Double, east: Double, west: Double,
                   zMin: Int, zMax: Int): List<IntArray> =
        (zMin..zMax).map { z ->
            intArrayOf(z,
                ConvoyTileCalculator.lon2tile(west, z), ConvoyTileCalculator.lon2tile(east, z),
                ConvoyTileCalculator.lat2tile(north, z), ConvoyTileCalculator.lat2tile(south, z))
        }

    /** 2.7e:263. */
    fun calculateTiles(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        zMin: Int,
        zMax: Int,
        sourceName: String = "",
        downloadZoom: Int,
    ): List<TileKey> {
        val effectiveZMax = if (sourceName.isNotEmpty()) minOf(zMax, maxZoomForSource(sourceName, downloadZoom)) else zMax
        val tiles = mutableListOf<TileKey>()
        for (z in zMin..effectiveZMax) {
            val xMin = ConvoyTileCalculator.lon2tile(west, z)
            val xMax = ConvoyTileCalculator.lon2tile(east, z)
            val yMin = ConvoyTileCalculator.lat2tile(north, z)  // north = smaller y value
            val yMax = ConvoyTileCalculator.lat2tile(south, z)  // south = larger y value
            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    tiles.add(TileKey(z, x, y))
                }
            }
        }
        return tiles
    }

    /** 2.7e ConvoyTileDownloader:54. SUBDOMAIN-2026-08-02: {s} cycles 0-3 by (x + y) mod 4. */
    fun buildTileUrl(tile: TileKey, sourceUrl: String): String {
        return sourceUrl
            .replace("{s}", (((tile.x + tile.y) % 4 + 4) % 4).toString())
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
    }
}
