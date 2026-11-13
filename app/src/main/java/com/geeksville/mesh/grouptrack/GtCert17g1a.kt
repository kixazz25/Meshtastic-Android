package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.ConvoyTileCalculatorCertOld
import com.geeksville.mesh.convoy.TileKey
import com.grouptrack.shared.cert.Cert17g1a
import java.io.Writer

/**
 * STEP17G1A-2026-11-13 (2.8 step 17, G1a cert, Droid side) ⚠ TEST-ONLY
 * (CODE RULE 3: remove with GtCertRun before 2.8 is released).
 *
 * OLD = 2.7e's ConvoyTileCalculator as real Android code (ConvoyTileCalculatorCertOld, counted
 * manifest) plus buildTileUrl from 2.7e's ConvoyTileDownloader.
 * NEW = the shared GtTileCalc.
 *
 * ⭐ NO CONTEXT IS USED. Six pure functions, no Android collaborators, no app state — the seam
 * (ConvoyConfig.DOWNLOAD_ZOOM) is a scripted parameter on both sides. ctx is taken only to match the
 * GtCertRun dispatch signature every other mode uses.
 *
 * Lines: G1A <tab> key <tab> SAME|DIFF <tab> value; footer "# G1A done: N SAME, N DIFF in S s".
 */
object GtCert17g1a {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** OLD: 2.7e, as real Android code. */
    private class OldSide : Cert17g1a.Side {
        override fun maxZoomForSource(sourceName: String, downloadZoom: Int): Int =
            ConvoyTileCalculatorCertOld.maxZoomForSource(sourceName, downloadZoom)

        override fun maxZoomForSlot(slotName: String, downloadZoom: Int): Int =
            ConvoyTileCalculatorCertOld.maxZoomForSlot(slotName, downloadZoom)

        override fun zoomRanges(north: Double, south: Double, east: Double, west: Double,
                                zMin: Int, zMax: Int): List<String> =
            ConvoyTileCalculatorCertOld.zoomRanges(north, south, east, west, zMin, zMax)
                .map { "${it[0]}:${it[1]},${it[2]},${it[3]},${it[4]}" }

        // ⛔ built explicitly, NOT via toString() -- see the matching note in Cert17g1a.NewSide.
        override fun calculateTiles(north: Double, south: Double, east: Double, west: Double,
                                    zMin: Int, zMax: Int, sourceName: String, downloadZoom: Int): List<String> =
            ConvoyTileCalculatorCertOld.calculateTiles(north, south, east, west, zMin, zMax, sourceName, downloadZoom)
                .map { "${it.z}/${it.x}/${it.y}" }

        override fun buildTileUrl(z: Int, x: Int, y: Int, sourceUrl: String): String =
            ConvoyTileCalculatorCertOld.buildTileUrl(TileKey(z, x, y), sourceUrl)
    }

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try { Cert17g1a.run(OldSide()) { k, v -> oldMap[k] = v } }
        catch (e: Exception) { w.write("G1A\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try { Cert17g1a.run(Cert17g1a.NewSide) { k, v -> newMap[k] = v } }
        catch (e: Exception) { w.write("G1A\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0
        var diff = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            if (o == n) { same++; w.write("G1A\t$k\tSAME\t${esc(o)}\n") }
            else { diff++; w.write("G1A\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# G1A done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
