package com.grouptrack.shared.cert

import com.grouptrack.shared.queue.GtTileCalc
import com.grouptrack.shared.queue.GtTileKey

/**
 * Cert17g1a — the ONE runner for step 17 group G1a (GtTileCalc).
 * STEP17G1A-CERT-2026-11-13
 *
 * ⭐ G1a IS THE SIMPLEST CERT IN STEP 17, and deliberately so: the inventory picked it to "prove the
 * group's pipe". Six PURE functions, zero Android ties, no collaborator seam. So none of G2f's four
 * harness rules apply here — there is no clock to script, no listFiles() order to fix, no truncated
 * log sample, no generated file text. Every input is scripted and every output is a value.
 *
 * ⛔ THE ONE SEAM: ConvoyConfig.DOWNLOAD_ZOOM. 2.7e reads it directly; it is a `var` the rider's
 * slider sets. Both sides take it as a parameter and the runner passes ONE scripted value, so a
 * difference cannot come from the slider having moved. See the CertOld manifest, item 2.
 *
 * ══════════════════════════════════════════════════════════════════
 *  WHAT IS COMPARED — and what is deliberately not
 * ══════════════════════════════════════════════════════════════════
 *  COMPARED: every returned value, in full. These are pure functions, so the return value IS the
 *  decision — there are no observations to separate out.
 *
 *  ⭐ ORDER IS COMPARED, NOT SORTED AWAY. This is the opposite of G2f's rule 3, and for a reason:
 *  2.7e builds `tiles` with `mutableListOf` and nested `for (z) for (x) for (y)` loops, so the order
 *  is fully determined by the source, not by any hash or filesystem. It is part of the behaviour and
 *  a reordering would be a real defect. G2f sorted because ITS collections came from set iteration
 *  and listFiles(); nothing here does.
 *
 *  NOT COMPARED: nothing. There is no logging, no timing and no I/O on either side.
 *
 * ⚠ lon2tile / lat2tile ARE NOT UNDER TEST. Certified at step 16a as MATHTILE — 0 DIFF across
 * 1,938,346 values. OLD calls 2.7e's own; NEW calls GtTileMath's. They are shared ground, which is
 * what makes a DIFF here attributable to G1a's members alone.
 *
 * ⚠ MERCATOR EDGES ARE INCLUDED ON PURPOSE. At |lat| near 90 the slippy formula runs away
 * (tan(rad) → ∞) and 2.7e clamps nothing. Both sides must produce the SAME runaway answer; that is
 * faithfulness, not correctness, and a defect both sides reproduce identically is a PASS.
 */
object Cert17g1a {

    /** The five members under test, in VALUE terms only — never a platform key type. */
    interface Side {
        fun maxZoomForSource(sourceName: String, downloadZoom: Int): Int
        fun maxZoomForSlot(slotName: String, downloadZoom: Int): Int
        /** flattened "z:xMin,xMax,yMin,yMax" per zoom, so IntArray identity never matters */
        fun zoomRanges(north: Double, south: Double, east: Double, west: Double, zMin: Int, zMax: Int): List<String>
        /** "z/x/y" in the order produced — see the note on order above */
        fun calculateTiles(north: Double, south: Double, east: Double, west: Double,
                           zMin: Int, zMax: Int, sourceName: String, downloadZoom: Int): List<String>
        fun buildTileUrl(z: Int, x: Int, y: Int, sourceUrl: String): String
    }

    // ---- scripted inputs ------------------------------------------------
    /** ⛔ The slider's value is scripted, never read. 18 is 2.7e's default; the sweep covers the rest. */
    private val ZOOMS = listOf(0, 1, 4, 10, 12, 14, 16, 17, 18, 19, 22)

    /** Case matters: 2.7e calls slotName.uppercase(), so "sat" must behave as "SAT". */
    private val SLOTS = listOf("", "SAT", "DEM", "VEC", "TOPO", "sat", "Vec", "dem", "HYB", "unknown", "SAT ")

    /** maxZoomForSource IGNORES this. Varied anyway, to prove it is ignored identically. */
    private val SOURCES = listOf("", "esri", "ESRI_World_Imagery", "topo+", "unknown source")

    /**
     * Boxes kept DELIBERATELY TINY. calculateTiles enumerates every tile: a state-sized box is
     * millions, which is the whole reason zoomRanges exists (REMOVEAREA-2026-11-03). These stay
     * bounded so the comparison is a value comparison and not a memory test.
     * (name, north, south, east, west)
     */
    private val BOXES = listOf(
        // ordinary Utah-ish ground, the app's real case
        listOf("utah", 37.2800, 37.2700, -112.9800, -112.9900),
        // equator and prime meridian — the formula's zero points
        listOf("equator", 0.0050, -0.0050, 0.0050, -0.0050),
        // southern hemisphere, to catch a sign error that cancels in the north
        listOf("south", -33.8600, -33.8700, 151.2100, 151.2000),
        // the antimeridian's east edge — lon2tile at +180
        listOf("dateline", 1.0050, 0.9950, 179.9950, 179.9850),
        // high latitude, where cos(lat) is small and the y formula stretches
        listOf("arctic", 78.2300, 78.2200, 15.6300, 15.6200),
        // ⚠ MERCATOR EDGE: tan(rad) runs away. Both sides must run away identically.
        listOf("edge_north", 89.9990, 89.9980, 0.0010, 0.0000),
        listOf("edge_south", -89.9980, -89.9990, 0.0010, 0.0000),
        // a box whose west > east (inverted) — 2.7e does not validate, so the loop yields nothing
        listOf("inverted", 37.2700, 37.2800, -112.9900, -112.9800),
    )

