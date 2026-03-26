import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Part 1: Add isLocalTiles state variable near mapTypeLabel
old1 = '    var mapTypeLabel by remember { mutableStateOf("SAT") }'
new1 = '    var mapTypeLabel by remember { mutableStateOf("SAT") }\n    var isLocalTiles by remember { mutableStateOf(false) }'

if old1 in content:
    content = content.replace(old1, new1)
    print("Part 1 OK — isLocalTiles state added")
else:
    print("ERROR: Part 1 anchor not found")
    sys.exit(1)

# Part 2: Update map bar header to show source indicator
old2 = '                            Text("MAP  $mapTypeLabel", color = Color(0xFF2E75B6), fontSize = 11.sp,\n                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)'
new2 = '                            Text("MAP  $mapTypeLabel", color = Color(0xFF2E75B6), fontSize = 11.sp,\n                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)\n                            Spacer(Modifier.width(6.dp))\n                            Text(\n                                if (isLocalTiles) "LOCAL" else "ONLINE",\n                                color = if (isLocalTiles) Color(0xFF4DA6FF) else Color(0xFF1CF0A0),\n                                fontSize = 8.sp,\n                                fontFamily = FontFamily.Monospace,\n                                fontWeight = FontWeight.Bold\n                            )'

if old2 in content:
    content = content.replace(old2, new2)
    print("Part 2 OK — source label added to map bar")
else:
    print("ERROR: Part 2 anchor not found")
    sys.exit(1)

# Part 3: Set isLocalTiles=false when tile source button tapped
old3 = """                                modifier = Modifier.weight(1f).clickable {
                                        mapTypeLabel = label
                                        webViewRef.value?.evaluateJavascript("setTileUrl('$url')", null)
                                    },"""
new3 = """                                modifier = Modifier.weight(1f).clickable {
                                        mapTypeLabel = label
                                        isLocalTiles = false
                                        webViewRef.value?.evaluateJavascript("setTileUrl('$url')", null)
                                    },"""

if old3 in content:
    content = content.replace(old3, new3)
    print("Part 3 OK — isLocalTiles=false on source tap")
else:
    print("ERROR: Part 3 anchor not found")
    sys.exit(1)

# Part 4: Set isLocalTiles=true when local URL is switched after download
old4 = '            val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"\n            webViewRef.value?.evaluateJavascript("setTileUrl(\'"+localUrl+"\')", null)'
new4 = '            val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"\n            webViewRef.value?.evaluateJavascript("setTileUrl(\'"+localUrl+"\')", null)\n            isLocalTiles = true'

if old4 in content:
    content = content.replace(old4, new4)
    print("Part 4 OK — isLocalTiles=true on download complete")
else:
    print("ERROR: Part 4 anchor not found")
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("ALL PATCHED OK")
