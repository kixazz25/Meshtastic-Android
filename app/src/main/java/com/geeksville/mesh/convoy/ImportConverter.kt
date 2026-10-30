package com.geeksville.mesh.convoy

import java.io.File

/**
 * MAPIMPORTS-2026-10-30 (D31, Fred 10-30): "Convert files to GPX" -- KML / KMZ / GeoJSON -> a GPX the import already reads.
 * Lines -> <trk> (one per part), points -> <wpt>. Names follow the Mac converter (kmz_to_gpx_trails_2026-10-29.py).
 * It imports NOTHING: the rider then picks Import GPX (tracks) or Create trails from GPX files (trails).
 */
object ImportConverter {

    private val CONVERTIBLE = setOf("kml", "kmz", "geojson", "json")

    class Converted(val file: File, val lines: Int, val tracks: Int, val points: Int)

    /** Every picked file: copied in, converted, the GPX saved to Download. One result line per file. */
    fun convertAll(context: android.content.Context, uris: List<android.net.Uri>): List<String> {
        val dir = File(context.cacheDir, "convert_staging")
        if (dir.exists()) dir.listFiles()?.forEach { it.delete() }   // a previous run that died leaves files here
        dir.mkdirs()
        val out = ArrayList<String>()
        for (uri in uris) {
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name ?: "file_${System.currentTimeMillis()}"
            try {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext == "gpx") { out.add("$name: already GPX -- import it with Import GPX or Import trails from GPX files"); continue }
                if (ext !in CONVERTIBLE) { out.add("FAILED $name: not a KML, KMZ or GeoJSON file"); continue }
                val src = File(dir, name)
                context.contentResolver.openInputStream(uri).use { i ->
                    if (i == null) throw IllegalStateException("cannot read the file")
                    src.outputStream().use { o -> i.copyTo(o) }
                }
                val r = toGpx(src, dir)
                val saved = saveToDownloads(context, r.file)
                out.add("$name -> Download/$saved   ${r.lines} lines (${r.tracks} tracks), ${r.points} points")
            } catch (e: Exception) {
                android.util.Log.e("ImportConvert", "$name: ${e.message}")
                out.add("FAILED $name: ${e.message ?: "could not convert"}")
            }
        }
        dir.listFiles()?.forEach { it.delete() }
        return out
    }

    /** ⚠ No WRITE permission is held below Android 10 (minSdk 26): there the save fails and the line says so. */
    private fun saveToDownloads(context: android.content.Context, f: File): String {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            val d = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            var t = File(d, f.name); var n = 2
            while (t.exists()) { t = File(d, "${f.nameWithoutExtension}_$n.gpx"); n++ }
            f.copyTo(t); return t.name
        }
        val cr = context.contentResolver
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            // CONVERTFIX-2026-10-30: NOT application/gpx+xml -- Android does not know it and renamed the file .bin.
            // octet-stream keeps the name as given.
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("could not create the file in Download")
        cr.openOutputStream(uri).use { o ->
            if (o == null) throw IllegalStateException("could not write the file in Download")
            f.inputStream().use { it.copyTo(o) }
        }
        v.clear(); v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, v, null, null)
        return cr.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: f.name
    }

    fun toGpx(src: File, outDir: File): Converted {
        val ext = src.extension.lowercase()
        val text = if (ext == "kmz") readKmz(src) else src.readText()
        val c = Collector(src.nameWithoutExtension)
        if (ext == "geojson" || ext == "json") fromGeoJson(text, c) else fromKml(text, c)
        if (c.trk.isEmpty() && c.wpt.isEmpty()) throw IllegalStateException("no lines or points found")
        var out = File(outDir, "${src.nameWithoutExtension}_$ext.gpx")
        var n = 2
        while (out.exists()) { out = File(outDir, "${src.nameWithoutExtension}_${ext}_$n.gpx"); n++ }
        out.writeText(buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"GroupTrack MAPIMPORTS-2026-10-30\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("<metadata><name>").append(clean(c.docName)).append("</name></metadata>\n")
            c.wpt.forEach { append(it).append('\n') }
            c.trk.forEach { append(it).append('\n') }
            append("</gpx>\n")
        })
        android.util.Log.i("ImportConvert", "${src.name}: ${c.lines} lines (${c.trk.size} tracks), ${c.wpt.size} points -> ${out.name}")
        return Converted(out, c.lines, c.trk.size, c.wpt.size)
    }

    private fun readKmz(src: File): String = java.util.zip.ZipFile(src).use { z ->
        val e = z.entries().asSequence().filter { it.name.lowercase().endsWith(".kml") }
            .sortedBy { if (it.name.lowercase().endsWith("doc.kml")) 0 else 1 }.firstOrNull()
            ?: throw IllegalStateException("no .kml inside ${src.name}")
        z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    // ---- shared naming + output -------------------------------------------------------------------------------
    private class Collector(var docName: String) {
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
        val desc = clean(c.docName) + if (rating.isNotEmpty()) " · $rating" else ""
        good.forEachIndexed { i, pts ->
            val nm = if (i == 0) name else "$name (part ${i + 1})"
            c.trk.add(buildString {
                append("<trk><name>").append(nm).append("</name><desc>").append(desc).append("</desc>")
                if (rating.isNotEmpty()) append("<type>").append(rating).append("</type>")
                append("<trkseg>")
                // ⚠ NOT self-closing: parseGpxTrackPointsFull matches <trkpt ...>...</trkpt>
                for ((lon, lat) in pts) append("<trkpt lat=\"%.7f\" lon=\"%.7f\"></trkpt>".format(java.util.Locale.US, lat, lon))
                append("</trkseg></trk>")
            })
        }
    }

    private fun addPoint(name: String, lon: Double, lat: Double, c: Collector) {
        c.wpt.add("<wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name><desc>%s</desc></wpt>"
            .format(java.util.Locale.US, lat, lon, name, clean(c.docName)))
    }

    // ---- KML ---------------------------------------------------------------------------------------------------
    private val NAME_RX = Regex("""<name>\s*(?:<!\[CDATA\[)?([\s\S]*?)(?:]]>)?\s*</name>""")

    private fun coords(s: String): List<Pair<Double, Double>> = s.trim().split(Regex("""\s+""")).mapNotNull { p ->
        val a = p.split(",")
        if (a.size < 2) null else {
            val lon = a[0].toDoubleOrNull(); val lat = a[1].toDoubleOrNull()
            if (lon == null || lat == null) null else Pair(lon, lat)
        }
    }

    private fun fromKml(t: String, c: Collector) {
        Regex("""<Document\b[^>]*>\s*<name>\s*(?:<!\[CDATA\[)?([\s\S]*?)(?:]]>)?\s*</name>""").find(t)?.let { c.docName = clean(it.groupValues[1]) }
        for (m in Regex("""<Placemark\b[^>]*>([\s\S]*?)</Placemark>""").findAll(t)) {
            val pm = m.groupValues[1]
            val d = LinkedHashMap<String, String>()
            for (r in Regex("""<td>([^<]*)</td>\s*<td>([^<]*)</td>""").findAll(pm)) d.putIfAbsent(r.groupValues[1].trim(), r.groupValues[2].trim())
            for (r in Regex("""<(?:Simple)?Data name="([^"]+)"[^>]*>\s*(?:<value>)?([^<]*)""").findAll(pm)) d.putIfAbsent(r.groupValues[1].trim(), r.groupValues[2].trim())
            val own = NAME_RX.find(pm)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            val lines = Regex("""<LineString\b[^>]*>[\s\S]*?<coordinates>([^<]*)</coordinates>""").findAll(pm).map { coords(it.groupValues[1]) }.toList()
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
    private fun line(a: org.json.JSONArray): List<Pair<Double, Double>> = (0 until a.length()).mapNotNull { i ->
        val p = a.optJSONArray(i) ?: return@mapNotNull null
        if (p.length() < 2) null else Pair(p.optDouble(0), p.optDouble(1))
    }.filter { !it.first.isNaN() && !it.second.isNaN() }

    private fun fromGeoJson(t: String, c: Collector) {
        val root = org.json.JSONObject(t)
        root.optString("name").takeIf { it.isNotBlank() }?.let { c.docName = clean(it) }
        val feats = when (root.optString("type")) {
            "FeatureCollection" -> root.optJSONArray("features") ?: org.json.JSONArray()
            "Feature" -> org.json.JSONArray().put(root)
            else -> org.json.JSONArray().put(org.json.JSONObject().put("type", "Feature").put("geometry", root))
        }
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val g = f.optJSONObject("geometry") ?: continue
            val props = f.optJSONObject("properties")
            val d = LinkedHashMap<String, String>()
            if (props != null) for (k in props.keys()) { val v = props.opt(k); if (v != null && v != org.json.JSONObject.NULL) d[k] = v.toString() }
            val own = pick(d, "name", "title")
            val cs = g.optJSONArray("coordinates") ?: continue
            when (g.optString("type")) {
                "LineString" -> { val (n, r) = nameOf(own, d, c); addLine(n, r, listOf(line(cs)), c) }
                "MultiLineString" -> { val (n, r) = nameOf(own, d, c); addLine(n, r, (0 until cs.length()).mapNotNull { j -> cs.optJSONArray(j)?.let { line(it) } }, c) }
                "Point" -> if (cs.length() >= 2) addPoint(nameOf(own, d, c).first, cs.optDouble(0), cs.optDouble(1), c)
                "MultiPoint" -> { val n = nameOf(own, d, c).first; line(cs).forEach { (lon, lat) -> addPoint(n, lon, lat, c) } }
            }
        }
    }
}
