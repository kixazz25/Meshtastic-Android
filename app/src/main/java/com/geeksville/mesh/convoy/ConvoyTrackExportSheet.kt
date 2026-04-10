package com.geeksville.mesh.convoy

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.io.File

/**
 * ConvoyTrackExportSheet
 *
 * Track file manager — opens Android file manager at Documents/my_tracks/.
 * All file operations (move, delete, copy, email, create folders) handled
 * natively by the OS file manager. No custom copy-to-Downloads needed.
 *
 * V2.4.x — full track management UI moves to Map Manager in V3.0 Phase C.
 */
@Composable
fun ConvoyTrackExportSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val tracksDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "my_tracks"
    )

    // Count files for display
    val fileCount = remember {
        if (tracksDir.exists())
            tracksDir.listFiles { f -> f.extension.lowercase() in listOf("gpx", "kml") }?.size ?: 0
        else 0
    }

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
                "TRACK MANAGER",
                color = Color(0xFF97D5A5), fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                "X", color = Color(0xFF8B938A), fontSize = 18.sp,
                modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Location info
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF0A100A)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Documents/my_tracks/",
                    color = Color(0xFF4A6080), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$fileCount GPX/KML file(s)",
                    color = Color(0xFF97D5A5), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Open File Manager button
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        // Ensure directory exists
                        if (!tracksDir.exists()) tracksDir.mkdirs()

                        // Open file manager at Documents/my_tracks/
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                Uri.parse("content://com.android.externalstorage.documents/document/primary:Documents/my_tracks"),
                                "vnd.android.document/directory"
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        // Fallback to generic file manager
                        val fallback = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                Uri.fromFile(tracksDir),
                                "resource/folder"
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(fallback)
                        }
                        onDismiss()
                    } catch (e: Exception) {
                        // If no file manager available, do nothing
                    }
                },
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1A3A2A)
        ) {
            Text(
                "OPEN FILE MANAGER",
                color = Color(0xFF97D5A5), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 14.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Move, delete, copy, email, create folders — all from your device file manager.",
            color = Color(0xFF4A6080), fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))
    }
}
