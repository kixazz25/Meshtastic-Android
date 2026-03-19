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
 * Password: SHA-256 hashed
 * Wrong password: silent dismiss
 *
 * Two functions:
 *   1. EDIT APPLY LIST — navigate to full apply list checklist screen
 *   2. CAPTURE MASTER CONFIG — capture radio config as master template
 */

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
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            shape    = RoundedCornerShape(16.dp),
            color    = Color(0xFF1C211C)
        ) {
            Column(
                modifier            = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "\uD83D\uDD12", fontSize = 36.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    text          = "DEVELOPER ACCESS",
                    color         = Color(0xFF8B938A),
                    fontSize      = 11.sp,
                    fontFamily    = FontFamily.Monospace,
                    fontWeight    = FontWeight.Bold,
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
                        modifier = Modifier.weight(1f).clickable { onDismiss() },
                        shape    = RoundedCornerShape(8.dp),
                        color    = Color(0xFF101510)
                    ) {
                        Text(
                            text       = "CANCEL",
                            color      = Color(0xFF8B938A),
                            fontSize   = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign  = TextAlign.Center,
                            modifier   = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        modifier = Modifier.weight(1f).clickable { tryAuth() },
                        shape    = RoundedCornerShape(8.dp),
                        color    = Color(0xFF15512C)
                    ) {
                        Text(
                            text       = "ENTER",
                            color      = Color(0xFF97D5A5),
                            fontSize   = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign  = TextAlign.Center,
                            modifier   = Modifier.padding(vertical = 12.dp)
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
    onNavigateToCapture: () -> Unit,
    onNavigateToApplyList: () -> Unit = {}
) {
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
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text     = "\u2190",
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
                    text       = "\uD83D\uDD12 DEV",
                    color      = Color(0xFF8B938A),
                    fontSize   = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A3A1A)))
            Spacer(Modifier.height(20.dp))

            // ── Apply list ────────────────────────────────────────────────────
            PanelSection("APPLY LIST")
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToApplyList() },
                shape    = RoundedCornerShape(12.dp),
                color    = Color(0xFF101510)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text       = "EDIT APPLY LIST",
                        color      = Color(0xFF97D5A5),
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text       = "Manage which radio fields are written to rider radios.",
                        color      = Color(0xFF8B938A),
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            // ── Master config capture ─────────────────────────────────────────
            PanelSection("MASTER RADIO CONFIG")
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToCapture() },
                shape    = RoundedCornerShape(12.dp),
                color    = Color(0xFF101510)
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
                        text       = "Connect radio and capture full config as master template. Saves master.cfg, master_config.json and convoy_apply_list.json to assets.",
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
