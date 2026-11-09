package com.geeksville.mesh.convoy

import java.io.File
import com.grouptrack.shared.imports.GtImportConverter   // STEP15B-SWITCH-2026-11-09
import okio.FileSystem
import okio.Path.Companion.toOkioPath

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
                val r = toGpx(src, dir, false)   // ONEIMPORT-2026-11-04: this screen is off the menu; lines = tracks
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
    fun saveToDownloads(context: android.content.Context, f: File): String {   // ONEIMPORT-2026-11-04: the import saves the converted file too
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

    /**
     * ONEIMPORT-2026-11-04 (Fred 07:59-08:05): asTrails = the rider answered YES to "Are these files trails?". Every line is then
     * written as a GroupTrack trail (<type>grouptrack-trail</type> -- the mark the import reads) and points stay waypoints in the
     * same file. NO = lines are tracks. KML / KMZ / GeoJSON cannot say track or trail, nor can a GPX <trk> -- so GPX comes through
     * here for trails too. CODE RULE 1: asTrails is required -- every caller says which.
     */
    // STEP15B-SWITCH-2026-11-09: the certified shared converter (15b, RXFIX). Same files, same names, same counts; the ONEIMPORT log line is the
    // shared converter's (tag "ImportConvert", same text). The 2.7e body and its helpers are in git history.
    fun toGpx(src: File, outDir: File, asTrails: Boolean): Converted =
        GtImportConverter.toGpx(FileSystem.SYSTEM, src.toOkioPath(), outDir.toOkioPath(), asTrails).let { Converted(File(it.file.toString()), it.lines, it.tracks, it.points) }

    /** ONEIMPORT-2026-11-04: the mark the import reads on a <trk>. The rider-trail export (ExportAll) writes it too. */
    const val TRAIL_MARK = "grouptrack-trail"

}
