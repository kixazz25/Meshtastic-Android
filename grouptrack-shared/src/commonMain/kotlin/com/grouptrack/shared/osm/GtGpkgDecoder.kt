package com.grouptrack.shared.osm

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtGeom
import com.grouptrack.shared.text.GtJava

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmGpkgDecoder (OSM-C2-CATALOG-2026-07-28), PORTED AS IS -- GPKG binary geometry -> the
 * app's WKT (comma NO space, Double.toString per ordinate), its geom_hash and bounds, or a bare point.
 * WHAT CHANGED, and only this:
 *  - java.nio.ByteBuffer -> [Buf]: the same absolute reads (getInt / getDouble at an index, in the blob's own byte order) with the same
 *    bounds rule (index < 0 or index + width > size -> IndexOutOfBoundsException, as ByteBuffer.checkIndex). ⚠ The 0xFF mask on every
 *    byte read is kept (2.7e's "LOAD-BEARING" note: Kotlin's Byte is signed).
 *  - THE HASH TRAP (D13): StringBuilder.append(Double) is Java's Double.toString on Android, NOT on the iPad -> GtJava.jd for every
 *    ordinate, so the WKT -- and the SHA-256 geom_hash over it -- is the same text on both.
 *  - MessageDigest SHA-256 hex -> GtGeom.hash (the one certified hash: SHA-256 of the UTF-8 text, lowercase hex).
 *  - Log -> gtLogger (same tag).
 */
object GtGpkgDecoder {

    private val log = gtLogger("OsmGpkg")

    /** Envelope byte counts by code. Codes 5-7 are reserved => unusable. */
    private val ENVELOPE_BYTES = intArrayOf(0, 32, 48, 48, 64, -1, -1, -1)

    private const val WKB_POINT = 1
    private const val WKB_LINESTRING = 2
    private const val WKB_MULTILINESTRING = 5

    data class LineGeom(val wkt: String, val geomHash: String, val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)
    data class PointGeom(val lon: Double, val lat: Double)

    /** ByteBuffer.wrap(blob).order(o): absolute getInt / getLong / getDouble with ByteBuffer's bounds check. */
    class Buf(private val b: ByteArray, private val little: Boolean) {
        private fun check(i: Int, w: Int) { if (i < 0 || w > b.size - i) throw IndexOutOfBoundsException() }
        fun getInt(i: Int): Int {
            check(i, 4)
            val b0 = b[i].toInt() and 0xFF; val b1 = b[i + 1].toInt() and 0xFF; val b2 = b[i + 2].toInt() and 0xFF; val b3 = b[i + 3].toInt() and 0xFF
            return if (little) (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0 else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
        fun getLong(i: Int): Long {
            check(i, 8)
            var v = 0L
            if (little) { for (k in 7 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF) }
            else { for (k in 0..7) v = (v shl 8) or (b[i + k].toLong() and 0xFF) }
            return v
        }
        fun getDouble(i: Int): Double = Double.fromBits(getLong(i))
    }

    /** Byte offset at which the WKB begins, or -1 if this is not decodable GPKG binary. ⚠ THE MASK IS LOAD-BEARING. */
    fun wkbOffset(blob: ByteArray): Int {
        if (blob.size < 8) return -1
        if ((blob[0].toInt() and 0xFF) != 0x47 || (blob[1].toInt() and 0xFF) != 0x50) return -1
        val flags = blob[3].toInt() and 0xFF
        val code = (flags shr 1) and 0x07
        val env = ENVELOPE_BYTES[code]
        if (env < 0) return -1
        return 8 + env
    }

    private fun bufferFor(blob: ByteArray, off: Int): Buf? {
        val little = when (blob[off].toInt() and 0xFF) {
            1 -> true
            0 -> false
            else -> return null
        }
        return Buf(blob, little)
    }

    private fun geomType(buf: Buf, off: Int): Int = buf.getInt(off + 1) and 0xFF

    /** POINT. The gtype the places/natural/pois layers use. */
    fun decodePoint(blob: ByteArray?): PointGeom? {
        if (blob == null) return null
        val off = wkbOffset(blob)
        if (off < 0 || blob.size < off + 21) return null
        val buf = bufferFor(blob, off) ?: return null
        if (geomType(buf, off) != WKB_POINT) return null
        val lon = buf.getDouble(off + 5)
        val lat = buf.getDouble(off + 5 + 8)
        return PointGeom(lon, lat)
    }

    /** LINESTRING / MULTILINESTRING -> WKT in the app's EXACT format, plus the hash and bounds, computed in the same pass. */
    fun decodeLine(blob: ByteArray?): LineGeom? {
        if (blob == null) return null
        val off = wkbOffset(blob)
        if (off < 0 || blob.size < off + 5) return null
        val buf = bufferFor(blob, off) ?: return null

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        val sb = StringBuilder()

        fun readRing(start: Int, into: StringBuilder): Int {
            val n = buf.getInt(start)
            if (n < 0) return -1
            var p = start + 4
            for (i in 0 until n) {
                if (blob.size < p + 16) return -1
                val x = buf.getDouble(p)
                val y = buf.getDouble(p + 8)
                p += 16
                if (i > 0) into.append(',')
                into.append(GtJava.jd(x)).append(' ').append(GtJava.jd(y))     // 2.7e: append(x) = Double.toString
                if (y < minLat) minLat = y
                if (y > maxLat) maxLat = y
                if (x < minLon) minLon = x
                if (x > maxLon) maxLon = x
            }
            return if (n < 2) -1 else p
        }

        when (geomType(buf, off)) {
            WKB_LINESTRING -> {
                sb.append("LINESTRING(")
                if (readRing(off + 5, sb) < 0) return null
                sb.append(')')
            }
            WKB_MULTILINESTRING -> {
                var p = off + 5
                val nParts = buf.getInt(p)
                p += 4
                if (nParts <= 0) return null
                val parts = StringBuilder()
                var written = 0
                for (i in 0 until nParts) {
                    if (blob.size < p + 5) return null
                    p += 5
                    val partSb = StringBuilder()
                    val next = readRing(p, partSb)
                    if (next < 0) break
                    p = next
                    if (written > 0) parts.append(',')
                    parts.append('(').append(partSb).append(')')
                    written++
                }
                if (written == 0) return null
                sb.append("MULTILINESTRING(").append(parts).append(')')
            }
            else -> return null
        }

        if (minLat > maxLat) return null
        val wkt = sb.toString()
        return LineGeom(wkt, geomHash(wkt), minLat, maxLat, minLon, maxLon)
    }

    /** SpatialDbManager's identity function: SHA-256 of the WKT bytes, hex lowercase (GtGeom.hash, certified). */
    fun geomHash(wkt: String): String = GtGeom.hash(wkt)

    fun withinExtent(p: PointGeom, w: Double, s: Double, e: Double, n: Double): Boolean =
        p.lon >= w && p.lon <= e && p.lat >= s && p.lat <= n

    fun logSelfTest(label: String, p: PointGeom) {
        log.i { "decode self-test $label -> lon=${GtJava.jd(p.lon)} lat=${GtJava.jd(p.lat)}" }
    }
}
