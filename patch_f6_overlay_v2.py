import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

errors = []

# Part 1: DOWNLOAD button
old1 = '                    TextButton(onClick = { viewModel.startDownload(context, pending) }) {\n                        Text("DOWNLOAD")\n                    }'
new1 = '                    TextButton(onClick = {\n                        viewModel.startDownload(context, pending)\n                        coroutineScope.launch { convoyMenuSheetState.hide() }\n                        android.widget.Toast.makeText(context, "Downloading map tiles — keep app open", android.widget.Toast.LENGTH_LONG).show()\n                    }) {\n                        Text("DOWNLOAD")\n                    }'

if old1 in content:
    content = content.replace(old1, new1)
    print("Part 1 OK")
else:
    errors.append("Part 1 anchor not found")

# Part 2: Completion toast
anchor2 = "    Scaffold { innerPadding ->"
overlay2 = '    LaunchedEffect(downloadState) {\n        if (downloadState is ConvoyViewModel.DownloadState.Complete) {\n            val summary = (downloadState as ConvoyViewModel.DownloadState.Complete).summary\n            android.widget.Toast.makeText(context, "Map download complete — ${summary.downloaded} tiles", android.widget.Toast.LENGTH_LONG).show()\n        }\n    }\n\n'

if anchor2 in content:
    content = content.replace(anchor2, overlay2 + anchor2)
    print("Part 2 OK")
else:
    errors.append("Part 2 anchor not found")

# Part 3: Progress chip above button bar
old3 = '        ConvoyButtonBar(\n            hudMode = hudMode,\n            onModeChange = { viewModel.setHudMode(it) },\n            onNavigateToSettings = onNavigateToSettings,\n            modifier = Modifier.align(Alignment.BottomCenter)\n        )'
new3 = '        if (downloadState is ConvoyViewModel.DownloadState.Downloading) {\n            val ds = downloadState as ConvoyViewModel.DownloadState.Downloading\n            Surface(\n                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp).padding(horizontal = 16.dp),\n                shape = RoundedCornerShape(20.dp),\n                color = Color(0xE6131820),\n                shadowElevation = 4.dp\n            ) {\n                androidx.compose.foundation.layout.Row(\n                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),\n                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n                    verticalAlignment = Alignment.CenterVertically\n                ) {\n                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color(0xFF1CF0A0), strokeWidth = 2.dp)\n                    Text("⬇ ${ds.downloaded} / ${ds.total} tiles", color = Color(0xFF1CF0A0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)\n                }\n            }\n        }\n        ConvoyButtonBar(\n            hudMode = hudMode,\n            onModeChange = { viewModel.setHudMode(it) },\n            onNavigateToSettings = onNavigateToSettings,\n            modifier = Modifier.align(Alignment.BottomCenter)\n        )'

if old3 in content:
    content = content.replace(old3, new3)
    print("Part 3 OK")
else:
    errors.append("Part 3 anchor not found")

if errors:
    for e in errors: print("ERROR:", e)
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("ALL PARTS PATCHED OK")
