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
import androidx.navigation.toRoute
import com.geeksville.mesh.grouptrack.GroupTrackScreen   // ONEMAPHOST-2026-10-25
import com.geeksville.mesh.convoy.ConvoyDownloadRideConfigScreen
import com.geeksville.mesh.convoy.ConvoyMyOrganizersScreen
import com.geeksville.mesh.convoy.ConvoyCompletedRidesScreen
import com.geeksville.mesh.convoy.ConvoyNavArgs
import com.geeksville.mesh.convoy.ConvoyCompletedRideDetailScreen
import com.geeksville.mesh.convoy.ConvoySearchByAreaScreen
import com.geeksville.mesh.convoy.ConvoyTransferRideScreen
import com.geeksville.mesh.convoy.ConvoySettingsScreen
import com.geeksville.mesh.convoy.ConvoyMapSourceScreen
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
import com.geeksville.mesh.convoy.ConvoyTrackImportScreen
import com.geeksville.mesh.convoy.ConvoyTrailSourceScreen
import com.geeksville.mesh.convoy.ConvoyRouteCreateScreen

/** STD1D-2026-10-24: a GroupTrack screen route -- the screen is drawn inside GtTheme (one look for every screen, from the scheme). */
inline fun <reified T : Any> NavGraphBuilder.gtComposable(
    noinline content: @androidx.compose.runtime.Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit
) {
    composable<T> { entry -> val scope = this; com.geeksville.mesh.grouptrack.GtTheme { scope.content(entry) } }
}

