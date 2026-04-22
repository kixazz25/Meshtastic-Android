package com.geeksville.mesh.convoy

import android.annotation.SuppressLint
import android.location.Geocoder
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone map viewer with trail overlays and track display.
 * V2.4 -- independent from convoy map. Uses grouptrack_map.html.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConvoyMapViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var activeSource by remember { mutableStateOf("SAT") }
    var trailsOn by remember { mutableStateOf(true) }
    var showTrackPanel by remember { mutableStateOf(false) }
    var trackFileList by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadedTracks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var trackSearchText by remember { mutableStateOf("") }
    val trackColors = listOf("#39FF14")
    var nextColorIdx by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val tileSources = listOf(
        Triple("SAT", "Satellite",
            "https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"),
        Triple("TOPO", "Topo",
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}"),
        Triple("TOPO+", "Topo+",
            "https://server.arcgisonline.com/ArcGIS/rest/services/USA_Topo_Maps/MapServer/tile/{z}/{y}/{x}")
    )

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E14))) {
        // -- Header --
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF131820))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "BACK",
                color = Color(0xFF4DA6FF),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }.padding(8.dp)
            )
            Text(
                "MAP VIEWER",
                color = Color(0xFFE8EEF5),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "FIND",
                    color = if (showSearch) Color(0xFF00AAFF) else Color(0xFF445566),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showSearch = !showSearch }.padding(8.dp)
                )
                Text(
                    "TRAILS",
                    color = if (trailsOn) Color(0xFF00AAFF) else Color(0xFF445566),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        trailsOn = !trailsOn
                        webViewRef?.evaluateJavascript("toggleTrails()", null)
                    }.padding(8.dp)
                )
                Text(
                    "TRACKS",
                    color = if (showTrackPanel) Color(0xFF39FF14) else Color(0xFF445566),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        // Check file access permission
                        if (!showTrackPanel) {
                            trackFileList = scanTrackDir(context)
                            trackSearchText = ""
                        }
                        showTrackPanel = !showTrackPanel
                    }.padding(8.dp)
                )
            }
        }

        // -- Search bar (collapsible) --
        if (showSearch) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2030))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(Color(0xFF4DA6FF)),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0A1020), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { innerTextField ->
                        if (searchText.isEmpty()) {
                            Text("City, park, trail area...", color = Color(0xFF445566),
                                fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        }
                        innerTextField()
                    }
                )
                Surface(
                    modifier = Modifier.clickable {
                        if (searchText.isNotBlank()) {
                            coroutineScope.launch {
                                try {
                                    val results = withContext(Dispatchers.IO) {
                                        @Suppress("DEPRECATION")
                                        Geocoder(context).getFromLocationName(searchText, 5)
                                    }
                                    if (!results.isNullOrEmpty()) {
                                        val loc = results[0]
                                        webViewRef?.evaluateJavascript(
                                            "setView(" + loc.latitude + ", " + loc.longitude + ", 13)", null
                                        )
                                        webViewRef?.evaluateJavascript(
                                            "showSearchCenter(" + loc.latitude + ", " + loc.longitude + ")", null
                                        )
                                    } else {
                                        android.widget.Toast.makeText(context,
                                            "Location not found. Try adding state (e.g. Zion UT)",
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context,
                                        "Search error",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2E75B6)
                ) {
                    Text("FIND", color = Color.White, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }

        // -- Track panel --
        if (showTrackPanel) {
            val filtered = if (trackSearchText.isBlank()) trackFileList
                else trackFileList.filter { it.contains(trackSearchText, ignoreCase = true) }
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2030))
                    .heightIn(max = 220.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // Search
                BasicTextField(
                    value = trackSearchText,
                    onValueChange = { trackSearchText = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(Color(0xFF39FF14)),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF0A1020), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    decorationBox = { inner ->
                        if (trackSearchText.isEmpty()) Text("Search tracks...", color = Color(0xFF445566), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        inner()
                    }
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${filtered.size} of ${trackFileList.size} tracks",
                        color = Color(0xFF4A6080), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FIT", color = Color(0xFF4DA6FF), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { webViewRef?.evaluateJavascript("fitAllTrackFiles()", null) }.padding(4.dp))
                        Text("CLEAR", color = Color(0xFFFF4444), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                webViewRef?.evaluateJavascript("clearAllTrackFiles()", null)
                                loadedTracks = emptyList(); nextColorIdx = 0
                            }.padding(4.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtered.size) { idx ->
                        val name = filtered[idx]
                        val loaded = loadedTracks.any { it.first == name }
                        val displayColor = loadedTracks.firstOrNull { it.first == name }?.second
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    if (loaded) {
                                        val safe = name.replace("'", "\\'")
                                        webViewRef?.evaluateJavascript("removeTrackFile('$safe')", null)
                                        loadedTracks = loadedTracks.filterNot { it.first == name }
                                    } else {
                                        val color = trackColors[nextColorIdx % trackColors.size]
                                        nextColorIdx++
                                        loadTrackOnMap(context, name, color, webViewRef)
                                        loadedTracks = loadedTracks + Pair(name, color)
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(10.dp),
                                shape = RoundedCornerShape(2.dp),
                                color = if (loaded) Color(android.graphics.Color.parseColor(displayColor ?: "#666666")) else Color(0xFF2A3040)
                            ) {}
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = if (loaded) Color.White else Color(0xFF667788),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    }
                }
            }
        }

        // -- Map type buttons --
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF131820))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tileSources.forEach { (label, _, url) ->
                Surface(
                    modifier = Modifier.weight(1f).clickable {
                        activeSource = label
                        webViewRef?.evaluateJavascript(
                            "setTileUrl('" + url + "', '" + label + "')", null
                        )
                        trailsOn = (label == "SAT" || label == "TOPO")
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = if (activeSource == label) Color(0xFF2E75B6) else Color(0xFF1E252F)
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        // -- WebView --
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val satUrl = tileSources[0].third
                                view?.evaluateJavascript(
                                    "setTileUrl('" + satUrl + "', 'SAT')", null
                                )
                                // Load trail GeoJSON from assets and inject into WebView
                                try {
                                    val json = ctx.assets.open("utah_trails_stgeorge.geojson").bufferedReader().use { it.readText() }
                                    view?.evaluateJavascript("loadTrails(" + json + "); showTrails();", null)
                                    android.util.Log.d("MapViewer", "Trails injected from assets")
                                } catch (e: Exception) {
                                    android.util.Log.e("MapViewer", "Trail load error: " + e.message)
                                }
                            }
                        }
                        loadUrl("file:///android_asset/grouptrack_map.html")
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // -- Legend bar --
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF131820))
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(Color(0xFF00AAFF), "OHV")
            LegendDot(Color(0xFFFF8800), "Hike+Bike")
            LegendDot(Color(0xFFFFCC00), "Hike")
            LegendDot(Color(0xFFAA44FF), "Bike")
            LegendDot(Color(0xFF39FF14), "My Track")
        }
    }
}

