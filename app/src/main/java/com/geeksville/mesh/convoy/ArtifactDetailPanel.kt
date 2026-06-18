package com.geeksville.mesh.convoy

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ArtifactDetailPanel -- standalone, callable detail popup for ANY artifact.
 *
 * Self-loads its row via onLoadDetail (getArtifactDetail) -- does NOT depend on a
 * list collection. Owns FIT. Launched from: name search, select/edit row-tap,
 * and (future) map artifact popup. Dismisses on FIT or CLOSE; performs NO
 * list-state save -- it never touches convoy_panel.json except via FIT's own write.
 *
 * @param artifactType plural ("Tracks"/"Trails"/"Waypoints"/"Routes") -- drives gating + singular
 * @param id artifact id
 * @param name optional instant title before load resolves
 * @param onDismiss close the popup
 */
@Composable
fun ArtifactDetailPanel(
    artifactType: String,
    id: String,
    name: String? = null,
    mapKey: String = "convoy",
    fitWebView: android.webkit.WebView? = null,
    onLoadDetail: ((String, String) -> Map<String, String?>)? = null,
    onLoadAliases: ((String, String) -> List<Map<String, String?>>)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
    onExport: ((String) -> Unit)? = null,
    onChangeType: ((String, String) -> Unit)? = null,
    onDeleteAlias: ((String) -> Unit)? = null,
    onDismiss: (String?, String?) -> Unit
) {
    val aMono = FontFamily.Monospace
    val aGreen = Color(0xFF39FF14)
    val aBlue = Color(0xFF4DA6FF)
    val aOrange = Color(0xFFFF8C42)
    val aDim = Color(0xFF7A8DA0)
    val ctx = LocalContext.current

    val singular = artifactType.lowercase().removeSuffix("s")
    val detailFields = remember(id) { onLoadDetail?.invoke(singular, id) ?: emptyMap() }
    val dName = detailFields["name"] ?: name ?: "Unnamed"

    var showRenameDialog by remember(id) { mutableStateOf(false) }
    var renameText by remember(id) { mutableStateOf("") }
    var showDeleteConfirm by remember(id) { mutableStateOf(false) }
    var showTypeChooser by remember(id) { mutableStateOf(false) }
    var showTech by remember(id) { mutableStateOf(false) }
    var aliasRows by remember(id) {
        mutableStateOf(onLoadAliases?.invoke(singular, id) ?: emptyList())
    }
    fun reloadAliases() { aliasRows = onLoadAliases?.invoke(singular, id) ?: emptyList() }

    AlertDialog(
        onDismissRequest = { onDismiss(null, null) },
        title = {
            Column {
                Text(dName, color = Color.White, fontSize = 14.sp,
                    fontFamily = aMono, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Type: ${singular.uppercase()}", color = aDim, fontSize = 10.sp,
                    fontFamily = aMono)
            }
        },
        text = {
            Row(modifier = Modifier.fillMaxWidth()) {
                // -- LEFT RAIL: function list --
                Column(modifier = Modifier.width(118.dp)) {
                    if (artifactType != "Trails" && onRename != null) {
                        DetailActionButton("RENAME", aBlue) { renameText = dName; showRenameDialog = true }
                    }
                    if (artifactType != "Trails" && onDelete != null) {
                        DetailActionButton("DELETE", Color(0xFFFF6B6B)) { showDeleteConfirm = true }
                    }
                    if (onShare != null) { DetailActionButton("SHARE", aGreen) { onShare(id) } }
                    if (onExport != null) { DetailActionButton("EXPORT", aGreen) { onExport(id) } }
                    if (artifactType == "Waypoints" && onChangeType != null) {
                        DetailActionButton("CHANGE TYPE", aOrange) { showTypeChooser = true }
                    }
                    DetailActionButton("FIT", aBlue) {
                        ConvoyArtifactOps.fit(ctx, fitWebView, mapKey, singular, id)
                        onDismiss(artifactType, id)
                    }
                }

                Spacer(Modifier.width(10.dp))

                // -- RIGHT COLUMN: badge + aliases + full-data --
                Column(modifier = Modifier.weight(1f)) {
                    Text(singular.uppercase(), color = aDim, fontSize = 8.sp,
                        fontFamily = aMono, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))

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
                                Text(if (pref) "\u2605" else "\u2606",
                                    color = if (pref) aGreen else aDim, fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        android.util.Log.i("ArtifactDetail", "Preferred name-swap not yet built")
                                    }.padding(end = 4.dp))
                                Text(a["alias"] ?: "", color = aBlue, fontSize = 9.sp,
                                    fontFamily = aMono, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text(a["source"] ?: "", color = aDim, fontSize = 7.sp,
                                    fontFamily = aMono, modifier = Modifier.padding(horizontal = 3.dp))
                                if (onDeleteAlias != null && aliasRows.size > 1) {
                                    Text("\u00d7", color = Color(0xFFFF6B6B), fontSize = 12.sp,
                                        modifier = Modifier.clickable { onDeleteAlias(aId); reloadAliases() }
                                            .padding(start = 2.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    Text("DETAILS", color = aOrange, fontSize = 9.sp,
                        fontFamily = aMono, fontWeight = FontWeight.Bold)
                    if (detailFields.isEmpty()) {
                        Text("no additional details", color = aDim, fontSize = 9.sp, fontFamily = aMono)
                    } else {
                        val techKeys = setOf("min_lat", "max_lat", "min_lon", "max_lon", "created_at", "updated_at", "geom_hash")
                        detailFields.forEach { (k, v) ->
                            if (v.isNullOrBlank() || k in techKeys || k == "name") return@forEach
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
            TextButton(onClick = { onDismiss(null, null) }) { Text("CLOSE") }
        }
    )

    // -- Rename Dialog --
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename $artifactType") },
            text = {
                TextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename?.invoke(id, renameText); showRenameDialog = false; onDismiss(null, null)
                }) { Text("RENAME") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("CANCEL") }
            }
        )
    }

    // -- Delete Confirmation --
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete $dName?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete?.invoke(id); showDeleteConfirm = false; onDismiss(null, null)
                }) { Text("DELETE", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("CANCEL") }
            }
        )
    }

    // -- Type Chooser (Waypoints only) --
    if (showTypeChooser) {
        AlertDialog(
            onDismissRequest = { showTypeChooser = false },
            title = { Text("Change Waypoint Type") },
            text = {
                Column {
                    listOf("trailhead", "fuel", "gate", "hazard", "scenic",
                        "water", "camp", "parking", "rally", "other").forEach { wType ->
                        TextButton(onClick = {
                            onChangeType?.invoke(id, wType); showTypeChooser = false; onDismiss(null, null)
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