fun NavGraphBuilder.convoyGraph(
    navController: NavHostController? = null,
    viewModel: ConvoyViewModel? = null
) {
    // ── Main convoy map screen ────────────────────────────────────────────
    // Pre-convoy authority gate (self-contained, app boots here) ----------
    gtComposable<ConvoyRoutes.ConvoyAuthorityGate> {
        val navGateContext = androidx.compose.ui.platform.LocalContext.current
        com.geeksville.mesh.convoy.ConvoyAuthorityGateScreenV2(
            onProceed = {
                // PROFILE-2026-09-22: the callsign has to be in every TAK packet from
                // the first broadcast, so the rider profile is created HERE -- not at
                // ride creation. Existing profile: straight to the map, as before.
                val dest: Any =
                    if (com.geeksville.mesh.convoy.ConvoyProfileStore.exists())
                        ConvoyRoutes.Convoy
                    else
                        ConvoyRoutes.ConvoyRiderProfile
                navController?.navigate(dest) {
                    popUpTo(ConvoyRoutes.ConvoyAuthorityGate) { inclusive = true }
                }
            },
            // GATERETRY-2026-08-16: the gate is the nav START destination, so there is
            // nothing beneath it on the back stack and popBackStack() returns false and
            // no-ops -- Exit was a dead button. Exiting from the start destination means
            // leaving the app, so finish the hosting activity.
            onExit = { (navGateContext as? android.app.Activity)?.finish() }
        )
    }
    gtComposable<ConvoyRoutes.Convoy> {
        GroupTrackScreen(
            // TRACKRIDE-CONVOY-2026-10-05: ADD A RIDE / CREATE RIDE on the ride map's detail panel -> the ride form (as the planner).
            onAddRide = { _, routeId -> navController?.navigate(ConvoyRoutes.ConvoyRideCreate(routeId)) },
            onNavigateToProfile = { navController?.navigate(ConvoyRoutes.ConvoyRiderProfile) },   // ATTACH1-2026-10-22: MENU › Account
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyRideCreate()) },   // GTRIDES-2026-10-22: Rides › Create a ride
            onNavigateToMapSources = { navController?.navigate(ConvoyRoutes.ConvoyMapSources) },   // GTMAP-2026-10-22: Map › Map sources
            // PLANNEROUT-2026-10-23: the planning map is removed -- the classic layout's Ride Planning button says where it went.
            onNavigateToMapViewer = { navController?.context?.let { android.widget.Toast.makeText(it, "The planning map is now part of One Map \u2014 Settings \u203a Layout \u203a One Map.", android.widget.Toast.LENGTH_LONG).show() } },
            onNavigateToTrackExport = { navController?.navigate(ConvoyRoutes.ConvoyTracks) },
            onNavigateToTrackImport = { navController?.navigate(ConvoyRoutes.ConvoyTrackImport) },
            onNavigateToSettings = {
                navController?.navigate(ConvoyRoutes.ConvoySettings)
            }
        )
    }

    // ── Legacy settings screen ────────────────────────────────────────────
    // PROFILE-2026-09-22: first launch (no Cancel -- it cannot be skipped).
    gtComposable<ConvoyRoutes.ConvoyRiderProfile> {
        // PROFILE-2026-09-22: one screen, two modes. No profile yet -> first launch,
        // no Cancel, and Save opens the map. Profile exists -> reached from settings,
        // Cancel and Save both go back.
        val hasProfile = com.geeksville.mesh.convoy.ConvoyProfileStore.exists()
        com.geeksville.mesh.convoy.ConvoyRiderProfileScreen(
            onSaved = {
                if (hasProfile) {
                    navController?.popBackStack()
                } else {
                    navController?.navigate(ConvoyRoutes.Convoy) {
                        popUpTo(ConvoyRoutes.ConvoyRiderProfile) { inclusive = true }
                    }
                }
            },
            onCancel = if (hasProfile) ({ navController?.popBackStack(); Unit }) else null
        )
    }

    // RIDECREATE-2026-09-22: opens empty from here; the planner's route detail
    // panel will open it with a route already chosen (backfilled later).
    gtComposable<ConvoyRoutes.ConvoyRideCreate> { entry ->
        com.geeksville.mesh.convoy.ConvoyRideCreateScreen(
            initialRouteId = entry.toRoute<ConvoyRoutes.ConvoyRideCreate>().routeId,
            onRideCreated = { navController?.popBackStack() },
            onBack = { navController?.popBackStack() }
        )
    }

    gtComposable<ConvoyRoutes.ConvoySettings> {
        ConvoySettingsScreen(
            onNavigateBack = { navController?.popBackStack() },
            onNavigateToMapSources = { navController?.navigate(ConvoyRoutes.ConvoyMapSources) },
            onNavigateToProfile = { navController?.navigate(ConvoyRoutes.ConvoyRiderProfile) },
            onNavigateToRideCreate = { navController?.navigate(ConvoyRoutes.ConvoyRideCreate) }
        )
    }

    // ── V2.5 Trail Source Management ─────────────────────────────
    gtComposable<ConvoyRoutes.ConvoyTrailSources> {
        ConvoyTrailSourceScreen(
            onNavigateBack = { navController?.popBackStack() }
        )
    }

    // ── V2.5 Route Creation ──────────────────────────────────────
    gtComposable<ConvoyRoutes.ConvoyRouteCreate> {
        ConvoyRouteCreateScreen(
            onNavigateBack = { navController?.popBackStack() }
        )
    }

    // ── Map Sources ──────────────────────────────────────────────────
    gtComposable<ConvoyRoutes.ConvoyMapSources> {
        ConvoyMapSourceScreen(
            onNavigateBack = { navController?.popBackStack() }
        )
    }

    // ── Enrollment ────────────────────────────────────────────────────────
    gtComposable<ConvoyRoutes.ConvoyEnrollment> {
        ConvoyEnrollmentScreen(
            initialEmail = viewModel?.pendingEnrollmentEmail?.value ?: "",
            onEnrollmentComplete = { navController?.popBackStack() }
        )
    }

    // ── Create Event / Ride ───────────────────────────────────────────────
    gtComposable<ConvoyRoutes.ConvoyEmailGate> {
        ConvoyEmailGateScreen(
            onProceed       = { navController?.navigate(ConvoyRoutes.ConvoyCreateEvent) },
            onCreateNewUser = { email ->
                viewModel?.pendingEnrollmentEmail?.value = email
                navController?.navigate(ConvoyRoutes.ConvoyEnrollment)
            },
            onExit          = { navController?.popBackStack() }
        )
    }
    gtComposable<ConvoyRoutes.ConvoyCreateEvent> {
        if (viewModel != null) {
            ConvoyCreateEventScreen(
                viewModel = viewModel,
                onBack    = { navController?.popBackStack() }
            )
        }
    }

    // RETIRE26-2026-10-08: the 2.6 apply chain (developer panel, apply list, apply radio, reconnect wait, verify, master
    // capture/success, maintenance) is retired -- reference copies in docs/reference/retired_2.6_radio_apply/.

    // ── Transfer Ride ─────────────────────────────────────────────────────
    gtComposable<ConvoyRoutes.ConvoyTransferRide> {
        ConvoyTransferRideScreen(
            onBack = { navController?.popBackStack() }
        )
    }

    // ── Archive Restore ──────────────────────────────────────────────────
    gtComposable<ConvoyRoutes.ConvoyArchiveRestore> {
        // NORESTORE-2026-09-25 (Fred): there is NO whole-image restore. Every path that led here (developer panel,
        // old apply screens) now opens SAVED CONFIGS: Apply = the configurator, managed fields only; Compare =
        // information. The old ConvoyArchiveRestoreScreen was deleted (RETIRE26-2026-10-08).
        com.geeksville.mesh.convoy.ConfigReviewScreen(onClose = { navController?.popBackStack() })
    }

    // ── Track Export ─────────────────────────────────────────────────────
    gtComposable<ConvoyRoutes.ConvoyTracks> {
        com.geeksville.mesh.convoy.ConvoyTrackExportSheet(
            onDismiss = { navController?.popBackStack() },
            onNavigateToTrackImport = { navController?.navigate(ConvoyRoutes.ConvoyTrackImport) }
        )
    }
    // PLANNEROUT-2026-10-23: the planning map (ConvoyMapViewerScreen, its page grouptrack_map.html) is REMOVED -- every function it
    // held is on ONE MAP. Its files are in retired/2.7c_planner/ (not compiled; nothing deleted).
    // -- Track Import -- in-app file browser --
    gtComposable<ConvoyRoutes.ConvoyTrackImport> {
        ConvoyTrackImportScreen(
            onDismiss = { navController?.popBackStack() }
        )
    }
    // ── Sign-In — V3 Phase B ──────────────────────────────────────────────
    // First launch gate. On success navigates to Dashboard (subscribed)
    // or Subscription screen (free user).
    // DEV: When V3_FEATURES_ENABLED=true, shows dev simulator to pick launch scenario.
    gtComposable<ConvoyRoutes.ConvoySignIn> {
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
    gtComposable<ConvoyRoutes.ConvoyMyRides> {
        ConvoyMyRidesScreen(
            onNavigateToRideDetail = { navController?.navigate(ConvoyRoutes.ConvoyRideDetail) },
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyCreateRide) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onBack = { navController?.popBackStack() })
    }
    gtComposable<ConvoyRoutes.ConvoyCreateRide> {
        ConvoyCreateRideScreen(
            onRideCreated = { navController?.navigate(ConvoyRoutes.ConvoyRideDetail) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onApplyMasterConfig = { com.geeksville.mesh.convoy.GrpAwarenessLauncher.open() },   // V3-REFRESH (RETIRE26-2026-10-08): was the retired 2.6 apply chain; refresh to the 2.7 radio functions at V3 pick-up
            onArchiveRestore = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) },
            onBack = { navController?.popBackStack() })
    }
    gtComposable<ConvoyRoutes.ConvoyRideDetail> {
        ConvoyRideDetailScreen(
            rideId = "",
            onNavigateToSendInvite = { navController?.navigate(ConvoyRoutes.ConvoyInviteSend) },
            onNavigateToBroadcast  = { navController?.navigate(ConvoyRoutes.ConvoyBroadcast) },
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyCreateRide) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onApplyMasterConfig = { com.geeksville.mesh.convoy.GrpAwarenessLauncher.open() },   // V3-REFRESH (RETIRE26-2026-10-08): was the retired 2.6 apply chain; refresh to the 2.7 radio functions at V3 pick-up
            onArchiveRestore = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) },
            onBack = { navController?.popBackStack() })
    }
    gtComposable<ConvoyRoutes.ConvoyProfile> {
        ConvoyProfileScreen(
            onBack = { navController?.popBackStack() },
            onMyOrganizers = { navController?.navigate(ConvoyRoutes.ConvoyMyOrganizers) },
            onApplyMasterConfig = { com.geeksville.mesh.convoy.GrpAwarenessLauncher.open() },   // V3-REFRESH (RETIRE26-2026-10-08): was the retired 2.6 apply chain; refresh to the 2.7 radio functions at V3 pick-up
            onArchiveRestore = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) }
        )
    }
    gtComposable<ConvoyRoutes.ConvoyExplore> {
        ConvoyExploreScreen(onBack = { navController?.popBackStack() })
    }
    gtComposable<ConvoyRoutes.ConvoyTerms> {
        ConvoyTermsScreen(
            onAccept = { navController?.navigate(ConvoyRoutes.ConvoyPrivacy) { popUpTo(ConvoyRoutes.ConvoyTerms) { inclusive = true } } },
            onDecline = { navController?.navigate(ConvoyRoutes.Convoy) { popUpTo(ConvoyRoutes.ConvoyTerms) { inclusive = true } } }
        )
    }
    gtComposable<ConvoyRoutes.ConvoyPrivacy> {
        ConvoyPrivacyScreen(
            onAccept = { navController?.navigate(ConvoyRoutes.ConvoyDashboard) { popUpTo(ConvoyRoutes.ConvoyPrivacy) { inclusive = true } } },
            onDecline = { navController?.navigate(ConvoyRoutes.Convoy) { popUpTo(ConvoyRoutes.ConvoyPrivacy) { inclusive = true } } }
        )
    }
    gtComposable<ConvoyRoutes.ConvoySubscription> {
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
    gtComposable<ConvoyRoutes.ConvoyDashboard> {
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
            onApplyMasterConfig     = { com.geeksville.mesh.convoy.GrpAwarenessLauncher.open() },   // V3-REFRESH (RETIRE26-2026-10-08): was the retired 2.6 apply chain; refresh to the 2.7 radio functions at V3 pick-up
            onArchiveRestore        = { navController?.navigate(ConvoyRoutes.ConvoyArchiveRestore) },
            onDownloadRideConfig    = { navController?.navigate(ConvoyRoutes.ConvoyDownloadRideConfig) },
            onNavigateToCompletedRides = { tab ->
                ConvoyNavArgs.completedRidesTab = tab
                navController?.navigate(ConvoyRoutes.ConvoyCompletedRides)
            },
            onNavigateToSearchByArea = { navController?.navigate(ConvoyRoutes.ConvoySearchByArea) }
        )
    }

    gtComposable<ConvoyRoutes.ConvoyInviteSend> {
        ConvoyInviteSendScreen(
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyCreateRide) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onBack = { navController?.popBackStack() })
    }
    gtComposable<ConvoyRoutes.ConvoyBroadcast> {
        ConvoyBroadcastScreen(
            onNavigateToCreateRide = { navController?.navigate(ConvoyRoutes.ConvoyCreateRide) },
            onNavigateToFieldRadio = { navController?.navigate(ConvoyRoutes.ConvoyFieldRadio) },
            onBack = { navController?.popBackStack() })
    }
    // ── Field Radio — V3 Phase B ──────────────────────────────────────────
    // Always active. No internet needed. Radio config only.
    gtComposable<ConvoyRoutes.ConvoyFieldRadio> {
        ConvoyFieldRadioScreen(
            onNavigateToApplyMaster = { com.geeksville.mesh.convoy.GrpAwarenessLauncher.open() },   // V3-REFRESH (RETIRE26-2026-10-08): was the retired 2.6 apply chain; refresh to the 2.7 radio functions at V3 pick-up
            onNavigateToVerify      = { com.geeksville.mesh.convoy.GrpAwarenessLauncher.open() },   // V3-REFRESH (RETIRE26-2026-10-08): was the retired 2.6 apply chain; refresh to the 2.7 radio functions at V3 pick-up
            onBack                  = { navController?.popBackStack() }
        )
    }
    gtComposable<ConvoyRoutes.ConvoyDownloadRideConfig> {
        ConvoyDownloadRideConfigScreen(
            onBack = { navController?.popBackStack() }
        )
    }
    gtComposable<ConvoyRoutes.ConvoyMyOrganizers> {
        ConvoyMyOrganizersScreen(
            onBack = { navController?.popBackStack() }
        )
    }
    gtComposable<ConvoyRoutes.ConvoyCompletedRides> {
        ConvoyCompletedRidesScreen(
            initialTab = ConvoyNavArgs.completedRidesTab,
            onNavigateToDetail = { rideId ->
                ConvoyNavArgs.completedRideId = rideId
                navController?.navigate(ConvoyRoutes.ConvoyCompletedRideDetail)
            },
            onBack = { navController?.popBackStack() }
        )
    }
    gtComposable<ConvoyRoutes.ConvoySearchByArea> {
        ConvoySearchByAreaScreen(
            onBack = { navController?.popBackStack() }
        )
    }
    gtComposable<ConvoyRoutes.ConvoyCompletedRideDetail> {
        ConvoyCompletedRideDetailScreen(
            rideId = ConvoyNavArgs.completedRideId,
            onNavigateToMap = { navController?.navigate(ConvoyRoutes.Convoy) },
            onBack = { navController?.popBackStack() }
        )
    }
}
