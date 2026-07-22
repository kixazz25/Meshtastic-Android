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
                text     = "GroupTrack Rel 2.6c — Build ${BuildConfig.BUILD_STAMP}",
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
