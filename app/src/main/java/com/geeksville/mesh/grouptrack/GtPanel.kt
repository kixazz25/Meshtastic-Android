package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * SHELL-2026-10-22 (2.7c standard 2): ONE colour set for every GroupTrack panel, defined once. Screens do not write colours of
 * their own (S1 found 249 hex colours written 1,581 times across 77 files). The scheme itself is settled panel by panel in the
 * reviews (Fred 10-22); changing it here changes every panel drawn with GtPanel.
 */
object GtColors {
    val Panel = Color(0xF2161B22)
    val Edge = Color(0xFF30363D)
    val Text = Color(0xFFE6EDF3)
    val Dim = Color(0xFF8B949E)
    val Accent = Color(0xFF58A6FF)
    val Good = Color(0xFF3FB950)
    val Warn = Color(0xFFD29922)
    val Bad = Color(0xFFF85149)
    val Button = Color(0xE61F2630)
}

/** SHELL2-2026-10-22 (Fred 10:08-10:09): buttons AND panels follow the map type -- on satellite (SAT, hybrid) WHITE with BLUE letters,
 *  on topo BLUE with WHITE letters (the default for each map type). One place decides it; every GtButton reads it. */
class GtButtonScheme(val bg: Color, val text: Color, val edge: Color, val panel: Color, val panelText: Color, val panelDim: Color)

object GtScheme {
    private val Blue = Color(0xFF1565C0)
    // Fred 10:09: satellite imagery labels every feature in WHITE, so a dark panel with white letters is lost on it.
    private val OnSatellite = GtButtonScheme(Color.White, Blue, Blue, panel = Color(0xF7FFFFFF), panelText = Color(0xFF0D2A4F), panelDim = Color(0xFF5B6B7D))
    private val OnTopo = GtButtonScheme(Blue, Color.White, Color.White, panel = Color(0xF70D3B73), panelText = Color.White, panelDim = Color(0xFFB8C7D9))
    val current = mutableStateOf(OnSatellite)
    fun forMap(mapType: String): GtButtonScheme {
        val k = mapType.uppercase()
        return if (k.startsWith("SAT") || k.contains("HYB")) OnSatellite else OnTopo
    }
}

/** A labelled control (Fred: labels, never hover-only icons). [emphasis] overrides the scheme's letter colour
 *  (Color.Unspecified = the scheme's own). */
@Composable
fun GtButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, emphasis: Color = Color.Unspecified, enabled: Boolean = true) {
    val s = GtScheme.current.value
    Box(
        modifier = modifier
            .background(s.bg, RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, s.edge), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (!enabled) GtColors.Dim else if (emphasis == Color.Unspecified) s.text else emphasis,
            fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** SHELL3-2026-10-22 (Fred 10:12): a labelled control WITH an icon -- KEY (key), FILTER (funnel), CENTRE ON ME (bullseye).
 *  The label stays: icons are never the only label (Fred). */
@Composable
fun GtIconButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = GtScheme.current.value
    Row(
        modifier = modifier
            .background(s.bg, RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, s.edge), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = s.text, modifier = Modifier.size(22.dp))
        Text(label, color = s.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * SHELL-2026-10-22: the ONE container every launch module and every first screen draws in (2.7c standards 1, 4, 5 -- Fred 10-22):
 *   1. it always SCROLLS (landscape broke 19 long panels that could not);
 *   4. a panel that STAYS on screen has MOVE (drag) and HIDE/SHOW (collapse); every panel has DONE (complete);
 *   5. every panel has HELP, opening the manual page for its function (GtHelp, from the link table).
 * The title bar is the same on every panel, so riders learn it once.
 *
 * @param stays       true = the panel stays on screen after its job (Queues, a route being built, a followed cart): MOVE and HIDE shown.
 * @param maxHeight   the room the panel may use; the body scrolls inside it.
 */
@Composable
fun GtPanel(
    title: String,
    helpId: String,
    onHelp: (String) -> Unit,
    onDone: () -> Unit,
    stays: Boolean,
    width: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var dx by remember(title) { mutableFloatStateOf(0f) }
    var dy by remember(title) { mutableFloatStateOf(0f) }
    var collapsed by remember(title) { mutableStateOf(false) }
    val s = GtScheme.current.value   // SHELL2-2026-10-22
    Column(
        modifier = modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .width(width)
            .background(s.panel, RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, s.edge), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (stays) {
                Text(
                    "☰ MOVE", color = s.panelDim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .pointerInput(title) { detectDragGestures { change, drag -> change.consume(); dx += drag.x; dy += drag.y } }
                        .padding(6.dp)
                )
            }
            Text(title, color = s.panelText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (stays) {
                Text(if (collapsed) "SHOW" else "HIDE", color = GtColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { collapsed = !collapsed }.padding(6.dp))
            }
            Text("HELP", color = GtColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onHelp(GtHelp.linkFor(helpId)) }.padding(6.dp))
            Text("DONE", color = GtColors.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onDone() }.padding(6.dp))
        }
        if (!collapsed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}
