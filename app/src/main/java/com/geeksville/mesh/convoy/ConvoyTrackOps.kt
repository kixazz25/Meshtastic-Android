package com.geeksville.mesh.convoy

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pure file operations for tracks. No UI, no Compose. Callable from any screen.
 */
object ConvoyTrackOps {

    private const val TRACKS_DIR_NAME = "my_tracks"

    fun tracksDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        TRACKS_DIR_NAME
    )

    fun downloadsDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** List GPX/KML files, excluding hidden and Android-trash entries. */
    suspend fun listTracks(): List<File> = withContext(Dispatchers.IO) {
        val dir = tracksDir()
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles()?.filter { f ->
            val name = f.name
            val ext = f.extension.lowercase()
            (ext == "gpx" || ext == "kml") &&
            !name.startsWith(".") &&
            !name.startsWith(".trashed-")
        } ?: emptyList()
    }

    /** True if the track is an in-progress recording temp file. */
    fun isInProgress(file: File): Boolean =
        file.name.startsWith("convoy_track_temp_")

    sealed class RenameResult {
        object Success : RenameResult()
        object NameExists : RenameResult()
        object Failed : RenameResult()
    }

    /** Rename in place, preserving extension. */
    suspend fun renameTrack(file: File, newBaseName: String): RenameResult =
        withContext(Dispatchers.IO) {
            val ext = file.extension
            val target = File(file.parentFile, "$newBaseName.$ext")
            when {
                target.absolutePath == file.absolutePath -> RenameResult.Success
                target.exists() -> RenameResult.NameExists
                file.renameTo(target) -> RenameResult.Success
                else -> RenameResult.Failed
            }
        }

    /** Delete file. Returns true on success. */
    suspend fun deleteTrack(file: File): Boolean = withContext(Dispatchers.IO) {
        try { file.delete() } catch (e: Exception) { false }
    }

    /** Copy file to public Downloads directory. Returns true on success. */
    suspend fun copyToDownloads(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = File(downloadsDir(), file.name)
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("ConvoyTrackOps", "Copy to Downloads failed: ${e.message}")
            false
        }
    }

    /** Launch Android share sheet with the track as attachment. */
    fun shareTrack(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share track").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("ConvoyTrackOps", "Share failed: ${e.message}")
        }
    }

    /** Format size in B / KB / MB. */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    // ── Import + Date Preservation ─────────────────────────────────────

    /**
     * Result of an import operation.
     */
    sealed class ImportResult {
        data class Success(
            val sourceName: String,
            val createdFiles: List<String>
        ) : ImportResult()
        data class PartialSuccess(
            val sourceName: String,
            val createdFiles: List<String>,
            val skippedFiles: List<String>
        ) : ImportResult()
        data class Failed(
            val sourceName: String,
            val reason: String
        ) : ImportResult()
    }

    /**
     * Result of fix-dates operation across multiple files.
     */
    data class FixDatesResult(
        val updated: Int,
        val unchanged: Int,
        val failed: Int
    )

    /**
     * Extract earliest GPX <time> or KML <when> from content as epoch millis.
     * Returns null if no parseable time found.
     */
    fun extractEarliestTime(content: String): Long? {
        val patterns = listOf(
            Regex("""<time>([^<]+)</time>"""),
            Regex("""<when>([^<]+)</when>""")
        )
        var earliest: Long? = null
        for (pattern in patterns) {
            for (match in pattern.findAll(content)) {
                try {
                    val epochMs = java.time.Instant.parse(match.groupValues[1].trim()).toEpochMilli()
                    if (earliest == null || epochMs < earliest!!) {
                        earliest = epochMs
                    }
                } catch (e: Exception) {
                    // Some GPX files use timestamps without 'Z' or with offsets — try alternate parsers
                    try {
                        val odt = java.time.OffsetDateTime.parse(match.groupValues[1].trim())
                        val epochMs = odt.toInstant().toEpochMilli()
                        if (earliest == null || epochMs < earliest!!) {
                            earliest = epochMs
                        }
                    } catch (e2: Exception) {
                        // skip unparseable entries
                    }
                }
            }
        }
        return earliest
    }

    /**
     * Sanitize a track name for use as a filename.
     */
    private fun sanitizeFilename(name: String): String {
        return name
            .replace("/", "_")
            .replace("\\", "_")
            .replace(Regex("""[^a-zA-Z0-9_\- ]"""), "")
            .trim()
            .ifEmpty { "track" }
    }

    /**
     * Import a track file (single or multi-track GPX/KML).
     * Splits multi-track files into individual files in my_tracks/.
     * Preserves earliest <time> as the file's mtime.
     * Skips files that already exist (no overwrite).
     */
    suspend fun importTrackFile(
        sourceFile: File,
        onProgress: ((current: Int, total: Int, currentName: String) -> Unit)? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val sourceName = sourceFile.name
        try {
            if (!sourceFile.exists()) {
                return@withContext ImportResult.Failed(sourceName, "Source file not found")
            }

            var text = sourceFile.readText()
            // Strip <extensions> — custom namespaces (onXmaps etc) crash XML when tracks are split
            text = text.replace(Regex("""<extensions>[\s\S]*?</extensions>"""), "")
            val ext = sourceFile.extension.lowercase()
            val isGpx = ext == "gpx"
            val isKml = ext == "kml"
            if (!isGpx && !isKml) {
                return@withContext ImportResult.Failed(sourceName, "Not a GPX or KML file")
            }

            val dir = tracksDir()
            if (!dir.exists()) dir.mkdirs()

            val created = mutableListOf<String>()
            val skipped = mutableListOf<String>()

            // ── Multi-track GPX ──
            if (isGpx && text.contains("<trk>")) {
                val trkPattern = Regex("""<trk>([\s\S]*?)</trk>""")
                val tracks = trkPattern.findAll(text).toList()

                if (tracks.size > 1) {
                    val namePattern = Regex("<name>([^<]*)</name>")
                    for ((index, trk) in tracks.withIndex()) {
                        val trkContent = trk.groupValues[1]
                        val rawName = namePattern.find(trkContent)?.groupValues?.get(1)?.trim()
                            ?: "track_${index + 1}"
                        val baseName = sanitizeFilename(rawName)
                        val safeName = "$baseName.gpx"
                        onProgress?.invoke(index + 1, tracks.size, safeName)

                        val dest = File(dir, safeName)
                        if (dest.exists()) {
                            skipped.add(safeName)
                            continue
                        }
                        val singleGpx = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                            "<gpx version=\"1.1\" creator=\"GroupTrack\">\n" +
                            "<trk>${trkContent}</trk>\n</gpx>"
                        dest.writeText(singleGpx)

                        // Preserve earliest <time> as mtime
                        extractEarliestTime(singleGpx)?.let { dest.setLastModified(it) }
                            ?: dest.setLastModified(sourceFile.lastModified())

                        created.add(safeName)
                    }
                    return@withContext when {
                        created.isEmpty() && skipped.isNotEmpty() ->
                            ImportResult.PartialSuccess(sourceName, created, skipped)
                        skipped.isNotEmpty() ->
                            ImportResult.PartialSuccess(sourceName, created, skipped)
                        else -> ImportResult.Success(sourceName, created)
                    }
                }
            }

            // ── Multi-track KML ──
            if (isKml && text.contains("<Placemark>")) {
                val pmPattern = Regex("""<Placemark>([\s\S]*?)</Placemark>""")
                val placemarks = pmPattern.findAll(text)
                    .filter { it.groupValues[1].contains("<LineString>") }
                    .toList()

                if (placemarks.size > 1) {
                    val namePattern = Regex("<name>([^<]*)</name>")
                    for ((index, pm) in placemarks.withIndex()) {
                        val pmFull = pm.groupValues[0]
                        val pmInner = pm.groupValues[1]
                        val rawName = namePattern.find(pmInner)?.groupValues?.get(1)?.trim()
                            ?: "track_${index + 1}"
                        val baseName = sanitizeFilename(rawName)
                        val safeName = "$baseName.kml"
                        onProgress?.invoke(index + 1, placemarks.size, safeName)

                        val dest = File(dir, safeName)
                        if (dest.exists()) {
                            skipped.add(safeName)
                            continue
                        }
                        val singleKml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                            "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n" +
                            "<name>$rawName</name>\n$pmFull\n</Document>\n</kml>"
                        dest.writeText(singleKml)

                        extractEarliestTime(singleKml)?.let { dest.setLastModified(it) }
                            ?: dest.setLastModified(sourceFile.lastModified())

                        created.add(safeName)
                    }
                    return@withContext when {
                        skipped.isNotEmpty() ->
                            ImportResult.PartialSuccess(sourceName, created, skipped)
                        else -> ImportResult.Success(sourceName, created)
                    }
                }
            }

            // ── Single-track file ──
            onProgress?.invoke(1, 1, sourceName)
            val dest = File(dir, sourceName)
            if (dest.exists()) {
                return@withContext ImportResult.PartialSuccess(sourceName, emptyList(), listOf(sourceName))
            }
            dest.writeText(text)
            extractEarliestTime(text)?.let { dest.setLastModified(it) }
                ?: dest.setLastModified(sourceFile.lastModified())
            return@withContext ImportResult.Success(sourceName, listOf(sourceName))

        } catch (e: Exception) {
            android.util.Log.e("ConvoyTrackOps", "Import failed for $sourceName: ${e.message}")
            ImportResult.Failed(sourceName, e.message ?: "Unknown error")
        }
    }

    /**
     * Read file, extract earliest <time>, set as file's mtime.
     * Returns true if updated, false if no time found or file unchanged.
     */
    suspend fun fixDateFromContent(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext false
            val content = file.readText()
            val earliest = extractEarliestTime(content) ?: return@withContext false
            if (file.lastModified() == earliest) return@withContext false
            file.setLastModified(earliest)
            true
        } catch (e: Exception) {
            android.util.Log.e("ConvoyTrackOps", "fixDateFromContent failed: ${e.message}")
            false
        }
    }

    /**
     * Bulk fix-dates for a list of files.
     */
    suspend fun fixDatesForFiles(
        files: List<File>,
        onProgress: ((current: Int, total: Int, currentName: String) -> Unit)? = null
    ): FixDatesResult = withContext(Dispatchers.IO) {
        var updated = 0
        var unchanged = 0
        var failed = 0
        for ((i, f) in files.withIndex()) {
            onProgress?.invoke(i + 1, files.size, f.name)
            try {
                if (fixDateFromContent(f)) updated++ else unchanged++
            } catch (e: Exception) {
                failed++
            }
        }
        FixDatesResult(updated, unchanged, failed)
    }




    // ── GPX Waypoint/Route Data Classes ────────────────────────────

    data class GpxWaypoint(
        val name: String,
        val lat: Double,
        val lon: Double,
        val type: String,
        val description: String = ""
    )

    data class GpxRoute(
        val name: String,
        val points: List<Pair<Double, Double>>  // (lon, lat) pairs
    )

    data class ImportArtifactsSummary(
        val sourceName: String,
        val trackCount: Int,
        val waypointCount: Int,
        val routeCount: Int,
        val trackFiles: List<String>,
        val errors: List<String>,
        val inserted: Int = 0,   // ADDED 2026-06-02: tracks newly inserted
        val dropped: Int = 0     // ADDED 2026-06-02: tracks dropped as dupe (already in library)
    )

    // ── GPX Waypoint Parser ────────────────────────────────────────

    /**
     * Parse <wpt> elements from GPX text.
     * GPX format: <wpt lat="37.1" lon="-113.5"><name>...</name><type>...</type><desc>...</desc></wpt>
     */
    fun parseGpxWaypoints(gpxText: String): List<GpxWaypoint> {
        val results = mutableListOf<GpxWaypoint>()
        val wptPattern = Regex("""<wpt\s+lat="([^"]+)"\s+lon="([^"]+)"[^>]*>([\s\S]*?)</wpt>""")
        val namePattern = Regex("""<name>([^<]*)</name>""")
        val typePattern = Regex("""<type>([^<]*)</type>""")
        val descPattern = Regex("""<desc>([^<]*)</desc>""")

        for (match in wptPattern.findAll(gpxText)) {
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lon = match.groupValues[2].toDoubleOrNull() ?: continue
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
     */
    private fun mapWaypointType(rawType: String): String {
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
    fun parseGpxRoutes(gpxText: String): List<GpxRoute> {
        val results = mutableListOf<GpxRoute>()
        val rtePattern = Regex("""<rte>([\s\S]*?)</rte>""")
        val namePattern = Regex("""<name>([^<]*)</name>""")
        val rteptPattern = Regex("""<rtept\s+lat="([^"]+)"\s+lon="([^"]+)"""")

        for (match in rtePattern.findAll(gpxText)) {
            val inner = match.groupValues[1]
            val name = namePattern.find(inner)?.groupValues?.get(1)?.trim() ?: "Unnamed Route"
            val points = mutableListOf<Pair<Double, Double>>()

            for (ptMatch in rteptPattern.findAll(inner)) {
                val lat = ptMatch.groupValues[1].toDoubleOrNull() ?: continue
                val lon = ptMatch.groupValues[2].toDoubleOrNull() ?: continue
                points.add(Pair(lon, lat))  // WKT order: lon, lat
            }

            if (points.size >= 2) {
                results.add(GpxRoute(name, points))
            }
        }
        return results
    }

    // ── Full Artifact Import ───────────────────────────────────────

    /**
     * Import all artifact types from a GPX file:
     *   - Tracks: create GPX files in my_tracks/ + insert into spatial DB
     *   - Waypoints: insert into spatial DB waypoints table
     *   - Routes: insert into spatial DB tracks table (type='ROUTE')
     *
     * Source file is deleted from Downloads after successful import.
     *
     * @param sourceFile GPX file to import (typically from Downloads)
     * @param context Android context for SpatialDbManager init
     * @return ImportArtifactsSummary with counts per type
     */
    suspend fun importGpxAllArtifacts(
        sourceFile: File,
        context: android.content.Context
    ): ImportArtifactsSummary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sourceName = sourceFile.name
        val errors = mutableListOf<String>()
        val trackFiles = mutableListOf<String>()
        var waypointCount = 0
        var routeCount = 0

        try {
            if (!sourceFile.exists()) {
                return@withContext ImportArtifactsSummary(sourceName, 0, 0, 0, emptyList(), listOf("File not found"))
            }

            // Validate extension before reading (streaming — we never load the whole file)
            if (!sourceFile.extension.lowercase().let { it == "gpx" }) {
                return@withContext ImportArtifactsSummary(sourceName, 0, 0, 0, emptyList(), listOf("Not a GPX file"))
            }

            // Initialize spatial DB
            SpatialDbManager.init(context)
            val dir = tracksDir()
            if (!dir.exists()) dir.mkdirs()

            // ── 1. Process Tracks (<trk> elements) — STREAMED 2026-06-02 ──
            // Tier-2 streaming: read the file with a BufferedReader and accumulate ONE
            // <trk>..</trk> block at a time, process it, release it. The whole file is
            // never held in memory (was: readText + whole-file regex + findAll().toList(),
            // which OOM'd on large onX exports). Per-track parse/insert logic is reused.
            val namePattern = Regex("""<name>([^<]*)</name>""")
            val extPattern = Regex("""<extensions>[\s\S]*?</extensions>""")
            var trackIndex = 0
            var insertedCount = 0
            var droppedCount = 0

            sourceFile.bufferedReader().use { reader ->
                val block = StringBuilder()
                var inTrk = false
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val ln = line ?: continue
                    if (!inTrk) {
                        val s = ln.indexOf("<trk>")
                        if (s >= 0) { inTrk = true; block.setLength(0); block.append(ln.substring(s)).append("\n") }
                    } else {
                        block.append(ln).append("\n")
                    }
                    if (inTrk && ln.contains("</trk>")) {  // PERF 2026-06-02: check current line, not whole block (was O(n^2)/track)
                        // One complete <trk>..</trk> block accumulated. Process and release.
                        inTrk = false
                        val rawBlock = block.toString()
                        block.setLength(0)
                        val endIdx = rawBlock.indexOf("</trk>")
                        val trkWhole = rawBlock.substring(0, endIdx + "</trk>".length)
                        // strip extensions per-block (was whole-file)
                        val trkClean = extPattern.replace(trkWhole, "")
                        val trkContent = trkClean.removePrefix("<trk>").removeSuffix("</trk>")
                        trackIndex++
                        try {
                            val rawName = namePattern.find(trkContent)?.groupValues?.get(1)?.trim()
                                ?: "track_$trackIndex"
                            val baseName = rawName
                                .replace("/", "_").replace("\\", "_")
                                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                                .trim().ifEmpty { "track_$trackIndex" }
                            // IMPORT base fix (2026-06-29): DB-first, hash-named, like save/sync.
                            // Parse geometry FIRST so we can compute the hash that names the file.
                            val coords = SpatialDbManager.parseGpxTrackPoints("<trk>${trkContent}</trk>")
                            if (coords.isEmpty()) {
                                android.util.Log.w("Import", "skip no-geometry trk #$trackIndex ('$baseName')")
                                continue
                            }
                            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                            for (pair in coords) {
                                val lon = pair.first; val lat = pair.second
                                if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                                if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                            }
                            val wkt = "LINESTRING(" + coords.joinToString(",") { "${it.first} ${it.second}" } + ")"
                            val gh = SpatialDbManager.computeGeomHash(wkt)   // THE KEY -- names the file
                            val safeName = "$gh.gpx"

                            // DB record FIRST (derived name kept inside <trk><name>).
                            val wasNew = SpatialDbManager.insertTrackToDb(baseName, wkt, minLat, maxLat, minLon, maxLon)
                            if (wasNew) insertedCount++ else droppedCount++

                            // THEN the file, named by hash (collision-proof; identical geometry -> same file = natural dedup).
                            val dest = File(dir, safeName)
                            if (!dest.exists()) {
                                val singleGpx = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                    "<gpx version=\"1.1\" creator=\"GroupTrack\">\n" +
                                    "<trk>${trkContent}</trk>\n</gpx>"
                                dest.writeText(singleGpx)
                                extractEarliestTime(singleGpx)?.let { dest.setLastModified(it) }
                                    ?: dest.setLastModified(sourceFile.lastModified())
                            }

                            // metric feed: derive + write track_properties (reusable service; file now exists as <hash>.gpx)
                            SpatialDbManager.updateTrackPropertiesForHash(gh)

                            trackFiles.add(safeName)
                        } catch (e: Exception) {
                            errors.add("Track $trackIndex: ${e.message}")
                        }
                    }
                }
            }

            // ── 2. Process Waypoints (<wpt> elements) ──
            // TEMP BYPASS 2026-06-02: GPX import of waypoints/routes is a separate untested task.
            // In-app waypoint/route creation is UNAFFECTED. Re-enable the parseGpx* call when import
            // logic is built + tested. See STATE_OF_PLAY_2026-06-02.
            val waypoints = emptyList<GpxWaypoint>()  // was: parseGpxWaypoints(text)
            for (wpt in waypoints) {
                try {
                    SpatialDbManager.insertWaypoint(wpt.name, wpt.lat, wpt.lon, wpt.type)
                    waypointCount++
                } catch (e: Exception) {
                    errors.add("Waypoint ${wpt.name}: ${e.message}")
                }
            }

            // ── 3. Process Routes (<rte> elements) ──
            // TEMP BYPASS 2026-06-02: GPX import of waypoints/routes is a separate untested task.
            // In-app waypoint/route creation is UNAFFECTED. Re-enable the parseGpx* call when import
            // logic is built + tested. See STATE_OF_PLAY_2026-06-02.
            val routes = emptyList<GpxRoute>()  // was: parseGpxRoutes(text)
            for (route in routes) {
                try {
                    val pts = route.points
                    var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                    var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                    for ((lon, lat) in pts) {
                        if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                        if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                    }
                    val wkt = "LINESTRING(" + pts.joinToString(",") { "${it.first} ${it.second}" } + ")"
                    SpatialDbManager.insertRoute(route.name, wkt, minLat, maxLat, minLon, maxLon)
                    routeCount++
                } catch (e: Exception) {
                    errors.add("Route ${route.name}: ${e.message}")
                }
            }

            // ── 4. Delete source file after successful import ──
            val totalImported = trackFiles.size + waypointCount + routeCount
            if (totalImported > 0) {
                try {
                    sourceFile.delete()
                    android.util.Log.i("Import", "Deleted source file: $sourceName")
                } catch (e: Exception) {
                    android.util.Log.w("Import", "Could not delete source: ${e.message}")
                }
            }

            android.util.Log.i("Import", "Import complete: ${trackFiles.size} tracks, $waypointCount waypoints, $routeCount routes from $sourceName")
            ImportArtifactsSummary(sourceName, trackFiles.size, waypointCount, routeCount, trackFiles, errors, insertedCount, droppedCount)

        } catch (e: Exception) {
            android.util.Log.e("Import", "Import failed for $sourceName: ${e.message}")
            ImportArtifactsSummary(sourceName, 0, 0, 0, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }



    /** Share artifact as GPX via email intent. Creates temp file, attaches, deletes after. */
    fun shareGpx(context: android.content.Context, name: String, gpxContent: String) {
        try {
            val tempFile = java.io.File(context.cacheDir, "${name.replace(" ", "_")}.gpx")
            tempFile.writeText(gpxContent, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".provider", tempFile)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "GroupTrack: $name")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share $name"))
            // Temp file cleaned up on next cache clear
        } catch (e: Exception) {
            android.util.Log.e("TrackOps", "shareGpx failed: ${e.message}")
            android.widget.Toast.makeText(context, "Share failed: ${e.message}",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Export GPX to Downloads folder. File stays there. */
    fun exportGpxToDownloads(name: String, gpxContent: String): Boolean {
        return try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(downloadsDir, "${name.replace(" ", "_")}.gpx")
            file.writeText(gpxContent, Charsets.UTF_8)
            android.util.Log.i("TrackOps", "Exported to Downloads: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            android.util.Log.e("TrackOps", "exportGpxToDownloads failed: ${e.message}")
            false
        }
    }

}
