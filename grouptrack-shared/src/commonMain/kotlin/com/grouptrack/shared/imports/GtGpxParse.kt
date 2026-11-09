package com.grouptrack.shared.imports

import com.grouptrack.shared.text.GtJavaParse
import com.grouptrack.shared.text.GtRx
import com.grouptrack.shared.text.GtTime

/**
 * STEP15B-2026-11-09 (2.8 step 15b): the import's GPX / KML text parsing in SHARED Kotlin -- MOVED AS IT IS (owner decision 11-09 18:23:
 * no behaviour change, no fix, no improvement during the move), from 2.7e's ConvoyTrackOps (importExt, trailMarked, extractEarliestTime,
 * sanitizeFilename, the waypoint and route parsers) and SpatialDbManager (parseGpxTrackPointsFull). The comments and decision notes are
 * 2.7e's, moved with the code; each piece carries one STEP15B line saying what changed, and only that:
 *  - java.io.File arguments -> the file NAME (the caller has the file);
 *  - java.time / java.text date parsing -> GtTime (Android 14's rules, certified 15a);
 *  - Kotlin's String.toDoubleOrNull -> GtJavaParse.toDoubleOrNull (Java's grammar, owner rule 4);
 *  - RegexOption.DOT_MATCHES_ALL (not in common Kotlin) -> "[\s\S]" in its place.
 * Nothing here touches a database or a file. The app's ConvoyTrackOps / SpatialDbManager keep their own copies until the 15b switch.
 */
object GtGpxParse {

    // ── from ConvoyTrackOps ─────────────────────────────────────────────

    /** GPXDUPNAME-2026-11-01: the extension with a copy suffix ignored -- "x.gpx (2)" -> "gpx" (Droid 2 11-01: refused as
     *  "Not a GPX file").
     *  STEP15B-2026-11-09: takes the file's name (was the File; 2.7e read f.name). */
    fun importExt(fileName: String): String = fileName.replace(Regex("""\s*\(\d+\)$"""), "").substringAfterLast('.', "").lowercase()

    /** ONEIMPORT-2026-11-04 (Fred 07:59): a <trk> carrying GroupTrack's trail mark is a trail -- written by the converter when the
     *  rider answers YES, and by the rider-trail export. The 11-03 export wrote <type>trail</type>: accepted only in a GroupTrack file.
     *  STEP15B-2026-11-09: moved unchanged. */
    fun trailMarked(trk: String, file: String): Boolean =
        Regex("""<type>\s*grouptrack-trail\s*</type>""").containsMatchIn(trk) ||
            (file.take(600).contains("GroupTrack") && Regex("""<type>\s*trail\s*</type>""").containsMatchIn(trk))

    /**
     * Extract earliest GPX <time> or KML <when> from content as epoch millis.
     * Returns null if no parseable time found.
     * STEP15B-2026-11-09: java.time.Instant.parse, then OffsetDateTime.parse -> GtTime.instantParse, then GtTime.offsetDateTimeParse
     * (= GtTime.isoToEpochMs; Android 14's java.time, certified 15a).
     */
    fun extractEarliestTime(content: String): Long? {
        val patterns = listOf(
            Regex("""<time>([^<]+)</time>"""),
            Regex("""<when>([^<]+)</when>""")
        )
        var earliest: Long? = null
        for (pattern in patterns) {
            for (match in GtRx.findAll(pattern, content)) {
                val t = match.groupValues[1].trim()
                val epochMs = GtTime.instantParse(t)
                    // Some GPX files use timestamps without 'Z' or with offsets — try alternate parsers
                    ?: GtTime.offsetDateTimeParse(t)
                    // skip unparseable entries
                    ?: continue
                if (earliest == null || epochMs < earliest) {
                    earliest = epochMs
                }
            }
        }
        return earliest
    }

    /**
     * Sanitize a track name for use as a filename.
     * STEP15B-2026-11-09: moved unchanged (was private in ConvoyTrackOps; importTrackFile's caller moves in 15e).
     */
    fun sanitizeFilename(name: String): String {
        return name
            .replace("/", "_")
            .replace("\\", "_")
            .replace(Regex("""[^a-zA-Z0-9_\- ]"""), "")
            .trim()
            .ifEmpty { "track" }
    }

    // ── GPX Waypoint/Route Data Classes ────────────────────────────
    // STEP15B-2026-11-09: ConvoyTrackOps.GpxWaypoint / GpxRoute, moved unchanged (the app's become typealiases at the switch).

    data class GpxWaypoint(
        val name: String,
        val lat: Double,
        val lon: Double,
        val type: String,
        val description: String = ""
    )

    data class GpxRoute(
        val name: String,
        val points: List<Pair<Double, Double>>,  // (lon, lat) pairs
        /* CLOSEPLANNER-2026-08-29: a Gaia or onX <desc> IS the narrative --
         * same field, same purpose -- and our own extensions payload brings
         * the recipe home. Defaulted, so existing callers are unaffected. */
        val description: String = ""
    )

    // ── GPX Waypoint Parser ────────────────────────────────────────

