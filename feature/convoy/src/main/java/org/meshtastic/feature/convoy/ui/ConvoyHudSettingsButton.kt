package org.meshtastic.feature.convoy.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Settings gear icon for the Convoy HUD row.
 *
 * Add this to your existing HUD composable alongside Group | My Cart | Hide:
 *
 *   Row {
 *       ConvoyHudGroupButton(...)
 *       ConvoyHudMyCartButton(...)
 *       ConvoyHudHideButton(...)
 *       ConvoyHudSettingsButton(onNavigateToSettings = { navController.navigateToConvoySettings() })
 *   }
 */
@Composable
fun ConvoyHudSettingsButton(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick  = onNavigateToSettings,
        modifier = modifier
    ) {
        Icon(
            imageVector        = Icons.Default.Settings,
            contentDescription = "Convoy Settings",
            tint               = MaterialTheme.colorScheme.onSurface
        )
    }
}
