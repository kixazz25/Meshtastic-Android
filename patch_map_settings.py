path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add new state vars after showLayerMenu
old1 = "    var showLayerMenu by remember { mutableStateOf(false) }\n    var mapTypeLabel by remember { mutableStateOf(\"SAT\") }"
new1 = """    var showLayerMenu by remember { mutableStateOf(false) }
    var mapTypeLabel by remember { mutableStateOf("SAT") }
    var showMapSettings by remember { mutableStateOf(false) }
    var mapZoomLevel by remember { mutableStateOf(18f) }
    var isOfflineMode by remember { mutableStateOf(false) }
    var signalDropMinutes by remember { mutableStateOf(2f) }
    var lostMinutes by remember { mutableStateOf(10f) }
    var offTrackMiles by remember { mutableStateOf(0.5f) }"""

if old1 in content:
    content = content.replace(old1, new1)
    print("Added settings state vars")
else:
    print("ERROR: state vars not found")

# 2. Add Slider import
if "import androidx.compose.material3.Slider" not in content:
    content = content.replace(
        "import androidx.compose.material3.Card",
        "import androidx.compose.material3.Card\nimport androidx.compose.material3.Slider\nimport androidx.compose.material3.Switch"
    )
    print("Added Slider/Switch imports")

# 3. Replace the layers Box with full settings panel
old2 = """            // Map layer selector
            Box {
                Surface(
                    modifier = Modifier.clickable { showLayerMenu = !showLayerMenu },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC1E252F),
                    shadowElevation = 4.dp
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Layers,
                            contentDescription = "Map Layers",
                            tint = Color(0xFF2E75B6),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(mapTypeLabel, color = Color(0xFF2E75B6), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
                DropdownMenu(
                    expanded = showLayerMenu,
                    onDismissRequest = { showLayerMenu = false },
                    modifier = Modifier.background(Color(0xFF1E252F))
                ) {
                    listOf(
                        Triple("SAT", "Satellite", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
                        Triple("HYB", "Hybrid", "https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"),
                        Triple("TOPO", "Topo", "https://tile.opentopomap.org/"),
                        Triple("ROAD", "Road", "https://tile.openstreetmap.org/")
                    ).forEach { (label, name, url) ->
                        DropdownMenuItem(
                            text = { Text(name, color = if (mapTypeLabel == label) Color(0xFF2E75B6) else Color(0xFFE8EEF5),
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                            onClick = {
                                mapTypeLabel = label
                                showLayerMenu = false
                                val src = org.osmdroid.tileprovider.tilesource.XYTileSource(
                                    name, 1, 19, 256, if (label == "SAT") ".jpg" else ".png",
                                    arrayOf(url)
                                )
                                mapView.setTileSource(src)
                                mapView.invalidate()
                            }
                        )
                    }
                }
            }"""

new2 = """            // ── MAP SETTINGS PANEL ──────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xEE1E252F),
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(8.dp).width(180.dp)) {
                    // Header row — always visible, tap to expand/collapse
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showMapSettings = !showMapSettings },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Layers, contentDescription = "Settings",
                                tint = Color(0xFF2E75B6), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("MAP  $mapTypeLabel", color = Color(0xFF2E75B6), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Text(if (showMapSettings) "▲" else "▼", color = Color(0xFF4A6080), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }

                    // Expanded settings
                    if (showMapSettings) {
                        Spacer(Modifier.height(8.dp))

                        // ── Tile source buttons ──────────────────────────
                        Text("LAYER", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                Triple("SAT", "Satellite", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
                                Triple("HYB", "Hybrid", "https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"),
                                Triple("TOPO", "Topo", "https://tile.opentopomap.org/"),
                                Triple("RD", "Road", "https://tile.openstreetmap.org/")
                            ).forEach { (label, name, url) ->
                                Surface(
                                    modifier = Modifier.weight(1f).clickable {
                                        mapTypeLabel = label
                                        val src = org.osmdroid.tileprovider.tilesource.XYTileSource(
                                            name, 1, 19, 256, if (label == "SAT") ".jpg" else ".png", arrayOf(url))
                                        mapView.setTileSource(src)
                                        mapView.invalidate()
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (mapTypeLabel == label) Color(0xFF2E75B6) else Color(0xFF2A3545)
                                ) {
                                    Text(label, color = Color.White, fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // ── Zoom slider ──────────────────────────────────
                        Text("ZOOM  ${mapZoomLevel.toInt()}", color = Color(0xFF4A6080), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(
                            value = mapZoomLevel,
                            onValueChange = { mapZoomLevel = it },
                            onValueChangeFinished = { mapView.controller.setZoom(mapZoomLevel.toDouble()); mapView.invalidate() },
                            valueRange = 16f..19f,
                            steps = 2,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(6.dp))

                        // ── Online/Offline toggle ────────────────────────
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (isOfflineMode) "OFFLINE" else "ONLINE", color = if (isOfflineMode) Color(0xFFFFAA00) else Color(0xFF1CF0A0),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = isOfflineMode,
                                onCheckedChange = {
                                    isOfflineMode = it
                                    mapView.setUseDataConnection(!it)
                                    mapView.invalidate()
                                }
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // ── Download Region button ───────────────────────
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                // TODO: trigger tile pre-fetch for current bounding box at mapZoomLevel
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2A3545)
                        ) {
                            Text("⬇  DOWNLOAD REGION", color = Color(0xFF7A8DA0), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }

                        Spacer(Modifier.height(10.dp))

                        // ── Alert thresholds ─────────────────────────────
                        Text("ALERTS", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))

                        Text("SIGNAL DROP  ${signalDropMinutes.toInt()} min", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(value = signalDropMinutes, onValueChange = { signalDropMinutes = it },
                            valueRange = 1f..10f, steps = 8, modifier = Modifier.fillMaxWidth())

                        Text("LOST  ${lostMinutes.toInt()} min", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(value = lostMinutes, onValueChange = { lostMinutes = it },
                            valueRange = 5f..30f, steps = 4, modifier = Modifier.fillMaxWidth())

                        Text("OFF TRACK  ${"%.1f".format(offTrackMiles)} mi", color = Color(0xFF4A6080), fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace)
                        Slider(value = offTrackMiles, onValueChange = { offTrackMiles = it },
                            valueRange = 0.1f..2f, steps = 18, modifier = Modifier.fillMaxWidth())
                    }
                }
            }"""

if old2 in content:
    content = content.replace(old2, new2)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("Built map settings panel")
else:
    print("ERROR: layers panel not found")
