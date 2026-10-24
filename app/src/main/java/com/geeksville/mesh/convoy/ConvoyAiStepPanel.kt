package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * AISTEPS-2026-08-28 — the front of the AI flow: overview, then parameters.
 *
 * These two REPLACE the AI design panel's PHASE_SETUP. They are full screen and
 * carry no map, because neither is map work — Fred, 08-28: "we are not at the
 * map point in step two, we are setting end state parameters for the ride
 * creation. Maps are a distraction."
 *
 * ⭐ ONE FUNCTION FOR BOTH. The two steps differ in their words and in whether
 * they carry sliders; everything else — the header, the step counter, the
 * scroll, the two buttons — is identical. Building them as two composables
 * would be two copies of one layout, which is the disease the overriding rule
 * exists to prevent.
 *
 * ⚠ WHAT IS DELIBERATELY NOT HERE: no guided-tour mode, no per-step teaching
 * variants, no app-wide "show me how" flag. Fred, 08-28: "guided tour belongs
 * in the new quickstart." The checkbox on step 1 means exactly what it says and
 * nothing more.
 */

private val stPanel = Color(0xFF0F1216)
private val stCard  = Color(0xFF131820)
private val stInk   = Color(0xFFE6EDF3)
private val stDim   = Color(0xFF9AA4B2)
private val stBlue  = Color(0xFF4DA6FF)
private val stGreen = Color(0xFF4ADE80)
private val stTrack = Color(0xFF2A3038)
private val stMuted = Color(0xFF5A6472)
private val stMono  = FontFamily.Monospace

// DEADCODE2-2026-10-23: ConvoyAiStepPanel (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyAiStepPanel.pieces.txt

@Composable
private fun Body(t: String) {
    Text(t, color = stInk, fontSize = 14.sp, lineHeight = 21.sp,
        modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun Note(t: String) {
    Text(t, color = stDim, fontSize = 13.sp, lineHeight = 19.sp,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SliderRow(
    label: String, value: String, pos: Float, range: ClosedFloatingPointRange<Float>,
    tint: Color, onChange: (Float) -> Unit,
) {
    if (label.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
            Text(label, color = stDim, fontSize = 11.sp, fontFamily = stMono,
                modifier = Modifier.weight(1f))
            Text(value, color = tint, fontSize = 13.sp, fontFamily = stMono)
        }
    }
    Slider(
        value = pos, onValueChange = onChange, valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = tint, activeTrackColor = tint, inactiveTrackColor = stTrack
        ),
        modifier = Modifier.fillMaxWidth().height(28.dp)
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun StepButton(
    label: String, tint: Color, bg: Color, modifier: Modifier, onClick: () -> Unit,
) {
    Surface(modifier = modifier.height(42.dp), shape = RoundedCornerShape(7.dp),
        color = bg) {
        Box(contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().clickable { onClick() }) {
            Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
