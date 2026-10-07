package com.geeksville.mesh.convoy

import com.geeksville.mesh.BuildConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.geeksville.mesh.convoy.ConvoyViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoySettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMapSources: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToRideCreate: () -> Unit = {},
    viewModel: ConvoySettingsViewModel = hiltViewModel(),
    convoyViewModel: ConvoyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val trackLeadOnly by convoyViewModel.trackLeadOnly.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onUserMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convoy Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── Map Sources ──────────────────────────────────────────────
            // PROFILE-2026-09-22: the rider profile -- same screen, edit mode.
            // CONVSETTINGS-2026-09-24: the temporary "Create a ride (shell)" row is gone -- rides are created
            // from ADD A RIDE on the planner. The storage-conversion RECORD moved here from the old
            // radio-setup menu (the conversion itself runs at startup, in the gate).
            var showConversionRecord by remember { mutableStateOf(false) }
            SectionLabel("Storage")
            androidx.compose.material3.ListItem(
                headlineContent = { Text("View conversion record", style = MaterialTheme.typography.bodyLarge) },
                supportingContent = { Text("What moved to private storage, what it weighed, and what failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { showConversionRecord = true }
            )
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            if (showConversionRecord) {
                val convCtx = androidx.compose.ui.platform.LocalContext.current
                val recordJson = remember { GroupTrackConversion.readRecord(convCtx) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showConversionRecord = false },
                    confirmButton = { TextButton(onClick = { showConversionRecord = false }) { Text("CLOSE") } },
                    dismissButton = {
                        // SHARE, not just display: on a rider's device this is the only way the record
                        // reaches anyone who can read it.
                        TextButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "GroupTrack conversion record")
                                putExtra(android.content.Intent.EXTRA_TEXT, recordJson ?: "no record")
                            }
                            convCtx.startActivity(android.content.Intent.createChooser(send, "Send record"))
                        }) { Text("SHARE") }
                    },
                    title = { Text("Conversion record") },
                    text = {
                        Text(recordJson ?: "No record yet \u2014 the conversion has not run.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                )
            }

            // ROUTEFILES-2026-10-07 (Fred): see and clean up the AI-create route files in-app.
            var showRouteFiles by remember { mutableStateOf(false) }
            var routeFilesTick by remember { mutableStateOf(0) }
            var routeFileView by remember { mutableStateOf<String?>(null) }      // a draft name, or RF_HEADER
            var routeFilesConfirm by remember { mutableStateOf<String?>(null) }  // "batch" | "all"
            var routeZipConfirm by remember { mutableStateOf(false) }               // ROUTEZIP-2026-10-07
            val RF_HEADER = "__compare_set_header__"
            val rfCtx = androidx.compose.ui.platform.LocalContext.current
            fun rfShare(subject: String, body: String) {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                    putExtra(android.content.Intent.EXTRA_TEXT, body)
                }
                rfCtx.startActivity(android.content.Intent.createChooser(send, "Send"))
            }
            SectionLabel("Route files (AI create)")
            ListItem(
                headlineContent = { Text("View route files", style = MaterialTheme.typography.bodyLarge) },
                supportingContent = { Text("In-progress routes, the compare set and the last AI search -- view, share, clean up", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { routeFilesTick++; showRouteFiles = true }
            )
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            if (showRouteFiles) {
                val report = remember(routeFilesTick) { RouteDraftStore.routeFilesReport() }
                val names = remember(routeFilesTick) { RouteDraftStore.draftNames() }
                val hasHeader = remember(routeFilesTick) { RouteDraftStore.readBatchText() != null }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showRouteFiles = false },
                    confirmButton = { TextButton(onClick = { showRouteFiles = false }) { Text("CLOSE") } },
                    dismissButton = { TextButton(onClick = { rfShare("GroupTrack route files", report) }) { Text("SHARE") } },
                    title = { Text("Route files") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(report, style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Spacer(Modifier.height(10.dp))
                            Text("Tap a file to view it:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            if (hasHeader) TextButton(onClick = { routeFileView = RF_HEADER }) { Text("Compare set header") }
                            names.forEach { n -> TextButton(onClick = { routeFileView = n }) { Text(n) } }
                            Spacer(Modifier.height(10.dp))
                            if (hasHeader) TextButton(onClick = { routeFilesConfirm = "batch" }) { Text("CLEAR COMPARE SET (keeps the routes)") }
                            // ROUTEZIP-2026-10-07 (Fred): zip everything, email it, THEN empty the folders.
                            TextButton(onClick = {
                                val z = RouteDraftStore.zipRouteFiles(rfCtx)
                                if (z == null) {
                                    android.widget.Toast.makeText(rfCtx, "Could not make the zip -- nothing was deleted", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(rfCtx, "${rfCtx.packageName}.provider", z)
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "GroupTrack route files " + z.name)
                                        putExtra(android.content.Intent.EXTRA_TEXT, report)
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    rfCtx.startActivity(android.content.Intent.createChooser(send, "Send route files"))
                                    routeZipConfirm = true
                                }
                            }) { Text("ZIP + EMAIL, THEN EMPTY") }
                            if (names.isNotEmpty() || hasHeader) TextButton(onClick = { routeFilesConfirm = "all" }) { Text("DELETE ALL ROUTE FILES") }
                        }
                    }
                )
            }
            routeFileView?.let { v ->
                val isHeader = v == RF_HEADER
                val body = remember(v, routeFilesTick) {
                    (if (isHeader) RouteDraftStore.readBatchText() else RouteDraftStore.readDraftText(v)) ?: "(file not found)"
                }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { routeFileView = null },
                    confirmButton = { TextButton(onClick = { routeFileView = null }) { Text("CLOSE") } },
                    dismissButton = { TextButton(onClick = { rfShare("GroupTrack route file: " + (if (isHeader) "compare set header" else v), body) }) { Text("SHARE") } },
                    title = { Text(if (isHeader) "Compare set header" else v) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (!isHeader) TextButton(onClick = {
                                if (RouteDraftStore.deleteDraft(v)) {
                                    android.widget.Toast.makeText(rfCtx, "Deleted: " + v, android.widget.Toast.LENGTH_SHORT).show()
                                    routeFileView = null; routeFilesTick++
                                } else android.widget.Toast.makeText(rfCtx,
                                    "This route is part of an open compare set. Clear the compare set first, or keep or discard them in the compare table.",
                                    android.widget.Toast.LENGTH_LONG).show()
                            }) { Text("DELETE THIS ROUTE") }
                            Text(body, style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                )
            }
            if (routeZipConfirm) {   // ROUTEZIP-2026-10-07: asked when the rider comes back from sending
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { routeZipConfirm = false },
                    title = { Text("Empty the route folders now?") },
                    text = { Text("Send the zip first. Then CLEAR removes the compare set and every in-progress route file, leaving the folders empty. The zip is a separate copy. Saved routes, tracks and maps are not touched.") },
                    confirmButton = { TextButton(onClick = {
                        val n = RouteDraftStore.emptyRouteFolders()
                        android.widget.Toast.makeText(rfCtx, "Route folders emptied ($n files)", android.widget.Toast.LENGTH_SHORT).show()
                        routeZipConfirm = false; routeFilesTick++
                    }) { Text("CLEAR") } },
                    dismissButton = { TextButton(onClick = { routeZipConfirm = false }) { Text("NOT NOW") } }
                )
            }
            routeFilesConfirm?.let { which ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { routeFilesConfirm = null },
                    title = { Text(if (which == "batch") "Clear the compare set?" else "Delete ALL route files?") },
                    text = { Text(if (which == "batch")
                        "The compare-set header is removed. Its routes stay, as ordinary in-progress routes, and Route+ starts a new route."
                        else "Every in-progress route and the compare set are deleted. Saved routes are not affected. This cannot be undone.") },
                    confirmButton = { TextButton(onClick = {
                        if (which == "batch") {
                            RouteDraftStore.clearBatch()
                            android.widget.Toast.makeText(rfCtx, "Compare set cleared", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val n = RouteDraftStore.deleteAllRouteFiles()
                            android.widget.Toast.makeText(rfCtx, "Deleted $n route file(s)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        routeFilesConfirm = null; routeFilesTick++
                    }) { Text(if (which == "batch") "CLEAR" else "DELETE ALL") } },
                    dismissButton = { TextButton(onClick = { routeFilesConfirm = null }) { Text("CANCEL") } }
                )
            }

            SectionLabel("Your Profile")
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Edit rider profile", style = MaterialTheme.typography.bodyLarge) },
                supportingContent = { Text("Callsign, email, vehicle, team colour -- stays on this tablet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { onNavigateToProfile() }
            )
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            SectionLabel("Map Sources")
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Change Map Sources", style = MaterialTheme.typography.bodyLarge) },
                supportingContent = { Text("Assign tile sources to SAT / TOPO / TOPO+ slots", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { onNavigateToMapSources() }
            )
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ── Alert Thresholds ──────────────────────────────────────────
            SectionLabel("Alert Thresholds")

            AlertSlider(
                title       = "Signal Drop",
                description = "Minutes before radio disconnect warning fires",
                valueLabel  = "${uiState.signalDropMinutes.roundToInt()} min",
                value       = uiState.signalDropMinutes,
                valueRange  = 1f..10f,
                steps       = 8,
                onValueChangeFinished = viewModel::onSignalDropChanged
            )
            HorizontalDivider()

            AlertSlider(
                title       = "Signal Lost",
                description = "Minutes without a packet before node is considered lost",
                valueLabel  = "${uiState.signalLostMinutes.roundToInt()} min",
                value       = uiState.signalLostMinutes,
                valueRange  = 5f..30f,
                steps       = 4,
                onValueChangeFinished = viewModel::onSignalLostChanged
            )
            HorizontalDivider()

            AlertSlider(
                title       = "Off Track",
                description = "Miles from convoy track before off-route alert fires",
                valueLabel  = "${"%.1f".format(uiState.offTrackMiles)} mi",
                value       = uiState.offTrackMiles,
                valueRange  = 0.1f..2f,
                steps       = 18,
                onValueChangeFinished = viewModel::onOffTrackChanged
            )

            Spacer(Modifier.height(8.dp))

            // ── Node Filter ───────────────────────────────────────────────
            SectionLabel("Node Filter")

            AlertSlider(
                title       = "Admission Window",
                description = "Node must have been heard within this window today to appear on map",
                valueLabel  = "${uiState.admissionWindowHours} hr",
                value       = uiState.admissionWindowHours.toFloat(),
                valueRange  = 1f..12f,
                steps       = 10,
                onValueChangeFinished = { viewModel.onAdmissionWindowChanged(it.roundToInt()) }
            )

            Spacer(Modifier.height(8.dp))

            // ── Track Display ─────────────────────────────────────────────
            SectionLabel("Track Display")

            var trackMulticolor by remember { mutableStateOf(ConvoyConfig.TRACK_MULTICOLOR) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Multicolor Track", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (trackMulticolor) "Track colored by node position" else "Track shown in black",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = trackMulticolor,
                    onCheckedChange = {
                        trackMulticolor = it
                        ConvoyConfig.TRACK_MULTICOLOR = it
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Track Recording ───────────────────────────────────────────
            SectionLabel("Track Recording")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Lead Cart Only", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (trackLeadOnly) "Recording lead cart track only" else "Recording all carts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = trackLeadOnly,
                    onCheckedChange = { convoyViewModel.toggleLeadOnly() }
                )
            }
            HorizontalDivider()

            var trackExportGpx by remember { mutableStateOf(ConvoyConfig.TRACK_EXPORT_FORMAT.uppercase() == "GPX") }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Track Recording Format  KML / GPX", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (trackExportGpx) "GPX — Garmin, Strava, AllTrails" else "KML — Google Earth",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = trackExportGpx,
                    onCheckedChange = {
                        trackExportGpx = it
                        ConvoyConfig.TRACK_EXPORT_FORMAT = if (it) "GPX" else "KML"
                    }
                )
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            SectionLabel("Removed Carts")

            if (uiState.removedCarts.isEmpty()) {
                ListItem(
                    headlineContent = {
                        Text(
                            text  = "No carts removed today",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            } else {
                uiState.removedCarts.forEach { (nodeId, callsign) ->
                    RemovedCartRow(
                        callsign    = callsign,
                        nodeId      = nodeId,
                        onReinstate = { viewModel.onReinstateCart(nodeId, callsign) }
                    )
                    HorizontalDivider()
                }
            }

            // ── Build stamp ──────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Text(
                // HTMLVER-2026-08-13B: the release string here was one letter behind.
                text     = "GroupTrack Rel 2.7a — Build ${BuildConfig.BUILD_STAMP}",   // RELLABEL-2026-09-30
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            // HTMLVER-2026-08-13B: which bundled map HTML actually loaded. An old date
            // here means the device is running stale assets - the thing that cost
            // an evening of bisecting on 08-11 because it could not be seen.
            Text(
                text     = "Map HTML: ${ConvoyConfig.MAP_HTML_VERSION}",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun SectionLabel(title: String) {
    Text(
        text       = title,
        style      = MaterialTheme.typography.labelMedium,
        color      = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun AlertSlider(
    title:                 String,
    description:           String,
    valueLabel:            String,
    value:                 Float,
    valueRange:            ClosedFloatingPointRange<Float>,
    steps:                 Int = 0,
    onValueChangeFinished: (Float) -> Unit
) {
    var localValue = value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text       = valueLabel,
                style      = MaterialTheme.typography.bodyLarge,
                color      = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text     = description,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Slider(
            value                 = value,
            onValueChange         = { localValue = it },
            onValueChangeFinished = { onValueChangeFinished(localValue) },
            valueRange            = valueRange,
            steps                 = steps,
            modifier              = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun RemovedCartRow(
    callsign:    String,
    nodeId:      String,
    onReinstate: () -> Unit
) {
    ListItem(
        headlineContent   = { Text(callsign, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                text  = nodeId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent  = {
            Icon(
                imageVector        = Icons.Default.Warning,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.error
            )
        },
        trailingContent = {
            TextButton(onClick = onReinstate) {
                Icon(Icons.Default.AddCircle, contentDescription = null)
                Text("Reinstate", modifier = Modifier.padding(start = 4.dp))
            }
        }
    )
}
