package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FilterMode { ALL, SAVED, IN_PROGRESS }
private enum class SortMode { DATE_DESC, DATE_ASC, NAME_ASC, NAME_DESC }

/**
 * "Work With Tracks" full-screen manager. Function name preserved
 * for caller compatibility.
 */
@Composable
fun ConvoyTrackExportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var allTracks by remember { mutableStateOf<List<File>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.DATE_DESC) }
    var filterMode by remember { mutableStateOf(FilterMode.ALL) }
    var refreshTick by remember { mutableStateOf(0) }

    var actionTarget by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTick) {
        allTracks = ConvoyTrackOps.listTracks()
    }

    val visibleTracks = remember(allTracks, searchText, sortMode, filterMode, refreshTick) {
        allTracks
            .filter { f ->
                when (filterMode) {
                    FilterMode.ALL -> true
                    FilterMode.SAVED -> !ConvoyTrackOps.isInProgress(f)
                    FilterMode.IN_PROGRESS -> ConvoyTrackOps.isInProgress(f)
                }
            }
            .filter { f ->
                searchText.isBlank() || f.name.contains(searchText, ignoreCase = true)
            }
            .let { list ->
                when (sortMode) {
                    SortMode.DATE_DESC -> list.sortedByDescending { it.lastModified() }
                    SortMode.DATE_ASC -> list.sortedBy { it.lastModified() }
                    SortMode.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                    SortMode.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
                }
            }
    }

    val dateFmt = remember { SimpleDateFormat("MMM d yyyy, HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1020))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Work With Tracks",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color(0xFF39FF14), fontSize = 14.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${visibleTracks.size} of ${allTracks.size} tracks",
                color = Color(0xFF7A8DA0), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f))
            Surface(
                modifier = Modifier.clickable {
                    if (visibleTracks.isNotEmpty()) {
                        scope.launch {
                            statusMsg = "Fixing dates..."
                            val result = ConvoyTrackOps.fixDatesForFiles(visibleTracks) { cur, total, _ ->
                                statusMsg = "Fixing dates  $cur / $total"
                            }
                            statusMsg = "Dates fixed: ${result.updated} updated, ${result.unchanged} unchanged" +
                                if (result.failed > 0) ", ${result.failed} failed" else ""
                            refreshTick++
                        }
                    }
                },
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1A2233)
            ) {
                Text("Fix Dates",
                    color = Color(0xFF39FF14), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))

        BasicTextField(
            value = searchText,
            onValueChange = { searchText = it },
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Color(0xFF39FF14)),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF1A2233), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            decorationBox = { inner ->
                if (searchText.isEmpty())
                    Text("Search tracks...", color = Color(0xFF445566), fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace)
                inner()
            }
        )
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.clickable {
                    sortMode = when (sortMode) {
                        SortMode.DATE_DESC -> SortMode.DATE_ASC
                        SortMode.DATE_ASC -> SortMode.NAME_ASC
                        SortMode.NAME_ASC -> SortMode.NAME_DESC
                        SortMode.NAME_DESC -> SortMode.DATE_DESC
                    }
                },
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1A2233)
            ) {
                Text(
                    when (sortMode) {
                        SortMode.DATE_DESC -> "Date ↓"
                        SortMode.DATE_ASC -> "Date ↑"
                        SortMode.NAME_ASC -> "Name ↑"
                        SortMode.NAME_DESC -> "Name ↓"
                    },
                    color = Color(0xFF39FF14),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            FilterTab("All", filterMode == FilterMode.ALL) { filterMode = FilterMode.ALL }
            Spacer(Modifier.width(4.dp))
            FilterTab("Saved", filterMode == FilterMode.SAVED) { filterMode = FilterMode.SAVED }
            Spacer(Modifier.width(4.dp))
            FilterTab("In-Progress", filterMode == FilterMode.IN_PROGRESS) { filterMode = FilterMode.IN_PROGRESS }
        }
        Spacer(Modifier.height(8.dp))

        if (visibleTracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center) {
                Text("No tracks", color = Color(0xFF7A8DA0), fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(visibleTracks, key = { it.absolutePath }) { f ->
                    TrackRow(
                        file = f,
                        dateFmt = dateFmt,
                        onClick = { actionTarget = f }
                    )
                    Divider(color = Color(0xFF1A2233), thickness = 1.dp)
                }
            }
        }

        statusMsg?.let { msg ->
            LaunchedEffect(msg) {
                delay(2500)
                statusMsg = null
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = Color(0xFF1A2233),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(msg, color = Color(0xFF39FF14), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp))
            }
        }
    }

    TrackActionDialog(
        file = actionTarget,
        onDismiss = { actionTarget = null },
        onRename = { renameTarget = actionTarget; actionTarget = null },
        onDelete = { deleteTarget = actionTarget; actionTarget = null },
        onShare = {
            val f = actionTarget; actionTarget = null
            f?.let { ConvoyTrackOps.shareTrack(context, it) }
        },
        onMoveToDownloads = {
            val f = actionTarget; actionTarget = null
            f?.let {
                scope.launch {
                    val ok = ConvoyTrackOps.copyToDownloads(it)
                    statusMsg = if (ok) "Copied to Downloads" else "Copy failed"
                }
            }
        },
        onFixDate = {
            val f = actionTarget; actionTarget = null
            f?.let {
                scope.launch {
                    val ok = ConvoyTrackOps.fixDateFromContent(it)
                    statusMsg = if (ok) "Date updated" else "No <time> found in file"
                    refreshTick++
                }
            }
        }
    )

    RenameTrackDialog(
        file = renameTarget,
        onDismiss = { renameTarget = null },
        onConfirm = { newName ->
            val f = renameTarget; renameTarget = null
            f?.let {
                scope.launch {
                    val result = ConvoyTrackOps.renameTrack(it, newName)
                    statusMsg = when (result) {
                        is ConvoyTrackOps.RenameResult.Success -> "Renamed"
                        is ConvoyTrackOps.RenameResult.NameExists -> "Name already exists"
                        is ConvoyTrackOps.RenameResult.Failed -> "Rename failed"
                    }
                    refreshTick++
                }
            }
        }
    )

    DeleteTrackDialog(
        file = deleteTarget,
        onDismiss = { deleteTarget = null },
        onConfirm = {
            val f = deleteTarget; deleteTarget = null
            f?.let {
                scope.launch {
                    val ok = ConvoyTrackOps.deleteTrack(it)
                    statusMsg = if (ok) "Deleted" else "Delete failed"
                    refreshTick++
                }
            }
        }
    )
}

@Composable
private fun FilterTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        color = if (selected) Color(0xFF2E75B6) else Color(0xFF1A2233)
    ) {
        Text(label,
            color = if (selected) Color.White else Color(0xFF7A8DA0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

@Composable
private fun TrackRow(
    file: File,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    val isInProgress = ConvoyTrackOps.isInProgress(file)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = RoundedCornerShape(4.dp),
            color = if (isInProgress) Color(0xFFCC8800) else Color(0xFF39FF14)
        ) {}
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, color = Color.White, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(
                "${dateFmt.format(Date(file.lastModified()))}    ${ConvoyTrackOps.formatSize(file.length())}",
                color = Color(0xFF7A8DA0), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text("⋮", color = Color(0xFF7A8DA0), fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}
