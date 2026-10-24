package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GTMAP-2026-10-22 (2.7c, Fred 14:35 "bring over map functions EXACTLY as they exist"): MAP TYPE at the top of the Map panel --
 * the map bar's own slots (SAT / TOPO / TOPO+, from Map sources), the SAME change the map bar makes (one shared function).
 */
@Composable
fun GtMapTypeRow(types: List<String>, current: String, onType: (String) -> Unit) {
    val s = GtScheme.current.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("MAP TYPE", color = s.panelDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().border(1.dp, s.edge, RoundedCornerShape(8.dp))) {
            types.forEach { t ->
                val on = t == current
                Box(Modifier.weight(1f).background(if (on) Color(0xFF1565C0) else Color.Transparent).clickable { onType(t) }
                    .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(t, color = if (on) Color.White else s.panelText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
