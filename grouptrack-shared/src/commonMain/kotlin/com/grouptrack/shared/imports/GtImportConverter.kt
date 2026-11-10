package com.grouptrack.shared.imports

import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.text.GtFmt
import com.grouptrack.shared.text.GtJavaParse
import com.grouptrack.shared.text.GtRx
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/**
 * MAPIMPORTS-2026-10-30 (D31, Fred 10-30): "Convert files to GPX" -- KML / KMZ / GeoJSON -> a GPX the import already reads.
 * Lines -> <trk> (one per part), points -> <wpt>. Names follow the Mac converter (kmz_to_gpx_trails_2026-10-29.py).
 * It imports NOTHING: the rider then picks Import GPX (tracks) or Create trails from GPX files (trails).
 *
 * STEP15B-2026-11-09 (2.8 step 15b): 2.7e's ImportConverter.toGpx and everything it calls, MOVED AS IT IS (owner 11-09 18:23) to shared
 * Kotlin. What changed, and only this:
 *  - java.io.File -> okio (the caller's FileSystem): read, exists, write; File.extension / nameWithoutExtension -> the same rules on the
 *    name (substringAfterLast('.', "") / substringBeforeLast("."));
 *  - KMZ: java.util.zip.ZipFile -> okio's zip FileSystem, the entries taken in the archive's CENTRAL-DIRECTORY order (what ZipFile.entries()
 *    gives -- it decides which .kml is read when there is no doc.kml) by reading that directory here;
 *  - org.json -> kotlinx elements read the org.json way (GtOrgJson: optString / optDouble / a value's String.valueOf);
 *  - String.format(Locale.US, "%.7f") -> GtFmt.f(x, 7) (certified identical, step 13);
 *  - String.toDoubleOrNull -> GtJavaParse.toDoubleOrNull (Java's grammar, owner rule 4);
 *  - android.util.Log -> gtLogger("ImportConvert").
 * NOT moved: convertAll and saveToDownloads (the picker, the content resolver and MediaStore are the platform's) -- they stay in the app
 * and, at the 15b switch, call this.
 * ⚠ Errors: 2.7e throws its own IllegalStateException texts ("no lines or points found", "no .kml inside <name>") -- kept word for word.
 *   A file that is not a zip / not JSON throws the PLATFORM's exception (java.util.zip / org.json on 2.7e; okio / kotlinx here) with its
 *   own text: the import shows "FAILED <name>: <message>", so that one text differs (GtCert15b compares it as THROWS(platform)).
 */
object GtImportConverter {
    private val log = gtLogger("ImportConvert")

    class Converted(val file: Path, val lines: Int, val tracks: Int, val points: Int)

