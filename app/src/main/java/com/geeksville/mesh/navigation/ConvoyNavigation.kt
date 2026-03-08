package com.geeksville.mesh.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.geeksville.mesh.convoy.ConvoyScreen
import com.geeksville.mesh.convoy.ConvoySettingsScreen
import org.meshtastic.core.navigation.ConvoyRoutes

fun NavGraphBuilder.convoyGraph(navController: NavHostController? = null) {
    composable<ConvoyRoutes.Convoy> {
        ConvoyScreen(
            onNavigateToSettings = {
                navController?.navigate(ConvoyRoutes.ConvoySettings)
            }
        )
    }
    composable<ConvoyRoutes.ConvoySettings> {
        ConvoySettingsScreen(
            onNavigateBack = { navController?.popBackStack() }
        )
    }
}
