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
}
