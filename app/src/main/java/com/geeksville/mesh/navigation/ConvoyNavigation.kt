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
import com.geeksville.mesh.convoy.ConvoyDownloadRideConfigScreen
import com.geeksville.mesh.convoy.ConvoyTransferRideScreen
import com.geeksville.mesh.convoy.ConvoyReconnectWaitScreen
import com.geeksville.mesh.convoy.ConvoyVerifyConfigScreen
import com.geeksville.mesh.convoy.ConvoyMasterSuccessScreen
import com.geeksville.mesh.convoy.ConvoySettingsGate
import com.geeksville.mesh.convoy.ConvoySettingsPanelScreen
import com.geeksville.mesh.convoy.ConvoySettingsScreen
import com.geeksville.mesh.convoy.ConvoyViewModel
import com.geeksville.mesh.convoy.ConvoySignInScreen
import com.geeksville.mesh.convoy.ConvoyConfig
import com.geeksville.mesh.convoy.ConvoyDevLaunchScreen
import com.geeksville.mesh.convoy.ConvoySubscriptionScreen
import com.geeksville.mesh.convoy.ConvoyDashboardScreen
import com.geeksville.mesh.convoy.ConvoyFieldRadioScreen
import com.geeksville.mesh.convoy.ConvoySessionManager
import com.geeksville.mesh.convoy.ConvoyTermsScreen
import com.geeksville.mesh.convoy.ConvoyPrivacyScreen
import com.geeksville.mesh.convoy.ConvoyMyRidesScreen
import com.geeksville.mesh.convoy.ConvoyCreateRideScreen
import com.geeksville.mesh.convoy.ConvoyRideDetailScreen
import com.geeksville.mesh.convoy.ConvoyInviteSendScreen
import com.geeksville.mesh.convoy.ConvoyBroadcastScreen
import com.geeksville.mesh.convoy.ConvoyProfileScreen
import com.geeksville.mesh.convoy.ConvoyExploreScreen
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
                onNavigateToArchiveRestore   = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) },
                onNavigateToApplyListMaint   = { navController?.navigate(ConvoyRoutes.ConvoyApplyListMaint) },
                onNavigateToCaptureMaint     = { navController?.navigate(ConvoyRoutes.ConvoyMasterCaptureMaint) }
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

    // ── Apply List Maintenance ────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyApplyListMaint> {
        com.geeksville.mesh.convoy.ConvoyApplyListMaintenanceScreen(
            onBack = { navController?.popBackStack() }
        )
    }
    // ── Master Capture Maintenance ────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyMasterCaptureMaint> {
        val vm = viewModel ?: androidx.hilt.navigation.compose.hiltViewModel<ConvoyViewModel>()
        com.geeksville.mesh.convoy.ConvoyMasterCaptureMaintScreen(
            onBack = { navController?.popBackStack() }
        )
    }
    // ── Track Export ─────────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoyTracks> {
        com.geeksville.mesh.convoy.ConvoyTrackExportSheet(
            onDismiss = { navController?.popBackStack() }
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

    // ── Sign-In — V3 Phase B ──────────────────────────────────────────────
    // First launch gate. On success navigates to Dashboard (subscribed)
    // or Subscription screen (free user).
    // DEV: When V3_FEATURES_ENABLED=true, shows dev simulator to pick launch scenario.
    composable<ConvoyRoutes.ConvoySignIn> {
        val context = androidx.compose.ui.platform.LocalContext.current
        if (ConvoyConfig.V3_FEATURES_ENABLED) {
            ConvoyDevLaunchScreen(
                onLaunch = {
                    val route = ConvoySessionManager.resolveLaunchRoute(context, true)
                    val dest = when (route) {
                        ConvoySessionManager.LaunchRoute.TERMS        -> ConvoyRoutes.ConvoyTerms
                        ConvoySessionManager.LaunchRoute.PRIVACY      -> ConvoyRoutes.ConvoyPrivacy
                        ConvoySessionManager.LaunchRoute.SUBSCRIPTION -> ConvoyRoutes.ConvoySubscription
                        ConvoySessionManager.LaunchRoute.DASHBOARD    -> ConvoyRoutes.ConvoyDashboard
                        else                                          -> ConvoyRoutes.ConvoySignIn
                    }
                    navController?.navigate(dest) {
                        popUpTo(ConvoyRoutes.ConvoySignIn) { inclusive = true }
                    }
                }
            )
        } else {
            ConvoySignInScreen(
                onSignInComplete = {
                    val route = ConvoySessionManager.resolveLaunchRoute(context, true)
                    val dest = when (route) {
                        ConvoySessionManager.LaunchRoute.TERMS        -> ConvoyRoutes.ConvoyTerms
                        ConvoySessionManager.LaunchRoute.PRIVACY      -> ConvoyRoutes.ConvoyPrivacy
                        ConvoySessionManager.LaunchRoute.SUBSCRIPTION -> ConvoyRoutes.ConvoySubscription
                        else                                          -> ConvoyRoutes.ConvoyDashboard
                    }
                    navController?.navigate(dest) {
                        popUpTo(ConvoyRoutes.ConvoySignIn) { inclusive = true }
                    }
                },
                onSkip = {
                    navController?.navigate(ConvoyRoutes.Convoy) {
                        popUpTo(ConvoyRoutes.ConvoySignIn) { inclusive = true }
                    }
                }
            )
        }
    }

    // ── Subscription Value Prop — V3 Phase B ─────────────────────────────
    // Shown to free users after sign-in or when tapping gated Dashboard button.
    composable<ConvoyRoutes.ConvoyMyRides> {
        ConvoyMyRidesScreen(
            onNavigateToRideDetail = { navController?.navigate(ConvoyRoutes.ConvoyRideDetail) },
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyCreateRide) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onBack = { navController?.popBackStack() })
    }
    composable<ConvoyRoutes.ConvoyCreateRide> {
        ConvoyCreateRideScreen(
            onRideCreated = { navController?.navigate(ConvoyRoutes.ConvoyRideDetail) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onApplyMasterConfig = { navController?.navigate(ConvoyRoutes.ConvoyApplyRadio) },
            onArchiveRestore = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) },
            onBack = { navController?.popBackStack() })
    }
    composable<ConvoyRoutes.ConvoyRideDetail> {
        ConvoyRideDetailScreen(
            rideId = "",
            onNavigateToSendInvite = { navController?.navigate(ConvoyRoutes.ConvoyInviteSend) },
            onNavigateToBroadcast  = { navController?.navigate(ConvoyRoutes.ConvoyBroadcast) },
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyCreateRide) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onApplyMasterConfig = { navController?.navigate(ConvoyRoutes.ConvoyApplyRadio) },
            onArchiveRestore = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) },
            onBack = { navController?.popBackStack() })
    }
    composable<ConvoyRoutes.ConvoyProfile> {
        ConvoyProfileScreen(
            onBack = { navController?.popBackStack() },
            onApplyMasterConfig = { navController?.navigate(ConvoyRoutes.ConvoyApplyRadio) },
            onArchiveRestore = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) }
        )
    }
    composable<ConvoyRoutes.ConvoyExplore> {
        ConvoyExploreScreen(onBack = { navController?.popBackStack() })
    }
    composable<ConvoyRoutes.ConvoyTerms> {
        ConvoyTermsScreen(
            onAccept = { navController?.navigate(ConvoyRoutes.ConvoyPrivacy) { popUpTo(ConvoyRoutes.ConvoyTerms) { inclusive = true } } },
            onDecline = { navController?.navigate(ConvoyRoutes.Convoy) { popUpTo(ConvoyRoutes.ConvoyTerms) { inclusive = true } } }
        )
    }
    composable<ConvoyRoutes.ConvoyPrivacy> {
        ConvoyPrivacyScreen(
            onAccept = { navController?.navigate(ConvoyRoutes.ConvoyDashboard) { popUpTo(ConvoyRoutes.ConvoyPrivacy) { inclusive = true } } },
            onDecline = { navController?.navigate(ConvoyRoutes.Convoy) { popUpTo(ConvoyRoutes.ConvoyPrivacy) { inclusive = true } } }
        )
    }
    composable<ConvoyRoutes.ConvoySubscription> {
        ConvoySubscriptionScreen(
            onSubscribe = {
                // Phase C: launch Google Play billing here
                // For now navigate to Dashboard so flow is testable
                navController?.navigate(ConvoyRoutes.ConvoyDashboard) {
                    popUpTo(ConvoyRoutes.ConvoySubscription) { inclusive = true }
                }
            },
            onDismiss = {
                navController?.navigate(ConvoyRoutes.Convoy) {
                    popUpTo(ConvoyRoutes.ConvoySubscription) { inclusive = true }
                }
            }
        )
    }

    // ── Dashboard — V3 Phase B ────────────────────────────────────────────
    // Internet-required landing screen. Five buttons.
    // Free users routed here but buttons check subscription on tap.
    composable<ConvoyRoutes.ConvoyDashboard> {
        val context = androidx.compose.ui.platform.LocalContext.current
        ConvoyDashboardScreen(
            isSubscribed = ConvoySessionManager.isSubscribed(context),
            onNavigateToRides       = { navController?.navigate(ConvoyRoutes.ConvoyRideDetail) },
            onNavigateToExplore     = { navController?.navigate(ConvoyRoutes.ConvoyExplore) },
            onNavigateToTracks      = { navController?.navigate(ConvoyRoutes.ConvoyTracks) },
            onNavigateToProfile     = { navController?.navigate(ConvoyRoutes.ConvoyProfile) },
            onNavigateToFieldRadio  = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onShowSubscription      = { navController?.navigate(ConvoyRoutes.ConvoySubscription) },
            onBack                  = { navController?.popBackStack() },
            onApplyMasterConfig     = { navController?.navigate(ConvoyRoutes.ConvoyApplyRadio) },
            onArchiveRestore        = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) },
            onDownloadRideConfig    = { navController?.navigate(ConvoyRoutes.ConvoyDownloadRideConfig) }
        )
    }

    composable<ConvoyRoutes.ConvoyInviteSend> {
        ConvoyInviteSendScreen(
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyCreateRide) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onBack = { navController?.popBackStack() })
    }
    composable<ConvoyRoutes.ConvoyBroadcast> {
        ConvoyBroadcastScreen(
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyCreateRide) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onBack = { navController?.popBackStack() })
    }
    // ── Field Radio — V3 Phase B ──────────────────────────────────────────
    // Always active. No internet needed. Radio config only.
    composable<ConvoyRoutes.ConvoyFieldRadio> {
        ConvoyFieldRadioScreen(
            onNavigateToApplyMaster = { navController?.navigate(ConvoyRoutes.ConvoyApplyRadio) },
            onNavigateToVerify      = { navController?.navigate(ConvoyRoutes.ConvoyWriteVerify) },
            onBack                  = { navController?.popBackStack() }
        )
    }
    composable<ConvoyRoutes.ConvoyDownloadRideConfig> {
        ConvoyDownloadRideConfigScreen(
            onBack = { navController?.popBackStack() }
        )
    }
}
