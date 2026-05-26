package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------
// ConvoyDownloadPanel -- V2.5 Full Implementation
// Bidirectional area transfer panel: LOCAL imports + NET (V2.6 stubs)
// Sections: Flyover, Artifact Types, Draw Area, Show Downloads
// Source: ScreenReference v5, Download Panel Design May 20 2026
// ----------------------------------------------------------------

data class DownloadBbox(
    val north: Double = 0.0,
    val south: Double = 0.0,
    val east: Double = 0.0,
    val west: Double = 0.0
) {
    val isValid get() = north != 0.0 || south != 0.0
    fun toDisplayN() = String.format("%.2f\u00b0N", north)
    fun toDisplayS() = String.format("%.2f\u00b0N", south)
    fun toDisplayE() = String.format("%.2f\u00b0W", kotlin.math.abs(east))
    fun toDisplayW() = String.format("%.2f\u00b0W", kotlin.math.abs(west))
}

@Composable
fun ConvoyDownloadPanel(
    bbox: DownloadBbox = DownloadBbox(),
    tilesChecked: Boolean = false,
    onTilesCheckedChange: (Boolean) -> Unit = {},
    trailsChecked: Boolean = false,
    onTrailsCheckedChange: (Boolean) -> Unit = {},
    removeTilesChecked: Boolean = false,
    onRemoveTilesCheckedChange: (Boolean) -> Unit = {},
    flyoverZoom: Int = 18,
    onFlyoverZoomChange: (Int) -> Unit = {},
    tileEstimate: String = "",
    trailSourceCount: Int = 0,
    isDrawing: Boolean = false,
    onDrawArea: () -> Unit = {},
    onClearArea: () -> Unit = {},
    onExecuteDownload: (tiles: Boolean, trails: Boolean, removeTiles: Boolean) -> Unit = { _, _, _ -> },
    onNavigateToTrailSources: (DownloadBbox) -> Unit = {},
    onShowDownloadedMaps: (Boolean) -> Unit = {},
    onShowMapsInQueue: (Boolean) -> Unit = {},
    onShowDownloadedTrails: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val mono = FontFamily.Monospace
    val blue = Color(0xFF4DA6FF)
    val green = Color(0xFF3fb950)
    val red = Color(0xFFf85149)
    val orange = Color(0xFFd29922)
    val purple = Color(0xFFbc8cff)
    val dimText = Color(0xFF4A6080)
    val panelBg = Color(0xEE131820)
    val rowBg = Color(0xFF1A2A3A)
    val sectionBorder = Color(0xFF30363d)

    // Internal state
    var isNetMode by remember { mutableStateOf(false) }
    // tilesChecked, trailsChecked, removeTilesChecked, flyoverZoom — lifted to parent
    var flyoverMileage by remember { mutableFloatStateOf(0f) }
    var showMaps by remember { mutableStateOf(true) }
    var expandMapControls by remember { mutableStateOf(false) }
    var expandSelectTypes by remember { mutableStateOf(false) }
    var expandDrawArea by remember { mutableStateOf(false) }
    var expandShowDownloads by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(true) }
    // showTrails removed — not needed

    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(10.dp),
        color = panelBg,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {

            // ── HEADER with LOCAL/NET toggle ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isNetMode) "\u2195 TRANSFERS" else "\u2195 DOWNLOADS",
                    color = blue, fontSize = 10.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isNetMode) "NET" else "LOCAL",
                        color = if (isNetMode) purple else blue,
                        fontSize = 8.sp, fontFamily = mono, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = isNetMode,
                        onCheckedChange = { isNetMode = it },
                        modifier = Modifier.height(20.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = purple,
                            checkedTrackColor = purple.copy(alpha = 0.3f),
                            uncheckedThumbColor = blue,
                            uncheckedTrackColor = blue.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            // ── MAP CONTROLS (collapsible) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandMapControls = !expandMapControls },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandMapControls) "▼" else "▶", color = blue, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("MAP CONTROLS", color = blue, fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandMapControls) {
            PanelSectionHeader("FLYOVER", sectionBorder)
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                // Mileage slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mileage", color = dimText, fontSize = 9.sp,
                        fontFamily = mono, modifier = Modifier.width(52.dp))
                    Slider(
                        value = flyoverMileage,
                        onValueChange = { flyoverMileage = it; flyoverMileage = it },
                        valueRange = 0f..200f,
                        modifier = Modifier.weight(1f).height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = blue, activeTrackColor = blue
                        )
                    )
                    Text(
                        String.format("%.1f mi", flyoverMileage),
                        color = Color.White, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.width(42.dp)
                    )
                }
                // Zoom slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom", color = dimText, fontSize = 9.sp,
                        fontFamily = mono, modifier = Modifier.width(52.dp))
                    Slider(
                        value = flyoverZoom.toFloat(),
                        onValueChange = { onFlyoverZoomChange(it.toInt()) },
                        valueRange = 8f..18f,
                        steps = 9,
                        modifier = Modifier.weight(1f).height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = blue, activeTrackColor = blue
                        )
                    )
                    Text(
                        "Z$flyoverZoom",
                        color = Color.White, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.width(42.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── ARTIFACT TYPES (Step 1) ──
            } // end expandMapControls
            // ── SELECT TILES/ARTIFACTS (collapsible) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandSelectTypes = !expandSelectTypes },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandSelectTypes) "▼" else "▶", color = green, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("SELECT TILES / ARTIFACTS", color = green, fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandSelectTypes) {
            Spacer(Modifier.height(4.dp))

            if (!isNetMode) {
                // LOCAL MODE
                ArtifactCheckRow("Download Tiles", blue, tilesChecked, true) { onTilesCheckedChange(it) }
                ArtifactCheckRow("Import Trails", green, trailsChecked, true) { onTrailsCheckedChange(it) }
                ArtifactCheckRow("Remove Tiles", red, removeTilesChecked, true) { onRemoveTilesCheckedChange(it) }
                Spacer(Modifier.height(2.dp))
                ArtifactCheckRow("Waypoints", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("Tracks", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("Routes", dimText, false, false, "V2.6") {}
            } else {
                // NET MODE - all V2.6 stubs
                ArtifactCheckRow("\u2195 Tiles", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("\u2195 Trails", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("\u2195 Waypoints", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("\u2195 Tracks", dimText, false, false, "V2.6") {}
                ArtifactCheckRow("\u2195 Routes", dimText, false, false, "V2.6") {}
            }

            } // end expandSelectTypes

            // ── DRAW AREA (collapsible) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandDrawArea = !expandDrawArea },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandDrawArea) "▼" else "▶", color = blue, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("DRAW AREA", color = blue, fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandDrawArea) {
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelActionButton(
                    label = if (isDrawing) "Drawing..." else "Draw Area",
                    color = blue,
                    enabled = !isNetMode && (tilesChecked || trailsChecked || removeTilesChecked),
                    modifier = Modifier.weight(1f)
                ) { onDrawArea() }
                PanelActionButton(
                    label = "Clear",
                    color = dimText,
                    enabled = bbox.isValid,
                    modifier = Modifier.weight(0.6f)
                ) { onClearArea() }
            }

            // Bbox display
            if (bbox.isValid) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = rowBg
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Text(
                            "${bbox.toDisplayS()} \u2192 ${bbox.toDisplayN()}  ${bbox.toDisplayW()} \u2192 ${bbox.toDisplayE()}",
                            color = dimText, fontSize = 8.sp, fontFamily = mono
                        )
                        if (tileEstimate.isNotEmpty()) {
                            Text(
                                "Tiles: $tileEstimate",
                                color = Color.White, fontSize = 8.sp, fontFamily = mono
                            )
                        }
                        if (trailsChecked && trailSourceCount > 0) {
                            Text(
                                "Trails: $trailSourceCount sources found",
                                color = green, fontSize = 8.sp, fontFamily = mono
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            } // end expandDrawArea

            // ── SHOW DOWNLOADS (collapsible) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expandShowDownloads = !expandShowDownloads },
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(if (expandShowDownloads) "▼" else "▶", color = Color(0xFF8b949e), fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("SHOW DOWNLOADS", color = Color(0xFF8b949e), fontSize = 10.sp,
                        fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            if (expandShowDownloads) {
            Spacer(Modifier.height(4.dp))
            OverlayToggleRow("Downloaded maps area", blue, showMaps) {
                showMaps = it; onShowDownloadedMaps(it)
            }
            OverlayToggleRow("Maps in queue", red, showQueue) {
                showQueue = it; onShowMapsInQueue(it)
            }
            // Refresh button
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    onShowDownloadedMaps(true)
                    onShowMapsInQueue(true)
                },
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1A2A3A)
            ) {
                Text("REFRESH", color = Color(0xFF4DA6FF),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        .fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            } // end expandShowDownloads
        }
    }
}

// ── Helper composables ──

@Composable
private fun PanelSectionHeader(title: String, borderColor: Color) {
    Text(
        title,
        color = Color(0xFF8b949e),
        fontSize = 8.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth()
            .border(width = 0.5.dp, color = borderColor, shape = RoundedCornerShape(0.dp))
            .padding(vertical = 2.dp)
    )
}

@Composable
private fun ArtifactCheckRow(
    label: String,
    color: Color,
    checked: Boolean,
    enabled: Boolean,
    badge: String = "",
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            modifier = Modifier.size(18.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = color,
                uncheckedColor = color.copy(alpha = 0.4f),
                checkmarkColor = Color.Black
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = if (enabled) color else color.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (badge.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = Color(0xFF1A2A3A)
            ) {
                Text(
                    badge,
                    color = Color(0xFF4A6080),
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun PanelActionButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onShowDownloadedMaps: (Boolean) -> Unit = {},
    onShowMapsInQueue: (Boolean) -> Unit = {},
    onShowDownloadedTrails: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(4.dp),
        color = if (enabled) color.copy(alpha = 0.15f) else Color(0xFF1A2A3A).copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            if (enabled) color.copy(alpha = 0.5f) else Color(0xFF30363d)
        )
    ) {
        Text(
            label,
            color = if (enabled) color else Color(0xFF4A6080),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
                .fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun OverlayToggleRow(
    label: String,
    color: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(18.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = color,
                uncheckedColor = color.copy(alpha = 0.4f),
                checkmarkColor = Color.Black
            )
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier.size(10.dp)
                .background(color.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = Color(0xFF8b949e),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
