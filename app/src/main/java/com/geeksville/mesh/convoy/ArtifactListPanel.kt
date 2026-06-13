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
    onFitToSelected: (() -> Unit)? = null,
    onLoadDetail: ((String, String) -> Map<String, String?>)? = null,
    onLoadAliases: ((String, String) -> List<Map<String, String?>>)? = null,
    onAddAlias: ((String, String, String) -> Unit)? = null,
    onStarAlias: ((String, String, String) -> Unit)? = null,
    onDeleteAlias: ((String) -> Unit)? = null,
    onFit: ((String, String) -> Unit)? = null,
    initialDetailId: String? = null
) {
    val aMono = FontFamily.Monospace
    val aGreen = Color(0xFF39FF14)
    val aBlue = Color(0xFF4DA6FF)
    val aOrange = Color(0xFFFF8C42)
    val aDim = Color(0xFF7A8DA0)
    val aBg = Color(0xEE131820)
    val aItem = Color(0xFF1A2233)

    // Detail panel state
    var detailArtifactId by remember(initialDetailId) { mutableStateOf<String?>(initialDetailId) }
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
                        val singular = artifactType.lowercase().removeSuffix("s")
                        // Load detail + aliases when the dialog is shown (main-thread reads — tiny)
                        val detailFields = remember(dId) { onLoadDetail?.invoke(singular, dId) ?: emptyMap() }
                        var showTech by remember(dId) { mutableStateOf(false) }
                        var aliasRows by remember(dId) {
                            mutableStateOf(onLoadAliases?.invoke(singular, dId) ?: emptyList())
                        }
                        var showAddAlias by remember(dId) { mutableStateOf(false) }
                        var newAliasText by remember(dId) { mutableStateOf("") }
                        fun reloadAliases() { aliasRows = onLoadAliases?.invoke(singular, dId) ?: emptyList() }

                        Row(modifier = Modifier.fillMaxWidth()) {
                            // ── LEFT RAIL: function list ──
                            Column(modifier = Modifier.width(118.dp)) {
                                if (artifactType != "Trails" && onRename != null) {
                                    DetailActionButton("RENAME", aBlue) { renameText = dName; showRenameDialog = true }
                                }
                                if (artifactType != "Trails" && onDelete != null) {
                                    DetailActionButton("DELETE", Color(0xFFFF6B6B)) { showDeleteConfirm = true }
                                }
                                if (onShare != null) { DetailActionButton("SHARE", aGreen) { onShare!!(dId) } }
                                if (onExport != null) { DetailActionButton("EXPORT", aGreen) { onExport!!(dId) } }
                                if (artifactType == "Waypoints" && onChangeType != null) {
                                    DetailActionButton("CHANGE TYPE", aOrange) { showTypeChooser = true }
                                }
                                DetailActionButton("FIT", aBlue) {
                                    onFit?.invoke(singular, dId)
                                        ?: android.util.Log.i("ArtifactList", "FIT not yet wired")
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            // ── RIGHT COLUMN: badge + aliases + full-data ──
                            Column(modifier = Modifier.weight(1f)) {
                                // type badge
                                Text(singular.uppercase(), color = aDim, fontSize = 8.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))

                                // ALIAS ACCORDION
                                Text("ALIASES", color = aOrange, fontSize = 9.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold)
                                if (aliasRows.isEmpty()) {
                                    Text("none", color = aDim, fontSize = 9.sp, fontFamily = aMono)
                                } else {
                                    aliasRows.forEach { a ->
                                        val aId = a["alias_id"] ?: ""
                                        val pref = a["is_preferred"] == "1"
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()) {
                                            // star (preferred) — tap to set preferred
                                            Text(if (pref) "\u2605" else "\u2606",
                                                color = if (pref) aGreen else aDim, fontSize = 11.sp,
                                                modifier = Modifier.clickable {
                                                    android.util.Log.i("ArtifactList", "Preferred name-swap not yet built (coming with FIT)")
                                                }.padding(end = 4.dp))
                                            Text(a["alias"] ?: "", color = aBlue, fontSize = 9.sp,
                                                fontFamily = aMono, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f))
                                            // source chip (literal value — "add" today)
                                            Text(a["source"] ?: "", color = aDim, fontSize = 7.sp,
                                                fontFamily = aMono, modifier = Modifier.padding(horizontal = 3.dp))
                                            // delete (min-one guard: only if >1)
                                            if (onDeleteAlias != null && aliasRows.size > 1) {
                                                Text("\u00d7", color = Color(0xFFFF6B6B), fontSize = 12.sp,
                                                    modifier = Modifier.clickable { onDeleteAlias!!(aId); reloadAliases() }
                                                        .padding(start = 2.dp))
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))

                                // FULL-DATA card (curated: skip geometry; truncate geom_hash)
                                Text("DETAILS", color = aOrange, fontSize = 9.sp,
                                    fontFamily = aMono, fontWeight = FontWeight.Bold)
                                if (detailFields.isEmpty()) {
                                    Text("no additional details", color = aDim, fontSize = 9.sp, fontFamily = aMono)
                                } else {
                                    val techKeys = setOf("min_lat", "max_lat", "min_lon", "max_lon", "created_at", "updated_at", "geom_hash")
                                    detailFields.forEach { (k, v) ->
                                        if (v.isNullOrBlank() || k in techKeys) return@forEach
                                        val show = if (k == "geom_hash" && v.length > 12) v.take(12) + "\u2026" else v
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text(k, color = aDim, fontSize = 8.sp, fontFamily = aMono,
                                                modifier = Modifier.width(96.dp))
                                            Text(show, color = Color(0xFFB8C4D4), fontSize = 8.sp,
                                                fontFamily = aMono, maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f))
                                        }
                                    }
                                    // technical — collapsed by default
                                    val hasTech = detailFields.any { (k, v) -> k in techKeys && !v.isNullOrBlank() }
                                    if (hasTech) {
                                        Text(
                                            (if (showTech) "\u25be technical" else "\u25b8 technical"),
                                            color = aOrange, fontSize = 8.sp, fontFamily = aMono,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable { showTech = !showTech }.padding(top = 2.dp)
                                        )
                                        if (showTech) {
                                            detailFields.forEach { (k, v) ->
                                                if (v.isNullOrBlank() || k !in techKeys) return@forEach
                                                val show = if (k == "geom_hash" && v.length > 12) v.take(12) + "\u2026" else v
                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                    Text(k, color = aDim, fontSize = 8.sp, fontFamily = aMono,
                                                        modifier = Modifier.width(96.dp))
                                                    Text(show, color = Color(0xFFB8C4D4), fontSize = 8.sp,
                                                        fontFamily = aMono, maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
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
