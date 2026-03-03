package com.geeksville.mesh.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.geeksville.mesh.convoy.ConvoyScreen
import org.meshtastic.core.navigation.ConvoyRoutes

fun NavGraphBuilder.convoyGraph() {
    composable<ConvoyRoutes.Convoy> {
        ConvoyScreen()
    }
}
