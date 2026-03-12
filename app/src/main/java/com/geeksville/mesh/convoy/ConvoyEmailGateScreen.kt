package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Meshtastic palette
private val MshBg        = Color(0xFF101510)
private val MshSurface   = Color(0xFF1C211C)
private val MshSurfaceHi = Color(0xFF262B26)
private val MshPrimary   = Color(0xFF97D5A5)
private val MshPrimCont  = Color(0xFF15512C)
private val MshOnSurf    = Color(0xFFDFE4DC)
private val MshVariant   = Color(0xFFC1C9BF)
private val MshOutline   = Color(0xFF8B938A)
private val MshError     = Color(0xFFFFB4AB)

private sealed class GateState {
    object Idle          : GateState()
    object NotFound      : GateState()
    data class NeedUpgrade(val user: ConvoyUser) : GateState()
}

/**
 * ConvoyEmailGateScreen
 *
 * Validates email against local enrollment database before allowing event creation.
 *
 * ORGANIZER  → onProceed() immediately
 * RIDER      → upgrade prompt → confirm → onProceed()
 * Not found  → Retry | Create New User | Exit
 */
@Composable
fun ConvoyEmailGateScreen(
    onProceed: () -> Unit,
    onCreateNewUser: (String) -> Unit,
    onExit: () -> Unit
) {
    val context  = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    var email by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<GateState>(GateState.Idle) }

    fun validate() {
        keyboard?.hide()
        val trimmed = email.trim().lowercase()
        val users   = ConvoyUserStore.loadAll(context)
        val match   = users.firstOrNull { it.email.lowercase() == trimmed }
        state = when {
            match == null                            -> GateState.NotFound
            match.userType == ConvoyUserType.ORGANIZER -> { onProceed(); GateState.Idle }
            else                                     -> GateState.NeedUpgrade(match)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color    = MshBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.CenterHorizontally
        ) {
            // ── Header ────────────────────────────────────────────────────
            Text(
                text          = "CONVOY",
                color         = MshPrimary,
                fontSize      = 13.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 4.sp,
                textAlign     = TextAlign.Center,
                modifier      = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text       = "CREATE A RIDE",
                color      = MshOnSurf,
                fontSize   = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(32.dp))

            when (val s = state) {

                // ── IDLE / entry ──────────────────────────────────────────
                is GateState.Idle -> {
                    Text(
                        text       = "Enter your registered email address to continue.",
                        color      = MshVariant,
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(
                        value         = email,
                        onValueChange = { email = it; state = GateState.Idle },
                        label         = { Text("Email Address", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction    = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { validate() }),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = MshPrimary,
                            unfocusedBorderColor = MshOutline,
                            focusedLabelColor    = MshPrimary,
                            unfocusedLabelColor  = MshOutline,
                            focusedTextColor     = MshOnSurf,
                            unfocusedTextColor   = MshOnSurf,
                            cursorColor          = MshPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick  = { validate() },
                        enabled  = email.isNotBlank(),
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor         = MshPrimCont,
                            contentColor           = MshPrimary,
                            disabledContainerColor = MshSurfaceHi,
                            disabledContentColor   = MshOutline
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("CONTINUE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick  = onExit,
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MshVariant),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("CANCEL", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }

                // ── NOT FOUND ─────────────────────────────────────────────
                is GateState.NotFound -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(12.dp),
                        color    = MshSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text       = "Email Not Found",
                                color      = MshError,
                                fontSize   = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text       = email.trim(),
                                color      = MshVariant,
                                fontSize   = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text       = "This email is not registered in the Convoy system.",
                                color      = MshOutline,
                                fontSize   = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign  = TextAlign.Center
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick  = { state = GateState.Idle },
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = MshSurfaceHi, contentColor = MshOnSurf),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("RETRY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick  = { onCreateNewUser(email.trim()) },
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = MshPrimCont, contentColor = MshPrimary),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("CREATE NEW USER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick  = onExit,
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MshVariant),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("EXIT", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }

                // ── NEEDS UPGRADE ─────────────────────────────────────────
                is GateState.NeedUpgrade -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(12.dp),
                        color    = MshSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text       = "Organizer Access Required",
                                color      = MshPrimary,
                                fontSize   = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text       = "${s.user.firstName} ${s.user.lastName}",
                                color      = MshOnSurf,
                                fontSize   = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text       = "Your account is registered as a RIDER. Creating rides requires ORGANIZER access.",
                                color      = MshVariant,
                                fontSize   = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign  = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text       = "Upgrade is free during the current beta period.",
                                color      = MshOutline,
                                fontSize   = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign  = TextAlign.Center
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick  = {
                            // Upgrade user to ORGANIZER in local store
                            val upgraded = s.user.copy(userType = ConvoyUserType.ORGANIZER)
                            ConvoyUserStore.save(context, upgraded)
                            onProceed()
                        },
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = MshPrimCont, contentColor = MshPrimary),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("UPGRADE & CONTINUE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick  = onExit,
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MshVariant),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("CANCEL", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
