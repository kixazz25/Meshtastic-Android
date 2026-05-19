package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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

// V2.5 Scaffold (Pass 1) -- ScreenReference v5 section 8
// 3 queues, one template, type filter. Accordion style.

@Composable
fun ConvoyQueuesPanel(onDismiss: () -> Unit = {}, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val mono = FontFamily.Monospace

    Surface(modifier = modifier.width(260.dp), shape = RoundedCornerShape(10.dp),
        color = Color(0xEE131820), shadowElevation = 6.dp) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (expanded) "v" else ">", color = Color(0xFF4DA6FF), fontSize = 11.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold)
                Text("QUEUES", color = Color(0xFF4DA6FF), fontSize = 10.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold)
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ALL | TILE | UPLOAD | DOWNLOAD", color = Color(0xFF4A6080), fontSize = 8.sp, fontFamily = mono)
                    QRow("Tile Queue", 0, Color(0xFF1CF0A0))
                    QRow("Upload Queue", 0, Color(0xFFBC8CFF))
                    QRow("Download Queue", 0, Color(0xFF4DA6FF))
                    Text("[ Pass 1 scaffold ]", color = Color(0xFF4A6080), fontSize = 8.sp, fontFamily = mono)
                }
            }
        }
    }
}

@Composable
private fun QRow(label: String, count: Int, color: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFFCCDDEE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("$count", color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}
