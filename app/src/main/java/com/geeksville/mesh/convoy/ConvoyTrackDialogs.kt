package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Reusable track action sheet. Shows Rename / Delete / Share / Move options.
 * Pass null for actions you do not want to expose.
 */
@Composable
fun TrackActionDialog(
    file: File?,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onMoveToDownloads: (() -> Unit)? = null,
    onFixDate: (() -> Unit)? = null
) {
    if (file == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, fontSize = 14.sp) },
        text = {
            Column {
                if (onRename != null) ActionRow("Rename") { onRename() }
                if (onDelete != null) ActionRow("Delete") { onDelete() }
                if (onShare != null) ActionRow("Share") { onShare() }
                if (onMoveToDownloads != null) ActionRow("Move to Downloads") { onMoveToDownloads() }
                if (onFixDate != null) ActionRow("Fix Creation Date") { onFixDate() }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF39FF14),
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    )
}

@Composable
fun RenameTrackDialog(
    file: File?,
    onDismiss: () -> Unit,
    onConfirm: (newBaseName: String) -> Unit
) {
    if (file == null) return
    var text by remember(file) { mutableStateOf(file.nameWithoutExtension) }
    val ext = file.extension

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color(0xFF39FF14)),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF1A2233), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(".$ext will be preserved",
                    color = Color(0xFF7A8DA0), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newName = text.trim()
                    if (newName.isNotEmpty()) onConfirm(newName)
                }
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun DeleteTrackDialog(
    file: File?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (file == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete?") },
        text = { Text("Permanently delete ${file.name}?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = Color(0xFFFF4444))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
