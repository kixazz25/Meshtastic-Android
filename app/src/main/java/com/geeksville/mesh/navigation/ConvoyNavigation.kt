package com.geeksville.mesh.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.composable
import com.geeksville.mesh.convoy.ConvoyEmailGateScreen
import com.geeksville.mesh.convoy.ConvoyCreateEventScreen
import com.geeksville.mesh.convoy.ConvoyEnrollmentScreen
import com.geeksville.mesh.convoy.ConvoyMasterCaptureScreen
import com.geeksville.mesh.convoy.ConvoyScreen
import com.geeksville.mesh.convoy.ConvoyApplyListScreen
import com.geeksville.mesh.convoy.ConvoyApplyRadioScreen
import com.geeksville.mesh.convoy.ConvoyArchiveRestoreScreen
import com.geeksville.mesh.convoy.ConvoyTransferRideScreen
import com.geeksville.mesh.convoy.ConvoyReconnectWaitScreen
import com.geeksville.mesh.convoy.ConvoyVerifyConfigScreen
import com.geeksville.mesh.convoy.ConvoyMasterSuccessScreen
import com.geeksville.mesh.convoy.ConvoySettingsGate
import com.geeksville.mesh.convoy.ConvoySettingsPanelScreen
import com.geeksville.mesh.convoy.ConvoySettingsScreen
import com.geeksville.mesh.convoy.ConvoyViewModel
import org.meshtastic.core.navigation.ConvoyRoutes

fun NavGraphBuilder.convoyGraph(
    navController: NavHostController? = null,
    viewModel: ConvoyViewModel? = null
) {
    // ── Main convoy map screen ────────────────────────────────────────────
    composable<ConvoyRoutes.Convoy> {
        ConvoyScreen(
            onNavigateToSettings = {
                navController?.navigate(ConvoyRoutes.ConvoySettings)
            }
        )
    }

    // ── Legacy settings screen ────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoySettings> {
        ConvoySettingsScreen(
            onNavigateBack = { navController?.popBackStack() }
        )
    }

    // ── Enrollment ────────────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyEnrollment> {
        ConvoyEnrollmentScreen(
            initialEmail = viewModel?.pendingEnrollmentEmail?.value ?: "",
            onEnrollmentComplete = { navController?.popBackStack() }
        )
    }

    // ── Create Event / Ride ───────────────────────────────────────────────
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

    // ── Developer settings panel — password protected ─────────────────────
    composable<ConvoyRoutes.ConvoySettingsPanel> {
        var authenticated by remember { mutableStateOf(false) }
        var showPanel     by remember { mutableStateOf(false) }
        if (!authenticated) {
            ConvoySettingsGate(
                onAuthenticated = { authenticated = true; showPanel = true },
                onDismiss       = { navController?.popBackStack() }
            )
        } else if (showPanel) {
            ConvoySettingsPanelScreen(
                onBack                = { navController?.popBackStack() },
                onNavigateToCapture          = { navController?.navigate(ConvoyRoutes.ConvoyMasterCapture) },
                onNavigateToApplyList        = { navController?.navigate(ConvoyRoutes.ConvoyApplyList) },
                onNavigateToArchiveRestore   = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) }
            )
        }
    }

    // ── Apply list checklist ──────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyApplyList> {
        ConvoyApplyListScreen(
            onDone             = { navController?.popBackStack() },
            onCaptureNewMaster = { navController?.navigate(ConvoyRoutes.ConvoyMasterCapture) }
        )
    }

    // ── Apply Radio Config ────────────────────────────────────────────────
    // Entry point for both MASTER and RIDE apply processes.
    // MASTER: builds WorkingConfig -> archives binary -> imports master.cfg -> navigates to Verify
    // RIDE:   builds WorkingConfig -> archives binary -> imports ride.cfg -> navigates to Verify
    composable<ConvoyRoutes.ConvoyApplyRadio> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        ConvoyApplyRadioScreen(
            convoyViewModel = vm,
            onDone          = { navController?.popBackStack() },
            navController   = navController
        )
    }

    // ── Transfer Ride ─────────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyTransferRide> {
        ConvoyTransferRideScreen(
            onBack = { navController?.popBackStack() }
        )
    }

    // ── Archive Restore ──────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyArchiveRestore> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        ConvoyArchiveRestoreScreen(
            onDone = { navController?.navigate(ConvoyRoutes.Convoy) { popUpTo(ConvoyRoutes.Convoy) { inclusive = false } } },
            onBack = { navController?.popBackStack() }
        )
    }

    // ── Reconnect Wait — between import and verify ───────────────────────
    composable<ConvoyRoutes.ConvoyReconnectWait> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        ConvoyReconnectWaitScreen(
            convoyViewModel = vm,
            onProceed = { navController?.navigate(ConvoyRoutes.ConvoyWriteVerify) },
            onCancel  = { navController?.navigate(ConvoyRoutes.Convoy) { popUpTo(ConvoyRoutes.Convoy) { inclusive = false } } }
        )
    }

    // ── Verify Config ─────────────────────────────────────────────────────
    // Final step after master.cfg or ride.cfg import.
    // Reads back radio and compares all fields against WorkingConfig.
    // PASS: done, navigate to convoy map.
    // FAIL: shows failed fields — user can cancel or retry delta corrections.
    composable<ConvoyRoutes.ConvoyWriteVerify> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        val wc = vm.workingConfig.collectAsState().value
        if (wc == null) {
            android.util.Log.e("ConvoyNav", "workingConfig is NULL on WriteVerify")
            return@composable
        }
        ConvoyVerifyConfigScreen(
            workingConfig = wc,
            onDone = {
                vm.clearWorkingConfig()
                navController?.navigate(ConvoyRoutes.Convoy) {
                    popUpTo(ConvoyRoutes.Convoy) { inclusive = false }
                }
            },
            onBack = { navController?.popBackStack() }
        )
    }

    // ── Master capture success ────────────────────────────────────────────
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

    // ── Master config capture — developer only ────────────────────────────
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
