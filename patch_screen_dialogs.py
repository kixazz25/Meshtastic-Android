import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# ── Part 1: Insert state collection after pendingImportBanner line ──
anchor1 = "    val pendingImportBanner by viewModel.pendingImportBanner.collectAsStateWithLifecycle()"

if anchor1 not in content:
    print("ERROR: state collection anchor not found")
    sys.exit(1)

state_lines = """    val pendingImportBanner by viewModel.pendingImportBanner.collectAsStateWithLifecycle()
    val pendingDownload by viewModel.pendingDownload.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()"""

content = content.replace(anchor1, state_lines)

# ── Part 2: Insert download dialogs after the showNameDialog AlertDialog closing block ──
# Find the closing of the showNameDialog block and insert after it
anchor2 = """    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = {
                showNameDialog = false
                viewModel.finalizeTrack("convoy_track", context)
            },"""

if anchor2 not in content:
    print("ERROR: dialog anchor not found")
    sys.exit(1)

# Find the end of the showNameDialog block by locating its closing brace
# We'll insert our dialogs BEFORE the showNameDialog block
download_dialogs = """    // ── Download size estimation dialogs ─────────────────────────────────
    pendingDownload?.let { pending ->
        if (!pending.withinCeiling) {
            AlertDialog(
                onDismissRequest = { viewModel.clearPendingDownload() },
                title = { Text("Area Too Large") },
                text = {
                    Text(
                        "Estimated ${String.format("%.0f", pending.sizeMB)} MB " +
                        "exceeds the 500 MB limit.\\n\\nReduce the selected area and try again."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearPendingDownload() }) {
                        Text("OK")
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { viewModel.clearPendingDownload() },
                title = { Text("Download Map Area?") },
                text = {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
                    ) {
                        Text("${pending.tileCount} tiles — ${"%.1f".format(pending.sizeMB)} MB estimated")
                        Text("Source: ${pending.sourceName.uppercase()}")
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.height(4.dp)
                        )
                        Text(
                            "This may take several minutes on a slow connection.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.startDownload(context, pending) }) {
                        Text("DOWNLOAD")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.clearPendingDownload() }) {
                        Text("CANCEL")
                    }
                }
            )
        }
    }

    if (downloadState is ConvoyViewModel.DownloadState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDownload() },
            title = { Text("Download Error") },
            text = { Text((downloadState as ConvoyViewModel.DownloadState.Error).message) },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelDownload() }) { Text("OK") }
            }
        )
    }

"""

content = content.replace(anchor2, download_dialogs + anchor2)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("PATCHED OK")
