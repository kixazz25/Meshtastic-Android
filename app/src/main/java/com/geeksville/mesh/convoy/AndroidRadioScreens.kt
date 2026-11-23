// RADIOCONV-2026-11-23: ANDROID's half of the two radio screens.
//
// The screens are now in commonMain and are the SAME code on both platforms. Everything here is the
// call Android already made before the move: the ViewModel still supplies the comm handles it always
// supplied, HELP is still GtHelpButton (SEAMRULE-2026-11-19), back is still BackHandler, and the
// setup sheet is still GtConfigPdf -- reached through GtSheetOutAndroid, which does not change it.
package com.geeksville.mesh.convoy

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AndroidRadioConfigScreen(onClose: () -> Unit, convoyViewModel: ConvoyViewModel = hiltViewModel()) {
    val context = LocalContext.current
    RadioConfigScreen(
        config = convoyViewModel.radioConfig,
        link = convoyViewModel.link,
        device = convoyViewModel.myDevice,
        files = GtRidesAndroid.Files(context),
        settings = com.geeksville.mesh.grouptrack.GtSettingsAndroid(context),
        backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
        onClose = onClose,
        help = { com.geeksville.mesh.grouptrack.GtHelpButton("group.configure") },
    )
}

@Composable
fun AndroidConfigReviewScreen(onClose: () -> Unit, convoyViewModel: ConvoyViewModel = hiltViewModel()) {
    val context = LocalContext.current
    ConfigReviewScreen(
        config = convoyViewModel.radioConfig,
        link = convoyViewModel.link,
        device = convoyViewModel.myDevice,
        sheets = GtSheetOutAndroid(context),
        backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
        onClose = onClose,
        help = { com.geeksville.mesh.grouptrack.GtHelpButton("group.archive") },
    )
}
