package com.geeksville.mesh.grouptrack

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * PANELCHROME-2026-11-19 (2.8b): the PANEL STANDARD's three HOST services, seamed so the panel
 * chrome itself is shared code.
 *
 * STD / ISSUE-2026-10-24 put HELP, ISSUE and close on every panel. Two of the three need the host:
 * the manual viewer is a platform web view, and the issue report captures the screen and sends it.
 * GtPanel must not know how either is done -- it only knows that the host can do them.
 *
 * ANDROID provides today's exact implementations (GtHelpView, GtIssueButton, GtIssueHost), so
 * Android behaviour does not change. iOS provides its own.
 *
 * NO DEFAULT, DELIBERATELY: a host that forgets to provide this fails immediately with a message
 * naming the fix, rather than drawing panels with no HELP and no ISSUE and saying nothing. That is
 * the SKIPLOUD rule -- a skip nobody acts on is a loss, not a warning.
 */
interface GtPanelChrome {

    /** Open the manual at [id]'s page. Android: the one docs viewer. */
    fun openHelp(id: String)

    /** The ISSUE control. Android needs LocalView to capture the screen, so the host draws it. */
    @Composable
    fun IssueButton(id: String, color: Color)

    /** The report panel and the CAPTURE bar, drawn on every screen by GtTheme. */
    @Composable
    fun IssueHost()
}

val LocalGtPanelChrome = staticCompositionLocalOf<GtPanelChrome> {
    error(
        "GtPanelChrome was not provided. Wrap the host's tree in " +
            "CompositionLocalProvider(LocalGtPanelChrome provides <platform chrome>) " +
            "above GtTheme -- Android does this in ConvoyNavigation.gtComposable."
    )
}

/** CLOSEX-2026-10-22 (Fred 12:18): the standard close control -- the close glyph, top right, beside HELP,
 *  on every screen. Pure Compose, so it moved here from GtHelpViewer.kt with no change. */
@Composable
fun GtCloseButton(onClose: () -> Unit, enabled: Boolean = true, color: Color = Gt.accent) {
    TextButton(onClick = onClose, enabled = enabled) {
        Text("\u2715", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
