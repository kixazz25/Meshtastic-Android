import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

errors = []

# Part 1: Wire the switch onCheckedChange to actually switch tile URL
old1 = """                            Switch(
                                checked = isOfflineMode,
                                onCheckedChange = {
                                    isOfflineMode = it
                                    // TODO A2.5: setOffline via JS bridge
                                }
                            )"""

new1 = """                            Switch(
                                checked = isOfflineMode,
                                onCheckedChange = { goOffline ->
                                    isOfflineMode = goOffline
                                    if (goOffline) {
                                        val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"
                                        webViewRef.value?.evaluateJavascript("setTileUrl('"+localUrl+"')", null)
                                    } else {
                                        val onlineUrl = ConvoyConfig.TILE_SOURCES[ConvoyConfig.ACTIVE_TILE_SOURCE] ?: ""
                                        webViewRef.value?.evaluateJavascript("setTileUrl('"+onlineUrl+"')", null)
                                    }
                                }
                            )"""

if old1 in content:
    content = content.replace(old1, new1)
    print("Part 1 OK — switch wired to tile URL")
else:
    errors.append("Part 1: switch anchor not found")

# Part 2: Remove auto-switch on download complete
old2 = '            val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"\n            webViewRef.value?.evaluateJavascript("setTileUrl(\'"+localUrl+"\')", null)\n            isLocalTiles = true'
new2 = '            // Tile download complete — user controls online/offline via switch'

if old2 in content:
    content = content.replace(old2, new2)
    print("Part 2 OK — auto-switch removed")
else:
    errors.append("Part 2: auto-switch anchor not found")

# Part 3: Update map bar label to follow isOfflineMode
old3 = '                                if (isLocalTiles) "LOCAL" else "ONLINE",'
new3 = '                                if (isOfflineMode) "LOCAL" else "ONLINE",'
old3b = '                                color = if (isLocalTiles) Color(0xFF4DA6FF) else Color(0xFF1CF0A0),'
new3b = '                                color = if (isOfflineMode) Color(0xFF4DA6FF) else Color(0xFF1CF0A0),'

if old3 in content:
    content = content.replace(old3, new3)
    content = content.replace(old3b, new3b)
    print("Part 3 OK — map bar label follows switch state")
else:
    errors.append("Part 3: label anchor not found")

if errors:
    for e in errors: print("ERROR:", e)
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("ALL PATCHED OK")
