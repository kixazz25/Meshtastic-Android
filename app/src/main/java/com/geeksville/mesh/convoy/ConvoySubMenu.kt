package com.geeksville.mesh.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

/**
 * ConvoySubMenu — V2 accordion-style bottom sheet
 *
 * Top-level items expand/collapse to reveal sub-actions.
 * Last item is always CLOSE to dismiss back to map.
 * Long press CONVOY header → developer settings panel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConvoySubMenu(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onCreateEventRide: () -> Unit        = {},
    onTransferConfig: () -> Unit         = {},
    onNavigateToCreateEvent: () -> Unit  = {},
    onNavigateToSettingsPanel: () -> Unit = {},
    onNavigateToApplyList: () -> Unit = {},
    onNavigateToArchiveRestore: () -> Unit = {},
    onImportFromDownloads: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Color(0xFF101510),
        shape            = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        // Track which top-level item is expanded (null = none)
        var expanded by remember { mutableStateOf<String?>(null) }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {

            // ── CONVOY header — long press = developer settings ──────────
            Text(
                text          = "CONVOY",
                color         = Color(0xFF97D5A5),
                fontSize      = 13.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 4.sp,
                textAlign     = TextAlign.Center,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .combinedClickable(
                        onClick     = {},
                        onLongClick = {
                            onDismiss()
                            onNavigateToSettingsPanel()
                        }
                    )
            )

            // ── EVENT / RIDE ─────────────────────────────────────────────
            AccordionHeader(
                label    = "EVENT / RIDE",
                icon     = "🏁",
                expanded = expanded == "EVENT_RIDE",
                onClick  = { expanded = if (expanded == "EVENT_RIDE") null else "EVENT_RIDE" }
            )
            AnimatedVisibility(
                visible = expanded == "EVENT_RIDE",
                enter   = expandVertically(),
                exit    = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SubMenuItem(
                        label   = "Create a Ride",
                        enabled = true,
                        onClick = {
                            expanded = null
                            onCreateEventRide()
                            onDismiss()
                            onNavigateToCreateEvent()
                        }
                    )
                    SubMenuItem(
                        label   = "Transfer Event/Ride Details via Email",
                        sub     = "Select a ride and send via email attachment",
                        enabled = true,
                        onClick = {
                            expanded = null
                            onTransferConfig()
                        }
                    )
                    SubMenuItem(
                        label   = "Import Ride from Downloads",
                        sub     = "Scan Downloads for .convoy files and import",
                        enabled = true,
                        onClick = {
                            expanded = null
                            onDismiss()
                            onImportFromDownloads()
                        }
                    )
                    SubMenuItem(
                        label   = "Apply Ride Info to Radio",
                        sub     = "Apply master config or ride to connected radio",
                        enabled = true,
                        onClick = {
                            expanded = null
                            onDismiss()
                            onNavigateToApplyList()
                        }
                    )
                    SubMenuItem(
                        label   = "Restore Prior Config Post Ride",
                        sub     = "Select and restore from saved archive",
                        enabled = true,
                        onClick = { onDismiss(); onNavigateToArchiveRestore() }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── MAP SETTINGS ─────────────────────────────────────────────
            AccordionHeader(
                label    = "MAP SETTINGS",
                icon     = "🗺",
                expanded = expanded == "MAP_SETTINGS",
                onClick  = { expanded = if (expanded == "MAP_SETTINGS") null else "MAP_SETTINGS" }
            )
            AnimatedVisibility(
                visible = expanded == "MAP_SETTINGS",
                enter   = expandVertically(),
                exit    = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    SubMenuItem(
                        label   = "Offline Maps & Tile Management",
                        sub     = "Coming in V3",
                        enabled = false,
                        onClick = {}
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── CLOSE ─────────────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() },
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1C211C)
            ) {
                Text(
                    text       = "✕  CLOSE",
                    color      = Color(0xFFFFB4AB),
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Accordion header row ───────────────────────────────────────────────────────

@Composable
private fun AccordionHeader(
    label: String,
    icon: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (expanded) Color(0xFF15512C) else Color(0xFF262B26)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                Text(
                    text       = label,
                    color      = if (expanded) Color(0xFF97D5A5) else Color(0xFFDFE4DC),
                    fontSize   = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text     = if (expanded) "▲" else "▼",
                color    = Color(0xFF97D5A5),
                fontSize = 10.sp
            )
        }
    }
}

// ── Sub-menu item ──────────────────────────────────────────────────────────────

@Composable
private fun SubMenuItem(
    label: String,
    sub: String = "",
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) Color(0xFF313631) else Color(0xFF1C211C)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = label,
                    color      = if (enabled) Color(0xFFDFE4DC) else Color(0xFF8B938A),
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (sub.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text       = sub,
                        color      = if (enabled) Color(0xFFC1C9BF) else Color(0xFF8B938A),
                        fontSize   = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text     = if (enabled) "▶" else "🔒",
                color    = if (enabled) Color(0xFF97D5A5) else Color(0xFF8B938A),
                fontSize = if (enabled) 12.sp else 10.sp
            )
        }
    }
}
