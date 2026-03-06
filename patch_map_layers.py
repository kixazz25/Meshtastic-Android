path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add mapType state var
old1 = "    var recordingState by remember { mutableStateOf(RecordingState.IDLE) }"
new1 = """    var recordingState by remember { mutableStateOf(RecordingState.IDLE) }
    var showLayerMenu by remember { mutableStateOf(false) }
    var mapTypeLabel by remember { mutableStateOf("SAT") }"""

if old1 in content:
    content = content.replace(old1, new1)
    print("Added map type state vars")
else:
    print("ERROR: state var not found")

# 2. Replace Layers IconToggleButton with map type selector + lead track separate icon
old2 = """            // Task 5.3 — Show Lead Track toggle (REQ-110)
            IconToggleButton(
                checked = showLeadTrack,
                onCheckedChange = {
                    viewModel.setShowLeadTrack(it)
                    renderer.setLeadTrackVisible(it)
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Layers,
                    contentDescription = "Show Lead Track",
                    tint = if (showLeadTrack) Color(0xFF2E75B6) else Color(0xFF4A6080)
                )
            }"""

new2 = """            // Map layer selector
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
            }
            // Lead Track toggle
            IconToggleButton(
                checked = showLeadTrack,
                onCheckedChange = {
                    viewModel.setShowLeadTrack(it)
                    renderer.setLeadTrackVisible(it)
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Layers,
                    contentDescription = "Show Lead Track",
                    tint = if (showLeadTrack) Color(0xFF2E75B6) else Color(0xFF4A6080)
                )
            }"""

if old2 in content:
    content = content.replace(old2, new2)
    print("Added map layer selector")
else:
    print("ERROR: layers button not found")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
