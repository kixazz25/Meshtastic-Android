package com.geeksville.mesh.convoy

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Bump this when a build requires every tester to re-run the migration/backfill.
// The receipt is version-keyed: a persisted receipt from an older version does
// NOT block a new version's required pass.
private const val AUTO_RESYNC_VERSION = 1

/**
 * On the first launch after an update that needs it, run the all-tracks migration
 * resync (renames GPX to <hash>.gpx so the new retrieval path finds them, and
 * repopulates track_properties). Shows the shared SYNC TRACKS panel while it runs.
 * Writes a receipt to Documents/GroupTrack/autoresync_receipt.json (public, survives
 * reinstall). Gated so it runs once per version; retries next launch on non-execution.
 */
@Composable
fun AutoResyncHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var show by remember { mutableStateOf(false) }
    var syncLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var syncRunning by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<SpatialDbManager.TrackSyncResult?>(null) }

    // Decide once per process whether the migration is needed, then kick it.
    LaunchedEffect(Unit) {
        val needed = withContext(Dispatchers.IO) { autoResyncNeeded() }
        if (!needed) return@LaunchedEffect
        show = true
        syncRunning = true
        syncLines = listOf("\u2014 migrating tracks \u2014")
        syncResult = null
        val r = withContext(Dispatchers.IO) {
            SpatialDbManager.init(context)
            SpatialDbManager.syncTracksFromFiles(context) { line ->
                syncLines = syncLines + line
            }
        }
        // Executed = a real sweep ran (not an early-return sentinel).
        val executed = r.propsTotal > 0 || r.processed > 0
        if (executed) {
            withContext(Dispatchers.IO) { writeAutoResyncReceipt(r) }
        }
        syncResult = r
        syncRunning = false
    }

    if (show) {
        SyncTracksDialog(
            syncLines = syncLines,
            syncResult = syncResult,
            syncRunning = syncRunning,
            onStart = {
                // Manual RE-RUN from within the auto panel (same body, rewrites receipt).
                syncRunning = true
                syncLines = listOf("\u2014 starting sync \u2014")
                syncResult = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        SpatialDbManager.init(context)
                        SpatialDbManager.syncTracksFromFiles(context) { line ->
                            syncLines = syncLines + line
                        }
                    }
                    val executed = r.propsTotal > 0 || r.processed > 0
                    if (executed) {
                        withContext(Dispatchers.IO) { writeAutoResyncReceipt(r) }
                    }
                    syncResult = r
                    syncRunning = false
                }
            },
            onClose = { show = false }
        )
    }
}

/** True if no success receipt exists for the current AUTO_RESYNC_VERSION. */
private fun autoResyncNeeded(): Boolean {
    return try {
        val f = File(SpatialDbManager.groupTrackDir(), "autoresync_receipt.json")
        if (!f.exists()) return true
        val txt = f.readText()
        // Lightweight check — no JSON lib dependency. Look for this version + executed.
        val versionOk = Regex("\"version\"\\s*:\\s*(\\d+)")
            .find(txt)?.groupValues?.get(1)?.toIntOrNull() == AUTO_RESYNC_VERSION
        val executedOk = Regex("\"executed\"\\s*:\\s*true").containsMatchIn(txt)
        !(versionOk && executedOk)
    } catch (e: Exception) {
        // On any read error, prefer to run (self-healing) rather than skip.
        true
    }
}

/** Serialize the sync recap to a public, reinstall-surviving JSON receipt. */
private fun writeAutoResyncReceipt(r: SpatialDbManager.TrackSyncResult) {
    try {
        val dir = SpatialDbManager.groupTrackDir()
        if (!dir.exists()) dir.mkdirs()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val failuresJson = r.failures.joinToString(",") { "\"" + esc(it) + "\"" }
        val json = buildString {
            append("{\n")
            append("  \"version\": ").append(AUTO_RESYNC_VERSION).append(",\n")
            append("  \"datetime\": \"").append(iso).append("\",\n")
            append("  \"executed\": true,\n")
            append("  \"processed\": ").append(r.processed).append(",\n")
            append("  \"skipped\": ").append(r.skipped).append(",\n")
            append("  \"addedRenamed\": ").append(r.addedRenamed).append(",\n")
            append("  \"renamed\": ").append(r.renamed).append(",\n")
            append("  \"propsWritten\": ").append(r.propsWritten).append(",\n")
            append("  \"propsTotal\": ").append(r.propsTotal).append(",\n")
            append("  \"failures\": [").append(failuresJson).append("]\n")
            append("}\n")
        }
        File(dir, "autoresync_receipt.json").writeText(json)
    } catch (e: Exception) {
        android.util.Log.e("AutoResync", "receipt write failed: ${e.message}")
    }
}
