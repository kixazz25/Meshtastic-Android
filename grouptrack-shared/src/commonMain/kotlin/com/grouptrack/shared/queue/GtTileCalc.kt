package com.grouptrack.shared.queue
// STEP17G1A-2026-11-13: the shared port of the rest of 2.7e ConvoyTileCalculator (2.8 step 17, G1a)
// STEP17G1A-TRIM-2026-11-13: trimmed to the live members -- see "WHAT IS NOT HERE" below.

/**
 * GtTileCalc — the tile set arithmetic 2.7e's ConvoyTileCalculator does, PORTED AS IS.
 *
 * ⭐ WHAT 16b ALREADY DID, AND THIS BUILDS ON: [GtTileMath] holds lon2tile, lat2tile, quickEstimate
 * and segmentCells, certified at step 16a as MATHTILE -- 0 DIFF on 1,938,346 values. ⛔ This file
 * does NOT re-implement lon2tile or lat2tile; it calls the certified ones. GtTileMath is the same
 * package, which is why this file imports nothing at all.
 *
 * ⛔ APP STATE BECOMES A PARAMETER, following 16b's rule. 2.7e's defaults read
 * ConvoyConfig.DOWNLOAD_ZOOM, which is a `var` the rider's slider sets. Shared code cannot read app
 * state, so every zoom bound is REQUIRED here and the Android front door passes it. ⚠ That is not a
 * behaviour change: every live call site already passes its own bounds or reads the same var.
 *
 * ⚠ ONE THING DELIBERATELY LEFT ANDROID-SIDE: DownloadEstimate's display strings
 * (`"%.1f MB"`, `"%,d tiles"`). The thousands separator is locale-dependent and number text is a
 * known open item (GtFmt / GtWrite.numberToString, living master backlog). The port returns the
 * NUMBERS; the front door formats them.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 *  WHAT IS NOT HERE, AND WHY — READ BEFORE ADDING ANYTHING BACK
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * ⛔ THIS LIST EXISTS BECAUSE corridorTiles WAS PUT BACK ONCE ALREADY, on 11-13 at 13:20, on the
 * reasoning that its enclosing function had live callers. It does. The code is still dead. A port
 * carries live code only, and "its caller is live" is not the same as "it is reachable".
 *
 * [corridorTiles] + stampPoint — the pre-vector taper/funnel corridor derivation. NOT PORTED.
 *   Fred, 11-13 13:57: "we are a vector map application now ... there is no way to reach or run this
 *   code from vector maps." Its three uses are all dead: ConvoyCorridorWorker (zero callers),
 *   ConvoyCorridorDelete (zero reachable callers since 11-13), and the arm below
 *   `if (GtMapEngine.isVector(context)) { … return }` in ConvoyDownloadQueue.enqueueCorridor:795 —
 *   where isVector can never be false (`cached ?: true`, VECONE-2026-11-01 "the only map").
 *   ⭐⭐ DOWNLOAD BY CORRIDOR IS LIVE AND IS PORTED — JUST NOT HERE. It is the route/track-specific
 *   map download, reached from the GPX import (GtGpxImport:345/486 via the env.platform
 *   .enqueueCorridor seam, GtImportEnv:83), GroupTrackScreen:2536 and MapScreens:178/503. Its METHOD
 *   changed, not its existence: a corridor is now the track's AREA (VECDL-2026-10-31), derived by
 *   VectorTiles.squaresFor / GtVectorQueue — ALREADY CERTIFIED IN Cert16c. So the function lives in
 *   another group; only the old taper is gone. Removal of the dead arms is cleanup C5.
 *
 * estimateSizeMB + isWithinCeiling + AVG_TILE_KB — dead in 2.7e. NOT PORTED.
 *   isWithinCeiling has ZERO uses anywhere in the xref; estimateSizeMB's only use was
 *   isWithinCeiling; AVG_TILE_KB's only use was estimateSizeMB. A dead chain ports as nothing.
 *   (Earlier drafts kept them "to keep the OLD/NEW comparison total" — wrong: there is nothing to
 *   compare when neither side is ever called. Cleanup C3.)
 *
 * tileRelPath — ⛔ NEVER EXISTED IN 2.7e. It was INVENTED in an earlier draft of this port.
 *   The xref reports decl 0: no such function in ConvoyTileCalculator or ConvoyTileDownloader. The
 *   nearest Android thing is ConvoyTileDownloader.tilePath(context, …), which returns a File, has no
 *   callers of its own, and stays platform (it calls mkdirs). ⚠ THIS IS THE WORST OF THE SIX: a
 *   ported member with no OLD counterpart has nothing to certify against, so it passes by never
 *   being exercised. If a shared path helper is ever genuinely needed, port the real tilePath with a
 *   counted substitution — do not reinstate this.
 *
 * ⭐ maxZoomForSource IS KEPT even though nothing outside ConvoyTileCalculator calls it: it is
 * called by [calculateTiles] at ConvoyTileCalculator:272, which is live. An internal caller is a
 * caller. Judging it from a grep that excluded its own declaring file is exactly how it was
 * wrongly marked dead earlier the same day.
 *
 * ⭐ AND IT KEEPS ITS IGNORED sourceName PARAMETER (STEP17G1A-SOURCENAME-2026-11-13). An earlier draft
 * replaced it with downloadZoom; that is not what 16b says. App state becomes an ADDITIONAL parameter —
 * the original signature is still the thing being certified. 2.7e passes a source name and throws it
 * away, so the port does too.
 *
 * ⚠ DOWNLOAD_ZOOM_MIN IS ALREADY SHARED — ConvoyConfig.DOWNLOAD_ZOOM_MIN is a const reading
 * GtConfig.DOWNLOAD_ZOOM_MIN (SWITCH17G2B1-2026-11-12). Only DOWNLOAD_ZOOM, the `var` the slider sets,
 * is app state, so it is the only value the front door has to pass in. 2.7e carries both as parameter
 * DEFAULTS; the port makes them required, which is 16b's precedent and CODE RULE 1's preference.
 */
