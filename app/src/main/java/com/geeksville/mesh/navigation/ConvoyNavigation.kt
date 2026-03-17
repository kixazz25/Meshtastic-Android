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
import com.geeksville.mesh.convoy.ConvoyWriteArchiveScreen
import com.geeksville.mesh.convoy.ConvoyDeviceConfigScreen
import com.geeksville.mesh.convoy.ConvoyLoRaConfigScreen
import com.geeksville.mesh.convoy.ConvoyPositionConfigScreen
import com.geeksville.mesh.convoy.ConvoyChannelConfigScreen
import com.geeksville.mesh.convoy.ConvoyVerifyConfigScreen
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
    // ── Main convoy map screen ────────────────────────────────────────────
    composable<ConvoyRoutes.Convoy> {
        ConvoyScreen(
            onNavigateToSettings = {
                navController?.navigate(ConvoyRoutes.ConvoySettings)
            }
        )
    }

    // ── Legacy settings screen ──────────────────────────────────────────
    composable<ConvoyRoutes.ConvoySettings> {
        ConvoySettingsScreen(
            onNavigateBack = { navController?.popBackStack() }
        )
    }

    // ── Enrollment ────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyEnrollment> {
        ConvoyEnrollmentScreen(
            initialEmail = viewModel?.pendingEnrollmentEmail?.value ?: "",
            onEnrollmentComplete = { navController?.popBackStack() }
        )
    }

    // ── F1 Create Event / Ride ──────────────────────────────────────────
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
                onNavigateToCapture   = { navController?.navigate(ConvoyRoutes.ConvoyMasterCapture) },
                onNavigateToApplyList = {
                    navController?.navigate(ConvoyRoutes.ConvoyApplyList)
                }
            )
        }
    }

    // ── Apply list checklist ───────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyApplyList> {
        ConvoyApplyListScreen(
            onDone             = { navController?.popBackStack() },
            onCaptureNewMaster = { navController?.navigate(ConvoyRoutes.ConvoyMasterCapture) }
        )
    }

    // ── Apply radio ───────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyApplyRadio> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        ConvoyApplyRadioScreen(
            convoyViewModel = vm,
            onDone          = { navController?.popBackStack() },
            navController   = navController
        )
    }

    // ── Phase 0: Archive — uses shared viewModel to preserve workingConfig ─────────────────
    composable<ConvoyRoutes.ConvoyWriteArchive> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        ConvoyWriteArchiveScreen(
            convoyViewModel = vm,
            onProceed = { navController?.navigate(ConvoyRoutes.ConvoyWriteDevice) },
            onCancel  = { navController?.popBackStack() }
        )
    }

    // ── Screen 1: Device config ───────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyWriteDevice> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        val wc = vm.workingConfig.collectAsState().value
        if (wc == null) { android.util.Log.e("ConvoyNav", "workingConfig is NULL on WriteDevice"); return@composable }
        ConvoyDeviceConfigScreen(
            workingConfig = wc,
            onProceed     = { navController?.navigate(ConvoyRoutes.ConvoyWriteLoRa) },
            onBack        = { navController?.popBackStack() }
        )
    }

    // ── Screen 2: LoRa config ─────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyWriteLoRa> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        val wc = vm.workingConfig.collectAsState().value
        if (wc == null) { android.util.Log.e("ConvoyNav", "workingConfig is NULL on WriteDevice"); return@composable }
        ConvoyLoRaConfigScreen(
            workingConfig = wc,
            onProceed     = { navController?.navigate(ConvoyRoutes.ConvoyWritePosition) },
            onBack        = { navController?.popBackStack() }
        )
    }

    // ── Screen 3: Position config ───────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyWritePosition> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        val wc = vm.workingConfig.collectAsState().value
        if (wc == null) { android.util.Log.e("ConvoyNav", "workingConfig is NULL on WriteDevice"); return@composable }
        ConvoyPositionConfigScreen(
            workingConfig = wc,
            onProceed     = { navController?.navigate(ConvoyRoutes.ConvoyWriteChannel) },
            onBack        = { navController?.popBackStack() }
        )
    }

    // ── Screen 4: Channel + PSK ─────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyWriteChannel> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        val wc = vm.workingConfig.collectAsState().value
        if (wc == null) { android.util.Log.e("ConvoyNav", "workingConfig is NULL on WriteDevice"); return@composable }
        ConvoyChannelConfigScreen(
            workingConfig = wc,
            onComplete    = { navController?.navigate(ConvoyRoutes.ConvoyWriteVerify) },
            onBack        = { navController?.popBackStack() }
        )
    }

    // ── Screen 5: Verify config ─────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyWriteVerify> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        val wc = vm.workingConfig.collectAsState().value
        if (wc == null) { android.util.Log.e("ConvoyNav", "workingConfig is NULL on WriteDevice"); return@composable }
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

    // ── Master config capture — developer only ──────────────────────────────────
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
