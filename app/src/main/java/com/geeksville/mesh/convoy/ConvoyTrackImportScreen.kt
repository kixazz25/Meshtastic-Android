package com.geeksville.mesh.convoy

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.util.Date
import java.util.Locale

/**
 * ConvoyTrackImportScreen -- V2.4 in-app file browser for track import.
 *
 * Replaces the system file picker to eliminate ANR.
 * Scans /sdcard/Download/ for .gpx and .kml files.
 * Multi-select with checkboxes, progress dialog, recap dialog.
 * Uses existing ConvoyTrackOps.importTrackFile() for all processing.
 * Date correction is built into importTrackFile -- each created file
 * gets its mtime set from the earliest GPS <time> in the track data.
 */
@Composable
fun ConvoyTrackImportScreen(onDismiss: () -> Unit) {

    // -- State --------------------------------------------------------
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanning by remember { mutableStateOf(true) }

    // Progress dialog state
    var showProgress by remember { mutableStateOf(false) }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var progressName by remember { mutableStateOf("") }

    // Recap dialog state
    var showRecap by remember { mutableStateOf(false) }
    var recapImported by remember { mutableStateOf<List<String>>(emptyList()) }
    var recapSkipped by remember { mutableStateOf<List<String>>(emptyList()) }
    var recapFailed by remember { mutableStateOf<List<String>>(emptyList()) }
    var recapDatesCorrected by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()

    // -- Scan Downloads on launch -------------------------------------
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dlDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val found = dlDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in listOf("gpx", "kml") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            withContext(Dispatchers.Main) {
                files = found
                scanning = false
            }
        }
    }

    // -- Import handler -----------------------------------------------
    fun doImport() {
        val sel = files.filter { selected.contains(it.name) }
        if (sel.isEmpty()) return
        showProgress = true
        progressCurrent = 0
        progressTotal = sel.size
        scope.launch {
            val imported = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val failed = mutableListOf<String>()
            var datesCorrected = 0

            for ((i, f) in sel.withIndex()) {
                progressCurrent = i + 1
                progressName = f.name
                val result = ConvoyTrackOps.importTrackFile(f)
                when (result) {
                    is ConvoyTrackOps.ImportResult.Success -> {
                        imported.addAll(result.createdFiles)
                        datesCorrected += result.createdFiles.size
                    }
                    is ConvoyTrackOps.ImportResult.PartialSuccess -> {
                        imported.addAll(result.createdFiles)
                        skipped.addAll(result.skippedFiles)
                        datesCorrected += result.createdFiles.size
                    }
                    is ConvoyTrackOps.ImportResult.Failed -> {
                        failed.add("${result.sourceName}: ${result.reason}")
                    }
                }
            }
            recapImported = imported
            recapSkipped = skipped
            recapFailed = failed
            recapDatesCorrected = datesCorrected
            showProgress = false
            showRecap = true
        }
    }

    // -- Dialogs ------------------------------------------------------
    if (showProgress) {
        ImportProgressDialog(
            current = progressCurrent,
            total = progressTotal,
            currentName = progressName
        )
    }
    if (showRecap) {
        ImportRecapDialog(
            imported = recapImported,
            skipped = recapSkipped,
            failed = recapFailed,
            datesCorrected = recapDatesCorrected,
            onDismiss = { showRecap = false; onDismiss() }
        )
    }

    // -- Main UI ------------------------------------------------------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101510))
    ) {
        // -- Top bar --
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A2A1A))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "\u2190",
                color = Color(0xFF97D5A5),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(end = 16.dp)
            )
            Column {
                Text(
                    "Import Tracks from Downloads",
                    color = Color(0xFF97D5A5),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (scanning) "Scanning Downloads folder..."
                    else "${files.size} GPX/KML files found",
                    color = Color(0xFFC1C9BF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // -- Content area --
        if (scanning) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF39FF14))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Scanning Downloads...",
                        color = Color(0xFF8B938A),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No GPX or KML files found\nin Downloads.",
                        color = Color(0xFF8B938A),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.clickable { onDismiss() },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF262B26)
                    ) {
                        Text(
                            "  Back to Map  ",
                            color = Color(0xFF97D5A5),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        } else {
            // -- File list --
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(files, key = { it.absolutePath }) { file ->
                    val checked = selected.contains(file.name)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - file.name
                                else selected + file.name
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (checked) Color(0xFF15512C) else Color(0xFF262B26)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selected = if (it) selected + file.name
                                    else selected - file.name
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF39FF14),
                                    uncheckedColor = Color(0xFF445566),
                                    checkmarkColor = Color(0xFF101510)
                                )
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp)
                            ) {
                                Text(
                                    file.name,
                                    color = Color(0xFFDFE4DC),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        formatFileSize(file.length()),
                                        color = Color(0xFF8B938A),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        formatFileDate(file.lastModified()),
                                        color = Color(0xFF8B938A),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        file.extension.uppercase(),
                                        color = Color(0xFF6B8F71),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // -- Bottom bar -- Select All + Import button --
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                color = Color(0xFF1A2A1A)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Select All row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (selected.size == files.size) emptySet()
                                else files.map { it.name }.toSet()
                            }
                    ) {
                        Checkbox(
                            checked = selected.size == files.size && files.isNotEmpty(),
                            onCheckedChange = { checked ->
                                selected = if (checked) files.map { it.name }.toSet()
                                else emptySet()
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF39FF14),
                                uncheckedColor = Color(0xFF445566),
                                checkmarkColor = Color(0xFF101510)
                            )
                        )
                        Text(
                            "Select All  (${selected.size} of ${files.size})",
                            color = Color(0xFFC1C9BF),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // Import button
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = selected.isNotEmpty()) { doImport() },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected.isNotEmpty()) Color(0xFF15512C)
                        else Color(0xFF1C211C)
                    ) {
                        Text(
                            text = if (selected.isEmpty()) "Select files to import"
                            else "Import ${selected.size} Selected",
                            color = if (selected.isNotEmpty()) Color(0xFF97D5A5)
                            else Color(0xFF8B938A),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

// -- Import Progress Dialog -------------------------------------------

@Composable
private fun ImportProgressDialog(current: Int, total: Int, currentName: String) {
    AlertDialog(
        onDismissRequest = { /* non-dismissable */ },
        confirmButton = {},
        title = {
            Text(
                "Importing Tracks",
                color = Color(0xFF97D5A5),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(color = Color(0xFF39FF14))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Processing $current of $total",
                    color = Color(0xFFDFE4DC),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    currentName,
                    color = Color(0xFFC1C9BF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        },
        containerColor = Color(0xFF1C211C),
        shape = RoundedCornerShape(16.dp)
    )
}

// -- Import Recap Dialog ----------------------------------------------

@Composable
private fun ImportRecapDialog(
    imported: List<String>,
    skipped: List<String>,
    failed: List<String>,
    datesCorrected: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "OK",
                    color = Color(0xFF97D5A5),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        title = {
            Text(
                "Import Complete",
                color = Color(0xFF97D5A5),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        },
        text = {
            Column {
                // Summary counts
                if (imported.isNotEmpty()) {
                    Text(
                        "${imported.size} tracks imported",
                        color = Color(0xFF39FF14),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (skipped.isNotEmpty()) {
                    Text(
                        "${skipped.size} skipped (already exist)",
                        color = Color(0xFFFFB74D),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (failed.isNotEmpty()) {
                    Text(
                        "${failed.size} failed",
                        color = Color(0xFFFFB4AB),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (datesCorrected > 0) {
                    Text(
                        "Dates corrected: $datesCorrected of ${imported.size}",
                        color = Color(0xFFC1C9BF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Imported track names (show up to 20)
                if (imported.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Imported:",
                        color = Color(0xFFC1C9BF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    val shown = imported.take(20)
                    for (name in shown) {
                        Text(
                            "\u2022 $name",
                            color = Color(0xFF8B938A),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (imported.size > 20) {
                        Text(
                            "  ...and ${imported.size - 20} more",
                            color = Color(0xFF8B938A),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Failed details
                if (failed.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Failed:",
                        color = Color(0xFFFFB4AB),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    for (msg in failed.take(10)) {
                        Text(
                            "\u2022 $msg",
                            color = Color(0xFFFFB4AB),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFF1C211C),
        shape = RoundedCornerShape(16.dp)
    )
}

// -- Helper functions -------------------------------------------------

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatFileDate(millis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
