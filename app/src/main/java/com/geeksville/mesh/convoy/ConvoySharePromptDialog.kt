package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------
// ConvoySharePromptDialog -- V2.5 Scaffold (Pass 1)
// "Share this artifact with other GroupTrack users? Yes/No"
// Controlled by app_setting + ride privacy toggle.
// V2.5: collect only. V2.6: processes.
// Source: Decision Log v3 section 3.4, Master Build Phase 0
// ----------------------------------------------------------------

@Composable
fun ConvoySharePromptDialog(
    artifactName: String,
    onYes: () -> Unit = {},
    onNo: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xEE131820),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("SHARE ARTIFACT", color = Color(0xFF4DA6FF), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("Share '$artifactName' with other GroupTrack users?",
                color = Color(0xFFCCDDEE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("V2.5: collecting only", color = Color(0xFF4A6080), fontSize = 8.sp,
                fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onNo) { Text("NO", color = Color(0xFF7A8DA0)) }
                TextButton(onClick = onYes) { Text("SHARE", color = Color(0xFF1CF0A0)) }
            }
        }
    }
}
