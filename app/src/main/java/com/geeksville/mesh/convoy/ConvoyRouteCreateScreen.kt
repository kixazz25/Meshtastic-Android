package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// V2.5 Scaffold (Pass 1) -- Decision Log v3 section 11
// Route creation: draw / snap / convert.

@Composable
fun ConvoyRouteCreateScreen(onNavigateBack: () -> Unit = {}) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ROUTE CREATION", color = Color(0xFFBC8CFF), fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("Draw / Snap / Convert", color = Color(0xFF4A6080), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("[ Pass 1 scaffold ]", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
