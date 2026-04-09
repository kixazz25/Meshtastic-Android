package com.geeksville.mesh.convoy
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import java.text.SimpleDateFormat
import java.util.*

/**
 * ConvoyTrackExportSheet
 *
 * Exports GPX/KML track files to the device Downloads folder.
 * Uses Android file picker (OpenMultipleDocuments) — no permissions required.
 * Works on all Android versions. User selects files from any location.
 *
 * V2.4.x — Track management moves to Map Manager in V3.0 Phase C.
 */
@Composable
fun ConvoyTrackExportSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    data class PickedFile(
        val uri:      Uri,
        val name:     String,
        val sizeKb:   Long,
        val format:   String    // GPX or KML
    )

    var pickedFiles by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var selected    by remember { mutableStateOf<Set<String>>(emptySet()) }
    var statusMsg   by remember { mutableStateOf("Tap SELECT FILES to choose tracks") }
    var exporting   by remember { mutableStateOf(false) }

    // File picker — opens system file browser filtered to GPX/KML
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { uri ->
                try {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    var name = "track"
                    cursor?.use {
                        val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (it.moveToFirst()) {
                            if (nameIdx >= 0) name = it.getString(nameIdx) ?: "track"
                        }
                    }
                    val ext = name.substringAfterLast('.', "").uppercase()
                    if (ext !in listOf("GPX", "KML")) return@mapNotNull null
                    val size = try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd -> fd.statSize / 1024L } ?: 0L
                    } catch (e: Exception) { 0L }
                    PickedFile(uri = uri, name = name, sizeKb = size, format = ext)
                } catch (e: Exception) { null }
            }
            pickedFiles = files
            selected = files.map { it.name }.toSet()
            statusMsg = if (files.isEmpty()) "No GPX or KML files selected" else "${files.size} file(s) selected"
        }
    }

    // Export selected files to Downloads
    fun exportSelected() {
        exporting = true
        statusMsg = "Exporting..."
        scope.launch(Dispatchers.IO) {
            var copied = 0
            var failed = 0
            val toExport = pickedFiles.filter { it.name in selected }
            for (track in toExport) {
                try {
                    val mimeType = if (track.format == "GPX")
                        "application/gpx+xml"
                    else
                        "application/vnd.google-earth.kml+xml"
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, track.name)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (destUri != null) {
                        resolver.openOutputStream(destUri)?.use { out ->
                            resolver.openInputStream(track.uri)?.use { inp -> inp.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(destUri, values, null, null)
                        copied++
                    } else { failed++ }
                } catch (e: Exception) { failed++ }
            }
            exporting = false
            statusMsg = when {
                failed == 0 -> "Exported $copied file(s) to Downloads"
                copied == 0 -> "Export failed ($failed errors)"
                else        -> "Exported $copied, failed $failed"
            }
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101510))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "EXPORT TRACKS",
                color = Color(0xFF97D5A5), fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                "✕", color = Color(0xFF8B938A), fontSize = 18.sp,
                modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // SELECT FILES button
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !exporting) {
                    filePicker.launch(arrayOf("application/gpx+xml", "application/vnd.google-earth.kml+xml", "*/*"))
                },
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1A3A2A)
        ) {
            Text(
                "📂  SELECT FILES",
                color = Color(0xFF97D5A5), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 14.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Status message
        if (statusMsg.isNotEmpty()) {
            Text(
                statusMsg,
                color = if (statusMsg.contains("Exported")) Color(0xFF97D5A5)
                        else if (statusMsg.contains("failed") || statusMsg.contains("No ")) Color(0xFFFFB4AB)
                        else Color(0xFF8B938A),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
        }

        // File list
        if (pickedFiles.isNotEmpty()) {
            // Select all toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selected = if (selected.size == pickedFiles.size) emptySet()
                                   else pickedFiles.map { it.name }.toSet()
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (selected.size == pickedFiles.size) "☑" else "☐",
                    color = Color(0xFF97D5A5), fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "SELECT ALL (${pickedFiles.size})",
                    color = Color(0xFF8B938A), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            HorizontalDivider(color = Color(0xFF262B26))
            Spacer(Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(pickedFiles) { track ->
                    val isSelected = track.name in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (isSelected) selected - track.name
                                           else selected + track.name
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isSelected) "☑" else "☐",
                            color = if (isSelected) Color(0xFF97D5A5) else Color(0xFF8B938A),
                            fontSize = 14.sp, fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.name,
                                color = Color(0xFFD4D8D4), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${track.format}  •  ${track.sizeKb} KB",
                                color = Color(0xFF8B938A), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1A1F1A))
                }
            }

            Spacer(Modifier.height(12.dp))

            // EXPORT SELECTED button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = selected.isNotEmpty() && !exporting) { exportSelected() },
                shape = RoundedCornerShape(10.dp),
                color = if (selected.isNotEmpty() && !exporting) Color(0xFF15512C) else Color(0xFF1C211C)
            ) {
                Text(
                    if (exporting) "EXPORTING..." else "EXPORT SELECTED (${selected.size})",
                    color = if (selected.isNotEmpty() && !exporting) Color(0xFF97D5A5) else Color(0xFF8B938A),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
