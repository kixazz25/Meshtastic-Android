package com.grouptrack.shared.cert

import okio.ByteString.Companion.encodeUtf8

/**
 * CORRIDORCERT-2026-11-17 -- mode corridor17. ⚠ TEST-ONLY (CODE RULE 3).
 *
 * Fred 11-17: "test harness should use existing tracks to calc areas for download queue. and validate
 * areas are correct."
 *
 * ⭐ WHAT CAN ACTUALLY DIVERGE, which is what this compares. squaresFor and queueSquaresNamed are
 * SHARED and Android delegates to them (VectorTiles.squaresFor -> GtVectorQueue), with
 * GtJavaHashMapLong reproducing Java's iteration order so the box order matches. So the square and box
 * math cannot differ between platforms. The WKT PARSE and the LOOKUP can, because those were written
 * fresh on 11-17 (CORRIDORREAD-2026-11-17). [oldSegments] below is 2.7e's parser reproduced verbatim,
 * so one run over real geometry compares the only piece that is new.
 *
 * ⭐ WHY BOTH SIDES RUN IN ONE PLACE instead of a droid report and a mac comparison. 2.7e's parser
 * does not exist on the Mac except as the reproduction below, so a droid leg would only prove that the
 * Droid agrees with itself. The comparison that matters is OLD PARSER vs NEW PARSER over the same
 * rows, and both run here, in one pass -- which is also what Cert16c does with its OLD copy.
 * ⚠ NOT because the datasets might drift. They do not: nothing runs on any device that changes the
 * underlying data (Fred, 11-17). An earlier draft of this note claimed drift as the reason and was
 * wrong; the snapshot on the Mac is the Droid's data, so the comparison is over real current geometry.
 */
object Cert17corridor {

    /**
     * 2.7e SpatialDbManager.parseWktSegments, reproduced verbatim -- THE OLD SIDE.
     *
     * ⛔ DO NOT "TIDY" THIS. Every oddity is load-bearing because it is what Android does today:
     *  - split(" ") with NO empty-filtering, so "lon  lat" (two spaces) yields ["lon", "", "lat"],
     *    field 1 is blank, and THE POINT IS DROPPED. GtGeom.parse filters empties and would keep it.
     *  - fields beyond the first two are IGNORED, so "lon lat ele" is accepted. GtGeom.parse requires
     *    exactly two and would reject the whole geometry.
     *  - a malformed point is SKIPPED, not fatal; a segment left with no points is dropped; an empty
     *    result becomes null.
     *  - removeSuffix(")") rather than requiring a closing paren.
     *  - returns (lat, lon) -- WKT is lon lat, and GtVectorQueue.squaresFor wants (lat, lon).
     */
    fun oldSegments(wkt: String): List<List<Pair<Double, Double>>>? {
        val g = wkt.trim()
        if (g.isBlank()) return null
        val segments: List<String> = when {
            g.startsWith("MULTILINESTRING(") ->
                g.removePrefix("MULTILINESTRING(").removeSuffix(")")
                    .split("),(").map { it.trim('(', ')') }
            g.startsWith("LINESTRING(") -> listOf(g.removePrefix("LINESTRING(").removeSuffix(")"))
            else -> return null
        }
        val out = segments.mapNotNull { seg ->
            val pts = seg.split(",").mapNotNull { p ->
                val parts = p.trim().split(" ")
                if (parts.size < 2) return@mapNotNull null
                val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                Pair(lat, lon)
            }
            if (pts.isEmpty()) null else pts
        }
        return if (out.isEmpty()) null else out
    }

    /** A stable fingerprint of a square set -- order-independent, so only the SET is compared. */
    fun squareDigest(squares: Set<Long>): String =
        if (squares.isEmpty()) "empty"
        else squares.sorted().joinToString(",").encodeUtf8().sha256().hex().take(16) + "/" + squares.size

    /** A stable fingerprint of the AREAS queued -- the boxes, in the order they were enqueued. */
    fun areaDigest(areas: List<String>): String =
        if (areas.isEmpty()) "none" else areas.joinToString(";").encodeUtf8().sha256().hex().take(16) + "/" + areas.size

    /** One queued entry as text: the box, the slot and the label -- what the rider's download covers. */
    fun areaOf(n: Double, s: Double, e: Double, w: Double, slot: String, label: String): String =
        "n=" + fmt(n) + ",s=" + fmt(s) + ",e=" + fmt(e) + ",w=" + fmt(w) + ",slot=" + slot + ",label=" + label

    /** 6 decimals -- ~11 cm, finer than any tile edge, and stable across platforms. */
    private fun fmt(d: Double): String {
        val r = kotlin.math.round(d * 1_000_000.0) / 1_000_000.0
        return r.toString()
    }
}
