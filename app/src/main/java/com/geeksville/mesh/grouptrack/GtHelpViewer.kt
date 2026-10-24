package com.geeksville.mesh.grouptrack

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * TOUCHSTD-2026-10-22 (2.7c standard 5): ONE help viewer for every screen. GtHelpView.open(id) opens the manual page the link
 * table gives for that id; GtHelpViewer() is drawn LAST in Main's overlay box, so it sits on top of whatever screen asked.
 * "" = closed (CODE RULE 1: no null).
 */
object GtHelpView {
    val doc = mutableStateOf("")
    fun open(id: String) { doc.value = GtHelp.linkFor(id) }
    fun close() { doc.value = "" }
}

/** The standard HELP control for a screen's top bar. */
@Composable
fun GtHelpButton(id: String, color: Color = Gt.accent) {
    GtHelp.load(LocalContext.current)
    // ISSUE-2026-10-24: ISSUE beside HELP -- wherever HELP is, ISSUE is.
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(onClick = { GtHelpView.open(id) }) { Text("HELP", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        GtIssueButton(id, color)
    }
}

/** CLOSEX-2026-10-22 (Fred 12:18): the standard close control -- ✕, top right, beside HELP, on every screen. */
@Composable
fun GtCloseButton(onClose: () -> Unit, enabled: Boolean = true, color: Color = Gt.accent) {
    TextButton(onClick = onClose, enabled = enabled) { Text("\u2715", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun GtHelpViewer() {
    val d = GtHelpView.doc.value
    if (d.isEmpty()) return
    BackHandler(enabled = true) { GtHelpView.close() }
    val key = d.substringBefore("#")
    val frag = d.substringAfter("#", "")
    val url = "file:///android_asset/" + when (key) {
        "notes" -> "grouptrack_release_notes.html"
        "quickstart" -> "grouptrack_quickstart.html"
        else -> "grouptrack_manual.html"
    } + (if (frag.isNotEmpty()) "#$frag" else "")
    Surface(modifier = Modifier.fillMaxSize(), color = Gt.panel) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {   // CLOSEX-2026-10-22: ✕ top right
                GtCloseButton({ GtHelpView.close() })
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE   // as the docs viewer: never a cached asset
                        settings.allowFileAccess = true
                        webViewClient = android.webkit.WebViewClient()
                        loadUrl(url)
                    }
                },
                update = { wv -> if (wv.url != url) wv.loadUrl(url) }
            )
        }
    }
}