    /**
     * Parse <wpt> elements from GPX text.
     * GPX format: <wpt lat="37.1" lon="-113.5"><name>...</name><type>...</type><desc>...</desc></wpt>
     * STEP15B-2026-11-09: toDoubleOrNull -> GtJavaParse.toDoubleOrNull.
     */
    fun parseGpxWaypoints(gpxText: String): List<GpxWaypoint> {
        val results = mutableListOf<GpxWaypoint>()
        val wptPattern = Regex("""<wpt\s+lat="([^"]+)"\s+lon="([^"]+)"[^>]*>([\s\S]*?)</wpt>""")
        val namePattern = Regex("""<name>([^<]*)</name>""")
        val typePattern = Regex("""<type>([^<]*)</type>""")
        val descPattern = Regex("""<desc>([^<]*)</desc>""")

        for (match in GtRx.findAll(wptPattern, gpxText)) {
            val lat = GtJavaParse.toDoubleOrNull(match.groupValues[1]) ?: continue
            val lon = GtJavaParse.toDoubleOrNull(match.groupValues[2]) ?: continue
            val inner = match.groupValues[3]

            val name = namePattern.find(inner)?.groupValues?.get(1)?.trim() ?: "Unnamed Waypoint"
            val rawType = typePattern.find(inner)?.groupValues?.get(1)?.trim()?.lowercase() ?: "other"
            val desc = descPattern.find(inner)?.groupValues?.get(1)?.trim() ?: ""

            // Map common GPX types to our 10 types
            val mappedType = mapWaypointType(rawType)
            results.add(GpxWaypoint(name, lat, lon, mappedType, desc))
        }
        return results
    }

    /**
     * Map GPX waypoint type strings to our 10 standard types.
     * STEP15B-2026-11-09: moved unchanged (was private).
     */
    fun mapWaypointType(rawType: String): String {
        val lower = rawType.lowercase().trim()
        return when {
            lower in listOf("trailhead", "trail head") -> "trailhead"
            lower in listOf("fuel", "gas", "gas station", "petrol") -> "fuel"
            lower in listOf("gate", "barrier") -> "gate"
            lower in listOf("hazard", "danger", "warning", "caution") -> "hazard"
            lower in listOf("scenic", "viewpoint", "scenic viewpoint", "overlook", "vista") -> "scenic"
            lower in listOf("water", "water source", "spring", "creek", "river") -> "water"
            lower in listOf("camp", "campsite", "camping", "campground") -> "camp"
            lower in listOf("parking", "park", "lot") -> "parking"
            lower in listOf("rally", "rally point", "meetup", "meeting") -> "rally"
            lower.length <= 12 && lower in listOf(
                "trailhead", "fuel", "gate", "hazard", "scenic",
                "water", "camp", "parking", "rally", "other"
            ) -> lower
            else -> "other"
        }
    }

    // ── GPX Route Parser ───────────────────────────────────────────

