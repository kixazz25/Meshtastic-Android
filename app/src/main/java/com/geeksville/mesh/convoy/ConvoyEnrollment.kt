package com.geeksville.mesh.convoy

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ConvoyEnrollmentScreen — V2 first launch enrollment
 *
 * Required fields: First Name, Last Name, Email, Cell Phone, Vehicle Type
 * User Type selection: ORGANIZER or RIDER
 * Expiration: auto-set one year from creation
 * User ID: auto-generated
 * Known Devices: auto-populated on radio connection
 *
 * Shows once on first launch. Accessible again via My Profile in Event/Ride tab.
 */
@Composable
fun ConvoyEnrollmentScreen(
    onEnrollmentComplete: () -> Unit
) {
    val context = LocalContext.current

    var firstName   by remember { mutableStateOf("") }
    var lastName    by remember { mutableStateOf("") }
    var email       by remember { mutableStateOf("") }
    var cellPhone   by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("") }
    var userType    by remember { mutableStateOf(ConvoyUserType.RIDER) }
    var errorMsg    by remember { mutableStateOf("") }

    val allFilled = firstName.isNotBlank() && lastName.isNotBlank() &&
            email.isNotBlank() && cellPhone.isNotBlank() && vehicleType.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // ── Header ────────────────────────────────────────────────────────
            Text(
                text = "⬡",
                color = Color(0xFF67EA94),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "CONVOY",
                color = Color(0xFF67EA94),
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Create Your Profile",
                color = Color(0xFF7A8DA0),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            // ── User Type Selection ───────────────────────────────────────────
            Text(
                text = "I AM A",
                color = Color(0xFF4A6080),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ConvoyUserType.entries.forEach { type ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { userType = type },
                        shape = RoundedCornerShape(10.dp),
                        color = if (userType == type) Color(0xFF1E3A5F) else Color(0xFF1E252F)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (type == ConvoyUserType.ORGANIZER) "🎯" else "🏍",
                                fontSize = 24.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = type.name,
                                color = if (userType == type) Color(0xFF2E75B6) else Color(0xFF4A6080),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (type == ConvoyUserType.ORGANIZER)
                                    "Creates rides" else "Joins rides",
                                color = Color(0xFF3A4A5A),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Form Fields ───────────────────────────────────────────────────
            Text(
                text = "YOUR DETAILS",
                color = Color(0xFF4A6080),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // First Name + Last Name side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ConvoyTextField(
                    value         = firstName,
                    onValueChange = { firstName = it },
                    label         = "First Name",
                    modifier      = Modifier.weight(1f)
                )
                ConvoyTextField(
                    value         = lastName,
                    onValueChange = { lastName = it },
                    label         = "Last Name",
                    modifier      = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            ConvoyTextField(
                value         = email,
                onValueChange = { email = it },
                label         = "Email Address",
                keyboardType  = KeyboardType.Email
            )
            Spacer(Modifier.height(10.dp))
            ConvoyTextField(
                value         = cellPhone,
                onValueChange = { cellPhone = it },
                label         = "Cell Phone",
                keyboardType  = KeyboardType.Phone
            )
            Spacer(Modifier.height(10.dp))
            ConvoyTextField(
                value         = vehicleType,
                onValueChange = { vehicleType = it },
                label         = "Vehicle Type  (e.g. Harley Softail, Jeep Wrangler)"
            )

            // ── Error message ─────────────────────────────────────────────────
            if (errorMsg.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text  = errorMsg,
                    color = Color(0xFFF44336),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Submit button ─────────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!allFilled) {
                            errorMsg = "All fields are required."
                        } else {
                            val user = ConvoyUser.create(
                                userType    = userType,
                                firstName   = firstName,
                                lastName    = lastName,
                                email       = email,
                                cellPhone   = cellPhone,
                                vehicleType = vehicleType
                            )
                            ConvoyUserStore.save(context, user)
                            ConvoyUserStore.setActiveUser(context, user.userId)
                            onEnrollmentComplete()
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                color = if (allFilled) Color(0xFF1E3A5F) else Color(0xFF1A1F2B)
            ) {
                Text(
                    text = "CREATE PROFILE",
                    color = if (allFilled) Color(0xFF2E75B6) else Color(0xFF2A3545),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Expiry notice ─────────────────────────────────────────────────
            Text(
                text = "Your profile is valid for one year from creation.",
                color = Color(0xFF2A3545),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Shared text field style ───────────────────────────────────────────────────
@Composable
fun ConvoyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
        singleLine    = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Color(0xFF2E75B6),
            unfocusedBorderColor = Color(0xFF2A3545),
            focusedLabelColor    = Color(0xFF2E75B6),
            unfocusedLabelColor  = Color(0xFF4A6080),
            focusedTextColor     = Color(0xFFE8EEF5),
            unfocusedTextColor   = Color(0xFFE8EEF5),
            cursorColor          = Color(0xFF2E75B6)
        ),
        modifier = modifier
    )
}
