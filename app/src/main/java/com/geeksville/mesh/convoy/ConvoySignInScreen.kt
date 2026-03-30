package com.geeksville.mesh.convoy

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

// ============================================================
// ConvoySignInScreen.kt
// Version 3 Phase A — NEW FILE — NOT wired to nav graph yet.
// Added to nav graph in Version 3 Phase B Task W-01.
//
// GroupTrack branded Google Sign-In screen.
// On success: calls ConvoyApiClient.registerUser()
//             caches user_id, google_id, name in SharedPreferences
// On failure: shows error, allows retry
// Offline: shows offline message with retry button
// ============================================================

private const val TAG = "ConvoySignIn"
private const val PREFS_NAME = "grouptrack_user"
private const val PREF_USER_ID = "user_id"
private const val PREF_GOOGLE_ID = "google_id"
private const val PREF_FIRST_NAME = "first_name"
private const val PREF_LAST_NAME = "last_name"
private const val PREF_EMAIL = "email"

// ── SharedPreferences helpers ─────────────────────────────────────────────────

fun saveUserToPrefs(context: Context, userId: String, googleId: String,
                    email: String, firstName: String, lastName: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
        putString(PREF_USER_ID, userId)
        putString(PREF_GOOGLE_ID, googleId)
        putString(PREF_EMAIL, email)
        putString(PREF_FIRST_NAME, firstName)
        putString(PREF_LAST_NAME, lastName)
        apply()
    }
    Log.i(TAG, "User cached: $userId")
}

fun getUserId(context: Context): String? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREF_USER_ID, null)

fun isSignedIn(context: Context): Boolean = getUserId(context) != null

// ── Sign-In Screen ────────────────────────────────────────────────────────────

@Composable
fun ConvoySignInScreen(
    onSignInComplete: () -> Unit,
    onSkip: (() -> Unit)? = null  // null = skip not allowed (first launch gate)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Configure Google Sign-In
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestProfile()
        .requestId()
        .build()

    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    // Activity result launcher for Google Sign-In intent
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
                val googleId   = account.id ?: ""
                val email      = account.email ?: ""
                val firstName  = account.givenName ?: ""
                val lastName   = account.familyName ?: ""

                if (googleId.isEmpty() || email.isEmpty()) {
                    errorMessage = "Sign-in incomplete — missing account info. Please try again."
                    isLoading = false
                    return@rememberLauncherForActivityResult
                }

                isLoading = true
                errorMessage = ""

                scope.launch {
                    val result = ConvoyApiClient.registerUser(googleId, email, firstName, lastName)
                    result.fold(
                        onSuccess = { userId ->
                            saveUserToPrefs(context, userId, googleId, email, firstName, lastName)
                            isLoading = false
                            onSignInComplete()
                        },
                        onFailure = { e ->
                            isLoading = false
                            errorMessage = "Could not register with GroupTrack server. Check your connection and try again."
                            Log.e(TAG, "registerUser failed: ${e.message}")
                        }
                    )
                }
            } catch (e: ApiException) {
                isLoading = false
                errorMessage = when (e.statusCode) {
                    12501 -> "Sign-in cancelled."
                    7     -> "No network connection. Connect to the internet and try again."
                    else  -> "Sign-in failed (${e.statusCode}). Please try again."
                }
                Log.e(TAG, "Google Sign-In failed: ${e.statusCode}")
            }
        } else {
            isLoading = false
            errorMessage = "Sign-in cancelled."
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GroupTrackColors.Navy),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        GroupTrackHeader(subtitle = "Create Your Account")

        // Center content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome to GroupTrack",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Sign in with Google to create your account and start organizing rides.",
                color = Color(0xFFAACCDD),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(8.dp))
            GroupTrackTagline()
            Spacer(Modifier.height(40.dp))

            if (isLoading) {
                CircularProgressIndicator(
                    color = GroupTrackColors.SkyBlue,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Registering your account...",
                    color = GroupTrackColors.SkyBlue,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                GroupTrackButton(
                    text = "SIGN IN WITH GOOGLE",
                    onClick = {
                        errorMessage = ""
                        signInLauncher.launch(googleSignInClient.signInIntent)
                    }
                )

                if (errorMessage.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A1A1A))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF6B6B),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Skip option — only shown if onSkip provided (not first-launch gate)
                onSkip?.let {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Skip for now — ride tracking works without an account",
                        color = Color(0xFF557799),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(Color.Transparent)
                            .padding(8.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    GroupTrackButton(
                        text = "CONTINUE WITHOUT ACCOUNT",
                        onClick = it,
                        color = Color(0xFF2A3545)
                    )
                }
            }
        }

        // Footer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F1E2E))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "By signing in you agree to the GroupTrack\nRider Terms and Privacy Policy",
                color = Color(0xFF445566),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp
            )
        }
    }
}
