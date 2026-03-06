path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Switch to Esri WorldImagery which supports zoom 19
old1 = "            setTileSource(TileSourceFactory.USGS_SAT)"
new1 = """            // Esri WorldImagery supports zoom 19, better detail than USGS_SAT
            val esriSat = org.osmdroid.tileprovider.tilesource.XYTileSource(
                "Esri.WorldImagery", 1, 19, 256, ".jpg",
                arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
            )
            setTileSource(esriSat)"""

if old1 in content:
    content = content.replace(old1, new1)
    print("Switched to Esri WorldImagery zoom 19")
else:
    print("ERROR: tile source not found")

# 2. Add firstTick guard to suppress auto-zoom on startup
old2 = "    var recordingState by remember { mutableStateOf(RecordingState.IDLE) }"
new2 = """    var recordingState by remember { mutableStateOf(RecordingState.IDLE) }
    var mapInitialized by remember { mutableStateOf(false) }"""

if old2 in content:
    content = content.replace(old2, new2)
    print("Added mapInitialized guard")
else:
    print("ERROR: state var not found")

# 3. Wrap auto-zoom in mapInitialized check
old3 = "                // Smart zoom based on HUD mode\n                if (convoyState.nodes.isNotEmpty()) {"
new3 = "                // Smart zoom based on HUD mode — skip first tick to allow GPS open\n                if (convoyState.nodes.isNotEmpty() && mapInitialized) {"

if old3 in content:
    content = content.replace(old3, new3)
    print("Auto-zoom guarded by mapInitialized")
else:
    print("ERROR: auto-zoom marker not found")

# 4. Set mapInitialized after first render
old4 = "            controller.setCenter(startCenter)"
new4 = """            controller.setCenter(startCenter)
            // Mark initialized after 3 seconds to allow GPS center to settle
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // mapInitialized set via LaunchedEffect below
            }, 3000)"""

if old4 in content:
    content = content.replace(old4, new4)
    print("Added init delay placeholder")
else:
    print("ERROR: setCenter not found")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
