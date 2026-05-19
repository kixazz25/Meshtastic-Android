package com.geeksville.mesh.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------
// ConvoyDownloadPanel -- V2.5 Scaffold (Pass 1)
// V2.5 ACTIVE: Download Tiles (existing), Remove Tiles by Area (new)
// V2.6 STUBS: Download Area, Download Artifact, Upload Artifacts
// Source: Decision Log v3 section 3.6, Master Build Phase 0
// ----------------------------------------------------------------

@Composable
fun ConvoyDownloadPanel(
    onDownloadTiles: () -> Unit = {},
    onRemoveTilesByArea: () -> Unit = {},
    onDownloadArea: () -> Unit = {},
    onDownloadArtifact: () -> Unit = {},
    onUploadArtifacts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val mono = FontFamily.Monospace
    Surface(
        modifier = modifier.width(260.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xEE131820),
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("DOWNLOAD PANEL", color = Color(0xFF4DA6FF), fontSize = 10.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold)

            // V2.5 Active
            DownloadPanelButton("Download Tiles", Color(0xFF4DA6FF), true, onDownloadTiles)
            DownloadPanelButton("Remove Tiles by Area", Color(0xFFf85149), true, onRemoveTilesByArea)

            Spacer(modifier = Modifier.height(4.dp))
            Text("V2.6", color = Color(0xFF4A6080), fontSize = 8.sp, fontFamily = mono)

            // V2.6 Stubs
            DownloadPanelButton("Download Area", Color(0xFF4A6080), false, onDownloadArea)
            DownloadPanelButton("Download Artifact", Color(0xFF4A6080), false, onDownloadArtifact)
            DownloadPanelButton("Upload Artifacts", Color(0xFF4A6080), false, onUploadArtifacts)
        }
    }
}

@Composable
private fun DownloadPanelButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(4.dp),
        color = if (enabled) Color(0xFF1A2A3A) else Color(0xFF1A2A3A).copy(alpha = 0.4f)
    ) {
        Text(label, color = if (enabled) color else color.copy(alpha = 0.4f),
            fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}
