package org.meshtastic.feature.convoy.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import org.meshtastic.feature.convoy.settings.ConvoySettingsScreen

// ── Type-Safe Routes ──────────────────────────────────────────────────────────

@Serializable
object ConvoyRoute                  // Root convoy map screen

@Serializable
object ConvoySettingsRoute          // Settings screen — reached from HUD gear icon

// ── Nav Graph ─────────────────────────────────────────────────────────────────

/**
 * Adds Convoy destinations to the main NavHost.
 *
 * Call from your app-level NavHost in app/src/main/java/com/geeksville/mesh/ui/Main.kt:
 *
 *   convoyGraph(navController)
 */
fun NavGraphBuilder.convoyGraph(
    navController: NavHostController,
    // Pass the live removed cart callsigns map from the convoy ViewModel
    removedCartCallsigns: Map<String, String> = emptyMap()
) {
    composable<ConvoySettingsRoute> {
        ConvoySettingsScreen(
            onNavigateBack       = { navController.popBackStack() },
            removedCartCallsigns = removedCartCallsigns
        )
    }
}

// ── Navigation Actions ────────────────────────────────────────────────────────

/**
 * Navigate to Convoy Settings from the HUD gear icon.
 * Call this from the Convoy map screen's HUD composable.
 */
fun NavHostController.navigateToConvoySettings() {
    navigate(ConvoySettingsRoute)
}
