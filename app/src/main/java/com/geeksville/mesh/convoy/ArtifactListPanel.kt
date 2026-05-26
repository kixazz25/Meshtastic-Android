package com.geeksville.mesh.convoy

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
 * @param onRename Rename artifact (id, newName)
 * @param onDelete Delete artifact (id)
 * @param onShare Share artifact (id) — tracks only
 * @param onChangeType Change waypoint type (id, newType) — waypoints only
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
    onRename: ((String, String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
    onExport: ((String) -> Unit)? = null,
    onChangeType: ((String, String) -> Unit)? = null,
    onViewAliases: ((String) -> Unit)? = null,
    onFitToSelected: (() -> Unit)? = null
) {
    val aMono = FontFamily.Monospace
    val aGreen = Color(0xFF39FF14)
    val aBlue = Color(0xFF4DA6FF)
    val aOrange = Color(0xFFFF8C42)
    val aDim = Color(0xFF7A8DA0)
    val aBg = Color(0xEE131820)
    val aItem = Color(0xFF1A2233)

    // Detail panel state
    var detailArtifactId by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTypeChooser by remember { mutableStateOf(false) }

    val detailArtifact = artifacts.find { it["id"] == detailArtifactId }

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
                            onNameClick = { detailArtifactId = id }
                        )
                    }
                }
            }

            // ── Detail Panel (inline, below list) ──
            if (detailArtifact != null) {
                val dId = detailArtifact["id"] ?: ""
                val dName = detailArtifact["name"] ?: "Unnamed"
                val dType = detailArtifact["type"] ?: ""

                // Detail shown as centered dialog
                AlertDialog(
                    onDismissRequest = { detailArtifactId = null },
                    title = {
                        Column {
                            Text(dName, color = Color.White, fontSize = 14.sp,
                                fontFamily = aMono, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (dType.isNotEmpty()) {
                                Text("Type: $dType", color = aDim, fontSize = 10.sp,
                                    fontFamily = aMono)
                            }
                        }
                    },
                    text = {
                        Column {
                            // Rename — all except Trails
                            if (artifactType != "Trails" && onRename != null) {
                                DetailActionButton("RENAME", aBlue) {
                                    renameText = dName
                                    showRenameDialog = true
                                }
                            }

                            // Delete — all except Trails
                            if (artifactType != "Trails" && onDelete != null) {
                                DetailActionButton("DELETE", Color(0xFFFF6B6B)) {
                                    showDeleteConfirm = true
                                }
                            }

                            // Share — all artifact types
                            if (onShare != null) {
                                DetailActionButton("SHARE", aGreen) {
                                    onShare!!(dId)
                                }
                            }
                            // Export to Downloads — all artifact types
                            if (onExport != null) {
                                DetailActionButton("EXPORT", aGreen) {
                                    onExport!!(dId)
                                }
                            }

                            // Change Type — Waypoints only
                            if (artifactType == "Waypoints" && onChangeType != null) {
                                DetailActionButton("CHANGE TYPE", aOrange) {
                                    showTypeChooser = true
                                }
                            }

                            // Aliases — all types (placeholder)
                            DetailActionButton("ALIASES", aDim) {
                                onViewAliases?.invoke(dId)
                                    ?: android.util.Log.i("ArtifactList", "Alias panel not yet built")
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { detailArtifactId = null }) { Text("CLOSE") }
                    }
                )

                // ── Rename Dialog ──
                if (showRenameDialog) {
                    AlertDialog(
                        onDismissRequest = { showRenameDialog = false },
                        title = { Text("Rename $artifactType") },
                        text = {
                            TextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                onRename?.invoke(dId, renameText)
                                showRenameDialog = false
                                detailArtifactId = null
                            }) { Text("RENAME") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRenameDialog = false }) { Text("CANCEL") }
                        }
                    )
                }

                // ── Delete Confirmation ──
                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Delete $dName?") },
                        text = { Text("This cannot be undone.") },
                        confirmButton = {
                            TextButton(onClick = {
                                onDelete?.invoke(dId)
                                showDeleteConfirm = false
                                detailArtifactId = null
                            }) { Text("DELETE", color = Color(0xFFFF6B6B)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text("CANCEL") }
                        }
                    )
                }

                // ── Type Chooser (Waypoints only) ──
                if (showTypeChooser) {
                    AlertDialog(
                        onDismissRequest = { showTypeChooser = false },
                        title = { Text("Change Waypoint Type") },
                        text = {
                            Column {
                                listOf("trailhead", "fuel", "gate", "hazard", "scenic",
                                    "water", "camp", "parking", "rally", "other").forEach { wType ->
                                    TextButton(onClick = {
                                        onChangeType?.invoke(dId, wType)
                                        showTypeChooser = false
                                        detailArtifactId = null
                                    }) { Text(wType.replaceFirstChar { it.uppercase() }) }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showTypeChooser = false }) { Text("CANCEL") }
                        }
                    )
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

@Composable
private fun DetailActionButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp)
    )
}
