import sys

path_screen = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"
path_config = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt"

# ── Part 1: Add file access settings to WebView ──────────────
with open(path_screen, "r", encoding="utf-8") as f:
    screen = f.read()

old1 = """                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)"""

new1 = """                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)"""

if old1 in screen:
    screen = screen.replace(old1, new1)
    print("Part 1 OK — file access settings added")
else:
    print("ERROR: Part 1 anchor not found")
    sys.exit(1)

# ── Part 2: Switch to local tile URL after download completes ─
# After download Complete — call setTileUrl with local path
old2 = """                onSuccess = { summary ->
                    _downloadState.value = DownloadState.Complete(summary)
                    kotlinx.coroutines.delay(3_000L)
                    _downloadState.value = DownloadState.Idle"""

# This is in ViewModel — handle in screen via LaunchedEffect instead
# Add a LaunchedEffect to switch tile URL when download completes
# Find the completion toast LaunchedEffect and extend it

old2 = """    LaunchedEffect(downloadState) {
        if (downloadState is ConvoyViewModel.DownloadState.Complete) {
            val summary = (downloadState as ConvoyViewModel.DownloadState.Complete).summary
            android.widget.Toast.makeText(
                context,
                "Map download complete — ${summary.downloaded} tiles",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }"""

new2 = """    LaunchedEffect(downloadState) {
        if (downloadState is ConvoyViewModel.DownloadState.Complete) {
            val summary = (downloadState as ConvoyViewModel.DownloadState.Complete).summary
            android.widget.Toast.makeText(
                context,
                "Map download complete — ${summary.downloaded} tiles",
                android.widget.Toast.LENGTH_LONG
            ).show()
            // Switch map to local tile cache
            val sourceName = ConvoyConfig.ACTIVE_TILE_SOURCE
            val localUrl = ConvoyConfig.LOCAL_TILE_BASE + sourceName + "/{z}/{x}/{y}.png"
            webViewRef.value?.evaluateJavascript("setTileUrl('$localUrl')", null)
        }
    }"""

if old2 in screen:
    screen = screen.replace(old2, new2)
    print("Part 2 OK — local tile switch on completion")
else:
    print("ERROR: Part 2 anchor not found")
    sys.exit(1)

with open(path_screen, "w", encoding="utf-8") as f:
    f.write(screen)

# ── Part 3: Add LOCAL_TILE_BASE to ConvoyConfig ───────────────
with open(path_config, "r", encoding="utf-8") as f:
    config = f.read()

old3 = "    var ACTIVE_TILE_SOURCE = \"SAT\""
new3 = """    var ACTIVE_TILE_SOURCE = "SAT"
    const val LOCAL_TILE_BASE = "file:///data/user/0/com.geeksville.mesh.google.debug/files/tiles/\""""

if old3 in config:
    config = config.replace(old3, new3)
    print("Part 3 OK — LOCAL_TILE_BASE added to ConvoyConfig")
else:
    print("ERROR: Part 3 anchor not found")
    sys.exit(1)

with open(path_config, "w", encoding="utf-8") as f:
    f.write(config)

print("ALL PARTS PATCHED OK")
