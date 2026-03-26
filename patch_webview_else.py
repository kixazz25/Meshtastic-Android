import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """                        loadUrl("file:///android_asset/convoy_map.html")
                    }.also {
                        viewModel.persistentWebView = it
                        webViewRef.value = it
                    }"""

new = """                        loadUrl("file:///android_asset/convoy_map.html")
                        addJavascriptInterface(object : Any() {
                            @android.webkit.JavascriptInterface
                            fun onMarkerTapped(nodeId: String) {
                                val node = viewModel.convoyState.value.nodes.firstOrNull { it.nodeId == nodeId }
                                if (node != null) viewModel.onMarkerTapped(node)
                            }

                            @android.webkit.JavascriptInterface
                            fun onAreaSelected(north: Double, south: Double, east: Double, west: Double) {
                                val estimate = ConvoyTileCalculator.quickEstimate(north, south, east, west)
                                val pending = ConvoyViewModel.PendingDownload(
                                    tileCount     = estimate.tileCount,
                                    sizeMB        = estimate.estimatedMB,
                                    withinCeiling = estimate.withinCeiling,
                                    north         = north,
                                    south         = south,
                                    east          = east,
                                    west          = west,
                                    sourceName    = ConvoyConfig.ACTIVE_TILE_SOURCE,
                                    sourceUrl     = ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: ""
                                )
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    viewModel.setPendingDownload(pending)
                                }
                            }
                        }, "Android")
                    }.also {
                        viewModel.persistentWebView = it
                        webViewRef.value = it
                    }"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("PATCHED OK")
else:
    print("ERROR: anchor not found")
    sys.exit(1)
