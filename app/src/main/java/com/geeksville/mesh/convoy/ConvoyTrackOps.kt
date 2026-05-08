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

            val text = sourceFile.readText()
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


}
