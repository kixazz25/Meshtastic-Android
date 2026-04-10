package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ConvoyApplyListMaintenanceScreen
 *
 * MAINTENANCE ONLY — edit which fields are applied during radio config.
 * No radio connection required. No apply flow. No proceed button.
 * Changes save immediately on toggle.
 *
 * Entry: GroupTrack header long press → Settings Panel → EDIT APPLY LIST
 */
@Composable
fun ConvoyApplyListMaintenanceScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var applyList by remember { mutableStateOf(ConvoyApplyList.load(context)) }
    var loraOpen     by remember { mutableStateOf(true) }
    var channelOpen  by remember { mutableStateOf(true) }
    var deviceOpen   by remember { mutableStateOf(false) }
    var positionOpen by remember { mutableStateOf(false) }
    var displayOpen  by remember { mutableStateOf(false) }
    var moduleOpen   by remember { mutableStateOf(false) }
    var statusMsg    by remember { mutableStateOf("") }

    fun saveAndSync() {
        ConvoyApplyList.save(context, applyList)
        statusMsg = "Saved"
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←", color = Color(0xFF97D5A5), fontSize = 20.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp)
                )
                Text(
                    "APPLY LIST MAINTENANCE",
                    color = Color(0xFF97D5A5), fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Toggle which fields are written during Apply Radio Config. Changes save immediately.",
                color = Color(0xFF8B938A), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            if (statusMsg.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(statusMsg, color = Color(0xFF97D5A5), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(16.dp))

            // ── LoRa ─────────────────────────────────────────────────────────
            MaintAccordionHeader("LORA", loraOpen) { loraOpen = !loraOpen }
            AnimatedVisibility(visible = loraOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    LoraField.values().forEach { field ->
                        val checked = field in applyList.loraFields
                        MaintFieldRow(field.label, checked) {
                            applyList = applyList.copy(
                                loraFields = if (checked) applyList.loraFields - field
                                             else applyList.loraFields + field
                            )
                            saveAndSync()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Channel ───────────────────────────────────────────────────────
            MaintAccordionHeader("CHANNEL", channelOpen) { channelOpen = !channelOpen }
            AnimatedVisibility(visible = channelOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    ChannelField.values().forEach { field ->
                        val locked = field == ChannelField.CHANNEL_NAME || field == ChannelField.ENCRYPTION_KEY
                        val checked = locked || field in applyList.channelFields
                        MaintFieldRow(field.label, checked, locked) {
                            if (!locked) {
                                applyList = applyList.copy(
                                    channelFields = if (field in applyList.channelFields)
                                        applyList.channelFields - field
                                    else applyList.channelFields + field
                                )
                                saveAndSync()
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Device ────────────────────────────────────────────────────────
            MaintAccordionHeader("DEVICE", deviceOpen) { deviceOpen = !deviceOpen }
            AnimatedVisibility(visible = deviceOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    DeviceField.values().forEach { field ->
                        val checked = field in applyList.deviceFields
                        MaintFieldRow(field.label, checked) {
                            applyList = applyList.copy(
                                deviceFields = if (checked) applyList.deviceFields - field
                                               else applyList.deviceFields + field
                            )
                            saveAndSync()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Position ──────────────────────────────────────────────────────
            MaintAccordionHeader("POSITION", positionOpen) { positionOpen = !positionOpen }
            AnimatedVisibility(visible = positionOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    PositionField.values().forEach { field ->
                        val checked = field in applyList.positionFields
                        MaintFieldRow(field.label, checked) {
                            applyList = applyList.copy(
                                positionFields = if (checked) applyList.positionFields - field
                                                 else applyList.positionFields + field
                            )
                            saveAndSync()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Display ───────────────────────────────────────────────────────
            MaintAccordionHeader("DISPLAY", displayOpen) { displayOpen = !displayOpen }
            AnimatedVisibility(visible = displayOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    DisplayField.values().forEach { field ->
                        val checked = field in applyList.displayFields
                        MaintFieldRow(field.label, checked) {
                            applyList = applyList.copy(
                                displayFields = if (checked) applyList.displayFields - field
                                                else applyList.displayFields + field
                            )
                            saveAndSync()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Module ────────────────────────────────────────────────────────
            MaintAccordionHeader("MODULE", moduleOpen) { moduleOpen = !moduleOpen }
            AnimatedVisibility(visible = moduleOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    ModuleField.values().forEach { field ->
                        val checked = field in applyList.moduleFields
                        MaintFieldRow(field.label, checked) {
                            applyList = applyList.copy(
                                moduleFields = if (checked) applyList.moduleFields - field
                                               else applyList.moduleFields + field
                            )
                            saveAndSync()
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ── Sync to Assets button ─────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    ConvoyApplyList.save(context, applyList)
                    statusMsg = "Saved. Run sync_assets_v1.sh to update repo assets."
                },
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1A3A2A)
            ) {
                Text(
                    "SAVE + NOTE SYNC REQUIRED",
                    color = Color(0xFF97D5A5), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "After saving, run: bash ~/Meshtastic-Android/docs/sync_assets_v1.sh",
                color = Color(0xFF4A6080), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MaintAccordionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1A2A1A)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = Color(0xFF97D5A5), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp)
            Text(if (expanded) "▲" else "▼", color = Color(0xFF97D5A5), fontSize = 10.sp)
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun MaintFieldRow(label: String, checked: Boolean, locked: Boolean = false, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked) { onToggle() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (locked) null else { _ -> onToggle() },
            enabled = !locked,
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF97D5A5),
                uncheckedColor = Color(0xFF4A6080),
                disabledCheckedColor = Color(0xFF97D5A5).copy(alpha = 0.5f)
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (locked) Color(0xFF8B938A) else Color(0xFFD4D8D4),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace
        )
        if (locked) {
            Spacer(Modifier.width(8.dp))
            Text("LOCKED", color = Color(0xFF4A6080), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}
