package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest

/**
 * ConvoySettingsPanel — password-protected developer settings
 *
 * Access: long press on CONVOY header in submenu
 * Password: SHA-256 hashed, stored locally
 * Wrong password: silent dismiss — no error shown
 *
 * Controls:
 *   - LoRa field apply list — which LoRa fields get written on ride install
 *   - Position field apply list — which position fields get written on ride install
 *   - Master config capture (debug builds only)
 *
 * One-time setup tool. Password protected. Ships in release build.
 */

// ── Password hash — SHA-256 of the developer password ────────────────────────
// Default password: "convoy2024" — change before shipping
private const val PASSWORD_HASH = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"

private fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

// ── Password gate screen ──────────────────────────────────────────────────────
@Composable
fun ConvoySettingsGate(
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun tryAuth() { keyboard?.hide(); if (sha256(password) == PASSWORD_HASH) onAuthenticated() else onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C211C)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🔒",
                    fontSize = 36.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "DEVELOPER ACCESS",
                    color = Color(0xFF8B938A),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(16.dp))
                ConvoyTextField(
                    value         = password,
                    onValueChange = { password = it },
                    label         = "Access Code",
                    imeAction     = ImeAction.Done,
                    onImeAction   = { tryAuth() }
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onDismiss() },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF101510)
                    ) {
                        Text(
                            text = "CANCEL",
                            color = Color(0xFF8B938A),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { tryAuth() },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF15512C)
                    ) {
                        Text(
                            text = "ENTER",
                            color = Color(0xFF97D5A5),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Settings panel ────────────────────────────────────────────────────────────
@Composable
fun ConvoySettingsPanelScreen(
    onBack: () -> Unit,
    onNavigateToCapture: () -> Unit
) {
    val context     = LocalContext.current
    var applyList   by remember { mutableStateOf(ConvoyApplyList.load(context)) }
    var savedMsg    by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .imePadding()
    ) {
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
                    text     = "←",
                    color    = Color(0xFF97D5A5),
                    fontSize = 20.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp)
                )
                Text(
                    text          = "CONVOY SETTINGS",
                    color         = Color(0xFFFFB74D),
                    fontSize      = 14.sp,
                    fontFamily    = FontFamily.Monospace,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text       = "🔒 DEV",
                    color      = Color(0xFF8B938A),
                    fontSize   = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A3A1A)))
            Spacer(Modifier.height(8.dp))
            Text(
                text       = "Select which radio fields are written to rider devices on ride install. Unchecked fields are left as-is.",
                color      = Color(0xFF8B938A),
                fontSize   = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier   = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))

            // ── LoRa fields ───────────────────────────────────────────────────
            PanelSection("LORA SETTINGS")
            Spacer(Modifier.height(8.dp))
            LoraField.entries.forEach { field ->
                val checked = field in applyList.loraFields
                FieldCheckRow(
                    label       = field.label,
                    description = field.description,
                    checked     = checked,
                    onToggle    = {
                        val newSet = applyList.loraFields.toMutableSet()
                        if (checked) newSet.remove(field) else newSet.add(field)
                        applyList = applyList.copy(loraFields = newSet)
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Position fields ───────────────────────────────────────────────
            PanelSection("POSITION SETTINGS")
            Spacer(Modifier.height(8.dp))
            PositionField.entries.forEach { field ->
                val checked = field in applyList.positionFields
                FieldCheckRow(
                    label       = field.label,
                    description = field.description,
                    checked     = checked,
                    onToggle    = {
                        val newSet = applyList.positionFields.toMutableSet()
                        if (checked) newSet.remove(field) else newSet.add(field)
                        applyList = applyList.copy(positionFields = newSet)
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Save button ───────────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ConvoyApplyList.save(context, applyList)
                        savedMsg = "✓ Apply list saved."
                    },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E3A1A)
            ) {
                Text(
                    text       = "SAVE APPLY LIST",
                    color      = Color(0xFF97D5A5),
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 14.dp)
                )
            }

            if (savedMsg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text       = savedMsg,
                    color      = Color(0xFF97D5A5),
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier   = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Master config capture ─────────────────────────────────────────
            PanelSection("MASTER RADIO CONFIG")
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToCapture() },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF101510)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text       = "CAPTURE MASTER CONFIG",
                        color      = Color(0xFFFFB74D),
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text       = "Connect your radio and capture its full config as the master template. One-time operation.",
                        color      = Color(0xFF8B938A),
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Shared composables ────────────────────────────────────────────────────────
@Composable
private fun PanelSection(text: String) {
    Text(
        text          = text,
        color         = Color(0xFFFFB74D),
        fontSize      = 10.sp,
        fontFamily    = FontFamily.Monospace,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier      = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FieldCheckRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked         = checked,
            onCheckedChange = { onToggle() },
            colors          = CheckboxDefaults.colors(
                checkedColor   = Color(0xFF97D5A5),
                uncheckedColor = Color(0xFF262B26)
            )
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = label,
                color      = if (checked) Color(0xFFDFE4DC) else Color(0xFF8B938A),
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text       = description,
                color      = Color(0xFF262B26),
                fontSize   = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