    /** URL templates, including the {s} subdomain cycle that SUBDOMAIN-2026-08-02 fixed. */
    private val URLS = listOf(
        "https://{s}.tile.example.org/{z}/{x}/{y}.png",
        "https://tile.example.org/{z}/{x}/{y}@2x.jpg",
        "https://example.org/no/placeholders.png",
        "{s}{s}/{z}{z}/{x}-{y}",
    )

    /** Tiles chosen so (x + y) hits every residue AND goes negative — the bug {s} was written for. */
    private val URL_TILES = listOf(
        listOf(10, 0, 0), listOf(10, 1, 0), listOf(10, 1, 1), listOf(10, 2, 1),
        listOf(12, 7, 5), listOf(14, 1023, 1024),
        listOf(0, -1, 0), listOf(0, -2, -1), listOf(5, -3, -4),
    )

    /** Sink form, matching every other 17-group runner (GtCert17g2g et al): (key, value). */
    fun run(side: Side, sink: (String, String) -> Unit) {

        // 1. maxZoomForSlot — the download zoom rule (ZOOMSLOT-2026-08-10D)
        for (slot in SLOTS) {
            val v = ZOOMS.joinToString(",") { side.maxZoomForSlot(slot, it).toString() }
            sink("MAXSLOT|${esc(slot)}", v)
        }

        // 2. maxZoomForSource — must ignore sourceName identically on both sides
        for (src in SOURCES) {
            val v = ZOOMS.joinToString(",") { side.maxZoomForSource(src, it).toString() }
            sink("MAXSRC|${esc(src)}", v)
        }

        // 3. zoomRanges — ranges without enumerating tiles
        for (b in BOXES) {
            val name = b[0] as String
            val n = b[1] as Double; val s = b[2] as Double; val e = b[3] as Double; val w = b[4] as Double
            for (zr in listOf(listOf(10, 12), listOf(0, 4), listOf(14, 14), listOf(12, 10))) {
                val v = side.zoomRanges(n, s, e, w, zr[0], zr[1]).joinToString(";")
                sink("ZRANGE|$name|z${zr[0]}-${zr[1]}", v)
            }
        }

        // 4. calculateTiles — the enumeration, in order
        for (b in BOXES) {
            val name = b[0] as String
            val n = b[1] as Double; val s = b[2] as Double; val e = b[3] as Double; val w = b[4] as Double
            for (zr in listOf(listOf(10, 12), listOf(14, 14), listOf(12, 10))) {
                // sourceName EMPTY vs NON-EMPTY is the branch that reaches maxZoomForSource
                for (src in listOf("", "esri")) {
                    for (dz in listOf(11, 18)) {
                        val keys = side.calculateTiles(n, s, e, w, zr[0], zr[1], src, dz)
                        sink("CALC|$name|z${zr[0]}-${zr[1]}|src=${esc(src)}|dz=$dz",
                             "${keys.size}|${keys.joinToString(",")}")
                    }
                }
            }
        }

        // 5. buildTileUrl — the {s} cycle and the negative-safe modulo
        for (u in URLS.indices) {
            val v = URL_TILES.joinToString(";") { t ->
                side.buildTileUrl(t[0], t[1], t[2], URLS[u])
            }
            sink("URL|$u", v)
        }

    }

    /** Keys must survive being printed in a log line: make empties and spaces visible. */
    private fun esc(s: String): String = if (s.isEmpty()) "(empty)" else s.replace(" ", "\\s")

    /** The NEW side — the shared port. */
    object NewSide : Side {
        override fun maxZoomForSource(sourceName: String, downloadZoom: Int): Int =
            GtTileCalc.maxZoomForSource(sourceName, downloadZoom)

        override fun maxZoomForSlot(slotName: String, downloadZoom: Int): Int =
            GtTileCalc.maxZoomForSlot(slotName, downloadZoom)

        override fun zoomRanges(north: Double, south: Double, east: Double, west: Double,
                                zMin: Int, zMax: Int): List<String> =
            GtTileCalc.zoomRanges(north, south, east, west, zMin, zMax)
                .map { "${it[0]}:${it[1]},${it[2]},${it[3]},${it[4]}" }

        override fun calculateTiles(north: Double, south: Double, east: Double, west: Double,
                                    zMin: Int, zMax: Int, sourceName: String, downloadZoom: Int): List<String> =
            // ⛔ built explicitly, NOT via toString(). Both TileKey and GtTileKey happen to override it
            // to "$z/$x/$y", but a cert that depends on two independent overrides staying in step is
            // one silent edit away from comparing nothing.
            GtTileCalc.calculateTiles(north, south, east, west, zMin, zMax, sourceName, downloadZoom)
                .map { "${it.z}/${it.x}/${it.y}" }

        override fun buildTileUrl(z: Int, x: Int, y: Int, sourceUrl: String): String =
            GtTileCalc.buildTileUrl(GtTileKey(z, x, y), sourceUrl)
    }
}
