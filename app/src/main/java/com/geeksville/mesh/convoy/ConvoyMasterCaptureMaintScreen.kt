package com.geeksville.mesh.convoy

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * ConvoyMasterCaptureMaintScreen
 * Maintenance entry point for master config capture.
 * Delegates to ConvoyMasterCaptureScreen -- same capture logic,
 * accessed from maintenance panel not apply flow.
 */
@Composable
fun ConvoyMasterCaptureMaintScreen(
    onBack: () -> Unit,
    viewModel: ConvoyViewModel = hiltViewModel()
) {
    ConvoyMasterCaptureScreen(
        viewModel = viewModel,
        onBack = onBack
    )
}