data class GtTileKey(val z: Int, val x: Int, val y: Int) {
    /** Slippy-map convention. ⚠ MBTiles paths and registry keys are built from this. */
    override fun toString(): String = "$z/$x/$y"

    companion object {
        fun fromString(s: String): GtTileKey {
            val parts = s.split("/")
            return GtTileKey(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }
}

object GtTileCalc {

    /**
     * [V2.6b-NO-ZOOM-CAP] Download requests the FULL zoom range for every source. The per-source
     * max_zoom in map_sources.json is retained as a DISPLAY clamp -- download and display ceilings
     * are intentionally decoupled.
     * ⚠ DEAD in 2.7e: no caller outside its own file (cleanup task C3). Ported to keep the
     * OLD/NEW comparison total.
     */
    // STEP17G1A-SOURCENAME-2026-11-13: [sourceName] IS IGNORED, exactly as in 2.7e
    // (ConvoyTileCalculator:72 -- a [V2.6b-NO-ZOOM-CAP] leftover: the per-source cap went, the parameter
    // stayed). ⛔ DO NOT "TIDY IT AWAY". 16b's rule adds the app-state parameter, it does not displace the
    // original ones, and 2.7e still passes a source name here -- so the signatures must line up argument for
    // argument or the cert cannot compare them. The unused parameter IS the ported behaviour.
    @Suppress("UNUSED_PARAMETER")
    fun maxZoomForSource(sourceName: String, downloadZoom: Int): Int = downloadZoom

    /**
     * ZOOMSLOT-2026-08-10D: THE DOWNLOAD ZOOM RULE. Satellite to z18, rendered maps to z16.
     *
     * Economics, not capability: each level quadruples the tile count. Above z18 satellite imagery
     * in open country is upscaled rather than sharper, and above z16 a topo map has said everything
     * it has to say. So the levels above these cost 4x per step and return nothing.
     *
     * Keyed on the SLOT, not the source, because every download path carries the slot and none of
     * them carry the source. ⛔ An EMPTY slot means NO CAP -- see the note on [corridorTiles].
     */
    fun maxZoomForSlot(slotName: String, downloadZoom: Int): Int = when (slotName.uppercase()) {
        "" -> downloadZoom
        "SAT" -> minOf(18, downloadZoom)
        "DEM" -> minOf(12, downloadZoom)   // DEMSOURCE-2026-10-30: elevation to z12; MapLibre stretches it closer in
        "VEC" -> minOf(14, downloadZoom)   // VECSOURCE-2026-10-30: vector tiles end at z14; MapLibre over-zooms
        else -> minOf(16, downloadZoom)
    }

    /**
     * REMOVEAREA-2026-11-03: each zoom's x / y range of a box -- [z, xMin, xMax, yMin, yMax] --
     * WITHOUT listing its tiles. ⚠ A state-sized box is millions of tiles: listing them ran out of
     * memory. Same zooms and edges as [calculateTiles].
     */
    fun zoomRanges(north: Double, south: Double, east: Double, west: Double, zMin: Int, zMax: Int): List<IntArray> =
        (zMin..zMax).map { z ->
            intArrayOf(z, GtTileMath.lon2tile(west, z), GtTileMath.lon2tile(east, z),
                GtTileMath.lat2tile(north, z), GtTileMath.lat2tile(south, z))
        }

    /** Every tile key for a bounding box across zoom levels. */
    fun calculateTiles(
        north: Double, south: Double, east: Double, west: Double,
        zMin: Int, zMax: Int,
        sourceName: String = "",
        downloadZoom: Int,
    ): List<GtTileKey> {
        // 2.7e: minOf(zMax, maxZoomForSource(sourceName)) -- same call, plus the app-state parameter.
        val effectiveZMax = if (sourceName.isNotEmpty()) minOf(zMax, maxZoomForSource(sourceName, downloadZoom)) else zMax
        val tiles = mutableListOf<GtTileKey>()
        for (z in zMin..effectiveZMax) {
            val xMin = GtTileMath.lon2tile(west, z)
            val xMax = GtTileMath.lon2tile(east, z)
            val yMin = GtTileMath.lat2tile(north, z)  // north = smaller y value
            val yMax = GtTileMath.lat2tile(south, z)  // south = larger y value
            for (x in xMin..xMax) for (y in yMin..yMax) tiles.add(GtTileKey(z, x, y))
        }
        return tiles
    }

    /**
     * SUBDOMAIN-2026-08-02: the tile URL. ⚠ {s} cycles 0-3 by (x + y) mod 4, written so a negative
     * never reaches the modulo.
     * ⭐ From ConvoyTileDownloader -- the only part of it that ports. The FETCH stays platform
     * (OkHttp, retry/backoff, 429/503 handling, the workers).
     */
    fun buildTileUrl(tile: GtTileKey, sourceUrl: String): String =
        sourceUrl
            .replace("{s}", (((tile.x + tile.y) % 4 + 4) % 4).toString())
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
}
