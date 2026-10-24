package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.Gt   // STD1-2026-10-24
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
import androidx.compose.ui.input.pointer.pointerInput   // LISTSIZE-2026-10-22
import androidx.compose.foundation.gestures.detectDragGestures   // LISTDRAGFIX-2026-10-22

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
    // PROBESOUT-2026-09-03: stage 5 removed with the rest. The trail it was
    // hunting was two roads sharing a name; there was never a display bug.
    val aMono = FontFamily.Monospace
    val aGreen = Gt.accent
    val aBlue = Gt.accent
    val aOrange = Gt.warn
    val aDim = Gt.dim
    val aBg = Gt.panel
    val aItem = Gt.panel
    val ctx = LocalContext.current


    // LISTSIZE-2026-10-22 (Fred 12:44): smaller (300 dp, was the full column) and DRAGGABLE by ☰ MOVE; HIDE folds it to its header.
    var gtDrag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var gtFolded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(gtDrag.x.toInt(), gtDrag.y.toInt()) }
            .width(300.dp).padding(horizontal = 8.dp),
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
                Text("☰", color = aDim, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.pointerInputDrag { d -> gtDrag += d }.padding(end = 6.dp))
                Text(
                    "$artifactType (${artifacts.size})",
                    color = aBlue, fontSize = 13.sp, fontFamily = aMono,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Text(if (gtFolded) "SHOW" else "HIDE", color = aBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { gtFolded = !gtFolded }.padding(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {   // GTFILTER-2026-10-22: the standard HELP ✕ (was CLOSE); ✕ applies the ticks, as CLOSE did
                    com.geeksville.mesh.grouptrack.GtHelpButton("filter.select")
                    com.geeksville.mesh.grouptrack.GtCloseButton(onDismiss)
                }
            }

            if (!gtFolded) {   // LISTSIZE-2026-10-22: HIDE folds everything below the header
            Spacer(Modifier.height(6.dp))

            // ── Select All / Deselect All / Fit ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "SELECT ALL", color = aGreen, fontSize = 12.sp, fontFamily = aMono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onSelectAll() }.padding(4.dp)
                )
                Text(
                    "DESELECT ALL", color = aOrange, fontSize = 12.sp, fontFamily = aMono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDeselectAll() }.padding(4.dp)
                )
                if (onFitToSelected != null) {
                    Text(
                        "FIT", color = aBlue, fontSize = 12.sp, fontFamily = aMono,
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
                    color = aDim, fontSize = 12.sp, fontFamily = aMono,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp)   // LISTSIZE-2026-10-22: was 300
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
            }   // LISTSIZE-2026-10-22: end of the foldable part


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
        color = if (isSelected) Gt.text.copy(alpha = 0.18f) else Gt.text.copy(alpha = 0.18f)
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
                    checkedColor = Gt.accent,
                    uncheckedColor = Gt.text
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                color = Gt.accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { onNameClick() }
            )
            if (type.isNotEmpty() && artifactType == "Waypoints") {
                Text(
                    type, color = Gt.dim, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}




/** LISTSIZE-2026-10-22: drag by this element (the ☰ handle). */
private fun Modifier.pointerInputDrag(onDrag: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectDragGestures { change, d -> change.consume(); onDrag(d) }   // LISTDRAGFIX-2026-10-22
    }
