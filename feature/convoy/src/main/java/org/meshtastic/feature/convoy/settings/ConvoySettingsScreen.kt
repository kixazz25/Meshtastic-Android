package org.meshtastic.feature.convoy.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

// ── Screen Entry Point ────────────────────────────────────────────────────────

@Composable
fun ConvoySettingsScreen(
    onNavigateBack: () -> Unit,
    // Provide node callsigns from the convoy node registry for display in Removed Carts
    // Key: nodeId, Value: callsign (Long Name from device)
    removedCartCallsigns: Map<String, String> = emptyMap(),
    viewModel: ConvoySettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar on reinstate
    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onUserMessageShown()
        }
    }

    ConvoySettingsContent(
        uiState               = uiState,
        snackbarHostState     = snackbarHostState,
        removedCartCallsigns  = removedCartCallsigns,
        onNavigateBack        = onNavigateBack,
        onSignalDropChanged   = viewModel::onSignalDropDbmChanged,
        onSignalLostChanged   = viewModel::onSignalLostMinutesChanged,
        onOffTrackChanged     = viewModel::onOffTrackMetersChanged,
        onAdmissionChanged    = viewModel::onAdmissionWindowHoursChanged,
        onReinstateCart       = viewModel::onReinstateCart,
    )
}

// ── Content ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConvoySettingsContent(
    uiState:               ConvoySettingsUiState,
    snackbarHostState:     SnackbarHostState,
    removedCartCallsigns:  Map<String, String>,
    onNavigateBack:        () -> Unit,
    onSignalDropChanged:   (Float) -> Unit,
    onSignalLostChanged:   (Int)   -> Unit,
    onOffTrackChanged:     (Int)   -> Unit,
    onAdmissionChanged:    (Int)   -> Unit,
    onReinstateCart:       (nodeId: String, callsign: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convoy Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector    = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->

        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── Section: Alert Thresholds ─────────────────────────────────────
            SectionHeader(title = "Alert Thresholds")

            // Signal Drop
            ThresholdSliderItem(
                title       = "Signal Drop",
                description = "Minimum SNR before radio disconnect warning fires",
                valueLabel  = "${uiState.signalDropDbm.roundToInt()} dBm",
                value       = uiState.signalDropDbm,
                valueRange  = -130f..-80f,
                onValueChangeFinished = onSignalDropChanged,
            )

            HorizontalDivider()

            // Signal Lost
            ThresholdSliderItem(
                title       = "Signal Lost",
                description = "Minutes without a packet before node is considered lost",
                valueLabel  = "${uiState.signalLostMinutes} min",
                value       = uiState.signalLostMinutes.toFloat(),
                valueRange  = 1f..60f,
                steps       = 58,
                onValueChangeFinished = { onSignalLostChanged(it.roundToInt()) },
            )

            HorizontalDivider()

            // Off Track
            ThresholdSliderItem(
                title       = "Off Track",
                description = "Distance from convoy track before off-route alert fires",
                valueLabel  = "${uiState.offTrackMeters} m",
                value       = uiState.offTrackMeters.toFloat(),
                valueRange  = 50f..1000f,
                steps       = 18,
                onValueChangeFinished = { onOffTrackChanged(it.roundToInt()) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Section: Node Filter ──────────────────────────────────────────
            SectionHeader(title = "Node Filter")

            ThresholdSliderItem(
                title       = "Admission Window",
                description = "Node must have been heard within this window today to join the convoy group",
                valueLabel  = "${uiState.admissionWindowHours} hr",
                value       = uiState.admissionWindowHours.toFloat(),
                valueRange  = 1f..12f,
                steps       = 10,
                onValueChangeFinished = { onAdmissionChanged(it.roundToInt()) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Section: Removed Carts ────────────────────────────────────────
            SectionHeader(title = "Removed Carts")

            if (uiState.removedCartIds.isEmpty()) {
                ListItem(
                    headlineContent = {
                        Text(
                            text  = "No carts removed today",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            } else {
                uiState.removedCartIds.forEach { nodeId ->
                    val callsign = removedCartCallsigns[nodeId] ?: nodeId
                    RemovedCartItem(
                        callsign    = callsign,
                        nodeId      = nodeId,
                        onReinstate = { onReinstateCart(nodeId, callsign) }
                    )
                    HorizontalDivider()
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Reusable Components ───────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun ThresholdSliderItem(
    title:                  String,
    description:            String,
    valueLabel:             String,
    value:                  Float,
    valueRange:             ClosedFloatingPointRange<Float>,
    steps:                  Int = 0,
    onValueChangeFinished:  (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier            = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment   = Alignment.CenterVertically
        ) {
            Text(
                text  = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text  = valueLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
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
            value         = value,
            onValueChange = { /* live drag handled visually */ },
            onValueChangeFinished = { onValueChangeFinished(value) },
            valueRange    = valueRange,
            steps         = steps,
            modifier      = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun RemovedCartItem(
    callsign:    String,
    nodeId:      String,
    onReinstate: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text  = callsign,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                text  = nodeId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector        = Icons.Default.Warning,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.error
            )
        },
        trailingContent = {
            TextButton(onClick = onReinstate) {
                Icon(
                    imageVector        = Icons.Default.AddCircle,
                    contentDescription = null
                )
                Text(
                    text     = "Reinstate",
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    )
}