    /**
     * ONEIMPORT-2026-11-04 (Fred 07:59-08:05): asTrails = the rider answered YES to "Are these files trails?". Every line is then
     * written as a GroupTrack trail (<type>grouptrack-trail</type> -- the mark the import reads) and points stay waypoints in the
     * same file. NO = lines are tracks. KML / KMZ / GeoJSON cannot say track or trail, nor can a GPX <trk> -- so GPX comes through
     * here for trails too. CODE RULE 1: asTrails is required -- every caller says which.
     * STEP15B-2026-11-09: [fs] is the caller's okio FileSystem (Android: FileSystem.SYSTEM); [src] and [outDir] are paths on it.
     */
    fun toGpx(fs: FileSystem, src: Path, outDir: Path, asTrails: Boolean): Converted {
        val srcName = src.name
        val ext = srcName.substringAfterLast('.', "").lowercase()
        val text = if (ext == "kmz") readKmz(fs, src) else fs.read(src) { readUtf8() }
        val c = Collector(srcName.substringBeforeLast("."), asTrails)
        when (ext) { "geojson", "json" -> fromGeoJson(text, c); "gpx" -> fromGpx(text, c); else -> fromKml(text, c) }
        if (c.trk.isEmpty() && c.wpt.isEmpty()) throw IllegalStateException("no lines or points found")
        val base = srcName.substringBeforeLast(".") + if (asTrails) "-Trails" else "-Converted"
        var out = outDir / "$base.gpx"
        var n = 2
        while (fs.exists(out)) { out = outDir / "${base}_$n.gpx"; n++ }
        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"GroupTrack ONEIMPORT-2026-11-04\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("<metadata><name>").append(clean(c.docName)).append("</name></metadata>\n")
            c.wpt.forEach { append(it).append('\n') }
            c.trk.forEach { append(it).append('\n') }
            append("</gpx>\n")
        }
        fs.write(out) { writeUtf8(gpx) }
        log.i { "ONEIMPORT $srcName trails=$asTrails: ${c.lines} lines (${c.trk.size} parts), ${c.wpt.size} points -> ${out.name}" }
        return Converted(out, c.lines, c.trk.size, c.wpt.size)
    }

    /** ONEIMPORT-2026-11-04: the mark the import reads on a <trk>. The rider-trail export (ExportAll) writes it too. */
    const val TRAIL_MARK = "grouptrack-trail"

    // ---- GPX (through here only when the rider says the file is trails) -----------------------------------------
    private val GPX_PT = Regex("""<(?:trkpt|rtept)\b([^>]*)>""")
    private val LAT_RX = Regex("""\blat\s*=\s*["']([-0-9.eE+]+)["']""")
    private val LON_RX = Regex("""\blon\s*=\s*["']([-0-9.eE+]+)["']""")
    private fun gpxPts(block: String): List<Pair<Double, Double>> = GtRx.findAll(GPX_PT, block).mapNotNull { m ->
        val lat = LAT_RX.find(m.groupValues[1])?.groupValues?.get(1)?.let { GtJavaParse.toDoubleOrNull(it) }
        val lon = LON_RX.find(m.groupValues[1])?.groupValues?.get(1)?.let { GtJavaParse.toDoubleOrNull(it) }
        if (lat == null || lon == null) null else Pair(lon, lat)
    }.toList()

    private fun gpxLine(b: String, segs: List<List<Pair<Double, Double>>>, c: Collector) {
        val own = Regex("""<name>\s*(?:<!\[CDATA\[)?([\s\S]*?)(?:]]>)?\s*</name>""").find(b)?.groupValues?.get(1)?.let { clean(it) } ?: ""
        val ty = Regex("""<type>([\s\S]*?)</type>""").find(b)?.groupValues?.get(1)?.let { clean(it) } ?: ""
        val d = LinkedHashMap<String, String>()
        // a <type> that is not the trail mark is the file's difficulty / class (the old converter wrote the rating there)
        if (ty.isNotEmpty() && !ty.equals(TRAIL_MARK, ignoreCase = true) && !ty.equals("trail", ignoreCase = true)) d["Difficulty"] = ty
        val (nm, r) = nameOf(own, d, c)
        addLine(nm, r, segs, c)
    }

    private fun fromGpx(t: String, c: Collector) {
        Regex("""<metadata>[\s\S]*?<name>([\s\S]*?)</name>""").find(t)?.let { m -> clean(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { c.docName = it } }
        val body = t.replace(Regex("""<extensions>[\s\S]*?</extensions>"""), "")
        for (m in GtRx.findAll(Regex("""<trk\b[^>]*>([\s\S]*?)</trk>"""), body)) {
            val b = m.groupValues[1]
            val segs = GtRx.findAll(Regex("""<trkseg\b[^>]*>([\s\S]*?)</trkseg>"""), b).map { gpxPts(it.groupValues[1]) }.toList()
            gpxLine(b.substringBefore("<trkseg"), segs.ifEmpty { listOf(gpxPts(b)) }, c)
        }
        // a <rte> in a file the rider says is trails is a trail too (Fred 08:04: anything else is a trail by importing trails only)
        for (m in GtRx.findAll(Regex("""<rte\b[^>]*>([\s\S]*?)</rte>"""), body)) {
            val b = m.groupValues[1]
            gpxLine(b.substringBefore("<rtept"), listOf(gpxPts(b)), c)
        }
        // single waypoints stay in the same file (Fred 08:04: we know how to encode them)
        for (m in GtRx.findAll(Regex("""<wpt\b[^>]*/>|<wpt\b[\s\S]*?</wpt>"""), t)) c.wpt.add(m.value)
    }

    /** STEP15B-2026-11-09: java.util.zip.ZipFile -> okio's zip FileSystem; the entry NAMES in central-directory order (as
     *  ZipFile.entries()), then 2.7e's own choice: the .kml entries, doc.kml first (sortedBy is stable), the first of them. */
    private fun readKmz(fs: FileSystem, src: Path): String {
        val e = zipEntryNames(fs, src).filter { it.lowercase().endsWith(".kml") }
            .sortedBy { if (it.lowercase().endsWith("doc.kml")) 0 else 1 }.firstOrNull()
            ?: throw IllegalStateException("no .kml inside ${src.name}")
        val zip = fs.openZip(src)
        return zip.read(("/" + e).toPath()) { readByteString() }.utf8()
    }

    /**
     * STEP15B-2026-11-09: the entry names of a zip in CENTRAL-DIRECTORY order -- java.util.zip.ZipFile.entries()' order. Read from the
     * End Of Central Directory record (the last 22 + up to 65,535 bytes of comment) and the directory it points at; names as UTF-8 (as
     * ZipFile reads them). Not a zip -> IOException (the platform's kind of failure, as 2.7e's ZipException). ZIP64 is not read here: a
     * KMZ is small (a ZIP64 archive's 0xFFFFFFFF offset fails as "not a zip").
     */
    // STEP15G-2026-11-10: internal (was private) -- GtOsmExtract / GtOsmStage read the GeoPackage zip's entries with it too.
    internal fun zipEntryNames(fs: FileSystem, zipPath: Path): List<String> {
        val size = fs.metadata(zipPath).size ?: throw okio.IOException("zip: no size for $zipPath")
        val tailLen = minOf(size, 22L + 65_535L)
        val tail = fs.openReadOnly(zipPath).use { h ->
            val buf = okio.Buffer()
            h.source(size - tailLen).buffer().use { it.readFully(buf, tailLen) }
            buf.readByteArray()
        }
        fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)
        var eocd = -1
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) { eocd = i; break }
        }
        if (eocd < 0) throw okio.IOException("zip END header not found")
        val count = u16(tail, eocd + 10)
        val cdSize = u32(tail, eocd + 12)
        val cdOffset = u32(tail, eocd + 16)
        if (cdOffset + cdSize > size) throw okio.IOException("zip: central directory outside the file")
        val cd = fs.openReadOnly(zipPath).use { h ->
            val buf = okio.Buffer()
            h.source(cdOffset).buffer().use { it.readFully(buf, cdSize) }
            buf.readByteArray()
        }
        val names = ArrayList<String>(count)
        var p = 0
        for (k in 0 until count) {
            if (p + 46 > cd.size || u32(cd, p) != 0x02014b50L) throw okio.IOException("zip: bad central directory entry $k")
            val nameLen = u16(cd, p + 28); val extraLen = u16(cd, p + 30); val commentLen = u16(cd, p + 32)
            names.add(cd.copyOfRange(p + 46, p + 46 + nameLen).decodeToString())
            p += 46 + nameLen + extraLen + commentLen
        }
        return names
    }

    // ---- shared naming + output -------------------------------------------------------------------------------
    private class Collector(var docName: String, val asTrails: Boolean) {   // ONEIMPORT-2026-11-04
        val trk = ArrayList<String>(); val wpt = ArrayList<String>(); val used = HashMap<String, Int>(); var lines = 0
    }

    /** Names go into <name> and the import reads them as written, so they are made plain: entities decoded, & -> and, no < > ". */
    private fun clean(s: String): String = s
        .replace(Regex("""<!\[CDATA\[|]]>"""), "")
        .replace("&amp;", "&").replace("&lt;", "").replace("&gt;", "").replace("&quot;", "").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&", " and ").replace("<", "").replace(">", "").replace("\"", "")
        .replace(Regex("""\s+"""), " ").trim()

    private fun pick(d: Map<String, String>, vararg keys: String): String {
        for (k in keys) for ((dk, v) in d) if (dk.equals(k, ignoreCase = true) && v.isNotBlank()) return v.trim()
        return ""
    }

    private fun nameOf(own: String, d: Map<String, String>, c: Collector): Pair<String, String> {
        val nm = pick(d, "NAME", "TRAIL_NAME", "TrailName", "TRAILNAME")
        val num = pick(d, "Number", "Label", "TRAIL_NO", "TrailNo", "NUM")
        val rating = pick(d, "Rating", "Difficulty", "DIFF", "Class")
        val base = nm.ifEmpty { if (own.isNotEmpty() && !own.all { it.isDigit() }) own else "" }
        var name = when {
            base.isNotEmpty() && num.isNotEmpty() && !base.contains(num) -> "$base ($num)"
            base.isNotEmpty() -> base
            num.isNotEmpty() -> "Trail $num"
            own.isNotEmpty() -> "Trail $own"
            pick(d, "FID", "OBJECTID").isNotEmpty() -> "Trail FID ${pick(d, "FID", "OBJECTID")}"
            else -> "Unnamed trail"
        }
        name = clean(name).ifEmpty { "Unnamed trail" }
        val k = (c.used[name] ?: 0) + 1; c.used[name] = k
        if (k > 1) name = "$name #$k"
        return Pair(name, clean(rating))
    }

    private fun addLine(name: String, rating: String, parts: List<List<Pair<Double, Double>>>, c: Collector) {
        val good = parts.filter { it.size >= 2 }
        if (good.isEmpty()) return
        c.lines++
        val desc = clean(c.docName)
        // ONEIMPORT-2026-11-04 (Fred 07:59): a difficulty in the file goes into the name -- <type> carries only the trail mark
        val full = if (rating.isNotEmpty() && !name.contains(rating, ignoreCase = true)) "$name ($rating)" else name
        good.forEachIndexed { i, pts ->
            val nm = if (i == 0) full else "$full (part ${i + 1})"
            c.trk.add(buildString {
                append("<trk><name>").append(nm).append("</name><desc>").append(desc).append("</desc>")
                if (c.asTrails) append("<type>").append(TRAIL_MARK).append("</type>")
                append("<trkseg>")
                // ⚠ NOT self-closing: parseGpxTrackPointsFull matches <trkpt ...>...</trkpt>
                // STEP15B-2026-11-09: "%.7f".format(Locale.US, lat, lon) -> GtFmt.f(x, 7)
                for ((lon, lat) in pts) append("<trkpt lat=\"").append(GtFmt.f(lat, 7)).append("\" lon=\"").append(GtFmt.f(lon, 7)).append("\"></trkpt>")
                append("</trkseg></trk>")
            })
        }
    }

    private fun addPoint(name: String, lon: Double, lat: Double, c: Collector) {
        // STEP15B-2026-11-09: "<wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name><desc>%s</desc></wpt>".format(Locale.US, ...) -> GtFmt.f(x, 7)
        c.wpt.add("<wpt lat=\"" + GtFmt.f(lat, 7) + "\" lon=\"" + GtFmt.f(lon, 7) + "\"><name>" + name + "</name><desc>" + clean(c.docName) + "</desc></wpt>")
    }

    // ---- KML ---------------------------------------------------------------------------------------------------
    private val NAME_RX = Regex("""<name>\s*(?:<!\[CDATA\[)?([\s\S]*?)(?:]]>)?\s*</name>""")

    private fun coords(s: String): List<Pair<Double, Double>> = s.trim().split(Regex("""\s+""")).mapNotNull { p ->
        val a = p.split(",")
        if (a.size < 2) null else {
            val lon = GtJavaParse.toDoubleOrNull(a[0]); val lat = GtJavaParse.toDoubleOrNull(a[1])
            if (lon == null || lat == null) null else Pair(lon, lat)
        }
    }

    private fun fromKml(t: String, c: Collector) {
        Regex("""<Document\b[^>]*>\s*<name>\s*(?:<!\[CDATA\[)?([\s\S]*?)(?:]]>)?\s*</name>""").find(t)?.let { c.docName = clean(it.groupValues[1]) }
        for (m in GtRx.findAll(Regex("""<Placemark\b[^>]*>([\s\S]*?)</Placemark>"""), t)) {
            val pm = m.groupValues[1]
            val d = LinkedHashMap<String, String>()
            for (r in GtRx.findAll(Regex("""<td>([^<]*)</td>\s*<td>([^<]*)</td>"""), pm)) { val k = r.groupValues[1].trim(); if (k !in d) d[k] = r.groupValues[2].trim() }   // STEP15B-FIXB: putIfAbsent is JVM-only
            for (r in GtRx.findAll(Regex("""<(?:Simple)?Data name="([^"]+)"[^>]*>\s*(?:<value>)?([^<]*)"""), pm)) { val k = r.groupValues[1].trim(); if (k !in d) d[k] = r.groupValues[2].trim() }   // STEP15B-FIXB
            val own = NAME_RX.find(pm)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            val lines = GtRx.findAll(Regex("""<LineString\b[^>]*>[\s\S]*?<coordinates>([^<]*)</coordinates>"""), pm).map { coords(it.groupValues[1]) }.toList()
            if (lines.isNotEmpty()) {
                val (name, rating) = nameOf(own, d, c)
                addLine(name, rating, lines, c)
                continue
            }
            val p = Regex("""<Point\b[^>]*>[\s\S]*?<coordinates>([^<]*)</coordinates>""").find(pm) ?: continue
            val pt = coords(p.groupValues[1]).firstOrNull() ?: continue
            addPoint(nameOf(own, d, c).first, pt.first, pt.second, c)
        }
    }

    // ---- GeoJSON -----------------------------------------------------------------------------------------------
    // STEP15B-2026-11-09: org.json -> GtOrgJson on kotlinx elements: optJSONArray / optJSONObject = "the value if it is an array / an
    // object", length() = size, optDouble(i) = GtOrgJson.optDouble (NaN when missing), optString = GtOrgJson.optString.
    private fun line(a: JsonArray): List<Pair<Double, Double>> = (0 until a.size).mapNotNull { i ->
        val p = a[i] as? JsonArray ?: return@mapNotNull null
        if (p.size < 2) null else Pair(GtOrgJson.optDouble(p, 0), GtOrgJson.optDouble(p, 1))
    }.filter { !it.first.isNaN() && !it.second.isNaN() }

    /** org.json's `v != null && v != JSONObject.NULL`: a JSON null, or an unquoted null literal kotlinx passed through. */
    private fun isOrgNull(v: JsonElement): Boolean =
        v is JsonNull || (v is JsonPrimitive && !v.isString && v.content.equals("null", ignoreCase = true))

    private fun fromGeoJson(t: String, c: Collector) {
        // STEP15B-2026-11-09: org.json.JSONObject(t) -- not an object -> the platform's exception, as 2.7e's JSONException
        val root = GtOrgJson.parse(t) as? JsonObject ?: throw IllegalArgumentException("GeoJSON root is not a JSON object")
        GtOrgJson.optString(root, "name").takeIf { it.isNotBlank() }?.let { c.docName = clean(it) }
        val feats = when (GtOrgJson.optString(root, "type")) {
            "FeatureCollection" -> root["features"] as? JsonArray ?: JsonArray(emptyList())
            "Feature" -> JsonArray(listOf(root))
            else -> JsonArray(listOf(JsonObject(linkedMapOf("type" to JsonPrimitive("Feature"), "geometry" to root))))
        }
        for (i in 0 until feats.size) {
            val f = feats[i] as? JsonObject ?: continue
            val g = f["geometry"] as? JsonObject ?: continue
            val props = f["properties"] as? JsonObject
            val d = LinkedHashMap<String, String>()
            if (props != null) for ((k, v) in props) { if (!isOrgNull(v)) d[k] = GtOrgJson.valueText(v) ?: continue }
            val own = pick(d, "name", "title")
            val cs = g["coordinates"] as? JsonArray ?: continue
            when (GtOrgJson.optString(g, "type")) {
                "LineString" -> { val (n, r) = nameOf(own, d, c); addLine(n, r, listOf(line(cs)), c) }
                "MultiLineString" -> { val (n, r) = nameOf(own, d, c); addLine(n, r, (0 until cs.size).mapNotNull { j -> (cs[j] as? JsonArray)?.let { line(it) } }, c) }
                "Point" -> if (cs.size >= 2) addPoint(nameOf(own, d, c).first, GtOrgJson.optDouble(cs, 0), GtOrgJson.optDouble(cs, 1), c)
                "MultiPoint" -> { val n = nameOf(own, d, c).first; line(cs).forEach { (lon, lat) -> addPoint(n, lon, lat, c) } }
            }
        }
    }
}
