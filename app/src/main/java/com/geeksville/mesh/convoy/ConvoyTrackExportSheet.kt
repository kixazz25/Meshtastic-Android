package com.geeksville.mesh.convoy

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ConvoyTrackExportSheet
 *
 * Bottom sheet for exporting GPX/KML track files to the device Downloads folder.
 * Scans filesDir/my_tracks/ on open. User searches, selects one or more files,
 * taps EXPORT SELECTED. Files are copied via MediaStore API (Android 10+).
 *
 * Temporary solution -- V2.4.x. Track management moves to Map Manager in V3.0 Phase C.
 */
@Composable
fun ConvoyTrackExportSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // Track file data class
    data class TrackFile(
        val file:     File,
        val name:     String,   // display name without date/ext
        val dateStr:  String,   // parsed from filename
        val sizeKb:   Long,
        val format:   String    // GPX or KML
    )

    var allFiles    by remember { mutableStateOf<List<TrackFile>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selected    by remember { mutableStateOf<Set<String>>(emptySet()) }
    var statusMsg   by remember { mutableStateOf("") }
    var exporting   by remember { mutableStateOf(false) }

    // Parse filename: trackName_yyyyMMdd_HHmmss.ext
    fun parseTrackFile(f: File): TrackFile {
        val nameNoExt = f.nameWithoutExtension
        val fmt       = f.extension.uppercase()
        // Try to extract date from last two underscore segments
        val parts = nameNoExt.split("_")
        val dateStr = try {
            if (parts.size >= 2) {
                val datePart = parts[parts.size - 2]
                val timePart = parts[parts.size - 1]
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val sdfOut = SimpleDateFormat("MMM d yyyy  HH:mm", Locale.US)
                sdfOut.format(sdf.parse("${datePart}_${timePart}") ?: f.lastModified().let { Date(it) })
            } else ""
        } catch (e: Exception) {
            SimpleDateFormat("MMM d yyyy", Locale.US).format(Date(f.lastModified()))
        }
        val displayName = parts.dropLast(2).joinToString(" ").ifEmpty { nameNoExt }
        return TrackFile(
            file    = f,
            name    = displayName,
            dateStr = dateStr,
            sizeKb  = f.length() / 1024,
            format  = fmt
        )
    }

    // Scan my_tracks/ on open
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "my_tracks")
            val files = if (dir.exists()) {
                dir.listFiles { f -> f.extension.lowercase() in listOf("gpx", "kml") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { parseTrackFile(it) }
                    ?: emptyList()
            } else emptyList()
            allFiles = files
        }
        statusMsg = if (allFiles.isEmpty()) "No tracks found in my_tracks/" else ""
    }

    val filtered = allFiles.filter {
        searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
    }
    val allSelected = filtered.isNotEmpty() && filtered.all { it.file.name in selected }

    // Export selected files to Downloads
    fun exportSelected() {
        exporting = true
        statusMsg = "Exporting..."
        scope.launch(Dispatchers.IO) {
            var copied = 0
            var failed = 0
            val toExport = allFiles.filter { it.file.name in selected }
            for (track in toExport) {
                try {
                    val mimeType = if (track.format == "GPX")
                        "application/gpx+xml"
                    else
                        "application/vnd.google-earth.kml+xml"
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, track.file.name)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            track.file.inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        copied++
                        android.util.Log.i("ConvoyExport", "Exported: ${track.file.name}")
                    } else {
                        failed++
                        android.util.Log.e("ConvoyExport", "Insert failed: ${track.file.name}")
                    }
                } catch (e: Exception) {
                    failed++
                    android.util.Log.e("ConvoyExport", "Error: ${track.file.name} -- ${e.message}")
                }
            }
            withContext(Dispatchers.Main) {
                exporting = false
                statusMsg = when {
                    failed == 0 -> "✓ $copied track${if (copied != 1) "s" else ""} copied to Downloads"
                    copied == 0 -> "✗ Export failed -- check storage permissions"
                    else        -> "$copied copied, $failed failed"
                }
            }
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101510))
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        // Header
        Text(
            text          = "EXPORT TRACKS",
            color         = Color(0xFF97D5A5),
            fontSize      = 13.sp,
            fontFamily    = FontFamily.Monospace,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 4.sp,
            textAlign     = TextAlign.Center,
            modifier      = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        )

        // Search field
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape    = RoundedCornerShape(8.dp),
            color    = Color(0xFF1C211C)
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value         = searchQuery,
                onValueChange = { searchQuery = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle     = androidx.compose.ui.text.TextStyle(
                    color      = Color(0xFFDFE4DC),
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace
                ),
                decorationBox = { inner ->
                    if (searchQuery.isEmpty()) {
                        Text(
                            "Search tracks...",
                            color      = Color(0xFF4A6A4A),
                            fontSize   = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    inner()
                }
            )
        }

        // SELECT ALL / count row
        if (filtered.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text       = "${selected.size} of ${allFiles.size} selected",
                    color      = Color(0xFF8B938A),
                    fontSize   = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Surface(
                    modifier = Modifier.clickable {
                        selected = if (allSelected)
                            selected - filtered.map { it.file.name }.toSet()
                        else
                            selected + filtered.map { it.file.name }.toSet()
                    },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF1C3A1C)
                ) {
                    Text(
                        text     = if (allSelected) "DESELECT ALL" else "SELECT ALL",
                        color    = Color(0xFF97D5A5),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // File list
        if (allFiles.isEmpty()) {
            Text(
                text       = "No GPX or KML files found in my_tracks/",
                color      = Color(0xFF8B938A),
                fontSize   = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                items(filtered) { track ->
                    val isSelected = track.file.name in selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable {
                                selected = if (isSelected)
                                    selected - track.file.name
                                else
                                    selected + track.file.name
                            },
                        shape = RoundedCornerShape(7.dp),
                        color = if (isSelected) Color(0xFF1C3A1C) else Color(0xFF1C211C)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Checkmark
                            Text(
                                text     = if (isSelected) "✓" else "○",
                                color    = if (isSelected) Color(0xFF97D5A5) else Color(0xFF4A6A4A),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            // Name + date
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text       = track.name,
                                    color      = if (isSelected) Color(0xFFDFE4DC) else Color(0xFF8B938A),
                                    fontSize   = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text       = track.dateStr,
                                    color      = Color(0xFF4A6A4A),
                                    fontSize   = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            // Size + format badge
                            Column(
                                horizontalAlignment = Alignment.End
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (track.format == "GPX")
                                        Color(0xFF1A3A2A) else Color(0xFF1A2A3A)
                                ) {
                                    Text(
                                        text     = track.format,
                                        color    = if (track.format == "GPX")
                                            Color(0xFF97D5A5) else Color(0xFF4AB8E8),
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text       = "${track.sizeKb} KB",
                                    color      = Color(0xFF4A6A4A),
                                    fontSize   = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier   = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Status message
        if (statusMsg.isNotEmpty()) {
            Text(
                text       = statusMsg,
                color      = if (statusMsg.startsWith("✓"))
                    Color(0xFF97D5A5) else Color(0xFFFFB74D),
                fontSize   = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        // EXPORT SELECTED button
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = selected.isNotEmpty() && !exporting) {
                    exportSelected()
                },
            shape = RoundedCornerShape(10.dp),
            color = if (selected.isNotEmpty() && !exporting)
                Color(0xFF15512C) else Color(0xFF1C211C)
        ) {
            Text(
                text = when {
                    exporting           -> "EXPORTING..."
                    selected.isEmpty()  -> "SELECT TRACKS TO EXPORT"
                    else                -> "EXPORT ${selected.size} TRACK${if (selected.size != 1) "S" else ""} TO DOWNLOADS"
                },
                color = if (selected.isNotEmpty() && !exporting)
                    Color(0xFF97D5A5) else Color(0xFF4A6A4A),
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.padding(vertical = 14.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // CLOSE
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDismiss() },
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF2A1A1A)
        ) {
            Text(
                text       = "✕  CLOSE",
                color      = Color(0xFFFFB4AB),
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.padding(vertical = 12.dp)
            )
        }
    }
}
