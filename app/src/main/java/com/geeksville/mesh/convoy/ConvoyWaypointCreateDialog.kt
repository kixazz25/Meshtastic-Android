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

// V2.5 Scaffold (Pass 1) -- ScreenReference v5 section 6
// Long-press: type -> proximity -> alias/create -> name -> share.

@Composable
fun ConvoyWaypointCreateDialog(
    lat: Double, lon: Double, onDismiss: () -> Unit = {}, modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.width(280.dp), shape = RoundedCornerShape(12.dp),
        color = Color(0xEE131820), shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CREATE WAYPOINT", color = Color(0xFFD29922), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("%.4f N, %.4f W".format(lat, kotlin.math.abs(lon)), color = Color(0xFF4A6080),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("1. Select Type", color = Color(0xFFCCDDEE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("2. Proximity + Alias", color = Color(0xFFCCDDEE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("3. Name + Share", color = Color(0xFFCCDDEE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("[ Pass 1 scaffold ]", color = Color(0xFF4A6080), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
