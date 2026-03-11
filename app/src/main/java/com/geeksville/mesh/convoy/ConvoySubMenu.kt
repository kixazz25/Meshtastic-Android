package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

// ── TAB CONSTANTS ─────────────────────────────────────────────────────────────
private const val TAB_MAP_SETTINGS = 0
private const val TAB_EVENT_RIDE   = 1

/**
 * ConvoySubMenu — V2 bottom sheet
 *
 * Tab 0 — MAP SETTINGS   : Placeholder. Settings remain in map overlay for V1/V2.
 *                          Tab slot reserved for V3 migration.
 * Tab 1 — EVENT / RIDE   : Four-function Event/Ride menu. F1 + F2 active in V2.
 *                          F3 + F4 visible but disabled — V3 scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoySubMenu(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onCreateEventRide: () -> Unit    = {},
    onTransferConfig: () -> Unit     = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1F2B),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        var selectedTab by remember { mutableIntStateOf(TAB_EVENT_RIDE) }

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {

            // ── Sheet handle label ────────────────────────────────────────
            Text(
                text = "CONVOY",
                color = Color(0xFF2E75B6),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            // ── Tab row ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tab 0 — MAP SETTINGS (placeholder)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedTab = TAB_MAP_SETTINGS },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedTab == TAB_MAP_SETTINGS)
                        Color(0xFF2E75B6) else Color(0xFF2A3545)
                ) {
                    Text(
                        text = "MAP SETTINGS",
                        color = if (selectedTab == TAB_MAP_SETTINGS)
                            Color.White else Color(0xFF7A8DA0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                // Tab 1 — EVENT / RIDE
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedTab = TAB_EVENT_RIDE },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedTab == TAB_EVENT_RIDE)
                        Color(0xFF2E75B6) else Color(0xFF2A3545)
                ) {
                    Text(
                        text = "EVENT / RIDE",
                        color = if (selectedTab == TAB_EVENT_RIDE)
                            Color.White else Color(0xFF7A8DA0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Tab content ───────────────────────────────────────────────
            when (selectedTab) {
                TAB_MAP_SETTINGS -> MapSettingsPlaceholder()
                TAB_EVENT_RIDE   -> EventRideMenu(
                    onCreateEventRide = onCreateEventRide,
                    onTransferConfig  = onTransferConfig
                )
            }
        }
    }
}

// ── TAB 0 — MAP SETTINGS PLACEHOLDER ─────────────────────────────────────────

@Composable
private fun MapSettingsPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1E252F)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🗺",
                    fontSize = 36.sp
                )
                Text(
                    text = "MAP SETTINGS",
                    color = Color(0xFF4A6080),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Coming in V3",
                    color = Color(0xFF2E75B6),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Map settings are currently accessible\nvia the map overlay panel.",
                    color = Color(0xFF4A6080),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }
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
        // F1 — Create Event/Ride  (ACTIVE)
        EventRideButton(
            label     = "F1  —  CREATE EVENT / RIDE",
            sublabel  = "Generate config + map tile package",
            active    = true,
            onClick   = onCreateEventRide
        )

        // F2 — Transfer Config  (ACTIVE)
        EventRideButton(
            label     = "F2  —  TRANSFER CONFIGURATION",
            sublabel  = "Email complete ride kit to participants",
            active    = true,
            onClick   = onTransferConfig
        )

        // F3 — Update Radio  (DISABLED — V3)
        EventRideButton(
            label     = "F3  —  UPDATE RADIO FOR EVENT",
            sublabel  = "Apply event config to paired radio  —  V3",
            active    = false,
            onClick   = {}
        )

        // F4 — Restore Backup  (DISABLED — V3)
        EventRideButton(
            label     = "F4  —  RESTORE RADIO CONFIG",
            sublabel  = "Restore previous radio settings  —  V3",
            active    = false,
            onClick   = {}
        )
    }
}

// ── SHARED BUTTON ─────────────────────────────────────────────────────────────

@Composable
private fun EventRideButton(
    label:    String,
    sublabel: String,
    active:   Boolean,
    onClick:  () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = active) { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (active) Color(0xFF1E3A5F) else Color(0xFF1A1F2B)
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
                    text  = label,
                    color = if (active) Color(0xFFE8EEF5) else Color(0xFF3A4A5A),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text  = sublabel,
                    color = if (active) Color(0xFF4A7FA0) else Color(0xFF2A3545),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text  = if (active) "▶" else "🔒",
                color = if (active) Color(0xFF2E75B6) else Color(0xFF2A3545),
                fontSize = if (active) 14.sp else 12.sp
            )
        }
    }
}
