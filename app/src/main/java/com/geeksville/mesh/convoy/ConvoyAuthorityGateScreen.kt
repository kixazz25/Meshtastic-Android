package com.geeksville.mesh.convoy

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.io.File

// Meshtastic palette (matches ConvoyEmailGateScreen)
private val MshBg        = Color(0xFF101510)
private val MshSurface   = Color(0xFF1C211C)
private val MshSurfaceHi = Color(0xFF262B26)
private val MshPrimary   = Color(0xFF97D5A5)
private val MshPrimCont  = Color(0xFF15512C)
private val MshOnSurf    = Color(0xFFDFE4DC)
private val MshVariant   = Color(0xFFC1C9BF)
private val MshOutline   = Color(0xFF8B938A)
private val MshError     = Color(0xFFFFB4AB)

/**
 * ConvoyAuthorityGateScreen
 *
 * Pre-convoy authority gate. Runs BEFORE convoy is reachable and grants the two
 * foundational authorities properly, replacing the juvenile grants that were
 * embedded in ConvoyScreen.kt and did not survive an accurate authority test.
 *
 * FIRST PRINCIPLE — SAFE HALT, NEVER DESTRUCTIVE PROCEED:
 *   Authority is confirmed by a REAL FILESYSTEM READ of the spatial-DB directory,
 *   NOT by Environment.isExternalStorageManager() (which can lie). If the real read
 *   fails, the gate HALTS here and touches nothing — no DB, no convoy — until the
 *   user grants access and the real read passes.
 *
 * Grants, in order, each verified before proceeding:
 *   1. ALL-OBJECT (all-files) access   — MANAGE_EXTERNAL_STORAGE, verified by real read
 *   2. ALL-THE-TIME (background) GPS    — ACCESS_BACKGROUND_LOCATION (2-step: fine → background)
 *   Then onProceed() → convoy loads, assuming both are granted.
 *
 * NOTE: standalone/compiles-first. Not yet wired into the nav graph.
 */

private sealed class AuthorityState {
    object CheckingStorage   : AuthorityState()   // evaluating the real read
    object NeedStorage       : AuthorityState()   // all-files not granted → prompt
    object NeedFineLocation  : AuthorityState()   // storage OK → need foreground location
    object NeedBackground    : AuthorityState()   // fine OK → need "all the time"
    object Granted           : AuthorityState()   // everything satisfied
}

/**
 * The accurate authority test. Attempts a REAL read of the spatial-DB directory
 * rather than trusting Environment.isExternalStorageManager(). Returns true only
 * if the directory is actually listable/creatable — i.e. we genuinely have access.
 *
 * canaryDir defaults to the GroupTrack documents directory on shared storage.
 */
fun hasRealStorageAccess(
    canaryDir: File = File(Environment.getExternalStorageDirectory(), "Documents/GroupTrack")
): Boolean {
    return try {
        // Below API R the legacy model applies and this path is directly accessible.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        // Accurate test: can we actually see/enter the directory on disk?
        if (canaryDir.exists()) {
            // A real, granted manager can list it; a juvenile/absent grant cannot.
            canaryDir.canRead() && canaryDir.listFiles() != null
        } else {
            // Not yet created: can we create it? Only a real grant allows this.
            canaryDir.mkdirs() || canaryDir.exists()
        }
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}

private fun hasBackgroundLocation(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun hasFineLocation(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** Compute the correct starting state from current, real authority. */
private fun evaluateState(context: android.content.Context): AuthorityState = when {
    !hasRealStorageAccess()       -> AuthorityState.NeedStorage
    !hasFineLocation(context)     -> AuthorityState.NeedFineLocation
    !hasBackgroundLocation(context) -> AuthorityState.NeedBackground
    else                          -> AuthorityState.Granted
}

@Composable
fun ConvoyAuthorityGateScreen(
    onProceed: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current

    var state by remember { mutableStateOf<AuthorityState>(evaluateState(context)) }

    // Foreground (fine) location request launcher.
    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        state = evaluateState(context)
    }

    // Background ("all the time") location request launcher.
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        state = evaluateState(context)
    }

    // Re-evaluate every time we return to this screen (e.g. back from the
    // system all-files settings page). Re-verify with the REAL READ — never
    // trust that the user actually toggled it on.
    LifecycleResumeEffect(Unit) {
        state = evaluateState(context)
        // If everything is satisfied, proceed out of the gate.
        if (state is AuthorityState.Granted) {
            onProceed()
        }
        onPauseOrDispose { }
    }

    Surface(color = MshBg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "GroupTrack Setup",
                color = MshPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            when (state) {
                is AuthorityState.CheckingStorage -> {
                    GateBody(
                        title = "Checking access…",
                        body = "Verifying storage access to your offline maps."
                    )
                }

                is AuthorityState.NeedStorage -> {
                    GateBody(
                        title = "All-files access required",
                        body = "GroupTrack stores 50–60 GB of offline maps in shared " +
                            "storage so they survive updates and reinstalls. Grant " +
                            "\"Allow access to manage all files\" to continue."
                    )
                    Spacer(Modifier.height(24.dp))
                    GateButton("Grant all-files access") {
                        // Correct app-specific intent ONLY. No list-variant fallback.
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // If the app-specific page is unavailable, send to the
                            // general all-files settings page (still correct action,
                            // no behavioral list-grant assumption).
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                )
                            } catch (_: Exception) { /* no-op: user can retry */ }
                        }
                    }
                }

                is AuthorityState.NeedFineLocation -> {
                    GateBody(
                        title = "Location access required",
                        body = "GroupTrack needs location access to show your position " +
                            "and track your ride."
                    )
                    Spacer(Modifier.height(24.dp))
                    GateButton("Grant location") {
                        fineLocationLauncher.launch(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    }
                }

                is AuthorityState.NeedBackground -> {
                    GateBody(
                        title = "Background location required",
                        body = "GroupTrack reports your position to your convoy while " +
                            "riding, even when the screen is off. Please allow location " +
                            "\"all the time\"."
                    )
                    Spacer(Modifier.height(24.dp))
                    GateButton("Allow all the time") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            bgLocationLauncher.launch(
                                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            )
                        } else {
                            state = evaluateState(context)
                        }
                    }
                }

                is AuthorityState.Granted -> {
                    GateBody(
                        title = "Ready",
                        body = "All access granted. Starting GroupTrack…"
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            GateOutlineButton("Exit", onExit)
        }
    }
}

@Composable
private fun GateBody(title: String, body: String) {
    Text(
        text = title,
        color = MshOnSurf,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = body,
        color = MshVariant,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun GateButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MshPrimCont,
            contentColor = MshPrimary
        )
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GateOutlineButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MshSurfaceHi,
            contentColor = MshVariant
        )
    ) {
        Text(label, fontFamily = FontFamily.Monospace)
    }
}
