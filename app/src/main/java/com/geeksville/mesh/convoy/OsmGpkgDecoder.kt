package com.geeksville.mesh.convoy

import com.grouptrack.shared.osm.GtGpkgDecoder

/**
 * GPKG binary geometry -> the WKT this app stores, or a bare point (OSM-C2-CATALOG-2026-07-28).
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR of the certified shared GtGpkgDecoder (2.8 step 15g, 57cd2a8a7). The public API is 2.7e's,
 * unchanged; the 2.7e file (the 0xFF-mask invariant, the WKT format contract) is in git history (57cd2a8a7) and in the TEST-ONLY
 * OsmGpkgDecoderCertOld.
 */
object OsmGpkgDecoder {
    data class LineGeom(val wkt: String, val geomHash: String, val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)
    data class PointGeom(val lon: Double, val lat: Double)

    fun wkbOffset(blob: ByteArray): Int = GtGpkgDecoder.wkbOffset(blob)
    fun decodePoint(blob: ByteArray?): PointGeom? = GtGpkgDecoder.decodePoint(blob)?.let { PointGeom(it.lon, it.lat) }
    fun decodeLine(blob: ByteArray?): LineGeom? =
        GtGpkgDecoder.decodeLine(blob)?.let { LineGeom(it.wkt, it.geomHash, it.minLat, it.maxLat, it.minLon, it.maxLon) }
    fun geomHash(wkt: String): String = GtGpkgDecoder.geomHash(wkt)
    fun withinExtent(p: PointGeom, w: Double, s: Double, e: Double, n: Double): Boolean =
        GtGpkgDecoder.withinExtent(GtGpkgDecoder.PointGeom(p.lon, p.lat), w, s, e, n)
    fun logSelfTest(label: String, p: PointGeom) = GtGpkgDecoder.logSelfTest(label, GtGpkgDecoder.PointGeom(p.lon, p.lat))
}
