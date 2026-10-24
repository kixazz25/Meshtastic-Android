package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val mono = FontFamily.Monospace
private val bg: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF0D1117 
private val cardBg: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF161B22 
private val green: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF1CF0A0 
private val blue: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF4DA6FF 
private val txtB: Color get() = Gt.text  // STD1-2026-10-24 was 0xFFE8EEF5 
private val txtD: Color get() = Gt.text  // STD1-2026-10-24 was 0xFF7A8DA0 

// DEADCODE2-2026-10-23: TileDownloadRequest (unused) moved to retired/2.7c_dead/pieces/convoy/ConvoyDownloadConfirm.pieces.txt

data class SlotDisplayInfo(
    val slotName: String,
    val sourceName: String,
    val directory: String,
    val tileCount: Int = 0,
    val sizeMB: Float = 0f,
    val preSelected: Boolean = true
)

@Composable
fun ConvoyDownloadConfirm(
    estimatedTiles: Int,
    estimatedMB: Float,
    areaDesc: String = "",
    bbox: DownloadBbox,
    slots: List<SlotDisplayInfo>,
    initialReplaceExisting: Boolean = false,
    onProceed: (bbox: DownloadBbox, selectedSlots: List<String>, replaceExisting: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSlots by remember {
        mutableStateOf(slots.filter { it.preSelected }.map { it.slotName }.toSet())
    }
    var replaceExisting by remember { mutableStateOf(initialReplaceExisting) }
    var showDetail by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.width(300.dp),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("DOWNLOAD TILES", color = green, fontSize = 12.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (estimatedTiles > 0) {
                Text("$estimatedTiles tiles per source", color = txtD,
                    fontSize = 12.sp, fontFamily = mono)
            }
            if (areaDesc.isNotEmpty()) {
                Text(areaDesc, color = txtD, fontSize = 12.sp, fontFamily = mono)
            }
            Spacer(Modifier.height(10.dp))

            Text("MAP SOURCES", color = txtD, fontSize = 12.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))

            slots.forEach { slot ->
                val checked = slot.slotName in selectedSlots
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clickable {
                            selectedSlots = if (checked)
                                selectedSlots - slot.slotName
                            else selectedSlots + slot.slotName
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = cardBg
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selectedSlots = if (it)
                                    selectedSlots + slot.slotName
                                else selectedSlots - slot.slotName
                            },
                            modifier = Modifier.size(18.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = green, checkmarkColor = Color.Black)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row {
                                Text(slot.slotName, color = txtB, fontSize = 12.sp,
                                    fontFamily = mono, fontWeight = FontWeight.Bold)
                                Text(" \u2192 ", color = txtD, fontSize = 12.sp, fontFamily = mono)
                                Text(slot.directory + "/", color = green, fontSize = 12.sp,
                                    fontFamily = mono)
                            }
                            Text(slot.sourceName, color = txtD, fontSize = 12.sp, fontFamily = mono)
                            if (showDetail && slot.tileCount > 0) {
                                Text("${slot.tileCount} tiles \u00b7 ${String.format("%.1f", slot.sizeMB)} MB",
                                    color = txtD, fontSize = 12.sp, fontFamily = mono)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = replaceExisting,
                    onCheckedChange = { replaceExisting = it },
                    modifier = Modifier.size(18.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = blue, checkmarkColor = Color.Black)
                )
                Spacer(Modifier.width(8.dp))
                Text("Replace existing tiles", color = txtB, fontSize = 12.sp, fontFamily = mono)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                if (showDetail) "\u25B2 Hide details" else "\u25BC Show details",
                color = blue, fontSize = 12.sp, fontFamily = mono,
                modifier = Modifier.clickable { showDetail = !showDetail }.padding(vertical = 4.dp)
            )

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("CANCEL", fontFamily = mono, fontSize = 12.sp)
                }
                Button(
                    onClick = { onProceed(bbox, selectedSlots.toList(), replaceExisting) },
                    enabled = selectedSlots.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = green.copy(alpha = 0.18f))
                ) {
                    Text("PROCEED", fontFamily = mono, fontSize = 12.sp, color = Color.Black)
                }
            }

            val count = selectedSlots.size
            Text("$count source${if (count != 1) "s" else ""}" + if (estimatedTiles > 0) " \u00b7 ~${estimatedTiles * count} total tiles" else "",
                color = txtD, fontSize = 12.sp, fontFamily = mono,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}
