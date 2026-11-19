package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * IOSHELP-2026-11-19 (Fred 11-19 11:52 "three items to be wired on menu"): A.MENU > menu.help's chooser,
 * transcribed from GroupTrackScreen.kt:2050-2120 so BOTH platforms offer the same four rows.
 *
 * The notes that came with it, kept because they are the reasons it looks like this:
 *  - DOCSCHOOSER-2026-09-02: this dialog once HAD NO WAY OUT. Material3's AlertDialog gives two button
 *    slots and both were spent on documents, so dismissButton was "Full Manual" rather than a cancel --
 *    the only exits were a back press or a tap outside, neither visible. Fred 09-01: "it is insane trying
 *    to get back to the desktop once we have things open." THE DOCUMENTS LIVE IN THE BODY as a vertical
 *    list, which frees dismissButton for a real Cancel and reads as a menu. It also scales: a fourth
 *    document is one more row, not a layout problem.
 *  - HELPVIDEOS-2026-11-05: VIDEOS opens the website; the three documents stay in the app. LocalUriHandler
 *    exists in Compose Multiplatform, so this row needs no seam -- it works on the iPad as it does on
 *    Android, and the try/catch is Android's (an absent browser must not crash the dialog).
 *
 * SEAMRULE-2026-11-19: opening a DOCUMENT is the platform's job -- Android pushes its docsView stack, the
 * iPad presents a WKWebView over the bundle file -- so [onOpen] is a lambda. The chooser itself is shared.
 */
@Composable
fun GtDocsChooser(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val uri = LocalUriHandler.current   // HELPVIDEOS-2026-11-05
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Help & Info") },
        text = {
            Column {
                Text("Which document would you like?", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                for ((key, title, sub) in DOCS_ROWS) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                onClose()
                                if (key == "videos") {
                                    try { uri.openUri(VIDEOS_URL) } catch (e: Exception) { }
                                } else onOpen(key)
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58A6FF))
                        Text(sub, fontSize = 12.sp, color = Color(0xFF8B949E))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** The four rows, in GroupTrackScreen's order and wording. */
val DOCS_ROWS: List<Triple<String, String, String>> = listOf(
    Triple("quickstart", "Quick Start", "The short version \u2014 set up and go"),
    Triple("notes", "Release Notes", "What changed, newest first"),
    Triple("manual", "Full Manual", "Everything, in detail"),
    Triple("videos", "Videos", "Short demos on grouptrack.org (needs internet)"),
)

/** HELPVIDEOS-2026-11-05. */
const val VIDEOS_URL = "https://grouptrack.org/videos.html"

/** IOSHELP-2026-11-19: the chooser's key -> the asset basename. Android resolves these through its own
 *  assets; the iPad reads the same three files from the bundle (staged by gt_iosres_2026-11-19_v2.py). */
fun docsAssetFor(key: String): String? = when (key) {
    "quickstart" -> "grouptrack_quickstart.html"
    "notes" -> "grouptrack_release_notes.html"
    "manual" -> "grouptrack_manual.html"
    else -> null
}
