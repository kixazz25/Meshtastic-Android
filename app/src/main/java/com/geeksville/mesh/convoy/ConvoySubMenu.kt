package com.geeksville.mesh.convoy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAB_MAP_SETTINGS = 0
private const val TAB_EVENT_RIDE   = 1

/**
 * ConvoySubMenu — V2 bottom sheet
 *
 * Tab 0 — MAP SETTINGS   : Simple placeholder menu item. No map load in V2.
 *                          Map functionality coming in V3.
 * Tab 1 — EVENT / RIDE   : F1 + F2 active. F3 + F4 visible but locked — V3 scope.
 *
 * Long press on CONVOY header — opens password-protected developer settings panel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConvoySubMenu(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onCreateEventRide: () -> Unit       = {},
    onTransferConfig: () -> Unit        = {},
    onNavigateToCreateEvent: () -> Unit = {},
    onNavigateToSettingsPanel: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Color(0xFF1A1F2B),
        shape            = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        var selectedTab by remember { mutableIntStateOf(TAB_EVENT_RIDE) }

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {

            // ── CONVOY header — long press opens developer settings ────────
            Text(
                text          = "CONVOY",
                color         = Color(0xFF2E75B6),
                fontSize      = 13.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 4.sp,
                textAlign     = TextAlign.Center,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .combinedClickable(
                        onClick      = {},
                        onLongClick  = {
                            onDismiss()
                            onNavigateToSettingsPanel()
                        }
                    )
            )

            // ── Tab row ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f).clickable { selectedTab = TAB_MAP_SETTINGS },
                    shape    = RoundedCornerShape(10.dp),
                    color    = if (selectedTab == TAB_MAP_SETTINGS) Color(0xFF2E75B6) else Color(0xFF2A3545)
                ) {
                    Text(
                        text       = "MAP SETTINGS",
                        color      = if (selectedTab == TAB_MAP_SETTINGS) Color.White else Color(0xFF7A8DA0),
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.padding(vertical = 12.dp)
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable { selectedTab = TAB_EVENT_RIDE },
                    shape    = RoundedCornerShape(10.dp),
                    color    = if (selectedTab == TAB_EVENT_RIDE) Color(0xFF2E75B6) else Color(0xFF2A3545)
                ) {
                    Text(
                        text       = "EVENT / RIDE",
                        color      = if (selectedTab == TAB_EVENT_RIDE) Color.White else Color(0xFF7A8DA0),
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.padding(vertical = 12.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when (selectedTab) {
                TAB_MAP_SETTINGS -> MapSettingsTab()
                TAB_EVENT_RIDE   -> EventRideMenu(
                    onCreateEventRide       = {
                        onCreateEventRide()
                        onDismiss()
                        onNavigateToCreateEvent()
                    },
                    onTransferConfig        = onTransferConfig
                )
            }
        }
    }
}

// ── TAB 0 — MAP SETTINGS — simple placeholder ─────────────────────────────────
@Composable
private fun MapSettingsTab() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Placeholder item — no functional action
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(10.dp),
            color    = Color(0xFF1A1F2B)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = "MAP CONFIGURATION",
                        color      = Color(0xFF3A4A5A),
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text       = "Offline maps + tile management  —  Coming in V3",
                        color      = Color(0xFF2A3545),
                        fontSize   = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("🔒", fontSize = 12.sp, color = Color(0xFF2A3545))
            }
        }

        // Map settings currently in map overlay panel
        Text(
            text       = "Map display settings are available in the map overlay panel.",
            color      = Color(0xFF2A3545),
            fontSize   = 9.sp,
            fontFamily = FontFamily.Monospace,
            textAlign  = TextAlign.Center,
            modifier   = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

// ── TAB 1 — EVENT / RIDE MENU ─────────────────────────────────────────────────
@Composable
private fun EventRideMenu(
    onCreateEventRide: () -> Unit,
    onTransferConfig:  () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ConvoyMenuButton(
            icon    = "🎯",
            label   = "F1  —  CREATE EVENT / RIDE",
            sub     = "Set up a new convoy event",
            enabled = true,
            onClick = onCreateEventRide
        )
        ConvoyMenuButton(
            icon    = "📤",
            label   = "F2  —  TRANSFER CONFIGURATION",
            sub     = "Email complete ride kit to participants",
            enabled = true,
            onClick = onTransferConfig
        )
        ConvoyMenuButton(
            icon    = "📻",
            label   = "F3  —  UPDATE RADIO FOR EVENT",
            sub     = "Apply event config to paired radio  —  V3",
            enabled = false,
            onClick = {}
        )
        ConvoyMenuButton(
            icon    = "↩",
            label   = "F4  —  RESTORE RADIO CONFIG",
            sub     = "Restore previous radio settings  —  V3",
            enabled = false,
            onClick = {}
        )
    }
}

// ── Shared button ─────────────────────────────────────────────────────────────
@Composable
private fun ConvoyMenuButton(
    icon: String,
    label: String,
    sub: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) Color(0xFF1E3A5F) else Color(0xFF1A1F2B)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(icon, fontSize = 18.sp, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = label,
                    color      = if (enabled) Color(0xFFE8EEF5) else Color(0xFF3A4A5A),
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text       = sub,
                    color      = if (enabled) Color(0xFF4A7FA0) else Color(0xFF2A3545),
                    fontSize   = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text     = if (enabled) "▶" else "🔒",
                color    = if (enabled) Color(0xFF2E75B6) else Color(0xFF2A3545),
                fontSize = if (enabled) 14.sp else 12.sp
            )
        }
    }
}
