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
import androidx.compose.material3.OutlinedTextField
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
    var applyList    by remember { mutableStateOf(ConvoyApplyList.load(context)) }
    var applyMode    by remember { mutableStateOf("MASTER") } // "MASTER" or "RIDE"
    var selectedRide by remember { mutableStateOf<ConvoyEventConfig?>(null) }
    val rides        = remember { ConvoyEventStore.loadAll(context).sortedBy { it.eventDate } }
    var longName     by remember { mutableStateOf("") }
    var showConfirm  by remember { mutableStateOf(false) }

    var loraOpen     by remember { mutableStateOf(true) }
    var channelOpen  by remember { mutableStateOf(true) }
    var deviceOpen   by remember { mutableStateOf(false) }
    var positionOpen by remember { mutableStateOf(false) }
    var displayOpen  by remember { mutableStateOf(false) }
    var moduleOpen   by remember { mutableStateOf(false) }

    // Confirmation accordion state
    var confirmLoraOpen     by remember { mutableStateOf(true) }
    var confirmChannelOpen  by remember { mutableStateOf(true) }
    var confirmDeviceOpen   by remember { mutableStateOf(true) }
    var confirmPositionOpen by remember { mutableStateOf(false) }
    var confirmDisplayOpen  by remember { mutableStateOf(false) }
    var confirmModuleOpen   by remember { mutableStateOf(false) }

    val masterConfig = remember { ConvoyMasterConfig.load(context) }
    val canProceed   = applyMode == "MASTER" || selectedRide != null

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Title ─────────────────────────────────────────────────────────
            Text(
                text          = "APPLY RADIO CONFIG",
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
                text       = "Select operation, review fields, then proceed to update radio.",
                color      = Color(0xFF8B938A),
                fontSize   = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262B26)))
            Spacer(Modifier.height(12.dp))

            // ── Operation selector ────────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MASTER" to "Apply Master Config", "RIDE" to "Apply Ride").forEach { (mode, label) ->
                    Surface(
                        modifier = Modifier.weight(1f).clickable {
                            applyMode = mode
                            selectedRide = null
                            showConfirm = false
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (applyMode == mode) Color(0xFF2E75B6) else Color(0xFF1C211C)
                    ) {
                        Text(
                            text       = label,
                            color      = if (applyMode == mode) Color.White else Color(0xFF8B938A),
                            fontSize   = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign  = TextAlign.Center,
                            modifier   = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── Ride picker (APPLY RIDE only) ─────────────────────────────────
            if (applyMode == "RIDE") {
                if (rides.isEmpty()) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Color(0xFF2A1A1A)) {
                        Text(
                            "No rides found. Create a ride first.",
                            color      = Color(0xFFFFB4AB),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier   = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Text(
                        "SELECT RIDE",
                        color      = Color(0xFF8B938A),
                        fontSize   = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                        modifier   = Modifier.padding(bottom = 6.dp)
                    )
                    rides.forEach { ride ->
                        val isSelected = selectedRide?.eventId == ride.eventId
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                                selectedRide = ride
                                showConfirm = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF1F4E79) else Color(0xFF1C211C)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(ride.eventName, color = Color(0xFFDFE4DC), fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Text(ride.eventDate, color = Color(0xFF8B938A), fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                                Text(ride.channelName, color = Color(0xFF97D5A5), fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Long Name ─────────────────────────────────────────────────────
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Color(0xFF1C211C)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("LONG NAME", color = Color(0xFF8B938A), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = longName,
                        onValueChange = { longName = it },
                        placeholder = {
                            Text("Enter node long name", fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, color = Color(0xFF8B938A))
                        },
                        singleLine  = true,
                        modifier    = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

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
                        val locked = field == ChannelField.CHANNEL_NAME || field == ChannelField.ENCRYPTION_KEY
                        val checked = locked || field in applyList.channelFields
                        FieldRow(field.label, field.description, checked, locked) {
                            if (!locked) {
                                val newFields = if (field in applyList.channelFields)
                                    applyList.channelFields - field
                                else applyList.channelFields + field
                                applyList = applyList.copy(channelFields = newFields)
                                ConvoyApplyList.save(context, applyList)
                            }
                        }
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
            Spacer(Modifier.height(16.dp))

            // ── PROCEED button (gated: radio must be online + ride selected if RIDE mode) ──
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = canProceed) {
                    if (canProceed) showConfirm = true
                },
                shape = RoundedCornerShape(10.dp),
                color = if (canProceed) Color(0xFF1F4E79) else Color(0xFF101510)
            ) {
                Text(
                    text       = if (!canProceed) "SELECT A RIDE TO PROCEED" else "REVIEW CHANGES →",
                    color      = if (canProceed) Color.White else Color(0xFF262B26),
                    fontSize   = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 14.dp)
                )
            }
            Spacer(Modifier.height(16.dp))

            // ── CONFIRMATION ACCORDION (shown after REVIEW CHANGES) ───────────
            if (showConfirm) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E75B6)))
                Spacer(Modifier.height(12.dp))
                Text(
                    text          = "CHANGES TO BE APPLIED",
                    color         = Color(0xFF2E75B6),
                    fontSize      = 11.sp,
                    fontFamily    = FontFamily.Monospace,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier      = Modifier.fillMaxWidth()
                )
                Text(
                    text       = "Current radio values not yet available — connect radio to populate.",
                    color      = Color(0xFF8B938A),
                    fontSize   = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier   = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                // Column headers
                ConfirmHeader()
                Spacer(Modifier.height(6.dp))

                // ── LoRa confirm ──
                AccordionHeader("LORA", confirmLoraOpen) { confirmLoraOpen = !confirmLoraOpen }
                AnimatedVisibility(visible = confirmLoraOpen) {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        LoraField.values().forEach { field ->
                            val checked = field in applyList.loraFields
                            val newVal  = if (checked) masterConfig?.let { getLoraValue(it, field) } ?: "—" else "—"
                            val rule    = if (checked) "CHECKLIST" else "ORIGINAL RADIO"
                            ConfirmRow(field.label, "—", newVal, rule, checked)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // ── Channel confirm ──
                AccordionHeader("CHANNEL", confirmChannelOpen) { confirmChannelOpen = !confirmChannelOpen }
                AnimatedVisibility(visible = confirmChannelOpen) {
                    val channelSource = if (applyMode == "RIDE") "RIDE FILE" else "MASTER CFG"
                    val channelName_  = if (applyMode == "RIDE") selectedRide?.channelName ?: "—"
                                       else masterConfig?.primaryChannelName ?: "—"
                    val psk_          = if (applyMode == "RIDE") selectedRide?.channelPsk ?: "—"
                                       else "From master config"
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        ConfirmRow("Channel Name", "—", channelName_, channelSource, true)
                        ConfirmRow("Encryption Key", "—", "****", channelSource, true)
                    }
                }
                Spacer(Modifier.height(6.dp))

                // ── Device confirm ──
                AccordionHeader("DEVICE", confirmDeviceOpen) { confirmDeviceOpen = !confirmDeviceOpen }
                AnimatedVisibility(visible = confirmDeviceOpen) {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        ConfirmRow("Long Name", "—", longName.ifBlank { "— (unchanged)" },
                            if (longName.isBlank()) "ORIGINAL RADIO" else "EDITED", longName.isNotBlank())
                        DeviceField.values().filter { it.name != "LONG_NAME" }.forEach { field ->
                            val checked = field in applyList.deviceFields
                            val rule    = if (checked) "CHECKLIST" else "ORIGINAL RADIO"
                            ConfirmRow(field.label, "—", if (checked) "From master" else "—", rule, checked)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // ── Position confirm ──
                AccordionHeader("POSITION", confirmPositionOpen) { confirmPositionOpen = !confirmPositionOpen }
                AnimatedVisibility(visible = confirmPositionOpen) {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        PositionField.values().forEach { field ->
                            val checked = field in applyList.positionFields
                            val rule    = if (checked) "CHECKLIST" else "ORIGINAL RADIO"
                            ConfirmRow(field.label, "—", if (checked) "From master" else "—", rule, checked)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ── PROCEED TO UPDATE / CANCEL ────────────────────────────────
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f).clickable { showConfirm = false; onDone() },
                        shape    = RoundedCornerShape(10.dp),
                        color    = Color(0xFF2A1A1A)
                    ) {
                        Text("CANCEL", color = Color(0xFFFFB4AB), fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 16.dp))
                    }
                    Surface(
                        modifier = Modifier.weight(1f).clickable {
                            // TODO: wire radio write
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF15512C)
                    ) {
                        Text("PROCEED TO UPDATE", color = Color(0xFF97D5A5), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 16.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Capture new master (developer only) ───────────────────────────
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Confirmation helpers ──────────────────────────────────────────────────────

@Composable
private fun ConfirmHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("FIELD", color = Color(0xFF4A6080), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
        Text("CURRENT", color = Color(0xFF4A6080), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
        Text("NEW VALUE", color = Color(0xFF4A6080), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
        Text("RULE", color = Color(0xFF4A6080), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun ConfirmRow(field: String, current: String, newVal: String, rule: String, changing: Boolean) {
    val ruleColor = when (rule) {
        "CHECKLIST"    -> Color(0xFF97D5A5)
        "MASTER CFG"   -> Color(0xFF2E75B6)
        "RIDE FILE"    -> Color(0xFFF9C835)
        "ORIGINAL RADIO" -> Color(0xFF8B938A)
        "EDITED"       -> Color(0xFFFFB74D)
        else           -> Color(0xFF8B938A)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape    = RoundedCornerShape(6.dp),
        color    = if (changing) Color(0xFF1C211C) else Color(0xFF101510)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(field, color = if (changing) Color(0xFFDFE4DC) else Color(0xFF8B938A),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
            Text(current, color = Color(0xFF8B938A), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
            Text(newVal, color = if (changing) Color(0xFFDFE4DC) else Color(0xFF8B938A),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(2f))
            Text(rule, color = ruleColor, fontSize = 8.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(2f))
        }
    }
}

private fun getLoraValue(master: ConvoyMasterConfig, field: LoraField): String = when (field) {
    LoraField.REGION        -> master.loraRegion
    LoraField.MODEM_PRESET  -> master.loraModemPreset
    LoraField.BANDWIDTH     -> master.loraBandwidth.toString()
    LoraField.SPREAD_FACTOR -> master.loraSpreadFactor.toString()
    LoraField.CODING_RATE   -> master.loraCodingRate.toString()
    LoraField.HOP_LIMIT     -> master.loraHopLimit.toString()
    LoraField.TX_ENABLED    -> master.loraTxEnabled.toString()
    LoraField.TX_POWER      -> master.loraTxPower.toString()
    else                    -> "—"
}

// ── Shared composables ────────────────────────────────────────────────────────

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
