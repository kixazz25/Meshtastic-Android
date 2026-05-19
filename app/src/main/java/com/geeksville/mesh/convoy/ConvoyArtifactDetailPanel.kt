package com.geeksville.mesh.convoy

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

// V2.5 Scaffold (Pass 1) -- ScreenReference v5 section 5
// One composable, 4 type configs. Badge + aliases + props + actions.

@Composable
fun ConvoyArtifactDetailPanel(
    artifactType: String, artifactId: String, isConvoyMap: Boolean,
    onDismiss: () -> Unit = {}, modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.width(260.dp), shape = RoundedCornerShape(10.dp),
        color = Color(0xEE131820), shadowElevation = 6.dp) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ARTIFACT DETAIL", color = Color(0xFF4DA6FF), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("$artifactType: $artifactId", color = Color(0xFFCCDDEE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(if (isConvoyMap) "Read-only" else "FIT | +ALIAS | type actions", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("[ Pass 1 scaffold ]", color = Color(0xFF4A6080), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
