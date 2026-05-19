package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// V2.5 Scaffold (Pass 1) -- ScreenReference v5 section 9 (revised)
// Trail source catalog + two import methods (full source / area).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoyTrailSourceScreen(onNavigateBack: () -> Unit = {}) {
    val mono = FontFamily.Monospace
    Scaffold(topBar = {
        TopAppBar(title = { Text("Trail Source Management", fontFamily = mono, fontSize = 14.sp) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("APPROVED SOURCES", color = Color(0xFF4A6080), fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
            SourceCard("UGRC Utah Trails", "GeoJSON | Utah | OHV+Multi")
            SourceCard("BLM GTLF Motorized Trails", "GeoJSON | National | Motorized")
            SourceCard("USFS NFS Trails", "Shapefile | National | All")
            Spacer(modifier = Modifier.height(12.dp))
            Text("IMPORT METHODS", color = Color(0xFF4A6080), fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
            Text("A: Import Full Source", color = Color(0xFFCCDDEE), fontSize = 10.sp, fontFamily = mono)
            Text("B: Import by Area (draw boundary)", color = Color(0xFFCCDDEE), fontSize = 10.sp, fontFamily = mono)
            Text("[ Pass 1 scaffold ]", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = mono)
        }
    }
}

@Composable
private fun SourceCard(name: String, meta: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), color = Color(0xFF1A2233)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(name, color = Color(0xFFCCDDEE), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(meta, color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
