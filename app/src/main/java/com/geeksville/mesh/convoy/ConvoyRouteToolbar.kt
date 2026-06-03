package com.geeksville.mesh.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------
// ConvoyRouteToolbar -- V2.5 route builder overlay (shared, both maps)
//
// Replaces the collapsed WORK WITH ARTIFACTS panel when route mode is on.
// SCAFFOLD: layout + visual method selection are live; build actions are
// callbacks the host screen supplies. Point-to-point is the live method;
// Draw / Suggest are visible "soon" placeholders.
// ----------------------------------------------------------------

const val ROUTE_METHOD_P2P = 0      // point-to-point snap-2 (LIVE)
const val ROUTE_METHOD_DRAW = 1     // finger-trace (placeholder)
const val ROUTE_METHOD_SUGGEST = 2  // suggest from points (placeholder)

private val rtPanelBg = Color(0xEE131820)
private val rtRowBg   = Color(0xFF1A2233)
private val rtSelBg   = Color(0xFF0F6E56)
private val rtPurple  = Color(0xFFBC8CFF)
private val rtGreen   = Color(0xFF1CF0A0)
private val rtBlue    = Color(0xFF4DA6FF)
private val rtTxtD    = Color(0xFF7A8DA0)
private val rtAmber   = Color(0xFFD29922)
private val rtRed     = Color(0xFFE86B6B)
private val rtMono    = FontFamily.Monospace

@Composable
fun ConvoyRouteToolbar(
    isConvoyMap: Boolean,
    vertexCount: Int = 0,
    selectedMethod: Int = ROUTE_METHOD_P2P,
    onSelectMethod: (Int) -> Unit = {},
    onAddPointModeArmed: () -> Unit = {},
    onUndo: () -> Unit = {},
    onSaveCompleted: () -> Unit = {},
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(248.dp),
        shape = RoundedCornerShape(10.dp),
        color = rtPanelBg,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("ROUTE +", color = rtPurple, fontSize = 12.sp,
                    fontFamily = rtMono, fontWeight = FontWeight.Bold)
                Text("EXIT", color = rtRed, fontSize = 11.sp,
                    fontFamily = rtMono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onExit() }.padding(start = 8.dp))
            }

            Text("Start / Continue planning your route",
                color = rtTxtD, fontSize = 9.sp, fontFamily = rtMono)

            Text("METHOD", color = rtTxtD, fontSize = 9.sp, fontFamily = rtMono)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MethodChip("Point", selectedMethod == ROUTE_METHOD_P2P, true) {
                    onSelectMethod(ROUTE_METHOD_P2P); onAddPointModeArmed()
                }
                MethodChip("Draw", selectedMethod == ROUTE_METHOD_DRAW, false) {
                    onSelectMethod(ROUTE_METHOD_DRAW)
                }
                MethodChip("Suggest", selectedMethod == ROUTE_METHOD_SUGGEST, false) {
                    onSelectMethod(ROUTE_METHOD_SUGGEST)
                }
            }

            Text("BUILD  ($vertexCount pts)", color = rtTxtD, fontSize = 9.sp, fontFamily = rtMono)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                BuildBtn("Add", rtGreen, Modifier.weight(1f)) { onAddPointModeArmed() }
                BuildBtn("Undo", rtBlue, Modifier.weight(1f)) { onUndo() }
            }

            BuildBtn(
                if (vertexCount >= 2) "Save completed" else "Save completed (need 2+)",
                if (vertexCount >= 2) rtGreen else rtTxtD,
                Modifier.fillMaxWidth()
            ) { if (vertexCount >= 2) onSaveCompleted() }
        }
    }
}

@Composable
private fun MethodChip(label: String, selected: Boolean, live: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(5.dp),
        color = if (selected) rtSelBg else rtRowBg
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (selected) rtGreen else rtTxtD,
                fontSize = 10.sp, fontFamily = rtMono, fontWeight = FontWeight.Bold)
            Text(if (live) "live" else "soon",
                color = if (live) rtGreen else rtAmber,
                fontSize = 8.sp, fontFamily = rtMono)
        }
    }
}

@Composable
private fun BuildBtn(label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(5.dp),
        color = rtRowBg
    ) {
        Text(label, color = tint, fontSize = 10.sp, fontFamily = rtMono,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp))
    }
}
