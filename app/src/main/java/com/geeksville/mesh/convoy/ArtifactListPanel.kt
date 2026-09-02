package com.geeksville.mesh.convoy

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ArtifactListPanel — universal select/edit panel for all artifact types.
 *
 * Shows artifacts in the current map viewport with:
 *   - Checkbox per item (show/hide on map)
 *   - Select All / Deselect All
 *   - Click name → detail/edit panel
 *   - Type-specific edit functions
 *   - Alias panel placeholder
 *
 * @param artifactType "Tracks", "Trails", "Waypoints", "Routes"
 * @param artifacts List of maps with id, name, type (from viewport query)
 * @param onDismiss Close the panel
 * @param onToggleItem Toggle individual artifact visibility on map
 * @param onToggleAll Toggle all artifacts visibility
 * @param onViewAliases View aliases for artifact (id) — placeholder
 */
@Composable
fun ArtifactListPanel(
    artifactType: String,
    artifacts: List<Map<String, String?>>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onToggleItem: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onViewAliases: ((String) -> Unit)? = null,
    onFitToSelected: (() -> Unit)? = null,
    onAddAlias: ((String, String, String) -> Unit)? = null,
    onStarAlias: ((String, String, String) -> Unit)? = null,
    mapKey: String = "convoy",
    fitWebView: android.webkit.WebView? = null,
    onOpenDetail: (String, String) -> Unit = { _, _ -> }
) {
    // LIFECYCLE-2026-09-01 stage 5: what the PANEL was handed. If the target
    // is here but not on screen, the loss is in the rendering below; if it is
    // already gone, the loss is upstream and stages 1-4 say where.
    androidx.compose.runtime.SideEffect {
        android.util.Log.i("LIFECYCLE",
            "5 panel got ${artifacts.size} $artifactType, selected=" +
                "${selectedIds.size}, '" + SpatialDbManager.LIFECYCLE_NAME +
                "' = " + artifacts.count { (it["name"] ?: "")
                    .contains(SpatialDbManager.LIFECYCLE_NAME, true) })
    }
    val aMono = FontFamily.Monospace
    val aGreen = Color(0xFF39FF14)
    val aBlue = Color(0xFF4DA6FF)
    val aOrange = Color(0xFFFF8C42)
    val aDim = Color(0xFF7A8DA0)
    val aBg = Color(0xEE131820)
    val aItem = Color(0xFF1A2233)
    val ctx = LocalContext.current


    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = aBg,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$artifactType (${artifacts.size})",
                    color = aBlue, fontSize = 13.sp, fontFamily = aMono,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "CLOSE", color = aDim, fontSize = 11.sp, fontFamily = aMono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── Select All / Deselect All / Fit ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "SELECT ALL", color = aGreen, fontSize = 9.sp, fontFamily = aMono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onSelectAll() }.padding(4.dp)
                )
                Text(
                    "DESELECT ALL", color = aOrange, fontSize = 9.sp, fontFamily = aMono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDeselectAll() }.padding(4.dp)
                )
                if (onFitToSelected != null) {
                    Text(
                        "FIT", color = aBlue, fontSize = 9.sp, fontFamily = aMono,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onFitToSelected() }.padding(4.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Artifact List ──
            if (artifacts.isEmpty()) {
                Text(
                    "No $artifactType in current map view",
                    color = aDim, fontSize = 10.sp, fontFamily = aMono,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(artifacts, key = { it["id"] ?: "" }) { artifact ->
                        val id = artifact["id"] ?: return@items
                        val name = artifact["name"] ?: "Unnamed"
                        val type = artifact["type"] ?: ""
                        val isSelected = id in selectedIds

                        ArtifactListItem(
                            name = name,
                            type = type,
                            artifactType = artifactType,
                            isSelected = isSelected,
                            onToggle = { onToggleItem(id, !isSelected) },
                            onNameClick = { onOpenDetail(artifactType, id) }
                        )
                    }
                }
            }


        }
    }
}

@Composable
private fun ArtifactListItem(
    name: String,
    type: String,
    artifactType: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onNameClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        shape = RoundedCornerShape(4.dp),
        color = if (isSelected) Color(0xFF1A2E4A) else Color(0xFF1A2233)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(20.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF4DA6FF),
                    uncheckedColor = Color(0xFF4A6080)
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                color = Color(0xFF4DA6FF),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { onNameClick() }
            )
            if (type.isNotEmpty() && artifactType == "Waypoints") {
                Text(
                    type, color = Color(0xFF7A8DA0), fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}


