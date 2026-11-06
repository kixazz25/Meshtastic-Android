package com.grouptrack.shared.gpx

/**
 * GtGpx -- the GPX that GroupTrack writes for one waypoint, route or trail (Export all, an item's export, the ride package).
 * GTBATCH2A-2026-11-06 (2.8): MOVED to shared Kotlin from SpatialDbManager.build{Waypoint,Route,Trail}GpxById -- the text is built
 * exactly as 2.7e built it (same header, same escaping, same order, byte for byte). The database reads stay with the platform for now
 * (they move with SpatialDbManager's reads, batch 2b); the caller hands over the stored values.
 * ⚠ KNOWN 2.7e DEFECT, KEPT ON PURPOSE (so the move certifies byte-identical): a route with notes carries its <extensions> notes block
 *   TWICE -- before and after the points. To be fixed as its own item after certification.
 */
object GtGpx {
    const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"GroupTrack\"\n" +
        "     xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
        "     xmlns:grouptrack=\"https://grouptrack.app/gpx/1\">\n"
    const val FOOTER = "</gpx>"

    fun xmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** One waypoint. [geom] = the stored POINT(lon lat). Null = not a usable point (as 2.7e). */
    fun waypoint(name: String, type: String, geom: String, desc: String): String? {
        val coords = geom.removePrefix("POINT(").removeSuffix(")").trim().split(" ")
        if (coords.size < 2) return null
        val sb = StringBuilder()
        sb.append(HEADER)
        sb.append("  <wpt lat=\"").append(coords[1]).append("\" lon=\"").append(coords[0]).append("\">\n")
        sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
        if (type.isNotEmpty()) sb.append("    <type>").append(xmlEscape(type)).append("</type>\n")
        if (desc.isNotEmpty()) sb.append("    <desc>").append(xmlEscape(desc)).append("</desc>\n")
        sb.append("  </wpt>\n")
        sb.append(FOOTER)
        return sb.toString()
    }

    /** One route. [geom] = the stored LINESTRING; [headline] = the narrative headline ("" = none) for <desc>;
     *  [notesJson] = the route's notes exactly as the platform serialises them (null = no notes -> no extensions block).
     *  CODE RULE 1: notesJson is nullable because most routes have no notes -- 2.7e writes no block for them. */
    fun route(name: String, geom: String, headline: String, notesJson: String?): String {
        val points = lineLatLon(geom)
        val sb = StringBuilder()
        sb.append(HEADER)
        sb.append("  <rte>\n")
        sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
        if (headline.isNotEmpty()) sb.append("    <desc>").append(xmlEscape(headline)).append("</desc>\n")
        if (notesJson != null) notes(sb, notesJson)   // CLOSEPLANNER-2026-08-29 / ROUTEEXTENSIONS-2026-09-08
        var idx = 1
        for ((lat, lon) in points) {
            sb.append("    <rtept lat=\"").append(lat).append("\" lon=\"").append(lon).append("\">\n")
            sb.append("      <name>Pt ").append(idx).append("</name>\n")
            sb.append("    </rtept>\n")
            idx++
        }
        if (notesJson != null) notes(sb, notesJson)   // ⚠ the 2.7e duplicate (see the class note)
        sb.append("  </rte>\n")
        sb.append(FOOTER)
        return sb.toString()
    }

    /** One trail as a single-segment track. [geom] = the stored LINESTRING. */
    fun trail(name: String, geom: String): String {
        val sb = StringBuilder()
        sb.append(HEADER)
        sb.append("  <trk>\n")
        sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for ((lat, lon) in lineLatLon(geom)) sb.append("      <trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"/>\n")
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append(FOOTER)
        return sb.toString()
    }

    private fun notes(sb: StringBuilder, notesJson: String) {
        sb.append("    <extensions>\n")
        sb.append("      <grouptrack:notes>").append(xmlEscape(notesJson)).append("</grouptrack:notes>\n")
        sb.append("    </extensions>\n")
    }

    /** LINESTRING(lon lat, ...) -> (lat, lon) text pairs, as stored (no number reformatting). */
    private fun lineLatLon(geom: String): List<Pair<String, String>> =
        geom.removePrefix("LINESTRING(").removeSuffix(")").split(",").mapNotNull { pt ->
            val parts = pt.trim().split(" ")
            if (parts.size >= 2) Pair(parts[1], parts[0]) else null
        }
}
