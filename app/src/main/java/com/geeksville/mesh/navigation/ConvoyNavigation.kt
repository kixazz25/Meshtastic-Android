package com.geeksville.mesh.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.geeksville.mesh.convoy.ConvoyCreateEventScreen
import com.geeksville.mesh.convoy.ConvoyEnrollmentScreen
import com.geeksville.mesh.convoy.ConvoyMasterCaptureScreen
import com.geeksville.mesh.convoy.ConvoyScreen
import com.geeksville.mesh.convoy.ConvoySettingsPanelScreen
import com.geeksville.mesh.convoy.ConvoySettingsGate
import com.geeksville.mesh.convoy.ConvoySettingsScreen
import com.geeksville.mesh.convoy.ConvoyUserStore
import com.geeksville.mesh.convoy.ConvoyViewModel
import org.meshtastic.core.navigation.ConvoyRoutes

fun NavGraphBuilder.convoyGraph(
    navController: NavHostController? = null,
    viewModel: ConvoyViewModel? = null
) {
    // ── Main convoy map screen ────────────────────────────────────────────────
    composable<ConvoyRoutes.Convoy> { backStackEntry ->
        val context = androidx.compose.ui.platform.LocalContext.current

        // First launch — redirect to enrollment if no profile exists
        val isEnrolled = remember { ConvoyUserStore.isEnrolled(context) }

        if (!isEnrolled) {
            navController?.navigate(ConvoyRoutes.ConvoyEnrollment) {
                popUpTo(ConvoyRoutes.Convoy) { inclusive = false }
            }
            return@composable
        }

        ConvoyScreen(
            onNavigateToSettings = {
                navController?.navigate(ConvoyRoutes.ConvoySettings)
            },
            onNavigateToCreateEvent = {
                navController?.navigate(ConvoyRoutes.ConvoyCreateEvent)
            },
            onNavigateToSettingsPanel = {
                navController?.navigate(ConvoyRoutes.ConvoySettingsPanel)
            }
        )
    }

    // ── Enrollment — first launch, one-time profile setup ────────────────────
    composable<ConvoyRoutes.ConvoyEnrollment> {
        ConvoyEnrollmentScreen(
            onEnrollmentComplete = {
                navController?.navigate(ConvoyRoutes.Convoy) {
                    popUpTo(ConvoyRoutes.ConvoyEnrollment) { inclusive = true }
                }
            }
        )
    }

    // ── Legacy settings screen ────────────────────────────────────────────────
    composable<ConvoyRoutes.ConvoySettings> {
        ConvoySettingsScreen(
            onNavigateBack = { navController?.popBackStack() }
        )
    }

    // ── F1 Create Event / Ride ────────────────────────────────────────────────
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

        if (!authenticated) {
            ConvoySettingsGate(
                onAuthenticated = { authenticated = true },
                onDismiss       = { navController?.popBackStack() }
            )
        } else {
            ConvoySettingsPanelScreen(
                onBack              = { navController?.popBackStack() },
                onNavigateToCapture = {
                    navController?.navigate(ConvoyRoutes.ConvoyMasterCapture)
                }
            )
        }
    }

    // ── Master config capture — developer only ────────────────────────────────
    composable<ConvoyRoutes.ConvoyMasterCapture> {
        if (viewModel != null) {
            ConvoyMasterCaptureScreen(
                viewModel = viewModel,
                onBack    = { navController?.popBackStack() }
            )
        }
    }
}
