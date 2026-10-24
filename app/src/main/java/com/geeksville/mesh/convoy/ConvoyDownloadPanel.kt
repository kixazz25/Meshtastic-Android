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

// DEADCODE2-2026-10-23: ConvoyDownloadPanel (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyDownloadPanel.pieces.txt

// ── Helper composables ──

// DEADCODE2-2026-10-23: PanelSectionHeader (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyDownloadPanel.pieces.txt

// DEADCODE2-2026-10-23: ArtifactCheckRow (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyDownloadPanel.pieces.txt

// DEADCODE2-2026-10-23: PanelActionButton (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyDownloadPanel.pieces.txt

// DEADCODE2-2026-10-23: OverlayToggleRow (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyDownloadPanel.pieces.txt
