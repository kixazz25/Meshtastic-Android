package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
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

@Composable
fun ConvoyApplyListScreen(
    onDone: () -> Unit,
    onCaptureNewMaster: () -> Unit
) {
    val context = LocalContext.current
    var applyList by remember { mutableStateOf(ConvoyApplyList.load(context)) }

    var loraOpen     by remember { mutableStateOf(true) }
    var channelOpen  by remember { mutableStateOf(true) }
    var deviceOpen   by remember { mutableStateOf(false) }
    var positionOpen by remember { mutableStateOf(false) }
    var displayOpen  by remember { mutableStateOf(false) }
    var moduleOpen   by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text          = "RADIO APPLY LIST",
                color         = Color(0xFF97D5A5),
                fontSize      = 13.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign     = TextAlign.Center,
                modifier      = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text       = "Checked fields will be written to rider radios at ride install.",
                color      = Color(0xFF8B938A),
                fontSize   = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262B26)))
            Spacer(Modifier.height(16.dp))

            // ── LORA ──────────────────────────────────────────────────────────
            AccordionHeader("LORA", loraOpen) { loraOpen = !loraOpen }
            AnimatedVisibility(visible = loraOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    LoraField.values().forEach { field ->
                        val checked = field in applyList.loraFields
                        FieldRow(field.label, field.description, checked, false) {
                            val new = if (checked) applyList.loraFields - field
                                      else applyList.loraFields + field
                            applyList = applyList.copy(loraFields = new)
                            ConvoyApplyList.save(context, applyList)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── CHANNEL ───────────────────────────────────────────────────────
            AccordionHeader("CHANNEL", channelOpen) { channelOpen = !channelOpen }
            AnimatedVisibility(visible = channelOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    ChannelField.values().forEach { field ->
                        FieldRow(field.label, field.description, checked = true, locked = true) {}
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── DEVICE ────────────────────────────────────────────────────────
            AccordionHeader("DEVICE", deviceOpen) { deviceOpen = !deviceOpen }
            AnimatedVisibility(visible = deviceOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    DeviceField.values().forEach { field ->
                        val checked = field in applyList.deviceFields
                        FieldRow(field.label, field.description, checked, false) {
                            val new = if (checked) applyList.deviceFields - field
                                      else applyList.deviceFields + field
                            applyList = applyList.copy(deviceFields = new)
                            ConvoyApplyList.save(context, applyList)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── POSITION ──────────────────────────────────────────────────────
            AccordionHeader("POSITION", positionOpen) { positionOpen = !positionOpen }
            AnimatedVisibility(visible = positionOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    PositionField.values().forEach { field ->
                        val checked = field in applyList.positionFields
                        FieldRow(field.label, field.description, checked, false) {
                            val new = if (checked) applyList.positionFields - field
                                      else applyList.positionFields + field
                            applyList = applyList.copy(positionFields = new)
                            ConvoyApplyList.save(context, applyList)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── DISPLAY ───────────────────────────────────────────────────────
            AccordionHeader("DISPLAY", displayOpen) { displayOpen = !displayOpen }
            AnimatedVisibility(visible = displayOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    DisplayField.values().forEach { field ->
                        val checked = field in applyList.displayFields
                        FieldRow(field.label, field.description, checked, false) {
                            val new = if (checked) applyList.displayFields - field
                                      else applyList.displayFields + field
                            applyList = applyList.copy(displayFields = new)
                            ConvoyApplyList.save(context, applyList)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── MODULE ────────────────────────────────────────────────────────
            AccordionHeader("MODULE", moduleOpen) { moduleOpen = !moduleOpen }
            AnimatedVisibility(visible = moduleOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    ModuleField.values().forEach { field ->
                        val checked = field in applyList.moduleFields
                        FieldRow(field.label, field.description, checked, false) {
                            val new = if (checked) applyList.moduleFields - field
                                      else applyList.moduleFields + field
                            applyList = applyList.copy(moduleFields = new)
                            ConvoyApplyList.save(context, applyList)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onCaptureNewMaster() },
                shape    = RoundedCornerShape(10.dp),
                color    = Color(0xFF1C211C)
            ) {
                Text(
                    text       = "CAPTURE NEW MASTER",
                    color      = Color(0xFFFFB4AB),
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 14.dp)
                )
            }
            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onDone() },
                shape    = RoundedCornerShape(10.dp),
                color    = Color(0xFF15512C)
            ) {
                Text(
                    text       = "DONE",
                    color      = Color(0xFF97D5A5),
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 16.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AccordionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape    = RoundedCornerShape(8.dp),
        color    = if (expanded) Color(0xFF15512C) else Color(0xFF262B26)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text          = title,
                color         = if (expanded) Color(0xFF97D5A5) else Color(0xFFDFE4DC),
                fontSize      = 11.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text     = if (expanded) "▲" else "▼",
                color    = Color(0xFF8B938A),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    description: String,
    checked: Boolean,
    locked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) { onToggle() },
        shape    = RoundedCornerShape(8.dp),
        color    = when {
            locked  -> Color(0xFF15512C)
            checked -> Color(0xFF1C211C)
            else    -> Color(0xFF101510)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (locked) {
                Text("🔒", fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
            } else {
                Checkbox(
                    checked         = checked,
                    onCheckedChange = { onToggle() },
                    colors          = CheckboxDefaults.colors(
                        checkedColor   = Color(0xFF97D5A5),
                        uncheckedColor = Color(0xFF8B938A),
                        checkmarkColor = Color(0xFF101510)
                    )
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = label,
                    color      = if (checked || locked) Color(0xFFDFE4DC) else Color(0xFF8B938A),
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text       = description,
                    color      = Color(0xFF8B938A),
                    fontSize   = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