// ── Track file helpers ─────────────────────────────────────────────
private fun scanTrackDir(context: android.content.Context): List<String> {
    val dir = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
        "my_tracks"
    )
    if (!dir.exists()) return emptyList()
    val files = dir.listFiles()
        ?.filter { f ->
            val ext = f.extension.lowercase()
            (ext == "kml" || ext == "gpx") &&
            !f.name.startsWith(".") &&
            !f.name.startsWith("convoy_track_temp")
        }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.name }
        ?: emptyList()
    android.util.Log.d("MapViewer", "scanTrackDir found ${files.size} tracks")
    return files
}

private fun loadTrackOnMap(
    context: android.content.Context,
    fileName: String,
    color: String,
    webView: android.webkit.WebView?
) {
    try {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ), "my_tracks"
        )
        val file = java.io.File(dir, fileName)
        if (!file.exists()) return
        val text = file.readText()
        val coords = if (fileName.lowercase().endsWith(".gpx")) parseGpx(text) else parseKml(text)
        if (coords.isEmpty()) return
        val json = coords.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
        val safe = fileName.replace("'", "\\'")
        webView?.evaluateJavascript("loadTrackFile('$safe', '$json', '$color')", null)
        android.util.Log.d("MapViewer", "Loaded $fileName: ${coords.size} points")
    } catch (e: Exception) {
        android.util.Log.e("MapViewer", "Track load error $fileName: ${e.message}")
    }
}

private fun parseKml(text: String): List<Pair<Double, Double>> {
    val coords = mutableListOf<Pair<Double, Double>>()
    val pattern = Regex("""<coordinates>([\s\S]*?)</coordinates>""")
    pattern.findAll(text).forEach { match ->
        match.groupValues[1].trim().lines().forEach { line ->
            val parts = line.trim().split(",")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lon != null && lat != null && lat != 0.0 && lon != 0.0) {
                    coords.add(Pair(lat, lon))
                }
            }
        }
    }
    return coords
}

private fun parseGpx(text: String): List<Pair<Double, Double>> {
    val coords = mutableListOf<Pair<Double, Double>>()
    val pattern = Regex("""<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)"""")
    pattern.findAll(text).forEach { match ->
        val lat = match.groupValues[1].toDoubleOrNull()
        val lon = match.groupValues[2].toDoubleOrNull()
        if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
            coords.add(Pair(lat, lon))
        }
    }
    return coords
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = RoundedCornerShape(4.dp),
            color = color
        ) {}
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            color = Color(0xFF8B938A),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
