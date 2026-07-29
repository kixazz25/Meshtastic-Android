package com.geeksville.mesh.convoy

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * GPKG binary geometry -> the WKT this app already stores, or a bare point.
 *
 * OSM-C2-CATALOG-2026-07-28
 *
 * Ported from osm_gpkg_subset_v2_2026-07-27.py, which was itself written
 * because an earlier version WOULD HAVE LIED: it emitted WKT as
 * "lon lat, lon lat" at fixed precision, while the app writes comma with NO
 * space and Kotlin's shortest-round-trip Double.toString()
 * (TrailImporter.coordRingToWkt). A hash over a string differing by one space
 * is a completely different hash, so every feature would have reported as new.
 *
 * ⭐ ONE THING GETS SIMPLER IN KOTLIN. The Python had to imitate
 * Double.toString() and carried a warning about exponent notation near the
 * prime meridian. Here we ARE the authority -- whatever Double.toString()
 * emits is by definition what the app emits, so that edge case does not get
 * handled, it stops existing.
 *
 * ⚠⚠ AND ONE THING GETS HARDER, WHICH IS THE WHOLE REVIEW SURFACE OF THIS
 * FILE. Python's blob[3] is an unsigned 0-255 int. Kotlin's Byte is SIGNED.
 * Transcribing `(blob[3] >> 1) & 0x07` directly SIGN-EXTENDS for any byte
 * >= 0x80 and returns the wrong envelope size -- which starts the WKB read at
 * the wrong offset and yields PLAUSIBLE GARBAGE COORDINATES, not an
 * exception. Every single byte read below masks with 0xFF first. If you
 * change anything in this file, check that invariant before anything else.
 *
 * VERIFIED against arizona.gpkg 2026-07-28: Phoenix decoded 0.05 km from its
 * known position (a centroid-definition difference, not an error), and
 * 1,581 of 1,581 places landed inside the layer's declared extent.
 */
object OsmGpkgDecoder {

    private const val TAG = "OsmGpkg"

    /** Envelope byte counts by code. Codes 5-7 are reserved => unusable. */
    private val ENVELOPE_BYTES = intArrayOf(0, 32, 48, 48, 64, -1, -1, -1)

    private const val WKB_POINT = 1
    private const val WKB_LINESTRING = 2
    private const val WKB_MULTILINESTRING = 5

    data class LineGeom(
        val wkt: String,
        val geomHash: String,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    data class PointGeom(val lon: Double, val lat: Double)

    /**
     * Byte offset at which the WKB begins, or -1 if this is not decodable
     * GPKG binary.
     *
     * ⚠ THE MASK IS LOAD-BEARING -- see the class KDoc.
     */
    fun wkbOffset(blob: ByteArray): Int {
        if (blob.size < 8) return -1
        // "GP" magic
        if ((blob[0].toInt() and 0xFF) != 0x47 || (blob[1].toInt() and 0xFF) != 0x50) return -1
        val flags = blob[3].toInt() and 0xFF          // <-- mask, NOT blob[3].toInt()
        val code = (flags shr 1) and 0x07
        val env = ENVELOPE_BYTES[code]
        if (env < 0) return -1
        return 8 + env
    }

    private fun bufferFor(blob: ByteArray, off: Int): ByteBuffer? {
        val order = when (blob[off].toInt() and 0xFF) {   // <-- mask
            1 -> ByteOrder.LITTLE_ENDIAN
            0 -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        return ByteBuffer.wrap(blob).order(order)
    }

    /**
     * Geometry type, or -1. Masking to 0xFF matches the validated Python.
     * Z/M-flagged types (1001, 2001, ...) fall outside the 2D values we
     * accept and are simply rejected -- Geofabrik extracts are 2D.
     */
    private fun geomType(buf: ByteBuffer, off: Int): Int =
        buf.getInt(off + 1) and 0xFF

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

    /**
     * LINESTRING / MULTILINESTRING -> WKT in the app's EXACT format, plus the
     * hash and bounds, computed in the same pass.
     *
     * Format contract, and it is a contract because geom_hash depends on it:
     *   - comma between points, NO space
     *   - space between lon and lat
     *   - Kotlin Double.toString() for every ordinate
     */
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
                into.append(x).append(' ').append(y)     // Double.toString(), comma no space
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
                    p += 5                                // per-part byte order + type
                    val partSb = StringBuilder()
                    val next = readRing(p, partSb)
                    if (next < 0) {
                        // A part with < 2 points is skipped, matching the
                        // validated Python. But we cannot resynchronise
                        // without its length, so stop here.
                        break
                    }
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

    /**
     * SpatialDbManager's identity function: SHA-256 of the WKT bytes, hex
     * lowercase. Must stay byte-identical or cross-source dedup stops
     * recognising its own rows.
     */
    fun geomHash(wkt: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(wkt.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF                   // <-- mask here too
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * The abort condition C2 enforces before it touches trails.
     *
     * Points decode first because they are trivial after the header -- two
     * doubles, no ring count -- so a bad envelope offset shows up immediately
     * against a town you can recognise, instead of silently poisoning 87k
     * linestrings. On the Arizona run this cost nothing: 1,581 of 1,581 were
     * inside. Any value outside means the header math is wrong, and writing
     * that into a recovery point is the worst outcome available, because in
     * this pipeline EXISTENCE MEANS COMPLETE.
     */
    fun withinExtent(p: PointGeom, w: Double, s: Double, e: Double, n: Double): Boolean =
        p.lon >= w && p.lon <= e && p.lat >= s && p.lat <= n

    fun logSelfTest(label: String, p: PointGeom) {
        Log.i(TAG, "decode self-test $label -> lon=${p.lon} lat=${p.lat}")
    }
}
