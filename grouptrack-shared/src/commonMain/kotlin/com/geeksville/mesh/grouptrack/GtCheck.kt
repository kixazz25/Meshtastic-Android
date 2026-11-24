package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TRAILTABLE-2026-11-24: MOVED here from app/DownloadDefaults.kt so GtTrailTable can draw it in
 * commonMain. SAME PACKAGE as before, so all 17 Android call sites resolve unchanged.
 */
/** DLDEFAULTS: the GroupTrack check -- a bordered box with a check mark when on, blank when off (the STD1H rule), readable on both maps. */
@Composable
fun GtCheck(checked: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).border(2.dp, Gt.text, RoundedCornerShape(4.dp)).background(Gt.panel, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center) {
        if (checked) Text("\u2713", color = Gt.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
