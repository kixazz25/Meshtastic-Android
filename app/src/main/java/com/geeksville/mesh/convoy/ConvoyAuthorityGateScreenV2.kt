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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

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
 * ConvoyAuthorityGateScreenV2
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
    object StorageDeclined   : AuthorityState()   // GATESTATES-2026-08-16B: returned from settings without granting
    object NeedBackground    : AuthorityState()   // fine OK → need "all the time"
    object Granted           : AuthorityState()   // everything satisfied
    // GATEJOB-2026-08-21G: authority is satisfied but the rider has no trails.
    // Evaluated only AFTER storage and background pass, so it can never
    // pre-empt or alter any of the three certified authority paths.
    object NeedTrailData     : AuthorityState()   // authority OK, zero trails -> Home State
}

// GATEJOB-2026-08-21G: one-shot latch. evaluateState runs on every resume and on
// every retry attempt; the sweep MOVES FILES and the trail check OPENS A DATABASE.
// Neither belongs in something that fires on a loop, so the job runs once per
// process, on the first evaluation that gets past authority.
private var startupJobDone = false

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
    // Below API R the legacy model applies and this path is directly accessible.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true

    // Necessary first gate: if the OS says we are NOT the storage manager, we
    // definitely do not have all-files access. (This can go stale-TRUE after
    // clear-data, so it is necessary but NOT sufficient — hence the write test.)
    if (!Environment.isExternalStorageManager()) return false

    // Sufficient test: actually exercise all-files authority by writing and
    // deleting a canary file in the target dir. Scoped storage / MediaStore does
    // NOT permit this in Documents/GroupTrack without MANAGE_EXTERNAL_STORAGE, so
    // a successful write+delete is ground-truth proof of real access. This catches
    // the stale-TRUE case: isExternalStorageManager() lies "yes" but the write fails.
    return try {
        if (!canaryDir.exists() && !canaryDir.mkdirs()) return false
        val canary = File(canaryDir, ".authority_probe")
        canary.writeText("probe")           // throws if access is not real
        val ok = canary.exists() && canary.canRead()
        canary.delete()
        ok
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

/** Compute the correct starting state from current, real authority.
 *  NOTE: fine location is handled by the base Meshtastic app, so the gate does
 *  NOT request it — only all-files and background ("all the time") location.
 */
private fun evaluateState(context: android.content.Context): AuthorityState =
    evaluateState(context, -1)

/** GATERETRY-2026-08-16: same evaluation, but it says what it saw and what it chose.
 *  attempt is the retry index for the post-settings poll, or -1 when not retrying.
 *  Working out what the gate concluded previously meant inferring it from platform
 *  quota noise around the canary file; one line removes that guesswork.
 */
private fun evaluateState(context: android.content.Context, attempt: Int): AuthorityState {
    val storage = hasRealStorageAccess()
    val background = hasBackgroundLocation(context)
    // GATEJOB-2026-08-21G: THE STARTUP JOB. Runs inside the authority loop, which is
    // what makes it run on an ESTABLISHED device too -- there the gate evaluates,
    // passes silently and renders nothing, but this still executes.
    // Slot confirmed 08-16: AFTER the authority gate, BEFORE Convoy. The all-files
    // grant is already proven by real use at this point, and nothing can have
    // launched an import yet, so the sweep cannot collide with a live manifest.
    if (storage && background && !startupJobDone) {
        startupJobDone = true
        try {
            // TRAILFILTER-2026-08-24K: BEFORE needsTrailData below, so the count
            // it reads is already zero and the picker launches. Runs once per
            // device; every launch after the first returns -1 and costs one
            // File.exists().
            HomeStateImportController.clearTrailsOnce(context)
            HomeStateImportController.sweepManifests(context)
            // DEFAULTS-2026-09-02: the shipped map-key palette, copied out of
            // the APK if the rider has no file of their own.
            // ⭐ HERE, not at panel-open. Fred, 09-02: "right in your authority
            // setup where we have the clear." Setup work belongs in ONE place
            // that runs before the app, which is the rule agreed on 09-01 after
            // a migration was smuggled into a database open and ANR'd.
            // ⚠ Safe in this slot for the same reason clearTrailsOnce is: the
            // gate has already granted authority, so shared storage is
            // readable. And it costs one File.exists() on every launch after
            // the first.
            TrailFilterState.ensureDefaults(context)
        } catch (e: Exception) {
            // Housekeeping must never block the gate. A sweep that fails leaves
            // the manifests where they are and retries next launch.
            android.util.Log.e("ConvoyGate", "startup sweep failed: " + e.message)
        }
    }

    val needsTrails = if (storage && background) {
        try {
            HomeStateImportController.needsTrailData(context)
        } catch (e: Exception) {
            // Never strand the rider at the gate over a failed check.
            android.util.Log.e("ConvoyGate", "trail check failed: " + e.message)
            false
        }
    } else false

    val result = when {
        !storage     -> AuthorityState.NeedStorage
        !background  -> AuthorityState.NeedBackground
        needsTrails  -> AuthorityState.NeedTrailData
        else         -> AuthorityState.Granted
    }
    android.util.Log.i(
        "ConvoyGate",
        "eval: realStorage=" + storage + " background=" + background +
            " -> " + result.javaClass.simpleName +
            (if (attempt >= 0) " (attempt " + attempt + ")" else "")
    )
    return result
}

@Composable
fun ConvoyAuthorityGateScreenV2(
    onProceed: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()   // GATEASYNC-2026-08-30

    // GATEASYNC-2026-08-30: was mutableStateOf(evaluateState(context)) -- the
    // remember initialiser runs DURING COMPOSITION, on the main thread, and the
    // first evaluation is the one that runs the startup job. The ANR stack of
    // 08-30 10:50:54 showed sqlite3BtreeClearTable under
    // ConvoyAuthorityGateScreenV2 itself: 38s to first frame while 279,376 rows
    // were deleted. CheckingStorage already exists and already renders.
    var state by remember { mutableStateOf<AuthorityState>(AuthorityState.CheckingStorage) }

    // GATEASYNC-2026-08-30: null until the first evaluation lands. It CANNOT be
    // computed during composition any more, and it must not be guessed -- it
    // governs the certified clean pass ("authority already real, proceed
    // without showing a screen"). Reading it as false too early would put a
    // gate screen in front of every established rider.
    var firstEval by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val first = withContext(Dispatchers.IO) { evaluateState(context) }
        firstEval = first is AuthorityState.Granted
        state = first
    }

    // === GATESTATES-2026-08-16B ===
    // Was authority ALREADY real on the first evaluation of this session? If so nothing
    // was just granted, there is no grant to settle, and the gate has nothing to say --
    // proceed without showing a screen. The Continue barrier below is retained for the
    // path where the state changed to Granted during this session (returning from the
    // settings page), which is the case that raced the DB open.
    // GATEASYNC-2026-08-30: decided by the effect above, not during composition.
    val passedOnEntry = firstEval == true
    // GATEJOB-2026-08-21G: NeedTrailData is deliberately NOT part of passedOnEntry.
    // passedOnEntry means "nothing to say, proceed without a screen"; this state
    // exists precisely to show one. Keeping them separate is what leaves the
    // certified clean-pass behaviour exactly as it was.

    // Set when the all-files settings page is launched. On resume, still-no-storage plus
    // this flag means the user went and came back without granting -- which is a
    // different thing to say than the first-time prompt.
    var settingsVisited by remember { mutableStateOf(false) }

    // GATEASYNC-2026-08-30: keyed on firstEval, which is null until known --
    // so this cannot fire on the unknown state. Behaviour once known is
    // unchanged: passed on entry means proceed with no screen.
    LaunchedEffect(firstEval) {
        if (firstEval == true) onProceed()
    }

    // Background ("all the time") location request launcher.
    // NOTE: fine location is handled by the base Meshtastic app, so the gate
    // requests ONLY background location here — no duplicate fine-location prompt.
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // GATEASYNC-2026-08-30: off the main thread. Cheap in practice --
        // startupJobDone has latched by now -- but it opens a database.
        scope.launch {
            state = withContext(Dispatchers.IO) { evaluateState(context) }
        }
    }

    // Re-evaluate every time we return to this screen (e.g. back from the
    // system all-files settings page). Re-verify with the REAL READ — never
    // trust that the user actually toggled it on.
    // IMPORTANT: do NOT auto-proceed here. Auto-proceeding raced the authority
    // grant into the DB open (the "flash-past"). Instead we re-check and, when
    // authority is real, show a "Continue" button — the user's tap is a natural
    // settle barrier that lets the grant land before convoy/DB opens, and makes
    // the grant screen unmissable.
    // === GATERETRY-2026-08-16 ===
    // Bumped on every resume. The resume effect itself no longer decides anything: a
    // single probe taken the instant the user returns from the settings page can read
    // "not granted" simply because the grant has not landed yet, and the old code latched
    // that into StorageDeclined with nothing left to re-evaluate.
    var resumeTick by remember { mutableStateOf(0) }

    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }

    LaunchedEffect(resumeTick) {
        if (resumeTick == 0) return@LaunchedEffect
        // GATEASYNC-2026-08-30: a LaunchedEffect body runs on the MAIN
        // dispatcher. These were main-thread disk reads too.
        var fresh = withContext(Dispatchers.IO) { evaluateState(context, 0) }
        // Only the post-settings case needs patience: the user has just been sent to grant
        // all-files access, so a negative read here is more likely to be a grant still
        // settling than a real refusal. Re-check a few times before concluding.
        if (fresh is AuthorityState.NeedStorage && settingsVisited) {
            state = AuthorityState.CheckingStorage
            var attempt = 1
            while (attempt <= 4 && fresh is AuthorityState.NeedStorage) {
                kotlinx.coroutines.delay(400L)
                fresh = withContext(Dispatchers.IO) { evaluateState(context, attempt) }   // GATEASYNC-2026-08-30
                attempt++
            }
        }
        state = if (fresh is AuthorityState.NeedStorage && settingsVisited) {
            android.util.Log.w(
                "ConvoyGate",
                "storage still not real after retries; showing StorageDeclined"
            )
            AuthorityState.StorageDeclined
        } else {
            fresh
        }
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
                        // GATESTATES-2026-08-16B: record that the settings page was opened, so a
                        // return without a grant becomes StorageDeclined rather than a repeat.
                        settingsVisited = true
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

                is AuthorityState.StorageDeclined -> {
                    // === GATESTATES-2026-08-16B ===
                    // No proceed door. Without all-files access the app cannot open its
                    // maps, trails or tracks -- there is no degraded mode worth entering.
                    // Exiting is safe: the gate halts before any database is opened or
                    // created, so nothing on the device is changed.
                    GateBody(
                        title = "All-files access not granted",
                        body = "GroupTrack will not open without this access. Your " +
                            "offline maps, trails and tracks are stored in shared " +
                            "storage and cannot be read without it.\n\n" +
                            "Exiting is safe \u2014 nothing on your device is changed or " +
                            "removed. Grant access at any time and everything will be " +
                            "as you left it."
                    )
                    Spacer(Modifier.height(24.dp))
                    GateButton("Try again") {
                        settingsVisited = true
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                )
                            } catch (_: Exception) { /* no-op: user can retry */ }
                        }
                    }
                }

                is AuthorityState.NeedBackground -> {
                    GateBody(
                        title = "Background location recommended",
                        body = "GroupTrack reports your position to your convoy while " +
                            "riding, even when the screen is off. Without \"all the " +
                            "time\" location, track recording stops when the screen " +
                            "sleeps.\n\nYou can continue without it and grant it later."
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
                    Spacer(Modifier.height(12.dp))
                    // GATESTATES-2026-08-16B: background location is not destructive to decline --
                    // it reduces functionality and is reversible from system settings, so a
                    // skip is offered. Deliberately NOT remembered: the prompt recurs every
                    // launch because the degradation is silent until a track is lost.
                    GateOutlineButton("Skip for now") { onProceed() }
                }

                // GATEJOB-2026-08-21G: authority satisfied, no trails yet. The picker
                // owns this surface -- it already carries the state list, the
                // progress display and the completion recap. Full-screen because
                // the import is the whole task at this point.
                is AuthorityState.NeedTrailData -> {
                    HomeStatePickerScreen(
                        onNavigateBack = {
                            // Re-evaluate rather than proceed: if the rider imported,
                            // trails now exist and this resolves to Granted. If they
                            // backed out, the gate asks again -- the setup is offered
                            // again on next launch by design.
                            state = evaluateState(context)
                            if (state is AuthorityState.NeedTrailData) onProceed()
                        }
                    )
                }
                is AuthorityState.Granted -> {
                    GateBody(
                        title = "Access granted",
                        body = "All-files and location access confirmed. Tap Continue " +
                            "to start GroupTrack."
                    )
                    Spacer(Modifier.height(24.dp))
                    GateButton("Continue") {
                        // Final settle barrier: re-verify authority is STILL real
                        // at the moment of proceeding (not a stale earlier check),
                        // then hand off to convoy. The user's tap gave the grant
                        // time to settle before the DB opens.
                        if (hasRealStorageAccess() && hasBackgroundLocation(context)) {
                            onProceed()
                        } else {
                            // Authority slipped between grant and tap — re-gate.
                            state = evaluateState(context)
                        }
                    }
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
