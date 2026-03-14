package com.geeksville.mesh.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.geeksville.mesh.convoy.ConvoyEmailGateScreen
import com.geeksville.mesh.convoy.ConvoyCreateEventScreen
import com.geeksville.mesh.convoy.ConvoyEnrollmentScreen
import com.geeksville.mesh.convoy.ConvoyMasterCaptureScreen
import com.geeksville.mesh.convoy.ConvoyScreen
import com.geeksville.mesh.convoy.ConvoyApplyListScreen
import com.geeksville.mesh.convoy.ConvoyApplyRadioScreen
import com.geeksville.mesh.convoy.ConvoyMasterSuccessScreen
import com.geeksville.mesh.convoy.ConvoySettingsGate
import com.geeksville.mesh.convoy.ConvoySettingsPanelScreen
import com.geeksville.mesh.convoy.ConvoySettingsScreen
import com.geeksville.mesh.convoy.ConvoyUserStore
import com.geeksville.mesh.convoy.ConvoyViewModel
import org.meshtastic.core.navigation.ConvoyRoutes

fun NavGraphBuilder.convoyGraph(
    navController: NavHostController? = null,
    viewModel: ConvoyViewModel? = null
) {
    // ── Main convoy map screen ────────────────────────────────────────────────
    composable<ConvoyRoutes.Convoy> {
        ConvoyScreen(
            onNavigateToSettings = {
                navController?.navigate(ConvoyRoutes.ConvoySettings)
            }
        )
    }

    // ── Legacy settings screen ────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoySettings> {
        ConvoySettingsScreen(
            onNavigateBack = { navController?.popBackStack() }
        )
    }

    // ── Enrollment ────────────────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyEnrollment> {
        ConvoyEnrollmentScreen(
            initialEmail = viewModel?.pendingEnrollmentEmail?.value ?: "",
            onEnrollmentComplete = { navController?.popBackStack() }
        )
    }

    // ── F1 Create Event / Ride ────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyEmailGate> {
        ConvoyEmailGateScreen(
            onProceed       = { navController?.navigate(ConvoyRoutes.ConvoyCreateEvent) },
            onCreateNewUser = { email ->
                viewModel?.pendingEnrollmentEmail?.value = email
                navController?.navigate(ConvoyRoutes.ConvoyEnrollment)
            },
            onExit          = { navController?.popBackStack() }
        )
    }
    composable<ConvoyRoutes.ConvoyCreateEvent> {
        if (viewModel != null) {
            ConvoyCreateEventScreen(
                viewModel = viewModel,
                onBack    = { navController?.popBackStack() }
            )
        }
    }

    // ── Developer settings panel — password protected ─────────────────────────
    composable<ConvoyRoutes.ConvoySettingsPanel> {
        val context = androidx.compose.ui.platform.LocalContext.current
        var authenticated by remember { mutableStateOf(false) }
        if (!authenticated) {
            ConvoySettingsGate(
                onAuthenticated = {
                    authenticated = true
                    // Settings panel always goes to master capture
                    navController?.navigate(ConvoyRoutes.ConvoyMasterCapture)
                },
                onDismiss = { navController?.popBackStack() }
            )
        }
    }

    // ── Apply list checklist ─────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyApplyList> {
        ConvoyApplyListScreen(
            onDone             = { navController?.popBackStack() },
            onCaptureNewMaster = { navController?.navigate(ConvoyRoutes.ConvoyMasterCapture) }
        )
    }

    // ── Apply radio (member function — no password gate) ────────────────────
    composable<ConvoyRoutes.ConvoyApplyRadio> {
        ConvoyApplyRadioScreen(
            onDone = { navController?.popBackStack() }
        )
    }

    // ── Master capture success ────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyMasterSuccess> {
        ConvoyMasterSuccessScreen(
            onSaveAndChecklist = {
                navController?.navigate(ConvoyRoutes.ConvoyApplyList) {
                    popUpTo(ConvoyRoutes.ConvoyMasterCapture) { inclusive = true }
                }
            },
            onCaptureNew = {
                navController?.navigate(ConvoyRoutes.ConvoyMasterCapture) {
                    popUpTo(ConvoyRoutes.ConvoyMasterCapture) { inclusive = true }
                }
            }
        )
    }

    // ── Master config capture — developer only ────────────────────────────────
    composable<ConvoyRoutes.ConvoyMasterCapture> {
        if (viewModel != null) {
            ConvoyMasterCaptureScreen(
                viewModel        = viewModel,
                onBack           = { navController?.popBackStack() },
                onCaptureSuccess = {
                    navController?.navigate(ConvoyRoutes.ConvoyMasterSuccess) {
                        popUpTo(ConvoyRoutes.ConvoyMasterCapture) { inclusive = true }
                    }
                }
            )
        }
    }
}
