package com.geeksville.mesh.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * CONVOYDOCS-2026-09-07 -- the Help chooser and the document viewer, ONCE.
 *
 * \u26d4 WHY THIS FILE EXISTS. There were two implementations: ConvoyScreen's,
 * which had the whole 2.6g documents feature, and the planning map's, which
 * offered Release Notes and Full Manual from a hardcoded AlertDialog and
 * nothing else. The planner was missing the Quick Start, the fragment links,
 * the stack and the centred Close -- so adding two menu rows to it would have
 * fixed the visible symptom and left three real behaviours broken.
 *
 * \u26d4 AND THE COMMENT THAT KEPT THEM APART WAS WRONG. It read: "INLINED, not a
 * shared helper: a `private fun` in another file is invisible here -- Kotlin's
 * private is FILE-scoped, not package-scoped." The fact is right; the
 * conclusion is not. The answer to "private is file-scoped" is not to make it
 * private. SyncTracksDialog in this same package is already shared by two
 * hosts, hoisted exactly this way.
 *
 * State stays with the caller -- docsView, the stack, and the two navigation
 * functions -- so each screen keeps ownership and these two composables decide
 * nothing.
 */

/** The three documents, in the order a rider meets them. */
private val DOC_CHOICES = listOf(
    Triple("quickstart", "Quick Start", "The short version \u2014 set up and go"),
    Triple("notes", "Release Notes", "What changed, newest first"),
    Triple("manual", "Full Manual", "Everything, in detail")
)

/** Asset filename for a document key. \u26a0 The key may carry a fragment; split
 *  it before calling this. */
private fun assetOf(docKey: String): String = when (docKey) {
    "notes" -> "grouptrack_release_notes.html"
    "quickstart" -> "grouptrack_quickstart.html"
    else -> "grouptrack_manual.html"
}

/**
 * The Help chooser. [onPick] receives a document key -- "quickstart", "notes"
 * or "manual" -- and the caller decides what to do with it.
 */
@Composable
fun ConvoyDocsChooser(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Help & Info", color = Color(0xFF58A6FF),
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Which document would you like?", fontSize = 13.sp,
                    color = Color(0xFFE6EDF3))
                Spacer(Modifier.height(10.dp))
                for ((key, title, sub) in DOC_CHOICES) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onPick(key) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF58A6FF))
                        Text(sub, fontSize = 11.sp, color = Color(0xFF8B949E))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8B949E))
            }
        }
    )
}

/**
 * The full-screen document viewer.
 *
 * @param docsView the current document key, optionally with a fragment --
 *                 "manual#mapkeys". Never null here; the caller gates on it.
 * @param onOpen   push another document onto the caller's stack.
 * @param onBack   pop the caller's stack, or close if it is empty.
 *
 * \u2b50 A LINK TO ANOTHER DOCUMENT PUSHES ONTO THE STACK rather than navigating
 * this WebView. That is what lets Close come back to the Quick Start at the
 * task the rider was on. \u26a0 A link WITHIN the same document -- an anchor -- is
 * left alone, or every jump would stack.
 */
@Composable
fun ConvoyDocsViewer(
    docsView: String,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val docKey = docsView.substringBefore("#")
    val docFrag = docsView.substringAfter("#", "")
    val assetFile = assetOf(docKey) + (if (docFrag.isNotEmpty()) "#$docFrag" else "")

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF10130F)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                // \u26d4 CLOSE IS CENTRED. It was top right, under QUEUES.
                // Fred: "queues overlays the text box." Centre is the one place
                // on this bar nothing else claims.
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = onBack) {
                    Text("Close", color = Color(0xFF8FD0FF))
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        // HTMLVER-2026-08-13B: never serve a cached copy of a
                        // bundled asset. \u26a0 A cache-buster on the URL is NOT
                        // used - WebView treats file:///android_asset/x.html as
                        // a filename, so a query string risks a 404.
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.allowFileAccess = true
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = true
                        webViewClient = object : android.webkit.WebViewClient() {
                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(
                                view: android.webkit.WebView?, url: String?
                            ): Boolean {
                                val u = url ?: return false
                                if (!u.startsWith("file:///android_asset/")) return false
                                val name = u.removePrefix("file:///android_asset/")
                                val f = name.substringAfter("#", "")
                                val target = when {
                                    name.startsWith("grouptrack_manual") -> "manual"
                                    name.startsWith("grouptrack_quickstart") -> "quickstart"
                                    name.startsWith("grouptrack_release_notes") -> "notes"
                                    else -> return false
                                }
                                if (target == docKey) return false   // same doc: let it scroll
                                onOpen(if (f.isEmpty()) target else "$target#$f")
                                return true
                            }
                        }
                        loadUrl("file:///android_asset/" + assetFile)
                    }
                },
                update = { it.loadUrl("file:///android_asset/" + assetFile) }
            )
        }
    }
}
