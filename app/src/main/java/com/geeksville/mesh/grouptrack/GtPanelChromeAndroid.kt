package com.geeksville.mesh.grouptrack

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * PANELCHROME-2026-11-19: the ANDROID chrome. Every member delegates to the function that already
 * served it, so nothing about Android's behaviour changes -- this only gives the shared GtPanel a
 * way to reach code that cannot leave app/ (GtIssue needs LocalView, Handler/Looper and an Activity;
 * the help viewer is a WebView).
 */
object GtPanelChromeAndroid : GtPanelChrome {

    override fun openHelp(id: String) = GtHelpView.open(id)

    @Composable
    override fun IssueButton(id: String, color: Color) = GtIssueButton(id, color)

    @Composable
    override fun IssueHost() = GtIssueHost()

    @Composable
    override fun UtahPrivatePanel() = GtUtahPrivatePanel()

    @Composable
    override fun QueueSummary(onOpen: () -> Unit, modifier: androidx.compose.ui.Modifier) =
        GtQueueSummary(onOpen = onOpen, modifier = modifier)

    @Composable
    override fun DownloadSettingsPanel() = GtDownloadSettingsPanel()
}