    /**
     * Parse <rte> elements from GPX text.
     * GPX format: <rte><name>...</name><rtept lat="37.1" lon="-113.5">...</rtept></rte>
     */
    /**
     * GPXUNESCAPE-2026-09-08: XML entities back to characters.
     *
     * ⛔ WHY IT IS NEEDED. buildRouteGpxById escapes what it writes -- it has
     * to, the notes payload is full of quotes and its recipe can carry < and >.
     * The readers here are REGEXES, not an XML parser, so they hand back the
     * raw escaped text. The payload then fails to parse as JSON and lands in
     * the narrative headline as gibberish, which is exactly what a real
     * device-to-device transfer produced today.
     *
     * ⚠ &amp; LAST. Decoding it first turns a literal "&amp;quot;" into
     * "&quot;" and then into a quote, corrupting any text that legitimately
     * contained an escaped entity. The order is the correctness.
     *
     * STEP15B-2026-11-09: moved unchanged (was private).
     */
    fun xmlUnescape(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    /** STEP15B-2026-11-09: toDoubleOrNull -> GtJavaParse.toDoubleOrNull; otherwise moved unchanged. */
    fun parseGpxRoutes(gpxText: String): List<GpxRoute> {
        val results = mutableListOf<GpxRoute>()
        // GPXIMPORT-2026-08-22N: was <rte> -- a BARE tag only. A GPX writing
        // <rte xmlns=...> or even "<rte >" matched nothing and imported silently as
        // zero routes, which is indistinguishable from a file with no routes in it.
        // The <wpt> pattern already tolerated attributes; this one did not.
        val rtePattern = Regex("""<rte(?:\s[^>]*)?>([\s\S]*?)</rte>""")
        val namePattern = Regex("""<name>([^<]*)</name>""")
        /* CLOSEPLANNER-2026-08-29: the route parser ignored <desc>, though the
         * waypoint parser has always read it.
         *
         * ⭐ A Gaia or onX route's description IS its narrative — same field,
         * same purpose — so it should arrive as one rather than be discarded.
         * And our own <extensions> payload carries the recipe home. */
        val descPattern = Regex("""<desc>([^<]*)</desc>""")
        val notesPattern = Regex(
            """<grouptrack:notes>([\s\S]*?)</grouptrack:notes>""")
        val rteptPattern = Regex("""<rtept\s+lat="([^"]+)"\s+lon="([^"]+)"""")

        for (match in GtRx.findAll(rtePattern, gpxText)) {
            val inner = match.groupValues[1]
            val name = namePattern.find(inner)?.groupValues?.get(1)?.trim() ?: "Unnamed Route"
            val points = mutableListOf<Pair<Double, Double>>()

            for (ptMatch in GtRx.findAll(rteptPattern, inner)) {
                val lat = GtJavaParse.toDoubleOrNull(ptMatch.groupValues[1]) ?: continue
                val lon = GtJavaParse.toDoubleOrNull(ptMatch.groupValues[2]) ?: continue
                points.add(Pair(lon, lat))  // WKT order: lon, lat
            }

            if (points.size >= 2) {
                // ⚠ the payload wins where present — it is ours and complete;
                // <desc> is one line and may be another app's
                // GPXUNESCAPE-2026-09-08: decode before either is used. The
                // payload is unreadable escaped, and a <desc> from another app
                // can carry &amp; or &apos; just as legitimately.
                val notes = xmlUnescape(
                    notesPattern.find(inner)?.groupValues?.get(1)?.trim() ?: "")
                val desc = xmlUnescape(
                    descPattern.find(inner)?.groupValues?.get(1)?.trim() ?: "")
                results.add(GpxRoute(name, points, notes.ifBlank { desc }))
            }
        }
        return results
    }

    // ── from SpatialDbManager: TRACK PROPERTIES UPDATER's GPX read ──────
    // STEP15B-2026-11-09: only the PARSE moves now (no database); updateTrackPropertiesForHash itself moves in 15d.

    /** One trackpoint with optional elevation (meters) + time (epoch millis). */
    data class TrackPtFull(val lat: Double, val lon: Double, val eleM: Double?, val timeMs: Long?)

    /** Richer GPX parse: lat, lon, <ele> (m), <time> (ISO 8601 'yyyy-MM-dd'T'HH:mm:ss'Z'').
     *  Existing parseGpxTrackPoints (lon,lat only, used for WKT/hash) is untouched.
     *  STEP15B-2026-11-09: the four SimpleDateFormat patterns (UTC, non-lenient) -> GtTime.gpxTime (Android 14's rules, certified 15a);
     *  RegexOption.DOT_MATCHES_ALL "(.*?)" -> "([\s\S]*?)"; toDoubleOrNull -> GtJavaParse.toDoubleOrNull.
     *  ⚠ 2.7e ORDER KEPT (owner 11-09: as it is): every paired <trkpt>...</trkpt> first, then every self-closing one; a self-closing
     *  trkpt before a later paired one is read by the first pattern up to that </trkpt> (INVENTORY 3.3). */
    fun parseGpxTrackPointsFull(gpxText: String): List<TrackPtFull> {
        val out = mutableListOf<TrackPtFull>()
        val ptRegex = Regex("""<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"[^>]*?>([\s\S]*?)</trkpt>""")
        val latLonOnly = Regex("""<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"[^>]*/>""")
        val eleRegex = Regex("""<ele>([^<]+)</ele>""")
        val timeRegex = Regex("""<time>([^<]+)</time>""")
        // fractional seconds + Z (Gaia/onX exports), whole seconds + Z (recorder), whole seconds + numeric offset (+00:00),
        // fractional + numeric offset -- in that order, as 2.7e's timeFormats list
        fun parseTime(s: String): Long? = GtTime.gpxTime(s)

        // RXFIX-2026-11-09 B: every match of ptRegex ends with "</trkpt>", so none can lie past the LAST one: the search runs only up to it,
        // and not at all when there is none. Same matches (JVM proof 11-09, 0 DIFF). 2.7e searches the whole text and hunts to the end of
        // the file once per self-closing <trkpt .../> (HillbillyHills_trails: 19,555 of them, no </trkpt>): 80 s on a JVM, 498 s on Droid 1.
        val lastClose = gpxText.lastIndexOf("</trkpt>")
        val ptText = if (lastClose < 0) "" else gpxText.substring(0, lastClose + "</trkpt>".length)
        for (m in GtRx.findAll(ptRegex, ptText)) {
            val lat = GtJavaParse.toDoubleOrNull(m.groupValues[1]) ?: continue
            val lon = GtJavaParse.toDoubleOrNull(m.groupValues[2]) ?: continue
            val inner = m.groupValues[3]
            val ele = eleRegex.find(inner)?.groupValues?.get(1)?.trim()?.let { GtJavaParse.toDoubleOrNull(it) }
            val tMs = timeRegex.find(inner)?.groupValues?.get(1)?.let { parseTime(it) }
            out.add(TrackPtFull(lat, lon, ele, tMs))
        }
        for (m in GtRx.findAll(latLonOnly, gpxText)) {
            val lat = GtJavaParse.toDoubleOrNull(m.groupValues[1]) ?: continue
            val lon = GtJavaParse.toDoubleOrNull(m.groupValues[2]) ?: continue
            out.add(TrackPtFull(lat, lon, null, null))
        }
        return out
    }
}
